import { randomBytes, randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';
import type { OpenClawConfig } from 'openclaw/plugin-sdk/channel-core';
import { buildChannelInboundEventContext, type BuildChannelInboundEventContextParams } from 'openclaw/plugin-sdk/channel-inbound';
import { resolveAgentRoute } from 'openclaw/plugin-sdk/routing';
import { handlePlayerAcknowledgement, handlePlayerProgress, handlePlayerRequest } from '../channel.js';
import { createAcknowledgementContextUpdater, type AcknowledgementContextUpdater } from '../context-update.js';
import { DEPLOYMENT } from '../generated/deployment.js';
import {
  clientTranscript,
  createProof,
  type DialogueAcknowledgement,
  type DialogueProgress,
  type DialogueRequest,
} from '../protocol.js';
import { WorldspeakerTransport, type TransportConfig } from '../transport.js';
import { HostWorkBudget } from '../work-budget.js';

export const CHANNEL_TEST_BINDING = {
  serverId: 'server-channel-transport-test',
  worldId: 'world-channel-transport-test',
  characterId: 'character-channel-transport-test',
  agentId: 'agent-channel-transport-test',
} as const;

export const CHANNEL_TEST_PLAYER_A = '00000000-0000-4000-8000-000000000041';
export const CHANNEL_TEST_PLAYER_B = '00000000-0000-4000-8000-000000000042';
export const CHANNEL_TEST_ACCOUNT = { accountId: 'default', binding: CHANNEL_TEST_BINDING } as
  Parameters<typeof handlePlayerRequest>[1];

type Delivery = (payload: Record<string, unknown>, info: { kind: string }) => Promise<unknown>;

export type ChannelTransportOptions = Readonly<{
  dispatch: (request: DialogueRequest, deliver: Delivery) => Promise<void>;
  enqueueContext?: Parameters<typeof createAcknowledgementContextUpdater>[0];
  updateContext?: AcknowledgementContextUpdater;
  resolveRoute?: (input: Parameters<typeof resolveAgentRoute>[0]) => ReturnType<typeof resolveAgentRoute>;
  resolveIngress?: (input: Record<string, unknown>) => Promise<unknown>;
  onStatus?: (status: 'starting' | 'ready' | 'unavailable', reason: string) => void;
}>;

/**
 * Runs the real transport through the exported channel handlers and public SDK seams.
 * Tests replace only the host SDK's ingress/dispatch/context promises, never its failure classifier.
 */
export function createChannelBackedTransport(
  port: number,
  secret: string,
  options: ChannelTransportOptions,
  budget = new HostWorkBudget(),
): Readonly<{ transport: WorldspeakerTransport; budget: HostWorkBudget; port: number }> {
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
    binding: CHANNEL_TEST_BINDING,
  };
  const cfg = {} as OpenClawConfig;
  const updateContext = options.updateContext ?? createAcknowledgementContextUpdater(
    options.enqueueContext ?? (async (input) => ({ enqueued: true, id: 'fixture-injection', sessionKey: input.sessionKey })),
  );
  const runtime = {
    routing: {
      resolveAgentRoute: (input: Parameters<typeof resolveAgentRoute>[0]) =>
        options.resolveRoute ? options.resolveRoute(input) : resolveAgentRoute(input),
    },
    inbound: {
      ingress: {
        resolve: async (input: Record<string, unknown>) => options.resolveIngress
          ? options.resolveIngress(input)
          : { ingress: { admission: 'dispatch', decision: 'allow' } },
      },
      buildContext: (input: BuildChannelInboundEventContextParams) => buildChannelInboundEventContext(input),
    },
    reply: {
      dispatchReplyWithBufferedBlockDispatcher: async (input: {
        ctx: { worldspeaker: DialogueRequest };
        dispatcherOptions: { deliver: Delivery };
        toolsAllow: readonly string[];
      }) => {
        assertToolsDisabled(input);
        await options.dispatch(input.ctx.worldspeaker, input.dispatcherOptions.deliver);
      },
    },
  } as unknown as Parameters<typeof handlePlayerRequest>[2];

  const transport = new WorldspeakerTransport(
    config,
    DEPLOYMENT.hostVersion,
    DEPLOYMENT.companionVersion,
    (request, sendFinal, isLive) => handlePlayerRequest(
      cfg, CHANNEL_TEST_ACCOUNT, runtime, request, sendFinal, isLive, updateContext,
    ),
    (request, context) => 'phase' in context
      ? handlePlayerProgress(cfg, CHANNEL_TEST_ACCOUNT, runtime, request, context, updateContext)
      : handlePlayerAcknowledgement(cfg, CHANNEL_TEST_ACCOUNT, runtime, request, context, updateContext),
    options.onStatus ?? (() => {}),
    undefined,
    budget,
  );

  return { transport, budget, port };
}

export function channelRequest(
  playerId: string,
  text: string,
  overrides: Partial<DialogueRequest> = {},
): DialogueRequest {
  const eventId = randomUUID();
  const invocationId = randomUUID();
  const requestId = randomUUID();
  const request: DialogueRequest = {
    type: 'dialogue.request',
    protocol: DEPLOYMENT.protocol,
    requestId,
    invocationId,
    abilityPolicyRevision: 0,
    ...CHANNEL_TEST_BINDING,
    event: {
      ...CHANNEL_TEST_BINDING,
      eventId,
      serverEpochId: randomUUID(),
      occurredAtUnixMillis: 1_729_500_000_000,
      capturedAtMonotonicNanos: '1234567890123456789',
      eventOrigin: 'live',
      inputKind: 'command',
      playerId,
      playerName: playerId === CHANNEL_TEST_PLAYER_A ? 'PlayerA' : 'PlayerB',
      dimension: 'minecraft:overworld',
      text,
    },
    conversation: { conversationId: randomUUID(), replyToMessageId: null },
    replyAudience: { kind: 'player', playerId },
    contextBatch: [],
    contextStatus: { state: 'disabled', lossRevision: '0', reasons: [] },
    acknowledgement: null,
    progressContext: [],
    ...overrides,
  };
  return request;
}

export function channelProgress(request: DialogueRequest): DialogueProgress {
  return {
    messageId: randomUUID(),
    text: '[Worldspeaker] Your request is still being handled.',
    dispatchedAtUnixMillis: 1_729_500_000_001,
    dispatchedAtMonotonicNanos: '1234567890123456790',
    replyAudience: request.replyAudience,
    phase: 'awaiting_answer',
  };
}

export function channelAcknowledgementFrame(request: DialogueRequest) {
  const acknowledgement: DialogueAcknowledgement = {
    messageId: randomUUID(),
    text: 'I have your request.',
    dispatchedAtUnixMillis: 1_729_500_000_001,
    dispatchedAtMonotonicNanos: '1234567890123456790',
    replyAudience: request.replyAudience,
  };
  return {
    type: 'dialogue.ack',
    protocol: DEPLOYMENT.protocol,
    requestId: request.requestId,
    invocationId: request.invocationId,
    playerId: request.event.playerId,
    acknowledgement,
  };
}

export function channelProgressFrame(request: DialogueRequest) {
  return {
    type: 'dialogue.progress',
    protocol: DEPLOYMENT.protocol,
    requestId: request.requestId,
    invocationId: request.invocationId,
    playerId: request.event.playerId,
    progress: channelProgress(request),
  };
}

export function channelCancellation(request: DialogueRequest) {
  return {
    type: 'dialogue.cancel',
    protocol: DEPLOYMENT.protocol,
    requestId: request.requestId,
    invocationId: request.invocationId,
    playerId: request.event.playerId,
  };
}

export async function authenticateChannelSocket(socket: WebSocket, secret: string): Promise<Record<string, unknown>> {
  const challenge = await nextChannelFrame(socket);
  if (challenge.type !== 'challenge' || typeof challenge.challenge !== 'string') {
    throw new Error('authenticated channel did not provide a challenge');
  }
  const unsigned = {
    type: 'hello' as const,
    protocol: DEPLOYMENT.protocol,
    challenge: challenge.challenge,
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
    ...CHANNEL_TEST_BINDING,
  };
  const welcomePromise = nextChannelFrame(socket);
  socket.send(JSON.stringify({ ...unsigned, proof: createProof(secret, clientTranscript(unsigned)) }));
  const welcome = await welcomePromise;
  if (welcome.type !== 'welcome') throw new Error('authenticated channel did not become ready');
  return welcome;
}

export async function nextChannelFrame(socket: WebSocket): Promise<Record<string, unknown>> {
  return await new Promise((resolve, reject) => {
    const cleanup = () => {
      clearTimeout(timer);
      socket.off('message', onMessage);
      socket.off('error', onError);
      socket.off('close', onClose);
    };
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error('timed out waiting for an authenticated transport frame (3s)'));
    }, 3000);
    const onMessage = (data: Buffer | ArrayBuffer | Buffer[]) => {
      cleanup();
      try {
        const text = Array.isArray(data) ? Buffer.concat(data).toString('utf8') :
          Buffer.isBuffer(data) ? data.toString('utf8') : Buffer.from(data).toString('utf8');
        resolve(JSON.parse(text) as Record<string, unknown>);
      } catch (error) {
        reject(error);
      }
    };
    const onError = (error: Error) => {
      cleanup();
      reject(error);
    };
    const onClose = (code: number, reason: Buffer) => {
      cleanup();
      reject(new Error(`authenticated transport closed before a frame (${code}: ${reason.toString('utf8')})`));
    };
    socket.once('message', onMessage);
    socket.once('error', onError);
    socket.once('close', onClose);
  });
}

export async function channelCloseDetails(socket: WebSocket): Promise<{ code: number; reason: string }> {
  if (socket.readyState === WebSocket.CLOSED) throw new Error('transport socket closed before close was observed');
  return await new Promise((resolve, reject) => {
    const cleanup = () => {
      clearTimeout(timer);
      socket.off('close', onClose);
      socket.off('error', onError);
    };
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error('timed out waiting for transport close (3s)'));
    }, 3000);
    const onClose = (code: number, reason: Buffer) => {
      cleanup();
      resolve({ code, reason: reason.toString('utf8') });
    };
    const onError = (error: Error) => {
      cleanup();
      reject(error);
    };
    socket.once('close', onClose);
    socket.once('error', onError);
  });
}

export function bounded<T>(promise: Promise<T>, description: string, timeoutMs = 3000): Promise<T> {
  let timer: NodeJS.Timeout;
  return Promise.race([
    promise,
    new Promise<never>((_resolve, reject) => {
      timer = setTimeout(() => reject(new Error(`timed out waiting for ${description} (${timeoutMs}ms)`)), timeoutMs);
    }),
  ]).finally(() => clearTimeout(timer!));
}

function assertToolsDisabled(input: { ctx: unknown; dispatcherOptions: { deliver: Delivery }; toolsAllow: readonly string[] }): void {
  void input.ctx;
  void input.dispatcherOptions;
  assert.deepEqual(input.toolsAllow, [], 'the real channel pipeline disables tools for player requests');
}
