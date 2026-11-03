import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';
import {
  PUBLIC_BODY_UNICODE_DATA_SHA256,
  PUBLIC_BODY_UNICODE_VERSION,
  hasPublicBodyUnicodeProperty,
  type PublicBodyUnicodeProperty,
} from '../generated/public-body-unicode.js';

type UnicodeTable = {
  unicodeVersion: string;
  propertiesSha256: string;
  properties: Record<PublicBodyUnicodeProperty, Array<[number, number]>>;
};

const table = JSON.parse(readFileSync(
  resolve(process.cwd(), '../compatibility/unicode/public-body-unicode-15.0.0.json'), 'utf8',
)) as UnicodeTable;
const EXPECTED_PROPERTIES: PublicBodyUnicodeProperty[] = [
  'letter', 'mark', 'number', 'punctuation', 'symbol', 'other', 'unassigned', 'visibleBase',
  'defaultIgnorable', 'extendedPictographic', 'emojiModifier', 'arabicLetter', 'virama', 'variationSelector',
];

test('all Unicode 15 public-body property classifications match the normative table exhaustively', () => {
  assert.equal(table.unicodeVersion, PUBLIC_BODY_UNICODE_VERSION);
  assert.equal(table.propertiesSha256, PUBLIC_BODY_UNICODE_DATA_SHA256);
  const properties = Object.keys(table.properties) as PublicBodyUnicodeProperty[];
  assert.deepEqual(properties.sort(), [...EXPECTED_PROPERTIES].sort());
  const rangeIndexes = Object.fromEntries(properties.map((property) => [property, 0])) as
    Record<PublicBodyUnicodeProperty, number>;

  for (let codePoint = 0; codePoint <= 0x10ffff; codePoint += 1) {
    for (const property of properties) {
      const ranges = table.properties[property];
      let rangeIndex = rangeIndexes[property];
      while (rangeIndex < ranges.length && codePoint > ranges[rangeIndex]![1]) rangeIndex += 1;
      rangeIndexes[property] = rangeIndex;
      const range = ranges[rangeIndex];
      const expected = range !== undefined && range[0] <= codePoint;
      const actual = hasPublicBodyUnicodeProperty(property, codePoint);
      if (actual !== expected) {
        throw new Error(`${property} differs at U+${codePoint.toString(16).toUpperCase()}`);
      }
    }
  }
});

test('selected-version assignment sentinels distinguish older and newer data boundaries', () => {
  assert.equal(hasPublicBodyUnicodeProperty('unassigned', 0x088f), true);
  assert.equal(hasPublicBodyUnicodeProperty('letter', 0x088f), false);
  assert.equal(hasPublicBodyUnicodeProperty('other', 0x088f), true);
  assert.equal(hasPublicBodyUnicodeProperty('letter', 0x11f02), true);
  assert.equal(hasPublicBodyUnicodeProperty('visibleBase', 0x11f02), true);
  assert.equal(hasPublicBodyUnicodeProperty('extendedPictographic', 0x1fae8), true);
  assert.equal(hasPublicBodyUnicodeProperty('other', 0x10ffff), true);
  assert.equal(hasPublicBodyUnicodeProperty('other', 0xd800), true);
});

test('the production body helper does not consult host ECMAScript Unicode property escapes', () => {
  const implementation = readFileSync(resolve(process.cwd(), 'src/protocol-text.ts'), 'utf8');
  assert.doesNotMatch(implementation, /\\p\{/u);
  assert.match(implementation, /hasPublicBodyUnicodeProperty/u);
});
