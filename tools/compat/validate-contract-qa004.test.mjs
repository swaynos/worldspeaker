import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import {
  artifactPairSha256,
  verifyReleaseEvidence,
  verifySyntheticFixtureContract,
} from './verify-release-evidence.mjs';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const validator = join(repository, 'tools/compat/validate-contract.mjs');
const releaseVerifier = join(repository, 'tools/compat/verify-release-evidence.mjs');
const deploymentPath = join(repository, 'compatibility/deployment.json');
const qualificationSchema = JSON.parse(readFileSync(join(repository, 'compatibility/qualification.schema.json'), 'utf8'));
const deploymentSchema = JSON.parse(readFileSync(join(repository, 'compatibility/deployment.schema.json'), 'utf8'));

test('accepts and verifies a complete synthetic approval contract only in an isolated fixture', () => {
  const fixture = makeFixture();
  try {
    const validation = spawnSync(process.execPath, [validator, '--qualification', fixture.recordPath], {
      cwd: repository,
      encoding: 'utf8',
    });
    assert.equal(validation.status, 0, `${validation.stdout}\n${validation.stderr}`);
    assert.match(validation.stdout, /WS-CONTRACT-VALID/);

    const verified = verifySyntheticFixtureContract(fixture.options);
    assert.equal(verified.fixtureValidated, true);
    assert.equal(verified.releaseAuthorized, false);
    assert.throws(
      () => verifyReleaseEvidence(fixture.options),
      (error) => error.code === 'WS-RELEASE-SYNTHETIC-APPROVAL',
    );
    const releaseCheck = spawnSync(process.execPath, [
      releaseVerifier,
      '--record', fixture.recordPath,
      '--manifest', fixture.manifestPath,
      '--mod', fixture.modPath,
      '--companion', fixture.companionPath,
    ], { cwd: repository, encoding: 'utf8' });
    assert.equal(releaseCheck.status, 1, `${releaseCheck.stdout}\n${releaseCheck.stderr}`);
    assert.match(`${releaseCheck.stdout}\n${releaseCheck.stderr}`, /WS-RELEASE-SYNTHETIC-APPROVAL/);

    assert.equal(existsSync(join(repository, 'docs/qualification/COMPAT-01/approved.json')), false,
      'synthetic fixtures must never be written as the real approval record');
    const canonical = JSON.parse(readFileSync(deploymentPath, 'utf8'));
    assert.equal(canonical.qualification.state, 'unqualified');
    assert.deepEqual(canonical.qualification.evidence, []);
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true });
  }
});

test('rejects malformed qualification fields and absent T1-T8 results', () => {
  const fixture = makeFixture();
  try {
    const extraField = structuredClone(fixture.options);
    extraField.record.unrecognizedAuthority = true;
    assertReleaseRefusal(extraField, 'WS-RELEASE-RECORD-INVALID');

    const missingT8 = structuredClone(fixture.options);
    missingT8.record.tests = missingT8.record.tests.filter(({ id }) => id !== 'T8');
    assertReleaseRefusal(missingT8, 'WS-RELEASE-RECORD-INVALID');
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true });
  }
});

test('rejects a mismatched final mod/companion SHA-256 pair', () => {
  const fixture = makeFixture();
  try {
    for (const index of [0, 1]) {
      const mismatch = structuredClone(fixture.options);
      mismatch.record.artifacts[index].sha256 = '0'.repeat(64);
      assertReleaseRefusal(mismatch, 'WS-RELEASE-ARTIFACT-PAIR-UNVERIFIED');
    }
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true });
  }
});

test('rejects missing QA or stakeholder approvals and explicitly synthetic approvals', () => {
  const fixture = makeFixture();
  try {
    const missingQa = structuredClone(fixture.options);
    missingQa.record.approvals.qa = null;
    assertReleaseRefusal(missingQa, 'WS-RELEASE-RECORD-INVALID');

    const missingStakeholder = structuredClone(fixture.options);
    missingStakeholder.record.approvals.stakeholder = null;
    assertReleaseRefusal(missingStakeholder, 'WS-RELEASE-RECORD-INVALID');

    assert.throws(
      () => verifyReleaseEvidence(fixture.options),
      (error) => error.code === 'WS-RELEASE-SYNTHETIC-APPROVAL',
    );

    const relabelledFixture = structuredClone(fixture.options);
    relabelledFixture.record.recordType = 'qualification';
    relabelledFixture.record.status = 'qualified';
    relabelledFixture.record.dirtyTree = false;
    assert.throws(
      () => verifyReleaseEvidence(relabelledFixture),
      (error) => error.code === 'WS-RELEASE-RECORD-INVALID',
      'synthetic approvals/evidence cannot be relabelled as a qualified record',
    );
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true });
  }
});

test('rejects stale deployment-manifest binding and stale reviewer approval binding', () => {
  const fixture = makeFixture();
  try {
    const staleManifest = structuredClone(fixture.options);
    staleManifest.record.deploymentManifestSha256 = '0'.repeat(64);
    assertReleaseRefusal(staleManifest, 'WS-RELEASE-MANIFEST-STALE');

    const staleApproval = structuredClone(fixture.options);
    staleApproval.record.approvals.qa.artifactPairSha256 = '0'.repeat(64);
    assertReleaseRefusal(staleApproval, 'WS-RELEASE-APPROVAL-STALE');
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true });
  }
});

test('rejects skipped or failed runtime tests and missing real evidence files', () => {
  const fixture = makeFixture();
  try {
    for (const status of ['failed', 'skipped']) {
      const incomplete = structuredClone(fixture.options);
      incomplete.record.tests.find(({ id }) => id === 'T3').status = status;
      assertReleaseRefusal(incomplete, 'WS-RELEASE-TESTS-INCOMPLETE');
    }

    const missingEvidence = structuredClone(fixture.options);
    missingEvidence.record.tests.find(({ id }) => id === 'T6').evidence[0].path = 'evidence/missing-T6.log';
    assertReleaseRefusal(missingEvidence, 'WS-RELEASE-REAL-EVIDENCE-MISSING');

    const unclassifiedEvidence = structuredClone(fixture.options);
    unclassifiedEvidence.record.tests.find(({ id }) => id === 'T3').evidence[0].evidenceClass = 'real';
    assertReleaseRefusal(unclassifiedEvidence, 'WS-RELEASE-RECORD-INVALID');
  } finally {
    rmSync(fixture.directory, { recursive: true, force: true });
  }
});

test('the real deployment manifest remains unqualified and has no approval record', () => {
  const manifest = JSON.parse(readFileSync(deploymentPath, 'utf8'));
  assert.equal(manifest.qualification.state, 'unqualified');
  assert.deepEqual(manifest.qualification.evidence, []);
  assert.equal(existsSync(join(repository, 'docs/qualification/COMPAT-01/approved.json')), false);
});

function assertReleaseRefusal(options, code) {
  assert.throws(
    () => verifySyntheticFixtureContract(options),
    (error) => error.code === code,
  );
}

function makeFixture() {
  // This is a synthetic contract fixture in a fresh OS temporary directory.
  // It is never written to docs/qualification or treated as real evidence.
  const directory = mkdtempSync(join(tmpdir(), 'worldspeaker-qa004-synthetic-fixture-'));
  writeFileSync(join(directory, 'FIXTURE-ONLY.txt'),
    'SYNTHETIC QA-004 CONTRACT TEST FIXTURE ONLY; NEVER A REAL QUALIFICATION OR APPROVED RECORD.\n');
  const evidenceDirectory = join(directory, 'evidence');
  const artifactsDirectory = join(directory, 'artifacts');
  mkdirSync(evidenceDirectory);
  mkdirSync(artifactsDirectory);

  const deployment = JSON.parse(readFileSync(deploymentPath, 'utf8'));
  deployment.qualification = {
    state: 'qualified',
    evidence: ['SYNTHETIC QA-004 TEST FIXTURE ONLY; not deployment qualification'],
  };
  const deploymentBytes = Buffer.from(`${JSON.stringify(deployment, null, 2)}\n`);
  const deploymentManifestSha256 = sha256(deploymentBytes);
  const manifestPath = join(directory, 'synthetic-deployment-fixture.json');
  writeFileSync(manifestPath, deploymentBytes);

  const modPath = join(artifactsDirectory, `${deployment.mod.id}-${deployment.mod.version}.jar`);
  const companionPath = join(artifactsDirectory, 'worldspeaker-openclaw-channel-0.1.0-dev.1.tgz');
  writeFileSync(modPath, 'SYNTHETIC QA-004 MOD FIXTURE; NOT A BUILD ARTIFACT.\n');
  writeFileSync(companionPath, 'SYNTHETIC QA-004 COMPANION FIXTURE; NOT A BUILD ARTIFACT.\n');
  const artifacts = [
    { name: basename(modPath), version: deployment.mod.version, sha256: sha256(readFileSync(modPath)), path: basename(modPath) },
    { name: basename(companionPath), version: deployment.companion.version, sha256: sha256(readFileSync(companionPath)), path: basename(companionPath) },
  ];
  const pairSha256 = artifactPairSha256(artifacts);

  const tests = ['T1', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'T8'].map((id) => {
    const evidencePath = `evidence/${id}.log`;
    const bytes = Buffer.from(`SYNTHETIC QA-004 ${id} EVIDENCE FIXTURE ONLY.\n`);
    writeFileSync(join(directory, evidencePath), bytes);
    return {
      id,
      command: `synthetic isolated fixture for ${id}`,
      status: 'passed',
      sampleCount: 1,
      exitCode: 0,
      evidence: [{ path: evidencePath, sha256: sha256(bytes), evidenceClass: 'synthetic-fixture' }],
    };
  });

  const approval = (role) => ({
    reviewer: `SYNTHETIC TEST ONLY ${role}`,
    role,
    approvedAtUtc: '2026-10-28T12:34:56.123Z',
    sourceRevision: 'synthetic-qa004-fixture-only',
    deploymentManifestSha256,
    artifactPairSha256: pairSha256,
    approvalClass: 'synthetic-fixture',
    fixtureOnly: true,
  });
  const record = {
    schemaVersion: 1,
    recordType: 'synthetic-test-fixture',
    status: 'fixture',
    deploymentId: deployment.deploymentId,
    manifestRevision: deployment.manifestRevision,
    deploymentManifestSha256,
    runId: 'synthetic-qa004-fixture-only',
    sourceRevision: 'synthetic-qa004-fixture-only',
    dirtyTree: true,
    startedAtUtc: '2026-10-28T12:00:00.000Z',
    testerRole: 'ACT-MAINTAINER',
    reviewerRole: null,
    platform: {
      os: 'synthetic fixture',
      imageDigest: null,
      kernel: 'synthetic fixture',
      cpu: 'synthetic fixture',
      memoryMiB: 0,
      jvmFlags: [],
    },
    software: {
      java: 'synthetic fixture',
      node: process.versions.node,
      npm: 'synthetic fixture',
      openclaw: 'synthetic fixture',
      loader: 'synthetic fixture',
      fabricApi: 'synthetic fixture',
    },
    artifacts,
    tests,
    approvals: { qa: approval('QA'), stakeholder: approval('STAKEHOLDER') },
    features: [],
    limitations: ['Synthetic fixture only; no runtime claim.'],
  };
  const recordPath = join(directory, 'synthetic-qualification-fixture.json');
  writeFileSync(recordPath, `${JSON.stringify(record, null, 2)}\n`);

  return {
    directory,
    recordPath,
    manifestPath,
    modPath,
    companionPath,
    options: {
      record,
      qualificationSchema,
      deployment,
      deploymentSchema,
      deploymentBytes,
      artifacts: [
        { path: modPath, version: deployment.mod.version },
        { path: companionPath, version: deployment.companion.version },
      ],
      evidenceBaseDir: directory,
    },
  };
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}
