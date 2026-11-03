import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import test from 'node:test';

const root = process.cwd();
const companion = resolve(root, 'companion');
const require = createRequire(resolve(companion, 'package.json'));
const Ajv = require('ajv').default;
const corpus = JSON.parse(await readFile(resolve(root, 'compatibility/ability-policy-v1-limits-corpus.json'), 'utf8'));
const startupSchema = JSON.parse(await readFile(resolve(root, 'compatibility/startup-config-v1.schema.json'), 'utf8'));
const startupFixtures = JSON.parse(await readFile(resolve(root, 'compatibility/startup-config-v1-fixtures.json'), 'utf8'));
const { parseAbilityPolicy } = await import(pathToFileURL(resolve(companion, 'dist/ability-policy.js')));
const { parseStrictJson } = await import(pathToFileURL(resolve(companion, 'dist/strict-json.js')));

const cases = [
  ...corpus.valid.map((item) => ({ ...item, expected: true })),
  ...corpus.invalid.map((item) => ({ ...item, expected: false })),
];

test('canonical startup schema binds optional limits to the exact abilityId and contractVersion', () => {
  const validate = new Ajv({ allErrors: true, strict: false }).compile(startupSchema);
  const baseBundle = startupFixtures.valid.find((fixture) => fixture.name === 'minimal-deny-all').bundle;

  for (const item of cases) {
    const bundle = structuredClone(baseBundle);
    bundle.abilityPolicy = JSON.parse(item.rawPolicyJson);
    const accepted = validate(bundle);
    if (item.schemaScope === 'parsed-number-projection') {
      assert.equal(JSON.parse(item.rawPolicyJson).grants[0].limits.requesterIntervalMs, 2_000,
        `${item.name}: JavaScript JSON parsing loses the original fractional token`);
      assert.equal(accepted, true, `${item.name}: schema validates parsed values, not source token precision`);
    } else {
      assert.equal(accepted, item.expected, `${item.name}: ${JSON.stringify(validate.errors)}`);
    }
  }
});

test('production TypeScript policy parser matches the shared limits corpus and preserves omitted defaults', () => {
  for (const item of cases) {
    let parsed = null;
    let failure = null;
    try {
      parsed = parseAbilityPolicy(parseStrictJson(item.rawPolicyJson, 8), 'fixture-character');
    } catch (error) {
      failure = error;
    }
    assert.equal(failure === null, item.expected, item.name);
    if (item.expectedLimits) {
      assert.deepEqual(parsed.grants[0].limits, item.expectedLimits, `${item.name}: effective defaults`);
    }
  }
});
