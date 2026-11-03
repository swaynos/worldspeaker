import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const sourcePath = resolve(root, 'compatibility/ability-contracts-v1.json');
const sourceBytes = await readFile(sourcePath);
const sourceText = sourceBytes.toString('utf8');
if (sourceText.startsWith('\ufeff')) throw new Error('ability catalogue source must not contain a BOM');
const catalogue = JSON.parse(sourceText);
const sourceSchema = JSON.parse(await readFile(resolve(root, 'compatibility/ability-contracts-v1.schema.json'), 'utf8'));
const discoverySchema = JSON.parse(await readFile(resolve(root, 'compatibility/ability-discovery-v1.schema.json'), 'utf8'));
const predicateDialect = JSON.parse(await readFile(resolve(root, 'compatibility/ability-predicates-v1.schema.json'), 'utf8'));
const expectedContracts = [
  ['worldspeaker.core.observe_player', 'query'],
  ['worldspeaker.core.strike_lightning', 'effect'],
];
if (catalogue.catalogueVersion !== 1 || !Array.isArray(catalogue.contracts) || catalogue.contracts.length !== 2 ||
    catalogue.contracts.some((item, index) => item.abilityId !== expectedContracts[index]?.[0] ||
      item.kind !== expectedContracts[index]?.[1] || item.contractVersion !== 1)) {
  throw new Error('canonical ability catalogue must contain the two ordered selected v1 contracts');
}
if (predicateDialect.$id !== 'https://worldspeaker.invalid/schema/ability-predicates-v1.schema.json' ||
    predicateDialect.$vocabulary?.['https://worldspeaker.invalid/vocab/ability-predicates-v1'] !== true ||
    discoverySchema.$schema !== predicateDialect.$id ||
    discoverySchema.$defs?.discoveryEntry?.properties?.contract?.$ref !== `${catalogue.$id}#/$defs/contract`) {
  throw new Error('ability discovery resources must use the required local predicate dialect and catalogue contract reference');
}

const contractDefinitionNames = [
  'identity', 'permission', 'bounds', 'lightningBounds', 'preconditions', 'lightningPreconditions',
  'execution', 'reliability', 'lightningReliability', 'privacy', 'presentation',
  'contract', 'observeContract', 'lightningContract',
];
for (const name of contractDefinitionNames) {
  if (!sourceSchema.$defs?.[name] || catalogue.$defs[name]) {
    throw new Error(`ability catalogue resource cannot safely merge contract definition ${name}`);
  }
}
const validationResource = {
  $schema: predicateDialect.$id,
  $id: catalogue.$id,
  $defs: {
    ...catalogue.$defs,
    ...Object.fromEntries(contractDefinitionNames.map((name) => [name, sourceSchema.$defs[name]])),
  },
};
const validationResourceText = `${JSON.stringify(validationResource, null, 2)}\n`;

const require = createRequire(resolve(root, 'companion/package.json'));
const Ajv2020 = require('ajv/dist/2020').default;
const ajv = new Ajv2020({ allErrors: true, strict: false });
ajv.addKeyword({ keyword: 'worldspeakerAsciiOnly', schemaType: 'boolean', type: 'string',
  validate: (enabled, value) => !enabled || /^[\x00-\x7f]*$/u.test(value) });
ajv.addMetaSchema(predicateDialect);
ajv.addSchema(sourceSchema);
ajv.addSchema(validationResource);
ajv.addSchema(discoverySchema);
const validateCatalogue = ajv.compile(sourceSchema);
if (!validateCatalogue(catalogue)) {
  throw new Error(`canonical ability catalogue does not satisfy its strict schema: ${validateCatalogue.errors?.map((error) => `${error.instancePath} ${error.keyword}`).join('; ')}`);
}
if (!ajv.getSchema(discoverySchema.$id)) throw new Error('ability discovery schema could not be resolved locally');
for (const contract of catalogue.contracts) {
  if (!ajv.getSchema(contract.inputSchema.$ref) || !ajv.getSchema(contract.resultSchema.$ref)) {
    throw new Error(`ability schema resource is missing a local input or result reference for ${contract.abilityId}`);
  }
}

const outputFiles = new Map([
  [resolve(root, 'compatibility/ability-contracts-v1-schema-resource.json'), validationResourceText],
  [resolve(root, 'companion/src/generated/ability-catalogue.ts'),
    `/** Generated from compatibility/ability-contracts-v1.json; do not edit. */\n` +
    `export const ABILITY_CATALOGUE = ${JSON.stringify(catalogue, null, 2)} as const;\n`],
]);
for (const name of [
  'ability-contracts-v1.json',
  'ability-contracts-v1.schema.json',
  'ability-discovery-v1.schema.json',
  'ability-predicates-v1.schema.json',
]) {
  outputFiles.set(resolve(root, 'companion/compatibility', name), await readFile(resolve(root, 'compatibility', name), 'utf8'));
}
outputFiles.set(resolve(root, 'companion/compatibility/ability-contracts-v1.json'), sourceText);
outputFiles.set(resolve(root, 'companion/compatibility/ability-contracts-v1-schema-resource.json'), validationResourceText);

const check = process.argv.includes('--check');
for (const [path, expected] of outputFiles) {
  if (check) {
    let actual;
    try { actual = await readFile(path, 'utf8'); }
    catch { throw new Error(`stale or missing generated ability metadata: ${path}`); }
    if (actual !== expected) throw new Error(`stale generated ability metadata: ${path}`);
    continue;
  }
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, expected, 'utf8');
}
if (check) process.stdout.write('WS-ABILITY-CATALOGUE-FRESH: canonical source and packaged companions match.\n');
