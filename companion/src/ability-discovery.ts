import { completeGrantsFor, type AbilityGrant, type AbilityPolicy } from './ability-policy.js';
import { resolveAbilityPolicy } from './ability-policy-resolver.js';
import { abilityDiscoverySchemaResources, type AbilitySchemaResource } from './ability-schema-resources.js';
import {
  abilityContracts,
  assertValidAbilityDiscovery,
  MAX_ABILITY_DISCOVERY_BYTES,
  MAX_POLICY_GRANT_SUMMARIES,
  type AbilityContract,
} from './ability-catalogue.js';
import { isCanonicalConfigUuid } from './config-uuid.js';
import type { Binding, DialogueRequest } from './protocol.js';

export type GrantSummary = Readonly<{
  grantId: string;
  selfTargeting: true;
  otherTargetIds: readonly string[];
  dimensions: readonly string[];
  maxRangeBlocks: number;
  region: AbilityGrant['region'];
  limits: AbilityGrant['limits'];
  allowVisual: boolean;
  allowDamage: boolean;
  maxDamage: number | null;
}>;
export type AbilityDiscoveryEntry = Readonly<{
  contract: AbilityContract;
  implementationStatus: 'UNIMPLEMENTED';
  qualificationStatus: 'UNQUALIFIED';
  availability: 'UNAVAILABLE';
  disabledReasons: readonly ('unimplemented' | 'unqualified' | 'no_grant' | 'explicit_denial' | 'requester_not_granted')[];
  policyDisposition: 'denied' | 'conditional';
  effectiveGrants: readonly GrantSummary[];
}>;
export type AbilityDiscovery = Readonly<{
  catalogueVersion: 1;
  binding: Binding;
  requestId: string;
  invocationId: string;
  policyRevision: number;
  schemaResources: readonly AbilitySchemaResource[];
  entries: readonly AbilityDiscoveryEntry[];
}>;

export function buildAbilityDiscovery(input: Readonly<{
  binding: Binding;
  request: DialogueRequest;
  policy: AbilityPolicy;
  isLive: () => boolean;
}>): Readonly<{ value: AbilityDiscovery; json: string; utf8Bytes: number }> {
  const { binding, request, policy } = input;
  if (typeof input.isLive !== 'function' || !input.isLive() || request.event.eventOrigin !== 'live' ||
      request.abilityPolicyRevision !== policy.revision ||
      !sameBinding(binding, request) || !isCanonicalConfigUuid(request.requestId) ||
      !isCanonicalConfigUuid(request.invocationId) || !isCanonicalConfigUuid(request.event.playerId) ||
      !isCanonicalConfigUuid(request.event.serverEpochId) || request.requestId === request.invocationId) {
    throw new Error('ability discovery requires current authenticated request context');
  }
  const entries = abilityContracts().map((contract) => discoveryEntry(contract, binding, policy,
    request.event.playerId, request.invocationId));
  const value: AbilityDiscovery = Object.freeze({
    catalogueVersion: 1,
    binding: Object.freeze({ ...binding }),
    requestId: request.requestId,
    invocationId: request.invocationId,
    policyRevision: policy.revision,
    schemaResources: abilityDiscoverySchemaResources(),
    entries: Object.freeze(entries),
  });
  assertValidAbilityDiscovery(value);
  const json = JSON.stringify(value);
  const utf8Bytes = Buffer.byteLength(json, 'utf8');
  if (utf8Bytes > MAX_ABILITY_DISCOVERY_BYTES) throw new Error('ability discovery exceeds its complete metadata byte ceiling');
  if (!input.isLive()) throw new Error('ability request expired while building discovery');
  return Object.freeze({ value, json, utf8Bytes });
}

function discoveryEntry(contract: AbilityContract, binding: Binding, policy: AbilityPolicy, requesterId: string,
  invocationId: string): AbilityDiscoveryEntry {
  const resolution = resolveAbilityPolicy({
    binding,
    policy,
    policyRevision: policy.revision,
    authorityCurrent: true,
    requesterId,
    invocationId,
    abilityId: contract.abilityId,
    contractVersion: contract.contractVersion,
  });
  const denied = resolution.reasonCode === 'explicit_denial';
  const matching = resolution.candidateGrantIds
    .map((id) => completeGrantsFor(policy, contract.abilityId).find((grant) => grant.id === id))
    .filter((grant): grant is AbilityGrant => grant !== undefined);
  const disabledReasons: Array<'unimplemented' | 'unqualified' | 'no_grant' | 'explicit_denial' | 'requester_not_granted'> = [
    'unimplemented', 'unqualified',
  ];
  if (denied) disabledReasons.push('explicit_denial');
  else if (resolution.reasonCode === 'no_complete_grant') disabledReasons.push('no_grant');
  else if (resolution.reasonCode === 'requester_not_granted' || resolution.reasonCode === 'stale_context') disabledReasons.push('requester_not_granted');
  const grants = matching
    .slice(0, MAX_POLICY_GRANT_SUMMARIES).map(grantSummary);
  return Object.freeze({
    contract,
    implementationStatus: 'UNIMPLEMENTED',
    qualificationStatus: 'UNQUALIFIED',
    availability: 'UNAVAILABLE',
    disabledReasons: Object.freeze(disabledReasons),
    policyDisposition: grants.length === 0 ? 'denied' : 'conditional',
    effectiveGrants: Object.freeze(grants),
  });
}

function grantSummary(grant: AbilityGrant): GrantSummary {
  return Object.freeze({
    grantId: grant.id,
    selfTargeting: true,
    otherTargetIds: Object.freeze([...grant.otherTargetIds]),
    dimensions: Object.freeze([...grant.dimensions]),
    maxRangeBlocks: grant.maxRangeBlocks,
    region: grant.region === null ? null : Object.freeze({ ...grant.region }),
    limits: Object.freeze({ ...grant.limits }),
    allowVisual: grant.allowVisual,
    allowDamage: grant.allowDamage,
    maxDamage: grant.maxDamage,
  });
}

function sameBinding(left: Binding, right: DialogueRequest): boolean {
  return left.serverId === right.serverId && left.worldId === right.worldId &&
    left.characterId === right.characterId && left.agentId === right.agentId;
}

function asciiCompare(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0;
}
