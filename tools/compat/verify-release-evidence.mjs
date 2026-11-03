import { createHash } from 'node:crypto';
import { readFileSync, realpathSync, statSync } from 'node:fs';
import { createRequire } from 'node:module';
import { basename, dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifyCompanionPackageArchive } from './verify-companion-package-archive.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(resolve(root, 'companion/package.json'));
const Ajv2020 = require('ajv/dist/2020').default;
const addFormats = require('ajv-formats').default;

const REQUIRED_TEST_IDS = Object.freeze(['T1', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'T8']);

export function artifactPairSha256(artifacts) {
  const canonical = [...artifacts]
    .map(({ name, version, sha256 }) => ({ name, version, sha256 }))
    .sort((left, right) => left.name.localeCompare(right.name))
    .map(({ name, version, sha256 }) => `${name}\0${version}\0${sha256}`)
    .join('\n') + '\n';
  return sha256(Buffer.from(canonical));
}

export function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

export function validateDocument(document, schema) {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  let validate;
  try {
    validate = ajv.compile(schema);
  } catch (error) {
    throw new VerificationError('WS-RELEASE-SCHEMA-INVALID', safeErrorMessage(error));
  }
  if (!validate(document)) {
    const errors = (validate.errors ?? []).map((error) => `${error.instancePath || '/'} ${error.message}`).join('; ');
    throw new VerificationError('WS-RELEASE-RECORD-INVALID', errors || 'document does not satisfy its strict schema');
  }
}

export function verifyReleaseEvidence(options) {
  return verifyQualification(options, false);
}

// Contract tests may validate a fully isolated, explicitly marked fixture. This
// path can never produce release authorization and is not exposed by the CLI.
export function verifySyntheticFixtureContract(options) {
  const result = verifyQualification(options, true);
  return { ...result, fixtureValidated: true, releaseAuthorized: false };
}

function verifyQualification({
  record,
  qualificationSchema,
  deployment,
  deploymentSchema,
  deploymentBytes,
  artifacts,
  evidenceBaseDir,
  companionArchivePath,
  presetArchivePath,
}, allowSyntheticFixture) {
  validateDocument(record, qualificationSchema);

  const syntheticFixture = record.recordType === 'synthetic-test-fixture' && record.status === 'fixture';
  if (allowSyntheticFixture && !syntheticFixture) {
    refuse('WS-RELEASE-SYNTHETIC-APPROVAL', 'fixture-contract validation accepts only explicitly marked synthetic test fixtures');
  }
  if (syntheticFixture && !allowSyntheticFixture) {
    refuse('WS-RELEASE-SYNTHETIC-APPROVAL', 'synthetic fixture approvals and evidence are never release authorization');
  }
  if (syntheticFixture && [record.approvals?.qa, record.approvals?.stakeholder].some((approval) =>
      approval?.approvalClass !== 'synthetic-fixture' || approval?.fixtureOnly !== true)) {
    refuse('WS-RELEASE-SYNTHETIC-APPROVAL', 'test fixtures must carry explicit synthetic-only approval markers');
  }
  if (!syntheticFixture && (record.recordType !== 'qualification' || record.status !== 'qualified')) {
    refuse('WS-RELEASE-EVIDENCE-INCOMPLETE', 'the record is not a completed qualification');
  }

  validateDocument(deployment, deploymentSchema);
  if (deployment.qualification.state !== 'qualified' || deployment.qualification.evidence.length === 0) {
    refuse('WS-RELEASE-UNQUALIFIED', 'the deployment manifest remains unqualified or has no qualification evidence');
  }

  const currentManifestSha256 = sha256(deploymentBytes);
  if (record.deploymentId !== deployment.deploymentId ||
      record.manifestRevision !== deployment.manifestRevision ||
      record.deploymentManifestSha256 !== currentManifestSha256) {
    refuse('WS-RELEASE-MANIFEST-STALE', 'qualification record does not bind the current deployment manifest and SHA-256');
  }
  if ((!syntheticFixture && record.dirtyTree !== false) || !record.sourceRevision.trim()) {
    refuse('WS-RELEASE-EVIDENCE-INVALID', 'qualification must identify a clean source revision');
  }

  const testById = new Map();
  for (const result of record.tests) {
    if (testById.has(result.id)) refuse('WS-RELEASE-TESTS-INCOMPLETE', `${result.id} appears more than once`);
    testById.set(result.id, result);
  }
  if (REQUIRED_TEST_IDS.some((id) => !testById.has(id)) || testById.size !== REQUIRED_TEST_IDS.length) {
    refuse('WS-RELEASE-TESTS-INCOMPLETE', 'exactly one result for each required test T1-T8 is required');
  }
  for (const id of REQUIRED_TEST_IDS) {
    const result = testById.get(id);
    if (result.status !== 'passed' || result.exitCode !== 0 || result.sampleCount < 1 || result.evidence.length === 0) {
      refuse('WS-RELEASE-TESTS-INCOMPLETE', `${id} must have a passing result, a sample, exit code 0, and evidence`);
    }
    for (const item of result.evidence) {
      const expectedEvidenceClass = syntheticFixture ? 'synthetic-fixture' : 'real';
      if (item.evidenceClass !== expectedEvidenceClass) {
        refuse('WS-RELEASE-REAL-EVIDENCE-MISSING', `${id} evidence is absent or not classified as ${expectedEvidenceClass}`);
      }
      verifyEvidenceFile(item, evidenceBaseDir, id);
    }
  }

  const actualArtifacts = readArtifactPair(artifacts);
  if (!syntheticFixture) {
    if (!companionArchivePath || !presetArchivePath) {
      refuse('WS-RELEASE-PRESET-ARCHIVE-UNVERIFIED', 'the approved companion and matching preset sidecar are required');
    }
    let sourcePackageManifest;
    try {
      sourcePackageManifest = JSON.parse(readFileSync(resolve(root, 'companion/package.json'), 'utf8'));
    } catch {
      refuse('WS-RELEASE-PRESET-ARCHIVE-UNVERIFIED', 'the current companion package manifest is unavailable');
    }
    try {
      verifyCompanionPackageArchive({
        sourceManifest: sourcePackageManifest,
        archivePath: companionArchivePath,
        sidecarPath: presetArchivePath,
      });
    } catch (error) {
      const code = error?.code ?? 'companion-preset-archive-invalid';
      refuse('WS-RELEASE-PRESET-ARCHIVE-UNVERIFIED', `companion preset sidecar verification failed: ${code}`);
    }
  }
  if (record.artifacts.length !== actualArtifacts.length) {
    refuse('WS-RELEASE-ARTIFACT-PAIR-UNVERIFIED', 'qualification must contain exactly the final mod and companion artifacts');
  }
  const recordArtifacts = new Map();
  for (const item of record.artifacts) {
    if (recordArtifacts.has(item.name)) {
      refuse('WS-RELEASE-ARTIFACT-PAIR-UNVERIFIED', `duplicate artifact record for ${item.name}`);
    }
    recordArtifacts.set(item.name, item);
  }
  for (const actual of actualArtifacts) {
    const approved = recordArtifacts.get(actual.name);
    if (approved === undefined || approved.version !== actual.version || approved.sha256 !== actual.sha256 ||
        approved.path !== actual.name) {
      refuse('WS-RELEASE-ARTIFACT-PAIR-UNVERIFIED', `approved evidence does not match ${actual.name} final SHA-256`);
    }
  }

  const pairSha256 = artifactPairSha256(actualArtifacts);
  const qa = record.approvals?.qa;
  const stakeholder = record.approvals?.stakeholder;
  if (!isApproval(qa, 'QA', syntheticFixture) || !isApproval(stakeholder, 'STAKEHOLDER', syntheticFixture)) {
    refuse('WS-RELEASE-APPROVALS-MISSING', 'separate QA and stakeholder approvals are required');
  }
  for (const approval of [qa, stakeholder]) {
    if (approval.sourceRevision !== record.sourceRevision ||
        approval.deploymentManifestSha256 !== currentManifestSha256 ||
        approval.artifactPairSha256 !== pairSha256) {
      refuse('WS-RELEASE-APPROVAL-STALE', 'approval is not bound to this source, manifest, and final artifact pair');
    }
  }

  return {
    deploymentId: deployment.deploymentId,
    manifestSha256: currentManifestSha256,
    artifactPairSha256: pairSha256,
    releaseAuthorized: !syntheticFixture,
  };
}

export function verifyEvidenceFile(item, evidenceBaseDir, testId) {
  if (isAbsolute(item.path) || item.path.split(/[\\/]/).includes('..')) {
    refuse('WS-RELEASE-REAL-EVIDENCE-MISSING', `${testId} evidence path must stay inside the qualification record directory`);
  }
  let base;
  let filePath;
  try {
    base = realpathSync(evidenceBaseDir);
    filePath = realpathSync(resolve(base, item.path));
  } catch {
    refuse('WS-RELEASE-REAL-EVIDENCE-MISSING', `${testId} evidence file is absent`);
  }
  const relativePath = relative(base, filePath);
  if (!relativePath || relativePath === '..' || relativePath.startsWith(`..${sep}`) || isAbsolute(relativePath)) {
    refuse('WS-RELEASE-REAL-EVIDENCE-MISSING', `${testId} evidence resolves outside the qualification record directory`);
  }
  try {
    if (!statSync(filePath).isFile() || sha256(readFileSync(filePath)) !== item.sha256) {
      refuse('WS-RELEASE-REAL-EVIDENCE-MISSING', `${testId} evidence SHA-256 does not match its recorded file`);
    }
  } catch (error) {
    if (error instanceof VerificationError) throw error;
    refuse('WS-RELEASE-REAL-EVIDENCE-MISSING', `${testId} evidence file cannot be verified`);
  }
}

function readArtifactPair(inputs) {
  if (!Array.isArray(inputs) || inputs.length !== 2) {
    refuse('WS-RELEASE-ARTIFACT-PAIR-UNVERIFIED', 'release verification requires exactly two final artifacts');
  }
  const artifacts = inputs.map(({ path, version }) => {
    try {
      const bytes = readFileSync(path);
      const name = basename(path);
      return { name, path, version, sha256: sha256(bytes) };
    } catch {
      refuse('WS-RELEASE-ARTIFACT-PAIR-UNVERIFIED', 'a final artifact is absent or unreadable');
    }
  });
  if (new Set(artifacts.map((item) => item.name)).size !== 2) {
    refuse('WS-RELEASE-ARTIFACT-PAIR-UNVERIFIED', 'the final mod and companion artifact names must be distinct');
  }
  return artifacts;
}

function isApproval(approval, role, syntheticFixture) {
  return approval !== null && typeof approval === 'object' &&
    approval.reviewer.trim().length > 0 && approval.role === role &&
    approval.approvalClass === (syntheticFixture ? 'synthetic-fixture' : 'real') &&
    approval.fixtureOnly === syntheticFixture;
}

class VerificationError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

function refuse(code, message) {
  throw new VerificationError(code, message);
}

function safeErrorMessage(error) {
  return error instanceof Error ? error.message : 'unknown validation error';
}

function parseArgs(tokens) {
  const result = {};
  for (let index = 0; index < tokens.length; index += 1) {
    const option = tokens[index];
    if (!['--record', '--manifest', '--mod', '--companion', '--preset'].includes(option)) {
      throw new VerificationError('WS-RELEASE-VERIFIER-USAGE', `unknown option: ${option}`);
    }
    const value = tokens[++index];
    if (!value || value.startsWith('--')) {
      throw new VerificationError('WS-RELEASE-VERIFIER-USAGE', `${option} requires a path`);
    }
    if (result[option.slice(2)] !== undefined) {
      throw new VerificationError('WS-RELEASE-VERIFIER-USAGE', `${option} may be supplied only once`);
    }
    result[option.slice(2)] = value;
  }
  for (const name of ['record', 'manifest', 'mod', 'companion']) {
    if (!result[name]) throw new VerificationError('WS-RELEASE-VERIFIER-USAGE', `--${name} is required`);
  }
  return result;
}

function readJson(path, code) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch {
    throw new VerificationError(code, 'required JSON file is absent or malformed');
  }
}

function main() {
  try {
    const args = parseArgs(process.argv.slice(2));
    const record = readJson(args.record, 'WS-RELEASE-RECORD-INVALID');
    const manifestBytes = readFileSync(args.manifest);
    let deployment;
    try {
      deployment = JSON.parse(manifestBytes.toString('utf8'));
    } catch {
      throw new VerificationError('WS-RELEASE-MANIFEST-INVALID', 'deployment manifest is malformed JSON');
    }
    const qualificationSchema = readJson(resolve(root, 'compatibility/qualification.schema.json'), 'WS-RELEASE-SCHEMA-INVALID');
    const deploymentSchema = readJson(resolve(root, 'compatibility/deployment.schema.json'), 'WS-RELEASE-SCHEMA-INVALID');
    const result = verifyReleaseEvidence({
      record,
      qualificationSchema,
      deployment,
      deploymentSchema,
      deploymentBytes: manifestBytes,
      artifacts: [
        { path: args.mod, version: deployment.mod?.version },
        { path: args.companion, version: deployment.companion?.version },
      ],
      evidenceBaseDir: dirname(resolve(args.record)),
      companionArchivePath: args.companion,
      presetArchivePath: args.preset,
    });
    process.stdout.write(`WS-RELEASE-EVIDENCE-VALID deployment=${result.deploymentId} manifest=${result.manifestSha256} pair=${result.artifactPairSha256}\n`);
  } catch (error) {
    const code = error instanceof VerificationError ? error.code : 'WS-RELEASE-EVIDENCE-INVALID';
    const message = error instanceof VerificationError ? error.message : 'verification could not be completed';
    process.stderr.write(`${code}: ${message}\n`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
