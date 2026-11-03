import { constants as fsConstants } from 'node:fs';
import { lstat, open } from 'node:fs/promises';
import { dirname, isAbsolute, normalize, parse, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const NODE_VERSION = '24.22.1';
const MAX_PATH_UNITS = 1024;
const MAX_IPC_BYTES = 4096;
const ALLOWED_LIMITS = new Set([4096, 32768, 86]);
const OPENED_MARKER = Buffer.from('WSR1', 'ascii');
const RESULT_NAME = 'private-file-reader.result';

function fail() {
  throw new Error('private file read rejected');
}

async function* readUtf8Lines(stream) {
  let pending = Buffer.alloc(0);
  let total = 0;
  for await (const chunk of stream) {
    total += chunk.length;
    if (total > MAX_IPC_BYTES) fail();
    pending = Buffer.concat([pending, chunk]);
    for (;;) {
      const newline = pending.indexOf(0x0a);
      if (newline < 0) break;
      if (newline > MAX_IPC_BYTES) fail();
      const line = pending.subarray(0, newline);
      pending = pending.subarray(newline + 1);
      let decoded;
      try {
        decoded = new TextDecoder('utf-8', { fatal: true }).decode(line);
      } catch {
        fail();
      }
      yield decoded;
    }
    if (pending.length > MAX_IPC_BYTES) fail();
  }
  if (pending.length !== 0) fail();
}

function canonicalLocalPath(value) {
  if (typeof value !== 'string' || value.length < 1 || value.length > MAX_PATH_UNITS ||
      value.includes('://') || /[\u0000-\u001f\u007f-\u009f]/u.test(value) || !isAbsolute(value) ||
      normalize(value) !== value) fail();
  const root = parse(value).root;
  if (!root || relative(root, value).split(sep).filter(Boolean).length < 2) fail();
  return value;
}

function identity(stat) {
  return {
    dev: stat.dev,
    ino: stat.ino,
    mode: stat.mode,
    uid: stat.uid,
    gid: stat.gid,
    nlink: stat.nlink,
    size: stat.size,
    ctimeNs: stat.ctimeNs,
    mtimeNs: stat.mtimeNs,
  };
}

function sameIdentity(left, right) {
  return Object.keys(left).every((key) => left[key] === right[key]);
}

function sameStableIdentity(left, right) {
  return ['dev', 'ino', 'mode', 'uid', 'gid', 'nlink'].every((key) => left[key] === right[key]);
}

function requireDirectory(stat, serviceUid, isParent) {
  if (!stat.isDirectory() || stat.isSymbolicLink()) fail();
  const mode = stat.mode & 0o7777n;
  if (isParent) {
    if (stat.uid !== serviceUid || mode !== 0o700n) fail();
  } else if ((stat.uid !== 0n && stat.uid !== serviceUid) || (mode & 0o022n) !== 0n) {
    fail();
  }
}

async function inspectDirectories(filePath, serviceUid) {
  const parent = dirname(filePath);
  const root = parse(filePath).root;
  const segments = relative(root, parent).split(sep).filter(Boolean);
  const paths = [root];
  let current = root;
  for (const segment of segments) {
    current = current.endsWith(sep) ? `${current}${segment}` : `${current}${sep}${segment}`;
    paths.push(current);
  }
  const identities = [];
  for (let index = 0; index < paths.length; index += 1) {
    const directoryPath = paths[index];
    const stat = await lstat(directoryPath, { bigint: true });
    requireDirectory(stat, serviceUid, directoryPath === parent);
    identities.push({ path: directoryPath, identity: identity(stat) });
  }
  return identities;
}

function requirePrivateRegularFile(stat, serviceUid, maximum, minimum = 1) {
  if (!stat.isFile() || stat.isSymbolicLink() || stat.uid !== serviceUid || stat.nlink !== 1n ||
      (stat.mode & 0o7777n) !== 0o600n || stat.size < BigInt(minimum) || stat.size > BigInt(maximum)) fail();
}

async function inspectSourcePath(filePath, serviceUid, maximum) {
  const stat = await lstat(filePath, { bigint: true });
  if (!stat.isFile() && stat.isFIFO()) {
    const flags = fsConstants.O_RDONLY | fsConstants.O_NONBLOCK | fsConstants.O_NOFOLLOW;
    const descriptor = await open(filePath, flags);
    try {
      const opened = await descriptor.stat({ bigint: true });
      if (!opened.isFIFO() || !sameIdentity(identity(stat), identity(opened))) fail();
    } finally {
      await descriptor.close();
    }
    fail();
  }
  requirePrivateRegularFile(stat, serviceUid, maximum);
  return identity(stat);
}

async function inspectResultPath(resultPath, serviceUid) {
  const stat = await lstat(resultPath, { bigint: true });
  if (!stat.isFile() || stat.isSymbolicLink() || stat.uid !== serviceUid || stat.nlink !== 1n ||
      (stat.mode & 0o7777n) !== 0o600n || stat.size !== 0n) fail();
  return identity(stat);
}

async function readBounded(handle, maximum) {
  const bytes = Buffer.alloc(maximum + 1);
  let total = 0;
  while (total <= maximum) {
    const { bytesRead } = await handle.read(bytes, total, maximum + 1 - total, null);
    if (bytesRead === 0) break;
    total += bytesRead;
    if (total > maximum) fail();
  }
  return bytes.subarray(0, total);
}

async function writeBounded(handle, bytes) {
  let offset = 0;
  while (offset < bytes.length) {
    const { bytesWritten } = await handle.write(bytes, offset, bytes.length - offset, offset);
    if (bytesWritten <= 0) fail();
    offset += bytesWritten;
  }
}

async function readOne(request, serviceUid, resultPath, inputLines) {
  const sourcePath = canonicalLocalPath(request.path);
  if (!ALLOWED_LIMITS.has(request.maximum) || typeof request.credential !== 'boolean' ||
      request.credential !== (request.maximum === 86) || sourcePath === resultPath) fail();

  const directoriesBefore = await inspectDirectories(sourcePath, serviceUid);
  const sourcePathBefore = await inspectSourcePath(sourcePath, serviceUid, request.maximum);
  const sourceFlags = fsConstants.O_RDONLY | fsConstants.O_NONBLOCK | fsConstants.O_NOFOLLOW;
  if (!fsConstants.O_NONBLOCK || !fsConstants.O_NOFOLLOW ||
      !Number.isInteger(sourceFlags)) fail();

  let source;
  let result;
  try {
    source = await open(sourcePath, sourceFlags);
    const descriptorBefore = identity(await source.stat({ bigint: true }));
    requirePrivateRegularFile(await source.stat({ bigint: true }), serviceUid, request.maximum);
    if (!sameIdentity(sourcePathBefore, descriptorBefore)) fail();

    const resultBefore = await inspectResultPath(resultPath, serviceUid);
    const resultFlags = fsConstants.O_WRONLY | fsConstants.O_NONBLOCK | fsConstants.O_NOFOLLOW;
    result = await open(resultPath, resultFlags);
    const resultDescriptorBefore = identity(await result.stat({ bigint: true }));
    if (!sameIdentity(resultBefore, resultDescriptorBefore)) fail();

    if (!process.stdout.write(OPENED_MARKER)) fail();
    const acknowledgement = await inputLines.next();
    if (acknowledgement.done || acknowledgement.value !== 'GO') fail();

    const bytes = await readBounded(source, request.maximum);
    const descriptorAfter = identity(await source.stat({ bigint: true }));
    const sourcePathAfter = await inspectSourcePath(sourcePath, serviceUid, request.maximum);
    const directoriesAfter = await inspectDirectories(sourcePath, serviceUid);
    if (!sameIdentity(descriptorBefore, descriptorAfter) || !sameIdentity(descriptorBefore, sourcePathAfter) ||
        bytes.length !== Number(descriptorBefore.size) || JSON.stringify(directoriesBefore, bigintJson) !==
        JSON.stringify(directoriesAfter, bigintJson)) fail();

    await writeBounded(result, bytes);
    const resultDescriptorAfter = identity(await result.stat({ bigint: true }));
    const resultPathAfterStat = await lstat(resultPath, { bigint: true });
    const resultPathAfter = identity(resultPathAfterStat);
    if (!resultPathAfterStat.isFile() || resultPathAfterStat.isSymbolicLink() ||
        resultPathAfterStat.uid !== serviceUid || resultPathAfterStat.nlink !== 1n ||
        (resultPathAfterStat.mode & 0o7777n) !== 0o600n || resultPathAfterStat.size !== BigInt(bytes.length) ||
        !sameStableIdentity(resultDescriptorBefore, resultDescriptorAfter) ||
        resultDescriptorAfter.size !== BigInt(bytes.length) || !sameIdentity(resultDescriptorAfter, resultPathAfter)) fail();
  } finally {
    if (result) await result.close();
    if (source) await source.close();
  }
}

function bigintJson(_key, value) {
  return typeof value === 'bigint' ? value.toString() : value;
}

async function main() {
  if (process.versions.node !== NODE_VERSION || typeof process.getuid !== 'function') fail();
  const helperPath = fileURLToPath(import.meta.url);
  const resultPath = `${dirname(helperPath)}${sep}${RESULT_NAME}`;
  const inputLines = readUtf8Lines(process.stdin);
  const first = await inputLines.next();
  if (first.done) fail();
  let request;
  try {
    request = JSON.parse(first.value);
  } catch {
    fail();
  }
  if (request === null || typeof request !== 'object' || Array.isArray(request) ||
      Object.keys(request).sort().join(',') !== 'credential,maximum,path') fail();
  await readOne(request, BigInt(process.getuid()), resultPath, inputLines);
}

main().catch(() => {
  process.stdin.destroy();
  process.exitCode = 1;
});
