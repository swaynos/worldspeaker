import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const companion = resolve(root, 'companion');
const require = createRequire(resolve(companion, 'package.json'));
const Ajv2020 = require('ajv/dist/2020').default;
const DRAFT_2020_SCHEMA_ID = 'https://json-schema.org/draft/2020-12/schema';
const PREDICATE_DIALECT_ID = 'https://worldspeaker.invalid/schema/ability-predicates-v1.schema.json';
const CATALOGUE_ID = 'https://worldspeaker.invalid/schema/ability-contracts-v1.json';
const CATALOGUE_SCHEMA_ID = 'https://worldspeaker.invalid/schema/ability-contracts-v1.schema.json';
const DISCOVERY_SCHEMA_ID = 'https://worldspeaker.invalid/schema/ability-discovery-v1.schema.json';
const PREDICATE_VOCABULARY_ID = 'https://worldspeaker.invalid/vocab/ability-predicates-v1';
const EXPECTED_DIALECT_VOCABULARY = {
  'https://json-schema.org/draft/2020-12/vocab/core': true,
  'https://json-schema.org/draft/2020-12/vocab/applicator': true,
  'https://json-schema.org/draft/2020-12/vocab/unevaluated': true,
  'https://json-schema.org/draft/2020-12/vocab/validation': true,
  'https://json-schema.org/draft/2020-12/vocab/meta-data': false,
  'https://json-schema.org/draft/2020-12/vocab/format-annotation': false,
  [PREDICATE_VOCABULARY_ID]: true,
};

test('canonical and packaged ability metadata remain byte-for-byte fresh', async () => {
  execFileSync(process.execPath, ['tools/abilities/generate-ability-contracts.mjs', '--check'], { cwd: root, stdio: 'pipe' });
  const source = await readFile(resolve(root, 'compatibility/ability-contracts-v1.json'));
  for (const name of [
    'ability-contracts-v1.json', 'ability-contracts-v1.schema.json', 'ability-discovery-v1.schema.json',
  ]) {
    assert.deepEqual(await readFile(resolve(companion, 'compatibility', name)),
      await readFile(resolve(root, 'compatibility', name)), `${name} is the same packaged source`);
  }
  const generated = await readFile(resolve(companion, 'src/generated/ability-catalogue.ts'), 'utf8');
  assert.equal(generated, `/** Generated from compatibility/ability-contracts-v1.json; do not edit. */\n` +
    `export const ABILITY_CATALOGUE = ${JSON.stringify(JSON.parse(source.toString('utf8')), null, 2)} as const;\n`);
});

test('required Worldspeaker dialect and local catalogue/discovery references compile without network retrieval', async () => {
  const source = JSON.parse(await readFile(resolve(root, 'compatibility/ability-contracts-v1.json'), 'utf8'));
  const sourceSchema = JSON.parse(await readFile(resolve(root, 'compatibility/ability-contracts-v1.schema.json'), 'utf8'));
  const catalogueResource = JSON.parse(await readFile(resolve(root, 'compatibility/ability-contracts-v1-schema-resource.json'), 'utf8'));
  const discoverySchema = JSON.parse(await readFile(resolve(root, 'compatibility/ability-discovery-v1.schema.json'), 'utf8'));

  const predicateDialect = JSON.parse(await readFile(resolve(root, 'compatibility/ability-predicates-v1.schema.json'), 'utf8'));
  assertPredicateDialectHeader(predicateDialect);
  assert.equal(source.$schema, DRAFT_2020_SCHEMA_ID);
  assert.equal(source.$id, CATALOGUE_ID);
  assert.equal(sourceSchema.$schema, source.$schema);
  assert.equal(sourceSchema.$id, CATALOGUE_SCHEMA_ID);
  assert.equal(catalogueResource.$schema, predicateDialect.$id);
  assert.equal(catalogueResource.$id, source.$id);
  assert.equal(discoverySchema.$schema, predicateDialect.$id);
  assert.equal(discoverySchema.$id, DISCOVERY_SCHEMA_ID);

  const discoveryResourceId = (name) => discoverySchema.$defs?.[name]?.allOf?.[1]?.properties?.$id?.const;
  assert.deepEqual([
    discoveryResourceId('predicateDialectResource'),
    discoveryResourceId('discoverySchemaResource'),
    discoveryResourceId('catalogueSchemaResource'),
  ], [predicateDialect.$id, discoverySchema.$id, catalogueResource.$id],
  'discovery pins the dialect, discovery schema, and catalogue resources in order');
  assert.equal(discoverySchema.$defs.discoveryEntry.properties.contract.$ref,
    `${catalogueResource.$id}#/$defs/contract`);
  assert.equal(discoverySchema.$defs.observeDiscoveryEntry.allOf[1].properties.contract.$ref,
    `${catalogueResource.$id}#/$defs/observeContract`);
  assert.equal(discoverySchema.$defs.lightningDiscoveryEntry.allOf[1].properties.contract.$ref,
    `${catalogueResource.$id}#/$defs/lightningContract`);
  for (const contract of source.contracts) {
    assert.ok(contract.inputSchema.$ref.startsWith(`${catalogueResource.$id}#/$defs/`));
    assert.ok(contract.resultSchema.$ref.startsWith(`${catalogueResource.$id}#/$defs/`));
  }

  const ajv = new Ajv2020({ allErrors: true, strict: false });
  ajv.addKeyword({ keyword: 'worldspeakerAsciiOnly', schemaType: 'boolean', type: 'string',
    validate: (enabled, value) => !enabled || /^[\x00-\x7f]*$/u.test(value) });
  ajv.addMetaSchema(predicateDialect);
  ajv.addSchema(sourceSchema);
  ajv.addSchema(catalogueResource);
  ajv.addSchema(discoverySchema);
  const validateSource = ajv.getSchema(sourceSchema.$id);
  const validateDiscovery = ajv.getSchema(discoverySchema.$id);
  assert.equal(typeof validateSource, 'function');
  assert.equal(typeof validateDiscovery, 'function');
  assert.equal(validateSource(source), true, JSON.stringify(validateSource.errors));
  for (const id of [predicateDialect.$id, source.$id, sourceSchema.$id, discoverySchema.$id]) {
    assert.ok(id.startsWith('https://worldspeaker.invalid/schema/'), `stable local schema ID: ${id}`);
  }
});

test('predicate dialect assertions reject missing or non-required vocabulary and a changed dialect ID', async () => {
  const predicateDialect = JSON.parse(await readFile(resolve(root, 'compatibility/ability-predicates-v1.schema.json'), 'utf8'));
  assertPredicateDialectHeader(predicateDialect);

  const missingPredicateVocabulary = structuredClone(predicateDialect);
  delete missingPredicateVocabulary.$vocabulary[PREDICATE_VOCABULARY_ID];
  assert.throws(() => assertPredicateDialectHeader(missingPredicateVocabulary));

  const optionalPredicateVocabulary = structuredClone(predicateDialect);
  optionalPredicateVocabulary.$vocabulary[PREDICATE_VOCABULARY_ID] = false;
  assert.throws(() => assertPredicateDialectHeader(optionalPredicateVocabulary));

  const missingDraftCoreVocabulary = structuredClone(predicateDialect);
  delete missingDraftCoreVocabulary.$vocabulary['https://json-schema.org/draft/2020-12/vocab/core'];
  assert.throws(() => assertPredicateDialectHeader(missingDraftCoreVocabulary));

  const changedDialectId = structuredClone(predicateDialect);
  changedDialectId.$id = 'https://worldspeaker.invalid/schema/other-predicates.schema.json';
  assert.throws(() => assertPredicateDialectHeader(changedDialectId));
});

test('operation result references pin their own ability IDs across every lifecycle state', async () => {
  const catalogue = JSON.parse(await readFile(resolve(root, 'compatibility/ability-contracts-v1.json'), 'utf8'));
  const resource = JSON.parse(await readFile(resolve(root, 'compatibility/ability-contracts-v1-schema-resource.json'), 'utf8'));
  const predicateDialect = JSON.parse(await readFile(resolve(root, 'compatibility/ability-predicates-v1.schema.json'), 'utf8'));
  const corpus = JSON.parse(await readFile(resolve(root, 'compatibility/ability-results-v1-corpus.json'), 'utf8'));
  const fetchDeniedAjv = new Ajv2020({
    allErrors: true,
    strict: false,
    loadSchema: async (uri) => { throw new Error(`unexpected remote schema retrieval: ${uri}`); },
  });
  fetchDeniedAjv.addMetaSchema(predicateDialect);
  fetchDeniedAjv.addSchema(resource);

  const resultValidators = new Map([
    ['worldspeaker.core.observe_player', fetchDeniedAjv.getSchema(`${resource.$id}#/$defs/observeResult`)],
    ['worldspeaker.core.strike_lightning', fetchDeniedAjv.getSchema(`${resource.$id}#/$defs/lightningResult`)],
  ]);
  assert.ok([...resultValidators.values()].every((validate) => typeof validate === 'function'),
    'both advertised result references compile using only the received catalogue resource');
  const states = new Set();
  const factForms = new Set();
  const operations = new Set();
  const positiveResults = new Map();
  for (const item of corpus.positiveCases) {
    const rawTail = item.tail.replace(/\{\{([A-Za-z]+)\}\}/gu, (placeholder, name) => {
      assert.ok(Object.hasOwn(corpus.facts, name), `unknown result fixture token ${placeholder}`);
      return corpus.facts[name];
    });
    const result = JSON.parse(corpus.prefix + rawTail);
    const validate = resultValidators.get(result.abilityId);
    assert.ok(validate, `known operation result reference: ${item.name}`);
    assert.equal(validate(result), true, `${item.name}: correct direct result reference`);
    const wrongAbilityId = result.abilityId === 'worldspeaker.core.observe_player'
      ? 'worldspeaker.core.strike_lightning'
      : 'worldspeaker.core.observe_player';
    assert.equal(validate({ ...result, abilityId: wrongAbilityId }), false,
      `${item.name}: a known but different operation ID is rejected by the advertised result reference`);
    states.add(result.state);
    factForms.add(result.facts === null ? 'null' : 'facts');
    operations.add(result.abilityId);
    positiveResults.set(item.name, result);
  }
  assert.deepEqual([...states].sort(), [
    'admitted', 'cancelled', 'executed', 'expired', 'failed', 'partial', 'rejected', 'submitted', 'unknown',
  ]);
  assert.deepEqual([...factForms].sort(), ['facts', 'null']);
  assert.deepEqual([...operations].sort(), ['worldspeaker.core.observe_player', 'worldspeaker.core.strike_lightning']);

  const query = positiveResults.get('rejected invalid_input');
  const lightning = positiveResults.get('lightning failed with failed presentation');
  assert.ok(query && lightning);
  const queryActionSwap = { ...query, actionId: query.queryId };
  delete queryActionSwap.queryId;
  assert.equal(resultValidators.get('worldspeaker.core.observe_player')(queryActionSwap), false,
    'the query result reference does not accept an actionId in place of queryId');
  const lightningQuerySwap = { ...lightning, queryId: lightning.actionId };
  delete lightningQuerySwap.actionId;
  assert.equal(resultValidators.get('worldspeaker.core.strike_lightning')(lightningQuerySwap), false,
    'the action result reference does not accept a queryId in place of actionId');

  assert.equal(resource.$defs.baseResult.properties.contractVersion.const, 1,
    'the inherited required contract version remains pinned without adding a separate result field');
  assert.ok(resource.$defs.baseResult.required.includes('contractVersion'));
  assert.equal(catalogue.$defs.observeResult.allOf[1].properties.abilityId.const, 'worldspeaker.core.observe_player');
  assert.equal(catalogue.$defs.lightningResult.allOf[1].properties.abilityId.const, 'worldspeaker.core.strike_lightning');
});

test('the source fixes the selected operations, envelope ceilings, and unavailable execution statuses', async () => {
  const catalogue = JSON.parse(await readFile(resolve(root, 'compatibility/ability-contracts-v1.json'), 'utf8'));
  assert.deepEqual(catalogue.contracts.map(({ abilityId, contractVersion, kind }) => [abilityId, contractVersion, kind]), [
    ['worldspeaker.core.observe_player', 1, 'query'],
    ['worldspeaker.core.strike_lightning', 1, 'effect'],
  ]);
  assert.equal(catalogue.limits.maxRequestUtf8Bytes, 4096);
  assert.equal(catalogue.limits.maxResultUtf8Bytes, 4096);
  assert.equal(catalogue.limits.maxDiscoveryUtf8Bytes, 65536);
  assert.ok(catalogue.contracts.every(({ execution }) => execution.implementationStatus === 'UNIMPLEMENTED' &&
    execution.qualificationStatus === 'UNQUALIFIED' && execution.availability === 'UNAVAILABLE'));
});

function assertPredicateDialectHeader(predicateDialect) {
  assert.equal(predicateDialect.$schema, DRAFT_2020_SCHEMA_ID,
    'the Worldspeaker dialect is itself defined against Draft 2020-12');
  assert.equal(predicateDialect.$id, PREDICATE_DIALECT_ID,
    'the Worldspeaker predicate dialect has its stable resource ID');
  assert.ok(predicateDialect.allOf?.some((schema) => schema.$ref === DRAFT_2020_SCHEMA_ID),
    'the Worldspeaker dialect builds on the Draft 2020-12 meta-schema');
  assert.deepEqual(predicateDialect.$vocabulary, EXPECTED_DIALECT_VOCABULARY,
    'Draft 2020-12 core vocabularies and the Worldspeaker predicate vocabulary retain their required/optional declarations');
}
