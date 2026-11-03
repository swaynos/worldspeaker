import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  buildGodPresetArchive,
  expectedGodPresetArchivePaths,
  PRESET_ARCHIVE_NAME,
  readGodPresetArchive,
  verifyGodPresetArchiveBytes,
} from './god-preset-archive.mjs';
import { readSafePresetFile, writeFixedPresetArchives } from './god-preset-filesystem.mjs';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const buildArchive = `build/presets/${PRESET_ARCHIVE_NAME}`;
const companionArchive = `companion/presets/${PRESET_ARCHIVE_NAME}`;

export function buildGodPreset({ write = false } = {}) {
  const expected = buildGodPresetArchive({ root: repositoryRoot });
  const verification = verifyGodPresetArchiveBytes(expected, { root: repositoryRoot });
  if (!write) {
    const buildBytes = readFixedArchive(buildArchive);
    const companionBytes = readFixedArchive(companionArchive);
    verifyGodPresetArchiveBytes(buildBytes, { root: repositoryRoot });
    verifyGodPresetArchiveBytes(companionBytes, { root: repositoryRoot });
    if (!buildBytes.equals(companionBytes) || !buildBytes.equals(expected)) throw codedError('preset-sidecar-stale');
    return verification;
  }

  writeFixedPresetArchives(repositoryRoot, expected, isKnownPresetArchive);
  return verification;
}

function readFixedArchive(relativePath) {
  return readSafePresetFile(repositoryRoot, relativePath, {
    errorCode: 'preset-archive-missing', maxBytes: 128 * 1024,
  });
}

function isKnownPresetArchive(bytes) {
  try {
    const entries = readGodPresetArchive(bytes);
    const paths = expectedGodPresetArchivePaths();
    if ([...entries.keys()].some((path, index) => path !== paths[index])) return false;

    const manifestBytes = entries.get('manifest.json');
    const manifest = JSON.parse(manifestBytes.toString('utf8'));
    const manifestKeys = [
      'schemaVersion', 'presetId', 'presetVersion', 'startupSchemaVersion', 'deploymentId',
      'deploymentManifestSha256', 'entries',
    ];
    if (JSON.stringify(Object.keys(manifest)) !== JSON.stringify(manifestKeys) ||
        manifest.schemaVersion !== 1 || manifest.presetId !== 'worldspeaker.god' ||
        manifest.presetVersion !== 1 || manifest.startupSchemaVersion !== 1 ||
        typeof manifest.deploymentId !== 'string' || manifest.deploymentId.length === 0 ||
        !/^[0-9a-f]{64}$/u.test(manifest.deploymentManifestSha256) ||
        !Array.isArray(manifest.entries) || manifest.entries.length !== paths.length - 1) return false;

    const sourcePaths = paths.filter((path) => path !== 'manifest.json');
    for (let index = 0; index < sourcePaths.length; index += 1) {
      const path = sourcePaths[index];
      const row = manifest.entries[index];
      const entryBytes = entries.get(path);
      if (!row || JSON.stringify(Object.keys(row)) !== JSON.stringify(['path', 'bytes', 'sha256']) ||
          row.path !== path || row.bytes !== entryBytes.length ||
          row.sha256 !== createHash('sha256').update(entryBytes).digest('hex')) return false;
    }
    if (!Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`, 'utf8').equals(manifestBytes)) return false;
    return true;
  } catch {
    return false;
  }
}

function codedError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

function main() {
  const args = process.argv.slice(2);
  if (args.length !== 1 || (args[0] !== '--write' && args[0] !== '--check')) {
    throw codedError('preset-builder-usage');
  }
  const result = buildGodPreset({ write: args[0] === '--write' });
  process.stdout.write(`WS-GOD-PRESET-${args[0] === '--write' ? 'WRITTEN' : 'CURRENT'}: ${result.bytes} bytes sha256=${result.sha256}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(); }
  catch (error) {
    process.stderr.write(`WS-GOD-PRESET-BUILD-FAILED: ${error?.code ?? 'preset-build-failed'}\n`);
    process.exitCode = 1;
  }
}
