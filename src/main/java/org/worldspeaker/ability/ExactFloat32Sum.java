package org.worldspeaker.ability;

import java.math.BigInteger;

/**
 * Exact comparisons for sums of canonical binary32 result values. Each operand
 * is decoded from fixed-width IEEE-754 bits, never from a decimal spelling.
 * Float32 units are at most 1202 bits and the finite comparison limit (0..5)
 * at most 1077 bits, so work is strictly bounded by the formats.
 */
final class ExactFloat32Sum {
    private static final double MAX_DAMAGE_LIMIT = 5.0;
    private static final int FLOAT32_TO_BINARY64_UNIT_SHIFT = 925;

    private ExactFloat32Sum() {}

    static boolean isAtMost(float left, float right, double limit) {
        BigInteger leftUnits = float32Units(left);
        BigInteger rightUnits = float32Units(right);
        BigInteger limitUnits = nonNegativeDoubleUnits(limit);
        return leftUnits != null && rightUnits != null && limitUnits != null &&
                leftUnits.add(rightUnits).compareTo(limitUnits) <= 0;
    }

    static boolean isZero(float left, float right) {
        BigInteger leftUnits = float32Units(left);
        BigInteger rightUnits = float32Units(right);
        return leftUnits != null && rightUnits != null && leftUnits.add(rightUnits).signum() == 0;
    }

    static boolean isPositive(float left, float right) {
        BigInteger leftUnits = float32Units(left);
        BigInteger rightUnits = float32Units(right);
        return leftUnits != null && rightUnits != null && leftUnits.add(rightUnits).signum() > 0;
    }

    private static BigInteger float32Units(float value) {
        int bits = Float.floatToRawIntBits(value);
        int sign = bits >>> 31;
        int exponent = (bits >>> 23) & 0xff;
        int fraction = bits & 0x7fffff;
        if (exponent == 0xff || sign != 0 && (exponent != 0 || fraction != 0)) return null;

        int significand = exponent == 0 ? fraction : (1 << 23) | fraction;
        if (significand == 0) return BigInteger.ZERO; // Includes negative zero.
        int shift = exponent == 0 ? FLOAT32_TO_BINARY64_UNIT_SHIFT : exponent - 1 + FLOAT32_TO_BINARY64_UNIT_SHIFT;
        return BigInteger.valueOf(significand).shiftLeft(shift);
    }

    private static BigInteger nonNegativeDoubleUnits(double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > MAX_DAMAGE_LIMIT) return null;

        long bits = Double.doubleToRawLongBits(value);
        int sign = (int) (bits >>> 63);
        int exponent = (int) ((bits >>> 52) & 0x7ffL);
        long fraction = bits & 0x000f_ffff_ffff_ffffL;
        if (exponent == 0x7ff || sign != 0 && (exponent != 0 || fraction != 0L)) return null;

        long significand = exponent == 0 ? fraction : (1L << 52) | fraction;
        if (significand == 0L) return BigInteger.ZERO; // Includes negative zero.
        return BigInteger.valueOf(significand).shiftLeft(exponent == 0 ? 0 : exponent - 1);
    }
}
