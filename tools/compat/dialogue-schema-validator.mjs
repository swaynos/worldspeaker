import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  isSafePublicText,
  PUBLIC_BODY_UNICODE_DATA_SHA256,
  PUBLIC_BODY_UNICODE_VERSION,
} from '../../companion/dist/protocol-text.js';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(resolve(root, 'companion/package.json'));
const Ajv2020 = require('ajv/dist/2020').default;
const INT64_MIN = -9_223_372_036_854_775_808n;
const INT64_MAX = 9_223_372_036_854_775_807n;

export const DIALOGUE_DIALECT_ID = 'https://worldspeaker.invalid/schema/dialogue-v5-meta-schema.json';
export const DIALOGUE_VOCABULARY_ID = 'https://worldspeaker.invalid/schema/dialogue-v5-vocabulary';

export function createDialogueSchemaValidator(schema, metaSchema) {
  const dialectId = schema?.$schema;
  const version = typeof dialectId === 'string'
    ? /^https:\/\/worldspeaker\.invalid\/schema\/dialogue-v([2345])-meta-schema\.json$/u.exec(dialectId)?.[1]
    : undefined;
  const vocabularyId = version === undefined ? undefined
    : `https://worldspeaker.invalid/schema/dialogue-v${version}-vocabulary`;
  if (version === undefined || metaSchema?.$id !== dialectId ||
      metaSchema.$vocabulary?.[vocabularyId] !== true ||
      ['4', '5'].includes(version) && (metaSchema?.allOf?.[1]?.properties?.worldspeakerSafePublicBody?.const !== true ||
        metaSchema?.allOf?.[1]?.properties?.worldspeakerPublicBodyUnicodeVersion?.const !== PUBLIC_BODY_UNICODE_VERSION ||
        metaSchema?.allOf?.[1]?.properties?.worldspeakerPublicBodyUnicodeDataSha256?.const !== PUBLIC_BODY_UNICODE_DATA_SHA256)) {
    throw new Error('dialogue schema requires the registered Worldspeaker Draft 2020-12 vocabulary');
  }

  const ajv = new Ajv2020({ allErrors: true, strict: true });
  ajv.addKeyword({
    keyword: 'worldspeakerMaxUtf16Length',
    type: 'string',
    schemaType: 'number',
    metaSchema: { type: 'integer', minimum: 1 },
    validate: (limit, value) => value.length <= limit,
  });
  ajv.addKeyword({
    keyword: 'worldspeakerNonBlankTrim',
    type: 'string',
    schemaType: 'boolean',
    metaSchema: { const: true },
    validate: (enabled, value) => !enabled || value.trim().length > 0,
  });
  ajv.addKeyword({
    keyword: 'worldspeakerSafePublicBody',
    type: 'string',
    schemaType: 'boolean',
    metaSchema: { const: true },
    validate: (enabled, value) => !enabled || isSafePublicText(value),
  });
  ajv.addKeyword({
    keyword: 'worldspeakerPublicBodyUnicodeVersion',
    type: 'string',
    schemaType: 'string',
    metaSchema: { const: PUBLIC_BODY_UNICODE_VERSION },
    validate: (version) => version === PUBLIC_BODY_UNICODE_VERSION,
  });
  ajv.addKeyword({
    keyword: 'worldspeakerPublicBodyUnicodeDataSha256',
    type: 'string',
    schemaType: 'string',
    metaSchema: { const: PUBLIC_BODY_UNICODE_DATA_SHA256 },
    validate: (sha256) => sha256 === PUBLIC_BODY_UNICODE_DATA_SHA256,
  });
  ajv.addKeyword({
    keyword: 'worldspeakerInt64Range',
    type: 'string',
    schemaType: 'boolean',
    metaSchema: { const: true },
    validate: (enabled, value) => {
      if (!enabled) return true;
      try {
        const integer = BigInt(value);
        return integer >= INT64_MIN && integer <= INT64_MAX;
      } catch {
        return false;
      }
    },
  });
  ajv.addKeyword({
    keyword: 'worldspeakerNonnegativeInt64Range',
    type: 'string',
    schemaType: 'boolean',
    metaSchema: { const: true },
    validate: (enabled, value) => {
      if (!enabled) return true;
      try {
        const integer = BigInt(value);
        return integer >= 0n && integer <= INT64_MAX;
      } catch {
        return false;
      }
    },
  });
  ajv.addKeyword({
    keyword: 'worldspeakerMaxUtf8Bytes',
    type: 'array',
    schemaType: 'number',
    metaSchema: { type: 'integer', minimum: 0 },
    validate: (limit, value) => Buffer.byteLength(JSON.stringify(value), 'utf8') <= limit,
  });

  ajv.addMetaSchema(metaSchema);
  return ajv.compile(schema);
}

export function createUnsupportedDraft2020Validator() {
  return new Ajv2020({ allErrors: true, strict: true });
}
