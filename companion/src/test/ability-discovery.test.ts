import assert from 'node:assert/strict';
import test from 'node:test';
import { assertValidAbilityDiscovery } from '../ability-catalogue.js';
import { buildAbilityDiscovery } from '../ability-discovery.js';
import { parseAbilityPolicy, OBSERVE_PLAYER_ABILITY, STRIKE_LIGHTNING_ABILITY } from '../ability-policy.js';
import type { DialogueRequest } from '../protocol.js';

const REQUESTER = '00000000-0000-4000-8000-000000000041';
const OTHER_REQUESTER = '00000000-0000-4000-8000-000000000042';
const TARGET = '00000000-0000-4000-8000-000000000043';
const REQUEST_ID = '00000000-0000-4000-8000-000000000061';
const INVOCATION_ID = '00000000-0000-4000-8000-000000000062';
const EPOCH_ID = '00000000-0000-4000-8000-000000000063';
const BINDING = Object.freeze({ serverId: 'server-test', worldId: 'world-test', characterId: 'character-test', agentId: 'agent-test' });

test('discovery includes both unavailable contracts and projects only the current requesters complete grants', () => {
  const policy = parseAbilityPolicy({
    schemaVersion: 1, revision: 7,
    grants: [
      observeGrant('z-observe', REQUESTER, { otherTargetIds: [TARGET], dimensions: ['minecraft:overworld'], maxRangeBlocks: 48 }),
      observeGrant('a-other-requester', OTHER_REQUESTER, { otherTargetIds: [TARGET], dimensions: ['minecraft:the_nether'], maxRangeBlocks: 2 }),
      lightningGrant('lightning-visual', REQUESTER, { allowDamage: true, maxDamage: 4.5 }),
    ],
    denials: [{ id: 'deny-lightning', abilityId: STRIKE_LIGHTNING_ABILITY, contractVersion: 1 }],
    protection: { externalClaimsMode: 'NONE' },
  }, BINDING.characterId);
  let live = true;
  const result = buildAbilityDiscovery({ binding: BINDING, request: request(policy.revision), policy, isLive: () => live });
  assertValidAbilityDiscovery(result.value);
  assert.equal(result.utf8Bytes, Buffer.byteLength(result.json, 'utf8'));
  assert.ok(result.utf8Bytes <= 65_536);
  assert.equal(result.value.catalogueVersion, 1);
  assert.equal(result.value.policyRevision, 7);
  assert.deepEqual(result.value.entries.map((entry) => entry.contract.abilityId), [OBSERVE_PLAYER_ABILITY, STRIKE_LIGHTNING_ABILITY]);
  assert.ok(result.value.entries.every((entry) => entry.implementationStatus === 'UNIMPLEMENTED' &&
    entry.qualificationStatus === 'UNQUALIFIED' && entry.availability === 'UNAVAILABLE'));

  const observe = result.value.entries[0]!;
  assert.equal(observe.policyDisposition, 'conditional');
  assert.deepEqual(observe.effectiveGrants.map((grant) => grant.grantId), ['z-observe']);
  assert.deepEqual(observe.effectiveGrants[0]!.otherTargetIds, [TARGET]);
  assert.deepEqual(observe.effectiveGrants[0]!.dimensions, ['minecraft:overworld']);
  assert.equal(observe.effectiveGrants[0]!.maxRangeBlocks, 48);
  assert.equal(Object.hasOwn(observe.effectiveGrants[0]!, 'worldBorder'), false);
  assert.equal(Object.hasOwn(observe.effectiveGrants[0]!, 'effectiveSpatialBounds'), false);
  assert.equal(observe.disabledReasons.includes('unimplemented'), true);
  assert.equal(observe.disabledReasons.includes('unqualified'), true);
  assert.equal(JSON.stringify(result.value).includes(OTHER_REQUESTER), false);

  const lightning = result.value.entries[1]!;
  assert.equal(lightning.policyDisposition, 'denied');
  assert.deepEqual(lightning.effectiveGrants, []);
  assert.equal(lightning.disabledReasons.includes('explicit_denial'), true);
  assert.equal(JSON.stringify(result.value).includes('lightning-visual'), false,
    'denial suppresses otherwise complete grants from requester discovery');

  live = false;
  assert.throws(() => buildAbilityDiscovery({ binding: BINDING, request: request(policy.revision), policy, isLive: () => live }),
    /current authenticated request context/u);
});

test('missing grants and complete grants for another requester stay visible as distinct denial reasons', () => {
  const empty = parseAbilityPolicy({ schemaVersion: 1, revision: 0, grants: [], denials: [], protection: {} }, BINDING.characterId);
  const noGrant = buildAbilityDiscovery({ binding: BINDING, request: request(0), policy: empty, isLive: () => true }).value;
  assert.deepEqual(noGrant.entries[0]!.disabledReasons, ['unimplemented', 'unqualified', 'no_grant']);
  assert.equal(noGrant.entries[0]!.policyDisposition, 'denied');

  const otherOnly = parseAbilityPolicy({ schemaVersion: 1, revision: 1,
    grants: [observeGrant('observe-other', OTHER_REQUESTER, { otherTargetIds: [TARGET] })],
    denials: [], protection: {} }, BINDING.characterId);
  const discovery = buildAbilityDiscovery({ binding: BINDING, request: request(1), policy: otherOnly, isLive: () => true }).value;
  assert.deepEqual(discovery.entries[0]!.disabledReasons, ['unimplemented', 'unqualified', 'requester_not_granted']);
  assert.equal(discovery.entries[0]!.policyDisposition, 'denied');
  assert.deepEqual(discovery.entries[0]!.effectiveGrants, []);
});

test('discovery refuses stale revisions, mismatched bindings, non-live requests, and over-ceiling metadata', () => {
  const policy = parseAbilityPolicy({ schemaVersion: 1, revision: 3,
    grants: [observeGrant('observe-current', REQUESTER)], denials: [], protection: {} }, BINDING.characterId);
  assert.throws(() => buildAbilityDiscovery({ binding: BINDING, request: request(2), policy, isLive: () => true }),
    /current authenticated request context/u);
  assert.throws(() => buildAbilityDiscovery({ binding: { ...BINDING, characterId: 'other-character' }, request: request(3), policy, isLive: () => true }),
    /current authenticated request context/u);
  assert.throws(() => buildAbilityDiscovery({ binding: BINDING, request: request(3, 'history'), policy, isLive: () => true }),
    /current authenticated request context/u);

  const oversizedGrants = Array.from({ length: 32 }, (_, index) => observeGrant(`large-${String(index).padStart(2, '0')}`, REQUESTER, {
    dimensions: Array.from({ length: 16 }, (_unused, dimIndex) => `ns${String(dimIndex).padStart(2, '0')}${'a'.repeat(116)}:${'b'.repeat(127)}`),
  }));
  const oversized = parseAbilityPolicy({ schemaVersion: 1, revision: 4, grants: oversizedGrants, denials: [], protection: {} }, BINDING.characterId);
  assert.throws(() => buildAbilityDiscovery({ binding: BINDING, request: request(4), policy: oversized, isLive: () => true }),
    /metadata byte ceiling/u);

  const valid = buildAbilityDiscovery({ binding: BINDING, request: request(3), policy, isLive: () => true }).value;
  assert.throws(() => assertValidAbilityDiscovery({ ...valid, extraAuthority: true }), /invalid ability discovery/u);
});

test('discovery rejects missing schema dependencies, changed refs, and resource hash/content mismatches', () => {
  const policy = parseAbilityPolicy({ schemaVersion: 1, revision: 5,
    grants: [observeGrant('observe-current', REQUESTER)], denials: [], protection: {} }, BINDING.characterId);
  const valid = buildAbilityDiscovery({ binding: BINDING, request: request(5), policy, isLive: () => true }).value;
  assert.equal(valid.schemaResources.length, 3);
  assert.deepEqual(valid.schemaResources.map((resource) => resource.$id), [
    'https://worldspeaker.invalid/schema/ability-predicates-v1.schema.json',
    'https://worldspeaker.invalid/schema/ability-discovery-v1.schema.json',
    'https://worldspeaker.invalid/schema/ability-contracts-v1.json',
  ]);

  const missing = structuredClone(valid) as any;
  missing.schemaResources.pop();
  assert.throws(() => assertValidAbilityDiscovery(missing), /invalid ability discovery/u);

  const changedRef = structuredClone(valid) as any;
  changedRef.entries[0].contract.inputSchema.$ref =
    'https://worldspeaker.invalid/schema/ability-contracts-v1.json#/$defs/missingInput';
  assert.throws(() => assertValidAbilityDiscovery(changedRef), /invalid ability discovery/u);

  const changedHash = structuredClone(valid) as any;
  changedHash.schemaResources[2].schemaJson += ' ';
  assert.throws(() => assertValidAbilityDiscovery(changedHash), /invalid ability discovery schema resources/u);

  const changedResourceAndHash = structuredClone(valid) as any;
  changedResourceAndHash.schemaResources[2].schemaJson += ' ';
  changedResourceAndHash.schemaResources[2].sha256 = '0'.repeat(64);
  assert.throws(() => assertValidAbilityDiscovery(changedResourceAndHash), /invalid ability discovery schema resources/u);
});

function request(policyRevision: number, origin: 'live' | 'history' = 'live'): DialogueRequest {
  return {
    type: 'dialogue.request', protocol: 'worldspeaker.compat.v5', requestId: REQUEST_ID,
    invocationId: INVOCATION_ID, abilityPolicyRevision: policyRevision, ...BINDING,
    event: { ...BINDING, eventId: '00000000-0000-4000-8000-000000000064', serverEpochId: EPOCH_ID,
      occurredAtUnixMillis: 0, capturedAtMonotonicNanos: '0', eventOrigin: origin,
      inputKind: 'command', playerId: REQUESTER, playerName: 'Requester', dimension: 'minecraft:overworld', text: 'test' },
    conversation: { conversationId: '00000000-0000-4000-8000-000000000065', replyToMessageId: null },
    replyAudience: { kind: 'player', playerId: REQUESTER }, contextBatch: [],
    contextStatus: { state: 'disabled', lossRevision: '0', reasons: [] }, acknowledgement: null, progressContext: [],
  };
}

function observeGrant(id: string, requesterId: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return { id, characterId: BINDING.characterId, abilityId: OBSERVE_PLAYER_ABILITY,
    contractVersion: 1, requesterIds: [requesterId], dimensions: ['minecraft:overworld'], ...overrides };
}

function lightningGrant(id: string, requesterId: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return { id, characterId: BINDING.characterId, abilityId: STRIKE_LIGHTNING_ABILITY,
    contractVersion: 1, requesterIds: [requesterId], dimensions: ['minecraft:overworld'],
    allowVisual: true, ...overrides };
}
