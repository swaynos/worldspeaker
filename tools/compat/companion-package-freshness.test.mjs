import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { verifyCompanionPackageFreshness } from './companion-package-freshness.mjs';

const sourcePackagePath = fileURLToPath(new URL('../../companion/package.json', import.meta.url));
const sourceManifest = JSON.parse(await readFile(sourcePackagePath, 'utf8'));

test('current packed manifest is fresh and declares ws as a runtime dependency', () => {
  const result = verifyCompanionPackageFreshness({
    sourceManifest,
    archiveManifest: structuredClone(sourceManifest),
  });
  assert.equal(result.name, '@worldspeaker/openclaw-channel');
  assert.equal(result.version, '0.1.0-dev.1');
  assert.equal(result.wsVersion, '8.22.0');
});

test('a hash-valid old dev-only ws manifest is rejected as stale', () => {
  const { dependencies: _dependencies, devDependencies, ...oldManifest } = sourceManifest;
  assert.throws(() => verifyCompanionPackageFreshness({
    sourceManifest,
    archiveManifest: {
      ...oldManifest,
      devDependencies: { ...devDependencies, ws: sourceManifest.dependencies.ws },
    },
  }), /companion-package-archive-stale/);
});

test('any package manifest drift invalidates the packed archive', () => {
  assert.throws(() => verifyCompanionPackageFreshness({
    sourceManifest,
    archiveManifest: { ...structuredClone(sourceManifest), version: '0.1.0-dev.0' },
  }), /companion-package-archive-stale/);
});

test('source cannot pass freshness unless ws is declared only as a runtime dependency', () => {
  assert.throws(() => verifyCompanionPackageFreshness({
    sourceManifest: {
      ...sourceManifest,
      dependencies: {},
      devDependencies: { ...sourceManifest.devDependencies, ws: '8.22.0' },
    },
    archiveManifest: structuredClone(sourceManifest),
  }), /companion-source-ws-runtime-dependency-invalid/);
});
