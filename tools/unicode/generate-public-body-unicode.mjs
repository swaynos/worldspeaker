#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const manifestPath = resolve(root, 'tools/unicode/manifest-15.0.0.json');
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
const version = manifest.unicodeVersion;
const dataDirectory = resolve(root, 'tools/unicode/data', version);
const generatedDataPath = resolve(root, `compatibility/unicode/public-body-unicode-${version}.json`);
const generatedTypeScriptPath = resolve(root, 'companion/src/generated/public-body-unicode.ts');
const generatedJavaPath = resolve(root, 'src/main/java/org/worldspeaker/generated/PublicBodyUnicode.java');

const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const requiredSourceNames = [
  'UnicodeData.txt', 'DerivedCoreProperties.txt', 'Scripts.txt', 'IndicSyllabicCategory.txt',
  'PropList.txt', 'emoji-data.txt', 'ReadMe.txt', 'LICENSE.txt',
].sort();
if (JSON.stringify(Object.keys(manifest.sources).sort()) !== JSON.stringify(requiredSourceNames) ||
    manifest.sourceBaseUrl !== 'https://www.unicode.org/Public/15.0.0/ucd/' ||
    manifest.licenseUrl !== 'https://www.unicode.org/license.txt') {
  throw new Error('Unicode source manifest is missing a pinned input or uses an unexpected source URL');
}
for (const [name, expectedHash] of Object.entries(manifest.sources)) {
  const bytes = readFileSync(resolve(dataDirectory, name));
  const actualHash = hash(bytes);
  if (actualHash !== expectedHash) {
    throw new Error(`Unicode ${version} source hash mismatch for ${name}: ${actualHash}`);
  }
}

const read = (name) => readFileSync(resolve(dataDirectory, name), 'utf8');
if (!read('ReadMe.txt').includes(`Version ${version} of the Unicode Standard`)) {
  throw new Error(`Unicode source ReadMe does not identify version ${version}`);
}
for (const name of ['DerivedCoreProperties.txt', 'Scripts.txt', 'IndicSyllabicCategory.txt', 'PropList.txt']) {
  if (!read(name).includes(`${name.replace('.txt', '')}-${version}.txt`)) {
    throw new Error(`Unicode source ${name} does not identify version ${version}`);
  }
}
if (!read('emoji-data.txt').includes(`Emoji Version ${version.slice(0, -2)}`)) {
  throw new Error(`Unicode source emoji-data.txt does not identify version ${version}`);
}

function intervalsFromUnicodeData(text) {
  const result = new Map();
  let first = null;
  for (const line of text.split(/\r?\n/u)) {
    if (line.length === 0) continue;
    const fields = line.split(';');
    const codePoint = Number.parseInt(fields[0], 16);
    const name = fields[1];
    const category = fields[2];
    if (name.endsWith(', First>')) {
      if (first !== null) throw new Error('Nested UnicodeData First range');
      first = { codePoint, category };
      continue;
    }
    if (name.endsWith(', Last>')) {
      if (first === null || first.category !== category || first.codePoint > codePoint) {
        throw new Error('Mismatched UnicodeData Last range');
      }
      add(result, category, first.codePoint, codePoint);
      first = null;
      continue;
    }
    if (first !== null) throw new Error('Unexpected UnicodeData record inside First/Last range');
    add(result, category, codePoint, codePoint);
  }
  if (first !== null) throw new Error('Unclosed UnicodeData First range');
  return result;
}

function parsePropertyFile(text, select) {
  const result = new Map();
  for (const originalLine of text.split(/\r?\n/u)) {
    const line = originalLine.split('#', 1)[0].trim();
    if (line.length === 0) continue;
    const fields = line.split(';').map((field) => field.trim());
    const [rangeText, property] = fields;
    if (!select(property, fields)) continue;
    const [startText, endText = startText] = rangeText.split('..');
    add(result, property, Number.parseInt(startText, 16), Number.parseInt(endText, 16));
  }
  return result;
}

function add(map, property, start, end) {
  if (!map.has(property)) map.set(property, []);
  map.get(property).push([start, end]);
}

function normalize(ranges) {
  const sorted = ranges.map(([start, end]) => [start, end]).sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  const merged = [];
  for (const [start, end] of sorted) {
    const previous = merged.at(-1);
    if (previous && start <= previous[1] + 1) previous[1] = Math.max(previous[1], end);
    else merged.push([start, end]);
  }
  return merged;
}

function contains(ranges, codePoint) {
  let low = 0;
  let high = ranges.length - 1;
  while (low <= high) {
    const middle = (low + high) >>> 1;
    const [start, end] = ranges[middle];
    if (codePoint < start) high = middle - 1;
    else if (codePoint > end) low = middle + 1;
    else return true;
  }
  return false;
}

function union(...rangeLists) {
  return normalize(rangeLists.flat());
}

function intersection(left, right) {
  const result = [];
  let a = 0;
  let b = 0;
  while (a < left.length && b < right.length) {
    const start = Math.max(left[a][0], right[b][0]);
    const end = Math.min(left[a][1], right[b][1]);
    if (start <= end) result.push([start, end]);
    if (left[a][1] < right[b][1]) a += 1;
    else b += 1;
  }
  return result;
}

function subtract(source, excluded) {
  const result = [];
  let exclusionIndex = 0;
  for (const [start, end] of source) {
    let cursor = start;
    while (exclusionIndex < excluded.length && excluded[exclusionIndex][1] < cursor) exclusionIndex += 1;
    let index = exclusionIndex;
    while (index < excluded.length && excluded[index][0] <= end) {
      const [excludedStart, excludedEnd] = excluded[index];
      if (excludedStart > cursor) result.push([cursor, excludedStart - 1]);
      cursor = Math.max(cursor, excludedEnd + 1);
      if (cursor > end) break;
      index += 1;
    }
    if (cursor <= end) result.push([cursor, end]);
  }
  return result;
}

const categories = intervalsFromUnicodeData(read('UnicodeData.txt'));
const assigned = normalize([...categories.values()].flat());
function complement(ranges) {
  const result = [];
  let cursor = 0;
  for (const [start, end] of ranges) {
    if (cursor < start) result.push([cursor, start - 1]);
    cursor = Math.max(cursor, end + 1);
  }
  if (cursor <= 0x10ffff) result.push([cursor, 0x10ffff]);
  return result;
}
const categoryRanges = (prefix) => normalize([...categories]
  .filter(([category]) => category.startsWith(prefix))
  .flatMap(([, ranges]) => ranges));
const derived = parsePropertyFile(read('DerivedCoreProperties.txt'), (property) => property === 'Default_Ignorable_Code_Point');
const scripts = parsePropertyFile(read('Scripts.txt'), (property) => property === 'Arabic');
const syllabic = parsePropertyFile(read('IndicSyllabicCategory.txt'), (property) => property === 'Virama');
const propertyList = parsePropertyFile(read('PropList.txt'), (property) => property === 'Variation_Selector');
const emoji = parsePropertyFile(read('emoji-data.txt'), (property) => property === 'Extended_Pictographic' || property === 'Emoji_Modifier');
const allVariationSelectors = normalize(propertyList.get('Variation_Selector') ?? []);

const letter = categoryRanges('L');
const mark = categoryRanges('M');
const number = categoryRanges('N');
const punctuation = categoryRanges('P');
const symbol = categoryRanges('S');
const unassigned = complement(assigned);
const other = union(categoryRanges('C'), unassigned);
const defaultIgnorable = normalize(derived.get('Default_Ignorable_Code_Point') ?? []);
const emojiModifier = normalize(emoji.get('Emoji_Modifier') ?? []);
const properties = {
  letter,
  mark,
  number,
  punctuation,
  symbol,
  other,
  unassigned,
  visibleBase: subtract(union(letter, number, punctuation, symbol), union(defaultIgnorable, emojiModifier, [[0x2800, 0x2800]])),
  defaultIgnorable,
  extendedPictographic: normalize(emoji.get('Extended_Pictographic') ?? []),
  emojiModifier,
  arabicLetter: intersection(letter, normalize(scripts.get('Arabic') ?? [])),
  virama: normalize(syllabic.get('Virama') ?? []),
  // Preserve the existing contract: emoji and ideographic selectors are contextual;
  // Mongolian free variation selectors stay default-ignorable and rejected.
  variationSelector: intersection(allVariationSelectors, [[0xfe00, 0xfe0f], [0xe0100, 0xe01ef]]),
};

for (const [property, ranges] of Object.entries(properties)) {
  if (ranges.length === 0) throw new Error(`Unicode property ${property} has no ranges`);
  for (const [start, end] of ranges) {
    if (start < 0 || end > 0x10ffff || start > end) throw new Error(`Invalid Unicode range in ${property}`);
  }
}
if (!contains(properties.other, 0x088f) || !contains(properties.letter, 0x11f02) ||
    !contains(properties.visibleBase, 0x11f02) || !contains(properties.extendedPictographic, 0x1fae8)) {
  throw new Error('Unicode 15.0.0 sentinel properties do not match the reviewed contract');
}

const propertyPayload = { unicodeVersion: version, properties };
const propertiesSha256 = hash(Buffer.from(JSON.stringify(propertyPayload)));
const generatedJson = `${JSON.stringify({
  unicodeVersion: version,
  propertiesSha256,
  sources: Object.entries(manifest.sources).map(([name, sha256]) => ({
    path: `tools/unicode/data/${version}/${name}`,
    url: name === 'LICENSE.txt'
      ? manifest.licenseUrl
      : `${manifest.sourceBaseUrl}${name === 'emoji-data.txt' ? 'emoji/' : ''}${name}`,
    sha256,
  })),
  properties,
}, null, 2)}\n`;

const propertyNames = Object.keys(properties);
const typeScriptArrays = propertyNames.map((property) =>
  `  ${property}: [${properties[property].flat().map((value) => `0x${value.toString(16)}`).join(', ')}],`,
).join('\n');
const generatedTypeScript = `/** Generated by tools/unicode/generate-public-body-unicode.mjs; do not edit. */
export const PUBLIC_BODY_UNICODE_VERSION = '${version}';
export const PUBLIC_BODY_UNICODE_DATA_SHA256 = '${propertiesSha256}';

const RANGES = {
${typeScriptArrays}
} as const;

export type PublicBodyUnicodeProperty = keyof typeof RANGES;

/** Unicode ${version} property lookup; it never consults the host runtime's Unicode tables. */
export function hasPublicBodyUnicodeProperty(property: PublicBodyUnicodeProperty, codePoint: number): boolean {
  const ranges: readonly number[] = RANGES[property];
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
`;

const javaArrays = propertyNames.map((property) =>
  `    private static final int[] ${property.replace(/[A-Z]/gu, (letter) => `_${letter}`).toUpperCase()} = {\n` +
  `            ${properties[property].flat().map((value) => `0x${value.toString(16)}`).join(', ')}\n` +
  '    };',
).join('\n');
const javaMethods = propertyNames.map((property) => {
  const method = property[0].toUpperCase() + property.slice(1);
  const table = property.replace(/[A-Z]/gu, (letter) => `_${letter}`).toUpperCase();
  return `    public static boolean is${method}(int codePoint) { return contains(${table}, codePoint); }`;
}).join('\n');
const generatedJava = `package org.worldspeaker.generated;

/** Generated from Unicode ${version}; do not edit. See compatibility/unicode/public-body-unicode-${version}.json. */
public final class PublicBodyUnicode {
    public static final String VERSION = "${version}";
    public static final String DATA_SHA256 = "${propertiesSha256}";

    private PublicBodyUnicode() {}

${javaArrays}

${javaMethods}

    public static boolean hasProperty(String property, int codePoint) {
        return switch (property) {
${propertyNames.map((property) => `            case "${property}" -> is${property[0].toUpperCase() + property.slice(1)}(codePoint);`).join('\n')}
            default -> throw new IllegalArgumentException("Unknown public-body Unicode property");
        };
    }

    private static boolean contains(int[] ranges, int codePoint) {
        int low = 0;
        int high = ranges.length / 2 - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int start = ranges[middle * 2];
            int end = ranges[middle * 2 + 1];
            if (codePoint < start) high = middle - 1;
            else if (codePoint > end) low = middle + 1;
            else return true;
        }
        return false;
    }
}
`;

const outputs = [
  [generatedDataPath, generatedJson],
  [generatedTypeScriptPath, generatedTypeScript],
  [generatedJavaPath, generatedJava],
];
const arguments_ = process.argv.slice(2);
if (arguments_.length > 1 || arguments_.some((argument) => argument !== '--check')) {
  throw new Error('Usage: node tools/unicode/generate-public-body-unicode.mjs [--check]');
}
const checkOnly = arguments_.includes('--check');
for (const [path, expected] of outputs) {
  let actual = null;
  try {
    actual = readFileSync(path, 'utf8');
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  if (actual === expected) continue;
  if (checkOnly) throw new Error(`Generated Unicode data is stale: ${path}`);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, expected);
}

console.log(`Unicode ${version} public-body tables ${checkOnly ? 'verified' : 'generated'} (${propertiesSha256}).`);
