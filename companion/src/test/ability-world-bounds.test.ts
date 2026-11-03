import assert from 'node:assert/strict';
import test from 'node:test';
import {
  STRIKE_LIGHTNING_ABILITY,
  validateAbilityResultSchema,
  type AbilityResult,
} from '../ability-catalogue.js';
import {
  captureAbilityExecutionWorldBounds,
  isPlacementInsideExecutionWorld,
  isValidAbilityExecutionWorldBoundsSnapshot,
  validateAbilityResultForAuthoritativePublication,
  type AbilityExecutionWorldBoundsSnapshot,
  type AbilityExecutionWorldBoundsHandle,
} from '../ability-world-bounds.js';
import {
  MINECRAFT_1_21_1_BLOCKPOS_XZ_MAX,
  MINECRAFT_1_21_1_BLOCKPOS_XZ_MIN,
  MINECRAFT_1_21_1_BLOCKPOS_Y_MAX,
  MINECRAFT_1_21_1_BLOCKPOS_Y_MIN,
} from '../minecraft-coordinate-bounds.js';
import type { AbilityTrustedContextHandle } from '../ability-validation.js';

const REQUESTER = '00000000-0000-4000-8000-000000000041';
const REQUEST_ID = '00000000-0000-4000-8000-000000000061';
const INVOCATION_ID = '00000000-0000-4000-8000-000000000062';
const EPOCH_ID = '00000000-0000-4000-8000-000000000063';
const ACTION_ID = '00000000-0000-4000-8000-000000000065';
const BINDING = Object.freeze({ serverId: 'server-test', worldId: 'world-test', characterId: 'character-test', agentId: 'agent-test' });

test('Minecraft 1.21.1 BlockPos representation accepts exact signed limits, not safe-integer widening', () => {
  for (const coordinates of [
    [MINECRAFT_1_21_1_BLOCKPOS_XZ_MIN, MINECRAFT_1_21_1_BLOCKPOS_Y_MIN, MINECRAFT_1_21_1_BLOCKPOS_XZ_MIN],
    [MINECRAFT_1_21_1_BLOCKPOS_XZ_MAX, MINECRAFT_1_21_1_BLOCKPOS_Y_MAX, MINECRAFT_1_21_1_BLOCKPOS_XZ_MAX],
    [-1, -64, -1],
  ]) assert.equal(validateAbilityResultSchema(lightningResult(coordinates)), true, JSON.stringify(coordinates));

  for (const coordinates of [
    [Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER],
    [MINECRAFT_1_21_1_BLOCKPOS_XZ_MIN - 1, 0, 0],
    [MINECRAFT_1_21_1_BLOCKPOS_XZ_MAX + 1, 0, 0],
    [0, MINECRAFT_1_21_1_BLOCKPOS_Y_MIN - 1, 0],
    [0, MINECRAFT_1_21_1_BLOCKPOS_Y_MAX + 1, 0],
    [0, -64.5, 0],
    [Number.NaN, 0, 0],
    [0, Number.POSITIVE_INFINITY, 0],
    [0, 64],
    [0, null, 0],
  ]) assert.equal(validateAbilityResultSchema(lightningResult(coordinates)), false, JSON.stringify(coordinates));

  assert.equal(validateAbilityResultSchema(lightningResult(null)), true, 'null remains the representation for unknown placement');
});

test('generic result schema is structural only; non-null placement publication fails closed without trusted WORLD bounds', () => {
  const result = lightningResult([0, 64, 0]);
  assert.equal(validateAbilityResultSchema(result), true, 'schema validity does not claim live-world qualification');
  assert.equal(validateAbilityResultForAuthoritativePublication(result, null, null), false);
  assert.equal(validateAbilityResultForAuthoritativePublication(lightningResult(null), null, null), true,
    'unknown coordinates remain null when authoritative world context is unavailable');

  const modelSuppliedAuthority = { ...result, worldBounds: {
    dimensionId: 'minecraft:overworld', minBuildYInclusive: -64, maxBuildYExclusive: 320,
    borderMinX: -29_999_984, borderMaxX: 29_999_984, borderMinZ: -29_999_984, borderMaxZ: 29_999_984,
  } };
  assert.equal(validateAbilityResultForAuthoritativePublication(modelSuppliedAuthority, null, null), false,
    'result/model fields cannot mint or substitute the opaque trusted bounds capability');

  const forgedHandle = Object.freeze({ snapshot: { dimensionId: 'minecraft:overworld' } }) as AbilityExecutionWorldBoundsHandle;
  assert.equal(validateAbilityResultForAuthoritativePublication(result, null, forgedHandle), false);
});

test('synthetic bounds predicate checks build Y, dimension, border, and full footprint without minting authority', () => {
  // Pure fixture geometry only: publication still requires an owner-issued live request handle.
  const snapshot: AbilityExecutionWorldBoundsSnapshot = Object.freeze({
    dimensionId: 'minecraft:overworld',
    minBuildYInclusive: 50,
    maxBuildYExclusive: 70,
    borderMinX: -5.5,
    borderMaxX: 5.5,
    borderMinZ: -5.5,
    borderMaxZ: 5.5,
  });
  assert.equal(isPlacementInsideExecutionWorld(snapshot, 'minecraft:overworld', [0, 50, 0]), true,
    'minimum build Y and the centered 9x9 columns fit');
  assert.equal(isPlacementInsideExecutionWorld(snapshot, 'minecraft:overworld', [0, 69, 0]), true,
    'top in-range build Y is inclusive');
  assert.equal(isPlacementInsideExecutionWorld(snapshot, 'minecraft:overworld', [0, 49, 0]), false);
  assert.equal(isPlacementInsideExecutionWorld(snapshot, 'minecraft:overworld', [0, 70, 0]), false,
    'max build Y is exclusive');
  assert.equal(isPlacementInsideExecutionWorld(snapshot, 'minecraft:overworld', [1, 60, 0]), false,
    'the impact block is inside the synthetic border but its full 9x9 footprint is not');
  assert.equal(isPlacementInsideExecutionWorld(snapshot, 'minecraft:overworld', [0, 60, -1]), true,
    'the opposite footprint edge still fits the narrower border');
  assert.equal(isPlacementInsideExecutionWorld(snapshot, 'minecraft:the_nether', [0, 60, 0]), false, 'dimension must match');
  const forgedContext = Object.freeze({}) as AbilityTrustedContextHandle;
  assert.equal(captureAbilityExecutionWorldBounds(forgedContext, snapshot), null,
    'a caller-made handle is not an authenticated execution context');
  assert.equal(isValidAbilityExecutionWorldBoundsSnapshot({ ...snapshot, borderMinX: Number.NaN }), false,
    'malformed authoritative bounds fail closed');
  assert.equal(isValidAbilityExecutionWorldBoundsSnapshot({
    ...snapshot, maxBuildYExclusive: MINECRAFT_1_21_1_BLOCKPOS_Y_MAX + 2,
  }), false, 'trusted build bounds must stay within the versioned representation domain');
});

function lightningResult(coordinates: unknown, dimension = 'minecraft:overworld'): AbilityResult {
  const unknownPlacement = coordinates === null;
  return {
    serverId: REQUEST_ID, worldId: INVOCATION_ID, characterId: EPOCH_ID,
    requestId: REQUEST_ID, invocationId: INVOCATION_ID, abilityId: STRIKE_LIGHTNING_ABILITY,
    contractVersion: 1, policyRevision: 7, serverEpochId: EPOCH_ID,
    state: unknownPlacement ? 'failed' : 'executed', reasonCode: unknownPlacement ? 'execution_failed' : 'none',
    observedAt: null, executedAt: unknownPlacement ? null : 100, retryAfterMs: null, actionId: ACTION_ID,
    facts: {
      actionId: ACTION_ID, targetPlayerId: REQUESTER, dimension, executedAt: unknownPlacement ? null : 100,
      coordinates: coordinates as readonly [number, number, number] | null, visualOnly: true,
      steps: [{ kind: 'presentation', state: unknownPlacement ? 'failed' : 'confirmed' }],
    },
  };
}
