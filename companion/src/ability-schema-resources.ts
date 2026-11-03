import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

export const ABILITY_PREDICATE_DIALECT_ID = 'https://worldspeaker.invalid/schema/ability-predicates-v1.schema.json';
export const ABILITY_DISCOVERY_SCHEMA_ID = 'https://worldspeaker.invalid/schema/ability-discovery-v1.schema.json';
export const ABILITY_CATALOGUE_SCHEMA_RESOURCE_ID = 'https://worldspeaker.invalid/schema/ability-contracts-v1.json';

export type AbilitySchemaResource = Readonly<{
  $id: string;
  sha256: string;
  schemaJson: string;
}>;

const RESOURCE_FILES = [
  ['ability-predicates-v1.schema.json', ABILITY_PREDICATE_DIALECT_ID],
  ['ability-discovery-v1.schema.json', ABILITY_DISCOVERY_SCHEMA_ID],
  ['ability-contracts-v1-schema-resource.json', ABILITY_CATALOGUE_SCHEMA_RESOURCE_ID],
] as const;

const resources: readonly AbilitySchemaResource[] = Object.freeze(RESOURCE_FILES.map(([file, expectedId]) => {
  const schemaJson = readFileSync(new URL(`../compatibility/${file}`, import.meta.url), 'utf8');
  if (schemaJson.startsWith('\ufeff')) throw new Error(`ability schema resource ${expectedId} has a BOM`);
  let parsed: unknown;
  try { parsed = JSON.parse(schemaJson); }
  catch { throw new Error(`ability schema resource ${expectedId} is not valid JSON`); }
  if (!isRecord(parsed) || parsed.$id !== expectedId || typeof parsed.$schema !== 'string') {
    throw new Error(`ability schema resource ${expectedId} has a changed $id or missing $schema`);
  }
  const sha256 = createHash('sha256').update(schemaJson, 'utf8').digest('hex');
  return Object.freeze({ $id: expectedId, sha256, schemaJson });
}));

export function abilityDiscoverySchemaResources(): readonly AbilitySchemaResource[] {
  return resources;
}

/** Rejects missing, reordered, changed, or hash-mismatched per-request resources. */
export function assertAbilitySchemaResources(value: unknown): asserts value is readonly AbilitySchemaResource[] {
  if (!Array.isArray(value) || value.length !== resources.length) {
    throw new Error('invalid ability discovery schema resources: dependency count');
  }
  for (let index = 0; index < resources.length; index += 1) {
    const received = value[index];
    const expected = resources[index]!;
    if (!isRecord(received) || received.$id !== expected.$id || received.sha256 !== expected.sha256 ||
        received.schemaJson !== expected.schemaJson || typeof received.schemaJson !== 'string' ||
        createHash('sha256').update(received.schemaJson, 'utf8').digest('hex') !== received.sha256) {
      throw new Error(`invalid ability discovery schema resources: changed dependency ${expected.$id}`);
    }
    let parsed: unknown;
    try { parsed = JSON.parse(received.schemaJson); }
    catch { throw new Error(`invalid ability discovery schema resources: malformed dependency ${expected.$id}`); }
    if (!isRecord(parsed) || parsed.$id !== expected.$id) {
      throw new Error(`invalid ability discovery schema resources: changed resource $id ${expected.$id}`);
    }
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
