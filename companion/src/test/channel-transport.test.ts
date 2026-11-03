import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:net';
import test from 'node:test';
import { WebSocket } from 'ws';
import type { OpenClawPluginApi } from 'openclaw/plugin-sdk/channel-core';
import { resolveAgentRoute } from 'openclaw/plugin-sdk/routing';
import { DEPLOYMENT } from '../generated/deployment.js';
import { HostWorkBudget } from '../work-budget.js';
import {
  authenticateChannelSocket,
  bounded,
  channelAcknowledgementFrame,
  channelCancellation,
  channelCloseDetails,
  channelProgressFrame,
  channelRequest,
  CHANNEL_TEST_PLAYER_A,
  CHANNEL_TEST_PLAYER_B,
  createChannelBackedTransport,
  nextChannelFrame,
} from './transport-channel-fixture.js';
import type { DialogueRequest } from '../protocol.js';
import { clientTranscript, createProof } from '../protocol.js';

test('real authenticated channel classifies only settled A-local failures and serves useful B', { timeout: 20_000 }, async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const statuses: string[] = [];
  const dispatches: string[] = [];
  let refusedRequestId: string | undefined;
  const fixture = createChannelBackedTransport(port, secret, {
    onStatus: (status, reason) => statuses.push(`${status}:${reason}`),
    dispatch: async (request, deliver) => {
      dispatches.push(request.requestId);
      switch (request.event.text) {
        case 'A invalid final':
          await deliver({ text: 'provider error', isError: true }, { kind: 'final' });
          return;
        case 'A no final':
          return;
        case 'A refused final':
          refusedRequestId = request.requestId;
          await deliver({ text: 'This safe final is refused by the transport seam.' }, { kind: 'final' });
          return;
        default:
          await deliver({ text: '7 * 8 = 56.' }, { kind: 'final' });
      }
    },
  });
  // Deterministically emulate a live socket refusing this one answer enqueue; the real channel
  // handler must classify its null send result as request-local and the transport must send A's failure.
  const sendSeam = fixture.transport as unknown as {
    send(peer: unknown, frame: Record<string, unknown>): boolean;
  };
  const originalSend = sendSeam.send.bind(fixture.transport);
  sendSeam.send = (peer, frame) => frame.type === 'dialogue.message' && frame.requestId === refusedRequestId
    ? false
    : originalSend(peer, frame);

  let socket: WebSocket | undefined;
  try {
    await fixture.transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    assert.equal((await authenticateChannelSocket(socket, secret)).type, 'welcome');

    for (const text of ['A invalid final', 'A no final', 'A refused final']) {
      const request = channelRequest(CHANNEL_TEST_PLAYER_A, text);
      const responsePromise = nextChannelFrame(socket);
      socket.send(JSON.stringify(request));
      const response = await responsePromise;
      assert.equal(response.type, 'dialogue.failure', `${text} has one safe terminal refusal`);
      assert.equal(response.requestId, request.requestId);
      assert.equal(response.invocationId, request.invocationId);
      assert.deepEqual(response.replyAudience, { kind: 'player', playerId: CHANNEL_TEST_PLAYER_A });
      assert.equal(socket.readyState, WebSocket.OPEN, `${text} does not poison the authenticated peer`);

      const bRequest = channelRequest(CHANNEL_TEST_PLAYER_B, 'What is 7 * 8?');
      const bResponsePromise = nextChannelFrame(socket);
      socket.send(JSON.stringify(bRequest));
      const bResponse = await bResponsePromise;
      assert.equal(bResponse.type, 'dialogue.message', 'B receives a useful complete answer after A-local failure');
      assert.equal(bResponse.requestId, bRequest.requestId);
      assert.equal(bResponse.text, '7 * 8 = 56.');
      assert.deepEqual(bResponse.replyAudience, { kind: 'player', playerId: CHANNEL_TEST_PLAYER_B });
      assert.equal(socket.readyState, WebSocket.OPEN);
    }

    const heartbeatPromise = nextChannelFrame(socket);
    socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 1 }));
    assert.deepEqual(await heartbeatPromise, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 1 });
    assert.equal(dispatches.length, 6, 'each A failure and each separate B turn reached the real channel handler once');
    assert.ok(statuses.includes('ready:WS-COMPAT-READY'));
    assert.equal(statuses.includes('unavailable:WS-COMPAT-HOST-REQUEST-FAILED'), false);
    assert.equal(statuses.includes('unavailable:WS-COMPAT-ACK-CONTEXT-UPDATE-FAILED'), false);
  } finally {
    socket?.terminate();
    await fixture.transport.close();
  }
});

test('a throw after actual answer delivery neither duplicates A terminal nor poisons B', { timeout: 10_000 }, async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const statuses: string[] = [];
  const postAnswerThrow = deferred<void>();
  const throwCompleted = deferred<void>();
  const dispatches: string[] = [];
  const fixture = createChannelBackedTransport(port, secret, {
    onStatus: (status, reason) => statuses.push(`${status}:${reason}`),
    dispatch: async (request, deliver) => {
      dispatches.push(request.requestId);
      if (request.event.text === 'answer, then throw') {
        await deliver({ text: 'A answer was delivered once.' }, { kind: 'final' });
        await postAnswerThrow.promise;
        try {
          throw new Error('post-answer SDK cleanup failure');
        } catch (error) {
          throwCompleted.resolve();
          throw error;
        }
      }
      await deliver({ text: 'B still gets 56.' }, { kind: 'final' });
    },
  });
  let socket: WebSocket | undefined;
  try {
    await fixture.transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateChannelSocket(socket, secret);

    const aRequest = channelRequest(CHANNEL_TEST_PLAYER_A, 'answer, then throw');
    const aResponsePromise = nextChannelFrame(socket);
    socket.send(JSON.stringify(aRequest));
    const aResponse = await aResponsePromise;
    assert.equal(aResponse.type, 'dialogue.message');
    assert.equal(aResponse.requestId, aRequest.requestId);
    assert.equal(aResponse.text, 'A answer was delivered once.');
    assert.deepEqual(aResponse.replyAudience, { kind: 'player', playerId: CHANNEL_TEST_PLAYER_A });

    postAnswerThrow.resolve();
    await bounded(throwCompleted.promise, 'the SDK post-answer throw');
    const bRequest = channelRequest(CHANNEL_TEST_PLAYER_B, 'What is 7 * 8?');
    const bResponsePromise = nextChannelFrame(socket);
    socket.send(JSON.stringify(bRequest));
    const bResponse = await bResponsePromise;
    assert.equal(bResponse.type, 'dialogue.message');
    assert.equal(bResponse.requestId, bRequest.requestId);
    assert.equal(bResponse.text, 'B still gets 56.');
    assert.deepEqual(bResponse.replyAudience, { kind: 'player', playerId: CHANNEL_TEST_PLAYER_B });
    assert.equal(socket.readyState, WebSocket.OPEN);
    assert.equal(dispatches.length, 2);
    assert.equal(statuses.includes('unavailable:WS-COMPAT-HOST-REQUEST-FAILED'), false);

    const heartbeatPromise = nextChannelFrame(socket);
    socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 1 }));
    assert.deepEqual(await heartbeatPromise, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 1 },
      'an extra A failure or second terminal would arrive before this ordered heartbeat barrier');
  } finally {
    postAnswerThrow.resolve();
    socket?.terminate();
    await fixture.transport.close();
  }
});

test('actual ACK context injection loss stays sticky across account restart and blocks B', { timeout: 15_000 }, async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const budget = new HostWorkBudget();
  const statuses: string[] = [];
  const injections: Parameters<OpenClawPluginApi['session']['workflow']['enqueueNextTurnInjection']>[0][] = [];
  let dispatchCount = 0;
  const enqueue: OpenClawPluginApi['session']['workflow']['enqueueNextTurnInjection'] = async (input) => {
    injections.push(input);
    throw new Error('pinned SDK next-turn history queue rejected the accepted ACK');
  };
  const options = {
    onStatus: (status: 'starting' | 'ready' | 'unavailable', reason: string) => statuses.push(`${status}:${reason}`),
    enqueueContext: enqueue,
    dispatch: async (_request: DialogueRequest, deliver: (payload: Record<string, unknown>, info: { kind: string }) => Promise<unknown>) => {
      dispatchCount++;
      await deliver({ text: 'A complete answer.' }, { kind: 'final' });
    },
  };
  const first = createChannelBackedTransport(port, secret, options, budget);
  let restarted: ReturnType<typeof createChannelBackedTransport> | undefined;
  let socket: WebSocket | undefined;
  let restartedSocket: WebSocket | undefined;
  try {
    await first.transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateChannelSocket(socket, secret);
    const request = channelRequest(CHANNEL_TEST_PLAYER_A, 'A with dispatched ACK context');
    const answerPromise = nextChannelFrame(socket);
    socket.send(JSON.stringify(request));
    assert.equal((await answerPromise).type, 'dialogue.message');

    const closePromise = channelCloseDetails(socket);
    socket.send(JSON.stringify(channelAcknowledgementFrame(request)));
    assert.deepEqual(await closePromise, { code: 1011, reason: 'service unavailable' });
    assert.equal(injections.length, 1, 'the real public next-turn SDK seam received the actual ACK context');
    assert.ok(String(injections[0]?.idempotencyKey).startsWith('worldspeaker:ack:'));
    assert.ok(statuses.includes('unavailable:WS-COMPAT-ACK-CONTEXT-UPDATE-FAILED'));
    assert.equal(dispatchCount, 1);

    socket = undefined;
    await first.transport.close();
    restarted = createChannelBackedTransport(await availablePort(), secret, {
      onStatus: (status, reason) => statuses.push(`${status}:${reason}`),
      dispatch: async (_request, deliver) => {
        dispatchCount++;
        await deliver({ text: 'Must not pass failed accepted history.' }, { kind: 'final' });
      },
    }, budget);
    await restarted.transport.start();
    restartedSocket = new WebSocket(`ws://127.0.0.1:${restarted.port}`);
    const challenge = await nextChannelFrame(restartedSocket);
    assert.equal(challenge.type, 'challenge');
    const closeAfterHello = channelCloseDetails(restartedSocket);
    restartedSocket.send(JSON.stringify(makeHello(secret, challenge.challenge as string)));
    assert.deepEqual(await closeAfterHello, { code: 1011, reason: 'service unavailable' });
    assert.equal(dispatchCount, 1, 'account restart cannot route B around ACK history loss');
  } finally {
    socket?.terminate();
    restartedSocket?.terminate();
    await first.transport.close();
    await restarted?.transport.close();
  }
});

test('actual failed progress injection prevents queued B and remains sticky after restart', { timeout: 15_000 }, async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const budget = new HostWorkBudget();
  const statuses: string[] = [];
  const progressInjection = deferred<void>();
  const aStarted = deferred<void>();
  const finishA = deferred<void>();
  const dispatches: string[] = [];
  const enqueue: OpenClawPluginApi['session']['workflow']['enqueueNextTurnInjection'] = async (input) => {
    if ((input.metadata as Record<string, unknown> | undefined)?.kind === 'dispatched-progress') progressInjection.resolve();
    throw new Error('pinned SDK progress history queue rejected the accepted notice');
  };
  const first = createChannelBackedTransport(port, secret, {
    onStatus: (status, reason) => statuses.push(`${status}:${reason}`),
    enqueueContext: enqueue,
    dispatch: async (request, deliver) => {
      dispatches.push(request.requestId);
      if (request.event.text === 'A remains in progress') {
        aStarted.resolve();
        await finishA.promise;
      }
      await deliver({ text: 'A completes, but failed history must stop B.' }, { kind: 'final' });
    },
  }, budget);
  let restarted: ReturnType<typeof createChannelBackedTransport> | undefined;
  let socket: WebSocket | undefined;
  let restartedSocket: WebSocket | undefined;
  try {
    await first.transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateChannelSocket(socket, secret);

    const aRequest = channelRequest(CHANNEL_TEST_PLAYER_A, 'A remains in progress');
    socket.send(JSON.stringify(aRequest));
    await bounded(aStarted.promise, 'A entering the real SDK dispatcher');
    socket.send(JSON.stringify(channelProgressFrame(aRequest)));
    const bRequest = channelRequest(CHANNEL_TEST_PLAYER_B, 'B must not pass failed progress history');
    socket.send(JSON.stringify(bRequest));
    const inboundBarrier = nextChannelFrame(socket);
    socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 1 }));
    assert.deepEqual(await inboundBarrier, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 1 });

    const aAnswerPromise = nextChannelFrame(socket);
    const closePromise = channelCloseDetails(socket);
    finishA.resolve();
    const aAnswer = await aAnswerPromise;
    assert.equal(aAnswer.type, 'dialogue.message');
    assert.equal(aAnswer.requestId, aRequest.requestId);
    assert.deepEqual(aAnswer.replyAudience, { kind: 'player', playerId: CHANNEL_TEST_PLAYER_A });
    assert.deepEqual(await closePromise, { code: 1011, reason: 'service unavailable' });
    await bounded(progressInjection.promise, 'the actual progress next-turn SDK seam');
    assert.ok(statuses.includes('unavailable:WS-COMPAT-PROGRESS-CONTEXT-UPDATE-FAILED'));
    assert.deepEqual(dispatches, [aRequest.requestId], 'queued B is retired when accepted progress history fails');

    socket = undefined;
    await first.transport.close();
    restarted = createChannelBackedTransport(await availablePort(), secret, {
      onStatus: (status, reason) => statuses.push(`${status}:${reason}`),
      dispatch: async (request, deliver) => {
        dispatches.push(request.requestId);
        await deliver({ text: 'Must not pass failed progress history.' }, { kind: 'final' });
      },
    }, budget);
    await restarted.transport.start();
    const restartPort = restarted.port;
    restartedSocket = new WebSocket(`ws://127.0.0.1:${restartPort}`);
    const challenge = await nextChannelFrame(restartedSocket);
    const closeAfterHello = channelCloseDetails(restartedSocket);
    restartedSocket.send(JSON.stringify(makeHello(secret, challenge.challenge as string)));
    assert.deepEqual(await closeAfterHello, { code: 1011, reason: 'service unavailable' });
    assert.deepEqual(dispatches, [aRequest.requestId]);
  } finally {
    finishA.resolve();
    socket?.terminate();
    restartedSocket?.terminate();
    await first.transport.close();
    await restarted?.transport.close();
  }
});

test('authenticated wire binding mismatch is rejected before channel history or B dispatch', { timeout: 10_000 }, async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  let dispatchCount = 0;
  const fixture = createChannelBackedTransport(port, secret, {
    dispatch: async (_request, deliver) => {
      dispatchCount++;
      await deliver({ text: 'A mismatched binding must never dispatch.' }, { kind: 'final' });
    },
  });
  let socket: WebSocket | undefined;
  try {
    await fixture.transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateChannelSocket(socket, secret);
    const valid = channelRequest(CHANNEL_TEST_PLAYER_A, 'A claims another world');
    const mismatched = {
      ...valid,
      worldId: `${valid.worldId}-other`,
      event: { ...valid.event, worldId: `${valid.worldId}-other` },
    };
    const closePromise = channelCloseDetails(socket);
    socket.send(JSON.stringify(mismatched));
    assert.deepEqual(await closePromise, { code: 4002, reason: 'dialogue request rejected' });
    assert.equal(dispatchCount, 0, 'binding validation precedes the real typed channel handler');
  } finally {
    socket?.terminate();
    await fixture.transport.close();
  }
});

test('SDK shared-route binding failure closes admission instead of becoming an A-local answer failure', { timeout: 15_000 }, async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const budget = new HostWorkBudget();
  const statuses: string[] = [];
  let dispatchCount = 0;
  const first = createChannelBackedTransport(port, secret, {
    onStatus: (status, reason) => statuses.push(`${status}:${reason}`),
    resolveRoute: (input) => {
      const route = resolveAgentRoute(input);
      return { ...route, sessionKey: route.mainSessionKey };
    },
    dispatch: async (_request, deliver) => {
      dispatchCount++;
      await deliver({ text: 'A route mismatch must not reach dispatch.' }, { kind: 'final' });
    },
  }, budget);
  let restarted: ReturnType<typeof createChannelBackedTransport> | undefined;
  let socket: WebSocket | undefined;
  let restartedSocket: WebSocket | undefined;
  try {
    await first.transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateChannelSocket(socket, secret);
    const closePromise = channelCloseDetails(socket);
    socket.send(JSON.stringify(channelRequest(CHANNEL_TEST_PLAYER_A, 'A must not pass route binding failure')));
    assert.deepEqual(await closePromise, { code: 1011, reason: 'service unavailable' });
    assert.ok(statuses.includes('unavailable:WS-COMPAT-HOST-REQUEST-FAILED'));
    assert.equal(dispatchCount, 0, 'the production channel verifies the SDK route before ingress/dispatch');

    socket = undefined;
    await first.transport.close();
    restarted = createChannelBackedTransport(await availablePort(), secret, {
      onStatus: (status, reason) => statuses.push(`${status}:${reason}`),
      dispatch: async (_request, deliver) => {
        dispatchCount++;
        await deliver({ text: 'Must not pass a sticky route/history failure.' }, { kind: 'final' });
      },
    }, budget);
    await restarted.transport.start();
    const restartPort = restarted.port;
    restartedSocket = new WebSocket(`ws://127.0.0.1:${restartPort}`);
    const challenge = await nextChannelFrame(restartedSocket);
    const closeAfterHello = channelCloseDetails(restartedSocket);
    restartedSocket.send(JSON.stringify(makeHello(secret, challenge.challenge as string)));
    assert.deepEqual(await closeAfterHello, { code: 1011, reason: 'service unavailable' });
    assert.equal(dispatchCount, 0, 'restart does not clear a real binding/history failure');
  } finally {
    socket?.terminate();
    restartedSocket?.terminate();
    await first.transport.close();
    await restarted?.transport.close();
  }
});

test('16 actual host permits survive cancel, disconnect, account restart, then release before B serves', { timeout: 20_000 }, async () => {
  const firstPort = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const budget = new HostWorkBudget();
  const firstStarted = deferred<void>();
  const releaseFirst = deferred<void>();
  let firstRequest: DialogueRequest | undefined;
  const first = createChannelBackedTransport(firstPort, secret, {
    dispatch: async (request, deliver) => {
      if (!firstRequest) {
        firstRequest = request;
        firstStarted.resolve();
        await releaseFirst.promise;
      }
      await deliver({ text: 'The released request is no longer live.' }, { kind: 'final' });
    },
  }, budget);
  const statuses: string[] = [];
  let replacementDispatches = 0;
  let replacement: ReturnType<typeof createChannelBackedTransport> | undefined;
  let socket: WebSocket | undefined;
  let refusedSocket: WebSocket | undefined;
  let recoverySocket: WebSocket | undefined;
  try {
    await first.transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${firstPort}`);
    await authenticateChannelSocket(socket, secret);

    const firstWireRequest = channelRequest(CHANNEL_TEST_PLAYER_A, 'held A');
    socket.send(JSON.stringify(firstWireRequest));
    await bounded(firstStarted.promise, 'the first real host dispatcher');
    for (let index = 0; index < 15; index++) {
      socket.send(JSON.stringify(channelRequest(CHANNEL_TEST_PLAYER_A, `queued host permit ${index}`)));
    }
    const admissionBarrier = nextChannelFrame(socket);
    socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 1 }));
    assert.deepEqual(await admissionBarrier, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 1 });
    assert.deepEqual(budget.snapshot(), { hostCalls: 16, acknowledgementCalls: 0 },
      'all sixteen permits came from authenticated dialogue.request admissions');

    const cancelBarrier = nextChannelFrame(socket);
    socket.send(JSON.stringify(channelCancellation(firstWireRequest)));
    socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 2 }));
    assert.deepEqual(await cancelBarrier, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 2 });
    const disconnected = channelCloseDetails(socket);
    socket.terminate();
    await disconnected;
    socket = undefined;
    await first.transport.close();
    assert.deepEqual(budget.snapshot(), { hostCalls: 16, acknowledgementCalls: 0 },
      'cancel, disconnect, and account stop do not release running or queued host work');

    const restartPort = await availablePort();
    replacement = createChannelBackedTransport(restartPort, secret, {
      onStatus: (status, reason) => statuses.push(`${status}:${reason}`),
      dispatch: async (_request, deliver) => {
        replacementDispatches++;
        await deliver({ text: 'After settlement B receives 56.' }, { kind: 'final' });
      },
    }, budget);
    await replacement.transport.start();
    refusedSocket = new WebSocket(`ws://127.0.0.1:${restartPort}`);
    const challenge = await nextChannelFrame(refusedSocket);
    const saturatedClose = channelCloseDetails(refusedSocket);
    refusedSocket.send(JSON.stringify(makeHello(secret, challenge.challenge as string)));
    assert.deepEqual(await saturatedClose, { code: 1011, reason: 'service unavailable' },
      'account restart cannot replace the sixteen unsettled permits');
    assert.ok(statuses.includes('unavailable:WS-COMPAT-HOST-WORK-LIMIT'));
    assert.deepEqual(budget.snapshot(), { hostCalls: 16, acknowledgementCalls: 0 });
    assert.equal(replacementDispatches, 0);

    releaseFirst.resolve();
    await waitForBudgetState(budget, { hostCalls: 0, acknowledgementCalls: 0 });
    assert.ok(firstRequest);

    recoverySocket = new WebSocket(`ws://127.0.0.1:${restartPort}`);
    await authenticateChannelSocket(recoverySocket, secret);
    const bRequest = channelRequest(CHANNEL_TEST_PLAYER_B, 'What is 7 * 8?');
    const bResponsePromise = nextChannelFrame(recoverySocket);
    recoverySocket.send(JSON.stringify(bRequest));
    const bResponse = await bResponsePromise;
    assert.equal(bResponse.type, 'dialogue.message');
    assert.equal(bResponse.requestId, bRequest.requestId);
    assert.equal(bResponse.text, 'After settlement B receives 56.');
    assert.deepEqual(bResponse.replyAudience, { kind: 'player', playerId: CHANNEL_TEST_PLAYER_B });
    assert.equal(replacementDispatches, 1);
    assert.deepEqual(budget.snapshot(), { hostCalls: 0, acknowledgementCalls: 0 });
  } finally {
    releaseFirst.resolve();
    socket?.terminate();
    refusedSocket?.terminate();
    recoverySocket?.terminate();
    await first.transport.close();
    await replacement?.transport.close();
  }
});

function deferred<T = void>(): { promise: Promise<T>; resolve: (value: T | PromiseLike<T>) => void } {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((complete) => { resolve = complete; });
  return { promise, resolve };
}

async function waitForBudgetState(
  budget: HostWorkBudget,
  expected: Readonly<{ hostCalls: number; acknowledgementCalls: number }>,
): Promise<void> {
  const deadline = performance.now() + 3000;
  while (budget.snapshot().hostCalls !== expected.hostCalls ||
      budget.snapshot().acknowledgementCalls !== expected.acknowledgementCalls) {
    if (performance.now() >= deadline) throw new Error('timed out waiting for real host permit settlement (3s)');
    await new Promise<void>((resolve) => setImmediate(resolve));
  }
}

function makeHello(secret: string, challenge: string) {
  // Authenticate with the same public transcript primitive used by the Java companion.
  const unsigned = {
    type: 'hello' as const,
    protocol: DEPLOYMENT.protocol,
    challenge,
    nonce: randomBytes(32).toString('base64url'),
    deploymentId: DEPLOYMENT.deploymentId,
    manifestSha256: DEPLOYMENT.manifestSha256,
    minecraftVersion: DEPLOYMENT.minecraftVersion,
    fabricLoaderVersion: DEPLOYMENT.fabricLoaderVersion,
    fabricApiVersion: DEPLOYMENT.fabricApiVersion,
    javaMajor: DEPLOYMENT.javaMajor,
    javaVendor: DEPLOYMENT.javaVendor,
    javaRuntimeVersion: DEPLOYMENT.javaRuntimeVersion,
    configurationHandshakeVersion: 1,
    configSetSha256: 'a'.repeat(64),
    serverId: 'server-channel-transport-test',
    worldId: 'world-channel-transport-test',
    characterId: 'character-channel-transport-test',
    agentId: 'agent-channel-transport-test',
  };
  return { ...unsigned, proof: createProof(secret, clientTranscript(unsigned)) };
}

async function availablePort(): Promise<number> {
  const server = createServer();
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  if (address === null || typeof address === 'string') throw new Error('no ephemeral TCP port was assigned');
  const port = address.port;
  await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  return port;
}
