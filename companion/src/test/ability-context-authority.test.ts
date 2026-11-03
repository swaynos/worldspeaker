import assert from 'node:assert/strict';
import { randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
import test from 'node:test';
import { WebSocket, type RawData } from 'ws';
import { parseAbilityPolicy } from '../ability-policy.js';
import { OBSERVE_PLAYER_ABILITY } from '../ability-catalogue.js';
import { validateAbilityIntent, evaluateAbilityExecution, type AbilityTrustedContextHandle } from '../ability-validation.js';
import { DEPLOYMENT } from '../generated/deployment.js';
import {
  clientTranscript,
  createProof,
  type Binding,
  type ClientHello,
  type DialogueRequest,
} from '../protocol.js';
import { isCurrentAbilityContext, WorldspeakerTransport, type TransportConfig } from '../transport.js';

const binding: Binding = Object.freeze({
  serverId: 'server-ability-owner',
  worldId: 'world-ability-owner',
  characterId: 'character-ability-owner',
  agentId: 'agent-ability-owner',
});
const PLAYER_A = '00000000-0000-4000-8000-000000000041';
const PLAYER_B = '00000000-0000-4000-8000-000000000042';
const OTHER = '00000000-0000-4000-8000-000000000043';

test('only the authenticated transport owner can consume its exact live request authority', async () => {
  const port = await availablePort();
  const secret = randomBytes(32).toString('base64url');
  const request = createRequest(PLAYER_A, 8);
  const intent = validateAbilityIntent(intentBytes(PLAYER_A));
  assert.equal(intent.status, 'VALID');
  if (intent.status !== 'VALID') return;

  let admitted!: (value: { request: DialogueRequest; sendFinal: (text: string) => string | null;
    context: AbilityTrustedContextHandle; isLive: () => boolean }) => void;
  const admission = new Promise<{ request: DialogueRequest; sendFinal: (text: string) => string | null;
    context: AbilityTrustedContextHandle; isLive: () => boolean }>((resolve) => { admitted = resolve; });
  let releaseHandler!: () => void;
  const handlerDone = new Promise<void>((resolve) => { releaseHandler = resolve; });
  const policy = parseAbilityPolicy({
    schemaVersion: 1,
    revision: 8,
    grants: [{ id: 'observe-a', characterId: binding.characterId, abilityId: OBSERVE_PLAYER_ABILITY,
      contractVersion: 1, requesterIds: [PLAYER_A], otherTargetIds: [OTHER], dimensions: ['minecraft:overworld'] }],
    denials: [],
    protection: { externalClaimsMode: 'REQUIRED' },
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
    configSetSha256: 'b'.repeat(64),
    abilityPolicy: policy,
    binding,
  };
  const transport = new WorldspeakerTransport(config, DEPLOYMENT.hostVersion, DEPLOYMENT.companionVersion,
    async (ownedRequest, sendFinal, isLive, _discovery, context) => {
      admitted({ request: ownedRequest, sendFinal, isLive, context });
      await handlerDone;
    }, async () => {}, () => {});
  let socket: WebSocket | undefined;
  try {
    await transport.start();
    socket = new WebSocket(`ws://127.0.0.1:${port}`);
    await authenticate(socket, secret);
    socket.send(JSON.stringify(request));
    const owned = await admission;
    assert.equal(Object.isFrozen(owned.request), true);
    assert.equal(isCurrentAbilityContext(owned.context, { request: owned.request, binding,
      audiencePlayerId: PLAYER_A }), true);
    assert.equal(owned.isLive(), true);

    const expected = { request: owned.request, binding, audiencePlayerId: PLAYER_A } as const;
    assert.deepEqual(evaluateAbilityExecution(owned.context, expected, intent.intent), {
      state: 'rejected', reasonCode: 'execution_unavailable', effectStarted: false,
    });
    const allowedOther = validateAbilityIntent(intentBytes(OTHER));
    assert.equal(allowedOther.status, 'VALID');
    if (allowedOther.status === 'VALID') assert.deepEqual(evaluateAbilityExecution(owned.context, expected, allowedOther.intent), {
      state: 'rejected', reasonCode: 'execution_unavailable', effectStarted: false,
    });
    const unlistedOther = validateAbilityIntent(intentBytes(PLAYER_B));
    assert.equal(unlistedOther.status, 'VALID');
    if (unlistedOther.status === 'VALID') assert.deepEqual(evaluateAbilityExecution(owned.context, expected, unlistedOther.intent), {
      state: 'rejected', reasonCode: 'permission_denied', effectStarted: false,
    });

    const forged = Object.freeze(Object.create(null)) as AbilityTrustedContextHandle;
    const requestB = createRequest(PLAYER_B, 8);
    assert.deepEqual(evaluateAbilityExecution(forged, expected, intent.intent), {
      state: 'rejected', reasonCode: 'stale_context', effectStarted: false,
    });
    assert.deepEqual(evaluateAbilityExecution(owned.context, { ...expected, request: requestB }, intent.intent), {
      state: 'rejected', reasonCode: 'stale_context', effectStarted: false,
    });
    assert.deepEqual(evaluateAbilityExecution(owned.context, { ...expected, audiencePlayerId: PLAYER_B }, intent.intent), {
      state: 'rejected', reasonCode: 'stale_context', effectStarted: false,
    });
    assert.deepEqual(evaluateAbilityExecution(owned.context, { ...expected, binding: { ...binding, worldId: 'other-world' } }, intent.intent), {
      state: 'rejected', reasonCode: 'stale_context', effectStarted: false,
    });
    assert.equal(isCurrentAbilityContext(JSON.parse(JSON.stringify(owned.context)) as AbilityTrustedContextHandle), false,
      'serializing an opaque handle cannot reproduce WeakMap owner membership');

    const response = nextMessage(socket);
    assert.ok(owned.sendFinal('The request has completed.'));
    assert.equal(owned.isLive(), false);
    assert.deepEqual(evaluateAbilityExecution(owned.context, expected, intent.intent), {
      state: 'rejected', reasonCode: 'stale_context', effectStarted: false,
    });
    releaseHandler();
    assert.equal((await response).type, 'dialogue.message');
  } finally {
    releaseHandler?.();
    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.terminate();
    await transport.close();
  }
});

function intentBytes(targetPlayerId: string): Uint8Array {
  return Buffer.from(JSON.stringify({ abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1,
    arguments: { targetPlayerId } }), 'utf8');
}

function createRequest(playerId: string, policyRevision: number): DialogueRequest {
  const requestId = randomUUID();
  let invocationId = randomUUID();
  while (invocationId === requestId) invocationId = randomUUID();
  return {
    type: 'dialogue.request',
    protocol: DEPLOYMENT.protocol,
    requestId,
    invocationId,
    abilityPolicyRevision: policyRevision,
    ...binding,
    event: {
      ...binding,
      eventId: randomUUID(),
      serverEpochId: randomUUID(),
      occurredAtUnixMillis: 1_729_500_000_000,
      capturedAtMonotonicNanos: '1234567890123456789',
      eventOrigin: 'live',
      inputKind: 'command',
      playerId,
      playerName: `Player${playerId === PLAYER_A ? 'A' : 'B'}`,
      dimension: 'minecraft:overworld',
      text: `request from ${playerId}`,
    },
    conversation: { conversationId: randomUUID(), replyToMessageId: null },
    replyAudience: { kind: 'player', playerId },
    contextBatch: [],
    contextStatus: { state: 'disabled', lossRevision: '0', reasons: [] },
    acknowledgement: null,
    progressContext: [],
  };
}

async function authenticate(socket: WebSocket, secret: string): Promise<void> {
  const challengePromise = nextMessage(socket);
  await waitForOpen(socket);
  const challenge = await challengePromise;
  assert.equal(challenge.type, 'challenge');
  const nonce = randomBytes(32).toString('base64url');
  const unsigned = {
    type: 'hello' as const,
    protocol: DEPLOYMENT.protocol,
    challenge: challenge.challenge as string,
    nonce,
    deploymentId: DEPLOYMENT.deploymentId,
    manifestSha256: DEPLOYMENT.manifestSha256,
    minecraftVersion: DEPLOYMENT.minecraftVersion,
    fabricLoaderVersion: DEPLOYMENT.fabricLoaderVersion,
    fabricApiVersion: DEPLOYMENT.fabricApiVersion,
    javaMajor: DEPLOYMENT.javaMajor,
    javaVendor: DEPLOYMENT.javaVendor,
    javaRuntimeVersion: DEPLOYMENT.javaRuntimeVersion,
    configurationHandshakeVersion: 1 as const,
    configSetSha256: 'b'.repeat(64),
    ...binding,
  };
  const welcomePromise = nextMessage(socket);
  const hello: ClientHello = { ...unsigned, proof: createProof(secret, clientTranscript(unsigned)) };
  socket.send(JSON.stringify(hello));
  const welcome = await welcomePromise;
  assert.equal(welcome.type, 'welcome');
  assert.equal(welcome.configSetSha256, 'b'.repeat(64));
  assert.equal(welcome.serverId, binding.serverId);
}

async function availablePort(): Promise<number> {
  const server = createServer();
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const address = server.address();
  if (address === null || typeof address === 'string') throw new Error('no TCP port was assigned');
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
    const onError = (error: Error) => { cleanup(); reject(error); };
    const onMessage = (data: RawData) => {
      cleanup();
      try { resolve(JSON.parse(data.toString()) as Record<string, unknown>); }
      catch (error) { reject(error); }
    };
    socket.once('error', onError);
    socket.once('message', onMessage);
  });
}
