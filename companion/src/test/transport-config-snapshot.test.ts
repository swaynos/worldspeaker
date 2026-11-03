import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import test from 'node:test';
import { DEPLOYMENT } from '../generated/deployment.js';
import { OBSERVE_PLAYER_ABILITY, type AbilityPolicy } from '../ability-policy.js';
import type { Binding } from '../protocol.js';
import { WorldspeakerTransport, type TransportConfig } from '../transport.js';

const binding: Binding = {
  serverId: 'server-config-snapshot-test',
  worldId: 'world-config-snapshot-test',
  characterId: 'character-config-snapshot-test',
  agentId: 'agent-config-snapshot-test',
};

const endpointCorpus = JSON.parse(await readFile(resolve(process.cwd(), '../compatibility/endpoint-syntax-v1-corpus.json'), 'utf8')) as {
  cases: readonly Readonly<{ id: string; valid: boolean; endpoint: string }>[];
};

function config(overrides: Partial<TransportConfig> = {}): TransportConfig {
  return {
    enabled: true,
    bindHost: '127.0.0.1',
    port: 18_473,
    serverEndpoint: 'ws://127.0.0.1:18473',
    bearerSecret: randomBytes(32).toString('base64url'),
    deploymentId: DEPLOYMENT.deploymentId,
    manifestSha256: DEPLOYMENT.manifestSha256,
    configurationHandshakeVersion: 1,
    configSetSha256: 'a'.repeat(64),
    binding: { ...binding },
    ...overrides,
  };
}

function construct(candidate: unknown): WorldspeakerTransport {
  return new WorldspeakerTransport(candidate as TransportConfig,
    DEPLOYMENT.hostVersion, DEPLOYMENT.companionVersion, async () => {}, async () => {}, () => {});
}

function retainedConfig(transport: WorldspeakerTransport): TransportConfig {
  return (transport as unknown as { config: TransportConfig }).config;
}

function assertConfigError(operation: () => unknown, message = 'invalid transport configuration'): void {
  assert.throws(operation, (error: unknown) => {
    assert.ok(error instanceof Error);
    assert.equal(error.name, 'TransportConfigError');
    assert.equal(error.message, message);
    assert.equal('cause' in error, false);
    return true;
  });
}

test('direct transport construction never invokes stateful endpoint or secret accessors', () => {
  const unsafeEndpoint = config();
  let endpointReads = 0;
  Object.defineProperty(unsafeEndpoint, 'serverEndpoint', {
    enumerable: true,
    get() {
      endpointReads++;
      return endpointReads === 1 ? 'ws://example.invalid:18473' : 'ws://127.0.0.1:18473';
    },
  });
  assertConfigError(() => construct(unsafeEndpoint));
  assert.equal(endpointReads, 0, 'an unsafe first endpoint value cannot be validated and replaced by a later value');

  const validSecret = randomBytes(32).toString('base64url');
  const invalidSecret = config({ bearerSecret: validSecret });
  let secretReads = 0;
  Object.defineProperty(invalidSecret, 'bearerSecret', {
    enumerable: true,
    get() {
      secretReads++;
      return secretReads <= 4 ? validSecret : 'invalid-secret-retained-after-validation';
    },
  });
  assertConfigError(() => construct(invalidSecret));
  assert.equal(secretReads, 0, 'a stateful credential accessor is rejected without invocation');
});

test('direct transport construction applies the shared endpoint corpus without rewriting accepted strings', () => {
  for (const entry of endpointCorpus.cases) {
    const candidate = config({ serverEndpoint: entry.endpoint });
    if (entry.valid) {
      assert.equal(retainedConfig(construct(candidate)).serverEndpoint, entry.endpoint, entry.id);
    } else {
      assertConfigError(() => construct(candidate), 'invalid server endpoint');
    }
  }
});

test('root, binding, and nested policy accessors are rejected before invocation', () => {
  for (const field of ['enabled', 'port'] as const) {
    const candidate = config();
    let reads = 0;
    Object.defineProperty(candidate, field, {
      enumerable: true,
      get() { reads++; return field === 'enabled' ? true : 18_473; },
    });
    assertConfigError(() => construct(candidate));
    assert.equal(reads, 0, `${field} getter is not invoked`);
  }

  const nestedBinding = config();
  let bindingReads = 0;
  Object.defineProperty(nestedBinding.binding, 'serverId', {
    enumerable: true,
    get() { bindingReads++; return binding.serverId; },
  });
  assertConfigError(() => construct(nestedBinding));
  assert.equal(bindingReads, 0);

  const policy: AbilityPolicy = {
    schemaVersion: 1,
    revision: 4,
    grants: [],
    denials: [],
    externalClaimsMode: 'NONE',
  };
  const nestedPolicy = config({ abilityPolicy: policy });
  let policyReads = 0;
  Object.defineProperty(policy, 'revision', {
    enumerable: true,
    get() { policyReads++; return 4; },
  });
  assertConfigError(() => construct(nestedPolicy));
  assert.equal(policyReads, 0);
});

test('constructor retains frozen copies while caller config, binding, and policy remain mutable', () => {
  const sourceBinding = { ...binding };
  const sourceDenial = { id: 'deny-observe', abilityId: OBSERVE_PLAYER_ABILITY, contractVersion: 1 as const };
  const sourcePolicy: AbilityPolicy = {
    schemaVersion: 1,
    revision: 7,
    grants: [],
    denials: [sourceDenial],
    externalClaimsMode: 'NONE',
  };
  const sourceConfig = config({ binding: sourceBinding, abilityPolicy: sourcePolicy });
  const mutableSourceConfig = sourceConfig as unknown as {
    enabled: boolean;
    port: number;
    serverEndpoint: string;
    bearerSecret: string;
    configSetSha256: string;
  };
  const mutableSourcePolicy = sourcePolicy as unknown as { revision: number };
  const originalEndpoint = sourceConfig.serverEndpoint;
  const originalSecret = sourceConfig.bearerSecret;
  const transport = construct(sourceConfig);

  mutableSourceConfig.enabled = false;
  mutableSourceConfig.port = 18_474;
  mutableSourceConfig.serverEndpoint = 'ws://example.invalid:18473';
  mutableSourceConfig.bearerSecret = 'invalid-after-construction';
  mutableSourceConfig.configSetSha256 = 'b'.repeat(64);
  sourceBinding.serverId = 'caller-mutated-server';
  sourceDenial.id = 'caller-mutated-denial';
  mutableSourcePolicy.revision = 8;

  const retained = retainedConfig(transport);
  const retainedPolicy = retained.abilityPolicy;
  assert.ok(retainedPolicy);
  assert.equal(retained.enabled, true);
  assert.equal(retained.port, 18_473);
  assert.equal(retained.serverEndpoint, originalEndpoint);
  assert.equal(retained.bearerSecret, originalSecret);
  assert.equal(retained.configSetSha256, 'a'.repeat(64));
  assert.deepEqual(retained.binding, binding);
  assert.equal(retainedPolicy.revision, 7);
  assert.equal(retainedPolicy.denials[0]?.id, 'deny-observe');
  assert.ok(Object.isFrozen(retained));
  assert.ok(Object.isFrozen(retained.binding));
  assert.ok(Object.isFrozen(retainedPolicy));
  assert.ok(Object.isFrozen(retainedPolicy.denials));
  assert.ok(Object.isFrozen(retainedPolicy.denials[0]));
  assert.equal(Object.isFrozen(sourceConfig), false);
  assert.equal(Object.isFrozen(sourceBinding), false);
  assert.equal(Object.isFrozen(sourcePolicy), false);
});

test('optional ability policy absence preserves the deny-all default', () => {
  const retained = retainedConfig(construct(config()));
  assert.deepEqual(retained.abilityPolicy, {
    schemaVersion: 1,
    revision: 0,
    grants: [],
    denials: [],
    externalClaimsMode: 'REQUIRED',
  });
});

test('plain and null-prototype data records are accepted, but custom prototypes are rejected', () => {
  const candidate = config();
  const nullPrototypeConfig: Record<string, unknown> = Object.assign(Object.create(null), candidate);
  nullPrototypeConfig['binding'] = Object.assign(Object.create(null) as Record<string, unknown>, candidate.binding);
  assert.equal(retainedConfig(construct(nullPrototypeConfig)).binding.characterId, binding.characterId);

  const inherited = Object.assign(Object.create({ serverEndpoint: 'ws://127.0.0.1:18473' }), candidate);
  assertConfigError(() => construct(inherited));

  const nonEnumerableUnknown = config() as unknown as Record<string, unknown>;
  Object.defineProperty(nonEnumerableUnknown, 'callerAuthority', { value: true, enumerable: false });
  assertConfigError(() => construct(nonEnumerableUnknown));

  const symbolUnknown = config() as unknown as Record<PropertyKey, unknown>;
  symbolUnknown[Symbol('caller authority')] = true;
  assertConfigError(() => construct(symbolUnknown));
});

test('descriptor and proxy failures become fixed value-free typed errors', () => {
  const descriptorFailure = new Error('PRIVATE_DESCRIPTOR_SENTINEL');
  const candidate = config();
  const proxy = new Proxy(candidate, {
    getOwnPropertyDescriptor(target, property) {
      if (property === 'serverEndpoint') throw descriptorFailure;
      return Reflect.getOwnPropertyDescriptor(target, property);
    },
  });
  assertConfigError(() => construct(proxy));

  const ownKeysFailure = new Error('PRIVATE_OWN_KEYS_SENTINEL');
  const ownKeysProxy = new Proxy(config(), {
    ownKeys() { throw ownKeysFailure; },
  });
  assertConfigError(() => construct(ownKeysProxy));
});

test('config digest validation does not coerce caller-owned objects', () => {
  let coercions = 0;
  const candidate = config({ configSetSha256: {
    toString() { coercions++; return 'a'.repeat(64); },
  } as unknown as string });
  assertConfigError(() => construct(candidate));
  assert.equal(coercions, 0);
});
