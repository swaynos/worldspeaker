import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readFile } from 'node:fs/promises';

export async function installedOpenClawVersion(): Promise<string> {
  const sdkPath = fileURLToPath(import.meta.resolve('openclaw/plugin-sdk/channel-core'));
  let directory = dirname(sdkPath);
  for (let depth = 0; depth < 8; depth += 1) {
    try {
      const manifest = JSON.parse(await readFile(join(directory, 'package.json'), 'utf8')) as { name?: unknown; version?: unknown };
      if (manifest.name === 'openclaw' && typeof manifest.version === 'string') return manifest.version;
    } catch {
      // Continue toward the package root without importing private host code.
    }
    directory = dirname(directory);
  }
  throw new Error('installed OpenClaw package identity is unavailable');
}

export async function installedCompanionVersion(): Promise<string> {
  const packageFile = new URL('../package.json', import.meta.url);
  const manifest = JSON.parse(await readFile(packageFile, 'utf8')) as { name?: unknown; version?: unknown };
  if (manifest.name !== '@worldspeaker/openclaw-channel' || typeof manifest.version !== 'string') {
    throw new Error('installed Worldspeaker companion package identity is invalid');
  }
  return manifest.version;
}
