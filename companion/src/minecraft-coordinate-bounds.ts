/**
 * Minecraft Java 1.21.1 BlockPos.asLong uses signed packed coordinates with
 * 26 bits for X/Z and 12 bits for Y. This is only the lossless BlockPos
 * representation domain; it is not a dimension's build height or world border.
 */
export const MINECRAFT_1_21_1_BLOCKPOS_XZ_MIN = -(2 ** 25);
export const MINECRAFT_1_21_1_BLOCKPOS_XZ_MAX = (2 ** 25) - 1;
export const MINECRAFT_1_21_1_BLOCKPOS_Y_MIN = -(2 ** 11);
export const MINECRAFT_1_21_1_BLOCKPOS_Y_MAX = (2 ** 11) - 1;

export type Minecraft1211BlockPosition = readonly [x: number, y: number, z: number];

/** Checks only the selected-version packed BlockPos representation domain. */
export function isMinecraft1211BlockPositionRepresentation(value: unknown): value is Minecraft1211BlockPosition {
  if (!Array.isArray(value) || value.length !== 3) return false;
  const [x, y, z] = value;
  return Number.isSafeInteger(x) && x >= MINECRAFT_1_21_1_BLOCKPOS_XZ_MIN && x <= MINECRAFT_1_21_1_BLOCKPOS_XZ_MAX &&
    Number.isSafeInteger(y) && y >= MINECRAFT_1_21_1_BLOCKPOS_Y_MIN && y <= MINECRAFT_1_21_1_BLOCKPOS_Y_MAX &&
    Number.isSafeInteger(z) && z >= MINECRAFT_1_21_1_BLOCKPOS_XZ_MIN && z <= MINECRAFT_1_21_1_BLOCKPOS_XZ_MAX;
}
