import { createHash } from 'node:crypto';
import { inflateRawSync } from 'node:zlib';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { spawn, spawnSync } from 'node:child_process';
import { basename, join } from 'node:path';
import { verifyCompanionPackageFreshness } from './companion-package-freshness.mjs';

export const CLEAN_INSTALL_PHASES = Object.freeze([
  'contract', 'runtimePreflight', 'candidateArtifacts', 'pinnedDownloads', 'fabricInstall',
  'openclawInstall', 'pluginInstall', 'pluginConfiguration', 'gatewayStartup', 'serverStartup',
  'authenticatedHandshake', 'shutdown',
]);

export function parseArgs(tokens, repositoryRoot = process.cwd()) {
  const result = {
    deployment: 'compatibility/deployment.json',
    distribution: 'build/distributions/worldspeaker-candidate',
    java: 'java',
    node: process.execPath,
    npm: 'npm',
    startServer: false,
    acceptEula: false,
    help: false,
  };
  const values = new Map([
    ['--workdir', 'workdir'], ['--deployment', 'deployment'], ['--distribution', 'distribution'],
    ['--java', 'java'], ['--node', 'node'], ['--npm', 'npm'],
  ]);
  const booleans = new Map([['--start-server', 'startServer'], ['--accept-eula', 'acceptEula'], ['--help', 'help']]);
  const seen = new Set();
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (seen.has(token)) throw codedError(`duplicate-option-${String(token).slice(2)}`);
    seen.add(token);
    if (booleans.has(token)) {
      const name = booleans.get(token);
      if (result[name]) throw codedError(`duplicate-option-${token.slice(2)}`);
      result[name] = true;
      continue;
    }
    const name = values.get(token);
    if (!name) throw codedError(`unknown-option-${String(token).replace(/[^a-z0-9-]/gi, '').slice(0, 40) || 'invalid'}`);
    const value = tokens[++index];
    if (!value || value.startsWith('--')) throw codedError(`option-value-required-${token.slice(2)}`);
    result[name] = value;
  }
  if (!result.help && !result.workdir) throw codedError('workdir-required');
  if (result.workdir && !isAbsolutePath(result.workdir)) throw codedError('workdir-must-be-absolute');
  result.repositoryRoot = repositoryRoot;
  return result;
}

export function assertEulaAuthorization(options) {
  if (options.startServer && !options.acceptEula) throw codedError('server-start-requires-explicit-accept-eula');
  if (options.acceptEula && !options.startServer) throw codedError('accept-eula-requires-start-server');
}

export function setPhase(record, name, state, { code, exitCode, diagnostic } = {}) {
  const phase = { state, finishedAtUtc: new Date().toISOString() };
  if (code) phase.code = code;
  if (Number.isInteger(exitCode)) phase.exitCode = exitCode;
  if (diagnostic) phase.diagnostic = diagnostic;
  record.phases[name] = phase;
}

export async function verifyCandidateDistribution({ distribution, manifest, manifestSha256, readZipJson = defaultReadZipJson, readTarJson = defaultReadTarJson }) {
  const packagedManifest = await readFile(join(distribution, 'deployment.json')).catch(() => null);
  if (!packagedManifest || hash('sha256', packagedManifest) !== manifestSha256) throw codedError('candidate-manifest-hash-mismatch');
  const outerBytes = await readFile(join(distribution, 'outer-manifest.json')).catch(() => null);
  if (!outerBytes) throw codedError('candidate-outer-manifest-missing');
  let outer;
  try { outer = JSON.parse(outerBytes.toString('utf8')); } catch { throw codedError('candidate-outer-manifest-invalid'); }
  if (outer.deploymentId !== manifest.deploymentId || outer.manifestRevision !== manifest.manifestRevision ||
      outer.deploymentManifestSha256 !== manifestSha256 || outer.qualificationState !== 'unqualified') {
    throw codedError('candidate-outer-manifest-binding-mismatch');
  }
  const modName = `${manifest.mod.id}-${manifest.mod.version}.jar`;
  if (!Array.isArray(outer.artifacts) || outer.artifacts.some((item) =>
    typeof item?.name !== 'string' || (item.name.endsWith('.jar') && item.name !== modName) ||
    /load-harness|worldspeaker-load-harness/iu.test(item.name))) {
    throw codedError('candidate-contains-nonproduction-load-artifact');
  }
  const distributionEntries = await readdir(distribution, { withFileTypes: true });
  if (distributionEntries.some((entry) => entry.isSymbolicLink() ||
      /compat-load-harness|worldspeaker-load-harness/iu.test(entry.name))) {
    throw codedError('candidate-contains-nonproduction-load-artifact');
  }
  const companionName = `${manifest.companion.package.replace(/^@/, '').replace('/', '-')}-${manifest.companion.version}.tgz`;
  const modPath = join(distribution, modName);
  const companionPath = join(distribution, companionName);
  const artifacts = [];
  for (const [name, path] of [[modName, modPath], [companionName, companionPath]]) {
    const bytes = await readFile(path).catch(() => null);
    const outerArtifact = outer.artifacts?.find((item) => item.name === name);
    if (!bytes || !outerArtifact || outerArtifact.bytes !== bytes.byteLength || outerArtifact.sha256 !== hash('sha256', bytes)) {
      throw codedError(`candidate-artifact-hash-mismatch-${name.replace(/[^a-z0-9.-]/gi, '-')}`);
    }
    artifacts.push({ name, bytes: bytes.byteLength, sha256: outerArtifact.sha256, path });
  }

  const mod = await readZipJson(modPath, 'fabric.mod.json');
  if (mod.id !== manifest.mod.id || mod.version !== manifest.mod.version || mod.environment !== 'server' ||
      mod.depends?.minecraft !== manifest.target.minecraft || mod.depends?.fabricloader !== manifest.runtimeDependencies.fabricLoader ||
      mod.depends?.['fabric-api'] !== manifest.runtimeDependencies.fabricApi) {
    throw codedError('candidate-mod-metadata-mismatch');
  }
  const companion = await readTarJson(companionPath, 'package/package.json');
  const sourceCompanion = JSON.parse((await readFile(new URL('../../companion/package.json', import.meta.url))).toString('utf8'));
  verifyCompanionPackageFreshness({ sourceManifest: sourceCompanion, archiveManifest: companion });
  if (companion.name !== manifest.companion.package || companion.version !== manifest.companion.version ||
      companion.peerDependencies?.openclaw !== manifest.openclaw.version ||
      companion.openclaw?.compat?.pluginApi !== `=${manifest.openclaw.version}`) {
    throw codedError('candidate-companion-metadata-mismatch');
  }
  return { modPath, companionPath, artifacts };
}

export async function readZipJson(path, entry) {
  const bytes = await readFile(path);
  const value = readZipEntry(bytes, entry);
  try { return JSON.parse(value.toString('utf8')); } catch { throw codedError('archive-json-entry-invalid'); }
}

export async function downloadBytes(url, { allowedHosts, maxBytes, timeoutMs = 60_000, fetchImpl = fetch }) {
  let parsed;
  try { parsed = new URL(url); } catch { throw codedError('download-url-invalid'); }
  if (parsed.protocol !== 'https:' || !allowedHosts.includes(parsed.hostname) || parsed.username || parsed.password) {
    throw codedError('download-origin-not-allowed');
  }
  let response;
  let current = parsed;
  let redirects = 0;
  const signal = AbortSignal.timeout(timeoutMs);
  while (true) {
    try {
      response = await fetchImpl(current, { redirect: 'manual', signal });
    } catch {
      throw codedError('download-network-error');
    }
    if (![301, 302, 303, 307, 308].includes(response.status)) break;
    await response.body?.cancel().catch(() => {});
    if (redirects >= 5) throw codedError('download-redirect-limit-exceeded');
    const location = response.headers.get('location');
    if (!location) throw codedError('download-redirect-location-missing');
    let redirected;
    try { redirected = new URL(location, current); } catch { throw codedError('download-redirect-url-invalid'); }
    if (redirected.protocol !== 'https:' || !allowedHosts.includes(redirected.hostname) || redirected.username || redirected.password) {
      throw codedError('download-redirect-origin-not-allowed');
    }
    current = redirected;
    redirects += 1;
  }
  let finalUrl;
  try { finalUrl = new URL(response.url || current.href); } catch { throw codedError('download-final-url-invalid'); }
  if (finalUrl.protocol !== 'https:' || !allowedHosts.includes(finalUrl.hostname)) throw codedError('download-redirect-origin-not-allowed');
  if (!response.ok) throw codedError(`download-http-${response.status}`);
  const lengthHeader = Number(response.headers.get('content-length'));
  if (Number.isFinite(lengthHeader) && lengthHeader > maxBytes) throw codedError('download-size-limit-exceeded');
  const chunks = [];
  let totalBytes = 0;
  if (response.body?.getReader) {
    const reader = response.body.getReader();
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        totalBytes += value.byteLength;
        if (totalBytes > maxBytes) {
          await reader.cancel();
          throw codedError('download-size-limit-exceeded');
        }
        chunks.push(Buffer.from(value));
      }
    } finally {
      reader.releaseLock();
    }
  } else {
    const bytes = Buffer.from(await response.arrayBuffer());
    totalBytes = bytes.byteLength;
    if (totalBytes > maxBytes) throw codedError('download-size-limit-exceeded');
    chunks.push(bytes);
  }
  if (totalBytes === 0) throw codedError('download-size-invalid');
  return Buffer.concat(chunks, totalBytes);
}

export async function downloadMavenArtifact({ path, output, fetchImpl = fetch, timeoutMs = 60_000 }) {
  const baseUrl = `https://maven.fabricmc.net/${path}`;
  const checksumUrl = `${baseUrl}.sha1`;
  const checksumBytes = await downloadBytes(checksumUrl, {
    allowedHosts: ['maven.fabricmc.net'], maxBytes: 1024, timeoutMs, fetchImpl,
  });
  const expectedSha1 = checksumBytes.toString('utf8').trim().split(/\s+/)[0].toLowerCase();
  if (!/^[a-f0-9]{40}$/.test(expectedSha1)) throw codedError('maven-sha1-sidecar-invalid');
  const bytes = await downloadBytes(baseUrl, {
    allowedHosts: ['maven.fabricmc.net'], maxBytes: 100 * 1024 * 1024, timeoutMs, fetchImpl,
  });
  if (hash('sha1', bytes) !== expectedSha1) throw codedError('maven-artifact-checksum-mismatch');
  await writeFile(output, bytes, { mode: 0o600, flag: 'wx' });
  return {
    name: basename(output),
    path: output,
    bytes: bytes.byteLength,
    sha1: expectedSha1,
    sha256: hash('sha256', bytes),
    sourceUrl: baseUrl,
    checksumSource: checksumUrl,
  };
}

export async function runBoundedCommand(command, args, { cwd, env, timeoutMs, spawnImpl = spawn }) {
  return new Promise((resolve) => {
    let child;
    let timedOut = false;
    let spawnFailed = false;
    let stdout = '';
    let stderr = '';
    let forceTimer;
    try {
      child = spawnImpl(command, args, { cwd, env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    } catch {
      resolve({ exitCode: null, signal: null, timedOut: false, errorCode: 'process-spawn-failed', stdout, stderr });
      return;
    }
    const appendBounded = (value, chunk) => (value + chunk.toString('utf8')).slice(-256 * 1024);
    child.stdout?.on('data', (chunk) => { stdout = appendBounded(stdout, chunk); });
    child.stderr?.on('data', (chunk) => { stderr = appendBounded(stderr, chunk); });
    child.once('error', () => { spawnFailed = true; });
    const timeout = setTimeout(() => {
      timedOut = true;
      child.kill('SIGTERM');
      forceTimer = setTimeout(() => child.kill('SIGKILL'), 1_000);
    }, timeoutMs);
    child.once('close', (code, signal) => {
      clearTimeout(timeout);
      clearTimeout(forceTimer);
      resolve({
        exitCode: Number.isInteger(code) ? code : null,
        signal: signal ?? null,
        timedOut,
        errorCode: spawnFailed ? 'process-spawn-failed' : null,
        stdout,
        stderr,
      });
    });
  });
}

export async function writeRunRecord(path, record) {
  const temporary = `${path}.tmp`;
  const safeRecord = JSON.stringify(record, null, 2);
  await writeFile(temporary, `${safeRecord}\n`, { encoding: 'utf8', mode: 0o600 });
  const { chmod, rename } = await import('node:fs/promises');
  await chmod(temporary, 0o600);
  await rename(temporary, path);
}

export function hash(algorithm, bytes, encoding = 'hex') {
  return createHash(algorithm).update(bytes).digest(encoding);
}

function defaultReadZipJson(path, entry) {
  return readZipJson(path, entry);
}

function defaultReadTarJson(path, entry) {
  const result = spawnSync('tar', ['-xOzf', path, entry], { encoding: 'utf8', timeout: 10_000, maxBuffer: 1024 * 1024 });
  if (result.status !== 0) throw codedError('candidate-companion-archive-unreadable');
  try { return JSON.parse(result.stdout); } catch { throw codedError('candidate-companion-metadata-invalid'); }
}

function isAbsolutePath(path) {
  return typeof path === 'string' && (path.startsWith('/') || /^[A-Za-z]:[\\/]/.test(path));
}

function readZipEntry(archive, entryName) {
  const minimumEndRecord = 22;
  const maxCommentBytes = 65_535;
  const firstPossibleEnd = Math.max(0, archive.byteLength - minimumEndRecord - maxCommentBytes);
  let endRecord = -1;
  for (let cursor = archive.byteLength - minimumEndRecord; cursor >= firstPossibleEnd; cursor -= 1) {
    if (archive.readUInt32LE(cursor) === 0x06054b50) {
      endRecord = cursor;
      break;
    }
  }
  if (endRecord < 0) throw codedError('zip-end-record-missing');
  const entries = archive.readUInt16LE(endRecord + 10);
  const directorySize = archive.readUInt32LE(endRecord + 12);
  const directoryOffset = archive.readUInt32LE(endRecord + 16);
  if (entries === 0xffff || directorySize === 0xffffffff || directoryOffset === 0xffffffff ||
      directoryOffset + directorySize > endRecord) throw codedError('zip64-or-invalid-directory-unsupported');

  let offset = directoryOffset;
  const matches = [];
  for (let index = 0; index < entries; index += 1) {
    if (offset + 46 > archive.byteLength || archive.readUInt32LE(offset) !== 0x02014b50) throw codedError('zip-central-directory-invalid');
    const flags = archive.readUInt16LE(offset + 8);
    const compression = archive.readUInt16LE(offset + 10);
    const compressedSize = archive.readUInt32LE(offset + 20);
    const uncompressedSize = archive.readUInt32LE(offset + 24);
    const nameLength = archive.readUInt16LE(offset + 28);
    const extraLength = archive.readUInt16LE(offset + 30);
    const commentLength = archive.readUInt16LE(offset + 32);
    const localOffset = archive.readUInt32LE(offset + 42);
    const nameStart = offset + 46;
    const nextEntry = nameStart + nameLength + extraLength + commentLength;
    if (nextEntry > archive.byteLength) throw codedError('zip-central-directory-invalid');
    const name = archive.subarray(nameStart, nameStart + nameLength).toString('utf8');
    if (name === entryName) matches.push({ flags, compression, compressedSize, uncompressedSize, localOffset });
    offset = nextEntry;
  }
  if (matches.length !== 1) throw codedError(matches.length === 0 ? 'zip-entry-missing' : 'zip-entry-duplicated');
  const match = matches[0];
  if ((match.flags & 1) !== 0 || match.uncompressedSize > 1024 * 1024 || match.compressedSize > 1024 * 1024 ||
      match.localOffset + 30 > archive.byteLength) throw codedError('zip-entry-bounds-or-encryption-invalid');
  if (archive.readUInt32LE(match.localOffset) !== 0x04034b50) throw codedError('zip-local-header-invalid');
  const localNameLength = archive.readUInt16LE(match.localOffset + 26);
  const localExtraLength = archive.readUInt16LE(match.localOffset + 28);
  const dataOffset = match.localOffset + 30 + localNameLength + localExtraLength;
  const dataEnd = dataOffset + match.compressedSize;
  if (dataEnd > archive.byteLength) throw codedError('zip-entry-out-of-bounds');
  const compressed = archive.subarray(dataOffset, dataEnd);
  let output;
  if (match.compression === 0) output = compressed;
  else if (match.compression === 8) {
    try { output = inflateRawSync(compressed, { maxOutputLength: 1024 * 1024 }); } catch { throw codedError('zip-entry-deflate-invalid'); }
  } else throw codedError('zip-compression-method-unsupported');
  if (output.byteLength !== match.uncompressedSize) throw codedError('zip-entry-size-mismatch');
  return output;
}

function codedError(code, exitCode) {
  const error = new Error(code);
  error.code = code;
  if (Number.isInteger(exitCode)) error.exitCode = exitCode;
  return error;
}
