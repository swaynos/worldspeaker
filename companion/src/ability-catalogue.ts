import { Ajv2020, type AnySchema, type ErrorObject, type ValidateFunction } from 'ajv/dist/2020.js';
import { readFileSync } from 'node:fs';
import { types as utilTypes } from 'node:util';
import { ABILITY_CATALOGUE } from './generated/ability-catalogue.js';
import { exactFloat32SumAtMost, exactFloat32SumIsPositive, exactFloat32SumIsZero } from './exact-float32-sum.js';
import { isMinecraft1211BlockPositionRepresentation } from './minecraft-coordinate-bounds.js';
import { isJavaFloat32WireNumber, normalizeFloat32WireNumber } from './protocol-text.js';
import {
  abilityDiscoverySchemaResources,
  assertAbilitySchemaResources,
  ABILITY_CATALOGUE_SCHEMA_RESOURCE_ID,
  ABILITY_PREDICATE_DIALECT_ID,
} from './ability-schema-resources.js';

export const OBSERVE_PLAYER_ABILITY = 'worldspeaker.core.observe_player' as const;
export const STRIKE_LIGHTNING_ABILITY = 'worldspeaker.core.strike_lightning' as const;
export const ABILITY_CATALOGUE_VERSION = 1 as const;
export const ABILITY_CONTRACT_VERSION = 1 as const;
export const MAX_ABILITY_FRAME_BYTES = 4096;
export const MAX_ABILITY_JSON_DEPTH = 8;
export const MAX_ABILITY_DISCOVERY_BYTES = 65_536;
export const MAX_POLICY_GRANT_SUMMARIES = 32;
export const ABILITY_SOURCE_SCHEMA_ID = 'https://worldspeaker.invalid/schema/ability-contracts-v1.json';
export const ABILITY_SOURCE_META_SCHEMA_ID = 'https://worldspeaker.invalid/schema/ability-contracts-v1.schema.json';
export const ABILITY_DISCOVERY_SCHEMA_ID = 'https://worldspeaker.invalid/schema/ability-discovery-v1.schema.json';
export const ABILITY_SCHEMA_PREDICATE_VOCABULARY = Object.freeze({
  id: 'https://worldspeaker.invalid/vocab/ability-predicates-v1',
  required: true,
  keywords: Object.freeze([
    'worldspeakerAsciiOnly', 'worldspeakerFloat32', 'worldspeakerHealthAtMostMaxHealth',
    'worldspeakerMinecraft1211BlockPosRepresentation', 'worldspeakerDamageStepFields',
    'worldspeakerLightningFactsSemantics', 'worldspeakerOutcomeStateFacts',
  ] as const),
});

export type AbilityId = typeof OBSERVE_PLAYER_ABILITY | typeof STRIKE_LIGHTNING_ABILITY;
export type AbilityState = 'rejected' | 'admitted' | 'submitted' | 'executed' | 'failed' | 'partial' | 'expired' | 'cancelled' | 'unknown';
export type AbilityReasonCode = 'none' | 'invalid_input' | 'unsupported_contract' | 'unsupported_state' |
  'permission_denied' | 'target_unavailable' | 'out_of_scope' | 'protected_area' | 'stale_context' |
  'rate_limited' | 'concurrency_limited' | 'deadline_expired' | 'cancelled' | 'storage_unavailable' |
  'execution_unavailable' | 'execution_failed' | 'outcome_unknown';

export type AbilityContract = Readonly<{
  abilityId: AbilityId;
  contractVersion: 1;
  kind: 'query' | 'effect';
  purpose: string;
  inputSchema: Readonly<{ $ref: string }>;
  resultSchema: Readonly<{ $ref: string }>;
  permission: Readonly<Record<string, unknown>>;
  bounds: Readonly<Record<string, unknown>>;
  preconditions: Readonly<Record<string, unknown>>;
  execution: Readonly<Record<string, unknown>>;
  reliability: Readonly<Record<string, unknown>>;
  privacy: Readonly<Record<string, unknown>>;
  presentation: Readonly<Record<string, unknown>>;
}>;

export type ObserveArguments = Readonly<{ targetPlayerId: string }>;
export type StrikeArguments = Readonly<{ targetPlayerId: string; visualOnly: boolean }>;
export type ValidatedAbilityInput =
  | Readonly<{ abilityId: typeof OBSERVE_PLAYER_ABILITY; contractVersion: 1; arguments: ObserveArguments }>
  | Readonly<{ abilityId: typeof STRIKE_LIGHTNING_ABILITY; contractVersion: 1; arguments: StrikeArguments }>;

export type ObserveFacts = Readonly<{
  targetPlayerId: string;
  observedAt: number;
  health: number;
  maxHealth: number;
  dimension: string;
  biome: string;
  holdingItem: string;
}>;
export type LightningStep = Readonly<{
  kind: 'presentation' | 'damage';
  state: 'prevented' | 'confirmed' | 'failed' | 'unknown';
  healthRemoved?: number | null;
  absorptionRemoved?: number | null;
  damageDisposition?: 'applied' | 'mitigated' | 'invulnerable' | 'protection_veto' | 'non_lethal_limit' | null;
}>;
export type LightningFacts = Readonly<{
  actionId: string;
  targetPlayerId: string;
  dimension: string;
  executedAt: number | null;
  coordinates: readonly [number, number, number] | null;
  visualOnly: boolean;
  steps: readonly LightningStep[];
}>;
export type AbilityOutcomeIdentity = Readonly<{
  serverId: string;
  worldId: string;
  characterId: string;
  requestId: string;
  invocationId: string;
  abilityId: AbilityId;
  contractVersion: 1;
  policyRevision: number;
  serverEpochId: string;
  state: AbilityState;
  reasonCode: AbilityReasonCode;
  observedAt: number | null;
  executedAt: number | null;
  retryAfterMs: number | null;
}>;
export type ObservePlayerResult = AbilityOutcomeIdentity & Readonly<{
  abilityId: typeof OBSERVE_PLAYER_ABILITY;
  queryId: string;
  facts: ObserveFacts | null;
}>;
export type StrikeLightningResult = AbilityOutcomeIdentity & Readonly<{
  abilityId: typeof STRIKE_LIGHTNING_ABILITY;
  actionId: string;
  facts: LightningFacts | null;
}>;
export type AbilityResult = ObservePlayerResult | StrikeLightningResult;

function deepFreeze<T>(value: T): T {
  if (value !== null && typeof value === 'object' && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const child of Object.values(value as Record<string, unknown>)) deepFreeze(child);
  }
  return value;
}

const catalogue = deepFreeze(ABILITY_CATALOGUE as unknown as Readonly<{
  catalogueVersion: 1;
  contracts: readonly AbilityContract[];
}>);
const knownIds = new Set<string>([OBSERVE_PLAYER_ABILITY, STRIKE_LIGHTNING_ABILITY]);
const MAX_ABILITY_SNAPSHOT_NODES = 1024;
const INVALID_ABILITY_JSON_SNAPSHOT = Symbol('invalid ability JSON snapshot');
const sourceSchema = JSON.parse(readFileSync(new URL('../compatibility/ability-contracts-v1.schema.json', import.meta.url), 'utf8')) as AnySchema;
const schemaResourceDocuments = abilityDiscoverySchemaResources().map((resource) => JSON.parse(resource.schemaJson) as AnySchema);
const predicateDialect = schemaResourceDocuments[0]!;
const discoverySchema = schemaResourceDocuments[1]!;
const catalogueSchemaResource = schemaResourceDocuments[2]!;

function healthIsAtMostMaxHealth(value: Record<string, unknown>): boolean {
  const health = normalizeFloat32WireNumber(value.health);
  const maxHealth = normalizeFloat32WireNumber(value.maxHealth);
  return health !== null && maxHealth !== null && health <= maxHealth;
}

const ajv = new Ajv2020({ allErrors: true, strict: false, validateFormats: true });
ajv.addKeyword({ keyword: 'worldspeakerAsciiOnly', schemaType: 'boolean', type: 'string',
  validate: (enabled: boolean, value: string) => !enabled || /^[\x00-\x7f]*$/u.test(value) });
ajv.addKeyword({ keyword: 'worldspeakerFloat32', schemaType: 'string', type: 'number',
  validate: (bound: string, value: number) => {
    if (!isJavaFloat32WireNumber(value)) return false;
    return bound === 'finite-positive' ? value > 0 : value >= 0;
  } });
ajv.addKeyword({ keyword: 'worldspeakerHealthAtMostMaxHealth', schemaType: 'boolean', type: 'object',
  validate: (enabled: boolean, value: Record<string, unknown>) => !enabled || healthIsAtMostMaxHealth(value) });
ajv.addKeyword({ keyword: 'worldspeakerMinecraft1211BlockPosRepresentation', schemaType: 'boolean', type: 'array',
  validate: (enabled: boolean, value: unknown[]) => !enabled || isMinecraft1211BlockPositionRepresentation(value) });
ajv.addKeyword({ keyword: 'worldspeakerDamageStepFields', schemaType: 'boolean', type: 'object',
  validate: (enabled: boolean, value: Record<string, unknown>) => {
    if (!enabled) return true;
    const damage = value.kind === 'damage';
    const hasDamageFields = ['healthRemoved', 'absorptionRemoved', 'damageDisposition'].every((key) => Object.hasOwn(value, key));
    if (damage !== hasDamageFields) return false;
    if (!damage) return true;
    const confirmed = value.state === 'confirmed';
    return confirmed ? value.healthRemoved !== null && value.absorptionRemoved !== null && value.damageDisposition !== null
      : value.healthRemoved === null && value.absorptionRemoved === null && value.damageDisposition === null;
  } });
ajv.addKeyword({ keyword: 'worldspeakerLightningFactsSemantics', schemaType: 'boolean', type: 'object',
  validate: (enabled: boolean, value: Record<string, unknown>) => {
    if (!enabled || !Array.isArray(value.steps)) return !enabled;
    const steps = value.steps as Array<Record<string, unknown>>;
    if (steps.length < 1 || steps.length > 2 || steps[0]?.kind !== 'presentation' ||
        steps.some((step, index) => index === 1 && step.kind !== 'damage') ||
        new Set(steps.map((step) => step.kind)).size !== steps.length) return false;
    const damage = steps.find((step) => step.kind === 'damage');
    if (value.visualOnly === true && damage !== undefined) return false;
    if (damage?.state !== 'confirmed') return true;
    const health = damage.healthRemoved;
    const absorption = damage.absorptionRemoved;
    return typeof health === 'number' && typeof absorption === 'number' && exactFloat32SumAtMost(health, absorption, 5);
  } });
const RESULT_REASON_STATES: Readonly<Record<AbilityReasonCode, readonly AbilityState[]>> = Object.freeze({
  none: ['admitted', 'submitted', 'executed'],
  invalid_input: ['rejected'],
  unsupported_contract: ['rejected'],
  unsupported_state: ['rejected', 'failed', 'partial'],
  permission_denied: ['rejected', 'failed', 'partial'],
  target_unavailable: ['rejected', 'failed', 'partial'],
  out_of_scope: ['rejected', 'failed', 'partial'],
  protected_area: ['rejected', 'failed', 'partial'],
  stale_context: ['rejected', 'failed', 'partial'],
  rate_limited: ['rejected'],
  concurrency_limited: ['rejected'],
  deadline_expired: ['expired', 'partial'],
  cancelled: ['cancelled', 'partial'],
  storage_unavailable: ['rejected', 'failed', 'partial', 'unknown'],
  execution_unavailable: ['rejected', 'failed', 'partial'],
  execution_failed: ['failed', 'partial'],
  outcome_unknown: ['unknown', 'partial'],
});

const PARTIAL_UNKNOWN_REASONS = new Set<AbilityReasonCode>(['outcome_unknown', 'storage_unavailable']);

function semanticRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

/**
 * Pure cross-field validation for the logical ability-result lifecycle.
 * Schema validation still owns field types, bounds, and closed-object checks.
 */
export function abilityResultSemanticsAreCoherent(value: unknown): boolean {
  const result = semanticRecord(value);
  if (!result || typeof result.abilityId !== 'string' || typeof result.state !== 'string' ||
      typeof result.reasonCode !== 'string' || !Object.hasOwn(result, 'facts') ||
      !Object.hasOwn(result, 'observedAt') || !Object.hasOwn(result, 'executedAt') ||
      !Object.hasOwn(result, 'retryAfterMs')) return false;

  const state = result.state as AbilityState;
  const reason = result.reasonCode as AbilityReasonCode;
  const reasonStates = Object.hasOwn(RESULT_REASON_STATES, reason) ? RESULT_REASON_STATES[reason] : undefined;
  if (!reasonStates?.includes(state)) return false;

  const retryAfterMs = result.retryAfterMs;
  if (retryAfterMs !== null && (state !== 'rejected' || reason !== 'rate_limited' ||
      typeof retryAfterMs !== 'number' || !Number.isSafeInteger(retryAfterMs) || retryAfterMs < 0 || retryAfterMs > 30_000)) return false;

  const facts = result.facts;
  if (facts === null) {
    if (state === 'executed' || state === 'partial' || result.observedAt !== null || result.executedAt !== null) return false;
    return result.abilityId === OBSERVE_PLAYER_ABILITY || result.abilityId === STRIKE_LIGHTNING_ABILITY;
  }

  if (result.abilityId === OBSERVE_PLAYER_ABILITY) {
    const observation = semanticRecord(facts);
    return state === 'executed' && reason === 'none' && observation !== null &&
      typeof observation.observedAt === 'number' && observation.observedAt === result.observedAt &&
      result.executedAt === null;
  }

  if (result.abilityId !== STRIKE_LIGHTNING_ABILITY || result.observedAt !== null) return false;
  const lightning = semanticRecord(facts);
  if (!lightning || lightning.actionId !== result.actionId || lightning.executedAt !== result.executedAt ||
      typeof lightning.visualOnly !== 'boolean' || !Array.isArray(lightning.steps) ||
      lightning.steps.length < 1 || lightning.steps.length > 2) return false;

  const steps: Record<string, unknown>[] = [];
  for (let index = 0; index < lightning.steps.length; index += 1) {
    if (!Object.hasOwn(lightning.steps, index)) return false;
    const step = semanticRecord(lightning.steps[index]);
    if (!step) return false;
    steps.push(step);
  }
  const presentation = steps[0];
  if (!presentation) return false;
  if (presentation.kind !== 'presentation' || typeof presentation.state !== 'string' ||
      !['prevented', 'confirmed', 'failed', 'unknown'].includes(presentation.state)) return false;
  if (Object.keys(presentation).some((field) => field !== 'kind' && field !== 'state')) return false;
  const damage = steps[1];
  if (damage !== undefined && (damage?.kind !== 'damage' || typeof damage.state !== 'string' ||
      !['prevented', 'confirmed', 'failed', 'unknown'].includes(damage.state))) return false;
  if (damage !== undefined && Object.keys(damage).some((field) =>
      !['kind', 'state', 'healthRemoved', 'absorptionRemoved', 'damageDisposition'].includes(field))) return false;
  if (lightning.visualOnly) {
    if (steps.length !== 1) return false;
  } else if (presentation.state === 'confirmed') {
    if (steps.length !== 2) return false;
  }

  if (damage !== undefined) {
    const damageFields = ['healthRemoved', 'absorptionRemoved', 'damageDisposition'];
    if (!damageFields.every((field) => Object.hasOwn(damage, field))) return false;
    if (damage.state === 'confirmed') {
      const health = damage.healthRemoved;
      const absorption = damage.absorptionRemoved;
      const disposition = damage.damageDisposition;
      if (typeof health !== 'number' || typeof absorption !== 'number' || typeof disposition !== 'string') return false;
      if (!exactFloat32SumAtMost(health, absorption, 5)) return false;
      if (disposition === 'applied' && !exactFloat32SumIsPositive(health, absorption)) return false;
      if (['invulnerable', 'protection_veto'].includes(disposition) && !exactFloat32SumIsZero(health, absorption)) return false;
    } else if (damage.healthRemoved !== null || damage.absorptionRemoved !== null || damage.damageDisposition !== null) return false;
  }

  const states = steps.map((step) => step.state);
  const hasConfirmed = states.includes('confirmed');
  const hasUnknown = states.includes('unknown');
  const allConfirmed = states.every((stepState) => stepState === 'confirmed');
  if (hasConfirmed && lightning.coordinates === null) return false;

  switch (state) {
    case 'executed':
      return reason === 'none' && allConfirmed;
    case 'partial':
      return !lightning.visualOnly && hasConfirmed && !allConfirmed &&
        (hasUnknown ? PARTIAL_UNKNOWN_REASONS.has(reason) : reason !== 'outcome_unknown');
    case 'failed':
      return !hasConfirmed && !hasUnknown;
    case 'unknown':
      return !hasConfirmed && hasUnknown && (reason === 'outcome_unknown' || reason === 'storage_unavailable');
    default:
      return false;
  }
}

ajv.addKeyword({ keyword: 'worldspeakerOutcomeStateFacts', schemaType: 'boolean', type: 'object',
  validate: (enabled: boolean, value: Record<string, unknown>) => !enabled || abilityResultSemanticsAreCoherent(value) });

const installedPredicateAdapters = new WeakSet<Ajv2020>();

/** Installs the required Worldspeaker predicate vocabulary; draft-only validators are not equivalent. */
export function installAbilitySchemaPredicates(validator: Ajv2020): void {
  if (installedPredicateAdapters.has(validator)) return;
  if (!validator.getSchema(ABILITY_PREDICATE_DIALECT_ID)) {
    throw new Error('Worldspeaker predicate dialect resource must be installed before its predicate adapter');
  }
  validator.addKeyword({ keyword: 'worldspeakerAsciiOnly', schemaType: 'boolean', type: 'string',
    validate: (enabled: boolean, value: string) => !enabled || /^[\x00-\x7f]*$/u.test(value) });
  validator.addKeyword({ keyword: 'worldspeakerFloat32', schemaType: 'string', type: 'number',
    validate: (bound: string, value: number) => {
      if (!isJavaFloat32WireNumber(value)) return false;
      return bound === 'finite-positive' ? value > 0 : value >= 0;
    } });
  validator.addKeyword({ keyword: 'worldspeakerHealthAtMostMaxHealth', schemaType: 'boolean', type: 'object',
    validate: (enabled: boolean, value: Record<string, unknown>) => !enabled || healthIsAtMostMaxHealth(value) });
  validator.addKeyword({ keyword: 'worldspeakerMinecraft1211BlockPosRepresentation', schemaType: 'boolean', type: 'array',
    validate: (enabled: boolean, value: unknown[]) => !enabled || isMinecraft1211BlockPositionRepresentation(value) });
  validator.addKeyword({ keyword: 'worldspeakerDamageStepFields', schemaType: 'boolean', type: 'object',
    validate: (enabled: boolean, value: Record<string, unknown>) => {
      if (!enabled) return true;
      const damage = value.kind === 'damage';
      const hasDamageFields = ['healthRemoved', 'absorptionRemoved', 'damageDisposition'].every((key) => Object.hasOwn(value, key));
      if (damage !== hasDamageFields) return false;
      if (!damage) return true;
      const confirmed = value.state === 'confirmed';
      return confirmed ? value.healthRemoved !== null && value.absorptionRemoved !== null && value.damageDisposition !== null
        : value.healthRemoved === null && value.absorptionRemoved === null && value.damageDisposition === null;
    } });
  validator.addKeyword({ keyword: 'worldspeakerLightningFactsSemantics', schemaType: 'boolean', type: 'object',
    validate: (enabled: boolean, value: Record<string, unknown>) => {
      if (!enabled || !Array.isArray(value.steps)) return !enabled;
      const steps = value.steps as Array<Record<string, unknown>>;
      if (steps.length < 1 || steps.length > 2 || steps[0]?.kind !== 'presentation' ||
          steps.some((step, index) => index === 1 && step.kind !== 'damage') ||
          new Set(steps.map((step) => step.kind)).size !== steps.length) return false;
      const damage = steps.find((step) => step.kind === 'damage');
      if (value.visualOnly === true && damage !== undefined) return false;
      if (damage?.state !== 'confirmed') return true;
      const health = damage.healthRemoved;
      const absorption = damage.absorptionRemoved;
      return typeof health === 'number' && typeof absorption === 'number' && exactFloat32SumAtMost(health, absorption, 5);
    } });
  validator.addKeyword({ keyword: 'worldspeakerOutcomeStateFacts', schemaType: 'boolean', type: 'object',
    validate: (enabled: boolean, value: Record<string, unknown>) => !enabled || abilityResultSemanticsAreCoherent(value) });
  installedPredicateAdapters.add(validator);
}

ajv.addMetaSchema(predicateDialect as Exclude<AnySchema, boolean>);
ajv.addSchema(catalogueSchemaResource, ABILITY_CATALOGUE_SCHEMA_RESOURCE_ID);
ajv.addSchema(sourceSchema as AnySchema, ABILITY_SOURCE_META_SCHEMA_ID);
ajv.addSchema(discoverySchema as AnySchema, ABILITY_DISCOVERY_SCHEMA_ID);
const validateCatalogue = ajv.getSchema(ABILITY_SOURCE_META_SCHEMA_ID) as ValidateFunction;
const validateDiscoverySchema = ajv.getSchema(ABILITY_DISCOVERY_SCHEMA_ID) as ValidateFunction;

export function abilityContracts(): readonly AbilityContract[] {
  assertValidAbilityCatalogue();
  return catalogue.contracts;
}

export function abilityContract(abilityId: string): AbilityContract | undefined {
  return abilityContracts().find((entry) => entry.abilityId === abilityId);
}

export function validateAbilityInputSchema(value: unknown): ValidatedAbilityInput | null {
  try {
    const snapshot = snapshotAbilityJsonData(value);
    if (snapshot === INVALID_ABILITY_JSON_SNAPSHOT || snapshot === null || typeof snapshot !== 'object' || Array.isArray(snapshot)) {
      return null;
    }

    const record = snapshot as Record<string, unknown>;
    const abilityId = record.abilityId;
    if (typeof abilityId !== 'string' || !knownIds.has(abilityId) || record.contractVersion !== 1) return null;
    const contract = abilityContract(abilityId);
    if (!contract) return null;
    const validator = ajv.getSchema(contract.inputSchema.$ref) as ValidateFunction | undefined;
    if (!validator || !validator(snapshot)) return null;

    const args = record.arguments as Record<string, unknown>;
    if (abilityId === OBSERVE_PLAYER_ABILITY) {
      return Object.freeze({ abilityId, contractVersion: 1,
        arguments: Object.freeze({ targetPlayerId: args.targetPlayerId as string }) });
    }
    if (abilityId === STRIKE_LIGHTNING_ABILITY) return Object.freeze({ abilityId, contractVersion: 1, arguments: Object.freeze({
      targetPlayerId: args.targetPlayerId as string,
      visualOnly: args.visualOnly === undefined ? true : args.visualOnly as boolean,
    }) });
    return null;
  } catch {
    return null;
  }
}

export function validateAbilityResultSchema(value: unknown): value is AbilityResult {
  try {
    if (value === null || typeof value !== 'object' || utilTypes.isProxy(value) || Array.isArray(value)) return false;
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) return false;
    const abilityIdDescriptor = Object.getOwnPropertyDescriptor(value, 'abilityId');
    if (!isDataPropertyDescriptor(abilityIdDescriptor) || typeof abilityIdDescriptor.value !== 'string') return false;
    const abilityId = abilityIdDescriptor.value;
    if (!knownIds.has(abilityId)) return false;

    const contract = abilityContract(abilityId);
    if (!contract) return false;
    const snapshot = snapshotAbilityJsonData(value);
    if (snapshot === INVALID_ABILITY_JSON_SNAPSHOT) return false;
    const validator = ajv.getSchema(contract.resultSchema.$ref) as ValidateFunction | undefined;
    return validator !== undefined && validator(snapshot);
  } catch {
    return false;
  }
}

type AbilityJsonSnapshotBudget = { nodes: number; jsonBytes: number };

function snapshotAbilityJsonData(value: unknown): unknown | typeof INVALID_ABILITY_JSON_SNAPSHOT {
  const budget: AbilityJsonSnapshotBudget = { nodes: 0, jsonBytes: 0 };
  return snapshotJsonData(value, 0, budget);
}

function snapshotJsonData(
  value: unknown,
  containerDepth: number,
  budget: AbilityJsonSnapshotBudget,
): unknown | typeof INVALID_ABILITY_JSON_SNAPSHOT {
  budget.nodes++;
  if (budget.nodes > MAX_ABILITY_SNAPSHOT_NODES) return INVALID_ABILITY_JSON_SNAPSHOT;

  if (value === null) return addSnapshotBytes(budget, 'null') ? null : INVALID_ABILITY_JSON_SNAPSHOT;
  if (typeof value === 'string') {
    return addQuotedJsonTextBytes(budget, value) ? value : INVALID_ABILITY_JSON_SNAPSHOT;
  }
  if (typeof value === 'boolean') return addSnapshotBytes(budget, value ? 'true' : 'false') ? value : INVALID_ABILITY_JSON_SNAPSHOT;
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return INVALID_ABILITY_JSON_SNAPSHOT;
    const encoded = JSON.stringify(value);
    return addSnapshotBytes(budget, encoded) ? value : INVALID_ABILITY_JSON_SNAPSHOT;
  }
  if (typeof value !== 'object' || utilTypes.isProxy(value) || containerDepth >= MAX_ABILITY_JSON_DEPTH) {
    return INVALID_ABILITY_JSON_SNAPSHOT;
  }

  const nextDepth = containerDepth + 1;
  if (Array.isArray(value)) return snapshotJsonArray(value, nextDepth, budget);
  const prototype = Object.getPrototypeOf(value);
  if (prototype !== Object.prototype && prototype !== null) return INVALID_ABILITY_JSON_SNAPSHOT;
  if (hasTooManyEnumerableKeys(value, MAX_ABILITY_SNAPSHOT_NODES - budget.nodes)) {
    return INVALID_ABILITY_JSON_SNAPSHOT;
  }

  const keys = Reflect.ownKeys(value);
  if (keys.length > MAX_ABILITY_SNAPSHOT_NODES - budget.nodes || !addSnapshotBytes(budget, '{')) {
    return INVALID_ABILITY_JSON_SNAPSHOT;
  }
  const snapshot = Object.create(null) as Record<string, unknown>;
  for (let index = 0; index < keys.length; index++) {
    const key = keys[index];
    if (typeof key !== 'string') return INVALID_ABILITY_JSON_SNAPSHOT;
    const descriptor = Object.getOwnPropertyDescriptor(value, key);
    if (!isDataPropertyDescriptor(descriptor) || !descriptor.enumerable) return INVALID_ABILITY_JSON_SNAPSHOT;
    if (index > 0 && !addSnapshotBytes(budget, ',')) return INVALID_ABILITY_JSON_SNAPSHOT;
    if (!addQuotedJsonTextBytes(budget, key) || !addSnapshotBytes(budget, ':')) return INVALID_ABILITY_JSON_SNAPSHOT;
    const child = snapshotJsonData(descriptor.value, nextDepth, budget);
    if (child === INVALID_ABILITY_JSON_SNAPSHOT) return INVALID_ABILITY_JSON_SNAPSHOT;
    Object.defineProperty(snapshot, key, { value: child, enumerable: true, writable: true, configurable: true });
  }
  return addSnapshotBytes(budget, '}') ? snapshot : INVALID_ABILITY_JSON_SNAPSHOT;
}

function snapshotJsonArray(
  value: unknown[],
  containerDepth: number,
  budget: AbilityJsonSnapshotBudget,
): unknown[] | typeof INVALID_ABILITY_JSON_SNAPSHOT {
  if (Object.getPrototypeOf(value) !== Array.prototype) return INVALID_ABILITY_JSON_SNAPSHOT;
  const lengthDescriptor = Object.getOwnPropertyDescriptor(value, 'length');
  if (!isDataPropertyDescriptor(lengthDescriptor) || typeof lengthDescriptor.value !== 'number' ||
      !Number.isSafeInteger(lengthDescriptor.value) || lengthDescriptor.value < 0 ||
      lengthDescriptor.value > MAX_ABILITY_SNAPSHOT_NODES - budget.nodes) return INVALID_ABILITY_JSON_SNAPSHOT;
  const length = lengthDescriptor.value;
  if (hasTooManyEnumerableKeys(value, MAX_ABILITY_SNAPSHOT_NODES - budget.nodes)) {
    return INVALID_ABILITY_JSON_SNAPSHOT;
  }
  const keys = Reflect.ownKeys(value);
  if (keys.length !== length + 1 || !addSnapshotBytes(budget, '[')) return INVALID_ABILITY_JSON_SNAPSHOT;
  for (const key of keys) {
    if (key === 'length') continue;
    if (typeof key !== 'string' || !isArrayIndexKey(key, length)) return INVALID_ABILITY_JSON_SNAPSHOT;
  }

  const snapshot: unknown[] = new Array(length);
  let index = 0;
  for (const key of keys) {
    if (key === 'length') continue;
    const descriptor = Object.getOwnPropertyDescriptor(value, key);
    if (!isDataPropertyDescriptor(descriptor) || !descriptor.enumerable ||
        (index > 0 && !addSnapshotBytes(budget, ','))) return INVALID_ABILITY_JSON_SNAPSHOT;
    const child = snapshotJsonData(descriptor.value, containerDepth, budget);
    if (child === INVALID_ABILITY_JSON_SNAPSHOT) return INVALID_ABILITY_JSON_SNAPSHOT;
    snapshot[index] = child;
    index++;
  }
  return addSnapshotBytes(budget, ']') ? snapshot : INVALID_ABILITY_JSON_SNAPSHOT;
}

function isDataPropertyDescriptor(descriptor: PropertyDescriptor | undefined): descriptor is PropertyDescriptor & { value: unknown } {
  return descriptor !== undefined && Object.hasOwn(descriptor, 'value');
}

function isArrayIndexKey(key: string, length: number): boolean {
  if (!/^(0|[1-9][0-9]*)$/u.test(key)) return false;
  const index = Number(key);
  return Number.isSafeInteger(index) && index < length;
}

function hasTooManyEnumerableKeys(value: object, maximum: number): boolean {
  let count = 0;
  for (const key in value) {
    if (!Object.hasOwn(value, key)) continue;
    count++;
    if (count > maximum) return true;
  }
  return false;
}

function addSnapshotBytes(budget: AbilityJsonSnapshotBudget, value: string): boolean {
  budget.jsonBytes += Buffer.byteLength(value, 'utf8');
  return budget.jsonBytes <= MAX_ABILITY_FRAME_BYTES;
}

function addQuotedJsonTextBytes(budget: AbilityJsonSnapshotBudget, value: string): boolean {
  if (value.length > MAX_ABILITY_FRAME_BYTES - budget.jsonBytes) return false;
  const encoded = JSON.stringify(value);
  return typeof encoded === 'string' && addSnapshotBytes(budget, encoded);
}

export function assertValidAbilityCatalogue(): void {
  if (!validateCatalogue(ABILITY_CATALOGUE)) throw new Error(`invalid canonical ability catalogue: ${formatErrors(validateCatalogue.errors)}`);
  if (catalogue.catalogueVersion !== ABILITY_CATALOGUE_VERSION || catalogue.contracts.length !== 2) {
    throw new Error('canonical ability catalogue selection mismatch');
  }
}

export function assertValidAbilityDiscovery(value: unknown): asserts value is Record<string, unknown> {
  if (!validateDiscoverySchema(value)) throw new Error(`invalid ability discovery: ${formatErrors(validateDiscoverySchema.errors)}`);
  assertAbilitySchemaResources((value as Record<string, unknown>).schemaResources);
}

export function formatAbilitySchemaErrors(errors: readonly ErrorObject[] | null | undefined): string {
  return formatErrors(errors);
}

function formatErrors(errors: readonly ErrorObject[] | null | undefined): string {
  return errors?.map((error) => `${error.instancePath || '/'} ${error.keyword}`).join('; ') ?? 'schema rejected';
}
