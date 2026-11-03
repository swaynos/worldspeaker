import { readFileSync } from 'node:fs';
import { buildAgentSessionKey, resolveAgentRoute } from 'openclaw/plugin-sdk/routing';
import type { OpenClawConfig } from 'openclaw/plugin-sdk/channel-core';
import { resolvePlayerRoute, handlePlayerRequest } from '../channel.js';
import type { Binding, DialogueContextTarget } from '../protocol.js';

type Presentation = Readonly<{
  displayName: string;
  aliases: readonly string[];
  chatStyle: 'plain' | 'bracketed';
}>;

type Snapshot = Readonly<{ binding: Binding; presentation: Presentation }>;
type Input = Readonly<{ before: Snapshot; after: Snapshot }>;

const input = parseInput(readFileSync(0, 'utf8'));
const cfg = {} as OpenClawConfig;
const runtime = {
  routing: { resolveAgentRoute },
} as unknown as Parameters<typeof handlePlayerRequest>[2];

function route(binding: Binding, playerId: string, accountId = 'default') {
  const account = { accountId, binding } as Parameters<typeof handlePlayerRequest>[1];
  return resolvePlayerRoute(cfg, account, runtime, request(binding, playerId));
}

const beforeA = route(input.before.binding, '00000000-0000-4000-8000-000000000041');
const beforeB = route(input.before.binding, '00000000-0000-4000-8000-000000000042');
const afterA = route(input.after.binding, '00000000-0000-4000-8000-000000000041');
const afterB = route(input.after.binding, '00000000-0000-4000-8000-000000000042');
if (beforeA.gamePeerId !== afterA.gamePeerId || beforeA.route.sessionKey !== afterA.route.sessionKey ||
    beforeA.route.sessionKey !== beforeB.route.sessionKey || afterA.route.sessionKey !== afterB.route.sessionKey) {
  throw new Error('presentation edits changed the shared SDK route');
}
if (!/^worldspeaker-game:v2:[0-9a-f]{64}$/u.test(afterA.gamePeerId)) {
  throw new Error('production route did not use the configured v2 peer identity');
}

const privateAdminSessionKey = buildAgentSessionKey({
  agentId: input.after.binding.agentId,
  channel: 'control-ui',
  accountId: 'default',
  peer: { kind: 'direct', id: 'offline-private-admin' },
});
if (afterA.route.sessionKey === privateAdminSessionKey) throw new Error('game route collapsed into private admin route');

const changedRoutes: Record<string, boolean> = {};
for (const [field, replacement] of [
  ['serverId', 'changed-server'],
  ['worldId', 'changed-world'],
  ['characterId', 'changed-character'],
  ['agentId', 'changed-agent'],
] as const) {
  const changed = { ...input.after.binding, [field]: replacement };
  const changedRoute = route(changed, '00000000-0000-4000-8000-000000000041');
  changedRoutes[field] = changedRoute.route.sessionKey !== afterA.route.sessionKey;
  if (!changedRoutes[field]) throw new Error(`${field} mutation did not change the actual SDK route`);
}
const changedAccount = route(input.after.binding, '00000000-0000-4000-8000-000000000041', 'other-account');
changedRoutes.accountId = changedAccount.route.sessionKey !== afterA.route.sessionKey;
if (!changedRoutes.accountId) throw new Error('account mutation did not change the actual SDK route');

process.stdout.write(JSON.stringify({
  kind: 'character-identity-result',
  routePrefix: 'worldspeaker-game:v2:',
  beforeSessionKey: beforeA.route.sessionKey,
  afterSessionKey: afterA.route.sessionKey,
  beforeAgentId: beforeA.route.agentId,
  afterAgentId: afterA.route.agentId,
  playersShareRoute: beforeA.route.sessionKey === beforeB.route.sessionKey &&
    afterA.route.sessionKey === afterB.route.sessionKey,
  privateAdminSeparate: afterA.route.sessionKey !== privateAdminSessionKey,
  changedRoutes,
}));

function request(binding: Binding, playerId: string): DialogueContextTarget {
  const eventId = '00000000-0000-4000-8000-000000000061';
  return {
    type: 'dialogue.request',
    protocol: 'worldspeaker.compat.v5',
    requestId: '00000000-0000-4000-8000-000000000062',
    invocationId: '00000000-0000-4000-8000-000000000063',
    abilityPolicyRevision: 0,
    ...binding,
    event: {
      ...binding,
      eventId,
      serverEpochId: '00000000-0000-4000-8000-000000000064',
      occurredAtUnixMillis: 1_793_275_200_000,
      capturedAtMonotonicNanos: '1',
      eventOrigin: 'live',
      inputKind: 'command',
      playerId,
      playerName: 'OfflinePlayer',
      dimension: 'minecraft:overworld',
      text: 'offline route fixture',
    },
    conversation: { conversationId: '00000000-0000-4000-8000-000000000065', replyToMessageId: null },
    replyAudience: { kind: 'player', playerId },
    contextBatch: [],
    contextStatus: { state: 'disabled', lossRevision: '0', reasons: [] },
    acknowledgement: null,
    progressContext: [],
  };
}

function parseInput(raw: string): Input {
  const value: unknown = JSON.parse(raw);
  if (!isRecord(value) || !sameKeys(value, ['before', 'after'])) throw new Error('identity fixture input fields are invalid');
  const before = parseSnapshot(value.before);
  const after = parseSnapshot(value.after);
  return { before, after };
}

function parseSnapshot(value: unknown): Snapshot {
  if (!isRecord(value) || !sameKeys(value, ['binding', 'presentation'])) throw new Error('identity snapshot fields are invalid');
  const binding = value.binding;
  const presentation = value.presentation;
  if (!isRecord(binding) || !sameKeys(binding, ['serverId', 'worldId', 'characterId', 'agentId']) ||
      Object.values(binding).some((field) => typeof field !== 'string')) {
    throw new Error('identity binding input is invalid');
  }
  if (!isRecord(presentation) || !sameKeys(presentation, ['displayName', 'aliases', 'chatStyle']) ||
      typeof presentation.displayName !== 'string' || !Array.isArray(presentation.aliases) ||
      presentation.aliases.some((alias) => typeof alias !== 'string') ||
      (presentation.chatStyle !== 'plain' && presentation.chatStyle !== 'bracketed')) {
    throw new Error('identity presentation input is invalid');
  }
  return {
    binding: binding as unknown as Binding,
    presentation: presentation as unknown as Presentation,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function sameKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  return Object.keys(value).sort().join(',') === [...keys].sort().join(',');
}
