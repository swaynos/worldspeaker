import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';
import {
  validateDialogueAcknowledgement,
  validateDialogueCancel,
  validateDialogueRequest,
  validateDialogueResponse,
  type Binding,
  type DialogueRequest,
} from '../protocol.js';

type PathSegment = string | number;
type AuthorityIdPath = Readonly<{
  name: string;
  fixture: string;
  path: readonly PathSegment[];
  setup?: 'followup';
}>;
type NonStringValue = Readonly<{ name: string; value: unknown }>;

const root = resolve(process.cwd(), '..');
const fixtures = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v5-fixtures.json'), 'utf8')) as Record<string, unknown>;
const corpus = JSON.parse(readFileSync(resolve(root, 'compatibility/dialogue-v2-authority-id-corpus.json'), 'utf8')) as {
  nonStringValues: readonly NonStringValue[];
  authorityIdPaths: readonly AuthorityIdPath[];
};
const binding = {
  serverId: 'fixture-server',
  worldId: 'fixture-world',
  characterId: 'fixture-character',
  agentId: 'fixture-agent',
} satisfies Binding;
const validRequest = validateDialogueRequest(structuredClone(fixtures.request), binding);

test('every dialogue authority-ID path rejects arrays, objects, null, booleans, and numbers', () => {
  let testedPaths = 0;
  for (const item of corpus.authorityIdPaths) {
    for (const nonString of corpus.nonStringValues) {
      const frame = structuredClone(fixtures[item.fixture]) as Record<string, unknown>;
      if (item.setup === 'followup') {
        const event = frame.event as Record<string, unknown>;
        event.inputKind = 'followup';
        (frame.conversation as Record<string, unknown>).replyToMessageId = '00000000-0000-4000-8000-00000000000d';
      }
      setPath(frame, item.path, structuredClone(nonString.value));

      const validate = () => {
        switch (item.fixture) {
          case 'request': return validateDialogueRequest(frame, binding);
          case 'ack': return validateDialogueAcknowledgement(frame);
          case 'cancel': return validateDialogueCancel(frame);
          case 'message':
          case 'failure': return validateDialogueResponse(frame, validRequest);
          default: throw new Error(`unsupported authority-ID fixture: ${item.fixture}`);
        }
      };
      assert.throws(validate, `${item.name} must reject ${nonString.name}`);
      testedPaths++;
    }
  }

  assert.equal(testedPaths, corpus.authorityIdPaths.length * corpus.nonStringValues.length);
  assert.equal(corpus.nonStringValues.length, 5);
});

function setPath(rootValue: Record<string, unknown>, path: readonly PathSegment[], value: unknown): void {
  let parent: unknown = rootValue;
  for (const segment of path.slice(0, -1)) {
    if (Array.isArray(parent)) {
      parent = parent[segment as number];
    } else if (parent !== null && typeof parent === 'object') {
      parent = (parent as Record<string, unknown>)[String(segment)];
    } else {
      throw new Error(`authority-ID corpus path is invalid: ${path.join('.')}`);
    }
  }

  const last = path[path.length - 1];
  if (last === undefined) throw new Error('authority-ID corpus path is empty');
  if (Array.isArray(parent)) {
    parent[last as number] = value;
    return;
  }
  if (parent === null || typeof parent !== 'object') throw new Error(`authority-ID corpus path is invalid: ${path.join('.')}`);
  (parent as Record<string, unknown>)[String(last)] = value;
}
