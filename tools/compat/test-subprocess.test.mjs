import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  realpathSync,
  rmSync,
  statSync,
  symlinkSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import {
  MAX_FAILURE_EVIDENCE_BYTES,
  MAX_FAILURE_RECORDS,
  runBoundedSubprocess,
} from './test-subprocess.mjs';

test('file-backed runner observes root exit without waiting for an owned inherited-stdout descendant', async () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-subprocess-fixture-'));
  chmodSync(fixture, 0o700);
  const marker = join(fixture, 'preserve-me.txt');
  const pidFile = join(fixture, 'descendant.pid');
  const unrelated = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
  const descendantLifetimeMs = process.platform === 'win32' ? 1_500 : 2_500;
  writeFileSync(marker, 'unrelated fixture data\n', { mode: 0o600 });
  const source = [
    'const { spawn } = require("node:child_process");',
    'const { writeFileSync } = require("node:fs");',
    `const descendant = spawn(process.execPath, ["-e", "setTimeout(() => {}, ${descendantLifetimeMs})"], { stdio: ["ignore", "inherit", "inherit"] });`,
    'descendant.unref();',
    'writeFileSync(process.env.WS_FIXTURE_PID_FILE, String(descendant.pid));',
    'process.stdout.write("fixture-root-output\\n");',
    'process.exit(0);',
  ].join('\n');
  const env = { ...process.env, WS_FIXTURE_PID_FILE: pidFile };

  try {
    const legacy = spawnSync(process.execPath, ['-e', source], {
      env,
      encoding: 'utf8',
      timeout: 1_000,
      maxBuffer: 1024 * 1024,
      detached: true,
    });
    const legacyDescendantPid = Number(readFileSync(pidFile, 'utf8'));
    assert.equal(legacy.status, 0, 'the root process itself exits successfully');
    assert.equal(legacy.error?.code, 'ETIMEDOUT', 'spawnSync waits for the descendant to close inherited stdout');
    assert.match(legacy.stdout, /fixture-root-output/);
    await terminateFixtureProcess(legacyDescendantPid);
    unlinkSync(pidFile);

    const startedAt = Date.now();
    const result = await runBoundedSubprocess(process.execPath, ['-e', source], {
      env,
      timeoutMs: 2_000,
      maxBuffer: 1024 * 1024,
      temporaryDirectory: fixture,
      cleanupOwnedProcessGroupOnExit: process.platform !== 'win32',
    });
    const descendantPid = Number(readFileSync(pidFile, 'utf8'));

    assert.equal(result.error, null);
    assert.equal(result.status, 0);
    assert.equal(result.signal, null);
    assert.equal(result.stdout, 'fixture-root-output\n');
    assert.equal(result.stderr, '');
    assert.ok(Date.now() - startedAt < 2_000, 'runner must finish before the bounded descendant timer');
    assert.deepEqual(result.logPermissions, { directory: 0o700, stdout: 0o600, stderr: 0o600 });
    assert.equal(result.processCleanup.completed, true);
    assert.equal(result.outputStats.stdoutBytes, Buffer.byteLength('fixture-root-output\n'));
    assert.ok(result.outputStats.rootExitDrainMs <= result.outputStats.rootExitDrainMaxMs);
    await assertProcessGone(descendantPid, 2_000);
    assert.equal(processExists(unrelated.pid), true, 'group cleanup must not signal an unrelated process');
    unlinkSync(pidFile);
    assert.deepEqual(readdirSync(fixture).sort(), ['preserve-me.txt']);
    assert.equal(readFileSync(marker, 'utf8'), 'unrelated fixture data\n');
  } finally {
    await terminateFixtureProcess(unrelated.pid);
    if (existsSync(pidFile)) {
      const leftoverPid = readFileSync(pidFile, 'utf8').trim();
      if (/^\d+$/.test(leftoverPid)) await terminateFixtureProcess(Number(leftoverPid));
    }
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('overflow retains private byte-capped child diagnostics and exposes no failure output', async () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-subprocess-overflow-output-'));
  chmodSync(fixture, 0o700);
  const failureRoot = join(fixture, 'evidence');
  mkdirSync(failureRoot, { mode: 0o700 });
  const script = 'process.stdout.write(Buffer.alloc(4096, 0x61)); process.stderr.write(Buffer.alloc(4096, 0x62));';

  try {
    const result = await runBoundedSubprocess(process.execPath, ['-e', script], {
      timeoutMs: 2_000,
      maxBuffer: 1024,
      failureRoot,
      testLabel: 'overflow-capture-test',
    });

    assert.equal(result.status, null, 'overflow must not be reported as a successful status=0 result');
    assert.equal(result.error?.code, 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER');
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, '');
    assert.deepEqual(result.outputStats, {
      stdoutBytes: 1024,
      stderrBytes: 1024,
      totalBytes: 2048,
      maxBufferPerStream: 1024,
      maxChunkBytes: { stdout: 4096, stderr: 4096 },
      truncated: { stdout: true, stderr: true },
      rootExitDrainMs: result.outputStats.rootExitDrainMs,
      rootExitDrainMaxMs: 500,
    });
    assert.ok(result.outputStats.rootExitDrainMs <= 500);
    const evidence = inspectEvidence(result, failureRoot);
    assert.equal(evidence.stdout.length, 1024);
    assert.equal(evidence.stderr.length, 1024);
    assert.equal(evidence.manifest.errorCode, 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER');
    assert.deepEqual(evidence.manifest.truncated, { stdout: true, stderr: true });
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('runner drains all root-produced bytes available at root exit before a bounded quiet close', async () => {
  const expected = `${'x'.repeat(128 * 1024)}BUILD SUCCESSFUL\n`;
  const script = [
    'const { writeSync } = require("node:fs");',
    `writeSync(1, Buffer.from(${JSON.stringify(expected)}));`,
    'process.exit(0);',
  ].join('\n');
  const result = await runBoundedSubprocess(process.execPath, ['-e', script], {
    timeoutMs: 2_000,
    maxBuffer: 256 * 1024,
  });

  assert.equal(result.error, null);
  assert.equal(result.status, 0);
  assert.equal(result.stdout, expected);
  assert.match(result.stdout, /BUILD SUCCESSFUL\n$/);
  assert.equal(result.outputStats.stdoutBytes, Buffer.byteLength(expected));
});

test('overflow stops its owned process group and preserves unrelated processes and files', async () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-subprocess-overflow-'));
  chmodSync(fixture, 0o700);
  const marker = join(fixture, 'preserve-me.txt');
  const pidFile = join(fixture, 'descendant.pid');
  const failureRoot = join(fixture, 'evidence');
  mkdirSync(failureRoot, { mode: 0o700 });
  const unrelated = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
  writeFileSync(marker, 'unrelated fixture data\n', { mode: 0o600 });
  const source = [
    'const { spawn } = require("node:child_process");',
    'const { writeFileSync, writeSync } = require("node:fs");',
    'const descendant = spawn(process.execPath, ["-e", "setInterval(() => {}, 1000)"], { stdio: ["ignore", "inherit", "inherit"] });',
    'descendant.unref();',
    'writeFileSync(process.env.WS_FIXTURE_PID_FILE, String(descendant.pid));',
    'const chunk = Buffer.alloc(16 * 1024, 0x61);',
    'setInterval(() => writeSync(1, chunk), 1);',
  ].join('\n');

  try {
    const result = await runBoundedSubprocess(process.execPath, ['-e', source], {
      env: { ...process.env, WS_FIXTURE_PID_FILE: pidFile },
      timeoutMs: 2_000,
      maxBuffer: 1024,
      temporaryDirectory: fixture,
      cleanupOwnedProcessGroupOnExit: true,
      failureRoot,
      testLabel: 'overflow-owned-group-test',
    });
    const descendantPid = Number(readFileSync(pidFile, 'utf8'));

    assert.equal(result.error?.code, 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER');
    assert.equal(result.status, null);
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, '');
    assert.equal(result.outputStats.stdoutBytes, 1024);
    assert.ok(result.outputStats.totalBytes <= 2 * result.outputStats.maxBufferPerStream);
    assert.equal(result.processCleanup.completed, true);
    await assertProcessGone(descendantPid, 1_000);
    assert.equal(processExists(unrelated.pid), true, 'owned-group cleanup must leave a separate process alive');
    const evidence = inspectEvidence(result, failureRoot);
    assert.equal(evidence.stdout.length, 1024);
    assert.equal(evidence.manifest.testLabel, 'overflow-owned-group-test');
    assert.equal(evidence.manifest.rootExit, null);
    assert.equal(evidence.manifest.errorCode, 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER');
    unlinkSync(pidFile);
    assert.deepEqual(readdirSync(fixture).sort(), ['evidence', 'preserve-me.txt']);
    assert.equal(readFileSync(marker, 'utf8'), 'unrelated fixture data\n');
  } finally {
    await terminateFixtureProcess(unrelated.pid);
    if (existsSync(pidFile)) {
      const leftoverPid = readFileSync(pidFile, 'utf8').trim();
      if (/^\d+$/.test(leftoverPid)) await terminateFixtureProcess(Number(leftoverPid));
    }
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('timeout force-kills an owned process group within the bounded grace period', async () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-subprocess-timeout-'));
  chmodSync(fixture, 0o700);
  const pidFile = join(fixture, 'descendant.pid');
  const failureRoot = join(fixture, 'evidence');
  mkdirSync(failureRoot, { mode: 0o700 });
  const source = [
    'const { spawn } = require("node:child_process");',
    'const { writeFileSync } = require("node:fs");',
    'const descendant = spawn(process.execPath, ["-e", "setInterval(() => {}, 1000)"], { stdio: ["ignore", "inherit", "inherit"] });',
    'descendant.unref();',
    'writeFileSync(process.env.WS_FIXTURE_PID_FILE, String(descendant.pid));',
    'process.on("SIGTERM", () => {});',
    'setInterval(() => {}, 1000);',
  ].join('\n');

  try {
    const startedAt = Date.now();
    const result = await runBoundedSubprocess(process.execPath, ['-e', source], {
      env: { ...process.env, WS_FIXTURE_PID_FILE: pidFile },
      timeoutMs: 100,
      maxBuffer: 1024,
      temporaryDirectory: fixture,
      cleanupOwnedProcessGroupOnExit: true,
      failureRoot,
      testLabel: 'timeout-owned-group-test',
    });
    const elapsedMs = Date.now() - startedAt;
    const descendantPid = Number(readFileSync(pidFile, 'utf8'));

    assert.equal(result.error?.code, 'ETIMEDOUT');
    assert.equal(result.signal, 'SIGKILL');
    assert.ok(elapsedMs <= 1_500, `timeout cleanup took ${elapsedMs}ms`);
    assert.equal(result.processCleanup.completed, true);
    await assertProcessGone(descendantPid, 1_000);
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, '');
    const evidence = inspectEvidence(result, failureRoot);
    assert.equal(evidence.manifest.rootExit, null, 'root exit code remains unknown after timeout termination');
    assert.equal(evidence.manifest.signal, 'SIGKILL');
    assert.equal(evidence.manifest.errorCode, 'ETIMEDOUT');
    assert.deepEqual(evidence.manifest.truncated, { stdout: false, stderr: false });
  } finally {
    if (existsSync(pidFile)) {
      const leftoverPid = readFileSync(pidFile, 'utf8').trim();
      if (/^\d+$/.test(leftoverPid)) await terminateFixtureProcess(Number(leftoverPid));
    }
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('spawn errors retain fixed ENOENT metadata and a private empty diagnostic record', async () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-subprocess-spawn-error-'));
  chmodSync(fixture, 0o700);
  const failureRoot = join(fixture, 'evidence');
  mkdirSync(failureRoot, { mode: 0o700 });
  try {
    const result = await runBoundedSubprocess(join(tmpdir(), `worldspeaker-missing-${process.pid}`), [], {
      timeoutMs: 1_000,
      maxBuffer: 1024,
      failureRoot,
      testLabel: 'spawn-error-test',
    });

    assert.equal(result.error?.code, 'ENOENT');
    assert.equal(result.status, null);
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, '');
    assert.equal(result.outputStats.totalBytes, 0);
    const evidence = inspectEvidence(result, failureRoot);
    assert.equal(evidence.manifest.rootExit, null);
    assert.equal(evidence.manifest.signal, null);
    assert.equal(evidence.manifest.errorCode, 'ENOENT');
    assert.equal(evidence.stdout, '');
    assert.equal(evidence.stderr, '');
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('nonzero child exit retains private logs and returns no child bytes to assertions', async () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-subprocess-nonzero-'));
  chmodSync(fixture, 0o700);
  const failureRoot = join(fixture, 'evidence');
  mkdirSync(failureRoot, { mode: 0o700 });
  const secretSentinel = 'NOSECRET_SENTINEL_4e58dd9b1c';
  const script = `process.stdout.write('safe-stdout-marker\\n'); process.stderr.write(${JSON.stringify(secretSentinel)}); process.exit(17);`;

  try {
    const result = await runBoundedSubprocess(process.execPath, ['-e', script], {
      timeoutMs: 2_000,
      maxBuffer: 1024,
      failureRoot,
      testLabel: 'nonzero-private-test',
    });
    assert.equal(result.error, null);
    assert.equal(result.status, 17);
    assert.equal(result.signal, null);
    assert.equal(result.diagnosticCode, 'CHILD_EXIT_NONZERO');
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, '');
    assert.ok(!JSON.stringify(result).includes(secretSentinel), 'child bytes must not escape through the result object');
    const evidence = inspectEvidence(result, failureRoot);
    assert.ok(evidence.stdout.includes('safe-stdout-marker'));
    assert.ok(evidence.stderr === secretSentinel);
    assert.ok(!readFileSync(join(result.evidencePath, 'manifest.json'), 'utf8').includes(secretSentinel),
      'manifest must not copy child bytes');
    assert.equal(evidence.manifest.rootExit, 17);
    assert.equal(evidence.manifest.signal, null);
    assert.equal(evidence.manifest.errorCode, 'CHILD_EXIT_NONZERO');
    assert.equal(evidence.manifest.testLabel, 'nonzero-private-test');
    assert.equal(createHash('sha256').update(evidence.stderr).digest('hex'),
      createHash('sha256').update(secretSentinel).digest('hex'));
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('root signal termination is retained as a signal diagnostic without returning child bytes', async () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-subprocess-signal-'));
  chmodSync(fixture, 0o700);
  const failureRoot = join(fixture, 'evidence');
  mkdirSync(failureRoot, { mode: 0o700 });
  try {
    const result = await runBoundedSubprocess(process.execPath, ['-e', 'process.stderr.write("signal-marker"); process.kill(process.pid, "SIGTERM");'], {
      timeoutMs: 1_000,
      maxBuffer: 1024,
      failureRoot,
      testLabel: 'signal-private-test',
    });

    assert.equal(result.error, null);
    assert.equal(result.status, null);
    assert.equal(result.signal, 'SIGTERM');
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, '');
    const evidence = inspectEvidence(result, failureRoot);
    assert.equal(evidence.stderr, 'signal-marker');
    assert.equal(evidence.manifest.rootExit, null);
    assert.equal(evidence.manifest.signal, 'SIGTERM');
    assert.equal(evidence.manifest.errorCode, 'CHILD_SIGNAL');
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('failure-root symlinks are refused without following or changing their targets', async () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-subprocess-linked-root-'));
  chmodSync(fixture, 0o700);
  const target = join(fixture, 'target');
  const linkedRoot = join(fixture, 'linked-evidence');
  mkdirSync(target, { mode: 0o700 });
  const marker = join(target, 'keep.txt');
  writeFileSync(marker, 'pre-existing evidence\n', { mode: 0o600 });
  symlinkSync(target, linkedRoot, 'dir');
  try {
    const result = await runBoundedSubprocess(process.execPath, ['-e', 'process.exit(6);'], {
      timeoutMs: 1_000,
      maxBuffer: 1024,
      failureRoot: linkedRoot,
      testLabel: 'linked-root-refusal',
    });

    assert.equal(result.status, 6);
    assert.equal(result.evidencePath, null);
    assert.equal(result.evidenceCode, 'FAILURE_ROOT_REJECTED');
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, '');
    assert.deepEqual(readdirSync(target), ['keep.txt']);
    assert.equal(readFileSync(marker, 'utf8'), 'pre-existing evidence\n');
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('failure retention refuses the ninth record and preserves prior user files without broad deletion', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-subprocess-capacity-'));
  chmodSync(fixture, 0o700);
  const failureRoot = join(fixture, 'evidence');
  mkdirSync(failureRoot, { mode: 0o700 });
  const olderEvidence = join(failureRoot, 'older-user-evidence');
  mkdirSync(olderEvidence, { mode: 0o700 });
  const marker = join(olderEvidence, 'keep.txt');
  writeFileSync(marker, 'do not delete or overwrite\n', { mode: 0o600 });

  try {
    const helperUrl = new URL('./test-subprocess.mjs', import.meta.url).href;
    const source = `
      import { MAX_FAILURE_RECORDS, recordFailureEvidence, runBoundedSubprocess } from ${JSON.stringify(helperUrl)};
      const results = [];
      for (let index = 0; index <= MAX_FAILURE_RECORDS; index += 1) {
        const result = await runBoundedSubprocess(process.execPath, ['-e', 'process.exit(7);'], {
          timeoutMs: 2000,
          maxBuffer: 1024,
          failureRoot: process.env.WS_FAILURE_ROOT,
          testLabel: 'capacity-probe',
        });
        recordFailureEvidence(result);
        results.push({ code: result.evidenceCode, path: result.evidencePath, status: result.status });
      }
      process.stdout.write(JSON.stringify(results));
    `;
    const child = spawnSync(process.execPath, ['--input-type=module', '-e', source], {
      encoding: 'utf8',
      timeout: 30_000,
      maxBuffer: 1024 * 1024,
      env: { ...process.env, WS_FAILURE_ROOT: failureRoot },
    });

    assert.ifError(child.error);
    assert.equal(child.status, 0, 'isolated capacity probe must finish all child runs');
    const results = JSON.parse(child.stdout);
    assert.equal(results.length, MAX_FAILURE_RECORDS + 1);
    assert.ok(results.slice(0, MAX_FAILURE_RECORDS).every(({ code, path, status }) => code === 'RETAINED' && path && status === 7));
    assert.equal(results[MAX_FAILURE_RECORDS].code, 'CAPACITY_REFUSED');
    assert.equal(results[MAX_FAILURE_RECORDS].path, null);
    assert.equal(results[MAX_FAILURE_RECORDS].status, 7, 'capacity refusal happens after the child has exited');

    const recordNames = readdirSync(failureRoot).filter((name) => name.startsWith('failure-'));
    assert.equal(recordNames.length, MAX_FAILURE_RECORDS);
    assert.deepEqual(readdirSync(failureRoot).sort(), [...recordNames, 'older-user-evidence'].sort());
    assert.equal(readFileSync(marker, 'utf8'), 'do not delete or overwrite\n');
    const aggregateBytes = recordNames.reduce((total, name) => total
      + readdirSync(join(failureRoot, name)).reduce((directoryTotal, file) =>
        directoryTotal + statSync(join(failureRoot, name, file)).size, 0), 0);
    assert.ok(aggregateBytes <= MAX_FAILURE_EVIDENCE_BYTES);
    assert.ok(!child.stderr.includes('process.exit(7)'), 'outer test output contains no child source or raw exception text');
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

function inspectEvidence(result, expectedRoot) {
  assert.equal(result.evidenceCode, 'RETAINED');
  assert.ok(typeof result.evidencePath === 'string');
  assert.equal(realpathSync(dirname(result.evidencePath)), realpathSync(expectedRoot));
  assert.equal(statSync(result.evidencePath).mode & 0o777, 0o700);
  assert.deepEqual(readdirSync(result.evidencePath).sort(), ['manifest.json', 'stderr.log', 'stdout.log']);

  const manifestPath = join(result.evidencePath, 'manifest.json');
  const stdoutPath = join(result.evidencePath, 'stdout.log');
  const stderrPath = join(result.evidencePath, 'stderr.log');
  assert.equal(statSync(manifestPath).mode & 0o777, 0o600);
  assert.equal(statSync(stdoutPath).mode & 0o777, 0o600);
  assert.equal(statSync(stderrPath).mode & 0o777, 0o600);
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const stdout = readFileSync(stdoutPath, 'utf8');
  const stderr = readFileSync(stderrPath, 'utf8');
  assert.deepEqual(Object.keys(manifest).sort(), [
    'elapsedMs', 'errorCode', 'maxBytes', 'rootExit', 'signal', 'testLabel', 'truncated', 'version',
  ]);
  assert.ok(Number.isSafeInteger(manifest.elapsedMs) && manifest.elapsedMs >= 0);
  assert.ok(Buffer.byteLength(stdout) <= manifest.maxBytes.perStream);
  assert.ok(Buffer.byteLength(stderr) <= manifest.maxBytes.perStream);
  assert.ok(manifest.maxBytes.childTotal <= MAX_FAILURE_EVIDENCE_BYTES);
  assert.equal(manifest.maxBytes.processAggregate, MAX_FAILURE_EVIDENCE_BYTES);
  return { manifest, stdout, stderr };
}

async function terminateFixtureProcess(pid) {
  try {
    process.kill(pid, 'SIGTERM');
  } catch (error) {
    if (error.code === 'ESRCH') return;
    throw error;
  }
  await assertProcessGone(pid);
}

async function assertProcessGone(pid, timeoutMs = 1_000) {
  const deadline = Date.now() + timeoutMs;
  while (processExists(pid) && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  assert.equal(processExists(pid), false, `owned fixture process ${pid} must be gone`);
}

function processExists(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error.code !== 'ESRCH';
  }
}
