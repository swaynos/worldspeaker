import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { lstat, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  assertEulaAuthorization,
  downloadBytes,
  downloadMavenArtifact,
  parseArgs,
  readZipJson,
  runBoundedCommand,
  verifyCandidateDistribution,
} from './clean-install-core.mjs';
import { phaseForError } from './clean-install.mjs';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const script = join(root, 'tools/compat/clean-install.mjs');
const manifest = JSON.parse(await readFile(join(root, 'compatibility/deployment.json'), 'utf8'));
const companionPackage = JSON.parse(await readFile(join(root, 'companion/package.json'), 'utf8'));
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const sha1 = (bytes) => createHash('sha1').update(bytes).digest('hex');

test('EULA approval is required only for an explicit runtime start', () => {
  const stage = parseArgs(['--workdir', '/tmp/clean-install-stage']);
  assert.doesNotThrow(() => assertEulaAuthorization(stage));
  assert.throws(() => assertEulaAuthorization({ startServer: true, acceptEula: false }), /explicit-accept-eula/);
  assert.throws(() => assertEulaAuthorization({ startServer: false, acceptEula: true }), /accept-eula-requires-start-server/);
  assert.doesNotThrow(() => assertEulaAuthorization({ startServer: true, acceptEula: true }));
});

test('CLI refuses missing EULA approval before creating a work directory or starting anything', async () => {
  const parent = await mkdtemp(join(tmpdir(), 'worldspeaker-clean-refusal-'));
  const workdir = join(parent, 'never-created');
  try {
    const result = spawnSync(process.execPath, [script, '--workdir', workdir, '--start-server'], { encoding: 'utf8', timeout: 10_000 });
    assert.equal(result.status, 2);
    assert.match(result.stderr, /server-start-requires-explicit-accept-eula/);
    assert.equal(spawnSync('test', ['-e', workdir]).status, 1);

    const approvalOnly = spawnSync(process.execPath, [script, '--workdir', workdir, '--accept-eula'], { encoding: 'utf8', timeout: 10_000 });
    assert.equal(approvalOnly.status, 2);
    assert.match(approvalOnly.stderr, /accept-eula-requires-start-server/);
  } finally {
    await rm(parent, { recursive: true, force: true });
  }
});

test('CLI preserves safe refusal codes for repository, symlink, and invalid workdirs', async () => {
  const parent = await mkdtemp(join(tmpdir(), 'worldspeaker-clean-workdir-refusal-'));
  const repositoryAlias = join(parent, 'repository-link');
  const token = randomUUID();
  const repositoryWorkdir = join(root, `.worldspeaker-refused-${token}`);
  const symlinkWorkdir = join(repositoryAlias, `.worldspeaker-refused-${token}`);
  const relativeWorkdir = `relative-worldspeaker-refused-${token}`;
  const missingParent = join(parent, 'missing-parent');
  const missingParentWorkdir = join(missingParent, 'candidate');
  const existingWorkdir = join(parent, 'already-exists');
  const destinationSymlink = join(parent, 'destination-link');
  const symlinkTarget = join(root, `.worldspeaker-symlink-target-${token}`);
  const sentinel = join(existingWorkdir, 'sentinel.txt');

  try {
    await symlink(root, repositoryAlias, 'dir');
    await symlink(symlinkTarget, destinationSymlink, 'dir');
    await mkdir(existingWorkdir);
    await writeFile(sentinel, 'leave unchanged\n');

    const cases = [
      [repositoryWorkdir, 'workdir-must-be-outside-repository'],
      [symlinkWorkdir, 'workdir-must-be-outside-repository'],
      [relativeWorkdir, 'workdir-must-be-absolute'],
      [missingParentWorkdir, 'workdir-parent-must-exist'],
      [existingWorkdir, 'workdir-already-exists'],
      [destinationSymlink, 'workdir-already-exists'],
    ];
    for (const [workdir, code] of cases) {
      const result = spawnSync(process.execPath, [script, '--workdir', workdir], {
        encoding: 'utf8',
        timeout: 10_000,
      });
      assert.ifError(result.error);
      assert.equal(result.status, 2, `${code}: ${result.stderr}`);
      assert.equal(result.stdout, '');
      assert.equal(result.stderr, `WS-CLEAN-INSTALL-REFUSED ${code}\n`);
      assert.equal(result.stderr.includes(parent), false);
      assert.equal(result.stderr.includes(root), false);
    }

    await assert.rejects(lstat(repositoryWorkdir), { code: 'ENOENT' });
    await assert.rejects(lstat(symlinkWorkdir), { code: 'ENOENT' });
    await assert.rejects(lstat(relativeWorkdir), { code: 'ENOENT' });
    await assert.rejects(lstat(missingParent), { code: 'ENOENT' });
    assert.equal((await lstat(destinationSymlink)).isSymbolicLink(), true);
    await assert.rejects(lstat(symlinkTarget), { code: 'ENOENT' });
    assert.equal(await readFile(sentinel, 'utf8'), 'leave unchanged\n');
    await assert.rejects(lstat(join(existingWorkdir, 'run-record.json')), { code: 'ENOENT' });
  } finally {
    await rm(parent, { recursive: true, force: true });
  }
});

test('argument parser rejects duplicate options and requires absolute workdir', () => {
  assert.throws(() => parseArgs(['--workdir', '/tmp/a', '--workdir', '/tmp/b']), /duplicate-option-workdir/);
  assert.throws(() => parseArgs(['--workdir', 'relative']), /workdir-must-be-absolute/);
});

test('candidate distribution fixture is checked against the outer hash manifest and embedded package facts', async () => {
  const parent = await mkdtemp(join(tmpdir(), 'worldspeaker-clean-fixture-'));
  const distribution = join(parent, 'candidate');
  await mkdir(distribution);
  try {
    const manifestBytes = await readFile(join(root, 'compatibility/deployment.json'));
    const deploymentSha256 = sha256(manifestBytes);
    const modName = `${manifest.mod.id}-${manifest.mod.version}.jar`;
    const companionName = `${manifest.companion.package.replace(/^@/, '').replace('/', '-')}-${manifest.companion.version}.tgz`;
    const modBytes = Buffer.from('temporary fake jar bytes');
    const companionBytes = Buffer.from('temporary fake npm tarball bytes');
    await writeFile(join(distribution, 'deployment.json'), manifestBytes);
    await writeFile(join(distribution, modName), modBytes);
    await writeFile(join(distribution, companionName), companionBytes);
    await writeFile(join(distribution, 'outer-manifest.json'), JSON.stringify({
      deploymentId: manifest.deploymentId,
      manifestRevision: manifest.manifestRevision,
      deploymentManifestSha256: deploymentSha256,
      qualificationState: 'unqualified',
      artifacts: [
        { name: modName, bytes: modBytes.byteLength, sha256: sha256(modBytes) },
        { name: companionName, bytes: companionBytes.byteLength, sha256: sha256(companionBytes) },
      ],
    }));

    const result = await verifyCandidateDistribution({
      distribution,
      manifest,
      manifestSha256: deploymentSha256,
      readZipJson: async () => ({
        id: manifest.mod.id,
        version: manifest.mod.version,
        environment: 'server',
        depends: {
          minecraft: manifest.target.minecraft,
          fabricloader: manifest.runtimeDependencies.fabricLoader,
          'fabric-api': manifest.runtimeDependencies.fabricApi,
        },
      }),
      readTarJson: async () => ({
        ...companionPackage,
      }),
    });
    assert.equal(result.artifacts.length, 2);
    assert.equal(result.modPath, join(distribution, modName));

    const harnessName = `${manifest.mod.id}-${manifest.mod.version}-compat-load-harness.jar`;
    await writeFile(join(distribution, harnessName), Buffer.from('synthetic separate harness artifact'));
    await assert.rejects(verifyCandidateDistribution({
      distribution,
      manifest,
      manifestSha256: deploymentSha256,
      readZipJson: async () => ({
        id: manifest.mod.id,
        version: manifest.mod.version,
        environment: 'server',
        depends: {
          minecraft: manifest.target.minecraft,
          fabricloader: manifest.runtimeDependencies.fabricLoader,
          'fabric-api': manifest.runtimeDependencies.fabricApi,
        },
      }),
      readTarJson: async () => ({ ...companionPackage }),
    }), /candidate-contains-nonproduction-load-artifact/);
    await rm(join(distribution, harnessName));

    const { dependencies: _dependencies, devDependencies, ...stalePackage } = companionPackage;
    const staleArchiveManifest = {
      ...stalePackage,
      devDependencies: { ...devDependencies, ws: companionPackage.dependencies.ws },
    };
    await assert.rejects(verifyCandidateDistribution({
      distribution,
      manifest,
      manifestSha256: deploymentSha256,
      readZipJson: async () => ({
        id: manifest.mod.id,
        version: manifest.mod.version,
        environment: 'server',
        depends: {
          minecraft: manifest.target.minecraft,
          fabricloader: manifest.runtimeDependencies.fabricLoader,
          'fabric-api': manifest.runtimeDependencies.fabricApi,
        },
      }),
      readTarJson: async () => staleArchiveManifest,
    }), /companion-package-archive-stale/);

    await writeFile(join(distribution, modName), Buffer.from('tampered fixture'));
    await assert.rejects(verifyCandidateDistribution({
      distribution,
      manifest,
      manifestSha256: deploymentSha256,
      readZipJson: async () => ({}),
      readTarJson: async () => ({}),
    }), /candidate-artifact-hash-mismatch/);
  } finally {
    await rm(parent, { recursive: true, force: true });
  }
});

test('bounded ZIP reader extracts the exact metadata entry without an external unzip command', async () => {
  const parent = await mkdtemp(join(tmpdir(), 'worldspeaker-clean-zip-'));
  const path = join(parent, 'fixture.jar');
  const metadata = Buffer.from(JSON.stringify({ id: 'fabric-api', version: 'fixture' }));
  try {
    await writeFile(path, storedZip('fabric.mod.json', metadata));
    assert.deepEqual(await readZipJson(path, 'fabric.mod.json'), { id: 'fabric-api', version: 'fixture' });
    await assert.rejects(readZipJson(path, 'missing.json'), /zip-entry-missing/);
  } finally {
    await rm(parent, { recursive: true, force: true });
  }
});

test('Fabric Maven artifact bytes must match the publisher checksum sidecar', async () => {
  const parent = await mkdtemp(join(tmpdir(), 'worldspeaker-clean-maven-'));
  const artifactPath = join(parent, 'fixture.jar');
  const artifact = Buffer.from('fixture bytes from an exact Maven coordinate');
  const checksum = sha1(artifact);
  let badBytes = false;
  const fakeFetch = async (url) => {
    const text = String(url);
    const isChecksum = text.endsWith('.sha1');
    const bytes = isChecksum ? Buffer.from(`${checksum}  fixture.jar\n`) : badBytes ? Buffer.from('wrong bytes') : artifact;
    return new Response(bytes, { status: 200, headers: { 'content-length': String(bytes.byteLength) } });
  };
  try {
    const accepted = await downloadMavenArtifact({ path: 'fabric/test/1.0/test-1.0.jar', output: artifactPath, fetchImpl: fakeFetch });
    assert.equal(accepted.sha1, checksum);
    assert.deepEqual(await readFile(artifactPath), artifact);

    const secondPath = join(parent, 'bad.jar');
    badBytes = true;
    await assert.rejects(downloadMavenArtifact({ path: 'fabric/test/1.0/test-1.0.jar', output: secondPath, fetchImpl: fakeFetch }), /maven-artifact-checksum-mismatch/);
  } finally {
    await rm(parent, { recursive: true, force: true });
  }
});

test('download redirects are validated before following and remain on allowed HTTPS hosts', async () => {
  let requests = 0;
  await assert.rejects(downloadBytesForTest(async () => {
    requests += 1;
    return new Response(null, { status: 302, headers: { location: 'https://attacker.invalid/payload' } });
  }), /download-redirect-origin-not-allowed/);
  assert.equal(requests, 1);

  requests = 0;
  const bytes = await downloadBytesForTest(async (url, options) => {
    requests += 1;
    assert.equal(options.redirect, 'manual');
    if (String(url).endsWith('/start')) return new Response(null, { status: 302, headers: { location: '/final' } });
    return new Response(Buffer.from('verified fixture'));
  });
  assert.equal(bytes.toString('utf8'), 'verified fixture');
  assert.equal(requests, 2);
});

test('runtime startup errors are attributed to the first uncompleted startup phase', () => {
  const phases = Object.fromEntries([
    'contract', 'candidateArtifacts', 'runtimePreflight', 'pinnedDownloads', 'fabricInstall', 'openclawInstall',
    'pluginInstall', 'pluginConfiguration', 'gatewayStartup', 'serverStartup', 'authenticatedHandshake',
  ].map((name) => [name, { state: 'passed' }]));
  phases.gatewayStartup = { state: 'skipped', code: 'not-run' };
  phases.serverStartup = { state: 'skipped', code: 'not-run' };
  phases.authenticatedHandshake = { state: 'skipped', code: 'not-run' };
  assert.equal(phaseForError({ phases }), 'gatewayStartup');
  phases.gatewayStartup = { state: 'passed' };
  assert.equal(phaseForError({ phases }), 'serverStartup');
  phases.serverStartup = { state: 'passed' };
  assert.equal(phaseForError({ phases }), 'authenticatedHandshake');
});

test('bounded subprocess helper terminates a hanging fixture process', async () => {
  const result = await runBoundedCommand(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
    cwd: root,
    env: { PATH: process.env.PATH },
    timeoutMs: 50,
  });
  assert.equal(result.timedOut, true);
  assert.notEqual(result.signal, null);
});

function storedZip(name, data) {
  const fileName = Buffer.from(name);
  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(20, 4);
  local.writeUInt32LE(0, 14);
  local.writeUInt32LE(data.byteLength, 18);
  local.writeUInt32LE(data.byteLength, 22);
  local.writeUInt16LE(fileName.byteLength, 26);
  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50, 0);
  central.writeUInt16LE(20, 4);
  central.writeUInt16LE(20, 6);
  central.writeUInt32LE(0, 16);
  central.writeUInt32LE(data.byteLength, 20);
  central.writeUInt32LE(data.byteLength, 24);
  central.writeUInt16LE(fileName.byteLength, 28);
  central.writeUInt32LE(0, 42);
  const centralDirectory = Buffer.concat([central, fileName]);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(1, 8);
  end.writeUInt16LE(1, 10);
  end.writeUInt32LE(centralDirectory.byteLength, 12);
  end.writeUInt32LE(local.byteLength + fileName.byteLength + data.byteLength, 16);
  return Buffer.concat([local, fileName, data, centralDirectory, end]);
}

function downloadBytesForTest(fetchImpl) {
  return downloadBytes('https://registry.npmjs.org/start', {
    allowedHosts: ['registry.npmjs.org'],
    maxBytes: 1024,
    fetchImpl,
  });
}
