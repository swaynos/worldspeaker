import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve, relative, isAbsolute, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PRESET_ARCHIVE_NAME, verifyGodPresetArchiveBytes } from '../presets/god-preset-archive.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const output = process.argv[2] ? resolve(process.argv[2]) : null;
if (!output) throw new Error('Usage: node tools/compat/package-status-docs.mjs <package-directory>');

const compatibility = (await readFile(resolve(root, 'docs/compatibility.md'), 'utf8'))
  .replaceAll('(r01/qa/COMPAT-01.md)', '(QA-STATUS.md)')
  .replaceAll('(r01/build/COMPAT-01.md)', '(COMPAT-01.md)')
  .replaceAll('[CONV-06 offline evaluation procedure](../tools/dialogue/PROCEDURE.md)',
    'test-only CONV-06 offline evaluation procedure (available in the source repository only)')
  .replaceAll('(../build/presets/worldspeaker-god-preset-v1.zip)', '(worldspeaker-god-preset-v1.zip)')
  .replaceAll('(../presets/god/README.md)', '(god-preset-README.md)')
  .replaceAll('(../LICENSE)', '(LICENSE)')
  .replaceAll('(../compatibility/startup-config-v1.schema.json)', '(startup-config-v1.schema.json)')
  .replaceAll('(../compatibility/deployment.json)', '(deployment.json)');
const builderReport = (await readFile(resolve(root, 'docs/r01/build/COMPAT-01.md'), 'utf8'))
  .replaceAll('(../qa/COMPAT-01.md)', '(QA-STATUS.md)');
const playerGuide = (await readFile(resolve(root, 'docs/player-compatibility.md'), 'utf8'))
  .replaceAll('(r01/qa/COMPAT-01.md)', '(QA-STATUS.md)')
  .replaceAll('(../build/presets/worldspeaker-god-preset-v1.zip)', '(worldspeaker-god-preset-v1.zip)')
  .replaceAll('(../presets/god/README.md)', '(god-preset-README.md)')
  .replaceAll('(../LICENSE)', '(LICENSE)')
  .replaceAll('(../compatibility/startup-config-v1.schema.json)', '(startup-config-v1.schema.json)');
const qaStatus = await readFile(resolve(root, 'docs/r01/qa/COMPAT-01.md'), 'utf8');
const deploymentManifest = await readFile(resolve(root, 'compatibility/deployment.json'), 'utf8');
const startupSchema = await readFile(resolve(root, 'compatibility/startup-config-v1.schema.json'), 'utf8');
const license = await readFile(resolve(root, 'LICENSE'));
const presetArchive = await readFile(resolve(root, 'build/presets', PRESET_ARCHIVE_NAME));
verifyGodPresetArchiveBytes(presetArchive, { root });
const presetReadme = (await readFile(resolve(root, 'presets/god/README.md'), 'utf8'))
  .replaceAll('(../../compatibility/startup-config-v1.schema.json)', '(startup-config-v1.schema.json)')
  .replaceAll('(../../LICENSE)', '(LICENSE)');

await mkdir(output, { recursive: true });
const packaged = new Map([
  ['compatibility.md', compatibility],
  ['player-compatibility.md', playerGuide],
  ['COMPAT-01.md', builderReport],
  ['QA-STATUS.md', qaStatus],
  ['deployment.json', deploymentManifest],
  ['startup-config-v1.schema.json', startupSchema],
  ['LICENSE', license],
  ['god-preset-README.md', presetReadme],
  [PRESET_ARCHIVE_NAME, presetArchive],
]);
for (const [name, contents] of packaged) {
  await writeFile(resolve(output, name), contents, 'utf8');
}

// Fail packaging rather than ship a status page whose relative Markdown links
// point outside the package or to files that were not included.
for (const [name, contents] of packaged) {
  if (typeof contents !== 'string') continue;
  for (const [, target] of contents.matchAll(/\[[^\]]+\]\(([^)]+)\)/gs)) {
    if (/^(?:[a-z][a-z\d+.-]*:|\/\/|#)/i.test(target)) continue;
    const localTarget = target.split('#', 1)[0];
    const resolvedTarget = resolve(output, localTarget);
    const fromPackage = relative(output, resolvedTarget);
    if (isAbsolute(fromPackage) || fromPackage === '..' || fromPackage.startsWith(`..${sep}`)) {
      throw new Error(`Packaged document ${name} links outside the package: ${target}`);
    }
    try {
      await readFile(resolvedTarget);
    } catch {
      throw new Error(`Packaged document ${name} has a broken reference: ${target}`);
    }
  }
}
