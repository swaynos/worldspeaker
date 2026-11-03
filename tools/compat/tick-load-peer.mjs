import { createHash, createHmac, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { createRequire } from 'node:module';
import { readFile, rename, stat, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const requireCompanion = createRequire(join(ROOT, 'companion/package.json'));
const { WebSocket, WebSocketServer } = requireCompanion('ws');
const deploymentBytes = await readFile(join(ROOT, 'compatibility/deployment.json'));
const DEPLOYMENT = JSON.parse(deploymentBytes.toString('utf8'));
const MANIFEST_SHA256 = createHash('sha256').update(deploymentBytes).digest('hex');
const MAX_FRAME_BYTES = 16 * 1024;
const MAX_DELAYED_REQUESTS = 16;
const MAX_CONTROL_BYTES = 1024;
const PHASES = new Set(['idle', 'normal', 'overload', 'unavailable', 'recovery', 'repeat', 'stop-backlog']);
const CHALLENGE_FIELDS = [
  'type', 'challenge', 'deploymentId', 'manifestSha256', 'protocol', 'hostVersion', 'nodeVersion',
  'companionVersion', 'serverId', 'worldId', 'characterId', 'agentId', 'proof',
];
const HELLO_FIELDS = [
  'type', 'protocol', 'challenge', 'nonce', 'deploymentId', 'manifestSha256', 'minecraftVersion',
  'fabricLoaderVersion', 'fabricApiVersion', 'javaMajor', 'javaVendor', 'javaRuntimeVersion',
  'serverId', 'worldId', 'characterId', 'agentId', 'proof',
];
const REQUEST_FIELDS = [
  'type', 'protocol', 'requestId', 'invocationId', 'serverId', 'worldId', 'characterId', 'agentId',
  'event', 'conversation', 'replyAudience', 'contextBatch', 'contextStatus', 'acknowledgement', 'progressContext',
];
const EVENT_FIELDS = [
  'serverId', 'worldId', 'characterId', 'agentId', 'eventId', 'serverEpochId', 'occurredAtUnixMillis',
  'capturedAtMonotonicNanos', 'eventOrigin', 'inputKind', 'playerId', 'playerName', 'dimension', 'text',
];
const ATTENTION_EVENT_FIELDS = [
  'serverId', 'worldId', 'characterId', 'agentId', 'eventId', 'serverEpochId', 'occurredAtUnixMillis',
  'capturedAtMonotonicNanos', 'eventOrigin', 'source', 'subject', 'dimension', 'eventKind', 'details',
];
const CONTEXT_STATUS_FIELDS = ['state', 'lossRevision', 'reasons'];
const CONTEXT_LOSS_REASONS = new Set([
  'epoch_start', 'expiry', 'capacity', 'rate', 'bytes', 'frame', 'transport', 'source_unavailable',
]);
const ACK_FIELDS = ['type', 'protocol', 'requestId', 'invocationId', 'playerId', 'acknowledgement'];
const CANCEL_FIELDS = ['type', 'protocol', 'requestId', 'invocationId', 'playerId'];
const HEARTBEAT_FIELDS = ['type', 'protocol', 'sequence'];
const ACKNOWLEDGEMENT_FIELDS = ['messageId', 'text', 'dispatchedAtUnixMillis', 'dispatchedAtMonotonicNanos', 'replyAudience'];
const PROGRESS_FIELDS = ['messageId', 'text', 'dispatchedAtUnixMillis', 'dispatchedAtMonotonicNanos', 'replyAudience', 'phase'];
const PROGRESS_FRAME_FIELDS = ['type', 'protocol', 'requestId', 'invocationId', 'playerId', 'progress'];
const AUDIENCE_FIELDS = ['kind', 'playerId'];
const CONVERSATION_FIELDS = ['conversationId', 'replyToMessageId'];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export function parsePeerArgs(tokens) {
  const result = { help: false, stallHealth: false };
  const values = new Map([['--config', 'configPath'], ['--control', 'controlPath'], ['--status', 'statusPath']]);
  const booleans = new Map([['--help', 'help'], ['--stall-health', 'stallHealth']]);
  const seen = new Set();
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (seen.has(token)) throw codedError(`duplicate-option-${safeOption(token)}`);
    seen.add(token);
    if (booleans.has(token)) {
      result[booleans.get(token)] = true;
      continue;
    }
    const name = values.get(token);
    if (!name) throw codedError(`unknown-option-${safeOption(token)}`);
    const value = tokens[++index];
    if (!value || value.startsWith('--')) throw codedError(`option-value-required-${safeOption(token)}`);
    if (!isAbsolutePath(value)) throw codedError(`option-path-must-be-absolute-${safeOption(token)}`);
    result[name] = value;
  }
  if (result.help) return result;
  for (const key of ['configPath', 'controlPath', 'statusPath']) {
    if (!result[key]) throw codedError(`option-required-${key.replace('Path', '')}`);
  }
  return result;
}

export async function startFixturePeer({ config, controlPath, statusPath, stallHealth = false, serverClass = WebSocketServer }) {
  validateConfig(config);
  if (!isAbsolutePath(controlPath) || !isAbsolutePath(statusPath)) throw codedError('peer-path-must-be-absolute');
  const state = {
      server: new serverClass({
      host: '127.0.0.1',
      port: config.port,
      maxPayload: MAX_FRAME_BYTES,
      perMessageDeflate: false,
      clientTracking: true,
    }),
    socket: null,
    phase: 'idle',
    authenticated: false,
    requestCount: 0,
    answerCount: 0,
    stalledCount: 0,
    overflowFailureCount: 0,
    requestsByPhase: Object.fromEntries([...PHASES].map((phase) => [phase, 0])),
    delayedRequestsByPhase: Object.fromEntries([...PHASES].map((phase) => [phase, 0])),
    delayedPendingHighWaterByPhase: Object.fromEntries([...PHASES].map((phase) => [phase, 0])),
    protocolErrors: 0,
    runId: config.runId,
    pending: new Map(),
    retainedRequests: new Map(),
    handshakeTimer: null,
    controlTimer: null,
    controlReads: new Set(),
    statusWriteTail: Promise.resolve(),
    closePromise: null,
    stopped: false,
    statusCreated: false,
  };
  state.server.on('connection', (socket) => acceptConnection(state, socket, config, statusPath, stallHealth));
  await new Promise((resolveReady, reject) => {
    const onListening = () => {
      state.server.off('error', onError);
      resolveReady();
    };
    const onError = () => {
      state.server.off('listening', onListening);
      reject(codedError('fixture-peer-listen-failed'));
    };
    state.server.once('listening', onListening);
    state.server.once('error', onError);
  });
  await writeStatus(state, statusPath, 'listening');
  state.controlTimer = setInterval(() => {
    const read = readControl(state, controlPath, statusPath);
    state.controlReads.add(read);
    void read.then(
      () => state.controlReads.delete(read),
      () => state.controlReads.delete(read),
    );
  }, 100);
  state.controlTimer.unref();

  return Object.freeze({
    port: config.port,
    status: () => publicStatus(state),
    close: () => closeFixturePeer(state, statusPath),
  });
}

function acceptConnection(state, socket, config, statusPath, stallHealth) {
  if (state.stopped || state.phase === 'unavailable' || state.socket) {
    socket.close(1013, 'fixture admission unavailable');
    return;
  }
  state.socket = socket;
  state.authenticated = false;
  const challenge = randomBytes(32).toString('base64url');
  const facts = welcomeFacts(config.binding, config.manifestSha256);
  state.handshakeTimer = setTimeout(() => closePeerSocket(state, 4001, 'fixture handshake timeout'), 5000);
  socket.on('message', (raw, isBinary) => receive(state, config, socket, challenge, facts, raw, isBinary, statusPath, stallHealth));
  socket.on('close', () => releaseSocket(state, socket, statusPath));
  socket.on('error', () => releaseSocket(state, socket, statusPath));
  sendJson(socket, {
    type: 'challenge', challenge, ...facts,
    proof: proof(config.secret, challengeTranscript(challenge, facts)),
  });
}

function receive(state, config, socket, challenge, facts, raw, isBinary, statusPath, stallHealth) {
  if (socket !== state.socket || state.stopped) return;
  if (isBinary) return rejectFrame(state, socket, statusPath);
  const bytes = Buffer.isBuffer(raw) ? raw : Buffer.from(raw);
  if (bytes.byteLength > MAX_FRAME_BYTES) return rejectFrame(state, socket, statusPath);
  let frame;
  try { frame = JSON.parse(bytes.toString('utf8')); } catch { return rejectFrame(state, socket, statusPath); }

  if (!state.authenticated) {
    try {
      verifyHello(frame, config, challenge, facts);
      clearTimeout(state.handshakeTimer);
      state.authenticated = true;
      sendJson(socket, {
        type: 'welcome', challenge, nonce: frame.nonce, ...facts,
        proof: proof(config.secret, serverTranscript(challenge, frame.nonce, facts)),
      });
      void writeStatus(state, statusPath, 'authenticated');
    } catch {
      rejectFrame(state, socket, statusPath);
    }
    return;
  }

  if (!frame || typeof frame !== 'object' || Array.isArray(frame)) return rejectFrame(state, socket, statusPath);
  if (frame.type === 'heartbeat') {
    try {
      exactFrame(frame, HEARTBEAT_FIELDS);
      if (frame.protocol !== DEPLOYMENT.companion.protocol || !Number.isSafeInteger(frame.sequence) || frame.sequence < 1) throw new Error('heartbeat rejected');
      if (!stallHealth) sendJson(socket, { type: 'heartbeat.ack', protocol: DEPLOYMENT.companion.protocol, sequence: frame.sequence });
    } catch { rejectFrame(state, socket, statusPath); }
    return;
  }
  if (frame.type === 'dialogue.ack') {
    try {
      validateAcknowledgement(frame, config);
      const retained = state.retainedRequests.get(frame.requestId);
      if (retained && (retained.invocationId !== frame.invocationId || retained.playerId !== frame.playerId)) {
        throw new Error('acknowledgement attribution mismatch');
      }
    } catch { rejectFrame(state, socket, statusPath); }
    return;
  }
  if (frame.type === 'dialogue.progress') {
    try {
      const retained = state.retainedRequests.get(frame.requestId);
      if (!retained) throw new Error('progress request is not retained');
      validateProgressFrame(frame, retained);
    } catch { rejectFrame(state, socket, statusPath); }
    return;
  }
  if (frame.type === 'dialogue.cancel') {
    try {
      exactFrame(frame, CANCEL_FIELDS);
      validateBoundIdentity(frame, config);
      const pending = state.pending.get(frame.requestId);
       const retained = state.retainedRequests.get(frame.requestId);
       if (retained && (retained.invocationId !== frame.invocationId || retained.playerId !== frame.playerId) ||
           pending && (pending.invocationId !== frame.invocationId || pending.playerId !== frame.playerId)) {
         throw new Error('cancel attribution mismatch');
       }
       if (pending) clearTimeout(pending.timer);
       state.pending.delete(frame.requestId);
       state.retainedRequests.delete(frame.requestId);
    } catch { rejectFrame(state, socket, statusPath); }
    return;
  }
  if (frame.type !== 'dialogue.request') return rejectFrame(state, socket, statusPath);
  try {
    validateRequest(frame, config);
    if (state.pending.has(frame.requestId) || state.retainedRequests.has(frame.requestId)) throw new Error('duplicate request');
  } catch { rejectFrame(state, socket, statusPath); return; }
  state.requestCount = increment(state.requestCount);
  state.requestsByPhase[state.phase] = increment(state.requestsByPhase[state.phase]);
  const delayed = ['overload', 'repeat', 'stop-backlog'].includes(state.phase);
  if (delayed) {
    state.stalledCount = increment(state.stalledCount);
    state.delayedRequestsByPhase[state.phase] = increment(state.delayedRequestsByPhase[state.phase]);
  }
  if (delayed && state.pending.size >= MAX_DELAYED_REQUESTS) {
    const sent = sendJson(socket, {
      type: 'dialogue.failure',
      protocol: DEPLOYMENT.companion.protocol,
      requestId: frame.requestId,
      invocationId: frame.invocationId,
      characterId: frame.characterId,
      replyAudience: frame.replyAudience,
      reason: 'unavailable',
    });
    if (sent) state.overflowFailureCount = increment(state.overflowFailureCount);
    void writeStatus(state, statusPath, 'delayed-capacity-reached');
    return;
  }
  const timer = setTimeout(() => {
    const current = state.pending.get(frame.requestId);
    if (!current || current.socket !== socket || socket.readyState !== WebSocket.OPEN || !state.authenticated) return;
    state.pending.delete(frame.requestId);
    if (state.phase === 'unavailable') return;
    const sent = sendJson(socket, {
      type: 'dialogue.message',
      protocol: DEPLOYMENT.companion.protocol,
      messageId: randomUUID(),
      requestId: frame.requestId,
      invocationId: frame.invocationId,
      characterId: frame.characterId,
      replyAudience: frame.replyAudience,
      kind: 'answer',
      text: 'This is a synthetic fixture reply.',
    });
    if (sent) state.answerCount = increment(state.answerCount);
    void writeStatus(state, statusPath, sent ? 'answer' : 'answer-send-failed');
  }, delayed ? 30_000 : 100);
  rememberAttribution(state.retainedRequests, frame.requestId, {
    invocationId: frame.invocationId,
    playerId: frame.event.playerId,
    progress: new Map(frame.progressContext.map((progress) => [progress.messageId, progressContent(progress)])),
  });
  state.pending.set(frame.requestId, {
    socket, invocationId: frame.invocationId, playerId: frame.event.playerId,
    characterId: frame.characterId, replyAudience: frame.replyAudience, timer,
  });
  if (delayed) {
    state.delayedPendingHighWaterByPhase[state.phase] = Math.max(
      state.delayedPendingHighWaterByPhase[state.phase], state.pending.size,
    );
  }
  void writeStatus(state, statusPath, 'request');
}

function verifyHello(frame, config, expectedChallenge, facts) {
  exactFrame(frame, HELLO_FIELDS);
  const target = {
    deploymentId: DEPLOYMENT.deploymentId,
    manifestSha256: config.manifestSha256,
    protocol: DEPLOYMENT.companion.protocol,
    minecraftVersion: DEPLOYMENT.target.minecraft,
    fabricLoaderVersion: DEPLOYMENT.runtimeDependencies.fabricLoader,
    fabricApiVersion: DEPLOYMENT.runtimeDependencies.fabricApi,
    javaMajor: DEPLOYMENT.target.java.major,
    javaVendor: DEPLOYMENT.target.java.vendor,
    javaRuntimeVersion: DEPLOYMENT.target.java.runtimeVersion,
    ...config.binding,
  };
  for (const [key, value] of Object.entries(target)) if (frame[key] !== value) throw new Error('client facts mismatch');
  if (frame.type !== 'hello' || frame.challenge !== expectedChallenge || !/^[A-Za-z0-9_-]{43}$/.test(frame.challenge) ||
      !/^[A-Za-z0-9_-]{43}$/.test(frame.nonce)) throw new Error('hello challenge mismatch');
    const transcript = [
    DEPLOYMENT.companion.protocol, 'client', frame.challenge, frame.nonce, frame.deploymentId, frame.manifestSha256,
    frame.protocol, frame.minecraftVersion, frame.fabricLoaderVersion, frame.fabricApiVersion, String(frame.javaMajor),
    frame.javaVendor, frame.javaRuntimeVersion, frame.serverId, frame.worldId, frame.characterId, frame.agentId,
    ].join('\n');
  if (!verifyProof(config.secret, transcript, frame.proof)) throw new Error('client proof rejected');
  if (facts.nodeVersion !== process.versions.node) throw new Error('fixture Node version mismatch');
}

function validateRequest(frame, config) {
  exactFrame(frame, REQUEST_FIELDS);
  validateBoundIdentity(frame, config);
  if (!UUID.test(frame.requestId) || !UUID.test(frame.invocationId) || frame.requestId === frame.invocationId) {
    throw new Error('dialogue request identity rejected');
  }
  const event = validateEvent(frame.event, config.binding, true);
  if (!config.authorizedPlayerIds.includes(event.playerId)) throw new Error('dialogue player is not authorized');
  for (const key of ['serverId', 'worldId', 'characterId', 'agentId']) {
    if (frame[key] !== event[key]) throw new Error('dialogue event binding mismatch');
  }
  validateAudience(frame.replyAudience, event.playerId);
  exactFrame(frame.conversation, CONVERSATION_FIELDS);
  if (!UUID.test(frame.conversation.conversationId) ||
      (event.inputKind === 'followup' ? !UUID.test(frame.conversation.replyToMessageId ?? '') : frame.conversation.replyToMessageId !== null)) {
    throw new Error('conversation anchor rejected');
  }
  if (!Array.isArray(frame.contextBatch) || frame.contextBatch.length > 8) throw new Error('context batch rejected');
  if (Buffer.byteLength(JSON.stringify(frame.contextBatch), 'utf8') > 8192) throw new Error('context batch byte bound rejected');
  const eventIds = new Set([event.eventId]);
  for (const item of frame.contextBatch) {
    const earlier = validateContextEvent(item, config);
    if (eventIds.has(earlier.eventId)) throw new Error('context event id reused');
    eventIds.add(earlier.eventId);
  }
  validateContextStatus(frame.contextStatus);
  if (frame.acknowledgement !== null) validateAcknowledgementValue(frame.acknowledgement, event.playerId);
  if (!Array.isArray(frame.progressContext) || frame.progressContext.length > 1 ||
      Buffer.byteLength(JSON.stringify(frame.progressContext), 'utf8') > 8192) throw new Error('progress context bound rejected');
  const progressIds = new Set();
  if (frame.acknowledgement !== null) progressIds.add(frame.acknowledgement.messageId);
  for (const progress of frame.progressContext) {
    validateProgressRecord(progress, event.playerId, 'queued');
    if (progressIds.has(progress.messageId)) throw new Error('progress context id reuse rejected');
    progressIds.add(progress.messageId);
  }
}

function validateProgressFrame(frame, retained) {
  exactFrame(frame, PROGRESS_FRAME_FIELDS);
  if (frame.type !== 'dialogue.progress' || frame.protocol !== DEPLOYMENT.companion.protocol ||
      !UUID.test(frame.requestId) || !UUID.test(frame.invocationId) || frame.requestId === frame.invocationId ||
      frame.invocationId !== retained.invocationId || !UUID.test(frame.playerId) || frame.playerId !== retained.playerId) {
    throw new Error('progress attribution rejected');
  }
  const progress = validateProgressRecord(frame.progress, retained.playerId, 'awaiting_answer');
  const content = progressContent(progress);
  const previous = retained.progress.get(progress.messageId);
  if (previous !== undefined) {
    if (previous !== content) throw new Error('conflicting progress id reuse rejected');
    return false;
  }
  if (retained.progress.size >= 3) throw new Error('progress record limit rejected');
  retained.progress.set(progress.messageId, content);
  return true;
}

function validateProgressRecord(value, expectedPlayerId, expectedPhase) {
  exactFrame(value, PROGRESS_FIELDS);
  if (!UUID.test(value.messageId) || !isSafeProgressText(value.text) ||
      !Number.isSafeInteger(value.dispatchedAtUnixMillis) || value.dispatchedAtUnixMillis < 0 ||
      !isJavaLong(value.dispatchedAtMonotonicNanos) || value.phase !== expectedPhase) {
    throw new Error('progress record rejected');
  }
  validateAudience(value.replyAudience, expectedPlayerId);
  return value;
}

function progressContent(progress) {
  return JSON.stringify({
    text: progress.text,
    dispatchedAtUnixMillis: progress.dispatchedAtUnixMillis,
    dispatchedAtMonotonicNanos: progress.dispatchedAtMonotonicNanos,
    replyAudience: progress.replyAudience,
    phase: progress.phase,
  });
}

function isSafeProgressText(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 2048 &&
    value.startsWith('[Worldspeaker] ') && /[\p{L}\p{N}\p{P}\p{S}]/u.test(value) &&
    !/[\u0000-\u001f\u007f-\u009f\u2028\u2029\u00a7\u00ad\u034f\u115f-\u1160\u17b4-\u17b5\u180b-\u180f\u200b-\u200f\u202a-\u202e\u2060-\u206f\u2800\u3164\ufe00-\ufe0f\ufeff\uffa0\ufff0-\ufff8\p{Cf}]/u.test(value);
}

function validateContextEvent(value, config) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('context event rejected');
  const fields = Object.keys(value).sort();
  if (sameFields(fields, EVENT_FIELDS)) return validateEvent(value, config.binding, false);
  exactFrame(value, ATTENTION_EVENT_FIELDS);
  for (const key of ['serverId', 'worldId', 'characterId', 'agentId']) {
    if (value[key] !== config.binding[key]) throw new Error('attention binding rejected');
  }
  if (!UUID.test(value.eventId) || !UUID.test(value.serverEpochId) ||
      !Number.isSafeInteger(value.occurredAtUnixMillis) || value.occurredAtUnixMillis < 0 ||
      !isJavaLong(value.capturedAtMonotonicNanos) || !['live', 'history'].includes(value.eventOrigin) ||
      !['server_callback', 'server_sample'].includes(value.source) ||
      !value.subject || Array.isArray(value.subject) || !sameFields(Object.keys(value.subject).sort(), ['playerId', 'playerName']) ||
      !UUID.test(value.subject.playerId) || !config.authorizedPlayerIds.includes(value.subject.playerId) ||
      typeof value.subject.playerName !== 'string' || value.subject.playerName.trim().length < 1 ||
      value.subject.playerName.length > 32 || /[\u0000-\u001f\u007f]/u.test(value.subject.playerName) ||
      typeof value.dimension !== 'string' || value.dimension.length < 3 || value.dimension.length > 256 ||
      !/^[a-z0-9_.-]+:[a-z0-9/._-]+$/u.test(value.dimension)) throw new Error('attention event rejected');
  const details = value.details;
  if (!details || typeof details !== 'object' || Array.isArray(details)) throw new Error('attention details rejected');
  switch (value.eventKind) {
    case 'joined': case 'left': case 'died':
      exactFrame(details, []);
      if (value.source !== 'server_callback') throw new Error('attention source rejected');
      break;
    case 'dimension_changed':
      exactFrame(details, ['originDimension']);
      if (value.source !== 'server_callback' || typeof details.originDimension !== 'string' ||
          details.originDimension.length > 256 || !/^[a-z0-9_.-]+:[a-z0-9/._-]+$/u.test(details.originDimension)) {
        throw new Error('attention dimension details rejected');
      }
      break;
    case 'respawned':
      exactFrame(details, ['copiedAlive']);
      if (value.source !== 'server_callback' || typeof details.copiedAlive !== 'boolean') throw new Error('attention respawn details rejected');
      break;
    case 'moved':
      exactFrame(details, ['basis']);
      if (value.source !== 'server_sample' || details.basis !== 'sampled_block_change') throw new Error('attention movement details rejected');
      break;
    default: throw new Error('attention kind rejected');
  }
  return value;
}

function validateContextStatus(value) {
  exactFrame(value, CONTEXT_STATUS_FIELDS);
  if (!['disabled', 'bounded', 'incomplete'].includes(value.state) ||
      typeof value.lossRevision !== 'string' || !/^(?:0|[1-9][0-9]*)$/u.test(value.lossRevision) ||
      BigInt(value.lossRevision) > 9_223_372_036_854_775_807n || !Array.isArray(value.reasons) ||
      value.reasons.length > 8 || value.reasons.some((reason) => !CONTEXT_LOSS_REASONS.has(reason)) ||
      new Set(value.reasons).size !== value.reasons.length ||
      value.state === 'disabled' && (value.lossRevision !== '0' || value.reasons.length !== 0) ||
      value.state === 'bounded' && value.reasons.length !== 0 ||
      value.state === 'incomplete' && value.reasons.length === 0) throw new Error('context status rejected');
}

function validateAcknowledgement(frame, config) {
  exactFrame(frame, ACK_FIELDS);
  validateBoundIdentity(frame, config);
  if (!UUID.test(frame.requestId) || !UUID.test(frame.invocationId) || frame.requestId === frame.invocationId ||
      !UUID.test(frame.playerId) || !config.authorizedPlayerIds.includes(frame.playerId)) {
    throw new Error('acknowledgement audience rejected');
  }
  validateAcknowledgementValue(frame.acknowledgement, frame.playerId);
}

function validateAcknowledgementValue(value, playerId) {
  exactFrame(value, ACKNOWLEDGEMENT_FIELDS);
  if (!UUID.test(value.messageId) || typeof value.text !== 'string' || value.text.trim().length < 1 || value.text.length > 2048 ||
      /[\u0000-\u001f\u007f]/u.test(value.text) || !Number.isSafeInteger(value.dispatchedAtUnixMillis) ||
      value.dispatchedAtUnixMillis < 0 || !isJavaLong(value.dispatchedAtMonotonicNanos)) {
    throw new Error('acknowledgement rejected');
  }
  validateAudience(value.replyAudience, playerId);
}

function validateEvent(value, binding, primary) {
  exactFrame(value, EVENT_FIELDS);
  for (const key of ['serverId', 'worldId', 'characterId', 'agentId']) {
    if (value[key] !== binding[key]) throw new Error('event binding rejected');
  }
  if (!UUID.test(value.eventId) || !UUID.test(value.serverEpochId) || !UUID.test(value.playerId) ||
      !Number.isSafeInteger(value.occurredAtUnixMillis) || value.occurredAtUnixMillis < 0 ||
      !isJavaLong(value.capturedAtMonotonicNanos) || !['live', 'history'].includes(value.eventOrigin) ||
      primary && value.eventOrigin !== 'live' || !['command', 'natural_address', 'followup'].includes(value.inputKind) ||
      typeof value.playerName !== 'string' || value.playerName.length < 1 || value.playerName.length > 32 || /[\u0000-\u001f\u007f]/u.test(value.playerName) ||
      typeof value.dimension !== 'string' || value.dimension.length < 3 || value.dimension.length > 256 ||
      !/^[a-z0-9_.-]+:[a-z0-9/._-]+$/u.test(value.dimension) ||
      typeof value.text !== 'string' || value.text.trim().length < 1 || value.text.length > 512 ||
      /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(value.text)) throw new Error('event rejected');
  return value;
}

function validateAudience(value, expectedPlayerId) {
  exactFrame(value, AUDIENCE_FIELDS);
  if (value.kind !== 'player' || !UUID.test(value.playerId) || expectedPlayerId !== undefined && value.playerId !== expectedPlayerId) {
    throw new Error('reply audience rejected');
  }
}

function isJavaLong(value) {
  if (typeof value !== 'string' || !/^(?:0|-?[1-9][0-9]*)$/u.test(value)) return false;
  try {
    const parsed = BigInt(value);
    return parsed >= -9_223_372_036_854_775_808n && parsed <= 9_223_372_036_854_775_807n;
  } catch { return false; }
}

function validateBoundIdentity(frame, config) {
  if (frame.protocol !== DEPLOYMENT.companion.protocol || frame.serverId !== undefined && frame.serverId !== config.binding.serverId ||
      frame.worldId !== undefined && frame.worldId !== config.binding.worldId ||
      frame.characterId !== undefined && frame.characterId !== config.binding.characterId ||
      frame.agentId !== undefined && frame.agentId !== config.binding.agentId) throw new Error('binding rejected');
}

function rememberAttribution(values, key, value) {
  while (values.size >= 256) {
    const oldest = values.keys().next().value;
    if (oldest === undefined) break;
    values.delete(oldest);
  }
  values.set(key, value);
}

async function readControl(state, controlPath, statusPath) {
  if (state.stopped) return;
  try {
    const info = await stat(controlPath);
    if (state.stopped) return;
    if (!info.isFile() || info.size > MAX_CONTROL_BYTES) throw new Error('control limit');
    const value = JSON.parse(await readFile(controlPath, 'utf8'));
    if (state.stopped) return;
    exactFrame(value, ['schemaVersion', 'runId', 'phase', 'warmup']);
    if (value.schemaVersion !== 1 || value.runId !== state.runId || !PHASES.has(value.phase) || typeof value.warmup !== 'boolean') {
      throw new Error('control shape');
    }
    if (value.phase === state.phase) return;
    state.phase = value.phase;
    if (state.phase === 'unavailable') closePeerSocket(state, 1013, 'fixture unavailable');
    void writeStatus(state, statusPath, 'phase-change');
  } catch {
    if (state.stopped) return;
    state.protocolErrors = increment(state.protocolErrors);
    void writeStatus(state, statusPath, 'control-invalid');
  }
}

function writeStatus(state, statusPath, event) {
  if (state.stopped && event !== 'stopped') return Promise.resolve();
  const record = {
    schemaVersion: 1,
    event,
    phase: state.phase,
    listening: !state.stopped,
    authenticated: state.authenticated,
    requestCount: state.requestCount,
    answerCount: state.answerCount,
    delayedCount: state.stalledCount,
    overflowFailureCount: state.overflowFailureCount,
    requestsByPhase: state.requestsByPhase,
    delayedRequestsByPhase: state.delayedRequestsByPhase,
    delayedPendingHighWaterByPhase: state.delayedPendingHighWaterByPhase,
    protocolErrors: state.protocolErrors,
  };
  const bytes = `${JSON.stringify(record)}\n`;
  const write = state.statusWriteTail.then(async () => {
    try {
      if (!state.statusCreated) {
        await writeFile(statusPath, bytes, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
        state.statusCreated = true;
        return;
      }
      const temporary = `${statusPath}.${randomUUID()}.tmp`;
      await writeFile(temporary, bytes, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
      await rename(temporary, statusPath);
    } catch {
      state.protocolErrors = increment(state.protocolErrors);
    }
  });
  state.statusWriteTail = write;
  return write;
}

function releaseSocket(state, socket, statusPath) {
  if (socket !== state.socket) return;
  clearTimeout(state.handshakeTimer);
  for (const pending of state.pending.values()) clearTimeout(pending.timer);
  state.pending.clear();
  state.retainedRequests.clear();
  state.socket = null;
  state.authenticated = false;
  void writeStatus(state, statusPath, 'disconnected');
}

function closePeerSocket(state, code, reason) {
  const socket = state.socket;
  if (!socket) return;
  try { socket.close(code, reason); } catch { socket.terminate(); }
}

function closeFixturePeer(state, statusPath) {
  if (state.closePromise) return state.closePromise;
  state.stopped = true;
  clearInterval(state.controlTimer);
  clearTimeout(state.handshakeTimer);
  for (const pending of state.pending.values()) clearTimeout(pending.timer);
  state.pending.clear();
  state.retainedRequests.clear();
  for (const socket of state.server.clients) {
    try { socket.terminate(); } catch { /* status remains fail-closed */ }
  }
  state.socket = null;
  state.closePromise = (async () => {
    await new Promise((resolveClose) => state.server.close(() => resolveClose()));
    await Promise.allSettled([...state.controlReads]);
    await state.statusWriteTail;
    await writeStatus(state, statusPath, 'stopped');
  })();
  return state.closePromise;
}

function publicStatus(state) {
  return Object.freeze({
    phase: state.phase,
    authenticated: state.authenticated,
    requestCount: state.requestCount,
    answerCount: state.answerCount,
    delayedCount: state.stalledCount,
    overflowFailureCount: state.overflowFailureCount,
    requestsByPhase: { ...state.requestsByPhase },
    delayedRequestsByPhase: { ...state.delayedRequestsByPhase },
    delayedPendingHighWaterByPhase: { ...state.delayedPendingHighWaterByPhase },
    protocolErrors: state.protocolErrors,
    stopped: state.stopped,
  });
}

function validateConfig(config) {
  exactFrame(config, ['schemaVersion', 'runId', 'port', 'secret', 'manifestSha256', 'binding', 'authorizedPlayerIds']);
  exactFrame(config.binding, ['serverId', 'worldId', 'characterId', 'agentId']);
  if (config.schemaVersion !== 1 || !/^[a-f0-9-]{1,64}$/.test(config.runId) || config.manifestSha256 !== MANIFEST_SHA256 ||
      !Number.isInteger(config.port) || config.port < 1024 || config.port > 65535 ||
      typeof config.secret !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(config.secret) ||
      Buffer.from(config.secret, 'base64url').length !== 32 ||
      !Array.isArray(config.authorizedPlayerIds) || config.authorizedPlayerIds.length < 1 || config.authorizedPlayerIds.length > 8 ||
      config.authorizedPlayerIds.some((id) => !UUID.test(id)) || new Set(config.authorizedPlayerIds).size !== config.authorizedPlayerIds.length ||
      Object.values(config.binding).some((value) => typeof value !== 'string' || !/^[A-Za-z0-9._:-]{1,128}$/.test(value))) {
    throw codedError('fixture-peer-config-invalid');
  }
}

function welcomeFacts(binding, manifestSha256) {
  return {
    deploymentId: DEPLOYMENT.deploymentId,
    manifestSha256,
    protocol: DEPLOYMENT.companion.protocol,
    hostVersion: DEPLOYMENT.openclaw.version,
    nodeVersion: process.versions.node,
    companionVersion: DEPLOYMENT.companion.version,
    ...binding,
  };
}

function challengeTranscript(challenge, facts) {
  return [
    DEPLOYMENT.companion.protocol, 'challenge', challenge, facts.deploymentId, facts.manifestSha256,
    facts.protocol, facts.hostVersion, facts.companionVersion, facts.nodeVersion,
    facts.serverId, facts.worldId, facts.characterId, facts.agentId,
  ].join('\n');
}

function serverTranscript(challenge, nonce, facts) {
  return [
    DEPLOYMENT.companion.protocol, 'server', challenge, nonce, facts.deploymentId, facts.manifestSha256,
    facts.protocol, facts.hostVersion, facts.companionVersion, facts.nodeVersion,
    facts.serverId, facts.worldId, facts.characterId, facts.agentId,
  ].join('\n');
}

function proof(secret, transcript) {
  return createHmac('sha256', Buffer.from(secret, 'base64url')).update(transcript, 'utf8').digest('base64url');
}

function verifyProof(secret, transcript, supplied) {
  if (typeof supplied !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(supplied)) return false;
  const expected = Buffer.from(proof(secret, transcript), 'base64url');
  const observed = Buffer.from(supplied, 'base64url');
  return expected.length === observed.length && timingSafeEqual(expected, observed);
}

function sendJson(socket, frame) {
  if (socket.readyState !== WebSocket.OPEN) return false;
  const value = JSON.stringify(frame);
  const bytes = Buffer.byteLength(value, 'utf8');
  if (bytes > MAX_FRAME_BYTES || socket.bufferedAmount + bytes > MAX_FRAME_BYTES * 16) return false;
  try {
    socket.send(value, { compress: false }, () => {});
    return true;
  } catch {
    return false;
  }
}

function rejectFrame(state, socket, statusPath) {
  state.protocolErrors = increment(state.protocolErrors);
  closePeerSocket(state, 4002, 'fixture protocol rejected');
  void writeStatus(state, statusPath, 'protocol-rejected');
}

function exactFrame(value, expected) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error('frame shape');
  const actual = Object.keys(value).sort();
  const fields = [...expected].sort();
  if (actual.length !== fields.length || actual.some((field, index) => field !== fields[index])) throw new Error('frame fields');
}

function sameFields(actual, expected) {
  const sorted = [...expected].sort();
  return actual.length === sorted.length && actual.every((field, index) => field === sorted[index]);
}

function increment(value) {
  return value === Number.MAX_SAFE_INTEGER ? value : value + 1;
}

function isAbsolutePath(value) {
  return typeof value === 'string' && (value.startsWith('/') || /^[A-Za-z]:[\\/]/.test(value));
}

function safeOption(value) {
  return typeof value === 'string' ? value.replace(/[^a-z0-9-]/gi, '').slice(2, 42) || 'invalid' : 'invalid';
}

function codedError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

async function main() {
  const options = parsePeerArgs(process.argv.slice(2));
  if (options.help) {
    process.stdout.write('Usage: node tick-load-peer.mjs --config <absolute-private-json> --control <absolute-json> --status <absolute-json> [--stall-health]\n');
    return;
  }
  const info = await stat(options.configPath);
  if (!info.isFile() || info.size > 4096 || (info.mode & 0o077) !== 0) throw codedError('fixture-peer-config-permissions-invalid');
  const config = JSON.parse(await readFile(options.configPath, 'utf8'));
  const peer = await startFixturePeer({
    config, controlPath: options.controlPath, statusPath: options.statusPath, stallHealth: options.stallHealth,
  });
  const shutdown = () => { void peer.close().finally(() => process.exit(0)); };
  process.once('SIGTERM', shutdown);
  process.once('SIGINT', shutdown);
  process.once('uncaughtException', () => { void peer.close().finally(() => process.exit(1)); });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    process.stderr.write(`WS-TICK-LOAD-PEER-FAILED ${/^[a-z0-9-]+$/.test(error?.code ?? '') ? error.code : 'fixture-peer-failed'}\n`);
    process.exitCode = 2;
  });
}
