/** Exact startup-name rules shared with the Java natural-address parser. */
import { hasPublicBodyUnicodeProperty } from './generated/public-body-unicode.js';

export const MAX_CONFIGURED_NAME_UTF16_UNITS = 48;
export const MAX_CONFIGURED_ALIASES = 8;
export const MAX_CONFIGURED_ALIASES_UTF16_UNITS = 384;

// General_Category=Cf ranges from UnicodeData.txt 15.0.0 (pinned source manifest).
const FORMAT_RANGES = [
  0x00ad, 0x00ad, 0x0600, 0x0605, 0x061c, 0x061c, 0x06dd, 0x06dd,
  0x070f, 0x070f, 0x0890, 0x0891, 0x08e2, 0x08e2, 0x180e, 0x180e,
  0x200b, 0x200f, 0x202a, 0x202e, 0x2060, 0x2064, 0x2066, 0x206f,
  0xfeff, 0xfeff, 0xfff9, 0xfffb, 0x110bd, 0x110bd, 0x110cd, 0x110cd,
  0x13430, 0x1343f, 0x1bca0, 0x1bca3, 0x1d173, 0x1d17a,
  0xe0001, 0xe0001, 0xe0020, 0xe007f,
] as const;

/** NFC, locale-independent lowercase, then NFC; this is not full case folding. */
export function normalizeConfiguredName(value: string): string {
  return value.normalize('NFC').toLowerCase().normalize('NFC');
}

/**
 * Matches NaturalAddressParser.isValidConfiguredName with Unicode 15 category
 * tables. Unpaired surrogates and address-input unsafe code points are rejected;
 * a name needs at least one letter, number, punctuation mark, or symbol.
 */
export function isValidNaturallyAddressableName(value: unknown): value is string {
  if (typeof value !== 'string' || value.length === 0 || value.length > MAX_CONFIGURED_NAME_UTF16_UNITS ||
      hasJavaStripWhitespaceAtEdge(value)) return false;

  let visible = false;
  for (let offset = 0; offset < value.length;) {
    const first = value.charCodeAt(offset);
    const codePoint = value.codePointAt(offset)!;
    const width = codePoint > 0xffff ? 2 : 1;
    if (first >= 0xd800 && first <= 0xdbff && width !== 2 ||
        first >= 0xdc00 && first <= 0xdfff && width !== 2) return false;
    if (isUnsafeAddressCodePoint(codePoint)) return false;
    if (isVisibleNameCodePoint(codePoint)) visible = true;
    offset += width;
  }
  return visible && normalizeConfiguredName(value).length <= 2048;
}

/** Java CompanionConfig removes only U+0020 edges before validating an alias. */
export function canonicalizeConfiguredAlias(value: string): string {
  let start = 0;
  let end = value.length;
  while (start < end && value.charCodeAt(start) === 0x20) start += 1;
  while (end > start && value.charCodeAt(end - 1) === 0x20) end -= 1;
  return value.slice(start, end);
}

export function isValidConfiguredAlias(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  return isValidNaturallyAddressableName(canonicalizeConfiguredAlias(value));
}

/** Validates and returns aliases in the same explicit ASCII-edge form as Java. */
export function validateConfiguredCharacterNames(
  displayName: unknown,
  aliasValues: unknown,
): Readonly<{ displayName: string; aliases: readonly string[] }> | null {
  if (!isValidNaturallyAddressableName(displayName) || !Array.isArray(aliasValues) ||
      aliasValues.length > MAX_CONFIGURED_ALIASES) return null;

  const aliases: string[] = [];
  const normalizedNames = new Set([normalizeConfiguredName(displayName)]);
  let totalLength = 0;
  for (const value of aliasValues as unknown[]) {
    if (typeof value !== 'string') return null;
    const alias = canonicalizeConfiguredAlias(value);
    if (!isValidNaturallyAddressableName(alias)) return null;
    totalLength += alias.length;
    const normalizedAlias = normalizeConfiguredName(alias);
    if (totalLength > MAX_CONFIGURED_ALIASES_UTF16_UNITS || normalizedNames.has(normalizedAlias)) return null;
    normalizedNames.add(normalizedAlias);
    aliases.push(alias);
  }
  return { displayName, aliases };
}

function hasJavaStripWhitespaceAtEdge(value: string): boolean {
  const first = value.codePointAt(0)!;
  const lastUnit = value.charCodeAt(value.length - 1);
  const lastStart = lastUnit >= 0xdc00 && lastUnit <= 0xdfff && value.length > 1 &&
    value.charCodeAt(value.length - 2) >= 0xd800 && value.charCodeAt(value.length - 2) <= 0xdbff
    ? value.length - 2
    : value.length - 1;
  const last = value.codePointAt(lastStart)!;
  return isJavaWhitespace(first) || isJavaWhitespace(last);
}

function isUnsafeAddressCodePoint(codePoint: number): boolean {
  return codePoint >= 0 && codePoint <= 0x1f || codePoint >= 0x7f && codePoint <= 0x9f ||
    contains(FORMAT_RANGES, codePoint) || codePoint === 0x00a7 || codePoint === 0x2800 ||
    codePoint >= 0xfff0 && codePoint <= 0xfff8 || codePoint === 0x2028 || codePoint === 0x2029 ||
    hasPublicBodyUnicodeProperty('defaultIgnorable', codePoint);
}

function isVisibleNameCodePoint(codePoint: number): boolean {
  if (isUnicodeAddressWhitespace(codePoint) || isUnsafeAddressCodePoint(codePoint) ||
      hasPublicBodyUnicodeProperty('mark', codePoint) ||
      hasPublicBodyUnicodeProperty('emojiModifier', codePoint)) return false;
  return hasPublicBodyUnicodeProperty('letter', codePoint) ||
    hasPublicBodyUnicodeProperty('number', codePoint) ||
    hasPublicBodyUnicodeProperty('punctuation', codePoint) ||
    hasPublicBodyUnicodeProperty('symbol', codePoint);
}

function isUnicodeAddressWhitespace(codePoint: number): boolean {
  return isJavaWhitespace(codePoint) || codePoint === 0x00a0 || codePoint === 0x2007 ||
    codePoint === 0x202f;
}

function isJavaWhitespace(codePoint: number): boolean {
  return codePoint >= 0x09 && codePoint <= 0x0d || codePoint >= 0x1c && codePoint <= 0x1f ||
    codePoint === 0x20 || codePoint === 0x1680 || codePoint >= 0x2000 && codePoint <= 0x2006 ||
    codePoint >= 0x2008 && codePoint <= 0x200a || codePoint === 0x2028 || codePoint === 0x2029 ||
    codePoint === 0x205f || codePoint === 0x3000;
}

function contains(ranges: readonly number[], codePoint: number): boolean {
  let low = 0;
  let high = ranges.length / 2 - 1;
  while (low <= high) {
    const middle = (low + high) >>> 1;
    const start = ranges[middle * 2]!;
    const end = ranges[middle * 2 + 1]!;
    if (codePoint < start) high = middle - 1;
    else if (codePoint > end) low = middle + 1;
    else return true;
  }
  return false;
}
