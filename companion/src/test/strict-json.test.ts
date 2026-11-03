import assert from 'node:assert/strict';
import test from 'node:test';
import { parseStrictJson } from '../strict-json.js';

test('rejects duplicate object names including escaped-equivalent names', () => {
  assert.throws(() => parseStrictJson('{"text":"first","text":"last"}'), /duplicate JSON object key/);
  assert.throws(() => parseStrictJson('{"text":"first","\\u0074ext":"last"}'), /duplicate JSON object key/);
  assert.throws(() => parseStrictJson('{"serverId":"attacker","\\u0073erverId":"bound"}'), /duplicate JSON object key/);
  assert.throws(() => parseStrictJson('{"acknowledgement":{"text":"sent","\\u0074ext":"different"}}'), /duplicate JSON object key/);
});

test('rejects nested duplicate names but permits the same name in separate objects', () => {
  assert.throws(() => parseStrictJson('{"metadata":{"mode":1,"mode":2}}'), /duplicate JSON object key/);
  assert.deepEqual(parseStrictJson('{"left":{"mode":1},"right":{"mode":2}}'), {
    left: { mode: 1 }, right: { mode: 2 },
  });
});

test('rejects excessive nesting deterministically', () => {
  assert.throws(() => parseStrictJson(`${'['.repeat(129)}0${']'.repeat(129)}`), /nesting limit exceeded/);
});
