import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const validator = join(repository, 'tools/compat/validate-contract.mjs');
const currentManifest = join(repository, 'compatibility/deployment.json');

test('accepts a schema-valid synthetic qualified deployment without changing the current manifest', () => {
  const original = JSON.parse(readFileSync(currentManifest, 'utf8'));
  assert.equal(original.qualification.state, 'unqualified');
  assert.deepEqual(original.qualification.evidence, []);

  const temporaryDirectory = mkdtempSync(join(tmpdir(), 'worldspeaker-qa002-synthetic-'));
  try {
    const syntheticManifest = {
      ...original,
      qualification: {
        state: 'qualified',
        evidence: ['SYNTHETIC QA-002 TEST FIXTURE ONLY; not approval or release evidence'],
      },
    };
    const syntheticPath = join(temporaryDirectory, 'synthetic-qualified-deployment.json');
    writeFileSync(syntheticPath, `${JSON.stringify(syntheticManifest, null, 2)}\n`);

    const result = runValidator(syntheticPath);
    assert.ifError(result.error);
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    assert.match(result.stdout, /state=qualified/);

    const unchanged = JSON.parse(readFileSync(currentManifest, 'utf8'));
    assert.equal(unchanged.qualification.state, 'unqualified');
    assert.deepEqual(unchanged.qualification.evidence, []);
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});

test('rejects a qualified deployment with no schema-required evidence', () => {
  const original = JSON.parse(readFileSync(currentManifest, 'utf8'));
  const temporaryDirectory = mkdtempSync(join(tmpdir(), 'worldspeaker-qa002-invalid-'));
  try {
    const syntheticPath = join(temporaryDirectory, 'synthetic-qualified-without-evidence.json');
    writeFileSync(syntheticPath, JSON.stringify({
      ...original,
      qualification: { state: 'qualified', evidence: [] },
    }));

    const result = runValidator(syntheticPath);
    assert.ifError(result.error);
    assert.notEqual(result.status, 0);
    assert.match(`${result.stdout}\n${result.stderr}`, /deployment manifest does not satisfy its strict schema/);
  } finally {
    rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});

test('continues to validate the repository manifest as unqualified and evidence-free', () => {
  const result = runValidator();
  assert.ifError(result.error);
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stdout, /state=unqualified/);

  const manifest = JSON.parse(readFileSync(currentManifest, 'utf8'));
  assert.equal(manifest.qualification.state, 'unqualified');
  assert.deepEqual(manifest.qualification.evidence, []);
  assert.deepEqual(manifest.availableFeatures, []);
});

function runValidator(deploymentPath = undefined) {
  const args = [validator];
  if (deploymentPath) args.push('--deployment', deploymentPath);
  return spawnSync(process.execPath, args, { cwd: repository, encoding: 'utf8' });
}
