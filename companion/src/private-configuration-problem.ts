export const PRIVATE_CONFIGURATION_CODES = Object.freeze({
  fieldInvalid: 'WS-COMPAT-CONFIG-FIELD-INVALID',
  unknownKey: 'WS-COMPAT-CONFIG-UNKNOWN-KEY',
  legacyBootstrap: 'WS-COMPAT-CONFIG-LEGACY-BOOTSTRAP',
  privateFileInvalid: 'WS-COMPAT-CONFIG-PRIVATE-FILE-INVALID',
  personaPublicationUnqualified: 'WS-COMPAT-PERSONA-PUBLICATION-CONTROL-UNQUALIFIED',
} as const);

export const PRIVATE_CONFIGURATION_FIELDS = Object.freeze({
  bootstrap: 'bootstrap',
  startupBundle: 'startup.bundle',
  schemaVersion: 'schemaVersion',
  configFile: 'configFile',
  credentialFile: 'credentialFile',
  credentialContent: 'credentialFile content',
  unknownKey: 'unknown-key',
  bindingServerId: 'binding.serverId',
  bindingWorldId: 'binding.worldId',
  bindingCharacterId: 'binding.characterId',
  bindingAgentId: 'binding.agentId',
  companionBindHost: 'companion.bindHost',
  companionPort: 'companion.port',
  companionDeploymentId: 'companion.deploymentId',
  companionManifestHash: 'companion.manifestSha256',
  serverEndpoint: 'server.endpoint',
  serverAcknowledgement: 'server.acknowledgementTemplate',
  serverDisplayName: 'server.characterDisplayName',
  serverAllowedPlayers: 'server.conversationAllowedPlayers',
  serverAliases: 'server.characterAliases',
  serverFollowupWindow: 'server.followupWindowSeconds',
  serverExecutionBudget: 'server.executionBudgetSeconds',
  serverChatStyle: 'server.characterChatStyle',
  serverAttention: 'server.attention',
  attentionSubscriptions: 'server.attention.subscriptions',
  attentionMaxEvents: 'server.attention.maxEvents',
  attentionMaxAge: 'server.attention.maxAgeSeconds',
  attentionMaxBytes: 'server.attention.maxBytes',
  attentionMaxEventsPerSecond: 'server.attention.maxEventsPerSecond',
  attentionMaxEventsPerPlayerSecond: 'server.attention.maxEventsPerPlayerSecond',
  abilityPolicy: 'abilityPolicy',
  abilityPolicySchemaVersion: 'abilityPolicy.schemaVersion',
  abilityPolicyRevision: 'abilityPolicy.revision',
  abilityPolicyGrants: 'abilityPolicy.grants',
  abilityPolicyDenials: 'abilityPolicy.denials',
  abilityDenialAbilityId: 'abilityPolicy.denials[].abilityId',
  abilityGrantAbilityId: 'abilityPolicy.grants[].abilityId',
  abilityPolicyProtection: 'abilityPolicy.protection',
  abilityExternalClaimsMode: 'abilityPolicy.protection.externalClaimsMode',
  personaFile: 'personaFile',
  nodeRuntime: 'Node.js runtime',
} as const);

export const PRIVATE_CONFIGURATION_EXPECTED = Object.freeze({
  versionedBootstrap: 'schemaVersion=1 and one configFile reference',
  v1Bundle: 'exact schemaVersion 1 JSON bundle',
  schemaVersion1: 'integer 1',
  canonicalLocalReference: 'canonical absolute local path',
  privateBootstrapFile: '0600 regular single-link file, 0700 parent, 1..4096 bytes',
  privateBundleFile: '0600 regular single-link file, 0700 parent, 1..32768 bytes',
  privateCredentialFile: '0600 regular single-link file, 0700 parent, 1..86 bytes',
  privateReaderRuntime: 'pinned private-file helper with Node.js 24.22.1',
  credentialContent: 'canonical unpadded base64url encoding of 32..64 bytes',
  noUnknownKeys: 'only fields defined by startup schema v1',
  asciiIdentifier: 'ASCII string [A-Za-z0-9._:-], 1..128 characters',
  loopbackHost: 'literal 127.0.0.1',
  port: 'integer 1024..65535',
  installedDeployment: 'installed deployment identifier and manifest digest',
  strictEndpoint: 'strict ws/wss endpoint syntax, 1..2048 printable ASCII characters',
  acknowledgement: 'visible safe text, at most 256 units, optional {player}',
  displayName: 'visible naturally addressable single-line name, 1..48 units',
  uuidList: 'array of at most 32 unique canonical UUID strings',
  aliases: 'at most eight unique visible naturally addressable names, 1..48 units each',
  followupWindow: 'integer 0..30',
  executionBudget: 'integer 1..30',
  chatStyle: 'plain or bracketed',
  attentionObject: 'bounded attention object with known fields only',
  subscriptions: 'up to five unique supported subscription strings',
  integer1To64: 'integer 1..64',
  integer1To60: 'integer 1..60',
  integer1024To8192: 'integer 1024..8192',
  integer1To32: 'integer 1..32',
  integer1To8AtMostGlobal: 'integer 1..8 and no greater than maxEventsPerSecond',
  abilityIdV1: 'known observe_player or strike_lightning ability v1',
  boundedAbilityPolicy: 'bounded schemaVersion 1 ability policy with known fields',
  personaInactive: 'persona publication control remains unqualified',
} as const);

export const PRIVATE_CONFIGURATION_ACTIONS = Object.freeze({
  correctV1Field: 'correct this field in the v1 bundle and coordinate a restart',
  removeUnknownKeys: 'remove unknown fields from the v1 bundle and restart',
  migrateLegacyBootstrap: 'migrate inline legacy properties privately to a v1 bundle and restart',
  repairBootstrapFile: 'repair private bootstrap file; helper requires Node.js 24.22.1; restart',
  repairBundleFile: 'repair private startup bundle file; helper requires Node.js 24.22.1; restart',
  repairCredentialFile: 'repair private credential file; helper requires Node.js 24.22.1; restart',
  repairBundleFileTs: 'repair the private startup bundle file and restart',
  repairCredentialFileTs: 'repair the private credential file and restart',
  installNodeRuntime: 'provide Node.js 24.22.1 on the trusted service PATH and restart',
  keepPersonaInactive: 'keep persona inactive until qualified private-prose publication control exists',
} as const);

export type PrivateConfigurationProblem = Readonly<{
  code: typeof PRIVATE_CONFIGURATION_CODES[keyof typeof PRIVATE_CONFIGURATION_CODES];
  field: typeof PRIVATE_CONFIGURATION_FIELDS[keyof typeof PRIVATE_CONFIGURATION_FIELDS];
  expected: typeof PRIVATE_CONFIGURATION_EXPECTED[keyof typeof PRIVATE_CONFIGURATION_EXPECTED];
  action: typeof PRIVATE_CONFIGURATION_ACTIONS[keyof typeof PRIVATE_CONFIGURATION_ACTIONS];
}>;

type FieldKey = keyof typeof PRIVATE_CONFIGURATION_FIELDS;
type ExpectedKey = keyof typeof PRIVATE_CONFIGURATION_EXPECTED;
type ActionKey = keyof typeof PRIVATE_CONFIGURATION_ACTIONS;

export function privateConfigurationProblem(field: FieldKey, expected: ExpectedKey,
  action: ActionKey = 'correctV1Field'): PrivateConfigurationProblem {
  return Object.freeze({
    code: PRIVATE_CONFIGURATION_CODES.fieldInvalid,
    field: PRIVATE_CONFIGURATION_FIELDS[field],
    expected: PRIVATE_CONFIGURATION_EXPECTED[expected],
    action: PRIVATE_CONFIGURATION_ACTIONS[action],
  });
}

export function unknownPrivateConfigurationKey(): PrivateConfigurationProblem {
  return Object.freeze({ code: PRIVATE_CONFIGURATION_CODES.unknownKey,
    field: PRIVATE_CONFIGURATION_FIELDS.unknownKey, expected: PRIVATE_CONFIGURATION_EXPECTED.noUnknownKeys,
    action: PRIVATE_CONFIGURATION_ACTIONS.removeUnknownKeys });
}

export function legacyBootstrapProblem(): PrivateConfigurationProblem {
  return Object.freeze({ code: PRIVATE_CONFIGURATION_CODES.legacyBootstrap,
    field: PRIVATE_CONFIGURATION_FIELDS.bootstrap, expected: PRIVATE_CONFIGURATION_EXPECTED.versionedBootstrap,
    action: PRIVATE_CONFIGURATION_ACTIONS.migrateLegacyBootstrap });
}

export function privateFileProblem(field: 'bootstrap' | 'configFile' | 'credentialFile',
  expected: 'privateBootstrapFile' | 'privateBundleFile' | 'privateCredentialFile',
  action: 'repairBundleFileTs' | 'repairCredentialFileTs'): PrivateConfigurationProblem {
  return Object.freeze({ code: PRIVATE_CONFIGURATION_CODES.privateFileInvalid,
    field: PRIVATE_CONFIGURATION_FIELDS[field], expected: PRIVATE_CONFIGURATION_EXPECTED[expected],
    action: PRIVATE_CONFIGURATION_ACTIONS[action] });
}

export function personaInactiveProblem(): PrivateConfigurationProblem {
  return Object.freeze({ code: PRIVATE_CONFIGURATION_CODES.personaPublicationUnqualified,
    field: PRIVATE_CONFIGURATION_FIELDS.personaFile, expected: PRIVATE_CONFIGURATION_EXPECTED.personaInactive,
    action: PRIVATE_CONFIGURATION_ACTIONS.keepPersonaInactive });
}

export function formatPrivateConfigurationProblem(problem: PrivateConfigurationProblem): string {
  return `${problem.code} field=${problem.field} expected=${problem.expected} action=${problem.action}`;
}
