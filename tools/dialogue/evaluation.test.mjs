import assert from 'node:assert/strict';
import { chmodSync, existsSync, lstatSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, truncateSync, writeFileSync, unlinkSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';
import test from 'node:test';
import { OPERATIONAL_FORMAT_VERSION } from './evaluation-core.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const cli = join(root, 'tools/dialogue/evaluate.mjs');
const counterValues = () => Array(39).fill(0);
const operationalCounterValues = () => Array(47).fill(0);
const histogram = () => Array(10).fill(0);
const dispatchHistograms = () => Array.from({ length: 14 }, () => Array.from({ length: 12 }, histogram));
const turnOne = '11111111-1111-4111-8111-111111111111';
const turnTwo = '33333333-3333-4333-8333-333333333333';
const turnThree = '44444444-4444-4444-8444-444444444444';
const turnFour = '55555555-5555-4555-8555-555555555555';
const turnFive = '66666666-6666-4666-8666-666666666666';
const player = '22222222-2222-4222-8222-222222222222';

test('current operational metrics guidance follows the Java producer, schema, and CLI', () => {
  const producerSource = readFileSync(join(root, 'src/main/java/org/worldspeaker/runtime/TickMetrics.java'), 'utf8');
  const producerVersionMatch = producerSource.match(/public static final int NUMERIC_FORMAT_VERSION = (\d+);/u);
  assert.ok(producerVersionMatch, 'bounded Java producer source declares its numeric format version');
  const producerVersion = Number(producerVersionMatch[1]);
  assert.equal(OPERATIONAL_FORMAT_VERSION, producerVersion, 'reader current version matches the Java producer');

  const wireSource = readFileSync(join(root, 'src/main/java/org/worldspeaker/integration/DialogueEnvelope.java'), 'utf8');
  const wireProtocolMatch = wireSource.match(/public static final String PROTOCOL = "([^"]+)";/u);
  assert.ok(wireProtocolMatch, 'bounded dialogue envelope source declares its wire protocol');

  const streamSchema = readJson(join(root, 'tools/dialogue/numeric-stream.schema.json'));
  assert.ok(streamSchema.$defs.manifest.properties.numericFormatVersion.enum.includes(producerVersion));
  assert.ok(streamSchema.$defs.chunk.properties.numericFormatVersion.enum.includes(producerVersion));
  assert.match(streamSchema.title, new RegExp(`stream v${producerVersion}\\b`, 'u'));

  const help = command(['--help']);
  assert.equal(help.status, 0, help.stderr);
  assert.match(help.stdout, new RegExp(`Operational TickMetrics v${producerVersion} chunk streams`, 'u'));
  assert.match(help.stdout, /reader also accepts legacy v3\/v4 streams/u);

  const procedure = readFileSync(join(root, 'tools/dialogue/PROCEDURE.md'), 'utf8');
  assert.ok(procedure.includes('Current Java `TickMetrics` exports use operational numeric stream format v' + producerVersion));
  assert.ok(procedure.includes('companion dialogue wire remains `' + wireProtocolMatch[1] + '`'),
    'procedure distinguishes the independent dialogue wire version');
  assert.ok(procedure.includes(`Neither v3 nor v4 records v${producerVersion} cancellation attribution`));
  assert.match(procedure, /cancellationEvidence\.availability` is `UNKNOWN_LEGACY_STREAM`/u);
});

test('operational chunk reports cover 0, 64, 65, and 4096 rows without changing the human 64-turn gate', () => {
  const scratch = privateDirectory();
  try {
    for (const count of [0, 64, 65, 4096]) {
      const records = Array.from({ length: count }, (_, index) => operationalInvocation(index));
      const sourcePath = join(scratch, `numeric-${count}.jsonl`);
      writePrivate(sourcePath, operationalStream(records));
      const out = join(scratch, `operational-report-${count}`);
      const generated = command(['operational-report', '--source', sourcePath, '--out', out]);
      assert.equal(generated.status, 0, `operational count ${count}: ${generated.stderr}`);
      const report = readJson(join(out, 'report.json'));
      assert.equal(report.status, 'COMPLETE', `${count} rows: ${report.incompleteReasons.join(', ')}`);
      assert.equal(report.sourceInvocationCount, count);
      assert.equal(report.retainedInvocationCount, count);
      assert.equal(report.observedInvocationCount, count);
      assert.equal(report.omittedInvocationCount, 0);
      assert.equal(report.snapshots[0].outcomeCounts.ADMITTED, count);
      assert.equal(report.humanEvaluation.status, 'NOT_PERFORMED');
      assert.equal(report.humanEvaluation.coversOperationalSource, false);
      assert.equal(report.humanEvaluation.maxTurns, 64);
      assert.equal(report.humanEvaluation.maxPlayerAliases, 3);
      assert.equal(report.humanEvaluation.maxRunAndReportBytes, 1024 * 1024);
      assert.equal(report.qualityDecision.automaticPass, false);
      assert.doesNotMatch(readFileSync(join(out, 'report.json'), 'utf8'), /22222222-2222-4222-8222-222222222222/u);
      assert.equal(lstatSync(out).mode & 0o777, 0o700);
      assert.equal(lstatSync(join(out, 'report.json')).mode & 0o777, 0o600);
      assert.equal(lstatSync(join(out, 'report.md')).mode & 0o777, 0o600);
      assert.ok(readFileSync(join(out, 'report.json')).length + readFileSync(join(out, 'report.md')).length <= 1024 * 1024,
        `bounded report for ${count} rows`);
    }

    const runDir = join(scratch, 'human-limit-run');
    assert.equal(command(['init', '--out', runDir]).status, 0);
    const runFile = join(runDir, 'run.json');
    const before = readFileSync(runFile);
    const oversizedHumanEntry = join(runDir, 'numeric-65.json');
    const sixtyFive = Array.from({ length: 65 }, (_, index) =>
      rawInvocation(idFor(index), null, null, -1, null, null, -1, []));
    writePrivate(oversizedHumanEntry, JSON.stringify(snapshot(sixtyFive)));
    const refused = command(['record', '--run', runFile, '--entry', oversizedHumanEntry]);
    assert.notEqual(refused.status, 0);
    assert.match(refused.stderr, /numeric-invocation-limit/u);
    assert.deepEqual(readFileSync(runFile), before, 'the limited human run remains untouched');
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('operational partial, duplicate, out-of-order, and conflicting chunks produce INCOMPLETE diagnostics and preserve source bytes', () => {
  const scratch = privateDirectory();
  try {
    const records = Array.from({ length: 65 }, (_, index) => operationalInvocation(index));
    for (const mode of ['missing', 'duplicate', 'duplicate-row', 'invalid-row', 'out-of-order', 'conflict']) {
      const sourcePath = join(scratch, `${mode}.jsonl`);
      const source = operationalStream(records, { mode });
      writePrivate(sourcePath, source);
      const original = readFileSync(sourcePath);
      const out = join(scratch, `${mode}-report`);
      const generated = command(['operational-report', '--source', sourcePath, '--out', out]);
      assert.equal(generated.status, 0, generated.stderr);
      assert.deepEqual(readFileSync(sourcePath), original, `${mode} import preserves the original snapshot`);
      const report = readJson(join(out, 'report.json'));
      assert.equal(report.status, 'INCOMPLETE', mode);
      assert.equal(report.sourceInvocationCount, 65, mode);
      assert.ok(report.incompleteReasons.length > 0, mode);
      assert.equal(report.humanEvaluation.coversOperationalSource, false);
      assert.equal(report.snapshots.length, 0, 'a malformed chunk set is never summarized as a complete prefix');
       if (mode !== 'out-of-order') assert.equal(report.omittedInvocationCount, 1, mode);
       else assert.equal(report.omittedInvocationCount, 0, 'all validated invocation rows were present');
       if (mode === 'duplicate-row' || mode === 'invalid-row') {
         assert.equal(report.observedInvocationCount, 64, `${mode} rows are not counted as observed`);
       }
    }
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('late duplicate and conflicting chunks invalidate only their matching finalized snapshot', () => {
  const scratch = privateDirectory();
  try {
    const rows = Array.from({ length: 65 }, (_, index) => operationalInvocation(index));
    const validSource = operationalStream(rows);
    const validLines = validSource.trimEnd().split('\n');
    const lastChunk = JSON.parse(validLines.at(-1));
    const firstChunk = JSON.parse(validLines[1]);

    for (const [mode, lateChunk, reason] of [
      ['duplicate-late', lastChunk, 'operational-chunk-duplicate-late'],
      ['conflict-late', { ...lastChunk, records: lastChunk.records.map((row, index) =>
        index === 0 ? { ...row, id: idFor(10000) } : row) }, 'operational-chunk-conflict-late'],
    ]) {
      const source = `${validSource}${JSON.stringify(lateChunk)}\n`;
      const sourcePath = join(scratch, `${mode}.jsonl`);
      writePrivate(sourcePath, source);
      const original = readFileSync(sourcePath);
      const out = join(scratch, `${mode}-report`);
      const result = command(['operational-report', '--source', sourcePath, '--out', out]);
      assert.equal(result.status, 0, result.stderr);
      assert.deepEqual(readFileSync(sourcePath), original, 'the exact source bytes remain untouched');

      const report = readJson(join(out, 'report.json'));
      assert.equal(report.status, 'INCOMPLETE');
      assert.ok(report.incompleteReasons.includes(reason), mode);
      assert.equal(report.sourceInvocationCount, 65);
      assert.equal(report.retainedInvocationCount, 65);
      assert.equal(report.observedInvocationCount, 65);
      assert.equal(report.omittedInvocationCount, 0);
      assert.equal(report.declaredOmittedInvocationCount, 0);
      assert.equal(report.snapshotCount, 1);
      assert.equal(report.snapshots[0].status, 'INCOMPLETE');
      assert.ok(report.snapshots[0].incompleteReasons.includes(reason));
      assert.equal(report.snapshots[0].outcomeCounts.ADMITTED, 65,
        'a repeated chunk is diagnosed but never counted as another 64/1 rows');
      assert.equal(report.humanEvaluation.maxTurns, 64);
      assert.equal(report.qualityDecision.automaticPass, false);
    }

    const duplicateManifestSource = `${validSource}${validLines[0]}\n`;
    const duplicateManifestPath = join(scratch, 'duplicate-manifest.jsonl');
    writePrivate(duplicateManifestPath, duplicateManifestSource);
    const duplicateManifestOut = join(scratch, 'duplicate-manifest-report');
    assert.equal(command(['operational-report', '--source', duplicateManifestPath, '--out', duplicateManifestOut]).status, 0);
    const duplicateManifestReport = readJson(join(duplicateManifestOut, 'report.json'));
    assert.equal(duplicateManifestReport.status, 'INCOMPLETE');
    assert.ok(duplicateManifestReport.incompleteReasons.includes('operational-snapshot-id-duplicate'));
    assert.equal(duplicateManifestReport.sourceInvocationCount, 65);
    assert.equal(duplicateManifestReport.observedInvocationCount, 65,
      'a repeated header does not replace the first manifest diagnostics with empty counts');
    assert.equal(duplicateManifestReport.snapshots[0].status, 'INCOMPLETE');

    const trailingAndForeign = [
      ['trailing-garbage', `${validSource}not-json\n`, 'operational-source-trailing-data'],
      ['foreign-id', `${validSource}${JSON.stringify({ ...firstChunk, snapshotId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' })}\n`,
        'operational-chunk-snapshot-mismatch'],
    ];
    for (const [name, source, reason] of trailingAndForeign) {
      const sourcePath = join(scratch, `${name}.jsonl`);
      writePrivate(sourcePath, source);
      const out = join(scratch, `${name}-report`);
      assert.equal(command(['operational-report', '--source', sourcePath, '--out', out]).status, 0);
      const report = readJson(join(out, 'report.json'));
      assert.equal(report.status, 'INCOMPLETE', name);
      assert.ok(report.incompleteReasons.includes(reason), name);
      assert.equal(report.sourceInvocationCount, 65);
      assert.equal(report.observedInvocationCount, 65);
      assert.equal(report.omittedInvocationCount, 0);
      assert.equal(report.snapshots[0].status, 'COMPLETE',
        'unattributable trailing data does not invalidate a distinct, complete snapshot');
    }

    const unreadTailPath = join(scratch, 'bounded-unread-tail.jsonl');
    writePrivate(unreadTailPath, `${validSource}${'not-json\n'.repeat(192)}${JSON.stringify(lastChunk)}\n`);
    const unreadTailOut = join(scratch, 'bounded-unread-tail-report');
    assert.equal(command(['operational-report', '--source', unreadTailPath, '--out', unreadTailOut]).status, 0);
    const unreadTailReport = readJson(join(unreadTailOut, 'report.json'));
    assert.equal(unreadTailReport.status, 'INCOMPLETE');
    assert.ok(unreadTailReport.incompleteReasons.includes('operational-line-count-limit'));
    assert.equal(unreadTailReport.observedInvocationCount, 65,
      'the bounded reader does not claim an unread trailing duplicate as observed');
    assert.equal(unreadTailReport.snapshots[0].status, 'INCOMPLETE',
      'an unread tail prevents a per-snapshot completeness claim');
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('partial earlier snapshots and late repeats do not erase later valid summaries or counts', () => {
  const scratch = privateDirectory();
  try {
    const firstRows = Array.from({ length: 65 }, (_, index) => operationalInvocation(index));
    const secondRows = [operationalInvocation(1000)];
    const first = operationalStream(firstRows);
    const second = withOperationalSnapshotId(operationalStream(secondRows), 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb');
    const firstLines = first.trimEnd().split('\n');
    const secondLines = second.trimEnd().split('\n');
    const source = `${first}${second}${firstLines[2]}\n`;
    const sourcePath = join(scratch, 'partial-then-valid-late-duplicate.jsonl');
    writePrivate(sourcePath, source);
    const original = readFileSync(sourcePath);
    const out = join(scratch, 'partial-then-valid-late-duplicate-report');
    const result = command(['operational-report', '--source', sourcePath, '--out', out]);
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(readFileSync(sourcePath), original);
    const report = readJson(join(out, 'report.json'));
    assert.equal(report.status, 'INCOMPLETE');
    assert.equal(report.sourceInvocationCount, 1);
    assert.equal(report.retainedInvocationCount, 1);
    assert.equal(report.observedInvocationCount, 1);
    assert.equal(report.omittedInvocationCount, 0);
    assert.equal(report.snapshotCount, 2);
    assert.deepEqual(report.snapshots.map((snapshot) => snapshot.status), ['INCOMPLETE', 'COMPLETE']);
    assert.ok(report.snapshots[0].incompleteReasons.includes('operational-chunk-duplicate-late'));
    assert.equal(report.snapshots[0].outcomeCounts.ADMITTED, 65);
    assert.equal(report.snapshots[1].outcomeCounts.ADMITTED, 1,
      'the older snapshot repeat never changes the unrelated later summary');

    const partialFirst = operationalStream(firstRows, { mode: 'missing' });
    const partialSourcePath = join(scratch, 'partial-first-valid-second.jsonl');
    writePrivate(partialSourcePath, `${partialFirst}${second}`);
    const partialOut = join(scratch, 'partial-first-valid-second-report');
    assert.equal(command(['operational-report', '--source', partialSourcePath, '--out', partialOut]).status, 0);
    const partialReport = readJson(join(partialOut, 'report.json'));
    assert.equal(partialReport.status, 'INCOMPLETE');
    assert.equal(partialReport.sourceInvocationCount, 1);
    assert.equal(partialReport.retainedInvocationCount, 1);
    assert.equal(partialReport.observedInvocationCount, 1);
    assert.equal(partialReport.omittedInvocationCount, 0);
    assert.equal(partialReport.snapshotCount, 1);
    assert.equal(partialReport.snapshots[0].status, 'COMPLETE',
      'a partial earlier snapshot does not poison the later, independently complete capture');
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('1000-row operational stream remains bounded and keeps report and human schemas separate', () => {
  const scratch = privateDirectory();
  try {
    const records = Array.from({ length: 1000 }, (_, index) => operationalInvocation(index));
    const sourcePath = join(scratch, 'numeric-1000.jsonl');
    writePrivate(sourcePath, operationalStream(records));
    const out = join(scratch, 'operational-report-1000');
    const result = command(['operational-report', '--source', sourcePath, '--out', out]);
    assert.equal(result.status, 0, result.stderr);
    const report = readJson(join(out, 'report.json'));
    assert.equal(report.status, 'COMPLETE');
    assert.equal(report.sourceInvocationCount, 1000);
    assert.equal(report.retainedInvocationCount, 1000);
    assert.equal(report.observedInvocationCount, 1000);
    assert.equal(report.omittedInvocationCount, 0);
    assert.equal(report.declaredOmittedInvocationCount, 0);
    assert.equal(report.snapshotCount, 1);
    assert.equal(report.snapshots[0].outcomeCounts.ADMITTED, 1000);
    assert.equal(report.humanEvaluation.status, 'NOT_PERFORMED');
    assert.equal(report.humanEvaluation.maxTurns, 64);
    assert.equal(report.qualityDecision.status, 'NOT_EVALUATED');
    assert.equal(report.qualityDecision.automaticPass, false);
    assert.ok(readFileSync(join(out, 'report.json')).length < 1024 * 1024);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('operational v3 evidence keeps deadlines, progress publication, context transport, and terminal delivery separate', () => {
  const scratch = privateDirectory();
  try {
    const answer = operationalInvocation(0, {
      firstDispatchNanos: 50,
      terminalOutcome: 'ANSWER',
      terminalNanos: 20_000_000_000,
      terminalDeliveryResult: 'SUBMITTED',
    });
    answer.progress = [{
      phase: 'awaiting_answer', state: 'forwarded_waiting', status: 'dispatched', latencyNanos: 10_000_000_000,
      contextSendResult: 'SEND_COMPLETED', contextCompletedAtNanos: '10123456789',
    }];
    const source = operationalStream([answer]);
    const sourcePath = join(scratch, 'resp02-v3.jsonl');
    writePrivate(sourcePath, source);
    const out = join(scratch, 'resp02-v3-report');
    assert.equal(command(['operational-report', '--source', sourcePath, '--out', out]).status, 0);
    const snapshot = readJson(join(out, 'report.json')).snapshots[0];
    assert.equal(snapshot.status, 'COMPLETE');
    assert.equal(snapshot.queueEvidence.availability, 'UNKNOWN_LEGACY_STREAM');
    assert.equal(snapshot.cancellationEvidence.availability, 'UNKNOWN_LEGACY_STREAM');
    assert.equal(snapshot.cancellationEvidence.terminalCauseCounts, null);
    assert.equal(snapshot.cancellationEvidence.providerCancel, null);
    assert.equal(snapshot.queueEvidence.forwardedInvocationCount, null,
      'v3 absence of forwarding evidence stays unknown, not zero');
    assert.deepEqual(snapshot.executionBudgetCounts, { '30': 1 });
    assert.deepEqual(snapshot.progressCounts, { dispatched: 1, failed: 0, missed: 0, unknown: 0 });
    assert.deepEqual(snapshot.progressStateCounts, { forwarded_waiting: 1 });
    assert.deepEqual(snapshot.progressPublicSendResultCounts, { SUBMITTED: 1 });
    assert.deepEqual(snapshot.progressContextSendResultCounts, { SEND_COMPLETED: 1 });
    assert.deepEqual(snapshot.terminalOutcomeCounts, { ANSWER: 1 });
    assert.deepEqual(snapshot.terminalDeliveryResultCounts, { SUBMITTED: 1 });
    assert.match(readFileSync(join(out, 'report.md'), 'utf8'), /context transport results/u);

    const invalid = JSON.parse(source.trimEnd().split('\n')[1]);
    invalid.records[0].executionBudgetSeconds = 31;
    const invalidPath = join(scratch, 'invalid-budget.jsonl');
    writePrivate(invalidPath, `${source.trimEnd().split('\n')[0]}\n${JSON.stringify(invalid)}\n`);
    const invalidOut = join(scratch, 'invalid-budget-report');
    assert.equal(command(['operational-report', '--source', invalidPath, '--out', invalidOut]).status, 0);
    const invalidReport = readJson(join(invalidOut, 'report.json'));
    assert.equal(invalidReport.status, 'INCOMPLETE');
    assert.ok(invalidReport.incompleteReasons.includes('operational-chunk-invalid'));
    assert.equal(invalidReport.snapshots.length, 0, 'invalid v3 fields never produce a partial complete summary');
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('operational v4 reconciles repeat refusals and reports bounded queue and per-player evidence', () => {
  const scratch = privateDirectory();
  try {
    const records = [
      operationalInvocation(0, {
        firstDispatchNanos: 500_000_000,
        terminalOutcome: 'ANSWER',
        terminalNanos: 5_000_000_000,
        terminalDeliveryResult: 'SUBMITTED',
        pendingResidenceNanos: 400_000_000,
        playerQueueDepthAtAdmission: 1,
        globalPendingAtForward: 2,
        playerPendingAtForward: 1,
      }),
      operationalInvocation(1, {
        outcome: 'COALESCED', requestId: 'request-1', rateLimitStatus: 'ALLOWED',
      }),
      operationalInvocation(2, {
        outcome: 'RATE_LIMITED', requestId: 'request-1', rateLimitStatus: 'REPEAT_GUARD',
      }),
      operationalInvocation(3, {
        outcome: 'RATE_LIMITED', rateLimitStatus: 'REPEAT_GUARD_CAPACITY',
      }),
    ];
    const sourcePath = join(scratch, 'resp04-v4.jsonl');
    writePrivate(sourcePath, operationalStream(records, {
      formatVersion: 4,
      queueEvidence: {
        pendingQueueScans: 3,
        pendingQueueRecordSamples: 8,
        maxPendingGlobal: 2,
        maxPendingPerPlayer: 1,
        maxPendingAgeNanos: '700000000',
        maxPendingCleanupDelayNanos: '100000000',
        maxRecordsPerScan: 4,
        maxPlayerQueuesPerScan: 3,
        recordLimitPerScan: 17,
        playerQueueLimitPerScan: 16,
      },
    }));
    const out = join(scratch, 'resp04-v4-report');
    const generated = command(['operational-report', '--source', sourcePath, '--out', out]);
    assert.equal(generated.status, 0, generated.stderr);
    const report = readJson(join(out, 'report.json'));
    assert.equal(report.status, 'COMPLETE', report.incompleteReasons.join(', '));
    const snapshot = report.snapshots[0];
    assert.equal(snapshot.status, 'COMPLETE');
    assert.equal(snapshot.outcomeCounts.ADMITTED, 1);
    assert.equal(snapshot.outcomeCounts.COALESCED, 1);
    assert.equal(snapshot.outcomeCounts.RATE_LIMITED, 2);
    assert.equal(snapshot.requestLinkReconciliation.coalescedCount, 1);
    assert.equal(snapshot.requestLinkReconciliation.resolvedCoalescedCount, 1);
    assert.equal(snapshot.requestLinkReconciliation.repeatGuardRefusedCount, 1);
    assert.equal(snapshot.requestLinkReconciliation.resolvedRepeatGuardCount, 1);
    assert.equal(snapshot.requestLinkReconciliation.unresolvedRepeatGuardCount, 0);
    assert.deepEqual(snapshot.requestLinkReconciliation.repeatGuardOriginalTerminalOutcomeCounts, { ANSWER: 1 });
    assert.equal(snapshot.queueEvidence.availability, 'OBSERVED');
    assert.equal(snapshot.queueEvidence.pendingQueueScans, 3);
    assert.equal(snapshot.queueEvidence.sampledMaxima.globalPending, 2);
    assert.equal(snapshot.queueEvidence.captureToForwardWait.sampleCount, 1);
    assert.equal(snapshot.queueEvidence.captureToForwardWait.maxMs, 400);
    assert.equal(snapshot.cancellationEvidence.availability, 'UNKNOWN_LEGACY_STREAM');
    assert.equal(snapshot.cancellationEvidence.terminalCauseCounts, null);
    assert.equal(snapshot.cancellationEvidence.retirementNoticeResultCounts, null);
    assert.equal(snapshot.cancellationEvidence.localCancelStatusCounts, null);
    assert.equal(snapshot.cancellationEvidence.localCancelSendResultCounts, null);
    assert.equal(snapshot.cancellationEvidence.providerCancel, null);
    assert.equal(snapshot.cancellationEvidence.lateOutputRejectionCount, null);
    assert.equal(snapshot.counters.LATE_OUTPUT_REJECTIONS, null);
    assert.equal(snapshot.perPlayer['player-001'].invocations, 4);
    assert.equal(snapshot.perPlayer['player-001'].admittedLogicalRequests, 1);
    assert.equal(snapshot.perPlayer['player-001'].coalescedCount, 1);
    assert.equal(snapshot.perPlayer['player-001'].repeatGuardRefusedCount, 1);
    assert.equal(snapshot.perPlayer['player-001'].repeatGuardCapacityRefusedCount, 1);
    assert.equal(snapshot.perPlayer['player-001'].forwardedCount, 1);
    assert.equal(snapshot.perPlayer['player-001'].queueWait.maxMs, 400);
    assert.equal(snapshot.perPlayer['player-001'].queueAgeAtFirstDispatch.maxMs, 500);
    assert.equal(snapshot.perPlayer['player-001'].confirmedAnswerCompletion.count, 1);
    const markdown = readFileSync(join(out, 'report.md'), 'utf8');
    assert.match(markdown, /Repeat guard: 1 refusals; resolved to one admitted anchor 1; unresolved 0/u);
    assert.match(markdown, /capture-to-forward wait n=1, median 400\.000 ms/u);
    assert.match(markdown, /1 repeat-guard refused; 1 repeat-guard-capacity refused/u);
    assert.match(markdown, /model starts unknown \(not exposed\)/u);

    const validV4 = operationalStream(records.slice(0, 1), { formatVersion: 4 });
    const v4Lines = validV4.trimEnd().split('\n').map((line) => JSON.parse(line));
    delete v4Lines[0].queueEvidence;
    const missingManifestPath = join(scratch, 'v4-missing-queue-evidence.jsonl');
    writePrivate(missingManifestPath, `${v4Lines.map(JSON.stringify).join('\n')}\n`);
    const missingManifestOut = join(scratch, 'v4-missing-queue-evidence-report');
    assert.equal(command(['operational-report', '--source', missingManifestPath, '--out', missingManifestOut]).status, 0);
    const missingManifestReport = readJson(join(missingManifestOut, 'report.json'));
    assert.equal(missingManifestReport.status, 'INCOMPLETE');
    assert.ok(missingManifestReport.incompleteReasons.includes('operational-manifest-invalid'));
    assert.equal(missingManifestReport.snapshots.length, 0);

    const v4MissingRowField = validV4.trimEnd().split('\n').map((line) => JSON.parse(line));
    v4MissingRowField[1].records[0].playerPendingAtForward = undefined;
    delete v4MissingRowField[1].records[0].playerPendingAtForward;
    const missingRowPath = join(scratch, 'v4-missing-queue-row-field.jsonl');
    writePrivate(missingRowPath, `${v4MissingRowField.map(JSON.stringify).join('\n')}\n`);
    const missingRowOut = join(scratch, 'v4-missing-queue-row-field-report');
    assert.equal(command(['operational-report', '--source', missingRowPath, '--out', missingRowOut]).status, 0);
    const missingRowReport = readJson(join(missingRowOut, 'report.json'));
    assert.equal(missingRowReport.status, 'INCOMPLETE');
    assert.ok(missingRowReport.incompleteReasons.includes('operational-chunk-invalid'));
    assert.equal(missingRowReport.snapshots.length, 0);

    const v3WithQueueField = operationalStream(records.slice(0, 1)).trimEnd().split('\n')
      .map((line) => JSON.parse(line));
    Object.assign(v3WithQueueField[1].records[0], {
      playerQueueDepthAtAdmission: 0,
      forwardedAtNanos: null,
      pendingResidenceNanos: null,
      globalPendingAtForward: null,
      playerPendingAtForward: null,
    });
    const extraV3Path = join(scratch, 'v3-with-v4-row-fields.jsonl');
    writePrivate(extraV3Path, `${v3WithQueueField.map(JSON.stringify).join('\n')}\n`);
    const extraV3Out = join(scratch, 'v3-with-v4-row-fields-report');
    assert.equal(command(['operational-report', '--source', extraV3Path, '--out', extraV3Out]).status, 0);
    const extraV3Report = readJson(join(extraV3Out, 'report.json'));
    assert.equal(extraV3Report.status, 'INCOMPLETE');
    assert.ok(extraV3Report.incompleteReasons.includes('operational-chunk-invalid'));
    assert.equal(extraV3Report.snapshots.length, 0);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('operational v5 reports bounded cancellation evidence and legacy cancellation fields stay unknown', () => {
  const scratch = privateDirectory();
  try {
    const cancelled = operationalInvocation(0, {
      terminalOutcome: 'CANCELLED',
      terminalNanos: 300_000_000,
      terminalDeliveryResult: 'SUBMITTED',
      pendingResidenceNanos: 25_000_000,
      playerQueueDepthAtAdmission: 0,
      globalPendingAtForward: 1,
      playerPendingAtForward: 1,
    });
    Object.assign(cancelled, {
      terminalCause: 'BLOCK_MOVEMENT',
      retirementNoticeResult: 'SUBMITTED',
      localCancelStatus: 'UNKNOWN',
      localCancelSendResult: 'UNKNOWN',
      providerCancel: false,
      lateOutputRejections: 2,
    });
    const source = join(scratch, 'v5-cancellation.jsonl');
    writePrivate(source, operationalStream([cancelled], { formatVersion: OPERATIONAL_FORMAT_VERSION }));
    const out = join(scratch, 'v5-cancellation-report');
    const generated = command(['operational-report', '--source', source, '--out', out]);
    assert.equal(generated.status, 0, generated.stderr);
    const report = readJson(join(out, 'report.json'));
    assert.equal(report.status, 'COMPLETE', report.incompleteReasons.join(', '));
    assert.equal(report.sourceFormatVersion, OPERATIONAL_FORMAT_VERSION);
    const evidence = report.snapshots[0].cancellationEvidence;
    assert.equal(evidence.availability, 'OBSERVED');
    assert.deepEqual(evidence.terminalCauseCounts, { BLOCK_MOVEMENT: 1 });
    assert.deepEqual(evidence.retirementNoticeResultCounts, { SUBMITTED: 1 });
    assert.deepEqual(evidence.localCancelStatusCounts, { UNKNOWN: 1 });
    assert.deepEqual(evidence.localCancelSendResultCounts, { UNKNOWN: 1 });
    assert.deepEqual(evidence.providerCancel, { falseCount: 1, unknownCount: 0, trueCount: 0 });
    assert.equal(evidence.lateOutputRejectionCount, 2);
    assert.equal(evidence.linkedLateOutputRejectionCount, 2);
    assert.equal(evidence.lateOutputCounterMatches, true);

    const mismatched = operationalStream([cancelled], { formatVersion: OPERATIONAL_FORMAT_VERSION }).trimEnd().split('\n')
      .map((line) => JSON.parse(line));
    mismatched[0].counters[46] = 1;
    const mismatchSource = join(scratch, 'v5-cancellation-mismatch.jsonl');
    writePrivate(mismatchSource, `${mismatched.map(JSON.stringify).join('\n')}\n`);
    const mismatchOut = join(scratch, 'v5-cancellation-mismatch-report');
    assert.equal(command(['operational-report', '--source', mismatchSource, '--out', mismatchOut]).status, 0);
    const mismatchReport = readJson(join(mismatchOut, 'report.json'));
    assert.equal(mismatchReport.status, 'INCOMPLETE');
    assert.ok(mismatchReport.incompleteReasons.includes('operational-late-output-counter-mismatch'));
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('operational reader deduplicates deadline settlement and late first dispatch for one invocation', () => {
  const scratch = privateDirectory();
  try {
    const lateAfterDeadline = operationalInvocation(0, {
      firstDispatchNanos: 1_200_000_000,
      firstReplyDisposition: 'ACKNOWLEDGED',
      firstReplyResult: 'LATE',
      deadlineSettledAtNanos: String(123_456_789 + 1_001_000_000),
    });
    const fast = operationalInvocation(1, {
      firstDispatchNanos: 100_000_000,
      firstReplyDisposition: 'ANSWERED',
      firstReplyResult: 'ON_TIME',
    });
    const sourcePath = join(scratch, 'late-after-deadline.jsonl');
    writePrivate(sourcePath, operationalStream([lateAfterDeadline, fast]));
    const out = join(scratch, 'late-after-deadline-report');
    assert.equal(command(['operational-report', '--source', sourcePath, '--out', out]).status, 0);

    const report = readJson(join(out, 'report.json'));
    const snapshot = report.snapshots[0];
    assert.equal(snapshot.status, 'COMPLETE');
    assert.equal(snapshot.firstDispatch.sampleCount, 2);
    assert.equal(snapshot.firstDispatch.maxMs, 1200);
    assert.equal(snapshot.firstDispatch.over1000msCount, 1);
    assert.equal(snapshot.firstDispatch.noDispatchAtDeadlineCount, 1);
    assert.equal(snapshot.firstDispatch.noDispatchSettlementCount, 1);
    assert.equal(snapshot.firstDispatch.noDispatchDeadlineCount, 0,
      'the invocation has a successful first dispatch by export');
    assert.equal(snapshot.firstDispatch.eventualNoDispatchCount, 0);
    assert.equal(snapshot.firstDispatch.noFirstDispatchCount, 0);
    assert.equal(snapshot.firstDispatch.knownDeadlineMissCount, 1,
      'late dispatch and the earlier no-dispatch-at-deadline observation are one invocation miss');
    assert.equal(snapshot.firstDispatch.aggregateDeadlineMissCount, 1);
    assert.equal(snapshot.firstDispatch.deadlineCounterMatches, true);
    assert.equal(snapshot.firstDispatch.noDispatchCounterMatches, true);
    assert.equal(snapshot.firstDispatch.allFirstReplyCountersMatch, true);
    assert.match(readFileSync(join(out, 'report.md'), 'utf8'), /1 unique linked misses \(1 late first dispatches; 1 no-dispatch observations at\/after deadline, with overlap counted once/u);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('operational answer completion requires a submitted terminal ANSWER and keeps ACK-only and uncertain sends outside success', () => {
  const scratch = privateDirectory();
  try {
    const records = [
      operationalInvocation(0, {
        firstDispatchNanos: 100_000_000,
        firstReplyDisposition: 'ANSWERED',
        terminalOutcome: 'ANSWER',
        terminalDeliveryResult: 'SUBMITTED',
        terminalNanos: 5_000_000_000,
      }),
      operationalInvocation(1, {
        firstDispatchNanos: 50_000_000,
        firstReplyDisposition: 'ACKNOWLEDGED',
        terminalOutcome: 'ANSWER',
        terminalDeliveryResult: 'UNKNOWN',
        terminalNanos: 20_000_000_000,
      }),
      operationalInvocation(2, {
        firstDispatchNanos: 75_000_000,
        firstReplyDisposition: 'ACKNOWLEDGED',
        terminalOutcome: 'ANSWER',
        terminalDeliveryResult: 'DEFINITELY_UNSENT',
        terminalNanos: 8_000_000_000,
      }),
      operationalInvocation(3, {
        firstDispatchNanos: 90_000_000,
        firstReplyDisposition: 'ACKNOWLEDGED',
        terminalOutcome: 'ANSWER',
        terminalDeliveryResult: 'NO_ATTEMPT',
        terminalNanos: 15_000_000_000,
      }),
      operationalInvocation(4, {
        firstDispatchNanos: 60_000_000,
        firstReplyDisposition: 'ACKNOWLEDGED',
        terminalOutcome: 'ANSWER',
        terminalDeliveryResult: 'SUBMITTED',
        terminalNanos: 10_000_000_000,
      }),
      operationalInvocation(5, { firstReplyResult: 'PENDING' }),
    ];
    const sourcePath = join(scratch, 'terminal-delivery-results.jsonl');
    writePrivate(sourcePath, operationalStream(records));
    const out = join(scratch, 'terminal-delivery-results-report');
    const generated = command(['operational-report', '--source', sourcePath, '--out', out]);
    assert.equal(generated.status, 0, generated.stderr);
    const report = readJson(join(out, 'report.json'));
    const snapshot = report.snapshots[0];
    const completeness = snapshot.responseCompleteness;
    const markdown = readFileSync(join(out, 'report.md'), 'utf8');

    assert.equal(snapshot.status, 'COMPLETE');
    assert.deepEqual(snapshot.terminalOutcomeCounts, { ANSWER: 5, NO_TERMINAL: 1 });
    assert.deepEqual(snapshot.terminalDeliveryResultCounts,
      { DEFINITELY_UNSENT: 1, NO_ATTEMPT: 1, NO_TERMINAL: 1, SUBMITTED: 2, UNKNOWN: 1 });
    assert.deepEqual(completeness.answerTerminalAttemptDeliveryResultCounts,
      { DEFINITELY_UNSENT: 1, NO_ATTEMPT: 1, SUBMITTED: 2, UNKNOWN: 1 });
    assert.equal(completeness.answerTerminalAttemptCount, 5);
    assert.equal(completeness.unconfirmedAnswerAttemptCount, 3);
    assert.equal(completeness.answerCompletedCount, 2);
    assert.equal(completeness.admittedWithoutMeasuredAnswer, 4);
    assert.equal(completeness.answerCompletionRateOverAllInvocations.numerator, 2);
    assert.equal(completeness.answerCompletionRateOverAllInvocations.denominator, 6);
    assert.equal(completeness.answerCompletionRateOverAllInvocations.rate, 1 / 3);
    assert.equal(completeness.answerCompletionRateOverAdmitted.numerator, 2);
    assert.equal(completeness.answerCompletionRateOverAdmitted.denominator, 6);
    assert.equal(completeness.answerCompletionRateOverAdmitted.rate, 1 / 3);
    assert.equal(completeness.answerCompletion.sampleCount, 2);
    assert.equal(completeness.answerCompletion.medianMs, 5000);
    assert.equal(completeness.answerCompletion.p95Ms, 10_000);
    assert.equal(completeness.answerCompletion.maxMs, 10_000,
      'latency uses terminal settlement, not the earlier ACK t1');
    assert.match(completeness.answerCompletionLatencyBasis, /terminalNanos.*terminalAtNanos remains a separate event timestamp/u);
    assert.equal(snapshot.firstReplyOutcomeCounts.PENDING, 1);
    assert.match(markdown, /Confirmed technical answers: 2\/6 linked invocations \(33\.3%\); 2\/6 admitted \(33\.3%\)/u);
    assert.match(markdown, /ANSWER terminal attempts: 5; delivery results: DEFINITELY_UNSENT: 1; NO_ATTEMPT: 1; SUBMITTED: 2; UNKNOWN: 1; unconfirmed attempts: 3/u);
    assert.match(markdown, /latency from terminalNanos: n=2\/6 admitted; median 5000\.000 ms; p95 10000\.000 ms; maximum 10000\.000 ms/u);
    assert.match(markdown, /terminalAtNanos is a separate event timestamp/u);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('operational importer enforces its source-byte ceiling without overwriting or deleting the source', () => {
  const scratch = privateDirectory();
  try {
    const source = join(scratch, 'over-byte-limit.jsonl');
    writePrivate(source, '');
    truncateSync(source, 64 * 1024 * 1024 + 1);
    const out = join(scratch, 'refused-report');
    const refused = command(['operational-report', '--source', source, '--out', out]);
    assert.notEqual(refused.status, 0);
    assert.match(refused.stderr, /private-file-permissions-or-size-invalid/u);
    assert.equal(lstatSync(source).size, 64 * 1024 * 1024 + 1);
    assert.equal(existsSync(out), false);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('init creates a private empty offline run and blank review forms; validation is incomplete, never accepted', () => {
  const scratch = privateDirectory();
  try {
    const out = join(scratch, 'run');
    const init = command(['init', '--out', out]);
    assert.equal(init.status, 0, init.stderr);
    assert.equal(readJson(join(out, 'run.json')).schemaVersion, 3);
    assert.equal(readJson(join(out, 'run.json')).provenance, 'offline_fixture');
    assert.equal(readJson(join(out, 'review-form.json')).ratings.answerQuality, null);
    assert.deepEqual(readJson(join(out, 'review-form.json')).scenarioIds, []);
    assert.deepEqual(readJson(join(out, 'review-form.json')).reasonCodes, []);
    assert.deepEqual(readJson(join(out, 'review-form.json')).weaknessCodes, []);
    assert.equal(readJson(join(out, 'independent-review-form.json')).reviewerRole, 'independent_evaluator');
    assert.deepEqual(readJson(join(out, 'independent-review-form.json')).decisionReasonCodes, []);
    assert.match(readJson(join(out, 'review-codebook.json')).weaknessCodes.other_observed_issue, /details are not collected/u);
    for (const formName of ['review-form.json', 'independent-review-form.json']) {
      const form = readJson(join(out, formName));
      for (const field of ['reason', 'weakness', 'decisionReason', 'prioritizedWeaknesses', 'notes']) {
        assert.equal(Object.hasOwn(form, field), false, `${formName} has no free-form ${field} field`);
      }
    }
    assert.deepEqual(readJson(join(out, 'session-form.json')).consents.map((item) => item.approved), [false, false, false]);
    assert.equal(lstatSync(out).mode & 0o777, 0o700);
    for (const name of ['run.json', 'review-form.json', 'independent-review-form.json', 'session-form.json', 'review-codebook.json']) {
      assert.equal(lstatSync(join(out, name)).mode & 0o777, 0o600);
    }
    const validation = command(['validate', '--run', join(out, 'run.json')]);
    assert.equal(validation.status, 0, validation.stderr);
    const result = JSON.parse(validation.stdout);
    assert.equal(result.valid, true);
    assert.equal(result.provenance, 'offline_fixture');
    assert.equal(result.acceptance, 'NOT_ACCEPTED');
    assert.equal(result.automaticPass, false);
    assert.ok(result.blockers.some((item) => /offline_fixture/u.test(item)));
    assert.notEqual(command(['init', '--out', out]).status, 0, 'init refuses to overwrite an existing run');
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('numeric imports preserve bounded snapshots, link locally with aliases, and report latency separately from human quality', () => {
  const scratch = privateDirectory();
  try {
    const runDir = join(scratch, 'run');
    assert.equal(command(['init', '--out', runDir]).status, 0);
    const runFile = join(runDir, 'run.json');
    const numericPath = join(runDir, 'numeric.jsonl');
    const numeric = `${JSON.stringify(snapshot([
      rawInvocation(turnOne, player, 'req-one', 100_000_000, 'ACKNOWLEDGED', null, -1, [progress('queued', 'dispatched', 10_000_000_000)]),
    ]))}\n${JSON.stringify(snapshot([
      rawInvocation(turnOne, player, 'req-one', 100_000_000, 'ACKNOWLEDGED', 'ANSWER', 2_000_000_000, [progress('queued', 'dispatched', 10_000_000_000)]),
      rawInvocation(turnTwo, player, 'req-two', 1_250_000_000, 'ACKNOWLEDGED', 'UNRESOLVED', 30_000_000_000, []),
    ]))}\n`;
    writePrivate(numericPath, numeric);
    const numericRecord = command(['record', '--run', runFile, '--entry', numericPath]);
    assert.equal(numericRecord.status, 0, numericRecord.stderr);

    const run = readJson(runFile);
    assert.equal(run.entries[0].snapshots.length, 2);
    assert.equal(run.entries[0].snapshots[1].invocations.length, 2);
    assert.equal(run.entries[0].snapshots[1].invocations[0].requestAlias,
      run.entries[0].snapshots[0].invocations[0].requestAlias);
    const persisted = readFileSync(runFile, 'utf8');
    assert.doesNotMatch(persisted, new RegExp(turnOne, 'u'));
    assert.doesNotMatch(persisted, new RegExp(player, 'u'));
    assert.doesNotMatch(persisted, /actualWorld|serverBinding|worldBinding|characterBinding/u);

    const reviewPath = join(runDir, 'review.json');
    writePrivate(reviewPath, JSON.stringify({
      type: 'human_review', reviewerRole: 'player_1', scenarioIds: ['bounded-delay-or-failure', 'personal-advice'],
      ratings: { personaPremiseFit: 4, contextCoherence: 3, answerQuality: 4, discoverability: 2, enjoyment: 3 },
      reasonCodes: ['clarity', 'progress_visibility'],
      weaknessCodes: ['other_observed_issue', 'unresolved_exchange'],
      terminalClassifications: [
        { turnAlias: 'turn-001', classification: 'relevant_answer', scenarioId: 'personal-advice' },
        { turnAlias: 'turn-002', classification: 'unresolved' },
      ],
    }, null, 2));
    const reviewRecord = command(['record', '--run', runFile, '--entry', reviewPath]);
    assert.equal(reviewRecord.status, 0, reviewRecord.stderr);
    const secondReviewPath = join(runDir, 'player-two-review.json');
    writePrivate(secondReviewPath, JSON.stringify({
      type: 'human_review', reviewerRole: 'player_2', scenarioIds: ['bounded-delay-or-failure'],
      ratings: { personaPremiseFit: 4, contextCoherence: 4, answerQuality: 2, discoverability: 3, enjoyment: 4 },
      reasonCodes: ['answer_quality'], weaknessCodes: ['other_observed_issue'], terminalClassifications: [],
    }));
    assert.equal(command(['record', '--run', runFile, '--entry', secondReviewPath]).status, 0);
    const evaluatorReviewPath = join(runDir, 'independent-review.json');
    writePrivate(evaluatorReviewPath, JSON.stringify({
      type: 'human_review', reviewerRole: 'independent_evaluator', scenarioIds: ['bounded-delay-or-failure'],
      ratings: { personaPremiseFit: 3, contextCoherence: 3, answerQuality: 3, discoverability: 3, enjoyment: 3 },
      reasonCodes: ['mixed_or_inconclusive_evidence'], weaknessCodes: ['other_observed_issue'], terminalClassifications: [],
      decision: 'inconclusive', decisionReasonCodes: ['mixed_evidence'], prioritizedWeaknessCodes: ['other_observed_issue'],
    }));
    assert.equal(command(['record', '--run', runFile, '--entry', evaluatorReviewPath]).status, 0);
    const out = join(scratch, 'report');
    assert.equal(command(['report', '--run', runFile, '--out', out]).status, 0);
    const report = readJson(join(out, 'report.json'));
    assert.equal(report.acceptance.status, 'NOT_ACCEPTED');
    assert.equal(report.acceptance.automaticPass, false);
    assert.equal(report.numeric.invocationCount, 2);
    assert.equal(report.numeric.admittedCount, 2);
    assert.equal(report.numeric.snapshotCount, 2);
    assert.equal(report.numeric.firstDispatch.over1000msCount, 1);
    assert.equal(report.numeric.firstDispatch.over1000msRate, 0.5);
    assert.equal(report.numeric.firstDispatch.maxMs, 1250);
    assert.equal(report.numeric.responseCompleteness.answerCompletedCount, 0,
      'v2 terminal reasons lack terminal send evidence and must not default to SUBMITTED');
    assert.equal(report.numeric.responseCompleteness.answerTerminalAttemptCount, 1);
    assert.equal(report.numeric.responseCompleteness.unconfirmedAnswerAttemptCount, 1);
    assert.deepEqual(report.numeric.responseCompleteness.answerTerminalAttemptDeliveryResultCounts, { NOT_RECORDED: 1 });
    assert.deepEqual(report.numeric.terminalDeliveryResultCounts, { NOT_RECORDED: 2 });
    assert.equal(report.numeric.ordinaryCompletion.sampleCount, 0);
    assert.equal(report.numeric.ordinaryCompletion.medianMs, null);
    assert.equal(report.numeric.ordinaryCompletion.p95Ms, null);
    assert.equal(report.numeric.answerCompletionByWorkClass.ordinary.answerCompletedCount, 0);
    assert.equal(report.numeric.answerCompletionByWorkClass.unknown.admittedCount, 1,
      'a missing per-turn scenario assignment remains unknown, not ordinary');
    assert.equal(report.numeric.answerCompletionByWorkClass.unknown.completion.sampleCount, 0);
    assert.equal(report.numeric.progressCounts.dispatched, 1);
    assert.equal(report.numeric.humanClassifications.unanimousRelevantAnswers, 1,
      'an acknowledged request can later receive a human-classified answer terminal');
    assert.equal(report.numeric.humanClassifications.unanimousExplainedLimitations, 0);
    assert.equal(report.numeric.clientDisplay.unknownRecords, 2);
    assert.equal(report.runAlias, 'run-local');
    assert.deepEqual(report.humanReview.reasonCodesByReviewer[0].codes.map(({ code }) => code), ['clarity', 'progress_visibility']);
    assert.match(report.humanReview.reasonCodesByReviewer[0].codes[0].publicRendering, /Clarity/u);
    assert.deepEqual(report.humanReview.weaknessCodesByReviewer[0].codes.map(({ code }) => code), ['other_observed_issue', 'unresolved_exchange']);
    assert.match(report.humanReview.weaknessCodesByReviewer[0].codes[0].publicRendering, /details are not collected/u);
    assert.equal(report.humanReview.scoreDisagreements.answerQuality, true);
    assert.equal(report.humanReview.scoresByReviewer.length, 3);
    assert.equal(report.acceptance.acDecision, 'inconclusive');
    assert.equal(report.humanReview.decisions[0].reasonCodes[0].code, 'mixed_evidence');
    assert.equal(report.acceptance.automaticPass, false, 'a human decision never becomes a tool pass');
    assert.ok(report.privacyLimits.nameRedaction.includes('No general person-name detection'));
    assert.ok(report.privacyLimits.covertEncoding.includes('cannot be detected'));
    assert.doesNotMatch(readFileSync(join(out, 'report.json'), 'utf8'), new RegExp(turnOne, 'u'));
    assert.doesNotMatch(readFileSync(join(out, 'report.json'), 'utf8'), new RegExp(player, 'u'));
    assert.match(readFileSync(join(out, 'report.md'), 'utf8'), /NOT ACCEPTED/u);
    assert.match(readFileSync(join(out, 'report.md'), 'utf8'), /Score disagreements by dimension: .*answerQuality: true/u);
    const reportMarkdown = readFileSync(join(out, 'report.md'), 'utf8');
    assert.match(reportMarkdown, /unknown: 2/u);
    assert.match(reportMarkdown, /ANSWER terminal attempts: 1; delivery results: NOT_RECORDED: 1; unconfirmed attempts: 1/u);
    assert.match(reportMarkdown, /ordinary: n=0\/1 admitted \(0\.0%\).*maximum unknown.*ANSWER attempts NOT_RECORDED: 1/u);
    assert.match(reportMarkdown, /Unanimous relevant answers: 1\/2 admitted requests/u,
      'subjective usefulness remains separate and cannot confer technical send success');

    const duplicate = command(['record', '--run', runFile, '--entry', numericPath]);
    assert.notEqual(duplicate.status, 0);
    assert.match(duplicate.stderr, /duplicate-entry/u);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('per-turn answer completion uses selected scenarios and keeps missing and conflicting classes explicit', () => {
  const scratch = privateDirectory();
  try {
    const runDirectory = join(scratch, 'work-class-run');
    assert.equal(command(['init', '--out', runDirectory]).status, 0);
    const runFile = join(runDirectory, 'run.json');
    const records = [
      rawInvocation(turnOne, player, 'ordinary', 50_000_000, 'ACKNOWLEDGED', 'ANSWER', 2_000_000_000, [], { terminalDeliveryResult: 'SUBMITTED' }),
      rawInvocation(turnTwo, player, 'tool-heavy', 50_000_000, 'ACKNOWLEDGED', 'ANSWER', 20_000_000_000, [], { terminalDeliveryResult: 'SUBMITTED' }),
      rawInvocation(turnThree, player, 'unknown-failure', 50_000_000, 'ACKNOWLEDGED', 'DISPATCH_FAILED', 15_000_000_000, [], { terminalDeliveryResult: 'NO_ATTEMPT' }),
      rawInvocation(turnFour, player, 'mixed', 50_000_000, 'ACKNOWLEDGED', 'ANSWER', 4_000_000_000, [], { terminalDeliveryResult: 'UNKNOWN' }),
      rawInvocation(turnFive, player, 'unassigned-null', 50_000_000, 'ACKNOWLEDGED', 'ANSWER', 3_000_000_000, [], { terminalDeliveryResult: 'DEFINITELY_UNSENT' }),
      rawInvocation('77777777-7777-4777-8777-777777777777', player, 'unreviewed', 50_000_000, 'ACKNOWLEDGED', 'ANSWER', 1_000_000_000, [], { terminalDeliveryResult: 'SUBMITTED' }),
    ];
    const numericPath = join(runDirectory, 'numeric.json');
    writePrivate(numericPath, JSON.stringify(snapshot(records)));
    assert.equal(command(['record', '--run', runFile, '--entry', numericPath]).status, 0);

    const playerOneReview = reviewWithClassifications('player_1',
      ['personal-advice', 'supported-tool-heavy-work'], [
        { turnAlias: 'turn-001', classification: 'relevant_answer', scenarioId: 'personal-advice' },
        { turnAlias: 'turn-002', classification: 'relevant_answer', scenarioId: 'supported-tool-heavy-work' },
        { turnAlias: 'turn-003', classification: 'safe_technical_failure' },
        { turnAlias: 'turn-004', classification: 'relevant_answer', scenarioId: 'personal-advice' },
        { turnAlias: 'turn-005', classification: 'relevant_answer', scenarioId: null },
      ]);
    const playerOnePath = join(runDirectory, 'player-one-review.json');
    writePrivate(playerOnePath, JSON.stringify(playerOneReview));
    assert.equal(command(['record', '--run', runFile, '--entry', playerOnePath]).status, 0);

    const playerTwoReview = reviewWithClassifications('player_2', ['supported-tool-heavy-work'], [
      { turnAlias: 'turn-004', classification: 'relevant_answer', scenarioId: 'supported-tool-heavy-work' },
    ]);
    const playerTwoPath = join(runDirectory, 'player-two-review.json');
    writePrivate(playerTwoPath, JSON.stringify(playerTwoReview));
    assert.equal(command(['record', '--run', runFile, '--entry', playerTwoPath]).status, 0);

    const invalidReview = reviewWithClassifications('player_3', ['personal-advice'], [
      { turnAlias: 'turn-001', classification: 'relevant_answer', scenarioId: 'supported-tool-heavy-work' },
    ]);
    const invalidPath = join(runDirectory, 'mismatched-review.json');
    writePrivate(invalidPath, JSON.stringify(invalidReview));
    const beforeInvalidRecord = readFileSync(runFile);
    const refused = command(['record', '--run', runFile, '--entry', invalidPath]);
    assert.notEqual(refused.status, 0);
    assert.match(refused.stderr, /terminal-classification-scenario-invalid/u);
    assert.deepEqual(readFileSync(runFile), beforeInvalidRecord, 'a mismatched catalog assignment is not retained');

    const out = join(scratch, 'work-class-report');
    assert.equal(command(['report', '--run', runFile, '--out', out]).status, 0);
    const report = readJson(join(out, 'report.json'));
    const groups = report.numeric.answerCompletionByWorkClass;
    const completeness = report.numeric.responseCompleteness;
    assert.deepEqual(report.numeric.workClassAssignmentCounts,
      { ordinary: 1, 'tool-heavy': 1, unknown: 3, mixed: 1 });
    assert.equal(completeness.answerTerminalAttemptCount, 5);
    assert.deepEqual(completeness.answerTerminalAttemptDeliveryResultCounts,
      { DEFINITELY_UNSENT: 1, SUBMITTED: 3, UNKNOWN: 1 });
    assert.equal(completeness.unconfirmedAnswerAttemptCount, 2);
    assert.equal(completeness.answerCompletedCount, 3);
    assert.equal(completeness.answerCompletionRateOverAllInvocations.numerator, 3);
    assert.equal(completeness.answerCompletionRateOverAllInvocations.denominator, 6);
    assert.equal(completeness.answerCompletionRateOverAllInvocations.rate, 0.5);
    assert.equal(completeness.answerCompletionRateOverAdmitted.rate, 0.5);
    assert.equal(completeness.answerCompletion.sampleCount, 3);
    assert.equal(completeness.answerCompletion.medianMs, 2000);
    assert.equal(completeness.answerCompletion.p95Ms, 20_000);
    assert.equal(completeness.answerCompletion.maxMs, 20_000);
    assert.equal(groups.ordinary.completion.sampleCount, 1);
    assert.equal(groups.ordinary.completion.medianMs, 2000);
    assert.equal(groups.ordinary.completion.p95Ms, 2000);
    assert.equal(groups.ordinary.completion.maxMs, 2000);
    assert.equal(groups['tool-heavy'].completion.sampleCount, 1);
    assert.equal(groups['tool-heavy'].completion.maxMs, 20_000);
    assert.match(groups['tool-heavy'].supportStatus, /BLOCKED/u);
    assert.equal(groups.unknown.completion.sampleCount, 1);
    assert.equal(groups.unknown.completion.maxMs, 1000);
    assert.deepEqual(groups.unknown.answerTerminalAttemptDeliveryResultCounts,
      { DEFINITELY_UNSENT: 1, SUBMITTED: 1 });
    assert.equal(groups.unknown.technicalTerminalFailureCount, 1);
    assert.equal(groups.unknown.admittedWithoutMeasuredAnswer, 2);
    assert.equal(groups.mixed.completion.sampleCount, 0);
    assert.equal(groups.mixed.completion.maxMs, null);
    assert.deepEqual(groups.mixed.answerTerminalAttemptDeliveryResultCounts, { UNKNOWN: 1 });
    assert.equal(groups.mixed.answerCompletionRateOverAdmitted.rate, 0);
    assert.ok(report.acceptance.blockers.some((item) => /Tool-heavy work is BLOCKED/u.test(item)));
    const markdown = readFileSync(join(out, 'report.md'), 'utf8');
    assert.match(markdown, /Confirmed technical answer completion: 3\/6 all invocations \(50\.0%\); 3\/6 admitted \(50\.0%\)/u);
    assert.match(markdown, /ANSWER terminal attempts: 5; delivery results: DEFINITELY_UNSENT: 1; SUBMITTED: 3; UNKNOWN: 1; unconfirmed attempts: 2/u);
    assert.match(markdown, /Capture-to-terminal-settlement latency from terminalNanos: n=3; median 2000\.000 ms; p95 20000\.000 ms; maximum 20000\.000 ms/u);
    assert.match(markdown, /ordinary: n=1\/1 admitted \(100\.0%\).*maximum 2000\.000 ms.*technical terminal failures 0/u);
    assert.match(markdown, /tool-heavy: n=1\/1 admitted \(100\.0%\).*maximum 20000\.000 ms.*support BLOCKED/u);
    assert.match(markdown, /unknown: n=1\/3 admitted \(33\.3%\).*maximum 1000\.000 ms, ANSWER attempts DEFINITELY_UNSENT: 1; SUBMITTED: 1, unconfirmed ANSWER attempts 1.*technical terminal failures 1/u);
    assert.match(markdown, /mixed: n=0\/1 admitted \(0\.0%\).*maximum unknown, ANSWER attempts UNKNOWN: 1, unconfirmed ANSWER attempts 1/u);
    assert.match(report.numeric.workClassAssignmentMethod, /scenarioId/u);
    assert.equal(report.acceptance.automaticPass, false);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('evaluation accepts every current dispatch label and preserves the direct measured maximum', () => {
  const scratch = privateDirectory();
  try {
    const sources = [
      'LOCAL_ACKNOWLEDGEMENT', 'INTEGRATION_ANSWER', 'INTEGRATION_FAILURE', 'LOCAL_SAFE_RESPONSE',
      'LOCAL_REJECTION', 'LOCAL_UNAVAILABLE', 'LOCAL_COALESCED', 'COMMAND_HELP', 'LOCAL_FOLLOWUP_END',
      'COMMAND_GUIDANCE', 'NATURAL_INVALID', 'LOCAL_UNAUTHENTICATED', 'LOCAL_DENIED', 'LOCAL_BUSY',
    ];
    const dispositions = [
      'ACKNOWLEDGED', 'ANSWERED', 'FAILED', 'REJECTED', 'UNAVAILABLE', 'COALESCED', 'HELP',
      'FOLLOWUP_ENDED', 'INVALID', 'DENIED', 'UNAUTHENTICATED', 'BUSY',
    ];
    const records = sources.map((source, index) => rawInvocation(
      `77777777-7777-4777-8777-${String(index + 1).padStart(12, '0')}`, player, `request-${index + 1}`,
      20_000_000, dispositions[index % dispositions.length], null, -1, [], { firstDispatchSource: source },
    ));
    const { report } = numericReport(scratch, 'expanded-labels', records);
    const samples = report.entries[0].snapshots[0].invocations;
    assert.deepEqual(new Set(samples.map((item) => item.firstReplySource)), new Set(sources));
    assert.deepEqual(new Set(samples.map((item) => item.firstReplyDisposition)), new Set(dispositions));
    assert.equal(report.numeric.firstReplyDeadline.actualMaximumDispatchNanos, '20000000');
    assert.equal(report.numeric.firstReplyDeadline.actualMaximumDispatchMs, 20);
    assert.equal(report.numeric.firstReplyDeadline.knownMissCount, 0);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('numeric format v2 keeps signed int64 timestamps exact and requires explicit safe-v1 migration', () => {
  const scratch = privateDirectory();
  try {
    const runDirectory = join(scratch, 'run');
    assert.equal(command(['init', '--out', runDirectory]).status, 0);
    const runFile = join(runDirectory, 'run.json');
    const sourcePath = join(runDirectory, 'legacy-numeric.json');
    const legacy = legacySnapshot([
      rawInvocation(turnOne, player, 'legacy-safe', 250_000_000, 'ACKNOWLEDGED', 'ANSWER', 500_000_000, []),
    ]);
    writePrivate(sourcePath, JSON.stringify(legacy));

    const implicitMigration = command(['record', '--run', runFile, '--entry', sourcePath]);
    assert.equal(implicitMigration.status, 2);
    assert.match(implicitMigration.stderr, /numeric-format-version-required/u);
    assert.equal(readJson(runFile).entries.length, 0);

    const explicitMigration = command([
      'record', '--run', runFile, '--entry', sourcePath, '--legacy-numeric-v1',
    ]);
    assert.equal(explicitMigration.status, 0, explicitMigration.stderr);
    const migrated = readJson(runFile).entries[0];
    assert.equal(migrated.numericFormatVersion, 2);
    assert.equal(migrated.snapshots[0].invocations[0].capturedAtNanos, '123456789');
    assert.equal(migrated.snapshots[0].invocations[0].firstDispatchNanos, '250000000');
    assert.equal(migrated.snapshots[0].invocations[0].terminalNanos, '500000000');

    const unsafeRunDirectory = join(scratch, 'unsafe-run');
    assert.equal(command(['init', '--out', unsafeRunDirectory]).status, 0);
    const unsafeRunFile = join(unsafeRunDirectory, 'run.json');
    const unsafeSource = join(unsafeRunDirectory, 'unsafe-legacy-numeric.json');
    const unsafe = legacySnapshot([
      rawInvocation(turnTwo, player, 'unsafe', 1, 'ACKNOWLEDGED', 'ANSWER', 2, []),
    ]);
    unsafe.interactionRecords[0].t0Nanos = Number.MAX_SAFE_INTEGER + 1;
    writePrivate(unsafeSource, JSON.stringify(unsafe));
    const refusedUnsafeMigration = command([
      'record', '--run', unsafeRunFile, '--entry', unsafeSource, '--legacy-numeric-v1',
    ]);
    assert.equal(refusedUnsafeMigration.status, 2);
    assert.match(refusedUnsafeMigration.stderr, /legacy-numeric-v1-unsafe-or-invalid/u);
    assert.equal(readJson(unsafeRunFile).entries.length, 0);

    const currentRunDirectory = join(scratch, 'current-format-run');
    assert.equal(command(['init', '--out', currentRunDirectory]).status, 0);
    const currentRunFile = join(currentRunDirectory, 'run.json');
    const currentSource = join(currentRunDirectory, 'current-numeric.json');
    const current = snapshot([
      rawInvocation(turnTwo, player, 'current', 1, 'ACKNOWLEDGED', 'ANSWER', 2, []),
    ]);
    writePrivate(currentSource, JSON.stringify(current));
    const wrongMigrationFlag = command([
      'record', '--run', currentRunFile, '--entry', currentSource, '--legacy-numeric-v1',
    ]);
    assert.equal(wrongMigrationFlag.status, 2);
    assert.match(wrongMigrationFlag.stderr, /legacy-numeric-migration-not-applicable/u);
    current.interactionRecords[0].t0Nanos = Number.MAX_SAFE_INTEGER + 1;
    writePrivate(currentSource, JSON.stringify(current));
    const unsafeV2 = command(['record', '--run', currentRunFile, '--entry', currentSource]);
    assert.equal(unsafeV2.status, 2);
    assert.match(unsafeV2.stderr, /numeric-invocation-fields-invalid/u);
    assert.equal(readJson(currentRunFile).entries.length, 0);

    const repeated = numericReport(scratch, 'repeated-dispositions', [
      rawInvocation(turnThree, player, 'coalesced-one', 10, 'COALESCED', null, -1, [], { outcome: 'COALESCED' }),
      rawInvocation('77777777-7777-4777-8777-000000000001', player, 'coalesced-two', 20, 'COALESCED', null, -1, [], { outcome: 'COALESCED' }),
      rawInvocation(turnFour, player, 'coalesced-two', -1, null, null, -1, [], {
        outcome: 'RECEIPT_EVIDENCE_MISSING', firstReplyResult: 'EVIDENCE_MISSING', captureProvenance: 'MISSING_PACKET_RECEIPT',
      }),
    ]).report;
    assert.deepEqual(repeated.numeric.outcomeCounts, { COALESCED: 2, RECEIPT_EVIDENCE_MISSING: 1 });
    assert.equal(repeated.numeric.firstReplyDeadline.captureEvidenceMissingCount, 1);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('legacy ANSWER with a pending first reply and missing terminal delivery stays an unconfirmed attempt', () => {
  const scratch = privateDirectory();
  try {
    const { report, markdown } = numericReport(scratch, 'pending-answer-without-delivery', [
      rawInvocation(turnOne, player, 'pending-answer', -1, null, 'ANSWER', 12_000_000_000, [], {
        firstReplyResult: 'PENDING',
      }),
    ]);
    const numeric = report.numeric;
    assert.equal(numeric.firstDispatch.sampleCount, 0);
    assert.equal(numeric.noFirstDispatch.pendingCount, 1);
    assert.deepEqual(numeric.terminalOutcomeCounts, { ANSWER: 1 });
    assert.deepEqual(numeric.terminalDeliveryResultCounts, { NOT_RECORDED: 1 });
    assert.deepEqual(numeric.responseCompleteness.answerTerminalAttemptDeliveryResultCounts, { NOT_RECORDED: 1 });
    assert.equal(numeric.responseCompleteness.answerTerminalAttemptCount, 1);
    assert.equal(numeric.responseCompleteness.unconfirmedAnswerAttemptCount, 1);
    assert.equal(numeric.responseCompleteness.answerCompletedCount, 0);
    assert.equal(numeric.responseCompleteness.answerCompletionRateOverAdmitted.rate, 0);
    assert.equal(numeric.responseCompleteness.answerCompletion.sampleCount, 0);
    assert.equal(numeric.responseCompleteness.answerCompletion.maxMs, null);
    assert.match(markdown, /First-reply results: PENDING: 1/u);
    assert.match(markdown, /ANSWER terminal attempts: 1; delivery results: NOT_RECORDED: 1; unconfirmed attempts: 1/u);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('deadline evidence separates on-time, late, no-dispatch, pending, and missing receipt outcomes', () => {
  const scratch = privateDirectory();
  try {
    const records = [
      rawInvocation(turnOne, player, 'req-on-time', 1_000_000_000, 'BUSY', null, -1, [], { firstDispatchSource: 'LOCAL_BUSY' }),
      rawInvocation(turnTwo, player, 'req-late', 1_100_000_000, 'ANSWERED', null, -1, []),
      rawInvocation(turnThree, player, 'req-deadline', -1, null, null, -1, [], {
        firstReplyResult: 'NO_DISPATCH_DEADLINE', deadlineSettledAtNanos: 1_123_456_789,
      }),
      rawInvocation('88888888-8888-4888-8888-888888888888', player, 'req-settled', -1, null,
        'DISPATCH_FAILED', 100_000_000, []),
      rawInvocation('99999999-9999-4999-8999-999999999999', player, 'req-pending', -1, null, null, -1, []),
      rawInvocation('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', player, 'req-no-receipt', -1, null, null, -1, [], {
        outcome: 'RECEIPT_EVIDENCE_MISSING', captureProvenance: 'MISSING_PACKET_RECEIPT',
        firstReplyResult: 'EVIDENCE_MISSING',
      }),
    ];
    const { report } = numericReport(scratch, 'all-deadline-outcomes', records);
    const deadline = report.numeric.firstReplyDeadline;
    assert.equal(deadline.onTimeCount, 1);
    assert.equal(deadline.lateDispatchCount, 1);
    assert.equal(deadline.noDispatchDeadlineCount, 1);
    assert.equal(deadline.noDispatchSettledCount, 1);
    assert.equal(deadline.pendingCount, 1);
    assert.equal(deadline.captureEvidenceMissingCount, 1);
    assert.equal(deadline.knownMissCount, 2);
    assert.equal(deadline.aggregateCounterNumericallyMatchesKnownMissTotal, true);
    assert.equal(deadline.actualMaximumDispatchNanos, '1100000000');
    assert.equal(report.numeric.noFirstDispatch.pendingCount, 1);
    assert.equal(report.numeric.noFirstDispatch.evidenceMissingCount, 1);
    assert.ok(report.entries[0].snapshots[0].invocations.every((item) =>
      Object.hasOwn(item, 'handlerDelayNanos') && Object.hasOwn(item, 'fallbackCutoffAtNanos')));
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('two admitted invocations report one terminal no-dispatch miss and one measured 1 ms dispatch without conflating them', () => {
  const scratch = privateDirectory();
  try {
    const records = [
      rawInvocation(turnOne, player, 'req-no-dispatch', -1, null, 'DISPATCH_FAILED', 1_500_000_000, []),
      rawInvocation(turnTwo, player, 'req-fast', 1_000_000, 'ACKNOWLEDGED', 'ANSWER', 500_000_000, []),
    ];
    const counters = countersFor(records);
    counters[0] = 1;
    const { report, markdown } = numericReport(scratch, 'one-missing-one-measured', records, counters);
    const numeric = report.numeric;

    assert.equal(numeric.invocationCount, 2);
    assert.equal(numeric.admittedCount, 2);
    assert.equal(numeric.firstDispatch.sampleCount, 1);
    assert.equal(numeric.firstDispatch.populationCount, 2);
    assert.equal(numeric.firstDispatch.coverageRate.rate, 0.5);
    assert.equal(numeric.firstDispatch.medianMs, 1);
    assert.equal(numeric.firstDispatch.maxMs, 1, 'maximum is from the one measured dispatch, not the aggregate counter');
    assert.equal(numeric.firstDispatch.over1000msCount, 0);
    assert.equal(numeric.firstDispatch.over1000msOverAllInvocations.rate, 0);
    assert.equal(numeric.noFirstDispatch.count, 1);
    assert.equal(numeric.noFirstDispatch.rateOverAllInvocations.rate, 0.5);
    assert.equal(numeric.noFirstDispatch.rateOverAdmitted.rate, 0.5);
    assert.equal(numeric.noFirstDispatch.byOutcome.ADMITTED.noFirstDispatchRate.rate, 0.5);
    assert.equal(numeric.noFirstDispatch.byTerminalLifecycle.TERMINAL_AFTER_1000MS.noFirstDispatchCount, 1);
    assert.equal(numeric.noFirstDispatch.byTerminalLifecycle.TERMINAL_AFTER_1000MS.noFirstDispatchRate.rate, 1);
    assert.equal(numeric.firstReplyDeadline.measuredOver1000msCount, 0);
    assert.equal(numeric.firstReplyDeadline.noDispatchDeadlineCount, 1);
    assert.equal(numeric.firstReplyDeadline.knownMissCount, 1);
    assert.equal(numeric.firstReplyDeadline.knownMissRateOverAllInvocations.rate, 0.5);
    assert.equal(numeric.firstReplyDeadline.knownMissRateOverAdmitted.rate, 0.5);
    assert.equal(numeric.firstReplyDeadline.aggregateCounterMissCount, 1);
    assert.equal(numeric.firstReplyDeadline.reconciliationStatus, 'matches_linked_all_outcome_deadline_misses');
    assert.equal(numeric.firstReplyDeadline.aggregateCounterMatchesMeasuredLateDispatches, false);
    assert.equal(numeric.firstReplyDeadline.aggregateCounterNumericallyMatchesKnownMissTotal, true);
    assert.equal(numeric.firstReplyDeadline.evidenceIncomplete, false);
    assert.equal(numeric.responseCompleteness.answerCompletedCount, 0,
      'v2 ANSWER reasons do not contain terminal delivery confirmation');
    assert.deepEqual(numeric.responseCompleteness.answerTerminalAttemptDeliveryResultCounts, { NOT_RECORDED: 1 });
    assert.equal(numeric.responseCompleteness.answerCompletionRateOverAdmitted.rate, 0);
    assert.match(markdown, /Conditional observed first-public-dispatch latency .*n=1\/2 all invocations/u);
    assert.match(markdown, /No first public dispatch: 1\/2 \(50\.0%\) all invocations; 1\/2 \(50\.0%\) admitted/u);
    assert.match(markdown, /First-reply deadline misses: 1 unique invocations \(0 late first dispatches; 1 no-dispatch observations at\/after the deadline, with overlap counted once/u);
    assert.match(markdown, /maximum 1\.000 ms/u);
    assert.match(markdown, /all-outcome reconciliation: matches_linked_all_outcome_deadline_misses; numerical equality with known linked misses: yes/u);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('all missing first dispatches with no terminal remain exposure-unknown, not invented deadline misses', () => {
  const scratch = privateDirectory();
  try {
    const records = [
      rawInvocation(turnOne, player, 'req-live-one', -1, null, null, -1, []),
      rawInvocation(turnTwo, player, 'req-before-deadline', -1, null, 'DISPATCH_FAILED', 999_999_999, []),
      rawInvocation(turnThree, player, 'req-at-deadline', -1, null, 'DISPATCH_FAILED', 1_000_000_000, []),
    ];
    const { report, markdown } = numericReport(scratch, 'all-missing-live', records);
    const numeric = report.numeric;
    assert.equal(numeric.firstDispatch.sampleCount, 0);
    assert.equal(numeric.firstDispatch.maxMs, null);
    assert.equal(numeric.firstDispatch.over1000msRate, null);
    assert.equal(numeric.noFirstDispatch.count, 3);
    assert.equal(numeric.noFirstDispatch.rateOverAllInvocations.rate, 1);
    assert.equal(numeric.noFirstDispatch.byTerminalLifecycle.NO_TERMINAL_OBSERVED.noFirstDispatchCount, 1);
    assert.equal(numeric.noFirstDispatch.byTerminalLifecycle.TERMINAL_BEFORE_1000MS.noFirstDispatchCount, 1);
    assert.equal(numeric.noFirstDispatch.byTerminalLifecycle.TERMINAL_AT_1000MS.noFirstDispatchCount, 1);
    assert.equal(numeric.firstReplyDeadline.noDispatchDeadlineCount, 1);
    assert.equal(numeric.firstReplyDeadline.knownMissCount, 1);
    assert.equal(numeric.firstReplyDeadline.knownMissRateOverAdmitted.rate, 1 / 3);
    assert.match(markdown, /A missing terminal leaves deadline exposure unknown/u);
    assert.match(markdown, /NO_TERMINAL_OBSERVED 1\/1 \(100\.0%\)/u);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('all timely dispatches include the exact 1,000 ms boundary without counting it late', () => {
  const scratch = privateDirectory();
  try {
    const records = [
      rawInvocation(turnOne, player, 'req-exact-boundary', 1_000_000_000, 'ACKNOWLEDGED', 'UNRESOLVED', 2_000_000_000, []),
      rawInvocation(turnTwo, player, 'req-timely', 10_000_000, 'ANSWERED', 'ANSWER', 500_000_000, []),
    ];
    const { report } = numericReport(scratch, 'all-timely', records);
    const numeric = report.numeric;
    assert.equal(numeric.firstDispatch.sampleCount, 2);
    assert.equal(numeric.firstDispatch.over1000msCount, 0);
    assert.equal(numeric.firstReplyDeadline.measuredOver1000msCount, 0);
    assert.equal(numeric.firstReplyDeadline.knownMissCount, 0);
    assert.equal(numeric.firstReplyDeadline.reconciliationStatus, 'matches_linked_all_outcome_deadline_misses');
    assert.equal(numeric.responseCompleteness.answerCompletedCount, 0,
      'a timely first dispatch without terminal SUBMITTED evidence is not answer completion');
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('zero denominators are null in structured rates and render as unknown', () => {
  const scratch = privateDirectory();
  try {
    const { report, markdown } = numericReport(scratch, 'zero-denominators', []);
    const numeric = report.numeric;
    assert.equal(numeric.firstDispatch.coverageRate.rate, null);
    assert.equal(numeric.firstDispatch.over1000msRate, null);
    assert.equal(numeric.noFirstDispatch.rateOverAllInvocations.rate, null);
    assert.equal(numeric.noFirstDispatch.rateOverAdmitted.rate, null);
    assert.equal(numeric.firstReplyDeadline.knownMissRateOverAllInvocations.rate, null);
    assert.equal(numeric.firstReplyDeadline.knownMissRateOverAdmitted.rate, null);
    assert.equal(numeric.responseCompleteness.answerCompletionRateOverAllInvocations.rate, null);
    assert.equal(numeric.responseCompleteness.answerCompletionRateOverAdmitted.rate, null);
    assert.match(markdown, /n=0\/0 all invocations; median unknown; p95 unknown; maximum unknown/u);
    assert.match(markdown, /0\/0 \(unknown\)/u);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('aggregate miss mismatch and partial invocation evidence are flagged without invented timings', () => {
  const scratch = privateDirectory();
  try {
    const records = [
      rawInvocation(turnOne, player, 'req-fast', 1_000_000, 'ACKNOWLEDGED', 'ANSWER', 20_000_000, []),
      rawInvocation(turnTwo, player, 'req-unobserved', -1, null, null, -1, []),
    ];
    const counters = countersFor(records);
    counters[0] = 1;
    counters[31] = 1;
    const { report, markdown } = numericReport(scratch, 'partial-mismatch', records, counters);
    const numeric = report.numeric;
    assert.equal(numeric.firstDispatch.maxMs, 1);
    assert.equal(numeric.firstReplyDeadline.measuredOver1000msCount, 0);
    assert.equal(numeric.firstReplyDeadline.noDispatchDeadlineCount, 0);
    assert.equal(numeric.firstReplyDeadline.knownMissCount, 0);
    assert.equal(numeric.firstReplyDeadline.reconciliationStatus, 'aggregate_exceeds_linked_all_outcome_deadline_misses');
    assert.equal(numeric.firstReplyDeadline.evidenceIncomplete, true);
    assert.equal(numeric.evidenceCoverage.interactionEvidenceDroppedCount, 1);
    assert.equal(numeric.evidenceCoverage.incomplete, true);
    assert.ok(report.acceptance.blockers.some((item) => /aggregate counters and linked invocation evidence are incomplete/u.test(item)));
    assert.match(markdown, /aggregate counter: 1; all-outcome reconciliation: aggregate_exceeds_linked_all_outcome_deadline_misses \(numeric evidence incomplete\)/u);
    assert.match(markdown, /No first public dispatch: 1\/2 .* admitted/u);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('late first replies are measured strictly after 1,000 ms and refusals/coalescing/unavailable stay outside answer completion', () => {
  const scratch = privateDirectory();
  try {
    const records = [
      rawInvocation(turnOne, player, 'req-late', 1_000_000_001, 'ANSWERED', 'ANSWER', 2_000_000_000, []),
      rawInvocation(turnTwo, player, 'req-denied', 5_000_000, 'DENIED', 'DENIED', 5_000_000, [], { outcome: 'DENIED', firstDispatchSource: 'LOCAL_DENIED' }),
      rawInvocation(turnThree, player, 'req-coalesced', 6_000_000, 'COALESCED', 'COALESCED', 6_000_000, [], { outcome: 'COALESCED' }),
      rawInvocation(turnFour, player, 'req-unavailable', 7_000_000, 'UNAVAILABLE', 'UNAVAILABLE', 7_000_000, [], { outcome: 'UNAVAILABLE' }),
    ];
    const { report, markdown } = numericReport(scratch, 'late-and-refusals', records);
    const numeric = report.numeric;
    assert.equal(numeric.firstDispatch.byDisposition.ANSWERED.over1000msCount, 1);
    assert.equal(numeric.firstReplyDeadline.measuredOver1000msCount, 1);
    assert.equal(numeric.firstReplyDeadline.knownMissRateOverAllInvocations.rate, 0.25);
    assert.equal(numeric.firstReplyDeadline.knownMissRateOverAdmitted.rate, 1);
    assert.equal(numeric.responseCompleteness.answerCompletedCount, 0,
      'a late first dispatch is not terminal answer delivery evidence');
    assert.equal(numeric.responseCompleteness.nonAdmittedCount, 3);
    assert.deepEqual(numeric.responseCompleteness.nonAdmittedOutcomeCounts, { COALESCED: 1, DENIED: 1, UNAVAILABLE: 1 });
    assert.equal(numeric.responseCompleteness.answerCompletionRateOverAllInvocations.rate, 0);
    assert.equal(numeric.responseCompleteness.answerCompletionRateOverAdmitted.rate, 0);
    assert.match(markdown, /non-admitted outcomes remain separate \(COALESCED: 1; DENIED: 1; UNAVAILABLE: 1\) and are never successful answer completions/u);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('missing ratings, consent, isolation, and arbitrary prose are rejected before recording', () => {
  const scratch = privateDirectory();
  try {
    const runDir = join(scratch, 'run');
    command(['init', '--out', runDir]);
    const runFile = join(runDir, 'run.json');
    const reviewPath = join(runDir, 'bad-review.json');
    writePrivate(reviewPath, JSON.stringify({
      type: 'human_review', reviewerRole: 'player_1', scenarioIds: ['free-conversation'],
      ratings: { personaPremiseFit: null, contextCoherence: null, answerQuality: null, discoverability: null, enjoyment: null },
      reasonCodes: [], weaknessCodes: [], terminalClassifications: [],
    }));
    assert.notEqual(command(['record', '--run', runFile, '--entry', reviewPath]).status, 0, 'blank score form cannot become an entry');

    const contradictoryWeakness = reviewEntry('player_1');
    contradictoryWeakness.weaknessCodes = ['none_observed', 'technical_failure'];
    writePrivate(reviewPath, JSON.stringify(contradictoryWeakness));
    const contradictory = command(['record', '--run', runFile, '--entry', reviewPath]);
    assert.notEqual(contradictory.status, 0, 'none_observed cannot mask a selected weakness');
    assert.equal(readJson(runFile).entries.length, 0);

    const session = completeSessionForm();
    session.consents[0].approved = false;
    const sessionPath = join(runDir, 'session.json');
    writePrivate(sessionPath, JSON.stringify(session));
    const noConsent = command(['record', '--run', runFile, '--entry', sessionPath]);
    assert.notEqual(noConsent.status, 0);
    assert.match(noConsent.stderr, /live-consent-or-isolation-required/u);

    session.consents[0].approved = true;
    session.isolationApproval.approved = false;
    writePrivate(sessionPath, JSON.stringify(session));
    const noIsolation = command(['record', '--run', runFile, '--entry', sessionPath]);
    assert.notEqual(noIsolation.status, 0);
    assert.match(noIsolation.stderr, /live-consent-or-isolation-required/u);

    const privateReason = {
      type: 'human_review', reviewerRole: 'player_1', scenarioIds: ['free-conversation'],
      ratings: { personaPremiseFit: 4, contextCoherence: 4, answerQuality: 4, discoverability: 4, enjoyment: 4 },
      reasonCodes: ['answer_relevance'], weaknessCodes: ['none_observed'], terminalClassifications: [],
      reason: 'PrivatePersonNameFixture',
    };
    writePrivate(reviewPath, JSON.stringify(privateReason));
    const unchangedRun = readFileSync(runFile);
    const privateContent = command(['record', '--run', runFile, '--entry', reviewPath]);
    assert.notEqual(privateContent.status, 0);
    assert.match(privateContent.stderr, /entry-field-set-invalid/u);
    assert.doesNotMatch(`${privateContent.stdout}\n${privateContent.stderr}`, /PrivatePersonNameFixture/u);
    assert.deepEqual(readFileSync(runFile), unchangedRun);

    delete privateReason.reason;
    privateReason.privateNote = 'PrivatePersonNameFixture';
    writePrivate(reviewPath, JSON.stringify(privateReason));
    const unknownField = command(['record', '--run', runFile, '--entry', reviewPath]);
    assert.notEqual(unknownField.status, 0);
    assert.match(unknownField.stderr, /entry-field-set-invalid/u);
    assert.doesNotMatch(`${unknownField.stdout}\n${unknownField.stderr}`, /PrivatePersonNameFixture/u);
    assert.deepEqual(readFileSync(runFile), unchangedRun, 'unknown prose fields are not stored');

    const arbitraryCode = reviewEntry('player_1');
    arbitraryCode.reasonCodes = ['PrivatePersonNameFixture'];
    writePrivate(reviewPath, JSON.stringify(arbitraryCode));
    const rejectedCode = command(['record', '--run', runFile, '--entry', reviewPath]);
    assert.notEqual(rejectedCode.status, 0);
    assert.doesNotMatch(`${rejectedCode.stdout}\n${rejectedCode.stderr}`, /PrivatePersonNameFixture/u);
    assert.deepEqual(readFileSync(runFile), unchangedRun, 'reason code values must come from the fixed rubric');

    delete privateReason.privateNote;
    privateReason.reviewerRole = 'independent_evaluator';
    privateReason.decision = 'supported';
    privateReason.decisionReasonCodes = ['quality_criteria_supported'];
    privateReason.prioritizedWeaknessCodes = [];
    privateReason.weakness = 'PrivatePersonNameFixture';
    privateReason.decisionReason = 'PrivatePersonNameFixture';
    privateReason.prioritizedWeaknesses = ['PrivatePersonNameFixture'];
    writePrivate(reviewPath, JSON.stringify(privateReason));
    const legacyReviewFields = command(['record', '--run', runFile, '--entry', reviewPath]);
    assert.notEqual(legacyReviewFields.status, 0);
    assert.match(legacyReviewFields.stderr, /entry-field-set-invalid/u);
    assert.doesNotMatch(`${legacyReviewFields.stdout}\n${legacyReviewFields.stderr}`, /PrivatePersonNameFixture/u);
    assert.deepEqual(readFileSync(runFile), unchangedRun, 'legacy reason, weakness, and decision prose fields cannot be captured');

    const reportDirectory = join(scratch, 'privacy-report');
    assert.equal(command(['report', '--run', runFile, '--out', reportDirectory]).status, 0);
    assert.doesNotMatch(readFileSync(join(reportDirectory, 'report.json'), 'utf8'), /PrivatePersonNameFixture/u);
    assert.doesNotMatch(readFileSync(join(reportDirectory, 'report.md'), 'utf8'), /PrivatePersonNameFixture/u);
    assert.equal(lstatMaybe(join(runDir, '.evaluation.lock')), null, 'validation failures release their owned run lock');
    assert.equal(readJson(runFile).entries.length, 0, 'rejected evidence is not partly retained');
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('schema v1 review prose is not reinterpreted, exported, or mutated by validate/report', () => {
  const scratch = privateDirectory();
  try {
    const runDir = join(scratch, 'legacy-run');
    assert.equal(command(['init', '--out', runDir]).status, 0);
    const runFile = join(runDir, 'run.json');
    const legacy = readJson(runFile);
    legacy.schemaVersion = 1;
    legacy.entries.push({
      type: 'human_review', sourceSha256: 'b'.repeat(64), reviewerRole: 'player_1',
      scenarioIds: ['free-conversation'],
      ratings: { personaPremiseFit: 4, contextCoherence: 4, answerQuality: 4, discoverability: 4, enjoyment: 4 },
      reason: 'PrivatePersonNameFixture is visible in this legacy prose.', weakness: null,
      terminalClassifications: [],
    });
    legacy.integrityHash = integrityHash(legacy);
    writePrivate(runFile, JSON.stringify(legacy, null, 2));
    const before = readFileSync(runFile);

    for (const commandName of ['validate', 'report']) {
      const outputDirectory = join(scratch, 'legacy-report');
      const args = commandName === 'validate'
        ? ['validate', '--run', runFile]
        : ['report', '--run', runFile, '--out', outputDirectory];
      const result = command(args);
      assert.notEqual(result.status, 0);
      assert.match(result.stderr, /legacy-run-migration-required/u);
      assert.match(result.stderr, /fresh v3 run/u);
      assert.doesNotMatch(`${result.stdout}\n${result.stderr}`, /PrivatePersonNameFixture/u);
      assert.deepEqual(readFileSync(runFile), before, 'legacy files stay byte-for-byte unchanged');
      assert.equal(existsSync(outputDirectory), false, 'legacy prose is never exported');
    }

    legacy.schemaVersion = 2;
    legacy.integrityHash = integrityHash(legacy);
    writePrivate(runFile, JSON.stringify(legacy, null, 2));
    const schemaV2 = readFileSync(runFile);
    const oldNumericRun = command(['validate', '--run', runFile]);
    assert.equal(oldNumericRun.status, 2);
    assert.match(oldNumericRun.stderr, /legacy-run-migration-required/u);
    assert.deepEqual(readFileSync(runFile), schemaV2, 'schema-v2 data is preserved for explicit re-import');
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('concurrent CLI records serialize read-validate-update-commit without losing entries', async () => {
  const scratch = privateDirectory();
  try {
    const runDir = join(scratch, 'run');
    assert.equal(command(['init', '--out', runDir]).status, 0);
    const runFile = join(runDir, 'run.json');

    for (let round = 0; round < 12; round += 1) {
      const paths = [0, 1].map((index) => join(runDir, `concurrent-${round}-${index}.json`));
      paths.forEach((path, index) => writePrivate(path, JSON.stringify(reviewEntry(
        index === 0 ? 'player_1' : 'player_2', round * 2 + index,
      ))));
      const before = readJson(runFile).entries.length;
      const results = await Promise.all(paths.map((entry) => commandAsync(['record', '--run', runFile, '--entry', entry])));
      const successful = results.filter((result) => result.status === 0);
      const rejected = results.filter((result) => result.status !== 0);
      assert.ok(successful.length === 1 || successful.length === 2, results.map(({ stderr }) => stderr).join('\n'));
      if (successful.length === 1) {
        assert.equal(rejected.length, 1);
        assert.equal(rejected[0].status, 2);
        assert.match(rejected[0].stderr, /run-lock-busy/u, 'a losing writer is explicitly rejected, never silently dropped');
        assert.match(rejected[0].stderr, /retry when it finishes/u, 'lock-busy reports actionable recovery guidance');
      } else {
        assert.equal(rejected.length, 0);
      }

      const retained = readJson(runFile).entries;
      assert.equal(retained.length, before + successful.length,
        'both successes must retain both entries; one conflict retains exactly the winner');
      for (const result of successful) {
        const expectedHash = JSON.parse(result.stdout).sourceSha256;
        assert.ok(retained.some((entry) => entry.sourceSha256 === expectedHash));
      }
      assert.equal(lstatMaybe(join(runDir, '.evaluation.lock')), null, 'completed writers clean only their own lock');
    }
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('concurrent CLI init uses exclusive directory creation and leaves one complete private run', async () => {
  const scratch = privateDirectory();
  try {
    const runDir = join(scratch, 'concurrent-init');
    const results = await Promise.all([
      commandAsync(['init', '--out', runDir]),
      commandAsync(['init', '--out', runDir]),
    ]);
    const successful = results.filter((result) => result.status === 0);
    const rejected = results.filter((result) => result.status !== 0);
    assert.equal(successful.length, 1);
    assert.equal(rejected.length, 1);
    assert.equal(rejected[0].status, 2);
    assert.match(rejected[0].stderr, /target-exists/u);
    assert.equal(readJson(join(runDir, 'run.json')).entries.length, 0);
    assert.equal(lstatSync(runDir).mode & 0o777, 0o700);
    for (const name of ['run.json', 'review-form.json', 'independent-review-form.json', 'session-form.json', 'review-codebook.json']) {
      assert.equal(lstatSync(join(runDir, name)).mode & 0o777, 0o600);
    }
    assert.equal(lstatMaybe(join(runDir, '.evaluation.lock')), null);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('held, aborted, existing, and symlink locks fail closed; reporting reads a complete committed snapshot', async () => {
  const scratch = privateDirectory();
  try {
    const runDir = join(scratch, 'run');
    assert.equal(command(['init', '--out', runDir]).status, 0);
    const runFile = join(runDir, 'run.json');
    const entryPath = join(runDir, 'held-entry.json');
    writePrivate(entryPath, JSON.stringify(reviewEntry('player_1', 1)));
    const lockPath = join(runDir, '.evaluation.lock');
    const originalRun = readFileSync(runFile);

    writePrivate(lockPath, 'pid=999999\n');
    const heldResults = await Promise.all([
      commandAsync(['record', '--run', runFile, '--entry', entryPath]),
      commandAsync(['record', '--run', runFile, '--entry', entryPath]),
    ]);
    for (const result of heldResults) {
      assert.equal(result.status, 2);
      assert.match(result.stderr, /run-lock-busy/u);
    }
    assert.deepEqual(readFileSync(runFile), originalRun, 'a held lock prevents all run-file mutation');
    assert.equal(readFileSync(lockPath, 'utf8'), 'pid=999999\n', 'existing locks are never removed or overwritten');

    // Pause the real CLI immediately after O_EXCL creates its lock, then kill it to model a process crash.
    unlinkSync(lockPath);
    const preload = `data:text/javascript,${encodeURIComponent(`
      import fs from 'node:fs';
      import { syncBuiltinESMExports } from 'node:module';
      const originalOpen = fs.promises.open.bind(fs.promises);
      fs.promises.open = async (path, ...args) => {
        const handle = await originalOpen(path, ...args);
        if (String(path) === ${JSON.stringify(lockPath)} && (args[0] & fs.constants.O_EXCL) !== 0) {
          await handle.writeFile('pid=' + process.pid + '\\n');
          await handle.sync();
          process.stdout.write('LOCK_HELD\\n');
          await new Promise(() => {});
        }
        return handle;
      };
      syncBuiltinESMExports();
    `)}`;
    const crashed = startCommand(['record', '--run', runFile, '--entry', entryPath], ['--import', preload]);
    await waitForMarker(crashed, 'LOCK_HELD', 10_000);
    crashed.child.kill('SIGKILL');
    const crashedResult = await crashed.result;
    assert.equal(crashedResult.signal, 'SIGKILL', 'the barrier leaves a lock exactly as an aborted writer does');
    assert.equal(lstatSync(lockPath).mode & 0o777, 0o600);
    assert.match(readFileSync(lockPath, 'utf8'), /^pid=\d+\n$/u);
    const stale = await commandAsync(['record', '--run', runFile, '--entry', entryPath]);
    assert.equal(stale.status, 2);
    assert.match(stale.stderr, /run-lock-busy/u, 'a crash lock is not automatically replayed or deleted');
    assert.deepEqual(readFileSync(runFile), originalRun, 'aborted update did not commit');

    const reportDirectory = join(scratch, 'snapshot-report');
    const report = command(['report', '--run', runFile, '--out', reportDirectory]);
    assert.equal(report.status, 0, report.stderr);
    assert.equal(readJson(join(reportDirectory, 'report.json')).entries.length, 0,
      'report reads one complete committed snapshot without taking or changing the run lock');
    const reportBytes = readFileSync(join(reportDirectory, 'report.json'));
    assert.notEqual(command(['report', '--run', runFile, '--out', reportDirectory]).status, 0,
      'report refuses to overwrite an existing output directory');
    assert.deepEqual(readFileSync(join(reportDirectory, 'report.json')), reportBytes);
    unlinkSync(lockPath); // Test cleanup only after the child is confirmed dead and no writer remains.

    const linkTarget = join(scratch, 'lock-target');
    writePrivate(linkTarget, 'untouched\n');
    symlinkSync(linkTarget, lockPath);
    const symlinkResult = await commandAsync(['record', '--run', runFile, '--entry', entryPath]);
    assert.equal(symlinkResult.status, 2);
    assert.match(symlinkResult.stderr, /run-lock-symlink/u);
    assert.equal(lstatSync(lockPath).isSymbolicLink(), true, 'the symlink lock path is rejected, not followed or replaced');
    assert.equal(readFileSync(linkTarget, 'utf8'), 'untouched\n');
    assert.deepEqual(readFileSync(runFile), originalRun);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('human_live provenance requires explicit consent and isolation attestation and still never auto-accepts', () => {
  const scratch = privateDirectory();
  try {
    const runDir = join(scratch, 'run');
    command(['init', '--out', runDir]);
    const runFile = join(runDir, 'run.json');
    const entryPath = join(runDir, 'session.json');
    writePrivate(entryPath, JSON.stringify(completeSessionForm()));
    const result = command(['record', '--run', runFile, '--entry', entryPath]);
    assert.equal(result.status, 0, result.stderr);
    const run = readJson(runFile);
    assert.equal(run.provenance, 'human_live');
    assert.equal(run.metadata.humanAttestation, true);
    assert.equal(run.metadata.consents.length, 3);
    assert.equal(run.metadata.isolationApproval.scope, 'actual_host_private_admin_isolation');
    const report = command(['validate', '--run', runFile]);
    assert.equal(report.status, 0, report.stderr);
    assert.equal(JSON.parse(report.stdout).acceptance, 'NOT_ACCEPTED');
    assert.equal(JSON.parse(report.stdout).automaticPass, false);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('tampered labels, hashes, aliases, duplicate keys, overflows, and symlinks fail closed', () => {
  const scratch = privateDirectory();
  try {
    const runDir = join(scratch, 'run');
    command(['init', '--out', runDir]);
    const runFile = join(runDir, 'run.json');
    const run = readJson(runFile);
    run.provenance = 'human_live';
    writePrivate(runFile, JSON.stringify(run));
    assert.notEqual(command(['validate', '--run', runFile]).status, 0, 'an altered provenance label is detected');
    run.provenance = 'offline_fixture';
    run.integrityHash = 'a'.repeat(64);
    writePrivate(runFile, JSON.stringify(run));
    assert.notEqual(command(['validate', '--run', runFile]).status, 0, 'altered source/integrity hashes are detected');

    const duplicateJson = join(runDir, 'duplicate.json');
    writePrivate(duplicateJson, '{"type":"human_review","type":"human_review"}');
    assert.notEqual(command(['record', '--run', runFile, '--entry', duplicateJson]).status, 0);

    const huge = join(runDir, 'huge.json');
    writePrivate(huge, ' '.repeat(1024 * 1024 + 1));
    assert.notEqual(command(['record', '--run', runFile, '--entry', huge]).status, 0);

    const target = join(scratch, 'linked-run');
    symlinkSync(runDir, target);
    assert.notEqual(command(['init', '--out', target]).status, 0);
    const sourceTarget = join(runDir, 'linked-entry.json');
    writePrivate(sourceTarget, JSON.stringify(snapshot([])));
    const sourceLink = join(runDir, 'entry-link.json');
    symlinkSync(sourceTarget, sourceLink);
    assert.notEqual(command(['record', '--run', runFile, '--entry', sourceLink]).status, 0);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('offline scenario fixtures contain situations and goals, never golden prompts or success labels', () => {
  const catalog = readJson(join(root, 'tools/dialogue/scenarios.json'));
  assert.equal(catalog.provenance, 'offline_scenario_catalog_not_test_results');
  assert.equal(catalog.scenarios.length, 11);
  assert.deepEqual(catalog.workClassPolicy.classes, ['ordinary', 'tool-heavy', 'unknown', 'mixed']);
  assert.equal(catalog.workClassPolicy.toolHeavyScenarioMayRun, false);
  assert.ok(catalog.scenarios.some((item) => item.id === 'free-conversation'));
  assert.ok(catalog.scenarios.some((item) => item.id === 'shared-fictional-fact'));
  assert.ok(catalog.scenarios.every((item) => !Object.hasOwn(item, 'prompt') && !Object.hasOwn(item, 'expectedReply')));
});

test('schema and semantic validation reject forged or mismatched identifiers and evidence hashes', () => {
  const scratch = privateDirectory();
  try {
    const runDir = join(scratch, 'run');
    command(['init', '--out', runDir]);
    const runFile = join(runDir, 'run.json');
    const source = join(runDir, 'numeric.json');
    writePrivate(source, JSON.stringify(snapshot([rawInvocation(turnOne, player, 'req-one', 20, 'ACKNOWLEDGED', 'ANSWER', 30, [])])));
    assert.equal(command(['record', '--run', runFile, '--entry', source]).status, 0);
    const review = join(runDir, 'review.json');
    writePrivate(review, JSON.stringify({
      type: 'human_review', reviewerRole: 'independent_evaluator', scenarioIds: ['free-conversation'],
      ratings: { personaPremiseFit: 4, contextCoherence: 4, answerQuality: 4, discoverability: 4, enjoyment: 4 },
      reasonCodes: ['answer_relevance'], weaknessCodes: ['none_observed'], terminalClassifications: [
        { turnAlias: 'turn-999', classification: 'relevant_answer' },
      ], decision: 'supported', decisionReasonCodes: ['quality_criteria_supported'], prioritizedWeaknessCodes: [],
    }));
    const mismatch = command(['record', '--run', runFile, '--entry', review]);
    assert.notEqual(mismatch.status, 0);
    assert.match(mismatch.stderr, /review-turn-alias-unknown/u);

    const existing = readJson(runFile);
    existing.entries[0].sourceSha256 = 'f'.repeat(64);
    writePrivate(runFile, JSON.stringify(existing));
    assert.notEqual(command(['validate', '--run', runFile]).status, 0, 'changed source digest fails integrity validation');
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('production package manifest does not include evaluation fixtures or tooling', () => {
  const manifest = readJson(join(root, 'companion/package.json'));
  assert.deepEqual(manifest.files, [
    'dist/', 'compatibility/', 'openclaw.plugin.json', 'README.md', 'LICENSE', 'presets/worldspeaker-god-preset-v1.zip',
  ]);
  assert.ok(!manifest.files.some((path) => path.includes('tools/dialogue') || path.includes('scenarios.json')));
});

function rawInvocation(id, playerId, requestId, firstDispatchNanos, firstReplyDisposition, terminalOutcome, terminalNanos, progressRecords, options = {}) {
  const capturedAt = 123456789;
  const captureProvenance = options.captureProvenance ?? 'SYNTHETIC_HARNESS';
  const captureMissing = captureProvenance === 'MISSING_PACKET_RECEIPT';
  const firstReplyResult = options.firstReplyResult ?? (firstDispatchNanos >= 0
    ? firstDispatchNanos > 1_000_000_000 ? 'LATE' : 'ON_TIME'
    : terminalNanos >= 1_000_000_000 ? 'NO_DISPATCH_DEADLINE'
      : terminalNanos >= 0 ? 'NO_DISPATCH_SETTLED' : 'PENDING');
  return {
    id, t0Nanos: capturedAt, receiptWallTimeUnixMillis: captureMissing ? null : 1793275200000,
    handlerEnteredAtNanos: capturedAt + 10, handlerDelayNanos: captureMissing ? null : 10,
    admittedAtNanos: capturedAt + 20, admissionDelayNanos: captureMissing ? null : 20,
    queuedAtNanos: -1, queueDelayNanos: null,
    fallbackCutoffAtNanos: captureMissing ? null : capturedAt + 750_000_000,
    deadlineAtNanos: captureMissing ? null : capturedAt + 1_000_000_000,
    captureProvenance,
    producer: options.producer ?? (captureMissing ? 'MISSING_PACKET_RECEIPT' : 'UNSIGNED_COMMAND'), connectionGeneration: 0,
    player: playerId, actualWorld: 'fixture-world', serverBinding: 'fixture-server',
    worldBinding: 'fixture-world', characterBinding: 'fixture-character', origin: 'COMMAND', outcome: options.outcome ?? 'ADMITTED',
    templateRevision: 'sha256:abcd', requestId: options.requestId ?? requestId, queueDepthAtAdmission: 0, firstDispatchNanos,
    firstDispatchAtNanos: firstDispatchNanos < 0 ? -1 : capturedAt + firstDispatchNanos,
    queueAgeAtFirstDispatchNanos: null,
    publicationId: firstDispatchNanos < 0 ? null : options.publicationId ?? `bbbbbbbb-bbbb-4bbb-8bbb-${id.slice(-12)}`,
    firstDispatchSource: firstDispatchNanos < 0 ? null : options.firstDispatchSource ?? 'LOCAL_ACKNOWLEDGEMENT',
    firstDispatchDisposition: firstDispatchNanos < 0 ? null : firstReplyDisposition,
    firstReplyResult,
    sendResult: options.sendResult ?? (captureMissing ? 'NO_DISPATCH' :
      firstDispatchNanos >= 0 ? 'SUBMITTED' : terminalNanos >= 0 ? 'NO_DISPATCH' : null),
    deadlineSettledAtNanos: options.deadlineSettledAtNanos ?? (firstReplyResult.startsWith('NO_DISPATCH_')
      ? capturedAt + Math.max(0, terminalNanos) : -1),
    failedDispatches: 0, unknownDispatches: 0, progress: progressRecords,
    ...(Object.hasOwn(options, 'terminalDeliveryResult') ? { terminalDeliveryResult: options.terminalDeliveryResult } : {}),
    terminalOutcome, terminalNanos,
  };
}

function progress(phase, status, latencyNanos) { return { phase, status, latencyNanos }; }

function snapshot(records, counters = countersFor(records)) {
  return {
    numericFormatVersion: 2,
    counters, tickHistogram: histogram(), firstPublicDispatchHistograms: dispatchHistograms(),
    maxFirstPublicDispatchNanos: String(Math.max(0, ...records.map((item) => item.firstDispatchNanos).filter((value) => value >= 0))),
    interactionRecords: records.map(encodeNumericV2Invocation),
  };
}

function encodeNumericV2Invocation(item) {
  const captureMissing = item.captureProvenance === 'MISSING_PACKET_RECEIPT';
  return {
    ...item,
    t0Nanos: captureMissing ? null : String(item.t0Nanos),
    handlerEnteredAtNanos: String(item.handlerEnteredAtNanos),
    handlerDelayNanos: item.handlerDelayNanos === null ? null : String(item.handlerDelayNanos),
    admittedAtNanos: String(item.admittedAtNanos),
    admissionDelayNanos: item.admissionDelayNanos === null ? null : String(item.admissionDelayNanos),
    queuedAtNanos: item.queuedAtNanos === -1 ? null : String(item.queuedAtNanos),
    queueDelayNanos: item.queueDelayNanos === null ? null : String(item.queueDelayNanos),
    fallbackCutoffAtNanos: item.fallbackCutoffAtNanos === null ? null : String(item.fallbackCutoffAtNanos),
    deadlineAtNanos: item.deadlineAtNanos === null ? null : String(item.deadlineAtNanos),
    firstDispatchNanos: String(item.firstDispatchNanos),
    firstDispatchAtNanos: item.firstDispatchAtNanos === -1 ? null : String(item.firstDispatchAtNanos),
    queueAgeAtFirstDispatchNanos: item.queueAgeAtFirstDispatchNanos === null ? null : String(item.queueAgeAtFirstDispatchNanos),
    deadlineSettledAtNanos: item.deadlineSettledAtNanos === -1 ? null : String(item.deadlineSettledAtNanos),
    progress: item.progress.map((progressRecord) => ({ ...progressRecord, latencyNanos: String(progressRecord.latencyNanos) })),
    terminalNanos: String(item.terminalNanos),
  };
}

function legacySnapshot(records, counters = countersFor(records)) {
  const current = snapshot(records, counters);
  delete current.numericFormatVersion;
  current.maxFirstPublicDispatchNanos = Number(current.maxFirstPublicDispatchNanos);
  current.interactionRecords = records;
  return current;
}

function countersFor(records) {
  const counters = counterValues();
  const noDispatchSettlementElapsed = (item) => item.deadlineSettledAtNanos === -1 ? null
    : BigInt.asIntN(64, BigInt(item.deadlineSettledAtNanos) - BigInt(item.t0Nanos));
  counters[0] = records.filter((item) => item.firstDispatchNanos > 1_000_000_000 ||
    (noDispatchSettlementElapsed(item) ?? -1n) >= 1_000_000_000n).length;
  counters[18] = records.length;
  counters[32] = records.filter((item) => item.firstReplyResult === 'ON_TIME').length;
  counters[33] = records.filter((item) => item.firstReplyResult === 'LATE').length;
  counters[34] = records.filter((item) => item.deadlineSettledAtNanos !== -1).length;
  counters[35] = records.filter((item) => (noDispatchSettlementElapsed(item) ?? 1_000_000_000n) < 1_000_000_000n).length;
  counters[24] = records.filter((item) => item.firstReplyResult === 'EVIDENCE_MISSING').length;
  return counters;
}

function idFor(index) {
  return `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`;
}

function operationalInvocation(index, options = {}) {
  const firstDispatchNanos = options.firstDispatchNanos ?? -1;
  const terminalNanos = options.terminalNanos ?? -1;
  const outcome = options.outcome ?? 'ADMITTED';
  const captureProvenance = options.captureProvenance ?? 'SYNTHETIC_HARNESS';
  const terminalOutcome = options.terminalOutcome ?? null;
  const record = rawInvocation(idFor(index), options.player ?? player, `request-${index + 1}`, firstDispatchNanos,
    firstDispatchNanos < 0 ? null : options.firstReplyDisposition ?? 'ACKNOWLEDGED',
    terminalOutcome, terminalNanos, [], { outcome, captureProvenance,
      requestId: options.requestId,
      firstReplyResult: options.firstReplyResult,
      deadlineSettledAtNanos: options.deadlineSettledAtNanos,
      producer: captureProvenance === 'MISSING_PACKET_RECEIPT' ? 'MISSING_PACKET_RECEIPT' : 'SYNTHETIC_HARNESS' });
  record.rateLimitStatus = options.rateLimitStatus;
  record.pendingResidenceNanos = options.pendingResidenceNanos ?? null;
  record.playerQueueDepthAtAdmission = options.playerQueueDepthAtAdmission ?? 0;
  record.globalPendingAtForward = options.globalPendingAtForward ?? null;
  record.playerPendingAtForward = options.playerPendingAtForward ?? null;
  record.terminalDeliveryResult = Object.hasOwn(options, 'terminalDeliveryResult')
    ? options.terminalDeliveryResult
    : terminalOutcome === null ? null : terminalOutcome === 'ANSWER' ? 'UNKNOWN' : 'NO_ATTEMPT';
  record.actualWorld = 'example:dimension/path';
  return record;
}

function encodeOperationalInvocation(record, index, formatVersion) {
  const encoded = encodeNumericV2Invocation(record);
  const t0 = BigInt(record.t0Nanos);
  const hasBudget = record.outcome === 'ADMITTED';
  const hasRequestLink = ['ADMITTED', 'COALESCED'].includes(record.outcome) ||
    record.outcome === 'RATE_LIMITED' && record.rateLimitStatus === 'REPEAT_GUARD';
  const requestLinkAtNanos = hasRequestLink ? String(BigInt.asIntN(64, t0 + 30n)) : null;
  const value = {
    ...encoded,
    decisionAtNanos: String(BigInt.asIntN(64, t0 + 30n)),
    decisionRecorded: true,
    queuedAtNanos: record.outcome === 'ADMITTED' ? requestLinkAtNanos : null,
    queueDelayNanos: record.outcome === 'ADMITTED'
      ? String(BigInt.asIntN(64, BigInt(requestLinkAtNanos) - t0)) : null,
    queueAgeAtFirstDispatchNanos: record.outcome === 'ADMITTED' && record.firstDispatchNanos >= 0
      ? String(record.firstDispatchNanos - Number(BigInt(requestLinkAtNanos) - t0)) : null,
    requestId: hasRequestLink ? record.requestId : null,
    requestLinkAtNanos,
    rateLimitStatus: record.rateLimitStatus ?? (record.outcome === 'RATE_LIMITED' ? 'INVOCATION_LIMITED' :
      record.outcome === 'ADMITTED' ? 'ALLOWED' : 'NOT_CHECKED'),
    noticeStatus: record.noticeStatus ?? 'NOT_REQUIRED',
    executionBudgetSeconds: hasBudget ? 30 : null,
    pendingDeadlineAtNanos: hasBudget ? String(BigInt.asIntN(64, t0 + 15_000_000_000n)) : null,
    activeDeadlineAtNanos: null,
    absoluteExecutionDeadlineAtNanos: hasBudget
      ? String(BigInt.asIntN(64, t0 + 45_000_000_000n)) : null,
    progressIntervalNanos: '10000000000',
    progressNoticeLimit: 3,
    progress: record.progress.map((item, progressIndex) => {
      const dispatched = item.status === 'dispatched';
      const failed = item.status === 'failed';
      const unknown = item.status === 'unknown';
      const noticeId = dispatched ? item.noticeId ?? `progress-${index}-${progressIndex}` : null;
      return {
        phase: item.phase,
        state: item.state ?? (item.phase === 'queued' ? 'queued' : 'forwarded_waiting'),
        status: item.status,
        noticeId,
        publicSendResult: item.publicSendResult ??
          (dispatched ? 'SUBMITTED' : failed ? 'DEFINITELY_UNSENT' : unknown ? 'UNKNOWN' : 'NO_ATTEMPT'),
        publicDispatchAtNanos: item.status === 'missed' ? null :
          item.publicDispatchAtNanos ?? String(BigInt.asIntN(64, t0 + BigInt(item.latencyNanos))),
        contextSendResult: item.contextSendResult ?? null,
        contextCompletedAtNanos: item.contextCompletedAtNanos ?? null,
        latencyNanos: String(item.latencyNanos),
      };
    }),
    terminalDeliveryResult: record.terminalDeliveryResult,
    terminalAtNanos: record.terminalOutcome === null ? null : String(BigInt.asIntN(64, t0 + BigInt(record.terminalNanos))),
  };
  delete value.playerQueueDepthAtAdmission;
  delete value.forwardedAtNanos;
  delete value.pendingResidenceNanos;
  delete value.globalPendingAtForward;
  delete value.playerPendingAtForward;
  if (formatVersion >= 4) {
    const forwarded = record.pendingResidenceNanos !== null;
    value.playerQueueDepthAtAdmission = record.playerQueueDepthAtAdmission;
    value.forwardedAtNanos = forwarded
      ? String(BigInt.asIntN(64, t0 + BigInt(record.pendingResidenceNanos))) : null;
    value.pendingResidenceNanos = forwarded ? String(record.pendingResidenceNanos) : null;
    value.globalPendingAtForward = forwarded ? record.globalPendingAtForward : null;
    value.playerPendingAtForward = forwarded ? record.playerPendingAtForward : null;
  }
  if (formatVersion === 5) {
    value.terminalCause = record.terminalCause ?? null;
    value.retirementNoticeResult = record.retirementNoticeResult ?? null;
    value.localCancelStatus = record.localCancelStatus ?? 'NOT_ATTEMPTED';
    value.localCancelSendResult = record.localCancelSendResult ?? null;
    value.providerCancel = record.providerCancel ?? null;
    value.lateOutputRejections = record.lateOutputRejections ?? 0;
  }
  return value;
}

function operationalStream(records, {
  omitted = 0, mode = 'valid', captureHandoffOverflows = 0, formatVersion = 3,
  queueEvidence = {
    pendingQueueScans: 0, pendingQueueRecordSamples: 0,
    maxPendingGlobal: 0, maxPendingPerPlayer: 0,
    maxPendingAgeNanos: '0', maxPendingCleanupDelayNanos: '0',
    maxRecordsPerScan: 0, maxPlayerQueuesPerScan: 0,
    recordLimitPerScan: 17, playerQueueLimitPerScan: 16,
  },
} = {}) {
  const counters = operationalCounterValues().slice(0, formatVersion === 5 ? 47 : 46);
  const outcomeCounterIndexes = {
    HELP: 19, INVALID: 20, DENIED: 21, UNAUTHENTICATED: 22, UNAVAILABLE_BEFORE_BOOTSTRAP: 23,
    RECEIPT_EVIDENCE_MISSING: 24, UNAVAILABLE: 25, BUSY: 26, COALESCED: 27, ADMITTED: 28, FOLLOWUP_ENDED: 29,
    RATE_LIMITED: 39, EXPIRED: 40,
  };
  counters[18] = records.length + omitted;
  for (const record of records) counters[outcomeCounterIndexes[record.outcome]] += 1;
  const noDispatchSettlementElapsed = (record) => record.deadlineSettledAtNanos === -1 ? null
    : BigInt.asIntN(64, BigInt(record.deadlineSettledAtNanos) - BigInt(record.t0Nanos));
  counters[0] = records.filter((record) => record.firstDispatchNanos > 1_000_000_000 ||
    (noDispatchSettlementElapsed(record) ?? -1n) >= 1_000_000_000n).length;
  counters[31] = omitted;
  counters[32] = records.filter((record) => record.firstReplyResult === 'ON_TIME').length;
  counters[33] = records.filter((record) => record.firstReplyResult === 'LATE').length;
  counters[34] = records.filter((record) => record.deadlineSettledAtNanos !== -1).length;
  counters[35] = records.filter((record) => (noDispatchSettlementElapsed(record) ?? 1_000_000_000n) < 1_000_000_000n).length;
  counters[41] = records.filter((record) => record.rateLimitStatus === 'INVOCATION_LIMITED').length;
  counters[42] = records.filter((record) => record.rateLimitStatus === 'NEW_WORK_LIMITED').length;
  counters[43] = records.filter((record) => record.rateLimitStatus === 'SUBJECT_CAPACITY').length;
  counters[44] = records.filter((record) => record.noticeStatus === 'SUPPRESSED').length;
  counters[45] = captureHandoffOverflows;
  if (formatVersion === 5) counters[46] = records.reduce((count, record) => count + (record.lateOutputRejections ?? 0), 0);
  const maxDispatch = Math.max(0, ...records.filter((record) => record.firstDispatchNanos >= 0)
    .map((record) => record.firstDispatchNanos));
  const retained = records.map((record, index) => encodeOperationalInvocation(record, index, formatVersion));
  const chunks = [];
  for (let index = 0; index < retained.length; index += 64) {
    chunks.push({
      type: 'numeric_snapshot_chunk', numericFormatVersion: formatVersion, snapshotId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      index: index / 64, start: index, records: retained.slice(index, index + 64),
    });
  }
  const manifest = {
    type: 'numeric_snapshot_manifest', numericFormatVersion: formatVersion, snapshotId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    sourceInvocationCount: records.length + omitted, retainedInvocationCount: records.length,
    omittedInvocationCount: omitted, captureDroppedInvocationCount: omitted,
    captureStatus: omitted === 0 && captureHandoffOverflows === 0 ? 'complete' : 'incomplete', chunkSize: 64, chunkCount: chunks.length,
    counters, tickHistogram: histogram(), firstPublicDispatchHistograms: dispatchHistograms(),
    maxFirstPublicDispatchNanos: String(maxDispatch),
  };
  if (formatVersion >= 4) manifest.queueEvidence = queueEvidence;
  if (mode === 'missing') chunks.pop();
  if (mode === 'duplicate' && chunks.length > 1) chunks[1] = structuredClone(chunks[0]);
  if (mode === 'out-of-order') chunks.reverse();
  if (mode === 'conflict' && chunks.length > 1) {
    chunks[1] = structuredClone(chunks[0]);
    chunks[1].records[0].id = idFor(10000);
  }
  if (mode === 'duplicate-row' && chunks.length > 1) chunks[1].records[0].id = chunks[0].records[0].id;
  if (mode === 'invalid-row' && chunks.length > 1) chunks[1].records[0].outcome = 'INVALID_UNRECOGNIZED';
  return [manifest, ...chunks].map((item) => JSON.stringify(item)).join('\n') + '\n';
}

function withOperationalSnapshotId(source, snapshotId) {
  return source.trimEnd().split('\n').map((line) => {
    const value = JSON.parse(line);
    value.snapshotId = snapshotId;
    return JSON.stringify(value);
  }).join('\n') + '\n';
}

function completeSessionForm() {
  return {
    type: 'session_attestation', origin: 'human_live', humanAttestation: true, attestedByRole: 'test_administrator',
    artifactHashes: ['a'.repeat(64)],
    versions: { worldspeaker: 'conv-06.1', minecraft: '1.21.1', fabricLoader: '0.16.14', fabricApi: '0.116.7', java: '21', openclaw: '2026.10.8', companion: '0.1.0', node: '24.22.1' },
    modelProviderLabel: 'approved-provider-label', hardwareClass: 'test-server-class',
    workload: { durationMinutes: 10, playerCount: 2 }, clockBoundary: 'human_observed_client',
    personaBriefRevision: 'public-brief-r1', attentionSettings: 'bounded-test-settings',
    consents: [
      { kind: 'shared_game_history', approved: true, reference: 'approval-1', date: '2026-10-29', scope: 'shared_game_history', approvedByRole: 'test_administrator' },
      { kind: 'provider_transfer', approved: true, reference: 'approval-2', date: '2026-10-29', scope: 'provider_transfer', approvedByRole: 'test_administrator' },
      { kind: 'evaluation_records', approved: true, reference: 'approval-3', date: '2026-10-29', scope: 'evaluation_records', approvedByRole: 'test_administrator' },
    ],
    isolationApproval: { approved: true, reference: 'isolation-1', date: '2026-10-29', scope: 'actual_host_private_admin_isolation' },
    discoveries: [
      { playerRole: 'player_1', success: true, seconds: 45, helpProvided: false },
      { playerRole: 'player_2', success: false, seconds: 120, helpProvided: true },
    ],
  };
}

function reviewEntry(reviewerRole, variant = 0) {
  const scenarioIds = [
    'free-conversation', 'unaided-discovery', 'personal-advice', 'context-follow-up', 'shared-fictional-fact',
    'incomplete-old-attention', 'absent-world-ability', 'changed-situation', 'burst-and-distinct-question', 'bounded-delay-or-failure',
  ];
  return {
    type: 'human_review', reviewerRole, scenarioIds: [scenarioIds[variant % scenarioIds.length]],
    ratings: { personaPremiseFit: 4, contextCoherence: 4, answerQuality: Math.floor(variant / scenarioIds.length) + 1, discoverability: 4, enjoyment: 4 },
    reasonCodes: ['answer_relevance'], weaknessCodes: ['none_observed'], terminalClassifications: [],
  };
}

function reviewWithClassifications(reviewerRole, scenarioIds, terminalClassifications) {
  return {
    type: 'human_review', reviewerRole, scenarioIds,
    ratings: { personaPremiseFit: 4, contextCoherence: 4, answerQuality: 4, discoverability: 4, enjoyment: 4 },
    reasonCodes: ['answer_relevance'], weaknessCodes: ['none_observed'], terminalClassifications,
  };
}

function numericReport(scratch, name, records, counters = countersFor(records)) {
  const runDirectory = join(scratch, `${name}-run`);
  const init = command(['init', '--out', runDirectory]);
  assert.equal(init.status, 0, init.stderr);
  const runFile = join(runDirectory, 'run.json');
  const sourcePath = join(runDirectory, 'numeric.json');
  writePrivate(sourcePath, JSON.stringify(snapshot(records, counters)));
  const recorded = command(['record', '--run', runFile, '--entry', sourcePath]);
  assert.equal(recorded.status, 0, recorded.stderr);
  const outputDirectory = join(scratch, `${name}-report`);
  const generated = command(['report', '--run', runFile, '--out', outputDirectory]);
  assert.equal(generated.status, 0, generated.stderr);
  return {
    report: readJson(join(outputDirectory, 'report.json')),
    markdown: readFileSync(join(outputDirectory, 'report.md'), 'utf8'),
  };
}

function privateDirectory() {
  const directory = mkdtempSync(join(realpathSync(tmpdir()), 'worldspeaker-conv06-eval-'));
  chmodSync(directory, 0o700);
  return directory;
}

function writePrivate(path, content) {
  writeFileSync(path, content, { mode: 0o600 });
  chmodSync(path, 0o600);
}

function readJson(path) { return JSON.parse(readFileSync(path, 'utf8')); }

function integrityHash(run) {
  const payload = {
    schemaVersion: run.schemaVersion,
    runId: run.runId,
    provenance: run.provenance,
    aliasSalt: run.aliasSalt,
    aliasIndex: run.aliasIndex,
    metadata: run.metadata,
    entries: run.entries,
  };
  return createHash('sha256').update(JSON.stringify(payload)).digest('hex');
}

function lstatMaybe(path) {
  try {
    return lstatSync(path);
  } catch (error) {
    if (error?.code === 'ENOENT') return null;
    throw error;
  }
}

function command(args) {
  return spawnSync(process.execPath, [cli, ...args], { cwd: root, encoding: 'utf8', timeout: 15_000 });
}

function commandAsync(args) {
  return startCommand(args).result.then((result) => {
    assert.equal(result.timedOut, false, `CLI timed out: ${args.join(' ')}\n${result.stderr}`);
    return result;
  });
}

function startCommand(args, nodeOptions = []) {
  const child = spawn(process.execPath, [...nodeOptions, cli, ...args], { cwd: root });
  const state = { child, stdout: '', stderr: '', result: null };
  child.stdout.setEncoding('utf8');
  child.stderr.setEncoding('utf8');
  child.stdout.on('data', (chunk) => { state.stdout += chunk; });
  child.stderr.on('data', (chunk) => { state.stderr += chunk; });
  state.result = new Promise((resolve, reject) => {
    let timedOut = false;
    const timeout = setTimeout(() => {
      timedOut = true;
      child.kill('SIGKILL');
    }, 15_000);
    child.once('error', (error) => {
      clearTimeout(timeout);
      reject(error);
    });
    child.once('close', (status, signal) => {
      clearTimeout(timeout);
      resolve({ status, signal, stdout: state.stdout, stderr: state.stderr, timedOut });
    });
  });
  return state;
}

function waitForMarker(state, marker, timeoutMs) {
  if (state.stdout.includes(marker)) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => finish(new Error(`Timed out waiting for child marker ${marker}`)), timeoutMs);
    const onData = () => {
      if (state.stdout.includes(marker)) finish();
    };
    const onClose = () => finish(new Error(`Child exited before marker ${marker}: ${state.stderr}`));
    const finish = (error) => {
      clearTimeout(timeout);
      state.child.stdout.off('data', onData);
      state.child.off('close', onClose);
      if (error) reject(error);
      else resolve();
    };
    state.child.stdout.on('data', onData);
    state.child.once('close', onClose);
  });
}
