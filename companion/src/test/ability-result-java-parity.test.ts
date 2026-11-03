import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { validateAbilityResultBytes } from '../ability-validation.js';
import {
  abilityResultSemanticsAreCoherent,
  validateAbilityResultSchema,
  type AbilityResult,
} from '../ability-catalogue.js';
import { exactFloat32SumAtMost, exactFloat32SumIsZero } from '../exact-float32-sum.js';
import {
  isCanonicalJavaFloat32JsonNumberToken,
  isNonNegativeSafeJsonIntegerToken,
  normalizeFloat32WireNumber,
  isSafeJsonIntegerToken,
} from '../protocol-text.js';

type ResultCorpus = Readonly<{
  schemaVersion: number;
  fixtureNature: string;
  prefix: string;
  facts: Readonly<Record<string, string>>;
  positiveCases: readonly Readonly<{ name: string; state: string; reason: string; tail: string }>[];
  negativeMutations: readonly Readonly<{
    name: string;
    source: string;
    find: string;
    replace: string;
    occurrence?: 'first' | 'last';
  }>[];
  negativeSuffixes: readonly Readonly<{ name: string; tail: string }>[];
  damageSumCases: readonly Readonly<{
    name: string;
    health: Readonly<{ java: string; node: string }>;
    absorption: Readonly<{ java: string; node: string }>;
    comparisonLimit: string;
    resultAccepted: boolean;
    sumAtMostLimit: boolean;
    sumIsZero: boolean;
    disposition: DamageDisposition;
  }>[];
}>;
type NumberCorpus = Readonly<{
  float32Serializations: readonly Readonly<{ name: string; bits: string; javaText: string; nodeJsonText: string }>[];
  rawFloatTokens: readonly Readonly<{ name: string; token: string; accepted: boolean }>[];
  safeIntegerTokens: readonly Readonly<{ token: string; safe: boolean; nonNegative: boolean }>[];
}>;

const resultCorpus = JSON.parse(readFileSync(
  new URL('../../../compatibility/ability-results-v1-corpus.json', import.meta.url), 'utf8',
)) as ResultCorpus;
const numberCorpus = JSON.parse(readFileSync(
  new URL('../../../compatibility/ability-numbers-v1-corpus.json', import.meta.url), 'utf8',
)) as NumberCorpus;
const encoder = new TextEncoder();
const EPOCH = '00000000-0000-4000-8000-000000000106';
const TARGET = '00000000-0000-4000-8000-000000000107';
const ACTION = '00000000-0000-4000-8000-000000000109';
type DamageDisposition = 'applied' | 'mitigated' | 'invulnerable' | 'protection_veto' | 'non_lethal_limit';

test('shared synthetic result semantic corpus passes the same raw bytes as the Java validator', () => {
  assert.equal(resultCorpus.schemaVersion, 1);
  assert.match(resultCorpus.fixtureNature, /synthetic/u);
  const states = new Set<string>();
  const reasons = new Set<string>();
  const wrongAbilityIdStates = new Set<string>();
  for (const item of resultCorpus.positiveCases) {
    const rawResult = resultCorpus.prefix + expand(item.tail);
    const bytes = encoder.encode(rawResult);
    const result = validateAbilityResultBytes(bytes);
    assert.ok(result, item.name);
    assert.equal(result.state, item.state, item.name);
    assert.equal(result.reasonCode, item.reason, item.name);
    states.add(result.state);
    reasons.add(result.reasonCode);

    const correctAbilityId = result.abilityId;
    const wrongAbilityId = correctAbilityId === 'worldspeaker.core.observe_player'
      ? 'worldspeaker.core.strike_lightning'
      : 'worldspeaker.core.observe_player';
    const wrongIdentity = rawResult.replace(`"abilityId":"${correctAbilityId}"`, `"abilityId":"${wrongAbilityId}"`);
    assert.notEqual(wrongIdentity, rawResult, `${item.name}: identity mutation target`);
    assert.equal(validateAbilityResultBytes(encoder.encode(wrongIdentity)), null,
      `${item.name}: the byte validator rejects another known operation ID`);
    wrongAbilityIdStates.add(item.state);
  }
  assert.deepEqual([...states].sort(), [
    'admitted', 'cancelled', 'executed', 'expired', 'failed', 'partial', 'rejected', 'submitted', 'unknown',
  ]);
  assert.deepEqual([...reasons].sort(), [
    'cancelled', 'concurrency_limited', 'deadline_expired', 'execution_failed', 'execution_unavailable',
    'invalid_input', 'none', 'out_of_scope', 'outcome_unknown', 'permission_denied', 'protected_area',
    'rate_limited', 'stale_context', 'storage_unavailable', 'target_unavailable', 'unsupported_contract',
    'unsupported_state',
  ]);
  assert.deepEqual([...wrongAbilityIdStates].sort(), [
    'admitted', 'cancelled', 'executed', 'expired', 'failed', 'partial', 'rejected', 'submitted', 'unknown',
  ], 'wrong operation IDs are checked in every lifecycle state');

  const query = resultCorpus.positiveCases.find((candidate) => candidate.name === 'rejected invalid_input');
  const lightning = resultCorpus.positiveCases.find((candidate) => candidate.name === 'visual lightning executed');
  assert.ok(query && lightning);
  const queryResult = resultCorpus.prefix + expand(query.tail);
  const queryFieldSwap = queryResult.replace('"queryId":', '"actionId":');
  assert.equal(validateAbilityResultBytes(encoder.encode(queryFieldSwap)), null,
    'an actionId cannot substitute for queryId in byte input');
  const lightningResult = resultCorpus.prefix + expand(lightning.tail);
  const actionIndex = lightningResult.lastIndexOf('"actionId":');
  assert.notEqual(actionIndex, -1);
  const lightningFieldSwap = lightningResult.slice(0, actionIndex) +
    lightningResult.slice(actionIndex).replace('"actionId":', '"queryId":');
  assert.equal(validateAbilityResultBytes(encoder.encode(lightningFieldSwap)), null,
    'a queryId cannot substitute for actionId in byte input');
  for (const mutation of resultCorpus.negativeMutations) {
    const sourceCase = resultCorpus.positiveCases.find((candidate) => candidate.name === mutation.source);
    assert.ok(sourceCase, `missing shared positive fixture for ${mutation.name}`);
    const source = resultCorpus.prefix + expand(sourceCase.tail);
    const find = expand(mutation.find);
    const replacement = expand(mutation.replace);
    const index = mutation.occurrence === 'last' ? source.lastIndexOf(find) : source.indexOf(find);
    assert.notEqual(index, -1, `mutation needle not found: ${mutation.name}`);
    const changed = source.slice(0, index) + replacement + source.slice(index + find.length);
    assert.equal(validateAbilityResultBytes(encoder.encode(changed)), null, mutation.name);
  }
  for (const item of resultCorpus.negativeSuffixes) {
    assert.equal(validateAbilityResultBytes(encoder.encode(resultCorpus.prefix + expand(item.tail))), null, item.name);
  }
});

test('shared damage-sum corpus compares canonical float32 operands exactly at each limit', () => {
  for (const sample of resultCorpus.damageSumCases) {
    const decisions = new Set<string>();
    for (const healthForm of ['java', 'node'] as const) {
      for (const absorptionForm of ['java', 'node'] as const) {
        const healthToken = sample.health[healthForm];
        const absorptionToken = sample.absorption[absorptionForm];
        const json = lightningDamageResult(healthToken, absorptionToken, sample.disposition);
        assert.ok(json.includes(`"damageDisposition":"${sample.disposition}"`),
          `${sample.name}: synthetic JSON preserves its typed disposition`);
        const result = validateAbilityResultBytes(encoder.encode(json));
        assert.equal(result !== null, sample.resultAccepted, `${sample.name}: ${healthForm}/${absorptionForm} wire validation`);
        if (result?.abilityId === 'worldspeaker.core.strike_lightning' && result.facts !== null) {
          const damage = result.facts.steps.find((step) => step.kind === 'damage');
          assert.ok(damage?.kind === 'damage' && damage.state === 'confirmed', `${sample.name}: confirmed damage step`);
          assert.equal(damage.damageDisposition, sample.disposition, `${sample.name}: parsed disposition`);
        }

        const parsed = JSON.parse(json) as unknown;
        assert.equal(validateAbilityResultSchema(parsed), sample.resultAccepted, `${sample.name}: schema predicate`);
        assert.equal(abilityResultSemanticsAreCoherent(parsed), sample.resultAccepted, `${sample.name}: pure semantics`);

        const health = Number(healthToken);
        const absorption = Number(absorptionToken);
        const atMost = exactFloat32SumAtMost(health, absorption, Number(sample.comparisonLimit));
        const zero = exactFloat32SumIsZero(health, absorption);
        assert.equal(atMost, sample.sumAtMostLimit, `${sample.name}: exact threshold ${healthForm}/${absorptionForm}`);
        assert.equal(zero, sample.sumIsZero, `${sample.name}: exact zero ${healthForm}/${absorptionForm}`);
        assert.notEqual(normalizeFloat32WireNumber(health), null, `${sample.name}: canonical health`);
        assert.notEqual(normalizeFloat32WireNumber(absorption), null, `${sample.name}: canonical absorption`);
        decisions.add(`${result !== null}/${atMost}/${zero}`);
      }
    }
    assert.deepEqual([...decisions], [`${sample.resultAccepted}/${sample.sumAtMostLimit}/${sample.sumIsZero}`], sample.name);
  }
});

test('the shared Java/Node numeric corpus has matching result-byte acceptance and rejection', () => {
  for (const sample of numberCorpus.float32Serializations) {
    assert.equal(isCanonicalJavaFloat32JsonNumberToken(sample.javaText), true, `${sample.name}: Java form`);
    assert.equal(isCanonicalJavaFloat32JsonNumberToken(sample.nodeJsonText), true, `${sample.name}: Node form`);
  }
  for (const sample of numberCorpus.rawFloatTokens) {
    assert.equal(isCanonicalJavaFloat32JsonNumberToken(sample.token), sample.accepted, sample.name);
  }
  for (const sample of numberCorpus.safeIntegerTokens) {
    assert.equal(isSafeJsonIntegerToken(sample.token), sample.safe, sample.token);
    assert.equal(isNonNegativeSafeJsonIntegerToken(sample.token), sample.nonNegative, sample.token);
  }

  const javaPointOne = observeResult('0.1', '20.0', '100', '100');
  const nodePointOne = observeResult('0.10000000149011612', '20.0', '100', '100');
  assert.ok(javaPointOne);
  assert.ok(nodePointOne);
  if (javaPointOne.abilityId === 'worldspeaker.core.observe_player' && nodePointOne.abilityId === 'worldspeaker.core.observe_player' &&
      javaPointOne.facts !== null && nodePointOne.facts !== null) {
    assert.equal(Object.is(javaPointOne.facts.health, nodePointOne.facts.health), true);
  }
  const javaSubnormal = observeResult('1.4E-45', '1.4E-45', '100', '100');
  const nodeSubnormal = observeResult('1.401298464324817e-45', '1.401298464324817e-45', '100', '100');
  assert.ok(javaSubnormal);
  assert.ok(nodeSubnormal);
  const javaMax = observeResult('3.4028235E38', '3.4028235E38', '100', '100');
  const nodeMax = observeResult('3.4028234663852886e38', '3.4028234663852886e38', '100', '100');
  assert.ok(javaMax);
  assert.ok(nodeMax);

  assert.ok(observeResult('21.0', '21.0', '100', '100'), 'health is normalized, never clamped');
  const negativeZero = observeResult('-0.0', '1.0', '-0.000e9999', '-0');
  assert.ok(negativeZero);
  assert.equal(negativeZero?.abilityId === 'worldspeaker.core.observe_player' && negativeZero.facts !== null &&
    Object.is(negativeZero.facts.health, -0), true);
  assert.equal(observeResult('1e-50', '1.0', '100', '100'), null);
  assert.equal(observeResult('0.100000001490116120000000000001', '1.0', '100', '100'), null);
  assert.equal(observeResult('1.0000000000000001', '2.0', '100', '100'), null);
  assert.equal(observeResult('3.4028236E38', '3.4028235E38', '100', '100'), null);
  assert.equal(observeResult('0.1', '20.0', '1.0000000000000001', '1.0000000000000001'), null);
  assert.ok(observeResult('0.1', '20.0', '9.007199254740991e15', '9007199254740991'));
  assert.equal(observeResult('0.1', '20.0', '9007199254740992', '9007199254740992'), null);
  assert.ok(validateAbilityResultBytes(encoder.encode(lightningDamageResult('3.0', '1.9999998807907104', 'applied'))),
    'damage sum below five remains valid');
  assert.equal(validateAbilityResultBytes(encoder.encode(lightningDamageResult('3.0', '2.000000238418579', 'applied'))), null,
    'sum above five must not round down through Java float addition');
});

test('packed BlockPos bytes are structural only and invalid frames fail closed', () => {
  assert.ok(validateAbilityResultBytes(encoder.encode(lightningResult('-33554432', '-2048', '33554431'))));
  assert.ok(validateAbilityResultBytes(encoder.encode(lightningResult('33554431', '2047', '-33554432'))));
  assert.equal(validateAbilityResultBytes(encoder.encode(lightningResult('-33554433', '0', '0'))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(lightningResult('0', '2048', '0'))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(lightningResult('1.5', '64', '0'))), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(lightningResult('9007199254740992', '64', '0'))), null);

  assert.equal(validateAbilityResultBytes(null as unknown as Uint8Array), null);
  assert.equal(validateAbilityResultBytes(new Uint8Array([0xc3, 0x28])), null);
  assert.equal(validateAbilityResultBytes(new Uint8Array([0xef, 0xbb, 0xbf, 0x7b, 0x7d])), null);
  assert.equal(validateAbilityResultBytes(encoder.encode('{"x":}')), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(`${'['.repeat(9)}0${']'.repeat(9)}`)), null);
  assert.equal(validateAbilityResultBytes(encoder.encode(' '.repeat(4097))), null);
  const normal = observeResultJson('20.0', '20.0', '100', '100');
  const withExtra = normal.replace('"queryId":', `"unused":"${'x'.repeat(4100)}","queryId":`);
  assert.equal(validateAbilityResultBytes(encoder.encode(withExtra)), null);
  const withMultibytePadding = normal.replace('"queryId":', `"unused":"${'é'.repeat(2000)}","queryId":`);
  assert.equal(validateAbilityResultBytes(encoder.encode(withMultibytePadding)), null,
    '4 KiB is measured in UTF-8 bytes, not UTF-16 characters');
});

function expand(source: string): string {
  return source.replace(/\{\{([A-Za-z]+)\}\}/gu, (placeholder, key: string) => {
    const value = resultCorpus.facts[key];
    assert.ok(value, `unknown shared fixture placeholder ${placeholder}`);
    return value;
  });
}

function observeResult(health: string, maxHealth: string, observedAt: string, factObservedAt: string): AbilityResult | null {
  return validateAbilityResultBytes(encoder.encode(observeResultJson(health, maxHealth, observedAt, factObservedAt)));
}

function observeResultJson(health: string, maxHealth: string, observedAt: string, factObservedAt: string): string {
  return `{"serverId":"00000000-0000-4000-8000-000000000101","worldId":"00000000-0000-4000-8000-000000000102",` +
    `"characterId":"00000000-0000-4000-8000-000000000103","requestId":"00000000-0000-4000-8000-000000000104",` +
    `"invocationId":"00000000-0000-4000-8000-000000000105","abilityId":"worldspeaker.core.observe_player",` +
    `"contractVersion":1,"policyRevision":7,"serverEpochId":"${EPOCH}","state":"executed",` +
    `"reasonCode":"none","facts":{"targetPlayerId":"${TARGET}","observedAt":${factObservedAt},"health":${health},` +
    `"maxHealth":${maxHealth},"dimension":"minecraft:overworld","biome":"minecraft:plains",` +
    `"holdingItem":"minecraft:air"},"observedAt":${observedAt},"executedAt":null,"retryAfterMs":null,` +
    '"queryId":"00000000-0000-4000-8000-000000000108"}';
}

function lightningResult(x: string, y: string, z: string): string {
  return `{"serverId":"00000000-0000-4000-8000-000000000101","worldId":"00000000-0000-4000-8000-000000000102",` +
    `"characterId":"00000000-0000-4000-8000-000000000103","requestId":"00000000-0000-4000-8000-000000000104",` +
    `"invocationId":"00000000-0000-4000-8000-000000000105","abilityId":"worldspeaker.core.strike_lightning",` +
    `"contractVersion":1,"policyRevision":7,"serverEpochId":"${EPOCH}","state":"executed","reasonCode":"none",` +
    `"facts":{"actionId":"${ACTION}","targetPlayerId":"${TARGET}","dimension":"minecraft:overworld",` +
    `"executedAt":200,"coordinates":[${x},${y},${z}],"visualOnly":true,"steps":[{"kind":"presentation","state":"confirmed"}]},` +
    `"observedAt":null,"executedAt":200,"retryAfterMs":null,"actionId":"${ACTION}"}`;
}

function lightningDamageResult(
  healthRemoved: string,
  absorptionRemoved: string,
  disposition: DamageDisposition,
): string {
  return `{"serverId":"00000000-0000-4000-8000-000000000101","worldId":"00000000-0000-4000-8000-000000000102",` +
    `"characterId":"00000000-0000-4000-8000-000000000103","requestId":"00000000-0000-4000-8000-000000000104",` +
    `"invocationId":"00000000-0000-4000-8000-000000000105","abilityId":"worldspeaker.core.strike_lightning",` +
    `"contractVersion":1,"policyRevision":7,"serverEpochId":"${EPOCH}","state":"executed","reasonCode":"none",` +
    `"facts":{"actionId":"${ACTION}","targetPlayerId":"${TARGET}","dimension":"minecraft:overworld",` +
    `"executedAt":200,"coordinates":[0,64,0],"visualOnly":false,"steps":[{"kind":"presentation","state":"confirmed"},` +
    `{"kind":"damage","state":"confirmed","healthRemoved":${healthRemoved},"absorptionRemoved":${absorptionRemoved},` +
    `"damageDisposition":"${disposition}"}]},"observedAt":null,"executedAt":200,"retryAfterMs":null,"actionId":"${ACTION}"}`;
}
