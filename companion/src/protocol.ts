import { createHmac, timingSafeEqual } from 'node:crypto';
import { DEPLOYMENT } from './generated/deployment.js';
import {
  isEcmaScriptBlank,
  isNonNegativeSafeInteger,
  isSafePublicText as isSafePublicBodyText,
} from './protocol-text.js';

export type Binding = Readonly<{
  serverId: string;
  worldId: string;
  characterId: string;
  agentId: string;
}>;

export type ClientHello = Readonly<{
  type: 'hello';
  protocol: string;
  challenge: string;
  nonce: string;
  deploymentId: string;
  manifestSha256: string;
  minecraftVersion: string;
  fabricLoaderVersion: string;
  fabricApiVersion: string;
  javaMajor: number;
  javaVendor: string;
  javaRuntimeVersion: string;
  serverId: string;
  worldId: string;
  characterId: string;
  agentId: string;
  configurationHandshakeVersion: number;
  configSetSha256: string;
  proof: string;
}>;

export type ServerChallenge = Readonly<{
  type: 'challenge';
  challenge: string;
  proof: string;
} & WelcomeFacts>;

export type ReplyAudience = Readonly<{ kind: 'player'; playerId: string }>;

export type AttributedEvent = Readonly<Binding & {
  eventId: string;
  serverEpochId: string;
  occurredAtUnixMillis: number;
  /** Opaque server monotonic value. Never subtract it from a companion timestamp. */
  capturedAtMonotonicNanos: string;
  eventOrigin: 'live' | 'history';
  inputKind: 'command' | 'natural_address' | 'followup';
  playerId: string;
  playerName: string;
  dimension: string;
  text: string;
}>;

export type AttentionEvent = Readonly<Binding & {
  eventId: string;
  serverEpochId: string;
  occurredAtUnixMillis: number;
  capturedAtMonotonicNanos: string;
  eventOrigin: 'live' | 'history';
  source: 'server_callback' | 'server_sample';
  subject: Readonly<{ playerId: string; playerName: string }>;
  dimension: string;
  eventKind: 'joined' | 'left' | 'died' | 'respawned' | 'dimension_changed' | 'moved';
  details: Readonly<Record<string, string | boolean>>;
}>;

export type ContextEvent = AttributedEvent | AttentionEvent;
export type ContextLossReason = 'epoch_start' | 'expiry' | 'capacity' | 'rate' | 'bytes' | 'frame' | 'transport' | 'source_unavailable';
export type ContextStatus = Readonly<{
  state: 'disabled' | 'bounded' | 'incomplete';
  lossRevision: string;
  reasons: readonly ContextLossReason[];
}>;

export type DialogueAcknowledgement = Readonly<{
  messageId: string;
  text: string;
  dispatchedAtUnixMillis: number;
  dispatchedAtMonotonicNanos: string;
  replyAudience: ReplyAudience;
}>;

export type DialogueProgress = Readonly<DialogueAcknowledgement & {
  phase: 'queued' | 'awaiting_answer';
}>;

export type DialogueRequest = Readonly<{
  type: 'dialogue.request';
  protocol: string;
  requestId: string;
  invocationId: string;
  abilityPolicyRevision: number;
} & Binding & {
  event: AttributedEvent;
  conversation: Readonly<{ conversationId: string; replyToMessageId: string | null }>;
  replyAudience: ReplyAudience;
  contextBatch: readonly ContextEvent[];
  contextStatus: ContextStatus;
  acknowledgement: DialogueAcknowledgement | null;
  progressContext: readonly DialogueProgress[];
}>;

export type DialogueContextTarget = DialogueRequest;

export type DialogueAcknowledgementFrame = Readonly<{
  type: 'dialogue.ack';
  protocol: string;
  requestId: string;
  invocationId: string;
  playerId: string;
  acknowledgement: DialogueAcknowledgement;
}>;

export type DialogueProgressFrame = Readonly<{
  type: 'dialogue.progress';
  protocol: string;
  requestId: string;
  invocationId: string;
  playerId: string;
  progress: DialogueProgress;
}>;

export type DialogueCancelFrame = Readonly<{
  type: 'dialogue.cancel';
  protocol: string;
  requestId: string;
  invocationId: string;
  playerId: string;
}>;

export type DialogueMessage = Readonly<{
  type: 'dialogue.message';
  protocol: string;
  messageId: string;
  requestId: string;
  invocationId: string;
  characterId: string;
  replyAudience: ReplyAudience;
  kind: 'answer';
  text: string;
}>;

export type DialogueFailure = Readonly<{
  type: 'dialogue.failure';
  protocol: string;
  requestId: string;
  invocationId: string;
  characterId: string;
  replyAudience: ReplyAudience;
  reason: 'unavailable';
}>;

export type DialogueResponse = DialogueMessage | DialogueFailure;

export type WelcomeFacts = Readonly<{
  deploymentId: string;
  manifestSha256: string;
  protocol: string;
  hostVersion: string;
  nodeVersion: string;
  companionVersion: string;
  serverId: string;
  worldId: string;
  characterId: string;
  agentId: string;
  configurationHandshakeVersion: 1;
  configSetSha256: string;
}>;

/** A stable, expected protocol rejection (distinct from implementation failures). */
export class ProtocolRejectionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ProtocolRejectionError';
  }
}

export const HELLO_FIELDS = [
  'type', 'protocol', 'challenge', 'nonce', 'deploymentId', 'manifestSha256', 'minecraftVersion',
  'fabricLoaderVersion', 'fabricApiVersion', 'javaMajor', 'javaVendor', 'javaRuntimeVersion',
  'serverId', 'worldId', 'characterId', 'agentId', 'configurationHandshakeVersion', 'configSetSha256', 'proof',
] as const;
export const HEARTBEAT_FIELDS = ['type', 'protocol', 'sequence'] as const;

export const CHALLENGE_FIELDS = [
  'type', 'challenge', 'deploymentId', 'manifestSha256', 'protocol', 'hostVersion', 'nodeVersion',
  'companionVersion', 'serverId', 'worldId', 'characterId', 'agentId', 'configurationHandshakeVersion', 'configSetSha256', 'proof',
] as const;

export const DIALOGUE_EVENT_FIELDS = [
  'serverId', 'worldId', 'characterId', 'agentId', 'eventId', 'serverEpochId', 'occurredAtUnixMillis',
  'capturedAtMonotonicNanos', 'eventOrigin', 'inputKind', 'playerId', 'playerName', 'dimension', 'text',
] as const;
export const ATTENTION_EVENT_FIELDS = [
  'serverId', 'worldId', 'characterId', 'agentId', 'eventId', 'serverEpochId', 'occurredAtUnixMillis',
  'capturedAtMonotonicNanos', 'eventOrigin', 'source', 'subject', 'dimension', 'eventKind', 'details',
] as const;
export const CONTEXT_STATUS_FIELDS = ['state', 'lossRevision', 'reasons'] as const;
export const CONTEXT_LOSS_REASONS = [
  'epoch_start', 'expiry', 'capacity', 'rate', 'bytes', 'frame', 'transport', 'source_unavailable',
] as const;
export const AUDIENCE_FIELDS = ['kind', 'playerId'] as const;
export const ACKNOWLEDGEMENT_FIELDS = [
  'messageId', 'text', 'dispatchedAtUnixMillis', 'dispatchedAtMonotonicNanos', 'replyAudience',
] as const;
export const PROGRESS_FIELDS = [
  'messageId', 'text', 'dispatchedAtUnixMillis', 'dispatchedAtMonotonicNanos', 'replyAudience', 'phase',
] as const;
export const DIALOGUE_REQUEST_FIELDS = [
  'type', 'protocol', 'requestId', 'invocationId', 'abilityPolicyRevision', 'serverId', 'worldId', 'characterId', 'agentId',
  'event', 'conversation', 'replyAudience', 'contextBatch', 'contextStatus', 'acknowledgement', 'progressContext',
] as const;
export const DIALOGUE_ACK_FIELDS = ['type', 'protocol', 'requestId', 'invocationId', 'playerId', 'acknowledgement'] as const;
export const DIALOGUE_PROGRESS_FIELDS = ['type', 'protocol', 'requestId', 'invocationId', 'playerId', 'progress'] as const;
export const DIALOGUE_CANCEL_FIELDS = ['type', 'protocol', 'requestId', 'invocationId', 'playerId'] as const;
export const DIALOGUE_MESSAGE_FIELDS = [
  'type', 'protocol', 'messageId', 'requestId', 'invocationId', 'characterId', 'replyAudience', 'kind', 'text',
] as const;
export const DIALOGUE_FAILURE_FIELDS = [
  'type', 'protocol', 'requestId', 'invocationId', 'characterId', 'replyAudience', 'reason',
] as const;
export const MAX_COMPLETED_ANSWER_UNITS = 1980;
export const MAX_FRAME_BYTES = 16 * 1024;
export const HANDSHAKE_TIMEOUT_MS = 5000;
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const NONCE_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const BINDING_FIELDS = ['serverId', 'worldId', 'characterId', 'agentId'] as const;

export function exactRecord(value: unknown, fields: readonly string[]): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new ProtocolRejectionError('frame must be a JSON object');
  const record = value as Record<string, unknown>;
  const actual = Object.keys(record).sort();
  const expected = [...fields].sort();
  if (actual.length !== expected.length || actual.some((field, index) => field !== expected[index])) throw new ProtocolRejectionError('frame field set mismatch');
  return record;
}

export function clientTranscript(hello: Omit<ClientHello, 'proof'>): string {
  return [
    DEPLOYMENT.protocol, 'client', hello.challenge, hello.nonce, hello.deploymentId, hello.manifestSha256, hello.protocol,
    hello.minecraftVersion, hello.fabricLoaderVersion, hello.fabricApiVersion, String(hello.javaMajor),
    hello.javaVendor, hello.javaRuntimeVersion, hello.serverId, hello.worldId, hello.characterId, hello.agentId,
    String(hello.configurationHandshakeVersion), hello.configSetSha256,
  ].join('\n');
}

export function serverChallengeTranscript(challenge: string, facts: WelcomeFacts): string {
  return [
    DEPLOYMENT.protocol, 'challenge', challenge, facts.deploymentId, facts.manifestSha256, facts.protocol,
    facts.hostVersion, facts.companionVersion, facts.nodeVersion,
    facts.serverId, facts.worldId, facts.characterId, facts.agentId,
    String(facts.configurationHandshakeVersion), facts.configSetSha256,
  ].join('\n');
}

export function serverTranscript(challenge: string, nonce: string, facts: WelcomeFacts): string {
  return [
    DEPLOYMENT.protocol, 'server', challenge, nonce, facts.deploymentId, facts.manifestSha256, facts.protocol,
    facts.hostVersion, facts.companionVersion, facts.nodeVersion,
    facts.serverId, facts.worldId, facts.characterId, facts.agentId,
    String(facts.configurationHandshakeVersion), facts.configSetSha256,
  ].join('\n');
}

export function createProof(secret: string, transcript: string): string {
  const key = Buffer.from(secret, 'base64url');
  if (key.length < 32 || key.length > 64) throw new Error('invalid local handshake secret');
  return createHmac('sha256', key).update(transcript, 'utf8').digest('base64url');
}

export function verifyProof(secret: string, transcript: string, proof: string): boolean {
  if (typeof proof !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(proof)) return false;
  const expected = Buffer.from(createProof(secret, transcript), 'base64url');
  const supplied = Buffer.from(proof, 'base64url');
  return expected.length === supplied.length && timingSafeEqual(expected, supplied);
}

export function validateClientHello(value: unknown, secret: string, binding: Binding, manifestSha256: string,
                                   configSetSha256: string, expectedChallenge: string): ClientHello {
  const hello = exactRecord(value, HELLO_FIELDS) as unknown as ClientHello;
  if (hello.type !== 'hello' || hello.protocol !== DEPLOYMENT.protocol || hello.configurationHandshakeVersion !== 1 ||
      hello.configSetSha256 !== configSetSha256 ||
      !isNonce(hello.challenge) || hello.challenge !== expectedChallenge || !isNonce(hello.nonce)) {
    throw new ProtocolRejectionError('handshake identity mismatch');
  }
  const localDev = process.env.WORLDSPEAKER_LOCAL_DEVELOPMENT === 'true';
  if (hello.deploymentId !== DEPLOYMENT.deploymentId || hello.manifestSha256 !== manifestSha256 ||
      hello.minecraftVersion !== DEPLOYMENT.minecraftVersion || hello.fabricLoaderVersion !== DEPLOYMENT.fabricLoaderVersion ||
      hello.fabricApiVersion !== DEPLOYMENT.fabricApiVersion || hello.javaMajor !== DEPLOYMENT.javaMajor ||
      (!localDev && (hello.javaVendor !== DEPLOYMENT.javaVendor || hello.javaRuntimeVersion !== DEPLOYMENT.javaRuntimeVersion))) throw new ProtocolRejectionError('client runtime mismatch');
  if (!sameBinding(hello, binding)) throw new ProtocolRejectionError('binding mismatch');
  if (!verifyProof(secret, clientTranscript(hello), hello.proof)) throw new ProtocolRejectionError('client proof rejected');
  return hello;
}

export function validateDialogueRequest(value: unknown, binding: Binding): DialogueRequest {
  const request = exactRecord(value, DIALOGUE_REQUEST_FIELDS) as unknown as DialogueRequest;
  if (request.type !== 'dialogue.request' || request.protocol !== DEPLOYMENT.protocol ||
      !isUuid(request.requestId) || !isUuid(request.invocationId) || request.requestId === request.invocationId ||
      !isNonNegativeSafeInteger(request.abilityPolicyRevision) ||
      !sameBinding(request, binding)) throw new ProtocolRejectionError('dialogue request identity rejected');
  const event = validateEvent(request.event, binding, true);
  if (!sameBinding(event, request) || !request.replyAudience || !sameRecord(request.replyAudience, AUDIENCE_FIELDS) ||
      request.replyAudience.kind !== 'player' || !isUuid(request.replyAudience.playerId) ||
      request.replyAudience.playerId !== event.playerId) throw new ProtocolRejectionError('dialogue audience rejected');
  const conversation = exactRecord(request.conversation, ['conversationId', 'replyToMessageId']);
  if (!isUuid(conversation.conversationId)) throw new ProtocolRejectionError('conversation identity rejected');
  const followup = event.inputKind === 'followup';
  if (followup ? !isUuid(conversation.replyToMessageId) : conversation.replyToMessageId !== null) {
    throw new ProtocolRejectionError('follow-up anchor rejected');
  }
  if (!Array.isArray(request.contextBatch) || request.contextBatch.length > 8) throw new ProtocolRejectionError('context batch rejected');
  const encodedContextBatch = JSON.stringify(request.contextBatch);
  if (typeof encodedContextBatch !== 'string' || Buffer.byteLength(encodedContextBatch, 'utf8') > 8192) {
    throw new ProtocolRejectionError('context batch byte bound rejected');
  }
  validateContextStatus(request.contextStatus);
  const eventIds = new Set([event.eventId]);
  for (const item of request.contextBatch) {
    const earlier = validateContextEvent(item, binding);
    if (!sameBinding(earlier, request) || eventIds.has(earlier.eventId)) throw new ProtocolRejectionError('duplicate or cross-binding context event');
    eventIds.add(earlier.eventId);
  }
  if (request.acknowledgement !== null) validateAcknowledgement(request.acknowledgement, event.playerId);
  if (!Array.isArray(request.progressContext) || request.progressContext.length > 1) {
    throw new ProtocolRejectionError('queued progress context rejected');
  }
  const progressIds = new Set<string>();
  if (request.acknowledgement !== null) progressIds.add(request.acknowledgement.messageId);
  for (const progress of request.progressContext) {
    validateProgressRecord(progress, event.playerId, 'queued');
    if (progressIds.has(progress.messageId)) throw new ProtocolRejectionError('progress message id reuse rejected');
    progressIds.add(progress.messageId);
  }
  if (Buffer.byteLength(JSON.stringify(request.progressContext), 'utf8') > 8192) {
    throw new ProtocolRejectionError('progress context byte bound rejected');
  }
  return request;
}

export function validateDialogueAcknowledgement(value: unknown): DialogueAcknowledgementFrame {
  const frame = exactRecord(value, DIALOGUE_ACK_FIELDS) as unknown as DialogueAcknowledgementFrame;
  if (frame.type !== 'dialogue.ack' || frame.protocol !== DEPLOYMENT.protocol ||
      !isUuid(frame.requestId) || !isUuid(frame.invocationId) || frame.requestId === frame.invocationId ||
      !isUuid(frame.playerId)) throw new ProtocolRejectionError('dialogue acknowledgement identity rejected');
  validateAcknowledgement(frame.acknowledgement, frame.playerId);
  return frame;
}

export function validateDialogueProgress(value: unknown, request: DialogueRequest): DialogueProgressFrame {
  const frame = exactRecord(value, DIALOGUE_PROGRESS_FIELDS) as unknown as DialogueProgressFrame;
  if (frame.type !== 'dialogue.progress' || frame.protocol !== DEPLOYMENT.protocol ||
      !isUuid(frame.requestId) || !isUuid(frame.invocationId) || frame.requestId === frame.invocationId ||
      frame.requestId !== request.requestId || frame.invocationId !== request.invocationId ||
      !isUuid(frame.playerId) || frame.playerId !== request.event.playerId) {
    throw new ProtocolRejectionError('dialogue progress attribution rejected');
  }
  validateProgressRecord(frame.progress, request.event.playerId, 'awaiting_answer');
  return frame;
}

export function validateDialogueCancel(value: unknown): DialogueCancelFrame {
  const frame = exactRecord(value, DIALOGUE_CANCEL_FIELDS) as unknown as DialogueCancelFrame;
  if (frame.type !== 'dialogue.cancel' || frame.protocol !== DEPLOYMENT.protocol ||
      !isUuid(frame.requestId) || !isUuid(frame.invocationId) || frame.requestId === frame.invocationId ||
      !isUuid(frame.playerId)) throw new ProtocolRejectionError('dialogue cancellation identity rejected');
  return frame;
}

export function validateDialogueResponse(value: unknown, request: DialogueRequest): DialogueResponse {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new ProtocolRejectionError('dialogue response must be an object');
  const type = (value as Record<string, unknown>).type;
  const fields = type === 'dialogue.message' ? DIALOGUE_MESSAGE_FIELDS : type === 'dialogue.failure' ? DIALOGUE_FAILURE_FIELDS : null;
  if (!fields) throw new ProtocolRejectionError('unsupported dialogue response');
  const frame = exactRecord(value, fields);
  if (!isUuid(frame.requestId) || !isUuid(frame.invocationId) || !isBindingString(frame.characterId) ||
      frame.protocol !== DEPLOYMENT.protocol || frame.requestId !== request.requestId || frame.invocationId !== request.invocationId ||
      frame.characterId !== request.characterId || !frame.replyAudience || !sameRecord(frame.replyAudience, AUDIENCE_FIELDS) ||
      frame.replyAudience.kind !== 'player' || !isUuid(frame.replyAudience.playerId) ||
      frame.replyAudience.playerId !== request.replyAudience.playerId) {
    throw new ProtocolRejectionError('dialogue response attribution mismatch');
  }
  if (type === 'dialogue.message') {
    if (!isUuid(frame.messageId) || frame.kind !== 'answer' ||
        typeof frame.text !== 'string' || frame.text.length > MAX_COMPLETED_ANSWER_UNITS || !isSafePublicBodyText(frame.text)) {
      throw new ProtocolRejectionError('dialogue answer rejected');
    }
    return frame as unknown as DialogueMessage;
  }
  if (frame.reason !== 'unavailable') throw new ProtocolRejectionError('dialogue failure rejected');
  return frame as unknown as DialogueFailure;
}

function validateProgressRecord(value: unknown, expectedPlayerId: string, expectedPhase: DialogueProgress['phase']): DialogueProgress {
  const progress = exactRecord(value, PROGRESS_FIELDS) as unknown as DialogueProgress;
  if (!isUuid(progress.messageId) || typeof progress.text !== 'string' || !progress.text.startsWith('[Worldspeaker] ') ||
      progress.text.length > 2048 ||
      !isSafePublicBodyText(progress.text) || !isNonNegativeSafeInteger(progress.dispatchedAtUnixMillis) ||
      !isJavaLongString(progress.dispatchedAtMonotonicNanos) || !progress.replyAudience ||
      !sameRecord(progress.replyAudience, AUDIENCE_FIELDS) || progress.replyAudience.kind !== 'player' ||
      !isUuid(progress.replyAudience.playerId) || progress.replyAudience.playerId !== expectedPlayerId ||
      progress.phase !== expectedPhase) {
    throw new ProtocolRejectionError('dialogue progress record rejected');
  }
  return progress;
}

export function welcomeFacts(binding: Binding, manifestSha256: string, configSetSha256: string): WelcomeFacts {
  return {
    deploymentId: DEPLOYMENT.deploymentId,
    manifestSha256,
    protocol: DEPLOYMENT.protocol,
    hostVersion: DEPLOYMENT.hostVersion,
    nodeVersion: process.versions.node,
    companionVersion: DEPLOYMENT.companionVersion,
    configurationHandshakeVersion: 1,
    configSetSha256,
    ...binding,
  };
}

function validateEvent(value: unknown, binding: Binding, primary: boolean): AttributedEvent {
  const event = exactRecord(value, DIALOGUE_EVENT_FIELDS) as unknown as AttributedEvent;
  if (!BINDING_FIELDS.every((field) => isBindingString(event[field])) || !sameBinding(event, binding) ||
      !isUuid(event.eventId) || !isUuid(event.serverEpochId) ||
      !isUuid(event.playerId) || !isNonNegativeSafeInteger(event.occurredAtUnixMillis) ||
      !isJavaLongString(event.capturedAtMonotonicNanos) ||
      !['live', 'history'].includes(event.eventOrigin) || primary && event.eventOrigin !== 'live' ||
      !['command', 'natural_address', 'followup'].includes(event.inputKind) ||
      typeof event.playerName !== 'string' || isEcmaScriptBlank(event.playerName) || event.playerName.length > 32 || unsafeControls(event.playerName, false) ||
      typeof event.dimension !== 'string' || event.dimension.length > 256 || !/^[a-z0-9_.-]+:[a-z0-9/._-]+$/.test(event.dimension) ||
      typeof event.text !== 'string' || isEcmaScriptBlank(event.text) || event.text.length > 512 || unsafeControls(event.text, true)) {
    throw new ProtocolRejectionError('attributed event rejected');
  }
  return event;
}

function validateContextEvent(value: unknown, binding: Binding): ContextEvent {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new ProtocolRejectionError('context event rejected');
  const record = value as Record<string, unknown>;
  if (sameRecord(record, DIALOGUE_EVENT_FIELDS)) return validateEvent(value, binding, false);
  const event = exactRecord(value, ATTENTION_EVENT_FIELDS) as unknown as AttentionEvent;
  const validBinding = BINDING_FIELDS.every((field) => isBindingString(event[field])) && sameBinding(event, binding);
  const subject = event.subject;
  if (!validBinding || !isUuid(event.eventId) || !isUuid(event.serverEpochId) ||
      !isNonNegativeSafeInteger(event.occurredAtUnixMillis) || !isJavaLongString(event.capturedAtMonotonicNanos) ||
      !['live', 'history'].includes(event.eventOrigin) ||
      !['server_callback', 'server_sample'].includes(event.source) ||
      !subject || !sameRecord(subject, ['playerId', 'playerName']) || !isUuid(subject.playerId) ||
      typeof subject.playerName !== 'string' || isEcmaScriptBlank(subject.playerName) || subject.playerName.length > 32 ||
      unsafeControls(subject.playerName, false) || typeof event.dimension !== 'string' ||
      event.dimension.length < 3 || event.dimension.length > 256 || !/^[a-z0-9_.-]+:[a-z0-9/._-]+$/.test(event.dimension) ||
      !isAttentionDetails(event.eventKind, event.source, event.details)) {
    throw new ProtocolRejectionError('attention event rejected');
  }
  return event;
}

function validateContextStatus(value: unknown): ContextStatus {
  if (!sameRecord(value, CONTEXT_STATUS_FIELDS)) throw new ProtocolRejectionError('context status fields rejected');
  const status = value as ContextStatus;
  if (!['disabled', 'bounded', 'incomplete'].includes(status.state) ||
      typeof status.lossRevision !== 'string' || !/^(?:0|[1-9][0-9]*)$/u.test(status.lossRevision)) {
    throw new ProtocolRejectionError('context status identity rejected');
  }
  try {
    if (BigInt(status.lossRevision) > 9_223_372_036_854_775_807n) throw new Error('overflow');
  } catch {
    throw new ProtocolRejectionError('context status revision rejected');
  }
  if (!Array.isArray(status.reasons) || status.reasons.length > 8 ||
      status.reasons.some((reason) => !CONTEXT_LOSS_REASONS.includes(reason)) ||
      new Set(status.reasons).size !== status.reasons.length ||
      status.state === 'disabled' && (status.lossRevision !== '0' || status.reasons.length !== 0) ||
      status.state === 'bounded' && status.reasons.length !== 0 ||
      status.state === 'incomplete' && status.reasons.length === 0) {
    throw new ProtocolRejectionError('context status losses rejected');
  }
  return status;
}

function isAttentionDetails(kind: unknown, source: unknown, details: unknown): boolean {
  if (details === null || typeof details !== 'object' || Array.isArray(details)) return false;
  const record = details as Record<string, unknown>;
  const exact = (fields: readonly string[]) => sameRecord(record, fields);
  switch (kind) {
    case 'joined': case 'left': case 'died':
      return source === 'server_callback' && exact([]);
    case 'dimension_changed':
      return source === 'server_callback' && exact(['originDimension']) &&
        typeof record.originDimension === 'string' && record.originDimension.length <= 256 &&
        /^[a-z0-9_.-]+:[a-z0-9/._-]+$/.test(record.originDimension);
    case 'respawned':
      return source === 'server_callback' && exact(['copiedAlive']) && typeof record.copiedAlive === 'boolean';
    case 'moved':
      return source === 'server_sample' && exact(['basis']) && record.basis === 'sampled_block_change';
    default:
      return false;
  }
}

function validateAcknowledgement(value: unknown, expectedPlayerId: string): DialogueAcknowledgement {
  const acknowledgement = exactRecord(value, ACKNOWLEDGEMENT_FIELDS) as unknown as DialogueAcknowledgement;
  if (!isUuid(acknowledgement.messageId) || typeof acknowledgement.text !== 'string' ||
      isEcmaScriptBlank(acknowledgement.text) || acknowledgement.text.length > 2048 || unsafeControls(acknowledgement.text, false) ||
      !isNonNegativeSafeInteger(acknowledgement.dispatchedAtUnixMillis) ||
      !isJavaLongString(acknowledgement.dispatchedAtMonotonicNanos) || !sameRecord(acknowledgement.replyAudience, AUDIENCE_FIELDS) ||
      acknowledgement.replyAudience.kind !== 'player' || !isUuid(acknowledgement.replyAudience.playerId) ||
      acknowledgement.replyAudience.playerId !== expectedPlayerId) {
    throw new ProtocolRejectionError('dialogue acknowledgement rejected');
  }
  return acknowledgement;
}

function sameBinding(left: Partial<Binding>, right: Partial<Binding>): boolean {
  return BINDING_FIELDS.every((field) => typeof left[field] === 'string' && typeof right[field] === 'string' &&
    left[field] === right[field]);
}

function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID_PATTERN.test(value);
}

function isNonce(value: unknown): value is string {
  return typeof value === 'string' && NONCE_PATTERN.test(value);
}

function isBindingString(value: unknown): value is string {
  return typeof value === 'string' && !isEcmaScriptBlank(value) && value.length <= 512;
}

function sameRecord(value: unknown, fields: readonly string[]): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false;
  const keys = Object.keys(value as Record<string, unknown>).sort();
  const expected = [...fields].sort();
  return keys.length === expected.length && keys.every((key, index) => key === expected[index]);
}

function unsafeControls(value: string, allowLineWhitespace: boolean): boolean {
  return /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(value) ||
    !allowLineWhitespace && /[\u0009-\u000d]/u.test(value);
}

function isJavaLongString(value: unknown): value is string {
  if (typeof value !== 'string' || !/^(?:0|-?[1-9][0-9]*)$/u.test(value)) return false;
  try {
    const nanos = BigInt(value);
    return nanos >= -9_223_372_036_854_775_808n && nanos <= 9_223_372_036_854_775_807n;
  } catch {
    return false;
  }
}
