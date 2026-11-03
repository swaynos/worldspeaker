import { createHash } from 'node:crypto';
import { normalizeAccountId } from 'openclaw/plugin-sdk/routing';
import type { Binding } from './protocol.js';

const ROUTE_IDENTITY_DOMAIN = 'worldspeaker:group-route-identity:v2';
const PEER_ID_PREFIX = 'worldspeaker-game:v2:';
const ROUTE_IDENTITY_FIELDS = ['accountId', 'serverId', 'worldId', 'characterId', 'agentId'] as const;

/**
 * Produces a group peer ID that stays distinct under the pinned SDK's
 * trim/lowercase peer normalization. The versioned lowercase-hex digest is a
 * routing identity, not an authentication or trust signal.
 */
export function worldspeakerGamePeerId(accountId: string, binding: Binding): string {
  const values = [
    normalizeAccountId(accountId),
    binding.serverId,
    binding.worldId,
    binding.characterId,
    binding.agentId,
  ];
  const hash = createHash('sha256');
  updateFrame(hash, Buffer.from(ROUTE_IDENTITY_DOMAIN, 'ascii'));

  for (let index = 0; index < ROUTE_IDENTITY_FIELDS.length; index++) {
    const field = ROUTE_IDENTITY_FIELDS[index]!;
    updateFrame(hash, Buffer.from(field, 'ascii'));
    // UTF-16LE preserves the exact JavaScript string code units, including
    // composed/decomposed Unicode and any escaped lone surrogate.
    updateFrame(hash, Buffer.from(values[index]!, 'utf16le'));
  }

  return `${PEER_ID_PREFIX}${hash.digest('hex')}`;
}

function updateFrame(hash: ReturnType<typeof createHash>, bytes: Buffer): void {
  if (bytes.length > 0xffff_ffff) throw new Error('route identity component is too large');
  const length = Buffer.alloc(4);
  length.writeUInt32BE(bytes.length);
  hash.update(length);
  hash.update(bytes);
}
