import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join, relative, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';
import { verifyCompanionPackageArchive } from '../../tools/compat/verify-companion-package-archive.mjs';
import { PRESET_ARCHIVE_NAME, verifyGodPresetArchiveBytes } from '../../tools/presets/god-preset-archive.mjs';

const companionRoot = fileURLToPath(new URL('../', import.meta.url));
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';

test('packed plugin imports from a production-only install without host ws', { timeout: 180_000 }, async () => {
  const scratch = mkdtempSync(join(tmpdir(), 'worldspeaker-qa010-'));
  try {
    const packDir = join(scratch, 'pack');
    const extractedDir = join(scratch, 'extracted');
    const fixtureDir = join(scratch, 'fixture');
    const sdkDir = join(scratch, 'openclaw-sdk-fixture');
    mkdirSync(packDir);
    mkdirSync(extractedDir);
    mkdirSync(fixtureDir);
    mkdirSync(sdkDir);

    const packOutput = execFileSync(npm, [
      'pack', '--ignore-scripts', '--json', '--pack-destination', packDir,
    ], { cwd: companionRoot, encoding: 'utf8', timeout: 30_000 });
    const packResult = JSON.parse(packOutput);
    const packed = Array.isArray(packResult)
      ? packResult[0]
      : packResult.filename ? packResult : Object.values(packResult).find((value) => value?.filename);
    assert.ok(packed?.filename, `npm pack should report its archive filename: ${JSON.stringify(packResult)}`);
    const archive = join(packDir, packed.filename);
    const archivePaths = execFileSync('tar', ['-tzf', archive], { encoding: 'utf8', timeout: 10_000 })
      .split(/\r?\n/u).filter(Boolean);
    assert.ok(!archivePaths.some((path) => /(?:^|\/)tools\/dialogue\//u.test(path) ||
      /(?:evaluation\.schema|scenarios\.json|evaluation\.test\.mjs)/u.test(path)),
    'test-only evaluation tooling, schemas, and fixtures stay out of the production plugin');
    assert.ok(archivePaths.includes(`package/presets/${PRESET_ARCHIVE_NAME}`),
      'the actual God data ZIP is included in the npm package');
    execFileSync('tar', ['-xzf', archive, '-C', extractedDir], { encoding: 'utf8', timeout: 10_000 });
    const abilityMetadataFiles = [
      'ability-contracts-v1.json',
      'ability-contracts-v1.schema.json',
      'ability-contracts-v1-schema-resource.json',
      'ability-discovery-v1.schema.json',
      'ability-predicates-v1.schema.json',
    ];
    const archivedAbilityMetadata = new Map();
    for (const name of abilityMetadataFiles) {
      const archivePath = `package/compatibility/${name}`;
      assert.ok(archivePaths.includes(archivePath), `the actual TGZ includes ${archivePath}`);
      const archivedBytes = readFileSync(join(extractedDir, archivePath));
      const sourceBytes = readFileSync(join(companionRoot, 'compatibility', name));
      const canonicalBytes = readFileSync(join(companionRoot, '..', 'compatibility', name));
      assert.deepEqual(archivedBytes, sourceBytes, `${name} matches its generated companion copy`);
      assert.deepEqual(sourceBytes, canonicalBytes, `${name} matches the canonical repository source`);
      archivedAbilityMetadata.set(name, JSON.parse(archivedBytes.toString('utf8')));
    }
    const catalogueSourceSchema = archivedAbilityMetadata.get('ability-contracts-v1.schema.json');
    const catalogueSchemaResource = archivedAbilityMetadata.get('ability-contracts-v1-schema-resource.json');
    const discoverySchema = archivedAbilityMetadata.get('ability-discovery-v1.schema.json');
    const predicateDialect = archivedAbilityMetadata.get('ability-predicates-v1.schema.json');
    const catalogue = archivedAbilityMetadata.get('ability-contracts-v1.json');
    assert.equal(predicateDialect.$id, 'https://worldspeaker.invalid/schema/ability-predicates-v1.schema.json');
    assert.equal(predicateDialect.$vocabulary?.['https://worldspeaker.invalid/vocab/ability-predicates-v1'], true,
      'the packed dialect declares its custom predicate vocabulary required');
    assert.equal(discoverySchema.$schema, predicateDialect.$id,
      'the actual packed discovery schema retains the required predicate dialect');
    assert.equal(catalogueSchemaResource.$id, catalogue.$id,
      'the packed catalogue resource provides the catalogue identity used by all local references');
    assert.deepEqual(catalogue.contracts.map((contract) => contract.abilityId), [
      'worldspeaker.core.observe_player', 'worldspeaker.core.strike_lightning',
    ]);
    assert.ok(catalogue.contracts.every((contract) => contract.execution.implementationStatus === 'UNIMPLEMENTED' &&
      contract.execution.qualificationStatus === 'UNQUALIFIED' && contract.execution.availability === 'UNAVAILABLE'));
    const nestedPreset = execFileSync('tar', [
      '-xOzf', archive, `package/presets/${PRESET_ARCHIVE_NAME}`,
    ], { timeout: 10_000 });
    const sourcePreset = readFileSync(join(companionRoot, 'presets', PRESET_ARCHIVE_NAME));
    assert.deepEqual(nestedPreset, sourcePreset, 'TGZ contains byte-identical preset sidecar');
    assert.ok(verifyGodPresetArchiveBytes(nestedPreset).sha256.length === 64,
      'actual nested ZIP verifies against current allowlisted sources/schema/license');
    const sourceManifest = JSON.parse(readFileSync(join(companionRoot, 'package.json'), 'utf8'));
    const packedManifest = JSON.parse(execFileSync('tar', [
      '-xOzf', archive, 'package/package.json',
    ], { encoding: 'utf8', timeout: 10_000 }));
    const archiveFreshness = verifyCompanionPackageArchive({ sourceManifest, archivePath: archive });
    assert.deepEqual(archiveFreshness.abilityMetadataFiles, [
      'ability-contracts-v1.json', 'ability-contracts-v1.schema.json', 'ability-discovery-v1.schema.json',
    ]);
    assert.equal(archiveFreshness.version, sourceManifest.version);

    // Minimal SDK peer fixture: it permits plugin-entry import but is not a live
    // OpenClaw host and deliberately has no dependency tree (especially no ws).
    writeJson(join(sdkDir, 'package.json'), {
      name: 'openclaw',
      version: '2026.10.8',
      type: 'module',
      exports: {
        './plugin-sdk/channel-core': './channel-core.js',
        './plugin-sdk/routing': './routing.js',
        './plugin-sdk/channel-ingress-runtime': './channel-ingress-runtime.js',
        './plugin-sdk/reply-payload': './reply-payload.js',
      },
    });
    writeFileSync(join(sdkDir, 'channel-core.js'), [
      'export function defineChannelPluginEntry(entry) { return entry; }',
      'export function createChannelPluginBase() { return {}; }',
      'export function createChatChannelPlugin({ base }) { return base; }',
      '',
    ].join('\n'));
    writeFileSync(join(sdkDir, 'routing.js'), [
      'export function buildAgentSessionKey() { return "agent:test:session"; }',
      'export function normalizeAccountId(value) { return String(value ?? "").trim().toLowerCase() || "default"; }',
      'export function normalizeAgentId(value) { return String(value ?? "").trim().toLowerCase().replace(/[^a-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 64) || "main"; }',
      '',
    ].join('\n'));
    writeFileSync(join(sdkDir, 'channel-ingress-runtime.js'), [
      'export function defineStableChannelIngressIdentity(identity) { return identity; }',
      '',
    ].join('\n'));
    writeFileSync(join(sdkDir, 'reply-payload.js'), [
      'export function isReplyPayloadTerminalContent() { return true; }',
      '',
    ].join('\n'));
    writeJson(join(fixtureDir, 'package.json'), {
      name: 'worldspeaker-qa-010-install-fixture',
      version: '1.0.0',
      private: true,
      type: 'module',
      dependencies: {
        '@worldspeaker/openclaw-channel': `file:${archive}`,
        openclaw: `file:${sdkDir}`,
      },
    });

    execFileSync(npm, [
      'install', '--omit=dev', '--ignore-scripts', '--no-audit', '--no-fund',
    ], {
      cwd: fixtureDir,
      encoding: 'utf8',
      timeout: 150_000,
      env: { ...process.env, npm_config_audit: 'false', npm_config_fund: 'false' },
    });

    const fixturePackage = JSON.parse(readFileSync(join(fixtureDir, 'package.json'), 'utf8'));
    const hostPackage = JSON.parse(readFileSync(join(sdkDir, 'package.json'), 'utf8'));
    assert.equal(fixturePackage.dependencies.ws, undefined, 'fixture host must not declare ws');
    assert.equal(hostPackage.dependencies?.ws, undefined, 'SDK peer fixture must not pull in ws');

    const pluginDir = join(fixtureDir, 'node_modules', '@worldspeaker', 'openclaw-channel');
    const installedManifest = JSON.parse(readFileSync(join(pluginDir, 'package.json'), 'utf8'));
    assert.equal(installedManifest.dependencies?.ws, '8.22.0', 'packed manifest must declare runtime ws');
    assert.equal(installedManifest.devDependencies?.ws, undefined, 'ws must not be dev-only');
    assert.equal(installedManifest.dependencies?.ajv, '8.17.1', 'packed manifest must declare runtime Ajv');

    const requireFromPlugin = createRequire(join(pluginDir, 'dist', 'index.js'));
    const wsEntry = requireFromPlugin.resolve('ws');
    const wsFromFixture = relative(realpathSync(fixtureDir), realpathSync(wsEntry));
    assert.ok(!isAbsolute(wsFromFixture) && wsFromFixture !== '..' && !wsFromFixture.startsWith(`..${sep}`),
      `ws must resolve from the isolated install: ${wsEntry}`);
    assert.equal(JSON.parse(readFileSync(join(dirname(wsEntry), 'package.json'), 'utf8')).version, '8.22.0');
    const ajvEntry = realpathSync(requireFromPlugin.resolve('ajv/dist/2020'));
    const ajvFromFixture = relative(realpathSync(fixtureDir), ajvEntry);
    assert.ok(!isAbsolute(ajvFromFixture) && ajvFromFixture !== '..' && !ajvFromFixture.startsWith(`..${sep}`),
      `Ajv must resolve from the isolated packed install: ${ajvEntry}`);

    // Compile only resources extracted from the TGZ, using the adapter delivered
    // by the packed production module and Ajv resolved from the isolated install.
    const packagedCatalogue = await import(pathToFileURL(join(pluginDir, 'dist', 'ability-catalogue.js')).href);
    assert.equal(typeof packagedCatalogue.installAbilitySchemaPredicates, 'function',
      'the packed production adapter is available to the standalone schema harness');
    const Ajv2020 = requireFromPlugin('ajv/dist/2020').default;
    const noNetworkAjvOptions = {
      allErrors: true,
      strict: false,
      loadSchema: async () => { throw new Error('outbound schema resolution is forbidden'); },
    };
    const abilityAjv = new Ajv2020(noNetworkAjvOptions);
    assert.throws(() => packagedCatalogue.installAbilitySchemaPredicates(new Ajv2020(noNetworkAjvOptions)),
      /dialect resource must be installed before/u,
      'the production adapter refuses to run without the declared required dialect');
    abilityAjv.addMetaSchema(predicateDialect);
    packagedCatalogue.installAbilitySchemaPredicates(abilityAjv);
    abilityAjv.addSchema(catalogueSourceSchema);
    abilityAjv.addSchema(catalogueSchemaResource, catalogueSchemaResource.$id);
    abilityAjv.addSchema(discoverySchema);
    assert.ok(abilityAjv.getSchema(predicateDialect.$id), 'the extracted predicate dialect is installed as a meta-schema');
    assert.ok(abilityAjv.getSchema(discoverySchema.$id),
      'the actual extracted discovery schema compiles with its local contract reference and no network access');
    const validateCatalogue = abilityAjv.getSchema(catalogueSourceSchema.$id);
    assert.ok(validateCatalogue, 'the actual extracted catalogue validator compiles');
    assert.equal(validateCatalogue(catalogue), true,
      'the actual packed catalogue validates against its extracted strict schema');
    for (const contract of catalogue.contracts) {
      assert.ok(abilityAjv.getSchema(contract.inputSchema.$ref), `packed input ref resolves locally: ${contract.inputSchema.$ref}`);
      assert.ok(abilityAjv.getSchema(contract.resultSchema.$ref), `packed result ref resolves locally: ${contract.resultSchema.$ref}`);
    }

    const predicateKeywordNames = Object.keys(predicateDialect.properties)
      .filter((keyword) => keyword.startsWith('worldspeaker')).sort();
    assert.deepEqual([...packagedCatalogue.ABILITY_SCHEMA_PREDICATE_VOCABULARY.keywords].sort(), predicateKeywordNames,
      'the packed production adapter declares every keyword in the required vocabulary');
    for (const keyword of predicateKeywordNames) {
      assert.ok(abilityAjv.getKeyword(keyword), `the required predicate implementation is installed: ${keyword}`);
    }

    const observeFactsSchema = abilityAjv.getSchema(`${catalogueSchemaResource.$id}#/$defs/observeFacts`);
    assert.ok(observeFactsSchema, 'packed observation facts schema compiles from the extracted catalogue resource');
    const observation = {
      targetPlayerId: '00000000-0000-4000-8000-000000000043', observedAt: 0,
      health: 0.1, maxHealth: 0.2, dimension: 'minecraft:overworld',
      biome: 'minecraft:plains', holdingItem: 'minecraft:air',
    };
    assert.equal(observeFactsSchema(observation), true, 'production float32 and health coherence predicates accept valid facts');
    assert.equal(observeFactsSchema({ ...observation, health: 0.1000000000000001 }), false,
      'production float32 predicate rejects a noncanonical rounded value');
    assert.equal(observeFactsSchema({ ...observation, health: 2, maxHealth: 1 }), false,
      'production health-coherence predicate rejects health above maxHealth');

    const lightningFactsSchema = abilityAjv.getSchema(`${catalogueSchemaResource.$id}#/$defs/lightningFacts`);
    assert.ok(lightningFactsSchema, 'packed lightning facts schema compiles from the extracted catalogue resource');
    const lightningFacts = {
      actionId: '00000000-0000-4000-8000-000000000061',
      targetPlayerId: '00000000-0000-4000-8000-000000000043',
      dimension: 'minecraft:overworld', executedAt: null,
      coordinates: [33554431, 2047, -33554432], visualOnly: true,
      steps: [{ kind: 'presentation', state: 'confirmed' }],
    };
    assert.equal(lightningFactsSchema(lightningFacts), true, 'production coordinate predicate accepts representable BlockPos bounds');
    assert.equal(lightningFactsSchema({ ...lightningFacts, coordinates: [33554432, 0, 0] }), false,
      'production coordinate predicate rejects an unrepresentable BlockPos');

    const schemaResourceNames = [
      'ability-predicates-v1.schema.json',
      'ability-discovery-v1.schema.json',
      'ability-contracts-v1-schema-resource.json',
    ];
    const packagedSchemaResources = schemaResourceNames.map((name) => {
      const schemaJson = readFileSync(join(extractedDir, 'package', 'compatibility', name), 'utf8');
      const schema = JSON.parse(schemaJson);
      return { $id: schema.$id, sha256: createHash('sha256').update(schemaJson, 'utf8').digest('hex'), schemaJson };
    });
    const packagedSchemaResourceModule = await import(pathToFileURL(join(pluginDir, 'dist', 'ability-schema-resources.js')).href);
    packagedSchemaResourceModule.assertAbilitySchemaResources(packagedSchemaResources);
    assert.throws(() => packagedSchemaResourceModule.assertAbilitySchemaResources(packagedSchemaResources.slice(0, -1)),
      /dependency count/u, 'production discovery hash validation rejects a missing dependency');
    const wrongHash = packagedSchemaResources.map((resource) => ({ ...resource }));
    wrongHash[2].sha256 = '0'.repeat(64);
    assert.throws(() => packagedSchemaResourceModule.assertAbilitySchemaResources(wrongHash),
      /changed dependency/u, 'production discovery hash validation rejects a changed hash');
    const changedRefSchema = structuredClone(discoverySchema);
    changedRefSchema.$defs.discoveryEntry.properties.contract.$ref = `${catalogueSchemaResource.$id}#/$defs/missingContract`;
    const refAjv = new Ajv2020(noNetworkAjvOptions);
    refAjv.addMetaSchema(predicateDialect);
    packagedCatalogue.installAbilitySchemaPredicates(refAjv);
    refAjv.addSchema(catalogueSchemaResource, catalogueSchemaResource.$id);
    assert.throws(() => refAjv.compile(changedRefSchema), /missingContract/u,
      'schema compilation rejects a changed reference that is absent from the packed resources');

    const entry = await import(pathToFileURL(join(pluginDir, 'dist', 'index.js')).href);
    assert.equal(entry.default.id, 'worldspeaker');
    assert.strictEqual(entry.installAbilitySchemaPredicates, packagedCatalogue.installAbilitySchemaPredicates,
      'the real packed plugin entry imports through the fixture SDK and exports its production adapter');
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}
