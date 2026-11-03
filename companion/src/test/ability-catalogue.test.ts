import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import test from 'node:test';
import { Ajv2020 } from 'ajv/dist/2020.js';
import {
  abilityContracts,
  assertValidAbilityCatalogue,
  installAbilitySchemaPredicates,
  validateAbilityInputSchema,
  validateAbilityResultSchema,
  OBSERVE_PLAYER_ABILITY,
  STRIKE_LIGHTNING_ABILITY,
  type AbilityResult,
} from '../ability-catalogue.js';
import { abilityDiscoverySchemaResources } from '../ability-schema-resources.js';
import {
  validateAbilityIntent,
} from '../ability-validation.js';

const TARGET = '00000000-0000-4000-8000-0000000000ab';
const REQUESTER = '00000000-0000-4000-8000-000000000041';
const REQUEST_ID = '00000000-0000-4000-8000-000000000061';
const INVOCATION_ID = '00000000-0000-4000-8000-000000000062';
const EPOCH_ID = '00000000-0000-4000-8000-000000000063';
const BINDING = Object.freeze({ serverId: 'server-test', worldId: 'world-test', characterId: 'character-test', agentId: 'agent-test' });

test('canonical catalogue contains both ordered typed contracts and no available operation', () => {
  assert.doesNotThrow(assertValidAbilityCatalogue);
  const contracts = abilityContracts();
  assert.equal(Object.isFrozen(contracts), true);
  assert.equal(Object.isFrozen(contracts[0]!.bounds), true);
  assert.deepEqual(contracts.map((contract) => [contract.abilityId, contract.contractVersion, contract.kind]), [
    [OBSERVE_PLAYER_ABILITY, 1, 'query'],
    [STRIKE_LIGHTNING_ABILITY, 1, 'effect'],
  ]);
  assert.ok(contracts.every((contract) => contract.execution.implementationStatus === 'UNIMPLEMENTED' &&
    contract.execution.qualificationStatus === 'UNQUALIFIED' && contract.execution.availability === 'UNAVAILABLE'));
});

test('Ajv input validators close nested objects and apply only the declared lightning default', () => {
  const observe = validateAbilityInputSchema({
    abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET },
  });
  assert.deepEqual(observe, {
    abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET },
  });
  const strike = validateAbilityInputSchema({
    abilityId: STRIKE_LIGHTNING_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET },
  });
  assert.deepEqual(strike, {
    abilityId: STRIKE_LIGHTNING_ABILITY, contractVersion: 1,
    arguments: { targetPlayerId: TARGET, visualOnly: true },
  });
  for (const invalid of [
    { abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET, operator: true } },
    { abilityId: STRIKE_LIGHTNING_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET, visualOnly: null } },
    { abilityId: STRIKE_LIGHTNING_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET, requesterId: REQUESTER } },
    { abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 2, arguments: { targetPlayerId: TARGET } },
  ]) assert.equal(validateAbilityInputSchema(invalid), null);
});

test('direct ability inputs reject getters and proxy traps before lookup or Ajv without invoking user code', () => {
  let invocations = 0;
  const throwingGetter = () => {
    invocations++;
    throw new Error('private input getter sentinel');
  };

  const topGetter = {};
  Object.defineProperty(topGetter, 'abilityId', { enumerable: true, get: throwingGetter });
  assert.equal(validateAbilityInputSchema(topGetter), null);

  const argumentsGetter = { abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1 };
  Object.defineProperty(argumentsGetter, 'arguments', { enumerable: true, get: throwingGetter });
  assert.equal(validateAbilityInputSchema(argumentsGetter), null);

  const nestedGetter = {
    abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1, arguments: {},
  };
  Object.defineProperty(nestedGetter.arguments, 'targetPlayerId', { enumerable: true, get: throwingGetter });
  assert.equal(validateAbilityInputSchema(nestedGetter), null);

  const proxyHandler: ProxyHandler<Record<string, unknown>> = {
    get() { invocations++; throw new Error('private input proxy get sentinel'); },
    getPrototypeOf() { invocations++; throw new Error('private input proxy prototype sentinel'); },
    ownKeys() { invocations++; throw new Error('private input proxy keys sentinel'); },
    getOwnPropertyDescriptor() { invocations++; throw new Error('private input proxy descriptor sentinel'); },
  };
  const topProxy = new Proxy({
    abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET },
  }, proxyHandler);
  assert.equal(validateAbilityInputSchema(topProxy), null);

  const nestedProxy = new Proxy({ targetPlayerId: TARGET }, proxyHandler);
  assert.equal(validateAbilityInputSchema({
    abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1, arguments: nestedProxy,
  }), null);

  const descriptorProxy = new Proxy({}, {
    ownKeys() { invocations++; throw new Error('private descriptor ownKeys sentinel'); },
    getOwnPropertyDescriptor() { invocations++; throw new Error('private descriptor trap sentinel'); },
  });
  assert.equal(validateAbilityInputSchema(descriptorProxy), null);
  assert.equal(invocations, 0);
});

test('direct ability input is type-first, rejects non-JSON objects, and returns only frozen owned values', () => {
  let invocations = 0;
  const coercible = {
    [Symbol.toPrimitive]() { invocations++; return OBSERVE_PLAYER_ABILITY; },
    toString() { invocations++; return OBSERVE_PLAYER_ABILITY; },
    valueOf() { invocations++; return OBSERVE_PLAYER_ABILITY; },
    toJSON() { invocations++; return OBSERVE_PLAYER_ABILITY; },
  };
  const base = { abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET } };
  for (const invalid of [null, undefined, true, false, 1, 'input', [], new Date(), Object.create({ inherited: true })]) {
    assert.equal(validateAbilityInputSchema(invalid), null);
  }
  for (const abilityId of [coercible, [], {}, null, true, 1, undefined]) {
    assert.equal(validateAbilityInputSchema({ ...base, abilityId }), null);
  }
  for (const contractVersion of [coercible, [], {}, null, true, '1', 2]) {
    assert.equal(validateAbilityInputSchema({ ...base, contractVersion }), null);
  }
  for (const targetPlayerId of [coercible, [], {}, null, true, 1]) {
    assert.equal(validateAbilityInputSchema({ ...base, arguments: { targetPlayerId } }), null);
  }
  assert.equal(invocations, 0);

  const argumentsCopy = Object.assign(Object.create(null) as Record<string, unknown>, { targetPlayerId: TARGET });
  const inputCopy = Object.assign(Object.create(null) as Record<string, unknown>, {
    abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1, arguments: argumentsCopy,
  });
  const validated = validateAbilityInputSchema(inputCopy);
  assert.deepEqual(validated, {
    abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET },
  });
  assert.equal(Object.isFrozen(validated), true);
  assert.equal(Object.isFrozen(validated?.arguments), true);
  assert.equal(Object.isFrozen(inputCopy), false);
  assert.equal(Object.isFrozen(argumentsCopy), false);
});

test('direct input snapshots match byte-ingress canonical controls without claiming raw-token proof', () => {
  const controls = [
    { abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET } },
    { abilityId: STRIKE_LIGHTNING_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET } },
    { abilityId: STRIKE_LIGHTNING_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET, visualOnly: false } },
  ];
  for (const control of controls) {
    const direct = validateAbilityInputSchema(control);
    const decision = validateAbilityIntent(new TextEncoder().encode(JSON.stringify(control)));
    assert.equal(decision.status, 'VALID');
    if (decision.status === 'VALID') assert.deepEqual(direct, decision.intent);
  }

  const oversized = { ...controls[0]!, unused: 'x'.repeat(5_000) };
  assert.equal(validateAbilityInputSchema(oversized), null);
  assert.deepEqual(validateAbilityIntent(new TextEncoder().encode(JSON.stringify(oversized))), {
    status: 'REJECTED', reasonCode: 'invalid_input',
  });
});

test('production byte validator shares strict cross-runtime request vectors and the 4096-byte envelope bound', async () => {
  const path = resolve(process.cwd(), '../compatibility/ability-contracts-v1-corpus.json');
  const corpus = JSON.parse(await readFile(path, 'utf8')) as { vectors: Array<{ name: string; rawJson: string; reasonCode: string }> };
  for (const vector of corpus.vectors) {
    const decision = validateAbilityIntent(new TextEncoder().encode(vector.rawJson));
    const actual = decision.status === 'VALID' ? 'none' : decision.reasonCode;
    assert.equal(actual, vector.reasonCode, vector.name);
  }
  const valid = JSON.stringify({ abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET } });
  const exact = `${valid}${' '.repeat(4096 - Buffer.byteLength(valid, 'utf8'))}`;
  assert.equal(Buffer.byteLength(exact, 'utf8'), 4096);
  assert.equal(validateAbilityIntent(new TextEncoder().encode(exact)).status, 'VALID');
  assert.deepEqual(validateAbilityIntent(new Uint8Array(4097)), { status: 'REJECTED', reasonCode: 'invalid_input' });
  assert.deepEqual(validateAbilityIntent(new Uint8Array([0xc3, 0x28])), { status: 'REJECTED', reasonCode: 'invalid_input' });
  assert.deepEqual(validateAbilityIntent(new TextEncoder().encode(`\ufeff${valid}`)), { status: 'REJECTED', reasonCode: 'invalid_input' });
});

test('intent parsing separates unsupported contracts from malformed intent without request authority', () => {
  const unknown = JSON.stringify({ abilityId: 'worldspeaker.core.unknown', contractVersion: 1, arguments: {} });
  const wrongVersion = JSON.stringify({ abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 2, arguments: { targetPlayerId: TARGET } });
  const malformed = JSON.stringify({ abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET }, policyRevision: 0 });
  assert.deepEqual(validateAbilityIntent(new TextEncoder().encode(unknown)), { status: 'REJECTED', reasonCode: 'unsupported_contract' });
  assert.deepEqual(validateAbilityIntent(new TextEncoder().encode(wrongVersion)), { status: 'REJECTED', reasonCode: 'unsupported_contract' });
  assert.deepEqual(validateAbilityIntent(new TextEncoder().encode(malformed)), { status: 'REJECTED', reasonCode: 'invalid_input' });
  assert.equal(validateAbilityIntent(new TextEncoder().encode(JSON.stringify({
    abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1, arguments: { targetPlayerId: TARGET },
  }))).status, 'VALID', 'parsing is separate from owner authority checks');
});

test('Ajv result extensions reject invalid health, float32 overflow, coordinate widening, damage fields, and state contradictions', () => {
  const observe: AbilityResult = {
    serverId: REQUEST_ID, worldId: INVOCATION_ID, characterId: EPOCH_ID,
    requestId: REQUEST_ID, invocationId: INVOCATION_ID, abilityId: OBSERVE_PLAYER_ABILITY,
    contractVersion: 1, policyRevision: 7, serverEpochId: EPOCH_ID, state: 'executed', reasonCode: 'none',
    observedAt: 100, executedAt: null, retryAfterMs: null, queryId: '00000000-0000-4000-8000-000000000064',
    facts: { targetPlayerId: TARGET, observedAt: 100, health: 40, maxHealth: 40,
      dimension: 'minecraft:overworld', biome: 'minecraft:plains', holdingItem: 'minecraft:air' },
  };
  assert.equal(validateAbilityResultSchema(observe), true);
  assert.equal(validateAbilityResultSchema({ ...observe, facts: { ...observe.facts!, health: 41, maxHealth: 40 } }), false);
  assert.equal(validateAbilityResultSchema({ ...observe, facts: { ...observe.facts!, health: Number.MAX_VALUE } }), false);
  assert.equal(validateAbilityResultSchema({ ...observe, state: 'executed', reasonCode: 'rate_limited', retryAfterMs: 2 }), false);
  assert.equal(validateAbilityResultSchema({ ...observe, state: 'admitted', facts: observe.facts }), false);

  const lightning: AbilityResult = {
    serverId: REQUEST_ID, worldId: INVOCATION_ID, characterId: EPOCH_ID,
    requestId: REQUEST_ID, invocationId: INVOCATION_ID, abilityId: STRIKE_LIGHTNING_ABILITY,
    contractVersion: 1, policyRevision: 7, serverEpochId: EPOCH_ID, state: 'executed', reasonCode: 'none',
    observedAt: null, executedAt: 100, retryAfterMs: null, actionId: '00000000-0000-4000-8000-000000000065',
    facts: { actionId: '00000000-0000-4000-8000-000000000065', targetPlayerId: TARGET,
      dimension: 'minecraft:overworld', executedAt: 100, coordinates: [0, 64, 0], visualOnly: true,
      steps: [{ kind: 'presentation', state: 'confirmed' }] },
  };
  assert.equal(validateAbilityResultSchema(lightning), true);
  assert.equal(validateAbilityResultSchema({ ...lightning, facts: { ...lightning.facts!, coordinates: [0.5, 64, 0] } }), false);
  assert.equal(validateAbilityResultSchema({ ...lightning, facts: { ...lightning.facts!, visualOnly: false,
    steps: [{ kind: 'presentation', state: 'confirmed' }, { kind: 'damage', state: 'confirmed' }] } }), false);
  assert.equal(validateAbilityResultSchema({ ...lightning, facts: { ...lightning.facts!, visualOnly: false,
    steps: [{ kind: 'presentation', state: 'confirmed' }, { kind: 'damage', state: 'confirmed',
      healthRemoved: null, absorptionRemoved: null, damageDisposition: null }] } }), false);
});

test('health bounds compare canonical binary32 values in both predicate adapters', () => {
  const resources = abilityDiscoverySchemaResources();
  const validator = new Ajv2020({ allErrors: true, strict: false });
  validator.addMetaSchema(JSON.parse(resources[0]!.schemaJson));
  installAbilitySchemaPredicates(validator);
  const validateReceivedShape = validator.compile({
    type: 'object', required: ['health', 'maxHealth'], additionalProperties: false,
    properties: {
      health: { type: 'number', minimum: 0, worldspeakerFloat32: 'finite-nonnegative' },
      maxHealth: { type: 'number', exclusiveMinimum: 0, worldspeakerFloat32: 'finite-positive' },
    },
    worldspeakerHealthAtMostMaxHealth: true,
  });

  const sameFloat32Forms = [
    { name: '0.1', java: 0.1, ecma: Math.fround(0.1) },
    { name: 'Float.MAX_VALUE', java: Number('3.4028235e38'), ecma: Math.fround(Number('3.4028235e38')) },
    { name: 'minimum subnormal', java: Number('1.4e-45'), ecma: Number('1.401298464324817e-45') },
  ];
  for (const sample of sameFloat32Forms) {
    for (const [health, maxHealth] of [[sample.java, sample.ecma], [sample.ecma, sample.java]]) {
      const pair = { health, maxHealth };
      const originalPair = { ...pair };
      assert.equal(validateAbilityResultSchema(observationResult(health, maxHealth)), true,
        `${sample.name}: production result schema`);
      assert.equal(validateReceivedShape(pair), true, `${sample.name}: exported predicate adapter`);
      assert.deepEqual(pair, originalPair, `${sample.name}: predicate does not mutate its caller`);
    }
  }

  const lowerAdjacent = float32FromBits(0x3dcccccd);
  const higherAdjacent = float32FromBits(0x3dccccce);
  assert.equal(validateAbilityResultSchema(observationResult(lowerAdjacent, higherAdjacent)), true);
  assert.equal(validateReceivedShape({ health: lowerAdjacent, maxHealth: higherAdjacent }), true);
  assert.equal(validateAbilityResultSchema(observationResult(higherAdjacent, lowerAdjacent)), false);
  assert.equal(validateReceivedShape({ health: higherAdjacent, maxHealth: lowerAdjacent }), false);

  for (const [health, maxHealth] of [
    [Number.NaN, 1], [1, Number.POSITIVE_INFINITY], [1e-50, 1], [1, 1e-50],
    [3.4028236e38, 3.4028235e38], [0.10000000000000002, Math.fround(0.1)],
    ['0.1', 1], [1, null],
  ] as const) {
    assert.equal(validateAbilityResultSchema(observationResult(health, maxHealth)), false);
    assert.equal(validateReceivedShape({ health, maxHealth }), false);
  }

  const originalResult = observationResult(Math.fround(0.1), 0.1);
  const resultBeforeValidation = structuredClone(originalResult);
  assert.equal(validateAbilityResultSchema(originalResult), true);
  assert.deepEqual(originalResult, resultBeforeValidation, 'production result validation does not normalize the caller in place');
});

function observationResult(health: unknown, maxHealth: unknown): Record<string, unknown> {
  return {
    serverId: REQUEST_ID, worldId: INVOCATION_ID, characterId: EPOCH_ID,
    requestId: REQUEST_ID, invocationId: INVOCATION_ID, abilityId: OBSERVE_PLAYER_ABILITY,
    contractVersion: 1, policyRevision: 7, serverEpochId: EPOCH_ID, state: 'executed', reasonCode: 'none',
    observedAt: 100, executedAt: null, retryAfterMs: null, queryId: '00000000-0000-4000-8000-000000000064',
    facts: { targetPlayerId: TARGET, observedAt: 100, health, maxHealth,
      dimension: 'minecraft:overworld', biome: 'minecraft:plains', holdingItem: 'minecraft:air' },
  };
}

function float32FromBits(bits: number): number {
  const view = new DataView(new ArrayBuffer(4));
  view.setUint32(0, bits, false);
  return view.getFloat32(0, false);
}
