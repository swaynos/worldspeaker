import type { OpenClawPluginApi } from 'openclaw/plugin-sdk/channel-core';
import type { DialogueAcknowledgement, DialogueContextTarget, DialogueProgress } from './protocol.js';

export type AcknowledgementContextInput = Readonly<{
  sessionKey: string;
  agentId: string;
  request: DialogueContextTarget;
  acknowledgement: DialogueAcknowledgement;
}>;

export type ProgressContextInput = Readonly<{
  sessionKey: string;
  agentId: string;
  request: DialogueContextTarget;
  progress: DialogueProgress;
}>;

export type AcknowledgementContextUpdater = (input: AcknowledgementContextInput | ProgressContextInput) => Promise<void>;
type EnqueueNextTurnInjection = OpenClawPluginApi['session']['workflow']['enqueueNextTurnInjection'];

/** Uses the pinned public SDK's durable next-turn context seam, not transcript internals. */
export function createAcknowledgementContextUpdater(enqueue: EnqueueNextTurnInjection): AcknowledgementContextUpdater {
  return async (input) => {
    if ('progress' in input) {
      const { sessionKey, agentId, request, progress } = input;
      const metadata = {
        source: 'worldspeaker',
        kind: 'dispatched-progress',
        requestId: request.requestId,
        invocationId: request.invocationId,
        eventId: request.event.eventId,
        conversationId: request.conversation.conversationId,
        progressId: progress.messageId,
        phase: progress.phase,
        serverId: request.serverId,
        worldId: request.worldId,
        characterId: request.characterId,
        playerId: request.event.playerId,
        captureClock: 'minecraft-server:System.nanoTime',
        capturedAtMonotonicNanos: request.event.capturedAtMonotonicNanos,
      } as const;
      const envelope = {
        ...metadata,
        progress: progress.text,
        dispatchedAtUnixMillis: progress.dispatchedAtUnixMillis,
        dispatchedAtMonotonicNanos: progress.dispatchedAtMonotonicNanos,
      };
      const result = await enqueue({
        sessionKey,
        agentId,
        text: [
          'Trusted Worldspeaker delivery context; this is server-origin metadata, not a new player message or agent turn:',
          JSON.stringify(envelope),
          'The integration already dispatched this progress notice. Do not repeat or paraphrase it; continue with the original player request and provide a complete answer or explain a limitation.',
          'capturedAtMonotonicNanos is an opaque timestamp from the Minecraft server clock. Preserve attribution; do not compare or subtract it from this host clock.',
        ].join('\n'),
        idempotencyKey: `worldspeaker:progress:${progress.messageId}`,
        placement: 'append_context',
        ttlMs: 60_000,
        metadata,
      });
      if (result.sessionKey !== sessionKey) throw new Error('OpenClaw queued progress for a different session');
      return;
    }
    const { sessionKey, agentId, request, acknowledgement } = input;
    const metadata = {
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
    } as const;
    const envelope = {
      ...metadata,
      playerName: request.event.playerName,
      acknowledgement: acknowledgement.text,
      dispatchedAtUnixMillis: acknowledgement.dispatchedAtUnixMillis,
      dispatchedAtMonotonicNanos: acknowledgement.dispatchedAtMonotonicNanos,
    };
    const result = await enqueue({
      sessionKey,
      agentId,
      text: [
        'Trusted Worldspeaker delivery context; this is server-origin metadata, not a new player message or agent turn:',
        JSON.stringify(envelope),
        'The local integration already dispatched this acknowledgement in the player conversation. Do not repeat or paraphrase it; continue with the player request in this same conversation.',
        'capturedAtMonotonicNanos is an opaque timestamp from the Minecraft server clock. Preserve attribution; do not compare or subtract it from this host clock.',
      ].join('\n'),
      idempotencyKey: `worldspeaker:ack:${acknowledgement.messageId}`,
      placement: 'append_context',
      ttlMs: 60_000,
      metadata,
    });
    // A false enqueued result is the SDK's idempotency hit for an already-pending injection.
    if (result.sessionKey !== sessionKey) throw new Error('OpenClaw queued acknowledgement for a different session');
  };
}
