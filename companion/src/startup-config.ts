import { constants as fsConstants, type BigIntStats } from 'node:fs';
import { createHash } from 'node:crypto';
import { lstat, open, type FileHandle } from 'node:fs/promises';
import { isAbsolute, resolve } from 'node:path';
import { isValidAcknowledgementTemplate } from './acknowledgement-template.js';
import { AbilityPolicyError, parseAbilityPolicy, type AbilityPolicy } from './ability-policy.js';
import { isCanonicalConfigUuid } from './config-uuid.js';
import { DEPLOYMENT } from './generated/deployment.js';
import { isValidNaturallyAddressableName, validateConfiguredCharacterNames } from './natural-address-name.js';
import { isValidEndpointSyntax } from './endpoint-syntax.js';
import { parseStrictJson } from './strict-json.js';
import {
  formatPrivateConfigurationProblem,
  legacyBootstrapProblem,
  personaInactiveProblem,
  privateConfigurationProblem,
  privateFileProblem,
  unknownPrivateConfigurationKey,
  type PrivateConfigurationProblem,
} from './private-configuration-problem.js';

export const MAX_STARTUP_BUNDLE_BYTES = 32_768;
export const MAX_STARTUP_CREDENTIAL_BYTES = 86;
export const MAX_STARTUP_REFERENCE_UNITS = 1_024;

const ID = /^[A-Za-z0-9._:-]{1,128}$/u;
const DIMENSION = /^[a-z0-9_.-]+:[a-z0-9/._-]+$/u;
const BASE64URL = /^[A-Za-z0-9_-]{43,86}$/u;

export type StartupConfigErrorCode = 'invalid-reference' | 'unsafe-file' | 'invalid-content' |
  'invalid-schema' | 'persona-publication-unqualified';

const ERROR_TEXT: Readonly<Record<StartupConfigErrorCode, string>> = Object.freeze({
  'invalid-reference': 'WS-COMPAT-CONFIG-REFERENCE-INVALID: use one canonical absolute local configFile path and restart.',
  'unsafe-file': 'WS-COMPAT-CONFIG-FILE-UNSAFE: referenced files must be service-owned 0600 regular single-link files in dedicated 0700 directories with trusted ancestors.',
  'invalid-content': 'WS-COMPAT-CONFIG-CONTENT-INVALID: startup bundle or credential content is malformed, oversized, or noncanonical.',
  'invalid-schema': 'WS-COMPAT-CONFIG-SCHEMA-INVALID: correct the existing schemaVersion 1 startup bundle and restart.',
  'persona-publication-unqualified': 'WS-COMPAT-PERSONA-PUBLICATION-CONTROL-UNQUALIFIED: configured persona remains inactive until a qualified private-prose publication control exists.',
});

export class StartupConfigError extends Error {
  readonly code: StartupConfigErrorCode;
  readonly problem: PrivateConfigurationProblem | null;
  constructor(code: StartupConfigErrorCode, problem: PrivateConfigurationProblem | null = null) {
    super(problem === null ? ERROR_TEXT[code] : formatPrivateConfigurationProblem(problem));
    this.name = 'StartupConfigError';
    this.code = code;
    this.problem = problem;
  }
}

export type StartupConfigSnapshot = Readonly<{
  binding: Readonly<{ serverId: string; worldId: string; characterId: string; agentId: string }>;
  server: Readonly<Record<string, unknown>>;
  companion: Readonly<{ bindHost: '127.0.0.1'; port: number; deploymentId: string; manifestSha256: string }>;
  abilityPolicy: AbilityPolicy;
  configFile: string;
  credentialFile: string;
  configSetSha256: string;
  bearerSecret: string;
  personaFile?: string;
}>;

type DirectoryIdentity = Readonly<{ path: string; dev: bigint; ino: bigint; uid: bigint; mode: bigint }>;
type FileIdentity = Readonly<{
  path: string; dev: bigint; ino: bigint; uid: bigint; mode: bigint; size: bigint; nlink: bigint; mtimeNs: bigint; ctimeNs: bigint;
}>;
type PathCheck = Readonly<{ directories: readonly DirectoryIdentity[]; file: FileIdentity }>;

/** Load and validate one bundle and credential as an asynchronous, immutable startup snapshot. */
export async function loadStartupConfig(configFile: string): Promise<StartupConfigSnapshot> {
  const reference = canonicalReference(configFile);
  const bytes = await readPrivateFile(reference, MAX_STARTUP_BUNDLE_BYTES, 'configFile');
  const bundle = parseBundle(decodeUtf8(bytes, 'startupBundle', 'v1Bundle'));
  if (bundle.personaFile !== undefined) {
    // The known CHAR-02 production publication blocker is a hard startup stop.
    throw new StartupConfigError('persona-publication-unqualified', personaInactiveProblem());
  }

  const credentialFile = canonicalReference(bundle.credentialFile, 'credentialFile');
  const credentialBytes = await readPrivateFile(credentialFile, MAX_STARTUP_CREDENTIAL_BYTES, 'credentialFile');
  const bearerSecret = decodeUtf8(credentialBytes, 'credentialContent', 'credentialContent');
  if (!BASE64URL.test(bearerSecret)) throw invalid('credentialContent', 'credentialContent');
  const credential = Buffer.from(bearerSecret, 'base64url');
  if (credential.length < 32 || credential.length > 64 || credential.toString('base64url') !== bearerSecret) {
    throw invalid('credentialContent', 'credentialContent');
  }

  const snapshot: StartupConfigSnapshot = {
    binding: deepFreeze({ ...bundle.binding }),
    server: deepFreeze({ ...bundle.server }),
    companion: deepFreeze({ ...bundle.companion }),
    abilityPolicy: bundle.abilityPolicy,
    configFile: reference,
    credentialFile,
    configSetSha256: createHash('sha256').update(bytes).digest('hex'),
    bearerSecret,
  };
  return deepFreeze(snapshot);
}

type ParsedBundle = Readonly<{
  binding: StartupConfigSnapshot['binding'];
  server: Readonly<Record<string, unknown>>;
  companion: StartupConfigSnapshot['companion'];
  abilityPolicy: AbilityPolicy;
  credentialFile: string;
  personaFile?: string;
}>;

function parseBundle(source: string): ParsedBundle {
  let value: unknown;
  try { value = parseStrictJson(source, 8); }
  catch { throw invalid('startupBundle', 'v1Bundle', 'invalid-content'); }
  try {
    const bundle = object(value, 'startupBundle', 'v1Bundle');
    exactWithOptional(bundle, ['schemaVersion', 'binding', 'server', 'companion', 'abilityPolicy', 'credentialFile'], ['personaFile']);
    if (bundle.schemaVersion !== 1) throw invalid('schemaVersion', 'schemaVersion1');

    const bindingObject = object(bundle.binding, 'startupBundle', 'v1Bundle');
    exactWithOptional(bindingObject, [], ['serverId', 'worldId', 'characterId', 'agentId']);
    const binding = Object.freeze({
      serverId: bindingId(bindingObject.serverId, 'bindingServerId'),
      worldId: bindingId(bindingObject.worldId, 'bindingWorldId'),
      characterId: bindingId(bindingObject.characterId, 'bindingCharacterId'),
      agentId: bindingId(bindingObject.agentId, 'bindingAgentId'),
    });

    const companionObject = object(bundle.companion, 'startupBundle', 'v1Bundle');
    exactWithOptional(companionObject, [], ['bindHost', 'port', 'deploymentId', 'manifestSha256']);
    if (companionObject.bindHost !== '127.0.0.1') throw invalid('companionBindHost', 'loopbackHost');
    if (!boundedInteger(companionObject.port, 1024, 65_535)) throw invalid('companionPort', 'port');
    if (companionObject.deploymentId !== DEPLOYMENT.deploymentId) throw invalid('companionDeploymentId', 'installedDeployment');
    if (companionObject.manifestSha256 !== DEPLOYMENT.manifestSha256) throw invalid('companionManifestHash', 'installedDeployment');
    const companion = Object.freeze({
      bindHost: '127.0.0.1' as const,
      port: companionObject.port as number,
      deploymentId: string(companionObject.deploymentId, 'companionDeploymentId', 'installedDeployment'),
      manifestSha256: string(companionObject.manifestSha256, 'companionManifestHash', 'installedDeployment'),
    });

    const server = validateServerSettings(bundle.server);
    let abilityPolicy: AbilityPolicy;
    try { abilityPolicy = parseAbilityPolicy(bundle.abilityPolicy, binding.characterId); }
    catch (error) {
      if (error instanceof AbilityPolicyError) throw new StartupConfigError('invalid-schema', error.problem);
      throw invalid('abilityPolicy', 'boundedAbilityPolicy');
    }
    const credentialFile = canonicalReference(string(bundle.credentialFile, 'credentialFile', 'canonicalLocalReference'), 'credentialFile');
    const personaFile = Object.hasOwn(bundle, 'personaFile')
      ? canonicalReference(string(bundle.personaFile, 'personaFile', 'canonicalLocalReference'), 'personaFile')
      : undefined;
    return Object.freeze({ binding, server, companion, abilityPolicy, credentialFile, ...(personaFile === undefined ? {} : { personaFile }) });
  } catch (error) {
    if (error instanceof StartupConfigError) throw error;
    if (error instanceof AbilityPolicyError) throw new StartupConfigError('invalid-schema', error.problem);
    throw invalid('startupBundle', 'v1Bundle');
  }
}

function validateServerSettings(value: unknown): Readonly<Record<string, unknown>> {
  const server = object(value, 'startupBundle', 'v1Bundle');
  const required = ['endpoint', 'acknowledgementTemplate', 'characterDisplayName', 'conversationAllowedPlayers'];
  const optional = ['characterAliases', 'followupWindowSeconds', 'executionBudgetSeconds', 'characterChatStyle', 'attention'];
  exactWithOptional(server, [], [...required, ...optional]);
  if (!Object.hasOwn(server, 'endpoint')) throw invalid('serverEndpoint', 'strictEndpoint');
  if (!Object.hasOwn(server, 'acknowledgementTemplate')) throw invalid('serverAcknowledgement', 'acknowledgement');
  if (!Object.hasOwn(server, 'characterDisplayName')) throw invalid('serverDisplayName', 'displayName');
  if (!Object.hasOwn(server, 'conversationAllowedPlayers')) throw invalid('serverAllowedPlayers', 'uuidList');
  if (!isValidEndpointSyntax(server.endpoint)) throw invalid('serverEndpoint', 'strictEndpoint');
  const acknowledgement = string(server.acknowledgementTemplate, 'serverAcknowledgement', 'acknowledgement');
  if (!isValidAcknowledgementTemplate(acknowledgement)) throw invalid('serverAcknowledgement', 'acknowledgement');
  const displayName = string(server.characterDisplayName, 'serverDisplayName', 'displayName');
  if (!isValidNaturallyAddressableName(displayName)) throw invalid('serverDisplayName', 'displayName');
  if (server.characterAliases !== undefined && !Array.isArray(server.characterAliases)) throw invalid('serverAliases', 'aliases');
  const configuredNames = validateConfiguredCharacterNames(displayName,
    server.characterAliases === undefined ? [] : server.characterAliases);
  if (configuredNames === null) throw invalid('serverAliases', 'aliases');
  if (!Array.isArray(server.conversationAllowedPlayers) || server.conversationAllowedPlayers.length > 32 ||
      server.conversationAllowedPlayers.some((id) => !isCanonicalConfigUuid(id)) ||
      new Set(server.conversationAllowedPlayers).size !== server.conversationAllowedPlayers.length) throw invalid('serverAllowedPlayers', 'uuidList');
  if (server.followupWindowSeconds !== undefined && !boundedInteger(server.followupWindowSeconds, 0, 30)) throw invalid('serverFollowupWindow', 'followupWindow');
  if (server.executionBudgetSeconds !== undefined && !boundedInteger(server.executionBudgetSeconds, 1, 30)) throw invalid('serverExecutionBudget', 'executionBudget');
  if (server.characterChatStyle !== undefined && server.characterChatStyle !== 'plain' && server.characterChatStyle !== 'bracketed') {
    throw invalid('serverChatStyle', 'chatStyle');
  }
  if (server.attention !== undefined) validateAttention(server.attention);
  return deepFreeze({ ...server, ...(server.characterAliases === undefined ? {} : { characterAliases: [...configuredNames.aliases] }) });
}

function validateAttention(value: unknown): void {
  const attention = object(value, 'serverAttention', 'attentionObject');
  exactWithOptional(attention, [], ['subscriptions', 'maxEvents', 'maxAgeSeconds', 'maxBytes', 'maxEventsPerSecond', 'maxEventsPerPlayerSecond']);
  const subscriptions = Object.hasOwn(attention, 'subscriptions') ? attention.subscriptions : [];
  const supported = new Set(['player_connection', 'player_dimension', 'player_death', 'player_respawn', 'player_movement']);
  if (!Array.isArray(subscriptions) || subscriptions.length > 5 || subscriptions.some((item) => typeof item !== 'string' || !supported.has(item)) ||
      new Set(subscriptions).size !== subscriptions.length) throw invalid('attentionSubscriptions', 'subscriptions');
  const maxEvents = Object.hasOwn(attention, 'maxEvents') ? attention.maxEvents : 32;
  const maxAgeSeconds = Object.hasOwn(attention, 'maxAgeSeconds') ? attention.maxAgeSeconds : 30;
  const maxBytes = Object.hasOwn(attention, 'maxBytes') ? attention.maxBytes : 8192;
  const maxEventsPerSecond = Object.hasOwn(attention, 'maxEventsPerSecond') ? attention.maxEventsPerSecond : 16;
  const maxEventsPerPlayerSecond = Object.hasOwn(attention, 'maxEventsPerPlayerSecond') ? attention.maxEventsPerPlayerSecond : 4;
  if (!boundedInteger(maxEvents, 1, 64)) throw invalid('attentionMaxEvents', 'integer1To64');
  if (!boundedInteger(maxAgeSeconds, 1, 60)) throw invalid('attentionMaxAge', 'integer1To60');
  if (!boundedInteger(maxBytes, 1024, 8192)) throw invalid('attentionMaxBytes', 'integer1024To8192');
  if (!boundedInteger(maxEventsPerSecond, 1, 32)) throw invalid('attentionMaxEventsPerSecond', 'integer1To32');
  if (!boundedInteger(maxEventsPerPlayerSecond, 1, 8) || maxEventsPerPlayerSecond > maxEventsPerSecond) {
    throw invalid('attentionMaxEventsPerPlayerSecond', 'integer1To8AtMostGlobal');
  }
}

function canonicalReference(value: string, field: 'configFile' | 'credentialFile' | 'personaFile' = 'configFile'): string {
  if (typeof value !== 'string' || value.length < 1 || value.length > MAX_STARTUP_REFERENCE_UNITS || !isAbsolute(value) || resolve(value) !== value ||
      value.includes('://') || /[\u0000-\u001f\u007f-\u009f]/u.test(value)) {
    throw invalid(field, 'canonicalLocalReference', 'invalid-reference');
  }
  return value;
}

async function readPrivateFile(filePath: string, maximum: number, kind: 'configFile' | 'credentialFile'): Promise<Buffer> {
  const isCredential = kind === 'credentialFile';
  const expected = isCredential ? 'privateCredentialFile' : 'privateBundleFile';
  const action = isCredential ? 'repairCredentialFileTs' : 'repairBundleFileTs';
  let handle: FileHandle | undefined;
  try {
    assertFileSystemSupport();
    const before = await inspectPath(filePath);
    if (before.file.size < 1n || before.file.size > BigInt(maximum)) {
      throw new StartupConfigError('invalid-content', privateFileProblem(kind, expected, action));
    }
    handle = await open(filePath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW | fsConstants.O_NONBLOCK);
    const opened = await handle.stat({ bigint: true }) as BigIntStats;
    assertFile(opened);
    if (!sameFile(before.file, opened)) throw new StartupConfigError('unsafe-file');

    const bytes = Buffer.alloc(maximum + 1);
    let length = 0;
    while (length < bytes.length) {
      const result = await handle.read(bytes, length, bytes.length - length, null);
      if (result.bytesRead === 0) break;
      length += result.bytesRead;
    }
    if (length < 1 || length > maximum || BigInt(length) !== opened.size) throw new StartupConfigError('invalid-content');

    const afterDescriptor = await handle.stat({ bigint: true }) as BigIntStats;
    const afterPath = await inspectPath(filePath);
    if (!sameFile(before.file, afterDescriptor) || !sameFile(before.file, afterPath.file) ||
        !sameDirectories(before.directories, afterPath.directories)) throw new StartupConfigError('unsafe-file');
    return bytes.subarray(0, length);
  } catch (error) {
    if (error instanceof StartupConfigError && error.problem !== null) throw error;
    if (error instanceof StartupConfigError && error.code === 'invalid-content') {
      throw new StartupConfigError('invalid-content', privateFileProblem(kind, expected, action));
    }
    throw new StartupConfigError('unsafe-file', privateFileProblem(kind, expected, action));
  } finally {
    try { await handle?.close(); } catch { throw new StartupConfigError('unsafe-file', privateFileProblem(kind, expected, action)); }
  }
}

async function inspectPath(filePath: string): Promise<PathCheck> {
  const serviceUid = BigInt(process.getuid!());
  const segments = filePath.split('/').filter(Boolean);
  if (segments.length < 2) throw new StartupConfigError('unsafe-file');
  const root = await lstat('/', { bigint: true }) as BigIntStats;
  if (root.isSymbolicLink() || !root.isDirectory() || root.uid !== 0n && root.uid !== serviceUid || (root.mode & 0o022n) !== 0n) {
    throw new StartupConfigError('unsafe-file');
  }
  const directories: DirectoryIdentity[] = [{ path: '/', dev: root.dev, ino: root.ino, uid: root.uid, mode: root.mode & 0o7777n }];
  let current = '/';
  for (const segment of segments.slice(0, -1)) {
    current = current === '/' ? `/${segment}` : `${current}/${segment}`;
    const stat = await lstat(current, { bigint: true }) as BigIntStats;
    if (stat.isSymbolicLink() || !stat.isDirectory()) throw new StartupConfigError('unsafe-file');
    const mode = stat.mode & 0o7777n;
    const directParent = current === resolve(filePath, '..');
    if (directParent) {
      if (stat.uid !== serviceUid || mode !== 0o700n) throw new StartupConfigError('unsafe-file');
    } else if ((stat.uid !== 0n && stat.uid !== serviceUid) || (mode & 0o022n) !== 0n) {
      throw new StartupConfigError('unsafe-file');
    }
    directories.push({ path: current, dev: stat.dev, ino: stat.ino, uid: stat.uid, mode });
  }
  const fileStat = await lstat(filePath, { bigint: true }) as BigIntStats;
  assertFile(fileStat);
  return { directories, file: fileIdentity(filePath, fileStat) };
}

function assertFile(stat: BigIntStats): void {
  const serviceUid = BigInt(process.getuid!());
  if (!stat.isFile() || stat.isSymbolicLink() || stat.uid !== serviceUid ||
      (stat.mode & 0o7777n) !== 0o600n || stat.nlink !== 1n) throw new StartupConfigError('unsafe-file');
}

function fileIdentity(path: string, stat: BigIntStats): FileIdentity {
  return { path, dev: stat.dev, ino: stat.ino, uid: stat.uid, mode: stat.mode & 0o7777n,
    size: stat.size, nlink: stat.nlink, mtimeNs: stat.mtimeNs, ctimeNs: stat.ctimeNs };
}

function sameFile(left: FileIdentity, right: FileIdentity | BigIntStats): boolean {
  const rightFile = 'path' in right ? right : fileIdentity(left.path, right);
  return left.path === rightFile.path && left.dev === rightFile.dev && left.ino === rightFile.ino &&
    left.uid === rightFile.uid && left.mode === rightFile.mode && left.size === rightFile.size &&
    left.nlink === rightFile.nlink && left.mtimeNs === rightFile.mtimeNs && left.ctimeNs === rightFile.ctimeNs;
}

function sameDirectories(left: readonly DirectoryIdentity[], right: readonly DirectoryIdentity[]): boolean {
  return left.length === right.length && left.every((entry, index) => {
    const candidate = right[index];
    return candidate !== undefined && entry.path === candidate.path && entry.dev === candidate.dev &&
      entry.ino === candidate.ino && entry.uid === candidate.uid && entry.mode === candidate.mode;
  });
}

function assertFileSystemSupport(): void {
  if (typeof process.getuid !== 'function' || typeof fsConstants.O_NOFOLLOW !== 'number' || typeof fsConstants.O_NONBLOCK !== 'number') {
    throw new StartupConfigError('unsafe-file');
  }
}

function decodeUtf8(bytes: Buffer, field: 'startupBundle' | 'credentialContent', expected: 'v1Bundle' | 'credentialContent'): string {
  if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) throw invalid(field, expected, 'invalid-content');
  try { return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes); }
  catch { throw invalid(field, expected, 'invalid-content'); }
}

function bindingId(value: unknown, field: 'bindingServerId' | 'bindingWorldId' | 'bindingCharacterId' | 'bindingAgentId'): string {
  if (typeof value !== 'string' || !ID.test(value)) throw invalid(field, 'asciiIdentifier');
  return value;
}

function boundedInteger(value: unknown, minimum: number, maximum: number): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum && value <= maximum;
}

function string(value: unknown,
  field: Parameters<typeof privateConfigurationProblem>[0] = 'startupBundle',
  expected: Parameters<typeof privateConfigurationProblem>[1] = 'v1Bundle'): string {
  if (typeof value !== 'string') throw invalid(field, expected);
  return value;
}

function object(value: unknown,
  field: Parameters<typeof privateConfigurationProblem>[0] = 'startupBundle',
  expected: Parameters<typeof privateConfigurationProblem>[1] = 'v1Bundle'): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw invalid(field, expected);
  return value as Record<string, unknown>;
}

function exactWithOptional(value: Record<string, unknown>, required: readonly string[], optional: readonly string[]): void {
  const allowed = new Set([...required, ...optional]);
  const keys = Object.keys(value);
  if (keys.some((key) => !allowed.has(key))) throw new StartupConfigError('invalid-schema', unknownPrivateConfigurationKey());
  if (required.some((key) => !Object.hasOwn(value, key))) throw new Error('fields');
}

function invalid(field: Parameters<typeof privateConfigurationProblem>[0],
  expected: Parameters<typeof privateConfigurationProblem>[1], code: StartupConfigErrorCode = 'invalid-schema'): StartupConfigError {
  return new StartupConfigError(code, privateConfigurationProblem(field, expected));
}

function deepFreeze<T>(value: T): T {
  if (value !== null && typeof value === 'object' && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const child of Object.values(value as Record<string, unknown>)) deepFreeze(child);
  }
  return value;
}
