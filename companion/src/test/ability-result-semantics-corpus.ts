import {
  OBSERVE_PLAYER_ABILITY,
  STRIKE_LIGHTNING_ABILITY,
  type AbilityReasonCode,
  type AbilityResult,
  type AbilityState,
  type LightningFacts,
  type ObserveFacts,
} from '../ability-catalogue.js';

const SERVER = '00000000-0000-4000-8000-000000000101';
const WORLD = '00000000-0000-4000-8000-000000000102';
const CHARACTER = '00000000-0000-4000-8000-000000000103';
const REQUEST = '00000000-0000-4000-8000-000000000104';
const INVOCATION = '00000000-0000-4000-8000-000000000105';
const EPOCH = '00000000-0000-4000-8000-000000000106';
const TARGET = '00000000-0000-4000-8000-000000000107';
const QUERY = '00000000-0000-4000-8000-000000000108';
const ACTION = '00000000-0000-4000-8000-000000000109';

const OBSERVATION: ObserveFacts = {
  targetPlayerId: TARGET,
  observedAt: 100,
  health: 18,
  maxHealth: 20,
  dimension: 'minecraft:overworld',
  biome: 'minecraft:plains',
  holdingItem: 'minecraft:air',
};

function observeResult(
  state: AbilityState,
  reasonCode: AbilityReasonCode,
  facts: ObserveFacts | null = null,
  retryAfterMs: number | null = null,
): AbilityResult {
  return {
    serverId: SERVER,
    worldId: WORLD,
    characterId: CHARACTER,
    requestId: REQUEST,
    invocationId: INVOCATION,
    abilityId: OBSERVE_PLAYER_ABILITY,
    contractVersion: 1,
    policyRevision: 7,
    serverEpochId: EPOCH,
    state,
    reasonCode,
    observedAt: facts?.observedAt ?? null,
    executedAt: null,
    retryAfterMs,
    queryId: QUERY,
    facts,
  } as AbilityResult;
}

function lightningResult(
  state: AbilityState,
  reasonCode: AbilityReasonCode,
  facts: LightningFacts | null = null,
): AbilityResult {
  return {
    serverId: SERVER,
    worldId: WORLD,
    characterId: CHARACTER,
    requestId: REQUEST,
    invocationId: INVOCATION,
    abilityId: STRIKE_LIGHTNING_ABILITY,
    contractVersion: 1,
    policyRevision: 7,
    serverEpochId: EPOCH,
    state,
    reasonCode,
    observedAt: null,
    executedAt: facts?.executedAt ?? null,
    retryAfterMs: null,
    actionId: ACTION,
    facts,
  } as AbilityResult;
}

function lightningFacts(
  visualOnly: boolean,
  steps: LightningFacts['steps'],
  overrides: Partial<Pick<LightningFacts, 'executedAt' | 'coordinates'>> = {},
): LightningFacts {
  return {
    actionId: ACTION,
    targetPlayerId: TARGET,
    dimension: 'minecraft:overworld',
    executedAt: 200,
    coordinates: [0, 64, 0],
    visualOnly,
    steps,
    ...overrides,
  };
}

const visualSuccess = lightningFacts(true, [{ kind: 'presentation', state: 'confirmed' }]);
const damageSuccessZero = lightningFacts(false, [
  { kind: 'presentation', state: 'confirmed' },
  { kind: 'damage', state: 'confirmed', healthRemoved: 0, absorptionRemoved: 0, damageDisposition: 'mitigated' },
]);
const damageSuccessApplied = lightningFacts(false, [
  { kind: 'presentation', state: 'confirmed' },
  { kind: 'damage', state: 'confirmed', healthRemoved: 1.5, absorptionRemoved: 0, damageDisposition: 'applied' },
]);
const damageSuccessNonLethalCap = lightningFacts(false, [
  { kind: 'presentation', state: 'confirmed' },
  { kind: 'damage', state: 'confirmed', healthRemoved: 0.5, absorptionRemoved: 0, damageDisposition: 'non_lethal_limit' },
]);
const presentationThenDamageFailed = lightningFacts(false, [
  { kind: 'presentation', state: 'confirmed' },
  { kind: 'damage', state: 'failed', healthRemoved: null, absorptionRemoved: null, damageDisposition: null },
]);
const presentationThenDamageUnknown = lightningFacts(false, [
  { kind: 'presentation', state: 'confirmed' },
  { kind: 'damage', state: 'unknown', healthRemoved: null, absorptionRemoved: null, damageDisposition: null },
]);
const presentationThenDamagePrevented = lightningFacts(false, [
  { kind: 'presentation', state: 'confirmed' },
  { kind: 'damage', state: 'prevented', healthRemoved: null, absorptionRemoved: null, damageDisposition: null },
]);
const failedPresentationThenDamageConfirmed = lightningFacts(false, [
  { kind: 'presentation', state: 'failed' },
  { kind: 'damage', state: 'confirmed', healthRemoved: 0.5, absorptionRemoved: 0, damageDisposition: 'applied' },
]);
const unknownPresentationThenDamageConfirmed = lightningFacts(false, [
  { kind: 'presentation', state: 'unknown' },
  { kind: 'damage', state: 'confirmed', healthRemoved: 0.5, absorptionRemoved: 0, damageDisposition: 'applied' },
]);

export type AbilityResultSemanticCase = Readonly<{ name: string; result: AbilityResult }>;

/** Valid examples shared by the result semantic regression tests. */
export const ABILITY_RESULT_SEMANTIC_POSITIVES: readonly AbilityResultSemanticCase[] = [
  { name: 'query executed with its snapshot', result: observeResult('executed', 'none', OBSERVATION) },
  { name: 'rejected invalid input', result: observeResult('rejected', 'invalid_input') },
  { name: 'rejected unsupported contract', result: observeResult('rejected', 'unsupported_contract') },
  { name: 'rejected unsupported state', result: observeResult('rejected', 'unsupported_state') },
  { name: 'rejected permission denial', result: observeResult('rejected', 'permission_denied') },
  { name: 'rejected unavailable target', result: observeResult('rejected', 'target_unavailable') },
  { name: 'rejected out of scope', result: observeResult('rejected', 'out_of_scope') },
  { name: 'rejected protected area', result: observeResult('rejected', 'protected_area') },
  { name: 'rejected stale context', result: observeResult('rejected', 'stale_context') },
  { name: 'rejected known rate limit with bounded retry hint', result: observeResult('rejected', 'rate_limited', null, 30_000) },
  { name: 'rejected concurrency limit without retry hint', result: observeResult('rejected', 'concurrency_limited') },
  { name: 'rejected unavailable storage', result: observeResult('rejected', 'storage_unavailable') },
  { name: 'rejected unavailable executor', result: observeResult('rejected', 'execution_unavailable') },
  { name: 'query admitted without a snapshot', result: observeResult('admitted', 'none') },
  { name: 'query submitted without a snapshot', result: observeResult('submitted', 'none') },
  { name: 'query failed on unsupported server state', result: observeResult('failed', 'unsupported_state') },
  { name: 'query expired without a reusable snapshot', result: observeResult('expired', 'deadline_expired') },
  { name: 'query cancelled before a snapshot', result: observeResult('cancelled', 'cancelled') },
  { name: 'query outcome remains unknown without invented facts', result: observeResult('unknown', 'outcome_unknown') },
  { name: 'query outcome remains unknown when evidence storage is unavailable', result: observeResult('unknown', 'storage_unavailable') },
  { name: 'visual lightning executed with confirmed presentation', result: lightningResult('executed', 'none', visualSuccess) },
  { name: 'damage-mode execution can confirm zero mitigated damage', result: lightningResult('executed', 'none', damageSuccessZero) },
  { name: 'damage-mode execution confirms applied damage', result: lightningResult('executed', 'none', damageSuccessApplied) },
  { name: 'non-lethal damage limit can coexist with confirmed positive measured loss', result: lightningResult('executed', 'none', damageSuccessNonLethalCap) },
  { name: 'lightning failed with a known failed presentation', result: lightningResult('failed', 'execution_failed', lightningFacts(true, [
    { kind: 'presentation', state: 'failed' },
  ])) },
  { name: 'lightning failed before any facts were available', result: lightningResult('failed', 'execution_failed') },
  { name: 'presentation confirmed and damage failed is partial, not rollback', result: lightningResult('partial', 'execution_failed', presentationThenDamageFailed) },
  { name: 'presentation confirmed and damage unknown remains partial and uncertain', result: lightningResult('partial', 'outcome_unknown', presentationThenDamageUnknown) },
  { name: 'presentation confirmed and damage prevented remains partial', result: lightningResult('partial', 'protected_area', presentationThenDamagePrevented) },
  { name: 'failed presentation does not erase later confirmed damage', result: lightningResult('partial', 'execution_failed', failedPresentationThenDamageConfirmed) },
  { name: 'unknown presentation does not erase later confirmed damage', result: lightningResult('partial', 'outcome_unknown', unknownPresentationThenDamageConfirmed) },
  { name: 'lightning remains unknown when presentation outcome is unknown', result: lightningResult('unknown', 'outcome_unknown', lightningFacts(true, [
    { kind: 'presentation', state: 'unknown' },
  ], { executedAt: null, coordinates: null })) },
  { name: 'lightning outcome can be unknown before facts are available', result: lightningResult('unknown', 'outcome_unknown') },
  { name: 'lightning expires before execution without coordinates', result: lightningResult('expired', 'deadline_expired') },
  { name: 'lightning cancels before execution without claiming a receipt', result: lightningResult('cancelled', 'cancelled') },
];
