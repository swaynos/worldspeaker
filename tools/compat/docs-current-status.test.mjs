import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const generator = join(root, 'tools/compat/generate-compatibility-docs.mjs');
const packager = join(root, 'tools/compat/package-status-docs.mjs');

test('public and packaged compatibility docs point to current QA and reject historical artifacts', () => {
  const generated = spawnSync(process.execPath, [generator], { cwd: root, encoding: 'utf8' });
  assert.ifError(generated.error);
  assert.equal(generated.status, 0, generated.stderr);

  const readme = readFileSync(join(root, 'README.md'), 'utf8');
  const requirements = readFileSync(join(root, 'docs/compatibility.md'), 'utf8');
  const builderReport = readFileSync(join(root, 'docs/r01/build/COMPAT-01.md'), 'utf8');
  const build = readFileSync(join(root, 'build.gradle'), 'utf8');

  assert.match(readme, /\[current QA decision\]\(docs\/r01\/qa\/COMPAT-01\.md\)/);
  assert.match(readme, /historical builder report/);
  assert.match(requirements, /\[latest QA decision\]\(r01\/qa\/COMPAT-01\.md\)/);
  assert.match(requirements, /\[builder report\]\(r01\/build\/COMPAT-01\.md\)/);
  assert.match(requirements, /Current qualification decision: FAIL/);
  assert.match(requirements, /cecd300e85c23df429c426370acbfbb6c3ab8da5b4fe5a0205645d276983c4f2/);
  assert.match(requirements, /e0c3702d472706bee2736c98349e1e181702823ecde1c78924ba605ee63faa23/);
  assert.match(requirements, /Do not use the historical candidate pair for qualification or T3–T6/);
  assert.match(requirements, /pinned Ubuntu 24\.04 Linux x86-64/);
  assert.match(requirements, /## Offline compatibility diagnosis/);
  assert.match(requirements, /node tools\/compat\/preflight\.mjs --jar build\/distributions\/worldspeaker-candidate\/worldspeaker-0\.1\.0-dev\.1\.jar/);
  assert.match(requirements, /umask 077/);
  assert.match(requirements, /exit "\$preflight_status"/);
  assert.match(requirements, /chmod 700/);
  assert.match(requirements, /chmod 600/);
  assert.match(requirements, /--java <java-executable>/);
  assert.match(requirements, /--npm <npm-executable>/);
  assert.match(requirements, /--companion companion\/package\.json/);
  assert.match(requirements, /WS-PREFLIGHT-PASS/);
  assert.match(requirements, /WS-PREFLIGHT-UNAVAILABLE/);
  assert.match(requirements, /\| `java-command`, `java-vendor`, `java-build`, `java-major` \|/);
  assert.match(requirements, /\| `qualification-platform` \|/);
  assert.match(requirements, /\| `mod-metadata`, `minecraft-metadata`, `loader-metadata`, `fabric-api-metadata`, `server-only-metadata` \|/);
  assert.match(requirements, /does not inspect\s+an installed OpenClaw host/);
  assert.match(requirements, /Fabric refusing a\s+missing\/incompatible mod dependency, stops startup before Worldspeaker initializes/);
  assert.match(requirements, /WS-COMPAT-JAVA-MAJOR/);
  assert.match(requirements, /WS-COMPAT-HOST-VERSION-MISMATCH/);
  assert.match(requirements, /including chat sent by an operator who is also a player/);
  assert.match(requirements, /runtime player\/operator-player privacy remains unverified/);
  assert.match(requirements, /historical candidate pair is stale,\s+is not valid for qualification or T3–T6, and requires a fresh pinned rebuild/);
  assert.match(requirements, /\[God preset data ZIP\]\(\.\.\/build\/presets\/worldspeaker-god-preset-v1\.zip\)/);
  assert.match(requirements, /128 KiB compressed and expanded/);
  assert.match(requirements, /Configured God persona use is \*\*INACTIVE \/ BLOCKED\*\*/);
  assert.match(requirements, /No client installation, realm\/private-workspace import, provider/);
  assert.match(requirements, /tokens do not create an operation/);

  const companionReadme = readFileSync(join(root, 'companion/README.md'), 'utf8');
  assert.match(companionReadme, /node tools\/compat\/preflight\.mjs --jar build\/distributions\/worldspeaker-candidate\/worldspeaker-0\.1\.0-dev\.1\.jar/);
  assert.match(companionReadme, /exit "\$preflight_status"/);
  assert.match(companionReadme, /WS-PREFLIGHT-UNAVAILABLE/);
  assert.match(companionReadme, /private launcher\/Loader log/);
  assert.match(companionReadme, /operator-player chat/);
  assert.match(companionReadme, /historical candidate pair is stale/);
  assert.match(companionReadme, /runtime behavior remains unverified/);
  assert.match(companionReadme, /Minecraft `1\.21\.1`, Fabric Loader `0\.20\.1`, Fabric API/);
  assert.match(companionReadme, /Temurin HotSpot JDK `21\.0\.13\+9-LTS`/);
  assert.match(companionReadme, /Node `24\.22\.1` and npm `11\.19\.1`/);

  assert.match(builderReport, /historical builder artifacts only/);
  assert.match(builderReport, /\| `worldspeaker-0\.1\.0-dev\.1\.jar` \| 46,006 \| `cecd300e85c23df429c426370acbfbb6c3ab8da5b4fe5a0205645d276983c4f2` \|/);
  assert.match(builderReport, /\| `worldspeaker-openclaw-channel-0\.1\.0-dev\.1\.tgz` \| 24,275 \| `e0c3702d472706bee2736c98349e1e181702823ecde1c78924ba605ee63faa23` \|/);
  assert.match(builderReport, /Do not execute T3–T6 against the historical hashes above/);
  assert.doesNotMatch(builderReport, /Execute T3-T6 against these exact artifact hashes/);
  assert.match(build, /package-status-docs\.mjs/);

  const packageDirectory = mkdtempSync(join(tmpdir(), 'worldspeaker-status-docs-'));
  try {
    const packaged = spawnSync(process.execPath, [packager, packageDirectory], { cwd: root, encoding: 'utf8' });
    assert.ifError(packaged.error);
    assert.equal(packaged.status, 0, packaged.stderr);
    assert.deepEqual(readdirSync(packageDirectory).sort(), [
      'COMPAT-01.md', 'LICENSE', 'QA-STATUS.md', 'compatibility.md', 'deployment.json', 'god-preset-README.md',
      'player-compatibility.md', 'startup-config-v1.schema.json', 'worldspeaker-god-preset-v1.zip',
    ]);

    const packagedRequirements = readFileSync(join(packageDirectory, 'compatibility.md'), 'utf8');
    const packagedBuilder = readFileSync(join(packageDirectory, 'COMPAT-01.md'), 'utf8');
    const packagedPlayerGuide = readFileSync(join(packageDirectory, 'player-compatibility.md'), 'utf8');
    assert.match(packagedRequirements, /\[latest QA decision\]\(QA-STATUS\.md\)/);
    assert.match(packagedRequirements, /\[builder report\]\(COMPAT-01\.md\)/);
    assert.match(packagedRequirements, /\[deployment manifest\]\(deployment\.json\)/);
    assert.match(packagedRequirements, /\[startup bundle schema\]\(startup-config-v1\.schema\.json\)/);
    assert.match(packagedRequirements, /\[God preset data ZIP\]\(worldspeaker-god-preset-v1\.zip\)/);
    assert.match(packagedRequirements, /\[instructions\]\(god-preset-README\.md\)/);
    assert.match(packagedRequirements, /\[GPL-3\.0-only license\]\(LICENSE\)/);
    assert.match(packagedRequirements, /## Offline compatibility diagnosis/);
    assert.match(packagedRequirements, /WS-PREFLIGHT-UNAVAILABLE/);
    assert.match(packagedRequirements, /historical candidate pair is\s+stale/);
    assert.match(packagedRequirements, /\[player compatibility guide\]\(player-compatibility\.md\)/);
    assert.match(packagedPlayerGuide, /\[server requirements\]\(compatibility\.md\)/);
    assert.match(packagedPlayerGuide, /\[latest qualification QA decision\]\(QA-STATUS\.md\)/);
    assert.match(packagedBuilder, /\[latest QA\s+decision\]\(QA-STATUS\.md\)/);
    assert.match(readFileSync(join(packageDirectory, 'QA-STATUS.md'), 'utf8'), /FAIL for qualification/);
  } finally {
    rmSync(packageDirectory, { recursive: true, force: true });
  }
});
