import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, realpathSync, readdirSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const packager = join(root, 'tools/compat/package-status-docs.mjs');
const presetArchive = join(root, 'build/presets/worldspeaker-god-preset-v1.zip');

function resolveLocalMarkdownLinks(document, baseDirectory, allowedRoot, label) {
  const localTargets = [];
  const externalTargets = [];
  for (const [, rawTarget] of document.matchAll(/\[[^\]]+\]\(([^)]+)\)/gs)) {
    let target = rawTarget.trim();
    if (target.startsWith('<')) {
      const closingBracket = target.indexOf('>');
      if (closingBracket < 0) throw new Error(`${label} has a malformed Markdown link`);
      target = target.slice(1, closingBracket);
    } else {
      target = target.split(/\s+/, 1)[0];
    }
    if (!target) continue;
    if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(target)) {
      externalTargets.push(target);
      continue;
    }

    const pathPart = target.split(/[?#]/, 1)[0];
    if (!pathPart) continue;
    let decodedPath;
    try {
      decodedPath = decodeURIComponent(pathPart);
    } catch {
      throw new Error(`${label} has a malformed URI-encoded Markdown link: ${target}`);
    }
    if (decodedPath.startsWith('/') || decodedPath.includes('\\') || /^[a-z]:/i.test(decodedPath)) {
      throw new Error(`${label} has an absolute local Markdown link: ${target}`);
    }

    const resolvedTarget = resolve(baseDirectory, decodedPath);
    const fromAllowedRoot = relative(allowedRoot, resolvedTarget);
    if (isAbsolute(fromAllowedRoot) || fromAllowedRoot === '..' || fromAllowedRoot.startsWith(`..${sep}`)) {
      throw new Error(`${label} has a local Markdown link outside its allowed root: ${target}`);
    }
    let realTarget;
    try {
      realTarget = realpathSync(resolvedTarget);
      if (!statSync(realTarget).isFile()) throw new Error('not a file');
    } catch {
      throw new Error(`${label} has a broken local Markdown link: ${target}`);
    }
    const realFromAllowedRoot = relative(realpathSync(allowedRoot), realTarget);
    if (isAbsolute(realFromAllowedRoot) || realFromAllowedRoot === '..' || realFromAllowedRoot.startsWith(`..${sep}`)) {
      throw new Error(`${label} has a local Markdown link outside its allowed root: ${target}`);
    }
    localTargets.push(target);
  }
  return { localTargets, externalTargets };
}

function startupFollowupContract(schema, label) {
  assert.equal(schema.properties.server.$ref, '#/$defs/server', `${label} must use the v1 server schema`);
  const server = schema.$defs.server;
  const followup = server.properties.followupWindowSeconds;
  assert.ok(followup, `${label} must define server.followupWindowSeconds`);
  assert.ok(!server.required.includes('followupWindowSeconds'),
    `${label} must keep server.followupWindowSeconds optional`);
  assert.equal(followup.type, 'integer', `${label} follow-up value must be an integer`);
  assert.equal(followup.minimum, 0, `${label} follow-up minimum must be 0`);
  assert.equal(followup.maximum, 30, `${label} follow-up maximum must be 30`);
  return followup;
}

function followupParagraph(document, fieldPath, label) {
  const paragraph = document.split(/\n\s*\n/)
    .find((part) => part.includes(`\`${fieldPath}\``) && part.includes('is optional'));
  assert.ok(paragraph, `${label} must document \`${fieldPath}\``);
  return paragraph;
}

function assertServerFollowupDocumentation(document, label, contract) {
  const paragraph = followupParagraph(document, 'server.followupWindowSeconds', label).replace(/\s+/g, ' ').trim();
  assert.ok(paragraph.includes('`server.followupWindowSeconds` is optional and defaults to 30;'),
    `${label} must document the typed path, optionality, and default`);
  assert.ok(paragraph.includes(`${contract.minimum} disables recognition`),
    `${label} must document that the schema minimum disables recognition`);
  assert.ok(paragraph.includes(`unsigned whole numbers ${contract.minimum + 1}..${contract.maximum} set the hard duration in seconds`),
    `${label} must document the remaining schema bounds`);
}

function assertPlayerFollowupDocumentation(document, label, contract) {
  const paragraph = followupParagraph(document, 'followupWindowSeconds', label).replace(/\s+/g, ' ').trim();
  assert.ok(paragraph.includes('`followupWindowSeconds` is optional: absent means `30`, `0` disables follow-up recognition,'),
    `${label} must document the optional field and default`);
  assert.ok(paragraph.includes(`values \`${contract.minimum + 1}\`–\`${contract.maximum}\` set the hard window in seconds`),
    `${label} must document the schema bounds`);
}

test('player instructions, generated server requirements, and packaged links agree', () => {
  const generatorSource = readFileSync(join(root, 'tools/compat/generate-compatibility-docs.mjs'), 'utf8');
  const guide = readFileSync(join(root, 'docs/player-compatibility.md'), 'utf8');
  const requirements = readFileSync(join(root, 'docs/compatibility.md'), 'utf8');
  const readme = readFileSync(join(root, 'README.md'), 'utf8');
  const manifest = JSON.parse(readFileSync(join(root, 'compatibility/deployment.json'), 'utf8'));
  const startupSchema = JSON.parse(readFileSync(join(root, 'compatibility/startup-config-v1.schema.json'), 'utf8'));
  const followupContract = startupFollowupContract(startupSchema, 'startup-config-v1.schema.json');
  const renderedGeneratorSource = generatorSource.replaceAll('\\`', '`');

  const originalBadGuide = guide.replace(
    '[administrator migration notes](compatibility.md)',
    '[administrator migration notes](../compatibility.md)',
  );
  assert.notEqual(originalBadGuide, guide, 'the original bad relative link must be represented by the regression');
  assert.throws(
    () => resolveLocalMarkdownLinks(originalBadGuide, join(root, 'docs'), root, 'mutated source player guide'),
    /broken local Markdown link: \.\.\/compatibility\.md/,
  );
  resolveLocalMarkdownLinks(guide, join(root, 'docs'), root, 'source player guide');

  assert.match(generatorSource, /unmodified Minecraft Java/);
  assert.match(generatorSource, /no client Fabric Loader, Fabric API, or Worldspeaker client mod/);
  assert.match(generatorSource, /Another server mod may independently require/);
  assert.match(generatorSource, /\[player compatibility guide\]\(player-compatibility\.md\)/);
  assert.match(generatorSource, /conversationAllowedPlayers/);
  assert.match(generatorSource, /canonical UUIDs/);
  assert.match(generatorSource, /Natural-address and recognized follow-up text remains ordinary public signed/);
  assertServerFollowupDocumentation(renderedGeneratorSource, 'compatibility-doc generator', followupContract);
  assert.match(generatorSource, /executionBudgetSeconds.*defaults to 30/s);
  assert.match(generatorSource, /exact limited English\s+whole-line rule/);
  assert.match(generatorSource, /up to eight configured/);
  assert.match(readme, /\[client and command guide\]\(docs\/player-compatibility\.md\)/);
  assert.match(readme, /\[God preset data ZIP\]\(build\/presets\/worldspeaker-god-preset-v1\.zip\)/);
  assert.match(readme, /\[preset instructions\]\(presets\/god\/README\.md\)/);
  assert.match(readme, /not a client resource pack, installer, or activation mechanism/);
  assert.match(guide, /unmodified Minecraft client/i);
  assert.match(guide, /You do not install Worldspeaker, Fabric Loader, or\s+Fabric API on your client/i);
  assert.match(guide, /does not request a resource pack or add\s+custom rendering or a Worldspeaker Minecraft packet extension/);
  assert.match(guide, /server requirements/);
  assert.match(guide, /\[God preset data ZIP\]\(\.\.\/build\/presets\/worldspeaker-god-preset-v1\.zip\)/);
  assert.match(guide, /\[preset instructions\]\(\.\.\/presets\/god\/README\.md\)/);
  assert.match(guide, /INACTIVE \/ BLOCKED/);
  assert.match(guide, /must never\s+be used by deleting an existing reference/);
  assert.match(guide, /\*\*not\*\* a Minecraft client resource pack/);
  assert.match(guide, /realm, private workspace,\s+administrator history, client, or provider data/);
  assert.match(guide, /other mods may have separate client requirements/i);
  assert.match(guide, /ask its administrator\s+which mod requires\s+it/i);
  assert.match(guide, /`\/worldspeaker`/);
  assert.match(guide, /`\/worldspeaker ask <text>`/);
  assert.match(guide, /512 UTF-16\s+units/);
  assert.match(guide, /conversationAllowedPlayers/);
  assert.match(guide, /up to 32 unique canonical UUIDs/);
  assert.match(guide, /operator status alone does not\s+grant access/);
  assert.match(guide, /Natural-address and recognized follow-up chat is normal public signed\s+Minecraft chat, including after a private `\/worldspeaker ask`/);
  assert.match(guide, /Only safely named input or an exact recognized follow-up\s+leaves the server for the character/);
  assert.match(guide, /up to eight\s+configured aliases/);
  assert.match(guide, /U\+200C ZWNJ,\s+U\+200D ZWJ, variation selectors, and embedded U\+2800 are rejected/);
  assert.match(guide, /visible joined emoji such as `👩‍🚀`/);
  assert.match(guide, /complete leading online-player profile token takes precedence/);
  assert.match(guide, /these whole-line English\s+phrases, with optional one/);
  assert.match(guide, /not semantic understanding/);
  assert.match(guide, /`\/worldspeaker end` is a local recognition reset only/);
  assert.match(guide, /remote OpenClaw session reset is not implemented/);
  assert.match(guide, /`executionBudgetSeconds` is optional and defaults to `30`/);
  assertPlayerFollowupDocumentation(guide, 'player compatibility guide', followupContract);
  assert.match(guide, /fixed at 15 seconds from the original receipt/);
  assert.match(guide, /does\s+not prove that OpenClaw or a provider stopped executing/);
  assert.match(guide, /player\s+UUID and profile name, event kind, dimension, original capture times/);
  assert.match(guide, /They contain no coordinates or world scans; sampled movement\s+is not continuous tracking/);
  assert.match(guide, /four retained events and 4 KiB per player, 2 KiB per event/);
  assert.match(guide, /configured OpenClaw agent can receive them and, depending on the administrator's\s+provider configuration/);
  assert.match(guide, /Mandatory dialogue-freshness checks remain active even when optional\s+attention is disabled/);
  assert.doesNotMatch(guide, /no\s+follow-up window\s+is implemented/i);
  assert.match(guide, /only to the requesting player's\s+current connection/);
  assert.match(guide, /`\[Worldspeaker\] <name>:`/);
  assert.match(guide, /non-overlay system chat/);
  assert.match(guide, /both unavailable/);
  assert.match(guide, /selected and unqualified/);
  assert.match(guide, /latest COMPAT-01 QA decision/);
  assert.doesNotMatch(guide, /historical builder report.*current proof/i);
  for (const ability of manifest.unavailableAbilities) assert.ok(guide.includes(`\`${ability}\``));

  assert.match(requirements, /unmodified Minecraft Java `1\.21\.1` client/);
  assert.match(requirements, /no client Fabric Loader, Fabric API, or Worldspeaker client mod/);
  assert.match(requirements, /Worldspeaker does not require a resource pack, custom renderer, or custom Minecraft packet extension/);
  assert.match(requirements, /server requirements only/);
  assert.match(requirements, /Another server mod may independently require/);
  assert.match(requirements, /\[player compatibility guide\]\(player-compatibility\.md\)/);
  assert.match(requirements, /conversationAllowedPlayers/);
  assert.match(requirements, /32\s+unique canonical UUIDs/);
  assert.match(requirements, /characterAliases/);
  assert.match(requirements, /up to eight configured\s+aliases anywhere in ordinary chat/);
  assert.match(requirements, /U\+200C ZWNJ, U\+200D ZWJ, variation selectors, and embedded U\+2800 are rejected/);
  assert.match(requirements, /visible joined emoji remain supported in reply text/);
  assert.match(requirements, /Natural-address and recognized follow-up text remains ordinary public signed\s+Minecraft chat/);
  assertServerFollowupDocumentation(requirements, 'rendered server requirements', followupContract);
  assert.match(requirements, /`server\.executionBudgetSeconds` is optional and defaults to `30`/);
  assert.match(requirements, /fixed at 15 seconds from the original receipt/);
  assert.match(requirements, /Natural-address and follow-up input is public/);
  assert.match(requirements, /`\/worldspeaker end` locally closes recognition for a granted player/);
  assert.match(requirements, /Optional player attention is \*\*disabled by default\*\*/);
  assert.match(requirements, /`player_connection`, `player_dimension`,\s+`player_death`, `player_respawn`, and `player_movement`/);

  const packageDirectory = mkdtempSync(join(tmpdir(), 'worldspeaker-player-docs-'));
  try {
    const packaged = spawnSync(process.execPath, [packager, packageDirectory], { cwd: root, encoding: 'utf8', timeout: 15_000 });
    assert.ifError(packaged.error);
    assert.equal(packaged.status, 0, packaged.stderr);
     assert.deepEqual(readdirSync(packageDirectory).sort(), [
       'COMPAT-01.md', 'LICENSE', 'QA-STATUS.md', 'compatibility.md', 'deployment.json', 'god-preset-README.md',
       'player-compatibility.md', 'startup-config-v1.schema.json', 'worldspeaker-god-preset-v1.zip',
     ]);

    const packagedRequirements = readFileSync(join(packageDirectory, 'compatibility.md'), 'utf8');
    const packagedGuide = readFileSync(join(packageDirectory, 'player-compatibility.md'), 'utf8');
    const packagedSchema = JSON.parse(readFileSync(join(packageDirectory, 'startup-config-v1.schema.json'), 'utf8'));
    const packagedFollowupContract = startupFollowupContract(packagedSchema, 'packaged startup-config-v1.schema.json');
    assert.deepEqual(packagedSchema, startupSchema, 'the package must include the current startup-v1 schema');
    assertServerFollowupDocumentation(packagedRequirements, 'packaged server requirements', packagedFollowupContract);
    assertPlayerFollowupDocumentation(packagedGuide, 'packaged player guide', packagedFollowupContract);
    assert.match(packagedRequirements, /\[player compatibility guide\]\(player-compatibility\.md\)/);
    assert.match(packagedGuide, /\[server requirements\]\(compatibility\.md\)/);
    assert.match(packagedGuide, /\[latest qualification QA decision\]\(QA-STATUS\.md\)/);
     assert.match(packagedGuide, /\[startup bundle schema\]\(startup-config-v1\.schema\.json\)/);
     assert.match(packagedRequirements, /\[God preset data ZIP\]\(worldspeaker-god-preset-v1\.zip\)/);
      assert.match(packagedRequirements, /\[instructions\]\(god-preset-README\.md\)/);
     assert.match(packagedRequirements, /\[GPL-3\.0-only license\]\(LICENSE\)/);
     assert.match(packagedGuide, /\[God preset data ZIP\]\(worldspeaker-god-preset-v1\.zip\)/);
      assert.match(packagedGuide, /\[preset instructions\]\(god-preset-README\.md\)/);
     assert.deepEqual(readFileSync(join(packageDirectory, 'worldspeaker-god-preset-v1.zip')),
       readFileSync(presetArchive), 'packaged guidance ships the actual data ZIP');
     assert.match(readFileSync(join(packageDirectory, 'god-preset-README.md'), 'utf8'), /NOT AN ACTIVE GOD PERSONA/);
    assert.match(packagedGuide, /currently\s+unqualified|still \*\*unqualified\*\*/);
    for (const markdownFile of readdirSync(packageDirectory).filter((name) => name.endsWith('.md'))) {
      resolveLocalMarkdownLinks(
        readFileSync(join(packageDirectory, markdownFile), 'utf8'),
        packageDirectory,
        packageDirectory,
        `packaged ${markdownFile}`,
      );
    }

    const candidateRequirements = join(packageDirectory, 'REQUIREMENTS.md');
    writeFileSync(candidateRequirements, `${packagedRequirements}\n\nCandidate assembly does not itself confer qualification or support.\n`);
    assert.match(readFileSync(candidateRequirements, 'utf8'), /\]\(player-compatibility\.md\)/);
    assert.ok(readFileSync(join(packageDirectory, 'player-compatibility.md'), 'utf8').length > 0,
      'the linked player guide is present in the temporary candidate assembly');
  } finally {
    rmSync(packageDirectory, { recursive: true, force: true });
  }
});

test('Markdown link resolution decodes local URIs, strips anchors, bounds paths, and skips external URLs', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'worldspeaker-markdown-links-'));
  const outside = join(dirname(fixture), `${fixture.split(sep).at(-1)}-outside.md`);
  try {
    writeFileSync(join(fixture, 'guide notes.md'), '# local target\n');
    writeFileSync(outside, '# outside the allowed root\n');
    symlinkSync(outside, join(fixture, 'escaped.md'));
    const checked = resolveLocalMarkdownLinks(
      '[decoded local target](guide%20notes.md#details) [external](https://example.invalid/not-fetched)',
      fixture,
      fixture,
      'link resolver fixture',
    );
    assert.deepEqual(checked.localTargets, ['guide%20notes.md#details']);
    assert.deepEqual(checked.externalTargets, ['https://example.invalid/not-fetched']);
    assert.throws(
      () => resolveLocalMarkdownLinks('[escape](../outside.md)', fixture, fixture, 'bounded fixture'),
      /outside its allowed root/,
    );
    assert.throws(
      () => resolveLocalMarkdownLinks('[encoded escape](%2e%2e/outside.md)', fixture, fixture, 'encoded-bounded fixture'),
      /outside its allowed root/,
    );
    assert.throws(
      () => resolveLocalMarkdownLinks('[symlink escape](escaped.md)', fixture, fixture, 'symlink-bounded fixture'),
      /outside its allowed root/,
    );
  } finally {
    rmSync(fixture, { recursive: true, force: true });
    rmSync(outside, { force: true });
  }
});

test('follow-up documentation guard rejects a missing optional field paragraph', () => {
  const requirements = readFileSync(join(root, 'docs/compatibility.md'), 'utf8');
  const schema = JSON.parse(readFileSync(join(root, 'compatibility/startup-config-v1.schema.json'), 'utf8'));
  const contract = startupFollowupContract(schema, 'startup-config-v1.schema.json');
  const paragraph = followupParagraph(requirements, 'server.followupWindowSeconds', 'rendered server requirements');
  const missingFieldMutation = requirements.replace(paragraph, '');

  assert.notEqual(missingFieldMutation, requirements, 'the mutation must remove the documented field');
  assert.throws(
    () => assertServerFollowupDocumentation(missingFieldMutation, 'mutated server requirements', contract),
    /mutated server requirements must document `server\.followupWindowSeconds`/,
  );
});
