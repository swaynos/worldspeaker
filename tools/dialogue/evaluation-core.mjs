import { createHash, createHmac, randomBytes, randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { constants as fsConstants } from 'node:fs';
import { mkdir, open, lstat, readFile, rename, rmdir, unlink } from 'node:fs/promises';
import { dirname, join, parse, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const SCHEMA_PATH = join(ROOT, 'tools/dialogue/evaluation.schema.json');
const OPERATIONAL_STREAM_SCHEMA_PATH = join(ROOT, 'tools/dialogue/numeric-stream.schema.json');
const SCENARIOS_PATH = join(ROOT, 'tools/dialogue/scenarios.json');
const requireCompanion = createRequire(join(ROOT, 'companion/package.json'));
const Ajv = requireCompanion('ajv').default;
const ajv = new Ajv({ allErrors: true, strict: false, validateFormats: false });
const schema = JSON.parse(await readFile(SCHEMA_PATH, 'utf8'));
const validateRunSchema = ajv.compile(schema);
const operationalStreamSchema = JSON.parse(await readFile(OPERATIONAL_STREAM_SCHEMA_PATH, 'utf8'));
const validateOperationalStreamLine = ajv.compile(operationalStreamSchema);
const scenarios = JSON.parse(await readFile(SCENARIOS_PATH, 'utf8'));

export const MAX_BYTES = 1024 * 1024;
export const MAX_OPERATIONAL_SOURCE_BYTES = 64 * 1024 * 1024;
export const MAX_OPERATIONAL_REPORT_BYTES = 1024 * 1024;
export const MAX_ENTRIES = 256;
export const MAX_INVOCATIONS = 64;
export const MAX_PLAYERS = 3;
export const NUMERIC_FORMAT_VERSION = 2;
export const OPERATIONAL_FORMAT_VERSION = 5;
const OPERATIONAL_SUPPORTED_FORMAT_VERSIONS = new Set([3, 4, OPERATIONAL_FORMAT_VERSION]);
const OPERATIONAL_MAX_LINE_BYTES = 256 * 1024;
const OPERATIONAL_CHUNK_SIZE = 64;
const OPERATIONAL_MAX_RECORDS = 4096;
const OPERATIONAL_MAX_SNAPSHOTS = 2;
const OPERATIONAL_LINES_PER_SNAPSHOT = 1 + OPERATIONAL_MAX_RECORDS / OPERATIONAL_CHUNK_SIZE;
// Keep one bounded snapshot's worth of trailing lines so a repeated manifest/chunk
// can invalidate a finalized snapshot without retaining an unbounded stream index.
const OPERATIONAL_MAX_LINES = (OPERATIONAL_MAX_SNAPSHOTS + 1) * OPERATIONAL_LINES_PER_SNAPSHOT;

const OUTCOMES = new Set([
  'HELP', 'INVALID', 'DENIED', 'UNAUTHENTICATED', 'UNAVAILABLE_BEFORE_BOOTSTRAP', 'RECEIPT_EVIDENCE_MISSING', 'UNAVAILABLE',
  'BUSY', 'COALESCED', 'FOLLOWUP_ENDED', 'RATE_LIMITED', 'EXPIRED', 'ADMITTED',
]);
const RATE_LIMIT_STATUSES = new Set([
  'NOT_RECORDED', 'NOT_CHECKED', 'ALLOWED', 'INVOCATION_LIMITED', 'SUBJECT_CAPACITY',
  'NEW_WORK_LIMITED', 'EVIDENCE_UNAVAILABLE', 'REPEAT_GUARD', 'REPEAT_GUARD_CAPACITY',
]);
const NOTICE_STATUSES = new Set([
  'NOT_RECORDED', 'NOT_REQUIRED', 'QUEUED', 'SUPPRESSED', 'SUBMITTED',
  'DEFINITELY_UNSENT', 'UNKNOWN', 'OVERFLOW', 'CANCELLED',
]);
const NOTICE_BATCH_FIELDS = [
  'noticeBatchPublicationId', 'noticeBatchResult', 'noticeBatchAttemptedAtNanos', 'noticeBatchLatencyNanos',
];
const NOTICE_BATCH_RESULTS = new Set([
  'NOT_BATCHED', 'PENDING', 'CLAIMED', 'SUBMITTED', 'DEFINITELY_UNSENT', 'UNKNOWN', 'CANCELLED',
]);
const ORIGINS = new Set(['COMMAND', 'NATURAL_ADDRESS', 'FOLLOWUP']);
const FIRST_SOURCES = new Set([
  'LOCAL_ACKNOWLEDGEMENT', 'INTEGRATION_ANSWER', 'INTEGRATION_FAILURE', 'LOCAL_SAFE_RESPONSE',
  'LOCAL_REJECTION', 'LOCAL_UNAVAILABLE', 'LOCAL_COALESCED', 'COMMAND_HELP', 'LOCAL_FOLLOWUP_END',
  'COMMAND_GUIDANCE', 'NATURAL_INVALID', 'LOCAL_UNAUTHENTICATED', 'LOCAL_DENIED', 'LOCAL_BUSY',
]);
const FIRST_DISPOSITIONS = new Set([
  'ACKNOWLEDGED', 'ANSWERED', 'FAILED', 'REJECTED', 'UNAVAILABLE', 'COALESCED', 'HELP', 'FOLLOWUP_ENDED',
  'INVALID', 'DENIED', 'UNAUTHENTICATED', 'BUSY',
]);
const TERMINAL_OUTCOMES = new Set([
  'ANSWER', 'DISPATCH_FAILED', 'CANCELLED', 'UNRESOLVED', ...FIRST_DISPOSITIONS,
]);
const REVIEW_ROLES = new Set(['player_1', 'player_2', 'player_3', 'independent_evaluator']);
const REVIEW_REASON_RENDERINGS = Object.freeze({
  persona_premise_fit: 'Approved premise/persona fit was considered.',
  context_coherence: 'Use of available conversational context was considered.',
  answer_relevance: 'Relevance to the observed request was considered.',
  answer_quality: 'Overall answer quality was considered.',
  discoverability: 'Unaided discoverability was considered.',
  enjoyment: 'Player enjoyment was considered.',
  clarity: 'Clarity of the response was considered.',
  progress_visibility: 'Truthful progress and completion visibility were considered.',
  reliability: 'Successful completion and technical reliability were considered.',
  latency: 'Observed response timing was considered.',
  safety_or_privacy: 'Safety or privacy behavior was considered.',
  mixed_or_inconclusive_evidence: 'The observed evidence was mixed or inconclusive.',
});
const REVIEW_WEAKNESS_RENDERINGS = Object.freeze({
  none_observed: 'No listed weakness was observed.',
  unclear_or_incomplete_answer: 'The answer was unclear or incomplete.',
  irrelevant_or_off_topic_answer: 'The answer was irrelevant or off topic.',
  unresolved_exchange: 'The exchange remained unresolved.',
  missing_or_late_feedback: 'Feedback was missing or late.',
  discovery_barrier: 'A discovery barrier was observed.',
  persona_or_context_mismatch: 'A premise/persona or context mismatch was observed.',
  technical_failure: 'A technical failure was observed.',
  safety_or_privacy_concern: 'A safety or privacy concern was observed.',
  other_observed_issue: 'Other observed issue; details are not collected or represented by this tool.',
});
const DECISION_REASON_RENDERINGS = Object.freeze({
  quality_criteria_supported: 'Observed quality evidence supports the criteria.',
  quality_criteria_not_supported: 'Observed quality evidence does not support the criteria.',
  required_evidence_missing: 'Required evidence is missing.',
  safety_or_privacy_blocker: 'A safety or privacy blocker was observed.',
  mixed_evidence: 'Evidence is mixed.',
  other_observed_issue: 'Other observed issue; details are not collected or represented by this tool.',
});
const REVIEW_REASON_CODES = new Set(Object.keys(REVIEW_REASON_RENDERINGS));
const REVIEW_WEAKNESS_CODES = new Set(Object.keys(REVIEW_WEAKNESS_RENDERINGS));
const PRIORITIZED_WEAKNESS_CODES = new Set([...REVIEW_WEAKNESS_CODES].filter((code) => code !== 'none_observed'));
const DECISION_REASON_CODES = new Set(Object.keys(DECISION_REASON_RENDERINGS));
const REVIEW_RATINGS = ['personaPremiseFit', 'contextCoherence', 'answerQuality', 'discoverability', 'enjoyment'];
const DECISIONS = new Set(['supported', 'not_supported', 'blocked', 'inconclusive']);
const CONSENT_KINDS = ['shared_game_history', 'provider_transfer', 'evaluation_records'];
const CONSENT_SCOPES = new Map([
  ['shared_game_history', 'shared_game_history'],
  ['provider_transfer', 'provider_transfer'],
  ['evaluation_records', 'evaluation_records'],
]);
const COUNTER_NAMES = [
  'FIRST_REPLY_DEADLINE_MISSES', 'TERMINAL_DUPLICATES', 'MAILBOX_OVERFLOWS', 'STALE_OUTPUTS',
  'TICK_BUDGET_EXHAUSTED', 'PENDING_EXPIRED', 'UPSTREAM_REJECTIONS', 'ACK_CONTEXT_FAILURES',
  'CONNECTION_FAILURES', 'RECONNECT_EXHAUSTED', 'REQUESTS_ADMITTED', 'ANSWERS_DELIVERED',
  'PROGRESS_DISPATCHES', 'PROGRESS_FAILURES', 'PROGRESS_MISSED', 'PROGRESS_UNKNOWN',
  'PROGRESS_CONTEXT_FAILURES', 'UNAVAILABLE_DISPOSITIONS', 'INVOCATIONS_CAPTURED', 'INTERACTION_HELP',
  'INTERACTION_INVALID', 'INTERACTION_DENIED', 'INTERACTION_UNAUTHENTICATED',
  'INTERACTION_UNAVAILABLE_BEFORE_BOOTSTRAP', 'INTERACTION_RECEIPT_EVIDENCE_MISSING',
  'INTERACTION_UNAVAILABLE', 'INTERACTION_BUSY',
  'INTERACTION_COALESCED', 'INTERACTION_ADMITTED', 'INTERACTION_FOLLOWUP_ENDED',
  'FAILED_PUBLIC_DISPATCHES', 'INTERACTION_EVIDENCE_DROPPED', 'FIRST_REPLY_ON_TIME', 'FIRST_REPLY_LATE',
  'FIRST_REPLY_NO_DISPATCH', 'FIRST_REPLY_NO_DISPATCH_SETTLED', 'UNKNOWN_PUBLIC_DISPATCHES',
  'CONTROL_QUEUE_OVERFLOWS', 'DISPOSITION_QUEUE_OVERFLOWS',
];
const OPERATIONAL_COUNTER_NAMES = Object.freeze([
  ...COUNTER_NAMES, 'INTERACTION_RATE_LIMITED', 'INTERACTION_EXPIRED', 'INVOCATION_RATE_LIMITED',
  'NEW_WORK_RATE_LIMITED', 'RATE_LIMIT_SUBJECT_OVERFLOWS', 'RATE_LIMIT_NOTICES_SUPPRESSED',
  'CAPTURE_HANDOFF_OVERFLOWS', 'LATE_OUTPUT_REJECTIONS',
]);
const OPERATIONAL_OUTCOME_COUNTERS = Object.freeze({
  HELP: 'INTERACTION_HELP',
  INVALID: 'INTERACTION_INVALID',
  DENIED: 'INTERACTION_DENIED',
  UNAUTHENTICATED: 'INTERACTION_UNAUTHENTICATED',
  UNAVAILABLE_BEFORE_BOOTSTRAP: 'INTERACTION_UNAVAILABLE_BEFORE_BOOTSTRAP',
  RECEIPT_EVIDENCE_MISSING: 'INTERACTION_RECEIPT_EVIDENCE_MISSING',
  UNAVAILABLE: 'INTERACTION_UNAVAILABLE',
  BUSY: 'INTERACTION_BUSY',
  COALESCED: 'INTERACTION_COALESCED',
  ADMITTED: 'INTERACTION_ADMITTED',
  FOLLOWUP_ENDED: 'INTERACTION_FOLLOWUP_ENDED',
  RATE_LIMITED: 'INTERACTION_RATE_LIMITED',
  EXPIRED: 'INTERACTION_EXPIRED',
});
const SOURCE_FIELDS = [
  'numericFormatVersion',
  'counters', 'tickHistogram', 'firstPublicDispatchHistograms', 'maxFirstPublicDispatchNanos', 'interactionRecords',
];
const LEGACY_SOURCE_FIELDS = SOURCE_FIELDS.filter((field) => field !== 'numericFormatVersion');
const RAW_INVOCATION_FIELDS = [
  'id', 't0Nanos', 'receiptWallTimeUnixMillis', 'handlerEnteredAtNanos', 'handlerDelayNanos',
  'admittedAtNanos', 'decisionAtNanos', 'decisionRecorded', 'admissionDelayNanos',
  'queuedAtNanos', 'requestLinkAtNanos', 'queueDelayNanos',
  'fallbackCutoffAtNanos', 'deadlineAtNanos', 'captureProvenance', 'producer', 'connectionGeneration',
  'player', 'actualWorld', 'serverBinding', 'worldBinding', 'characterBinding', 'origin', 'outcome',
  'rateLimitStatus', 'noticeStatus', 'templateRevision', 'requestId', 'queueDepthAtAdmission',
  'firstDispatchNanos', 'firstDispatchAtNanos',
  'queueAgeAtFirstDispatchNanos', 'publicationId', 'firstDispatchSource', 'firstDispatchDisposition',
  'firstReplyResult', 'sendResult', 'deadlineSettledAtNanos', 'failedDispatches', 'unknownDispatches',
  'progress', 'terminalOutcome', 'terminalNanos',
];
const LEGACY_RAW_INVOCATION_FIELDS = RAW_INVOCATION_FIELDS.filter((field) =>
  !['decisionAtNanos', 'decisionRecorded', 'requestLinkAtNanos', 'rateLimitStatus', 'noticeStatus'].includes(field));
const OPERATIONAL_RAW_INVOCATION_FIELDS = [
  ...RAW_INVOCATION_FIELDS.filter((field) => field !== 'progress'),
  'executionBudgetSeconds', 'pendingDeadlineAtNanos', 'activeDeadlineAtNanos',
  'absoluteExecutionDeadlineAtNanos', 'progressIntervalNanos', 'progressNoticeLimit',
  'progress', 'terminalDeliveryResult', 'terminalAtNanos',
];
const OPERATIONAL_QUEUE_INVOCATION_FIELDS = [
  'playerQueueDepthAtAdmission', 'forwardedAtNanos', 'pendingResidenceNanos',
  'globalPendingAtForward', 'playerPendingAtForward',
];
const OPERATIONAL_CANCELLATION_INVOCATION_FIELDS = [
  'terminalCause', 'retirementNoticeResult', 'localCancelStatus', 'localCancelSendResult',
  'providerCancel', 'lateOutputRejections',
];
const FIRST_REPLY_RESULTS = new Set([
  'PENDING', 'ON_TIME', 'LATE', 'NO_DISPATCH_DEADLINE', 'NO_DISPATCH_SETTLED', 'EVIDENCE_MISSING',
]);
const SEND_RESULTS = new Set(['SUBMITTED', 'DEFINITELY_UNSENT', 'UNKNOWN', 'NO_DISPATCH']);
const TERMINAL_DELIVERY_RESULTS = new Set(['SUBMITTED', 'DEFINITELY_UNSENT', 'UNKNOWN', 'NO_ATTEMPT']);
const RETIREMENT_NOTICE_RESULTS = new Set([
  'SUBMITTED', 'DEFINITELY_UNSENT', 'UNKNOWN', 'NO_AUDIENCE', 'SUPPRESSED', 'SHUTDOWN',
]);
const LOCAL_CANCEL_STATUSES = new Set([
  'NOT_ATTEMPTED', 'QUEUED', 'DEFINITELY_UNSENT', 'SUBMITTED', 'UNKNOWN', 'SHUTDOWN',
]);
const LOCAL_CANCEL_SEND_RESULTS = new Set(['SUBMITTED', 'UNKNOWN']);
const PUBLIC_VERSIONS = ['worldspeaker', 'minecraft', 'fabricLoader', 'fabricApi', 'java', 'openclaw', 'companion', 'node'];
const SCENARIO_IDS = new Set(scenarios.scenarios.map((scenario) => scenario.id));
const SCENARIO_WORK_CLASSES = new Map(scenarios.scenarios.map((scenario) => [scenario.id, scenario.workClass]));
const WORK_CLASS_BUCKETS = ['ordinary', 'tool-heavy', 'unknown', 'mixed'];
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const SHA_RE = /^[a-f0-9]{64}$/u;

export function createEmptyRun() {
  const run = {
    schemaVersion: 3,
    runId: randomUUID(),
    provenance: 'offline_fixture',
    aliasSalt: randomBytes(32).toString('base64url'),
    aliasIndex: { players: {}, turns: {}, requests: {} },
    metadata: emptyMetadata(),
    entries: [],
  };
  sealRun(run);
  return run;
}

export function reviewForm() {
  return {
    type: 'human_review',
    reviewerRole: 'player_1',
    scenarioIds: [],
    ratings: { personaPremiseFit: null, contextCoherence: null, answerQuality: null, discoverability: null, enjoyment: null },
    reasonCodes: [],
    weaknessCodes: [],
    terminalClassifications: [],
  };
}

export function independentReviewForm() {
  return {
    ...reviewForm(),
    reviewerRole: 'independent_evaluator',
    decision: null,
    decisionReasonCodes: [],
    prioritizedWeaknessCodes: [],
  };
}

export function reviewCodebook() {
  return {
    reasonCodes: REVIEW_REASON_RENDERINGS,
    weaknessCodes: REVIEW_WEAKNESS_RENDERINGS,
    decisionReasonCodes: DECISION_REASON_RENDERINGS,
    decisions: {
      supported: 'Independent human decision: criteria supported.',
      not_supported: 'Independent human decision: criteria not supported.',
      blocked: 'Independent human decision: blocked by a prerequisite or evidence limitation.',
      inconclusive: 'Independent human decision: evidence is inconclusive.',
    },
    ratings: Object.fromEntries(REVIEW_RATINGS.map((name) => [name, 'Select an integer from 1 (lowest) through 5 (highest).'])),
    privacyLimit: 'Codes are categories only. Do not add explanatory prose. For an unlisted weakness select other_observed_issue; details are not collected.',
  };
}

export function sessionForm() {
  return {
    type: 'session_attestation',
    origin: 'human_live',
    humanAttestation: false,
    attestedByRole: 'test_administrator',
    artifactHashes: [],
    versions: Object.fromEntries(PUBLIC_VERSIONS.map((key) => [key, null])),
    modelProviderLabel: null,
    hardwareClass: null,
    workload: { durationMinutes: null, playerCount: null },
    clockBoundary: 'unknown',
    personaBriefRevision: null,
    attentionSettings: null,
    consents: CONSENT_KINDS.map((kind) => ({
      kind, approved: false, reference: null, date: null, scope: null, approvedByRole: null,
    })),
    isolationApproval: { approved: false, reference: null, date: null, scope: null },
    discoveries: [],
  };
}

export async function initRun(outDirectory) {
  const directory = await resolveSafeTarget(outDirectory, { mustNotExist: true });
  try {
    await mkdir(directory, { mode: 0o700 });
  } catch (error) {
    if (error?.code === 'EEXIST') fail('target-exists');
    throw error;
  }
  const directoryIdentity = fileIdentity(await lstat(directory));
  const createdFiles = [];
  let initialized = false;
  try {
    await chmodOwnerOnly(directory, 0o700);
    return await withRunLock(directory, async () => {
      const run = createEmptyRun();
      try {
        const reviewPath = join(directory, 'review-form.json');
        createdFiles.push({ path: reviewPath, identity: await createNewPrivateFile(reviewPath, `${JSON.stringify(reviewForm(), null, 2)}\n`) });
        const independentReviewPath = join(directory, 'independent-review-form.json');
        createdFiles.push({ path: independentReviewPath, identity: await createNewPrivateFile(independentReviewPath, `${JSON.stringify(independentReviewForm(), null, 2)}\n`) });
        const sessionPath = join(directory, 'session-form.json');
        createdFiles.push({ path: sessionPath, identity: await createNewPrivateFile(sessionPath, `${JSON.stringify(sessionForm(), null, 2)}\n`) });
        const codebookPath = join(directory, 'review-codebook.json');
        createdFiles.push({ path: codebookPath, identity: await createNewPrivateFile(codebookPath, `${JSON.stringify(reviewCodebook(), null, 2)}\n`) });
        // Publish run.json last, so readers cannot observe a partially initialized run.
        const runPath = join(directory, 'run.json');
        createdFiles.push({ path: runPath, identity: await createNewPrivateFile(runPath, `${JSON.stringify(run, null, 2)}\n`) });
        initialized = true;
        return { directory, runId: run.runId, provenance: run.provenance };
      } catch (error) {
        await removeCreatedFiles(createdFiles);
        throw error;
      }
    });
  } catch (error) {
    if (!initialized) await removeEmptyDirectoryIfSame(directory, directoryIdentity);
    throw error;
  }
}

export async function recordEntry(runPath, entryPath, { legacyNumericV1 = false } = {}) {
  const resolvedRunPath = await resolveSafeExisting(runPath);
  const directory = dirname(resolvedRunPath);
  return await withRunLock(directory, async (lock) => {
    const runSnapshot = await readPrivateFileSnapshot(resolvedRunPath, MAX_BYTES);
    const entryFile = await readPrivateFile(entryPath, MAX_BYTES);
    const run = parseStrictJson(runSnapshot.bytes.toString('utf8'));
    validateRun(run);
    const bytes = entryFile.toString('utf8');
    const sourceSha256 = sha256(entryFile);
    if (run.entries.some((entry) => entry.sourceSha256 === sourceSha256)) fail('duplicate-entry');
    if (run.entries.length >= MAX_ENTRIES) fail('metadata-event-limit');
    const parsed = parseEntryInput(bytes, { legacyNumericV1 });
    const normalized = parsed.type === 'numeric_evidence'
      ? normalizeNumericEvidence(parsed.snapshots, sourceSha256, run)
      : parsed.type === 'session_attestation'
        ? normalizeSessionAttestation(parsed, sourceSha256)
        : normalizeHumanReview(parsed, sourceSha256);

    if (normalized.type === 'session_attestation') applySessionAttestation(run, normalized);
    run.entries.push(normalized);
    sealRun(run);
    validateRun(run);
    const output = `${JSON.stringify(run, null, 2)}\n`;
    if (Buffer.byteLength(output, 'utf8') > MAX_BYTES) fail('run-size-limit');

    await assertOwnedRunLock(lock);
    const current = await readPrivateFileSnapshot(resolvedRunPath, MAX_BYTES);
    if (!current.bytes.equals(runSnapshot.bytes) || !sameIdentity(current.identity, runSnapshot.identity)) {
      fail('run-version-conflict');
    }
    await atomicReplacePrivateFile(resolvedRunPath, output, runSnapshot.identity);
    return { runId: run.runId, entryType: normalized.type, entryCount: run.entries.length, sourceSha256 };
  });
}

export async function loadRun(runPath) {
  const bytes = await readPrivateFile(runPath, MAX_BYTES);
  const run = parseStrictJson(bytes.toString('utf8'));
  validateRun(run);
  return run;
}

export function validateRun(run) {
  if (run && typeof run === 'object' && (run.schemaVersion === 1 || run.schemaVersion === 2)) {
    fail('legacy-run-migration-required');
  }
  if (!validateRunSchema(run)) fail('run-schema-invalid');
  if (run.integrityHash !== calculateIntegrityHash(run)) fail('run-integrity-mismatch');
  if (!validUniqueAliases(run.aliasIndex)) fail('alias-index-invalid');
  if (Object.keys(run.aliasIndex.turns).length > MAX_INVOCATIONS || Object.keys(run.aliasIndex.players).length > MAX_PLAYERS) {
    fail('run-capacity-exceeded');
  }
  if (run.provenance === 'human_live') {
    if (!run.metadata.humanAttestation || livePrerequisites(run.metadata).length > 0) fail('live-attestation-incomplete');
    const attestations = run.entries.filter((entry) => entry.type === 'session_attestation');
    if (attestations.length !== 1) fail('live-attestation-count-invalid');
    const { sourceSha256: _hash, type: _type, origin: _origin, discoveries: _discoveries, ...attestedMetadata } = attestations[0];
    if (canonicalJson(attestedMetadata) !== canonicalJson(run.metadata)) fail('live-metadata-mismatch');
  } else if (run.metadata.humanAttestation || run.entries.some((entry) => entry.type === 'session_attestation')) {
    fail('provenance-label-mismatch');
  }

  const turnAliases = new Set(Object.values(run.aliasIndex.turns));
  const playerAliases = new Set(Object.values(run.aliasIndex.players));
  const knownTurns = new Set();
  const distinctEvidenceTurns = new Set();
  for (const entry of run.entries) {
    if (!SHA_RE.test(entry.sourceSha256)) fail('source-hash-invalid');
    if (entry.type === 'numeric_evidence') {
      if (entry.numericFormatVersion !== NUMERIC_FORMAT_VERSION) fail('numeric-format-version-invalid');
      for (const snapshot of entry.snapshots) {
        const seen = new Set();
        for (const invocation of snapshot.invocations) {
          if (seen.has(invocation.turnAlias)) fail('duplicate-invocation-in-snapshot');
          seen.add(invocation.turnAlias);
          if (!turnAliases.has(invocation.turnAlias) || invocation.playerAlias !== null && !playerAliases.has(invocation.playerAlias)) {
            fail('numeric-alias-unresolved');
          }
          if (invocation.requestAlias !== null && !Object.values(run.aliasIndex.requests).includes(invocation.requestAlias)) {
            fail('request-alias-unresolved');
          }
          knownTurns.add(invocation.turnAlias);
          distinctEvidenceTurns.add(invocation.turnAlias);
          if (invocation.firstDispatchNanos === '-1' &&
              (invocation.firstReplySource !== null || invocation.firstReplyDisposition !== null)) fail('first-dispatch-fields-invalid');
          if (invocation.firstDispatchNanos !== '-1' &&
              (invocation.firstReplySource === null || invocation.firstReplyDisposition === null)) fail('first-dispatch-fields-invalid');
           if (invocation.terminalNanos === '-1' && invocation.terminalOutcome !== null ||
               invocation.terminalNanos !== '-1' && invocation.terminalOutcome === null) fail('terminal-fields-invalid');
           if (invocation.terminalOutcome !== null && !TERMINAL_OUTCOMES.has(invocation.terminalOutcome)) fail('terminal-outcome-invalid');
           if (Object.hasOwn(invocation, 'terminalDeliveryResult') && invocation.terminalDeliveryResult !== null &&
               !TERMINAL_DELIVERY_RESULTS.has(invocation.terminalDeliveryResult) ||
               invocation.terminalOutcome === null && invocation.terminalDeliveryResult != null) {
             fail('terminal-delivery-fields-invalid');
           }
        }
      }
    } else if (entry.type === 'session_attestation') {
      validateSessionEntry(entry);
    } else if (entry.type === 'human_review') {
      validateReviewEntry(entry);
      for (const item of entry.terminalClassifications) {
        if (!knownTurns.has(item.turnAlias)) fail('review-turn-alias-unknown');
      }
    } else fail('entry-type-invalid');
  }
  if (distinctEvidenceTurns.size > MAX_INVOCATIONS) fail('invocation-limit');
  return true;
}

export function buildReport(run) {
  validateRun(run);
  const latestByTurn = new Map();
  const sourceHashes = [];
  const numericSnapshots = [];
  const reviews = [];
  const session = run.entries.find((entry) => entry.type === 'session_attestation') ?? null;
  for (const entry of run.entries) {
    sourceHashes.push(entry.sourceSha256);
    if (entry.type === 'numeric_evidence') {
      for (const snapshot of entry.snapshots) {
        numericSnapshots.push({ sourceSha256: entry.sourceSha256, index: snapshot.index, counters: snapshot.counters,
          firstPublicDispatchHistograms: snapshot.firstPublicDispatchHistograms,
          maxFirstPublicDispatchNanos: snapshot.maxFirstPublicDispatchNanos });
        for (const invocation of snapshot.invocations) latestByTurn.set(invocation.turnAlias, invocation);
      }
    } else if (entry.type === 'human_review') reviews.push(entry);
  }
  const invocations = [...latestByTurn.values()];
  const admitted = invocations.filter((item) => item.outcome === 'ADMITTED');
  const nonAdmitted = invocations.filter((item) => item.outcome !== 'ADMITTED');
  const firstDispatchInvocations = invocations.filter((item) => item.firstDispatchNanos !== '-1');
  const admittedFirstDispatchInvocations = firstDispatchInvocations.filter((item) => item.outcome === 'ADMITTED');
  const firstDispatch = firstDispatchInvocations.map((item) => BigInt(item.firstDispatchNanos));
  const admittedFirstDispatch = admittedFirstDispatchInvocations.map((item) => BigInt(item.firstDispatchNanos));
  const noFirstDispatchInvocations = invocations.filter((item) => item.firstDispatchNanos === '-1');
  const admittedNoFirstDispatch = noFirstDispatchInvocations.filter((item) => item.outcome === 'ADMITTED');
  const firstDispatchOver1000 = firstDispatchInvocations.filter((item) => BigInt(item.firstDispatchNanos) > 1_000_000_000n);
  const noDispatchDeadlineMisses = noFirstDispatchInvocations.filter((item) =>
    noDispatchAtOrAfterDeadline(item));
  const noDispatchSettled = noFirstDispatchInvocations.filter((item) => item.firstReplyResult === 'NO_DISPATCH_SETTLED');
  const noDispatchSettlements = invocations.filter((item) => item.deadlineSettledAtNanos !== null);
  const noDispatchAtDeadline = invocations.filter((item) => noDispatchAtOrAfterDeadline(item));
  const noDispatchSettledBeforeDeadline = invocations.filter((item) => noDispatchBeforeDeadline(item));
  const eventualNoDispatch = noDispatchDeadlineMisses.length + noDispatchSettled.length;
  const pendingFirstReplies = invocations.filter((item) => item.firstReplyResult === 'PENDING');
  const captureEvidenceMissing = invocations.filter((item) => item.firstReplyResult === 'EVIDENCE_MISSING');
  const knownDeadlineMissInvocations = invocations.filter(hasFirstReplyDeadlineMiss);
  const knownDeadlineMissCount = knownDeadlineMissInvocations.length;
  const admittedKnownDeadlineMissCount = knownDeadlineMissInvocations.filter((item) => item.outcome === 'ADMITTED').length;
  const answerTerminalAttempts = invocations.filter((item) => item.terminalOutcome === 'ANSWER');
  const completions = invocations.filter((item) => isConfirmedAnswer(item))
    .map((item) => BigInt(item.terminalNanos));
  const admittedAliases = new Set(admitted.map((item) => item.turnAlias));
  const outcomeCounts = countBy(invocations, (item) => item.outcome);
  const decisionRecordedCount = invocations.filter((item) => item.decisionRecorded).length;
  const decisionEvidence = {
    recordedCount: decisionRecordedCount,
    missingCount: invocations.length - decisionRecordedCount,
    complete: decisionRecordedCount === invocations.length,
  };
  const rateLimitStatusCounts = countBy(invocations, (item) => item.rateLimitStatus);
  const noticeStatusCounts = countBy(invocations, (item) => item.noticeStatus);
  const requestLinkReconciliation = summarizeRequestLinks(invocations);
  const noFirstDispatchByOutcome = Object.fromEntries(Object.entries(outcomeCounts).sort(([a], [b]) => a.localeCompare(b)).map(([outcome, count]) => {
    const outcomeInvocations = invocations.filter((item) => item.outcome === outcome);
    const missing = outcomeInvocations.filter((item) => item.firstDispatchNanos === '-1').length;
    return [outcome, { invocationCount: count, noFirstDispatchCount: missing, noFirstDispatchRate: ratio(missing, count) }];
  }));
  const terminalLifecycles = ['NO_TERMINAL_OBSERVED', 'TERMINAL_BEFORE_1000MS', 'TERMINAL_AT_1000MS', 'TERMINAL_AFTER_1000MS'];
  const terminalLifecycleOf = (item) => item.terminalOutcome === null ? 'NO_TERMINAL_OBSERVED'
    : BigInt(item.terminalNanos) > 1_000_000_000n ? 'TERMINAL_AFTER_1000MS'
      : BigInt(item.terminalNanos) === 1_000_000_000n ? 'TERMINAL_AT_1000MS' : 'TERMINAL_BEFORE_1000MS';
  const noFirstDispatchByTerminalLifecycle = Object.fromEntries(terminalLifecycles.map((lifecycle) => {
    const lifecycleInvocations = invocations.filter((item) => terminalLifecycleOf(item) === lifecycle);
    const missing = lifecycleInvocations.filter((item) => item.firstDispatchNanos === '-1').length;
    return [lifecycle, {
      invocationCount: lifecycleInvocations.length,
      noFirstDispatchCount: missing,
      noFirstDispatchRate: ratio(missing, lifecycleInvocations.length),
      knownNoDispatchDeadlineMissCount: lifecycle === 'TERMINAL_AFTER_1000MS' ? missing : 0,
    }];
  }));
  const firstDispatchByDisposition = summarizeLatencyGroups(firstDispatchInvocations, (item) => item.firstReplyDisposition);
  const latestNumericSnapshot = numericSnapshots.at(-1) ?? null;
  const latestCounters = latestNumericSnapshot?.counters
    ? Object.fromEntries(COUNTER_NAMES.map((name, index) => [name, latestNumericSnapshot.counters[index]])) : null;
  const aggregateDeadlineMissCount = latestCounters?.FIRST_REPLY_DEADLINE_MISSES ?? null;
  const aggregateCapturedInvocationCount = latestCounters?.INVOCATIONS_CAPTURED ?? null;
  const interactionEvidenceDroppedCount = latestCounters?.INTERACTION_EVIDENCE_DROPPED ?? null;
  const numericSourceCount = new Set(numericSnapshots.map((item) => item.sourceSha256)).size;
  let deadlineMissReconciliationStatus = 'unavailable';
  if (aggregateDeadlineMissCount !== null) {
    if (numericSourceCount > 1) deadlineMissReconciliationStatus = 'not_comparable_multiple_numeric_sources';
    else if (aggregateDeadlineMissCount === knownDeadlineMissCount) deadlineMissReconciliationStatus = 'matches_linked_all_outcome_deadline_misses';
    else if (aggregateDeadlineMissCount > knownDeadlineMissCount) deadlineMissReconciliationStatus = 'aggregate_exceeds_linked_all_outcome_deadline_misses';
    else deadlineMissReconciliationStatus = 'linked_all_outcome_deadline_misses_exceed_aggregate';
  }
  const deadlineMissEvidenceIncomplete = captureEvidenceMissing.length > 0 || (deadlineMissReconciliationStatus !== 'unavailable' &&
    deadlineMissReconciliationStatus !== 'matches_linked_all_outcome_deadline_misses') ||
    (interactionEvidenceDroppedCount !== null && interactionEvidenceDroppedCount > 0);
  const invocationEvidenceIncomplete = aggregateCapturedInvocationCount !== null &&
    (numericSourceCount > 1 || aggregateCapturedInvocationCount !== invocations.length || interactionEvidenceDroppedCount > 0);
  const numericEvidenceIncomplete = deadlineMissEvidenceIncomplete || invocationEvidenceIncomplete ||
    !decisionEvidence.complete || requestLinkReconciliation.unresolvedCoalescedCount > 0 ||
    requestLinkReconciliation.unresolvedRepeatGuardCount > 0 ||
    requestLinkReconciliation.missingLinkTimestampCount > 0;
  const hasAggregateDispatchSample = (latestNumericSnapshot?.firstPublicDispatchHistograms
    ?.flat(2).some((count) => count > 0) ?? false) ||
    (latestNumericSnapshot?.counters?.[32] ?? 0) > 0 || (latestNumericSnapshot?.counters?.[33] ?? 0) > 0;
  const actualMaximumDispatchNanos = hasAggregateDispatchSample
    ? latestNumericSnapshot.maxFirstPublicDispatchNanos : null;
  const perPlayer = {};
  for (const item of invocations) {
    if (item.playerAlias === null) continue;
    const row = perPlayer[item.playerAlias] ?? {
      invocations: 0, admitted: 0, firstDispatches: 0, firstReplyOnTime: 0, firstReplyLate: 0,
      noDispatchDeadlines: 0, noDispatchAtDeadline: 0, pendingFirstReplies: 0, receiptEvidenceMissing: 0,
      terminals: 0, answerTerminals: 0, technicalTerminalFailures: 0,
    };
    row.invocations += 1;
    if (item.outcome === 'ADMITTED') row.admitted += 1;
    if (item.firstDispatchNanos !== '-1') row.firstDispatches += 1;
    if (item.firstReplyResult === 'ON_TIME') row.firstReplyOnTime += 1;
    if (item.firstReplyResult === 'LATE') row.firstReplyLate += 1;
    if (noDispatchAtOrAfterDeadline(item) && item.firstDispatchNanos === '-1') row.noDispatchDeadlines += 1;
    if (noDispatchAtOrAfterDeadline(item)) row.noDispatchAtDeadline += 1;
    if (item.firstReplyResult === 'PENDING') row.pendingFirstReplies += 1;
    if (item.firstReplyResult === 'EVIDENCE_MISSING') row.receiptEvidenceMissing += 1;
    if (item.terminalNanos !== '-1') row.terminals += 1;
    if (item.terminalOutcome === 'ANSWER') row.answerTerminals += 1;
    if (item.terminalOutcome !== null && !['ANSWER', 'UNRESOLVED', 'CANCELLED'].includes(item.terminalOutcome)) {
      row.technicalTerminalFailures += 1;
    }
    perPlayer[item.playerAlias] = row;
  }
  const progressCounts = { dispatched: 0, failed: 0, missed: 0, unknown: 0 };
  for (const item of invocations) for (const progress of item.progress) progressCounts[progress.status] += 1;

  const classificationsByTurn = new Map();
  const workClassAssignmentsByTurn = new Map();
  const reviewRoles = new Set(reviews.map((entry) => entry.reviewerRole));
  const ratingDistributions = Object.create(null);
  const scoreDisagreements = Object.create(null);
  for (const dimension of ['personaPremiseFit', 'contextCoherence', 'answerQuality', 'discoverability', 'enjoyment']) {
    ratingDistributions[dimension] = countBy(reviews, (entry) => String(entry.ratings[dimension]));
    scoreDisagreements[dimension] = new Set(reviews.map((entry) => entry.ratings[dimension])).size > 1;
  }
  let clientDisplayObserved = 0;
  let clientDisplayUnknown = 0;
  for (const review of reviews) {
    for (const item of review.terminalClassifications) {
      const set = classificationsByTurn.get(item.turnAlias) ?? new Set();
      set.add(item.classification);
      classificationsByTurn.set(item.turnAlias, set);
      const assignments = workClassAssignmentsByTurn.get(item.turnAlias) ?? new Set();
      assignments.add(item.scenarioId === undefined || item.scenarioId === null
        ? 'unknown' : SCENARIO_WORK_CLASSES.get(item.scenarioId) ?? 'unknown');
      workClassAssignmentsByTurn.set(item.turnAlias, assignments);
      if (item.clientDisplay?.observation === 'displayed' || item.clientDisplay?.observation === 'not_displayed') clientDisplayObserved += 1;
      else clientDisplayUnknown += 1;
    }
  }
  const workClassByTurn = new Map();
  for (const item of invocations) {
    workClassByTurn.set(item.turnAlias, resolveWorkClass(workClassAssignmentsByTurn.get(item.turnAlias)));
  }
  const workClassCompletion = summarizeWorkClassCompletion(invocations, workClassByTurn);
  const ordinaryCompletions = invocations.filter((item) => workClassByTurn.get(item.turnAlias) === 'ordinary' &&
      isConfirmedAnswer(item))
    .map((item) => BigInt(item.terminalNanos));
  const workClassAssignmentCounts = Object.fromEntries(WORK_CLASS_BUCKETS.map((workClass) => [workClass,
    invocations.filter((item) => workClassByTurn.get(item.turnAlias) === workClass).length]));
  const unanimous = { relevant_answer: 0, explained_limitation: 0, disagreement: 0 };
  for (const [turnAlias, values] of classificationsByTurn) {
    if (!admittedAliases.has(turnAlias)) continue;
    if (values.size > 1) unanimous.disagreement += 1;
    else {
      const invocation = latestByTurn.get(turnAlias);
      if (invocation?.terminalOutcome !== 'ANSWER') continue;
      if (values.has('relevant_answer')) unanimous.relevant_answer += 1;
      else if (values.has('explained_limitation')) unanimous.explained_limitation += 1;
    }
  }
  const classifiedAdmitted = [...admittedAliases].filter((turnAlias) => classificationsByTurn.has(turnAlias)).length;
  const acDecision = reviews.find((entry) => entry.reviewerRole === 'independent_evaluator')?.decision ?? null;
  const unclassifiedAdmittedCount = admitted.length - classifiedAdmitted;
  const blockers = acceptanceBlockers({
    run, session, invocations, admitted, reviews, reviewRoles, unanimous,
    clientDisplayObserved, clientDisplayUnknown, unclassifiedAdmittedCount, numericEvidenceIncomplete,
  });
  if (workClassCompletion['tool-heavy'].admittedCount > 0 && scenarios.workClassPolicy?.toolHeavyScenarioMayRun !== true) {
    blockers.push('Tool-heavy work is BLOCKED: no publicly supported and approved tool-heavy execution path is available; these samples do not qualify that workload.');
  }

  return {
    schemaVersion: 3,
    runAlias: 'run-local',
    provenance: run.provenance,
    acceptance: { status: 'NOT_ACCEPTED', automaticPass: false, acDecision, blockers },
    provenanceDetails: cloneJson(run.metadata),
    artifactHashes: [...run.metadata.artifactHashes],
    sourceHashes: [...new Set(sourceHashes)],
    numeric: {
      snapshotCount: numericSnapshots.length,
      invocationCount: invocations.length,
      admittedCount: admitted.length,
      outcomeCounts,
      decisionEvidence,
      rateLimitStatusCounts,
      noticeStatusCounts,
      requestLinkReconciliation,
      terminalOutcomeCounts: countBy(invocations, (item) => item.terminalOutcome ?? 'NO_TERMINAL'),
      terminalDeliveryResultCounts: countBy(invocations, (item) => item.terminalOutcome === null
        ? 'NO_TERMINAL' : item.terminalDeliveryResult ?? 'NOT_RECORDED'),
      answerTerminalAttemptDeliveryResultCounts: countBy(answerTerminalAttempts,
        (item) => item.terminalDeliveryResult ?? 'NOT_RECORDED'),
      terminalFailureCount: invocations.filter((item) => item.terminalOutcome !== null &&
        !['ANSWER', 'UNRESOLVED', 'CANCELLED'].includes(item.terminalOutcome)).length,
      firstDispatch: {
        populationCount: invocations.length,
        sampleCount: firstDispatch.length,
        coverageRate: ratio(firstDispatch.length, invocations.length),
        admittedPopulationCount: admitted.length,
        admittedSampleCount: admittedFirstDispatch.length,
        admittedCoverageRate: ratio(admittedFirstDispatch.length, admitted.length),
        admittedMedianMs: latencySummary(admittedFirstDispatch).medianMs,
        admittedP95Ms: latencySummary(admittedFirstDispatch).p95Ms,
        admittedMaxMs: admittedFirstDispatch.length ? toMs(maxBigInt(admittedFirstDispatch)) : null,
        conditionalOnMeasuredDispatch: true,
        medianMs: latencySummary(firstDispatch).medianMs,
        p95Ms: latencySummary(firstDispatch).p95Ms,
        maxMs: firstDispatch.length ? toMs(maxBigInt(firstDispatch)) : null,
        maxScope: 'maximum among linked invocations with a measured successful first public dispatch only; aggregate counters do not provide a maximum',
        over1000msCount: firstDispatchOver1000.length,
        over1000msRate: firstDispatch.length ? firstDispatchOver1000.length / firstDispatch.length : null,
        over1000msOverAllInvocations: ratio(firstDispatchOver1000.length, invocations.length),
        byDisposition: firstDispatchByDisposition,
      },
      noFirstDispatch: {
        count: noFirstDispatchInvocations.length,
        rateOverAllInvocations: ratio(noFirstDispatchInvocations.length, invocations.length),
        admittedCount: admittedNoFirstDispatch.length,
        rateOverAdmitted: ratio(admittedNoFirstDispatch.length, admitted.length),
        pendingCount: pendingFirstReplies.length,
        noDispatchSettledCount: noDispatchSettled.length,
        evidenceMissingCount: captureEvidenceMissing.length,
        byOutcome: noFirstDispatchByOutcome,
        byTerminalLifecycle: noFirstDispatchByTerminalLifecycle,
        exposureLimit: 'PENDING records have no successful first dispatch and are not treated as a settled no-dispatch outcome or deadline miss. EVIDENCE_MISSING has no valid receipt clock and cannot be timed.',
      },
      firstReplyDeadline: {
        thresholdMs: 1000,
        comparison: 'successful dispatch at exactly 1,000 ms is on time; absent dispatch settled at the 1,000 ms deadline is a miss',
        onTimeCount: invocations.filter((item) => item.firstReplyResult === 'ON_TIME').length,
        lateDispatchCount: firstDispatchOver1000.length,
        measuredOver1000msCount: firstDispatchOver1000.length,
        noDispatchDeadlineCount: noDispatchDeadlineMisses.length,
        noDispatchAtDeadlineCount: noDispatchAtDeadline.length,
        noDispatchSettlementCount: noDispatchSettlements.length,
        noDispatchSettledBeforeDeadlineCount: noDispatchSettledBeforeDeadline.length,
        eventualNoDispatchCount: eventualNoDispatch,
        lateDispatchAfterDeadlineSettlementCount: firstDispatchOver1000.filter((item) =>
          noDispatchAtOrAfterDeadline(item)).length,
        knownMissCount: knownDeadlineMissCount,
        knownMissRateOverAllInvocations: ratio(knownDeadlineMissCount, invocations.length),
        admittedKnownMissCount: admittedKnownDeadlineMissCount,
        knownMissRateOverAdmitted: ratio(admittedKnownDeadlineMissCount, admitted.length),
        noDispatchSettledCount: noDispatchSettled.length,
        pendingCount: pendingFirstReplies.length,
        captureEvidenceMissingCount: captureEvidenceMissing.length,
        aggregateCounterMissCount: aggregateDeadlineMissCount,
        reconciliationStatus: deadlineMissReconciliationStatus,
        aggregateCounterMatchesMeasuredLateDispatches: aggregateDeadlineMissCount === null || numericSourceCount > 1
          ? null : aggregateDeadlineMissCount === firstDispatchOver1000.length,
        aggregateCounterNumericallyMatchesKnownMissTotal: aggregateDeadlineMissCount === null || numericSourceCount > 1
          ? null : aggregateDeadlineMissCount === knownDeadlineMissCount,
        actualMaximumDispatchNanos,
        actualMaximumDispatchMs: actualMaximumDispatchNanos === null ? null : toMs(actualMaximumDispatchNanos),
        actualMaximumScope: 'direct maxFirstPublicDispatchNanos metric; not a histogram bucket ceiling; may include invocations not linked in this bounded evaluation export',
        outcomeCounts: countBy(invocations, (item) => item.firstReplyResult),
        evidenceIncomplete: deadlineMissEvidenceIncomplete,
        attributionLimit: 'A deadline miss is a linked invocation with either a measured first dispatch strictly after 1,000 ms or a no-dispatch settlement at/after the deadline. If both events occur for one invocation, it counts once. Eventual no-dispatch is reported separately; pending and receipt-evidence-missing outcomes remain distinct, and no t1 latency is inferred.',
      },
      responseCompleteness: {
        allInvocationCount: invocations.length,
        admittedCount: admitted.length,
        answerCompletedCount: completions.length,
        answerTerminalAttemptCount: answerTerminalAttempts.length,
        unconfirmedAnswerAttemptCount: answerTerminalAttempts.length - completions.length,
        answerTerminalAttemptDeliveryResultCounts: countBy(answerTerminalAttempts,
          (item) => item.terminalDeliveryResult ?? 'NOT_RECORDED'),
        answerCompletion: latencySummary(completions),
        answerCompletionLatencyBasis: 'terminalNanos: capture-to-terminal-settlement; terminalAtNanos remains a separate event timestamp',
        answerCompletionRateOverAllInvocations: ratio(completions.length, invocations.length),
        answerCompletionRateOverAdmitted: ratio(completions.length, admitted.length),
        admittedWithoutAnswerCount: admitted.length - completions.length,
        nonAdmittedCount: nonAdmitted.length,
        nonAdmittedOutcomeCounts: countBy(nonAdmitted, (item) => item.outcome),
        answerDefinition: 'An admitted invocation with terminalOutcome ANSWER, terminalDeliveryResult SUBMITTED, and measured terminalNanos; a first reply or acknowledgement is not proof of terminal delivery, and a direct submitted terminal answer does not require an earlier acknowledgement.',
      },
      evidenceCoverage: {
        numericSourceCount,
        aggregateCapturedInvocationCount,
        linkedInvocationCount: invocations.length,
        interactionEvidenceDroppedCount,
        deadlineMissReconciliationStatus,
        incomplete: numericEvidenceIncomplete,
      },
      ordinaryCompletion: {
        ...latencySummary(ordinaryCompletions),
        admittedCount: workClassCompletion.ordinary.admittedCount,
        answerCompletionRateOverAdmitted: workClassCompletion.ordinary.answerCompletionRateOverAdmitted,
        provisionalTargets: { medianMs: 5000, p95Ms: 10000, acceptanceGate: false },
      },
      workClassAssignmentMethod: 'Derived only from the selected catalog scenarioId on human terminal classifications; no assignment is inferred from response content, timing, or an ability request.',
      answerCompletionByWorkClass: workClassCompletion,
      workClassAssignmentCounts,
      progressCounts,
      perPlayer,
      humanClassifications: {
        reviewedTurnCount: classificationsByTurn.size,
        unclassifiedAdmittedCount,
        unanimousRelevantAnswers: unanimous.relevant_answer,
        unanimousExplainedLimitations: unanimous.explained_limitation,
        disagreements: unanimous.disagreement,
        relevantAnswersOverAdmitted: ratio(unanimous.relevant_answer, admitted.length),
        explainedLimitationsOverAdmitted: ratio(unanimous.explained_limitation, admitted.length),
        classifications: classificationCounts(reviews),
      },
      clientDisplay: { observedRecords: clientDisplayObserved, unknownRecords: clientDisplayUnknown },
      latestCounters,
    },
    humanReview: {
      reviewerRoles: [...reviewRoles].sort(),
      scoreDistributions: ratingDistributions,
      reasonCodesByReviewer: reviews.map((entry) => ({
        reviewerRole: entry.reviewerRole,
        codes: renderSelectedCodes(entry.reasonCodes, REVIEW_REASON_RENDERINGS),
      })),
      weaknessCodesByReviewer: reviews.map((entry) => ({
        reviewerRole: entry.reviewerRole,
        codes: renderSelectedCodes(entry.weaknessCodes, REVIEW_WEAKNESS_RENDERINGS),
      })),
      reasonCodeCounts: codeCounts(reviews.flatMap((entry) => entry.reasonCodes)),
      weaknessCodeCounts: codeCounts(reviews.flatMap((entry) => entry.weaknessCodes)),
      scoresByReviewer: reviews.map((entry) => ({ reviewerRole: entry.reviewerRole, ratings: entry.ratings })),
      scoreDisagreements,
      missingReviewerRoles: expectedReviewRoles(session).filter((role) => !reviewRoles.has(role)),
      decisions: reviews.filter((item) => item.reviewerRole === 'independent_evaluator').map((item) => ({
        decision: item.decision,
        reasonCodes: renderSelectedCodes(item.decisionReasonCodes, DECISION_REASON_RENDERINGS),
        prioritizedWeaknessCodes: renderSelectedCodes(item.prioritizedWeaknessCodes, REVIEW_WEAKNESS_RENDERINGS),
      })),
      disagreementsAndMissingScores: true,
    },
    privacyLimits: {
      humanReviewInput: 'Only closed-vocabulary codes and bounded structured ratings/observations are accepted; free-form prose fields are rejected.',
      nameRedaction: 'No general person-name detection or anonymization guarantee is made; arbitrary review prose is not accepted.',
      otherObservedIssue: 'The other_observed_issue category is reported without details; details are not collected by this tool.',
      covertEncoding: 'Intentional encoding through permitted codes, ratings, or other structured values cannot be detected or ruled out.',
    },
    entries: run.entries.map((entry) => redactEntry(entry)),
    scenarios: scenarios.scenarios,
  };
}

export function renderMarkdown(report) {
  const numeric = report.numeric;
  const blockerLines = report.acceptance.blockers.length
    ? report.acceptance.blockers.map((item) => `- ${item}`).join('\n') : '- No structural blockers found; human acceptance is still required.';
  const first = numeric.firstDispatch;
  const noFirst = numeric.noFirstDispatch;
  const deadline = numeric.firstReplyDeadline;
  const completeness = numeric.responseCompleteness;
  const completion = numeric.ordinaryCompletion;
  const lines = [
    '# CONV-06 offline evaluation report',
    '',
    `- Run alias: ${report.runAlias}`,
    `- Provenance: ${report.provenance}`,
    '- Acceptance: **NOT ACCEPTED** (the tool never grants an automatic quality pass)',
    '',
    '## Engineering and numeric evidence',
    '',
    `- Invocations: ${numeric.invocationCount}; admitted: ${numeric.admittedCount}.`,
    `- Dispositions: ${formatCounts(numeric.outcomeCounts)}.`,
    `- Admission decision evidence: ${numeric.decisionEvidence.recordedCount}/${numeric.invocationCount} decisions have a recorded decision time; rate-limit states: ${formatCounts(numeric.rateLimitStatusCounts)}; notice states: ${formatCounts(numeric.noticeStatusCounts)}.`,
    `- Request-link reconciliation: ${numeric.requestLinkReconciliation.linkedInvocationCount} linked rows across ${numeric.requestLinkReconciliation.requestGroupCount} request groups; coalesced ${numeric.requestLinkReconciliation.coalescedCount}, resolved to one admitted anchor ${numeric.requestLinkReconciliation.resolvedCoalescedCount}, unresolved ${numeric.requestLinkReconciliation.unresolvedCoalescedCount}; repeat-guard refusals ${numeric.requestLinkReconciliation.repeatGuardRefusedCount}, resolved ${numeric.requestLinkReconciliation.resolvedRepeatGuardCount}, unresolved ${numeric.requestLinkReconciliation.unresolvedRepeatGuardCount}; missing link time ${numeric.requestLinkReconciliation.missingLinkTimestampCount}. Repeats inherit only their original request link and never count as another turn.`,
    `- Conditional observed first-public-dispatch latency (measured dispatches only): n=${first.sampleCount}/${first.populationCount} all invocations; median ${formatMs(first.medianMs)}; p95 ${formatMs(first.p95Ms)}; maximum ${formatMs(first.maxMs)}; >1,000 ms ${first.over1000msCount}/${first.sampleCount} measured (${formatRate(first.over1000msRate)} conditional rate; ${formatRatio(first.over1000msOverAllInvocations)} over all invocations). Among admitted: n=${first.admittedSampleCount}/${first.admittedPopulationCount}; median ${formatMs(first.admittedMedianMs)}; maximum ${formatMs(first.admittedMaxMs)}. Maximum uses linked measured dispatch records only; an aggregate counter supplies no maximum.`,
    `- First-public-dispatch samples by dispatch disposition: ${formatDispositionLatency(first.byDisposition)}.`,
    `- No first public dispatch: ${formatCountRate(noFirst.count, noFirst.rateOverAllInvocations)} all invocations; ${formatCountRate(noFirst.admittedCount, noFirst.rateOverAdmitted)} admitted. Pending: ${noFirst.pendingCount}; settled without dispatch: ${noFirst.noDispatchSettledCount}; receipt evidence missing: ${noFirst.evidenceMissingCount}. By interaction outcome: ${formatNoDispatchOutcomes(noFirst.byOutcome)}.`,
    `- No-dispatch terminal lifecycle: ${formatNoDispatchLifecycles(noFirst.byTerminalLifecycle)}. A missing terminal leaves deadline exposure unknown; pending invocations are not counted as deadline misses.`,
    `- First-reply results: ${formatCounts(deadline.outcomeCounts)}. Pending is not a latency sample; missing receipt evidence has no valid deadline clock.`,
    `- First-reply deadline misses: ${deadline.knownMissCount} unique invocations (${deadline.measuredOver1000msCount} late first dispatches; ${deadline.noDispatchAtDeadlineCount} no-dispatch observations at/after the deadline, with overlap counted once; ${deadline.eventualNoDispatchCount} settled no-dispatch outcomes; ${noFirst.count} have no successful first dispatch by export, including pending/unknown outcomes); ${formatRatio(deadline.knownMissRateOverAllInvocations)} of all invocations and ${formatRatio(deadline.knownMissRateOverAdmitted)} of admitted. Direct actual maximum dispatch latency: ${formatMs(deadline.actualMaximumDispatchMs)}; aggregate counter: ${formatUnknown(deadline.aggregateCounterMissCount)}; all-outcome reconciliation: ${deadline.reconciliationStatus}${deadline.evidenceIncomplete ? ' (numeric evidence incomplete)' : ''}; numerical equality with known linked misses: ${formatBooleanOrUnknown(deadline.aggregateCounterNumericallyMatchesKnownMissTotal)}. The maximum is a direct metric, not a histogram bucket ceiling.`,
    `- Confirmed technical answer completion: ${completeness.answerCompletedCount}/${completeness.allInvocationCount} all invocations (${formatRate(completeness.answerCompletionRateOverAllInvocations.rate)}); ${completeness.answerCompletedCount}/${completeness.admittedCount} admitted (${formatRate(completeness.answerCompletionRateOverAdmitted.rate)}). ANSWER terminal attempts: ${completeness.answerTerminalAttemptCount}; delivery results: ${formatCounts(completeness.answerTerminalAttemptDeliveryResultCounts)}; unconfirmed attempts: ${completeness.unconfirmedAnswerAttemptCount}. Capture-to-terminal-settlement latency from terminalNanos: n=${completeness.answerCompletion.sampleCount}; median ${formatMs(completeness.answerCompletion.medianMs)}; p95 ${formatMs(completeness.answerCompletion.p95Ms)}; maximum ${formatMs(completeness.answerCompletion.maxMs)}. terminalAtNanos remains a separate event timestamp. Admitted without a confirmed submitted ANSWER: ${completeness.admittedWithoutAnswerCount}; non-admitted outcomes remain separate (${formatCounts(completeness.nonAdmittedOutcomeCounts)}) and are never successful answer completions.`,
    `- Ordinary scenario-assigned confirmed answer completion: n=${completion.sampleCount}/${numeric.ordinaryCompletion.admittedCount} admitted (${formatRate(numeric.ordinaryCompletion.answerCompletionRateOverAdmitted.rate)}); median ${formatMs(completion.medianMs)}; p95 ${formatMs(completion.p95Ms)}; maximum ${formatMs(completion.maxMs)} (nearest rank). Only terminalDeliveryResult SUBMITTED qualifies. Provisional 5,000/10,000 ms targets are descriptive only.`,
    `- Capture-to-confirmed-ANSWER completion by approved scenario work class: ${formatWorkClassCompletion(numeric.answerCompletionByWorkClass)}. Class is derived only from each human terminal classification's selected catalog scenarioId, never response speed, wording, or requested ability; unassigned/ambiguous records remain unknown and conflicting classes remain mixed. Human usefulness judgments do not establish technical delivery.`,
    `- Terminal outcomes: ${formatCounts(numeric.terminalOutcomeCounts)}; terminal delivery results: ${formatCounts(numeric.terminalDeliveryResultCounts)}; technical terminal failures: ${numeric.terminalFailureCount}.`,
    `- Progress records: ${formatCounts(numeric.progressCounts)}.`,
    `- Per-player invocation counts: ${formatPlayerCounts(numeric.perPlayer)}.`,
    '',
    'Dispatch latency is conditional on an observed successful public dispatch and is not response completeness, client display, or answer quality. Missing first dispatches and non-admitted outcomes remain in invocation denominators. Server dispatch is not client display. Human display observations are reported separately; unmeasured intervals remain unknown.',
    `- Client display observations: ${numeric.clientDisplay.observedRecords}; unknown: ${numeric.clientDisplay.unknownRecords}.`,
    '',
    '## Human judgments',
    '',
    `- Unanimous relevant answers: ${numeric.humanClassifications.unanimousRelevantAnswers}/${numeric.admittedCount} admitted requests.`,
    `- Unanimous explained limitations: ${numeric.humanClassifications.unanimousExplainedLimitations}/${numeric.admittedCount} admitted requests.`,
    `- Classification disagreements: ${numeric.humanClassifications.disagreements}; classifications: ${formatCounts(numeric.humanClassifications.classifications)}.`,
    `- Admitted requests without a human terminal classification: ${numeric.humanClassifications.unclassifiedAdmittedCount}.`,
    `- Independent evaluator decision: ${report.humanReview.decisions[0]?.decision ?? 'missing'}.`,
    '',
    'Scores remain independent, descriptive judgments; missing scores and disagreements are not averaged into a pass. The independent evaluator makes the quality decision; the tool never judges quality.',
    `- Score disagreements by dimension: ${formatCounts(report.humanReview.scoreDisagreements)}.`,
    `- Coded review reasons: ${formatReviewerCodes(report.humanReview.reasonCodesByReviewer)}.`,
    `- Coded weaknesses: ${formatReviewerCodes(report.humanReview.weaknessCodesByReviewer)}.`,
    `- Independent decision reason codes: ${formatRenderedCodes(report.humanReview.decisions[0]?.reasonCodes ?? [])}.`,
    '',
    '## Acceptance blockers',
    '',
    blockerLines,
    '',
    '## Provenance hashes',
    '',
    `- Artifacts: ${report.artifactHashes.length ? report.artifactHashes.join(', ') : 'missing'}.`,
    `- Sources: ${report.sourceHashes.length ? report.sourceHashes.join(', ') : 'missing'}.`,
    '',
    '## Privacy limits',
    '',
    'Review input accepts closed-vocabulary codes and bounded structured values only; arbitrary review prose is rejected before it can be stored or exported. No general person-name redaction or anonymization guarantee is made.',
    'The other_observed_issue category has no free-text detail. Intentional covert encoding through allowed codes, ratings, or other structured values cannot be detected or ruled out.',
    '',
  ];
  return lines.join('\n');
}

export async function writeReport(runPath, outDirectory) {
  const run = await loadRun(runPath);
  const report = buildReport(run);
  const directory = await resolveSafeTarget(outDirectory, { mustNotExist: true });
  await mkdir(directory, { mode: 0o700 });
  await chmodOwnerOnly(directory, 0o700);
  const json = `${JSON.stringify(report, null, 2)}\n`;
  const markdown = `${renderMarkdown(report)}\n`;
  if (Buffer.byteLength(json, 'utf8') + Buffer.byteLength(markdown, 'utf8') > MAX_BYTES) fail('report-size-limit');
  await createNewPrivateFile(join(directory, 'report.json'), json);
  await createNewPrivateFile(join(directory, 'report.md'), markdown);
  return { directory, status: report.acceptance.status, blockers: report.acceptance.blockers.length };
}

/** Creates a bounded numeric-only operational report, separate from human evaluation runs. */
export async function writeOperationalReport(sourcePath, outDirectory) {
  const source = await readPrivateFileSnapshot(sourcePath, MAX_OPERATIONAL_SOURCE_BYTES);
  const report = buildOperationalReport(source.bytes);
  const json = `${JSON.stringify(report, null, 2)}\n`;
  const markdown = `${renderOperationalMarkdown(report)}\n`;
  if (Buffer.byteLength(json, 'utf8') + Buffer.byteLength(markdown, 'utf8') > MAX_OPERATIONAL_REPORT_BYTES) {
    fail('operational-report-size-limit');
  }
  const directory = await resolveSafeTarget(outDirectory, { mustNotExist: true });
  await mkdir(directory, { mode: 0o700 });
  await chmodOwnerOnly(directory, 0o700);
  await createNewPrivateFile(join(directory, 'report.json'), json);
  await createNewPrivateFile(join(directory, 'report.md'), markdown);
  return {
    directory,
    status: report.status,
    sourceSha256: report.sourceSha256,
    sourceInvocationCount: report.sourceInvocationCount,
    retainedInvocationCount: report.retainedInvocationCount,
    observedInvocationCount: report.observedInvocationCount,
    omittedInvocationCount: report.omittedInvocationCount,
    declaredOmittedInvocationCount: report.declaredOmittedInvocationCount,
  };
}

export function buildOperationalReport(sourceBytes) {
  const parsed = parseOperationalStream(sourceBytes);
  const latest = parsed.latestDiagnostic;
  const sourceInvocationCount = latest?.sourceInvocationCount ?? null;
  const retainedInvocationCount = latest?.retainedInvocationCount ?? null;
  const observedInvocationCount = latest?.observedInvocationCount ?? null;
  const omittedInvocationCount = sourceInvocationCount === null || observedInvocationCount === null ||
    sourceInvocationCount < observedInvocationCount
    ? null : sourceInvocationCount - observedInvocationCount;
  const incompleteReasons = [...new Set([
    ...parsed.failures,
    ...parsed.snapshots.flatMap((snapshot) => snapshot.incompleteReasons),
  ])].sort();
  const complete = parsed.snapshots.length > 0 && incompleteReasons.length === 0;
  return {
    reportType: 'operational_numeric_report',
    schemaVersion: 1,
    sourceFormatVersion: parsed.sourceFormatVersion ?? OPERATIONAL_FORMAT_VERSION,
    sourceFormatVersions: parsed.sourceFormatVersions,
    status: complete ? 'COMPLETE' : 'INCOMPLETE',
    sourceSha256: sha256(sourceBytes),
    sourceInvocationCount,
    retainedInvocationCount,
    observedInvocationCount,
    omittedInvocationCount,
    declaredOmittedInvocationCount: latest?.declaredOmittedInvocationCount ?? null,
    snapshotCount: parsed.snapshots.length,
    snapshotAggregation: 'Snapshots are reported independently. Top-level declarations refer to the last unique manifest considered; observed counts include only distinct validated rows actually scanned, and repeated rows across captures are never summed.',
    incompleteReasons,
    snapshots: parsed.snapshots,
    humanEvaluation: {
      status: 'NOT_PERFORMED',
      coversOperationalSource: false,
      maxTurns: MAX_INVOCATIONS,
      maxPlayerAliases: MAX_PLAYERS,
      maxRunAndReportBytes: MAX_BYTES,
      statement: 'This operational numeric report is not a human evaluation. The separate human-evaluation path remains limited to 64 turns, 3 anonymous players, and 1 MiB; it does not represent a larger operational load.',
    },
    qualityDecision: { status: 'NOT_EVALUATED', automaticPass: false },
  };
}

function parseOperationalStream(sourceBytes) {
  const failures = new Set();
  const snapshots = [];
  const lineResult = operationalLineBuffers(sourceBytes);
  for (const failure of lineResult.failures) failures.add(failure);
  const snapshotStates = new Map();
  const sourceFormatVersions = new Set();
  let latestDiagnostic = null;
  let cursor = 0;

  while (cursor < lineResult.lines.length) {
    const line = lineResult.lines[cursor++];
    let value;
    try {
      value = parseOperationalLine(line);
    } catch (error) {
      failures.add(operationalFailureCode(error));
      failures.add('operational-source-trailing-data');
      continue;
    }

    if (value?.type === 'numeric_snapshot_chunk') {
      const state = snapshotStates.get(value.snapshotId);
      if (state) {
        noteLateOperationalChunk(state, value, line, failures);
      } else {
        failures.add('operational-chunk-snapshot-mismatch');
        failures.add('operational-source-trailing-data');
      }
      continue;
    }

    if (value?.type !== 'numeric_snapshot_manifest') {
      failures.add('operational-source-trailing-data');
      continue;
    }

    const diagnostic = operationalDiagnostic(value);
    if (OPERATIONAL_SUPPORTED_FORMAT_VERSIONS.has(value.numericFormatVersion)) {
      sourceFormatVersions.add(value.numericFormatVersion);
    }
    if (!validateOperationalStreamLine(value)) {
      latestDiagnostic = diagnostic;
      failures.add('operational-manifest-invalid');
      continue;
    }

    const priorState = snapshotStates.get(value.snapshotId);
    if (priorState) {
      // A repeated manifest is not a new capture and must not replace the counts
      // or summary associated with the first declaration of this snapshot ID.
      invalidateOperationalSnapshot(priorState, 'operational-snapshot-id-duplicate', failures);
      continue;
    }

    latestDiagnostic = diagnostic;
    if (snapshotStates.size >= OPERATIONAL_MAX_SNAPSHOTS) {
      failures.add('operational-snapshot-count-limit');
      continue;
    }

    const state = {
      snapshotId: value.snapshotId,
      manifest: value,
      diagnostic,
      chunkBytesByIndex: new Map(),
      groupFailures: new Set(),
      summary: null,
    };
    snapshotStates.set(value.snapshotId, state);
    cursor = readOperationalSnapshotChunks(lineResult.lines, cursor, state, snapshotStates, failures);
    if (state.groupFailures.size === 0) {
      const rows = [...state.validChunksByIndex.entries()].sort(([left], [right]) => left - right)
        .flatMap(([, chunk]) => chunk.records);
      state.summary = summarizeOperationalSnapshot(value, rows);
      snapshots.push(state.summary);
    }
    state.validChunksByIndex.clear();
    for (const reason of state.groupFailures) failures.add(reason);
  }
  if (lineResult.failures.includes('operational-line-count-limit')) {
    // The bounded scanner could not inspect the remaining tail, so it cannot
    // rule out a late repeat for any known snapshot.
    for (const state of snapshotStates.values()) {
      invalidateOperationalSnapshot(state, 'operational-line-count-limit', failures);
    }
  }
  if (latestDiagnostic === null) failures.add('operational-manifest-missing');
  return {
    snapshots,
    failures: [...failures],
    latestDiagnostic,
    sourceFormatVersion: latestDiagnostic?.numericFormatVersion ?? null,
    sourceFormatVersions: [...sourceFormatVersions].sort((left, right) => left - right),
  };
}

function operationalDiagnostic(manifest) {
  return {
    numericFormatVersion: manifest?.numericFormatVersion ?? null,
    sourceInvocationCount: safeCounterOrNull(manifest?.sourceInvocationCount),
    retainedInvocationCount: safeCounterOrNull(manifest?.retainedInvocationCount),
    declaredOmittedInvocationCount: safeCounterOrNull(manifest?.omittedInvocationCount),
    // Set after chunk scanning. Invalid or over-limit manifests remain unknown
    // instead of claiming zero observed.
    observedInvocationCount: null,
  };
}

function readOperationalSnapshotChunks(lines, cursor, state, snapshotStates, failures) {
  const { manifest, diagnostic, snapshotId } = state;
  const expectedChunkCount = Math.ceil(manifest.retainedInvocationCount / OPERATIONAL_CHUNK_SIZE);
  const expectedObservedChunks = new Map();
  state.validChunksByIndex = expectedObservedChunks;
  if (manifest.chunkCount !== expectedChunkCount) {
    state.groupFailures.add('operational-manifest-chunk-count-mismatch');
  }

  const seenInvocationIds = new Set();
  for (let expectedIndex = 0; expectedIndex < manifest.chunkCount; expectedIndex += 1) {
    if (cursor >= lines.length) {
      state.groupFailures.add('operational-chunk-missing');
      break;
    }
    const chunkBytes = lines[cursor];
    let chunk;
    try {
      chunk = parseOperationalLine(chunkBytes);
    } catch (error) {
      cursor += 1;
      state.groupFailures.add(operationalFailureCode(error));
      continue;
    }
    if (chunk?.type === 'numeric_snapshot_manifest') {
      state.groupFailures.add('operational-snapshot-interrupted');
      break;
    }
    cursor += 1;
    if (!validateOperationalStreamLine(chunk) || chunk.type !== 'numeric_snapshot_chunk' ||
        chunk.numericFormatVersion !== manifest.numericFormatVersion) {
      state.groupFailures.add('operational-chunk-invalid');
      continue;
    }

    if (chunk.snapshotId !== snapshotId) {
      state.groupFailures.add('operational-chunk-snapshot-mismatch');
      const priorState = snapshotStates.get(chunk.snapshotId);
      if (priorState && priorState !== state) noteLateOperationalChunk(priorState, chunk, chunkBytes, failures);
      continue;
    }
    if (chunk.index !== expectedIndex) state.groupFailures.add('operational-chunk-order-invalid');

    const priorBytes = state.chunkBytesByIndex.get(chunk.index);
    if (priorBytes) {
      state.groupFailures.add(priorBytes.equals(chunkBytes)
        ? 'operational-chunk-duplicate' : 'operational-chunk-conflict');
      continue;
    }
    state.chunkBytesByIndex.set(chunk.index, chunkBytes);

    const expectedStart = chunk.index * OPERATIONAL_CHUNK_SIZE;
    const expectedLength = Math.max(0, Math.min(OPERATIONAL_CHUNK_SIZE,
      manifest.retainedInvocationCount - expectedStart));
    if (chunk.start !== expectedStart || chunk.records.length !== expectedLength || expectedLength === 0) {
      state.groupFailures.add('operational-chunk-range-invalid');
      continue;
    }

    const chunkIds = chunk.records.map((item) => item?.id);
    const localIds = new Set();
    let duplicateRow = false;
    for (const id of chunkIds) {
      if (!isUuid(id) || localIds.has(id) || seenInvocationIds.has(id)) duplicateRow = true;
      if (isUuid(id)) localIds.add(id);
    }
    if (duplicateRow) {
      state.groupFailures.add('operational-invocation-duplicate');
      continue;
    }

    try {
      validateOperationalRawSnapshot({
        numericFormatVersion: NUMERIC_FORMAT_VERSION,
        counters: manifest.counters.slice(0, COUNTER_NAMES.length),
        tickHistogram: manifest.tickHistogram,
        firstPublicDispatchHistograms: manifest.firstPublicDispatchHistograms,
        maxFirstPublicDispatchNanos: manifest.maxFirstPublicDispatchNanos,
        queueEvidence: manifest.queueEvidence,
        interactionRecords: chunk.records,
      }, manifest.numericFormatVersion);
    } catch (error) {
      state.groupFailures.add(operationalFailureCode(error));
      continue;
    }

    for (const id of localIds) seenInvocationIds.add(id);
    expectedObservedChunks.set(chunk.index, chunk);
  }

  for (let expectedIndex = 0; expectedIndex < expectedChunkCount; expectedIndex += 1) {
    if (!state.chunkBytesByIndex.has(expectedIndex)) state.groupFailures.add('operational-chunk-missing');
  }
  if (expectedObservedChunks.size !== expectedChunkCount || manifest.chunkCount !== expectedChunkCount) {
    state.groupFailures.add('operational-chunk-sequence-incomplete');
  }
  diagnostic.observedInvocationCount = [...expectedObservedChunks.values()]
    .reduce((count, chunk) => count + chunk.records.length, 0);
  return cursor;
}

function validateOperationalRawSnapshot(snapshot, streamFormatVersion = OPERATIONAL_FORMAT_VERSION) {
  const coreRows = snapshot.interactionRecords.map((item) => {
    const hasNoticeBatchFields = NOTICE_BATCH_FIELDS.every((field) => Object.hasOwn(item, field));
    const hasAnyNoticeBatchField = NOTICE_BATCH_FIELDS.some((field) => Object.hasOwn(item, field));
    if (hasAnyNoticeBatchField && !hasNoticeBatchFields) fail('numeric-notice-batch-fields-invalid');
    const hasQueueFields = OPERATIONAL_QUEUE_INVOCATION_FIELDS.every((field) => Object.hasOwn(item, field));
    const hasAnyQueueField = OPERATIONAL_QUEUE_INVOCATION_FIELDS.some((field) => Object.hasOwn(item, field));
    if (hasAnyQueueField !== hasQueueFields || (streamFormatVersion >= 4) !== hasQueueFields) {
      fail('numeric-queue-invocation-fields-invalid');
    }
    const hasCancellationFields = OPERATIONAL_CANCELLATION_INVOCATION_FIELDS.every((field) => Object.hasOwn(item, field));
    const hasAnyCancellationField = OPERATIONAL_CANCELLATION_INVOCATION_FIELDS.some((field) => Object.hasOwn(item, field));
    if (hasAnyCancellationField !== hasCancellationFields ||
        (streamFormatVersion === OPERATIONAL_FORMAT_VERSION) !== hasCancellationFields) {
      fail('numeric-cancellation-fields-invalid');
    }
    const operationalFields = [
      ...OPERATIONAL_RAW_INVOCATION_FIELDS,
      ...(hasQueueFields ? OPERATIONAL_QUEUE_INVOCATION_FIELDS : []),
      ...(hasCancellationFields ? OPERATIONAL_CANCELLATION_INVOCATION_FIELDS : []),
    ];
    exactFields(item, hasNoticeBatchFields ? [...operationalFields, ...NOTICE_BATCH_FIELDS] : operationalFields);
    const core = {
      ...Object.fromEntries(RAW_INVOCATION_FIELDS
        .filter((field) => field !== 'progress')
        .map((field) => [field, item[field]])),
      progress: item.progress.map(({ phase, status, latencyNanos }) => ({ phase, status, latencyNanos })),
    };
    if (hasNoticeBatchFields) {
      for (const field of NOTICE_BATCH_FIELDS) core[field] = item[field];
    }
    return core;
  });
  validateRawSnapshot({ ...snapshot, interactionRecords: coreRows });

  for (const item of snapshot.interactionRecords) {
    const captureMissing = item.captureProvenance === 'MISSING_PACKET_RECEIPT';
    const hasBudget = Number.isInteger(item.executionBudgetSeconds);
    if (item.executionBudgetSeconds !== null && (!hasBudget || item.executionBudgetSeconds < 1 || item.executionBudgetSeconds > 30) ||
        !isNullableInt64String(item.pendingDeadlineAtNanos) || !isNullableInt64String(item.activeDeadlineAtNanos) ||
        !isNullableInt64String(item.absoluteExecutionDeadlineAtNanos) || item.progressIntervalNanos !== '10000000000' ||
        item.progressNoticeLimit !== 3) fail('operational-execution-policy-invalid');
    const deadlineFields = [item.pendingDeadlineAtNanos, item.activeDeadlineAtNanos, item.absoluteExecutionDeadlineAtNanos];
    if (hasBudget !== (item.pendingDeadlineAtNanos !== null && item.absoluteExecutionDeadlineAtNanos !== null) ||
        !hasBudget && (item.activeDeadlineAtNanos !== null || deadlineFields.some((value) => value !== null)) ||
        captureMissing && hasBudget) fail('operational-execution-policy-invalid');
    if (hasBudget) {
      const t0 = BigInt(item.t0Nanos);
      const expectedPending = int64String(t0 + 15_000_000_000n);
      const expectedAbsolute = int64String(BigInt(expectedPending) + BigInt(item.executionBudgetSeconds) * 1_000_000_000n);
      if (item.pendingDeadlineAtNanos !== expectedPending || item.absoluteExecutionDeadlineAtNanos !== expectedAbsolute) {
        fail('operational-execution-deadline-invalid');
      }
      if (item.activeDeadlineAtNanos !== null) {
        const activeAge = monotonicElapsed(item.activeDeadlineAtNanos, item.t0Nanos);
        if (activeAge < BigInt(item.executionBudgetSeconds) * 1_000_000_000n ||
            activeAge > BigInt(15 + item.executionBudgetSeconds) * 1_000_000_000n) {
          fail('operational-active-deadline-invalid');
        }
      }
    }

    const hasTerminal = item.terminalOutcome !== null;
    const hasTerminalLatency = item.terminalNanos !== '-1';
    if (hasTerminal !== hasTerminalLatency || hasTerminal !== (item.terminalAtNanos !== null) ||
        hasTerminal !== (item.terminalDeliveryResult !== null)) fail('operational-terminal-fields-invalid');
    if (hasTerminal && !captureMissing &&
        monotonicElapsed(item.terminalAtNanos, item.t0Nanos) !== BigInt(item.terminalNanos)) {
      fail('operational-terminal-time-invalid');
    }

    if (streamFormatVersion >= 4) validateOperationalQueueInvocation(item);
    if (streamFormatVersion === OPERATIONAL_FORMAT_VERSION) validateOperationalCancellationInvocation(item);

    for (const progress of item.progress) {
      const expectedSend = ({ dispatched: 'SUBMITTED', failed: 'DEFINITELY_UNSENT', unknown: 'UNKNOWN', missed: 'NO_ATTEMPT' })[progress.status];
      const hasContextOutcome = progress.contextSendResult !== null;
      if (!['queued', 'awaiting_answer'].includes(progress.phase) ||
          !['queued', 'forwarded_waiting', 'context_blocked'].includes(progress.state) ||
          expectedSend !== progress.publicSendResult || !isNullableOpaqueId(progress.noticeId) ||
          !isNullableInt64String(progress.publicDispatchAtNanos) ||
          !['SEND_COMPLETED', 'FAILED', 'UNKNOWN', 'QUEUED_WITH_REQUEST', null].includes(progress.contextSendResult) ||
          !isNullableInt64String(progress.contextCompletedAtNanos) ||
          hasContextOutcome !== (progress.contextCompletedAtNanos !== null) ||
          hasContextOutcome && progress.noticeId === null ||
          (progress.status === 'dispatched') !== (progress.noticeId !== null) ||
          (progress.status === 'missed') !== (progress.publicDispatchAtNanos === null) ||
          progress.phase === 'queued' && progress.state === 'forwarded_waiting' ||
          progress.phase === 'awaiting_answer' && progress.state === 'queued') {
        fail('operational-progress-fields-invalid');
      }
    }
  }
  if (streamFormatVersion >= 4) validateOperationalQueueEvidence(snapshot.queueEvidence);
}

function validateOperationalCancellationInvocation(item) {
  if (item.terminalCause !== null &&
      (typeof item.terminalCause !== 'string' || !/^[A-Z_]{1,32}$/u.test(item.terminalCause)) ||
      item.retirementNoticeResult !== null && !RETIREMENT_NOTICE_RESULTS.has(item.retirementNoticeResult) ||
      !LOCAL_CANCEL_STATUSES.has(item.localCancelStatus) ||
      item.localCancelSendResult !== null && !LOCAL_CANCEL_SEND_RESULTS.has(item.localCancelSendResult) ||
      item.providerCancel !== null && item.providerCancel !== false ||
      !isCounter(item.lateOutputRejections)) {
    fail('numeric-cancellation-fields-invalid');
  }
  if (item.terminalOutcome === null && (item.terminalCause !== null || item.retirementNoticeResult !== null)) {
    fail('numeric-cancellation-fields-invalid');
  }
}

function validateOperationalQueueInvocation(item) {
  const hasForward = item.forwardedAtNanos !== null;
  if (!Number.isInteger(item.playerQueueDepthAtAdmission) || item.playerQueueDepthAtAdmission < 0 ||
      item.playerQueueDepthAtAdmission > 2 || !isNullableInt64String(item.forwardedAtNanos) ||
      !isNullableDurationString(item.pendingResidenceNanos) ||
      !(item.globalPendingAtForward === null || isCounter(item.globalPendingAtForward) && item.globalPendingAtForward <= 16) ||
      !(item.playerPendingAtForward === null || isCounter(item.playerPendingAtForward) && item.playerPendingAtForward <= 2) ||
      hasForward !== (item.pendingResidenceNanos !== null) ||
      hasForward !== (item.globalPendingAtForward !== null) ||
      hasForward !== (item.playerPendingAtForward !== null)) {
    fail('numeric-queue-invocation-fields-invalid');
  }
  if (!hasForward) return;
  if (item.outcome !== 'ADMITTED' || item.captureProvenance === 'MISSING_PACKET_RECEIPT' ||
      item.pendingDeadlineAtNanos === null ||
      BigInt(item.pendingResidenceNanos) >= 15_000_000_000n ||
      monotonicElapsed(item.forwardedAtNanos, item.t0Nanos) !== BigInt(item.pendingResidenceNanos)) {
    fail('numeric-forwarding-evidence-invalid');
  }
}

function validateOperationalQueueEvidence(value) {
  const countFields = ['pendingQueueScans', 'pendingQueueRecordSamples'];
  const boundedFields = [
    ['maxPendingGlobal', 16], ['maxPendingPerPlayer', 2],
    ['maxRecordsPerScan', 17], ['maxPlayerQueuesPerScan', 16],
  ];
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      countFields.some((field) => !isCounter(value[field])) ||
      !isNonnegativeLongString(value.maxPendingAgeNanos) ||
      !isNonnegativeLongString(value.maxPendingCleanupDelayNanos) ||
      boundedFields.some(([field, maximum]) => !Number.isInteger(value[field]) || value[field] < 0 || value[field] > maximum) ||
      value.recordLimitPerScan !== 17 || value.playerQueueLimitPerScan !== 16) {
    fail('numeric-queue-evidence-invalid');
  }
}

function noteLateOperationalChunk(state, chunk, bytes, failures) {
  let reason;
  if (!validateOperationalStreamLine(chunk) || chunk.type !== 'numeric_snapshot_chunk') {
    reason = 'operational-chunk-invalid-late';
  } else {
    const original = state.chunkBytesByIndex.get(chunk.index);
    reason = original === undefined
      ? 'operational-chunk-unexpected-late'
      : original.equals(bytes) ? 'operational-chunk-duplicate-late' : 'operational-chunk-conflict-late';
  }
  invalidateOperationalSnapshot(state, reason, failures);
  failures.add('operational-source-trailing-data');
}

function invalidateOperationalSnapshot(state, reason, failures) {
  state.groupFailures.add(reason);
  failures.add(reason);
  if (state.summary) {
    state.summary.status = 'INCOMPLETE';
    if (!state.summary.incompleteReasons.includes(reason)) {
      state.summary.incompleteReasons.push(reason);
      state.summary.incompleteReasons.sort();
    }
    state.summary.evidenceCoverage.complete = false;
  }
}

function operationalLineBuffers(bytes) {
  const lines = [];
  const failures = [];
  if (bytes.length === 0) return { lines, failures: ['operational-source-empty'] };
  let start = 0;
  let lineCount = 0;
  while (start < bytes.length) {
    if (lineCount >= OPERATIONAL_MAX_LINES) {
      failures.push('operational-line-count-limit');
      break;
    }
    lineCount += 1;
    const newline = bytes.indexOf(0x0a, start);
    const end = newline < 0 ? bytes.length : newline;
    const length = end - start;
    if (length === 0) {
      failures.push('operational-blank-line');
    } else if (length > OPERATIONAL_MAX_LINE_BYTES) {
      failures.push('operational-line-byte-limit');
    } else {
      lines.push(bytes.subarray(start, end));
    }
    if (newline < 0) break;
    start = newline + 1;
  }
  return { lines, failures };
}

function parseOperationalLine(bytes) {
  let text;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    fail('operational-utf8-invalid');
  }
  return parseStrictJson(text);
}

function summarizeOperationalSnapshot(manifest, records) {
  const counters = Object.fromEntries(OPERATIONAL_COUNTER_NAMES.map((name, index) => [name, manifest.counters[index] ?? null]));
  const outcomeCounts = Object.fromEntries(Object.keys(OPERATIONAL_OUTCOME_COUNTERS).map((outcome) => [outcome, 0]));
  const terminalOutcomeCounts = Object.create(null);
  const terminalDeliveryResultCounts = Object.create(null);
  const firstReplyCounts = Object.create(null);
  const progressCounts = { dispatched: 0, failed: 0, missed: 0, unknown: 0 };
  const progressStateCounts = Object.create(null);
  const progressPublicSendResultCounts = Object.create(null);
  const progressContextSendResultCounts = Object.create(null);
  const executionBudgetCounts = Object.create(null);
  const firstDispatchNanos = [];
  const answerCompletionNanos = [];
  const answerTerminalAttemptDeliveryResultCounts = Object.create(null);
  let answerTerminalAttemptCount = 0;
  let admittedCount = 0;
  let noDispatchDeadlineCount = 0;
  let noDispatchSettledCount = 0;
  let pendingCount = 0;
  let captureEvidenceMissingCount = 0;
  let decisionRecordedCount = 0;
  const rateLimitStatusCounts = Object.create(null);
  const noticeStatusCounts = Object.create(null);

  for (const item of records) {
    outcomeCounts[item.outcome] += 1;
    terminalOutcomeCounts[item.terminalOutcome ?? 'NO_TERMINAL'] =
      (terminalOutcomeCounts[item.terminalOutcome ?? 'NO_TERMINAL'] ?? 0) + 1;
    terminalDeliveryResultCounts[item.terminalDeliveryResult ?? 'NO_TERMINAL'] =
      (terminalDeliveryResultCounts[item.terminalDeliveryResult ?? 'NO_TERMINAL'] ?? 0) + 1;
    const budgetKey = item.executionBudgetSeconds === null ? 'NOT_RECORDED' : String(item.executionBudgetSeconds);
    executionBudgetCounts[budgetKey] = (executionBudgetCounts[budgetKey] ?? 0) + 1;
    firstReplyCounts[item.firstReplyResult] = (firstReplyCounts[item.firstReplyResult] ?? 0) + 1;
    if (item.outcome === 'ADMITTED') admittedCount += 1;
    if (item.decisionRecorded) decisionRecordedCount += 1;
    rateLimitStatusCounts[item.rateLimitStatus] = (rateLimitStatusCounts[item.rateLimitStatus] ?? 0) + 1;
    noticeStatusCounts[item.noticeStatus] = (noticeStatusCounts[item.noticeStatus] ?? 0) + 1;
    if (item.firstDispatchNanos !== '-1') firstDispatchNanos.push(BigInt(item.firstDispatchNanos));
    if (item.firstReplyResult === 'NO_DISPATCH_DEADLINE') noDispatchDeadlineCount += 1;
    if (item.firstReplyResult === 'NO_DISPATCH_SETTLED') noDispatchSettledCount += 1;
    if (item.firstReplyResult === 'PENDING') pendingCount += 1;
    if (item.firstReplyResult === 'EVIDENCE_MISSING') captureEvidenceMissingCount += 1;
    if (item.terminalOutcome === 'ANSWER') {
      answerTerminalAttemptCount += 1;
      const deliveryResult = item.terminalDeliveryResult ?? 'NOT_RECORDED';
      answerTerminalAttemptDeliveryResultCounts[deliveryResult] =
        (answerTerminalAttemptDeliveryResultCounts[deliveryResult] ?? 0) + 1;
    }
    if (isConfirmedAnswer(item)) {
      answerCompletionNanos.push(BigInt(item.terminalNanos));
    }
    for (const progress of item.progress) {
      progressCounts[progress.status] += 1;
      progressStateCounts[progress.state] = (progressStateCounts[progress.state] ?? 0) + 1;
      progressPublicSendResultCounts[progress.publicSendResult] =
        (progressPublicSendResultCounts[progress.publicSendResult] ?? 0) + 1;
      if (progress.contextSendResult !== null) {
        progressContextSendResultCounts[progress.contextSendResult] =
          (progressContextSendResultCounts[progress.contextSendResult] ?? 0) + 1;
      }
    }
  }

  const lateDispatchCount = firstDispatchNanos.filter((value) => value > 1_000_000_000n).length;
  const noDispatchSettlementCount = records.filter((item) => item.deadlineSettledAtNanos !== null).length;
  const noDispatchAtDeadlineCount = records.filter(noDispatchAtOrAfterDeadline).length;
  const noDispatchSettledBeforeDeadlineCount = records.filter(noDispatchBeforeDeadline).length;
  const eventualNoDispatchCount = noDispatchDeadlineCount + noDispatchSettledCount;
  const knownMissCount = records.filter(hasFirstReplyDeadlineMiss).length;
  const linkedMaximum = firstDispatchNanos.length ? maxBigInt(firstDispatchNanos) : null;
  const aggregateHasDispatch = manifest.firstPublicDispatchHistograms.flat(2).some((value) => value > 0) ||
    manifest.counters[32] > 0 || manifest.counters[33] > 0;
  const aggregateMaximum = aggregateHasDispatch ? manifest.maxFirstPublicDispatchNanos : null;
  const expectedOmitted = Math.max(0, manifest.sourceInvocationCount - records.length);
  const outcomeCounterReconciliation = Object.fromEntries(Object.entries(OPERATIONAL_OUTCOME_COUNTERS).map(([outcome, name]) => {
    const aggregate = counters[name];
    const linked = outcomeCounts[outcome];
    return [outcome, { aggregate, linked, matches: aggregate === linked }];
  }));
  const allOutcomeCountersMatch = Object.values(outcomeCounterReconciliation).every((item) => item.matches);
  const requestLinkReconciliation = summarizeRequestLinks(records.map((item) => ({
    requestAlias: item.requestId,
    outcome: item.outcome,
    rateLimitStatus: item.rateLimitStatus,
    requestLinkAtNanos: item.requestLinkAtNanos,
    terminalOutcome: item.terminalOutcome,
  })));
  const queueEvidence = summarizeOperationalQueueEvidence(manifest, records);
  const cancellationEvidence = summarizeOperationalCancellationEvidence(manifest, records, counters);
  const perPlayer = summarizeOperationalPlayers(records, manifest.numericFormatVersion);
  const deadlineCounterMatches = counters.FIRST_REPLY_DEADLINE_MISSES === knownMissCount;
  const noDispatchCounterMatches = counters.FIRST_REPLY_NO_DISPATCH === noDispatchSettlementCount;
  const noDispatchSettledCounterMatches = counters.FIRST_REPLY_NO_DISPATCH_SETTLED === noDispatchSettledBeforeDeadlineCount;
  const onTimeCounterMatches = counters.FIRST_REPLY_ON_TIME === (firstReplyCounts.ON_TIME ?? 0);
  const lateCounterMatches = counters.FIRST_REPLY_LATE === (firstReplyCounts.LATE ?? 0);
  const firstReplyCounterReconciliation = {
    deadlineMisses: { aggregate: counters.FIRST_REPLY_DEADLINE_MISSES, linked: knownMissCount, matches: deadlineCounterMatches },
    noDispatchSettlements: { aggregate: counters.FIRST_REPLY_NO_DISPATCH, linked: noDispatchSettlementCount, matches: noDispatchCounterMatches },
    noDispatchSettledBeforeDeadline: {
      aggregate: counters.FIRST_REPLY_NO_DISPATCH_SETTLED,
      linked: noDispatchSettledBeforeDeadlineCount,
      matches: noDispatchSettledCounterMatches,
    },
    onTimeDispatches: { aggregate: counters.FIRST_REPLY_ON_TIME, linked: firstReplyCounts.ON_TIME ?? 0, matches: onTimeCounterMatches },
    lateDispatches: { aggregate: counters.FIRST_REPLY_LATE, linked: lateDispatchCount, matches: lateCounterMatches },
  };
  const allFirstReplyCountersMatch = Object.values(firstReplyCounterReconciliation).every((item) => item.matches);
  const omittedCountMatches = manifest.omittedInvocationCount === expectedOmitted;
  const captureDropCountMatches = manifest.captureDroppedInvocationCount === counters.INTERACTION_EVIDENCE_DROPPED &&
    manifest.captureDroppedInvocationCount <= manifest.omittedInvocationCount;
  const sourceCountMatches = manifest.sourceInvocationCount === counters.INVOCATIONS_CAPTURED;
  const retainedCountMatches = manifest.retainedInvocationCount === records.length;
  const captureStatusMatches = manifest.captureStatus ===
    (manifest.sourceInvocationCount === records.length && manifest.omittedInvocationCount === 0 &&
      counters.CAPTURE_HANDOFF_OVERFLOWS === 0 ? 'complete' : 'incomplete');
  const maximumMatches = manifest.omittedInvocationCount > 0 || linkedMaximum === null
    ? null : BigInt(aggregateMaximum ?? '-1') === linkedMaximum;
  const incompleteReasons = [];
  if (!sourceCountMatches) incompleteReasons.push('operational-source-count-mismatch');
  if (!retainedCountMatches) incompleteReasons.push('operational-retained-count-mismatch');
  if (!omittedCountMatches) incompleteReasons.push('operational-omitted-count-mismatch');
  if (!captureDropCountMatches) incompleteReasons.push('operational-capture-loss-mismatch');
  if (!captureStatusMatches) incompleteReasons.push('operational-capture-status-mismatch');
  if (manifest.omittedInvocationCount > 0) incompleteReasons.push('operational-source-rows-omitted');
  if (!allOutcomeCountersMatch) incompleteReasons.push('operational-outcome-counter-mismatch');
  if (!allFirstReplyCountersMatch) incompleteReasons.push('operational-first-reply-counter-mismatch');
  if (decisionRecordedCount !== records.length) incompleteReasons.push('operational-decision-evidence-incomplete');
  if (requestLinkReconciliation.unresolvedCoalescedCount > 0 || requestLinkReconciliation.unresolvedRepeatGuardCount > 0 ||
      requestLinkReconciliation.unlinkedAdmittedCount > 0 ||
      requestLinkReconciliation.missingLinkTimestampCount > 0) incompleteReasons.push('operational-request-link-incomplete');
  if (counters.CAPTURE_HANDOFF_OVERFLOWS > 0) incompleteReasons.push('operational-capture-handoff-overflow');
  if (cancellationEvidence.lateOutputCounterMatches === false) {
    incompleteReasons.push('operational-late-output-counter-mismatch');
  }
  if (maximumMatches === false) incompleteReasons.push('operational-maximum-dispatch-mismatch');

  return {
    snapshotId: manifest.snapshotId,
    status: incompleteReasons.length === 0 ? 'COMPLETE' : 'INCOMPLETE',
    sourceInvocationCount: manifest.sourceInvocationCount,
    retainedInvocationCount: manifest.retainedInvocationCount,
    observedInvocationCount: records.length,
    omittedInvocationCount: manifest.omittedInvocationCount,
    captureDroppedInvocationCount: manifest.captureDroppedInvocationCount,
    captureStatus: manifest.captureStatus,
    outcomeCounts,
    decisionEvidence: {
      recordedCount: decisionRecordedCount,
      missingCount: records.length - decisionRecordedCount,
      complete: decisionRecordedCount === records.length,
    },
    rateLimitStatusCounts: Object.fromEntries(Object.entries(rateLimitStatusCounts).sort(([a], [b]) => a.localeCompare(b))),
    noticeStatusCounts: Object.fromEntries(Object.entries(noticeStatusCounts).sort(([a], [b]) => a.localeCompare(b))),
    requestLinkReconciliation,
    queueEvidence,
    cancellationEvidence,
    perPlayer,
    terminalOutcomeCounts: Object.fromEntries(Object.entries(terminalOutcomeCounts).sort(([a], [b]) => a.localeCompare(b))),
    terminalDeliveryResultCounts: Object.fromEntries(Object.entries(terminalDeliveryResultCounts)
      .sort(([a], [b]) => a.localeCompare(b))),
    executionBudgetCounts: Object.fromEntries(Object.entries(executionBudgetCounts).sort(([a], [b]) => a.localeCompare(b))),
    firstReplyOutcomeCounts: Object.fromEntries(Object.entries(firstReplyCounts).sort(([a], [b]) => a.localeCompare(b))),
    firstDispatch: {
      populationCount: records.length,
      sampleCount: firstDispatchNanos.length,
      noFirstDispatchCount: records.length - firstDispatchNanos.length,
      conditionalOnMeasuredSuccessfulDispatch: true,
      ...latencySummary(firstDispatchNanos),
      over1000msCount: lateDispatchCount,
      noDispatchDeadlineCount,
      noDispatchSettledCount,
      noDispatchAtDeadlineCount,
      noDispatchSettlementCount,
      noDispatchSettledBeforeDeadlineCount,
      eventualNoDispatchCount,
      lateDispatchAfterDeadlineSettlementCount: records.filter((item) => item.firstDispatchNanos !== '-1' &&
        BigInt(item.firstDispatchNanos) > 1_000_000_000n && noDispatchAtOrAfterDeadline(item)).length,
      pendingCount,
      captureEvidenceMissingCount,
      knownDeadlineMissCount: knownMissCount,
      aggregateDeadlineMissCount: counters.FIRST_REPLY_DEADLINE_MISSES,
      deadlineCounterMatches,
      noDispatchCounterMatches,
      noDispatchSettledCounterMatches,
      onTimeCounterMatches,
      lateCounterMatches,
      firstReplyCounterReconciliation,
      allFirstReplyCountersMatch,
      linkedMaximumNanos: linkedMaximum === null ? null : String(linkedMaximum),
      aggregateMaximumNanos: aggregateMaximum,
      aggregateMaximumMatchesLinked: maximumMatches,
      actualMaximumDispatchNanos: aggregateMaximum,
      actualMaximumMatchesLinked: maximumMatches,
    },
    responseCompleteness: {
      admittedCount,
      answerCompletedCount: answerCompletionNanos.length,
      answerTerminalAttemptCount,
      unconfirmedAnswerAttemptCount: answerTerminalAttemptCount - answerCompletionNanos.length,
      answerTerminalAttemptDeliveryResultCounts: Object.fromEntries(
        Object.entries(answerTerminalAttemptDeliveryResultCounts).sort(([a], [b]) => a.localeCompare(b))),
      answerCompletion: latencySummary(answerCompletionNanos),
      answerCompletionLatencyBasis: 'terminalNanos: capture-to-terminal-settlement; terminalAtNanos remains a separate event timestamp',
      answerCompletionRateOverAllInvocations: ratio(answerCompletionNanos.length, records.length),
      answerCompletionRateOverAdmitted: ratio(answerCompletionNanos.length, admittedCount),
      admittedWithoutMeasuredAnswer: admittedCount - answerCompletionNanos.length,
    },
    progressCounts,
    progressStateCounts: Object.fromEntries(Object.entries(progressStateCounts).sort(([a], [b]) => a.localeCompare(b))),
    progressPublicSendResultCounts: Object.fromEntries(Object.entries(progressPublicSendResultCounts)
      .sort(([a], [b]) => a.localeCompare(b))),
    progressContextSendResultCounts: Object.fromEntries(Object.entries(progressContextSendResultCounts)
      .sort(([a], [b]) => a.localeCompare(b))),
    outcomeCounterReconciliation,
    allOutcomeCountersMatch,
    evidenceCoverage: {
      aggregateCapturedInvocationCount: counters.INVOCATIONS_CAPTURED,
      linkedInvocationCount: records.length,
      omittedInvocationCount: manifest.omittedInvocationCount,
      interactionEvidenceDroppedCount: counters.INTERACTION_EVIDENCE_DROPPED,
      captureHandoffOverflowCount: counters.CAPTURE_HANDOFF_OVERFLOWS,
      decisionRecordedCount,
      requestLinkReconciliation,
      complete: incompleteReasons.length === 0,
    },
    overflowCounters: {
      MAILBOX_OVERFLOWS: counters.MAILBOX_OVERFLOWS,
      CONTROL_QUEUE_OVERFLOWS: counters.CONTROL_QUEUE_OVERFLOWS,
      DISPOSITION_QUEUE_OVERFLOWS: counters.DISPOSITION_QUEUE_OVERFLOWS,
      INTERACTION_EVIDENCE_DROPPED: counters.INTERACTION_EVIDENCE_DROPPED,
      CAPTURE_HANDOFF_OVERFLOWS: counters.CAPTURE_HANDOFF_OVERFLOWS,
    },
    counters,
    tickHistogram: manifest.tickHistogram,
    firstPublicDispatchHistograms: manifest.firstPublicDispatchHistograms,
    maxFirstPublicDispatchNanos: aggregateMaximum,
    incompleteReasons,
  };
}

function summarizeOperationalQueueEvidence(manifest, records) {
  if (manifest.numericFormatVersion < 4) {
    return {
      availability: 'UNKNOWN_LEGACY_STREAM',
      pendingQueueScans: null,
      pendingQueueRecordSamples: null,
      sampledMaxima: null,
      forwardedInvocationCount: null,
      admittedWithoutForwardingCount: null,
      captureToForwardWait: null,
      basis: 'Legacy v3 did not record queue-scan or forwarding evidence; absence is unknown, not zero.',
    };
  }
  const forwarded = records.filter((item) => item.forwardedAtNanos !== null);
  const residence = forwarded.map((item) => BigInt(item.pendingResidenceNanos));
  return {
    availability: 'OBSERVED',
    pendingQueueScans: manifest.queueEvidence.pendingQueueScans,
    pendingQueueRecordSamples: manifest.queueEvidence.pendingQueueRecordSamples,
    sampledMaxima: {
      globalPending: manifest.queueEvidence.maxPendingGlobal,
      perPlayerPending: manifest.queueEvidence.maxPendingPerPlayer,
      oldestPendingAgeNanos: manifest.queueEvidence.maxPendingAgeNanos,
      oldestCleanupDelayNanos: manifest.queueEvidence.maxPendingCleanupDelayNanos,
      recordsPerScan: manifest.queueEvidence.maxRecordsPerScan,
      playerQueuesPerScan: manifest.queueEvidence.maxPlayerQueuesPerScan,
      recordLimitPerScan: manifest.queueEvidence.recordLimitPerScan,
      playerQueueLimitPerScan: manifest.queueEvidence.playerQueueLimitPerScan,
      interpretation: 'Observed bounded samples only; sampled maxima do not prove unseen wall-clock behavior.',
    },
    forwardedInvocationCount: forwarded.length,
    admittedWithoutForwardingCount: records.filter((item) => item.outcome === 'ADMITTED' &&
      item.forwardedAtNanos === null).length,
    captureToForwardWait: latencySummary(residence),
    basis: 'Forwarding is local outbound-queue acceptance, not model execution or client delivery.',
  };
}

function summarizeOperationalCancellationEvidence(manifest, records, counters) {
  if (manifest.numericFormatVersion < OPERATIONAL_FORMAT_VERSION) {
    return {
      availability: 'UNKNOWN_LEGACY_STREAM',
      terminalCauseCounts: null,
      retirementNoticeResultCounts: null,
      localCancelStatusCounts: null,
      localCancelSendResultCounts: null,
      providerCancel: null,
      lateOutputRejectionCount: null,
      lateOutputCounterMatches: null,
      basis: 'Legacy v3/v4 did not record cancellation attribution; absence is unknown, not zero.',
    };
  }

  const countPresent = (field) => Object.fromEntries(records.reduce((counts, item) => {
    const value = item[field];
    if (value !== null) counts.set(value, (counts.get(value) ?? 0) + 1);
    return counts;
  }, new Map()).entries());
  const linkedLateOutputRejectionCount = records.reduce((count, item) => count + item.lateOutputRejections, 0);
  return {
    availability: 'OBSERVED',
    terminalCauseCounts: countPresent('terminalCause'),
    retirementNoticeResultCounts: countPresent('retirementNoticeResult'),
    localCancelStatusCounts: countPresent('localCancelStatus'),
    localCancelSendResultCounts: countPresent('localCancelSendResult'),
    providerCancel: {
      falseCount: records.filter((item) => item.providerCancel === false).length,
      unknownCount: records.filter((item) => item.providerCancel === null).length,
      trueCount: 0,
    },
    lateOutputRejectionCount: counters.LATE_OUTPUT_REJECTIONS,
    linkedLateOutputRejectionCount,
    lateOutputCounterMatches: counters.LATE_OUTPUT_REJECTIONS === linkedLateOutputRejectionCount,
    basis: 'Body-free local evidence only; local send acceptance does not prove peer receipt, provider stop, or client display.',
  };
}

function summarizeOperationalPlayers(records, streamFormatVersion) {
  const identities = new Map();
  const rows = new Map();
  for (const item of records) {
    if (item.player === null) continue;
    let alias = identities.get(item.player);
    if (alias === undefined) {
      alias = `player-${String(identities.size + 1).padStart(3, '0')}`;
      identities.set(item.player, alias);
    }
    let row = rows.get(alias);
    if (row === undefined) {
      row = {
        invocations: 0,
        dispositions: Object.create(null),
         admittedLogicalRequests: 0,
         modelStarts: { status: 'UNKNOWN_NOT_EXPOSED', count: null },
         coalescedCount: 0,
         repeatGuardRefusedCount: 0,
         repeatGuardCapacityRefusedCount: 0,
          forwardedCount: streamFormatVersion >= 4 ? 0 : null,
         queueWaitNanos: [],
         queueAgeAtFirstDispatchNanos: [],
         firstDispatchNanos: [],
        firstReplyDeadlineMisses: 0,
        confirmedAnswerNanos: [],
      };
      rows.set(alias, row);
    }
    row.invocations += 1;
    row.dispositions[item.outcome] = (row.dispositions[item.outcome] ?? 0) + 1;
     if (item.outcome === 'ADMITTED') row.admittedLogicalRequests += 1;
     if (item.outcome === 'COALESCED') row.coalescedCount += 1;
     if (item.outcome === 'RATE_LIMITED' && item.rateLimitStatus === 'REPEAT_GUARD') row.repeatGuardRefusedCount += 1;
     if (item.outcome === 'RATE_LIMITED' && item.rateLimitStatus === 'REPEAT_GUARD_CAPACITY') {
       row.repeatGuardCapacityRefusedCount += 1;
     }
      if (streamFormatVersion >= 4 && item.forwardedAtNanos !== null) {
       row.forwardedCount += 1;
       row.queueWaitNanos.push(BigInt(item.pendingResidenceNanos));
     }
     if (item.queueAgeAtFirstDispatchNanos !== null) {
       row.queueAgeAtFirstDispatchNanos.push(BigInt(item.queueAgeAtFirstDispatchNanos));
     }
    if (item.firstDispatchNanos !== '-1') row.firstDispatchNanos.push(BigInt(item.firstDispatchNanos));
    if (hasFirstReplyDeadlineMiss(item)) row.firstReplyDeadlineMisses += 1;
    if (isConfirmedAnswer(item)) row.confirmedAnswerNanos.push(BigInt(item.terminalNanos));
  }
  return Object.fromEntries([...rows.entries()].map(([alias, row]) => [alias, {
    invocations: row.invocations,
    dispositions: Object.fromEntries(Object.entries(row.dispositions).sort(([left], [right]) => left.localeCompare(right))),
    admittedLogicalRequests: row.admittedLogicalRequests,
    modelStarts: row.modelStarts,
     coalescedCount: row.coalescedCount,
     repeatGuardRefusedCount: row.repeatGuardRefusedCount,
     repeatGuardCapacityRefusedCount: row.repeatGuardCapacityRefusedCount,
     forwardedCount: row.forwardedCount,
      queueWait: streamFormatVersion >= 4 ? latencySummary(row.queueWaitNanos) : null,
     queueAgeAtFirstDispatch: latencySummary(row.queueAgeAtFirstDispatchNanos),
    firstDispatch: {
      sampleCount: row.firstDispatchNanos.length,
      maxMs: row.firstDispatchNanos.length ? toMs(maxBigInt(row.firstDispatchNanos)) : null,
      knownDeadlineMissCount: row.firstReplyDeadlineMisses,
    },
    confirmedAnswerCompletion: {
      count: row.confirmedAnswerNanos.length,
      ...latencySummary(row.confirmedAnswerNanos),
    },
  }]));
}

function renderOperationalMarkdown(report) {
  const lines = [
    '# TickMetrics operational numeric report',
    '',
    `- Status: **${report.status}**.`,
    `- Source SHA-256: ${report.sourceSha256}.`,
    `- Source invocations: ${formatUnknown(report.sourceInvocationCount)}; retained: ${formatUnknown(report.retainedInvocationCount)}; observed: ${formatUnknown(report.observedInvocationCount)}; unobserved/omitted: ${formatUnknown(report.omittedInvocationCount)}; manifest-declared omitted: ${formatUnknown(report.declaredOmittedInvocationCount)}.`,
    `- Snapshot semantics: ${report.snapshotAggregation}`,
    `- Incomplete reasons: ${report.incompleteReasons.length ? report.incompleteReasons.join(', ') : 'none'}.`,
    '',
    'This is an operational numeric report only, not a human evaluation, acceptance, or a quality decision.',
    'The separate human-evaluation path remains limited to 64 turns, 3 anonymous player aliases, and 1 MiB; a larger operational load is not represented by that limited human evaluation.',
    '',
  ];
  for (const [index, snapshot] of report.snapshots.entries()) {
    lines.push(`## Snapshot ${index + 1} (${snapshot.status})`, '');
    lines.push(`- Source/retained/observed/omitted: ${snapshot.sourceInvocationCount}/${snapshot.retainedInvocationCount}/${snapshot.observedInvocationCount}/${snapshot.omittedInvocationCount}.`);
    lines.push(`- Interaction outcomes: ${formatCounts(snapshot.outcomeCounts)}.`);
    lines.push(`- Decision evidence: ${snapshot.decisionEvidence.recordedCount}/${snapshot.observedInvocationCount}; rate-limit states ${formatCounts(snapshot.rateLimitStatusCounts)}; notice states ${formatCounts(snapshot.noticeStatusCounts)}.`);
    lines.push(`- Request-link reconciliation: ${snapshot.requestLinkReconciliation.linkedInvocationCount} linked rows/${snapshot.requestLinkReconciliation.requestGroupCount} request groups; coalesced ${snapshot.requestLinkReconciliation.coalescedCount}, resolved ${snapshot.requestLinkReconciliation.resolvedCoalescedCount}, unresolved ${snapshot.requestLinkReconciliation.unresolvedCoalescedCount}, missing link time ${snapshot.requestLinkReconciliation.missingLinkTimestampCount}.`);
    lines.push(`- Repeat guard: ${snapshot.requestLinkReconciliation.repeatGuardRefusedCount} refusals; resolved to one admitted anchor ${snapshot.requestLinkReconciliation.resolvedRepeatGuardCount}; unresolved ${snapshot.requestLinkReconciliation.unresolvedRepeatGuardCount}.`);
    const queue = snapshot.queueEvidence;
    if (queue.availability === 'OBSERVED') {
      lines.push(`- Queue evidence: ${queue.pendingQueueScans} bounded scans/${queue.pendingQueueRecordSamples} record samples; sampled max global/per-player pending ${queue.sampledMaxima.globalPending}/${queue.sampledMaxima.perPlayerPending}; oldest sampled pending age ${queue.sampledMaxima.oldestPendingAgeNanos} ns; cleanup delay ${queue.sampledMaxima.oldestCleanupDelayNanos} ns; capture-to-forward wait n=${queue.captureToForwardWait.sampleCount}, median ${formatMs(queue.captureToForwardWait.medianMs)}, p95 ${formatMs(queue.captureToForwardWait.p95Ms)}, max ${formatMs(queue.captureToForwardWait.maxMs)}. Sampled maxima are not bounds on unseen wall-clock behavior.`);
    } else {
      lines.push(`- Queue evidence: unknown (${queue.basis})`);
    }
    for (const [playerAlias, player] of Object.entries(snapshot.perPlayer)) {
     const queueWait = player.queueWait === null ? 'unknown' :
       `n=${player.queueWait.sampleCount}, median ${formatMs(player.queueWait.medianMs)}, max ${formatMs(player.queueWait.maxMs)}`;
       const queueAge = player.queueAgeAtFirstDispatch === null ? 'unknown' :
         `n=${player.queueAgeAtFirstDispatch.sampleCount}, max ${formatMs(player.queueAgeAtFirstDispatch.maxMs)}`;
       lines.push(`- ${playerAlias}: ${player.invocations} invocations (${formatCounts(player.dispositions)}); ${player.admittedLogicalRequests} admitted logical requests; ${player.coalescedCount} coalesced; ${player.repeatGuardRefusedCount} repeat-guard refused; ${player.repeatGuardCapacityRefusedCount} repeat-guard-capacity refused; model starts ${player.modelStarts.count === null ? 'unknown (not exposed)' : player.modelStarts.count}; forwarding ${player.forwardedCount === null ? 'unknown' : player.forwardedCount} (${queueWait}; dispatch queue age ${queueAge}); first-dispatch max ${formatMs(player.firstDispatch.maxMs)}, known deadline misses ${player.firstDispatch.knownDeadlineMissCount}; confirmed submitted ANSWER completions ${player.confirmedAnswerCompletion.count}, max ${formatMs(player.confirmedAnswerCompletion.maxMs)}.`);
    }
    lines.push(`- First-reply outcomes: ${formatCounts(snapshot.firstReplyOutcomeCounts)}.`);
    lines.push(`- First dispatch: ${snapshot.firstDispatch.sampleCount}/${snapshot.firstDispatch.populationCount} linked; median ${formatMs(snapshot.firstDispatch.medianMs)}; p95 ${formatMs(snapshot.firstDispatch.p95Ms)}; linked maximum ${formatMs(snapshot.firstDispatch.maxMs)}; direct actual maximum ${formatUnknown(snapshot.firstDispatch.actualMaximumDispatchNanos)} ns.`);
    lines.push(`- First-reply deadline: ${snapshot.firstDispatch.knownDeadlineMissCount} unique linked misses (${snapshot.firstDispatch.over1000msCount} late first dispatches; ${snapshot.firstDispatch.noDispatchAtDeadlineCount} no-dispatch observations at/after deadline, with overlap counted once; ${snapshot.firstDispatch.eventualNoDispatchCount} settled no-dispatch outcomes; ${snapshot.firstDispatch.noFirstDispatchCount} have no successful first dispatch by export); aggregate counter ${snapshot.firstDispatch.aggregateDeadlineMissCount}; deadline reconciliation ${snapshot.firstDispatch.deadlineCounterMatches ? 'matches' : 'does not match'}; all first-reply counters ${snapshot.firstDispatch.allFirstReplyCountersMatch ? 'match linked rows' : 'do not match linked rows'}. No-dispatch settlements: ${snapshot.firstDispatch.noDispatchSettlementCount} total, ${snapshot.firstDispatch.noDispatchSettledBeforeDeadlineCount} before deadline.`);
    lines.push(`- Confirmed technical answers: ${snapshot.responseCompleteness.answerCompletedCount}/${snapshot.responseCompleteness.answerCompletionRateOverAllInvocations.denominator} linked invocations (${formatRate(snapshot.responseCompleteness.answerCompletionRateOverAllInvocations.rate)}); ${snapshot.responseCompleteness.answerCompletedCount}/${snapshot.responseCompleteness.admittedCount} admitted (${formatRate(snapshot.responseCompleteness.answerCompletionRateOverAdmitted.rate)}). ANSWER terminal attempts: ${snapshot.responseCompleteness.answerTerminalAttemptCount}; delivery results: ${formatCounts(snapshot.responseCompleteness.answerTerminalAttemptDeliveryResultCounts)}; unconfirmed attempts: ${snapshot.responseCompleteness.unconfirmedAnswerAttemptCount}. Capture-to-terminal-settlement latency from terminalNanos: n=${snapshot.responseCompleteness.answerCompletion.sampleCount}/${snapshot.responseCompleteness.admittedCount} admitted; median ${formatMs(snapshot.responseCompleteness.answerCompletion.medianMs)}; p95 ${formatMs(snapshot.responseCompleteness.answerCompletion.p95Ms)}; maximum ${formatMs(snapshot.responseCompleteness.answerCompletion.maxMs)}. terminalAtNanos is a separate event timestamp. Admitted without confirmed submitted ANSWER: ${snapshot.responseCompleteness.admittedWithoutMeasuredAnswer}.`);
    lines.push(`- Configured execution budgets (seconds): ${formatCounts(snapshot.executionBudgetCounts)}; fixed progress interval/limit: 10 seconds / 3 notices per request.`);
    lines.push(`- Terminal reason counts: ${formatCounts(snapshot.terminalOutcomeCounts)}; delivery results: ${formatCounts(snapshot.terminalDeliveryResultCounts)}.`);
    lines.push(`- Progress states: ${formatCounts(snapshot.progressStateCounts)}; public send results: ${formatCounts(snapshot.progressPublicSendResultCounts)}; context transport results: ${formatCounts(snapshot.progressContextSendResultCounts)}.`);
    lines.push(`- Overflow counters: ${formatCounts(snapshot.overflowCounters)}.`);
    lines.push(`- All disposition aggregate counters match linked rows: ${snapshot.allOutcomeCountersMatch ? 'yes' : 'no'}.`);
    if (snapshot.incompleteReasons.length) lines.push(`- Snapshot incompleteness: ${snapshot.incompleteReasons.join(', ')}.`);
    lines.push('');
  }
  lines.push(`- Human evaluation: ${report.humanEvaluation.status}; turn limit ${report.humanEvaluation.maxTurns}; this operational source is not covered.`);
  lines.push('- Automatic quality pass: no.');
  return lines.join('\n');
}

function operationalFailureCode(error) {
  return /^[a-z0-9-]+$/u.test(error?.code ?? '') ? error.code : 'operational-source-invalid';
}

function safeCounterOrNull(value) {
  return isCounter(value) ? value : null;
}

export async function validateRunFile(runPath) {
  const run = await loadRun(runPath);
  const report = buildReport(run);
  return {
    valid: true,
    runId: run.runId,
    provenance: run.provenance,
    acceptance: report.acceptance.status,
    automaticPass: false,
    blockers: report.acceptance.blockers,
  };
}

function emptyMetadata() {
  return {
    artifactHashes: [],
    versions: Object.fromEntries(PUBLIC_VERSIONS.map((key) => [key, null])),
    modelProviderLabel: null,
    hardwareClass: null,
    workload: { durationMinutes: null, playerCount: null },
    clockBoundary: 'unknown',
    personaBriefRevision: null,
    attentionSettings: null,
    consents: CONSENT_KINDS.map((kind) => ({
      kind, approved: false, reference: null, date: null, scope: null, approvedByRole: null,
    })),
    isolationApproval: { approved: false, reference: null, date: null, scope: null },
    humanAttestation: false,
    attestedByRole: null,
  };
}

function parseEntryInput(source, { legacyNumericV1 = false } = {}) {
  const chunks = source.split(/\n/u).filter((item) => item.trim().length > 0);
  let value;
  try {
    value = parseStrictJson(source);
  } catch (error) {
    if (chunks.length > 1) return { type: 'numeric_evidence', snapshots: parseNumericSnapshots(source, { legacyNumericV1 }) };
    throw error;
  }
  if (legacyNumericV1 && value && typeof value === 'object' && !Array.isArray(value) &&
      ['session_attestation', 'human_review'].includes(value.type)) {
    fail('legacy-numeric-migration-not-applicable');
  }
  if (value && typeof value === 'object' && !Array.isArray(value) && value.type === 'session_attestation') {
    exactFields(value, [
      'type', 'origin', 'humanAttestation', 'attestedByRole', 'artifactHashes', 'versions', 'modelProviderLabel',
      'hardwareClass', 'workload', 'clockBoundary', 'personaBriefRevision', 'attentionSettings', 'consents',
      'isolationApproval', 'discoveries',
    ]);
    return value;
  }
  if (value && typeof value === 'object' && !Array.isArray(value) && value.type === 'human_review') {
    const base = [
      'type', 'reviewerRole', 'scenarioIds', 'ratings', 'reasonCodes', 'weaknessCodes', 'terminalClassifications',
    ];
    const evaluator = ['decision', 'decisionReasonCodes', 'prioritizedWeaknessCodes'];
    if (value.reviewerRole === 'independent_evaluator') exactFields(value, [...base, ...evaluator]);
    else exactFields(value, base);
    return value;
  }
  return { type: 'numeric_evidence', snapshots: parseNumericSnapshots(source, { legacyNumericV1 }) };
}

function parseNumericSnapshots(source, { legacyNumericV1 = false } = {}) {
  const chunks = source.split(/\n/u).filter((item) => item.trim().length > 0);
  if (chunks.length < 1 || chunks.length > 2) fail('numeric-snapshot-count-invalid');
  return chunks.map((chunk) => {
    const snapshot = parseStrictJson(chunk);
    if (Object.hasOwn(snapshot ?? {}, 'numericFormatVersion')) {
      if (legacyNumericV1) fail('legacy-numeric-migration-not-applicable');
      exactFields(snapshot, SOURCE_FIELDS);
      if (snapshot.numericFormatVersion !== NUMERIC_FORMAT_VERSION) fail('numeric-format-version-unsupported');
      validateRawSnapshot(snapshot);
      return snapshot;
    }
    if (!legacyNumericV1) fail('numeric-format-version-required');
    exactFields(snapshot, LEGACY_SOURCE_FIELDS);
    validateLegacyRawSnapshot(snapshot);
    const migrated = migrateLegacyRawSnapshot(snapshot);
    validateRawSnapshot(migrated);
    return migrated;
  });
}

function validateRawSnapshot(snapshot) {
  if (snapshot.numericFormatVersion !== NUMERIC_FORMAT_VERSION) fail('numeric-format-version-unsupported');
  if (!Array.isArray(snapshot.counters) || snapshot.counters.length !== 39 || !snapshot.counters.every(isCounter)) fail('numeric-counters-invalid');
  if (!Array.isArray(snapshot.tickHistogram) || snapshot.tickHistogram.length !== 10 || !snapshot.tickHistogram.every(isCounter)) fail('numeric-tick-histogram-invalid');
  if (!Array.isArray(snapshot.firstPublicDispatchHistograms) || snapshot.firstPublicDispatchHistograms.length !== 14 ||
      snapshot.firstPublicDispatchHistograms.some((source) => !Array.isArray(source) || source.length !== 12 ||
        source.some((disposition) => !Array.isArray(disposition) || disposition.length !== 10 || !disposition.every(isCounter)))) {
    fail('numeric-dispatch-histogram-invalid');
  }
  if (!isNonnegativeLongString(snapshot.maxFirstPublicDispatchNanos)) fail('numeric-dispatch-maximum-invalid');
  if (!Array.isArray(snapshot.interactionRecords) || snapshot.interactionRecords.length > MAX_INVOCATIONS) fail('numeric-invocation-limit');
  const seen = new Set();
  for (const rawItem of snapshot.interactionRecords) {
    const hasNoticeBatchFields = NOTICE_BATCH_FIELDS.every((field) => Object.hasOwn(rawItem, field));
    const hasAnyNoticeBatchField = NOTICE_BATCH_FIELDS.some((field) => Object.hasOwn(rawItem, field));
    if (hasAnyNoticeBatchField && !hasNoticeBatchFields) fail('numeric-notice-batch-fields-invalid');
    const hasResp03Evidence = ['decisionAtNanos', 'decisionRecorded', 'requestLinkAtNanos',
      'rateLimitStatus', 'noticeStatus'].some((field) => Object.hasOwn(rawItem, field));
    const resp03FieldsPresent = ['decisionAtNanos', 'decisionRecorded', 'requestLinkAtNanos',
      'rateLimitStatus', 'noticeStatus'].every((field) => Object.hasOwn(rawItem, field));
    if (hasResp03Evidence && !resp03FieldsPresent) fail('numeric-resp03-evidence-fields-invalid');
    const baseRawFields = resp03FieldsPresent ? RAW_INVOCATION_FIELDS : LEGACY_RAW_INVOCATION_FIELDS;
    const rawFields = hasNoticeBatchFields ? [...baseRawFields, ...NOTICE_BATCH_FIELDS] : baseRawFields;
    exactFields(rawItem, Object.hasOwn(rawItem, 'terminalDeliveryResult')
      ? [...rawFields, 'terminalDeliveryResult'] : rawFields);
    const item = resp03FieldsPresent ? rawItem : {
      ...rawItem,
      decisionAtNanos: null,
      decisionRecorded: false,
      requestLinkAtNanos: null,
      rateLimitStatus: 'NOT_RECORDED',
      noticeStatus: 'NOT_RECORDED',
    };
    if (!isUuid(item.id) || seen.has(item.id)) fail('numeric-invocation-id-invalid');
    seen.add(item.id);
    const captureMissing = item.captureProvenance === 'MISSING_PACKET_RECEIPT';
    if (!(captureMissing ? item.t0Nanos === null : isInt64String(item.t0Nanos)) ||
        item.receiptWallTimeUnixMillis !== null && !isCounter(item.receiptWallTimeUnixMillis) ||
        !isInt64String(item.handlerEnteredAtNanos) || !isNullableDurationString(item.handlerDelayNanos) ||
        !isInt64String(item.admittedAtNanos) || !isNullableDurationString(item.admissionDelayNanos) ||
        !isNullableInt64String(item.queuedAtNanos) || !isNullableDurationString(item.queueDelayNanos) ||
        !isNullableInt64String(item.fallbackCutoffAtNanos) || !isNullableInt64String(item.deadlineAtNanos) ||
        !['LIVE_PACKET', 'SYNTHETIC_HARNESS', 'MISSING_PACKET_RECEIPT'].includes(item.captureProvenance) ||
         !['SIGNED_CHAT', 'UNSIGNED_COMMAND', 'SIGNED_COMMAND', 'SYNTHETIC_HARNESS', 'MISSING_PACKET_RECEIPT', 'LIVE_PACKET'].includes(item.producer) ||
        !isCounter(item.connectionGeneration) ||
         !isNullableOpaqueId(item.templateRevision) || !isCounter(item.queueDepthAtAdmission) || item.queueDepthAtAdmission > 17 ||
        !isLatencyString(item.firstDispatchNanos) || !isNullableInt64String(item.firstDispatchAtNanos) ||
        !isNullableDurationString(item.queueAgeAtFirstDispatchNanos) || !isNullableUuid(item.publicationId) ||
        !FIRST_REPLY_RESULTS.has(item.firstReplyResult) || item.sendResult !== null && !SEND_RESULTS.has(item.sendResult) ||
        !isNullableInt64String(item.deadlineSettledAtNanos) || !isCounter(item.unknownDispatches) || !isNullableUuid(item.player) ||
         !isNullableWorldId(item.actualWorld) || !isNullableOpaqueId(item.serverBinding) ||
         !isNullableOpaqueId(item.worldBinding) || !isNullableOpaqueId(item.characterBinding) ||
         !ORIGINS.has(item.origin) || !OUTCOMES.has(item.outcome) || !isNullableOpaqueId(item.requestId) ||
         !isNullableInt64String(item.decisionAtNanos) || typeof item.decisionRecorded !== 'boolean' ||
         !RATE_LIMIT_STATUSES.has(item.rateLimitStatus) || !NOTICE_STATUSES.has(item.noticeStatus) ||
         !isNullableInt64String(item.requestLinkAtNanos) ||
         !isNullableEnum(item.firstDispatchSource, FIRST_SOURCES) ||
        !isNullableEnum(item.firstDispatchDisposition, FIRST_DISPOSITIONS) || !isCounter(item.failedDispatches) ||
          !Array.isArray(item.progress) || item.progress.length > 3 || !isLatencyString(item.terminalNanos) ||
          hasNoticeBatchFields && (!isNullableUuid(item.noticeBatchPublicationId) ||
            !NOTICE_BATCH_RESULTS.has(item.noticeBatchResult) ||
            !isNullableInt64String(item.noticeBatchAttemptedAtNanos) ||
            !isNullableDurationString(item.noticeBatchLatencyNanos)) ||
         Object.hasOwn(item, 'terminalDeliveryResult') && item.terminalDeliveryResult !== null &&
           !TERMINAL_DELIVERY_RESULTS.has(item.terminalDeliveryResult) ||
         item.terminalOutcome === null && item.terminalDeliveryResult != null ||
         item.terminalOutcome !== null && (typeof item.terminalOutcome !== 'string' || !/^[A-Z_]{1,32}$/u.test(item.terminalOutcome))) {
       fail('numeric-invocation-fields-invalid');
     }
    if (hasNoticeBatchFields) {
      const hasBatchId = item.noticeBatchPublicationId !== null;
      const hasAttempt = item.noticeBatchAttemptedAtNanos !== null;
      const hasLatency = item.noticeBatchLatencyNanos !== null;
      const completedResult = ['SUBMITTED', 'DEFINITELY_UNSENT', 'UNKNOWN'].includes(item.noticeBatchResult);
      if (item.noticeBatchResult === 'NOT_BATCHED'
        ? hasBatchId || hasAttempt || hasLatency
        : !hasBatchId || completedResult !== (hasAttempt && hasLatency) || hasAttempt !== hasLatency) {
        fail('numeric-notice-batch-fields-invalid');
      }
    }
    if (item.decisionRecorded !== (item.decisionAtNanos !== null) ||
        item.requestLinkAtNanos !== null && item.requestId === null) fail('numeric-resp03-evidence-fields-invalid');
    const repeatGuardLinked = item.outcome === 'RATE_LIMITED' && item.rateLimitStatus === 'REPEAT_GUARD';
    const requiresRequestLink = ['ADMITTED', 'COALESCED'].includes(item.outcome) || repeatGuardLinked;
    if (resp03FieldsPresent && (requiresRequestLink
      ? item.requestId === null || item.requestLinkAtNanos === null
      : item.requestId !== null || item.requestLinkAtNanos !== null)) {
      fail('numeric-request-link-fields-invalid');
    }
    if (resp03FieldsPresent && (item.outcome === 'ADMITTED'
      ? item.queuedAtNanos !== item.requestLinkAtNanos
      : ['COALESCED', 'RATE_LIMITED'].includes(item.outcome) && item.queuedAtNanos !== null)) {
      fail('numeric-request-link-fields-invalid');
    }
    if ((['REPEAT_GUARD', 'REPEAT_GUARD_CAPACITY'].includes(item.rateLimitStatus)) !==
        (item.outcome === 'RATE_LIMITED' && ['REPEAT_GUARD', 'REPEAT_GUARD_CAPACITY'].includes(item.rateLimitStatus))) {
      fail('numeric-repeat-guard-fields-invalid');
    }
    const hasDispatch = item.firstDispatchNanos !== '-1';
    if ((item.firstReplyResult === 'ON_TIME' || item.firstReplyResult === 'LATE') !== hasDispatch ||
        (!hasDispatch && (item.firstDispatchSource !== null || item.firstDispatchDisposition !== null || item.firstDispatchAtNanos !== null)) ||
        (hasDispatch && (item.firstDispatchSource === null || item.firstDispatchDisposition === null)) ||
        (item.firstReplyResult === 'PENDING' && item.deadlineSettledAtNanos !== null) ||
        (item.firstReplyResult.startsWith('NO_DISPATCH_') && item.deadlineSettledAtNanos === null) ||
        (item.firstReplyResult === 'EVIDENCE_MISSING' && !captureMissing) ||
        ((item.outcome === 'RECEIPT_EVIDENCE_MISSING') !== captureMissing) ||
        (captureMissing && item.producer !== 'MISSING_PACKET_RECEIPT') ||
        (captureMissing && (item.firstReplyResult !== 'EVIDENCE_MISSING' || item.sendResult !== 'NO_DISPATCH' ||
          item.receiptWallTimeUnixMillis !== null || item.handlerDelayNanos !== null || item.admissionDelayNanos !== null ||
          item.queuedAtNanos !== null || item.queueDelayNanos !== null || item.fallbackCutoffAtNanos !== null ||
          item.deadlineAtNanos !== null || hasDispatch))) {
      fail('numeric-first-reply-result-invalid');
    }
    if (!captureMissing) {
      const t0 = BigInt(item.t0Nanos);
      if (item.receiptWallTimeUnixMillis === null ||
          item.handlerDelayNanos !== durationString(monotonicElapsed(item.handlerEnteredAtNanos, t0)) ||
          item.admissionDelayNanos !== durationString(monotonicElapsed(item.admittedAtNanos, t0)) ||
          item.fallbackCutoffAtNanos !== int64String(wrapInt64(t0 + 750_000_000n)) ||
          item.deadlineAtNanos !== int64String(wrapInt64(t0 + 1_000_000_000n)) ||
          (item.queuedAtNanos === null
            ? item.queueDelayNanos !== null
            : item.queueDelayNanos !== durationString(monotonicElapsed(item.queuedAtNanos, t0))) ||
          (item.firstDispatchAtNanos === null || item.queuedAtNanos === null
            ? item.queueAgeAtFirstDispatchNanos !== null
            : item.queueAgeAtFirstDispatchNanos !== durationString(monotonicElapsed(item.firstDispatchAtNanos, item.queuedAtNanos)))) {
        fail('numeric-duration-validation-failed');
      }
    }
    for (const progress of item.progress) {
      exactFields(progress, ['phase', 'status', 'latencyNanos']);
      if (!['queued', 'awaiting_answer'].includes(progress.phase) ||
          !['dispatched', 'failed', 'missed', 'unknown'].includes(progress.status) || !isNonnegativeLongString(progress.latencyNanos)) {
        fail('numeric-progress-invalid');
      }
    }
  }
}

function validateLegacyRawSnapshot(snapshot) {
  if (!Array.isArray(snapshot.counters) || snapshot.counters.length !== 39 || !snapshot.counters.every(isCounter)) fail('numeric-counters-invalid');
  if (!Array.isArray(snapshot.tickHistogram) || snapshot.tickHistogram.length !== 10 || !snapshot.tickHistogram.every(isCounter)) fail('numeric-tick-histogram-invalid');
  if (!Array.isArray(snapshot.firstPublicDispatchHistograms) || snapshot.firstPublicDispatchHistograms.length !== 14 ||
      snapshot.firstPublicDispatchHistograms.some((source) => !Array.isArray(source) || source.length !== 12 ||
        source.some((disposition) => !Array.isArray(disposition) || disposition.length !== 10 || !disposition.every(isCounter)))) {
    fail('numeric-dispatch-histogram-invalid');
  }
  if (!isCounter(snapshot.maxFirstPublicDispatchNanos)) fail('numeric-dispatch-maximum-invalid');
  if (!Array.isArray(snapshot.interactionRecords) || snapshot.interactionRecords.length > MAX_INVOCATIONS) fail('numeric-invocation-limit');
  const seen = new Set();
  for (const item of snapshot.interactionRecords) {
    exactFields(item, Object.hasOwn(item, 'terminalDeliveryResult')
      ? [...LEGACY_RAW_INVOCATION_FIELDS, 'terminalDeliveryResult'] : LEGACY_RAW_INVOCATION_FIELDS);
    if (!isUuid(item.id) || seen.has(item.id)) fail('numeric-invocation-id-invalid');
    seen.add(item.id);
    if (!Number.isSafeInteger(item.t0Nanos) ||
        item.receiptWallTimeUnixMillis !== null && !isCounter(item.receiptWallTimeUnixMillis) ||
        !Number.isSafeInteger(item.handlerEnteredAtNanos) || item.handlerDelayNanos !== null && !isLegacyDuration(item.handlerDelayNanos) ||
        !Number.isSafeInteger(item.admittedAtNanos) || item.admissionDelayNanos !== null && !isLegacyDuration(item.admissionDelayNanos) ||
        !Number.isSafeInteger(item.queuedAtNanos) || item.queueDelayNanos !== null && !isLegacyDuration(item.queueDelayNanos) ||
        item.fallbackCutoffAtNanos !== null && !Number.isSafeInteger(item.fallbackCutoffAtNanos) ||
        item.deadlineAtNanos !== null && !Number.isSafeInteger(item.deadlineAtNanos) ||
        !['LIVE_PACKET', 'SYNTHETIC_HARNESS', 'MISSING_PACKET_RECEIPT'].includes(item.captureProvenance) ||
        !['SIGNED_CHAT', 'UNSIGNED_COMMAND', 'SIGNED_COMMAND', 'SYNTHETIC_HARNESS', 'MISSING_PACKET_RECEIPT'].includes(item.producer) ||
        !isCounter(item.connectionGeneration) || !isNullableOpaqueId(item.templateRevision) ||
        !isCounter(item.queueDepthAtAdmission) || item.queueDepthAtAdmission > 17 ||
        !isLegacyDuration(item.firstDispatchNanos) || !Number.isSafeInteger(item.firstDispatchAtNanos) ||
        item.queueAgeAtFirstDispatchNanos !== null && !isLegacyDuration(item.queueAgeAtFirstDispatchNanos) ||
        !isNullableUuid(item.publicationId) || !FIRST_REPLY_RESULTS.has(item.firstReplyResult) ||
        item.sendResult !== null && !SEND_RESULTS.has(item.sendResult) || !Number.isSafeInteger(item.deadlineSettledAtNanos) ||
         !isCounter(item.unknownDispatches) || !isNullableUuid(item.player) || !isNullableWorldId(item.actualWorld) ||
        !isNullableOpaqueId(item.serverBinding) || !isNullableOpaqueId(item.worldBinding) ||
        !isNullableOpaqueId(item.characterBinding) || !ORIGINS.has(item.origin) || !OUTCOMES.has(item.outcome) ||
        !isNullableOpaqueId(item.requestId) || !isNullableEnum(item.firstDispatchSource, FIRST_SOURCES) ||
        !isNullableEnum(item.firstDispatchDisposition, FIRST_DISPOSITIONS) || !isCounter(item.failedDispatches) ||
        !Array.isArray(item.progress) || item.progress.length > 3 || !isLegacyDuration(item.terminalNanos) ||
        item.terminalOutcome !== null && (typeof item.terminalOutcome !== 'string' || !/^[A-Z_]{1,32}$/u.test(item.terminalOutcome))) {
      fail('legacy-numeric-v1-unsafe-or-invalid');
    }
    for (const progress of item.progress) {
      exactFields(progress, ['phase', 'status', 'latencyNanos']);
      if (!['queued', 'awaiting_answer'].includes(progress.phase) ||
          !['dispatched', 'failed', 'missed', 'unknown'].includes(progress.status) || !isCounter(progress.latencyNanos)) {
        fail('legacy-numeric-v1-unsafe-or-invalid');
      }
    }
  }
}

function migrateLegacyRawSnapshot(snapshot) {
  return {
    numericFormatVersion: NUMERIC_FORMAT_VERSION,
    counters: snapshot.counters,
    tickHistogram: snapshot.tickHistogram,
    firstPublicDispatchHistograms: snapshot.firstPublicDispatchHistograms,
    maxFirstPublicDispatchNanos: String(snapshot.maxFirstPublicDispatchNanos),
    interactionRecords: snapshot.interactionRecords.map((item) => {
      const captureMissing = item.captureProvenance === 'MISSING_PACKET_RECEIPT';
      return {
        ...item,
        t0Nanos: captureMissing ? null : String(item.t0Nanos),
        receiptWallTimeUnixMillis: captureMissing ? null : item.receiptWallTimeUnixMillis,
        handlerEnteredAtNanos: String(item.handlerEnteredAtNanos),
        handlerDelayNanos: item.handlerDelayNanos === null || captureMissing ? null : String(item.handlerDelayNanos),
        admittedAtNanos: String(item.admittedAtNanos),
        admissionDelayNanos: item.admissionDelayNanos === null || captureMissing ? null : String(item.admissionDelayNanos),
        queuedAtNanos: item.queuedAtNanos === -1 ? null : String(item.queuedAtNanos),
        queueDelayNanos: item.queueDelayNanos === null || captureMissing ? null : String(item.queueDelayNanos),
        fallbackCutoffAtNanos: captureMissing || item.fallbackCutoffAtNanos === null ? null : String(item.fallbackCutoffAtNanos),
        deadlineAtNanos: captureMissing || item.deadlineAtNanos === null ? null : String(item.deadlineAtNanos),
        firstDispatchNanos: String(item.firstDispatchNanos),
        firstDispatchAtNanos: item.firstDispatchAtNanos === -1 ? null : String(item.firstDispatchAtNanos),
        queueAgeAtFirstDispatchNanos: item.queueAgeAtFirstDispatchNanos === null
          ? null : String(item.queueAgeAtFirstDispatchNanos),
        deadlineSettledAtNanos: item.deadlineSettledAtNanos === -1 ? null : String(item.deadlineSettledAtNanos),
        progress: item.progress.map((progress) => ({ ...progress, latencyNanos: String(progress.latencyNanos) })),
        terminalNanos: String(item.terminalNanos),
      };
    }),
  };
}

function normalizeNumericEvidence(rawSnapshots, sourceSha256, run) {
  const snapshots = rawSnapshots.map((raw, index) => ({
    index,
    counters: raw.counters,
    tickHistogram: raw.tickHistogram,
    firstPublicDispatchHistograms: raw.firstPublicDispatchHistograms,
    maxFirstPublicDispatchNanos: raw.maxFirstPublicDispatchNanos,
    invocations: raw.interactionRecords.map((item) => ({
      turnAlias: aliasFor(run, 'turns', 'turn', item.id),
      capturedAtNanos: item.t0Nanos,
      receiptWallTimeUnixMillis: item.receiptWallTimeUnixMillis,
      handlerEnteredAtNanos: item.handlerEnteredAtNanos,
      handlerDelayNanos: item.handlerDelayNanos,
      admittedAtNanos: item.admittedAtNanos,
      decisionAtNanos: item.decisionAtNanos ?? null,
      decisionRecorded: item.decisionRecorded ?? false,
      admissionDelayNanos: item.admissionDelayNanos,
      queuedAtNanos: item.queuedAtNanos,
      requestLinkAtNanos: item.requestLinkAtNanos ?? null,
      queueDelayNanos: item.queueDelayNanos,
      fallbackCutoffAtNanos: item.fallbackCutoffAtNanos,
      deadlineAtNanos: item.deadlineAtNanos,
      captureProvenance: item.captureProvenance,
      producer: item.producer,
      playerAlias: item.player === null ? null : aliasFor(run, 'players', 'player', item.player),
      requestAlias: item.requestId === null ? null : aliasFor(run, 'requests', 'request', item.requestId),
      origin: item.origin,
      outcome: item.outcome,
      rateLimitStatus: item.rateLimitStatus ?? 'NOT_RECORDED',
      noticeStatus: item.noticeStatus ?? 'NOT_RECORDED',
      templateRevision: item.templateRevision,
      queueDepthAtAdmission: item.queueDepthAtAdmission,
      firstReplySource: item.firstDispatchSource,
      firstReplyDisposition: item.firstDispatchDisposition,
      firstDispatchNanos: item.firstDispatchNanos,
      firstDispatchAtNanos: item.firstDispatchAtNanos,
      queueAgeAtFirstDispatchNanos: item.queueAgeAtFirstDispatchNanos,
      publicationId: item.publicationId,
      firstReplyResult: item.firstReplyResult,
      sendResult: item.sendResult,
      deadlineSettledAtNanos: item.deadlineSettledAtNanos,
      failedDispatches: item.failedDispatches,
      unknownDispatches: item.unknownDispatches,
      progress: item.progress,
      terminalOutcome: item.terminalOutcome,
       // Numeric v2 records predate terminal delivery results; do not infer a
       // submitted terminal from ANSWER, sendResult, or first-reply evidence.
       terminalDeliveryResult: item.terminalDeliveryResult ?? null,
       terminalCause: item.terminalCause ?? null,
       retirementNoticeResult: item.retirementNoticeResult ?? null,
       localCancelStatus: item.localCancelStatus ?? null,
       localCancelSendResult: item.localCancelSendResult ?? null,
       providerCancel: item.providerCancel ?? null,
       lateOutputRejections: item.lateOutputRejections ?? null,
       terminalNanos: item.terminalNanos,
    })),
  }));
  return { type: 'numeric_evidence', numericFormatVersion: NUMERIC_FORMAT_VERSION, sourceSha256, snapshots };
}

function normalizeSessionAttestation(input, sourceSha256) {
  const entry = { ...input, sourceSha256 };
  validateSessionEntry(entry);
  const missing = livePrerequisites(entry);
  if (missing.length) fail('live-consent-or-isolation-required');
  if (entry.versions && PUBLIC_VERSIONS.some((key) => entry.versions[key] === null)) fail('live-versions-incomplete');
  if (entry.workload.durationMinutes > 30) fail('session-duration-limit');
  return entry;
}

function validateSessionEntry(entry) {
  if (entry.origin !== 'human_live' || entry.humanAttestation !== true ||
      !['test_administrator', 'independent_evaluator'].includes(entry.attestedByRole) ||
      !Array.isArray(entry.artifactHashes) || entry.artifactHashes.length < 1 ||
      entry.artifactHashes.some((hash) => !SHA_RE.test(hash)) ||
      !Array.isArray(entry.consents) || entry.consents.length !== 3 ||
      new Set(entry.consents.map((item) => item.kind)).size !== 3 ||
      !Array.isArray(entry.discoveries) || entry.discoveries.length > MAX_PLAYERS) fail('session-attestation-invalid');
  if (entry.consent) fail('unknown-consent-field');
  for (const key of PUBLIC_VERSIONS) if (typeof entry.versions?.[key] !== 'string' || !safeLabel(entry.versions[key])) fail('live-versions-incomplete');
  if (!safeLabel(entry.modelProviderLabel) || !safeLabel(entry.hardwareClass) ||
      !Number.isInteger(entry.workload.durationMinutes) || entry.workload.durationMinutes < 1 || entry.workload.durationMinutes > 30 ||
      !Number.isInteger(entry.workload.playerCount) || entry.workload.playerCount < 1 || entry.workload.playerCount > MAX_PLAYERS ||
      !['unknown', 'server_monotonic_dispatch', 'human_observed_client'].includes(entry.clockBoundary) ||
      !safeLabel(entry.personaBriefRevision) || !safeLabel(entry.attentionSettings)) fail('session-metadata-invalid');
  const roles = new Set();
  for (const discovery of entry.discoveries) {
    if (!['player_1', 'player_2', 'player_3'].includes(discovery.playerRole) || roles.has(discovery.playerRole) ||
        typeof discovery.success !== 'boolean' || typeof discovery.helpProvided !== 'boolean' ||
        !Number.isInteger(discovery.seconds) || discovery.seconds < 0 || discovery.seconds > 120) fail('discovery-record-invalid');
    roles.add(discovery.playerRole);
  }
}

function normalizeHumanReview(input, sourceSha256) {
  validateReviewEntry(input);
  const entry = {
    ...input,
    reasonCodes: [...input.reasonCodes].sort(),
    weaknessCodes: [...input.weaknessCodes].sort(),
    ...(input.reviewerRole === 'independent_evaluator' ? {
      decisionReasonCodes: [...input.decisionReasonCodes].sort(),
      prioritizedWeaknessCodes: [...input.prioritizedWeaknessCodes].sort(),
    } : {}),
    sourceSha256,
  };
  validateReviewEntry(entry);
  return entry;
}

function validateReviewEntry(entry) {
  if (!REVIEW_ROLES.has(entry.reviewerRole) || !Array.isArray(entry.scenarioIds) ||
      entry.scenarioIds.length < 1 || entry.scenarioIds.some((id) => !SCENARIO_IDS.has(id)) ||
      new Set(entry.scenarioIds).size !== entry.scenarioIds.length ||
      !entry.ratings || typeof entry.ratings !== 'object' || Array.isArray(entry.ratings) ||
      !validCodeList(entry.reasonCodes, REVIEW_REASON_CODES, 1, 5) ||
      !validCodeList(entry.weaknessCodes, REVIEW_WEAKNESS_CODES, 1, 5) ||
      entry.weaknessCodes.includes('none_observed') && entry.weaknessCodes.length !== 1 ||
      !Array.isArray(entry.terminalClassifications) || entry.terminalClassifications.length > MAX_INVOCATIONS) fail('human-review-invalid');
  if (Object.keys(entry.ratings).length !== REVIEW_RATINGS.length ||
      REVIEW_RATINGS.some((name) => !Number.isInteger(entry.ratings[name]) || entry.ratings[name] < 1 || entry.ratings[name] > 5)) fail('rating-invalid');
  const seen = new Set();
  for (const item of entry.terminalClassifications) {
    if (!/^turn-[0-9]{3}$/u.test(item.turnAlias) || seen.has(item.turnAlias) ||
        !['relevant_answer', 'explained_limitation', 'off_topic', 'receipt_only', 'safe_technical_failure', 'unresolved'].includes(item.classification)) {
      fail('terminal-classification-invalid');
    }
    seen.add(item.turnAlias);
    if (item.scenarioId !== undefined && item.scenarioId !== null &&
        (!SCENARIO_IDS.has(item.scenarioId) || !entry.scenarioIds.includes(item.scenarioId))) {
      fail('terminal-classification-scenario-invalid');
    }
    if (item.clientDisplay !== undefined) validateClientDisplay(item.clientDisplay);
  }
  if (entry.reviewerRole === 'independent_evaluator') {
    if (!DECISIONS.has(entry.decision) ||
        !validCodeList(entry.decisionReasonCodes, DECISION_REASON_CODES, 1, 3) ||
        !validCodeList(entry.prioritizedWeaknessCodes, PRIORITIZED_WEAKNESS_CODES, 0, 3)) fail('evaluator-decision-invalid');
  }
}

function validateClientDisplay(value) {
  exactFields(value, ['observation', 'elapsedMs', 'method']);
  if (!['displayed', 'not_displayed', 'unknown'].includes(value.observation) ||
      !(value.elapsedMs === null || Number.isSafeInteger(value.elapsedMs) && value.elapsedMs >= 0 && value.elapsedMs <= 1_800_000) ||
      !['human_stopwatch', null].includes(value.method) ||
      value.observation === 'displayed' && (value.elapsedMs === null || value.method !== 'human_stopwatch') ||
      value.observation !== 'displayed' && value.elapsedMs !== null) fail('client-display-record-invalid');
}

function applySessionAttestation(run, entry) {
  if (run.provenance !== 'offline_fixture' || run.metadata.humanAttestation ||
      run.entries.some((item) => item.type === 'session_attestation')) fail('duplicate-live-attestation');
  run.provenance = 'human_live';
  const { sourceSha256: _sourceSha256, type: _type, origin: _origin, discoveries: _discoveries, ...metadata } = entry;
  run.metadata = metadata;
}

function livePrerequisites(value) {
  const missing = [];
  if (value.humanAttestation !== true || !['test_administrator', 'independent_evaluator'].includes(value.attestedByRole)) missing.push('human attestation');
  if (!Array.isArray(value.consents)) return [...missing, 'explicit consents'];
  for (const kind of CONSENT_KINDS) {
    const consent = value.consents.find((item) => item.kind === kind);
    if (!consent || consent.approved !== true || !safeLabel(consent.reference) || !validDate(consent.date) ||
        consent.scope !== CONSENT_SCOPES.get(kind) || !['test_administrator', 'independent_evaluator'].includes(consent.approvedByRole)) {
      missing.push(`consent:${kind}`);
    }
  }
  const isolation = value.isolationApproval;
  if (!isolation || isolation.approved !== true || !safeLabel(isolation.reference) || !validDate(isolation.date) ||
      isolation.scope !== 'actual_host_private_admin_isolation') missing.push('actual-host isolation approval');
  return missing;
}

function acceptanceBlockers({ run, session, invocations, admitted, reviews, reviewRoles, unanimous, clientDisplayObserved, clientDisplayUnknown, unclassifiedAdmittedCount, numericEvidenceIncomplete }) {
  const blockers = [];
  if (run.provenance !== 'human_live') blockers.push('Run is explicitly offline_fixture; offline evidence is not live acceptance evidence.');
  if (!session) blockers.push('No human live session attestation, consent record, or actual-host isolation approval.');
  else {
    const missing = livePrerequisites(session);
    if (missing.length) blockers.push(`Live prerequisites missing: ${missing.join(', ')}.`);
    if (!session.personaBriefRevision) blockers.push('Approved persona/premise brief revision is missing.');
    if (session.workload.playerCount < 2) blockers.push('At least two actual human players were not recorded.');
    if (session.workload.durationMinutes > 30) blockers.push('Session exceeds the 30-minute bound.');
    const discoveryRoles = new Set(session.discoveries.map((item) => item.playerRole));
    if (expectedPlayerRoles(session.workload.playerCount).some((role) => !discoveryRoles.has(role))) {
      blockers.push('Unaided discovery results are incomplete for the recorded players.');
    }
  }
  if (!invocations.length) blockers.push('No imported body-free invocation evidence.');
  if (!admitted.length) blockers.push('No admitted invocation is available for human quality review.');
  if (unanimous.relevant_answer + unanimous.explained_limitation === 0) blockers.push('No unanimous human-reviewed relevant answer or explained limitation.');
  if (expectedPlayerRoles(session?.workload.playerCount ?? 2).some((role) => !reviewRoles.has(role))) {
    blockers.push('Independent scores from the recorded actual players are missing.');
  }
  if (!reviewRoles.has('independent_evaluator')) blockers.push('Independent human evaluator review and AC1–AC3 decision are missing.');
  const freeChoiceRoles = new Set(reviews.filter((entry) => entry.scenarioIds.includes('free-conversation')).map((entry) => entry.reviewerRole));
  if (expectedPlayerRoles(session?.workload.playerCount ?? 2).some((role) => !freeChoiceRoles.has(role))) {
    blockers.push('Each player must complete a free-choice exchange and include it in their independent review.');
  }
  if (unclassifiedAdmittedCount > 0) blockers.push('One or more admitted requests lack a human terminal classification.');
  if (clientDisplayObserved === 0) blockers.push('No human-observed client display measurement; server dispatch is not client display.');
  if (clientDisplayUnknown > 0) blockers.push('Some observed terminal display intervals remain unmeasured.');
  if (numericEvidenceIncomplete) blockers.push('Imported aggregate counters and linked invocation evidence are incomplete or inconsistent; do not infer per-invocation timing from aggregate counts.');
  if (!run.metadata.artifactHashes.length) blockers.push('Pinned artifact/source provenance hashes are incomplete.');
  if (PUBLIC_VERSIONS.some((key) => run.metadata.versions[key] === null)) blockers.push('Exact public version labels are incomplete.');
  if (run.metadata.clockBoundary === 'unknown') blockers.push('Clock boundary is unknown; unmeasured intervals remain unknown.');
  return blockers;
}

function expectedPlayerRoles(count) {
  return Array.from({ length: Math.max(2, Math.min(count ?? 2, MAX_PLAYERS)) }, (_, index) => `player_${index + 1}`);
}

function expectedReviewRoles(session) {
  return [...expectedPlayerRoles(session?.workload.playerCount ?? 2), 'independent_evaluator'];
}

function classificationCounts(reviews) {
  const result = Object.create(null);
  for (const review of reviews) for (const item of review.terminalClassifications) {
    result[item.classification] = (result[item.classification] ?? 0) + 1;
  }
  return result;
}

function resolveWorkClass(assignments) {
  if (!assignments || assignments.size === 0) return 'unknown';
  const known = new Set([...assignments].filter((workClass) => workClass !== 'unknown'));
  if (known.size > 1) return 'mixed';
  if (assignments.has('unknown') || known.size === 0) return 'unknown';
  return [...known][0];
}

function summarizeWorkClassCompletion(invocations, workClassByTurn) {
  const groups = Object.fromEntries(WORK_CLASS_BUCKETS.map((workClass) => [workClass, {
    invocationCount: 0,
    admittedCount: 0,
    admittedWithoutMeasuredAnswer: 0,
    technicalTerminalFailureCount: 0,
    answerTerminalAttemptCount: 0,
    answerTerminalAttemptDeliveryResultCounts: Object.create(null),
    outcomeCounts: Object.create(null),
    terminalOutcomeCounts: Object.create(null),
    durations: [],
  }]));
  for (const item of invocations) {
    const group = groups[workClassByTurn.get(item.turnAlias) ?? 'unknown'];
    group.invocationCount += 1;
    group.outcomeCounts[item.outcome] = (group.outcomeCounts[item.outcome] ?? 0) + 1;
    const terminal = item.terminalOutcome ?? 'NO_TERMINAL';
    group.terminalOutcomeCounts[terminal] = (group.terminalOutcomeCounts[terminal] ?? 0) + 1;
    if (item.outcome === 'ADMITTED') group.admittedCount += 1;
    if (item.terminalOutcome === 'ANSWER') {
      group.answerTerminalAttemptCount += 1;
      const deliveryResult = item.terminalDeliveryResult ?? 'NOT_RECORDED';
      group.answerTerminalAttemptDeliveryResultCounts[deliveryResult] =
        (group.answerTerminalAttemptDeliveryResultCounts[deliveryResult] ?? 0) + 1;
    }
    if (item.terminalOutcome !== null && !['ANSWER', 'UNRESOLVED', 'CANCELLED'].includes(item.terminalOutcome)) {
      group.technicalTerminalFailureCount += 1;
    }
    if (isConfirmedAnswer(item)) {
      group.durations.push(BigInt(item.terminalNanos));
    }
  }
  return Object.fromEntries(WORK_CLASS_BUCKETS.map((workClass) => {
    const group = groups[workClass];
    const completion = latencySummary(group.durations);
    return [workClass, {
      invocationCount: group.invocationCount,
      admittedCount: group.admittedCount,
      answerCompletedCount: completion.sampleCount,
      answerTerminalAttemptCount: group.answerTerminalAttemptCount,
      unconfirmedAnswerAttemptCount: group.answerTerminalAttemptCount - completion.sampleCount,
      answerTerminalAttemptDeliveryResultCounts: Object.fromEntries(
        Object.entries(group.answerTerminalAttemptDeliveryResultCounts).sort(([a], [b]) => a.localeCompare(b))),
      answerCompletionRateOverAdmitted: ratio(completion.sampleCount, group.admittedCount),
      admittedWithoutMeasuredAnswer: group.admittedCount - completion.sampleCount,
      technicalTerminalFailureCount: group.technicalTerminalFailureCount,
      outcomeCounts: Object.fromEntries(Object.entries(group.outcomeCounts).sort(([a], [b]) => a.localeCompare(b))),
      terminalOutcomeCounts: Object.fromEntries(Object.entries(group.terminalOutcomeCounts).sort(([a], [b]) => a.localeCompare(b))),
      completion,
      supportStatus: workClass === 'tool-heavy'
        ? scenarios.workClassPolicy?.toolHeavySupport ?? 'UNCLASSIFIED_SUPPORT'
        : workClass === 'mixed' ? 'MIXED_ASSIGNMENT' : workClass === 'unknown' ? 'UNKNOWN_ASSIGNMENT' : 'CATALOG_ASSIGNED',
    }];
  }));
}

function formatWorkClassCompletion(groups) {
  return WORK_CLASS_BUCKETS.map((workClass) => {
    const item = groups[workClass];
    return `${workClass}: n=${item.completion.sampleCount}/${item.admittedCount} admitted (${formatRate(item.answerCompletionRateOverAdmitted.rate)}), median ${formatMs(item.completion.medianMs)}, p95 ${formatMs(item.completion.p95Ms)}, maximum ${formatMs(item.completion.maxMs)}, ANSWER attempts ${formatCounts(item.answerTerminalAttemptDeliveryResultCounts)}, unconfirmed ANSWER attempts ${item.unconfirmedAnswerAttemptCount}, technical terminal failures ${item.technicalTerminalFailureCount}, admitted without confirmed ANSWER ${item.admittedWithoutMeasuredAnswer}, support ${item.supportStatus}`;
  }).join('; ');
}

function isConfirmedAnswer(item) {
  return item.outcome === 'ADMITTED' && item.terminalOutcome === 'ANSWER' &&
    item.terminalDeliveryResult === 'SUBMITTED' && item.terminalNanos !== '-1';
}

function redactEntry(entry) {
  if (entry.type !== 'numeric_evidence') return cloneJson(entry);
  return cloneJson(entry);
}

function aliasFor(run, field, prefix, rawValue) {
  const digest = createHmac('sha256', Buffer.from(run.aliasSalt, 'base64url'))
    .update(`${field}\0${rawValue}`, 'utf8').digest('hex');
  const map = run.aliasIndex[field];
  if (map[digest]) return map[digest];
  const alias = `${prefix}-${String(Object.keys(map).length + 1).padStart(3, '0')}`;
  map[digest] = alias;
  return alias;
}

function validUniqueAliases(index) {
  for (const [field, prefix, limit] of [['players', 'player', 3], ['turns', 'turn', 64], ['requests', 'request', 64]]) {
    const values = Object.values(index[field]);
    if (values.length > limit || new Set(values).size !== values.length ||
        values.some((value, idx) => value !== `${prefix}-${String(idx + 1).padStart(3, '0')}`)) return false;
  }
  return true;
}

function latencySummary(values) {
  const sorted = [...values].sort((a, b) => a < b ? -1 : a > b ? 1 : 0);
  return {
    sampleCount: sorted.length,
    medianMs: sorted.length ? toMs(nearestRank(sorted, 0.5)) : null,
    p95Ms: sorted.length ? toMs(nearestRank(sorted, 0.95)) : null,
    maxMs: sorted.length ? toMs(sorted.at(-1)) : null,
  };
}

function summarizeLatencyGroups(records, keyOf) {
  const groups = new Map();
  for (const record of records) {
    const key = keyOf(record);
    const values = groups.get(key) ?? [];
    values.push(BigInt(record.firstDispatchNanos));
    groups.set(key, values);
  }
  return Object.fromEntries([...groups.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([key, values]) => {
    const late = values.filter((value) => value > 1_000_000_000).length;
    return [key, {
      ...latencySummary(values),
      over1000msCount: late,
      over1000msRate: values.length ? late / values.length : null,
    }];
  }));
}

function nearestRank(sorted, percentile) {
  return sorted[Math.max(0, Math.ceil(percentile * sorted.length) - 1)];
}

function maxBigInt(values) {
  return values.reduce((maximum, value) => value > maximum ? value : maximum, values[0]);
}

function summarizeRequestLinks(invocations) {
  const groups = new Map();
  let unlinkedAdmittedCount = 0;
  let unlinkedCoalescedCount = 0;
  let unlinkedRepeatGuardCount = 0;
  let missingLinkTimestampCount = 0;
  for (const item of invocations) {
    if (item.outcome === 'ADMITTED' && item.requestAlias === null) unlinkedAdmittedCount += 1;
    if (item.outcome === 'COALESCED' && item.requestAlias === null) unlinkedCoalescedCount += 1;
    if (item.outcome === 'RATE_LIMITED' && item.rateLimitStatus === 'REPEAT_GUARD' && item.requestAlias === null) {
      unlinkedRepeatGuardCount += 1;
    }
    if ((['ADMITTED', 'COALESCED'].includes(item.outcome) ||
        item.outcome === 'RATE_LIMITED' && item.rateLimitStatus === 'REPEAT_GUARD') && item.requestAlias !== null &&
        item.requestLinkAtNanos === null) missingLinkTimestampCount += 1;
    if (item.requestAlias === null) continue;
    const group = groups.get(item.requestAlias) ?? { admitted: [], coalesced: [], repeatGuard: [] };
    if (item.outcome === 'ADMITTED') group.admitted.push(item);
    if (item.outcome === 'COALESCED') group.coalesced.push(item);
    if (item.outcome === 'RATE_LIMITED' && item.rateLimitStatus === 'REPEAT_GUARD') group.repeatGuard.push(item);
    groups.set(item.requestAlias, group);
  }
  const coalesced = [...groups.values()].flatMap((group) => group.coalesced);
  const repeatGuard = [...groups.values()].flatMap((group) => group.repeatGuard);
  let resolvedCoalescedCount = 0;
  let resolvedRepeatGuardCount = 0;
  const coalescedOriginalTerminalOutcomeCounts = Object.create(null);
  const repeatGuardOriginalTerminalOutcomeCounts = Object.create(null);
  for (const group of groups.values()) {
    if (group.admitted.length !== 1) continue;
    resolvedCoalescedCount += group.coalesced.length;
    resolvedRepeatGuardCount += group.repeatGuard.length;
    const outcome = group.admitted[0].terminalOutcome ?? 'NO_TERMINAL';
    if (group.coalesced.length > 0) {
      coalescedOriginalTerminalOutcomeCounts[outcome] =
        (coalescedOriginalTerminalOutcomeCounts[outcome] ?? 0) + group.coalesced.length;
    }
    if (group.repeatGuard.length > 0) {
      repeatGuardOriginalTerminalOutcomeCounts[outcome] =
        (repeatGuardOriginalTerminalOutcomeCounts[outcome] ?? 0) + group.repeatGuard.length;
    }
  }
  return {
    linkedInvocationCount: invocations.filter((item) => item.requestAlias !== null).length,
    requestGroupCount: groups.size,
    admittedAnchorCount: [...groups.values()].reduce((count, group) => count + group.admitted.length, 0),
    coalescedCount: coalesced.length,
    resolvedCoalescedCount,
    unresolvedCoalescedCount: coalesced.length - resolvedCoalescedCount + unlinkedCoalescedCount,
    repeatGuardRefusedCount: repeatGuard.length + unlinkedRepeatGuardCount,
    resolvedRepeatGuardCount,
    unresolvedRepeatGuardCount: repeatGuard.length - resolvedRepeatGuardCount + unlinkedRepeatGuardCount,
    unlinkedAdmittedCount,
    unlinkedCoalescedCount,
    unlinkedRepeatGuardCount,
    missingLinkTimestampCount,
    coalescedOriginalTerminalOutcomeCounts: Object.fromEntries(
      Object.entries(coalescedOriginalTerminalOutcomeCounts).sort(([a], [b]) => a.localeCompare(b))),
    repeatGuardOriginalTerminalOutcomeCounts: Object.fromEntries(
      Object.entries(repeatGuardOriginalTerminalOutcomeCounts).sort(([a], [b]) => a.localeCompare(b))),
  };
}

function countBy(values, keyOf) {
  const result = Object.create(null);
  for (const item of values) {
    const key = keyOf(item);
    result[key] = (result[key] ?? 0) + 1;
  }
  return result;
}

function ratio(numerator, denominator) {
  return denominator > 0 ? { numerator, denominator, rate: numerator / denominator } : { numerator, denominator, rate: null };
}

function formatCounts(values) {
  return Object.entries(values).sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => `${key}: ${value}`).join('; ') || 'none';
}

function formatRatio(value) {
  return `${value.numerator}/${value.denominator} (${formatRate(value.rate)})`;
}

function formatCountRate(count, value) {
  return `${count}/${value.denominator} (${formatRate(value.rate)})`;
}

function formatNoDispatchOutcomes(values) {
  return Object.entries(values).sort(([a], [b]) => a.localeCompare(b))
    .map(([outcome, item]) => `${outcome} ${formatCountRate(item.noFirstDispatchCount, item.noFirstDispatchRate)}`)
    .join('; ') || 'none';
}

function formatNoDispatchLifecycles(values) {
  return Object.entries(values).map(([lifecycle, item]) =>
    `${lifecycle} ${formatCountRate(item.noFirstDispatchCount, item.noFirstDispatchRate)}`)
    .join('; ');
}

function formatDispositionLatency(values) {
  return Object.entries(values).sort(([a], [b]) => a.localeCompare(b)).map(([disposition, item]) =>
    `${disposition} n=${item.sampleCount}, >1,000 ms ${item.over1000msCount}/${item.sampleCount} (${formatRate(item.over1000msRate)})`)
    .join('; ') || 'none';
}

function formatUnknown(value) { return value === null ? 'unknown' : String(value); }
function formatBooleanOrUnknown(value) { return value === null ? 'unknown' : value ? 'yes' : 'no'; }

function formatPlayerCounts(values) {
  return Object.entries(values).sort(([a], [b]) => a.localeCompare(b)).map(([alias, row]) => `${alias} (${row.invocations} invocations, ${row.admitted} admitted)`).join('; ') || 'none';
}

function formatReviewerCodes(reviewerRecords) {
  return reviewerRecords.map(({ reviewerRole, codes }) =>
    `${reviewerRole}: ${formatRenderedCodes(codes)}`).join('; ') || 'none';
}

function formatRenderedCodes(codes) {
  return codes.map(({ code, publicRendering }) => `${code} — ${publicRendering}`).join('; ') || 'none';
}

function renderSelectedCodes(codes, renderings) {
  return codes.map((code) => ({ code, publicRendering: renderings[code] }));
}

function codeCounts(codes) {
  return countBy(codes, (code) => code);
}

function validCodeList(value, allowed, minimum, maximum) {
  return Array.isArray(value) && value.length >= minimum && value.length <= maximum &&
    value.every((code) => typeof code === 'string' && allowed.has(code)) &&
    new Set(value).size === value.length;
}

function formatMs(value) { return value === null ? 'unknown' : `${value.toFixed(3)} ms`; }
function formatRate(value) { return value === null ? 'unknown' : `${(value * 100).toFixed(1)}%`; }
function toMs(nanos) {
  const roundedThousandths = (BigInt(nanos) + 500n) / 1_000n;
  const whole = roundedThousandths / 1_000n;
  const fraction = String(roundedThousandths % 1_000n).padStart(3, '0');
  return Number(`${whole}.${fraction}`);
}
function sha256(bytes) { return createHash('sha256').update(bytes).digest('hex'); }
function cloneJson(value) { return JSON.parse(JSON.stringify(value)); }
function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function sealRun(run) {
  run.integrityHash = calculateIntegrityHash(run);
}

function calculateIntegrityHash(run) {
  const payload = {
    schemaVersion: run.schemaVersion,
    runId: run.runId,
    provenance: run.provenance,
    aliasSalt: run.aliasSalt,
    aliasIndex: run.aliasIndex,
    metadata: run.metadata,
    entries: run.entries,
  };
  return sha256(Buffer.from(JSON.stringify(payload), 'utf8'));
}

function safeLabel(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 80 &&
    /^[A-Za-z0-9][A-Za-z0-9 ._:/+()-]*$/u.test(value) && !UUID_RE.test(value) && !/\b\d{6,}\b/u.test(value) &&
    !value.includes('://') && !value.startsWith('/') && !value.includes('..');
}

function validDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/u.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(parsed.valueOf()) && parsed.toISOString().slice(0, 10) === value;
}
function isCounter(value) { return Number.isSafeInteger(value) && value >= 0; }
function isLegacyDuration(value) { return Number.isSafeInteger(value) && (value === -1 || value >= 0); }
function isInt64String(value) {
  if (typeof value !== 'string' || !/^(?:0|-?[1-9][0-9]*)$/u.test(value) || value.length > 20) return false;
  const parsed = BigInt(value);
  return parsed >= -(1n << 63n) && parsed <= (1n << 63n) - 1n;
}
function isNonnegativeLongString(value) { return isInt64String(value) && BigInt(value) >= 0n; }
function isNullableInt64String(value) { return value === null || isInt64String(value); }
function isNullableDurationString(value) { return value === null || isNonnegativeLongString(value); }
function isLatencyString(value) { return value === '-1' || isNonnegativeLongString(value); }
function wrapInt64(value) { return BigInt.asIntN(64, value); }
function monotonicElapsed(end, start) {
  const elapsed = wrapInt64(BigInt(end) - BigInt(start));
  return elapsed < 0n ? 0n : elapsed;
}
function noDispatchSettlementElapsed(item) {
  const settledAt = item.deadlineSettledAtNanos;
  const capturedAt = item.capturedAtNanos ?? item.t0Nanos;
  if (settledAt === null || settledAt === undefined || capturedAt === null || capturedAt === undefined) return null;
  return monotonicElapsed(settledAt, capturedAt);
}
function noDispatchAtOrAfterDeadline(item) {
  const elapsed = noDispatchSettlementElapsed(item);
  return elapsed !== null && elapsed >= 1_000_000_000n;
}
function noDispatchBeforeDeadline(item) {
  const elapsed = noDispatchSettlementElapsed(item);
  return elapsed !== null && elapsed < 1_000_000_000n;
}
function hasFirstReplyDeadlineMiss(item) {
  return item.firstDispatchNanos !== '-1' && BigInt(item.firstDispatchNanos) > 1_000_000_000n ||
    noDispatchAtOrAfterDeadline(item);
}
function durationString(value) { return String(value); }
function int64String(value) { return String(BigInt.asIntN(64, value)); }
function isUuid(value) { return typeof value === 'string' && UUID_RE.test(value); }
function isNullableUuid(value) { return value === null || isUuid(value); }
function isNullableEnum(value, set) { return value === null || set.has(value); }
function isNullableOpaqueId(value) { return value === null || typeof value === 'string' && value.length > 0 && value.length <= 128 && /^[A-Za-z0-9._:-]+$/u.test(value); }
function isNullableWorldId(value) { return value === null || typeof value === 'string' && value.length > 0 && value.length <= 128 && /^[A-Za-z0-9._:/-]+$/u.test(value); }

function exactFields(value, expected) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) fail('entry-object-required');
  const actual = Object.keys(value).sort();
  const fields = [...expected].sort();
  if (actual.length !== fields.length || actual.some((field, index) => field !== fields[index])) fail('entry-field-set-invalid');
}

export function parseStrictJson(source) {
  let index = 0;
  const skip = () => { while (/\s/u.test(source[index] ?? '') && index < source.length) index += 1; };
  const stringToken = () => {
    const start = index;
    if (source[index] !== '"') fail('json-invalid');
    index += 1;
    let escaped = false;
    while (index < source.length) {
      const char = source[index++];
      if (escaped) { escaped = false; continue; }
      if (char === '\\') { escaped = true; continue; }
      if (char === '"') return JSON.parse(source.slice(start, index));
      if (char.charCodeAt(0) < 0x20) fail('json-invalid');
    }
    fail('json-invalid');
  };
  const value = () => {
    skip();
    if (source[index] === '{') {
      index += 1; skip();
      const keys = new Set();
      if (source[index] === '}') { index += 1; return; }
      while (index < source.length) {
        skip();
        const key = stringToken();
        if (keys.has(key)) fail('json-duplicate-key');
        keys.add(key); skip();
        if (source[index++] !== ':') fail('json-invalid');
        value(); skip();
        if (source[index] === '}') { index += 1; return; }
        if (source[index++] !== ',') fail('json-invalid');
      }
      fail('json-invalid');
    }
    if (source[index] === '[') {
      index += 1; skip();
      if (source[index] === ']') { index += 1; return; }
      while (index < source.length) {
        value(); skip();
        if (source[index] === ']') { index += 1; return; }
        if (source[index++] !== ',') fail('json-invalid');
      }
      fail('json-invalid');
    }
    if (source[index] === '"') { stringToken(); return; }
    const tail = source.slice(index);
    const literal = /^(?:true|false|null)(?=$|[\s,}\]])/u.exec(tail);
    if (literal) { index += literal[0].length; return; }
    const number = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?(?=$|[\s,}\]])/u.exec(tail);
    if (number) { index += number[0].length; return; }
    fail('json-invalid');
  };
  try {
    value(); skip();
    if (index !== source.length) fail('json-invalid');
    return JSON.parse(source);
  } catch (error) {
    if (error?.code) throw error;
    fail('json-invalid');
  }
}

async function readPrivateFile(path, maxBytes) {
  return (await readPrivateFileSnapshot(path, maxBytes)).bytes;
}

async function readPrivateFileSnapshot(path, maxBytes) {
  const resolved = await resolveSafeExisting(path);
  await assertPrivateDirectory(dirname(resolved));
  const handle = await open(resolved, fsConstants.O_RDONLY | (fsConstants.O_NOFOLLOW ?? 0));
  try {
    const info = await handle.stat();
    assertPrivateFile(info, maxBytes);
    const bytes = await handle.readFile();
    if (bytes.length > maxBytes) fail('private-file-permissions-or-size-invalid');
    return { bytes, identity: fileIdentity(info) };
  } finally {
    await handle.close();
  }
}

async function resolveSafeExisting(path) {
  if (typeof path !== 'string' || path.length === 0) fail('path-invalid');
  const absolute = resolve(path);
  await assertNoSymlinkComponents(absolute, true);
  return absolute;
}

async function resolveSafeTarget(path, { mustNotExist }) {
  if (typeof path !== 'string' || path.length === 0) fail('path-invalid');
  const absolute = resolve(path);
  await assertNoSymlinkComponents(absolute, false);
  try {
    await lstat(absolute);
    if (mustNotExist) fail('target-exists');
    fail('target-invalid');
  } catch (error) {
    if (error?.code !== 'ENOENT') throw error;
  }
  const parent = dirname(absolute);
  const parentInfo = await lstat(parent);
  if (!parentInfo.isDirectory() || parentInfo.isSymbolicLink()) fail('target-parent-invalid');
  return absolute;
}

async function assertNoSymlinkComponents(absolute, includeLeaf) {
  const root = parse(absolute).root;
  const tail = relative(root, absolute).split(sep).filter(Boolean);
  const end = includeLeaf ? tail.length : Math.max(0, tail.length - 1);
  let current = root;
  for (let index = 0; index < end; index += 1) {
    current = join(current, tail[index]);
    const info = await lstat(current);
    if (info.isSymbolicLink()) fail('symlink-path-rejected');
  }
}

async function createNewPrivateFile(path, contents) {
  let handle;
  let identity;
  let created = false;
  let failure;
  try {
    handle = await open(path, fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_WRONLY | (fsConstants.O_NOFOLLOW ?? 0), 0o600);
    created = true;
    identity = fileIdentity(await handle.stat());
    await handle.chmod(0o600);
    await handle.writeFile(contents, 'utf8');
    await handle.sync();
  } catch (error) {
    failure = error;
  }
  if (handle) {
    try {
      await handle.close();
    } catch (error) {
      failure ??= error;
    }
  }
  if (failure) {
    if (created && identity) await unlinkIfSame(path, identity).catch(() => {});
    throw failure;
  }
  return identity;
}

async function atomicReplacePrivateFile(path, contents, expectedIdentity) {
  const directory = dirname(path);
  const temp = join(directory, `.evaluation-${randomUUID()}.tmp`);
  const tempIdentity = await createNewPrivateFile(temp, contents);
  let moved = false;
  try {
    await assertNoSymlinkComponents(path, true);
    const currentInfo = await lstat(path);
    assertPrivateFile(currentInfo, MAX_BYTES);
    if (!sameIdentity(fileIdentity(currentInfo), expectedIdentity)) fail('run-version-conflict');
    await rename(temp, path);
    moved = true;
  } finally {
    if (!moved) await unlinkIfSame(temp, tempIdentity).catch(() => {});
  }
}

async function withRunLock(directory, operation) {
  const lock = await acquireRunLock(directory);
  let result;
  let operationError;
  try {
    result = await operation(lock);
  } catch (error) {
    operationError = error;
  }

  let releaseError;
  try {
    await releaseRunLock(lock);
  } catch (error) {
    releaseError = error;
  }
  if (releaseError) fail('run-lock-cleanup-failed');
  if (operationError) throw operationError;
  return result;
}

async function acquireRunLock(directory) {
  const resolvedDirectory = await resolveSafeExisting(directory);
  await assertPrivateDirectory(resolvedDirectory);
  const path = join(resolvedDirectory, '.evaluation.lock');
  let handle;
  try {
    handle = await open(
      path,
      fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_WRONLY | (fsConstants.O_NOFOLLOW ?? 0),
      0o600,
    );
  } catch (error) {
    if (error?.code === 'EEXIST' || error?.code === 'ELOOP') {
      let existing;
      try {
        existing = await lstat(path);
      } catch (inspectError) {
        if (inspectError?.code !== 'ENOENT') throw inspectError;
      }
      if (existing?.isSymbolicLink()) fail('run-lock-symlink');
      if (existing && (!existing.isFile() || (existing.mode & 0o077) !== 0 ||
          process.getuid && existing.uid !== process.getuid())) fail('run-lock-permissions-invalid');
      fail('run-lock-busy');
    }
    throw error;
  }

  let identity;
  try {
    identity = fileIdentity(await handle.stat());
    await handle.chmod(0o600);
    await handle.writeFile(`pid=${process.pid}\n`, 'utf8');
    await handle.sync();
    await assertOwnedRunLock({ path, handle, identity });
    return { path, handle, identity };
  } catch (error) {
    if (identity) await unlinkIfSame(path, identity).catch(() => {});
    await handle.close().catch(() => {});
    throw error;
  }
}

async function assertOwnedRunLock(lock) {
  const [handleInfo, pathInfo] = await Promise.all([lock.handle.stat(), lstat(lock.path)]);
  if (!handleInfo.isFile() || !pathInfo.isFile() || pathInfo.isSymbolicLink() ||
      !sameIdentity(fileIdentity(handleInfo), lock.identity) || !sameIdentity(fileIdentity(pathInfo), lock.identity) ||
      (handleInfo.mode & 0o077) !== 0 || (pathInfo.mode & 0o077) !== 0 ||
      process.getuid && (handleInfo.uid !== process.getuid() || pathInfo.uid !== process.getuid())) {
    fail('run-lock-ownership-lost');
  }
}

async function releaseRunLock(lock) {
  let failure;
  try {
    await assertOwnedRunLock(lock);
    await unlink(lock.path);
  } catch (error) {
    failure = error;
  }
  try {
    await lock.handle.close();
  } catch (error) {
    failure ??= error;
  }
  if (failure) fail('run-lock-cleanup-failed');
}

async function assertPrivateDirectory(path) {
  const info = await lstat(path);
  if (!info.isDirectory() || info.isSymbolicLink() || (info.mode & 0o077) !== 0 ||
      process.getuid && info.uid !== process.getuid()) fail('private-directory-permissions-invalid');
}

function assertPrivateFile(info, maxBytes) {
  if (!info.isFile() || (info.mode & 0o077) !== 0 || info.size > maxBytes ||
      process.getuid && info.uid !== process.getuid()) fail('private-file-permissions-or-size-invalid');
}

function fileIdentity(info) {
  return { dev: info.dev, ino: info.ino };
}

function sameIdentity(left, right) {
  return left?.dev === right?.dev && left?.ino === right?.ino;
}

async function unlinkIfSame(path, identity) {
  try {
    const info = await lstat(path);
    if (!info.isFile() || info.isSymbolicLink() || !sameIdentity(fileIdentity(info), identity)) return;
    await unlink(path);
  } catch (error) {
    if (error?.code !== 'ENOENT') throw error;
  }
}

async function removeCreatedFiles(createdFiles) {
  for (let index = createdFiles.length - 1; index >= 0; index -= 1) {
    await unlinkIfSame(createdFiles[index].path, createdFiles[index].identity);
  }
}

async function removeEmptyDirectoryIfSame(path, identity) {
  try {
    const info = await lstat(path);
    if (!info.isDirectory() || info.isSymbolicLink() || !sameIdentity(fileIdentity(info), identity)) return;
    await rmdir(path);
  } catch (error) {
    if (error?.code !== 'ENOENT' && error?.code !== 'ENOTEMPTY' && error?.code !== 'EEXIST') throw error;
  }
}

async function chmodOwnerOnly(path, mode) {
  const { chmod } = await import('node:fs/promises');
  await chmod(path, mode);
}

function fail(code) {
  const error = new Error(code);
  error.code = code;
  throw error;
}
