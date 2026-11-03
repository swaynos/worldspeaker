import assert from 'node:assert/strict';
import test from 'node:test';
import {
  abilityResultSemanticsAreCoherent,
  validateAbilityResultSchema,
  type AbilityResult,
} from '../ability-catalogue.js';
import { ABILITY_RESULT_SEMANTIC_POSITIVES } from './ability-result-semantics-corpus.js';

const OTHER_UUID = '00000000-0000-4000-8000-000000000199';

function positive(name: string): AbilityResult {
  const entry = ABILITY_RESULT_SEMANTIC_POSITIVES.find((candidate) => candidate.name === name);
  assert.ok(entry, `missing semantic fixture: ${name}`);
  return entry.result;
}

function mutate(result: AbilityResult, mutation: (value: Record<string, unknown>) => void): unknown {
  const copy = JSON.parse(JSON.stringify(result)) as Record<string, unknown>;
  mutation(copy);
  return copy;
}

function factsOf(result: Record<string, unknown>): Record<string, unknown> {
  return result.facts as Record<string, unknown>;
}

function firstStepOf(facts: Record<string, unknown>): Record<string, unknown> {
  return (facts.steps as Array<Record<string, unknown>>)[0]!;
}

function secondStepOf(facts: Record<string, unknown>): Record<string, unknown> {
  return (facts.steps as Array<Record<string, unknown>>)[1]!;
}

const negativeOneFieldMutations: readonly Readonly<{ name: string; result: unknown }>[] = [
  {
    name: 'failed cannot claim reason none',
    result: mutate(positive('query failed on unsupported server state'), (value) => { value.reasonCode = 'none'; }),
  },
  {
    name: 'unknown cannot claim reason none',
    result: mutate(positive('query outcome remains unknown without invented facts'), (value) => { value.reasonCode = 'none'; }),
  },
  {
    name: 'rejected cannot claim reason none',
    result: mutate(positive('rejected invalid input'), (value) => { value.reasonCode = 'none'; }),
  },
  {
    name: 'admitted cannot carry a rejection reason',
    result: mutate(positive('query admitted without a snapshot'), (value) => { value.reasonCode = 'invalid_input'; }),
  },
  {
    name: 'submitted cannot carry a cancellation reason',
    result: mutate(positive('query submitted without a snapshot'), (value) => { value.reasonCode = 'cancelled'; }),
  },
  {
    name: 'executed cannot carry a failure reason',
    result: mutate(positive('query executed with its snapshot'), (value) => { value.reasonCode = 'execution_failed'; }),
  },
  {
    name: 'partial cannot claim reason none',
    result: mutate(positive('presentation confirmed and damage failed is partial, not rollback'), (value) => { value.reasonCode = 'none'; }),
  },
  {
    name: 'expired cannot claim reason none',
    result: mutate(positive('query expired without a reusable snapshot'), (value) => { value.reasonCode = 'none'; }),
  },
  {
    name: 'cancelled cannot claim reason none',
    result: mutate(positive('query cancelled before a snapshot'), (value) => { value.reasonCode = 'none'; }),
  },
  {
    name: 'executed query cannot lose its snapshot',
    result: mutate(positive('query executed with its snapshot'), (value) => { value.facts = null; }),
  },
  {
    name: 'duplicated query observation time must match',
    result: mutate(positive('query executed with its snapshot'), (value) => { factsOf(value).observedAt = 101; }),
  },
  {
    name: 'query cannot report execution time',
    result: mutate(positive('query executed with its snapshot'), (value) => { value.executedAt = 101; }),
  },
  {
    name: 'duplicated action identity must match',
    result: mutate(positive('visual lightning executed with confirmed presentation'), (value) => { value.actionId = OTHER_UUID; }),
  },
  {
    name: 'duplicated lightning execution time must match in facts and envelope',
    result: mutate(positive('visual lightning executed with confirmed presentation'), (value) => { factsOf(value).executedAt = 201; }),
  },
  {
    name: 'duplicated lightning execution time cannot be made unknown in only one copy',
    result: mutate(positive('visual lightning executed with confirmed presentation'), (value) => { value.executedAt = null; }),
  },
  {
    name: 'retry hint is only valid for a known rate limit',
    result: mutate(positive('rejected known rate limit with bounded retry hint'), (value) => { value.reasonCode = 'permission_denied'; }),
  },
  {
    name: 'concurrency limit cannot claim a cooldown retry time',
    result: mutate(positive('rejected concurrency limit without retry hint'), (value) => { value.retryAfterMs = 1; }),
  },
  {
    name: 'retry hint remains bounded',
    result: mutate(positive('rejected known rate limit with bounded retry hint'), (value) => { value.retryAfterMs = 30_001; }),
  },
  {
    name: 'unknown damage cannot be rewritten as confirmed without measurements',
    result: mutate(positive('presentation confirmed and damage unknown remains partial and uncertain'), (value) => {
      secondStepOf(factsOf(value)).state = 'confirmed';
    }),
  },
  {
    name: 'confirmed presentation plus unknown damage remains partial, not wholly unknown',
    result: mutate(positive('presentation confirmed and damage unknown remains partial and uncertain'), (value) => { value.state = 'unknown'; }),
  },
  {
    name: 'all-confirmed steps are not partial',
    result: mutate(positive('damage-mode execution can confirm zero mitigated damage'), (value) => { value.state = 'partial'; }),
  },
  {
    name: 'damage mode records its damage step after confirmed presentation',
    result: mutate(positive('damage-mode execution can confirm zero mitigated damage'), (value) => {
      (factsOf(value).steps as unknown[]).pop();
    }),
  },
  {
    name: 'visual-only mode cannot contain a damage step',
    result: mutate(positive('damage-mode execution can confirm zero mitigated damage'), (value) => { factsOf(value).visualOnly = true; }),
  },
  {
    name: 'damage cannot precede presentation',
    result: mutate(positive('damage-mode execution can confirm zero mitigated damage'), (value) => {
      (factsOf(value).steps as unknown[]).reverse();
    }),
  },
  {
    name: 'confirmed presentation requires its resolved coordinates',
    result: mutate(positive('visual lightning executed with confirmed presentation'), (value) => { factsOf(value).coordinates = null; }),
  },
  {
    name: 'applied damage must include positive measured loss',
    result: mutate(positive('damage-mode execution can confirm zero mitigated damage'), (value) => {
      secondStepOf(factsOf(value)).damageDisposition = 'applied';
    }),
  },
  {
    name: 'invulnerable damage disposition cannot report health loss',
    result: mutate(positive('damage-mode execution confirms applied damage'), (value) => {
      secondStepOf(factsOf(value)).damageDisposition = 'invulnerable';
    }),
  },
  {
    name: 'unknown damage quantities stay null rather than becoming zero',
    result: mutate(positive('presentation confirmed and damage unknown remains partial and uncertain'), (value) => {
      secondStepOf(factsOf(value)).healthRemoved = 0;
    }),
  },
  {
    name: 'steps cannot carry covert target authority',
    result: mutate(positive('visual lightning executed with confirmed presentation'), (value) => {
      firstStepOf(factsOf(value)).targetPlayerId = OTHER_UUID;
    }),
  },
  {
    name: 'steps cannot carry covert world authority',
    result: mutate(positive('visual lightning executed with confirmed presentation'), (value) => {
      firstStepOf(factsOf(value)).worldId = OTHER_UUID;
    }),
  },
  {
    name: 'result facts cannot accompany a rejection',
    result: mutate(positive('rejected invalid input'), (value) => {
      value.facts = positive('query executed with its snapshot').facts;
    }),
  },
];

test('shared semantic positive corpus covers all nine lifecycle states and all reason codes', () => {
  const coveredStates = new Set<string>();
  const coveredReasons = new Set<string>();
  for (const { name, result } of ABILITY_RESULT_SEMANTIC_POSITIVES) {
    assert.equal(validateAbilityResultSchema(result), true, name);
    assert.equal(abilityResultSemanticsAreCoherent(result), true, `pure semantic predicate: ${name}`);
    coveredStates.add(result.state);
    coveredReasons.add(result.reasonCode);
  }
  assert.deepEqual([...coveredStates].sort(), ['admitted', 'cancelled', 'executed', 'expired', 'failed', 'partial', 'rejected', 'submitted', 'unknown']);
  assert.deepEqual([...coveredReasons].sort(), [
    'cancelled', 'concurrency_limited', 'deadline_expired', 'execution_failed', 'execution_unavailable',
    'invalid_input', 'none', 'out_of_scope', 'outcome_unknown', 'permission_denied', 'protected_area',
    'rate_limited', 'stale_context', 'storage_unavailable', 'target_unavailable', 'unsupported_contract', 'unsupported_state',
  ]);
});

test('semantic one-field mutations reject lifecycle, evidence, step, mode, and identity contradictions', () => {
  for (const { name, result } of negativeOneFieldMutations) {
    assert.equal(validateAbilityResultSchema(result), false, name);
    assert.equal(abilityResultSemanticsAreCoherent(result), false, `pure semantic predicate: ${name}`);
  }
});

test('partial outcomes retain known presentation and failed or uncertain damage without rollback claims', () => {
  for (const name of [
    'presentation confirmed and damage failed is partial, not rollback',
    'presentation confirmed and damage unknown remains partial and uncertain',
    'presentation confirmed and damage prevented remains partial',
    'failed presentation does not erase later confirmed damage',
    'unknown presentation does not erase later confirmed damage',
  ]) {
    const result = positive(name);
    assert.equal(validateAbilityResultSchema(result), true, name);
    assert.equal(result.state, 'partial');
  }
});

test('world, dimension, target, request, and query identities stay distinct unless literally duplicated', () => {
  const result = positive('query executed with its snapshot');
  const sameOpaqueIdentifiers = mutate(result, (value) => {
    value.queryId = value.requestId;
    value.worldId = OTHER_UUID;
    const facts = factsOf(value);
    facts.targetPlayerId = OTHER_UUID;
    facts.dimension = 'minecraft:the_nether';
  });
  assert.equal(validateAbilityResultSchema(sameOpaqueIdentifiers), true);

  const lightning = positive('visual lightning executed with confirmed presentation');
  const distinctWorldAndDimension = mutate(lightning, (value) => {
    value.worldId = OTHER_UUID;
    factsOf(value).dimension = 'minecraft:the_nether';
  });
  assert.equal(validateAbilityResultSchema(distinctWorldAndDimension), true);
});

test('pure predicate is total for non-result inputs', () => {
  for (const value of [null, undefined, [], 'result', 4, {}, { state: 'unknown', reasonCode: 'toString' }]) {
    assert.equal(abilityResultSemanticsAreCoherent(value), false);
  }
  const sparseSteps = mutate(positive('visual lightning executed with confirmed presentation'), (value) => {
    factsOf(value).steps = Array(1);
  });
  assert.equal(abilityResultSemanticsAreCoherent(sparseSteps), false);
});
