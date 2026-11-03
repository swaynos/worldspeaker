import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';
import {
  isEcmaScriptBlank,
  isNonNegativeSafeInteger,
  isNonNegativeSafeJsonIntegerToken,
} from '../protocol-text.js';
import { validateDialogueRequest, validateDialogueResponse, type Binding } from '../protocol.js';
import { parseStrictJson } from '../strict-json.js';

type CorpusCase = Readonly<{
  name: string;
  kind: 'request' | 'response';
  accepted: boolean;
  rawJson: string;
}>;

const corpus = JSON.parse(readFileSync(
  resolve(process.cwd(), '../compatibility/dialogue-v2-qa-002-corpus.json'), 'utf8',
)) as Readonly<{ cases: readonly CorpusCase[] }>;
const binding: Binding = {
  serverId: 'fixture-server',
  worldId: 'fixture-world',
  characterId: 'fixture-character',
  agentId: 'fixture-agent',
};

const validRequestCase = corpus.cases.find((item) => item.kind === 'request' && item.accepted);
if (!validRequestCase) throw new Error('QA-002 raw corpus is missing a valid request case');
const validatedRequest = validateDialogueRequest(parseStrictJson(adaptV2CorpusFrame(validRequestCase.rawJson)), binding);

test('the retained CONV-04 raw JSON corpus has the same outcomes inside the v5 envelope', () => {
  for (const item of corpus.cases) {
    const validate = (): unknown => {
      const frame = parseStrictJson(adaptV2CorpusFrame(item.rawJson));
      return item.kind === 'request'
        ? validateDialogueRequest(frame, binding)
        : validateDialogueResponse(frame, validatedRequest);
    };

    const acceptedBeforeV5 = item.accepted && !(item.kind === 'response' && item.name === 'U+0085-only answer text is not ECMAScript blank');
    if (acceptedBeforeV5) assert.doesNotThrow(validate, item.name);
    else assert.throws(validate, item.name);
  }
});

test('the retained v2 fixture is rejected without protocol downgrade', () => {
  const oldFixture = JSON.parse(readFileSync(
    resolve(process.cwd(), '../compatibility/dialogue-v2-fixtures.json'), 'utf8',
  )).request;
  assert.throws(() => validateDialogueRequest(oldFixture, binding));
});

test('TypeScript predicates mirror ECMAScript trim and reject numeric coercion', () => {
  const ecmaWhitespace = '\u0009\u000a\u000b\u000c\u000d\u0020\u00a0\u1680' +
    '\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a' +
    '\u2028\u2029\u202f\u205f\u3000\ufeff';
  assert.equal(isEcmaScriptBlank(ecmaWhitespace), true);
  assert.equal(isEcmaScriptBlank(''), true);
  assert.equal(isEcmaScriptBlank('\u0085'), false);
  assert.equal(isEcmaScriptBlank('\u180e'), false);
  assert.equal(isEcmaScriptBlank(' x '), false);

  assert.equal(isNonNegativeSafeInteger(0), true);
  assert.equal(isNonNegativeSafeInteger(Number.MAX_SAFE_INTEGER), true);
  assert.equal(isNonNegativeSafeInteger(-0), true);
  assert.equal(isNonNegativeSafeInteger(1.5), false);
  assert.equal(isNonNegativeSafeInteger(Number.MAX_SAFE_INTEGER + 1), false);
  assert.equal(isNonNegativeSafeInteger(Number.NaN), false);
  assert.equal(isNonNegativeSafeInteger(Number.POSITIVE_INFINITY), false);
  assert.equal(isNonNegativeSafeInteger('1'), false);
  assert.equal(isNonNegativeSafeInteger(true), false);

  assert.equal(isNonNegativeSafeJsonIntegerToken('1.0'), true);
  assert.equal(isNonNegativeSafeJsonIntegerToken('1e0'), true);
  assert.equal(isNonNegativeSafeJsonIntegerToken('1.00000000000000001'), false);
  assert.equal(isNonNegativeSafeJsonIntegerToken('9007199254740991.4'), false);
  assert.equal(isNonNegativeSafeJsonIntegerToken('-0'), true);
});

function adaptV2CorpusFrame(rawJson: string): string {
  const v5 = rawJson.replaceAll('worldspeaker.compat.v2', 'worldspeaker.compat.v5')
    .replace('"contextBatch":[],"acknowledgement":',
      '"contextBatch":[],"contextStatus":{"state":"disabled","lossRevision":"0","reasons":[]},"acknowledgement":');
  if (!v5.includes('"type":"dialogue.request"')) return v5;
  const withRevision = v5.replace(/("invocationId":"[^"]+",)/u, '$1"abilityPolicyRevision":0,');
  return withRevision.replace(/}$/, ',"progressContext":[]}');
}
