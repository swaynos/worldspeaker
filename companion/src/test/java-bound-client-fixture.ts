import { createInterface } from 'node:readline';
import { loadStartupConfig } from '../startup-config.js';
import { WorldspeakerTransport } from '../transport.js';
import { DEPLOYMENT } from '../generated/deployment.js';

const port = Number(process.argv[2]);
const bundleFile = process.argv[4];
const loaded = bundleFile ? await loadStartupConfig(bundleFile) : undefined;
const bearerSecret = loaded?.bearerSecret ?? process.argv[3];
const binding = loaded?.binding ?? {
  serverId: 'server-test', worldId: 'world-test', characterId: 'character-test', agentId: 'agent-test',
};
const serverEndpoint = typeof loaded?.server.endpoint === 'string'
  ? loaded.server.endpoint
  : `ws://127.0.0.1:${port}`;
const MAX_PENDING = 16;
const pending = new Map<string, {
  request: import('../protocol.js').DialogueRequest;
  sendFinal: (text: string) => string | null;
  isLive: () => boolean;
  resolve: () => void;
  peerSerial: number;
}>();
const cancelFrames: Array<Record<string, unknown>> = [];
let readyPeerSerial = 0;
let transport: WorldspeakerTransport | undefined;

function emit(value: Record<string, unknown>): void {
  process.stdout.write(`${JSON.stringify(value)}\n`);
}

if (!Number.isInteger(port) || port < 1024 || port > 65535 || !bearerSecret ||
    loaded !== undefined && loaded.companion.port !== port) {
  throw new Error('cross-peer fixture arguments are invalid');
}

transport = new WorldspeakerTransport({
  enabled: true,
  bindHost: '127.0.0.1',
  port: loaded?.companion.port ?? port,
  serverEndpoint,
  bearerSecret,
  deploymentId: DEPLOYMENT.deploymentId,
  manifestSha256: DEPLOYMENT.manifestSha256,
  configurationHandshakeVersion: 1,
  configSetSha256: loaded?.configSetSha256 ?? '0'.repeat(64),
  ...(loaded === undefined ? {} : { abilityPolicy: loaded.abilityPolicy }),
  binding,
}, DEPLOYMENT.hostVersion, DEPLOYMENT.companionVersion,
async (request, sendFinal, isLive, abilityDiscovery) => {
  if (pending.size >= MAX_PENDING) throw new Error('cross-peer pending request cap reached');
  emit({ kind: 'discovery', requestId: request.requestId, discovery: abilityDiscovery });
  await new Promise<void>((resolve) => {
    pending.set(request.requestId, { request, sendFinal, isLive, resolve, peerSerial: readyPeerSerial });
    emit({
      kind: 'request', peerSerial: readyPeerSerial, protocol: DEPLOYMENT.protocol,
      abilityPolicyRevision: request.abilityPolicyRevision,
      requestId: request.requestId, invocationId: request.invocationId,
      playerId: request.event.playerId,
      inputKind: request.event.inputKind,
      contextEventKinds: request.contextBatch.map((event) =>
        'eventKind' in event ? event.eventKind : event.inputKind),
      contextStatus: request.contextStatus,
    });
  });
}, async (request, context) => {
  emit({
    kind: 'context', requestId: request.requestId, playerId: request.event.playerId,
    contextKind: 'phase' in context ? 'progress' : 'ack',
  });
}, (status, reason) => {
  if (status === 'ready') {
    readyPeerSerial++;
    emit({ kind: 'ready', peerSerial: readyPeerSerial, protocol: DEPLOYMENT.protocol });
  }
  void reason;
});

const internals = transport as unknown as {
  peer?: {
    requests: Map<string, unknown>;
    cancelledRequests: Set<string>;
    retainedEvents: Map<string, string>;
    retainedInvocations: Map<string, string>;
    retainedRequests: Map<string, string>;
  };
  receiveFrame: (peer: object, raw: unknown, isBinary: boolean) => unknown;
};
const receiveFrame = internals.receiveFrame.bind(transport);
const peerIds = new WeakMap<object, number>();
let nextPeerId = 0;
internals.receiveFrame = (peer, raw, isBinary) => {
  let peerId = peerIds.get(peer);
  if (peerId === undefined) {
    peerId = ++nextPeerId;
    peerIds.set(peer, peerId);
  }
  if (!isBinary) {
    try {
      const bytes = Array.isArray(raw) ? Buffer.concat(raw) : Buffer.from(raw as Uint8Array);
      const frame = JSON.parse(bytes.toString('utf8')) as Record<string, unknown>;
      if (frame.type === 'dialogue.cancel') {
        const record = {
          peerId, type: frame.type, protocol: frame.protocol,
          requestId: frame.requestId, invocationId: frame.invocationId,
          playerId: frame.playerId, fields: Object.keys(frame).sort(),
        };
        cancelFrames.push(record);
        emit({ kind: 'cancel', ...record });
      } else if (frame.type === 'dialogue.ack' || frame.type === 'dialogue.progress') {
        emit({
          kind: frame.type === 'dialogue.ack' ? 'ack' : 'progress',
          requestId: frame.requestId, invocationId: frame.invocationId,
          playerId: frame.playerId, peerId,
        });
      }
    } catch {
      // The production parser remains authoritative for malformed frames.
    }
  }
  return receiveFrame(peer, raw, isBinary);
};

await transport.start();
emit({ kind: 'started', protocol: DEPLOYMENT.protocol });
const input = createInterface({ input: process.stdin });
input.on('line', (line) => {
  void (async () => {
    if (line.startsWith('CHECK ')) {
      const requestId = line.slice(6);
      const record = pending.get(requestId);
      const deadline = Date.now() + 2500;
      while (record && record.isLive() && Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, 10));
      }
      const peer = internals.peer;
      const retainedCounts = [peer?.retainedEvents.size ?? 0, peer?.retainedInvocations.size ?? 0,
        peer?.retainedRequests.size ?? 0];
      if (retainedCounts.some((count) => count > 256)) throw new Error('production anti-replay cap exceeded');
      emit({
        kind: 'liveness', requestId,
        live: record?.isLive() ?? null,
        cancelledRetained: peer?.cancelledRequests.has(requestId) ?? false,
        requestRetained: peer?.requests.has(requestId) ?? false,
        peerSerial: record?.peerSerial ?? null,
      });
      return;
    }
    if (line.startsWith('FINAL ')) {
      const requestId = line.slice(6);
      const record = pending.get(requestId);
      const messageId = record?.sendFinal('A delayed answer after cancellation.');
      emit({ kind: 'final', requestId, accepted: messageId !== null && messageId !== undefined });
      record?.resolve();
      pending.delete(requestId);
      return;
    }
    if (line.startsWith('CLEAR ')) {
      const requestId = line.slice(6);
      const record = pending.get(requestId);
      record?.resolve();
      pending.delete(requestId);
      emit({ kind: 'cleared', requestId, pendingCount: pending.size });
      return;
    }
    if (line === 'SNAPSHOT') {
      const peer = internals.peer;
      emit({
        kind: 'snapshot', readyPeerSerial,
        peerRequestIds: peer ? [...peer.requests.keys()] : [],
        peerCancelledRequestIds: peer ? [...peer.cancelledRequests] : [],
        cancelPeerIds: cancelFrames.map((frame) => frame.peerId),
        latestPeerCancelCount: cancelFrames.filter((frame) => frame.peerId === readyPeerSerial).length,
        retainedEventCount: peer?.retainedEvents.size ?? 0,
        retainedInvocationCount: peer?.retainedInvocations.size ?? 0,
        retainedRequestCount: peer?.retainedRequests.size ?? 0,
      });
      return;
    }
    if (line === 'STOP') {
      for (const [requestId, record] of pending) {
        record.resolve();
        pending.delete(requestId);
      }
      await transport?.close();
      emit({ kind: 'stopped', pendingCount: pending.size });
      input.close();
      return;
    }
    emit({ kind: 'driver-error', message: 'unknown fixture command' });
  })().catch((error: unknown) => emit({ kind: 'driver-error', message: String(error) }));
});
