import { randomBytes } from 'node:crypto';
import {
  closeSync,
  constants,
  fchmodSync,
  fsyncSync,
  fstatSync,
  linkSync,
  lstatSync,
  mkdirSync,
  openSync,
  readSync,
  renameSync,
  unlinkSync,
  writeSync,
} from 'node:fs';
import { dirname, join, parse, relative, resolve, sep } from 'node:path';

export const PRESET_ARCHIVE_NAME = 'worldspeaker-god-preset-v1.zip';
export const MAX_PRESET_ARCHIVE_BYTES = 128 * 1024;

const FIXED_OUTPUTS = Object.freeze([
  'build/presets',
  'companion/presets',
].map((directory) => `${directory}/${PRESET_ARCHIVE_NAME}`));
const OUTPUT_MODE = 0o644;
const TEMP_MODE = 0o600;
const TEMP_ATTEMPTS = 8;
const NOFOLLOW = constants.O_NOFOLLOW ?? 0;
const REGULAR_FILE = 0o100000n;
const DIRECTORY = 0o040000n;
const FILE_TYPE_MASK = 0o170000n;

/**
 * Read a repository-owned allowlisted file without following a link at any path
 * component. The relative path is supplied by the caller's static allowlist.
 */
export function readSafePresetFile(root, relativePath, {
  errorCode = 'preset-source-not-regular',
  maxBytes = MAX_PRESET_ARCHIVE_BYTES,
} = {}) {
  const plan = inspectPath(root, relativePath, { errorCode, allowMissingTarget: false });
  const target = plan.target;
  assertOwnedRegularFile(target, errorCode, maxBytes);

  let descriptor;
  try {
    descriptor = openSync(plan.targetPath, constants.O_RDONLY | NOFOLLOW);
    const opened = fstatSync(descriptor, { bigint: true });
    if (!sameFileMetadata(opened, target)) throw codedError(errorCode);
    const bytes = readBounded(descriptor, Number(target.size), maxBytes, errorCode);
    const afterRead = fstatSync(descriptor, { bigint: true });
    if (!sameFileMetadata(afterRead, target)) throw codedError(errorCode);
    return bytes;
  } catch (error) {
    if (error?.code === errorCode) throw error;
    throw codedError(errorCode);
  } finally {
    if (descriptor !== undefined) closeSync(descriptor);
  }
}

/**
 * Build only the two fixed preset outputs beneath root. Both paths are checked
 * before creating directories or temporary files; output names are not caller
 * configurable.
 */
export function writeFixedPresetArchives(root, archiveBytes, validateGeneratedArchive) {
  const bytes = Buffer.from(archiveBytes);
  if (bytes.length === 0 || bytes.length > MAX_PRESET_ARCHIVE_BYTES ||
      typeof validateGeneratedArchive !== 'function') throw codedError('preset-output-invalid');
  try {
    if (validateGeneratedArchive(bytes) !== true) throw codedError('preset-output-invalid');
  } catch {
    throw codedError('preset-output-invalid');
  }

  // This complete first pass is deliberately mutation-free and checks both
  // destinations before either parent, temp file, or output can be changed.
  const plans = FIXED_OUTPUTS.map((relativePath) =>
    inspectOutput(root, relativePath, validateGeneratedArchive));
  const temps = [];

  try {
    for (const plan of plans) ensureOutputParents(plan);
    for (const plan of plans) recheckPlan(plan);

    for (const plan of plans) createPrivateTemp(plan, bytes, temps);

    // Recheck both after staging, before the first destination commit.
    for (const plan of plans) {
      recheckPlan(plan);
      recheckTemp(plan.temp);
    }

    for (const plan of plans) {
      // Recheck the destination and its directory identities at each commit.
      recheckPlan(plan);
      recheckTemp(plan.temp);
      commitTemp(plan);
    }
  } finally {
    for (const temp of temps) cleanupOwnedTemp(temp);
  }
}

function inspectOutput(root, relativePath, validateGeneratedArchive) {
  const plan = inspectPath(root, relativePath, { errorCode: 'preset-output-path-unsafe', allowMissingTarget: true });
  plan.relativePath = relativePath;
  plan.validateGeneratedArchive = validateGeneratedArchive;
  plan.initialTarget = plan.target ? fileIdentity(plan.target) : null;
  if (plan.target) {
    assertOwnedRegularFile(plan.target, 'preset-output-target-unsafe', MAX_PRESET_ARCHIVE_BYTES);
    const bytes = readSafePresetFile(root, relativePath, {
      errorCode: 'preset-output-target-unsafe', maxBytes: MAX_PRESET_ARCHIVE_BYTES,
    });
    try {
      if (validateGeneratedArchive(bytes) === false) throw codedError('preset-output-target-unsafe');
    } catch {
      throw codedError('preset-output-target-unsafe');
    }
  }
  return plan;
}

function inspectPath(root, relativePath, { errorCode, allowMissingTarget }) {
  const rootPath = resolve(root);
  if (typeof relativePath !== 'string' || relativePath.length === 0 || relativePath.includes('\\') ||
      relativePath.includes('\0') || relativePath.split('/').some((part) => !part || part === '.' || part === '..')) {
    throw codedError(errorCode);
  }

  const rootParents = inspectRoot(rootPath, errorCode);
  const targetPath = resolve(rootPath, relativePath);
  const withinRoot = relative(rootPath, targetPath);
  if (!withinRoot || withinRoot === '..' || withinRoot.startsWith(`..${sep}`) || parse(withinRoot).root) {
    throw codedError(errorCode);
  }

  const parts = relativePath.split('/');
  let currentPath = rootPath;
  const parentSnapshots = [...rootParents];
  let target = null;
  const missingDirectories = [];

  for (let index = 0; index < parts.length; index += 1) {
    currentPath = join(currentPath, parts[index]);
    const isTarget = index === parts.length - 1;
    const info = lstatIfExists(currentPath, errorCode);
    if (!info) {
      if (isTarget) {
        if (!allowMissingTarget) throw codedError(errorCode);
        break;
      }
      for (let missingIndex = index; missingIndex < parts.length - 1; missingIndex += 1) {
        missingDirectories.push(join(rootPath, ...parts.slice(0, missingIndex + 1)));
      }
      if (!allowMissingTarget) throw codedError(errorCode);
      break;
    }

    if (isSymbolicLink(info) || info.uid !== ownerUid()) throw codedError(errorCode);
    if (isTarget) {
      target = info;
    } else {
      if (!isDirectory(info)) throw codedError(errorCode);
      parentSnapshots.push({ path: currentPath, identity: directoryIdentity(info) });
    }
  }

  return { rootPath, targetPath, target, parentSnapshots, missingDirectories, temp: null };
}

function inspectRoot(rootPath, errorCode) {
  const parsed = parse(rootPath);
  let currentPath = parsed.root;
  const snapshots = [];
  for (const part of rootPath.slice(parsed.root.length).split(sep).filter(Boolean)) {
    currentPath = join(currentPath, part);
    const info = lstatIfExists(currentPath, errorCode);
    if (!info || isSymbolicLink(info) || !isDirectory(info)) throw codedError(errorCode);
    if (currentPath === rootPath && info.uid !== ownerUid()) throw codedError(errorCode);
    snapshots.push({ path: currentPath, identity: directoryIdentity(info) });
  }
  if (snapshots.length === 0) throw codedError(errorCode);
  return snapshots;
}

function ensureOutputParents(plan) {
  for (const directoryPath of plan.missingDirectories) {
    recheckParents(plan);
    try {
      mkdirSync(directoryPath, { mode: 0o755 });
    } catch {
      throw codedError('preset-output-path-unsafe');
    }
    const info = lstatIfExists(directoryPath, 'preset-output-path-unsafe');
    if (!info || isSymbolicLink(info) || !isDirectory(info) || info.uid !== ownerUid()) {
      throw codedError('preset-output-path-unsafe');
    }
    plan.parentSnapshots.push({ path: directoryPath, identity: directoryIdentity(info) });
  }
  plan.missingDirectories.length = 0;
}

function recheckPlan(plan) {
  recheckParents(plan);
  const current = lstatIfExists(plan.targetPath, 'preset-output-changed-during-write');
  if (plan.initialTarget === null) {
    if (current) throw codedError('preset-output-changed-during-write');
    return;
  }
  if (!current || isSymbolicLink(current) || !isRegularFile(current) || current.nlink !== 1n ||
      current.uid !== ownerUid() || fileIdentity(current) !== plan.initialTarget) {
    throw codedError('preset-output-changed-during-write');
  }
}

function recheckParents(plan) {
  for (const parent of plan.parentSnapshots) {
    const current = lstatIfExists(parent.path, 'preset-output-changed-during-write');
    if (!current || isSymbolicLink(current) || !isDirectory(current) ||
        directoryIdentity(current) !== parent.identity) {
      throw codedError('preset-output-changed-during-write');
    }
  }
}

function createPrivateTemp(plan, bytes, temps) {
  recheckParents(plan);
  const parentPath = dirname(plan.targetPath);
  for (let attempt = 0; attempt < TEMP_ATTEMPTS; attempt += 1) {
    const tempPath = join(parentPath, `.${PRESET_ARCHIVE_NAME}.tmp-${process.pid}-${randomBytes(12).toString('hex')}`);
    let descriptor;
    try {
      descriptor = openSync(tempPath, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | NOFOLLOW, TEMP_MODE);
    } catch (error) {
      if (error?.code === 'EEXIST') continue;
      throw codedError('preset-output-temp-create-failed');
    }

    try {
      const initial = fstatSync(descriptor, { bigint: true });
      const temp = { path: tempPath, identity: fileIdentity(initial), dev: initial.dev, ino: initial.ino };
      temps.push(temp);
      if (!isRegularFile(initial) || initial.uid !== ownerUid() || initial.nlink !== 1n ||
          (initial.mode & 0o7777n) !== BigInt(TEMP_MODE)) throw codedError('preset-output-temp-unsafe');
      writeAll(descriptor, bytes);
      fsyncSync(descriptor);
      fchmodSync(descriptor, OUTPUT_MODE);
      const finished = fstatSync(descriptor, { bigint: true });
      if (!isRegularFile(finished) || finished.uid !== ownerUid() || finished.nlink !== 1n ||
          finished.size !== BigInt(bytes.length) || (finished.mode & 0o7777n) !== BigInt(OUTPUT_MODE)) {
        throw codedError('preset-output-temp-unsafe');
      }
      temp.identity = fileIdentity(finished);
      recheckTemp(temp, descriptor);
      plan.temp = temp;
      return;
    } finally {
      closeSync(descriptor);
    }
  }
  throw codedError('preset-output-temp-create-failed');
}

function recheckTemp(temp, descriptor) {
  let opened;
  if (descriptor !== undefined) opened = fstatSync(descriptor, { bigint: true });
  const pathInfo = lstatIfExists(temp.path, 'preset-output-temp-unsafe');
  if (!pathInfo || isSymbolicLink(pathInfo) || !isRegularFile(pathInfo) || pathInfo.uid !== ownerUid() ||
      pathInfo.nlink !== 1n || fileIdentity(pathInfo) !== temp.identity ||
      (opened && fileIdentity(opened) !== temp.identity)) throw codedError('preset-output-temp-unsafe');
}

function commitTemp(plan) {
  const temp = plan.temp;
  const current = lstatIfExists(plan.targetPath, 'preset-output-changed-during-write');
  if (plan.initialTarget === null) {
    if (current) throw codedError('preset-output-changed-during-write');
    try {
      // link(2) provides exclusive creation if a path appears after the check.
      linkSync(temp.path, plan.targetPath);
      unlinkSync(temp.path);
    } catch {
      throw codedError('preset-output-commit-failed');
    }
  } else {
    if (!current || isSymbolicLink(current) || !isRegularFile(current) || current.nlink !== 1n ||
        current.uid !== ownerUid() || fileIdentity(current) !== plan.initialTarget) {
      throw codedError('preset-output-changed-during-write');
    }
    try {
      renameSync(temp.path, plan.targetPath);
    } catch {
      throw codedError('preset-output-commit-failed');
    }
  }

  const committed = lstatIfExists(plan.targetPath, 'preset-output-commit-failed');
  if (!committed || isSymbolicLink(committed) || !isRegularFile(committed) || committed.uid !== ownerUid() ||
      committed.nlink !== 1n || committed.size > BigInt(MAX_PRESET_ARCHIVE_BYTES) ||
      (committed.mode & 0o7777n) !== BigInt(OUTPUT_MODE) ||
      committed.dev !== temp.dev || committed.ino !== temp.ino) {
    throw codedError('preset-output-commit-failed');
  }
}

function cleanupOwnedTemp(temp) {
  if (!temp) return;
  try {
    const current = lstatIfExists(temp.path, 'preset-output-temp-cleanup-failed');
    if (!current || isSymbolicLink(current) || !isRegularFile(current) || current.uid !== ownerUid() ||
        current.dev !== temp.dev || current.ino !== temp.ino) return;
    unlinkSync(temp.path);
  } catch {
    // Cleanup is best-effort and only ever unlinks the exact file created here.
  }
}

function assertOwnedRegularFile(info, errorCode, maxBytes) {
  if (isSymbolicLink(info) || !isRegularFile(info) || info.uid !== ownerUid() || info.nlink !== 1n ||
      info.size > BigInt(maxBytes)) throw codedError(errorCode);
}

function readBounded(descriptor, expectedSize, maxBytes, errorCode) {
  if (!Number.isSafeInteger(expectedSize) || expectedSize < 0 || expectedSize > maxBytes) throw codedError(errorCode);
  const bytes = Buffer.alloc(expectedSize);
  let offset = 0;
  while (offset < bytes.length) {
    const count = readSync(descriptor, bytes, offset, bytes.length - offset, null);
    if (count === 0) throw codedError(errorCode);
    offset += count;
  }
  const extra = Buffer.alloc(1);
  if (readSync(descriptor, extra, 0, 1, null) !== 0) throw codedError(errorCode);
  return bytes;
}

function writeAll(descriptor, bytes) {
  let offset = 0;
  while (offset < bytes.length) {
    const count = writeSync(descriptor, bytes, offset, bytes.length - offset, null);
    if (count <= 0) throw codedError('preset-output-temp-write-failed');
    offset += count;
  }
}

function lstatIfExists(path, errorCode) {
  try { return lstatSync(path, { bigint: true }); }
  catch (error) {
    if (error?.code === 'ENOENT') return null;
    throw codedError(errorCode);
  }
}

function isSymbolicLink(info) {
  return (info.mode & FILE_TYPE_MASK) === 0o120000n;
}

function isRegularFile(info) {
  return (info.mode & FILE_TYPE_MASK) === REGULAR_FILE;
}

function isDirectory(info) {
  return (info.mode & FILE_TYPE_MASK) === DIRECTORY;
}

function ownerUid() {
  return BigInt(process.geteuid?.() ?? process.getuid());
}

function fileIdentity(info) {
  return [info.dev, info.ino, info.mode, info.nlink, info.uid, info.gid, info.size, info.mtimeNs, info.ctimeNs].join(':');
}

function sameFileMetadata(left, right) {
  return fileIdentity(left) === fileIdentity(right);
}

function directoryIdentity(info) {
  return [info.dev, info.ino, info.mode, info.uid, info.gid].join(':');
}

function codedError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}
