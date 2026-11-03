import assert from 'node:assert/strict';
import test from 'node:test';
import {
  OBSERVE_PLAYER_ABILITY,
  STRIKE_LIGHTNING_ABILITY,
  validateAbilityResultSchema,
  type AbilityResult,
} from '../ability-catalogue.js';

const SERVER_ID = '00000000-0000-4000-8000-000000000041';
const WORLD_ID = '00000000-0000-4000-8000-000000000042';
const CHARACTER_ID = '00000000-0000-4000-8000-000000000043';
const REQUEST_ID = '00000000-0000-4000-8000-000000000044';
const INVOCATION_ID = '00000000-0000-4000-8000-000000000045';
const EPOCH_ID = '00000000-0000-4000-8000-000000000046';
const TARGET_ID = '00000000-0000-4000-8000-000000000047';

test('ability result identity is type-checked before conversion, lookup, schema access, or byte sizing', () => {
  let invocations = 0;
  const coercible = {
    [Symbol.toPrimitive]() { invocations++; return OBSERVE_PLAYER_ABILITY; },
    toString() { invocations++; return OBSERVE_PLAYER_ABILITY; },
    valueOf() { invocations++; return OBSERVE_PLAYER_ABILITY; },
    toJSON() { invocations++; return OBSERVE_PLAYER_ABILITY; },
  };
  const base = observeResult();
  for (const abilityId of [coercible, [], {}, null, true, 1, undefined]) {
    assert.equal(validateAbilityResultSchema({ ...base, abilityId }), false);
  }
  assert.equal(invocations, 0);
});

test('ability result snapshot rejects getters, functions, symbols, and proxies without invoking user code', () => {
  let invocations = 0;
  const throwingGetter = () => {
    invocations++;
    throw new Error('private getter sentinel');
  };

  const identityGetter = observeResult();
  Object.defineProperty(identityGetter, 'abilityId', { enumerable: true, get: throwingGetter });
  assert.equal(validateAbilityResultSchema(identityGetter), false);

  const rootGetter = observeResult();
  Object.defineProperty(rootGetter, 'reasonCode', { enumerable: true, get: throwingGetter });
  assert.equal(validateAbilityResultSchema(rootGetter), false);

  const nestedGetter = observeResult();
  const factsWithGetter = { ...nestedGetter.facts! };
  Object.defineProperty(factsWithGetter, 'targetPlayerId', { enumerable: true, get: throwingGetter });
  assert.equal(validateAbilityResultSchema({ ...nestedGetter, facts: factsWithGetter }), false);

  const stepGetter = lightningResult();
  const step = { kind: 'presentation', state: 'confirmed' } as Record<string, unknown>;
  Object.defineProperty(step, 'kind', { enumerable: true, get: throwingGetter });
  assert.equal(validateAbilityResultSchema({ ...stepGetter, facts: { ...stepGetter.facts!, steps: [step] } }), false);

  const functionValue = observeResult() as unknown as Record<string, unknown>;
  functionValue.toJSON = () => { invocations++; throw new Error('private toJSON sentinel'); };
  functionValue.valueOf = () => { invocations++; throw new Error('private valueOf sentinel'); };
  assert.equal(validateAbilityResultSchema(functionValue), false);

  const symbolValue = observeResult() as unknown as Record<PropertyKey, unknown>;
  symbolValue[Symbol('private')] = 'hidden';
  assert.equal(validateAbilityResultSchema(symbolValue), false);

  const proxyHandler: ProxyHandler<Record<string, unknown>> = {
    get() { invocations++; throw new Error('proxy get sentinel'); },
    getPrototypeOf() { invocations++; throw new Error('proxy prototype sentinel'); },
    ownKeys() { invocations++; throw new Error('proxy keys sentinel'); },
    getOwnPropertyDescriptor() { invocations++; throw new Error('proxy descriptor sentinel'); },
  };
  assert.equal(validateAbilityResultSchema(new Proxy(observeResult(), proxyHandler)), false);
  const nestedProxyBase = observeResult();
  const nestedProxyResult = { ...nestedProxyBase, facts: new Proxy(nestedProxyBase.facts!, proxyHandler) };
  assert.equal(validateAbilityResultSchema(nestedProxyResult), false);

  assert.equal(invocations, 0);
});

test('ability result validation accepts JSON-shaped null-prototype and frozen records without changing caller data', () => {
  const result = observeResult();
  const facts = Object.assign(Object.create(null) as Record<string, unknown>, result.facts);
  const nullPrototypeResult = Object.assign(Object.create(null) as Record<string, unknown>, result, { facts });
  assert.equal(validateAbilityResultSchema(nullPrototypeResult), true);
  assert.equal(Object.getPrototypeOf(nullPrototypeResult), null);
  assert.equal(Object.getPrototypeOf(facts), null);
  assert.equal(Object.isFrozen(nullPrototypeResult), false);

  const frozen = observeResult();
  Object.freeze(frozen.facts);
  Object.freeze(frozen);
  assert.equal(validateAbilityResultSchema(frozen), true);
  assert.equal(Object.isFrozen(frozen), true);
  assert.equal(Object.isFrozen(frozen.facts), true);
});

test('ability result snapshots enforce JSON depth, byte, and node bounds and reject non-JSON values', () => {
  const tooLarge = { ...observeResult(), unused: 'x'.repeat(5_000) };
  assert.equal(validateAbilityResultSchema(tooLarge), false);

  const tooManyKeys = observeResult() as unknown as Record<string, unknown>;
  for (let index = 0; index < 1_100; index += 1) tooManyKeys[`unused${index}`] = null;
  assert.equal(validateAbilityResultSchema(tooManyKeys), false);

  const tooManyNodes = { ...observeResult(), unused: new Array(1_100).fill(null) };
  assert.equal(validateAbilityResultSchema(tooManyNodes), false);

  const sharedChild = [0];
  const repeatedReferences = { ...observeResult(), unused: new Array(500).fill(sharedChild) };
  assert.ok(Buffer.byteLength(JSON.stringify(repeatedReferences), 'utf8') <= 4_096,
    'the shared-reference control stays within the JSON byte budget');
  assert.equal(validateAbilityResultSchema(repeatedReferences), false,
    'shared references consume the bounded node budget for each copied JSON occurrence');

  const cyclic = { ...observeResult() } as Record<string, unknown>;
  cyclic.unused = { parent: cyclic };
  assert.equal(validateAbilityResultSchema(cyclic), false, 'cycles reject at the bounded JSON depth');

  const escapedSurrogates = { ...observeResult(), unused: '\ud800'.repeat(700) };
  assert.equal(validateAbilityResultSchema(escapedSurrogates), false,
    'escaped lone surrogates count their actual JSON-encoded UTF-8 bytes');

  let tooDeep: Record<string, unknown> = {};
  const deepRoot = tooDeep;
  for (let index = 0; index < 9; index++) {
    const child: Record<string, unknown> = {};
    tooDeep.child = child;
    tooDeep = child;
  }
  tooDeep.value = true;
  assert.equal(validateAbilityResultSchema({ ...observeResult(), unused: deepRoot }), false);

  assert.equal(validateAbilityResultSchema({ ...observeResult(), retryAfterMs: Number.NaN }), false);
  assert.equal(validateAbilityResultSchema({ ...observeResult(), retryAfterMs: 1n }), false);
});

function observeResult(): AbilityResult & { abilityId: typeof OBSERVE_PLAYER_ABILITY } {
  return {
    serverId: SERVER_ID,
    worldId: WORLD_ID,
    characterId: CHARACTER_ID,
    requestId: REQUEST_ID,
    invocationId: INVOCATION_ID,
    abilityId: OBSERVE_PLAYER_ABILITY,
    contractVersion: 1,
    policyRevision: 9_007_199_254_740_991,
    serverEpochId: EPOCH_ID,
    state: 'executed',
    reasonCode: 'none',
    observedAt: 9_007_199_254_740_991,
    executedAt: null,
    retryAfterMs: null,
    queryId: '00000000-0000-4000-8000-000000000048',
    facts: {
      targetPlayerId: TARGET_ID,
      observedAt: 9_007_199_254_740_991,
      health: 20,
      maxHealth: 20,
      dimension: 'minecraft:overworld',
      biome: 'minecraft:plains',
      holdingItem: 'minecraft:air',
    },
  };
}

function lightningResult(): Extract<AbilityResult, { abilityId: typeof STRIKE_LIGHTNING_ABILITY }> {
  return {
    serverId: SERVER_ID,
    worldId: WORLD_ID,
    characterId: CHARACTER_ID,
    requestId: REQUEST_ID,
    invocationId: INVOCATION_ID,
    abilityId: STRIKE_LIGHTNING_ABILITY,
    contractVersion: 1,
    policyRevision: 1,
    serverEpochId: EPOCH_ID,
    state: 'executed',
    reasonCode: 'none',
    observedAt: null,
    executedAt: 100,
    retryAfterMs: null,
    actionId: '00000000-0000-4000-8000-000000000049',
    facts: {
      actionId: '00000000-0000-4000-8000-000000000049',
      targetPlayerId: TARGET_ID,
      dimension: 'minecraft:overworld',
      executedAt: 100,
      coordinates: [0, 64, 0],
      visualOnly: true,
      steps: [{ kind: 'presentation', state: 'confirmed' }],
    },
  };
}
