import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
import { createInterface } from 'node:readline';
import test from 'node:test';
import { WebSocket, type RawData } from 'ws';
import { parseAbilityPolicy } from '../ability-policy.js';
import { DEPLOYMENT } from '../generated/deployment.js';
import type { MonotonicNow } from '../monotonic-clock.js';
import { clientTranscript, createProof, ATTENTION_EVENT_FIELDS, DIALOGUE_EVENT_FIELDS, HANDSHAKE_TIMEOUT_MS, serverChallengeTranscript, serverTranscript, type AttentionEvent, type AttributedEvent, type Binding, type ClientHello, type ContextEvent, type DialogueRequest, type WelcomeFacts } from '../protocol.js';
import { WorldspeakerTransport, type DialogueAcknowledgementHandler, type DialogueRequestHandler, type TransportConfig } from '../transport.js';
import { HostWorkBudget } from '../work-budget.js';
import { createManualMonotonicClock } from './monotonic-clock.js';

const binding: Binding = {
  serverId: 'server-transport-test',
  worldId: 'world-transport-test',
  characterId: 'character-transport-test',
  agentId: 'agent-transport-test',
};

test('direct transport construction rejects unsafe endpoint and credential values before listener startup', () => {
  const config: TransportConfig = {
    enabled: true,
    bindHost: '127.0.0.1',
    port: 18473,
    serverEndpoint: 'ws://127.0.0.1:18473',
    bearerSecret: randomBytes(32).toString('base64url'),
    deploymentId: DEPLOYMENT.deploymentId,
    manifestSha256: DEPLOYMENT.manifestSha256,
    configurationHandshakeVersion: 1,
    configSetSha256: 'a'.repeat(64),
    binding,
  };
  const construct = (candidate: TransportConfig) => new WorldspeakerTransport(candidate,
    DEPLOYMENT.hostVersion, DEPLOYMENT.companionVersion, async () => {}, async () => {}, () => {});
  assert.throws(() => construct({ ...config, serverEndpoint: `ws://127.0.0.1/${'a'.repeat(2_048)}` }), /endpoint/u);
  assert.throws(() => construct({ ...config, serverEndpoint: 'ws://example.invalid:18473' }), /endpoint/u);
  assert.throws(() => construct({ ...config, bearerSecret: 'short' }), /secret/u);
});

test('concurrent transport close calls share completion and a closed transport cannot restart', async () => {
  const port = await availablePort();
  const transport = new WorldspeakerTransport({
    enabled: true, bindHost: '127.0.0.1', port, serverEndpoint: `ws://127.0.0.1:${port}`,
    bearerSecret: randomBytes(32).toString('base64url'), deploymentId: DEPLOYMENT.deploymentId,
    manifestSha256: DEPLOYMENT.manifestSha256, configurationHandshakeVersion: 1,
    configSetSha256: 'a'.repeat(64), binding,
  }, DEPLOYMENT.hostVersion, DEPLOYMENT.companionVersion, async () => {}, async () => {}, () => {});
  try {
    await transport.start();
    const first = transport.close();
    const second = transport.close();
    try {
      assert.strictEqual(first, second, 'every close caller must await the actual shared socket shutdown');
    } finally {
      await Promise.all([first, second]);
    }
    assert.strictEqual(transport.close(), first);
    await assert.rejects(transport.start(), /stopped/u);
    const probe = createServer();
    await new Promise<void>((resolve, reject) => {
      probe.once('error', reject);
      probe.listen(port, '127.0.0.1', resolve);
    });
    await new Promise<void>((resolve, reject) => probe.close((error) => error ? reject(error) : resolve()));
  } finally {
    await transport.close();
  }
});

test('one bound socket completes nonce/HMAC handshake, heartbeat, and addressed final delivery', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const config: TransportConfig = {
    enabled: true,
    bindHost: '127.0.0.1',
    port,
    serverEndpoint: `ws://127.0.0.1:${port}`,
    bearerSecret: secret,
    deploymentId: DEPLOYMENT.deploymentId,
    manifestSha256: DEPLOYMENT.manifestSha256,
    configurationHandshakeVersion: 1,
    configSetSha256: 'a'.repeat(64),
    binding,
  };
  const statuses: string[] = [];
  let acknowledgementCount = 0;
  let socket: WebSocket | undefined;
  const transport = new WorldspeakerTransport(config, DEPLOYMENT.hostVersion, DEPLOYMENT.companionVersion,
    async (request, sendFinal) => {
      assert.equal(request.event.text, 'A bounded test prompt');
      assert.equal(request.event.capturedAtMonotonicNanos, '-9223372036854775808');
      assert.equal(request.acknowledgement, null);
      const messageId = sendFinal('A bounded test answer');
      assert.ok(messageId);
      assert.match(messageId, /^[0-9a-f-]{36}$/i);
    },
    async () => { acknowledgementCount++; },
    (status) => statuses.push(status));
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    const challengePromise = nextMessage(socket);
    await waitForOpen(socket);
    const challenge = await challengePromise;
    assert.equal(challenge.type, 'challenge');
    const hello = createHello(secret, challenge.challenge as string);
    const welcomePromise = nextMessage(socket);
    socket.send(JSON.stringify(hello));
    const welcome = await welcomePromise;
    assert.equal(welcome.type, 'welcome');
    assert.equal(welcome.challenge, hello.challenge);
    assert.equal(welcome.nonce, hello.nonce);
    const facts: WelcomeFacts = {
      deploymentId: DEPLOYMENT.deploymentId,
      manifestSha256: DEPLOYMENT.manifestSha256,
      protocol: DEPLOYMENT.protocol,
      hostVersion: DEPLOYMENT.hostVersion,
      nodeVersion: process.versions.node,
      companionVersion: DEPLOYMENT.companionVersion,
      configurationHandshakeVersion: 1,
      configSetSha256: 'a'.repeat(64),
      ...binding,
    };
    assert.equal(challenge.proof, createProof(secret, serverChallengeTranscript(challenge.challenge as string, facts)));
    assert.equal(welcome.proof, createProof(secret, serverTranscript(hello.challenge, hello.nonce, facts)));
    assert.ok(statuses.includes('ready'));

    const heartbeatPromise = nextMessage(socket);
    socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 1 }));
    const heartbeat = await heartbeatPromise;
    assert.deepEqual(heartbeat, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 1 });

    const playerId = randomUUID();
    const requestId = randomUUID();
    const request = createRequest(requestId, playerId, null, '-9223372036854775808');
    const answerPromise = nextMessage(socket);
    socket.send(JSON.stringify(request));
    const answer = await answerPromise;
    assert.equal(answer.type, 'dialogue.message');
    assert.equal(answer.requestId, requestId);
    assert.deepEqual(answer.replyAudience, { kind: 'player', playerId });
    assert.equal(answer.text, 'A bounded test answer');
    assert.equal(acknowledgementCount, 0, 'a request without a locally dispatched acknowledgement creates no context update');
  } finally {
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    await transport.close();
  }
});

test('authenticated request with a mismatched policy revision is rejected before identity retention or dispatch', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const abilityPolicy = parseAbilityPolicy({
    schemaVersion: 1, revision: 23, grants: [], denials: [], protection: { externalClaimsMode: 'NONE' },
  }, binding.characterId);
  const config: TransportConfig = {
    enabled: true,
    bindHost: '127.0.0.1',
    port,
    serverEndpoint: `ws://127.0.0.1:${port}`,
    bearerSecret: secret,
    deploymentId: DEPLOYMENT.deploymentId,
    manifestSha256: DEPLOYMENT.manifestSha256,
    configurationHandshakeVersion: 1,
    configSetSha256: 'a'.repeat(64),
    abilityPolicy,
    binding,
  };
  let dispatchCount = 0;
  const transport = new WorldspeakerTransport(config, DEPLOYMENT.hostVersion, DEPLOYMENT.companionVersion,
    async () => { dispatchCount++; }, async () => {}, () => {});
  let socket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(socket, secret);
    const internals = transport as unknown as { peer?: {
      retainedEvents: Map<string, string>;
      retainedInvocations: Map<string, string>;
      retainedRequests: Map<string, string>;
      requests: Map<string, DialogueRequest>;
    } };
    const authenticatedPeer = internals.peer;
    assert.ok(authenticatedPeer);

    const mismatch: DialogueRequest = { ...createRequest(randomUUID(), randomUUID()), abilityPolicyRevision: 22 };
    const closed = waitForCloseDetails(socket);
    socket.send(JSON.stringify(mismatch));
    assert.equal((await closed).code, 4002);
    assert.equal(dispatchCount, 0);
    assert.equal(authenticatedPeer.retainedEvents.size, 0);
    assert.equal(authenticatedPeer.retainedInvocations.size, 0);
    assert.equal(authenticatedPeer.retainedRequests.size, 0);
    assert.equal(authenticatedPeer.requests.size, 0);
  } finally {
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    await transport.close();
  }
});

test('canonical event identity covers every schema field and preserves exact Unicode text', () => {
  const event = createRequest(randomUUID(), randomUUID()).event;
  const canonicalEventIdentity = (WorldspeakerTransport as unknown as {
    canonicalEventIdentity(value: AttributedEvent): string;
  }).canonicalEventIdentity;
  const originalIdentity = canonicalEventIdentity(event);

  assert.equal(canonicalEventIdentity(reverseObjectKeysDeep(event) as AttributedEvent), originalIdentity);
  for (const field of DIALOGUE_EVENT_FIELDS) {
    assert.notEqual(canonicalEventIdentity(mutateEventField(event, field)), originalIdentity,
      `changing ${field} must change immutable event identity`);
  }

  const composedText = { ...event, text: 'Caf\u00e9' };
  const decomposedText = { ...event, text: 'Cafe\u0301' };
  assert.notEqual(canonicalEventIdentity(composedText), canonicalEventIdentity(decomposedText),
    'canonically equivalent Unicode spellings remain distinct event text');
});

test('canonical attention-event identity covers all S fields and ignores nested property order', () => {
  const event = createAttentionEvent(randomUUID());
  const canonicalEventIdentity = (WorldspeakerTransport as unknown as {
    canonicalEventIdentity(value: ContextEvent): string;
  }).canonicalEventIdentity;
  const originalIdentity = canonicalEventIdentity(event);

  assert.equal(canonicalEventIdentity(reverseObjectKeysDeep(event) as AttentionEvent), originalIdentity);
  for (const field of ATTENTION_EVENT_FIELDS) {
    assert.notEqual(canonicalEventIdentity(mutateAttentionEventField(event, field)), originalIdentity,
      `changing ${field} must change immutable observation identity`);
  }
  assert.notEqual(canonicalEventIdentity({ ...event, subject: { ...event.subject, playerName: 'OtherPlayer' } }), originalIdentity);
  assert.notEqual(canonicalEventIdentity({ ...event, details: { copiedAlive: true } }), originalIdentity);
});

test('authenticated transport accepts reordered nested duplicates without closing or redispatching', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const playerId = randomUUID();
  let dispatchCount = 0;
  let acknowledgementCount = 0;
  const transport = createTransport(port, secret, undefined, undefined,
    async (_request, sendFinal) => {
      dispatchCount++;
      assert.ok(sendFinal('The first event was handled.'));
    },
    async () => { acknowledgementCount++; });
  let socket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(socket, secret);

    const firstRequest: DialogueRequest = {
      ...createRequest(randomUUID(), playerId, { messageId: randomUUID(), text: 'Acknowledged.' }),
      contextBatch: [createContextEvent(playerId)],
    };
    const firstResponsePromise = nextMessage(socket);
    socket.send(JSON.stringify(firstRequest));
    assert.equal((await firstResponsePromise).type, 'dialogue.message');
    assert.equal(dispatchCount, 1);
    assert.equal(acknowledgementCount, 1);

    const freshIds = createRequest(randomUUID(), playerId);
    const duplicate: DialogueRequest = {
      ...firstRequest,
      requestId: freshIds.requestId,
      invocationId: freshIds.invocationId,
    };
    const duplicateResponsePromise = nextMessage(socket);
    socket.send(JSON.stringify(reverseObjectKeysDeep(duplicate)));
    const duplicateResponse = await duplicateResponsePromise;
    assert.equal(duplicateResponse.type, 'dialogue.failure');
    assert.equal(duplicateResponse.requestId, duplicate.requestId);
    assert.equal(socket.readyState, WebSocket.OPEN, 'an identical duplicate must not close its authenticated peer');
    assert.equal(dispatchCount, 1, 'an identical event under new request/invocation IDs must not dispatch again');
    assert.equal(acknowledgementCount, 1, 'a duplicate must not repeat acknowledgement side effects');

    const conflictingEvent = { ...firstRequest.contextBatch[0]!, dimension: 'minecraft:the_nether' };
    const conflict: DialogueRequest = {
      ...createRequest(randomUUID(), playerId),
      contextBatch: [conflictingEvent],
    };
    socket.send(JSON.stringify(conflict));
    assert.equal(await waitForClose(socket), true, 'conflicting reuse of an observation ID refuses the peer');
    assert.equal(dispatchCount, 1, 'conflicting observation identity never creates a second turn');
  } finally {
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    await transport.close();
  }
});

test('isolated authenticated child bounds unexpected refusals and later serves a valid peer', { timeout: 20_000 }, async () => {
  const port = await availablePort();
  const secret = Buffer.alloc(32, 0x5a).toString('base64url');
  const triggerEventId = randomUUID();
  const privateSentinel = 'CANONICALIZER_PRIVATE_SENTINEL';
  const transportUrl = new URL('../transport.js', import.meta.url).href;
  const deploymentUrl = new URL('../generated/deployment.js', import.meta.url).href;
  const childScript = `
    const { WorldspeakerTransport } = await import(${JSON.stringify(transportUrl)});
    const { DEPLOYMENT } = await import(${JSON.stringify(deploymentUrl)});
    const secret = ${JSON.stringify(secret)};
    const binding = ${JSON.stringify(binding)};
    const triggerEventId = ${JSON.stringify(triggerEventId)};
    const transport = new WorldspeakerTransport({
      enabled: true, bindHost: '127.0.0.1', port: Number(process.argv[1]),
      serverEndpoint: 'ws://127.0.0.1:' + Number(process.argv[1]), bearerSecret: secret,
      deploymentId: DEPLOYMENT.deploymentId, manifestSha256: DEPLOYMENT.manifestSha256,
      configurationHandshakeVersion: 1, configSetSha256: 'a'.repeat(64), binding,
    }, DEPLOYMENT.hostVersion, DEPLOYMENT.companionVersion, async (_request, sendFinal) => {
      process.stdout.write('HANDLED\\n');
      sendFinal('The isolated child stayed alive.');
    }, async () => {}, () => {});
    const classType = WorldspeakerTransport;
    const originalCanonicalizer = classType.canonicalEventIdentity;
    classType.canonicalEventIdentity = function(event) {
      if (event.eventId === triggerEventId) throw new Error(${JSON.stringify(privateSentinel)});
      return originalCanonicalizer.call(this, event);
    };
    await transport.start();
    process.stdout.write('READY\\n');
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', async (chunk) => {
      if (!chunk.includes('STOP')) return;
      await transport.close();
      process.stdout.write('STOPPED\\n');
      process.stdin.pause();
    });
  `;
  const child = spawn(process.execPath, ['--input-type=module', '-e', childScript, String(port)], {
    cwd: process.cwd(),
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const childLines: string[] = [];
  let childStderr = '';
  const childOutput = createInterface({ input: child.stdout });
  const lineWaiters = new Map<string, Array<() => void>>();
  childOutput.on('line', (line) => {
    childLines.push(line);
    const waiters = lineWaiters.get(line);
    waiters?.shift()?.();
  });
  child.stderr.on('data', (chunk: Buffer) => {
    if (childStderr.length < 4096) childStderr += chunk.toString('utf8').slice(0, 4096 - childStderr.length);
  });
  const waitForLine = (expected: string): Promise<void> => {
    if (childLines.includes(expected)) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        removeWaiter();
        reject(new Error(`isolated transport child did not emit ${expected}`));
      }, 3000);
      const onExit = (code: number | null, signal: NodeJS.Signals | null) => {
        clearTimeout(timer);
        removeWaiter();
        reject(new Error(`isolated transport child exited before ${expected} (${code ?? signal})`));
      };
      const onLine = () => {
        clearTimeout(timer);
        child.off('exit', onExit);
        resolve();
      };
      const removeWaiter = () => {
        const waiters = lineWaiters.get(expected);
        if (waiters) {
          const index = waiters.indexOf(onLine);
          if (index >= 0) waiters.splice(index, 1);
          if (waiters.length === 0) lineWaiters.delete(expected);
        }
        child.off('exit', onExit);
      };
      const waiters = lineWaiters.get(expected) ?? [];
      waiters.push(onLine);
      lineWaiters.set(expected, waiters);
      child.once('exit', onExit);
    });
  };
  const waitForExit = (timeoutMs = 3000): Promise<number | null> => {
    if (child.exitCode !== null) return Promise.resolve(child.exitCode);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        child.off('exit', onExit);
        reject(new Error('isolated transport child did not exit after stop'));
      }, timeoutMs);
      const onExit = (code: number | null) => {
        clearTimeout(timer);
        resolve(code);
      };
      child.once('exit', onExit);
    });
  };
  let firstSocket: WebSocket | undefined;
  const exceptionSockets: WebSocket[] = [];
  let thirdSocket: WebSocket | undefined;

  try {
    await waitForLine('READY');

    firstSocket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(firstSocket, secret);
    const malformed = createRequest(randomUUID(), randomUUID()) as unknown as {
      event: Record<string, unknown>;
    };
    malformed.event.eventId = [randomUUID()];
    const malformedClose = waitForCloseDetails(firstSocket);
    firstSocket.send(JSON.stringify(malformed));
    assert.deepEqual(await malformedClose, { code: 4002, reason: 'dialogue request rejected' });
    assert.equal(child.exitCode, null, 'the child must survive a coerced non-string authority ID');

    for (let attempt = 0; attempt < 10; attempt++) {
      const exceptionSocket = new WebSocket(`ws://127.0.0.1:${port}`);
      exceptionSockets.push(exceptionSocket);
      await authenticateSocket(exceptionSocket, secret);
      const canonicalizerFailure = createRequest(randomUUID(), randomUUID()) as unknown as {
        event: Record<string, unknown>;
      };
      canonicalizerFailure.event.eventId = triggerEventId;
      const exceptionClose = waitForCloseDetails(exceptionSocket);
      exceptionSocket.send(JSON.stringify(canonicalizerFailure));
      assert.deepEqual(await exceptionClose, { code: 4002, reason: 'frame processing rejected' });
      assert.equal(child.exitCode, null, 'unexpected canonicalizer errors must be contained at receive');
    }

    thirdSocket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(thirdSocket, secret);
    const validRequest = createRequest(randomUUID(), randomUUID());
    const answerPromise = nextMessage(thirdSocket);
    thirdSocket.send(JSON.stringify(validRequest));
    const answer = await answerPromise;
    assert.equal(answer.type, 'dialogue.message');
    assert.equal(answer.text, 'The isolated child stayed alive.');
    await waitForLine('HANDLED');
    assert.equal(child.exitCode, null, 'a later valid authenticated peer must still be served');

    assert.equal(childStderr.includes(privateSentinel), false, 'private exception messages must not be logged');
    assert.equal(childStderr.includes(secret), false, 'the handshake secret must not be logged');
    assert.equal(childStderr.includes(triggerEventId), false, 'frame identity must not be logged');
    const diagnostics = childStderr.split('\n').filter((line) => line.includes('[worldspeaker] inbound'));
    assert.equal(diagnostics.length, 8, 'diagnostics are capped across the transport lifetime');
    const diagnostic = diagnostics[0];
    assert.ok(diagnostic && diagnostic.length < 160, 'the diagnostic is fixed-format and bounded');
  } finally {
    for (const socket of [firstSocket, ...exceptionSockets, thirdSocket]) {
      if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    }
    if (child.exitCode === null && child.signalCode === null) {
      child.stdin.end('STOP\n');
      try {
        await waitForLine('STOPPED');
        await waitForExit();
      } catch {
        child.kill();
      }
    }
    childOutput.close();
  }
});

test('identity retention admits 256 entries and closes before a 257th can evict replay evidence', { timeout: 3000 }, async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const playerId = randomUUID();
  const statuses: Array<'starting' | 'ready' | 'unavailable'> = [];
  let dispatchCount = 0;
  const transport = createTransport(port, secret, undefined, (status) => statuses.push(status), async () => {
    dispatchCount++;
  });
  let socket: WebSocket | undefined;
  let replacementSocket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(socket, secret);

    let oldestRequest: DialogueRequest | undefined;
    for (let index = 0; index < 256; index++) {
      const request = createRequest(randomUUID(), playerId);
      if (index === 0) oldestRequest = request;
      const responsePromise = nextMessage(socket);
      socket.send(JSON.stringify(request));
      assert.equal((await responsePromise).type, 'dialogue.failure');
    }
    assert.equal(dispatchCount, 256);

    assert.ok(oldestRequest);
    const overflowRequest = createRequest(randomUUID(), playerId);
    socket.send(JSON.stringify(overflowRequest));
    assert.equal(await waitForClose(socket), true);
    await waitForArrayLength(statuses, 4);
    assert.equal(dispatchCount, 256, 'overflow is refused before a 257th identity reaches dispatch');

    replacementSocket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(replacementSocket, secret);
    const afterPeerReleasePromise = nextMessage(replacementSocket);
    replacementSocket.send(JSON.stringify(oldestRequest));
    assert.equal((await afterPeerReleasePromise).type, 'dialogue.failure');
    assert.equal(dispatchCount, 257, 'a new authenticated peer starts with fresh, peer-scoped dedup state');
  } finally {
    socket?.terminate();
    replacementSocket?.terminate();
    await transport.close();
  }
});

test('retains an immutable accepted ACK snapshot across cancellation and orders it before the next shared turn', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const playerId = randomUUID();
  const firstRequestId = randomUUID();
  const secondRequestId = randomUUID();
  const acknowledgementId = randomUUID();
  const acknowledgements: Array<{ request: DialogueRequest; messageId: string }> = [];
  const firstStarted = deferred<{ request: DialogueRequest; sendFinal: (text: string) => string | null }>();
  const finishFirst = deferred<void>();
  const acknowledgementStarted = deferred<void>();
  const finishAcknowledgement = deferred<void>();
  const secondStarted = deferred<void>();
  const budget = new HostWorkBudget(4, 2);
  let acknowledgementFinished = false;
  let secondDispatched = false;
  const handleRequest: DialogueRequestHandler = async (request, sendFinal) => {
    if (request.requestId === firstRequestId) {
      firstStarted.resolve({ request, sendFinal });
      await finishFirst.promise;
      assert.equal(sendFinal('A canceled response.'), null, 'cancellation must revoke the old request output');
      return;
    }
    assert.equal(request.requestId, secondRequestId);
    assert.equal(acknowledgementFinished, true, 'the next substantive turn waits for the received ACK context update');
    secondDispatched = true;
    secondStarted.resolve();
    assert.ok(sendFinal('The next turn is ready.'));
  };
  const handleAcknowledgement: DialogueAcknowledgementHandler = async (request, acknowledgement) => {
    acknowledgements.push({ request, messageId: acknowledgement.messageId });
    acknowledgementStarted.resolve();
    await finishAcknowledgement.promise;
    acknowledgementFinished = true;
  };
  const transport = createTransport(port, secret, undefined, undefined, handleRequest, handleAcknowledgement, budget);
  let socket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(socket, secret);

    const firstWireRequest = createRequest(firstRequestId, playerId);
    socket.send(JSON.stringify(firstWireRequest));
    const first = await firstStarted.promise;
    socket.send(JSON.stringify(createAcknowledgement(firstWireRequest, acknowledgementId, playerId)));
    const cancelBarrier = nextMessage(socket);
    socket.send(JSON.stringify(createCancellation(firstRequestId, firstWireRequest.invocationId, playerId)));
    socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 1 }));
    assert.deepEqual(await cancelBarrier, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 1 });

    const acceptedConversationId = first.request.conversation.conversationId;
    assert.ok(Object.isFrozen(first.request));
    assert.ok(Object.isFrozen(first.request.event));
    assert.ok(Object.isFrozen(first.request.conversation));

    const secondDispatch = secondStarted.promise;
    const secondResponse = nextMessage(socket);
    const secondWireRequest = createRequest(secondRequestId, playerId);
    socket.send(JSON.stringify(secondWireRequest));
    assert.equal(first.sendFinal('A canceled response.'), null);
    finishFirst.resolve();
    await acknowledgementStarted.promise;
    assert.equal(secondDispatched, false, 'a queued substantive turn cannot pass an in-flight ACK context update');
    assert.equal(acknowledgements.length, 1);
    assert.equal(acknowledgements[0]?.messageId, acknowledgementId);
    assert.equal(acknowledgements[0]?.request.event.playerName, 'TestPlayer', 'ACK authority is snapshotted at admission');
    assert.equal(acknowledgements[0]?.request.conversation.conversationId, acceptedConversationId,
      'the ACK context keeps the exact immutable conversation snapshot admitted for the request');
    assert.ok(Object.isFrozen(acknowledgements[0]?.request));
    assert.ok(Object.isFrozen(acknowledgements[0]?.request.event));
    assert.ok(Object.isFrozen(acknowledgements[0]?.request.replyAudience));

    finishAcknowledgement.resolve();
    await secondDispatch;
    const answer = await secondResponse;
    assert.equal(answer.type, 'dialogue.message');
    assert.equal(answer.requestId, secondRequestId);
    assert.equal(acknowledgements.length, 1, 'accepted ACK context is committed exactly once');
    await waitForBudgetState(budget, { hostCalls: 0, acknowledgementCalls: 0 });
  } finally {
    finishFirst.resolve();
    finishAcknowledgement.resolve();
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    await transport.close();
  }
});

test('an ACK received after cancellation is not admitted', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const playerId = randomUUID();
  const canceledRequestId = randomUUID();
  const nextRequestId = randomUUID();
  const firstStarted = deferred<{ sendFinal: (text: string) => string | null }>();
  const finishFirst = deferred<void>();
  const nextStarted = deferred<void>();
  let acknowledgementCount = 0;
  const transport = createTransport(port, secret, undefined, undefined, async (request, sendFinal) => {
    if (request.requestId === canceledRequestId) {
      firstStarted.resolve({ sendFinal });
      await finishFirst.promise;
      assert.equal(sendFinal('Canceled output.'), null);
      return;
    }
    assert.equal(request.requestId, nextRequestId);
    nextStarted.resolve();
    assert.ok(sendFinal('The next turn proceeds.'));
  }, async () => { acknowledgementCount++; });
  let socket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(socket, secret);

    const request = createRequest(canceledRequestId, playerId);
    socket.send(JSON.stringify(request));
    const first = await firstStarted.promise;
    const cancelBarrier = nextMessage(socket);
    socket.send(JSON.stringify(createCancellation(canceledRequestId, request.invocationId, playerId)));
    socket.send(JSON.stringify(createAcknowledgement(request, randomUUID(), playerId)));
    socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 1 }));
    assert.deepEqual(await cancelBarrier, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 1 });

    const nextResponse = nextMessage(socket);
    socket.send(JSON.stringify(createRequest(nextRequestId, playerId)));
    finishFirst.resolve();
    await nextStarted.promise;
    assert.equal((await nextResponse).type, 'dialogue.message');
    assert.equal(acknowledgementCount, 0, 'cancellation retires authority for ACKs that arrive afterward');
  } finally {
    finishFirst.resolve();
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    await transport.close();
  }
});

test('duplicate cancellation and cancelled request replay are bounded no-ops without failure output', { timeout: 3000 }, async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const playerId = randomUUID();
  const requestId = randomUUID();
  const started = deferred<void>();
  const finish = deferred<void>();
  const budget = new HostWorkBudget(2, 2);
  let dispatchCount = 0;
  const transport = createTransport(port, secret, undefined, undefined, async (_request, sendFinal) => {
    dispatchCount++;
    started.resolve();
    await finish.promise;
    assert.equal(sendFinal('The canceled turn cannot answer.'), null);
  }, undefined, budget);
  let socket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(socket, secret);
    const request = createRequest(requestId, playerId);
    socket.send(JSON.stringify(request));
    await started.promise;

    const heartbeat = nextMessage(socket);
    socket.send(JSON.stringify(createCancellation(requestId, request.invocationId, playerId)));
    socket.send(JSON.stringify(createCancellation(requestId, request.invocationId, playerId)));
    socket.send(JSON.stringify(request));
    socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 1 }));
    assert.deepEqual(await heartbeat, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 1 });
    assert.equal(dispatchCount, 1, 'cancellation does not dispatch or replay the retained identity');

    finish.resolve();
    await waitForBudgetState(budget, { hostCalls: 0, acknowledgementCalls: 0 });
  } finally {
    finish.resolve();
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    await transport.close();
  }
});

test('cancellation attribution mismatches reject the peer without reviving the retained request', { timeout: 3000 }, async () => {
  for (const mismatchedField of ['invocationId', 'playerId'] as const) {
    const port = await availablePort();
    const secret = randomBytes(32).toString('base64url');
    const playerId = randomUUID();
    const started = deferred<void>();
    const finish = deferred<void>();
    const budget = new HostWorkBudget();
    let dispatchCount = 0;
    let delayedFinal: string | null | undefined;
    const transport = createTransport(port, secret, undefined, undefined, async (_request, sendFinal) => {
      dispatchCount++;
      started.resolve();
      await finish.promise;
      delayedFinal = sendFinal('The mismatched cancellation cannot authorize output.');
    }, undefined, budget);
    let socket: WebSocket | undefined;
    try {
      await transport.start();
      socket = new WebSocket(`ws://127.0.0.1:${port}`);
      await authenticateSocket(socket, secret);
      const request = createRequest(randomUUID(), playerId);
      socket.send(JSON.stringify(request));
      await started.promise;

      const cancellation = createCancellation(request.requestId, request.invocationId, playerId);
      let mismatchedId = randomUUID();
      while (mismatchedId === request.requestId || mismatchedId === request.invocationId || mismatchedId === playerId) {
        mismatchedId = randomUUID();
      }
      if (mismatchedField === 'invocationId') cancellation.invocationId = mismatchedId;
      else cancellation.playerId = mismatchedId;
      const rejected = waitForCloseDetails(socket);
      socket.send(JSON.stringify(cancellation));
      assert.deepEqual(await rejected, { code: 4002, reason: 'cancellation attribution mismatch' });
      assert.equal(dispatchCount, 1, 'a known identity mismatch never dispatches replacement work');
      assert.equal(budget.snapshot().hostCalls, 1, 'peer rejection does not release unsettled host work');

      finish.resolve();
      await waitForBudget(budget, 0);
      assert.equal(delayedFinal, null, 'the retained request closure stays non-live after peer rejection');
    } finally {
      finish.resolve();
      socket?.terminate();
      await transport.close();
    }
  }
});

test('unknown scoped cancellation is a bounded no-op and does not retain an identity', { timeout: 3000 }, async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const transport = createTransport(port, secret);
  let socket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(socket, secret);
    const unknown = createRequest(randomUUID(), randomUUID());
    const frame = createCancellation(unknown.requestId, unknown.invocationId, unknown.event.playerId);
    const heartbeat = nextMessage(socket);
    socket.send(JSON.stringify(frame));
    socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 1 }));
    assert.deepEqual(await heartbeat, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 1 });
    const state = transport as unknown as { peer: { cancelledRequests: Set<string>; retainedRequests: Map<string, string> } };
    assert.equal(state.peer.cancelledRequests.size, 0);
    assert.equal(state.peer.retainedRequests.size, 0);
  } finally {
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    await transport.close();
  }
});

test('expired host output is rejected by the direct monotonic deadline gate before expiry scanning', { timeout: 3000 }, async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const clock = createManualMonotonicClock();
  const started = deferred<{ sendFinal: (text: string) => string | null }>();
  const finish = deferred<void>();
  const budget = new HostWorkBudget(1, 1);
  const transport = createTransport(port, secret, clock.now, undefined, async (_request, sendFinal) => {
    started.resolve({ sendFinal });
    await finish.promise;
  }, undefined, budget);
  let socket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(socket, secret);
    socket.send(JSON.stringify(createRequest(randomUUID(), randomUUID())));
    const work = await started.promise;
    clock.advance(30_000);
    assert.equal(work.sendFinal('Output at the expiry boundary.'), null);
    assert.equal(budget.snapshot().hostCalls, 1, 'expired local output does not release unsettled host work');
    finish.resolve();
    await waitForBudgetState(budget, { hostCalls: 0, acknowledgementCalls: 0 });
  } finally {
    finish.resolve();
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    await transport.close();
  }
});

test('a locally dispatched ACK remains admissible after request terminal completion', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const playerId = randomUUID();
  const completedRequestId = randomUUID();
  const nextRequestId = randomUUID();
  const acknowledgementId = randomUUID();
  const completed = deferred<{ sendFinal: (text: string) => string | null }>();
  const acknowledgementStarted = deferred<void>();
  const finishAcknowledgement = deferred<void>();
  const nextStarted = deferred<void>();
  let completedOutput: ((text: string) => string | null) | undefined;
  let acknowledgementCount = 0;
  let nextDispatched = false;
  const transport = createTransport(port, secret, undefined, undefined, async (request, sendFinal) => {
    if (request.requestId === completedRequestId) {
      completedOutput = sendFinal;
      assert.ok(sendFinal('The original answer is complete.'));
      completed.resolve({ sendFinal });
      return;
    }
    assert.equal(request.requestId, nextRequestId);
    nextDispatched = true;
    nextStarted.resolve();
    assert.ok(sendFinal('The next turn is ready.'));
  }, async (request, acknowledgement) => {
    assert.equal(request.requestId, completedRequestId);
    assert.equal(acknowledgement.messageId, acknowledgementId);
    acknowledgementCount++;
    acknowledgementStarted.resolve();
    await finishAcknowledgement.promise;
  });
  let socket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(socket, secret);
    const originalRequest = createRequest(completedRequestId, playerId);
    const originalResponse = nextMessage(socket);
    socket.send(JSON.stringify(originalRequest));
    assert.equal((await originalResponse).type, 'dialogue.message');
    await completed.promise;
    assert.equal(completedOutput?.('A terminal request cannot publish again.'), null);

    socket.send(JSON.stringify(createAcknowledgement(originalRequest, acknowledgementId, playerId)));
    const acknowledgementBarrier = nextMessage(socket);
    socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 1 }));
    assert.deepEqual(await acknowledgementBarrier, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 1 });
    await acknowledgementStarted.promise;
    assert.equal(acknowledgementCount, 1, 'terminal completion does not retroactively revoke a received ACK');

    socket.send(JSON.stringify(createRequest(nextRequestId, playerId)));
    const nextRequestBarrier = nextMessage(socket);
    socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 2 }));
    assert.deepEqual(await nextRequestBarrier, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 2 });
    assert.equal(nextDispatched, false, 'the next request waits for terminal request ACK context');

    const nextResponse = nextMessage(socket);
    finishAcknowledgement.resolve();
    await nextStarted.promise;
    assert.equal((await nextResponse).type, 'dialogue.message');
    assert.equal(acknowledgementCount, 1);
  } finally {
    finishAcknowledgement.resolve();
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    await transport.close();
  }
});

test('an embedded dispatched ACK survives cancellation while its request waits in the shared queue', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const playerId = randomUUID();
  const firstRequestId = randomUUID();
  const canceledRequestId = randomUUID();
  const nextRequestId = randomUUID();
  const embeddedAcknowledgementId = randomUUID();
  const firstStarted = deferred<{ sendFinal: (text: string) => string | null }>();
  const finishFirst = deferred<void>();
  const acknowledgementStarted = deferred<void>();
  const finishAcknowledgement = deferred<void>();
  const nextStarted = deferred<void>();
  const dispatched: string[] = [];
  const acknowledgements: string[] = [];
  const transport = createTransport(port, secret, undefined, undefined, async (request, sendFinal) => {
    dispatched.push(request.requestId);
    if (request.requestId === firstRequestId) {
      firstStarted.resolve({ sendFinal });
      await finishFirst.promise;
      assert.equal(sendFinal('Canceled first output.'), null);
      return;
    }
    assert.equal(request.requestId, nextRequestId, 'the canceled embedded-ACK request must never dispatch');
    assert.deepEqual(acknowledgements, [embeddedAcknowledgementId]);
    nextStarted.resolve();
    assert.ok(sendFinal('The following turn is ready.'));
  }, async (request, acknowledgement) => {
    assert.equal(request.requestId, canceledRequestId);
    acknowledgements.push(acknowledgement.messageId);
    acknowledgementStarted.resolve();
    await finishAcknowledgement.promise;
  });
  let socket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(socket, secret);

    const firstRequest = createRequest(firstRequestId, playerId);
    socket.send(JSON.stringify(firstRequest));
    const first = await firstStarted.promise;
    const canceledRequest = createRequest(canceledRequestId, playerId, {
      messageId: embeddedAcknowledgementId,
      text: 'The server already sent this acknowledgement.',
    });
    socket.send(JSON.stringify(canceledRequest));
    const cancelBarrier = nextMessage(socket);
    socket.send(JSON.stringify(createCancellation(firstRequestId, firstRequest.invocationId, playerId)));
    socket.send(JSON.stringify(createCancellation(canceledRequestId, canceledRequest.invocationId, playerId)));
    socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 1 }));
    assert.deepEqual(await cancelBarrier, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 1 });

    const nextResponse = nextMessage(socket);
    socket.send(JSON.stringify(createRequest(nextRequestId, playerId)));
    finishFirst.resolve();
    await acknowledgementStarted.promise;
    assert.deepEqual(dispatched, [firstRequestId], 'the canceled request never starts substantive work');
    assert.deepEqual(acknowledgements, [embeddedAcknowledgementId]);

    finishAcknowledgement.resolve();
    await nextStarted.promise;
    assert.equal((await nextResponse).type, 'dialogue.message');
    assert.deepEqual(dispatched, [firstRequestId, nextRequestId]);
  } finally {
    finishFirst.resolve();
    finishAcknowledgement.resolve();
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    await transport.close();
  }
});

test('an accepted ACK survives peer retirement and orders work after account restart', async () => {
  const firstPort = await availablePort();
  const firstSecret = randomBytes(32).toString('base64url');
  const secondPort = await availablePort();
  const secondSecret = randomBytes(32).toString('base64url');
  const playerId = randomUUID();
  const firstRequestId = randomUUID();
  const secondRequestId = randomUUID();
  const acknowledgementId = randomUUID();
  const embeddedAcknowledgementId = randomUUID();
  const firstStarted = deferred<{ sendFinal: (text: string) => string | null }>();
  const finishFirst = deferred<void>();
  const acknowledgementStarted = deferred<void>();
  const finishAcknowledgement = deferred<void>();
  const secondStarted = deferred<void>();
  const budget = new HostWorkBudget(3, 1);
  let secondDispatched = false;
  let secondAcknowledgementCount = 0;
  let receivedAcknowledgement: { request: DialogueRequest; messageId: string } | undefined;
  const firstTransport = createTransport(firstPort, firstSecret, undefined, undefined, async (_request, sendFinal) => {
    firstStarted.resolve({ sendFinal });
    await finishFirst.promise;
  }, async (request, acknowledgement) => {
    receivedAcknowledgement = { request, messageId: acknowledgement.messageId };
    acknowledgementStarted.resolve();
    await finishAcknowledgement.promise;
  }, budget);
  const secondTransport = createTransport(secondPort, secondSecret, undefined, undefined, async (request, sendFinal) => {
    assert.equal(request.requestId, secondRequestId);
    assert.equal(secondAcknowledgementCount, 1, 'replacement request ACK context precedes its substantive handler');
    secondDispatched = true;
    secondStarted.resolve();
    assert.ok(sendFinal('The restarted turn is ready.'));
  }, async (_request, acknowledgement) => {
    assert.equal(acknowledgement.messageId, embeddedAcknowledgementId);
    secondAcknowledgementCount++;
  }, budget);
  let firstSocket: WebSocket | undefined;
  let secondSocket: WebSocket | undefined;
  try {
    await firstTransport.start();
    firstSocket = new WebSocket(`ws://127.0.0.1:${firstPort}`);
    await authenticateSocket(firstSocket, firstSecret);
    const firstRequest = createRequest(firstRequestId, playerId);
    firstSocket.send(JSON.stringify(firstRequest));
    const first = await firstStarted.promise;
    firstSocket.send(JSON.stringify(createAcknowledgement(firstRequest, acknowledgementId, playerId)));
    const acceptedBarrier = nextMessage(firstSocket);
    firstSocket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 1 }));
    assert.deepEqual(await acceptedBarrier, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 1 });

    const closePromise = waitForClose(firstSocket);
    firstSocket.terminate();
    assert.equal(await closePromise, true);
    await firstTransport.close();
    assert.deepEqual(budget.snapshot(), { hostCalls: 1, acknowledgementCalls: 1 },
      'peer retirement and account stop do not release unsettled host/ACK permits');
    assert.equal(first.sendFinal('Output after peer retirement.'), null);

    await secondTransport.start();
    secondSocket = new WebSocket(`ws://127.0.0.1:${secondPort}`);
    await authenticateSocket(secondSocket, secondSecret);
    const secondDispatch = secondStarted.promise;
    const secondRequest = createRequest(secondRequestId, playerId, {
      messageId: embeddedAcknowledgementId,
      text: 'The replacement turn has a locally dispatched acknowledgement.',
    });
    secondSocket.send(JSON.stringify(secondRequest));
    const requestBarrier = nextMessage(secondSocket);
    secondSocket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 1 }));
    assert.deepEqual(await requestBarrier, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 1 });
    const secondResponse = nextMessage(secondSocket);
    finishFirst.resolve();
    await acknowledgementStarted.promise;
    assert.equal(secondDispatched, false, 'the retired peer\'s accepted ACK stays ahead of a replacement peer turn');
    assert.equal(receivedAcknowledgement?.request.requestId, firstRequestId);
    assert.equal(receivedAcknowledgement?.request.event.playerId, playerId);
    assert.equal(receivedAcknowledgement?.messageId, acknowledgementId);
    assert.deepEqual(budget.snapshot(), { hostCalls: 1, acknowledgementCalls: 1 });

    finishAcknowledgement.resolve();
    await secondDispatch;
    assert.equal((await secondResponse).type, 'dialogue.message');
    assert.equal(secondAcknowledgementCount, 1);
    await waitForBudgetState(budget, { hostCalls: 0, acknowledgementCalls: 0 });
  } finally {
    finishFirst.resolve();
    finishAcknowledgement.resolve();
    firstSocket?.terminate();
    secondSocket?.terminate();
    await firstTransport.close();
    await secondTransport.close();
  }
});

test('ACK attribution, audience, generation, and shape failures are denied before context dispatch', async () => {
  type InvalidAckCase = Readonly<{ name: string; mutate: (frame: ReturnType<typeof createAcknowledgement>) => unknown }>;
  const cases: InvalidAckCase[] = [
    {
      name: 'wrong invocation generation',
      mutate: (frame) => ({ ...frame, invocationId: randomUUID() }),
    },
    {
      name: 'wrong principal',
      mutate: (frame) => {
        const playerId = randomUUID();
        return { ...frame, playerId, acknowledgement: { ...frame.acknowledgement, replyAudience: { kind: 'player', playerId } } };
      },
    },
    {
      name: 'wrong nested audience',
      mutate: (frame) => ({
        ...frame,
        acknowledgement: { ...frame.acknowledgement, replyAudience: { kind: 'player', playerId: randomUUID() } },
      }),
    },
    {
      name: 'malformed extra field',
      mutate: (frame) => ({ ...frame, untrusted: true }),
    },
  ];
  for (const invalidCase of cases) {
    const port = await availablePort();
    const secret = randomBytes(32).toString('base64url');
    const playerId = randomUUID();
    let acknowledgementCount = 0;
    const transport = createTransport(port, secret, undefined, undefined, async () => {}, async () => { acknowledgementCount++; });
    let socket: WebSocket | undefined;
    try {
      await transport.start();
      socket = new WebSocket(`ws://127.0.0.1:${port}`);
      await authenticateSocket(socket, secret);
      const request = createRequest(randomUUID(), playerId);
      const responsePromise = nextMessage(socket);
      socket.send(JSON.stringify(request));
      assert.equal((await responsePromise).type, 'dialogue.failure', `${invalidCase.name}: request target is terminal but retained`);

      const rejectionPromise = waitForClose(socket);
      socket.send(JSON.stringify(invalidCase.mutate(createAcknowledgement(request, randomUUID(), playerId))));
      assert.equal(await rejectionPromise, true, `${invalidCase.name}: authenticated peer must reject invalid ACK attribution`);
      assert.equal(acknowledgementCount, 0, `${invalidCase.name}: invalid ACK cannot update context`);
    } finally {
      socket?.terminate();
      await transport.close();
    }
  }
});

test('ACK context failure closes the Java peer and remains sticky across transport restart', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const budget = new HostWorkBudget(2, 1);
  const playerId = randomUUID();
  const firstRequestId = randomUUID();
  const failureStatus = deferred<void>();
  const statuses: string[] = [];
  let dispatchCount = 0;
  const transport = createTransport(port, secret, undefined, (status, reason) => {
    statuses.push(`${status}:${reason}`);
    if (reason === 'WS-COMPAT-ACK-CONTEXT-UPDATE-FAILED') failureStatus.resolve();
  }, async (_request, sendFinal) => {
    dispatchCount++;
    assert.ok(sendFinal('The first turn is ready.'));
  }, async () => {
    throw new Error('SDK next-turn context queue failed');
  }, budget);
  let socket: WebSocket | undefined;
  let restartedSocket: WebSocket | undefined;
  let restartedTransport: WorldspeakerTransport | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(socket, secret);
    const firstRequest = createRequest(firstRequestId, playerId);
    const firstResponse = nextMessage(socket);
    socket.send(JSON.stringify(firstRequest));
    assert.equal((await firstResponse).type, 'dialogue.message');

    const peerClose = waitForCloseDetails(socket);
    socket.send(JSON.stringify(createAcknowledgement(firstRequest, randomUUID(), playerId)));
    assert.deepEqual(await peerClose, { code: 1011, reason: 'service unavailable' });
    await failureStatus.promise;
    assert.ok(statuses.includes('unavailable:WS-COMPAT-ACK-CONTEXT-UPDATE-FAILED'));
    assert.equal(dispatchCount, 1);
    assert.deepEqual(budget.snapshot(), { hostCalls: 0, acknowledgementCalls: 0 });

    await transport.close();
    socket = undefined;
    const restartedPort = await availablePort();
    restartedTransport = createTransport(restartedPort, secret, undefined,
      (status, reason) => statuses.push(`${status}:${reason}`), async () => { dispatchCount++; }, undefined, budget);
    await restartedTransport.start();
    restartedSocket = new WebSocket(`ws://127.0.0.1:${restartedPort}`);
    const challengePromise = nextMessage(restartedSocket);
    await waitForOpen(restartedSocket);
    const challenge = await challengePromise;
    const restartClose = waitForCloseDetails(restartedSocket);
    restartedSocket.send(JSON.stringify(createHello(secret, challenge.challenge as string)));
    assert.deepEqual(await restartClose, { code: 1011, reason: 'service unavailable' },
      'a new account transport cannot clear known context loss');
    assert.equal(statuses.filter((status) => status.startsWith('ready:')).length, 1,
      'only the original healthy peer reached READY');
    assert.equal(dispatchCount, 1, 'reconnect cannot replay or bypass the failed context');
  } finally {
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    restartedSocket?.terminate();
    await transport.close();
    await restartedTransport?.close();
  }
});

test('host-work saturation closes the authenticated peer without releasing its unsettled permit', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const budget = new HostWorkBudget(1, 1);
  const firstRequestId = randomUUID();
  const started = deferred<void>();
  const finish = deferred<void>();
  const statuses: string[] = [];
  const transport = createTransport(port, secret, undefined,
    (status, reason) => statuses.push(`${status}:${reason}`), async (request, sendFinal) => {
      if (request.requestId === firstRequestId) {
        started.resolve();
        await finish.promise;
        assert.equal(sendFinal('The retired request cannot publish.'), null);
      } else {
        assert.ok(sendFinal('The replacement request is ready.'));
      }
    }, undefined, budget);
  let socket: WebSocket | undefined;
  let replacementSocket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(socket, secret);
    const first = createRequest(firstRequestId, randomUUID());
    socket.send(JSON.stringify(first));
    await started.promise;
    assert.deepEqual(budget.snapshot(), { hostCalls: 1, acknowledgementCalls: 0 });

    const saturatedClose = waitForCloseDetails(socket);
    socket.send(JSON.stringify(createRequest(randomUUID(), randomUUID())));
    assert.deepEqual(await saturatedClose, { code: 1011, reason: 'service unavailable' });
    assert.ok(statuses.includes('unavailable:WS-COMPAT-HOST-WORK-LIMIT'));
    assert.deepEqual(budget.snapshot(), { hostCalls: 1, acknowledgementCalls: 0 },
      'peer retirement does not refund a still-running host call');

    finish.resolve();
    await waitForBudget(budget, 0);
    assert.deepEqual(budget.snapshot(), { hostCalls: 0, acknowledgementCalls: 0 });

    replacementSocket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(replacementSocket, secret);
    const answer = nextMessage(replacementSocket);
    replacementSocket.send(JSON.stringify(createRequest(randomUUID(), randomUUID())));
    assert.equal((await answer).text, 'The replacement request is ready.');
  } finally {
    finish.resolve();
    socket?.terminate();
    replacementSocket?.terminate();
    await transport.close();
  }
});

test('known host handler refusal closes the peer instead of only logging unavailability', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const unavailable = deferred<void>();
  const statuses: string[] = [];
  let dispatchCount = 0;
  const transport = createTransport(port, secret, undefined, (status, reason) => {
    statuses.push(`${status}:${reason}`);
    if (status === 'unavailable' && reason === 'WS-COMPAT-HOST-REQUEST-FAILED') unavailable.resolve();
  }, async () => {
    dispatchCount++;
    throw new Error('pinned SDK ingress denied dispatch');
  });
  let socket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(socket, secret);
    const peerClose = waitForCloseDetails(socket);
    socket.send(JSON.stringify(createRequest(randomUUID(), randomUUID())));
    assert.deepEqual(await peerClose, { code: 1011, reason: 'service unavailable' });
    await unavailable.promise;
    assert.equal(dispatchCount, 1);
    assert.ok(statuses.includes('ready:WS-COMPAT-READY'));
    assert.ok(statuses.includes('unavailable:WS-COMPAT-HOST-REQUEST-FAILED'));
  } finally {
    socket?.terminate();
    await transport.close();
  }
});

test('terminal ACK targets expire from peer memory on the monotonic deadline', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const playerId = randomUUID();
  const clock = createManualMonotonicClock();
  let acknowledgementCount = 0;
  const transport = createTransport(port, secret, clock.now, undefined, async () => {}, async () => { acknowledgementCount++; });
  let socket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticateSocket(socket, secret);
    const request = createRequest(randomUUID(), playerId);
    const terminalResponse = nextMessage(socket);
    socket.send(JSON.stringify(request));
    assert.equal((await terminalResponse).type, 'dialogue.failure');

    const transportState = transport as unknown as {
      peer: { acknowledgementTargets: Map<string, unknown>; acknowledgementIds: Map<string, string> };
    };
    assert.equal(transportState.peer.acknowledgementTargets.size, 1);
    for (let sequence = 1; sequence <= 4; sequence++) {
      clock.advance(9000);
      const heartbeat = nextMessage(socket);
      socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence }));
      assert.deepEqual(await heartbeat, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence });
    }
    await waitForMonotonicRead(clock, 3000);
    assert.equal(transportState.peer.acknowledgementTargets.size, 0);
    assert.equal(transportState.peer.acknowledgementIds.size, 0);

    const expiredAckBarrier = nextMessage(socket);
    socket.send(JSON.stringify(createAcknowledgement(request, randomUUID(), playerId)));
    socket.send(JSON.stringify({ type: 'heartbeat', protocol: DEPLOYMENT.protocol, sequence: 5 }));
    assert.deepEqual(await expiredAckBarrier, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: 5 });
    assert.equal(acknowledgementCount, 0, 'an expired context target cannot be revived by a late ACK');
  } finally {
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    await transport.close();
  }
});

test('unsettled host work remains charged across cancel and transport restart', async () => {
  const budget = new HostWorkBudget(1, 1);
  const firstPort = await availablePort();
  const firstSecret = randomBytes(32).toString('base64url');
  const firstRequestId = randomUUID();
  const playerId = randomUUID();
  let finishFirst!: () => void;
  let dispatchedFirst!: () => void;
  let settleFirst!: () => void;
  const firstDispatched = new Promise<void>((resolve) => { dispatchedFirst = resolve; });
  const firstSettled = new Promise<void>((resolve) => { settleFirst = resolve; });
  const firstTransport = createTransport(firstPort, firstSecret, undefined, undefined,
    async () => {
      dispatchedFirst();
      await new Promise<void>((resolve) => { finishFirst = resolve; });
      settleFirst();
    }, undefined, budget);
  let firstSocket: WebSocket | undefined;
  let secondSocket: WebSocket | undefined;
  let recoveredSocket: WebSocket | undefined;
  let restartedTransport: WorldspeakerTransport | undefined;
  let secondPort: number | undefined;
  let secondSecret: string | undefined;
  try {
    await firstTransport.start();
    firstSocket = new WebSocket(`ws://127.0.0.1:${firstPort}`);
    await authenticateSocket(firstSocket, firstSecret);
    const firstWireRequest = createRequest(firstRequestId, playerId);
    firstSocket.send(JSON.stringify(firstWireRequest));
    await firstDispatched;
    assert.deepEqual(budget.snapshot(), { hostCalls: 1, acknowledgementCalls: 0 });

    firstSocket.send(JSON.stringify(createCancellation(firstRequestId, firstWireRequest.invocationId, playerId)));
    await firstTransport.close();
    firstSocket = undefined;

    secondPort = await availablePort();
    secondSecret = randomBytes(32).toString('base64url');
    const replacementRequestId = randomUUID();
    let replacementDispatched = false;
    restartedTransport = createTransport(secondPort, secondSecret, undefined, undefined,
      async (_request, sendFinal) => {
        replacementDispatched = true;
        assert.ok(sendFinal('replacement answer'));
      }, undefined, budget);
    await restartedTransport.start();
    secondSocket = new WebSocket(`ws://127.0.0.1:${secondPort}`);
    const challengePromise = nextMessage(secondSocket);
    await waitForOpen(secondSocket);
    const challenge = await challengePromise;
    const refused = waitForCloseDetails(secondSocket);
    secondSocket.send(JSON.stringify(createHello(secondSecret, challenge.challenge as string)));
    assert.deepEqual(await refused, { code: 1011, reason: 'service unavailable' });
    assert.equal(replacementDispatched, false, 'a restarted transport cannot replace a never-settled host promise');

    finishFirst();
    await firstSettled;
    await waitForBudget(budget, 0);
    assert.deepEqual(budget.snapshot(), { hostCalls: 0, acknowledgementCalls: 0 });

    assert.ok(secondPort);
    assert.ok(secondSecret);
    recoveredSocket = new WebSocket(`ws://127.0.0.1:${secondPort}`);
    await authenticateSocket(recoveredSocket, secondSecret);
    const admitted = nextMessage(recoveredSocket);
    recoveredSocket.send(JSON.stringify(createRequest(replacementRequestId, playerId)));
    assert.equal((await admitted).type, 'dialogue.message');
    assert.equal(replacementDispatched, true);
    assert.deepEqual(budget.snapshot(), { hostCalls: 0, acknowledgementCalls: 0 });
  } finally {
    finishFirst?.();
    firstSocket?.terminate();
    secondSocket?.terminate();
    recoveredSocket?.terminate();
    await firstTransport.close();
    await restartedTransport?.close();
  }
});

test('captured signed hello is rejected after cache expiry and service restart', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  let now = 0;
  const firstTransport = createTransport(port, secret, () => now);
  let firstSocket: WebSocket | undefined;
  let replaySocket: WebSocket | undefined;
  try {
    await firstTransport.start();
    firstSocket = new WebSocket(`ws://127.0.0.1:${port}`);
    const challengePromise = nextMessage(firstSocket);
    await waitForOpen(firstSocket);
    const originalChallenge = await challengePromise;
    const capturedHello = createHello(secret, originalChallenge.challenge as string);
    const welcomePromise = nextMessage(firstSocket);
    firstSocket.send(JSON.stringify(capturedHello));
    assert.equal((await welcomePromise).type, 'welcome');
    firstSocket.terminate();
    await waitForClose(firstSocket);
    await firstTransport.close();

    // Simulate the old five-minute nonce-cache expiry before restarting the service.
    now = 300_001;
    const restartedTransport = createTransport(port, secret, () => now);
    try {
      await restartedTransport.start();
      replaySocket = new WebSocket(`ws://127.0.0.1:${port}`);
      const restartedChallengePromise = nextMessage(replaySocket);
      await waitForOpen(replaySocket);
      const restartedChallenge = await restartedChallengePromise;
      assert.notEqual(restartedChallenge.challenge, originalChallenge.challenge);
      const replayClose = waitForClose(replaySocket);
      replaySocket.send(JSON.stringify(capturedHello));
      assert.equal(await replayClose, true, 'hello signed for a previous server challenge must be rejected');
    } finally {
      replaySocket?.terminate();
      await restartedTransport.close();
    }
  } finally {
    firstSocket?.terminate();
    replaySocket?.terminate();
    await firstTransport.close();
  }
});

test('handshake deadline expires without accepting a valid but late challenge response', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const clock = createManualMonotonicClock(10);
  const transport = createTransport(port, secret, clock.now);
  let socket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    const challengePromise = nextMessage(socket);
    await waitForOpen(socket);
    const challenge = await challengePromise;
    clock.advance(HANDSHAKE_TIMEOUT_MS);
    const closePromise = waitForClose(socket);
    socket.send(JSON.stringify(createHello(secret, challenge.challenge as string)));
    assert.equal(await closePromise, true, 'a response at the deadline must fail closed');
  } finally {
    socket?.terminate();
    await transport.close();
  }
});

test('heartbeat health ignores wall-clock jumps and expires on monotonic time with interval cleanup', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const clock = createManualMonotonicClock();
  const statuses: string[] = [];
  const transport = createTransport(port, secret, clock.now, (status) => statuses.push(status));
  const originalDateNow = Date.now;
  let wallNow = originalDateNow();
  let socket: WebSocket | undefined;
  Date.now = () => wallNow;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    const challengePromise = nextMessage(socket);
    await waitForOpen(socket);
    const challenge = await challengePromise;
    const welcomePromise = nextMessage(socket);
    socket.send(JSON.stringify(createHello(secret, challenge.challenge as string)));
    assert.equal((await welcomePromise).type, 'welcome');
    assert.ok(statuses.includes('ready'));

    wallNow -= 60_000;
    await waitForMonotonicRead(clock, 3000);
    assert.equal(socket.readyState, WebSocket.OPEN, 'a backward wall-clock jump must not expire the peer');
    assert.equal(statuses.at(-1), 'ready');

    wallNow += 120_000;
    await waitForMonotonicRead(clock, 3000);
    assert.equal(socket.readyState, WebSocket.OPEN, 'a forward wall-clock jump must not expire the peer');
    assert.equal(statuses.at(-1), 'ready');

    clock.advance(10_001);
    assert.equal(await waitForClose(socket), true, 'monotonic heartbeat expiry must close the peer');
    assert.equal(statuses.at(-1), 'unavailable');

    const readsAfterClose = clock.readCount;
    await new Promise((resolve) => setTimeout(resolve, 1100));
    assert.equal(clock.readCount, readsAfterClose, 'the heartbeat interval must stop after peer cleanup');
  } finally {
    Date.now = originalDateNow;
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    await transport.close();
  }
});

test('fails closed when a second peer would exceed the single-challenge memory bound', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const transport = createTransport(port, secret);
  let firstSocket: WebSocket | undefined;
  let secondSocket: WebSocket | undefined;
  try {
    await transport.start();
    firstSocket = new WebSocket(`ws://127.0.0.1:${port}`);
    const challengePromise = nextMessage(firstSocket);
    await waitForOpen(firstSocket);
    assert.equal((await challengePromise).type, 'challenge');

    secondSocket = new WebSocket(`ws://127.0.0.1:${port}`);
    const closePromise = waitForClose(secondSocket);
    await waitForOpen(secondSocket);
    assert.equal(await closePromise, true, 'an additional peer must be rejected rather than allocating replay state');
  } finally {
    firstSocket?.terminate();
    secondSocket?.terminate();
    await transport.close();
  }
});

function createHello(secret: string, challenge: string, nonce = randomBytes(32).toString('base64url')): ClientHello {
  const unsigned = {
    type: 'hello' as const,
    protocol: DEPLOYMENT.protocol,
    challenge,
    nonce,
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
    ...binding,
  };
  return { ...unsigned, proof: createProof(secret, clientTranscript(unsigned)) };
}

function mutateEventField(event: AttributedEvent, field: (typeof DIALOGUE_EVENT_FIELDS)[number]): AttributedEvent {
  const changed: Record<string, string | number> = { ...event };
  switch (field) {
    case 'serverId': changed.serverId = `${event.serverId}-changed`; break;
    case 'worldId': changed.worldId = `${event.worldId}-changed`; break;
    case 'characterId': changed.characterId = `${event.characterId}-changed`; break;
    case 'agentId': changed.agentId = `${event.agentId}-changed`; break;
    case 'eventId': changed.eventId = randomUUID(); break;
    case 'serverEpochId': changed.serverEpochId = randomUUID(); break;
    case 'occurredAtUnixMillis': changed.occurredAtUnixMillis = event.occurredAtUnixMillis + 1; break;
    case 'capturedAtMonotonicNanos': changed.capturedAtMonotonicNanos = (BigInt(event.capturedAtMonotonicNanos) + 1n).toString(); break;
    case 'eventOrigin': changed.eventOrigin = 'history'; break;
    case 'inputKind': changed.inputKind = 'natural_address'; break;
    case 'playerId': changed.playerId = randomUUID(); break;
    case 'playerName': changed.playerName = 'ChangedPlayer'; break;
    case 'dimension': changed.dimension = 'minecraft:the_nether'; break;
    case 'text': changed.text = `${event.text}!`; break;
    default: {
      const exhaustive: never = field;
      throw new Error(`uncovered event identity field: ${exhaustive}`);
    }
  }
  return changed as unknown as AttributedEvent;
}

function reverseObjectKeysDeep(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(reverseObjectKeysDeep);
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>).reverse();
    return Object.fromEntries(entries.map(([key, nested]) => [key, reverseObjectKeysDeep(nested)]));
  }
  return value;
}

function createContextEvent(playerId: string): AttentionEvent {
  return createAttentionEvent(playerId);
}

function createAttentionEvent(playerId: string): AttentionEvent {
  return {
    ...binding,
    eventId: randomUUID(),
    serverEpochId: randomUUID(),
    occurredAtUnixMillis: 1_729_499_999_000,
    capturedAtMonotonicNanos: '1234567890123456788',
    eventOrigin: 'history',
    source: 'server_callback',
    subject: { playerId, playerName: 'TestPlayer' },
    dimension: 'minecraft:overworld',
    eventKind: 'joined',
    details: {},
  };
}

function mutateAttentionEventField(event: AttentionEvent, field: (typeof ATTENTION_EVENT_FIELDS)[number]): AttentionEvent {
  const changed: Record<string, unknown> = { ...event };
  switch (field) {
    case 'serverId': changed.serverId = `${event.serverId}-changed`; break;
    case 'worldId': changed.worldId = `${event.worldId}-changed`; break;
    case 'characterId': changed.characterId = `${event.characterId}-changed`; break;
    case 'agentId': changed.agentId = `${event.agentId}-changed`; break;
    case 'eventId': changed.eventId = randomUUID(); break;
    case 'serverEpochId': changed.serverEpochId = randomUUID(); break;
    case 'occurredAtUnixMillis': changed.occurredAtUnixMillis = event.occurredAtUnixMillis + 1; break;
    case 'capturedAtMonotonicNanos': changed.capturedAtMonotonicNanos = (BigInt(event.capturedAtMonotonicNanos) + 1n).toString(); break;
    case 'eventOrigin': changed.eventOrigin = 'live'; break;
    case 'source': changed.source = 'server_sample'; break;
    case 'subject': changed.subject = { ...event.subject, playerName: 'ChangedPlayer' }; break;
    case 'dimension': changed.dimension = 'minecraft:the_nether'; break;
    case 'eventKind': changed.eventKind = 'left'; break;
    case 'details': changed.details = { copiedAlive: true }; break;
    default: {
      const exhaustive: never = field;
      throw new Error(`uncovered attention identity field: ${exhaustive}`);
    }
  }
  return changed as AttentionEvent;
}

function createTransport(
  port: number,
  secret: string,
  now?: MonotonicNow,
  onStatus: (status: 'starting' | 'ready' | 'unavailable', reason: string) => void = () => {},
  handleRequest: DialogueRequestHandler = async () => {},
  handleAcknowledgement: DialogueAcknowledgementHandler = async () => {},
  workBudget?: HostWorkBudget,
): WorldspeakerTransport {
  const config: TransportConfig = {
    enabled: true,
    bindHost: '127.0.0.1',
    port,
    serverEndpoint: `ws://127.0.0.1:${port}`,
    bearerSecret: secret,
    deploymentId: DEPLOYMENT.deploymentId,
    manifestSha256: DEPLOYMENT.manifestSha256,
    configurationHandshakeVersion: 1,
    configSetSha256: 'a'.repeat(64),
    binding,
  };
  return new WorldspeakerTransport(config, DEPLOYMENT.hostVersion, DEPLOYMENT.companionVersion,
    handleRequest, handleAcknowledgement, onStatus, now, workBudget);
}

function createRequest(
  requestId: string,
  playerId: string,
  acknowledgement: { messageId: string; text: string } | null = null,
  capturedAtMonotonicNanos = '1234567890123456789',
): DialogueRequest {
  let invocationId = randomUUID();
  while (invocationId === requestId) invocationId = randomUUID();
  return {
    type: 'dialogue.request',
    protocol: DEPLOYMENT.protocol,
    requestId,
    invocationId,
    abilityPolicyRevision: 0,
    ...binding,
    event: {
      ...binding,
      eventId: randomUUID(),
      serverEpochId: randomUUID(),
      occurredAtUnixMillis: 1_729_500_000_000,
      capturedAtMonotonicNanos,
      eventOrigin: 'live',
      inputKind: 'command',
      playerId,
      playerName: 'TestPlayer',
      dimension: 'minecraft:overworld',
      text: 'A bounded test prompt',
    },
    conversation: { conversationId: randomUUID(), replyToMessageId: null },
    replyAudience: { kind: 'player', playerId },
    contextBatch: [],
    contextStatus: { state: 'disabled', lossRevision: '0', reasons: [] },
    acknowledgement: acknowledgement === null ? null : {
      ...acknowledgement,
      dispatchedAtUnixMillis: 1_729_500_000_001,
      dispatchedAtMonotonicNanos: '1234567890123456790',
      replyAudience: { kind: 'player', playerId },
    },
    progressContext: [],
  };
}

function createAcknowledgement(
  request: DialogueRequest, messageId: string, playerId: string,
  text = 'I have your request.',
) {
  return {
    type: 'dialogue.ack',
    protocol: DEPLOYMENT.protocol,
    requestId: request.requestId,
    invocationId: request.invocationId,
    playerId,
    acknowledgement: {
      messageId,
      text,
      dispatchedAtUnixMillis: 1_729_500_000_001,
      dispatchedAtMonotonicNanos: '1234567890123456790',
      replyAudience: { kind: 'player', playerId },
    },
  };
}

function createCancellation(requestId: string, invocationId: string, playerId: string) {
  return { type: 'dialogue.cancel', protocol: DEPLOYMENT.protocol, requestId, invocationId, playerId };
}

async function authenticateSocket(socket: WebSocket, secret: string): Promise<void> {
  const challengePromise = nextMessage(socket);
  await waitForOpen(socket);
  const challenge = await challengePromise;
  const welcomePromise = nextMessage(socket);
  socket.send(JSON.stringify(createHello(secret, challenge.challenge as string)));
  assert.equal((await welcomePromise).type, 'welcome');
}

function deferred<T = void>(): { promise: Promise<T>; resolve: (value: T | PromiseLike<T>) => void } {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>((complete) => { resolve = complete; });
  return { promise, resolve };
}

async function waitForArrayLength(values: unknown[], length: number): Promise<void> {
  const deadline = performance.now() + 3000;
  while (values.length < length) {
    if (performance.now() >= deadline) throw new Error('timed out waiting for acknowledgement context update');
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

async function waitForBudget(budget: HostWorkBudget, hostCalls: number): Promise<void> {
  const deadline = performance.now() + 3000;
  while (budget.snapshot().hostCalls !== hostCalls) {
    if (performance.now() >= deadline) throw new Error('timed out waiting for upstream work settlement');
    await new Promise<void>((resolve) => setImmediate(resolve));
  }
}

async function waitForBudgetState(
  budget: HostWorkBudget,
  expected: Readonly<{ hostCalls: number; acknowledgementCalls: number }>,
): Promise<void> {
  const deadline = performance.now() + 3000;
  while (JSON.stringify(budget.snapshot()) !== JSON.stringify(expected)) {
    if (performance.now() >= deadline) throw new Error('timed out waiting for upstream work settlement');
    await new Promise<void>((resolve) => setImmediate(resolve));
  }
}

async function waitForMonotonicRead(clock: ReturnType<typeof createManualMonotonicClock>, timeoutMs: number): Promise<void> {
  const initialReads = clock.readCount;
  const deadline = performance.now() + timeoutMs;
  while (clock.readCount === initialReads) {
    if (performance.now() >= deadline) throw new Error('timed out waiting for heartbeat clock check');
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
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

async function waitForOpen(socket: WebSocket): Promise<void> {
  if (socket.readyState === WebSocket.OPEN) return;
  await new Promise<void>((resolve, reject) => {
    socket.once('open', resolve);
    socket.once('error', reject);
  });
}

async function nextMessage(socket: WebSocket): Promise<Record<string, unknown>> {
  return await new Promise((resolve, reject) => {
    const cleanup = () => {
      socket.off('error', onError);
      socket.off('message', onMessage);
    };
    const onError = (error: Error) => {
      cleanup();
      reject(error);
    };
    const onMessage = (data: RawData) => {
      cleanup();
      try {
        resolve(JSON.parse(data.toString()) as Record<string, unknown>);
      } catch (error) {
        reject(error);
      }
    };
    socket.once('error', onError);
    socket.once('message', onMessage);
  });
}

async function waitForClose(socket: WebSocket): Promise<boolean> {
  if (socket.readyState === WebSocket.CLOSED) return true;
  return await new Promise((resolve) => {
    const timeout = setTimeout(() => resolve(false), 3000);
    socket.once('close', () => { clearTimeout(timeout); resolve(true); });
  });
}

async function waitForCloseDetails(socket: WebSocket): Promise<{ code: number; reason: string }> {
  return await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error('timed out waiting for WebSocket refusal'));
    }, 3000);
    const onClose = (code: number, reason: Buffer) => {
      cleanup();
      resolve({ code, reason: reason.toString('utf8') });
    };
    const onError = (error: Error) => {
      cleanup();
      reject(error);
    };
    const cleanup = () => {
      clearTimeout(timer);
      socket.off('close', onClose);
      socket.off('error', onError);
    };
    socket.once('close', onClose);
    socket.once('error', onError);
  });
}
