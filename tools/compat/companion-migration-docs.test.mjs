import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const readme = readFileSync(join(root, 'companion/README.md'), 'utf8');
const generatedGuide = readFileSync(join(root, 'docs/compatibility.md'), 'utf8');
const schema = JSON.parse(readFileSync(join(root, 'compatibility/startup-config-v1.schema.json'), 'utf8'));
const typescript = readFileSync(join(root, 'companion/src/startup-config.ts'), 'utf8');
const java = readFileSync(join(root, 'src/main/java/org/worldspeaker/compat/StartupConfig.java'), 'utf8');

test('companion migration walkthrough documents the current typed startup-v1 paths', () => {
  const section = markdownSection(readme, '## Migrating existing character settings');
  const guideSection = markdownSection(generatedGuide, '## Restart-only server configuration');
  const rows = presentationRows(section);
  const generatedRows = presentationRows(guideSection);
  const expected = [
    { formerKey: 'characterDisplayName', path: 'server.characterDisplayName' },
    { formerKey: 'characterAliases', path: 'server.characterAliases' },
    { formerKey: 'characterChatStyle', path: 'server.characterChatStyle' },
  ];

  assert.deepEqual(rows.map(({ formerKey, path }) => ({ formerKey, path })), expected,
    'the migration table must map exactly the three presentation properties to nested server paths');
  assert.deepEqual(generatedRows.map(({ formerKey, path }) => ({ formerKey, path })), expected,
    'the generated administrator guide must use the same v1 paths');
  assert.equal(new Set(rows.map(({ path }) => path)).size, rows.length, 'each documented path must be unique');

  const schemaServer = schema.$defs?.server;
  assert.ok(schemaServer, 'the shared schema must define the server object');
  const schemaProperties = schemaServer.properties;
  const schemaRequired = new Set(schemaServer.required);
  const tsServerValidator = typescript.match(/function validateServerSettings\(value: unknown\)[\s\S]*?\n\}/u)?.[0];
  assert.ok(tsServerValidator, 'the companion must retain its typed server-settings validator');
  const tsRequired = stringArrayFromDeclaration(tsServerValidator, 'required');
  const tsOptional = stringArrayFromDeclaration(tsServerValidator, 'optional');
  const javaAllowed = javaStringSet(java, 'serverAllowed');
  const javaRequired = javaStringSet(java, 'serverRequired');
  const tsAllowed = new Set([...tsRequired, ...tsOptional]);

  for (const { formerKey, path } of rows) {
    const property = path.slice('server.'.length);
    assert.equal(formerKey, property, `${path} must retain its leaf field name`);
    assert.ok(Object.hasOwn(schemaProperties, property), `${path} must exist in the shared schema`);
    assert.ok(tsAllowed.has(property), `${path} must be accepted by the companion typed validator`);
    assert.ok(javaAllowed.has(property), `${path} must be accepted by the Java startup parser`);
    assert.equal(schemaRequired.has(property), tsRequired.includes(property), `${path} schema/TypeScript requiredness must agree`);
    assert.equal(schemaRequired.has(property), javaRequired.has(property), `${path} schema/Java requiredness must agree`);
  }

  assert.equal(schemaRequired.has('characterDisplayName'), true, 'the display name is required');
  assert.equal(schemaProperties.characterAliases.type, 'array');
  assert.equal(schemaProperties.characterAliases.maxItems, 8);
  assert.deepEqual(schemaProperties.characterChatStyle.enum, ['plain', 'bracketed']);
  assert.match(rows.find(({ formerKey }) => formerKey === 'characterChatStyle').requirement,
    /`plain` when omitted/u);

  assert.match(readme, /Before\s+migrating, stop both services, back up the old settings privately/u);
  assert.match(section, /old flat Java properties are unsupported/u);
  assert.match(section, /Do not bulk-copy a legacy\s+properties file/u);
  assert.match(section, /coordinate the server and companion stop and\s+restart/u);
  assert.match(section, /editing the\s+files alone does not update names, aliases, or style/u);
  assert.doesNotMatch(readme, /owner-only server properties file/u);
  assert.doesNotMatch(section, /(?:bearerSecret|secret)\s*[:=]/iu,
    'the migration walkthrough must not contain a credential value or inline-secret setting');
  assert.match(readme, /`credentialFile` is a root-level path reference to a separate private file/u);
  assert.match(readme, /no credential value or\s+preset deployment values/u);
  assert.match(section, /`personaFile`, if present, is a root-level bundle reference, not a `server` field/u);
  assert.match(readme, /Configured `personaFile` remains blocked by CHAR-02's private publication gate/u);
  assert.match(readme, /before credential access or listener readiness/u);
  assert.match(section, /Omitting it retains no-persona behavior only/u);

  assert.match(guideSection, /coordinate the server and companion restart/u);
  assert.match(guideSection, /edits do not change a running snapshot/iu);
  assert.match(generatedGuide, /`server\.conversationAllowedPlayers` is a required JSON array/u);
  assert.doesNotMatch(generatedGuide, /conversationAllowedPlayers is an optional, comma-separated list/u);
  assert.doesNotMatch(guideSection, /Archivist|The Scribe|01234567-89ab-cdef-0123-456789abcdef/u,
    'the migration guide must not prescribe preset character, alias, or player values');
});

function presentationRows(section) {
  return [...section.matchAll(/^\| `([^`]+)` \| `(server\.[^`]+)` \| ([^|]+) \|$/gmu)]
    .map(([, formerKey, path, requirement]) => ({ formerKey, path, requirement: requirement.trim() }));
}

function markdownSection(markdown, heading) {
  const headingStart = markdown.indexOf(`${heading}\n`);
  assert.notEqual(headingStart, -1, `missing section: ${heading}`);
  const contentStart = headingStart + heading.length + 1;
  const nextHeading = markdown.indexOf('\n## ', contentStart);
  return markdown.slice(contentStart, nextHeading === -1 ? undefined : nextHeading);
}

function stringArrayFromDeclaration(source, declaration) {
  const match = source.match(new RegExp(`const ${declaration} = \\[([^\\]]*)\\];`, 'u'));
  assert.ok(match, `missing ${declaration} field declaration`);
  return [...match[1].matchAll(/'([^']+)'/gu)].map(([, value]) => value);
}

function javaStringSet(source, name) {
  const match = source.match(new RegExp(`Set<String> ${name} = Set\\.of\\(([\\s\\S]*?)\\);`, 'u'));
  assert.ok(match, `missing Java ${name} declaration`);
  return new Set([...match[1].matchAll(/"([^"]+)"/gu)].map(([, value]) => value));
}
