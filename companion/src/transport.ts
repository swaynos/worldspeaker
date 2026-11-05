import { randomBytes, randomUUID } from 'node:crypto';
import { WebSocket, WebSocketServer, type RawData } from 'ws';
import {
  createProof,
  ATTENTION_EVENT_FIELDS,
  DIALOGUE_EVENT_FIELDS,
  exactRecord,
  HANDSHAKE_TIMEOUT_MS,
  HEARTBEAT_FIELDS,
  MAX_FRAME_BYTES,
  ProtocolRejectionError,
  validateDialogueAcknowledgement,
  validateDialogueCancel,
  validateDialogueProgress,
  validateDialogueResponse,
  serverChallengeTranscript,
  serverTranscript,
  validateClientHello,
  validateDialogueRequest,
  welcomeFacts,
  type Binding,
  type DialogueRequest,
  type ContextEvent,
  type DialogueResponse,
  type DialogueAcknowledgement,
  type DialogueAcknowledgementFrame,
  type DialogueProgress,
  type DialogueProgressFrame,
  type DialogueContextTarget,
  type DialogueCancelFrame,
} from './protocol.js';
import { DEPLOYMENT } from './generated/deployment.js';
import { systemMonotonicNow, type MonotonicNow } from './monotonic-clock.js';
import { parseStrictJson } from './strict-json.js';
import { HostWorkBudget } from './work-budget.js';
import { canonicalizeAbilityPolicySnapshot, denyAllAbilityPolicy, type AbilityPolicy } from './ability-policy.js';
import { isValidEndpointSyntax } from './endpoint-syntax.js';
import { buildAbilityDiscovery, type AbilityDiscovery } from './ability-discovery.js';
import type { AbilityTrustedContextHandle } from './ability-validation.js';
import { resolveAbilityPolicy } from './ability-policy-resolver.js';
import { STRIKE_LIGHTNING_ABILITY, type ValidatedAbilityInput } from './ability-catalogue.js';

export type TransportConfig = Readonly<{
  enabled: boolean;
  bindHost: string;
  port: number;
  serverEndpoint: string;
  bearerSecret: string;
  deploymentId: string;
  manifestSha256: string;
  configurationHandshakeVersion: 1;
  configSetSha256: string;
  abilityPolicy?: AbilityPolicy;
  binding: Binding;
}>;
type FrozenTransportConfig = Omit<TransportConfig, 'abilityPolicy'> & Readonly<{ abilityPolicy: AbilityPolicy }>;

export type DialogueRequestHandler = (
  request: DialogueRequest,
  sendFinal: (text: string) => string | null,
  isLive: () => boolean,
  abilityDiscovery: AbilityDiscovery,
  abilityContext: AbilityTrustedContextHandle,
) => Promise<void>;

/** A settled, request-owned refusal; it does not imply loss of shared host/session state. */
export class RequestLocalDialogueFailure extends Error {
  constructor(stage: 'invalid-final' | 'no-final' | 'request-refused') {
    super(`Worldspeaker request-local failure: ${stage}`);
    this.name = 'RequestLocalDialogueFailure';
  }
}

export type DialogueAcknowledgementHandler = (request: DialogueContextTarget,
  context: DialogueAcknowledgement | DialogueProgress) => Promise<void>;
export type TransportStatus = (status: 'starting' | 'ready' | 'unavailable', reason: string) => void;

const ACKNOWLEDGEMENT_TARGET_TTL_MS = 30_000;
const MAX_ACKNOWLEDGEMENT_TARGETS = 16;
const MAX_RETAINED_IDENTITIES = 256;

type Peer = {
  generation: number;
  socket: WebSocket;
  challenge: string;
  handshakeDeadline: number;
  ready: boolean;
  lastHeartbeatAt: number;
  lastHeartbeatSequence: number;
  handshakeTimer: NodeJS.Timeout;
  heartbeatTimer?: NodeJS.Timeout;
  requests: Map<string, DialogueRequest>;
  requestDeadlines: Map<string, number>;
  acknowledgementTargets: Map<string, { request: DialogueContextTarget; expiresAt: number }>;
  acknowledgementIds: Map<string, string>;
  progressIds: Map<string, string>;
  progressCounts: Map<string, number>;
  retainedInvocations: Map<string, string>;
  retainedEvents: Map<string, string>;
  retainedRequests: Map<string, string>;
  retainedRequestAttribution: Map<string, { invocationId: string; playerId: string }>;
  retainedInvocationAttribution: Map<string, { requestId: string; playerId: string }>;
  cancelledRequests: Set<string>;
  abilityContexts: Map<string, object>;
};

type OwnedAbilityContext = Readonly<{
  config: FrozenTransportConfig;
  configSetSha256: string;
  policy: AbilityPolicy;
  binding: Binding;
  peer: Peer;
  peerGeneration: number;
  request: DialogueRequest;
  requestId: string;
  invocationId: string;
  requesterId: string;
  audiencePlayerId: string;
  serverEpochId: string;
  capturedAtMonotonicNanos: string;
  origin: 'live';
  isLive: () => boolean;
}>;

// This registry belongs to the accepted transport request owner. Handles are
// opaque WeakMap keys and are never attached to a wire frame or model context.
const ownedAbilityContexts = new WeakMap<object, OwnedAbilityContext>();

export type AbilityContextExpectation = Readonly<{
  request?: DialogueRequest;
  binding?: Binding;
  audiencePlayerId?: string;
}>;

/** Checked consumption only; there is deliberately no exported handle issuer. */
export function isCurrentAbilityContext(
  handle: AbilityTrustedContextHandle | null,
  expected: AbilityContextExpectation = {},
): boolean {
  try {
    if (handle === null || typeof handle !== 'object') return false;
    const state = ownedAbilityContexts.get(handle);
    if (!state || state.config.configSetSha256 !== state.configSetSha256 ||
        state.policy !== state.config.abilityPolicy || state.policy.revision !== state.request.abilityPolicyRevision ||
        state.binding.serverId !== state.config.binding.serverId || state.binding.worldId !== state.config.binding.worldId ||
        state.binding.characterId !== state.config.binding.characterId || state.binding.agentId !== state.config.binding.agentId ||
        state.peer.generation !== state.peerGeneration || state.peer.requests.get(state.requestId) !== state.request ||
        state.request.requestId !== state.requestId || state.request.invocationId !== state.invocationId ||
        state.request.event.playerId !== state.requesterId || state.request.replyAudience.kind !== 'player' ||
        state.request.replyAudience.playerId !== state.audiencePlayerId || state.request.event.serverEpochId !== state.serverEpochId ||
        state.request.event.capturedAtMonotonicNanos !== state.capturedAtMonotonicNanos ||
        state.request.event.eventOrigin !== state.origin || state.peer.cancelledRequests.has(state.requestId) ||
        state.peer.abilityContexts.get(state.requestId) !== handle ||
        expected.request !== undefined && expected.request !== state.request ||
        expected.audiencePlayerId !== undefined && expected.audiencePlayerId !== state.audiencePlayerId ||
        expected.binding !== undefined && (expected.binding.serverId !== state.binding.serverId ||
          expected.binding.worldId !== state.binding.worldId || expected.binding.characterId !== state.binding.characterId ||
          expected.binding.agentId !== state.binding.agentId)) return false;
    return state.isLive();
  } catch {
    return false;
  }
}

/** Checked consumption of the owner-private policy snapshot; callers cannot supply policy or requester facts. */
export function consumeCurrentAbilityPolicy(
  handle: AbilityTrustedContextHandle | null,
  expected: AbilityContextExpectation,
  intent: ValidatedAbilityInput,
): 'stale' | 'denied' | 'conditional' {
  try {
    if (expected.request === undefined || expected.binding === undefined || expected.audiencePlayerId === undefined ||
        !isCurrentAbilityContext(handle, expected)) return 'stale';
    const state = ownedAbilityContexts.get(handle as object);
    if (!state) return 'stale';
    const abilityId = intent.abilityId;
    const resolution = resolveAbilityPolicy({
      binding: state.binding,
      policy: state.policy,
      policyRevision: state.policy.revision,
      authorityCurrent: true,
      requesterId: state.requesterId,
      invocationId: state.invocationId,
      abilityId,
      contractVersion: intent.contractVersion,
      targetPlayerId: intent.arguments.targetPlayerId,
      ...(abilityId === STRIKE_LIGHTNING_ABILITY
        ? { visualOnly: intent.arguments.visualOnly }
        : {}),
    });
    if (resolution.reasonCode === 'stale_context') return 'stale';
    return resolution.status === 'DENIED' ? 'denied' : 'conditional';
  } catch {
    return 'stale';
  }
}

type SharedWorkState = {
  serialWork: Promise<void>;
  admissionFailed: boolean;
  failureReason: string | null;
};

const sharedWorkStates = new WeakMap<HostWorkBudget, SharedWorkState>();

export class WorldspeakerTransport {
  private readonly config: FrozenTransportConfig;
  private server: WebSocketServer | undefined;
  private peer: Peer | undefined;
  private peerGeneration = 0;
  private stopped = false;
  private closeTask: Promise<void> | undefined;
  private inboundExceptionDiagnostics = 0;
  private readonly sharedWork: SharedWorkState;

  constructor(
    config: TransportConfig,
    private readonly hostVersion: string,
    private readonly companionVersion: string,
    private readonly handleRequest: DialogueRequestHandler,
    private readonly handleAcknowledgement: DialogueAcknowledgementHandler,
    private readonly onStatus: TransportStatus,
    private readonly monotonicNow: MonotonicNow = systemMonotonicNow,
    private readonly workBudget: HostWorkBudget = new HostWorkBudget(),
  ) {
    this.config = freezeTransportConfig(config);
    this.sharedWork = sharedWorkState(this.workBudget);
  }

  async start(): Promise<void> {
    if (this.stopped) throw new Error('Worldspeaker transport is stopped');
    if (this.server) throw new Error('Worldspeaker transport is already started');
    if (!this.config.enabled) throw new Error('Worldspeaker channel is disabled');
    if (this.config.bindHost !== '127.0.0.1') throw new Error('this candidate supports a loopback listener only');
    if (this.config.deploymentId !== DEPLOYMENT.deploymentId || this.config.manifestSha256 !== DEPLOYMENT.manifestSha256) {
      throw new Error('deployment manifest mismatch');
    }
    this.onStatus('starting', 'WS-COMPAT-COMPANION-STARTING');
    const server = new WebSocketServer({
      host: this.config.bindHost,
      port: this.config.port,
      maxPayload: MAX_FRAME_BYTES,
      perMessageDeflate: false,
      clientTracking: false,
    });
    this.server = server;
    await new Promise<void>((resolve, reject) => {
      const onListening = () => {
        server.off('error', onError);
        resolve();
      };
      const onError = (error: Error) => {
        server.off('listening', onListening);
        reject(error);
      };
      server.once('listening', onListening);
      server.once('error', onError);
    });
    server.on('connection', (socket) => this.acceptConnection(socket));
    this.onStatus('unavailable', this.sharedWork.failureReason ?? 'WS-COMPAT-COMPANION-WAITING-FOR-PEER');
  }

  private acceptConnection(socket: WebSocket): void {
    if (this.stopped || this.peer) {
      socket.close(1013, 'one bound peer is allowed');
      return;
    }
    if (this.peerGeneration >= Number.MAX_SAFE_INTEGER) {
      socket.close(1011, 'peer generation exhausted');
      return;
    }
    const challenge = randomBytes(32).toString('base64url');
    const facts = welcomeFacts(this.config.binding, this.config.manifestSha256, this.config.configSetSha256);
    const peer: Peer = {
      generation: ++this.peerGeneration,
      socket,
      challenge,
      handshakeDeadline: this.monotonicNow() + HANDSHAKE_TIMEOUT_MS,
      ready: false,
      lastHeartbeatAt: this.monotonicNow(),
      lastHeartbeatSequence: 0,
      requests: new Map(),
      requestDeadlines: new Map(),
      acknowledgementTargets: new Map(),
      acknowledgementIds: new Map(),
      progressIds: new Map(),
      progressCounts: new Map(),
      retainedInvocations: new Map(),
      retainedEvents: new Map(),
      retainedRequests: new Map(),
      retainedRequestAttribution: new Map(),
      retainedInvocationAttribution: new Map(),
      cancelledRequests: new Set(),
      abilityContexts: new Map(),
      handshakeTimer: setTimeout(() => this.rejectPeer(peer, 4001, 'handshake timeout'), HANDSHAKE_TIMEOUT_MS),
    };
    this.peer = peer;
    socket.on('message', (data, isBinary) => this.receive(peer, data, isBinary));
    socket.on('close', () => this.releasePeer(peer));
    socket.on('error', () => this.releasePeer(peer));
    this.send(peer, {
      type: 'challenge',
      challenge,
      ...facts,
      proof: createProof(this.config.bearerSecret, serverChallengeTranscript(challenge, facts)),
    });
  }

  private receive(peer: Peer, raw: RawData, isBinary: boolean): void {
    try {
      this.receiveFrame(peer, raw, isBinary);
    } catch (error) {
      if (!(error instanceof ProtocolRejectionError)) this.diagnoseInboundException('receive', error);
      this.rejectPeer(peer, peer.ready ? 4002 : 4001, peer.ready ? 'frame processing rejected' : 'handshake rejected');
    }
  }

  private receiveFrame(peer: Peer, raw: RawData, isBinary: boolean): void {
    if (isBinary) return this.rejectPeer(peer, 4002, 'binary frames are unsupported');
    const frameBytes = rawToBuffer(raw);
    if (frameBytes.byteLength > MAX_FRAME_BYTES) return this.rejectPeer(peer, 4002, 'frame too large');
    let value: unknown;
    try {
      value = parseStrictJson(frameBytes.toString('utf8'));
    } catch {
      return this.rejectPeer(peer, peer.ready ? 4002 : 4001, 'malformed frame');
    }

    if (!peer.ready) {
      this.acceptHello(peer, value);
      return;
    }

    if (value === null || typeof value !== 'object' || Array.isArray(value)) return this.rejectPeer(peer, 4002, 'frame shape mismatch');
    const type = (value as Record<string, unknown>).type;
    if (type === 'heartbeat') {
      try {
        const frame = exactRecord(value, HEARTBEAT_FIELDS);
        if (frame.protocol !== DEPLOYMENT.protocol || !Number.isSafeInteger(frame.sequence) ||
            (frame.sequence as number) <= peer.lastHeartbeatSequence) throw new ProtocolRejectionError('heartbeat rejected');
        peer.lastHeartbeatSequence = frame.sequence as number;
        peer.lastHeartbeatAt = this.monotonicNow();
        this.send(peer, { type: 'heartbeat.ack', protocol: DEPLOYMENT.protocol, sequence: frame.sequence });
      } catch (error) {
        if (!(error instanceof ProtocolRejectionError)) this.diagnoseInboundException('heartbeat', error);
        this.rejectPeer(peer, 4002, 'heartbeat rejected');
      }
      return;
    }

    if (type === 'dialogue.ack') {
      let acknowledgementFrame: DialogueAcknowledgementFrame;
      try {
        acknowledgementFrame = validateDialogueAcknowledgement(value);
      } catch (error) {
        if (!(error instanceof ProtocolRejectionError)) this.diagnoseInboundException('acknowledgement', error);
        return this.rejectPeer(peer, 4002, 'acknowledgement rejected');
      }
      this.pruneAcknowledgementTargets(peer);
      const liveTarget = peer.acknowledgementTargets.get(acknowledgementFrame.requestId);
      if (!liveTarget) return;
      if (liveTarget.request.invocationId !== acknowledgementFrame.invocationId ||
          liveTarget.request.event.playerId !== acknowledgementFrame.playerId) {
        return this.rejectPeer(peer, 4002, 'acknowledgement attribution mismatch');
      }
      const priorMessageId = peer.acknowledgementIds.get(acknowledgementFrame.requestId);
      if (priorMessageId !== undefined) {
        if (priorMessageId !== acknowledgementFrame.acknowledgement.messageId) {
          this.rejectPeer(peer, 4002, 'acknowledgement replay rejected');
        }
        return;
      }
      const release = this.workBudget.tryAcquire('acknowledgement');
      if (!release) {
        this.failContextUpdates();
        return;
      }
      peer.acknowledgementIds.set(acknowledgementFrame.requestId, acknowledgementFrame.acknowledgement.messageId);
      const targetSnapshot = liveTarget.request;
      const acknowledgementSnapshot = snapshotAcknowledgement(acknowledgementFrame.acknowledgement);
      void this.queueSharedWork(async () => {
        try {
          await this.runAcknowledgementHandler(targetSnapshot, acknowledgementSnapshot);
        } finally {
          release();
        }
      })
        .catch(() => undefined);
      return;
    }

    if (type === 'dialogue.progress') {
      const retainedRequest = this.getRetainedProgressRequest(peer, value);
      if (!retainedRequest) return;
      let progressFrame: DialogueProgressFrame;
      try {
        progressFrame = validateDialogueProgress(value, retainedRequest);
      } catch (error) {
        if (!(error instanceof ProtocolRejectionError)) this.diagnoseInboundException('progress', error);
        return this.rejectPeer(peer, 4002, 'progress rejected');
      }
      const signature = progressIdentity(progressFrame.requestId, progressFrame.playerId, progressFrame.progress);
      const prior = peer.progressIds.get(progressFrame.progress.messageId);
      if (prior !== undefined) {
        if (prior !== signature) return this.rejectPeer(peer, 4002, 'progress message id reuse rejected');
        return;
      }
      const progressCount = peer.progressCounts.get(progressFrame.requestId) ?? retainedRequest.progressContext.length;
      if (progressCount >= 3) return this.rejectPeer(peer, 4002, 'progress limit rejected');
      if (peer.progressIds.size >= MAX_RETAINED_IDENTITIES) {
        this.closeUnavailablePeer(peer, 'WS-COMPAT-IDENTITY-RETENTION-LIMIT');
        return;
      }
      const release = this.workBudget.tryAcquire('acknowledgement');
      if (!release) {
        this.failContextUpdates('WS-COMPAT-PROGRESS-CONTEXT-UPDATE-FAILED');
        return;
      }
      peer.progressIds.set(progressFrame.progress.messageId, signature);
      peer.progressCounts.set(progressFrame.requestId, progressCount + 1);
      const targetSnapshot = contextTarget(retainedRequest);
      const progressSnapshot = snapshotProgress(progressFrame.progress);
      void this.queueSharedWork(async () => {
        try {
          await this.runProgressHandler(targetSnapshot, progressSnapshot);
        } finally {
          release();
        }
      }).catch(() => undefined);
      return;
    }

    if (type === 'dialogue.cancel') {
      let cancellation: DialogueCancelFrame;
      try {
        cancellation = validateDialogueCancel(value);
      } catch (error) {
        if (!(error instanceof ProtocolRejectionError)) this.diagnoseInboundException('cancellation', error);
        return this.rejectPeer(peer, 4002, 'cancellation rejected');
      }
      const target = peer.acknowledgementTargets.get(cancellation.requestId);
      if (target && (target.request.invocationId !== cancellation.invocationId ||
          target.request.event.playerId !== cancellation.playerId)) {
        return this.rejectPeer(peer, 4002, 'cancellation attribution mismatch');
      }
      const request = peer.requests.get(cancellation.requestId);
      if (request && (request.invocationId !== cancellation.invocationId ||
          request.event.playerId !== cancellation.playerId)) {
        return this.rejectPeer(peer, 4002, 'cancellation attribution mismatch');
      }
      const retainedRequest = peer.retainedRequestAttribution.get(cancellation.requestId);
      if (retainedRequest && (retainedRequest.invocationId !== cancellation.invocationId ||
          retainedRequest.playerId !== cancellation.playerId)) {
        return this.rejectPeer(peer, 4002, 'cancellation attribution mismatch');
      }
      const retainedInvocation = peer.retainedInvocationAttribution.get(cancellation.invocationId);
      if (retainedInvocation && (retainedInvocation.requestId !== cancellation.requestId ||
          retainedInvocation.playerId !== cancellation.playerId)) {
        return this.rejectPeer(peer, 4002, 'cancellation attribution mismatch');
      }
      if (!retainedRequest && !retainedInvocation && !request && !target) return;
      if (!retainedRequest || !retainedInvocation) return this.rejectPeer(peer, 4002, 'cancellation identity unavailable');
      if (peer.cancelledRequests.has(cancellation.requestId)) return;
      peer.cancelledRequests.add(cancellation.requestId);
      peer.requests.delete(cancellation.requestId);
      peer.abilityContexts.delete(cancellation.requestId);
      peer.requestDeadlines.delete(cancellation.requestId);
      peer.acknowledgementTargets.delete(cancellation.requestId);
      peer.acknowledgementIds.delete(cancellation.requestId);
      peer.progressCounts.delete(cancellation.requestId);
      return;
    }

    if (type !== 'dialogue.request') return this.rejectPeer(peer, 4002, 'unsupported message type');
    let request: DialogueRequest;
    try {
      request = freezeDialogueRequest(validateDialogueRequest(value, this.config.binding));
    } catch (error) {
      if (!(error instanceof ProtocolRejectionError)) this.diagnoseInboundException('request', error);
      return this.rejectPeer(peer, 4002, 'dialogue request rejected');
    }
    if (request.abilityPolicyRevision !== this.config.abilityPolicy.revision) {
      return this.rejectPeer(peer, 4002, 'dialogue policy revision rejected');
    }
    try {
      // Pure metadata preflight only: it runs before identity retention/host-work admission and does not mint authority.
      buildAbilityDiscovery({
        binding: this.config.binding,
        request,
        policy: this.config.abilityPolicy,
        isLive: () => true,
      });
    } catch (error) {
      if (error instanceof Error && error.message.includes('complete metadata byte ceiling')) {
        this.sendFailure(peer, request);
        return;
      }
      this.closeUnavailablePeer(peer, 'WS-COMPAT-ABILITY-DISCOVERY-UNAVAILABLE');
      return;
    }
    const identity = this.checkAndRememberRequest(peer, request);
    if (identity === 'capacity') {
      this.closeUnavailablePeer(peer, 'WS-COMPAT-IDENTITY-RETENTION-LIMIT');
      return;
    }
    if (identity === 'conflict') return this.rejectPeer(peer, 4002, 'dialogue identity reuse rejected');
    if (identity === 'duplicate') {
      if (!peer.cancelledRequests.has(request.requestId)) this.sendFailure(peer, request);
      return;
    }
    if (peer.requests.has(request.requestId) || peer.requests.size >= 16) {
      this.closeUnavailablePeer(peer, 'WS-COMPAT-HOST-WORK-LIMIT');
      return;
    }
    const releaseHost = this.workBudget.tryAcquire('host');
    if (!releaseHost) {
      this.closeUnavailablePeer(peer, 'WS-COMPAT-HOST-WORK-LIMIT');
      return;
    }
    const progressIdentityResult = this.rememberRequestProgress(peer, request);
    if (progressIdentityResult !== 'accepted') {
      releaseHost();
      if (progressIdentityResult === 'capacity') {
        return this.closeUnavailablePeer(peer, 'WS-COMPAT-IDENTITY-RETENTION-LIMIT');
      }
      return this.rejectPeer(peer, 4002, 'progress identity reuse rejected');
    }
    peer.requests.set(request.requestId, request);
    peer.requestDeadlines.set(request.requestId, this.monotonicNow() + 30_000);
    const target = this.rememberAcknowledgementTarget(peer, request);
    const acknowledgement = request.acknowledgement;
    if (acknowledgement !== null) peer.acknowledgementIds.set(request.requestId, acknowledgement.messageId);
    this.queueSharedWork(() => this.runRequest(peer, request, target, releaseHost));
  }

  private async runRequest(
    peer: Peer,
    request: DialogueRequest,
    acknowledgementTarget: { request: DialogueContextTarget; expiresAt: number } | undefined,
    releaseHost: () => void,
  ): Promise<void> {
    const isLive = () => this.isLive(peer, request);
    try {
      // An embedded ACK is accepted with the request and owns an immutable context
      // snapshot. Dispatch that accepted update even if cancellation retires the
      // substantive request while it waits behind earlier shared work.
      if (request.acknowledgement !== null) {
        const releaseAcknowledgement = this.workBudget.tryAcquire('acknowledgement');
        if (!releaseAcknowledgement) {
          this.failContextUpdates();
          this.onStatus('unavailable', 'WS-COMPAT-HOST-WORK-LIMIT');
          throw new Error('upstream acknowledgement work limit reached');
        }
        try {
          const targetSnapshot = acknowledgementTarget?.request;
          const acknowledgement = targetSnapshot?.acknowledgement;
          if (!targetSnapshot || !acknowledgement) throw new Error('acknowledgement snapshot missing');
          await this.runAcknowledgementHandler(targetSnapshot, acknowledgement);
        } finally {
          releaseAcknowledgement();
        }
      }
      if (!isLive()) return;
      const abilityContext = this.issueAbilityContext(peer, request, isLive);
      if (abilityContext === null) {
        if (isLive()) this.sendFailure(peer, request);
        return;
      }
      for (const progress of request.progressContext) {
        const releaseProgress = this.workBudget.tryAcquire('acknowledgement');
        if (!releaseProgress) {
          this.failContextUpdates('WS-COMPAT-PROGRESS-CONTEXT-UPDATE-FAILED');
          this.onStatus('unavailable', 'WS-COMPAT-HOST-WORK-LIMIT');
          throw new Error('upstream progress context work limit reached');
        }
        try {
          const targetSnapshot = acknowledgementTarget?.request ?? contextTarget(request);
          await this.runProgressHandler(targetSnapshot, snapshotProgress(progress));
        } finally {
          releaseProgress();
        }
      }
      if (this.sharedWork.admissionFailed) {
        if (isLive()) this.closeUnavailablePeer(peer,
          this.sharedWork.failureReason ?? 'WS-COMPAT-ACK-CONTEXT-UPDATE-FAILED');
        return;
      }
      if (!isLive()) return;
       let admittedDiscovery: AbilityDiscovery;
       try {
         admittedDiscovery = buildAbilityDiscovery({
           binding: this.config.binding,
           request,
           policy: this.config.abilityPolicy,
           isLive,
         }).value;
       } catch {
         if (isLive()) this.sendFailure(peer, request);
         return;
       }
       if (!isLive()) return;
       await this.handleRequest(request, (text) => this.sendAnswer(peer, request, text), isLive,
          admittedDiscovery, abilityContext);
      if (isLive()) this.sendFailure(peer, request);
    } catch (error) {
      if (error instanceof RequestLocalDialogueFailure) {
        if (isLive()) this.sendFailure(peer, request);
      } else if (isLive()) {
        this.failContextUpdates('WS-COMPAT-HOST-REQUEST-FAILED');
      }
    } finally {
      if (peer.requests.get(request.requestId) === request) {
        peer.requests.delete(request.requestId);
        peer.requestDeadlines.delete(request.requestId);
        peer.progressCounts.delete(request.requestId);
        peer.abilityContexts.delete(request.requestId);
      }
      releaseHost();
    }
  }

  private isLive(peer: Peer, request: DialogueRequest): boolean {
    const deadline = peer.requestDeadlines.get(request.requestId);
    return this.peer === peer && peer.ready && peer.requests.get(request.requestId) === request &&
      deadline !== undefined && this.monotonicNow() < deadline;
  }

  /** Issuance is deliberately private and follows authenticated validation plus retained membership. */
  private issueAbilityContext(peer: Peer, request: DialogueRequest, isLive: () => boolean): AbilityTrustedContextHandle | null {
    if (!isLive() || peer.requests.get(request.requestId) !== request || peer.abilityContexts.size >= 16 ||
        request.event.eventOrigin !== 'live' || request.replyAudience.kind !== 'player' ||
        request.replyAudience.playerId !== request.event.playerId ||
        request.abilityPolicyRevision !== this.config.abilityPolicy.revision) return null;
    const handle = Object.freeze(Object.create(null)) as object;
    const config = this.config;
    const contextIsLive = () => this.config === config && this.config.configSetSha256 === config.configSetSha256 &&
      this.config.abilityPolicy === config.abilityPolicy && this.peer === peer && peer.ready && isLive() &&
      peer.requests.get(request.requestId) === request && peer.abilityContexts.get(request.requestId) === handle;
    const state: OwnedAbilityContext = Object.freeze({
      config,
      configSetSha256: config.configSetSha256,
      policy: config.abilityPolicy,
      binding: Object.freeze({ ...config.binding }),
      peer,
      peerGeneration: peer.generation,
      request,
      requestId: request.requestId,
      invocationId: request.invocationId,
      requesterId: request.event.playerId,
      audiencePlayerId: request.replyAudience.playerId,
      serverEpochId: request.event.serverEpochId,
      capturedAtMonotonicNanos: request.event.capturedAtMonotonicNanos,
      origin: 'live',
      isLive: contextIsLive,
    });
    ownedAbilityContexts.set(handle, state);
    peer.abilityContexts.set(request.requestId, handle);
    return handle;
  }

  private getRetainedProgressRequest(peer: Peer, value: unknown): DialogueRequest | undefined {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return undefined;
    const requestId = (value as Record<string, unknown>).requestId;
    return typeof requestId === 'string' ? peer.requests.get(requestId) : undefined;
  }

  private rememberRequestProgress(peer: Peer, request: DialogueRequest): 'accepted' | 'conflict' | 'capacity' {
    const pending: Array<[string, string]> = [];
    const pendingIds = new Set<string>();
    for (const progress of request.progressContext) {
      const signature = progressIdentity(request.requestId, request.event.playerId, progress);
      const previous = peer.progressIds.get(progress.messageId);
      if (previous !== undefined && previous !== signature) return 'conflict';
      if (previous !== undefined) return 'conflict';
      if (pendingIds.has(progress.messageId)) return 'conflict';
      pendingIds.add(progress.messageId);
      pending.push([progress.messageId, signature]);
    }
    if (peer.progressIds.size + pending.length > MAX_RETAINED_IDENTITIES) return 'capacity';
    for (const [messageId, signature] of pending) peer.progressIds.set(messageId, signature);
    peer.progressCounts.set(request.requestId, request.progressContext.length);
    return 'accepted';
  }

  /** Serializes ACK context and substantive work across peers sharing the account session. */
  private queueSharedWork(operation: () => Promise<void> | void): Promise<void> {
    const next = this.sharedWork.serialWork.then(async () => { await operation(); });
    this.sharedWork.serialWork = next.catch(() => undefined);
    return next;
  }

  private checkAndRememberRequest(peer: Peer, request: DialogueRequest): 'new' | 'duplicate' | 'conflict' | 'capacity' {
    const batch = [...request.contextBatch, request.event];
    for (const event of batch) {
      const previous = peer.retainedEvents.get(event.eventId);
      if (previous !== undefined && previous !== WorldspeakerTransport.canonicalEventIdentity(event)) return 'conflict';
    }
    const priorEvent = peer.retainedEvents.get(request.event.eventId);
    const primarySignature = WorldspeakerTransport.canonicalEventIdentity(request.event);
    const invocationSignature = `${request.event.eventId}:${primarySignature}`;
    const requestSignature = `${request.invocationId}:${primarySignature}`;
    const priorInvocation = peer.retainedInvocations.get(request.invocationId);
    const priorRequest = peer.retainedRequests.get(request.requestId);
    if (priorInvocation !== undefined && priorInvocation !== invocationSignature ||
        priorRequest !== undefined && priorRequest !== requestSignature) return 'conflict';
    if (priorEvent !== undefined || priorInvocation !== undefined || priorRequest !== undefined) return 'duplicate';
    const uniqueEvents = new Map<string, string>();
    for (const event of batch) uniqueEvents.set(event.eventId, WorldspeakerTransport.canonicalEventIdentity(event));
    const freshEventCount = [...uniqueEvents.keys()].filter((eventId) => !peer.retainedEvents.has(eventId)).length;
    if (peer.retainedEvents.size + freshEventCount > MAX_RETAINED_IDENTITIES ||
        peer.retainedInvocations.size >= MAX_RETAINED_IDENTITIES ||
        peer.retainedRequests.size >= MAX_RETAINED_IDENTITIES ||
        peer.retainedRequestAttribution.size >= MAX_RETAINED_IDENTITIES ||
        peer.retainedInvocationAttribution.size >= MAX_RETAINED_IDENTITIES) return 'capacity';
    for (const [eventId, signature] of uniqueEvents) peer.retainedEvents.set(eventId, signature);
    peer.retainedInvocations.set(request.invocationId, invocationSignature);
    peer.retainedRequests.set(request.requestId, requestSignature);
    peer.retainedRequestAttribution.set(request.requestId, {
      invocationId: request.invocationId, playerId: request.event.playerId,
    });
    peer.retainedInvocationAttribution.set(request.invocationId, {
      requestId: request.requestId, playerId: request.event.playerId,
    });
    return 'new';
  }

  private static canonicalEventIdentity(event: ContextEvent): string {
    const fields = 'eventKind' in event ? ATTENTION_EVENT_FIELDS : DIALOGUE_EVENT_FIELDS;
    const record = event as unknown as Record<string, unknown>;
    const typedFields = fields.map((field) => {
      const value = record[field];
      if (typeof value === 'string') return [field, 'string', value] as const;
      if (typeof value === 'number') {
        return [field, 'number', Object.is(value, -0) ? '-0' : value.toString()] as const;
      }
      if (field === 'subject' && 'eventKind' in event) {
        const subject = event.subject;
        return [field, 'subject', subject.playerId, subject.playerName] as const;
      }
      if (field === 'details' && 'eventKind' in event) {
        const details = event.details;
        return [field, 'details', ...['originDimension', 'copiedAlive', 'basis'].map((key) =>
          Object.prototype.hasOwnProperty.call(details, key) ? [key, details[key]] : [key, null])];
      }
      throw new Error('unsupported dialogue event identity field');
    });
    return JSON.stringify(typedFields);
  }

  private async runAcknowledgementHandler(request: DialogueContextTarget, acknowledgement: DialogueAcknowledgement): Promise<void> {
    try {
      await this.handleAcknowledgement(request, acknowledgement);
    } catch {
      this.failContextUpdates();
      throw new Error('acknowledgement context update failed');
    }
  }

  private async runProgressHandler(request: DialogueContextTarget, progress: DialogueProgress): Promise<void> {
    try {
      await this.handleAcknowledgement(request, progress);
    } catch {
      this.failContextUpdates('WS-COMPAT-PROGRESS-CONTEXT-UPDATE-FAILED');
      throw new Error('progress context update failed');
    }
  }

  private failContextUpdates(reason = 'WS-COMPAT-ACK-CONTEXT-UPDATE-FAILED'): void {
    if (this.sharedWork.admissionFailed) return;
    this.sharedWork.admissionFailed = true;
    this.sharedWork.failureReason = reason;
    const peer = this.peer;
    if (peer) this.closeUnavailablePeer(peer, reason);
    else this.onStatus('unavailable', reason);
  }

  private rememberAcknowledgementTarget(
    peer: Peer,
    request: DialogueRequest,
  ): { request: DialogueContextTarget; expiresAt: number } {
    this.pruneAcknowledgementTargets(peer);
    while (peer.acknowledgementTargets.size >= MAX_ACKNOWLEDGEMENT_TARGETS) {
      const oldestRequestId = peer.acknowledgementTargets.keys().next().value as string | undefined;
      if (oldestRequestId === undefined) break;
      peer.acknowledgementTargets.delete(oldestRequestId);
      peer.acknowledgementIds.delete(oldestRequestId);
    }
    const target = {
      request: contextTarget(request),
      expiresAt: this.monotonicNow() + ACKNOWLEDGEMENT_TARGET_TTL_MS,
    };
    peer.acknowledgementTargets.set(request.requestId, target);
    return target;
  }

  private pruneAcknowledgementTargets(peer: Peer, now = this.monotonicNow()): void {
    for (const [requestId, target] of peer.acknowledgementTargets) {
      if (target.expiresAt <= now) {
        peer.acknowledgementTargets.delete(requestId);
        peer.acknowledgementIds.delete(requestId);
      }
    }
  }

  private acceptHello(peer: Peer, value: unknown): void {
    try {
      if (this.monotonicNow() >= peer.handshakeDeadline) throw new ProtocolRejectionError('handshake deadline expired');
      const hello = validateClientHello(
        value, this.config.bearerSecret, this.config.binding, this.config.manifestSha256,
        this.config.configSetSha256, peer.challenge,
      );
      const facts = welcomeFacts(this.config.binding, this.config.manifestSha256, this.config.configSetSha256);
      if (facts.hostVersion !== this.hostVersion || facts.companionVersion !== this.companionVersion || facts.nodeVersion !== process.versions.node) {
        throw new ProtocolRejectionError('installed host/runtime mismatch');
      }
      if (this.sharedWork.admissionFailed) {
        this.closeUnavailablePeer(peer,
          this.sharedWork.failureReason ?? 'WS-COMPAT-ACK-CONTEXT-UPDATE-FAILED');
        return;
      }
      if (this.hostWorkSaturated()) {
        this.closeUnavailablePeer(peer, 'WS-COMPAT-HOST-WORK-LIMIT');
        return;
      }
      const welcome = {
        type: 'welcome',
        challenge: peer.challenge,
        nonce: hello.nonce,
        ...facts,
        proof: createProof(this.config.bearerSecret, serverTranscript(peer.challenge, hello.nonce, facts)),
      };
      clearTimeout(peer.handshakeTimer);
      peer.ready = true;
      peer.lastHeartbeatAt = this.monotonicNow();
      this.send(peer, welcome);
      let lastPingAt = this.monotonicNow();
      peer.heartbeatTimer = setInterval(() => {
        const now = this.monotonicNow();
        this.expireRequests(peer, now);
        if (now - peer.lastHeartbeatAt > 10_000) {
          this.rejectPeer(peer, 4003, 'peer health expired');
          return;
        }
        if (now - lastPingAt >= 5000 && peer.socket.readyState === WebSocket.OPEN) {
          lastPingAt = now;
          peer.socket.ping();
        }
      }, 1000);
      this.onStatus('ready', 'WS-COMPAT-READY');
    } catch (error) {
      if (!(error instanceof ProtocolRejectionError)) this.diagnoseInboundException('handshake', error);
      this.rejectPeer(peer, 4001, 'handshake rejected');
      this.onStatus('unavailable', 'WS-COMPAT-HANDSHAKE-REJECTED');
    }
  }

  /** Writes at most eight fixed-format local diagnostics per transport lifetime. */
  private diagnoseInboundException(stage: 'receive' | 'heartbeat' | 'acknowledgement' | 'progress' | 'cancellation' | 'request' | 'handshake', error: unknown): void {
    if (this.inboundExceptionDiagnostics >= 8) return;
    this.inboundExceptionDiagnostics++;
    const errorKind = error instanceof TypeError ? 'TypeError' : error instanceof RangeError ? 'RangeError' :
      error instanceof Error ? 'Error' : 'Unknown';
    try {
      process.stderr.write(`[worldspeaker] inbound ${stage} exception (${errorKind}); peer refused\n`);
    } catch {
      // Diagnostics must never become a second failure at the socket boundary.
    }
  }

  private sendAnswer(peer: Peer, request: DialogueRequest, text: string): string | null {
    if (!this.isLive(peer, request) ||
        typeof text !== 'string' || text.trim().length === 0 || text.length > 2048 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(text)) return null;
    const messageId = randomUUID();
    const sent = this.send(peer, {
      type: 'dialogue.message',
      protocol: DEPLOYMENT.protocol,
      messageId,
      requestId: request.requestId,
      invocationId: request.invocationId,
      characterId: request.characterId,
      replyAudience: request.replyAudience,
      kind: 'answer',
      text,
    });
    if (!sent) return null;
    peer.requests.delete(request.requestId);
    peer.requestDeadlines.delete(request.requestId);
    peer.abilityContexts.delete(request.requestId);
    return messageId;
  }

  /** Generic sends are disabled; only the retained request-owned dispatcher may publish. */
  sendTextForTarget(target: string, text: string): string | null {
    void target;
    void text;
    return null;
  }

  private sendFailure(peer: Peer, request: DialogueRequest): void {
    if (this.peer !== peer || !peer.ready) return;
    this.send(peer, {
      type: 'dialogue.failure',
      protocol: DEPLOYMENT.protocol,
      requestId: request.requestId,
      invocationId: request.invocationId,
      characterId: request.characterId,
      replyAudience: request.replyAudience,
      reason: 'unavailable',
    });
  }

  private send(peer: Peer, frame: Record<string, unknown>): boolean {
    if (this.peer !== peer || peer.socket.readyState !== WebSocket.OPEN) return false;
    const serialized = JSON.stringify(frame);
    const bytes = Buffer.byteLength(serialized, 'utf8');
    if (bytes > MAX_FRAME_BYTES || peer.socket.bufferedAmount + bytes > MAX_FRAME_BYTES * 16) return false;
    peer.socket.send(serialized, { compress: false }, (error) => {
      if (error) this.releasePeer(peer);
    });
    return true;
  }

  private rejectPeer(peer: Peer, code: number, reason: string): void {
    if (this.peer !== peer) return;
    clearTimeout(peer.handshakeTimer);
    if (peer.heartbeatTimer) clearInterval(peer.heartbeatTimer);
    peer.ready = false;
    peer.socket.close(code, reason);
    this.releasePeer(peer);
  }

  private closeUnavailablePeer(peer: Peer, reason: string): void {
    if (this.peer !== peer) return;
    clearTimeout(peer.handshakeTimer);
    if (peer.heartbeatTimer) clearInterval(peer.heartbeatTimer);
    peer.ready = false;
    try {
      peer.socket.close(1011, 'service unavailable');
    } finally {
      this.releasePeer(peer, reason);
    }
  }

  private hostWorkSaturated(): boolean {
    return this.workBudget.snapshot().hostCalls >= this.workBudget.maxHostCalls;
  }

  private releasePeer(peer: Peer, unavailableReason = 'WS-COMPAT-COMPANION-DISCONNECTED'): void {
    if (this.peer !== peer) return;
    clearTimeout(peer.handshakeTimer);
    if (peer.heartbeatTimer) clearInterval(peer.heartbeatTimer);
    peer.ready = false;
    peer.requests.clear();
    peer.abilityContexts.clear();
    peer.requestDeadlines.clear();
    peer.acknowledgementTargets.clear();
    peer.acknowledgementIds.clear();
    peer.progressIds.clear();
    peer.progressCounts.clear();
    peer.retainedInvocations.clear();
    peer.retainedEvents.clear();
    peer.retainedRequests.clear();
    peer.retainedRequestAttribution.clear();
    peer.retainedInvocationAttribution.clear();
    peer.cancelledRequests.clear();
    this.peer = undefined;
    if (!this.stopped) this.onStatus('unavailable', unavailableReason);
  }

  close(): Promise<void> {
    return this.closeTask ??= this.closeOwnedResources();
  }

  private async closeOwnedResources(): Promise<void> {
    this.stopped = true;
    if (this.peer) {
      const peer = this.peer;
      clearTimeout(peer.handshakeTimer);
      if (peer.heartbeatTimer) clearInterval(peer.heartbeatTimer);
      peer.ready = false;
      peer.requests.clear();
      peer.abilityContexts.clear();
      peer.requestDeadlines.clear();
      peer.acknowledgementTargets.clear();
      peer.acknowledgementIds.clear();
      peer.progressIds.clear();
      peer.progressCounts.clear();
      peer.retainedInvocations.clear();
      peer.retainedEvents.clear();
      peer.retainedRequests.clear();
      peer.retainedRequestAttribution.clear();
      peer.retainedInvocationAttribution.clear();
      peer.cancelledRequests.clear();
      peer.socket.terminate();
      this.releasePeer(peer);
    }
    const server = this.server;
    this.server = undefined;
    if (server) await new Promise<void>((resolve) => server.close(() => resolve()));
  }

  private expireRequests(peer: Peer, now: number): void {
    this.pruneAcknowledgementTargets(peer, now);
    for (const [requestId, deadline] of peer.requestDeadlines) {
      if (deadline > now) continue;
      peer.requestDeadlines.delete(requestId);
      peer.requests.delete(requestId);
      peer.acknowledgementTargets.delete(requestId);
      peer.acknowledgementIds.delete(requestId);
      peer.progressCounts.delete(requestId);
    }
  }
}

function contextTarget(request: DialogueRequest): DialogueContextTarget {
  const contextBatch = Object.freeze(request.contextBatch.map((event) => Object.freeze({ ...event })));
  return Object.freeze({
    ...request,
    event: Object.freeze({ ...request.event }),
    conversation: Object.freeze({ ...request.conversation }),
    replyAudience: Object.freeze({ ...request.replyAudience }),
    contextBatch,
    acknowledgement: request.acknowledgement === null ? null : snapshotAcknowledgement(request.acknowledgement),
    progressContext: Object.freeze(request.progressContext.map(snapshotProgress)),
  });
}

function freezeDialogueRequest(request: DialogueRequest): DialogueRequest {
  const freeze = (value: unknown): void => {
    if (value === null || typeof value !== 'object' || Object.isFrozen(value)) return;
    for (const child of Object.values(value as Record<string, unknown>)) freeze(child);
    Object.freeze(value);
  };
  freeze(request);
  return request;
}

type TransportConfigErrorMessage = 'invalid transport configuration' | 'invalid server endpoint' |
  'invalid scoped secret' | 'invalid transport binding';

class TransportConfigError extends Error {
  constructor(message: TransportConfigErrorMessage) {
    super(message);
    this.name = 'TransportConfigError';
  }
}

const INVALID_CONFIG = Symbol('invalid transport configuration');
const INVALID_ENDPOINT = Symbol('invalid server endpoint');
const INVALID_SECRET = Symbol('invalid scoped secret');
const INVALID_BINDING = Symbol('invalid transport binding');

const TRANSPORT_CONFIG_FIELDS = [
  'enabled', 'bindHost', 'port', 'serverEndpoint', 'bearerSecret', 'deploymentId',
  'manifestSha256', 'configurationHandshakeVersion', 'configSetSha256', 'abilityPolicy', 'binding',
] as const;
const REQUIRED_TRANSPORT_CONFIG_FIELDS = TRANSPORT_CONFIG_FIELDS.filter((field) => field !== 'abilityPolicy');
const BINDING_FIELDS = ['agentId', 'characterId', 'serverId', 'worldId'] as const;
const MAX_SNAPSHOT_DEPTH = 16;
const MAX_SNAPSHOT_PROPERTIES = 256;
const MAX_SNAPSHOT_ARRAY_LENGTH = 64;

function freezeTransportConfig(value: TransportConfig): FrozenTransportConfig {
  let snapshot: unknown;
  try {
    snapshot = snapshotConfigData(value);
  } catch {
    // Descriptor/proxy failures are intentionally replaced with a fixed, value-free error.
    throw new TransportConfigError('invalid transport configuration');
  }

  try {
    return validateTransportConfigSnapshot(snapshot);
  } catch (error) {
    if (error === INVALID_ENDPOINT) throw new TransportConfigError('invalid server endpoint');
    if (error === INVALID_SECRET) throw new TransportConfigError('invalid scoped secret');
    if (error === INVALID_BINDING) throw new TransportConfigError('invalid transport binding');
    throw new TransportConfigError('invalid transport configuration');
  }
}

/** Capture only own data descriptors into deeply frozen, null-prototype record copies. */
function snapshotConfigData(value: unknown): unknown {
  return snapshotDataValue(value, new WeakMap<object, unknown>(), new WeakSet<object>(), 0);
}

function snapshotDataValue(
  value: unknown,
  snapshots: WeakMap<object, unknown>,
  active: WeakSet<object>,
  depth: number,
): unknown {
  if (value === null || typeof value === 'string' || typeof value === 'number' ||
      typeof value === 'boolean' || typeof value === 'undefined') return value;
  if (typeof value !== 'object' || depth >= MAX_SNAPSHOT_DEPTH) throw INVALID_CONFIG;

  const source = value as object;
  if (active.has(source)) throw INVALID_CONFIG;
  if (snapshots.has(source)) return snapshots.get(source);

  active.add(source);
  try {
    const isArray = Array.isArray(value);
    const prototype = Object.getPrototypeOf(source);
    if (isArray) {
      if (prototype !== Array.prototype) throw INVALID_CONFIG;
      const descriptors = snapshotOwnDataDescriptors(source, Reflect.ownKeys(source));
      const lengthDescriptor = descriptors.get('length');
      const length = lengthDescriptor?.value;
      if (typeof length !== 'number' || !Number.isSafeInteger(length) || length < 0 ||
          length > MAX_SNAPSHOT_ARRAY_LENGTH || descriptors.size !== length + 1) throw INVALID_CONFIG;

      const copy: unknown[] = new Array(length);
      snapshots.set(source, copy);
      for (let index = 0; index < length; index++) {
        const descriptor = descriptors.get(String(index));
        if (!descriptor) throw INVALID_CONFIG;
        copy[index] = snapshotDataValue(descriptor.value, snapshots, active, depth + 1);
      }
      return Object.freeze(copy);
    }

    if (prototype !== Object.prototype && prototype !== null) throw INVALID_CONFIG;
    const descriptors = snapshotOwnDataDescriptors(source, Reflect.ownKeys(source));
    const copy = Object.create(null) as Record<string, unknown>;
    snapshots.set(source, copy);
    for (const [key, descriptor] of descriptors) {
      Object.defineProperty(copy, key, {
        value: snapshotDataValue(descriptor.value, snapshots, active, depth + 1),
        enumerable: true,
        writable: false,
        configurable: false,
      });
    }
    return Object.freeze(copy);
  } finally {
    active.delete(source);
  }
}

function snapshotOwnDataDescriptors(source: object, keys: readonly PropertyKey[]): Map<string, PropertyDescriptor> {
  if (keys.length > MAX_SNAPSHOT_PROPERTIES) throw INVALID_CONFIG;
  const descriptors = new Map<string, PropertyDescriptor>();
  for (const key of keys) {
    if (typeof key !== 'string') throw INVALID_CONFIG;
    const descriptor = Object.getOwnPropertyDescriptor(source, key);
    if (!descriptor || !Object.hasOwn(descriptor, 'value')) throw INVALID_CONFIG;
    descriptors.set(key, descriptor);
  }
  return descriptors;
}

function validateTransportConfigSnapshot(snapshot: unknown): FrozenTransportConfig {
  if (snapshot === null || typeof snapshot !== 'object' || Array.isArray(snapshot)) throw INVALID_CONFIG;
  const value = snapshot as Record<string, unknown>;
  if (!hasExactSnapshotFields(value, TRANSPORT_CONFIG_FIELDS, REQUIRED_TRANSPORT_CONFIG_FIELDS)) throw INVALID_CONFIG;

  const enabled = value.enabled;
  const bindHost = value.bindHost;
  const port = value.port;
  const deploymentId = value.deploymentId;
  const manifestSha256 = value.manifestSha256;
  const configurationHandshakeVersion = value.configurationHandshakeVersion;
  const configSetSha256 = value.configSetSha256;
  if (typeof enabled !== 'boolean' || typeof bindHost !== 'string' || bindHost !== '127.0.0.1' ||
      typeof port !== 'number' || !Number.isSafeInteger(port) || port < 1024 || port > 65_535 ||
      typeof deploymentId !== 'string' || deploymentId !== DEPLOYMENT.deploymentId ||
      typeof manifestSha256 !== 'string' || manifestSha256 !== DEPLOYMENT.manifestSha256 ||
      configurationHandshakeVersion !== 1 || typeof configSetSha256 !== 'string' ||
      !/^[a-f0-9]{64}$/u.test(configSetSha256)) throw INVALID_CONFIG;

  const binding = value.binding;
  if (binding === null || typeof binding !== 'object' || Array.isArray(binding)) throw INVALID_BINDING;
  const bindingRecord = binding as Record<string, unknown>;
  if (!hasExactSnapshotFields(bindingRecord, BINDING_FIELDS, BINDING_FIELDS)) throw INVALID_BINDING;
  const serverId = bindingRecord.serverId;
  const worldId = bindingRecord.worldId;
  const characterId = bindingRecord.characterId;
  const agentId = bindingRecord.agentId;
  const bindingIds = [serverId, worldId, characterId, agentId];
  if (!bindingIds.every((id) => typeof id === 'string' && /^[A-Za-z0-9._:-]{1,128}$/u.test(id))) {
    throw INVALID_BINDING;
  }

  const serverEndpoint = value.serverEndpoint;
  validateTransportEndpoint(serverEndpoint);
  const bearerSecret = value.bearerSecret;
  if (typeof bearerSecret !== 'string' || !/^[A-Za-z0-9_-]{43,86}$/u.test(bearerSecret)) throw INVALID_SECRET;
  const secret = Buffer.from(bearerSecret, 'base64url');
  if (secret.length < 32 || secret.length > 64 || secret.toString('base64url') !== bearerSecret) throw INVALID_SECRET;

  const abilityPolicy = value.abilityPolicy === undefined
    ? denyAllAbilityPolicy()
    : canonicalizeAbilityPolicySnapshot(value.abilityPolicy, characterId as string);
  return Object.freeze({
    enabled,
    bindHost,
    port,
    serverEndpoint,
    bearerSecret,
    deploymentId,
    manifestSha256,
    configurationHandshakeVersion: 1,
    configSetSha256,
    abilityPolicy,
    binding: Object.freeze({
      serverId: serverId as string,
      worldId: worldId as string,
      characterId: characterId as string,
      agentId: agentId as string,
    }),
  });
}

function hasExactSnapshotFields(
  value: Record<string, unknown>,
  allowed: readonly string[],
  required: readonly string[],
): boolean {
  const keys = Object.keys(value);
  return required.every((key) => Object.hasOwn(value, key)) &&
    keys.every((key) => allowed.includes(key));
}

function validateTransportEndpoint(value: unknown): asserts value is string {
  if (!isValidEndpointSyntax(value)) throw INVALID_ENDPOINT;
}

function snapshotAcknowledgement(acknowledgement: DialogueAcknowledgement): DialogueAcknowledgement {
  return Object.freeze({
    ...acknowledgement,
    replyAudience: Object.freeze({ ...acknowledgement.replyAudience }),
  });
}

function snapshotProgress(progress: DialogueProgress): DialogueProgress {
  return Object.freeze({
    ...progress,
    replyAudience: Object.freeze({ ...progress.replyAudience }),
  });
}

function progressIdentity(requestId: string, playerId: string, progress: DialogueProgress): string {
  return JSON.stringify([
    requestId, playerId, progress.messageId, progress.text, progress.dispatchedAtUnixMillis,
    progress.dispatchedAtMonotonicNanos, progress.replyAudience.kind, progress.replyAudience.playerId, progress.phase,
  ]);
}

function sharedWorkState(workBudget: HostWorkBudget): SharedWorkState {
  let state = sharedWorkStates.get(workBudget);
  if (!state) {
    state = { serialWork: Promise.resolve(), admissionFailed: false, failureReason: null };
    sharedWorkStates.set(workBudget, state);
  }
  return state;
}

function rawToBuffer(raw: RawData): Buffer {
  if (Buffer.isBuffer(raw)) return raw;
  if (Array.isArray(raw)) return Buffer.concat(raw);
  return Buffer.from(raw as ArrayBuffer);
}
