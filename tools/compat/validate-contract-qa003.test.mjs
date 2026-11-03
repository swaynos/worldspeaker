import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const validator = join(repository, 'tools/compat/validate-contract.mjs');

test('valid schema-shaped qualification record passes strict date-time validation (QA-003 repro)', () => {
  const result = validateRecord(qualificationRecord());

  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stdout, /WS-CONTRACT-VALID/);
  assert.doesNotMatch(`${result.stdout}\n${result.stderr}`, /unknown format|uncaught|Error:/i);
});

test('invalid date-time values produce safe actionable schema diagnostics', () => {
  for (const startedAtUtc of [
    '2025-02-29T12:00:00Z',
    '2024-02-30T12:00:00Z',
    '2024-01-01T24:01:00Z',
    '2024-01-01T12:00:00+25:00',
    '2024-01-01T12:00:00',
    'not-a-date',
  ]) {
    const result = validateRecord(qualificationRecord({ startedAtUtc }));
    const output = `${result.stdout}\n${result.stderr}`;

    assert.equal(result.status, 1, `${startedAtUtc}: ${output}`);
    assert.match(output, /WS-CONTRACT-INVALID: qualification record does not satisfy its strict schema/);
    assert.match(output, /\/startedAtUtc must match format "date-time"/);
    assert.doesNotMatch(output, /unknown format|uncaught exception|\n\s+at /i);
  }
});

test('malformed qualification JSON gets a safe diagnostic instead of an exception', () => {
  const directory = mkdtempSync(join(tmpdir(), 'worldspeaker-qa003-invalid-json-'));
  try {
    const recordPath = join(directory, 'qualification.json');
    writeFileSync(recordPath, '{ "startedAtUtc": ');
    const result = spawnSync(process.execPath, [validator, '--qualification', recordPath], {
      cwd: repository,
      encoding: 'utf8',
    });
    const output = `${result.stdout}\n${result.stderr}`;

    assert.equal(result.status, 1, output);
    assert.match(output, /WS-CONTRACT-INVALID: qualification record or schema could not be read as valid JSON/);
    assert.doesNotMatch(output, /SyntaxError|uncaught exception|\n\s+at /i);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

function validateRecord(record) {
  const directory = mkdtempSync(join(tmpdir(), 'worldspeaker-qa003-record-'));
  try {
    const recordPath = join(directory, 'qualification.json');
    writeFileSync(recordPath, `${JSON.stringify(record, null, 2)}\n`);
    return spawnSync(process.execPath, [validator, '--qualification', recordPath], {
      cwd: repository,
      encoding: 'utf8',
    });
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

function qualificationRecord(overrides = {}) {
  const syntheticHash = 'a'.repeat(64);
  const approval = (role) => ({
    reviewer: `SYNTHETIC TEST ONLY ${role}`,
    role,
    approvedAtUtc: '2026-10-28T12:34:56.123Z',
    sourceRevision: 'synthetic-qa003-only',
    deploymentManifestSha256: syntheticHash,
    artifactPairSha256: syntheticHash,
    approvalClass: 'synthetic-fixture',
    fixtureOnly: true,
  });
  return {
    schemaVersion: 1,
    recordType: 'synthetic-test-fixture',
    status: 'fixture',
    deploymentId: 'synthetic-qa003-only',
    manifestRevision: 'synthetic-revision',
    deploymentManifestSha256: syntheticHash,
    runId: 'synthetic-qa003-only',
    sourceRevision: 'synthetic-source',
    dirtyTree: true,
    startedAtUtc: '2026-10-28T12:34:56.123Z',
    testerRole: 'ACT-MAINTAINER',
    reviewerRole: null,
    platform: {
      os: 'synthetic',
      imageDigest: null,
      kernel: 'synthetic',
      cpu: 'synthetic',
      memoryMiB: 0,
      jvmFlags: [],
    },
    software: {
      java: 'synthetic',
      node: process.versions.node,
      npm: 'synthetic',
      openclaw: 'synthetic',
      loader: 'synthetic',
      fabricApi: 'synthetic',
    },
    artifacts: [
      { name: 'synthetic-mod.jar', version: 'fixture', sha256: syntheticHash, path: 'synthetic-mod.jar' },
      { name: 'synthetic-companion.tgz', version: 'fixture', sha256: syntheticHash, path: 'synthetic-companion.tgz' },
    ],
    tests: ['T1', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'T8'].map((id) => ({
      id,
      command: `synthetic ${id} fixture only`,
      status: 'passed',
      sampleCount: 1,
      exitCode: 0,
      evidence: [{ path: `synthetic/${id}.json`, sha256: syntheticHash, evidenceClass: 'synthetic-fixture' }],
    })),
    approvals: { qa: approval('QA'), stakeholder: approval('STAKEHOLDER') },
    features: [],
    limitations: [],
    ...overrides,
  };
}
