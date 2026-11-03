import assert from 'node:assert/strict';
import test from 'node:test';
import { worldspeakerGamePeerId } from '../route-identity.js';

const binding = {
  serverId: 'server',
  worldId: 'world',
  characterId: 'character',
  agentId: 'agent',
};

test('peer identity is a versioned safe-alphabet SHA-256 value with SDK-canonical account scope', () => {
  const peerId = worldspeakerGamePeerId('DEFAULT', binding);

  assert.match(peerId, /^worldspeaker-game:v2:[0-9a-f]{64}$/u);
  assert.equal(peerId, worldspeakerGamePeerId('default', binding));
  assert.notEqual(peerId, worldspeakerGamePeerId('other', binding));
  assert.notEqual(peerId, worldspeakerGamePeerId('default', { ...binding, worldId: 'World' }));
});
