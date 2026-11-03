import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const schema = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v5.schema.json'), 'utf8'));
const metaSchema = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v5-meta-schema.json'), 'utf8'));
const legacyV4Schema = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v4.schema.json'), 'utf8'));
const legacyV4MetaSchema = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v4-meta-schema.json'), 'utf8'));
const legacyMetaSchema = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v3-meta-schema.json'), 'utf8'));
const fixtures = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v5-fixtures.json'), 'utf8'));
const publicBodyCorpus = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v4-public-body-corpus.json'), 'utf8'));
const legacyV3Fixtures = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v3-fixtures.json'), 'utf8'));
const legacyV4Fixtures = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v4-fixtures.json'), 'utf8'));
const { createDialogueSchemaValidator, createUnsupportedDraft2020Validator } = await import('./dialogue-schema-validator.mjs');
const validateSchema = createDialogueSchemaValidator(schema, metaSchema);
const production = await import('../../companion/dist/protocol.js');
const { parseStrictJson } = await import('../../companion/dist/strict-json.js');

const binding = {
  serverId: fixtures.request.serverId,
  worldId: fixtures.request.worldId,
  characterId: fixtures.request.characterId,
  agentId: fixtures.request.agentId,
};

test('v5 fixtures satisfy the reused Draft 2020-12 vocabulary and companion production validators', () => {
  for (const [name, frame] of Object.entries(fixtures)) {
    assert.equal(validateSchema(frame), true, `${name} should satisfy the schema: ${JSON.stringify(validateSchema.errors)}`);
    assert.doesNotThrow(() => validateProduction(frame), `${name} should pass the companion production validator`);
  }
});

test('retained v3 and v4 fixtures remain schema-valid historical evidence but are rejected by the v5 production parser', () => {
  const legacySchema = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v3.schema.json'), 'utf8'));
  const validateLegacySchema = createDialogueSchemaValidator(legacySchema, legacyMetaSchema);
  for (const [name, frame] of Object.entries(legacyV3Fixtures)) {
    assert.equal(validateLegacySchema(frame), true, `${name} remains valid under the retained v3 schema`);
    assert.throws(() => validateProduction(frame), undefined, `${name} must not be accepted as v5`);
  }
  const validateLegacyV4Schema = createDialogueSchemaValidator(legacyV4Schema, legacyV4MetaSchema);
  for (const [name, frame] of Object.entries(legacyV4Fixtures)) {
    assert.equal(validateLegacyV4Schema(frame), true, `${name} remains valid under the retained v4 schema`);
    assert.throws(() => validateProduction(frame), undefined, `${name} must not be accepted as v5`);
  }
});

test('the published schema requires its registered vocabulary and unsupported Draft 2020-12 validation fails closed', () => {
  assert.equal(schema.$schema, 'https://worldspeaker.invalid/schema/dialogue-v5-meta-schema.json');
  assert.equal(schema.$id, 'https://worldspeaker.invalid/schema/dialogue-v5.json');
  assert.equal(metaSchema.$vocabulary['https://worldspeaker.invalid/schema/dialogue-v5-vocabulary'], true);
  assert.equal(metaSchema.allOf[1].properties.worldspeakerSafePublicBody.const, true,
    'the required v4 vocabulary declares the exact public-body predicate');
  assert.equal(metaSchema.allOf[1].properties.worldspeakerPublicBodyUnicodeVersion.const, '15.0.0');
  assert.equal(metaSchema.allOf[1].properties.worldspeakerPublicBodyUnicodeDataSha256.const,
    '6fac8ff52897d02955f63e272c82f8a3fdaa6b6964d4502818c9581393656e23');
  assert.equal(schema.$defs.message.properties.text.worldspeakerSafePublicBody, true);
  assert.equal(schema.$defs.message.properties.text.worldspeakerPublicBodyUnicodeVersion, '15.0.0');
  assert.equal(schema.$defs.message.properties.text.worldspeakerPublicBodyUnicodeDataSha256,
    metaSchema.allOf[1].properties.worldspeakerPublicBodyUnicodeDataSha256.const);
  assert.equal(schema.$defs.message.properties.text.worldspeakerMaxUtf16Length, 1980);
  const optionalVocabulary = structuredClone(metaSchema);
  optionalVocabulary.$vocabulary['https://worldspeaker.invalid/schema/dialogue-v5-vocabulary'] = false;
  assert.throws(() => createDialogueSchemaValidator(schema, optionalVocabulary), /requires the registered Worldspeaker/u,
    'a consumer may not treat the public-body vocabulary as optional');
  assert.throws(
    () => createUnsupportedDraft2020Validator().compile(schema),
    /no schema|meta-schema|resolve.*schema/i,
  );

  const mismatchedUnicodeVersion = structuredClone(metaSchema);
  mismatchedUnicodeVersion.allOf[1].properties.worldspeakerPublicBodyUnicodeVersion.const = '17.0.0';
  assert.throws(() => createDialogueSchemaValidator(schema, mismatchedUnicodeVersion),
    /requires the registered Worldspeaker/u);
  const mismatchedDataHash = structuredClone(metaSchema);
  mismatchedDataHash.allOf[1].properties.worldspeakerPublicBodyUnicodeDataSha256.const = '0'.repeat(64);
  assert.throws(() => createDialogueSchemaValidator(schema, mismatchedDataHash),
    /requires the registered Worldspeaker/u);
});

test('the shared public-body Unicode corpus agrees across the v5 schema and production parser', () => {
  for (const item of publicBodyCorpus.cases) {
    const message = structuredClone(fixtures.message);
    message.text = item.text;
    assert.equal(validateSchema(message), item.accepted,
      `${item.name}: schema ${JSON.stringify(validateSchema.errors)}`);
    if (item.accepted) assert.doesNotThrow(() => validateProduction(message), `${item.name}: production parser`);
    else assert.throws(() => validateProduction(message), undefined, `${item.name}: production parser`);
  }
});

test('schema structure and production validation reject malformed and spoofed requests', () => {
  const cases = [
    {
      name: 'v1 downgrade', schemaValid: false,
      mutate: (request) => { request.protocol = 'worldspeaker.compat.v1'; },
    },
    {
      name: 'v2 peer downgrade', schemaValid: false,
      mutate: (request) => { request.protocol = 'worldspeaker.compat.v2'; },
    },
    {
      name: 'unknown root authority field', schemaValid: false,
      mutate: (request) => { request.policy = { admin: true }; },
    },
    {
      name: 'nested operator field', schemaValid: false,
      mutate: (request) => { request.event.operator = true; },
    },
    {
      name: 'missing primary event field', schemaValid: false,
      mutate: (request) => { delete request.event.serverEpochId; },
    },
    {
      name: 'coerced wall time', schemaValid: false,
      mutate: (request) => { request.event.occurredAtUnixMillis = '1793275200000'; },
    },
    {
      name: 'unsafe wall time', schemaValid: false,
      mutate: (request) => { request.event.occurredAtUnixMillis = Number.MAX_SAFE_INTEGER + 1; },
    },
    {
      name: 'whitespace-only acknowledgement text', schemaValid: false,
      mutate: (request) => { request.acknowledgement.text = '\u00a0\ufeff\u2003'; },
    },
    {
      name: 'out-of-range signed long', schemaValid: false,
      mutate: (request) => { request.event.capturedAtMonotonicNanos = '9223372036854775808'; },
    },
    {
      name: 'astral UTF-16 unit overflow', schemaValid: false,
      mutate: (request) => { request.event.text = '😀'.repeat(257); },
    },
    {
      name: 'whitespace-only binding identifier', schemaValid: false,
      mutate: (request) => {
        setBinding(request, '\u00a0\ufeff');
      },
    },
    {
      name: 'whitespace-only player name', schemaValid: false,
      mutate: (request) => { request.event.playerName = '\u00a0\ufeff'; },
    },
    {
      name: 'request and invocation identity collision', schemaValid: true,
      mutate: (request) => { request.invocationId = request.requestId; },
    },
    {
      name: 'history cannot be the primary event', schemaValid: false,
      mutate: (request) => { request.event.eventOrigin = 'history'; },
    },
    {
      name: 'spoofed player audience', schemaValid: true,
      mutate: (request) => { request.replyAudience.playerId = '00000000-0000-4000-8000-00000000000c'; },
    },
    {
      name: 'duplicate primary event in context batch', schemaValid: true,
      mutate: (request) => { request.contextBatch[0].eventId = request.event.eventId; },
    },
    {
      name: 'cross-binding context event', schemaValid: true,
      mutate: (request) => { request.contextBatch[0].worldId = 'another-world'; },
    },
    {
      name: 'attention event cannot serialize coordinates', schemaValid: false,
      mutate: (request) => { request.contextBatch[1].coordinates = [1, 2, 3]; },
    },
    {
      name: 'sample source is exclusive to sampled movement', schemaValid: false,
      mutate: (request) => { request.contextBatch[1].source = 'server_sample'; },
    },
    {
      name: 'attention dimension detail has an exact field set', schemaValid: false,
      mutate: (request) => { request.contextBatch[1].details.originDimension = 'minecraft:the_nether'; },
    },
    {
      name: 'loss revision is bounded to a nonnegative signed long', schemaValid: false,
      mutate: (request) => { request.contextStatus.lossRevision = '9223372036854775808'; },
    },
    {
      name: 'loss reasons are unique', schemaValid: false,
      mutate: (request) => { request.contextStatus.reasons.push('epoch_start'); },
    },
    {
      name: 'bounded context cannot claim loss reasons', schemaValid: false,
      mutate: (request) => { request.contextStatus.state = 'bounded'; },
    },
    {
      name: 'spoofed acknowledgement audience', schemaValid: true,
      mutate: (request) => { request.acknowledgement.replyAudience.playerId = '00000000-0000-4000-8000-00000000000c'; },
    },
  ];

  for (const item of cases) {
    const request = structuredClone(fixtures.request);
    item.mutate(request);
    assert.equal(validateSchema(request), item.schemaValid, `${item.name}: schema result`);
    assert.throws(() => validateProduction(request, bindingFrom(request)), undefined, `${item.name}: production result`);
  }
});

test('registered v3 schema and companion validators allow history only as inert context', () => {
  const request = structuredClone(fixtures.request);
  request.contextBatch[1].eventOrigin = 'history';

  assert.equal(validateSchema(request), true, `historical context should satisfy the registered schema: ${JSON.stringify(validateSchema.errors)}`);
  const accepted = validateProduction(request, bindingFrom(request));
  assert.equal(accepted.event.eventOrigin, 'live', 'history context must not replace the live primary event');
  assert.equal(accepted.event.text, fixtures.request.event.text, 'the original prompt remains the primary invocation event');
  assert.deepEqual(accepted.contextBatch.map((event) => event.eventOrigin), ['history', 'history']);

  const historicalPrimary = structuredClone(request);
  historicalPrimary.event.eventOrigin = 'history';
  assert.equal(validateSchema(historicalPrimary), false, 'the registered schema must reject a historical primary event');
  assert.throws(() => validateProduction(historicalPrimary, bindingFrom(historicalPrimary)), undefined,
    'the companion validator must reject a historical primary event');
});

test('schema and companion production validators accept UTF-16 text and signed-long boundaries', () => {
  const requestCases = [
    ['binding identifiers at 512 BMP units', (request) => setBinding(request, 'B'.repeat(512))],
    ['one binding identifier at 512 units as astral pairs', (request) => setBindingField(request, 'serverId', '😀'.repeat(256))],
    ['player name at 32 BMP units', (request) => { request.event.playerName = 'P'.repeat(32); }],
    ['player name at 32 units as astral pairs', (request) => { request.event.playerName = '😀'.repeat(16); }],
    ['event text at 512 BMP units', (request) => { request.event.text = 'E'.repeat(512); }],
    ['event text at 512 units as astral pairs', (request) => { request.event.text = '😀'.repeat(256); }],
    ['acknowledgement text at 2048 BMP units', (request) => { request.acknowledgement.text = 'A'.repeat(2048); }],
    ['acknowledgement text at 2048 units as astral pairs', (request) => { request.acknowledgement.text = '😀'.repeat(1024); }],
    ['signed-long minimum and maximum', (request) => {
      request.event.capturedAtMonotonicNanos = '-9223372036854775808';
      request.acknowledgement.dispatchedAtMonotonicNanos = '9223372036854775807';
    }],
    ['signed-long maximum and minimum', (request) => {
      request.event.capturedAtMonotonicNanos = '9223372036854775807';
      request.acknowledgement.dispatchedAtMonotonicNanos = '-9223372036854775808';
    }],
  ];

  for (const [name, mutate] of requestCases) {
    const request = structuredClone(fixtures.request);
    mutate(request);
    assert.equal(validateSchema(request), true, `${name}: schema ${JSON.stringify(validateSchema.errors)}`);
    assert.doesNotThrow(() => validateProduction(request, bindingFrom(request)), `${name}: companion production validator`);
  }

  for (const [name, text] of [
    ['message text at 1980 BMP units', 'M'.repeat(1980)],
    ['message text at 1980 units as astral pairs', '😀'.repeat(990)],
  ]) {
    const message = structuredClone(fixtures.message);
    message.text = text;
    assert.equal(validateSchema(message), true, `${name}: schema ${JSON.stringify(validateSchema.errors)}`);
    assert.doesNotThrow(() => validateProduction(message), `${name}: companion production validator`);
  }

  for (const [name, text] of [
    ['message text at 1981 BMP units', 'M'.repeat(1981)],
    ['message text at 1982 units as astral pairs', '😀'.repeat(991)],
  ]) {
    const message = structuredClone(fixtures.message);
    message.text = text;
    assert.equal(validateSchema(message), false, `${name}: schema should reject the final-text limit`);
    assert.throws(() => validateProduction(message), undefined, `${name}: companion should reject the final-text limit`);
  }
});

test('nonblank text follows the exact ECMAScript trim set', () => {
  const trimWhitespace = [
    '\u0009', '\u000a', '\u000b', '\u000c', '\u000d', '\u0020', '\u00a0', '\u1680',
    '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007',
    '\u2008', '\u2009', '\u200a', '\u2028', '\u2029', '\u202f', '\u205f', '\u3000', '\ufeff',
  ];
  for (const whitespace of trimWhitespace) {
    const request = structuredClone(fixtures.request);
    request.acknowledgement.text = whitespace;
    assert.equal(validateSchema(request), false, `U+${whitespace.codePointAt(0).toString(16)} should be blank`);
    assert.throws(() => validateProduction(request), undefined, `U+${whitespace.codePointAt(0).toString(16)} should be rejected by production`);
  }

  for (const nonWhitespace of ['\u0085', '\u180e']) {
    const request = structuredClone(fixtures.request);
    request.acknowledgement.text = nonWhitespace;
    assert.equal(nonWhitespace.trim(), nonWhitespace,
      `U+${nonWhitespace.codePointAt(0).toString(16)} is not in ECMAScript String.prototype.trim whitespace`);
    assert.equal(validateSchema(request), true);
    assert.doesNotThrow(() => validateProduction(request));
  }
});

test('strict JSON production parser rejects duplicate decoded keys at every scope', () => {
  for (const source of [
    '{"protocol":"worldspeaker.compat.v3","protocol":"worldspeaker.compat.v2"}',
    '{"event":{"serverId":"bound","\\u0073erverId":"attacker"}}',
    '{"acknowledgement":{"text":"sent","\\u0074ext":"changed"}}',
  ]) {
    assert.throws(() => parseStrictJson(source), /duplicate JSON object key/u);
  }
});

function validateProduction(frame, expectedBinding = binding) {
  switch (frame.type) {
    case 'dialogue.request':
      return production.validateDialogueRequest(frame, expectedBinding);
    case 'dialogue.ack':
      return production.validateDialogueAcknowledgement(frame);
    case 'dialogue.progress':
      return production.validateDialogueProgress(frame, fixtures.request);
    case 'dialogue.cancel':
      return production.validateDialogueCancel(frame);
    case 'dialogue.message':
    case 'dialogue.failure':
      return production.validateDialogueResponse(frame, fixtures.request);
    default:
      throw new Error('fixture frame type is unsupported');
  }
}

function setBinding(request, value) {
  for (const field of ['serverId', 'worldId', 'characterId', 'agentId']) setBindingField(request, field, value);
}

function setBindingField(request, field, value) {
  for (const target of [request, request.event, ...request.contextBatch]) target[field] = value;
}

function bindingFrom(request) {
  return {
    serverId: request.serverId,
    worldId: request.worldId,
    characterId: request.characterId,
    agentId: request.agentId,
  };
}
