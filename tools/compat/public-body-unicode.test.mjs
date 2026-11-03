import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));

test('pinned Unicode sources and generated Java/TypeScript/schema property tables are reproducible', () => {
  const output = execFileSync('node', [
    resolve(root, 'tools/unicode/generate-public-body-unicode.mjs'), '--check',
  ], { cwd: root, encoding: 'utf8' });
  assert.match(output, /Unicode 15\.0\.0 public-body tables verified/u);
});
