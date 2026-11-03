import assert from 'node:assert/strict';
import test from 'node:test';
import { executeUnavailableRecovery } from './tick-load.mjs';

test('unavailable outage reaches recovery epoch with the same run clock and cleans up both epochs', async () => {
  const runStarted = 2_010_000;
  const state = {
    now: runStarted,
    phase: null,
    controls: [],
    waits: [],
    stopped: [],
    collected: [],
    recoveryStart: null,
  };
  const first = makeEpoch(1);
  const runRecord = { epochs: [first.record] };
  const record = { runs: [runRecord] };
  const status = {
    authorizedListLoaded: true,
    integrationReady: true,
    onlineAuthorized: 1,
    onlineAuthorizedHighWater: 1,
    authorizedCount: 1,
  };
  const dependencies = {
    now: () => state.now,
    runLimitMs: 40 * 60 * 1000,
    delay: async (milliseconds) => {
      state.waits.push(milliseconds);
      state.now += milliseconds;
    },
    throwIfAborted: () => {},
    writeControl: async (_path, _runId, phase) => {
      state.phase = phase;
      state.controls.push(phase);
    },
    readPeerStatus: async () => ({ schemaVersion: 1, phase: state.phase, listening: true }),
    readHarnessStatus: async () => status,
    saveRecord: async () => {},
    stopPeer: async (peer) => {
      state.stopped.push(`peer-${peer.epochNumber}`);
      peer.exitCode = 0;
    },
    stopServer: async (server) => {
      state.stopped.push(`server-${server.epochNumber}`);
      server.exitCode = 0;
    },
    collectEpochEvidence: async (epochRecord) => {
      state.collected.push(epochRecord.epochNumber);
      epochRecord.evidenceComplete = true;
    },
  };

  const recovery = await executeUnavailableRecovery({
    epoch: first,
    runStarted,
    totalStarted: 10_000,
    runRecord,
    record,
    recordPath: 'fake-record.json',
    dependencies,
    startRecoveryEpoch: async (context) => {
      state.recoveryStart = context;
      return makeEpoch(2);
    },
  });

  assert.equal(recovery.record.epochNumber, 2);
  assert.deepEqual(first.record.phases, [
    { name: 'unavailable', durationMs: 300_000, peerRunning: false },
  ]);
  assert.deepEqual(recovery.record.phases.map(({ name, durationMs }) => [name, durationMs]), [
    ['recovery', 120_000],
    ['recovery', 300_000],
    ['repeat', 6_000],
    ['stop-backlog', 6_000],
  ]);
  assert.deepEqual(state.controls, ['unavailable', 'recovery', 'recovery', 'repeat', 'stop-backlog']);
  assert.equal(state.recoveryStart.runStarted, runStarted, 'recovery must not reset the per-run budget origin');
  assert.equal(state.recoveryStart.totalStarted, 10_000);
  assert.equal(state.now - runStarted, 732_000, 'outage and recovery delays must run on the injected monotonic clock');
  assert.ok(state.now - 10_000 > 40 * 60 * 1000,
    'the earlier invocation origin must not consume this run\'s 40-minute budget');
  assert.deepEqual(state.stopped, ['peer-1', 'server-1', 'server-2', 'peer-2']);
  assert.deepEqual(state.collected, [1, 2]);
  assert.equal(runRecord.epochs.length, 2);
  assert.equal(first.record.state, 'complete');
  assert.equal(recovery.record.state, 'complete');
});

function makeEpoch(epochNumber) {
  const peer = { epochNumber, exitCode: null };
  const server = { epochNumber, exitCode: null };
  const paths = { statusPath: `epoch-${epochNumber}-status.json` };
  const record = {
    epochNumber,
    epochId: `epoch-${epochNumber}-id`,
    state: 'running',
    phases: [],
    phaseSummaries: [],
    paths,
  };
  return {
    peer,
    server,
    paths,
    statusPath: paths.statusPath,
    controlPath: 'fake-control.json',
    record,
  };
}
