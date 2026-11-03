import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import {
  isCanonicalJavaFloat32JsonNumberToken,
  isJavaFloat32WireNumber,
  isNonNegativeSafeJsonIntegerToken,
  normalizeFloat32WireNumber,
  isSafeJsonIntegerToken,
} from '../protocol-text.js';
import { parseStrictJson } from '../strict-json.js';
import { validateAbilityResultBytes } from '../ability-validation.js';

type NumberCorpus = Readonly<{
  float32Serializations: readonly Readonly<{ name: string; bits: string; javaText: string; nodeJsonText: string }>[];
  rawFloatTokens: readonly Readonly<{ name: string; token: string; accepted: boolean }>[];
  safeIntegerTokens: readonly Readonly<{ token: string; safe: boolean; nonNegative: boolean }>[];
  semanticCases: Readonly<{ healthBeyondTwenty: Readonly<{ health: string; maxHealth: string; accepted: boolean }> }>;
}>;

const numberCorpus = JSON.parse(readFileSync(
  new URL('../../../compatibility/ability-numbers-v1-corpus.json', import.meta.url), 'utf8',
)) as NumberCorpus;
const encoder = new TextEncoder();
const UUIDS = {
  server: '00000000-0000-4000-8000-000000000001',
  world: '00000000-0000-4000-8000-000000000002',
  character: '00000000-0000-4000-8000-000000000003',
  request: '00000000-0000-4000-8000-000000000004',
  invocation: '00000000-0000-4000-8000-000000000005',
  epoch: '00000000-0000-4000-8000-000000000006',
  target: '00000000-0000-4000-8000-000000000007',
  query: '00000000-0000-4000-8000-000000000008',
  action: '00000000-0000-4000-8000-000000000009',
} as const;

test('shared corpus contains Java and ECMAScript serializations of the same binary32 values', () => {
  assert.ok(numberCorpus.float32Serializations.length >= 60);
  for (const sample of numberCorpus.float32Serializations) {
    const view = new DataView(new ArrayBuffer(4));
    view.setFloat32(0, Number(sample.javaText), false);
    assert.equal(view.getUint32(0, false).toString(16).padStart(8, '0'), sample.bits, sample.name);
    assert.equal(isJavaFloat32WireNumber(Number(sample.javaText)), true, sample.name);
    assert.equal(isCanonicalJavaFloat32JsonNumberToken(sample.javaText), true, sample.name);
    assert.equal(JSON.stringify(Math.fround(Number(sample.javaText))), sample.nodeJsonText, sample.name);
    assert.equal(isJavaFloat32WireNumber(Number(sample.nodeJsonText)), true, sample.name);
    assert.equal(isCanonicalJavaFloat32JsonNumberToken(sample.nodeJsonText), true, sample.name);
    const fromJava = normalizeFloat32WireNumber(Number(sample.javaText));
    const fromNode = normalizeFloat32WireNumber(Number(sample.nodeJsonText));
    assert.equal(Object.is(fromJava, Math.fround(Number(sample.javaText))), true, sample.name);
    if (sample.bits === '80000000') {
      assert.equal(Object.is(fromJava, -0), true, sample.name);
      assert.equal(Object.is(fromNode, 0), true, 'ECMAScript JSON loses negative-zero sign');
    } else {
      assert.equal(Object.is(fromJava, fromNode), true, sample.name);
    }
  }
});

test('both canonical wire forms normalize identically and arbitrary rounded decimals are rejected', () => {
  for (const sample of numberCorpus.rawFloatTokens) {
    assert.equal(isCanonicalJavaFloat32JsonNumberToken(sample.token), sample.accepted, sample.name);
  }
  const widened = JSON.stringify(Math.fround(0.1));
  assert.equal(widened, '0.10000000149011612');
  assert.equal(isCanonicalJavaFloat32JsonNumberToken(widened), true);
  assert.equal(isCanonicalJavaFloat32JsonNumberToken('0.1'), true);
  assert.equal(isJavaFloat32WireNumber(0.1), true);
  assert.equal(isJavaFloat32WireNumber(Math.fround(0.1)), true);
  assert.equal(Object.is(normalizeFloat32WireNumber(0.1), normalizeFloat32WireNumber(Math.fround(0.1))), true);
  assert.equal(isCanonicalJavaFloat32JsonNumberToken('0.100000001490116120000000000001'), false);
  assert.equal(Number('0.100000001490116120000000000001'), Number('0.10000000149011612'));
  assert.equal(isJavaFloat32WireNumber(1e-50), false);
  assert.equal(isJavaFloat32WireNumber(Number.MAX_VALUE), false);
  const parsed = parseStrictJson('{"health":0.1}') as { health: number };
  assert.equal(Object.is(parsed.health, Math.fround(0.1)), true);
});

test('raw mathematical safe integer tokens preserve decimal/exponent values and signed coordinates', () => {
  for (const sample of numberCorpus.safeIntegerTokens) {
    assert.equal(isSafeJsonIntegerToken(sample.token), sample.safe, sample.token);
    assert.equal(isNonNegativeSafeJsonIntegerToken(sample.token), sample.nonNegative, sample.token);
  }

  assert.doesNotThrow(() => parseStrictJson('{"nested":[{"observedAt":1e2,"policyRevision":7.00,"coordinates":[[-1.0,64e0,-0]]}]}'));
  assert.throws(() => parseStrictJson('{"nested":{"observedAt":1.0000000000000001}}'), /mathematical safe integer/);
  assert.throws(() => parseStrictJson('{"nested":{"executedAt":1e-50}}'), /mathematical safe integer/);
  assert.throws(() => parseStrictJson('{"nested":{"retryAfterMs":1.0000000000000001}}'), /mathematical safe integer/);
  assert.throws(() => parseStrictJson('{"outer":[{"coordinates":[[1.0000000000000001]]}]}'), /coordinate/);
  assert.throws(() => parseStrictJson('{"outer":[{"coordinates":[[1.5]]}]}'), /coordinate/);
  assert.throws(() => parseStrictJson('{"outer":[{"coordinates":[[9007199254740992]]}]}'), /coordinate/);
});

test('result bytes use raw token checks before Ajv can observe rounded JS numbers', () => {
  const canonical = validateAbilityResultBytes(encoder.encode(observeResult({
    contractVersion: '1.0', policyRevision: '7.00e0', observedAt: '1e2', factObservedAt: '100.0',
    health: '0.1', maxHealth: '21.0',
  })));
  assert.ok(canonical);
  if (canonical?.abilityId === 'worldspeaker.core.observe_player' && canonical.facts !== null) {
    assert.equal(Object.is(canonical.facts.health, Math.fround(0.1)), true);
    assert.equal(canonical.facts.maxHealth, 21);
  }
  const ecmaCanonical = validateAbilityResultBytes(encoder.encode(observeResult({
    health: JSON.stringify(Math.fround(0.1)), maxHealth: '21.0',
  })));
  assert.ok(ecmaCanonical);
  if (ecmaCanonical?.abilityId === 'worldspeaker.core.observe_player' && ecmaCanonical.facts !== null &&
      canonical?.abilityId === 'worldspeaker.core.observe_player' && canonical.facts !== null) {
    assert.equal(Object.is(ecmaCanonical.facts.health, canonical.facts.health), true);
  }

  assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ observedAt: '1.0000000000000001' }))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ factObservedAt: '1.0000000000000001' }))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ contractVersion: '1.0000000000000001' }))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ policyRevision: '1.0000000000000001' }))), null);
  assert.ok(validateAbilityResultBytes(encoder.encode(limitedResult('30.00e0'))));
  assert.equal(validateAbilityResultBytes(encoder.encode(limitedResult('1.0000000000000001'))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ health: '1e-50' }))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({
    health: '0.100000001490116120000000000001',
  }))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ health: '1.0000000000000001' }))), null);
  const javaMinimumSubnormal = validateAbilityResultBytes(encoder.encode(observeResult({
    health: '1.4e-45', maxHealth: '1.4e-45',
  })));
  const nodeMinimumSubnormal = validateAbilityResultBytes(encoder.encode(observeResult({
    health: '1.401298464324817e-45', maxHealth: '1.401298464324817e-45',
  })));
  assert.ok(javaMinimumSubnormal);
  assert.ok(nodeMinimumSubnormal);
  if (javaMinimumSubnormal?.abilityId === 'worldspeaker.core.observe_player' && javaMinimumSubnormal.facts !== null &&
      nodeMinimumSubnormal?.abilityId === 'worldspeaker.core.observe_player' && nodeMinimumSubnormal.facts !== null) {
    assert.equal(Object.is(javaMinimumSubnormal.facts.health, nodeMinimumSubnormal.facts.health), true);
    assert.equal(Object.is(javaMinimumSubnormal.facts.health, Math.fround(1.4e-45)), true);
  }
  const javaFloatMax = validateAbilityResultBytes(encoder.encode(observeResult({
    health: '3.4028235e38', maxHealth: '3.4028235e38',
  })));
  const nodeFloatMax = validateAbilityResultBytes(encoder.encode(observeResult({
    health: '3.4028234663852886e38', maxHealth: '3.4028234663852886e38',
  })));
  assert.ok(javaFloatMax);
  assert.ok(nodeFloatMax);
  if (javaFloatMax?.abilityId === 'worldspeaker.core.observe_player' && javaFloatMax.facts !== null &&
    nodeFloatMax?.abilityId === 'worldspeaker.core.observe_player' && nodeFloatMax.facts !== null) {
    assert.equal(Object.is(javaFloatMax.facts.health, nodeFloatMax.facts.health), true);
    assert.equal(Object.is(javaFloatMax.facts.health, Math.fround(3.4028235e38)), true);
  }
  assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ health: '3.4028234e38', maxHealth: '3.4028235e38' }))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ health: '3.4028236e38', maxHealth: '3.4028235e38' }))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ health: '-0.1', maxHealth: '1.0' }))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({
    health: '-0.10000000149011612', maxHealth: '1.0',
  }))), null);

  const zero = validateAbilityResultBytes(encoder.encode(observeResult({
    observedAt: '-0', factObservedAt: '-0', policyRevision: '-0', health: '-0.0', maxHealth: '1.0',
  })));
  assert.ok(zero);
  if (zero?.abilityId === 'worldspeaker.core.observe_player' && zero.facts !== null) {
    assert.equal(Object.is(zero.observedAt, -0), true);
    assert.equal(Object.is(zero.facts.observedAt, -0), true);
    assert.equal(Object.is(zero.facts.health, -0), true);
  }
  const ecmaZero = validateAbilityResultBytes(encoder.encode(observeResult({
    observedAt: '0', factObservedAt: '0', policyRevision: '0', health: '0', maxHealth: '1.0',
  })));
  assert.ok(ecmaZero);
  if (ecmaZero?.abilityId === 'worldspeaker.core.observe_player' && ecmaZero.facts !== null) {
    assert.equal(Object.is(ecmaZero.facts.health, 0), true);
    assert.equal(Object.is(ecmaZero.facts.health, -0), false);
  }

  const beyondTwenty = numberCorpus.semanticCases.healthBeyondTwenty;
  assert.equal(beyondTwenty.accepted, true);
  assert.ok(validateAbilityResultBytes(encoder.encode(observeResult({
    health: beyondTwenty.health, maxHealth: beyondTwenty.maxHealth,
  }))));
});

test('byte health bounds accept equivalent canonical float32 spellings and retain raw-token rejection', () => {
  const sameFloat32Forms = [
    { name: '0.1', java: '0.1', ecma: '0.10000000149011612' },
    { name: 'Float.MAX_VALUE', java: '3.4028235e38', ecma: '3.4028234663852886e38' },
    { name: 'minimum subnormal', java: '1.4e-45', ecma: '1.401298464324817e-45' },
  ];
  for (const sample of sameFloat32Forms) {
    assert.ok(validateAbilityResultBytes(encoder.encode(observeResult({ health: sample.java, maxHealth: sample.ecma }))),
      `${sample.name}: Java spelling below/equal Node spelling`);
    assert.ok(validateAbilityResultBytes(encoder.encode(observeResult({ health: sample.ecma, maxHealth: sample.java }))),
      `${sample.name}: Node spelling below/equal Java spelling`);
  }

  const lower = float32JsonToken(0x3dcccccd);
  const higher = float32JsonToken(0x3dccccce);
  assert.ok(validateAbilityResultBytes(encoder.encode(observeResult({ health: lower, maxHealth: higher }))));
  assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ health: higher, maxHealth: lower }))), null,
    'adjacent distinct float32 values still reject health above maxHealth');

  const hiddenPrecision = '0.100000001490116120000000000001';
  assert.equal(Number(hiddenPrecision), Number('0.10000000149011612'),
    'the hidden decimal tail disappears when converted to a JavaScript number');
  assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ health: hiddenPrecision, maxHealth: '0.1' }))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ health: '0.1', maxHealth: hiddenPrecision }))), null);
  for (const field of ['health', 'maxHealth'] as const) {
    assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ [field]: '1e-50' }))), null,
      `${field}: nonzero underflow is not rounded to zero`);
    assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ [field]: '3.4028236e38' }))), null,
      `${field}: overflow is not clamped to Float.MAX_VALUE`);
    for (const token of ['NaN', 'Infinity', '-Infinity']) {
      assert.equal(validateAbilityResultBytes(encoder.encode(observeResult({ [field]: token }))), null,
        `${field}: ${token} is not a JSON number`);
    }
  }
});

test('coordinate result tokens are checked as signed safe integers inside nested arrays', () => {
  assert.ok(validateAbilityResultBytes(encoder.encode(lightningResult('-1.00e0', '64.0', '-0'))));
  assert.ok(validateAbilityResultBytes(encoder.encode(lightningResult('-1', '64', '0', '1.00e2'))));
  assert.equal(validateAbilityResultBytes(encoder.encode(lightningResult('0', '64', '0', '1.0000000000000001'))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(lightningResult('1.0000000000000001', '64', '0'))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(lightningResult('1.5', '64', '0'))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(lightningResult('9007199254740992', '64', '0'))), null);
});

test('result byte intake keeps its 4 KiB and depth-eight whole-document bounds', () => {
  const oversized = `${observeResult().slice(0, -1)},"padding":"${'x'.repeat(4096)}"}`;
  assert.equal(validateAbilityResultBytes(encoder.encode(oversized)), null);
  const deeplyNested = observeResult().replace(
    '"facts":{', `"facts":{"extra":${'['.repeat(8)}0${']'.repeat(8)},`,
  );
  assert.equal(validateAbilityResultBytes(encoder.encode(deeplyNested)), null);
  assert.throws(() => parseStrictJson(`${'['.repeat(9)}0${']'.repeat(9)}`, 8), /nesting limit exceeded/);
});

type ObserveRawFields = Partial<Readonly<{
  contractVersion: string;
  policyRevision: string;
  observedAt: string;
  factObservedAt: string;
  health: string;
  maxHealth: string;
}>>;

function observeResult(fields: ObserveRawFields = {}): string {
  const contractVersion = fields.contractVersion ?? '1';
  const policyRevision = fields.policyRevision ?? '7';
  const observedAt = fields.observedAt ?? '100';
  const factObservedAt = fields.factObservedAt ?? '100';
  const health = fields.health ?? '20.0';
  const maxHealth = fields.maxHealth ?? '20.0';
  return `{"serverId":"${UUIDS.server}","worldId":"${UUIDS.world}","characterId":"${UUIDS.character}",` +
    `"requestId":"${UUIDS.request}","invocationId":"${UUIDS.invocation}",` +
    `"abilityId":"worldspeaker.core.observe_player","contractVersion":${contractVersion},` +
    `"policyRevision":${policyRevision},"serverEpochId":"${UUIDS.epoch}","state":"executed","reasonCode":"none",` +
    `"facts":{"targetPlayerId":"${UUIDS.target}","observedAt":${factObservedAt},"health":${health},` +
    `"maxHealth":${maxHealth},"dimension":"minecraft:overworld","biome":"minecraft:plains",` +
    `"holdingItem":"minecraft:air"},"observedAt":${observedAt},"executedAt":null,"retryAfterMs":null,` +
    `"queryId":"${UUIDS.query}"}`;
}

function lightningResult(x: string, y: string, z: string, executedAt = '100'): string {
  return `{"serverId":"${UUIDS.server}","worldId":"${UUIDS.world}","characterId":"${UUIDS.character}",` +
    `"requestId":"${UUIDS.request}","invocationId":"${UUIDS.invocation}",` +
    `"abilityId":"worldspeaker.core.strike_lightning","contractVersion":1,"policyRevision":7,` +
    `"serverEpochId":"${UUIDS.epoch}","state":"executed","reasonCode":"none","observedAt":null,` +
    `"executedAt":${executedAt},"retryAfterMs":null,"actionId":"${UUIDS.action}","facts":{"actionId":"${UUIDS.action}",` +
    `"targetPlayerId":"${UUIDS.target}","dimension":"minecraft:overworld","executedAt":${executedAt},` +
    `"coordinates":[${x},${y},${z}],"visualOnly":true,"steps":[{"kind":"presentation","state":"confirmed"}]}}`;
}

function float32JsonToken(bits: number): string {
  const view = new DataView(new ArrayBuffer(4));
  view.setUint32(0, bits, false);
  return JSON.stringify(view.getFloat32(0, false));
}

function limitedResult(retryAfterMs: string): string {
  return `{"serverId":"${UUIDS.server}","worldId":"${UUIDS.world}","characterId":"${UUIDS.character}",` +
    `"requestId":"${UUIDS.request}","invocationId":"${UUIDS.invocation}",` +
    `"abilityId":"worldspeaker.core.observe_player","contractVersion":1,"policyRevision":7,` +
    `"serverEpochId":"${UUIDS.epoch}","state":"rejected","reasonCode":"rate_limited","facts":null,` +
    `"observedAt":null,"executedAt":null,"retryAfterMs":${retryAfterMs},"queryId":"${UUIDS.query}"}`;
}
