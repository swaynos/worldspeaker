import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomBytes, createHash } from 'node:crypto';
import { chmod, link, lstat, mkdtemp, readFile, realpath, rename, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { Ajv } from 'ajv';
import test from 'node:test';
import {
  canonicalizeConfiguredAlias,
  isValidConfiguredAlias,
  isValidNaturallyAddressableName,
  validateConfiguredCharacterNames,
} from '../natural-address-name.js';
import { isValidAcknowledgementTemplate } from '../acknowledgement-template.js';
import { isValidEndpointSyntax } from '../endpoint-syntax.js';
import { loadStartupConfig, StartupConfigError, type StartupConfigSnapshot } from '../startup-config.js';
import { parseAbilityPolicy } from '../ability-policy.js';

type Fixture = Readonly<{ name: string; bundle: Record<string, any> }>;
type Fixtures = Readonly<{ valid: readonly Fixture[]; invalid: readonly Fixture[] }>;
type EndpointCorpus = Readonly<{ cases: readonly Readonly<{ id: string; valid: boolean; endpoint: string }>[] }>;
type AcknowledgementTemplateCase = Readonly<{
  id: string;
  valid: boolean;
  template?: string;
  templateRepeat?: Readonly<{ text: string; count: number }>;
}>;

const compatibility = resolve(process.cwd(), '../compatibility');
const fixtures = JSON.parse(await readFile(join(compatibility, 'startup-config-v1-fixtures.json'), 'utf8')) as Fixtures;
const endpointCorpus = JSON.parse(await readFile(join(compatibility, 'endpoint-syntax-v1-corpus.json'), 'utf8')) as EndpointCorpus;
const acknowledgementTemplateCorpus = JSON.parse(await readFile(join(compatibility, 'acknowledgement-template-v1-corpus.json'), 'utf8')) as {
  cases: readonly AcknowledgementTemplateCase[];
};
const schema = JSON.parse(await readFile(join(compatibility, 'startup-config-v1.schema.json'), 'utf8')) as object;

test('shared startup schema accepts bounded examples and rejects invalid corpus records', () => {
  const validate = compileStartupSchema();
  for (const fixture of fixtures.valid) assert.equal(validate(fixture.bundle), true, fixture.name);
  for (const fixture of fixtures.invalid) assert.equal(validate(fixture.bundle), false, fixture.name);
  const noncanonical = structuredClone(fixtures.valid[0]!.bundle);
  noncanonical.credentialFile = '/private//example/credential.txt';
  assert.equal(validate(noncanonical), false, 'repeated path separators are not canonical');
  const wrongDeployment = structuredClone(fixtures.valid[0]!.bundle);
  wrongDeployment.companion.deploymentId = 'different-deployment';
  assert.equal(validate(wrongDeployment), false, 'bundle identity is pinned to the installed deployment');
});

test('strict schema validation fails closed when the required endpoint vocabulary is missing', () => {
  assert.throws(() => new Ajv({ allErrors: true, strict: true }).compile(schema),
    'a generic strict draft-07 validator must not silently skip the required endpoint keyword');
});

test('shared ACK-template corpus matches startup schema and actual private-file admission without normalization', async () => {
  const validate = compileStartupSchema();
  for (const entry of acknowledgementTemplateCorpus.cases) {
    const template = expandAcknowledgementTemplate(entry);
    const source = structuredClone(fixtures.valid[0]!.bundle);
    source.server.acknowledgementTemplate = template;
    assert.equal(validate(source), entry.valid, `schema: ${entry.id}`);

    const fixture = await writeBundleFiles(source, randomBytes(32).toString('base64url'));
    try {
      if (!entry.valid) {
        await assert.rejects(loadStartupConfig(fixture.configFile), { code: 'invalid-schema' }, entry.id);
        continue;
      }

      const snapshot = await loadStartupConfig(fixture.configFile);
      assert.deepEqual(snapshot.server, source.server, `${entry.id}: cached server context and exact template are unchanged`);
    } finally {
      await rm(fixture.directory, { recursive: true, force: true });
    }
  }
});

test('private startup loader and shared schema agree on the canonical config UUID domain', async () => {
  const uuidCorpus = JSON.parse(await readFile(join(compatibility, 'startup-config-v1-uuid-corpus.json'), 'utf8')) as {
    valid: readonly Readonly<{ name: string; value: string }>[];
    invalidItems: readonly Readonly<{ name: string; value: unknown }>[];
    invalidLists: readonly Readonly<{ name: string; value: unknown }>[];
  };
  const validate = compileStartupSchema();
  const fields: ReadonlyArray<Readonly<{
    name: string;
    set: (bundle: Record<string, any>, value: unknown) => void;
  }>> = [
    { name: 'conversationAllowedPlayers', set: (bundle, value) => { bundle.server.conversationAllowedPlayers = value; } },
    { name: 'requesterIds', set: (bundle, value) => { bundle.abilityPolicy.grants[0].requesterIds = value; } },
    { name: 'otherTargetIds', set: (bundle, value) => { bundle.abilityPolicy.grants[0].otherTargetIds = value; } },
  ];
  const sourceFixture = fixtures.valid.find((fixture) => fixture.name === 'complete-observe-grant')!;
  const sourceBundle = structuredClone(sourceFixture.bundle);
  delete sourceBundle.personaFile;
  const ids = uuidCorpus.valid.map((item) => item.value);
  const valid = structuredClone(sourceBundle);
  for (const field of fields) field.set(valid, ids);
  assert.equal(validate(valid), true, 'all canonical version and variant bit patterns pass schema');
  const fixture = await writeBundleFiles(valid, randomBytes(32).toString('base64url'));
  try {
    const snapshot = await loadStartupConfig(fixture.configFile);
    assert.deepEqual(snapshot.server.conversationAllowedPlayers, ids);
    assert.deepEqual(snapshot.abilityPolicy.grants[0]!.requesterIds, ids);
    assert.deepEqual(snapshot.abilityPolicy.grants[0]!.otherTargetIds, ids);
  } finally {
    await rm(fixture.directory, { recursive: true, force: true });
  }

  for (const invalid of uuidCorpus.invalidItems) {
    for (const field of fields) {
      const bundle = structuredClone(sourceBundle);
      field.set(bundle, [invalid.value]);
      assert.equal(validate(bundle), false, `${field.name}: ${invalid.name}`);
      const invalidFixture = await writeBundleFiles(bundle, randomBytes(32).toString('base64url'));
      try { await assert.rejects(loadStartupConfig(invalidFixture.configFile), { code: 'invalid-schema' }, `${field.name}: ${invalid.name}`); }
      finally { await rm(invalidFixture.directory, { recursive: true, force: true }); }
    }
  }
  for (const invalid of uuidCorpus.invalidLists) {
    for (const field of fields) {
      const bundle = structuredClone(sourceBundle);
      field.set(bundle, invalid.value);
      assert.equal(validate(bundle), false, `${field.name}: ${invalid.name}`);
      const invalidFixture = await writeBundleFiles(bundle, randomBytes(32).toString('base64url'));
      try { await assert.rejects(loadStartupConfig(invalidFixture.configFile), { code: 'invalid-schema' }, `${field.name}: ${invalid.name}`); }
      finally { await rm(invalidFixture.directory, { recursive: true, force: true }); }
    }
  }
  for (const field of fields) {
    const bundle = structuredClone(sourceBundle);
    field.set(bundle, [ids[0], ids[0]]);
    assert.equal(validate(bundle), false, `${field.name}: duplicate canonical UUID`);
    const duplicateFixture = await writeBundleFiles(bundle, randomBytes(32).toString('base64url'));
    try { await assert.rejects(loadStartupConfig(duplicateFixture.configFile), { code: 'invalid-schema' }, `${field.name}: duplicate canonical UUID`); }
    finally { await rm(duplicateFixture.directory, { recursive: true, force: true }); }
  }
});

test('loads actual private files into an exact-hash immutable startup snapshot', async () => {
  const { directory, snapshot, bundleBytes, credentialFile, secret } = await writeFixture(fixtures.valid[0]!.bundle);
  try {
    assert.equal(snapshot.configSetSha256, createHash('sha256').update(bundleBytes).digest('hex'));
    assert.equal(snapshot.credentialFile, credentialFile);
    assert.equal(snapshot.bearerSecret, secret);
    assert.equal(Object.isFrozen(snapshot), true);
    assert.equal(Object.isFrozen(snapshot.binding), true);
    assert.equal(Object.isFrozen(snapshot.server), true);
    assert.equal(Object.isFrozen(snapshot.server.conversationAllowedPlayers), true);
    assert.equal(Object.isFrozen(snapshot.abilityPolicy), true);
    assert.equal(Object.hasOwn(snapshot, 'personaFile'), false);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('actual TypeScript startup loader and schema follow the shared naturally-addressable-name corpus', async () => {
  const corpus = JSON.parse(await readFile(join(compatibility, 'startup-name-validation-v1-corpus.json'), 'utf8')) as {
    cases: readonly Readonly<{ id: string; valid: boolean; displayName: unknown; aliases?: unknown; canonicalAliases?: readonly string[] }>[];
  };
  const validateSchema = compileStartupSchema();

  for (const sourceEntry of corpus.cases) {
    const entry = {
      ...sourceEntry,
      displayName: expandNameCorpusValue(sourceEntry.displayName),
      ...(sourceEntry.aliases === undefined ? {} : { aliases: expandNameCorpusValue(sourceEntry.aliases) }),
    };
    const source = structuredClone(fixtures.valid[0]!.bundle);
    source.server.characterDisplayName = entry.displayName;
    if (entry.aliases === undefined) delete source.server.characterAliases;
    else source.server.characterAliases = entry.aliases;
    assert.equal(validateSchema(source), entry.valid, `schema: ${entry.id}`);

    const fixture = await writeBundleFiles(source, randomBytes(32).toString('base64url'));
    try {
      if (!entry.valid) {
        await assert.rejects(loadStartupConfig(fixture.configFile), { code: 'invalid-schema' }, entry.id);
        continue;
      }

      const snapshot = await loadStartupConfig(fixture.configFile);
      assert.equal(snapshot.server.characterDisplayName, entry.displayName, entry.id);
      const aliases = (snapshot.server.characterAliases ?? []) as readonly string[];
      assert.deepEqual(aliases, entry.canonicalAliases ?? entry.aliases ?? [], entry.id);
      for (const name of [entry.displayName as string, ...aliases]) {
        assert.equal(isValidNaturallyAddressableName(name), true, `${entry.id} addressable name ${name}`);
      }
    } finally {
      await rm(fixture.directory, { recursive: true, force: true });
    }
  }
});

test('actual TypeScript startup files and required schema vocabulary follow the shared endpoint corpus', async () => {
  const validateSchema = compileStartupSchema();
  for (const entry of endpointCorpus.cases) {
    const bundle = structuredClone(fixtures.valid[0]!.bundle);
    bundle.server.endpoint = entry.endpoint;
    assert.equal(isValidEndpointSyntax(entry.endpoint), entry.valid, `shared helper: ${entry.id}`);
    assert.equal(validateSchema(bundle), entry.valid, `schema adapter: ${entry.id}`);

    const fixture = await writeBundleFiles(bundle, randomBytes(32).toString('base64url'));
    try {
      if (!entry.valid) {
        await assert.rejects(loadStartupConfig(fixture.configFile), { code: 'invalid-schema' }, entry.id);
        continue;
      }
      const snapshot = await loadStartupConfig(fixture.configFile);
      assert.equal(snapshot.server.endpoint, entry.endpoint, `${entry.id} retains the exact configured URI string`);
    } finally {
      await rm(fixture.directory, { recursive: true, force: true });
    }
  }
});

test('credential is canonical unpadded base64url for 32 through 64 bytes', async () => {
  const { directory, snapshot } = await writeFixture(fixtures.valid[0]!.bundle, randomBytes(64).toString('base64url'));
  try {
    assert.equal(Buffer.from(snapshot.bearerSecret, 'base64url').length, 64);
    await writeCredential(directory, `${snapshot.bearerSecret} `);
    await assert.rejects(loadStartupConfig(join(directory, 'startup.json')), { code: 'invalid-content' });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('rejects invalid UTF-8, BOM, malformed JSON, duplicate decoded keys, and nesting above eight', async () => {
  const cases: Buffer[] = [
    Buffer.from([0xc3, 0x28]),
    Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from('{}')]),
    Buffer.from('{"schemaVersion":1,}'),
    Buffer.from('{"schemaVersion":1,"\\u0073chemaVersion":1}'),
  ];
  for (const bytes of cases) {
    const fixture = await writeRawBundle(bytes);
    try { await assert.rejects(loadStartupConfig(fixture.configFile), { code: 'invalid-content' }); }
    finally { await rm(fixture.directory, { recursive: true, force: true }); }
  }

  const nested = structuredClone(fixtures.valid[0]!.bundle);
  let layer: Record<string, unknown> = {};
  nested.server.unused = layer;
  for (const key of 'abcdefg') {
    const child: Record<string, unknown> = {};
    layer[key] = child;
    layer = child;
  }
  const fixture = await writeBundleFiles(nested, randomBytes(32).toString('base64url'));
  try { await assert.rejects(loadStartupConfig(fixture.configFile), { code: 'invalid-content' }); }
  finally { await rm(fixture.directory, { recursive: true, force: true }); }
});

test('actual startup loader requires every root object and rejects unknown fields, nulls, and type coercion', async () => {
  const mutations: ((bundle: Record<string, any>) => void)[] = [
    (bundle) => { delete bundle.schemaVersion; },
    (bundle) => { delete bundle.binding; },
    (bundle) => { delete bundle.server; },
    (bundle) => { delete bundle.companion; },
    (bundle) => { delete bundle.abilityPolicy; },
    (bundle) => { delete bundle.credentialFile; },
    (bundle) => { bundle.schemaVersion = 2; },
    (bundle) => { bundle.unrecognizedAuthority = true; },
    (bundle) => { bundle.binding = null; },
    (bundle) => { bundle.server.endpoint = 17; },
    (bundle) => { bundle.companion.port = null; },
    (bundle) => { bundle.abilityPolicy.protection = null; },
    (bundle) => { bundle.credentialFile = null; },
  ];
  for (const mutate of mutations) {
    const fixture = await writeBundleFiles(fixtures.valid[0]!.bundle, randomBytes(32).toString('base64url'));
    try {
      const raw = JSON.parse(await readFile(fixture.configFile, 'utf8')) as Record<string, any>;
      mutate(raw);
      await writeFile(fixture.configFile, JSON.stringify(raw), { mode: 0o600 });
      await chmod(fixture.configFile, 0o600);
      await assert.rejects(loadStartupConfig(fixture.configFile), { code: 'invalid-schema' });
    } finally {
      await rm(fixture.directory, { recursive: true, force: true });
    }
  }
});

test('actual private TypeScript loader retains typed, value-free problems for v1 fields and unknown keys', async () => {
  const credentialCanary = randomBytes(32).toString('base64url');
  const unknownKeyCanary = 'unknownKeyCanaryMustNotBeLogged';
  const agentCanary = 'agentValueCanary\nWS-COMPAT-INJECTED';
  const styleCanary = 'styleValueCanary\nWS-COMPAT-INJECTED';
  const profiles: readonly Readonly<{
    name: string;
    field: string;
    expected: string;
    mutate: (bundle: Record<string, any>) => void;
  }>[] = [
    { name: 'port', field: 'companion.port', expected: 'integer 1024..65535',
      mutate: (bundle) => { bundle.companion.port = 65_536; } },
    { name: 'agent-type', field: 'binding.agentId', expected: 'ASCII string [A-Za-z0-9._:-], 1..128 characters',
      mutate: (bundle) => { bundle.binding.agentId = []; } },
    { name: 'attention-bound', field: 'server.attention.maxEvents', expected: 'integer 1..64',
      mutate: (bundle) => { bundle.server.attention = { maxEvents: 65 }; } },
    { name: 'denial-ability', field: 'abilityPolicy.denials[].abilityId', expected: 'known observe_player or strike_lightning ability v1',
      mutate: (bundle) => { bundle.abilityPolicy.denials = [{ id: 'denial-fixture', abilityId: 'unknownAbilityCanaryMustNotBeLogged', contractVersion: 1 }]; } },
    { name: 'unknown-key', field: 'unknown-key', expected: 'only fields defined by startup schema v1',
      mutate: (bundle) => { bundle[unknownKeyCanary] = 'unknownValueCanary'; } },
    { name: 'injected-style', field: 'server.characterChatStyle', expected: 'plain or bracketed',
      mutate: (bundle) => { bundle.server.characterChatStyle = styleCanary; } },
    { name: 'agent-value', field: 'binding.agentId', expected: 'ASCII string [A-Za-z0-9._:-], 1..128 characters',
      mutate: (bundle) => { bundle.binding.agentId = agentCanary; } },
  ];

  for (const profile of profiles) {
    const bundle = structuredClone(fixtures.valid[0]!.bundle);
    profile.mutate(bundle);
    const fixture = await writeBundleFiles(bundle, credentialCanary);
    try {
      await assert.rejects(loadStartupConfig(fixture.configFile), (error: unknown) => {
        assert.equal(error instanceof StartupConfigError, true, profile.name);
        const failure = error as StartupConfigError;
        assert.equal(failure.code, 'invalid-schema', profile.name);
        assert.ok(failure.problem, profile.name);
        assert.equal(failure.problem.field, profile.field, profile.name);
        assert.equal(failure.problem.expected, profile.expected, profile.name);
        assert.match(failure.message, new RegExp(failure.problem.code), profile.name);
        assert.doesNotMatch(failure.message, /migrate|create config/iu, profile.name);
        for (const canary of [credentialCanary, unknownKeyCanary, agentCanary, styleCanary,
          'unknownValueCanary', 'unknownAbilityCanaryMustNotBeLogged', fixture.configFile, fixture.credentialFile]) {
          assert.equal(failure.message.includes(canary), false, `${profile.name}: no leaked ${canary}`);
        }
        return true;
      });
    } finally {
      await rm(fixture.directory, { recursive: true, force: true });
    }
  }
});

test('accepts the exact 32 KiB bundle ceiling and rejects one byte over before parsing', async () => {
  const exactFixture = await writeSizedFixture(fixtures.valid[0]!.bundle, 32_768);
  try { assert.equal((await loadStartupConfig(exactFixture.configFile)).configSetSha256.length, 64); }
  finally { await rm(exactFixture.directory, { recursive: true, force: true }); }

  const overFixture = await writeSizedFixture(fixtures.valid[0]!.bundle, 32_769);
  try { await assert.rejects(loadStartupConfig(overFixture.configFile), { code: 'invalid-content' }); }
  finally { await rm(overFixture.directory, { recursive: true, force: true }); }
});

test('configured persona blocks before credential access and never becomes a no-persona fallback', async () => {
  const source = structuredClone(fixtures.valid[0]!.bundle);
  source.personaFile = '/private/inactive-persona.txt';
  const fixture = await writeBundleFiles(source, randomBytes(32).toString('base64url'));
  await rm(fixture.credentialFile);
  try {
    await assert.rejects(loadStartupConfig(fixture.configFile), (error: unknown) => {
      assert.equal(error instanceof StartupConfigError, true);
      assert.equal((error as StartupConfigError).code, 'persona-publication-unqualified');
      assert.doesNotMatch((error as Error).message, /inactive-persona|credential/u);
      return true;
    });
  } finally {
    await rm(fixture.directory, { recursive: true, force: true });
  }
});

test('actual God startup templates validate with current keywords and preserve the configured-persona startup block', async () => {
  const presetDirectory = resolve(process.cwd(), '../presets/god');
  const currentSchema = compileStartupSchema();
  const characterId = 'preset-test-character';
  const binding = { serverId: 'preset-test-server', worldId: 'preset-test-world', characterId, agentId: 'preset-test-agent' };

  const mainDirectory = await privateDirectory();
  try {
    const missingCredential = join(mainDirectory, 'not-created-credential.txt');
    const personaPath = join(mainDirectory, 'private-persona.txt');
    const mainBytes = renderGodStartupTemplate(
      await readFile(join(presetDirectory, 'startup-config.json.template'), 'utf8'),
      binding, missingCredential, personaPath,
    );
    const mainBundle = JSON.parse(mainBytes.toString('utf8')) as Record<string, any>;
    assert.equal(currentSchema(mainBundle), true, 'all required custom schema keywords accept the rendered template');
    assert.equal(mainBundle.personaFile, personaPath);
    await writePrivateFile(join(mainDirectory, 'startup.json'), mainBytes);
    await assert.rejects(loadStartupConfig(join(mainDirectory, 'startup.json')), (error: unknown) => {
      assert.equal(error instanceof StartupConfigError, true);
      assert.equal((error as StartupConfigError).code, 'persona-publication-unqualified');
      assert.doesNotMatch((error as Error).message, /private-persona|not-created-credential/u);
      return true;
    });
    await assert.rejects(readFile(missingCredential), { code: 'ENOENT' }, 'persona block precedes credential access');
  } finally {
    await rm(mainDirectory, { recursive: true, force: true });
  }

  const controlDirectory = await privateDirectory();
  try {
    const credentialFile = join(controlDirectory, 'throwaway-credential.txt');
    const secret = randomBytes(32).toString('base64url');
    await writePrivateFile(credentialFile, Buffer.from(secret, 'ascii'));
    const controlBytes = renderGodStartupTemplate(
      await readFile(join(presetDirectory, 'startup-config.no-guidance-control.json.template'), 'utf8'),
      binding, credentialFile,
    );
    const control = JSON.parse(controlBytes.toString('utf8')) as Record<string, any>;
    assert.equal(currentSchema(control), true, 'the current strict custom-keyword schema accepts the complete control');
    assert.equal(Object.hasOwn(control, 'personaFile'), false, 'the named control omits only the optional persona reference');
    const controlPath = join(controlDirectory, 'startup.json');
    await writePrivateFile(controlPath, controlBytes);
    const snapshot = await loadStartupConfig(controlPath);
    assert.equal(snapshot.configSetSha256, createHash('sha256').update(controlBytes).digest('hex'));
    assert.deepEqual(snapshot.binding, binding);
    assert.deepEqual(snapshot.abilityPolicy, {
      schemaVersion: 1, revision: 0, grants: [], denials: [], externalClaimsMode: 'REQUIRED',
    });
    assert.equal(Object.hasOwn(snapshot, 'personaFile'), false);

    const visualSource = await readFile(join(presetDirectory, 'visual-only-policy.example.json.template'), 'utf8');
    const visual = JSON.parse(visualSource.replaceAll('<GRANT_ID>', 'preset-visual-grant')
      .replaceAll('<CHARACTER_ID>', characterId)
      .replaceAll('<REQUESTER_UUID>', '00000000-0000-7000-8000-000000000041')) as Record<string, any>;
    const parsedVisual = parseAbilityPolicy(visual, characterId);
    assert.equal(parsedVisual.grants.length, 1);
    assert.equal(parsedVisual.grants[0]!.abilityId, 'worldspeaker.core.strike_lightning');
    assert.equal(parsedVisual.grants[0]!.allowVisual, true);
    assert.equal(parsedVisual.grants[0]!.allowDamage, false);
    assert.equal(parsedVisual.grants[0]!.maxDamage, null);
    assert.deepEqual(parsedVisual.grants[0]!.otherTargetIds, []);

    for (const mutation of [
      (policy: Record<string, any>) => { policy.grants[0].unknownOperation = true; },
      (policy: Record<string, any>) => { policy.grants[0].abilityId = 'worldspeaker.core.unimplemented'; },
      (policy: Record<string, any>) => { policy.grants[0].characterId = 'different-character'; },
      (policy: Record<string, any>) => { policy.grants[0].allowDamage = true; },
      (policy: Record<string, any>) => { policy.grants[0].allowDamage = false; policy.grants[0].maxDamage = 1; },
    ]) {
      const invalid = structuredClone(visual);
      mutation(invalid);
      assert.throws(() => parseAbilityPolicy(invalid, characterId));
    }
  } finally {
    await rm(controlDirectory, { recursive: true, force: true });
  }
});

test('actual startup loader rejects explicit null rather than applying omission defaults', async () => {
  const mutations: ((bundle: Record<string, any>) => void)[] = [
    (bundle) => { bundle.server.attention = { subscriptions: null }; },
    (bundle) => { bundle.server.attention = { maxEvents: null }; },
    (bundle) => { bundle.server.attention = { maxAgeSeconds: null }; },
    (bundle) => { bundle.server.attention = { maxBytes: null }; },
    (bundle) => { bundle.server.attention = { maxEventsPerSecond: null }; },
    (bundle) => { bundle.server.attention = { maxEventsPerPlayerSecond: null }; },
    (bundle) => { bundle.abilityPolicy.protection.externalClaimsMode = null; },
  ];
  for (const mutate of mutations) {
    const source = structuredClone(fixtures.valid[0]!.bundle);
    mutate(source);
    const fixture = await writeBundleFiles(source, randomBytes(32).toString('base64url'));
    try { await assert.rejects(loadStartupConfig(fixture.configFile), { code: 'invalid-schema' }); }
    finally { await rm(fixture.directory, { recursive: true, force: true }); }
  }
});

test('actual startup loader enforces the endpoint schema length bound', async () => {
  const source = structuredClone(fixtures.valid[0]!.bundle);
  source.server.endpoint = `ws://127.0.0.1/${'a'.repeat(2_048)}`;
  const fixture = await writeBundleFiles(source, randomBytes(32).toString('base64url'));
  try { await assert.rejects(loadStartupConfig(fixture.configFile), { code: 'invalid-schema' }); }
  finally { await rm(fixture.directory, { recursive: true, force: true }); }
});

test('enforces exact owner-only file mode, single link, and dedicated private parent', async () => {
  const fixture = await writeFixture(fixtures.valid[0]!.bundle);
  try {
    const startup = join(fixture.directory, 'startup.json');
    await chmod(startup, 0o640);
    await assert.rejects(loadStartupConfig(startup), { code: 'unsafe-file' });
    await chmod(startup, 0o600);
    const alias = join(fixture.directory, 'startup-hardlink.json');
    await link(startup, alias);
    await assert.rejects(loadStartupConfig(startup), { code: 'unsafe-file' });
    await rm(alias);
    assert.equal((await lstat(startup, { bigint: true })).nlink, 1n);
  } finally {
    await rm(fixture.directory, { recursive: true, force: true });
  }
});

test('actual startup loader rejects symlink and special-file references without opening them', async () => {
  const fixture = await writeFixture(fixtures.valid[0]!.bundle);
  try {
    const startup = join(fixture.directory, 'startup.json');
    const alias = join(fixture.directory, 'startup-link.json');
    await symlink(startup, alias);
    await assert.rejects(loadStartupConfig(alias), { code: 'unsafe-file' });
    await rm(alias);

    const credential = join(fixture.directory, 'credential.txt');
    const credentialTarget = join(fixture.directory, 'credential-target.txt');
    await rename(credential, credentialTarget);
    await symlink(credentialTarget, credential);
    await assert.rejects(loadStartupConfig(startup), { code: 'unsafe-file' });
    await rm(credential);
    await rename(credentialTarget, credential);

    await rename(startup, join(fixture.directory, 'startup-target.json'));
    execFileSync('mkfifo', [startup]);
    await assert.rejects(loadStartupConfig(startup), { code: 'unsafe-file' });
  } finally {
    await rm(fixture.directory, { recursive: true, force: true });
  }
});

test('canonical local references reject URLs, expansion, traversal, controls, and non-absolute paths', async () => {
  const fixture = await writeFixture(fixtures.valid[0]!.bundle);
  try {
    const raw = JSON.parse((await readFile(join(fixture.directory, 'startup.json'), 'utf8')) as string) as Record<string, any>;
    for (const credentialFile of ['relative/credential', 'https://example.invalid/secret', '/private/../secret', '/private/$HOME/secret', '/private/secret\n']) {
      raw.credentialFile = credentialFile;
      await writeFile(join(fixture.directory, 'startup.json'), JSON.stringify(raw), { mode: 0o600 });
      await chmod(join(fixture.directory, 'startup.json'), 0o600);
      await assert.rejects(loadStartupConfig(join(fixture.directory, 'startup.json')));
    }
  } finally {
    await rm(fixture.directory, { recursive: true, force: true });
  }
});

async function writeFixture(bundle: Record<string, any>, secret = randomBytes(32).toString('base64url')): Promise<{
  directory: string; snapshot: StartupConfigSnapshot; bundleBytes: Buffer; credentialFile: string; secret: string;
}> {
  const fixture = await writeBundleFiles(bundle, secret);
  const bundleBytes = await readFile(fixture.configFile);
  return {
    ...fixture,
    snapshot: await loadStartupConfig(fixture.configFile),
    bundleBytes,
    secret,
  };
}

function renderGodStartupTemplate(
  source: string,
  binding: Readonly<{ serverId: string; worldId: string; characterId: string; agentId: string }>,
  credentialFile: string,
  personaFile?: string,
): Buffer {
  const bundle = JSON.parse(source) as Record<string, any>;
  bundle.binding = { ...binding };
  bundle.companion.deploymentId = fixtures.valid[0]!.bundle.companion.deploymentId;
  bundle.companion.manifestSha256 = fixtures.valid[0]!.bundle.companion.manifestSha256;
  bundle.credentialFile = credentialFile;
  if (personaFile === undefined) delete bundle.personaFile;
  else bundle.personaFile = personaFile;
  return Buffer.from(JSON.stringify(bundle), 'utf8');
}

async function writeBundleFiles(bundle: Record<string, any>, secret: string): Promise<{
  directory: string; configFile: string; credentialFile: string;
}> {
  const directory = await privateDirectory();
  const credentialFile = join(directory, 'credential.txt');
  const actualBundle = withCredentialFile(bundle, credentialFile);
  const bundleBytes = Buffer.from(JSON.stringify(actualBundle));
  await writeFile(credentialFile, secret, { mode: 0o600 });
  await chmod(credentialFile, 0o600);
  const configFile = join(directory, 'startup.json');
  await writeFile(configFile, bundleBytes, { mode: 0o600 });
  await chmod(configFile, 0o600);
  return { directory, configFile, credentialFile };
}

async function writePrivateFile(path: string, bytes: Uint8Array): Promise<void> {
  await writeFile(path, bytes, { mode: 0o600 });
  await chmod(path, 0o600);
}

async function writeSizedFixture(bundle: Record<string, any>, size: number): Promise<{ directory: string; configFile: string }> {
  const directory = await privateDirectory();
  const credentialFile = join(directory, 'credential.txt');
  await writeFile(credentialFile, randomBytes(32).toString('base64url'), { mode: 0o600 });
  await chmod(credentialFile, 0o600);
  const source = Buffer.from(JSON.stringify(withCredentialFile(bundle, credentialFile)));
  assert.ok(source.length <= size);
  const bytes = Buffer.concat([source, Buffer.from(' '.repeat(size - source.length))]);
  const configFile = join(directory, 'startup.json');
  await writeFile(configFile, bytes, { mode: 0o600 });
  await chmod(configFile, 0o600);
  return { directory, configFile };
}

async function writeFixtureBytes(bytes: Buffer): Promise<{ directory: string; configFile: string }> {
  const directory = await privateDirectory();
  const credentialFile = join(directory, 'credential.txt');
  await writeFile(credentialFile, randomBytes(32).toString('base64url'), { mode: 0o600 });
  await chmod(credentialFile, 0o600);
  const configFile = join(directory, 'startup.json');
  await writeFile(configFile, bytes, { mode: 0o600 });
  await chmod(configFile, 0o600);
  return { directory, configFile };
}

async function writeRawBundle(bytes: Buffer): Promise<{ directory: string; configFile: string }> { return await writeFixtureBytes(bytes); }

async function privateDirectory(): Promise<string> {
  const root = await realpath(tmpdir());
  const directory = await mkdtemp(join(root, 'worldspeaker-startup-v1-'));
  await chmod(directory, 0o700);
  return directory;
}

function withCredentialFile(bundle: Record<string, any>, credentialFile: string): Record<string, any> {
  return { ...structuredClone(bundle), credentialFile };
}

async function writeCredential(directory: string, value: string): Promise<void> {
  const path = join(directory, 'credential.txt');
  await writeFile(path, value, { mode: 0o600 });
  await chmod(path, 0o600);
}

function expandNameCorpusValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(expandNameCorpusValue);
  if (value === null || typeof value !== 'object') return value;
  const record = value as Record<string, unknown>;
  if (typeof record.repeatCodePoint === 'string' && typeof record.repeatCount === 'number') {
    const codePoint = Number.parseInt(record.repeatCodePoint, 16);
    if (!Number.isInteger(codePoint) || codePoint < 0 || codePoint > 0x10ffff ||
        record.repeatCount < 0 || !Number.isSafeInteger(record.repeatCount)) throw new Error('invalid repeat code point corpus token');
    return String.fromCodePoint(codePoint).repeat(record.repeatCount);
  }
  if (typeof record.repeatCharacter === 'string' && typeof record.repeatCount === 'number') {
    if (record.repeatCharacter.length !== 1 || record.repeatCount < 0 || !Number.isSafeInteger(record.repeatCount)) {
      throw new Error('invalid repeat character corpus token');
    }
    return record.repeatCharacter.repeat(record.repeatCount);
  }
  return Object.fromEntries(Object.entries(record).map(([key, child]) => [key, expandNameCorpusValue(child)]));
}

function expandAcknowledgementTemplate(entry: AcknowledgementTemplateCase): string {
  if (entry.template !== undefined && entry.templateRepeat === undefined) return entry.template;
  if (entry.template === undefined && entry.templateRepeat !== undefined &&
      entry.templateRepeat.text.length > 0 && Number.isSafeInteger(entry.templateRepeat.count) &&
      entry.templateRepeat.count >= 0) return entry.templateRepeat.text.repeat(entry.templateRepeat.count);
  throw new Error(`invalid acknowledgement-template corpus record: ${entry.id}`);
}

function compileStartupSchema() {
  const ajv = new Ajv({ allErrors: true, strict: true });
  ajv.addKeyword({
    keyword: 'worldspeakerEndpoint',
    type: 'string',
    schemaType: 'boolean',
    errors: false,
    validate: (required: boolean, value: string) => !required || isValidEndpointSyntax(value),
  });
  ajv.addKeyword({
    keyword: 'worldspeakerAcknowledgementTemplate',
    type: 'string',
    schemaType: 'boolean',
    errors: false,
    validate: (enabled: boolean, value: unknown) => !enabled || isValidAcknowledgementTemplate(value),
  });
  ajv.addKeyword({
    keyword: 'worldspeakerMaxUtf16TotalLength',
    type: 'array',
    schemaType: 'number',
    errors: false,
    validate: (maximum: number, values: unknown[]) => values.every((value) => typeof value === 'string') &&
      values.reduce((sum, value) => sum + canonicalizeConfiguredAlias(value as string).length, 0) <= maximum,
  });
  ajv.addKeyword({
    keyword: 'worldspeakerPlayerRateAtMostGlobal',
    type: 'object',
    schemaType: 'boolean',
    errors: false,
    validate: (enabled: boolean, value: Record<string, unknown>) => !enabled ||
      typeof value.maxEventsPerPlayerSecond !== 'number' || typeof value.maxEventsPerSecond !== 'number' ||
      value.maxEventsPerPlayerSecond <= value.maxEventsPerSecond,
  });
  ajv.addKeyword({
    keyword: 'worldspeakerMinLessThanMax',
    type: 'object',
    schemaType: 'array',
    errors: false,
    validate: (pairs: readonly (readonly [string, string])[], value: Record<string, unknown>) => pairs.every(([minimum, maximum]) =>
      typeof value[minimum] === 'number' && Number.isSafeInteger(value[minimum]) &&
      typeof value[maximum] === 'number' && Number.isSafeInteger(value[maximum]) &&
      value[minimum] < value[maximum]),
  });
  ajv.addKeyword({
    keyword: 'worldspeakerNaturalAddressName',
    type: 'string',
    schemaType: 'boolean',
    errors: false,
    validate: (enabled: boolean, value: string) => !enabled || isValidNaturallyAddressableName(value),
  });
  ajv.addKeyword({
    keyword: 'worldspeakerConfiguredAlias',
    type: 'string',
    schemaType: 'boolean',
    errors: false,
    validate: (enabled: boolean, value: string) => !enabled || isValidConfiguredAlias(value),
  });
  ajv.addKeyword({
    keyword: 'worldspeakerDistinctConfiguredNames',
    type: 'object',
    schemaType: 'boolean',
    errors: false,
    validate: (enabled: boolean, value: Record<string, unknown>) => !enabled ||
      validateConfiguredCharacterNames(value.characterDisplayName,
        value.characterAliases === undefined ? [] : value.characterAliases) !== null,
  });
  return ajv.compile(schema);
}
