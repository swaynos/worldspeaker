import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import test from 'node:test';

const root = process.cwd();
const docs = await readFile(resolve(root, 'docs/ability-policy.md'), 'utf8');
const normalizedDocs = docs.replace(/\s+/gu, ' ');
const rootReadme = await readFile(resolve(root, 'README.md'), 'utf8');
const companionReadme = await readFile(resolve(root, 'companion/README.md'), 'utf8');
const startupSchema = JSON.parse(await readFile(resolve(root, 'compatibility/startup-config-v1.schema.json'), 'utf8'));
const { parseAbilityPolicy } = await import(pathToFileURL(resolve(root, 'companion/dist/ability-policy.js')));

test('ability administration guide is linked from both READMEs and preserves deny-only status', () => {
  assert.match(rootReadme, /\[Private ability-policy administration procedure\]\(docs\/ability-policy\.md\)/u);
  assert.match(companionReadme, /\[ability-policy administration procedure\]\(\.\.\/docs\/ability-policy\.md\)/u);
  assert.match(docs, /UNIMPLEMENTED \/ UNQUALIFIED \/ UNAVAILABLE/u);
  assert.match(docs, /`toolsAllow: \[\]`/u);
  assert.match(docs, /`effectStarted: false`/u);
  assert.match(docs, /No applicable|complete-grant evaluation/iu);
  assert.ok(normalizedDocs.includes('There is no live policy-edit API, control UI, provider adapter, reload command'));
  assert.match(docs, /same exact startup-bundle bytes/u);
  assert.match(docs, /different bytes are not coherent/u);
  assert.ok(normalizedDocs.includes('A coordinated restart retires the old peer generation'));
  assert.doesNotMatch(docs, /(?:bearerSecret|password|token)\s*[:=]\s*[A-Za-z0-9_-]{32,}/iu);
  assert.doesNotMatch(docs, /(?:\/Users\/|\/home\/|C:\\Users\\)/u);
});

test('ability policy JSON examples parse through the production TypeScript policy validator', () => {
  const blocks = [...docs.matchAll(/```json\s*\n([\s\S]*?)\n```/gu)].map((match) => JSON.parse(match[1]));
  assert.equal(blocks.length, 5);
  const [denyAll, observe, lightning, damageGrant, conflict] = blocks;
  for (const [name, value] of [['deny-all', denyAll], ['observe', observe], ['lightning', lightning], ['conflict', conflict]]) {
    const policy = parseAbilityPolicy(value, 'example-character');
    assert.equal(policy.schemaVersion, 1, name);
  }

  const observeGrant = parseAbilityPolicy(observe, 'example-character').grants[0];
  assert.equal(observeGrant.id, 'observe-example-self');
  assert.deepEqual(observeGrant.region, { minX: -128, maxXExclusive: 128, minZ: -128, maxZExclusive: 128 });
  assert.deepEqual(observeGrant.limits, { requesterIntervalMs: 5_000, characterIntervalMs: 1_000 });
  assert.deepEqual(observeGrant.otherTargetIds, []);

  const lightningGrant = parseAbilityPolicy(lightning, 'example-character').grants[0];
  assert.equal(lightningGrant.allowVisual, true);
  assert.equal(lightningGrant.allowDamage, false);
  assert.deepEqual(lightningGrant.limits,
    { requesterCooldownMs: 60_000, targetCooldownMs: 60_000, characterIntervalMs: 2_000 });

  const damagePolicy = parseAbilityPolicy({ schemaVersion: 1, revision: 4, grants: [damageGrant], denials: [],
    protection: { externalClaimsMode: 'REQUIRED' } }, 'example-character');
  assert.equal(damagePolicy.grants[0].allowDamage, true);
  assert.equal(damagePolicy.grants[0].maxDamage, 2);

  const conflictPolicy = parseAbilityPolicy(conflict, 'example-character');
  assert.equal(conflictPolicy.grants.length, 1);
  assert.equal(conflictPolicy.denials.length, 1);
  assert.equal(conflictPolicy.denials[0].abilityId, conflictPolicy.grants[0].abilityId);
});

test('documented new bounds agree with the shared startup schema', () => {
  const definitions = startupSchema.$defs;
  assert.deepEqual(Object.keys(definitions.abilityRegion.properties).sort(),
    ['maxXExclusive', 'maxZExclusive', 'minX', 'minZ']);
  assert.equal(definitions.observeLimits.properties.requesterIntervalMs.minimum, 2_000);
  assert.equal(definitions.observeLimits.properties.requesterIntervalMs.maximum, 300_000);
  assert.equal(definitions.observeLimits.properties.characterIntervalMs.minimum, 250);
  assert.equal(definitions.lightningLimits.properties.requesterCooldownMs.minimum, 30_000);
  assert.equal(definitions.lightningLimits.properties.targetCooldownMs.maximum, 300_000);
  assert.equal(definitions.lightningLimits.properties.characterIntervalMs.minimum, 1_000);
  for (const phrase of [
    'half-open X/Z rectangle',
    'signed mathematical JSON-safe integers',
    'no burst accumulation',
    'ASCII lexical order',
    'does not fall through',
    'complete 9-by-9 footprint',
    'normative world-instance UUIDs',
    'atomic limits',
  ]) assert.ok(normalizedDocs.includes(phrase), `guide documents ${phrase}`);
});
