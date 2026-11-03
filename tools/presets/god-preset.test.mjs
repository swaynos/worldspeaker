import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  cpSync, existsSync, linkSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync,
  realpathSync, renameSync, rmSync, statSync, symlinkSync, unlinkSync, writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import {
  buildGodPresetArchive,
  encodeGodPresetArchiveEntries,
  expectedGodPresetArchivePaths,
  readGodPresetArchive,
  verifyGodPresetArchiveBytes,
} from './god-preset-archive.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const buildPath = join(root, 'build/presets/worldspeaker-god-preset-v1.zip');
const companionPath = join(root, 'companion/presets/worldspeaker-god-preset-v1.zip');
const sourcePaths = [
  'presets/god/README.md',
  'presets/god/persona.txt',
  'presets/god/ability-policy.json',
  'presets/god/visual-only-policy.example.json.template',
  'presets/god/startup-config.json.template',
  'presets/god/startup-config.no-guidance-control.json.template',
  'compatibility/startup-config-v1.schema.json',
  'LICENSE',
];

test('actual God preset ZIP is bounded, exact, reproducible, current, and a public data archive', () => {
  assert.equal(existsSync(buildPath), true, 'fixed build archive exists');
  assert.equal(existsSync(companionPath), true, 'companion sidecar exists');
  const actual = readFileSync(buildPath);
  const sidecar = readFileSync(companionPath);
  assert.deepEqual(sidecar, actual, 'companion sidecar is the exact same ZIP bytes');
  assert.deepEqual(actual, buildGodPresetArchive(), 'rebuilding from the explicit current allowlist is byte-reproducible');
  const result = verifyGodPresetArchiveBytes(actual);
  assert.ok(result.bytes > 0 && result.bytes <= 128 * 1024);
  assert.ok(result.expandedBytes > 0 && result.expandedBytes <= 128 * 1024);
  assert.deepEqual(result.entries, expectedGodPresetArchivePaths());
  assert.deepEqual([...readGodPresetArchive(actual).keys()].sort(), expectedGodPresetArchivePaths());

  const entries = readGodPresetArchive(actual);
  const manifest = JSON.parse(entries.get('manifest.json').toString('utf8'));
  const deploymentBytes = readFileSync(join(root, 'compatibility/deployment.json'));
  const deployment = JSON.parse(deploymentBytes.toString('utf8'));
  assert.deepEqual(Object.keys(manifest).sort(), [
    'schemaVersion', 'presetId', 'presetVersion', 'startupSchemaVersion', 'deploymentId',
    'deploymentManifestSha256', 'entries',
  ].sort());
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.presetId, 'worldspeaker.god');
  assert.equal(manifest.presetVersion, 1);
  assert.equal(manifest.startupSchemaVersion, 1);
  assert.equal(manifest.deploymentId, deployment.deploymentId);
  assert.equal(manifest.deploymentManifestSha256, createHash('sha256').update(deploymentBytes).digest('hex'));
  assert.deepEqual(manifest.entries.map(({ path }) => path), expectedGodPresetArchivePaths().filter((path) => path !== 'manifest.json'));
  for (const row of manifest.entries) {
    const bytes = entries.get(row.path);
    assert.equal(row.bytes, bytes.length, `${row.path} byte count`);
    assert.equal(row.sha256, createHash('sha256').update(bytes).digest('hex'), `${row.path} content hash`);
  }
  assert.deepEqual(entries.get('startup-config-v1.schema.json'), readFileSync(join(root, 'compatibility/startup-config-v1.schema.json')),
    'the archive embeds the authoritative schema bytes, not a duplicate schema');
  assert.deepEqual(entries.get('LICENSE'), readFileSync(join(root, 'LICENSE')), 'the root GPL license is preserved exactly');

  const personaBytes = entries.get('persona.txt');
  const persona = new TextDecoder('utf-8', { fatal: true }).decode(personaBytes);
  assert.ok(personaBytes.length > 0 && personaBytes.length <= 8192);
  assert.equal(personaBytes[0] === 0xef && personaBytes[1] === 0xbb && personaBytes[2] === 0xbf, false);
  assert.doesNotMatch(persona, /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/u);
  assert.match(persona, /not omniscient/i);
  assert.match(persona, /ask rather than guess/i);
  assert.match(persona, /optional story ideas/i);
  assert.match(persona, /not a game operator/i);

  const archivedReadme = entries.get('README.md').toString('utf8');
  const internalLinks = [...archivedReadme.matchAll(/\[[^\]]+\]\(([^)]+)\)/gu)].map((match) => match[1]);
  for (const link of internalLinks) assert.equal(entries.has(link), true, `archive README link resolves to ${link}`);
  assert.match(archivedReadme, /data ZIP/);
  assert.match(archivedReadme, /NOT AN ACTIVE GOD PERSONA/);
  assert.match(archivedReadme, /24\.22\.1/);

  const policy = JSON.parse(entries.get('ability-policy.json').toString('utf8'));
  assert.deepEqual(policy, {
    schemaVersion: 1, revision: 0, grants: [], denials: [], protection: { externalClaimsMode: 'REQUIRED' },
  });
  assert.doesNotMatch(entries.get('ability-policy.json').toString('utf8'), /\/\/|\/\*/u);
  const visual = JSON.parse(entries.get('visual-only-policy.example.json.template').toString('utf8'));
  assert.deepEqual(Object.keys(visual.grants[0]).sort(), [
    'id', 'characterId', 'abilityId', 'contractVersion', 'requesterIds', 'dimensions', 'maxRangeBlocks',
    'allowVisual', 'allowDamage',
  ].sort());
  assert.equal(visual.grants[0].id, '<GRANT_ID>');
  assert.equal(visual.grants[0].characterId, '<CHARACTER_ID>');
  assert.deepEqual(visual.grants[0].requesterIds, ['<REQUESTER_UUID>']);
  assert.equal(visual.grants[0].allowVisual, true);
  assert.equal(visual.grants[0].allowDamage, false);
  assert.equal(Object.hasOwn(visual.grants[0], 'maxDamage'), false);
  assert.equal(Object.hasOwn(visual.grants[0], 'otherTargetIds'), false);
});

test('archive check mode is read-only and rejects a stale source or either stale sidecar', () => {
  const beforeBuild = statSync(buildPath).mtimeMs;
  const beforeCompanion = statSync(companionPath).mtimeMs;
  const check = spawnSync(process.execPath, [join(root, 'tools/presets/build-god-preset.mjs'), '--check'], {
    cwd: root, encoding: 'utf8', timeout: 15_000,
  });
  assert.ifError(check.error);
  assert.equal(check.status, 0, check.stderr);
  assert.match(check.stdout, /WS-GOD-PRESET-CURRENT:/);
  assert.equal(statSync(buildPath).mtimeMs, beforeBuild);
  assert.equal(statSync(companionPath).mtimeMs, beforeCompanion);

  const original = readFileSync(buildPath);
  for (const archivePath of [buildPath, companionPath]) {
    const saved = readFileSync(archivePath);
    try {
      writeFileSync(archivePath, Buffer.concat([saved, Buffer.from('stale')]));
      const stale = spawnSync(process.execPath, [join(root, 'tools/presets/build-god-preset.mjs'), '--check'], {
        cwd: root, encoding: 'utf8', timeout: 15_000,
      });
      assert.notEqual(stale.status, 0);
      assert.match(stale.stderr, /WS-GOD-PRESET-BUILD-FAILED: preset-archive-eocd-invalid/);
    } finally {
      writeFileSync(archivePath, saved);
    }
  }
  assert.deepEqual(readFileSync(buildPath), original);
});

test('each allowlisted source, current schema, and license invalidates a previously built archive', () => {
  const fixture = mkdtempSync(join(realpathSync(tmpdir()), 'worldspeaker-god-preset-freshness-'));
  try {
    for (const relativePath of [...sourcePaths, 'compatibility/deployment.json']) {
      const target = join(fixture, relativePath);
      mkdirSync(dirname(target), { recursive: true });
      cpSync(join(root, relativePath), target);
    }
    const pristine = buildGodPresetArchive({ root: fixture });
    for (const relativePath of sourcePaths) {
      const target = join(fixture, relativePath);
      const original = readFileSync(target);
      writeFileSync(target, Buffer.concat([original, Buffer.from('\n')]));
      assert.throws(() => verifyGodPresetArchiveBytes(pristine, { root: fixture }),
        (error) => error.code === 'preset-archive-stale' || /^preset-/.test(error.code), relativePath);
      writeFileSync(target, original);
    }
    const deploymentPath = join(fixture, 'compatibility/deployment.json');
    const deployment = readFileSync(deploymentPath);
    writeFileSync(deploymentPath, Buffer.concat([deployment, Buffer.from(' ')]));
    assert.throws(() => verifyGodPresetArchiveBytes(pristine, { root: fixture }),
      (error) => error.code === 'preset-archive-stale', 'deployment manifest freshness is also pinned');
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('fixed outputs are created privately, rebuilt deterministically, and refreshed from current sources', () => {
  const fixture = createFilesystemFixture();
  try {
    const first = buildGodPresetArchive({ root: fixture.root });
    const initialWrite = runFixtureBuilder(fixture.root, '--write');
    assert.equal(initialWrite.status, 0, initialWrite.stderr);
    const outputPaths = fixedFixtureOutputPaths(fixture.root);
    for (const path of outputPaths) {
      const info = lstatSync(path);
      assert.equal(info.isFile(), true);
      assert.equal(info.nlink, 1);
      assert.equal(info.uid, process.geteuid());
      assert.equal(info.mode & 0o7777, 0o644);
      assert.deepEqual(readFileSync(path), first);
    }
    assert.deepEqual(readFileSync(outputPaths[0]), readFileSync(outputPaths[1]));

    const firstHash = createHash('sha256').update(first).digest('hex');
    const deterministicRebuild = buildGodPresetArchive({ root: fixture.root });
    assert.equal(createHash('sha256').update(deterministicRebuild).digest('hex'), firstHash);
    const rebuild = runFixtureBuilder(fixture.root, '--write');
    assert.equal(rebuild.status, 0, rebuild.stderr);
    for (const path of outputPaths) assert.deepEqual(readFileSync(path), deterministicRebuild);
    const check = runFixtureBuilder(fixture.root, '--check');
    assert.equal(check.status, 0, check.stderr);

    const readmePath = join(fixture.root, 'presets/god/README.md');
    writeFileSync(readmePath, Buffer.concat([readFileSync(readmePath), Buffer.from('\nFreshness fixture.\n')]));
    const fresh = buildGodPresetArchive({ root: fixture.root });
    assert.notDeepEqual(fresh, first, 'allowlisted source freshness changes archive bytes');
    const refreshed = runFixtureBuilder(fixture.root, '--write');
    assert.equal(refreshed.status, 0, refreshed.stderr);
    assert.deepEqual(readFileSync(outputPaths[0]), fresh);
    assert.deepEqual(readFileSync(outputPaths[1]), fresh);
    assert.equal(createHash('sha256').update(readFileSync(outputPaths[0])).digest('hex'),
      createHash('sha256').update(readFileSync(outputPaths[1])).digest('hex'));
    for (const path of outputPaths) assert.equal(lstatSync(path).mode & 0o7777, 0o644);
  } finally {
    rmSync(fixture.parent, { recursive: true, force: true });
  }
});

for (const dangerousSecond of ['file symlink', 'parent symlink', 'hard link', 'unrelated regular file', 'directory target']) {
  test(`second fixed output ${dangerousSecond} is rejected before the first output or outside canary changes`, () => {
    const fixture = createFilesystemFixture();
    try {
      const oldBytes = buildGodPresetArchive({ root: fixture.root });
      const initialWrite = runFixtureBuilder(fixture.root, '--write');
      assert.equal(initialWrite.status, 0, initialWrite.stderr);
      const [firstPath, secondPath] = fixedFixtureOutputPaths(fixture.root);
      const firstBefore = lstatSync(firstPath);
      const firstBytesBefore = readFileSync(firstPath);

      const sourceReadme = join(fixture.root, 'presets/god/README.md');
      writeFileSync(sourceReadme, Buffer.concat([readFileSync(sourceReadme), Buffer.from('\nChanged source.\n')]));
      const newBytes = buildGodPresetArchive({ root: fixture.root });
      assert.notDeepEqual(newBytes, oldBytes);

      const canaryBytes = Buffer.from('outside-canary-must-remain-byte-identical\n');
      const canaryPath = join(fixture.parent, 'outside-canary.bin');
      writeFileSync(canaryPath, canaryBytes, { mode: 0o600 });

      if (dangerousSecond === 'file symlink') {
        unlinkSync(secondPath);
        symlinkSync(canaryPath, secondPath);
      } else if (dangerousSecond === 'parent symlink') {
        const secondParent = dirname(secondPath);
        const displacedParent = join(fixture.parent, 'original-companion-presets');
        renameSync(secondParent, displacedParent);
        const outsideDirectory = join(fixture.parent, 'outside-presets');
        mkdirSync(outsideDirectory, { mode: 0o700 });
        writeFileSync(join(outsideDirectory, 'worldspeaker-god-preset-v1.zip'), canaryBytes, { mode: 0o600 });
        symlinkSync(outsideDirectory, secondParent, 'dir');
      } else if (dangerousSecond === 'hard link') {
        unlinkSync(secondPath);
        linkSync(canaryPath, secondPath);
      } else if (dangerousSecond === 'unrelated regular file') {
        unlinkSync(secondPath);
        writeFileSync(secondPath, canaryBytes, { mode: 0o600 });
      } else {
        unlinkSync(secondPath);
        mkdirSync(secondPath, { mode: 0o700 });
      }

      const outsideTarget = dangerousSecond === 'parent symlink'
        ? join(fixture.parent, 'outside-presets/worldspeaker-god-preset-v1.zip')
        : canaryPath;
      const outsideBefore = readFileSync(outsideTarget);
      const rejected = runFixtureBuilder(fixture.root, '--write');
      assert.notEqual(rejected.status, 0, dangerousSecond);
      assert.match(rejected.stderr, /WS-GOD-PRESET-BUILD-FAILED: preset-output-/);

      assert.deepEqual(readFileSync(outsideTarget), outsideBefore, 'outside target bytes were not modified');
      assert.deepEqual(readFileSync(firstPath), firstBytesBefore, 'first safe output remains byte-identical');
      const firstAfter = lstatSync(firstPath);
      assert.equal(firstAfter.dev, firstBefore.dev);
      assert.equal(firstAfter.ino, firstBefore.ino);
      assert.equal(firstAfter.mtimeMs, firstBefore.mtimeMs);
      assert.equal(firstAfter.mode, firstBefore.mode);
      assert.equal(readdirSync(dirname(firstPath)).some((name) => name.includes('.tmp-')), false,
        'no owned staging temp remains after preflight rejection');
    } finally {
      rmSync(fixture.parent, { recursive: true, force: true });
    }
  });
}

test('allowlisted source symlinks and hard links are rejected without reading external canaries', () => {
  const fixture = createFilesystemFixture();
  try {
    const sourcePath = join(fixture.root, 'presets/god/persona.txt');
    const canaryPath = join(fixture.parent, 'private-canary.txt');
    const canaryBytes = Buffer.from('not an allowlisted preset source\n');
    writeFileSync(canaryPath, canaryBytes, { mode: 0o600 });

    unlinkSync(sourcePath);
    symlinkSync(canaryPath, sourcePath);
    assert.throws(() => buildGodPresetArchive({ root: fixture.root }),
      (error) => error.code === 'preset-source-not-regular');
    assert.deepEqual(readFileSync(canaryPath), canaryBytes);

    unlinkSync(sourcePath);
    writeFileSync(sourcePath, Buffer.from('placeholder\n'));
    unlinkSync(sourcePath);
    linkSync(canaryPath, sourcePath);
    assert.throws(() => buildGodPresetArchive({ root: fixture.root }),
      (error) => error.code === 'preset-source-not-regular');
    assert.deepEqual(readFileSync(canaryPath), canaryBytes);
  } finally {
    rmSync(fixture.parent, { recursive: true, force: true });
  }
});

function createFilesystemFixture() {
  const parent = mkdtempSync(join(realpathSync(tmpdir()), 'worldspeaker-god-preset-filesystem-'));
  const fixtureRoot = join(parent, 'repository');
  mkdirSync(fixtureRoot, { mode: 0o700 });
  for (const relativePath of [...sourcePaths, 'compatibility/deployment.json']) {
    const target = join(fixtureRoot, relativePath);
    mkdirSync(dirname(target), { recursive: true });
    cpSync(join(root, relativePath), target);
  }
  const toolDirectory = join(fixtureRoot, 'tools/presets');
  mkdirSync(toolDirectory, { recursive: true });
  for (const toolName of ['build-god-preset.mjs', 'god-preset-archive.mjs', 'god-preset-filesystem.mjs']) {
    cpSync(join(root, 'tools/presets', toolName), join(toolDirectory, toolName));
  }
  return { parent, root: fixtureRoot };
}

function fixedFixtureOutputPaths(fixtureRoot) {
  return [
    join(fixtureRoot, 'build/presets/worldspeaker-god-preset-v1.zip'),
    join(fixtureRoot, 'companion/presets/worldspeaker-god-preset-v1.zip'),
  ];
}

function runFixtureBuilder(fixtureRoot, mode) {
  return spawnSync(process.execPath, [join(fixtureRoot, 'tools/presets/build-god-preset.mjs'), mode], {
    cwd: fixtureRoot, encoding: 'utf8', timeout: 15_000,
  });
}

test('strict archive reader rejects truncation, extras, duplicate paths, traversals, links, encryption, and corruption', () => {
  const valid = readFileSync(buildPath);
  assert.throws(() => readGodPresetArchive(valid.subarray(0, valid.length - 1)));

  const entries = [...readGodPresetArchive(valid)];
  const withoutManifest = entries.filter(([path]) => path !== 'manifest.json');
  const extra = encodeGodPresetArchiveEntries([...withoutManifest, ['extra.txt', Buffer.from('unexpected')]]);
  assert.throws(() => readGodPresetArchive(extra), (error) => error.code === 'preset-archive-path-invalid');
  const readme = entries.find(([path]) => path === 'README.md');
  assert.ok(readme);
  const duplicate = encodeGodPresetArchiveEntries([...withoutManifest, readme]);
  assert.throws(() => readGodPresetArchive(duplicate), (error) => error.code === 'preset-archive-path-invalid');

  assert.throws(() => readGodPresetArchive(renameZipPath(valid, 'README.md', '../x.txt?')),
    (error) => error.code === 'preset-archive-path-invalid');
  assert.throws(() => readGodPresetArchive(mutateCentralU32(valid, 'README.md', 38, (value) => value ^ 0x80000000)),
    (error) => error.code === 'preset-archive-entry-metadata-invalid');
  assert.throws(() => readGodPresetArchive(mutateLocalAndCentralFlags(valid, 'README.md', 1)),
    (error) => error.code === 'preset-archive-entry-metadata-invalid');
  assert.throws(() => readGodPresetArchive(flipCompressedByte(valid, 'persona.txt')),
    (error) => /^preset-archive-/.test(error.code));
  assert.throws(() => verifyGodPresetArchiveBytes(mutateCentralU32(valid, 'README.md', 16, (value) => value ^ 1)),
    (error) => /^preset-archive-/.test(error.code));
  const oversizedEntries = expectedGodPresetArchivePaths().map((path, index) =>
    [path, Buffer.alloc(index === 0 ? 128 * 1024 + 1 : 1)]);
  assert.throws(() => encodeGodPresetArchiveEntries(oversizedEntries),
    (error) => error.code === 'preset-archive-expanded-size-invalid');
});

function renameZipPath(source, target, replacement) {
  assert.equal(target.length, replacement.length);
  const bytes = Buffer.from(source);
  const eocd = bytes.length - 22;
  const centralOffset = bytes.readUInt32LE(eocd + 16);
  let cursor = centralOffset;
  while (cursor < eocd) {
    const length = bytes.readUInt16LE(cursor + 28);
    const current = bytes.subarray(cursor + 46, cursor + 46 + length).toString('utf8');
    if (current === target) {
      const localOffset = bytes.readUInt32LE(cursor + 42);
      const localNameLength = bytes.readUInt16LE(localOffset + 26);
      assert.equal(bytes.subarray(localOffset + 30, localOffset + 30 + localNameLength).toString('utf8'), target);
      Buffer.from(replacement).copy(bytes, localOffset + 30);
      Buffer.from(replacement).copy(bytes, cursor + 46);
      return bytes;
    }
    cursor += 46 + length + bytes.readUInt16LE(cursor + 30) + bytes.readUInt16LE(cursor + 32);
  }
  throw new Error(`ZIP path not found: ${target}`);
}

function mutateCentralU32(source, target, fieldOffset, mutate) {
  const bytes = Buffer.from(source);
  const eocd = bytes.length - 22;
  let cursor = bytes.readUInt32LE(eocd + 16);
  while (cursor < eocd) {
    const nameLength = bytes.readUInt16LE(cursor + 28);
    const name = bytes.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8');
    if (name === target) {
      bytes.writeUInt32LE(mutate(bytes.readUInt32LE(cursor + fieldOffset)) >>> 0, cursor + fieldOffset);
      return bytes;
    }
    cursor += 46 + nameLength + bytes.readUInt16LE(cursor + 30) + bytes.readUInt16LE(cursor + 32);
  }
  throw new Error(`ZIP path not found: ${target}`);
}

function mutateLocalAndCentralFlags(source, target, flags) {
  const bytes = Buffer.from(source);
  const eocd = bytes.length - 22;
  let cursor = bytes.readUInt32LE(eocd + 16);
  while (cursor < eocd) {
    const nameLength = bytes.readUInt16LE(cursor + 28);
    const name = bytes.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8');
    if (name === target) {
      const localOffset = bytes.readUInt32LE(cursor + 42);
      const localNameLength = bytes.readUInt16LE(localOffset + 26);
      assert.equal(bytes.subarray(localOffset + 30, localOffset + 30 + localNameLength).toString('utf8'), target);
      bytes.writeUInt16LE(flags, localOffset + 6);
      bytes.writeUInt16LE(flags, cursor + 8);
      return bytes;
    }
    cursor += 46 + nameLength + bytes.readUInt16LE(cursor + 30) + bytes.readUInt16LE(cursor + 32);
  }
  throw new Error(`ZIP path not found: ${target}`);
}

function flipCompressedByte(source, target) {
  const bytes = Buffer.from(source);
  const localNameLength = bytes.readUInt16LE(26);
  const localExtraLength = bytes.readUInt16LE(28);
  assert.equal(bytes.subarray(30, 30 + localNameLength).toString('utf8'), 'LICENSE');
  // Select a non-first entry by reading its local offset from the central directory.
  const eocd = bytes.length - 22;
  let cursor = bytes.readUInt32LE(eocd + 16);
  while (cursor < eocd) {
    const nameLength = bytes.readUInt16LE(cursor + 28);
    const name = bytes.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8');
    if (name === target) {
      const localOffset = bytes.readUInt32LE(cursor + 42);
      const start = localOffset + 30 + bytes.readUInt16LE(localOffset + 26) + bytes.readUInt16LE(localOffset + 28);
      bytes[start] ^= 0x01;
      return bytes;
    }
    cursor += 46 + nameLength + bytes.readUInt16LE(cursor + 30) + bytes.readUInt16LE(cursor + 32);
  }
  throw new Error(`ZIP path not found: ${target}`);
}
