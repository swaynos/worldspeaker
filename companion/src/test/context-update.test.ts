import assert from 'node:assert/strict';
import test from 'node:test';
import type { OpenClawPluginApi } from 'openclaw/plugin-sdk/channel-core';
import { createAcknowledgementContextUpdater } from '../context-update.js';
import type { DialogueProgress, DialogueRequest } from '../protocol.js';

const request: DialogueRequest = {
  type: 'dialogue.request',
  protocol: 'worldspeaker.compat.v5',
  requestId: '00000000-0000-4000-8000-000000000061',
  invocationId: '00000000-0000-4000-8000-000000000060',
  abilityPolicyRevision: 0,
  serverId: 'server-test',
  worldId: 'world-test',
  characterId: 'character-test',
  agentId: 'agent-test',
  event: {
    serverId: 'server-test', worldId: 'world-test', characterId: 'character-test', agentId: 'agent-test',
    eventId: '00000000-0000-4000-8000-000000000064', serverEpochId: '00000000-0000-4000-8000-000000000065',
    occurredAtUnixMillis: 1_729_500_000_000, capturedAtMonotonicNanos: '-9223372036854775808',
    eventOrigin: 'live', inputKind: 'command', playerId: '00000000-0000-4000-8000-000000000062',
    playerName: 'TestPlayer', dimension: 'minecraft:overworld', text: 'Where am I?',
  },
  conversation: { conversationId: '00000000-0000-4000-8000-000000000066', replyToMessageId: null },
  replyAudience: { kind: 'player', playerId: '00000000-0000-4000-8000-000000000062' },
  contextBatch: [],
  contextStatus: { state: 'disabled', lossRevision: '0', reasons: [] },
  acknowledgement: null,
  progressContext: [],
};
const acknowledgement = {
  messageId: '00000000-0000-4000-8000-000000000063',
  text: 'I have your request.',
  dispatchedAtUnixMillis: 1_729_500_000_001,
  dispatchedAtMonotonicNanos: '1234567890123456789',
  replyAudience: { kind: 'player' as const, playerId: '00000000-0000-4000-8000-000000000062' },
};

test('queues the dispatched acknowledgement through the pinned public next-turn SDK seam', async () => {
  const injections: Parameters<OpenClawPluginApi['session']['workflow']['enqueueNextTurnInjection']>[0][] = [];
  const enqueue: OpenClawPluginApi['session']['workflow']['enqueueNextTurnInjection'] = async (input) => {
    injections.push(input);
    return { enqueued: true, id: 'injection-1', sessionKey: input.sessionKey };
  };
  const update = createAcknowledgementContextUpdater(enqueue);

  await update({ sessionKey: 'agent:agent-test:worldspeaker:game-test', agentId: 'agent-test', request, acknowledgement });

  assert.equal(injections.length, 1);
  const injection = injections[0]!;
  assert.equal(injection.sessionKey, 'agent:agent-test:worldspeaker:game-test');
  assert.equal(injection.agentId, 'agent-test');
  assert.equal(injection.placement, 'append_context');
  assert.equal(injection.idempotencyKey, `worldspeaker:ack:${acknowledgement.messageId}`);
  assert.equal(injection.ttlMs, 60_000);
  assert.deepEqual(injection.metadata, {
    source: 'worldspeaker',
    kind: 'locally-dispatched-acknowledgement',
    requestId: request.requestId,
    invocationId: request.invocationId,
    eventId: request.event.eventId,
    conversationId: request.conversation.conversationId,
    acknowledgementId: acknowledgement.messageId,
    serverId: request.serverId,
    worldId: request.worldId,
    characterId: request.characterId,
    playerId: request.event.playerId,
    captureClock: 'minecraft-server:System.nanoTime',
    capturedAtMonotonicNanos: request.event.capturedAtMonotonicNanos,
  });
  assert.match(injection.text, /"capturedAtMonotonicNanos":"-9223372036854775808"/u);
  assert.match(injection.text, /"acknowledgement":"I have your request\."/u);
  assert.doesNotMatch(injection.text, /Where am I\?/u);
  assert.match(injection.text, /do not compare or subtract it from this host clock/u);
  assert.match(injection.text, /Trusted Worldspeaker delivery context/u);
});

test('accepts the SDK idempotency result without creating a second context record', async () => {
  let calls = 0;
  const enqueue: OpenClawPluginApi['session']['workflow']['enqueueNextTurnInjection'] = async (input) => {
    calls++;
    return { enqueued: false, id: 'already-pending', sessionKey: input.sessionKey };
  };

  await createAcknowledgementContextUpdater(enqueue)({
    sessionKey: 'agent:agent-test:worldspeaker:game-test', agentId: 'agent-test', request, acknowledgement,
  });
  assert.equal(calls, 1);
});

test('queues dispatched progress as idempotent next-turn context, not as a new player input', async () => {
  const progress: DialogueProgress = {
    messageId: '00000000-0000-4000-8000-000000000067',
    text: '[Worldspeaker] Your request has been forwarded and I am still waiting for a complete answer.',
    dispatchedAtUnixMillis: 1_729_500_000_002,
    dispatchedAtMonotonicNanos: '1234567890123456790',
    replyAudience: { kind: 'player', playerId: request.event.playerId },
    phase: 'awaiting_answer',
  };
  const injections: Parameters<OpenClawPluginApi['session']['workflow']['enqueueNextTurnInjection']>[0][] = [];
  const enqueue: OpenClawPluginApi['session']['workflow']['enqueueNextTurnInjection'] = async (input) => {
    injections.push(input);
    return { enqueued: true, id: 'progress-injection', sessionKey: input.sessionKey };
  };

  await createAcknowledgementContextUpdater(enqueue)({
    sessionKey: 'agent:agent-test:worldspeaker:game-test', agentId: 'agent-test', request, progress,
  });

  assert.equal(injections.length, 1);
  const injection = injections[0]!;
  const metadata = injection.metadata as Record<string, unknown>;
  assert.equal(injection.placement, 'append_context');
  assert.equal(injection.ttlMs, 60_000);
  assert.equal(injection.idempotencyKey, `worldspeaker:progress:${progress.messageId}`);
  assert.equal(metadata.kind, 'dispatched-progress');
  assert.equal(metadata.phase, 'awaiting_answer');
  assert.match(injection.text, /not a new player message or agent turn/u);
  assert.match(injection.text, /continue with the original player request/u);
  assert.match(injection.text, /"progress":"\[Worldspeaker\] Your request/u);
  assert.doesNotMatch(injection.text, /Where am I\?/u);
});

test('rejects an SDK result that resolves to a different OpenClaw session', async () => {
  const enqueue: OpenClawPluginApi['session']['workflow']['enqueueNextTurnInjection'] = async () => ({
    enqueued: true, id: 'misrouted', sessionKey: 'agent:other:session',
  });

  await assert.rejects(createAcknowledgementContextUpdater(enqueue)({
    sessionKey: 'agent:agent-test:worldspeaker:game-test', agentId: 'agent-test', request, acknowledgement,
  }), /different session/u);
});
