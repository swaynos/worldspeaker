import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import test from 'node:test';
import {
  completeGrantsFor,
  canonicalizeAbilityPolicySnapshot,
  parseAbilityPolicy,
  STRIKE_LIGHTNING_ABILITY,
  OBSERVE_PLAYER_ABILITY,
  type AbilityPolicy,
} from '../ability-policy.js';
import { parseStrictJson } from '../strict-json.js';

const fixturePath = resolve(process.cwd(), '../compatibility/startup-config-v1-fixtures.json');
const fixtureRoot = JSON.parse(await readFile(fixturePath, 'utf8')) as { valid: Array<{ name: string; bundle: any }> };
const numericCorpusPath = resolve(process.cwd(), '../compatibility/ability-policy-v1-numeric-corpus.json');
const numericCorpus = JSON.parse(await readFile(numericCorpusPath, 'utf8')) as {
  valid: Array<{ name: string; rawJson: string }>;
  invalid: Array<{ name: string; rawJson: string }>;
};
const uuidCorpusPath = resolve(process.cwd(), '../compatibility/startup-config-v1-uuid-corpus.json');
const uuidCorpus = JSON.parse(await readFile(uuidCorpusPath, 'utf8')) as {
  valid: Array<{ name: string; value: string }>;
  invalidItems: Array<{ name: string; value: unknown }>;
  invalidLists: Array<{ name: string; value: unknown }>;
};
const characterId = 'fixture-character';
const requester = '00000000-0000-4000-8000-000000000041';

test('parses only complete finite observe and lightning grants without enabling operations', () => {
  const observe = fixtureRoot.valid.find((fixture) => fixture.name === 'complete-observe-grant')!.bundle.abilityPolicy;
  const lightning = fixtureRoot.valid.find((fixture) => fixture.name === 'complete-visual-lightning-grant')!.bundle.abilityPolicy;
  const parsedObserve = parseAbilityPolicy(observe, characterId);
  const parsedLightning = parseAbilityPolicy(lightning, characterId);
  assert.equal(parsedObserve.grants[0]!.abilityId, OBSERVE_PLAYER_ABILITY);
  assert.equal(parsedObserve.grants[0]!.maxRangeBlocks, 48);
  assert.equal(parsedLightning.grants[0]!.allowVisual, true);
  assert.equal(parsedLightning.grants[0]!.allowDamage, false);
  assert.equal(parsedLightning.grants[0]!.maxDamage, null);
  assert.deepEqual(completeGrantsFor(parsedObserve, OBSERVE_PLAYER_ABILITY).map((grant) => grant.id), ['observe-primary']);
  assert.deepEqual(completeGrantsFor(parsedLightning, STRIKE_LIGHTNING_ABILITY).map((grant) => grant.id), ['lightning-visual']);
});

test('explicit denial wins and complete grants are lexicographically selected without field union', () => {
  const policy = parseAbilityPolicy({
    schemaVersion: 1,
    revision: 9,
    grants: [
      observeGrant('z-narrow', { maxRangeBlocks: 2, dimensions: ['minecraft:the_nether'] }),
      observeGrant('a-selected', { maxRangeBlocks: 1, requesterIds: [requester] }),
    ],
    denials: [],
    protection: {},
  }, characterId);
  assert.deepEqual(completeGrantsFor(policy, OBSERVE_PLAYER_ABILITY).map((grant) => grant.id), ['a-selected', 'z-narrow']);
  const denied = parseAbilityPolicy({ ...policyInput(policy), denials: [{ id: 'deny-observe', abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1 }] }, characterId);
  assert.deepEqual(completeGrantsFor(denied, OBSERVE_PLAYER_ABILITY), []);
});

test('stable record IDs are unique across grants and denials', () => {
  assert.throws(() => parseAbilityPolicy({
    schemaVersion: 1, revision: 0,
    grants: [observeGrant('same-id')],
    denials: [{ id: 'same-id', abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1 }],
    protection: {},
  }, characterId));
});

test('unknown names, versions, fields, incomplete grants, and noncanonical identities fail closed', () => {
  const invalid = [
    observeGrant('unknown', { abilityId: 'worldspeaker.core.list_players' }),
    observeGrant('wrong-version', { contractVersion: 2 }),
    observeGrant('extra', { command: 'say hello' }),
    observeGrant('wrong-character', { characterId: 'other-character' }),
    observeGrant('no-requester', { requesterIds: [] }),
    observeGrant('bad-uuid', { requesterIds: ['not-a-uuid'] }),
    observeGrant('dup-requester', { requesterIds: [requester, requester] }),
    observeGrant('bad-dimension', { dimensions: ['Minecraft:Overworld'] }),
    observeGrant('range-zero', { maxRangeBlocks: 0 }),
    observeGrant('range-overflow', { maxRangeBlocks: 64.0001 }),
    lightningGrant('visual-false', { allowVisual: false }),
    lightningGrant('damage-without-ceiling', { allowDamage: true }),
    lightningGrant('damage-zero', { allowDamage: true, maxDamage: 0 }),
    lightningGrant('damage-too-large', { allowDamage: true, maxDamage: 5.001 }),
    lightningGrant('max-without-damage', { maxDamage: 1 }),
  ];
  for (const grant of invalid) {
    const abilityPolicy = { schemaVersion: 1, revision: 0, grants: [grant], denials: [], protection: {} };
    assert.throws(() => parseAbilityPolicy(abilityPolicy, characterId), (error: unknown) => error instanceof Error, JSON.stringify(grant));
  }
});

test('policy and list bounds are finite at the exact edges', () => {
  const exact = parseAbilityPolicy({
    schemaVersion: 1,
    revision: Number.MAX_SAFE_INTEGER,
    grants: [observeGrant('exact-range', { maxRangeBlocks: 64 })],
    denials: [],
    protection: { externalClaimsMode: 'NONE' },
  }, characterId);
  assert.equal(exact.revision, Number.MAX_SAFE_INTEGER);
  assert.equal(exact.grants[0]!.maxRangeBlocks, 64);
  assert.equal(exact.externalClaimsMode, 'NONE');
  assert.throws(() => parseAbilityPolicy({ ...policyInput(exact), revision: Number.MAX_SAFE_INTEGER + 1 }, characterId));
  assert.throws(() => parseAbilityPolicy({ ...policyInput(exact), revision: -1 }, characterId));
  assert.throws(() => parseAbilityPolicy({ ...policyInput(exact), protection: { externalClaimsMode: 'OPTIONAL' } }, characterId));
  assert.throws(() => parseAbilityPolicy({ ...policyInput(exact), protection: { externalClaimsMode: null } }, characterId));
});

test('shared mathematical JSON integer and exact bound corpus is accepted', () => {
  for (const item of numericCorpus.valid) {
    const policy = parseAbilityPolicy(parseStrictJson(item.rawJson), characterId);
    assert.equal(policy.schemaVersion, 1, item.name);
  }
  const negativeZeroCase = numericCorpus.valid.find((item) => item.name === 'negative zero is mathematical zero for revision')!;
  const negativeZero = parseAbilityPolicy(parseStrictJson(negativeZeroCase.rawJson), characterId);
  assert.equal(Object.is(negativeZero.revision, -0), false);
  const safeBoundary = numericCorpus.valid.find((item) => item.name === 'maximum safe revision in exponent notation')!;
  assert.equal(parseAbilityPolicy(parseStrictJson(safeBoundary.rawJson), characterId).revision,
    Number.MAX_SAFE_INTEGER);
});

test('shared numeric corpus rejects rounded fractions, unsafe integers, and exact bound overflows', () => {
  for (const item of numericCorpus.invalid) {
    assert.throws(() => parseAbilityPolicy(parseStrictJson(item.rawJson), characterId),
      (error: unknown) => error instanceof Error, item.name);
  }
});

test('ability-policy UUID lists match the canonical startup-config UUID corpus without coercion', () => {
  const ids = uuidCorpus.valid.map((item) => item.value);
  const valid = parseAbilityPolicy({
    schemaVersion: 1,
    revision: 0,
    grants: [observeGrant('uuid-domain', { requesterIds: ids, otherTargetIds: ids })],
    denials: [],
    protection: {},
  }, characterId);
  assert.deepEqual(valid.grants[0]!.requesterIds, ids);
  assert.deepEqual(valid.grants[0]!.otherTargetIds, ids);

  for (const item of uuidCorpus.invalidItems) {
    for (const field of ['requesterIds', 'otherTargetIds'] as const) {
      assert.throws(() => parseAbilityPolicy({
        schemaVersion: 1,
        revision: 0,
        grants: [observeGrant(`invalid-${field}`, { [field]: [item.value] })],
        denials: [],
        protection: {},
      }, characterId), (error: unknown) => error instanceof Error, `${field}: ${item.name}`);
    }
  }
  for (const item of uuidCorpus.invalidLists) {
    for (const field of ['requesterIds', 'otherTargetIds'] as const) {
      assert.throws(() => parseAbilityPolicy({
        schemaVersion: 1,
        revision: 0,
        grants: [observeGrant(`invalid-list-${field}`, { [field]: item.value })],
        denials: [],
        protection: {},
      }, characterId), (error: unknown) => error instanceof Error, `${field}: ${item.name}`);
    }
  }
  for (const field of ['requesterIds', 'otherTargetIds'] as const) {
    assert.throws(() => parseAbilityPolicy({
      schemaVersion: 1,
      revision: 0,
      grants: [observeGrant(`duplicate-${field}`, { [field]: [ids[0], ids[0]] })],
      denials: [],
      protection: {},
    }, characterId), (error: unknown) => error instanceof Error, `${field}: exact duplicate`);
  }
});

test('regions and ability-specific interval limits default, narrow, and enforce exact finite bounds', () => {
  const defaults = parseAbilityPolicy({ schemaVersion: 1, revision: 0,
    grants: [observeGrant('observe-defaults')], denials: [], protection: {} }, characterId).grants[0]!;
  assert.equal(defaults.region, null);
  assert.deepEqual(defaults.limits, { requesterIntervalMs: 2_000, characterIntervalMs: 250 });

  const narrowed = parseAbilityPolicy({ schemaVersion: 1, revision: 1, grants: [
    observeGrant('observe-narrow', { region: { minX: -64, maxXExclusive: 0, minZ: -8, maxZExclusive: 9 },
      limits: { requesterIntervalMs: 300_000, characterIntervalMs: 250 } }),
    lightningGrant('lightning-defaults', { limits: {} }),
  ], denials: [], protection: {} }, characterId);
  assert.deepEqual(narrowed.grants[0]!.region, { minX: -64, maxXExclusive: 0, minZ: -8, maxZExclusive: 9 });
  assert.deepEqual(narrowed.grants[0]!.limits, { requesterIntervalMs: 300_000, characterIntervalMs: 250 });
  assert.deepEqual(narrowed.grants[1]!.limits, { requesterCooldownMs: 30_000, targetCooldownMs: 30_000, characterIntervalMs: 1_000 });

  const invalid = [
    observeGrant('zero-width', { region: { minX: 1, maxXExclusive: 1, minZ: 0, maxZExclusive: 1 } }),
    observeGrant('unsafe-edge', { region: { minX: Number.MAX_SAFE_INTEGER + 1, maxXExclusive: Number.MAX_SAFE_INTEGER + 2,
      minZ: 0, maxZExclusive: 1 } }),
    observeGrant('small-requester-rate', { limits: { requesterIntervalMs: 1_999 } }),
    observeGrant('large-requester-rate', { limits: { requesterIntervalMs: 300_001 } }),
    observeGrant('arbitrary-rate-key', { limits: { targetCount: 1 } }),
    observeGrant('wrong-ability-rate-key', { limits: { requesterCooldownMs: 30_000 } }),
    lightningGrant('small-character-interval', { limits: { characterIntervalMs: 999 } }),
    lightningGrant('wrong-ability-rate-key', { limits: { requesterIntervalMs: 2_000 } }),
  ];
  for (const grant of invalid) {
    assert.throws(() => parseAbilityPolicy({ schemaVersion: 1, revision: 0, grants: [grant], denials: [], protection: {} }, characterId),
      (error: unknown) => error instanceof Error, JSON.stringify(grant));
  }
});

test('new raw integer tokens reject fractions that JavaScript would round into an integer or safe bound', () => {
  const prefix = `{"schemaVersion":1,"revision":0,"grants":[{"id":"raw","characterId":"${characterId}",` +
    `"abilityId":"${OBSERVE_PLAYER_ABILITY}","contractVersion":1,"requesterIds":["${requester}"],` +
    '"dimensions":["minecraft:overworld"],';
  for (const field of ['"minX":9007199254740992', '"minX":1.0000000000000001',
    '"requesterIntervalMs":2000.0000000000001', '"requesterIntervalMs":9007199254740992']) {
    const raw = field.includes('requesterIntervalMs')
      ? `${prefix}"limits":{${field}},"region":{"minX":-8,"maxXExclusive":8,"minZ":-8,"maxZExclusive":8}}],"denials":[],"protection":{}}`
      : `${prefix}"region":{${field},"maxXExclusive":8,"minZ":-8,"maxZExclusive":8}}],"denials":[],"protection":{}}`;
    assert.throws(() => parseAbilityPolicy(parseStrictJson(raw), characterId), (error: unknown) => error instanceof Error, field);
  }
});

test('direct policy inputs copy region, limits and lists, and reject accessors or proxies without callbacks', () => {
  const requesterIds = [requester];
  const dimensions = ['minecraft:overworld'];
  const region = { minX: -8, maxXExclusive: 8, minZ: -4, maxZExclusive: 4 };
  const limits = { requesterIntervalMs: 4_000, characterIntervalMs: 500 };
  const source = { schemaVersion: 1, revision: 4,
    grants: [observeGrant('direct', { requesterIds, dimensions, region, limits })], denials: [], protection: {} };
  const policy = parseAbilityPolicy(source, characterId);
  requesterIds.length = 0;
  dimensions[0] = 'minecraft:the_nether';
  region.minX = -100;
  limits.requesterIntervalMs = 300_000;
  assert.deepEqual(policy.grants[0]!.requesterIds, [requester]);
  assert.deepEqual(policy.grants[0]!.dimensions, ['minecraft:overworld']);
  assert.deepEqual(policy.grants[0]!.region, { minX: -8, maxXExclusive: 8, minZ: -4, maxZExclusive: 4 });
  assert.deepEqual(policy.grants[0]!.limits, { requesterIntervalMs: 4_000, characterIntervalMs: 500 });
  assert.equal(Object.isFrozen(policy.grants[0]!.region), true);
  assert.equal(Object.isFrozen(policy.grants[0]!.limits), true);

  let proxyCallbacks = 0;
  const hostile = new Proxy({ schemaVersion: 1, revision: 0, grants: [], denials: [], externalClaimsMode: 'REQUIRED' }, {
    get() { proxyCallbacks += 1; throw new Error('must not call a proxy getter'); },
    ownKeys() { proxyCallbacks += 1; throw new Error('must not enumerate a proxy'); },
  });
  assert.throws(() => canonicalizeAbilityPolicySnapshot(hostile, characterId));
  assert.equal(proxyCallbacks, 0);

  const accessor = Object.defineProperty({ schemaVersion: 1, revision: 0, grants: [], denials: [], externalClaimsMode: 'REQUIRED' },
    'revision', { enumerable: true, get: () => { proxyCallbacks += 1; return 0; } });
  assert.throws(() => canonicalizeAbilityPolicySnapshot(accessor, characterId));
  assert.equal(proxyCallbacks, 0);
});

function observeGrant(id: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id,
    characterId,
    abilityId: OBSERVE_PLAYER_ABILITY,
    contractVersion: 1,
    requesterIds: [requester],
    dimensions: ['minecraft:overworld'],
    ...overrides,
  };
}

function lightningGrant(id: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id,
    characterId,
    abilityId: STRIKE_LIGHTNING_ABILITY,
    contractVersion: 1,
    requesterIds: [requester],
    dimensions: ['minecraft:overworld'],
    allowVisual: true,
    ...overrides,
  };
}

function policyInput(policy: AbilityPolicy): Record<string, unknown> {
  return {
    schemaVersion: 1,
    revision: policy.revision,
    grants: policy.grants.map((grant) => ({
      id: grant.id, characterId: grant.characterId, abilityId: grant.abilityId,
      contractVersion: grant.contractVersion, requesterIds: grant.requesterIds,
      dimensions: grant.dimensions,
      ...(grant.otherTargetIds.length === 0 ? {} : { otherTargetIds: grant.otherTargetIds }),
      maxRangeBlocks: grant.maxRangeBlocks,
      ...(grant.abilityId === STRIKE_LIGHTNING_ABILITY ? {
        allowVisual: grant.allowVisual, allowDamage: grant.allowDamage,
        ...(grant.maxDamage === null ? {} : { maxDamage: grant.maxDamage }),
      } : {}),
    })),
    denials: policy.denials,
    protection: { externalClaimsMode: policy.externalClaimsMode },
  };
}
