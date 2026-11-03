import assert from 'node:assert/strict';
import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { chmod, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import type { OpenClawConfig } from 'openclaw/plugin-sdk/channel-core';
import { buildChannelInboundEventContext, type BuildChannelInboundEventContextParams } from 'openclaw/plugin-sdk/channel-inbound';
import { resolveAgentRoute } from 'openclaw/plugin-sdk/routing';
import { buildAbilityDiscovery } from '../ability-discovery.js';
import { OBSERVE_PLAYER_ABILITY, STRIKE_LIGHTNING_ABILITY } from '../ability-policy.js';
import { resolveAbilityPolicy } from '../ability-policy-resolver.js';
import {
  validateAbilityIntent,
  type AbilityTrustedContextHandle,
} from '../ability-validation.js';
import { handlePlayerRequest } from '../channel.js';
import { DEPLOYMENT } from '../generated/deployment.js';
import { loadStartupConfig, type StartupConfigSnapshot } from '../startup-config.js';
import type { DialogueRequest } from '../protocol.js';

const BINDING = Object.freeze({
  serverId: 'server-startup-abil02',
  worldId: 'world-startup-abil02',
  characterId: 'character-startup-abil02',
  agentId: 'agent-startup-abil02',
});
const PLAYER_A = '00000000-0000-4000-8000-000000000041';
const PLAYER_B = '00000000-0000-4000-8000-000000000042';
const TARGET = '00000000-0000-4000-8000-000000000043';
const SERVER_EPOCH = '00000000-0000-4000-8000-000000000044';

test('actual startup loader feeds the resolver, current-requester discovery, deny gate, and SDK BodyForAgent A/B path', async () => {
  const fixture = await privateStartupFixture();
  try {
    const loaded = await loadStartupConfig(fixture.configFile);
    assert.equal(loaded.abilityPolicy.revision, 23);
    assert.equal(loaded.abilityPolicy.grants.length, 3);
    assert.equal(Object.isFrozen(loaded.abilityPolicy.grants[0]!.limits), true);
    assert.equal(Object.isFrozen(loaded.abilityPolicy.grants[0]!.region), true);

    const liveA = { current: true };
    const liveB = { current: true };
    const requestA = request(PLAYER_A, loaded.abilityPolicy.revision);
    const requestB = request(PLAYER_B, loaded.abilityPolicy.revision);
    const contextA = Object.freeze(Object.create(null)) as AbilityTrustedContextHandle;
    const contextB = Object.freeze(Object.create(null)) as AbilityTrustedContextHandle;
    const selectedA = resolveAbilityPolicy({
      binding: loaded.binding,
      policy: loaded.abilityPolicy,
      policyRevision: loaded.abilityPolicy.revision,
      authorityCurrent: true,
      requesterId: PLAYER_A,
      invocationId: requestA.invocationId,
      abilityId: OBSERVE_PLAYER_ABILITY,
      contractVersion: 1,
      targetPlayerId: PLAYER_A,
      facts: worldFacts(PLAYER_A, 3),
    });
    assert.equal(selectedA.status, 'CONDITIONAL');
    assert.equal(selectedA.selectedGrantId, 'a-observe-requester');
    assert.deepEqual(selectedA.effectiveRestrictions?.region,
      { minX: -10, maxXExclusive: 10, minZ: -10, maxZExclusive: 10 });
    assert.deepEqual(selectedA.effectiveRestrictions?.worldBorder,
      { minX: -100, maxXExclusive: 100, minZ: -100, maxZExclusive: 100 });
    assert.deepEqual(selectedA.effectiveRestrictions?.effectiveSpatialBounds,
      { minX: -10, maxXExclusive: 10, minZ: -10, maxZExclusive: 10 });
    assert.ok(Object.isFrozen(selectedA.effectiveRestrictions?.worldBorder));
    assert.ok(Object.isFrozen(selectedA.effectiveRestrictions?.effectiveSpatialBounds));
    assert.deepEqual(selectedA.effectiveRestrictions?.limits,
      { requesterIntervalMs: 5_000, characterIntervalMs: 500 });
    assert.equal(selectedA.limitDemand?.requesterIntervalMs, 5_000);
    assert.ok(selectedA.unresolvedChecks.includes('normative_world_identity_mapping'));
    assert.ok(selectedA.unresolvedChecks.includes('atomic_limit_admission'));

    const intentA = validateAbilityIntent(intentBytes(PLAYER_A));
    assert.equal(intentA.status, 'VALID');
    if (intentA.status !== 'VALID') throw new Error('expected a valid fixture intent');

    const discoveryA = buildAbilityDiscovery({ binding: loaded.binding, request: requestA,
      policy: loaded.abilityPolicy, isLive: () => liveA.current }).value;
    const discoveryB = buildAbilityDiscovery({ binding: loaded.binding, request: requestB,
      policy: loaded.abilityPolicy, isLive: () => liveB.current }).value;
    assert.equal(discoveryA.policyRevision, 23);
    assert.deepEqual(discoveryA.entries[0]!.effectiveGrants.map((grant) => grant.grantId), ['a-observe-requester']);
    assert.deepEqual(discoveryB.entries[0]!.effectiveGrants.map((grant) => grant.grantId), ['b-observe-requester']);
    assert.equal(discoveryA.entries[1]!.availability, 'UNAVAILABLE');
    assert.equal(discoveryA.entries[1]!.implementationStatus, 'UNIMPLEMENTED');
    assert.equal(discoveryA.entries[1]!.qualificationStatus, 'UNQUALIFIED');

    const dispatches: Array<{ playerId: string; body: string; tools: readonly string[]; target: string | undefined }> = [];
    await dispatchThroughSdk(loaded, requestA, discoveryA, contextA, () => liveA.current, dispatches);
    await dispatchThroughSdk(loaded, requestB, discoveryB, contextB, () => liveB.current, dispatches);
    assert.equal(dispatches.length, 2);
    assert.deepEqual(dispatches.map((entry) => entry.tools), [[], []]);
    assert.deepEqual(dispatches.map((entry) => entry.target), [`worldspeaker:${PLAYER_A}`, `worldspeaker:${PLAYER_B}`]);
    const bodyA = bodyPayload(dispatches[0]!.body);
    const bodyB = bodyPayload(dispatches[1]!.body);
    assert.equal(bodyA.requestId, requestA.requestId);
    assert.equal(bodyA.event.playerId, PLAYER_A);
    assert.deepEqual(bodyA.abilityDiscovery.entries[0].effectiveGrants.map((grant: { grantId: string }) => grant.grantId),
      ['a-observe-requester']);
    assert.equal(dispatches[0]!.body.includes('b-observe-requester'), false);
    assert.equal(bodyB.requestId, requestB.requestId);
    assert.equal(bodyB.event.playerId, PLAYER_B);
    assert.deepEqual(bodyB.abilityDiscovery.entries[0].effectiveGrants.map((grant: { grantId: string }) => grant.grantId),
      ['b-observe-requester']);
    assert.equal(dispatches[1]!.body.includes('a-observe-requester'), false);

    liveA.current = false; // coordinated restart revokes the generation before its replacement is admitted
    assert.equal(validateAbilityIntent(intentBytes(PLAYER_A)).status, 'VALID');
    assert.throws(() => buildAbilityDiscovery({ binding: loaded.binding, request: requestA,
      policy: loaded.abilityPolicy, isLive: () => liveA.current }), /current authenticated request context/u);

    const edited = JSON.parse(await readFile(fixture.configFile, 'utf8')) as Record<string, any>;
    edited.server.characterDisplayName = 'The Archivist';
    await replacePrivate(fixture.configFile, Buffer.from(JSON.stringify(edited), 'utf8'));
    const sameRevision = await loadStartupConfig(fixture.configFile);
    assert.equal(sameRevision.abilityPolicy.revision, loaded.abilityPolicy.revision);
    assert.notEqual(sameRevision.configSetSha256, loaded.configSetSha256,
      'equal policy revisions do not establish exact-bundle handshake coherence');
    assert.equal(createHash('sha256').update(await readFile(fixture.configFile)).digest('hex'), sameRevision.configSetSha256);

    edited.abilityPolicy.revision = 24;
    edited.abilityPolicy.denials = [{ id: 'deny-observe-after-restart', abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1 }];
    await replacePrivate(fixture.configFile, Buffer.from(JSON.stringify(edited), 'utf8'));
    const revoked = await loadStartupConfig(fixture.configFile);
    const requestAfterRestart = request(PLAYER_A, revoked.abilityPolicy.revision);
    const contextAfterRestart = Object.freeze(Object.create(null)) as AbilityTrustedContextHandle;
    const revokedDiscovery = buildAbilityDiscovery({ binding: revoked.binding, request: requestAfterRestart,
      policy: revoked.abilityPolicy, isLive: () => true }).value;
    assert.equal(revokedDiscovery.policyRevision, 24);
    assert.equal(revokedDiscovery.entries[0]!.policyDisposition, 'denied');
    assert.deepEqual(revokedDiscovery.entries[0]!.effectiveGrants, []);
    assert.equal(loaded.abilityPolicy.revision, 23, 'editing/reloading does not mutate a captured snapshot');
  } finally {
    await rm(fixture.directory, { recursive: true, force: true });
  }
});

async function dispatchThroughSdk(loaded: StartupConfigSnapshot, incoming: DialogueRequest,
  discovery: ReturnType<typeof buildAbilityDiscovery>['value'], abilityContext: AbilityTrustedContextHandle,
  isLive: () => boolean,
  results: Array<{ playerId: string; body: string; tools: readonly string[]; target: string | undefined }>): Promise<void> {
  const account = { accountId: 'default', binding: loaded.binding, abilityPolicy: loaded.abilityPolicy } as
    Parameters<typeof handlePlayerRequest>[1];
  const cfg = {} as OpenClawConfig;
  const runtime = {
    routing: { resolveAgentRoute: (input: Parameters<typeof resolveAgentRoute>[0]) => resolveAgentRoute(input) },
    inbound: {
      ingress: { resolve: async () => ({ ingress: { admission: 'dispatch', decision: 'allow' } }) },
      buildContext: (input: BuildChannelInboundEventContextParams) => buildChannelInboundEventContext(input),
    },
    reply: {
      dispatchReplyWithBufferedBlockDispatcher: async (input: any) => {
        results.push({ playerId: incoming.event.playerId, body: input.ctx.BodyForAgent,
          tools: input.toolsAllow, target: input.ctx.To ?? input.ctx.input?.reply.deliveryTarget });
        await input.dispatcherOptions.deliver({ text: `Complete answer for ${incoming.event.playerName}` }, { kind: 'final' });
      },
    },
  } as unknown as Parameters<typeof handlePlayerRequest>[2];
  await handlePlayerRequest(cfg, account, runtime, incoming, () => 'fixture-message-id', isLive,
    async () => {}, undefined, discovery, abilityContext);
}

function bodyPayload(body: string): any {
  return JSON.parse(body.slice(body.lastIndexOf('\n') + 1));
}

function request(playerId: string, policyRevision: number): DialogueRequest {
  const requestId = randomUUID();
  let invocationId = randomUUID();
  while (requestId === invocationId) invocationId = randomUUID();
  return {
    type: 'dialogue.request', protocol: DEPLOYMENT.protocol, requestId, invocationId, abilityPolicyRevision: policyRevision,
    ...BINDING,
    event: { ...BINDING, eventId: randomUUID(), serverEpochId: SERVER_EPOCH,
      occurredAtUnixMillis: 1_729_500_000_000, capturedAtMonotonicNanos: '1234567890123456789',
      eventOrigin: 'live', inputKind: 'command', playerId, playerName: playerId === PLAYER_A ? 'PlayerA' : 'PlayerB',
      dimension: 'minecraft:overworld', text: `request from ${playerId}` },
    conversation: { conversationId: randomUUID(), replyToMessageId: null },
    replyAudience: { kind: 'player', playerId }, contextBatch: [],
    contextStatus: { state: 'disabled', lossRevision: '0', reasons: [] }, acknowledgement: null, progressContext: [],
  };
}

function intentBytes(targetPlayerId: string): Uint8Array {
  return Buffer.from(JSON.stringify({ abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1,
    arguments: { targetPlayerId } }), 'utf8');
}

function worldFacts(targetPlayerId: string, x: number) {
  return {
    currentWorldMatches: true, requestLive: true, requesterConnected: true, targetResolved: true,
    targetPlayerId, targetConnected: true, targetAlive: true, targetNotSpectating: true,
    targetChunkLoadedAndTicking: true, requesterDimension: 'minecraft:overworld', targetDimension: 'minecraft:overworld',
    requesterFeet: { x: 0, y: 64, z: 0 }, targetFeet: { x, y: 64, z: 0 },
    worldBorder: { minX: -100, maxXExclusive: 100, minZ: -100, maxZExclusive: 100 },
    lightningSkyAccess: true, protectedSpawnClear: true, protectedClaimsClear: true,
  } as const;
}

async function privateStartupFixture(): Promise<{ directory: string; configFile: string }> {
  const physicalTmp = await realpath(tmpdir());
  const directory = await mkdtemp(join(physicalTmp, 'worldspeaker-abil02-startup-'));
  await chmod(directory, 0o700);
  const credentialFile = join(directory, 'credential.txt');
  await writeFile(credentialFile, randomBytes(32).toString('base64url'), { mode: 0o600 });
  await chmod(credentialFile, 0o600);
  const configFile = join(directory, 'startup.json');
  const bundle = {
    schemaVersion: 1,
    binding: BINDING,
    server: { endpoint: 'ws://127.0.0.1:18473', acknowledgementTemplate: 'Hello {player}',
      characterDisplayName: 'Archivist', conversationAllowedPlayers: [] },
    companion: { bindHost: '127.0.0.1', port: 18_473, deploymentId: DEPLOYMENT.deploymentId,
      manifestSha256: DEPLOYMENT.manifestSha256 },
    abilityPolicy: {
      schemaVersion: 1,
      revision: 23,
      grants: [
        observeGrant('a-observe-requester', PLAYER_A, { otherTargetIds: [TARGET], maxRangeBlocks: 48,
          region: { minX: -10, maxXExclusive: 10, minZ: -10, maxZExclusive: 10 },
          limits: { requesterIntervalMs: 5_000, characterIntervalMs: 500 } }),
        observeGrant('b-observe-requester', PLAYER_B, { maxRangeBlocks: 12,
          region: { minX: -20, maxXExclusive: 20, minZ: -20, maxZExclusive: 20 } }),
        lightningGrant('a-lightning-requester', PLAYER_A),
      ],
      denials: [],
      protection: { externalClaimsMode: 'NONE' },
    },
    credentialFile,
  };
  await writeFile(configFile, JSON.stringify(bundle), { mode: 0o600 });
  await chmod(configFile, 0o600);
  return { directory, configFile };
}

async function replacePrivate(path: string, bytes: Buffer): Promise<void> {
  await writeFile(path, bytes, { mode: 0o600 });
  await chmod(path, 0o600);
}

function observeGrant(id: string, requesterId: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return { id, characterId: BINDING.characterId, abilityId: OBSERVE_PLAYER_ABILITY,
    contractVersion: 1, requesterIds: [requesterId], dimensions: ['minecraft:overworld'], ...overrides };
}

function lightningGrant(id: string, requesterId: string): Record<string, unknown> {
  return { id, characterId: BINDING.characterId, abilityId: STRIKE_LIGHTNING_ABILITY,
    contractVersion: 1, requesterIds: [requesterId], dimensions: ['minecraft:overworld'],
    allowVisual: true, limits: { requesterCooldownMs: 45_000, targetCooldownMs: 60_000, characterIntervalMs: 2_000 } };
}
