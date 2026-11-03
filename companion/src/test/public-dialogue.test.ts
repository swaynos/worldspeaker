import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';
import type { ReplyPayload } from 'openclaw/plugin-sdk/reply-payload';
import { completedPublicText } from '../public-dialogue.js';

const payload = (value: Record<string, unknown>): ReplyPayload => value as unknown as ReplyPayload;
const publicBodyCorpus = JSON.parse(readFileSync(
  resolve(process.cwd(), '../compatibility/dialogue-v4-public-body-corpus.json'), 'utf8',
)) as { cases: Array<{ name: string; accepted: boolean; text: string }> };

test('accepts one complete text-only final unchanged and rejects control separators', () => {
  assert.equal(completedPublicText(payload({ text: 'A complete answer.' }), 'final'), 'A complete answer.');
  for (const text of ['Line one\r\nLine two', 'Line one\nLine two', 'Line one\rLine two', 'Line one\tmore']) {
    assert.equal(completedPublicText(payload({ text }), 'final'), null, JSON.stringify(text));
  }
});

test('uses UTF-16 limits without truncating BMP or astral text', () => {
  assert.equal(completedPublicText(payload({ text: 'x'.repeat(1980) }), 'final')?.length, 1980);
  assert.equal(completedPublicText(payload({ text: '😀'.repeat(990) }), 'final')?.length, 1980);
  const joinedEmojiAtLimit = '👩‍🚀'.repeat(396);
  assert.equal(joinedEmojiAtLimit.length, 1980);
  assert.equal(completedPublicText(payload({ text: joinedEmojiAtLimit }), 'final'), joinedEmojiAtLimit);
  assert.equal(completedPublicText(payload({ text: 'x'.repeat(1981) }), 'final'), null);
  assert.equal(completedPublicText(payload({ text: '😀'.repeat(991) }), 'final'), null);
  assert.equal(completedPublicText(payload({ text: '👩‍🚀'.repeat(397) }), 'final'), null);
});

test('rejects malformed, invisible-only, and unsafe public text', () => {
  for (const text of ['', ' \t\n ', '\ud800', '\udc00', 'visible\u0000text', 'visible\u202etext', 'visible\u00a7ctext', '\u2800', '\u115f']) {
    assert.equal(completedPublicText(payload({ text }), 'final'), null, `rejected ${JSON.stringify(text)}`);
  }
});

test('the public-body Unicode corpus is applied unchanged to real SDK final payloads', () => {
  for (const item of publicBodyCorpus.cases) {
    assert.equal(
      completedPublicText(payload({ text: item.text }), 'final'),
      item.accepted ? item.text : null,
      item.name,
    );
  }
});

test('suppresses non-final lanes, errors, private metadata, rich-only output, and supplemental content', () => {
  const cases: Array<[string, Record<string, unknown>]> = [
    ['tool/block lane', { text: 'tool output' }],
    ['reasoning', { text: 'private thought', isReasoning: true }],
    ['reasoning snapshot', { text: 'private thought', isReasoningSnapshot: true }],
    ['commentary', { text: 'pre-tool commentary', isCommentary: true }],
    ['error', { text: 'provider error', isError: true }],
    ['compaction notice', { text: 'compacting', isCompactionNotice: true }],
    ['fallback notice', { text: 'fallback', isFallbackNotice: true }],
    ['status notice', { text: 'working', isStatusNotice: true }],
    ['media', { text: 'caption', mediaUrl: 'https://example.invalid/image.png' }],
    ['structured presentation', { text: 'fallback', presentation: { blocks: [] } }],
    ['TTS supplement', { text: 'spoken', spokenText: 'supplement' }],
    ['supplemental metadata', { text: 'spoken', ttsSupplement: { spokenText: 'spoken' } }],
    ['channel payload', { text: 'answer', channelData: { private: true } }],
    ['unknown payload field', { text: 'answer', extra: false }],
  ];
  for (const [name, value] of cases) {
    assert.equal(completedPublicText(payload(value), name === 'tool/block lane' ? 'tool' : 'final'), null, name);
  }
  assert.equal(completedPublicText(payload({ text: 'not final' }), 'block'), null);
  assert.equal(completedPublicText(payload({ text: 'not final' }), 'tool'), null);
});

test('a receipt-only sentence remains text, not a semantic quality pass', () => {
  assert.equal(completedPublicText(payload({ text: 'I heard you.' }), 'final'), 'I heard you.');
});
