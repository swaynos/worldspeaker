import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';
import {
  clientTranscript,
  createProof,
  serverChallengeTranscript,
  serverTranscript,
  validateClientHello,
  validateDialogueAcknowledgement,
  validateDialogueCancel,
  validateDialogueProgress,
  validateDialogueRequest,
  verifyProof,
  type Binding,
  type ClientHello,
  type DialogueRequest,
  type WelcomeFacts,
} from '../protocol.js';
import { DEPLOYMENT } from '../generated/deployment.js';

const binding: Binding = {
  serverId: 'server-test',
  worldId: 'world-test',
  characterId: 'character-test',
  agentId: 'agent-test',
};
const secret = randomBytes(32).toString('base64url');
const configSetSha256 = '0'.repeat(64);

function hello(challenge = randomBytes(32).toString('base64url'), nonce = randomBytes(32).toString('base64url')): ClientHello {
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
    configurationHandshakeVersion: 1 as const,
    configSetSha256,
    ...binding,
  };
  return { ...unsigned, proof: createProof(secret, clientTranscript(unsigned)) };
}

test('authenticates an exact candidate client hello and binding', () => {
  const challenge = randomBytes(32).toString('base64url');
  const candidate = hello(challenge);
  assert.deepEqual(validateClientHello(candidate, secret, binding, DEPLOYMENT.manifestSha256, configSetSha256, challenge), candidate);
});

test('rejects altered proofs, mismatched binding, and extra authority fields', () => {
  const challenge = randomBytes(32).toString('base64url');
  const candidate = hello(challenge);
  assert.throws(() => validateClientHello({ ...candidate, proof: 'A'.repeat(43) }, secret, binding, DEPLOYMENT.manifestSha256, configSetSha256, challenge));
  assert.throws(() => validateClientHello(candidate, secret, { ...binding, serverId: 'other' }, DEPLOYMENT.manifestSha256, configSetSha256, challenge));
  assert.throws(() => validateClientHello({ ...candidate, isAdmin: true }, secret, binding, DEPLOYMENT.manifestSha256, configSetSha256, challenge));
  assert.throws(() => validateClientHello(candidate, secret, binding, DEPLOYMENT.manifestSha256, configSetSha256, randomBytes(32).toString('base64url')));
  assert.throws(() => validateClientHello(candidate, secret, binding, DEPLOYMENT.manifestSha256, 'b'.repeat(64), challenge));
});

test('server challenge and response proofs bind the challenge, client nonce, and peer facts', () => {
  const candidate = hello();
  const { challenge, nonce } = candidate;
  const facts: WelcomeFacts = {
    deploymentId: DEPLOYMENT.deploymentId,
    manifestSha256: DEPLOYMENT.manifestSha256,
    protocol: DEPLOYMENT.protocol,
    hostVersion: DEPLOYMENT.hostVersion,
    nodeVersion: process.versions.node,
    companionVersion: DEPLOYMENT.companionVersion,
    configurationHandshakeVersion: 1,
    configSetSha256,
    ...binding,
  };
  const challengeProof = createProof(secret, serverChallengeTranscript(challenge, facts));
  assert.equal(verifyProof(secret, serverChallengeTranscript(challenge, facts), challengeProof), true);
  const changedChallenge = randomBytes(32).toString('base64url');
  assert.equal(verifyProof(secret, serverChallengeTranscript(changedChallenge, facts), challengeProof), false);

  const proof = createProof(secret, serverTranscript(challenge, nonce, facts));
  assert.equal(verifyProof(secret, serverTranscript(challenge, nonce, facts), proof), true);
  const alteredNonce = `${nonce[0] === 'A' ? 'B' : 'A'}${nonce.slice(1)}`;
  assert.equal(verifyProof(secret, serverTranscript(challenge, alteredNonce, facts), proof), false);
  assert.equal(verifyProof(secret, serverTranscript(changedChallenge, nonce, facts), proof), false);
});

test('client and server proof encodings match the Java handshake interoperability vectors', () => {
  const vectorSecret = Buffer.from(Array.from({ length: 32 }, (_, index) => index)).toString('base64url');
  const challenge = Buffer.alloc(32, 0x11).toString('base64url');
  const nonce = Buffer.alloc(32, 0x22).toString('base64url');
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
    configurationHandshakeVersion: 1 as const,
    configSetSha256,
    ...binding,
  };
  const facts: WelcomeFacts = {
    deploymentId: DEPLOYMENT.deploymentId,
    manifestSha256: DEPLOYMENT.manifestSha256,
    protocol: DEPLOYMENT.protocol,
    hostVersion: DEPLOYMENT.hostVersion,
    nodeVersion: DEPLOYMENT.nodeVersion,
    companionVersion: DEPLOYMENT.companionVersion,
    configurationHandshakeVersion: 1,
    configSetSha256,
    ...binding,
  };

  assert.equal(createProof(vectorSecret, clientTranscript(unsigned)), 'htUNjrre8sNnqoPf1GnKHNbKqR5ZVp-En7r_oB5sda8');
  assert.equal(createProof(vectorSecret, serverChallengeTranscript(challenge, facts)), 'kLNAixFZ_hKiR-oNGNQWZ5Z1SxmQcZWW5pie_onysv8');
  assert.equal(createProof(vectorSecret, serverTranscript(challenge, nonce, facts)), 'FcFcBF0ibVF1Zb7G9X0WJD9BijAe_bg32e_ud__109E');
});

test('dialogue envelope preserves exact server monotonic capture value as opaque attributed data', () => {
  const request: DialogueRequest = {
    type: 'dialogue.request',
    protocol: DEPLOYMENT.protocol,
    requestId: '00000000-0000-4000-8000-000000000041',
    invocationId: '00000000-0000-4000-8000-000000000044',
    abilityPolicyRevision: 0,
    ...binding,
    event: {
      ...binding,
      eventId: '00000000-0000-4000-8000-000000000042',
      serverEpochId: '00000000-0000-4000-8000-000000000043',
      occurredAtUnixMillis: 1_729_500_000_000,
      capturedAtMonotonicNanos: '-9223372036854775808',
      eventOrigin: 'live',
      inputKind: 'command',
      playerId: '00000000-0000-4000-8000-000000000045',
      playerName: 'TestPlayer',
      dimension: 'minecraft:overworld',
      text: 'A bounded test prompt',
    },
    conversation: { conversationId: '00000000-0000-4000-8000-000000000046', replyToMessageId: null },
    replyAudience: { kind: 'player', playerId: '00000000-0000-4000-8000-000000000045' },
    contextBatch: [],
    contextStatus: { state: 'disabled', lossRevision: '0', reasons: [] },
    acknowledgement: null,
    progressContext: [],
  };
  assert.deepEqual(validateDialogueRequest(request, binding), request);
  assert.throws(() => validateDialogueRequest({
    ...request,
    event: { ...request.event, eventOrigin: 'history' },
  }, binding));
  assert.throws(() => validateDialogueRequest({ ...request, protocol: 'worldspeaker.compat.v1' }, binding));
  assert.throws(() => validateDialogueRequest({ ...request, event: { ...request.event, capturedAtMonotonicNanos: 100 } }, binding));
  assert.throws(() => validateDialogueRequest({ ...request, event: { ...request.event, capturedAtMonotonicNanos: '9223372036854775808' } }, binding));
  assert.throws(() => validateDialogueRequest({ ...request, event: { ...request.event, capturedAtMonotonicNanos: '-0' } }, binding));
  assert.throws(() => validateDialogueRequest({ ...request, replyAudience: { kind: 'all', playerId: request.event.playerId } }, binding));
  assert.throws(() => validateDialogueRequest({ ...request, nested: { policy: 'admin' } }, binding));
});

test('the retained v4 request fixture is rejected without protocol downgrade', () => {
  const historical = JSON.parse(readFileSync(
    resolve(process.cwd(), '../compatibility/dialogue-v4-fixtures.json'), 'utf8',
  )) as { request: unknown };
  assert.throws(() => validateDialogueRequest(historical.request, binding));
});

test('strict current context accepts inert observations and validates every loss-status boundary', () => {
  const requestId = '00000000-0000-4000-8000-000000000071';
  const invocationId = '00000000-0000-4000-8000-000000000072';
  const playerId = '00000000-0000-4000-8000-000000000073';
  const request: DialogueRequest = {
    type: 'dialogue.request', protocol: DEPLOYMENT.protocol, requestId, invocationId, abilityPolicyRevision: 0, ...binding,
    event: {
      ...binding, eventId: '00000000-0000-4000-8000-000000000074',
      serverEpochId: '00000000-0000-4000-8000-000000000075', occurredAtUnixMillis: 1_729_500_000_000,
      capturedAtMonotonicNanos: '-9223372036854775808', eventOrigin: 'live', inputKind: 'command',
      playerId, playerName: 'PlayerA', dimension: 'minecraft:overworld', text: 'A private prompt',
    },
    conversation: { conversationId: '00000000-0000-4000-8000-000000000076', replyToMessageId: null },
    replyAudience: { kind: 'player', playerId },
    contextBatch: [{
      ...binding, eventId: '00000000-0000-4000-8000-000000000077',
      serverEpochId: '00000000-0000-4000-8000-000000000075', occurredAtUnixMillis: 1_729_499_999_000,
      capturedAtMonotonicNanos: '9223372036854775807', eventOrigin: 'history', source: 'server_callback',
      subject: { playerId, playerName: 'PlayerA' }, dimension: 'minecraft:overworld',
      eventKind: 'joined', details: {},
    }, {
      ...binding, eventId: '00000000-0000-4000-8000-000000000078',
      serverEpochId: '00000000-0000-4000-8000-000000000075', occurredAtUnixMillis: 1_729_499_998_000,
      capturedAtMonotonicNanos: '9223372036854775806', eventOrigin: 'history', inputKind: 'natural_address',
      playerId, playerName: 'PlayerB', dimension: 'minecraft:overworld', text: 'A historical public message',
    }],
    contextStatus: { state: 'incomplete', lossRevision: '1', reasons: ['epoch_start'] },
    acknowledgement: null,
    progressContext: [],
  };

  assert.deepEqual(validateDialogueRequest(request, binding), request);
  assert.equal(request.event.eventOrigin, 'live', 'historical context leaves the primary event live');
  assert.equal(request.event.text, 'A private prompt', 'only the primary event supplies the invocation prompt');
  assert.deepEqual(request.contextBatch.map((event) => event.eventOrigin), ['history', 'history']);

  const progress = {
    messageId: '00000000-0000-4000-8000-000000000079',
    text: '[Worldspeaker] Your request has been forwarded and I am still waiting for a complete answer.',
    dispatchedAtUnixMillis: 1_729_500_000_001,
    dispatchedAtMonotonicNanos: '1234567890123456789',
    replyAudience: { kind: 'player' as const, playerId },
    phase: 'awaiting_answer' as const,
  };
  const progressFrame = {
    type: 'dialogue.progress' as const, protocol: DEPLOYMENT.protocol,
    requestId, invocationId, playerId, progress,
  };
  assert.deepEqual(validateDialogueProgress(progressFrame, request), progressFrame);
  assert.throws(() => validateDialogueProgress({ ...progressFrame, playerId: '00000000-0000-4000-8000-000000000080' }, request));
  assert.throws(() => validateDialogueProgress({ ...progressFrame, progress: { ...progress, phase: 'queued' } }, request));

  const queued = { ...progress, phase: 'queued' as const };
  assert.doesNotThrow(() => validateDialogueRequest({ ...request, progressContext: [queued] }, binding));
  assert.throws(() => validateDialogueRequest({ ...request, progressContext: [progress] }, binding));
  assert.throws(() => validateDialogueRequest({ ...request, progressContext: [queued, { ...queued, messageId: '00000000-0000-4000-8000-000000000081' }] }, binding));
  const acknowledgement = {
    messageId: '00000000-0000-4000-8000-000000000077',
    text: 'I have your request.',
    dispatchedAtUnixMillis: 1_729_500_000_001,
    dispatchedAtMonotonicNanos: '1234567890123456789',
    replyAudience: { kind: 'player' as const, playerId },
  };
  assert.throws(() => validateDialogueRequest({ ...request, acknowledgement,
    progressContext: [{ ...queued, messageId: acknowledgement.messageId }] }, binding));
  assert.deepEqual(validateDialogueRequest({
    ...request,
    contextBatch: [reverseRecord(request.contextBatch[0]!)],
  }, binding).contextBatch[0], reverseRecord(request.contextBatch[0]!));

  for (const mutate of [
    (candidate: any) => { candidate.contextBatch[0].details.coordinates = [1, 2, 3]; },
    (candidate: any) => { candidate.contextBatch[0].source = 'server_sample'; },
    (candidate: any) => { candidate.contextBatch[0].eventKind = 'moved'; },
    (candidate: any) => { candidate.contextStatus.lossRevision = '9223372036854775808'; },
    (candidate: any) => { candidate.contextStatus.reasons.push('epoch_start'); },
    (candidate: any) => { candidate.contextStatus.reasons.push('invented'); },
  ]) {
    const malformed = structuredClone(request);
    mutate(malformed);
    assert.throws(() => validateDialogueRequest(malformed, binding));
  }

  for (const [state, lossRevision, reasons] of [
    ['disabled', '0', []], ['bounded', '9', []], ['incomplete', '0', ['rate']],
  ] as const) {
    const validStatus = { ...request, contextStatus: { state, lossRevision, reasons } };
    assert.doesNotThrow(() => validateDialogueRequest(validStatus, binding));
  }
});

test('acknowledgement and cancellation frames have exact fields and bounded attributed payloads', () => {
  const acknowledgement = {
    type: 'dialogue.ack',
    protocol: DEPLOYMENT.protocol,
    requestId: '00000000-0000-4000-8000-000000000051',
    invocationId: '00000000-0000-4000-8000-000000000054',
    playerId: '00000000-0000-4000-8000-000000000052',
    acknowledgement: {
      messageId: '00000000-0000-4000-8000-000000000053',
      text: 'I have your request.',
      dispatchedAtUnixMillis: 1_729_500_000_001,
      dispatchedAtMonotonicNanos: '1234567890123456789',
      replyAudience: { kind: 'player', playerId: '00000000-0000-4000-8000-000000000052' },
    },
  };
  assert.deepEqual(validateDialogueAcknowledgement(acknowledgement), acknowledgement);
  assert.throws(() => validateDialogueAcknowledgement({ ...acknowledgement, untrusted: true }));
  assert.throws(() => validateDialogueAcknowledgement({
    ...acknowledgement,
    acknowledgement: { ...acknowledgement.acknowledgement, text: 'unsafe\nline' },
  }));

  const cancellation = {
    type: 'dialogue.cancel',
    protocol: DEPLOYMENT.protocol,
    requestId: acknowledgement.requestId,
    invocationId: acknowledgement.invocationId,
    playerId: acknowledgement.playerId,
  };
  assert.deepEqual(validateDialogueCancel(cancellation), cancellation);
  assert.throws(() => validateDialogueCancel({ ...cancellation, reason: 'invented authority' }));
});

function reverseRecord(record: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(record).reverse());
}
