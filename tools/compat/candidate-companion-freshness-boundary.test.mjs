import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const helper = join(repository, 'tools/compat/verify-companion-package-archive.mjs');
const companionManifest = JSON.parse(readFileSync(join(repository, 'companion/package.json'), 'utf8'));
const deployment = JSON.parse(readFileSync(join(repository, 'compatibility/deployment.json'), 'utf8'));

test('archive verifier accepts a source-identical TGZ and rejects dev-only ws', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-companion-archive-'));
  try {
    const freshArchive = join(fixture, 'fresh.tgz');
    const staleArchive = join(fixture, 'stale.tgz');
    const stalePresetArchive = join(fixture, 'stale-preset.tgz');
    const staleAbilityArchive = join(fixture, 'stale-ability-metadata.tgz');
    createArchive(fixture, freshArchive, companionManifest, 'fresh-input');
    createArchive(fixture, staleArchive, staleWsManifest(companionManifest), 'stale-input');
    const stalePreset = Buffer.from(readFileSync(join(repository, 'companion/presets/worldspeaker-god-preset-v1.zip')));
    writeFileSync(join(fixture, 'stale-preset.zip'), stalePreset.subarray(0, stalePreset.length - 1));
    createArchive(fixture, stalePresetArchive, companionManifest, 'stale-preset-input',
      readFileSync(join(fixture, 'stale-preset.zip')));
    const staleAbilityCatalogue = Buffer.from(readFileSync(join(repository, 'companion/compatibility/ability-contracts-v1.json')));
    staleAbilityCatalogue[0] ^= 1;
    createArchive(fixture, staleAbilityArchive, companionManifest, 'stale-ability-input', undefined,
      { 'ability-contracts-v1.json': staleAbilityCatalogue });

    const fresh = runArchiveVerifier(freshArchive);
    assert.ifError(fresh.error);
    assert.equal(fresh.status, 0, fresh.stderr);
    assert.match(fresh.stdout, /WS-CANDIDATE-COMPANION-ARCHIVE-FRESH:/);

    const stale = runArchiveVerifier(staleArchive);
    assert.ifError(stale.error);
    assert.equal(stale.status, 1);
    assert.equal(stale.stdout, '');
    assert.equal(stale.stderr, 'WS-CANDIDATE-COMPANION-ARCHIVE-STALE: companion-package-archive-stale\n');

    const stalePresetResult = runArchiveVerifier(stalePresetArchive);
    assert.ifError(stalePresetResult.error);
    assert.equal(stalePresetResult.status, 1);
    assert.equal(stalePresetResult.stdout, '');
    assert.match(stalePresetResult.stderr,
      /WS-CANDIDATE-COMPANION-ARCHIVE-INVALID: preset-archive-(?:eocd|size)-invalid/);

    const staleAbilityResult = runArchiveVerifier(staleAbilityArchive);
    assert.ifError(staleAbilityResult.error);
    assert.equal(staleAbilityResult.status, 1);
    assert.equal(staleAbilityResult.stdout, '');
    assert.equal(staleAbilityResult.stderr,
      'WS-CANDIDATE-COMPANION-ARCHIVE-INVALID: companion-package-ability-metadata-stale\n');
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('assembleCandidate rejects a stale TGZ before deleting output when packager and validators are excluded', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-candidate-boundary-'));
  try {
    copyGradleFixture(fixture);
    installSyntheticFactsInFixtureOnly(fixture);

    const archivePath = join(fixture, 'companion/worldspeaker-openclaw-channel-0.1.0-dev.1.tgz');
    createArchive(fixture, archivePath, staleWsManifest(companionManifest), 'archive-input');
    const staleArchiveBytes = readFileSync(archivePath);

    const output = join(fixture, 'build/distributions/worldspeaker-candidate');
    mkdirSync(output, { recursive: true });
    const markerPath = join(output, 'preexisting-candidate-marker.txt');
    const outputArchivePath = join(output, 'worldspeaker-openclaw-channel-0.1.0-dev.1.tgz');
    writeFileSync(markerPath, 'preserve existing candidate output\n');
    writeFileSync(outputArchivePath, staleArchiveBytes);
    const beforeNames = readdirSync(output).sort();

    const result = spawnSync(join(fixture, 'gradlew'), [
      '--offline', '--no-daemon', '--console=plain', '--max-workers=1', '-PlocalDevelopment=true', '-p', fixture,
      'assembleCandidate',
      '-x', 'verifyServerOnly',
      '-x', 'packageCompanion',
      '-x', 'validateDeploymentContract',
      '-x', 'validateApprovedQualification',
      '-x', 'verifyCandidateBuildEnvironment',
      '-x', 'build',
      '-x', 'generateCompatibilityDocs',
    ], {
      cwd: fixture,
      encoding: 'utf8',
      timeout: 300_000,
      maxBuffer: 16 * 1024 * 1024,
      env: {
        ...process.env,
        GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? join(repository, '.compat-cache/gradle'),
      },
    });
    const outputText = `${result.stdout ?? ''}\n${result.stderr ?? ''}`;

    assert.ifError(result.error);
    assert.notEqual(result.status, 0, `stale archive unexpectedly passed the candidate action boundary:\n${outputText}`);
    assert.match(outputText, /WS-CANDIDATE-COMPANION-ARCHIVE-STALE: companion-package-archive-stale/);
    assert.match(outputText, /> Task :assembleCandidate(?:\s|$)/);
    assert.doesNotMatch(outputText, /> Task :(?:verifyServerOnly|packageCompanion|validateDeploymentContract|validateApprovedQualification|verifyCandidateBuildEnvironment|build|generateCompatibilityDocs)(?:\s|$)/,
      'the regression must reach the action boundary with the package task and validators excluded');
    assert.deepEqual(readdirSync(output).sort(), beforeNames);
    assert.equal(readFileSync(markerPath, 'utf8'), 'preserve existing candidate output\n');
    assert.deepEqual(readFileSync(outputArchivePath), staleArchiveBytes);
    assert.deepEqual(readFileSync(archivePath), staleArchiveBytes);
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

test('candidate action boundary rejects a stale inner preset before output mutation when preset prerequisites are excluded', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-candidate-preset-boundary-'));
  try {
    copyGradleFixture(fixture);
    installSyntheticFactsInFixtureOnly(fixture);
    const archivePath = join(fixture, 'companion/worldspeaker-openclaw-channel-0.1.0-dev.1.tgz');
    const stalePreset = readFileSync(join(fixture, 'companion/presets/worldspeaker-god-preset-v1.zip')).subarray(0, -1);
    createArchive(fixture, archivePath, companionManifest, 'stale-preset-package', stalePreset);

    const output = join(fixture, 'build/distributions/worldspeaker-candidate');
    mkdirSync(output, { recursive: true });
    const markerPath = join(output, 'preserve-candidate-marker.txt');
    const outputArchivePath = join(output, 'worldspeaker-openclaw-channel-0.1.0-dev.1.tgz');
    writeFileSync(markerPath, 'candidate output must remain untouched\n');
    writeFileSync(outputArchivePath, 'existing candidate archive marker\n');
    const beforeNames = readdirSync(output).sort();

    const result = spawnSync(join(fixture, 'gradlew'), [
      '--offline', '--no-daemon', '--console=plain', '--max-workers=1', '-PlocalDevelopment=true', '-p', fixture,
      'assembleCandidate',
      '-x', 'verifyServerOnly', '-x', 'packageCompanion', '-x', 'packageGodPreset', '-x', 'verifyGodPreset',
      '-x', 'validateDeploymentContract', '-x', 'validateApprovedQualification',
      '-x', 'verifyCandidateBuildEnvironment', '-x', 'build', '-x', 'generateCompatibilityDocs',
    ], {
      cwd: fixture,
      encoding: 'utf8',
      timeout: 300_000,
      maxBuffer: 16 * 1024 * 1024,
      env: { ...process.env, GRADLE_USER_HOME: process.env.GRADLE_USER_HOME ?? join(repository, '.compat-cache/gradle') },
    });
    const outputText = `${result.stdout ?? ''}\n${result.stderr ?? ''}`;
    assert.ifError(result.error);
    assert.notEqual(result.status, 0, `stale nested ZIP unexpectedly reached candidate copying:\n${outputText}`);
    assert.match(outputText, /WS-CANDIDATE-COMPANION-ARCHIVE-INVALID: preset-archive-(?:eocd|size)-invalid/);
    assert.match(outputText, /> Task :assembleCandidate(?:\s|$)/);
    assert.doesNotMatch(outputText, /> Task :(?:packageGodPreset|verifyGodPreset|packageCompanion|build|generateCompatibilityDocs)(?:\s|$)/,
      'the action boundary itself rejects stale nested bytes with preset/package tasks excluded');
    assert.deepEqual(readdirSync(output).sort(), beforeNames);
    assert.equal(readFileSync(markerPath, 'utf8'), 'candidate output must remain untouched\n');
    assert.equal(readFileSync(outputArchivePath, 'utf8'), 'existing candidate archive marker\n');
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

function runArchiveVerifier(archivePath) {
  return spawnSync(process.execPath, [helper, archivePath], { encoding: 'utf8', timeout: 15_000 });
}

function staleWsManifest(source) {
  const { dependencies, devDependencies, ...manifest } = source;
  return {
    ...manifest,
    ...(dependencies?.ws ? { devDependencies: { ...devDependencies, ws: dependencies.ws } } : {}),
  };
}

function createArchive(fixture, archivePath, manifest, inputDirectoryName,
                       presetBytes = readFileSync(join(repository, 'companion/presets/worldspeaker-god-preset-v1.zip')),
                       abilityMetadataOverrides = {}) {
  const packageDirectory = join(fixture, inputDirectoryName, 'package');
  mkdirSync(packageDirectory, { recursive: true });
  writeFileSync(join(packageDirectory, 'package.json'), `${JSON.stringify(manifest, null, 2)}\n`);
  const abilityFiles = [
    'ability-contracts-v1.json',
    'ability-contracts-v1.schema.json',
    'ability-discovery-v1.schema.json',
  ];
  const compatibilityDirectory = join(packageDirectory, 'compatibility');
  mkdirSync(compatibilityDirectory, { recursive: true });
  for (const name of abilityFiles) {
    writeFileSync(join(compatibilityDirectory, name), abilityMetadataOverrides[name] ??
      readFileSync(join(repository, 'companion/compatibility', name)));
  }
  const presetDirectory = join(packageDirectory, 'presets');
  mkdirSync(presetDirectory, { recursive: true });
  writeFileSync(join(presetDirectory, 'worldspeaker-god-preset-v1.zip'), presetBytes);
  const packed = spawnSync('tar', ['-czf', archivePath, '-C', join(fixture, inputDirectoryName), 'package'], {
    encoding: 'utf8',
    timeout: 15_000,
  });
  assert.ifError(packed.error);
  assert.equal(packed.status, 0, packed.stderr);
}

function copyGradleFixture(destination) {
  const paths = [
    'LICENSE', 'build.gradle', 'buildscript-gradle.lockfile', 'gradle.lockfile', 'gradle.properties',
    'gradlew', 'settings.gradle', 'gradle', 'compatibility', 'src', 'presets/god', 'tools/presets',
    'docs/compatibility.md', 'docs/r01/build/COMPAT-01.md',
    'tools/compat/companion-package-freshness.mjs', 'tools/compat/verify-companion-package-archive.mjs',
  ];
  for (const path of paths) {
    const source = join(repository, path);
    const target = join(destination, path);
    mkdirSync(dirname(target), { recursive: true });
    cpSync(source, target, { recursive: true });
  }

  const companionSource = join(repository, 'companion');
  const companionTarget = join(destination, 'companion');
  cpSync(companionSource, companionTarget, {
    recursive: true,
    filter: (path) => {
      const childPath = relative(companionSource, path);
      if (!childPath) return true;
      const parts = childPath.split(sep);
      return !parts.includes('node_modules') && !parts.includes('dist') && !childPath.endsWith('.tgz');
    },
  });
}

function installSyntheticFactsInFixtureOnly(fixture) {
  const buildFile = join(fixture, 'build.gradle');
  const original = readFileSync(buildFile, 'utf8');
  const start = original.indexOf('def candidateBuildFacts = {');
  const end = original.indexOf('\ndef verifyCandidateBuildEnvironment = {', start);
  assert.ok(start >= 0 && end > start, 'expected the candidate facts closure in the temporary Gradle fixture');

  const java = deployment.target.java;
  const quote = (value) => JSON.stringify(value.toString());
  const syntheticFacts = `def candidateBuildFacts = {
    // TEST FIXTURE ONLY: synthetic pinned facts let this disposable copy reach the action boundary.
    // This replacement is never written to the repository or exposed as a production override.
    [
        gradleJavaVendor: ${quote(java.vendor)},
        gradleJavaRuntimeVersion: ${quote(java.runtimeVersion)},
        gradleJavaMajor: ${quote(java.major)},
        compilerJavaVendor: ${quote(java.vendor)},
        compilerJavaRuntimeVersion: ${quote(java.runtimeVersion)},
        compilerJavaMajor: ${quote(java.major)},
        compilerArchitecture: 'x86_64',
        osName: 'Linux',
        osArchitecture: 'x86_64',
        distributionId: 'ubuntu',
        distributionVersion: '24.04',
        kernel: 'synthetic-test-fixture'
    ]
}
`;
  writeFileSync(buildFile, `${original.slice(0, start)}${syntheticFacts}${original.slice(end + 1)}`);
}
