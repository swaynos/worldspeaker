import {
  chmodSync,
  closeSync,
  copyFileSync,
  constants as fsConstants,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  openSync,
  readSync,
  realpathSync,
  rmSync,
  statSync,
  writeFileSync,
  writeSync,
} from 'node:fs';
import { randomUUID } from 'node:crypto';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { spawn } from 'node:child_process';

const ROOT_EXIT_DRAIN_QUIET_MS = 50;
const ROOT_EXIT_DRAIN_MAX_MS = 500;
export const MAX_FAILURE_RECORDS = 8;
export const MAX_FAILURE_EVIDENCE_BYTES = 64 * 1024 * 1024;
const SAFE_ERROR_CODES = new Set([
  'E2BIG', 'EACCES', 'EAGAIN', 'EBADF', 'EBUSY', 'EEXIST', 'EINTR', 'EINVAL', 'EIO',
  'EISDIR', 'EMFILE', 'ENFILE', 'ENOENT', 'ENOMEM', 'ENOSPC', 'ENOTDIR', 'EPERM',
  'ETIMEDOUT', 'EXDEV', 'ENOEXEC', 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER',
  'process-spawn-failed', 'private-log-setup-failed', 'private-log-read-failed',
  'private-log-bound-violated', 'private-log-write-failed', 'private-log-cleanup-failed',
  'owned-process-group-cleanup-failed', 'process-termination-failed', 'child-process-stdio-error',
  'CHILD_EXIT_NONZERO', 'CHILD_SIGNAL', 'CHILD_PROCESS_ERROR',
]);
const SAFE_SIGNALS = new Set([
  'SIGABRT', 'SIGALRM', 'SIGBUS', 'SIGCHLD', 'SIGCONT', 'SIGFPE', 'SIGHUP', 'SIGILL',
  'SIGINT', 'SIGIO', 'SIGIOT', 'SIGKILL', 'SIGPIPE', 'SIGQUIT', 'SIGSEGV', 'SIGSTOP',
  'SIGTERM', 'SIGTRAP', 'SIGTSTP', 'SIGTTIN', 'SIGTTOU', 'SIGURG', 'SIGUSR1', 'SIGUSR2',
  'SIGVTALRM', 'SIGXCPU', 'SIGXFSZ',
]);
const SAFE_EVIDENCE_CODES = new Set([
  'RETAINED', 'CAPACITY_REFUSED', 'FAILURE_ROOT_REJECTED', 'RETENTION_FAILED',
]);

let processFailureRoot;
let retainedFailureCount = 0;
let retainedFailureBytes = 0;

export async function runBoundedSubprocess(command, args, {
  cwd,
  env = process.env,
  timeoutMs,
  maxBuffer,
  temporaryDirectory = tmpdir(),
  cleanupOwnedProcessGroupOnExit = true,
  failureRoot,
  testLabel = 'unlabeled',
}) {
  if (!Number.isInteger(timeoutMs) || timeoutMs <= 0) throw new TypeError('timeoutMs must be a positive integer');
  if (!Number.isSafeInteger(maxBuffer) || maxBuffer <= 0) throw new TypeError('maxBuffer must be a positive safe integer');
  if (cleanupOwnedProcessGroupOnExit && process.platform === 'win32') {
    throw new Error('owned process-group cleanup is unavailable on Windows');
  }
  const startedAt = Date.now();
  const safeLabel = normalizeTestLabel(testLabel);

  let logDirectory;
  let stdoutPath;
  let stderrPath;
  let stdoutFd;
  let stderrFd;
  try {
    logDirectory = mkdtempSync(join(temporaryDirectory, 'worldspeaker-test-process-'));
    chmodSync(logDirectory, 0o700);
    stdoutPath = join(logDirectory, 'stdout.log');
    stderrPath = join(logDirectory, 'stderr.log');
    stdoutFd = openSync(stdoutPath, 'wx', 0o600);
    stderrFd = openSync(stderrPath, 'wx', 0o600);
    chmodSync(stdoutPath, 0o600);
    chmodSync(stderrPath, 0o600);
  } catch {
    if (Number.isInteger(stdoutFd)) closeSync(stdoutFd);
    if (Number.isInteger(stderrFd)) closeSync(stderrFd);
    if (logDirectory) rmSync(logDirectory, { recursive: true, force: true });
    return {
      ...emptyResult(processError('private-log-setup-failed'), maxBuffer, cleanupOwnedProcessGroupOnExit),
      evidencePath: null,
      evidenceCode: 'RETENTION_FAILED',
      diagnosticCode: 'private-log-setup-failed',
      elapsedMs: Date.now() - startedAt,
    };
  }

  let result;
  let child;
  try {
    child = spawn(command, args, {
      cwd,
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
      // With detached=true, Node starts a new POSIX session and process group;
      // its PID is therefore the group ID that this invocation owns.
      detached: cleanupOwnedProcessGroupOnExit,
      windowsHide: true,
    });
    result = await waitForRootExit(child, {
      timeoutMs,
      maxBuffer,
      stdoutFd,
      stderrFd,
      cleanupOwnedProcessGroup: cleanupOwnedProcessGroupOnExit,
    });
  } catch {
    result = {
      status: null,
      signal: null,
      error: processError('process-spawn-failed'),
      outputStats: emptyOutputStats(maxBuffer),
      processCleanup: {
        requested: cleanupOwnedProcessGroupOnExit,
        isolated: false,
        completed: null,
      },
      outputOverflow: false,
    };
  } finally {
    if (Number.isInteger(stdoutFd)) closeSync(stdoutFd);
    if (Number.isInteger(stderrFd)) closeSync(stderrFd);
  }

  const safeError = result.error ? processError(normalizeErrorCode(result.error.code)) : null;
  const boundedResult = {
    ...result,
    status: result.outputOverflow ? null : result.status,
    error: safeError,
    stdout: '',
    stderr: '',
    elapsedMs: Date.now() - startedAt,
  };
  const succeeded = boundedResult.status === 0
    && boundedResult.signal === null
    && boundedResult.error === null
    && !boundedResult.outputOverflow;

  if (succeeded) {
    try {
      const stdout = readBoundedLog(stdoutPath, maxBuffer);
      const stderr = readBoundedLog(stderrPath, maxBuffer);
      const logPermissions = {
        directory: statSync(logDirectory).mode & 0o777,
        stdout: statSync(stdoutPath).mode & 0o777,
        stderr: statSync(stderrPath).mode & 0o777,
      };
      rmSync(logDirectory, { recursive: true, force: true });
      return { ...boundedResult, stdout, stderr, logPermissions };
    } catch {
      boundedResult.error ??= processError('private-log-read-failed');
    }
  }

  const evidence = retainFailureEvidence({
    failureRoot,
    logDirectory,
    stdoutPath,
    stderrPath,
    maxBuffer,
    result: { ...result, error: boundedResult.error },
    testLabel: safeLabel,
    elapsedMs: boundedResult.elapsedMs,
  });
  boundedResult.evidencePath = evidence.path;
  boundedResult.evidenceCode = evidence.code;
  boundedResult.diagnosticCode = evidence.errorCode;
  try {
    rmSync(logDirectory, { recursive: true, force: true });
  } catch {
    boundedResult.error ??= processError('private-log-cleanup-failed');
  }
  return boundedResult;
}

export function recordFailureEvidence(result) {
  if (!result || !SAFE_EVIDENCE_CODES.has(result.evidenceCode)) return;
  const path = typeof result.evidencePath === 'string' ? result.evidencePath : null;
  const errorCode = normalizeErrorCode(result.diagnosticCode ?? result.error?.code);
  process.stderr.write(`[worldspeaker-private-child-evidence] ${JSON.stringify({ path, code: result.evidenceCode, errorCode })}\n`);
}

function waitForRootExit(child, {
  timeoutMs,
  maxBuffer,
  stdoutFd,
  stderrFd,
  cleanupOwnedProcessGroup,
}) {
  return new Promise((resolve) => {
    const streams = [
      { stream: child.stdout, fd: stdoutFd, bytesWritten: 0, maxChunkBytes: 0, writeFailed: false, ended: false, truncated: false },
      { stream: child.stderr, fd: stderrFd, bytesWritten: 0, maxChunkBytes: 0, writeFailed: false, ended: false, truncated: false },
    ];
    let rootExited = false;
    let drainFinished = false;
    let cleanupFinished = !cleanupOwnedProcessGroup;
    let cleanupSucceeded = null;
    let cleanupPromise;
    let rootStatus = null;
    let rootSignal = null;
    let resultError = null;
    let outputOverflow = false;
    let timeoutTimer;
    let forceKillTimer;
    let exitDrainQuietTimer;
    let exitDrainMaximumTimer;
    let drainStartedAt = null;
    let drainElapsedMs = 0;
    let stopStarted = false;
    let settled = false;

    const recordError = (code) => {
      resultError ??= processError(code);
    };

    const startOwnedGroupCleanup = () => {
      if (!cleanupOwnedProcessGroup || cleanupPromise) return;
      if (!Number.isSafeInteger(child.pid) || child.pid <= 1) {
        cleanupFinished = true;
        cleanupSucceeded = null;
        maybeFinish();
        return;
      }

      cleanupPromise = terminateOwnedProcessGroup(child.pid)
        .then((success) => {
          cleanupFinished = true;
          cleanupSucceeded = success;
          if (!success) recordError('owned-process-group-cleanup-failed');
          maybeFinish();
        }, () => {
          cleanupFinished = true;
          cleanupSucceeded = false;
          recordError('owned-process-group-cleanup-failed');
          maybeFinish();
        });
    };

    const signalRoot = (signal) => {
      try {
        child.kill(signal);
      } catch {
        recordError('process-termination-failed');
      }
    };

    const stopOwnedChild = () => {
      if (stopStarted) return;
      stopStarted = true;
      if (cleanupOwnedProcessGroup) startOwnedGroupCleanup();
      else signalRoot('SIGTERM');

      // Keep the direct-child fallback even for an owned group: it ensures a
      // timeout/overflow cannot leave the root alive if group cleanup cannot
      // be confirmed by the platform.
      forceKillTimer = setTimeout(() => {
        if (!rootExited) signalRoot('SIGKILL');
      }, 1_000);
    };

    const notePostExitOutput = () => {
      if (!rootExited || drainFinished) return;
      clearTimeout(exitDrainQuietTimer);
      exitDrainQuietTimer = setTimeout(finishDrain, ROOT_EXIT_DRAIN_QUIET_MS);
    };

    const finishDrain = () => {
      if (drainFinished) return;
      drainFinished = true;
      if (drainStartedAt !== null) drainElapsedMs = Date.now() - drainStartedAt;
      clearTimeout(exitDrainQuietTimer);
      clearTimeout(exitDrainMaximumTimer);
      for (const entry of streams) entry.stream?.destroy();
      maybeFinish();
    };

    const maybeFinish = () => {
      if (settled || !rootExited || !drainFinished || !cleanupFinished) return;
      settled = true;
      clearTimeout(timeoutTimer);
      clearTimeout(forceKillTimer);
      resolve({
        status: rootStatus,
        signal: rootSignal,
        error: resultError,
        outputOverflow,
        outputStats: {
          stdoutBytes: streams[0].bytesWritten,
          stderrBytes: streams[1].bytesWritten,
          totalBytes: streams[0].bytesWritten + streams[1].bytesWritten,
          maxBufferPerStream: maxBuffer,
          maxChunkBytes: {
            stdout: streams[0].maxChunkBytes,
            stderr: streams[1].maxChunkBytes,
          },
          truncated: {
            stdout: streams[0].truncated,
            stderr: streams[1].truncated,
          },
          rootExitDrainMs: drainElapsedMs,
          rootExitDrainMaxMs: ROOT_EXIT_DRAIN_MAX_MS,
        },
        processCleanup: {
          requested: cleanupOwnedProcessGroup,
          isolated: cleanupOwnedProcessGroup && Number.isSafeInteger(child.pid) && child.pid > 1,
          completed: cleanupSucceeded,
        },
      });
    };

    const finishRoot = (status, signal) => {
      if (rootExited) return;
      rootExited = true;
      rootStatus = Number.isInteger(status) ? status : null;
      rootSignal = signal ?? null;
      clearTimeout(timeoutTimer);
      clearTimeout(forceKillTimer);

      // The root process exit, not pipe EOF, starts finalization. If this call
      // owns a detached process group, stop inherited-pipe descendants now.
      if (cleanupOwnedProcessGroup) startOwnedGroupCleanup();

      drainStartedAt = Date.now();
      if (streams.every((entry) => entry.ended)) {
        finishDrain();
      } else {
        exitDrainMaximumTimer = setTimeout(finishDrain, ROOT_EXIT_DRAIN_MAX_MS);
        // Give already-readable kernel/Node buffers a poll turn before the
        // quiet window starts. New data resets that finite quiet window.
        setImmediate(notePostExitOutput);
      }
      maybeFinish();
    };

    for (const entry of streams) {
      if (!entry.stream) {
        entry.ended = true;
        continue;
      }
      entry.stream.on('data', (chunk) => {
        notePostExitOutput();
        entry.maxChunkBytes = Math.max(entry.maxChunkBytes, chunk.length);

        const remaining = maxBuffer - entry.bytesWritten;
        const writeLength = Math.min(chunk.length, remaining);
        let offset = 0;
        if (!entry.writeFailed) {
          try {
            while (offset < writeLength) {
              const written = writeSync(entry.fd, chunk, offset, writeLength - offset, null);
              if (written <= 0) throw new Error('private output write made no progress');
              offset += written;
              entry.bytesWritten += written;
            }
          } catch {
            entry.writeFailed = true;
            recordError('private-log-write-failed');
            stopOwnedChild();
          }
        }

        if (!entry.writeFailed && chunk.length > writeLength) {
          entry.truncated = true;
          if (!outputOverflow) {
            outputOverflow = true;
            recordError('ERR_CHILD_PROCESS_STDIO_MAXBUFFER');
            stopOwnedChild();
          }
        }
        if (entry.writeFailed) entry.truncated = true;
      });
      entry.stream.once('end', () => {
        entry.ended = true;
        if (rootExited && streams.every((candidate) => candidate.ended)) finishDrain();
      });
      entry.stream.once('error', () => {
        recordError('child-process-stdio-error');
        entry.ended = true;
        stopOwnedChild();
        if (rootExited && streams.every((candidate) => candidate.ended)) finishDrain();
      });
    }

    child.once('error', (error) => {
      if (rootExited) return;
      resultError ??= processError(normalizeErrorCode(error.code ?? 'process-spawn-failed'));
      finishRoot(null, null);
    });
    child.once('exit', finishRoot);
    timeoutTimer = setTimeout(() => {
      recordError('ETIMEDOUT');
      stopOwnedChild();
    }, timeoutMs);
  });
}

function readBoundedLog(path, maxBytes) {
  const size = statSync(path).size;
  if (size > maxBytes) throw processError('private-log-bound-violated');
  const bytes = Buffer.alloc(size);
  let fd;
  let offset = 0;
  try {
    fd = openSync(path, 'r');
    while (offset < size) {
      const read = readSync(fd, bytes, offset, size - offset, offset);
      if (read === 0) break;
      offset += read;
    }
  } finally {
    if (Number.isInteger(fd)) closeSync(fd);
  }
  if (offset !== size) throw processError('private-log-read-failed');
  return bytes.toString('utf8');
}

function retainFailureEvidence({
  failureRoot,
  logDirectory,
  stdoutPath,
  stderrPath,
  maxBuffer,
  result,
  testLabel,
  elapsedMs,
}) {
  const diagnosticCode = failureErrorCode(result);
  let root;
  try {
    root = failureRoot === undefined ? getProcessFailureRoot() : prepareExplicitFailureRoot(failureRoot);
  } catch {
    return { path: null, code: 'FAILURE_ROOT_REJECTED', errorCode: diagnosticCode };
  }

  let stdoutBytes;
  let stderrBytes;
  try {
    assertOwnedRegularLog(logDirectory, stdoutPath, maxBuffer);
    assertOwnedRegularLog(logDirectory, stderrPath, maxBuffer);
    stdoutBytes = statSync(stdoutPath).size;
    stderrBytes = statSync(stderrPath).size;
  } catch {
    return { path: null, code: 'RETENTION_FAILED', errorCode: diagnosticCode };
  }

  const manifest = {
    version: 1,
    testLabel,
    rootExit: Number.isInteger(result.status) ? result.status : null,
    signal: SAFE_SIGNALS.has(result.signal) ? result.signal : null,
    errorCode: diagnosticCode,
    elapsedMs: Number.isSafeInteger(elapsedMs) && elapsedMs >= 0 ? elapsedMs : 0,
    maxBytes: {
      perStream: maxBuffer,
      childTotal: Math.min(Number.MAX_SAFE_INTEGER, maxBuffer * 2),
      processAggregate: MAX_FAILURE_EVIDENCE_BYTES,
    },
    truncated: {
      stdout: result.outputStats?.truncated?.stdout === true,
      stderr: result.outputStats?.truncated?.stderr === true,
    },
  };
  const manifestBytes = Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
  const requiredBytes = stdoutBytes + stderrBytes + manifestBytes.length;
  if (retainedFailureCount >= MAX_FAILURE_RECORDS
    || requiredBytes > MAX_FAILURE_EVIDENCE_BYTES - retainedFailureBytes) {
    return { path: null, code: 'CAPACITY_REFUSED', errorCode: diagnosticCode };
  }

  let recordDirectory;
  try {
    recordDirectory = createFailureRecordDirectory(root);
    const retainedStdout = join(recordDirectory, 'stdout.log');
    const retainedStderr = join(recordDirectory, 'stderr.log');
    copyFileSync(stdoutPath, retainedStdout, fsConstants.COPYFILE_EXCL);
    copyFileSync(stderrPath, retainedStderr, fsConstants.COPYFILE_EXCL);
    chmodSync(retainedStdout, 0o600);
    chmodSync(retainedStderr, 0o600);
    const manifestPath = join(recordDirectory, 'manifest.json');
    writeFileSync(manifestPath, manifestBytes, { flag: 'wx', mode: 0o600 });
    chmodSync(manifestPath, 0o600);

    const finalBytes = statSync(retainedStdout).size
      + statSync(retainedStderr).size
      + statSync(manifestPath).size;
    if (finalBytes > requiredBytes
      || finalBytes > MAX_FAILURE_EVIDENCE_BYTES - retainedFailureBytes) {
      rmSync(recordDirectory, { recursive: true, force: true });
      return { path: null, code: 'CAPACITY_REFUSED', errorCode: diagnosticCode };
    }
    retainedFailureCount += 1;
    retainedFailureBytes += finalBytes;
    return { path: recordDirectory, code: 'RETAINED', errorCode: diagnosticCode };
  } catch {
    if (recordDirectory) {
      try {
        rmSync(recordDirectory, { recursive: true, force: true });
      } catch {
        // Remove only this invocation's newly-created record directory.
      }
    }
    return { path: null, code: 'RETENTION_FAILED', errorCode: diagnosticCode };
  }
}

function getProcessFailureRoot() {
  if (!processFailureRoot) {
    const createdRoot = mkdtempSync(join(tmpdir(), 'worldspeaker-failure-evidence-'));
    chmodSync(createdRoot, 0o700);
    processFailureRoot = realpathSync(createdRoot);
  }
  return processFailureRoot;
}

function prepareExplicitFailureRoot(failureRoot) {
  if (typeof failureRoot !== 'string' || failureRoot.length === 0) throw processError('failure-root-invalid');
  const requestedPath = resolve(failureRoot);
  const parentPath = realpathSync(dirname(requestedPath));
  const canonicalPath = join(parentPath, basename(requestedPath));
  let rootStat;
  try {
    rootStat = lstatSync(requestedPath);
    if (rootStat.isSymbolicLink()) throw processError('failure-root-symlink');
  } catch (error) {
    if (error.code !== 'ENOENT') throw processError('failure-root-invalid');
    mkdirSync(canonicalPath, { mode: 0o700 });
    chmodSync(canonicalPath, 0o700);
    rootStat = lstatSync(canonicalPath);
  }
  if (rootStat.isSymbolicLink() || !rootStat.isDirectory()) throw processError('failure-root-invalid');
  const physicalPath = realpathSync(canonicalPath);
  if (physicalPath !== canonicalPath) throw processError('failure-root-linked');
  const rootMode = rootStat.mode & 0o777;
  const currentUid = typeof process.getuid === 'function' ? process.getuid() : null;
  if (rootMode !== 0o700 || (currentUid !== null && rootStat.uid !== currentUid)) {
    throw processError('failure-root-not-private');
  }
  return canonicalPath;
}

function createFailureRecordDirectory(root) {
  validatePrivateFailureRoot(root);
  for (let attempt = 0; attempt < 5; attempt += 1) {
    const recordDirectory = join(root, `failure-${String(retainedFailureCount + 1).padStart(2, '0')}-${randomUUID()}`);
    try {
      mkdirSync(recordDirectory, { mode: 0o700 });
      chmodSync(recordDirectory, 0o700);
      return recordDirectory;
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
    }
  }
  throw processError('failure-record-name-exhausted');
}

function validatePrivateFailureRoot(root) {
  const rootStat = lstatSync(root);
  if (rootStat.isSymbolicLink() || !rootStat.isDirectory()) throw processError('failure-root-invalid');
  if (realpathSync(root) !== root || (rootStat.mode & 0o777) !== 0o700) {
    throw processError('failure-root-linked');
  }
  if (typeof process.getuid === 'function' && rootStat.uid !== process.getuid()) {
    throw processError('failure-root-not-owned');
  }
}

function assertOwnedRegularLog(logDirectory, logPath, maxBuffer) {
  const directoryStat = lstatSync(logDirectory);
  const fileStat = lstatSync(logPath);
  if (directoryStat.isSymbolicLink() || !directoryStat.isDirectory()
    || (directoryStat.mode & 0o777) !== 0o700
    || fileStat.isSymbolicLink() || !fileStat.isFile()
    || fileStat.nlink !== 1 || (fileStat.mode & 0o777) !== 0o600
    || fileStat.size > maxBuffer) {
    throw processError('private-log-bound-violated');
  }
}

function failureErrorCode(result) {
  if (result.error) return normalizeErrorCode(result.error.code);
  if (result.outputOverflow) return 'ERR_CHILD_PROCESS_STDIO_MAXBUFFER';
  if (result.signal) return 'CHILD_SIGNAL';
  if (Number.isInteger(result.status) && result.status !== 0) return 'CHILD_EXIT_NONZERO';
  return 'CHILD_PROCESS_ERROR';
}

function normalizeErrorCode(code) {
  return typeof code === 'string' && SAFE_ERROR_CODES.has(code) ? code : 'CHILD_PROCESS_ERROR';
}

function normalizeTestLabel(label) {
  return typeof label === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(label)
    ? label
    : 'unlabeled';
}

async function terminateOwnedProcessGroup(groupId) {
  if (!Number.isSafeInteger(groupId) || groupId <= 1) return false;
  signalOwnedGroup(groupId, 'SIGTERM');
  if (await waitForProcessGroupExit(groupId, 200)) return true;
  signalOwnedGroup(groupId, 'SIGKILL');
  return waitForProcessGroupExit(groupId, 750);
}

function signalOwnedGroup(groupId, signal) {
  try {
    process.kill(-groupId, signal);
  } catch (error) {
    if (error.code !== 'ESRCH') return false;
  }
  return true;
}

async function waitForProcessGroupExit(groupId, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (processGroupExists(groupId) && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  return !processGroupExists(groupId);
}

function processGroupExists(groupId) {
  try {
    process.kill(-groupId, 0);
    return true;
  } catch (error) {
    return error.code !== 'ESRCH';
  }
}

function emptyResult(error, maxBuffer, cleanupOwnedProcessGroup) {
  return {
    status: null,
    signal: null,
    error,
    stdout: '',
    stderr: '',
    outputStats: emptyOutputStats(maxBuffer),
    processCleanup: { requested: cleanupOwnedProcessGroup, isolated: false, completed: null },
  };
}

function emptyOutputStats(maxBuffer) {
  return {
    stdoutBytes: 0,
    stderrBytes: 0,
    totalBytes: 0,
    maxBufferPerStream: maxBuffer,
    maxChunkBytes: { stdout: 0, stderr: 0 },
    truncated: { stdout: false, stderr: false },
    rootExitDrainMs: 0,
    rootExitDrainMaxMs: ROOT_EXIT_DRAIN_MAX_MS,
  };
}

function processError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}
