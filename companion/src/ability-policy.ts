import { types as utilTypes } from 'node:util';
import { isCanonicalConfigUuid } from './config-uuid.js';
import {
  formatPrivateConfigurationProblem,
  privateConfigurationProblem,
  unknownPrivateConfigurationKey,
  type PrivateConfigurationProblem,
} from './private-configuration-problem.js';

export const OBSERVE_PLAYER_ABILITY = 'worldspeaker.core.observe_player' as const;
export const STRIKE_LIGHTNING_ABILITY = 'worldspeaker.core.strike_lightning' as const;
export const ABILITY_CONTRACT_VERSION = 1 as const;
export const MAX_POLICY_ENTRIES = 32;
export const MAX_POLICY_SAFE_INTEGER = Number.MAX_SAFE_INTEGER;

export type AbilityRegion = Readonly<{
  minX: number;
  maxXExclusive: number;
  minZ: number;
  maxZExclusive: number;
}>;
export type ObserveAbilityLimits = Readonly<{
  requesterIntervalMs: number;
  characterIntervalMs: number;
}>;
export type LightningAbilityLimits = Readonly<{
  requesterCooldownMs: number;
  targetCooldownMs: number;
  characterIntervalMs: number;
}>;
export type AbilityLimits = ObserveAbilityLimits | LightningAbilityLimits;

export type AbilityGrant = Readonly<{
  id: string;
  characterId: string;
  abilityId: typeof OBSERVE_PLAYER_ABILITY | typeof STRIKE_LIGHTNING_ABILITY;
  contractVersion: 1;
  requesterIds: readonly string[];
  otherTargetIds: readonly string[];
  dimensions: readonly string[];
  maxRangeBlocks: number;
  region: AbilityRegion | null;
  limits: AbilityLimits;
  allowVisual: boolean;
  allowDamage: boolean;
  maxDamage: number | null;
}>;

export type AbilityDenial = Readonly<{
  id: string;
  abilityId: typeof OBSERVE_PLAYER_ABILITY | typeof STRIKE_LIGHTNING_ABILITY;
  contractVersion: 1;
}>;

export type AbilityPolicy = Readonly<{
  schemaVersion: 1;
  revision: number;
  grants: readonly AbilityGrant[];
  denials: readonly AbilityDenial[];
  externalClaimsMode: 'REQUIRED' | 'NONE';
}>;

const ABILITIES = new Set<string>([OBSERVE_PLAYER_ABILITY, STRIKE_LIGHTNING_ABILITY]);
const STABLE_ID = /^[A-Za-z0-9._:-]{1,128}$/u;
const DIMENSION = /^[a-z0-9_.-]+:[a-z0-9/._-]+$/u;

export class AbilityPolicyError extends Error {
  readonly problem: PrivateConfigurationProblem;

  constructor(problem = privateConfigurationProblem('abilityPolicy', 'boundedAbilityPolicy')) {
    super(formatPrivateConfigurationProblem(problem));
    this.name = 'AbilityPolicyError';
    this.problem = problem;
  }
}

/** Parse complete v1 policy syntax. This snapshot does not expose or enable game operations. */
export function parseAbilityPolicy(value: unknown, boundCharacterId: string): AbilityPolicy {
  const policy = object(value);
  exact(policy, ['schemaVersion', 'revision', 'grants', 'denials', 'protection']);
  const revision = policy.revision;
  if (policy.schemaVersion !== 1) throw new AbilityPolicyError(privateConfigurationProblem('abilityPolicySchemaVersion', 'schemaVersion1'));
  if (!safeNonNegativeInteger(revision)) throw new AbilityPolicyError(privateConfigurationProblem('abilityPolicyRevision', 'boundedAbilityPolicy'));
  const protection = object(policy.protection);
  only(protection, ['externalClaimsMode']);
  const externalClaimsMode = Object.hasOwn(protection, 'externalClaimsMode')
    ? protection.externalClaimsMode
    : 'REQUIRED';
  if (externalClaimsMode !== 'REQUIRED' && externalClaimsMode !== 'NONE') throw new AbilityPolicyError();

  if (!dataArray(policy.grants) || policy.grants.length > MAX_POLICY_ENTRIES) throw new AbilityPolicyError();
  const stableIds = new Set<string>();
  const grants = policy.grants.map((raw): AbilityGrant => {
    const grant = object(raw);
    const abilityId = string(grant.abilityId);
    const lightning = abilityId === STRIKE_LIGHTNING_ABILITY;
    const required = ['id', 'characterId', 'abilityId', 'contractVersion', 'requesterIds', 'dimensions'];
    const optional = ['otherTargetIds', 'maxRangeBlocks', 'region', 'limits', ...(lightning ? ['allowVisual', 'allowDamage', 'maxDamage'] : [])];
    exactWithOptional(grant, required, optional);

    const id = string(grant.id);
    if (!STABLE_ID.test(id) || stableIds.has(id)) throw new AbilityPolicyError();
    stableIds.add(id);
    const characterId = string(grant.characterId);
    if (!ABILITIES.has(abilityId)) throw new AbilityPolicyError(privateConfigurationProblem('abilityGrantAbilityId', 'abilityIdV1'));
    if (characterId !== boundCharacterId || grant.contractVersion !== ABILITY_CONTRACT_VERSION) throw new AbilityPolicyError();
    const requesterIds = uuidList(grant.requesterIds, 1, 32);
    const otherTargetIds = grant.otherTargetIds === undefined ? [] : uuidList(grant.otherTargetIds, 0, 32);
    const dimensions = dimensionList(grant.dimensions);
    const maxRangeBlocks = grant.maxRangeBlocks === undefined ? 64 : finiteRange(grant.maxRangeBlocks);
    const region = grant.region === undefined ? null : parseRegion(grant.region);
    const limits = grant.limits === undefined
      ? lightning ? defaultLightningLimits() : defaultObserveLimits()
      : lightning ? parseLightningLimits(grant.limits) : parseObserveLimits(grant.limits);

    if (!lightning) {
      return Object.freeze({ id, characterId, abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1,
        requesterIds, otherTargetIds, dimensions, maxRangeBlocks, region, limits, allowVisual: false, allowDamage: false, maxDamage: null });
    }
    if (grant.allowVisual !== true || (grant.allowDamage !== undefined && typeof grant.allowDamage !== 'boolean')) {
      throw new AbilityPolicyError();
    }
    const allowDamage = grant.allowDamage === true;
    let maxDamage: number | null = null;
    if (allowDamage) maxDamage = boundedPositiveNumber(grant.maxDamage, 5);
    else if (grant.maxDamage !== undefined) throw new AbilityPolicyError();
    return Object.freeze({ id, characterId, abilityId: STRIKE_LIGHTNING_ABILITY, contractVersion: 1,
      requesterIds, otherTargetIds, dimensions, maxRangeBlocks, region, limits, allowVisual: true, allowDamage, maxDamage });
  });

  if (!dataArray(policy.denials) || policy.denials.length > MAX_POLICY_ENTRIES) throw new AbilityPolicyError();
  const denials = policy.denials.map((raw): AbilityDenial => {
    const denial = object(raw);
    exact(denial, ['id', 'abilityId', 'contractVersion']);
    const id = string(denial.id);
    if (!STABLE_ID.test(id) || stableIds.has(id)) throw new AbilityPolicyError();
    if (typeof denial.abilityId !== 'string' || !ABILITIES.has(denial.abilityId)) {
      throw new AbilityPolicyError(privateConfigurationProblem('abilityDenialAbilityId', 'abilityIdV1'));
    }
    const abilityId = denial.abilityId;
    if (denial.contractVersion !== ABILITY_CONTRACT_VERSION) throw new AbilityPolicyError();
    stableIds.add(id);
    return Object.freeze({ id, abilityId: abilityId as AbilityDenial['abilityId'], contractVersion: 1 });
  });

  return Object.freeze({ schemaVersion: 1, revision: Object.is(revision, -0) ? 0 : revision,
    grants: Object.freeze(grants), denials: Object.freeze(denials),
    externalClaimsMode: externalClaimsMode as AbilityPolicy['externalClaimsMode'] });
}

export function denyAllAbilityPolicy(): AbilityPolicy {
  return Object.freeze({ schemaVersion: 1, revision: 0, grants: Object.freeze([]), denials: Object.freeze([]), externalClaimsMode: 'REQUIRED' });
}

/** Re-validate a parsed authority projection before a caller-built transport may retain it. */
export function canonicalizeAbilityPolicySnapshot(value: unknown, boundCharacterId: string): AbilityPolicy {
  const snapshot = object(value);
  exact(snapshot, ['schemaVersion', 'revision', 'grants', 'denials', 'externalClaimsMode']);
  if (!dataArray(snapshot.grants) || !dataArray(snapshot.denials)) throw new AbilityPolicyError();
  const grants = snapshot.grants.map((raw) => {
    const grant = object(raw);
    const isLightning = grant.abilityId === STRIKE_LIGHTNING_ABILITY;
    const expected = ['id', 'characterId', 'abilityId', 'contractVersion', 'requesterIds', 'otherTargetIds',
      'dimensions', 'maxRangeBlocks', 'region', 'limits', 'allowVisual', 'allowDamage', 'maxDamage'];
    exact(grant, expected);
    const result: Record<string, unknown> = {
      id: grant.id, characterId: grant.characterId, abilityId: grant.abilityId,
      contractVersion: grant.contractVersion, requesterIds: grant.requesterIds,
      dimensions: grant.dimensions, maxRangeBlocks: grant.maxRangeBlocks,
    };
    if (Array.isArray(grant.otherTargetIds) && grant.otherTargetIds.length > 0) result.otherTargetIds = grant.otherTargetIds;
    result.region = grant.region === null ? undefined : grant.region;
    result.limits = grant.limits;
    if (isLightning) {
      result.allowVisual = grant.allowVisual;
      result.allowDamage = grant.allowDamage;
      if (grant.maxDamage !== null) result.maxDamage = grant.maxDamage;
    } else if (grant.allowVisual !== false || grant.allowDamage !== false || grant.maxDamage !== null) {
      throw new AbilityPolicyError();
    }
    return result;
  });
  const policy = {
    schemaVersion: snapshot.schemaVersion,
    revision: snapshot.revision,
    grants,
    denials: snapshot.denials,
    protection: { externalClaimsMode: snapshot.externalClaimsMode },
  };
  return parseAbilityPolicy(policy, boundCharacterId);
}

/** Explicit denial wins; returned complete grants are stable ordered and never merged. */
export function completeGrantsFor(policy: AbilityPolicy, abilityId: string): readonly AbilityGrant[] {
  if (policy.denials.some((denial) => denial.abilityId === abilityId)) return Object.freeze([]);
  return Object.freeze(policy.grants.filter((grant) => grant.abilityId === abilityId)
    .slice().sort((left, right) => left.id < right.id ? -1 : left.id > right.id ? 1 : 0));
}

function uuidList(value: unknown, minimum: number, maximum: number): readonly string[] {
  if (!dataArray(value) || value.length < minimum || value.length > maximum ||
      value.some((item) => !isCanonicalConfigUuid(item)) || new Set(value).size !== value.length) {
    throw new AbilityPolicyError();
  }
  return Object.freeze(value.map((item) => item as string));
}

function dimensionList(value: unknown): readonly string[] {
  if (!dataArray(value) || value.length < 1 || value.length > 16) throw new AbilityPolicyError();
  const values = value.map(string);
  if (values.some((item) => item.length > 256 || !DIMENSION.test(item)) || new Set(values).size !== values.length) {
    throw new AbilityPolicyError();
  }
  return Object.freeze(values);
}

function finiteRange(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0 || value > 64) throw new AbilityPolicyError();
  return value;
}

function parseRegion(value: unknown): AbilityRegion {
  const region = object(value);
  exact(region, ['minX', 'maxXExclusive', 'minZ', 'maxZExclusive']);
  const result = Object.freeze({
    minX: signedSafeInteger(region.minX),
    maxXExclusive: signedSafeInteger(region.maxXExclusive),
    minZ: signedSafeInteger(region.minZ),
    maxZExclusive: signedSafeInteger(region.maxZExclusive),
  });
  if (result.minX >= result.maxXExclusive || result.minZ >= result.maxZExclusive) throw new AbilityPolicyError();
  return result;
}

function parseObserveLimits(value: unknown): ObserveAbilityLimits {
  const limits = object(value);
  only(limits, ['requesterIntervalMs', 'characterIntervalMs']);
  return Object.freeze({
    requesterIntervalMs: limits.requesterIntervalMs === undefined ? 2_000 : boundedInteger(limits.requesterIntervalMs, 2_000, 300_000),
    characterIntervalMs: limits.characterIntervalMs === undefined ? 250 : boundedInteger(limits.characterIntervalMs, 250, 300_000),
  });
}

function parseLightningLimits(value: unknown): LightningAbilityLimits {
  const limits = object(value);
  only(limits, ['requesterCooldownMs', 'targetCooldownMs', 'characterIntervalMs']);
  return Object.freeze({
    requesterCooldownMs: limits.requesterCooldownMs === undefined ? 30_000 : boundedInteger(limits.requesterCooldownMs, 30_000, 300_000),
    targetCooldownMs: limits.targetCooldownMs === undefined ? 30_000 : boundedInteger(limits.targetCooldownMs, 30_000, 300_000),
    characterIntervalMs: limits.characterIntervalMs === undefined ? 1_000 : boundedInteger(limits.characterIntervalMs, 1_000, 300_000),
  });
}

function defaultObserveLimits(): ObserveAbilityLimits {
  return Object.freeze({ requesterIntervalMs: 2_000, characterIntervalMs: 250 });
}

function defaultLightningLimits(): LightningAbilityLimits {
  return Object.freeze({ requesterCooldownMs: 30_000, targetCooldownMs: 30_000, characterIntervalMs: 1_000 });
}

function boundedInteger(value: unknown, minimum: number, maximum: number): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new AbilityPolicyError();
  }
  return Object.is(value, -0) ? 0 : value;
}

function signedSafeInteger(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < -MAX_POLICY_SAFE_INTEGER ||
      value > MAX_POLICY_SAFE_INTEGER) throw new AbilityPolicyError();
  return Object.is(value, -0) ? 0 : value;
}

function boundedPositiveNumber(value: unknown, maximum: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0 || value > maximum) throw new AbilityPolicyError();
  return value;
}

function safeNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

function string(value: unknown): string {
  if (typeof value !== 'string') throw new AbilityPolicyError();
  return value;
}

function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value) || utilTypes.isProxy(value)) throw new AbilityPolicyError();
  const prototype = Object.getPrototypeOf(value);
  if (prototype !== Object.prototype && prototype !== null) throw new AbilityPolicyError();
  for (const key of Reflect.ownKeys(value)) {
    if (typeof key !== 'string') throw new AbilityPolicyError();
    const descriptor = Object.getOwnPropertyDescriptor(value, key);
    if (!descriptor || !Object.hasOwn(descriptor, 'value')) throw new AbilityPolicyError();
  }
  return value as Record<string, unknown>;
}

function dataArray(value: unknown): value is unknown[] {
  if (!Array.isArray(value) || utilTypes.isProxy(value) || Object.getPrototypeOf(value) !== Array.prototype) return false;
  const keys = Reflect.ownKeys(value);
  if (keys.length !== value.length + 1 || !keys.includes('length')) return false;
  for (let index = 0; index < value.length; index += 1) {
    const descriptor = Object.getOwnPropertyDescriptor(value, String(index));
    if (!descriptor || !Object.hasOwn(descriptor, 'value')) return false;
  }
  return true;
}

function exact(value: Record<string, unknown>, keys: readonly string[]): void {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  if (actual.some((key) => !keys.includes(key))) throw new AbilityPolicyError(unknownPrivateConfigurationKey());
  if (actual.length !== expected.length || actual.some((key, index) => key !== expected[index])) throw new AbilityPolicyError();
}

function exactWithOptional(value: Record<string, unknown>, required: readonly string[], optional: readonly string[]): void {
  const allowed = new Set([...required, ...optional]);
  const keys = Object.keys(value);
  if (keys.some((key) => !allowed.has(key))) throw new AbilityPolicyError(unknownPrivateConfigurationKey());
  if (required.some((key) => !Object.hasOwn(value, key))) throw new AbilityPolicyError();
}

function only(value: Record<string, unknown>, keys: readonly string[]): void {
  if (Object.keys(value).some((key) => !keys.includes(key))) throw new AbilityPolicyError(unknownPrivateConfigurationKey());
}
