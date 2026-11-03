import assert from 'node:assert/strict';
import { chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

test('assembleRelease enforces approval when its verifier is excluded and reruns with stale output', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-release-gate-'));
  try {
    copyFixture(fixture);

    const releaseDirectory = join(fixture, 'build/distributions/worldspeaker-release');
    mkdirSync(releaseDirectory, { recursive: true });
    const staleMarker = join(releaseDirectory, 'stale-release-marker.txt');
    writeFileSync(staleMarker, 'not a release artifact or qualification record\n');

    for (let attempt = 1; attempt <= 2; attempt += 1) {
      const result = spawnSync(join(fixture, 'gradlew'), [
        '--offline', '--no-daemon', '--console=plain', '--max-workers=1', '-PlocalDevelopment=true', '-p', fixture,
        'assembleRelease', '-x', 'verifyReleaseCompatibility',
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
      const output = `${result.stdout ?? ''}\n${result.stderr ?? ''}`;

      assert.ifError(result.error);
      assert.notEqual(result.status, 0, `attempt ${attempt} unexpectedly assembled a release:\n${output}`);
      assert.match(output, /WS-RELEASE-UNQUALIFIED/, `attempt ${attempt} did not reach the release boundary check:\n${output}`);
      assert.doesNotMatch(output, /> Task :verifyReleaseCompatibility(?:\s|$)/,
        'the verifier task itself should be excluded in this regression');
      assert.doesNotMatch(output, /> Task :assembleRelease UP-TO-DATE/,
        'release authorization must not be skipped because an output directory already exists');
      assert.equal(readFileSync(staleMarker, 'utf8'), 'not a release artifact or qualification record\n');
      assert.equal(existsSync(join(releaseDirectory, 'approved.json')), false);
    }
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
});

function copyFixture(destination) {
  const files = [
    'LICENSE', 'build.gradle', 'buildscript-gradle.lockfile', 'gradle.lockfile', 'gradle.properties',
    'gradlew', 'settings.gradle',
    'gradle', 'compatibility', 'src', 'presets/god', 'tools/presets',
    'build/presets/worldspeaker-god-preset-v1.zip',
    'docs/compatibility.md', 'docs/r01/build/COMPAT-01.md',
    'tools/compat/validate-contract.mjs', 'tools/compat/generate-companion-facts.mjs',
  ];
  for (const path of files) {
    const source = join(repository, path);
    const target = join(destination, path);
    mkdirSync(dirname(target), { recursive: true });
    cpSync(source, target, { recursive: true });
  }

  const sourceCompanion = join(repository, 'companion');
  const targetCompanion = join(destination, 'companion');
  cpSync(sourceCompanion, targetCompanion, {
    recursive: true,
    filter: (path) => {
      const childPath = relative(sourceCompanion, path);
      if (!childPath) return true;
      const parts = childPath.split(sep);
      return !parts.includes('node_modules') && !parts.includes('dist') && !childPath.endsWith('.tgz');
    },
  });
  symlinkSync(join(sourceCompanion, 'node_modules'), join(targetCompanion, 'node_modules'), 'dir');
  chmodSync(join(destination, 'gradlew'), 0o755);
}
