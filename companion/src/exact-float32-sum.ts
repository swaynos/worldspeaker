import { normalizeFloat32WireNumber } from './protocol-text.js';

const MAX_DAMAGE_LIMIT = 5;
const FLOAT32_TO_BINARY64_UNIT_SHIFT = 925n;

/**
 * Compare the exact mathematical sum of two canonical float32 values with an
 * exact finite binary64 limit. The format bounds all BigInts: float32 operands
 * need at most 1202 bits in 2^-1074 units and the <=5 limit needs at most 1077.
 */
export function exactFloat32SumAtMost(left: number, right: number, limit: number): boolean {
  const leftUnits = float32Units(left);
  const rightUnits = float32Units(right);
  const limitUnits = nonNegativeDoubleUnits(limit);
  return leftUnits !== null && rightUnits !== null && limitUnits !== null && leftUnits + rightUnits <= limitUnits;
}

/** True only when both finite canonical float32 values sum mathematically to zero. */
export function exactFloat32SumIsZero(left: number, right: number): boolean {
  const leftUnits = float32Units(left);
  const rightUnits = float32Units(right);
  return leftUnits !== null && rightUnits !== null && leftUnits + rightUnits === 0n;
}

/** True only when the exact sum of two finite canonical float32 values is positive. */
export function exactFloat32SumIsPositive(left: number, right: number): boolean {
  const leftUnits = float32Units(left);
  const rightUnits = float32Units(right);
  return leftUnits !== null && rightUnits !== null && leftUnits + rightUnits > 0n;
}

function float32Units(value: number): bigint | null {
  const normalized = normalizeFloat32WireNumber(value);
  if (normalized === null) return null;

  const view = new DataView(new ArrayBuffer(4));
  view.setFloat32(0, normalized, false);
  const bits = view.getUint32(0, false);
  const sign = bits >>> 31;
  const exponent = (bits >>> 23) & 0xff;
  const fraction = bits & 0x7fffff;
  if (exponent === 0xff || sign !== 0 && (exponent !== 0 || fraction !== 0)) return null;

  const significand = exponent === 0 ? fraction : (1 << 23) | fraction;
  if (significand === 0) return 0n; // Includes negative zero.
  const shift = exponent === 0 ? FLOAT32_TO_BINARY64_UNIT_SHIFT : BigInt(exponent - 1) + FLOAT32_TO_BINARY64_UNIT_SHIFT;
  return BigInt(significand) << shift;
}

function nonNegativeDoubleUnits(value: number): bigint | null {
  if (!Number.isFinite(value) || value < 0 || value > MAX_DAMAGE_LIMIT) return null;

  const view = new DataView(new ArrayBuffer(8));
  view.setFloat64(0, value, false);
  const high = view.getUint32(0, false);
  const low = view.getUint32(4, false);
  const sign = high >>> 31;
  const exponent = (high >>> 20) & 0x7ff;
  const fraction = (BigInt(high & 0xfffff) << 32n) | BigInt(low);
  if (exponent === 0x7ff || sign !== 0 && (exponent !== 0 || fraction !== 0n)) return null;

  const significand = exponent === 0 ? fraction : (1n << 52n) | fraction;
  if (significand === 0n) return 0n; // Includes negative zero.
  return significand << BigInt(exponent === 0 ? 0 : exponent - 1);
}
