import assert from 'node:assert/strict';
import { chmod, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import test from 'node:test';
import { performance } from 'node:perf_hooks';
import { startEpoch, waitForServerMarker } from './tick-load.mjs';

test('startup marker helper rejects missing, non-finite, future, and backwards time origins', async () => {
  const server = { exitCode: null, output: '' };
  let waits = 0;
  const dependencies = { now: () => 100, delay: async () => { waits += 1; } };

  for (const runStarted of [undefined, Number.NaN, Number.POSITIVE_INFINITY, -1, 101, Date.now()]) {
    await assert.rejects(waitForServerMarker(server, 'Done (', 500, runStarted, dependencies),
      (error) => error.code === 'monotonic-time-origin-invalid');
  }
  await assert.rejects(waitForServerMarker(server, 'Done (', Number.NaN, 100, dependencies),
    (error) => error.code === 'minecraft-startup-timeout-invalid');
  await assert.rejects(waitForServerMarker(server, 'Done (', 500, 100, {
    ...dependencies,
    runLimitMs: Number.POSITIVE_INFINITY,
  }), (error) => error.code === 'run-time-limit-invalid');

  const clock = [100, 99];
  await assert.rejects(waitForServerMarker(server, 'Done (', 500, 100, {
    now: () => clock.shift(),
    delay: async () => {},
  }), (error) => error.code === 'monotonic-time-origin-invalid');
  assert.equal(waits, 0, 'invalid inputs must fail before polling');
});

test('startEpoch waits for a delayed marker using its run origin', async (t) => {
  const fake = await makeFakeEpoch(t, {
    startupTimeoutMs: 1_000,
    runLimitMs: 5_000,
    afterDelay(state) {
      if (state.waits.length === 2) state.server.output = 'Starting server... Done (';
    },
  });

  const epoch = await fake.run();

  assert.deepEqual(fake.state.waits, [100, 100]);
  assert.equal(epoch.record.serverStarted, true);
  assert.equal(epoch.record.integrationReadyObserved, true);
  assert.equal(fake.state.serverStops, 0);
  assert.equal(fake.state.peerStops, 0);
});

test('startEpoch reports child exit and cleans up both owned children', async (t) => {
  const fake = await makeFakeEpoch(t, {
    startupTimeoutMs: 1_000,
    runLimitMs: 5_000,
    afterDelay(state) { state.server.exitCode = 1; },
  });

  await assert.rejects(fake.run(), (error) => error.code === 'minecraft-exited-before-ready');
  assert.equal(fake.state.waits.length, 1);
  assert.equal(fake.state.serverStops, 1);
  assert.equal(fake.state.peerStops, 1);
});

test('startEpoch distinguishes the startup deadline and cleans up on timeout', async (t) => {
  const fake = await makeFakeEpoch(t, { startupTimeoutMs: 250, runLimitMs: 5_000 });

  await assert.rejects(fake.run(), (error) => error.code === 'minecraft-startup-timeout');
  assertBoundedWaits(fake.state.waits);
  assert.equal(fake.state.serverStops, 1);
  assert.equal(fake.state.peerStops, 1);
});

test('startEpoch caps startup polling at the finite global run deadline', async (t) => {
  const fake = await makeFakeEpoch(t, { startupTimeoutMs: 5_000, runLimitMs: 250 });

  await assert.rejects(fake.run(), (error) => error.code === 'run-time-limit-exceeded');
  assertBoundedWaits(fake.state.waits);
  assert.equal(fake.state.serverStops, 1);
  assert.equal(fake.state.peerStops, 1);
});

test('startEpoch cleans up both children when startup polling is aborted', async (t) => {
  const controller = new AbortController();
  const fake = await makeFakeEpoch(t, {
    startupTimeoutMs: 5_000,
    runLimitMs: 5_000,
    signal: controller.signal,
    afterDelay() { controller.abort(); },
  });

  await assert.rejects(fake.run(), (error) => error.code === 'run-interrupted');
  assert.equal(fake.state.waits.length, 1);
  assert.equal(fake.state.serverStops, 1);
  assert.equal(fake.state.peerStops, 1);
});

async function makeFakeEpoch(t, behavior = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'worldspeaker-startup-regression-'));
  await chmod(directory, 0o700);
  t.after(() => rm(directory, { recursive: true, force: true }));
  const peerConfigPath = join(directory, 'peer.json');
  await writeFile(peerConfigPath, '{}\n', { encoding: 'utf8', mode: 0o600, flag: 'wx' });

  const state = {
    now: performance.now(),
    waits: [],
    server: null,
    peer: null,
    serverStops: 0,
    peerStops: 0,
  };
  const dependencies = {
    startupTimeoutMs: behavior.startupTimeoutMs,
    runLimitMs: behavior.runLimitMs,
    now: () => state.now,
    delay: async (milliseconds) => {
      state.waits.push(milliseconds);
      state.now += milliseconds;
      behavior.afterDelay?.(state, milliseconds);
    },
    throwIfAborted: () => {
      if (!behavior.signal?.aborted) return;
      const error = new Error('run-interrupted');
      error.code = 'run-interrupted';
      throw error;
    },
    startPeerProcess: async () => {
      state.peer = { exitCode: null };
      return state.peer;
    },
    waitForPeerReady: async () => ({}),
    ensureEula: async () => {},
    startServerProcess: () => {
      state.server = { exitCode: null, output: '' };
      return state.server;
    },
    stopServer: async (server) => {
      state.serverStops += 1;
      server.exitCode = 143;
    },
    stopPeer: async (peer) => {
      state.peerStops += 1;
      peer.exitCode = 143;
    },
    isLoopbackPortOpen: async () => true,
    waitForIntegrationReady: async () => ({ authorizedListLoaded: true, integrationReady: true }),
    writeOutput: () => {},
  };
  const runStarted = state.now;
  const context = {
    epochNumber: 1,
    runId: 'offline-startup-regression',
    runDirectory: directory,
    serverDirectory: join(directory, 'server'),
    peerConfigPath,
    controlPath: join(directory, 'control.json'),
    companionPort: 18432,
    serverPort: 25565,
    players: [],
    authorizedPlayersPath: join(directory, 'players.txt'),
    options: { java: 'unused-java', node: 'unused-node' },
    environment: {},
    record: { runs: [{ run: 1 }] },
    totalStarted: runStarted,
    runStarted,
    dependencies,
  };
  return { run: () => startEpoch(context), state };
}

function assertBoundedWaits(waits) {
  assert.equal(waits.length, 3);
  assert.deepEqual(waits.slice(0, 2), [100, 100]);
  assert.ok(Math.abs(waits[2] - 50) < 1e-9, `expected final wait near 50ms, got ${waits[2]}`);
}
