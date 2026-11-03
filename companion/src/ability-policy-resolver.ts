import { types as utilTypes } from 'node:util';
import {
  completeGrantsFor,
  OBSERVE_PLAYER_ABILITY,
  STRIKE_LIGHTNING_ABILITY,
  type AbilityGrant,
  type AbilityPolicy,
  type AbilityRegion,
  type LightningAbilityLimits,
  type ObserveAbilityLimits,
} from './ability-policy.js';

export type AbilityBinding = Readonly<{
  serverId: string;
  worldId: string;
  characterId: string;
  agentId: string;
}>;
export type AbilityPosition = Readonly<{ x: number; y: number; z: number }>;
export type AbilitySpatialBounds = Readonly<{
  minX: number;
  maxXExclusive: number;
  minZ: number;
  maxZExclusive: number;
}>;
export type AbilityWorldBorder = AbilitySpatialBounds;

/** Trusted observations are nullable: absent facts remain unresolved, never false or qualified. */
export type AbilityWorldFacts = Readonly<{
  currentWorldMatches: boolean | null;
  requestLive: boolean | null;
  requesterConnected: boolean | null;
  targetResolved: boolean | null;
  targetPlayerId: string | null;
  targetConnected: boolean | null;
  targetAlive: boolean | null;
  targetNotSpectating: boolean | null;
  targetChunkLoadedAndTicking: boolean | null;
  requesterDimension: string | null;
  targetDimension: string | null;
  requesterFeet: AbilityPosition | null;
  targetFeet: AbilityPosition | null;
  worldBorder: AbilityWorldBorder | null;
  lightningSkyAccess: boolean | null;
  protectedSpawnClear: boolean | null;
  protectedClaimsClear: boolean | null;
}>;

export type AbilityResolverCheck =
  | 'authenticated_request_eligibility'
  | 'requester_connected'
  | 'current_world_instance'
  | 'normative_world_identity_mapping'
  | 'target_resolution'
  | 'target_connected_alive_non_spectating'
  | 'same_dimension'
  | 'requester_target_range'
  | 'world_border'
  | 'grant_region'
  | 'target_chunk_loaded_and_ticking'
  | 'observation_freshness'
  | 'lightning_sky_access'
  | 'protected_spawn'
  | 'external_claims_authority'
  | 'atomic_limit_admission'
  | 'execution_boundary_policy_recheck'
  | 'durable_preparation'
  | 'tick_thread_revalidation'
  | 'native_lightning_side_effect_suppression'
  | 'damage_nonlethal_qualification'
  | 'authoritative_outcome_identity_mapping'
  | 'unknown_outcome_persistence';

export type AbilityPolicyReason =
  | 'none'
  | 'stale_context'
  | 'unsupported_contract'
  | 'explicit_denial'
  | 'no_complete_grant'
  | 'requester_not_granted'
  | 'target_not_granted'
  | 'dimension_not_granted'
  | 'target_unavailable'
  | 'out_of_scope'
  | 'selected_grant_mode_denied';

export type AbilityEffectiveRestrictions = Readonly<{
  maxRangeBlocks: number;
  /** Configured grant region; this is not the current world border or its intersection. */
  region: AbilityRegion | null;
  /** Current trusted fact, or null when unavailable/invalid. Never inferred from the grant. */
  worldBorder: AbilityWorldBorder | null;
  /** Known non-empty intersection of region and worldBorder; null if unknown or empty. */
  effectiveSpatialBounds: AbilitySpatialBounds | null;
  mode: 'observe' | 'visual' | 'damage';
  maxDamage: number | null;
  limits: ObserveAbilityLimits | LightningAbilityLimits;
}>;

export type AbilityLimitDemand = Readonly<{
  policyRevision: number;
  grantId: string;
  abilityId: string;
  characterScope: Readonly<{ serverId: string; worldId: string; characterId: string }>;
  requesterScope: Readonly<{ serverId: string; worldId: string; characterId: string; requesterId: string }>;
  targetScope: Readonly<{ serverId: string; worldId: string; characterId: string; targetPlayerId: string }>;
  invocationId: string;
  requesterIntervalMs: number | null;
  targetCooldownMs: number | null;
  characterIntervalMs: number;
  maxInFlightPerRequester: 1;
  maxInFlightPerCharacter: 1 | 16;
  invocationsPerOrigin: 1;
}>;

export type AbilityPolicyResolution = Readonly<{
  status: 'DENIED' | 'CONDITIONAL';
  abilityId: string;
  contractVersion: number;
  policyRevision: number;
  selectedGrantId: string | null;
  candidateGrantIds: readonly string[];
  effectiveRestrictions: AbilityEffectiveRestrictions | null;
  unresolvedChecks: readonly AbilityResolverCheck[];
  reasonCode: AbilityPolicyReason;
  limitDemand: AbilityLimitDemand | null;
}>;

export type AbilityPolicyResolverInput = Readonly<{
  binding: AbilityBinding;
  policy: AbilityPolicy;
  policyRevision: number;
  authorityCurrent: boolean;
  requesterId: string;
  invocationId: string;
  abilityId: string;
  contractVersion: number;
  targetPlayerId?: string;
  visualOnly?: boolean;
  facts?: AbilityWorldFacts | null;
}>;

/** Pure policy evaluation. It describes conditions only and can never admit or execute work. */
export function resolveAbilityPolicy(input: AbilityPolicyResolverInput): AbilityPolicyResolution {
  if (!isResolverInput(input)) {
    return denied({ abilityId: '', contractVersion: 1, policyRevision: 0 }, 'stale_context');
  }
  const base = {
    abilityId: input.abilityId,
    contractVersion: input.contractVersion,
    policyRevision: safeRevision(input.policyRevision) ? input.policyRevision : 0,
  };
  if (input.authorityCurrent !== true || !validBinding(input.binding) || !safeRevision(input.policyRevision) ||
      input.policy === null || typeof input.policy !== 'object' || input.policy.revision !== input.policyRevision ||
      !canonicalUuid(input.requesterId) || !canonicalUuid(input.invocationId)) {
    return denied(base, 'stale_context');
  }
  if (input.contractVersion !== 1 ||
      input.abilityId !== OBSERVE_PLAYER_ABILITY && input.abilityId !== STRIKE_LIGHTNING_ABILITY) {
    return denied(base, 'unsupported_contract');
  }
  const abilityId = input.abilityId;
  if (input.policy.denials.some((denial) => denial.abilityId === abilityId && denial.contractVersion === input.contractVersion)) {
    return denied(base, 'explicit_denial');
  }

  const matchingAbility = completeGrantsFor(input.policy, abilityId)
    .filter((grant) => grant.characterId === input.binding.characterId && grant.contractVersion === input.contractVersion);
  if (matchingAbility.length === 0) return denied(base, 'no_complete_grant');
  const matchingRequester = matchingAbility.filter((grant) => grant.requesterIds.includes(input.requesterId));
  if (matchingRequester.length === 0) return denied(base, 'requester_not_granted');

  const requestedTargetId = Object.hasOwn(input, 'targetPlayerId') ? input.targetPlayerId : undefined;
  const visualOnly = Object.hasOwn(input, 'visualOnly') ? input.visualOnly : undefined;
  const suppliedFacts = Object.hasOwn(input, 'facts') ? input.facts : undefined;
  if (requestedTargetId === undefined) {
    const candidates = matchingRequester.slice().sort((a, b) => asciiCompare(a.id, b.id));
    return conditional({ ...base, candidateGrantIds: Object.freeze(candidates.map((grant) => grant.id)) }, 'none', []);
  }
  if (!canonicalUuid(requestedTargetId)) return denied(base, 'target_unavailable');
  const matchingTarget = matchingRequester.filter((grant) => requestedTargetId === input.requesterId ||
    grant.otherTargetIds.includes(requestedTargetId));
  if (matchingTarget.length === 0) return denied(base, 'target_not_granted');
  const damageRequested = input.abilityId === STRIKE_LIGHTNING_ABILITY && visualOnly === false;
  if (damageRequested && matchingTarget.every((grant) => !grant.allowVisual || !grant.allowDamage || grant.maxDamage === null)) {
    return denied(base, 'selected_grant_mode_denied');
  }

  const facts = suppliedFacts ?? null;
  if (facts !== null && !consistentResolvedTargetFacts(facts, requestedTargetId)) {
    return denied(base, 'target_unavailable');
  }
  if (facts?.currentWorldMatches === false) return denied(base, 'stale_context');
  if (facts?.requestLive === false) return denied(base, 'stale_context');
  if (facts?.requesterConnected === false || facts?.targetConnected === false || facts?.targetAlive === false ||
      facts?.targetNotSpectating === false) return denied(base, 'target_unavailable');
  if (abilityId === STRIKE_LIGHTNING_ABILITY &&
      (facts?.lightningSkyAccess === false || facts?.protectedSpawnClear === false ||
        input.policy.externalClaimsMode === 'REQUIRED' && facts?.protectedClaimsClear === false)) {
    return denied(base, 'out_of_scope');
  }

  const targetDimension = facts?.targetResolved === true && facts.targetPlayerId === requestedTargetId
    ? facts.targetDimension : null;
  let dimensionCandidates = matchingTarget;
  if (targetDimension !== null) {
    if (facts?.requesterDimension !== null && facts?.requesterDimension !== undefined &&
        facts.requesterDimension !== targetDimension) return denied(base, 'out_of_scope');
    dimensionCandidates = matchingTarget.filter((grant) => grant.dimensions.includes(targetDimension));
    if (dimensionCandidates.length === 0) return denied(base, 'dimension_not_granted');
  }
  // Without trusted target dimension, no execution grant is selected: selection depends on that fact.
  if (targetDimension === null) {
    const unresolved = baseUnresolved(facts, abilityId, input.policy.externalClaimsMode, visualOnly === false);
    unresolved.push('same_dimension');
    return conditional({ ...base, candidateGrantIds: Object.freeze(matchingTarget
      .slice().sort((a, b) => asciiCompare(a.id, b.id)).map((grant) => grant.id)),
    }, 'none', uniqueChecks(unresolved));
  }

  const selected = dimensionCandidates.slice().sort((a, b) => asciiCompare(a.id, b.id))[0]!;
  if (abilityId === STRIKE_LIGHTNING_ABILITY && visualOnly === false &&
      (!selected.allowVisual || !selected.allowDamage || selected.maxDamage === null)) {
    return denied({ ...base, selectedGrantId: selected.id,
      candidateGrantIds: Object.freeze([selected.id]),
      effectiveRestrictions: restrictions(selected, 'damage', facts) }, 'selected_grant_mode_denied');
  }

  if (spatialIntersection(selected.region, facts?.worldBorder ?? null).kind === 'empty') {
    return denied(base, 'out_of_scope');
  }
  const mode = abilityId === OBSERVE_PLAYER_ABILITY ? 'observe' : visualOnly === false ? 'damage' : 'visual';
  const effective = restrictions(selected, mode, facts);
  const reason = checkSpatialRestrictions(selected, abilityId, facts);
  if (reason !== null) return denied({ ...base, selectedGrantId: selected.id,
    candidateGrantIds: Object.freeze([selected.id]), effectiveRestrictions: effective }, reason);

  const unresolved = baseUnresolved(facts, abilityId, input.policy.externalClaimsMode, visualOnly === false);
  const demand = limitDemand(input, selected, effective);
  return conditional({ ...base, selectedGrantId: selected.id,
    candidateGrantIds: Object.freeze([selected.id]), effectiveRestrictions: effective, limitDemand: demand }, 'none',
  uniqueChecks(unresolved));
}

function checkSpatialRestrictions(grant: AbilityGrant, abilityId: string, facts: AbilityWorldFacts | null): AbilityPolicyReason | null {
  if (facts === null) return null;
  if (facts.targetResolved === null || facts.targetResolved === undefined) return null;
  if (facts.targetResolved === false) return 'target_unavailable';
  if (facts.targetChunkLoadedAndTicking === false) return 'target_unavailable';
  const requesterFeet = facts.requesterFeet;
  const targetFeet = facts.targetFeet;
  if (requesterFeet === null || requesterFeet === undefined || targetFeet === null || targetFeet === undefined) return null;
  if (!validPosition(requesterFeet) || !validPosition(targetFeet)) return 'out_of_scope';
  const dx = requesterFeet.x - targetFeet.x;
  const dy = requesterFeet.y - targetFeet.y;
  const dz = requesterFeet.z - targetFeet.z;
  const range = Math.min(64, grant.maxRangeBlocks);
  if (dx * dx + dy * dy + dz * dz > range * range) return 'out_of_scope';

  const rawBorder = facts.worldBorder;
  const border = rawBorder !== null && rawBorder !== undefined && validBorder(rawBorder) ? rawBorder : null;
  if (facts.worldBorder !== null && facts.worldBorder !== undefined && border === null) return 'out_of_scope';
  if (abilityId === STRIKE_LIGHTNING_ABILITY) {
    const minX = Math.floor(targetFeet.x) - 4;
    const minZ = Math.floor(targetFeet.z) - 4;
    const maxXExclusive = minX + 9;
    const maxZExclusive = minZ + 9;
    if (![minX, minZ, maxXExclusive, maxZExclusive].every(Number.isSafeInteger)) return 'out_of_scope';
    if (grant.region !== null && !containsColumns(grant.region, minX, maxXExclusive, minZ, maxZExclusive)) return 'out_of_scope';
    if (border !== null && (minX < border.minX || maxXExclusive > border.maxXExclusive ||
        minZ < border.minZ || maxZExclusive > border.maxZExclusive)) return 'out_of_scope';
  } else {
    if (grant.region !== null && !containsFeet(grant.region, targetFeet)) return 'out_of_scope';
    if (border !== null && !containsFeet(border, targetFeet)) return 'out_of_scope';
  }
  return null;
}

function baseUnresolved(facts: AbilityWorldFacts | null, abilityId: string, externalClaimsMode: string,
  damageRequested: boolean): AbilityResolverCheck[] {
  const unresolved: AbilityResolverCheck[] = [];
  if (facts === null || facts.requestLive === null || facts.requestLive === undefined) unresolved.push('authenticated_request_eligibility');
  if (facts === null || facts.requesterConnected === null || facts.requesterConnected === undefined) unresolved.push('requester_connected');
  if (facts === null || facts.currentWorldMatches === null || facts.currentWorldMatches === undefined) unresolved.push('current_world_instance');
  // Configured ASCII binding IDs are not the unresolved normative UUID world identity mapping.
  unresolved.push('normative_world_identity_mapping');
  if (facts === null || facts.targetResolved === null || facts.targetResolved === undefined) unresolved.push('target_resolution');
  if (facts === null || facts.targetConnected === null || facts.targetConnected === undefined ||
      facts.targetAlive === null || facts.targetAlive === undefined || facts.targetNotSpectating === null ||
      facts.targetNotSpectating === undefined) unresolved.push('target_connected_alive_non_spectating');
  if (facts === null || facts.requesterDimension === null || facts.requesterDimension === undefined ||
      facts.targetDimension === null || facts.targetDimension === undefined) unresolved.push('same_dimension');
  if (facts === null || facts.requesterFeet === null || facts.requesterFeet === undefined ||
      facts.targetFeet === null || facts.targetFeet === undefined) unresolved.push('requester_target_range');
  if (facts === null || facts.worldBorder === null || facts.worldBorder === undefined) unresolved.push('world_border');
  if (facts === null || facts.targetFeet === null || facts.targetFeet === undefined) unresolved.push('grant_region');
  if (facts === null || facts.targetChunkLoadedAndTicking === null || facts.targetChunkLoadedAndTicking === undefined) {
    unresolved.push('target_chunk_loaded_and_ticking');
  }
  if (abilityId === OBSERVE_PLAYER_ABILITY) unresolved.push('observation_freshness');
  if (abilityId === STRIKE_LIGHTNING_ABILITY) {
    if (facts === null || facts.lightningSkyAccess === null || facts.lightningSkyAccess === undefined) unresolved.push('lightning_sky_access');
    if (facts === null || facts.protectedSpawnClear === null || facts.protectedSpawnClear === undefined) unresolved.push('protected_spawn');
    if (externalClaimsMode === 'REQUIRED' &&
        (facts === null || facts.protectedClaimsClear === null || facts.protectedClaimsClear === undefined)) {
      unresolved.push('external_claims_authority');
    }
    unresolved.push('durable_preparation', 'native_lightning_side_effect_suppression', 'unknown_outcome_persistence');
    if (damageRequested) unresolved.push('damage_nonlethal_qualification');
  }
  unresolved.push('atomic_limit_admission', 'execution_boundary_policy_recheck', 'tick_thread_revalidation',
    'authoritative_outcome_identity_mapping');
  return unresolved;
}

function limitDemand(input: AbilityPolicyResolverInput, grant: AbilityGrant,
  effective: AbilityEffectiveRestrictions): AbilityLimitDemand {
  const common = {
    policyRevision: input.policyRevision,
    grantId: grant.id,
    abilityId: grant.abilityId,
    characterScope: Object.freeze({ serverId: input.binding.serverId, worldId: input.binding.worldId,
      characterId: input.binding.characterId }),
    requesterScope: Object.freeze({ serverId: input.binding.serverId, worldId: input.binding.worldId,
      characterId: input.binding.characterId, requesterId: input.requesterId }),
    targetScope: Object.freeze({ serverId: input.binding.serverId, worldId: input.binding.worldId,
      characterId: input.binding.characterId, targetPlayerId: input.targetPlayerId! }),
    invocationId: input.invocationId,
    characterIntervalMs: effective.limits.characterIntervalMs,
    maxInFlightPerRequester: 1 as const,
    invocationsPerOrigin: 1 as const,
  };
  if (grant.abilityId === OBSERVE_PLAYER_ABILITY) {
    const limits = effective.limits as ObserveAbilityLimits;
    return Object.freeze({
    ...common,
    requesterIntervalMs: limits.requesterIntervalMs,
    targetCooldownMs: null,
    maxInFlightPerCharacter: 16 as const,
    });
  }
  const limits = effective.limits as LightningAbilityLimits;
  return Object.freeze({
    ...common,
    requesterIntervalMs: limits.requesterCooldownMs,
    targetCooldownMs: limits.targetCooldownMs,
    maxInFlightPerCharacter: 1 as const,
  });
}

function restrictions(grant: AbilityGrant, mode: AbilityEffectiveRestrictions['mode'],
  facts: AbilityWorldFacts | null): AbilityEffectiveRestrictions {
  const maxDamage = grant.abilityId === STRIKE_LIGHTNING_ABILITY && grant.allowDamage ? Math.min(5, grant.maxDamage!) : null;
  const rawBorder = facts?.worldBorder ?? null;
  const worldBorder = rawBorder !== null && validBorder(rawBorder)
    ? Object.freeze({ minX: rawBorder.minX, maxXExclusive: rawBorder.maxXExclusive,
      minZ: rawBorder.minZ, maxZExclusive: rawBorder.maxZExclusive })
    : null;
  const region = grant.region === null ? null : Object.freeze({ ...grant.region });
  const effectiveSpatialBounds = intersectSpatialBounds(region, worldBorder);
  const limits: ObserveAbilityLimits | LightningAbilityLimits = grant.abilityId === OBSERVE_PLAYER_ABILITY
    ? (() => { const value = grant.limits as ObserveAbilityLimits; return Object.freeze({
      requesterIntervalMs: Math.max(2_000, value.requesterIntervalMs),
      characterIntervalMs: Math.max(250, value.characterIntervalMs) }); })()
    : (() => { const value = grant.limits as LightningAbilityLimits; return Object.freeze({
      requesterCooldownMs: Math.max(30_000, value.requesterCooldownMs),
      targetCooldownMs: Math.max(30_000, value.targetCooldownMs),
      characterIntervalMs: Math.max(1_000, value.characterIntervalMs) }); })();
  return Object.freeze({ maxRangeBlocks: Math.min(64, grant.maxRangeBlocks), region, worldBorder,
    effectiveSpatialBounds, mode, maxDamage, limits });
}

function conditional(fields: Partial<AbilityPolicyResolution> & Pick<AbilityPolicyResolution, 'abilityId' | 'contractVersion' | 'policyRevision'>,
  reasonCode: AbilityPolicyReason, unresolvedChecks: readonly AbilityResolverCheck[]): AbilityPolicyResolution {
  return Object.freeze({ status: 'CONDITIONAL', abilityId: fields.abilityId, contractVersion: fields.contractVersion,
    policyRevision: fields.policyRevision, selectedGrantId: fields.selectedGrantId ?? null,
    candidateGrantIds: Object.freeze([...(fields.candidateGrantIds ?? [])]),
    effectiveRestrictions: fields.effectiveRestrictions ?? null,
    unresolvedChecks: Object.freeze([...unresolvedChecks]), reasonCode,
    limitDemand: fields.limitDemand ?? null });
}

function denied(fields: Partial<AbilityPolicyResolution> & Pick<AbilityPolicyResolution, 'abilityId' | 'contractVersion' | 'policyRevision'>,
  reasonCode: AbilityPolicyReason): AbilityPolicyResolution {
  return Object.freeze({ status: 'DENIED', abilityId: fields.abilityId, contractVersion: fields.contractVersion,
    policyRevision: fields.policyRevision, selectedGrantId: fields.selectedGrantId ?? null,
    candidateGrantIds: Object.freeze([...(fields.candidateGrantIds ?? [])]),
    effectiveRestrictions: fields.effectiveRestrictions ?? null,
    unresolvedChecks: Object.freeze([]), reasonCode, limitDemand: null });
}

function uniqueChecks(checks: readonly AbilityResolverCheck[]): readonly AbilityResolverCheck[] {
  return [...new Set(checks)];
}

function containsFeet(region: AbilityRegion | AbilityWorldBorder, feet: AbilityPosition): boolean {
  return feet.x >= region.minX && feet.x < region.maxXExclusive &&
    feet.z >= region.minZ && feet.z < region.maxZExclusive;
}

function containsColumns(region: AbilityRegion | AbilityWorldBorder, minX: number, maxXExclusive: number,
  minZ: number, maxZExclusive: number): boolean {
  return minX >= region.minX && maxXExclusive <= region.maxXExclusive &&
    minZ >= region.minZ && maxZExclusive <= region.maxZExclusive;
}

function validPosition(value: AbilityPosition): boolean {
  return Number.isFinite(value.x) && Number.isFinite(value.y) && Number.isFinite(value.z);
}

function validBorder(value: AbilityWorldBorder): boolean {
  return Number.isFinite(value.minX) && Number.isFinite(value.maxXExclusive) &&
    Number.isFinite(value.minZ) && Number.isFinite(value.maxZExclusive) &&
    value.minX < value.maxXExclusive && value.minZ < value.maxZExclusive;
}

type SpatialIntersection =
  | Readonly<{ kind: 'unknown'; bounds: null }>
  | Readonly<{ kind: 'empty'; bounds: null }>
  | Readonly<{ kind: 'non_empty'; bounds: AbilitySpatialBounds }>;

function spatialIntersection(region: AbilityRegion | null, border: AbilityWorldBorder | null): SpatialIntersection {
  if (border === null || !validBorder(border)) return { kind: 'unknown', bounds: null };
  const minX = region === null ? border.minX : Math.max(region.minX, border.minX);
  const maxXExclusive = region === null ? border.maxXExclusive : Math.min(region.maxXExclusive, border.maxXExclusive);
  const minZ = region === null ? border.minZ : Math.max(region.minZ, border.minZ);
  const maxZExclusive = region === null ? border.maxZExclusive : Math.min(region.maxZExclusive, border.maxZExclusive);
  if (minX >= maxXExclusive || minZ >= maxZExclusive) return { kind: 'empty', bounds: null };
  return { kind: 'non_empty', bounds: Object.freeze({ minX, maxXExclusive, minZ, maxZExclusive }) };
}

function intersectSpatialBounds(region: AbilityRegion | null, border: AbilityWorldBorder | null): AbilitySpatialBounds | null {
  const intersection = spatialIntersection(region, border);
  return intersection.kind === 'non_empty' ? intersection.bounds : null;
}

function isResolverInput(value: unknown): value is AbilityPolicyResolverInput {
  if (!ownDataRecord(value)) return false;
  const required = ['binding', 'policy', 'policyRevision', 'authorityCurrent', 'requesterId', 'invocationId',
    'abilityId', 'contractVersion'];
  return required.every((key) => Object.hasOwn(value, key)) && typeof value.abilityId === 'string' &&
    typeof value.contractVersion === 'number' && typeof value.policyRevision === 'number';
}

function ownDataRecord(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object' || utilTypes.isProxy(value) || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  if (prototype !== Object.prototype && prototype !== null) return false;
  return Reflect.ownKeys(value).every((key) => {
    if (typeof key !== 'string') return false;
    const descriptor = Object.getOwnPropertyDescriptor(value, key);
    return descriptor !== undefined && Object.hasOwn(descriptor, 'value');
  });
}

function consistentResolvedTargetFacts(facts: AbilityWorldFacts, targetPlayerId: string): boolean {
  if (!ownDataRecord(facts)) return false;
  const resolved = Object.getOwnPropertyDescriptor(facts, 'targetResolved')?.value;
  const observedPlayerId = Object.getOwnPropertyDescriptor(facts, 'targetPlayerId')?.value;
  if (resolved !== null && resolved !== undefined && typeof resolved !== 'boolean') return false;
  if (observedPlayerId !== null && observedPlayerId !== undefined && typeof observedPlayerId !== 'string') return false;

  if (resolved === false || resolved === true && (observedPlayerId === null || observedPlayerId === undefined) ||
      observedPlayerId !== null && observedPlayerId !== undefined && observedPlayerId !== targetPlayerId) return false;
  // A resolved lookup may identify the player while dimension and position facts remain unavailable.
  // Those are separate unresolved checks, matching the Java WorldFacts contract.
  return true;
}

function validBinding(value: AbilityBinding): boolean {
  return value !== null && typeof value === 'object' &&
    [value.serverId, value.worldId, value.characterId, value.agentId].every((item) =>
      typeof item === 'string' && /^[A-Za-z0-9._:-]{1,128}$/u.test(item));
}

function safeRevision(value: number): boolean {
  return Number.isSafeInteger(value) && value >= 0;
}

function canonicalUuid(value: unknown): value is string {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(value);
}

function asciiCompare(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}
