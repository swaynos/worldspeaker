import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';
import { createDialogueSchemaValidator } from '../../tools/compat/dialogue-schema-validator.mjs';

const root = resolve(process.cwd(), '..');
const fixtures = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v2-fixtures.json'), 'utf8'));
const corpus = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v2-authority-id-corpus.json'), 'utf8'));
const schema = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v2.schema.json'), 'utf8'));
const metaSchema = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v2-meta-schema.json'), 'utf8'));
const validateSchema = createDialogueSchemaValidator(schema, metaSchema);
const v4Fixtures = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v4-fixtures.json'), 'utf8'));
const v4Schema = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v4.schema.json'), 'utf8'));
const v4MetaSchema = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v4-meta-schema.json'), 'utf8'));
const validateV4Schema = createDialogueSchemaValidator(v4Schema, v4MetaSchema);

test('published dialogue schema rejects non-string values at every authority-ID path', () => {
  let testedPaths = 0;
  for (const item of corpus.authorityIdPaths) {
    for (const nonString of corpus.nonStringValues) {
      const frame = structuredClone(fixtures[item.fixture]);
      if (item.setup === 'followup') {
        frame.event.inputKind = 'followup';
        frame.conversation.replyToMessageId = '00000000-0000-4000-8000-00000000000d';
      }
      setPath(frame, item.path, structuredClone(nonString.value));
      assert.equal(validateSchema(frame), false, `${item.name} must reject ${nonString.name}`);
      testedPaths++;
    }
  }

  assert.equal(testedPaths, corpus.authorityIdPaths.length * corpus.nonStringValues.length);
});

test('v4 request and active-progress schemas reject non-string authority IDs and message identities', () => {
  const cases = [
    { fixture: 'request', path: ['event', 'playerId'] },
    { fixture: 'request', path: ['progressContext', 0, 'messageId'] },
    { fixture: 'request', path: ['progressContext', 0, 'replyAudience', 'playerId'] },
    { fixture: 'progress', path: ['requestId'] },
    { fixture: 'progress', path: ['invocationId'] },
    { fixture: 'progress', path: ['playerId'] },
    { fixture: 'progress', path: ['progress', 'messageId'] },
    { fixture: 'progress', path: ['progress', 'replyAudience', 'playerId'] },
  ];
  for (const item of cases) {
    for (const nonString of corpus.nonStringValues) {
      const frame = structuredClone(v4Fixtures[item.fixture]);
      setPath(frame, item.path, structuredClone(nonString.value));
      assert.equal(validateV4Schema(frame), false,
        `v4 ${item.fixture}.${item.path.join('.')} must reject ${nonString.name}`);
    }
  }
});

function setPath(rootValue, path, value) {
  let parent = rootValue;
  for (const segment of path.slice(0, -1)) parent = parent[segment];
  parent[path.at(-1)] = value;
}
