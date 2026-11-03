import {
  STRIKE_LIGHTNING_ABILITY,
  validateAbilityResultSchema,
  type AbilityResult,
  type LightningFacts,
} from './ability-catalogue.js';
import type { AbilityTrustedContextHandle } from './ability-validation.js';
import { isCurrentAbilityContext } from './transport.js';
import {
  isMinecraft1211BlockPositionRepresentation,
  MINECRAFT_1_21_1_BLOCKPOS_XZ_MAX,
  MINECRAFT_1_21_1_BLOCKPOS_XZ_MIN,
  MINECRAFT_1_21_1_BLOCKPOS_Y_MAX,
  MINECRAFT_1_21_1_BLOCKPOS_Y_MIN,
  type Minecraft1211BlockPosition,
} from './minecraft-coordinate-bounds.js';

/**
 * Immutable bounds captured by a future trusted WORLD adapter at the
 * authoritative execution boundary. Border values are block-coordinate
 * boundary values; build Y uses an inclusive minimum and exclusive maximum.
 * This is deliberately not part of a model request or result record.
 */
export type AbilityExecutionWorldBoundsSnapshot = Readonly<{
  dimensionId: string;
  minBuildYInclusive: number;
  maxBuildYExclusive: number;
  borderMinX: number;
  borderMaxX: number;
  borderMinZ: number;
  borderMaxZ: number;
}>;

/** Opaque capability; its authoritative snapshot is held only in a WeakMap. */
export type AbilityExecutionWorldBoundsHandle = object;

type CapturedBounds = Readonly<{
  trustedContext: AbilityTrustedContextHandle;
  snapshot: AbilityExecutionWorldBoundsSnapshot;
}>;

const capturedBounds = new WeakMap<object, CapturedBounds>();
const FOOTPRINT_RADIUS = 4;

/**
 * Future WORLD-adapter seam. Call only with bounds read from the trusted server
 * execution world, never with model/result fields. The handle is tied by object
 * identity to an authenticated ability context and is unforgeable via JSON.
 */
export function captureAbilityExecutionWorldBounds(
  context: AbilityTrustedContextHandle | null,
  snapshot: AbilityExecutionWorldBoundsSnapshot,
): AbilityExecutionWorldBoundsHandle | null {
  if (context === null || !isCurrentAbilityContext(context) || !isValidAbilityExecutionWorldBoundsSnapshot(snapshot)) return null;
  const handle = Object.freeze(Object.create(null)) as object;
  capturedBounds.set(handle, Object.freeze({
    trustedContext: context,
    snapshot: Object.freeze({ ...snapshot }),
  }));
  return handle;
}

/**
 * Fail-closed future authoritative-publication guard. Generic result-schema
 * validation establishes only static structure/representation. A non-null
 * lightning placement additionally requires this opaque, context-bound WORLD
 * snapshot and must fit its build height, world border, and complete 9x9
 * horizontal footprint. Null coordinates remain the safe unknown value.
 *
 * No authoritative outcome publisher or WORLD adapter currently calls this;
 * the current ability gate remains deny-only.
 */
export function validateAbilityResultForAuthoritativePublication(
  value: unknown,
  context: AbilityTrustedContextHandle | null,
  worldBounds: AbilityExecutionWorldBoundsHandle | null,
): value is AbilityResult {
  if (!validateAbilityResultSchema(value)) return false;
  if (value.abilityId !== STRIKE_LIGHTNING_ABILITY || value.facts === null) return true;

  const facts = value.facts as LightningFacts;
  const coordinates = facts.coordinates;
  if (coordinates === null) return true;
  if (!isMinecraft1211BlockPositionRepresentation(coordinates) || context === null || worldBounds === null) return false;

  const captured = capturedBounds.get(worldBounds);
  if (!captured || captured.trustedContext !== context || !isCurrentAbilityContext(context)) return false;
  return isPlacementInsideExecutionWorld(captured.snapshot, facts.dimension, coordinates);
}

export function isPlacementInsideExecutionWorld(
  bounds: AbilityExecutionWorldBoundsSnapshot,
  dimensionId: string,
  [x, y, z]: Minecraft1211BlockPosition,
): boolean {
  if (dimensionId !== bounds.dimensionId || y < bounds.minBuildYInclusive || y >= bounds.maxBuildYExclusive) return false;

  // The complete x-4..x+4, z-4..z+4 footprint must stay representable and
  // within the captured world-border rectangle. Max values are exclusive.
  const footprintMinX = x - FOOTPRINT_RADIUS;
  const footprintMaxXExclusive = x + FOOTPRINT_RADIUS + 1;
  const footprintMinZ = z - FOOTPRINT_RADIUS;
  const footprintMaxZExclusive = z + FOOTPRINT_RADIUS + 1;
  return footprintMinX >= MINECRAFT_1_21_1_BLOCKPOS_XZ_MIN &&
    footprintMaxXExclusive - 1 <= MINECRAFT_1_21_1_BLOCKPOS_XZ_MAX &&
    footprintMinZ >= MINECRAFT_1_21_1_BLOCKPOS_XZ_MIN &&
    footprintMaxZExclusive - 1 <= MINECRAFT_1_21_1_BLOCKPOS_XZ_MAX &&
    footprintMinX >= bounds.borderMinX && footprintMaxXExclusive <= bounds.borderMaxX &&
    footprintMinZ >= bounds.borderMinZ && footprintMaxZExclusive <= bounds.borderMaxZ;
}

export function isValidAbilityExecutionWorldBoundsSnapshot(snapshot: AbilityExecutionWorldBoundsSnapshot): boolean {
  try {
    return snapshot !== null && typeof snapshot === 'object' &&
      typeof snapshot.dimensionId === 'string' && snapshot.dimensionId.length <= 256 &&
      /^[a-z0-9_.-]+:[a-z0-9/._-]+$/u.test(snapshot.dimensionId) &&
      Number.isSafeInteger(snapshot.minBuildYInclusive) &&
      Number.isSafeInteger(snapshot.maxBuildYExclusive) &&
      snapshot.minBuildYInclusive >= MINECRAFT_1_21_1_BLOCKPOS_Y_MIN &&
      snapshot.maxBuildYExclusive <= MINECRAFT_1_21_1_BLOCKPOS_Y_MAX + 1 &&
      snapshot.minBuildYInclusive < snapshot.maxBuildYExclusive &&
      Number.isFinite(snapshot.borderMinX) && Number.isFinite(snapshot.borderMaxX) &&
      Number.isFinite(snapshot.borderMinZ) && Number.isFinite(snapshot.borderMaxZ) &&
      snapshot.borderMinX < snapshot.borderMaxX && snapshot.borderMinZ < snapshot.borderMaxZ;
  } catch {
    return false;
  }
}
