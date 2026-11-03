import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { PRESET_ARCHIVE_NAME, verifyGodPresetArchiveBytes } from './god-preset-archive.mjs';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const buildArchive = resolve(repositoryRoot, 'build/presets', PRESET_ARCHIVE_NAME);
const companionArchive = resolve(repositoryRoot, 'companion/presets', PRESET_ARCHIVE_NAME);

export function verifyGodPreset() {
  let buildBytes;
  let companionBytes;
  try {
    buildBytes = readFileSync(buildArchive);
    companionBytes = readFileSync(companionArchive);
  } catch {
    throw codedError('preset-archive-missing');
  }
  const verified = verifyGodPresetArchiveBytes(buildBytes, { root: repositoryRoot });
  verifyGodPresetArchiveBytes(companionBytes, { root: repositoryRoot });
  if (!buildBytes.equals(companionBytes)) throw codedError('preset-sidecar-mismatch');
  return verified;
}

function codedError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    if (process.argv.length !== 2) throw codedError('preset-verifier-usage');
    const result = verifyGodPreset();
    process.stdout.write(`WS-GOD-PRESET-VERIFIED: entries=${result.entries.length} bytes=${result.bytes} sha256=${result.sha256}\n`);
  } catch (error) {
    process.stderr.write(`WS-GOD-PRESET-INVALID: ${error?.code ?? 'preset-verification-failed'}\n`);
    process.exitCode = 1;
  }
}
