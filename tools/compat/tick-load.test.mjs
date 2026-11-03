import assert from 'node:assert/strict';
import { createHash, createHmac, randomBytes, randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { chmod, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { connect } from 'node:net';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import test from 'node:test';
import {
  computeHarnessSourcesSha256,
  assessRecordedEpochEvidence,
  parseTickLoadArgs,
  readAuthorizedPlayers,
  sanitizeJfrEvents,
  summarizePhase,
  validateHarnessMetadata,
  validateLoopbackEndpoint,
  verifyHarnessArtifact,
} from './tick-load.mjs';
import { startFixturePeer } from './tick-load-peer.mjs';
import { getFreeLoopbackPort } from './clean-install.mjs';
import { readZipEntries, verifyJar } from './verify-server-only.mjs';

const ROOT = resolve(new URL('../..', import.meta.url).pathname);
const DEPLOYMENT_BYTES = await readFile(join(ROOT, 'compatibility/deployment.json'));
const DEPLOYMENT = JSON.parse(DEPLOYMENT_BYTES.toString('utf8'));
const DEPLOYMENT_SHA256 = createHash('sha256').update(DEPLOYMENT_BYTES).digest('hex');
const requireCompanion = createRequire(join(ROOT, 'companion/package.json'));
const { WebSocket } = requireCompanion('ws');
const PLAYER_ID = '11111111-1111-4111-8111-111111111111';
const SERVER_EPOCH_ID = '33333333-3333-4333-8333-333333333333';

function runnerArgs(extra = [], peerMode = 'fixture') {
  return [
    '--workdir', '/private/var/tmp/worldspeaker-compat03-run',
    '--players-file', '/private/var/tmp/worldspeaker-compat03-players.txt',
    '--peer', peerMode, '--start-server', '--accept-eula', ...extra,
  ];
}

test('tick-load runner fails closed on missing EULA authority and provider mode', () => {
  assert.throws(() => parseTickLoadArgs([
    '--workdir', '/private/var/tmp/run', '--players-file', '/private/var/tmp/players', '--peer', 'fixture',
  ]), (error) => error.code === 'tick-load-requires-start-server-and-accept-eula');
  assert.throws(() => parseTickLoadArgs(runnerArgs([], 'openclaw')), (error) =>
    error.code === 'openclaw-peer-requires-separate-provider-authorization');
  assert.throws(() => parseTickLoadArgs(runnerArgs(['--start-server'])), (error) =>
    error.code.startsWith('duplicate-or-invalid-option'));
  assert.throws(() => parseTickLoadArgs([
    '--workdir', 'relative', '--players-file', '/private/var/tmp/players', '--peer', 'fixture',
    '--start-server', '--accept-eula',
  ]), (error) => error.code === 'workdir-must-be-absolute');
});

test('fixture endpoint validation permits only explicit loopback WebSocket endpoints', () => {
  assert.equal(validateLoopbackEndpoint('ws://127.0.0.1:18432/').hostname, '127.0.0.1');
  for (const endpoint of [
    'ws://localhost:18432', 'ws://0.0.0.0:18432', 'ws://[::1]:18432',
    'wss://127.0.0.1:18432', 'ws://user:pass@127.0.0.1:18432',
    'ws://127.0.0.1:18432/other', 'ws://127.0.0.1:80',
  ]) assert.throws(() => validateLoopbackEndpoint(endpoint));
});

test('authorized player input is owner-only, bounded, normalized, and duplicate-safe', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'worldspeaker-compat03-player-test-'));
  await chmod(directory, 0o700);
  t.after(() => rm(directory, { recursive: true, force: true }));
  const playersPath = join(directory, 'players.txt');
  await writeFile(playersPath, `${PLAYER_ID.toUpperCase()} PlayerOne\n`, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
  assert.deepEqual(await readAuthorizedPlayers(playersPath), [{ id: PLAYER_ID, name: 'PlayerOne' }]);
  await chmod(playersPath, 0o644);
  await assert.rejects(readAuthorizedPlayers(playersPath), (error) =>
    error.code === 'players-file-permissions-or-size-invalid');
});

test('harness metadata is source-fresh and its JAR stays outside production artifacts', async () => {
  const sourceSha256 = await computeHarnessSourcesSha256();
  const metadata = {
    schemaVersion: 1,
    modId: 'worldspeaker_compat_load_harness',
    deploymentId: DEPLOYMENT.deploymentId,
    deploymentManifestSha256: DEPLOYMENT_SHA256,
    harnessSourcesSha256: sourceSha256,
    entrypoint: 'org.worldspeaker.load.WorldspeakerTickLoadHarness',
  };
  assert.equal(validateHarnessMetadata(metadata, {
    deployment: DEPLOYMENT, deploymentSha256: DEPLOYMENT_SHA256, sourceSha256,
  }), true);
  assert.throws(() => validateHarnessMetadata({ ...metadata, harnessSourcesSha256: '0'.repeat(64) }, {
    deployment: DEPLOYMENT, deploymentSha256: DEPLOYMENT_SHA256, sourceSha256,
  }), (error) => error.code === 'load-harness-stale-or-manifest-mismatch');

  const harnessPath = join(ROOT, 'build/load-harness',
    `${DEPLOYMENT.mod.id}-${DEPLOYMENT.mod.version}-compat-load-harness.jar`);
  const harness = await verifyHarnessArtifact(harnessPath, {
    deployment: DEPLOYMENT, deploymentSha256: DEPLOYMENT_SHA256, sourceSha256,
  });
  assert.ok(harness.bytes > 0);
  assert.match(harness.sha256, /^[a-f0-9]{64}$/u);

  const libraries = join(ROOT, 'build/libs');
  const { readdir } = await import('node:fs/promises');
  const names = (await readdir(libraries)).filter((name) => name.endsWith('.jar') &&
    !name.includes('sources') && !name.includes('compat-load-harness'));
  let productionJars = 0;
  for (const name of names) {
    const jarPath = join(libraries, name);
    let entries;
    try { entries = readZipEntries(await readFile(jarPath)); } catch { continue; }
    if (!entries.has('fabric.mod.json')) continue;
    verifyJar(jarPath);
    productionJars += 1;
    assert.equal(entries.has('worldspeaker-load-harness.json'), false);
    assert.equal([...entries.keys()].some((entry) => entry.startsWith('org/worldspeaker/load/')), false);
  }
  assert.ok(productionJars > 0, 'run remapJar before this artifact-boundary check');
});

test('JFR sanitizer projects only bounded thread and I/O fields', () => {
  const secret = 'fixture-private-secret-value';
  const body = 'private conversation body';
  const endpoint = 'ws://127.0.0.1:18432/private/path';
  const result = sanitizeJfrEvents([
    {
      type: 'jdk.SocketWrite',
      values: {
        startTime: '2026-10-28T12:00:00Z', duration: 'PT0.001S', bytesWritten: 128,
        eventThread: { javaThreadId: 7, osName: 'private-thread-name' },
        uri: endpoint, endpoint, path: '/private/home', secret, body,
        stackTrace: { frames: [{ method: { type: { name: 'org.worldspeaker.Transport' }, name: 'send' }, lineNumber: 42 }] },
      },
    },
    { type: 'jdk.UnsafeUnknownEvent', values: { secret, body, endpoint } },
  ]);
  assert.equal(result.eventCount, 1);
  assert.equal(result.eventCounts['jdk.SocketWrite'], 1);
  const output = result.bytes.toString('utf8');
  assert.match(output, /"bytesWritten":128/u);
  for (const privateValue of [secret, body, endpoint, 'private/home', 'private-thread-name']) {
    assert.equal(output.includes(privateValue), false);
  }
});

test('phase report summarizes first-public dispatch by source and disposition with an open-ended bucket', () => {
  const tickBounds = [250_000, 500_000, Number.MAX_SAFE_INTEGER];
  const replyBounds = [250_000_000, 500_000_000, 30_000_000_000, Number.MAX_SAFE_INTEGER];
  const sources = [
    'LOCAL_ACKNOWLEDGEMENT', 'INTEGRATION_ANSWER', 'INTEGRATION_FAILURE', 'LOCAL_SAFE_RESPONSE',
    'LOCAL_REJECTION', 'LOCAL_UNAVAILABLE', 'LOCAL_COALESCED', 'COMMAND_HELP', 'LOCAL_FOLLOWUP_END',
    'COMMAND_GUIDANCE', 'NATURAL_INVALID', 'LOCAL_UNAUTHENTICATED', 'LOCAL_DENIED', 'LOCAL_BUSY',
  ];
  const dispositions = ['ACKNOWLEDGED', 'ANSWERED', 'FAILED', 'REJECTED', 'UNAVAILABLE', 'COALESCED', 'HELP',
    'FOLLOWUP_ENDED', 'INVALID', 'DENIED', 'UNAUTHENTICATED', 'BUSY'];
  const firstPublicDispatchHistograms = emptyFirstPublicHistograms(sources.length, dispositions.length, replyBounds.length);
  firstPublicDispatchHistograms[1][1] = [0, 1, 0, 1];
  const summary = {
    tickHistogramUpperNanos: tickBounds,
    firstPublicDispatchMetricsAvailable: true,
    firstPublicDispatchSourceNames: sources,
    firstPublicDispatchDispositionNames: dispositions,
    firstPublicDispatchUpperNanos: replyBounds,
    integrationCounterNames: [],
  };
  const phase = summarizePhase({
    name: 'normal',
    ticks: 3,
    attempts: 3,
    commands: 3,
    rejected: 0,
    distinctOtherRequests: 0,
    repeatAttempts: 0,
    integrationReadyTicks: 3,
    authorizedOnlineHighWater: 1,
    authorizedJoins: 1,
    authorizedLeaves: 0,
    authorizedRejoins: 0,
    maxTickNanos: 500_000,
    overFiftyMillis: 0,
    tickHistogram: [1, 2, 0],
    integrationCounters: [],
    firstPublicDispatchHistograms,
  }, summary, '');
  assert.equal(phase.firstPublicDispatchCount, 2);
  assert.equal(phase.firstPublicDispatchOverflowCount, 1);
  assert.equal(phase.firstPublicDispatchBySourceDisposition.INTEGRATION_ANSWER.ANSWERED.count, 2);
  assert.equal(phase.firstPublicDispatchP50UpperBoundNanos, 500_000_000);
  assert.equal(phase.firstPublicDispatchP95UpperBoundNanos, null,
    'an open-ended tail bucket must not be misreported as a precise upper bound');

  const unavailable = firstPublicDispatchHistograms;
  unavailable[5][4] = [1, 0, 0, 0];
  const sourceDisposition = summarizePhase({
    name: 'unavailable', tickHistogram: [1, 0, 0], firstPublicDispatchHistograms: unavailable,
    integrationCounters: [],
  }, summary, '');
  assert.equal(sourceDisposition.firstPublicDispatchBySourceDisposition.LOCAL_UNAVAILABLE.UNAVAILABLE.count, 1);

  const missing = summarizePhase({ name: 'normal', tickHistogram: [1, 0, 0], integrationCounters: [] }, summary, '');
  assert.equal(missing.firstPublicDispatchCount, null, 'missing histograms must not be fabricated as zero-success metrics');
  assert.equal(missing.firstPublicDispatchBySourceDisposition, null);
});

test('deadline evidence reconciles every outcome and leaves an absent dispatch maximum unknown', () => {
  const counterNames = [
    'FIRST_REPLY_DEADLINE_MISSES', 'INVOCATIONS_CAPTURED', 'INTERACTION_RECEIPT_EVIDENCE_MISSING',
    'FIRST_REPLY_ON_TIME', 'FIRST_REPLY_LATE', 'FIRST_REPLY_NO_DISPATCH',
    'FIRST_REPLY_NO_DISPATCH_SETTLED', 'FAILED_PUBLIC_DISPATCHES', 'UNKNOWN_PUBLIC_DISPATCHES',
  ];
  const set = (values, name, value) => { values[counterNames.indexOf(name)] = value; };
  const sources = [
    'LOCAL_ACKNOWLEDGEMENT', 'INTEGRATION_ANSWER', 'INTEGRATION_FAILURE', 'LOCAL_SAFE_RESPONSE',
    'LOCAL_REJECTION', 'LOCAL_UNAVAILABLE', 'LOCAL_COALESCED', 'COMMAND_HELP', 'LOCAL_FOLLOWUP_END',
    'COMMAND_GUIDANCE', 'NATURAL_INVALID', 'LOCAL_UNAUTHENTICATED', 'LOCAL_DENIED', 'LOCAL_BUSY',
  ];
  const dispositions = ['ACKNOWLEDGED', 'ANSWERED', 'FAILED', 'REJECTED', 'UNAVAILABLE', 'COALESCED', 'HELP',
    'FOLLOWUP_ENDED', 'INVALID', 'DENIED', 'UNAUTHENTICATED', 'BUSY'];
  const bounds = [250_000_000, Number.MAX_SAFE_INTEGER];
  const summaryFor = (phase) => ({
    schemaVersion: 3, tickHistogramUpperNanos: [250_000, Number.MAX_SAFE_INTEGER],
    firstPublicDispatchMetricsAvailable: true, firstPublicDispatchSourceNames: sources,
    firstPublicDispatchDispositionNames: dispositions, firstPublicDispatchUpperNanos: bounds,
    integrationCounterNames: counterNames, maxFirstPublicDispatchNanos: 1_100_000_000,
    phases: [phase],
  });
  const createPhase = () => ({
    name: 'normal', ticks: 1, tickHistogram: [1, 0], integrationCounters: Array(counterNames.length).fill(0),
    firstPublicDispatchHistograms: emptyFirstPublicHistograms(sources.length, dispositions.length, bounds.length),
  });

  const mixed = createPhase();
  set(mixed.integrationCounters, 'INVOCATIONS_CAPTURED', 6);
  set(mixed.integrationCounters, 'FIRST_REPLY_ON_TIME', 1);
  set(mixed.integrationCounters, 'FIRST_REPLY_LATE', 1);
  set(mixed.integrationCounters, 'FIRST_REPLY_NO_DISPATCH', 2);
  set(mixed.integrationCounters, 'FIRST_REPLY_NO_DISPATCH_SETTLED', 1);
  set(mixed.integrationCounters, 'INTERACTION_RECEIPT_EVIDENCE_MISSING', 1);
  set(mixed.integrationCounters, 'FIRST_REPLY_DEADLINE_MISSES', 2);
  mixed.firstPublicDispatchHistograms[1][1][1] = 1;
  const mixedSummary = summarizePhase(mixed, summaryFor(mixed), '');
  assert.equal(mixedSummary.firstReplyDeadline.noDispatchDeadlineCount, 1);
  assert.equal(mixedSummary.firstReplyDeadline.noDispatchSettledCount, 1);
  assert.equal(mixedSummary.firstReplyDeadline.captureEvidenceMissingCount, 1);
  assert.equal(mixedSummary.firstReplyDeadline.pendingCount, 1);
  assert.equal(mixedSummary.firstReplyDeadline.linkedKnownMissCount, 2);
  assert.equal(mixedSummary.firstReplyDeadline.reconciliationStatus, 'matches_linked_all_outcome_misses');
  assert.equal(mixedSummary.firstReplyDeadline.actualMaximumDispatchNanos, 1_100_000_000);
  assert.equal(mixedSummary.firstReplyDeadline.status, 'FAILED',
    'any recorded deadline miss fails even when another invocation remains pending');
  assert.equal(mixedSummary.firstReplyDeadline.available, true, 'pending is explicit, not invalid evidence');

  const noDispatch = createPhase();
  set(noDispatch.integrationCounters, 'INVOCATIONS_CAPTURED', 1);
  const noDispatchSummary = summarizePhase(noDispatch, summaryFor(noDispatch), '');
  assert.equal(noDispatchSummary.firstReplyDeadline.pendingCount, 1);
  assert.equal(noDispatchSummary.firstReplyDeadline.actualMaximumDispatchNanos, null,
    'an empty dispatch sample never becomes a zero-latency maximum');
  assert.equal(noDispatchSummary.firstReplyDeadline.status, 'INCONCLUSIVE');
  assert.equal(noDispatchSummary.firstReplyDeadline.available, true);

  const mismatched = createPhase();
  set(mismatched.integrationCounters, 'INVOCATIONS_CAPTURED', 1);
  set(mismatched.integrationCounters, 'FIRST_REPLY_DEADLINE_MISSES', 1);
  const mismatchedSummary = summarizePhase(mismatched, summaryFor(mismatched), '');
  assert.equal(mismatchedSummary.firstReplyDeadline.available, false);
  assert.equal(mismatchedSummary.firstReplyDeadline.reconciliationStatus, 'aggregate_linked_miss_mismatch');
});

test('synthetic recorded reports require complete phases, capture, and the distinct overload contract', () => {
  const expectedPhases = ['idle', 'normal', 'overload', 'unavailable'];
  const complete = makeRecordedEpochReport(expectedPhases);
  const result = assessRecordedEpochEvidence(complete);
  assert.equal(result.complete, true, result.failures.join(', '));
  assert.equal(result.capacityEvidence.observedHighWater.active, 1);
  assert.equal(result.capacityEvidence.observedHighWater.globalPending, 16);
  assert.equal(result.capacityEvidence.observedHighWater.perPlayerPending, 2);
  const normal = result.phaseSummaries.find((phase) => phase.name === 'normal');
  const overload = result.phaseSummaries.find((phase) => phase.name === 'overload');
  const unavailable = result.phaseSummaries.find((phase) => phase.name === 'unavailable');
  assert.equal(overload.attemptedRatePerSecond, 20);
  assert.equal(normal.firstPublicDispatchBySourceDisposition.INTEGRATION_ANSWER.ANSWERED.count, 1);
  assert.equal(overload.firstPublicDispatchBySourceDisposition.LOCAL_SAFE_RESPONSE.FAILED.count, 1);
  assert.equal(overload.firstPublicDispatchBySourceDisposition.LOCAL_REJECTION.REJECTED.count, 1);
  assert.equal(overload.firstPublicDispatchBySourceDisposition.LOCAL_COALESCED.COALESCED.count, 1);
  assert.equal(unavailable.firstPublicDispatchBySourceDisposition.INTEGRATION_FAILURE.FAILED.count, 1);
  assert.equal(unavailable.firstPublicDispatchBySourceDisposition.LOCAL_UNAVAILABLE.UNAVAILABLE.count, 1);
  assert.equal(result.firstReplyDeadline.status, 'PASS');
  assert.equal(result.firstReplyDeadline.pendingCount, 0);

  const deadlineMiss = makeRecordedEpochReport(expectedPhases);
  const normalCounters = deadlineMiss.summary.phases.find((phase) => phase.name === 'normal').integrationCounters;
  normalCounters[deadlineMiss.summary.integrationCounterNames.indexOf('FIRST_REPLY_DEADLINE_MISSES')] = 1;
  normalCounters[deadlineMiss.summary.integrationCounterNames.indexOf('FIRST_REPLY_LATE')] = 1;
  normalCounters[deadlineMiss.summary.integrationCounterNames.indexOf('FIRST_REPLY_ON_TIME')] = 0;
  deadlineMiss.summary.maxFirstPublicDispatchNanos = 1_100_000_000;
  deadlineMiss.summary.phases.find((phase) => phase.name === 'normal').firstPublicDispatchHistograms[1][1] = [0, 1];
  const missedResult = assessRecordedEpochEvidence(deadlineMiss);
  assert.equal(missedResult.firstReplyDeadline.status, 'FAILED');
  assert.equal(missedResult.firstReplyDeadline.deadlineMissCount, 1);
  assert.ok(missedResult.failures.includes('firstreply-deadline-miss'));

  const missingFirstPublic = makeRecordedEpochReport(expectedPhases);
  missingFirstPublic.summary.firstPublicDispatchMetricsAvailable = false;
  missingFirstPublic.summary.phases.find((phase) => phase.name === 'normal').firstPublicDispatchHistograms = null;
  const missingResult = assessRecordedEpochEvidence(missingFirstPublic);
  assert.equal(missingResult.complete, false);
  assert.ok(missingResult.failures.includes('firstpublic-dispatch-metrics-missing-or-invalid'));
  assert.equal(missingResult.phaseSummaries.find((phase) => phase.name === 'normal').firstPublicDispatchCount, null,
    'missing first-public metrics remain unavailable even when the independent answer counter is present');

  const missingSample = makeRecordedEpochReport(expectedPhases);
  missingSample.summary.phases.find((phase) => phase.name === 'normal').firstPublicDispatchHistograms =
    emptyFirstPublicHistograms(7, 6, 2);
  const missingSampleResult = assessRecordedEpochEvidence(missingSample);
  assert.equal(missingSampleResult.complete, false);
  assert.ok(missingSampleResult.failures.includes('normal-firstpublic-dispatch-outcome-missing'),
    'a positive answer counter cannot stand in for absent first-public samples');

  const coalescedOverload = makeRecordedEpochReport(expectedPhases);
  coalescedOverload.summary.phases.find((phase) => phase.name === 'overload').distinctInvocations = 1;
  const distinctResult = assessRecordedEpochEvidence(coalescedOverload);
  assert.equal(distinctResult.complete, false);
  assert.ok(distinctResult.failures.includes('overload-distinct-workload-volume-incomplete'));

  const incompleteCapture = makeRecordedEpochReport(expectedPhases);
  incompleteCapture.summary.captureIncomplete = true;
  incompleteCapture.summary.phases.find((phase) => phase.name === 'normal').ticks = 0;
  const incompleteResult = assessRecordedEpochEvidence(incompleteCapture);
  assert.equal(incompleteResult.complete, false);
  assert.ok(incompleteResult.failures.includes('capture-incomplete-or-authorized-list-not-loaded'));
  assert.ok(incompleteResult.failures.includes('required-phase-incomplete-normal'));

  const insufficientPlayers = makeRecordedEpochReport(expectedPhases);
  insufficientPlayers.summary.phases.find((phase) => phase.name === 'overload').capacityOnlineHighWater = 3;
  const playerResult = assessRecordedEpochEvidence(insufficientPlayers);
  assert.equal(playerResult.complete, false);
  assert.ok(playerResult.failures.includes('overload-authorized-capacity-not-reached'));
});

test('synthetic seven-repeat report keeps intentional repeats separate from distinct requests', () => {
  const report = makeRecordedEpochReport(['repeat']);
  const repeat = report.summary.phases.find((phase) => phase.name === 'repeat');
  repeat.attempts = 8;
  repeat.distinctInvocations = 2;
  repeat.repeatAttempts = 7;
  repeat.repeatRejected = 4;
  repeat.distinctOtherRequests = 1;
  const result = assessRecordedEpochEvidence(report);
  assert.equal(result.complete, true, result.failures.join(', '));

  repeat.distinctOtherRequests = 0;
  const noOtherPlayer = assessRecordedEpochEvidence(report);
  assert.equal(noOtherPlayer.complete, false);
  assert.ok(noOtherPlayer.failures.includes('repeat-regression-incomplete'));
});

function makeRecordedEpochReport(expectedPhases) {
  const bounds = [250_000, Number.MAX_SAFE_INTEGER];
  const firstPublicBounds = [250_000_000, Number.MAX_SAFE_INTEGER];
  const firstPublicSources = [
    'LOCAL_ACKNOWLEDGEMENT', 'INTEGRATION_ANSWER', 'INTEGRATION_FAILURE', 'LOCAL_SAFE_RESPONSE',
    'LOCAL_REJECTION', 'LOCAL_UNAVAILABLE', 'LOCAL_COALESCED', 'COMMAND_HELP', 'LOCAL_FOLLOWUP_END',
    'COMMAND_GUIDANCE', 'NATURAL_INVALID', 'LOCAL_UNAUTHENTICATED', 'LOCAL_DENIED', 'LOCAL_BUSY',
  ];
  const firstPublicDispositions = ['ACKNOWLEDGED', 'ANSWERED', 'FAILED', 'REJECTED', 'UNAVAILABLE', 'COALESCED',
    'HELP', 'FOLLOWUP_ENDED', 'INVALID', 'DENIED', 'UNAUTHENTICATED', 'BUSY'];
  const integrationCounterNames = [
    'FIRST_REPLY_DEADLINE_MISSES', 'TERMINAL_DUPLICATES', 'MAILBOX_OVERFLOWS', 'STALE_OUTPUTS',
    'TICK_BUDGET_EXHAUSTED', 'PENDING_EXPIRED', 'UPSTREAM_REJECTIONS', 'ACK_CONTEXT_FAILURES',
    'CONNECTION_FAILURES', 'RECONNECT_EXHAUSTED', 'REQUESTS_ADMITTED', 'ANSWERS_DELIVERED',
    'PROGRESS_DISPATCHES', 'PROGRESS_FAILURES', 'PROGRESS_MISSED', 'PROGRESS_UNKNOWN',
    'PROGRESS_CONTEXT_FAILURES', 'UNAVAILABLE_DISPOSITIONS', 'INVOCATIONS_CAPTURED', 'INTERACTION_HELP',
    'INTERACTION_INVALID', 'INTERACTION_DENIED', 'INTERACTION_UNAUTHENTICATED',
    'INTERACTION_UNAVAILABLE_BEFORE_BOOTSTRAP', 'INTERACTION_RECEIPT_EVIDENCE_MISSING', 'INTERACTION_UNAVAILABLE',
    'INTERACTION_BUSY', 'INTERACTION_COALESCED', 'INTERACTION_ADMITTED', 'INTERACTION_FOLLOWUP_ENDED',
    'FAILED_PUBLIC_DISPATCHES', 'INTERACTION_EVIDENCE_DROPPED', 'FIRST_REPLY_ON_TIME', 'FIRST_REPLY_LATE',
    'FIRST_REPLY_NO_DISPATCH', 'FIRST_REPLY_NO_DISPATCH_SETTLED', 'UNKNOWN_PUBLIC_DISPATCHES',
    'CONTROL_QUEUE_OVERFLOWS', 'DISPOSITION_QUEUE_OVERFLOWS',
  ];
  const durationMs = { idle: 300_000, normal: 300_000, overload: 300_000, unavailable: 300_000,
    recovery: 300_000, repeat: 6_000, 'stop-backlog': 6_000 };
  const measuredRows = [];
  for (const phaseName of expectedPhases) measuredRows.push(sampleRow(phaseName, false));
  measuredRows.push(sampleRow('idle', true));
  const phases = ['idle', 'normal', 'overload', 'unavailable', 'recovery', 'repeat', 'stop-backlog'].map((name) => {
    const observed = expectedPhases.includes(name);
    const counters = integrationCounterNames.map((counter) => {
      if (name === 'overload' && counter === 'REQUESTS_ADMITTED') return 17;
      if (name === 'overload' && counter === 'PENDING_EXPIRED') return 1;
      if (['normal', 'recovery'].includes(name) && counter === 'INVOCATIONS_CAPTURED') return 1;
      if (['normal', 'recovery'].includes(name) && counter === 'FIRST_REPLY_ON_TIME') return 1;
      if (['normal', 'recovery'].includes(name) && counter === 'ANSWERS_DELIVERED') return 1;
      if (name === 'unavailable' && counter === 'RECONNECT_EXHAUSTED') return 1;
      return 0;
    });
    const firstPublicDispatchHistograms = emptyFirstPublicHistograms(
      firstPublicSources.length, firstPublicDispositions.length, firstPublicBounds.length);
    if (['normal', 'recovery'].includes(name)) {
      firstPublicDispatchHistograms[1][1][0] = 1;
    } else if (name === 'overload') {
      firstPublicDispatchHistograms[3][2][0] = 1;
      firstPublicDispatchHistograms[4][3][0] = 1;
      firstPublicDispatchHistograms[6][5][0] = 1;
    } else if (name === 'unavailable') {
      firstPublicDispatchHistograms[2][2][0] = 1;
      firstPublicDispatchHistograms[5][4][0] = 1;
    }
    return {
      name,
      ticks: observed ? 1 : 0,
      elapsedNanos: observed ? durationMs[name] * 1_000_000 : 0,
      sampleRecords: observed ? 1 : 0,
      attempts: name === 'overload' ? 6000 : 0,
      distinctInvocations: name === 'overload' ? 6000 : 0,
      commands: name === 'overload' ? 17 : 0,
      rejected: name === 'overload' ? 1 : 0,
      repeatAttempts: 0,
      repeatAccepted: 0,
      repeatRejected: 0,
      distinctOtherAttempts: 0,
      distinctOtherRequests: 0,
      outstandingAdmissionHighWater: name === 'overload' ? 17 : 0,
      perPlayerAdmissionHighWater: name === 'overload' ? 2 : 0,
      playersAtTwoAdmissionsHighWater: name === 'overload' ? 8 : 0,
      capacityOnlineHighWater: name === 'overload' ? 8 : 0,
      capacityPerPlayerAdmissionHighWater: name === 'overload' ? 2 : 0,
      capacityPlayersAtTwoAdmissionsHighWater: name === 'overload' ? 8 : 0,
      capacityObservationTicks: name === 'overload' ? 1 : 0,
      integrationReadyTicks: observed ? 1 : 0,
      authorizedOnlineHighWater: name === 'overload' ? 8 : 0,
      authorizedJoins: 0,
      authorizedLeaves: 0,
      authorizedRejoins: 0,
      maxTickNanos: 250_000,
      overFiftyMillis: 0,
      tickHistogram: [1, 0],
      integrationCounters: counters,
      firstPublicDispatchHistograms,
    };
  });
  return {
    summary: {
      schemaVersion: 3,
      maxFirstPublicDispatchNanos: 125_000_000,
      sampleRecords: measuredRows.length - 1,
      warmupSampleRecords: 1,
      warmupTicks: 1,
      warmupElapsedNanos: 120_000_000_000,
      authorizedListLoaded: true,
      authorizedPlayerCount: 8,
      onlineAuthorizedHighWater: 8,
      captureIncomplete: false,
      workloadContract: {
        overloadAttemptedPerSecond: 20,
        overloadDistinctPerSecond: 20,
        overloadDurationMs: 300_000,
        overloadExpectedAttempts: 6000,
        overloadExpectedDistinctInvocations: 6000,
        repeatAttempts: 7,
        activeCapacity: 1,
        globalPendingCapacity: 16,
        perPlayerPendingCapacity: 2,
      },
      tickHistogramUpperNanos: bounds,
      firstPublicDispatchMetricsAvailable: true,
      firstPublicDispatchSourceNames: firstPublicSources,
      firstPublicDispatchDispositionNames: firstPublicDispositions,
      firstPublicDispatchUpperNanos: firstPublicBounds,
      integrationCounterNames,
      phases,
    },
    samples: `${measuredRows.join('\n')}\n`,
    peerStatus: {
      schemaVersion: 1,
      event: 'stopped',
      listening: false,
      requestCount: 1,
      answerCount: 0,
      requestsByPhase: { overload: 1 },
      delayedRequestsByPhase: { overload: 1 },
      delayedPendingHighWaterByPhase: { overload: 1 },
    },
    expectedPhases,
  };
}

function emptyFirstPublicHistograms(sourceCount, dispositionCount, bucketCount) {
  return Array.from({ length: sourceCount }, () =>
    Array.from({ length: dispositionCount }, () => Array(bucketCount).fill(0)));
}

function sampleRow(phaseName, warmup) {
  return [
    'sample', 'synthetic-run', phaseName, warmup, true, true, 1, 50_000_000, 250_000,
    0, 0, 0, 8, 0, 1024, 0, 0, 5,
  ].join(',');
}

test('loopback fixture authenticates, answers, and bounds delayed backlog', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'worldspeaker-compat03-fixture-test-'));
  let peer;
  let socket;
  t.after(async () => {
    if (socket && socket.readyState !== WebSocket.CLOSED) socket.terminate();
    try {
      await peer?.close();
    } finally {
      await rm(directory, { recursive: true });
    }
  });
  await chmod(directory, 0o700);
  const secret = randomBytes(32).toString('base64url');
  const binding = {
    serverId: `test-${randomUUID()}`,
    worldId: `world-${randomUUID()}`,
    characterId: `character-${randomUUID()}`,
    agentId: 'main',
  };
  const config = {
    schemaVersion: 1,
    runId: randomUUID(),
    port: await getFreeLoopbackPort(),
    secret,
    manifestSha256: DEPLOYMENT_SHA256,
    binding,
    authorizedPlayerIds: [PLAYER_ID, '22222222-2222-4222-8222-222222222222'],
  };
  const controlPath = join(directory, 'control.json');
  const statusPath = join(directory, 'status.json');
  await writeJsonPrivate(controlPath, { schemaVersion: 1, runId: config.runId, phase: 'idle', warmup: true });
  peer = await startFixturePeer({ config, controlPath, statusPath });

  const endpoint = `ws://127.0.0.1:${peer.port}`;
  const frames = createFrameReader();
  socket = new WebSocket(endpoint);
  socket.on('message', (raw) => frames.push(JSON.parse(raw.toString('utf8'))));
  socket.on('close', (code) => frames.close(code));
  socket.on('error', (error) => frames.fail(error));
  await waitForEvent(socket, 'open');
  const challenge = await frames.next((frame) => frame.type === 'challenge');
  const hello = makeHello(config, challenge.challenge);
  socket.send(JSON.stringify(hello));
  const welcome = await frames.next((frame) => frame.type === 'welcome');
  assert.equal(welcome.protocol, DEPLOYMENT.companion.protocol);

  const firstId = randomUUID();
  const firstRequest = makeRequest(config, firstId, PLAYER_ID);
  const queuedProgress = makeProgressRecord(PLAYER_ID, 'queued');
  firstRequest.progressContext = [queuedProgress];
  socket.send(JSON.stringify(firstRequest));
  const firstActiveProgress = makeProgressRecord(PLAYER_ID, 'awaiting_answer');
  const secondActiveProgress = makeProgressRecord(PLAYER_ID, 'awaiting_answer');
  const activeFrame = (progress) => ({
    type: 'dialogue.progress', protocol: DEPLOYMENT.companion.protocol,
    requestId: firstId, invocationId: firstRequest.invocationId, playerId: PLAYER_ID, progress,
  });
  const firstActiveFrame = activeFrame(firstActiveProgress);
  socket.send(JSON.stringify(firstActiveFrame));
  socket.send(JSON.stringify(firstActiveFrame));
  socket.send(JSON.stringify(activeFrame(secondActiveProgress)));
  const answer = await frames.next((frame) => frame.type === 'dialogue.message' && frame.requestId === firstId);
  assert.equal(answer.text, 'This is a synthetic fixture reply.');
  assert.equal(socket.readyState, WebSocket.OPEN, 'queued and active progress, including an identical retry, are accepted');
  assert.equal(peer.status().answerCount, 1);

  await writeJsonPrivate(controlPath, { schemaVersion: 1, runId: config.runId, phase: 'overload', warmup: false }, true);
  await waitFor(() => peer.status().phase === 'overload');
  const requestIds = Array.from({ length: 17 }, () => randomUUID());
  for (let index = 0; index < requestIds.length; index += 1) {
    socket.send(JSON.stringify(makeRequest(config, requestIds[index], index % 2 === 0 ? PLAYER_ID : config.authorizedPlayerIds[1])));
  }
  const overflow = await frames.next((frame) => frame.type === 'dialogue.failure');
  assert.equal(overflow.requestId, requestIds.at(-1));
  assert.equal(overflow.reason, 'unavailable');
  assert.equal(peer.status().overflowFailureCount, 1);
  assert.equal(peer.status().delayedRequestsByPhase.overload, 17);
  assert.equal(peer.status().delayedPendingHighWaterByPhase.overload, 16);

  await writeJsonPrivate(controlPath, { schemaVersion: 1, runId: config.runId, phase: 'unavailable', warmup: false }, true);
  await waitFor(() => peer.status().phase === 'unavailable');
  const closeCode = await frames.closed();
  assert.equal(closeCode, 1013);

  await Promise.all([peer.close(), peer.close()]);
  assert.equal(peer.status().stopped, true);
  const stoppedStatus = await readFile(statusPath, 'utf8');
  assert.equal(JSON.parse(stoppedStatus).event, 'stopped');
  await assertLoopbackListenerClosed(peer.port);
  await new Promise((resolveDelay) => setTimeout(resolveDelay, 125));
  assert.equal(await readFile(statusPath, 'utf8'), stoppedStatus,
    'fixture status must remain unchanged after close has settled');
});

function makeHello(config, challenge) {
  const nonce = randomBytes(32).toString('base64url');
  const hello = {
    type: 'hello',
    protocol: DEPLOYMENT.companion.protocol,
    challenge,
    nonce,
    deploymentId: DEPLOYMENT.deploymentId,
    manifestSha256: config.manifestSha256,
    minecraftVersion: DEPLOYMENT.target.minecraft,
    fabricLoaderVersion: DEPLOYMENT.runtimeDependencies.fabricLoader,
    fabricApiVersion: DEPLOYMENT.runtimeDependencies.fabricApi,
    javaMajor: DEPLOYMENT.target.java.major,
    javaVendor: DEPLOYMENT.target.java.vendor,
    javaRuntimeVersion: DEPLOYMENT.target.java.runtimeVersion,
    ...config.binding,
  };
  const transcript = [
    DEPLOYMENT.companion.protocol, 'client', hello.challenge, hello.nonce, hello.deploymentId,
    hello.manifestSha256, hello.protocol, hello.minecraftVersion, hello.fabricLoaderVersion,
    hello.fabricApiVersion, String(hello.javaMajor), hello.javaVendor, hello.javaRuntimeVersion,
    hello.serverId, hello.worldId, hello.characterId, hello.agentId,
  ].join('\n');
  hello.proof = createHmac('sha256', Buffer.from(config.secret, 'base64url')).update(transcript).digest('base64url');
  return hello;
}

function makeRequest(config, requestId, playerId, progressContext = []) {
  let invocationId = randomUUID();
  while (invocationId === requestId) invocationId = randomUUID();
  const playerName = playerId === PLAYER_ID ? 'FixtureOne' : 'FixtureTwo';
  const event = {
    ...config.binding,
    eventId: randomUUID(),
    serverEpochId: SERVER_EPOCH_ID,
    occurredAtUnixMillis: Date.now(),
    capturedAtMonotonicNanos: process.hrtime.bigint().toString(),
    eventOrigin: 'live',
    inputKind: 'command',
    playerId,
    playerName,
    dimension: 'minecraft:overworld',
    text: 'Synthetic compatibility fixture request.',
  };
  return {
    type: 'dialogue.request',
    protocol: DEPLOYMENT.companion.protocol,
    requestId,
    invocationId,
    ...config.binding,
    event,
    conversation: { conversationId: randomUUID(), replyToMessageId: null },
    replyAudience: { kind: 'player', playerId },
    contextBatch: [],
    contextStatus: { state: 'disabled', lossRevision: '0', reasons: [] },
    acknowledgement: null,
    progressContext,
  };
}

function makeProgressRecord(playerId, phase) {
  const body = phase === 'queued'
    ? 'I am still waiting to start your request.'
    : 'Your request has been forwarded and I am still waiting for a complete answer.';
  return {
    messageId: randomUUID(),
    text: `[Worldspeaker] Archivist: ${body}`,
    dispatchedAtUnixMillis: Date.now(),
    dispatchedAtMonotonicNanos: process.hrtime.bigint().toString(),
    replyAudience: { kind: 'player', playerId },
    phase,
  };
}

function createFrameReader() {
  const frames = [];
  const waiters = [];
  let closeCode = null;
  let closeResolve = null;
  let closeReject = null;
  let closeTimer = null;
  return {
    push(frame) {
      const index = waiters.findIndex((waiter) => waiter.predicate(frame));
      if (index < 0) frames.push(frame);
      else {
        const [waiter] = waiters.splice(index, 1);
        clearTimeout(waiter.timer);
        waiter.resolve(frame);
      }
    },
    fail(error) {
      for (const waiter of waiters.splice(0)) {
        clearTimeout(waiter.timer);
        waiter.reject(error);
      }
      closeReject?.(error);
    },
    close(code) {
      closeCode = code;
      clearTimeout(closeTimer);
      closeResolve?.(code);
      closeResolve = null;
      closeReject = null;
    },
    next(predicate, timeoutMs = 5000) {
      const index = frames.findIndex(predicate);
      if (index >= 0) return Promise.resolve(frames.splice(index, 1)[0]);
      return new Promise((resolveFrame, rejectFrame) => {
        const waiter = { predicate, resolve: resolveFrame, reject: rejectFrame, timer: null };
        waiter.timer = setTimeout(() => {
          const current = waiters.indexOf(waiter);
          if (current >= 0) waiters.splice(current, 1);
          rejectFrame(new Error('fixture frame timeout'));
        }, timeoutMs);
        waiters.push(waiter);
      });
    },
    closed() {
      if (closeCode !== null) return Promise.resolve(closeCode);
      return new Promise((resolveClose, rejectClose) => {
        closeResolve = resolveClose;
        closeReject = rejectClose;
        closeTimer = setTimeout(() => {
          closeResolve = null;
          closeReject = null;
          rejectClose(new Error('fixture close timeout'));
        }, 5000);
      });
    },
  };
}

function waitForEvent(emitter, name, timeoutMs = 5000) {
  return new Promise((resolveEvent, rejectEvent) => {
    const timeout = setTimeout(() => rejectEvent(new Error(`fixture event timeout: ${name}`)), timeoutMs);
    emitter.once(name, (...args) => {
      clearTimeout(timeout);
      resolveEvent(args[0]);
    });
  });
}

async function waitFor(predicate, timeoutMs = 3000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 10));
  }
  throw new Error('fixture state transition timeout');
}

async function assertLoopbackListenerClosed(port, timeoutMs = 1000) {
  await new Promise((resolveConnection, rejectConnection) => {
    const probe = connect(port, '127.0.0.1');
    let settled = false;
    const timer = setTimeout(() => finish(new Error('fixture listener close timeout')), timeoutMs);
    const finish = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      probe.removeListener('connect', onConnect);
      probe.removeListener('error', onError);
      if (error) rejectConnection(error);
      else resolveConnection();
    };
    const onConnect = () => {
      probe.destroy();
      finish(new Error('fixture peer listener remained open after close'));
    };
    const onError = (error) => {
      if (error.code === 'ECONNREFUSED') finish();
      else finish(error);
    };
    probe.once('connect', onConnect);
    probe.once('error', onError);
  });
}

async function writeJsonPrivate(path, value, replace = false) {
  const temporary = `${path}.${randomUUID()}.tmp`;
  await writeFile(temporary, `${JSON.stringify(value)}\n`, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
  if (replace) await rename(temporary, path);
  else await rename(temporary, path);
  await chmod(path, 0o600);
}
