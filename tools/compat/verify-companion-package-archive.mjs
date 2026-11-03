import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { verifyCompanionPackageFreshness } from './companion-package-freshness.mjs';
import { PRESET_ARCHIVE_NAME, verifyGodPresetArchiveBytes } from '../presets/god-preset-archive.mjs';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const presetSidecar = join(repositoryRoot, 'companion/presets', PRESET_ARCHIVE_NAME);

export function verifyCompanionPackageArchive({
  sourceManifest,
  archivePath,
  readArchiveManifest = readTarManifest,
  readArchiveFile = readTarFile,
  readArchivePreset = readTarPreset,
  sidecarPath = presetSidecar,
}) {
  if (!archivePath) throw codedError('companion-package-archive-path-required');
  const freshness = verifyCompanionPackageFreshness({
    sourceManifest,
    archiveManifest: readArchiveManifest(archivePath),
  });
  const abilityMetadataFiles = [
    'ability-contracts-v1.json',
    'ability-contracts-v1.schema.json',
    'ability-discovery-v1.schema.json',
  ];
  for (const name of abilityMetadataFiles) {
    let archived;
    let generated;
    let canonical;
    try {
      archived = Buffer.from(readArchiveFile(archivePath, name));
      generated = readFileSync(join(repositoryRoot, 'companion/compatibility', name));
      canonical = readFileSync(join(repositoryRoot, 'compatibility', name));
    } catch {
      throw codedError('companion-package-ability-metadata-missing');
    }
    if (!archived.equals(generated) || !generated.equals(canonical)) {
      throw codedError('companion-package-ability-metadata-stale');
    }
  }
  let archivedPreset;
  let sidecar;
  try {
    archivedPreset = Buffer.from(readArchivePreset(archivePath));
    sidecar = readFileSync(sidecarPath);
  } catch {
    throw codedError('companion-package-preset-missing');
  }
  const verified = verifyGodPresetArchiveBytes(archivedPreset, { root: repositoryRoot });
  verifyGodPresetArchiveBytes(sidecar, { root: repositoryRoot });
  if (!archivedPreset.equals(sidecar)) throw codedError('companion-package-preset-stale');
  return { ...freshness, abilityMetadataFiles, presetBytes: verified.bytes, presetSha256: verified.sha256 };
}

function readTarManifest(archivePath) {
  const result = spawnSync('tar', ['-xOzf', archivePath, 'package/package.json'], {
    encoding: 'utf8',
    timeout: 10_000,
    maxBuffer: 1024 * 1024,
  });
  if (result.error || result.status !== 0) throw codedError('companion-package-archive-unreadable');
  try {
    return JSON.parse(result.stdout);
  } catch {
    throw codedError('companion-package-archive-manifest-invalid');
  }
}

function readTarFile(archivePath, name) {
  const result = spawnSync('tar', ['-xOzf', archivePath, `package/compatibility/${name}`], {
    encoding: null,
    timeout: 10_000,
    maxBuffer: 1024 * 1024,
  });
  if (result.error || result.status !== 0 || !result.stdout?.length) {
    throw codedError('companion-package-ability-metadata-unreadable');
  }
  return result.stdout;
}

function readTarPreset(archivePath) {
  const result = spawnSync('tar', ['-xOzf', archivePath, `package/presets/${PRESET_ARCHIVE_NAME}`], {
    encoding: null,
    timeout: 10_000,
    maxBuffer: 256 * 1024,
  });
  if (result.error || result.status !== 0 || !result.stdout?.length) throw codedError('companion-package-preset-unreadable');
  return result.stdout;
}

function main() {
  if (process.argv.length !== 3) throw codedError('companion-package-archive-path-required');
  let sourceManifest;
  try {
    sourceManifest = JSON.parse(readFileSync(join(repositoryRoot, 'companion/package.json'), 'utf8'));
  } catch {
    throw codedError('companion-source-manifest-invalid');
  }
  const result = verifyCompanionPackageArchive({ sourceManifest, archivePath: process.argv[2] });
  process.stdout.write(`WS-CANDIDATE-COMPANION-ARCHIVE-FRESH: ${result.name}@${result.version} preset=${result.presetSha256}\n`);
}

function codedError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    main();
  } catch (error) {
    const code = error?.code ?? 'companion-package-archive-invalid';
    const disposition = code === 'companion-package-archive-stale' ? 'STALE' : 'INVALID';
    process.stderr.write(`WS-CANDIDATE-COMPANION-ARCHIVE-${disposition}: ${code}\n`);
    process.exitCode = 1;
  }
}
