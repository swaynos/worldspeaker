import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateRawSync, inflateRawSync } from 'node:zlib';
import { PRESET_ARCHIVE_NAME, readSafePresetFile } from './god-preset-filesystem.mjs';

export { PRESET_ARCHIVE_NAME };

export const MAX_ARCHIVE_BYTES = 128 * 1024;
export const MAX_EXPANDED_BYTES = 128 * 1024;

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const SOURCE_ALLOWLIST = Object.freeze([
  Object.freeze({ path: 'README.md', source: 'presets/god/README.md' }),
  Object.freeze({ path: 'persona.txt', source: 'presets/god/persona.txt' }),
  Object.freeze({ path: 'ability-policy.json', source: 'presets/god/ability-policy.json' }),
  Object.freeze({ path: 'visual-only-policy.example.json.template', source: 'presets/god/visual-only-policy.example.json.template' }),
  Object.freeze({ path: 'startup-config.json.template', source: 'presets/god/startup-config.json.template' }),
  Object.freeze({ path: 'startup-config.no-guidance-control.json.template', source: 'presets/god/startup-config.no-guidance-control.json.template' }),
  Object.freeze({ path: 'LICENSE', source: 'LICENSE' }),
  Object.freeze({ path: 'startup-config-v1.schema.json', source: 'compatibility/startup-config-v1.schema.json' }),
]);
const ARCHIVE_PATHS = Object.freeze([...SOURCE_ALLOWLIST.map(({ path }) => path), 'manifest.json'].sort());
const DEFAULT_POLICY = Object.freeze({
  schemaVersion: 1,
  revision: 0,
  grants: [],
  denials: [],
  protection: Object.freeze({ externalClaimsMode: 'REQUIRED' }),
});

export function buildGodPresetArchive({ root = repositoryRoot } = {}) {
  const entries = new Map();
  for (const item of SOURCE_ALLOWLIST) {
    const sourceBytes = readSafePresetFile(root, item.source, { errorCode: 'preset-source-not-regular' });
    entries.set(item.path, item.path === 'README.md' ? archiveReadme(sourceBytes) : sourceBytes);
  }

  validatePresetEntries(entries);
  const deploymentBytes = readSafePresetFile(root, 'compatibility/deployment.json', {
    errorCode: 'preset-deployment-not-regular',
  });
  const deployment = parseStrictJson(deploymentBytes, 'preset-deployment-invalid');
  if (typeof deployment.deploymentId !== 'string' || deployment.deploymentId.length === 0) {
    throw codedError('preset-deployment-invalid');
  }

  const manifestRows = [...entries.entries()]
    .map(([path, bytes]) => ({ path, bytes: bytes.length, sha256: sha256(bytes) }))
    .sort((left, right) => compareAscii(left.path, right.path));
  const manifest = {
    schemaVersion: 1,
    presetId: 'worldspeaker.god',
    presetVersion: 1,
    startupSchemaVersion: 1,
    deploymentId: deployment.deploymentId,
    deploymentManifestSha256: sha256(deploymentBytes),
    entries: manifestRows,
  };
  const manifestBytes = Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
  entries.set('manifest.json', manifestBytes);

  const orderedEntries = [...entries.entries()].sort(([left], [right]) => compareAscii(left, right));
  return writeZip(orderedEntries);
}

export function readGodPresetArchive(bytes) {
  const archive = Buffer.from(bytes);
  if (archive.length < 22 || archive.length > MAX_ARCHIVE_BYTES) throw codedError('preset-archive-size-invalid');
  const eocd = archive.length - 22;
  if (archive.readUInt32LE(eocd) !== 0x06054b50) throw codedError('preset-archive-eocd-invalid');
  if (archive.readUInt16LE(eocd + 4) !== 0 || archive.readUInt16LE(eocd + 6) !== 0 ||
      archive.readUInt16LE(eocd + 8) !== archive.readUInt16LE(eocd + 10) ||
      archive.readUInt16LE(eocd + 20) !== 0) throw codedError('preset-archive-eocd-invalid');

  const count = archive.readUInt16LE(eocd + 10);
  const centralSize = archive.readUInt32LE(eocd + 12);
  const centralOffset = archive.readUInt32LE(eocd + 16);
  if (count !== ARCHIVE_PATHS.length || centralOffset + centralSize !== eocd ||
      centralOffset > archive.length || centralSize > archive.length) {
    throw codedError('preset-archive-central-directory-invalid');
  }

  const entries = new Map();
  const localRecords = [];
  let cursor = centralOffset;
  let expandedTotal = 0;
  for (let index = 0; index < count; index += 1) {
    if (cursor + 46 > eocd || archive.readUInt32LE(cursor) !== 0x02014b50) {
      throw codedError('preset-archive-central-entry-invalid');
    }
    const madeBy = archive.readUInt16LE(cursor + 4);
    const needed = archive.readUInt16LE(cursor + 6);
    const flags = archive.readUInt16LE(cursor + 8);
    const method = archive.readUInt16LE(cursor + 10);
    const modifiedTime = archive.readUInt16LE(cursor + 12);
    const modifiedDate = archive.readUInt16LE(cursor + 14);
    const crc = archive.readUInt32LE(cursor + 16);
    const compressedSize = archive.readUInt32LE(cursor + 20);
    const uncompressedSize = archive.readUInt32LE(cursor + 24);
    const nameLength = archive.readUInt16LE(cursor + 28);
    const extraLength = archive.readUInt16LE(cursor + 30);
    const commentLength = archive.readUInt16LE(cursor + 32);
    const diskStart = archive.readUInt16LE(cursor + 34);
    const internalAttributes = archive.readUInt16LE(cursor + 36);
    const externalAttributes = archive.readUInt32LE(cursor + 38);
    const localOffset = archive.readUInt32LE(cursor + 42);
    const end = cursor + 46 + nameLength + extraLength + commentLength;
    if (end > eocd || !nameLength || extraLength !== 0 || commentLength !== 0 ||
        madeBy !== 0x0314 || needed !== 20 || flags !== 0 || method !== 8 ||
        modifiedTime !== 0 || modifiedDate !== 0x21 || diskStart !== 0 || internalAttributes !== 0 ||
        externalAttributes !== ((0o100644 << 16) >>> 0) || compressedSize === 0xffffffff ||
        uncompressedSize === 0xffffffff || localOffset === 0xffffffff || uncompressedSize > MAX_EXPANDED_BYTES) {
      throw codedError('preset-archive-entry-metadata-invalid');
    }

    const nameBytes = archive.subarray(cursor + 46, cursor + 46 + nameLength);
    const path = decodeUtf8(nameBytes, 'preset-archive-path-invalid');
    validateArchivePath(path);
    if (entries.has(path) || !ARCHIVE_PATHS.includes(path)) throw codedError('preset-archive-path-invalid');
    expandedTotal += uncompressedSize;
    if (expandedTotal > MAX_EXPANDED_BYTES) throw codedError('preset-archive-expanded-size-invalid');
    localRecords.push({ path, nameBytes, crc, compressedSize, uncompressedSize, localOffset, modifiedTime, modifiedDate });
    entries.set(path, null);
    cursor = end;
  }
  if (cursor !== eocd || entries.size !== ARCHIVE_PATHS.length ||
      ARCHIVE_PATHS.some((path) => !entries.has(path))) throw codedError('preset-archive-entry-set-invalid');

  let localCursor = 0;
  for (const item of localRecords) {
    if (item.localOffset !== localCursor || localCursor + 30 > centralOffset ||
        archive.readUInt32LE(localCursor) !== 0x04034b50) throw codedError('preset-archive-local-entry-invalid');
    const needed = archive.readUInt16LE(localCursor + 4);
    const flags = archive.readUInt16LE(localCursor + 6);
    const method = archive.readUInt16LE(localCursor + 8);
    const modifiedTime = archive.readUInt16LE(localCursor + 10);
    const modifiedDate = archive.readUInt16LE(localCursor + 12);
    const crc = archive.readUInt32LE(localCursor + 14);
    const compressedSize = archive.readUInt32LE(localCursor + 18);
    const uncompressedSize = archive.readUInt32LE(localCursor + 22);
    const nameLength = archive.readUInt16LE(localCursor + 26);
    const extraLength = archive.readUInt16LE(localCursor + 28);
    const nameStart = localCursor + 30;
    const dataStart = nameStart + nameLength;
    const dataEnd = dataStart + compressedSize;
    if (needed !== 20 || flags !== 0 || method !== 8 || modifiedTime !== item.modifiedTime ||
        modifiedDate !== item.modifiedDate || crc !== item.crc || compressedSize !== item.compressedSize ||
        uncompressedSize !== item.uncompressedSize || extraLength !== 0 ||
        nameLength !== item.nameBytes.length || dataEnd > centralOffset ||
        !archive.subarray(nameStart, dataStart).equals(item.nameBytes)) {
      throw codedError('preset-archive-local-entry-invalid');
    }
    const compressed = archive.subarray(dataStart, dataEnd);
    let inflated;
    try {
      const result = inflateRawSync(compressed, { maxOutputLength: MAX_EXPANDED_BYTES, info: true });
      inflated = result.buffer;
      if (result.engine.bytesWritten !== compressed.length) throw codedError('preset-archive-deflate-invalid');
    } catch {
      throw codedError('preset-archive-deflate-invalid');
    }
    if (inflated.length !== item.uncompressedSize || crc32(inflated) !== item.crc) {
      throw codedError('preset-archive-checksum-invalid');
    }
    entries.set(item.path, Buffer.from(inflated));
    localCursor = dataEnd;
  }
  if (localCursor !== centralOffset) throw codedError('preset-archive-layout-invalid');
  return entries;
}

export function verifyGodPresetArchiveBytes(bytes, { root = repositoryRoot } = {}) {
  const actualEntries = readGodPresetArchive(bytes);
  const expectedBytes = buildGodPresetArchive({ root });
  const expectedEntries = readGodPresetArchive(expectedBytes);
  if (!Buffer.from(bytes).equals(expectedBytes) || actualEntries.size !== expectedEntries.size ||
      [...expectedEntries].some(([path, expected]) => !actualEntries.get(path)?.equals(expected))) {
    throw codedError('preset-archive-stale');
  }
  return Object.freeze({
    bytes: Buffer.byteLength(bytes),
    expandedBytes: [...actualEntries.values()].reduce((sum, entry) => sum + entry.length, 0),
    sha256: sha256(Buffer.from(bytes)),
    entries: Object.freeze([...actualEntries.keys()].sort(compareAscii)),
  });
}

export function expectedGodPresetArchivePaths() {
  return [...ARCHIVE_PATHS];
}

/** Small deterministic encoder used by archive mutation tests; never used by a CLI. */
export function encodeGodPresetArchiveEntries(entries) {
  return writeZip([...entries].sort(([left], [right]) => compareAscii(left, right)));
}

function validatePresetEntries(entries) {
  if (entries.size !== SOURCE_ALLOWLIST.length) throw codedError('preset-source-set-invalid');
  const persona = decodeUtf8(entries.get('persona.txt'), 'preset-persona-utf8-invalid');
  if (Buffer.byteLength(persona, 'utf8') < 1 || Buffer.byteLength(persona, 'utf8') > 8192 ||
      persona.trim().length === 0 || hasUnsafeControls(persona)) throw codedError('preset-persona-content-invalid');

  const defaultPolicy = parseStrictJson(entries.get('ability-policy.json'), 'preset-policy-invalid');
  if (!deepEqual(defaultPolicy, DEFAULT_POLICY)) throw codedError('preset-default-policy-invalid');
  if (entries.get('ability-policy.json').includes(Buffer.from('//')) ||
      entries.get('ability-policy.json').includes(Buffer.from('/*'))) throw codedError('preset-policy-comments-invalid');

  const visual = parseStrictJson(entries.get('visual-only-policy.example.json.template'), 'preset-visual-example-invalid');
  if (visual.schemaVersion !== 1 || visual.revision !== 0 || !Array.isArray(visual.grants) || visual.grants.length !== 1 ||
      !Array.isArray(visual.denials) || visual.denials.length !== 0 ||
      !deepEqual(visual.protection, { externalClaimsMode: 'REQUIRED' })) throw codedError('preset-visual-example-invalid');
  const grant = visual.grants[0];
  const expectedGrantKeys = ['id', 'characterId', 'abilityId', 'contractVersion', 'requesterIds', 'dimensions',
    'maxRangeBlocks', 'allowVisual', 'allowDamage'].sort();
  if (!deepEqual(Object.keys(grant).sort(), expectedGrantKeys) || grant.id !== '<GRANT_ID>' ||
      grant.characterId !== '<CHARACTER_ID>' || grant.abilityId !== 'worldspeaker.core.strike_lightning' ||
      grant.contractVersion !== 1 || !deepEqual(grant.requesterIds, ['<REQUESTER_UUID>']) ||
      Object.hasOwn(grant, 'otherTargetIds') || Object.hasOwn(grant, 'maxDamage') ||
      !deepEqual(grant.dimensions, ['minecraft:overworld']) || grant.maxRangeBlocks !== 16 ||
      grant.allowVisual !== true || grant.allowDamage !== false) throw codedError('preset-visual-example-invalid');

  const main = parseStrictJson(entries.get('startup-config.json.template'), 'preset-startup-template-invalid');
  const control = parseStrictJson(entries.get('startup-config.no-guidance-control.json.template'), 'preset-control-template-invalid');
  validateStartupTemplate(main, true);
  validateStartupTemplate(control, false);
  if (main.personaFile !== '/ABSOLUTE_PRIVATE_DIRECTORY/<PERSONA_FILE>' ||
      Object.hasOwn(control, 'personaFile') || main.server.characterDisplayName !== 'God' ||
      control.server.characterDisplayName !== 'God' || !deepEqual(main.abilityPolicy, defaultPolicy) ||
      !deepEqual(control.abilityPolicy, defaultPolicy)) throw codedError('preset-startup-template-invalid');
}

function validateStartupTemplate(bundle, hasPersona) {
  const rootKeys = ['schemaVersion', 'binding', 'server', 'companion', 'abilityPolicy', 'credentialFile',
    ...(hasPersona ? ['personaFile'] : [])].sort();
  if (!deepEqual(Object.keys(bundle).sort(), rootKeys) || bundle.schemaVersion !== 1 ||
      !deepEqual(Object.keys(bundle.binding).sort(), ['agentId', 'characterId', 'serverId', 'worldId']) ||
      !deepEqual(bundle.binding, {
        serverId: '<SERVER_ID>', worldId: '<WORLD_ID>', characterId: '<CHARACTER_ID>', agentId: '<AGENT_ID>',
      }) || !deepEqual(Object.keys(bundle.server).sort(), [
        'endpoint', 'acknowledgementTemplate', 'characterDisplayName', 'characterAliases', 'characterChatStyle',
        'conversationAllowedPlayers', 'attention',
      ].sort()) || bundle.server.endpoint !== 'ws://127.0.0.1:18473' ||
      bundle.server.characterAliases.length !== 0 || bundle.server.characterChatStyle !== 'plain' ||
      bundle.server.conversationAllowedPlayers.length !== 0 || !deepEqual(bundle.server.attention, { subscriptions: [] }) ||
      !Object.keys(bundle.server.acknowledgementTemplate).length ||
      !deepEqual(Object.keys(bundle.companion).sort(), ['bindHost', 'port', 'deploymentId', 'manifestSha256'].sort()) ||
      bundle.companion.bindHost !== '127.0.0.1' || bundle.companion.port !== 18473 ||
      bundle.companion.deploymentId !== '<DEPLOYMENT_ID>' ||
      bundle.companion.manifestSha256 !== '<DEPLOYMENT_MANIFEST_SHA256>' ||
      bundle.credentialFile !== '/ABSOLUTE_PRIVATE_DIRECTORY/<CREDENTIAL_FILE>') {
    throw codedError('preset-startup-template-invalid');
  }
}

function writeZip(entries) {
  if (entries.length !== ARCHIVE_PATHS.length) throw codedError('preset-source-set-invalid');
  const local = [];
  const central = [];
  let offset = 0;
  let expanded = 0;
  for (const [path, source] of entries) {
    validateArchivePath(path);
    const content = Buffer.from(source);
    expanded += content.length;
    if (expanded > MAX_EXPANDED_BYTES || content.length > 0xffffffff) throw codedError('preset-archive-expanded-size-invalid');
    const name = Buffer.from(path, 'utf8');
    const compressed = deflateRawSync(content, { level: 9 });
    const checksum = crc32(content);
    if (name.length > 0xffff || compressed.length > 0xffffffff) throw codedError('preset-archive-size-invalid');
    const localHeader = Buffer.alloc(30);
    localHeader.writeUInt32LE(0x04034b50, 0);
    localHeader.writeUInt16LE(20, 4);
    localHeader.writeUInt16LE(0, 6);
    localHeader.writeUInt16LE(8, 8);
    localHeader.writeUInt16LE(0, 10);
    localHeader.writeUInt16LE(0x21, 12);
    localHeader.writeUInt32LE(checksum, 14);
    localHeader.writeUInt32LE(compressed.length, 18);
    localHeader.writeUInt32LE(content.length, 22);
    localHeader.writeUInt16LE(name.length, 26);
    localHeader.writeUInt16LE(0, 28);
    local.push(localHeader, name, compressed);

    const centralHeader = Buffer.alloc(46);
    centralHeader.writeUInt32LE(0x02014b50, 0);
    centralHeader.writeUInt16LE(0x0314, 4);
    centralHeader.writeUInt16LE(20, 6);
    centralHeader.writeUInt16LE(0, 8);
    centralHeader.writeUInt16LE(8, 10);
    centralHeader.writeUInt16LE(0, 12);
    centralHeader.writeUInt16LE(0x21, 14);
    centralHeader.writeUInt32LE(checksum, 16);
    centralHeader.writeUInt32LE(compressed.length, 20);
    centralHeader.writeUInt32LE(content.length, 24);
    centralHeader.writeUInt16LE(name.length, 28);
    centralHeader.writeUInt16LE(0, 30);
    centralHeader.writeUInt16LE(0, 32);
    centralHeader.writeUInt16LE(0, 34);
    centralHeader.writeUInt16LE(0, 36);
    centralHeader.writeUInt32LE((0o100644 << 16) >>> 0, 38);
    centralHeader.writeUInt32LE(offset, 42);
    central.push(centralHeader, name);
    offset += localHeader.length + name.length + compressed.length;
  }
  const centralBytes = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralBytes.length, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);
  const result = Buffer.concat([...local, centralBytes, end]);
  if (result.length > MAX_ARCHIVE_BYTES) throw codedError('preset-archive-size-invalid');
  return result;
}

function validateArchivePath(path) {
  if (typeof path !== 'string' || path.length === 0 || path.startsWith('/') || path.includes('\\') ||
      path.includes('\0') || path.split('/').some((part) => !part || part === '.' || part === '..') ||
      path.includes(':') || path.endsWith('/')) throw codedError('preset-archive-path-invalid');
}

function parseStrictJson(bytes, code) {
  const text = decodeUtf8(Buffer.from(bytes), code);
  assertStrictJsonSyntax(text, code);
  const value = JSON.parse(text);
  if (value === null || typeof value !== 'object') throw codedError(code);
  return value;
}

function assertStrictJsonSyntax(text, code) {
  let cursor = 0;
  const whitespace = () => { while (/[\u0020\u0009\u000a\u000d]/u.test(text[cursor] ?? '')) cursor += 1; };
  const stringToken = () => {
    const start = cursor;
    if (text[cursor] !== '"') throw codedError(code);
    cursor += 1;
    while (cursor < text.length) {
      const character = text[cursor++];
      if (character === '"') {
        try { return JSON.parse(text.slice(start, cursor)); }
        catch { throw codedError(code); }
      }
      if (character === '\\') {
        if (cursor >= text.length) throw codedError(code);
        cursor += 1;
      } else if (character.charCodeAt(0) < 0x20) throw codedError(code);
    }
    throw codedError(code);
  };
  const value = () => {
    whitespace();
    const character = text[cursor];
    if (character === '"') { stringToken(); return; }
    if (character === '{') {
      cursor += 1;
      whitespace();
      const keys = new Set();
      if (text[cursor] === '}') { cursor += 1; return; }
      while (true) {
        whitespace();
        const key = stringToken();
        if (keys.has(key)) throw codedError(code);
        keys.add(key);
        whitespace();
        if (text[cursor++] !== ':') throw codedError(code);
        value();
        whitespace();
        if (text[cursor] === '}') { cursor += 1; return; }
        if (text[cursor++] !== ',') throw codedError(code);
      }
    }
    if (character === '[') {
      cursor += 1;
      whitespace();
      if (text[cursor] === ']') { cursor += 1; return; }
      while (true) {
        value();
        whitespace();
        if (text[cursor] === ']') { cursor += 1; return; }
        if (text[cursor++] !== ',') throw codedError(code);
      }
    }
    const primitive = /^(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/u.exec(text.slice(cursor))?.[0];
    if (!primitive) throw codedError(code);
    cursor += primitive.length;
  };
  try {
    value();
    whitespace();
    if (cursor !== text.length) throw codedError(code);
  } catch {
    throw codedError(code);
  }
}

function archiveReadme(sourceBytes) {
  const source = decodeUtf8(sourceBytes, 'preset-readme-utf8-invalid');
  const transformed = source
    .replaceAll('(../../compatibility/startup-config-v1.schema.json)', '(startup-config-v1.schema.json)')
    .replaceAll('(../../LICENSE)', '(LICENSE)');
  return Buffer.from(transformed, 'utf8');
}

function decodeUtf8(bytes, code) {
  if (!Buffer.isBuffer(bytes)) throw codedError(code);
  if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) throw codedError(code);
  try { return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes); }
  catch { throw codedError(code); }
}

function hasUnsafeControls(text) {
  for (const character of text) {
    if (character === '\n' || character === '\r' || character === '\t') continue;
    if (/\p{Cc}/u.test(character) || (/\p{Cf}/u.test(character) && character !== '\u200c' && character !== '\u200d')) return true;
  }
  return false;
}

function deepEqual(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function compareAscii(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function codedError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

const CRC_TABLE = Uint32Array.from({ length: 256 }, (_, index) => {
  let value = index;
  for (let bit = 0; bit < 8; bit += 1) value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
  return value >>> 0;
});

function crc32(bytes) {
  let value = 0xffffffff;
  for (const byte of bytes) value = CRC_TABLE[(value ^ byte) & 0xff] ^ (value >>> 8);
  return (value ^ 0xffffffff) >>> 0;
}
