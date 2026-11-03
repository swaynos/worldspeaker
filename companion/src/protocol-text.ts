/** Shared text and numeric predicates for protocol validation and public output. */
import {
  PUBLIC_BODY_UNICODE_DATA_SHA256,
  PUBLIC_BODY_UNICODE_VERSION,
  hasPublicBodyUnicodeProperty,
  type PublicBodyUnicodeProperty,
} from './generated/public-body-unicode.js';

export { PUBLIC_BODY_UNICODE_DATA_SHA256, PUBLIC_BODY_UNICODE_VERSION };

const hasProperty = (property: PublicBodyUnicodeProperty, codePoint: number): boolean =>
  hasPublicBodyUnicodeProperty(property, codePoint);

export function isEcmaScriptBlank(value: string): boolean {
  return value.trim().length === 0;
}

/**
 * Validates exact public text without normalizing or removing code points.
 * Combining marks, selectors, and join controls are accepted only in a
 * supported sequence attached to a visible base.
 */
export function isSafePublicText(value: string): boolean {
  if (value.length === 0 || isEcmaScriptBlank(value)) return false;

  const codePoints: number[] = [];
  for (let index = 0; index < value.length;) {
    const first = value.charCodeAt(index);
    const codePoint = value.codePointAt(index)!;
    const width = codePoint > 0xffff ? 2 : 1;
    if (first >= 0xd800 && first <= 0xdbff && width !== 2 ||
        first >= 0xdc00 && first <= 0xdfff && width !== 2) return false;
    codePoints.push(codePoint);
    index += width;
  }

  let visible = false;
  let visibleBaseInCluster = false;
  for (let index = 0; index < codePoints.length; index += 1) {
    const codePoint = codePoints[index]!;
    if (codePoint <= 0x1f || codePoint >= 0x7f && codePoint <= 0x9f ||
        codePoint === 0x2028 || codePoint === 0x2029 || codePoint === 0x00a7 ||
        codePoint === 0x034f || codePoint === 0x2800 || isBidiControl(codePoint)) return false;

    if (isVariationSelector(codePoint)) {
      if (index === 0 || !isVisibleBase(codePoints[index - 1]!)) return false;
      continue;
    }

    if (codePoint === 0x200c || codePoint === 0x200d) {
      if (!isSupportedJoinControl(codePoints, index)) return false;
      visibleBaseInCluster = false;
      continue;
    }

    if (isDefaultIgnorable(codePoint) || hasProperty('other', codePoint)) return false;

    if (isEmojiModifier(codePoint)) {
      if (!hasEmojiBaseBefore(codePoints, index)) return false;
      visible = true;
      visibleBaseInCluster = true;
      continue;
    }

    if (hasProperty('mark', codePoint)) {
      if (codePoint === 0x20e3) {
        if (!isValidKeycap(codePoints, index)) return false;
        visible = true;
        visibleBaseInCluster = true;
        continue;
      }
      if (!visibleBaseInCluster) return false;
      continue;
    }

    if (isVisibleBase(codePoint)) {
      visible = true;
      visibleBaseInCluster = true;
      continue;
    }

    // Separators and other non-base characters end a combining sequence.
    visibleBaseInCluster = false;
  }

  return visible;
}

function isVisibleBase(codePoint: number): boolean {
  return hasProperty('visibleBase', codePoint);
}

function isVariationSelector(codePoint: number): boolean {
  return hasProperty('variationSelector', codePoint);
}

function isValidKeycap(codePoints: readonly number[], index: number): boolean {
  const baseIndex = codePoints[index - 1] === 0xfe0f ? index - 2 : index - 1;
  if (baseIndex < 0 || codePoints[index - 1] === 0xfe0e) return false;
  const base = codePoints[baseIndex]!;
  return base === 0x23 || base === 0x2a || base >= 0x30 && base <= 0x39;
}

function isSupportedJoinControl(codePoints: readonly number[], index: number): boolean {
  const previous = codePoints[index - 1];
  const next = codePoints[index + 1];
  if (previous === undefined || next === undefined) return false;

  let pictographIndex = index - 1;
  while (pictographIndex >= 0 &&
      (isEmojiModifier(codePoints[pictographIndex]!) || isVariationSelector(codePoints[pictographIndex]!) ||
        hasProperty('mark', codePoints[pictographIndex]!))) {
    pictographIndex -= 1;
  }
  if (pictographIndex >= 0 && isExtendedPictographic(codePoints[pictographIndex]!) && isExtendedPictographic(next)) {
    return true;
  }

  if (isArabicLetter(previous) && isArabicLetter(next)) return true;
  return index >= 2 && isVirama(previous) && isLetterCodePoint(codePoints[index - 2]!) && isLetterCodePoint(next);
}

function hasEmojiBaseBefore(codePoints: readonly number[], index: number): boolean {
  let baseIndex = index - 1;
  while (baseIndex >= 0 && isVariationSelector(codePoints[baseIndex]!)) baseIndex -= 1;
  return baseIndex >= 0 && isExtendedPictographic(codePoints[baseIndex]!);
}

function isArabicLetter(codePoint: number): boolean {
  return hasProperty('arabicLetter', codePoint);
}

function isLetterCodePoint(codePoint: number): boolean {
  return hasProperty('letter', codePoint);
}

function isVirama(codePoint: number): boolean {
  return hasProperty('virama', codePoint);
}

function isExtendedPictographic(codePoint: number): boolean {
  return hasProperty('extendedPictographic', codePoint);
}

function isEmojiModifier(codePoint: number): boolean {
  return hasProperty('emojiModifier', codePoint);
}

function isBidiControl(codePoint: number): boolean {
  return codePoint === 0x061c || codePoint === 0x200e || codePoint === 0x200f ||
    codePoint >= 0x202a && codePoint <= 0x202e || codePoint >= 0x2066 && codePoint <= 0x206f;
}

function isDefaultIgnorable(codePoint: number): boolean {
  return hasProperty('defaultIgnorable', codePoint);
}

export function isNonNegativeSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}

const MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991n;
const JSON_NUMBER_TOKEN = /^(-?)(0|[1-9][0-9]*)(?:\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?$/u;

/** Checks an original JSON number token as a mathematical safe integer, before Number rounding. */
export function isSafeJsonIntegerToken(token: string): boolean {
  return safeJsonIntegerTokenValue(token) !== null;
}

/** Checks an original JSON number token as a non-negative mathematical safe integer. */
export function isNonNegativeSafeJsonIntegerToken(token: string): boolean {
  const integer = safeJsonIntegerTokenValue(token);
  return integer !== null && integer >= 0n;
}

function safeJsonIntegerTokenValue(token: string): bigint | null {
  const match = JSON_NUMBER_TOKEN.exec(token);
  if (!match) return null;

  const fraction = match[3] ?? '';
  const digits = `${match[2]}${fraction}`.replace(/^0+/u, '');
  if (digits.length === 0) return 0n; // Both positive and negative zero are mathematical zero.

  const scale = BigInt(match[4] ?? '0') - BigInt(fraction.length);
  let integer: bigint;
  if (scale >= 0n) {
    if (BigInt(digits.length) + scale > 16n) return null;
    integer = BigInt(digits) * 10n ** scale;
  } else {
    const places = -scale;
    const withoutTrailingZeros = digits.replace(/0+$/u, '');
    const trailingZeros = digits.length - withoutTrailingZeros.length;
    if (places > BigInt(trailingZeros)) return null;
    const integerDigits = digits.slice(0, digits.length - Number(places));
    integer = BigInt(integerDigits || '0');
  }

  if (integer > MAX_SAFE_JSON_INTEGER) return null;
  return match[1] === '-' ? -integer : integer;
}

/**
 * Returns the normalized binary32 value when the input is one of that value's
 * Java Float.toString or ECMAScript JSON.stringify(Math.fround(value)) forms.
 * This is normalization, not clamping.
 */
export function normalizeFloat32WireNumber(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null;
  const normalized = Math.fround(value);
  if (!Number.isFinite(normalized) || value !== 0 && normalized === 0) return null;
  const javaCanonical = javaFloat32RoundTripDecimal(normalized);
  const ecmaCanonical = JSON.stringify(normalized);
  if (javaCanonical === null) return null;
  return Object.is(value, Number(javaCanonical)) || Object.is(value, Number(ecmaCanonical)) ? normalized : null;
}

/**
 * Value-level compatibility predicate. The historical Java name is retained
 * for API stability; both canonical Java and ECMAScript float32 serializations
 * are accepted.
 */
export function isJavaFloat32WireNumber(value: unknown): value is number {
  return normalizeFloat32WireNumber(value) !== null;
}

/** Checks an exact raw decimal against either canonical float32 wire encoding. */
export function isCanonicalJavaFloat32JsonNumberToken(token: string): boolean {
  const input = normalizedJsonDecimal(token);
  if (input === null) return false;
  const value = Number(token);
  const normalized = normalizeFloat32WireNumber(value);
  if (normalized === null) return false;
  const javaCanonical = javaFloat32RoundTripDecimal(normalized);
  if (javaCanonical === null) return false;
  const ecmaCanonical = JSON.stringify(normalized);
  return sameJsonDecimal(input, normalizedJsonDecimal(javaCanonical)) ||
    sameJsonDecimal(input, normalizedJsonDecimal(ecmaCanonical));
}

type NormalizedJsonDecimal = Readonly<{ negative: boolean; digits: string; exponent: bigint }>;

function normalizedJsonDecimal(token: string): NormalizedJsonDecimal | null {
  const match = JSON_NUMBER_TOKEN.exec(token);
  if (!match) return null;
  const negative = match[1] === '-';
  const fraction = match[3] ?? '';
  const rawDigits = `${match[2]}${fraction}`.replace(/^0+/u, '');
  if (rawDigits.length === 0) return { negative, digits: '0', exponent: 0n };
  const digits = rawDigits.replace(/0+$/u, '');
  const trailingZeros = rawDigits.length - digits.length;
  return {
    negative,
    digits,
    exponent: BigInt(match[4] ?? '0') - BigInt(fraction.length) + BigInt(trailingZeros),
  };
}

function sameJsonDecimal(left: NormalizedJsonDecimal, right: NormalizedJsonDecimal | null): boolean {
  return right !== null && left.negative === right.negative && left.digits === right.digits &&
    left.exponent === right.exponent;
}

function javaFloat32RoundTripDecimal(value: number): string | null {
  if (Object.is(value, -0)) return '-0.0';
  if (value === 0) return '0.0';

  const exact = exactFloat32Decimal(value);
  if (exact === null) return null;
  const minimumDigits = exact.exponent < -3 || exact.exponent >= 7 ? 2 : 1;
  for (let precision = minimumDigits; precision <= 9; precision += 1) {
    const candidate = roundedDecimal(exact, precision);
    if (Math.fround(Number(candidate)) === value) return candidate;
  }
  return null;
}

type ExactFloat32Decimal = Readonly<{ negative: boolean; digits: string; exponent: number }>;

function exactFloat32Decimal(value: number): ExactFloat32Decimal | null {
  if (!Number.isFinite(value) || value === 0) return null;
  const view = new DataView(new ArrayBuffer(4));
  view.setFloat32(0, Math.abs(value), false);
  const bits = view.getUint32(0, false);
  const exponentBits = (bits >>> 23) & 0xff;
  const fraction = bits & 0x7fffff;
  const significand = BigInt(exponentBits === 0 ? fraction : (1 << 23) | fraction);
  const binaryExponent = exponentBits === 0 ? -149 : exponentBits - 127 - 23;
  let digits: string;
  let decimalScale: number;
  if (binaryExponent >= 0) {
    digits = (significand << BigInt(binaryExponent)).toString();
    decimalScale = 0;
  } else {
    decimalScale = -binaryExponent;
    digits = (significand * 5n ** BigInt(decimalScale)).toString();
  }
  return {
    negative: value < 0,
    digits,
    exponent: digits.length - decimalScale - 1,
  };
}

function roundedDecimal(exact: ExactFloat32Decimal, precision: number): string {
  const kept = (exact.digits + '0'.repeat(Math.max(0, precision - exact.digits.length))).slice(0, precision);
  const discarded = exact.digits.slice(precision);
  let rounded = kept;
  if (discarded.length > 0) {
    const firstDiscarded = discarded[0]!;
    const remainderIsNonZero = /[1-9]/u.test(discarded.slice(1));
    const roundUp = firstDiscarded > '5' || firstDiscarded === '5' &&
      (remainderIsNonZero || Number(kept[kept.length - 1]) % 2 === 1);
    if (roundUp) {
      const incremented = (BigInt(kept) + 1n).toString();
      if (incremented.length > precision) {
        return `${exact.negative ? '-' : ''}1${precision > 1 ? `.${'0'.repeat(precision - 1)}` : ''}e${exact.exponent + 1}`;
      }
      rounded = incremented.padStart(precision, '0');
    }
  }
  return `${exact.negative ? '-' : ''}${rounded[0]}${precision > 1 ? `.${rounded.slice(1)}` : ''}e${exact.exponent}`;
}

/** Checks an exact positive JSON decimal/exponent token against an integer ceiling. */
export function isPositiveJsonNumberTokenAtMost(token: string, maximum: number): boolean {
  if (!Number.isSafeInteger(maximum) || maximum < 1) return false;
  const match = JSON_NUMBER_TOKEN.exec(token);
  if (!match || match[1] === '-') return false;

  const fraction = match[3] ?? '';
  const digits = `${match[2]}${fraction}`.replace(/^0+/u, '');
  if (digits.length === 0) return false;

  const scale = BigInt(match[4] ?? '0') - BigInt(fraction.length);
  const maximumDigits = BigInt(String(maximum).length);
  const ceiling = BigInt(maximum);
  if (scale >= 0n) {
    if (BigInt(digits.length) + scale > maximumDigits) return false;
    return BigInt(digits) * 10n ** scale <= ceiling;
  }

  const integerDigits = BigInt(digits.length) + scale;
  if (integerDigits > maximumDigits) return false;
  if (integerDigits <= 0n) return true;
  const wholeLength = Number(integerDigits);
  const whole = BigInt(digits.slice(0, wholeLength));
  if (whole < ceiling) return true;
  if (whole > ceiling) return false;
  return /^0*$/u.test(digits.slice(wholeLength));
}
