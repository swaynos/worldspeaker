import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import test from 'node:test';
import { parseAbilityPolicy } from '../ability-policy.js';
import { resolveAbilityPolicy, type AbilityPolicyResolverInput, type AbilityWorldFacts } from '../ability-policy-resolver.js';

const corpus = JSON.parse(await readFile(resolve(process.cwd(), '../compatibility/ability-policy-evaluation-v1-corpus.json'), 'utf8')) as {
  binding: AbilityPolicyResolverInput['binding'];
  requesterId: string;
  invocationId: string;
  targetId: string;
  facts: Record<string, unknown>;
  vectors: Array<Record<string, any>>;
};

test('production TypeScript resolver matches every shared Java evaluation vector', () => {
  for (const vector of corpus.vectors) {
    const policy = parseAbilityPolicy(vector.policy, corpus.binding.characterId);
    const profiles = vector.factProfiles ?? [{ name: null, factsOverrides: {} }];
    for (const profile of profiles) {
      const name = profile.name === null ? vector.name : `${vector.name}:${profile.name}`;
      const facts = Object.freeze({ ...corpus.facts, ...(vector.factsOverrides ?? {}), ...(profile.factsOverrides ?? {}) }) as AbilityWorldFacts;
      const input: AbilityPolicyResolverInput = {
        binding: vector.binding ?? corpus.binding,
        policy,
        policyRevision: policy.revision,
        authorityCurrent: vector.authorityCurrent ?? true,
        requesterId: corpus.requesterId,
        invocationId: corpus.invocationId,
        abilityId: vector.abilityId,
        contractVersion: vector.contractVersion,
        ...(vector.targetId === undefined ? {} : { targetPlayerId: vector.targetId }),
        visualOnly: vector.visualOnly,
        facts,
      };
      const result = resolveAbilityPolicy(input);
      const expected = vector.expected as Record<string, any>;
      assert.equal(result.status, expected.status, name);
      assert.equal(result.abilityId, expected.abilityId ?? vector.abilityId, name);
      assert.equal(result.contractVersion, expected.contractVersion ?? vector.contractVersion, name);
      assert.equal(result.policyRevision, expected.policyRevision ?? policy.revision, name);
      assert.equal(result.reasonCode, expected.reasonCode, name);
      assert.equal(result.selectedGrantId, expected.selectedGrantId, name);
      if (expected.candidateGrantIds !== undefined) assert.deepEqual(result.candidateGrantIds, expected.candidateGrantIds, name);
      if (expected.hasEffectiveRestrictions !== undefined) {
        assert.equal(result.effectiveRestrictions !== null, expected.hasEffectiveRestrictions, name);
      }
      if (Object.hasOwn(expected, 'effectiveRestrictions')) {
        assert.deepEqual(result.effectiveRestrictions, expected.effectiveRestrictions, name);
      }
      if (expected.hasLimitDemand !== undefined) assert.equal(result.limitDemand !== null, expected.hasLimitDemand, name);
      if (Object.hasOwn(expected, 'limitDemand')) assert.deepEqual(result.limitDemand, expected.limitDemand, name);
      if (expected.maxRangeBlocks !== undefined) assert.equal(result.effectiveRestrictions?.maxRangeBlocks, expected.maxRangeBlocks, name);
      if (expected.mode !== undefined) assert.equal(result.effectiveRestrictions?.mode, expected.mode, name);
      if (Object.hasOwn(expected, 'maxDamage')) assert.equal(result.effectiveRestrictions?.maxDamage, expected.maxDamage, name);
      if (expected.limits !== undefined) assert.deepEqual(result.effectiveRestrictions?.limits, expected.limits, name);
      if (expected.unresolvedChecks !== undefined) assert.deepEqual(result.unresolvedChecks, expected.unresolvedChecks, name);
      assert.ok(result.candidateGrantIds.length <= 32, name);
      assert.ok(Object.isFrozen(result) && Object.isFrozen(result.candidateGrantIds) && Object.isFrozen(result.unresolvedChecks), name);
      assert.equal('permit' in result, false, name);
      if (result.limitDemand !== null) {
        assert.equal(result.limitDemand.policyRevision, policy.revision, name);
        assert.equal(result.limitDemand.grantId, result.selectedGrantId, name);
        assert.equal(result.limitDemand.invocationsPerOrigin, 1, name);
        assert.equal(Object.isFrozen(result.limitDemand), true, name);
      }
    }
  }
});

test('resolver rejects non-data caller and target-fact records without invoking accessors', () => {
  const vector = corpus.vectors[0]!;
  const policy = parseAbilityPolicy(vector.policy, corpus.binding.characterId);
  const baseInput: AbilityPolicyResolverInput = {
    binding: corpus.binding, policy, policyRevision: policy.revision, authorityCurrent: true,
    requesterId: corpus.requesterId, invocationId: corpus.invocationId,
    abilityId: vector.abilityId, contractVersion: vector.contractVersion,
    targetPlayerId: vector.targetId, visualOnly: true,
  };

  let inputAccessorCalled = false;
  const accessorInput = { ...baseInput };
  Object.defineProperty(accessorInput, 'abilityId', { get() { inputAccessorCalled = true; return vector.abilityId; } });
  const invalidInput = resolveAbilityPolicy(accessorInput as unknown as AbilityPolicyResolverInput);
  assert.equal(inputAccessorCalled, false);
  assert.equal(invalidInput.status, 'DENIED');
  assert.equal(invalidInput.reasonCode, 'stale_context');

  let factAccessorCalled = false;
  const accessorFacts = { ...corpus.facts };
  Object.defineProperty(accessorFacts, 'targetResolved', { get() { factAccessorCalled = true; return true; } });
  const invalidFacts = resolveAbilityPolicy({ ...baseInput, facts: accessorFacts as unknown as AbilityWorldFacts });
  assert.equal(factAccessorCalled, false);
  assert.equal(invalidFacts.status, 'DENIED');
  assert.equal(invalidFacts.reasonCode, 'target_unavailable');
  assert.equal(invalidFacts.selectedGrantId, null);
  assert.deepEqual(invalidFacts.candidateGrantIds, []);
  assert.equal(invalidFacts.effectiveRestrictions, null);
  assert.equal(invalidFacts.limitDemand, null);
});

test('effective spatial restrictions copy and freeze direct-caller border facts', () => {
  const vector = corpus.vectors.find((candidate) =>
    candidate.name === 'grant-wider-than-current-border-returns-the-narrow-border-intersection')!;
  const policy = parseAbilityPolicy(vector.policy, corpus.binding.characterId);
  const mutableBorder = { minX: -10, maxXExclusive: 10, minZ: -10, maxZExclusive: 10 };
  const facts = { ...corpus.facts, worldBorder: mutableBorder } as AbilityWorldFacts;
  const result = resolveAbilityPolicy({ binding: corpus.binding, policy, policyRevision: policy.revision,
    authorityCurrent: true, requesterId: corpus.requesterId, invocationId: corpus.invocationId,
    abilityId: vector.abilityId, contractVersion: vector.contractVersion, targetPlayerId: vector.targetId, facts });
  const restrictions = result.effectiveRestrictions!;
  const borderSnapshot = restrictions.worldBorder!;
  const intersectionSnapshot = restrictions.effectiveSpatialBounds!;
  assert.notStrictEqual(borderSnapshot, mutableBorder);
  assert.notStrictEqual(intersectionSnapshot, mutableBorder);
  assert.ok(Object.isFrozen(restrictions));
  assert.ok(Object.isFrozen(restrictions.region));
  assert.ok(Object.isFrozen(borderSnapshot));
  assert.ok(Object.isFrozen(intersectionSnapshot));
  assert.ok(Object.isFrozen(restrictions.limits));
  mutableBorder.minX = -50;
  mutableBorder.maxXExclusive = 50;
  assert.deepEqual(borderSnapshot, { minX: -10, maxXExclusive: 10, minZ: -10, maxZExclusive: 10 });
  assert.deepEqual(intersectionSnapshot, { minX: -10, maxXExclusive: 10, minZ: -10, maxZExclusive: 10 });
});

test('discovery returns requester-filtered candidates but never selects an execution grant', () => {
  const policy = parseAbilityPolicy({ schemaVersion: 1, revision: 30, grants: [
    { id: 'z-requester', characterId: corpus.binding.characterId, abilityId: 'worldspeaker.core.observe_player',
      contractVersion: 1, requesterIds: [corpus.requesterId], dimensions: ['minecraft:overworld'], region: {
        minX: -8, maxXExclusive: 8, minZ: -8, maxZExclusive: 8,
      } },
    { id: 'a-other', characterId: corpus.binding.characterId, abilityId: 'worldspeaker.core.observe_player',
      contractVersion: 1, requesterIds: ['00000000-0000-4000-8000-000000000042'], dimensions: ['minecraft:overworld'] },
  ], denials: [], protection: {} }, corpus.binding.characterId);
  const discovery = resolveAbilityPolicy({ binding: corpus.binding, policy, policyRevision: policy.revision,
    authorityCurrent: true, requesterId: corpus.requesterId, invocationId: corpus.invocationId,
    abilityId: 'worldspeaker.core.observe_player', contractVersion: 1 });
  assert.equal(discovery.status, 'CONDITIONAL');
  assert.equal(discovery.selectedGrantId, null);
  assert.deepEqual(discovery.candidateGrantIds, ['z-requester']);
  assert.equal(discovery.effectiveRestrictions, null);
  assert.deepEqual(discovery.unresolvedChecks, []);
});

test('missing target dimension is unresolved and cannot create a selected permit', () => {
  const vector = corpus.vectors[0]!;
  const policy = parseAbilityPolicy(vector.policy, corpus.binding.characterId);
  const facts = { ...corpus.facts, targetDimension: null } as AbilityWorldFacts;
  const result = resolveAbilityPolicy({ binding: corpus.binding, policy, policyRevision: policy.revision,
    authorityCurrent: true, requesterId: corpus.requesterId, invocationId: corpus.invocationId,
    abilityId: vector.abilityId, contractVersion: 1, targetPlayerId: vector.targetId, facts });
  assert.equal(result.status, 'CONDITIONAL');
  assert.equal(result.selectedGrantId, null);
  assert.equal(result.effectiveRestrictions, null);
  assert.ok(result.unresolvedChecks.includes('same_dimension'));
});
