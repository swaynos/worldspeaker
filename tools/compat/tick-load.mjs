import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import {
  chmod, copyFile, lstat, mkdir, readFile, readdir, rename, stat, writeFile,
} from 'node:fs/promises';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { connect } from 'node:net';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';
import {
  buildIsolatedEnvironment,
  downloadFabricInputs,
  ensureNewWorkdirOutsideRepo,
  getFreeLoopbackPort,
  inspectRuntime,
  installFabric,
  startManagedProcess,
  stopManagedProcess,
  validateManifestPins,
  writeWorldspeakerConfig,
} from './clean-install.mjs';
import { assertEulaAuthorization } from './clean-install-core.mjs';
import { readZipEntries, verifyJar } from './verify-server-only.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const DEPLOYMENT_PATH = join(ROOT, 'compatibility/deployment.json');
const HARNESS_SOURCE_ROOT = join(ROOT, 'src/loadTest');
const FIXTURE_PEER_PATH = join(ROOT, 'tools/compat/tick-load-peer.mjs');
const RUNS_PER_INVOCATION = 3;
const RUN_LIMIT_MS = 40 * 60 * 1000;
const WARMUP_MS = 120_000;
const MEASURED_PHASE_MS = 300_000;
const REPEAT_PHASE_MS = 6_000;
const BACKLOG_PHASE_MS = 6_000;
const OVERLOAD_INVOCATIONS_PER_SECOND = 20;
const EXPECTED_OVERLOAD_ATTEMPTS = (MEASURED_PHASE_MS / 1000) * OVERLOAD_INVOCATIONS_PER_SECOND;
const MIN_OVERLOAD_RATE_PER_SECOND = OVERLOAD_INVOCATIONS_PER_SECOND * 0.99;
const ACTIVE_REQUEST_CAPACITY = 1;
const GLOBAL_PENDING_CAPACITY = 16;
const PER_PLAYER_PENDING_CAPACITY = 2;
const CAPACITY_PLAYER_COUNT = 8;
const REQUIRED_PHASE_DURATION_TOLERANCE_MS = 1_000;
const PEER_START_MS = 10_000;
const SERVER_START_MS = 120_000;
const INTEGRATION_READY_MS = 60_000;
const PEER_STOP_MS = 5_000;
const SERVER_STOP = Object.freeze({ gracefulStopMs: 10_000, forcedStopMs: 5_000 });
const MAX_PLAYERS_FILE_BYTES = 4096;
const MAX_EVIDENCE_BYTES_PER_RUN = 64 * 1024 * 1024;
const PHASE_NAMES = new Set(['idle', 'normal', 'overload', 'unavailable', 'recovery', 'repeat', 'stop-backlog']);
const FIRST_PUBLIC_DISPATCH_SOURCES = Object.freeze([
  'LOCAL_ACKNOWLEDGEMENT', 'INTEGRATION_ANSWER', 'INTEGRATION_FAILURE', 'LOCAL_SAFE_RESPONSE',
  'LOCAL_REJECTION', 'LOCAL_UNAVAILABLE', 'LOCAL_COALESCED', 'COMMAND_HELP', 'LOCAL_FOLLOWUP_END',
  'COMMAND_GUIDANCE', 'NATURAL_INVALID', 'LOCAL_UNAUTHENTICATED', 'LOCAL_DENIED', 'LOCAL_BUSY',
]);
const FIRST_PUBLIC_DISPATCH_DISPOSITIONS = Object.freeze([
  'ACKNOWLEDGED', 'ANSWERED', 'FAILED', 'REJECTED', 'UNAVAILABLE', 'COALESCED', 'HELP',
  'FOLLOWUP_ENDED', 'INVALID', 'DENIED', 'UNAUTHENTICATED', 'BUSY',
]);
const INTEGRATION_COUNTER_NAMES = Object.freeze([
  'FIRST_REPLY_DEADLINE_MISSES', 'TERMINAL_DUPLICATES', 'MAILBOX_OVERFLOWS', 'STALE_OUTPUTS',
  'TICK_BUDGET_EXHAUSTED', 'PENDING_EXPIRED', 'UPSTREAM_REJECTIONS', 'ACK_CONTEXT_FAILURES',
  'CONNECTION_FAILURES', 'RECONNECT_EXHAUSTED', 'REQUESTS_ADMITTED', 'ANSWERS_DELIVERED',
  'PROGRESS_DISPATCHES', 'PROGRESS_FAILURES', 'PROGRESS_MISSED', 'PROGRESS_UNKNOWN',
  'PROGRESS_CONTEXT_FAILURES', 'UNAVAILABLE_DISPOSITIONS', 'INVOCATIONS_CAPTURED', 'INTERACTION_HELP',
  'INTERACTION_INVALID', 'INTERACTION_DENIED', 'INTERACTION_UNAUTHENTICATED',
  'INTERACTION_UNAVAILABLE_BEFORE_BOOTSTRAP', 'INTERACTION_RECEIPT_EVIDENCE_MISSING', 'INTERACTION_UNAVAILABLE',
  'INTERACTION_BUSY', 'INTERACTION_COALESCED', 'INTERACTION_ADMITTED', 'INTERACTION_FOLLOWUP_ENDED',
  'FAILED_PUBLIC_DISPATCHES', 'INTERACTION_EVIDENCE_DROPPED', 'FIRST_REPLY_ON_TIME', 'FIRST_REPLY_LATE',
  'FIRST_REPLY_NO_DISPATCH', 'FIRST_REPLY_NO_DISPATCH_SETTLED', 'UNKNOWN_PUBLIC_DISPATCHES',
  'CONTROL_QUEUE_OVERFLOWS', 'DISPOSITION_QUEUE_OVERFLOWS',
]);
const EXPECTED_PHASE_DURATION_MS = Object.freeze({
  idle: MEASURED_PHASE_MS,
  normal: MEASURED_PHASE_MS,
  overload: MEASURED_PHASE_MS,
  unavailable: MEASURED_PHASE_MS,
  recovery: MEASURED_PHASE_MS,
  repeat: REPEAT_PHASE_MS,
  'stop-backlog': BACKLOG_PHASE_MS,
});
const ROOT_MANIFEST = JSON.parse(await readFile(DEPLOYMENT_PATH, 'utf8'));

let activeAbortController = null;

export function parseTickLoadArgs(tokens, repositoryRoot = ROOT) {
  const result = {
    distribution: 'build/distributions/worldspeaker-candidate',
    harness: `build/load-harness/${ROOT_MANIFEST.mod.id}-${ROOT_MANIFEST.mod.version}-compat-load-harness.jar`,
    java: 'java',
    node: process.execPath,
    npm: 'npm',
    peer: 'fixture',
    startServer: false,
    acceptEula: false,
    help: false,
  };
  const values = new Map([
    ['--distribution', 'distribution'], ['--harness', 'harness'], ['--workdir', 'workdir'],
    ['--players-file', 'playersFile'], ['--java', 'java'], ['--node', 'node'], ['--npm', 'npm'],
    ['--peer', 'peer'],
  ]);
  const booleans = new Map([['--start-server', 'startServer'], ['--accept-eula', 'acceptEula'], ['--help', 'help']]);
  const seen = new Set();
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (typeof token !== 'string' || seen.has(token)) throw codedError(`duplicate-or-invalid-option-${safeOption(token)}`);
    seen.add(token);
    if (booleans.has(token)) {
      result[booleans.get(token)] = true;
      continue;
    }
    const key = values.get(token);
    if (!key) throw codedError(`unknown-option-${safeOption(token)}`);
    const value = tokens[++index];
    if (typeof value !== 'string' || value.length === 0 || value.startsWith('--')) {
      throw codedError(`option-value-required-${safeOption(token)}`);
    }
    result[key] = value;
  }
  if (result.help) return result;
  if (!result.workdir) throw codedError('workdir-required');
  if (!isAbsolutePath(result.workdir)) throw codedError('workdir-must-be-absolute');
  if (!result.playersFile) throw codedError('players-file-required');
  if (!isAbsolutePath(result.playersFile)) throw codedError('players-file-must-be-absolute');
  if (!result.startServer || !result.acceptEula) throw codedError('tick-load-requires-start-server-and-accept-eula');
  assertEulaAuthorization(result);
  if (result.peer !== 'fixture' && result.peer !== 'openclaw') throw codedError('peer-mode-invalid');
  if (result.peer === 'openclaw') throw codedError('openclaw-peer-requires-separate-provider-authorization');
  result.repositoryRoot = repositoryRoot;
  result.distribution = resolve(repositoryRoot, result.distribution);
  result.harness = resolve(repositoryRoot, result.harness);
  return result;
}

export function validateLoopbackEndpoint(value) {
  let parsed;
  try { parsed = new URL(value); } catch { throw codedError('peer-endpoint-invalid'); }
  if (parsed.protocol !== 'ws:' || parsed.hostname !== '127.0.0.1' || parsed.username || parsed.password ||
      parsed.pathname !== '/' || parsed.search || parsed.hash || !parsed.port ||
      Number(parsed.port) < 1024 || Number(parsed.port) > 65535) {
    throw codedError('peer-endpoint-not-loopback');
  }
  return parsed;
}

export async function readAuthorizedPlayers(playersPath) {
  if (!isAbsolutePath(playersPath)) throw codedError('players-file-must-be-absolute');
  let info;
  try { info = await lstat(playersPath); } catch { throw codedError('players-file-unavailable'); }
  if (!info.isFile() || info.isSymbolicLink() || info.size < 1 || info.size > MAX_PLAYERS_FILE_BYTES ||
      (info.mode & 0o077) !== 0) throw codedError('players-file-permissions-or-size-invalid');
  const text = await readFile(playersPath, 'utf8').catch(() => { throw codedError('players-file-unreadable'); });
  const players = [];
  for (const line of text.split(/\r?\n/u)) {
    const value = line.trim();
    if (!value || value.startsWith('#')) continue;
    const fields = value.split(/\s+/u);
    if (fields.length !== 2 || !UUID.test(fields[0]) || !/^[A-Za-z0-9_]{3,16}$/u.test(fields[1])) {
      throw codedError('players-file-record-invalid');
    }
    const id = fields[0].toLowerCase();
    if (players.some((player) => player.id === id || player.name.toLowerCase() === fields[1].toLowerCase())) {
      throw codedError('players-file-duplicate-identity');
    }
    players.push(Object.freeze({ id, name: fields[1] }));
    if (players.length > 8) throw codedError('players-file-player-limit-exceeded');
  }
  if (players.length === 0) throw codedError('players-file-empty');
  return Object.freeze(players);
}

export async function computeHarnessSourcesSha256(sourceRoot = HARNESS_SOURCE_ROOT) {
  const files = await walkFiles(sourceRoot);
  if (files.length === 0 || files.length > 128) throw codedError('harness-source-count-invalid');
  const digest = createHash('sha256');
  let totalBytes = 0;
  for (const path of files.sort((left, right) => {
    const leftName = relative(sourceRoot, left).split(sep).join('/');
    const rightName = relative(sourceRoot, right).split(sep).join('/');
    return leftName < rightName ? -1 : leftName > rightName ? 1 : 0;
  })) {
    const name = relative(resolve(sourceRoot, '../..'), path).split(sep).join('/');
    const bytes = await readFile(path);
    totalBytes += bytes.byteLength;
    if (totalBytes > 4 * 1024 * 1024) throw codedError('harness-source-size-limit');
    digest.update(name, 'utf8');
    digest.update(Buffer.from([0]));
    digest.update(bytes);
    digest.update(Buffer.from([0]));
  }
  return digest.digest('hex');
}

export function validateHarnessMetadata(metadata, { deployment, deploymentSha256, sourceSha256 }) {
  if (!metadata || metadata.schemaVersion !== 1 || metadata.modId !== 'worldspeaker_compat_load_harness' ||
      metadata.deploymentId !== deployment.deploymentId || metadata.deploymentManifestSha256 !== deploymentSha256 ||
      metadata.harnessSourcesSha256 !== sourceSha256 ||
      metadata.entrypoint !== 'org.worldspeaker.load.WorldspeakerTickLoadHarness') {
    throw codedError('load-harness-stale-or-manifest-mismatch');
  }
  return true;
}

export async function verifyHarnessArtifact(harnessPath, { deployment, deploymentSha256, sourceSha256 }) {
  const info = await lstat(harnessPath).catch(() => null);
  if (!info?.isFile() || info.isSymbolicLink() || info.size < 22 || info.size > 32 * 1024 * 1024) {
    throw codedError('load-harness-artifact-size-invalid');
  }
  let entries;
  try { entries = readZipEntries(await readFile(harnessPath)); } catch { throw codedError('load-harness-archive-invalid'); }
  for (const required of ['fabric.mod.json', 'worldspeaker-load-harness.json']) {
    if (!entries.has(required)) throw codedError('load-harness-required-entry-missing');
  }
  let fabric;
  let metadata;
  try {
    fabric = JSON.parse(entries.get('fabric.mod.json').toString('utf8'));
    metadata = JSON.parse(entries.get('worldspeaker-load-harness.json').toString('utf8'));
  } catch { throw codedError('load-harness-metadata-invalid'); }
  validateHarnessMetadata(metadata, { deployment, deploymentSha256, sourceSha256 });
  if (fabric.schemaVersion !== 1 || fabric.id !== 'worldspeaker_compat_load_harness' || fabric.environment !== 'server' ||
      fabric.entrypoints?.main?.length !== 1 || fabric.entrypoints.main[0] !== metadata.entrypoint ||
      fabric.depends?.minecraft !== deployment.target.minecraft ||
      fabric.depends?.fabricloader !== deployment.runtimeDependencies.fabricLoader ||
      fabric.depends?.['fabric-api'] !== deployment.runtimeDependencies.fabricApi ||
      fabric.depends?.worldspeaker !== deployment.mod.version) throw codedError('load-harness-fabric-metadata-mismatch');
  const classes = [...entries.keys()].filter((name) => name.endsWith('.class'));
  if (!classes.some((name) => name === 'org/worldspeaker/load/WorldspeakerTickLoadHarness.class') ||
      classes.some((name) => name.startsWith('org/worldspeaker/WorldspeakerServer') ||
        name.startsWith('org/worldspeaker/runtime/') || name.startsWith('net/minecraft/') ||
        name.startsWith('net/fabricmc/'))) throw codedError('load-harness-not-isolated');
  return {
    bytes: info.size,
    sha256: await sha256File(harnessPath),
    sourceSha256,
    entries: entries.size,
    classes: classes.length,
  };
}

export async function preflightTickLoad(options, dependencies = {}) {
  const repositoryRoot = resolve(dependencies.repositoryRoot ?? ROOT);
  const deploymentPath = join(repositoryRoot, 'compatibility/deployment.json');
  const deploymentBytes = await readFile(deploymentPath).catch(() => { throw codedError('deployment-manifest-unavailable'); });
  const canonicalBytes = await readFile(DEPLOYMENT_PATH);
  if (!deploymentBytes.equals(canonicalBytes)) throw codedError('deployment-manifest-not-canonical');
  let deployment;
  try { deployment = JSON.parse(deploymentBytes.toString('utf8')); } catch { throw codedError('deployment-manifest-invalid'); }
  try { validateManifestPins(deployment); } catch { throw codedError('deployment-pins-invalid'); }
  const deploymentSha256 = createHash('sha256').update(deploymentBytes).digest('hex');
  const sourceSha256 = await computeHarnessSourcesSha256(join(repositoryRoot, 'src/loadTest'));
  const harness = await verifyHarnessArtifact(options.harness, { deployment, deploymentSha256, sourceSha256 });
  const candidate = await (dependencies.verifyCandidateDistribution ?? verifyCandidateDistributionWrapper)(options.distribution, deployment, deploymentSha256);
  let productionJar;
  try { productionJar = verifyJar(candidate.modPath); } catch { throw codedError('candidate-production-jar-rejected'); }
  const players = await readAuthorizedPlayers(options.playersFile);
  const runDirectory = await ensureNewWorkdirOutsideRepo(resolve(options.workdir), repositoryRoot);
  return {
    deployment,
    deploymentSha256,
    sourceSha256,
    harness,
    candidate,
    productionJar,
    players,
    runDirectory,
  };
}

async function verifyCandidateDistributionWrapper(distribution, deployment, deploymentSha256) {
  const { verifyCandidateDistribution } = await import('./clean-install-core.mjs');
  return verifyCandidateDistribution({ distribution, manifest: deployment, manifestSha256: deploymentSha256 });
}

export async function runTickLoad(options, dependencies = {}) {
  assertEulaAuthorization(options);
  if (options.startServer !== true || options.acceptEula !== true) throw codedError('tick-load-requires-start-server-and-accept-eula');
  if (options.peer !== 'fixture') throw codedError('openclaw-peer-requires-separate-provider-authorization');
  const preflight = await preflightTickLoad(options, dependencies);
  const workdir = preflight.runDirectory;
  await mkdir(workdir, { recursive: false, mode: 0o700 });
  await chmod(workdir, 0o700);
  const directories = {
    downloads: join(workdir, 'downloads'),
    runs: join(workdir, 'runs'),
    home: join(workdir, 'private-home'),
    state: join(workdir, 'private-home', '.openclaw'),
    workspace: join(workdir, 'private-home', '.openclaw', 'workspace'),
  };
  await Promise.all(Object.values(directories).map((path) => mkdir(path, { recursive: true, mode: 0o700 })));
  const authorizedPlayersPath = join(workdir, 'authorized-players-private.txt');
  await writeFile(authorizedPlayersPath,
    `${preflight.players.map((player) => `${player.id} ${player.name}`).join('\n')}\n`,
    { encoding: 'utf8', mode: 0o600, flag: 'wx' });
  await chmod(authorizedPlayersPath, 0o600);
  const environment = buildIsolatedEnvironment({
    openclawHome: directories.home,
    openclawState: directories.state,
    openclaw: join(workdir, 'openclaw-unused'),
    node: options.node,
    npm: options.npm,
  });
  await writeFile(environment.npm_config_userconfig, '', { encoding: 'utf8', mode: 0o600, flag: 'wx' });

  const record = makeRecord(preflight);
  record.startedAtUtc = new Date().toISOString();
  const recordPath = join(workdir, 'run-record.json');
  await saveRecord(recordPath, record);
  let downloads;
  try {
    record.phases.runtime = phase('running', 'pinned-runtime-preflight');
    await saveRecord(recordPath, record);
    const runtime = await (dependencies.inspectRuntime ?? inspectRuntime)({
      manifest: preflight.deployment,
      java: options.java,
      node: options.node,
      npm: options.npm,
      environment,
      dependencies,
    });
    record.runtime = redactRuntime(runtime.facts);
    record.phases.runtime = phase(runtime.matches ? 'passed' : 'failed', runtime.matches ? 'pinned-runtime' : 'runtime-mismatch');
    await saveRecord(recordPath, record);
    if (!runtime.matches) throw codedError('pinned-runtime-mismatch');
    record.phases.downloads = phase('running', 'pinned-fabric-inputs');
    await saveRecord(recordPath, record);
    downloads = await (dependencies.downloadFabricInputs ?? downloadFabricInputs)({
      manifest: preflight.deployment,
      output: directories.downloads,
      dependencies,
    });
    record.artifacts.push(...downloads.artifacts.map(({ name, bytes, sha256 }) => ({ name, bytes, sha256 })));
    record.phases.downloads = phase('passed', 'pinned-fabric-inputs-verified');
    await saveRecord(recordPath, record);

    const totalStarted = performance.now();
    record.phases.load = phase('running', 'three-fixture-runs');
    await saveRecord(recordPath, record);
    for (let runIndex = 1; runIndex <= RUNS_PER_INVOCATION; runIndex += 1) {
      const run = await executeRun({
        runIndex,
        preflight,
        downloads,
        directories,
        workdir,
        authorizedPlayersPath,
        environment,
        options,
        record,
        recordPath,
        totalStarted,
        dependencies,
      });
      if (!run.complete) record.evidenceComplete = false;
      await saveRecord(recordPath, record);
      if (!run.complete || activeAbortController?.signal.aborted) break;
    }
    record.phases.load = phase(record.evidenceComplete ? 'passed' : 'incomplete',
      record.evidenceComplete ? 'three-fixture-runs-captured' : 'one-or-more-fixture-runs-incomplete');
    record.finishedAtUtc = new Date().toISOString();
    await saveRecord(recordPath, record);
    return { ok: record.evidenceComplete, recordPath, record };
  } catch (error) {
    record.evidenceComplete = false;
    record.failure = { code: safeCode(error?.code), phase: nextPendingPhase(record) };
    const activeRun = record.runs.at(-1);
    if (activeRun?.state === 'running') {
      activeRun.state = 'incomplete';
      activeRun.complete = false;
      activeRun.failure = record.failure.code;
    }
    record.phases[record.failure.phase] = phase('failed', record.failure.code);
    record.finishedAtUtc = new Date().toISOString();
    await saveRecord(recordPath, record).catch(() => {});
    return { ok: false, recordPath, record };
  }
}

async function executeRun(context) {
  const {
    runIndex, preflight, downloads, directories, workdir, authorizedPlayersPath, environment, options,
    record, recordPath, totalStarted, dependencies,
  } = context;
  const now = dependencies.now ?? (() => performance.now());
  const runStarted = now();
  const runId = randomUUID();
  const runDirectory = join(directories.runs, `run-${runIndex}`);
  await mkdir(runDirectory, { recursive: false, mode: 0o700 });
  await chmod(runDirectory, 0o700);
  const runRecord = {
    run: runIndex,
    runId,
    state: 'running',
    epochCount: 0,
    maxDurationMs: RUN_LIMIT_MS,
    maxAuthorizedPlayers: preflight.players.length,
    eightPlayerCapacityCaseComplete: false,
    overloadWorkloadComplete: false,
    capacityHighWaterComplete: false,
    overloadContract: {
      expectedAttemptedPerSecond: OVERLOAD_INVOCATIONS_PER_SECOND,
      expectedDistinctPerSecond: OVERLOAD_INVOCATIONS_PER_SECOND,
      measuredDurationMs: MEASURED_PHASE_MS,
      expectedAttemptedInvocations: EXPECTED_OVERLOAD_ATTEMPTS,
      expectedDistinctInvocations: EXPECTED_OVERLOAD_ATTEMPTS,
      activeCapacity: ACTIVE_REQUEST_CAPACITY,
      globalPendingCapacity: GLOBAL_PENDING_CAPACITY,
      perPlayerPendingCapacity: PER_PLAYER_PENDING_CAPACITY,
      authorizedPlayersRequired: CAPACITY_PLAYER_COUNT,
    },
    connectionLifecycleCaseComplete: false,
    playerIdentityValuesRecorded: false,
    providerCalls: 0,
    jfr: 'sanitized-per-epoch-max-16MiB',
    jvmArgs: ['-Xms512M', '-Xmx2G'],
    epochs: [],
  };
  record.runs.push(runRecord);
  await saveRecord(recordPath, record);
  let server = null;
  let peer = null;
  let captureFailure = null;
  let currentEpoch = null;
  try {
    const secret = randomBytes(32).toString('base64url');
    const binding = {
      serverId: `compat03-${runId.slice(0, 12)}`,
      worldId: `world-${runId.slice(0, 12)}`,
      characterId: `character-${runId.slice(0, 12)}`,
      agentId: 'main',
    };
    const companionPort = await getFreeLoopbackPort();
    const serverPort = await getFreeLoopbackPort([companionPort]);
    const serverDirectory = join(runDirectory, 'server');
    await mkdir(serverDirectory, { recursive: false, mode: 0o700 });
    await chmod(serverDirectory, 0o700);
    const dirs = { server: serverDirectory };
    await (dependencies.installFabric ?? installFabric)({
      manifest: preflight.deployment,
      downloads,
      candidate: preflight.candidate,
      dirs,
      java: options.java,
      environment,
      dependencies,
    });
    await mkdir(join(serverDirectory, 'config'), { recursive: true, mode: 0o700 });
    const harnessDestination = join(serverDirectory, 'mods', `${preflight.deployment.mod.id}-${preflight.deployment.mod.version}-compat-load-harness.jar`);
    await copyFile(options.harness, harnessDestination);
    await chmod(harnessDestination, 0o600);
    if (await sha256File(harnessDestination) !== preflight.harness.sha256) throw codedError('copied-load-harness-hash-mismatch');
    await writeWorldspeakerConfig(join(serverDirectory, 'config'), preflight.deployment, secret, companionPort, binding);
    await writeServerProperties(serverDirectory, serverPort);
    await writeWhitelist(serverDirectory, preflight.players);
    await assertLoopbackProfile(serverDirectory, companionPort, serverPort);

    const peerConfigPath = join(runDirectory, 'fixture-peer-private.json');
    const peerConfig = {
      schemaVersion: 1,
      runId,
      port: companionPort,
      secret,
      manifestSha256: preflight.deploymentSha256,
      binding,
      authorizedPlayerIds: preflight.players.map((player) => player.id),
    };
    await writePrivateJson(peerConfigPath, peerConfig);
    // Do not retain the secret in report objects or log streams.
    for (const key of Object.keys(peerConfig)) if (key === 'secret') peerConfig[key] = '';
    const controlPath = join(runDirectory, 'control.json');
    const epochNumber = 1;
    let epoch = await startEpoch({
      epochNumber,
      runId: randomUUID(),
      runDirectory,
      serverDirectory,
      peerConfigPath,
      controlPath,
      companionPort,
      serverPort,
      players: preflight.players,
      authorizedPlayersPath,
      options,
      environment,
      record,
      totalStarted,
      runStarted,
      dependencies,
    });
    peer = epoch.peer;
    server = epoch.server;
    currentEpoch = epoch;
    runRecord.epochs.push(epoch.record);

    await setPhaseAndWait(epoch, 'idle', true, WARMUP_MS, runStarted, totalStarted, dependencies);
    await saveRecord(recordPath, record);
    await setPhaseAndWait(epoch, 'idle', false, MEASURED_PHASE_MS, runStarted, totalStarted, dependencies);
    await saveRecord(recordPath, record);
    await setPhaseAndWait(epoch, 'normal', false, MEASURED_PHASE_MS, runStarted, totalStarted, dependencies);
    await saveRecord(recordPath, record);
    await setPhaseAndWait(epoch, 'overload', false, MEASURED_PHASE_MS, runStarted, totalStarted, dependencies);
    await saveRecord(recordPath, record);
    const outageEpoch = epoch;
    epoch = await executeUnavailableRecovery({
      epoch: outageEpoch,
      runStarted,
      totalStarted,
      runRecord,
      record,
      recordPath,
      dependencies,
      startRecoveryEpoch: async (recoveryContext) => {
        assertRunTime(recoveryContext.runStarted, dependencies);
        return startEpoch({
          epochNumber: 2,
          runId: randomUUID(),
          runDirectory,
          serverDirectory,
          peerConfigPath,
          controlPath,
          companionPort,
          serverPort,
          players: preflight.players,
          authorizedPlayersPath,
          options,
          environment,
          record,
          totalStarted: recoveryContext.totalStarted,
          runStarted: recoveryContext.runStarted,
          dependencies,
        });
      },
      onOutageStopped() {
        peer = null;
        server = null;
        currentEpoch = null;
      },
      onEpochStarted(nextEpoch) {
        epoch = nextEpoch;
        peer = nextEpoch.peer;
        server = nextEpoch.server;
        currentEpoch = nextEpoch;
      },
      onRecoveryStopped() {
        peer = null;
        server = null;
        currentEpoch = null;
      },
    });
    const measuredPhases = epoch.record.phaseSummaries ?? [];
    const repeatSummary = measuredPhases.find((phaseSummary) => phaseSummary.name === 'repeat');
    if (!repeatSummary || repeatSummary.repeatAttempts !== 7 || repeatSummary.repeatRejected < 1 ||
        repeatSummary.distinctOtherRequests < 1) runRecord.complete = false;
    const firstEpoch = runRecord.epochs.find((item) => item.epochNumber === 1);
    runRecord.connectionLifecycleCaseComplete = ((firstEpoch?.connectionLeaves ?? 0) + epoch.record.connectionLeaves) > 0 &&
      epoch.record.authorizedRejoins > 0;
    runRecord.epochCount = 2;
    runRecord.durationMs = Math.round(now() - runStarted);
    runRecord.complete = runRecord.complete !== false && runRecord.eightPlayerCapacityCaseComplete &&
      runRecord.overloadWorkloadComplete && runRecord.capacityHighWaterComplete &&
      runRecord.connectionLifecycleCaseComplete &&
      runRecord.epochs.every((item) => item.evidenceComplete === true);
    runRecord.state = runRecord.complete ? 'complete' : 'incomplete';
    runRecord.connectionLifecycleEvidence = runRecord.epochs.map((item) => ({
      epoch: item.epochNumber,
      joins: item.connectionJoins,
      leaves: item.connectionLeaves,
    }));
    return runRecord;
  } catch (error) {
    captureFailure = safeCode(error?.code);
    runRecord.state = 'incomplete';
    runRecord.failure = captureFailure;
    runRecord.durationMs = Math.round(now() - runStarted);
    return runRecord;
  } finally {
    const cleanupFailures = [];
    if (server) {
      try { await stopServer(server); } catch { cleanupFailures.push('minecraft-stop-timeout'); }
    }
    if (peer) {
      try { await stopPeer(peer); } catch { cleanupFailures.push('fixture-peer-stop-timeout'); }
    }
    if (cleanupFailures.length > 0) {
      runRecord.cleanupFailures = cleanupFailures;
      runRecord.complete = false;
      record.evidenceComplete = false;
    }
    if (currentEpoch && currentEpoch.record.evidenceComplete !== true) {
      await collectEpochEvidence(currentEpoch.record, currentEpoch.paths).catch(() => {});
      currentEpoch.record.state = currentEpoch.record.evidenceComplete ? 'complete' : 'incomplete';
    }
    if (captureFailure) record.evidenceComplete = false;
    const writtenEvidenceBytes = await measureRunEvidence(runDirectory).catch(() => Number.MAX_SAFE_INTEGER);
    runRecord.evidenceBytes = writtenEvidenceBytes;
    if (writtenEvidenceBytes > MAX_EVIDENCE_BYTES_PER_RUN) {
      runRecord.complete = false;
      runRecord.failure ??= 'evidence-size-limit-exceeded';
      record.evidenceComplete = false;
    }
    runRecord.durationMs ??= Math.round(now() - runStarted);
    runRecord.epochCount = runRecord.epochs.length;
    if (runRecord.durationMs > RUN_LIMIT_MS) {
      runRecord.complete = false;
      runRecord.failure ??= 'run-time-limit-exceeded';
      record.evidenceComplete = false;
    }
    if (runRecord.complete !== true) runRecord.state = 'incomplete';
  }
}

export async function executeUnavailableRecovery(context) {
  const {
    epoch: outageEpoch, runStarted, totalStarted, runRecord, record, recordPath,
    startRecoveryEpoch, onOutageStopped = () => {}, onEpochStarted = () => {},
    onRecoveryStopped = () => {}, dependencies = {},
  } = context;
  if (typeof startRecoveryEpoch !== 'function') throw codedError('recovery-epoch-starter-required');

  const write = dependencies.writeControl ?? writeControl;
  const save = dependencies.saveRecord ?? saveRecord;
  const stopPeerProcess = dependencies.stopPeer ?? stopPeer;
  const stopServerProcess = dependencies.stopServer ?? stopServer;
  const collect = dependencies.collectEpochEvidence ?? collectEpochEvidence;
  const setPhase = dependencies.setPhaseAndWait ?? setPhaseAndWait;
  const readStatus = dependencies.readHarnessStatus ?? readHarnessStatus;

  await write(outageEpoch.controlPath, outageEpoch.record.epochId, 'unavailable', false);
  await waitForFixturePhase(outageEpoch, 'unavailable', runStarted, dependencies);
  await delayAndCheck(MEASURED_PHASE_MS, {
    server: outageEpoch.server, peer: outageEpoch.peer, runStarted,
  }, dependencies);
  outageEpoch.record.phases.push({ name: 'unavailable', durationMs: MEASURED_PHASE_MS, peerRunning: false });
  await save(recordPath, record);
  await stopPeerProcess(outageEpoch.peer);
  await stopServerProcess(outageEpoch.server);
  outageEpoch.record.serverStoppedForRestart = true;
  await collect(outageEpoch.record, outageEpoch.record.paths);
  outageEpoch.record.state = outageEpoch.record.evidenceComplete ? 'complete' : 'incomplete';
  onOutageStopped(outageEpoch);

  const overloadSummary = outageEpoch.record.phaseSummaries?.find((item) => item.name === 'overload');
  runRecord.overloadWorkloadComplete = overloadSummary?.attempts >= EXPECTED_OVERLOAD_ATTEMPTS &&
    overloadSummary?.distinctInvocations >= EXPECTED_OVERLOAD_ATTEMPTS;
  runRecord.capacityHighWaterComplete = outageEpoch.record.capacityEvidence?.observedHighWater?.active === ACTIVE_REQUEST_CAPACITY &&
    outageEpoch.record.capacityEvidence?.observedHighWater?.globalPending === GLOBAL_PENDING_CAPACITY &&
    outageEpoch.record.capacityEvidence?.observedHighWater?.perPlayerPending === PER_PLAYER_PENDING_CAPACITY;
  runRecord.eightPlayerCapacityCaseComplete = runRecord.overloadWorkloadComplete && runRecord.capacityHighWaterComplete &&
    outageEpoch.record.capacityEvidence?.observedHighWater?.authorizedOnline >= CAPACITY_PLAYER_COUNT;
  await save(recordPath, record);

  assertRunTime(runStarted, dependencies);
  const recoveryEpoch = await startRecoveryEpoch({ runStarted, totalStarted });
  runRecord.epochs.push(recoveryEpoch.record);
  onEpochStarted(recoveryEpoch);
  await setPhase(recoveryEpoch, 'recovery', true, WARMUP_MS, runStarted, totalStarted, dependencies);
  await save(recordPath, record);
  await setPhase(recoveryEpoch, 'recovery', false, MEASURED_PHASE_MS, runStarted, totalStarted, dependencies);
  await save(recordPath, record);
  await setPhase(recoveryEpoch, 'repeat', false, REPEAT_PHASE_MS, runStarted, totalStarted, dependencies);
  await save(recordPath, record);
  await setPhase(recoveryEpoch, 'stop-backlog', false, BACKLOG_PHASE_MS, runStarted, totalStarted, dependencies);
  await save(recordPath, record);

  const finalStatus = await readStatus(recoveryEpoch.record.paths.statusPath);
  recoveryEpoch.record.onlineAuthorizedHighWater = finalStatus?.onlineAuthorizedHighWater ?? 0;
  recoveryEpoch.record.authorizedListLoaded = finalStatus?.authorizedListLoaded === true;
  recoveryEpoch.record.integrationReadyObserved = finalStatus?.integrationReady === true;
  await stopServerProcess(recoveryEpoch.server);
  await stopPeerProcess(recoveryEpoch.peer);
  await collect(recoveryEpoch.record, recoveryEpoch.paths);
  recoveryEpoch.record.state = recoveryEpoch.record.evidenceComplete ? 'complete' : 'incomplete';
  onRecoveryStopped(recoveryEpoch);
  return recoveryEpoch;
}

export async function startEpoch(context) {
  const {
    epochNumber, runId, runDirectory, serverDirectory, peerConfigPath,
    controlPath, companionPort, serverPort, players, authorizedPlayersPath, options, environment, record,
    totalStarted, runStarted, dependencies,
  } = context;
  assertRunTime(runStarted, dependencies);
  const epochDirectory = join(runDirectory, `epoch-${epochNumber}`);
  await mkdir(epochDirectory, { recursive: false, mode: 0o700 });
  await chmod(epochDirectory, 0o700);
  const paths = {
    samplesPath: join(epochDirectory, 'tick-samples.csv'),
    summaryPath: join(epochDirectory, 'tick-summary.json'),
    statusPath: join(epochDirectory, 'harness-status.json'),
    peerStatusPath: join(epochDirectory, 'fixture-peer-status.json'),
    controlPath,
    jfrPath: join(epochDirectory, 'recording-private.jfr'),
    jfrSanitizedPath: join(epochDirectory, 'thread-io-trace-redacted.jsonl'),
  };
  const epochPeerStatusPath = paths.peerStatusPath;
  let peer = null;
  let server = null;
  try {
    await writeControl(controlPath, runId, 'idle', true);
    await writePeerRunId(peerConfigPath, runId);
    peer = await (dependencies.startPeerProcess ?? startPeerProcess)({
      node: options.node,
      configPath: peerConfigPath,
      controlPath,
      statusPath: epochPeerStatusPath,
      cwd: ROOT,
      spawnImpl: dependencies.spawn,
    });
    await (dependencies.waitForPeerReady ?? waitForPeerReady)(peer, epochPeerStatusPath, PEER_START_MS, runStarted, totalStarted);
    await (dependencies.ensureEula ?? ensureExplicitEula)(join(serverDirectory, 'eula.txt'));
    record.eulaFileWritten = true;
    const args = [
      '-Xms512M', '-Xmx2G',
      `-XX:StartFlightRecording=filename=${paths.jfrPath},settings=profile,dumponexit=true,maxsize=16m`,
      `-Dworldspeaker.load.players=${authorizedPlayersPath}`,
      `-Dworldspeaker.load.control=${controlPath}`,
      `-Dworldspeaker.load.samples=${paths.samplesPath}`,
      `-Dworldspeaker.load.summary=${paths.summaryPath}`,
      `-Dworldspeaker.load.status=${paths.statusPath}`,
      '-jar', 'fabric-server-launch.jar', 'nogui',
    ];
    server = (dependencies.startServerProcess ?? startManagedProcess)(options.java, args, {
      cwd: serverDirectory,
      env: environment,
      spawnImpl: dependencies.spawn,
    });
    await waitForServerStartup(server, runStarted, dependencies);
    if (!await (dependencies.isLoopbackPortOpen ?? isLoopbackPortOpen)(serverPort)) {
      throw codedError('minecraft-loopback-listener-not-ready');
    }
    const readyStatus = await (dependencies.waitForIntegrationReady ?? waitForIntegrationReady)(
      paths.statusPath, server, INTEGRATION_READY_MS, runStarted, totalStarted,
    );
    (dependencies.writeOutput ?? ((message) => process.stdout.write(message)))(
      `WS-TICK-LOAD-PLAYER-READY run=${record.runs.at(-1)?.run ?? 0} epoch=${epochNumber} target=127.0.0.1:${serverPort}\n`,
    );
    const epochRecord = {
      epochNumber,
      epochId: runId,
      state: 'running',
      phases: [],
      serverStarted: true,
      serverBindScope: '127.0.0.1',
      peerBindScope: '127.0.0.1',
      authorizedCount: players.length,
      authorizedListLoaded: readyStatus.authorizedListLoaded,
      integrationReadyObserved: readyStatus.integrationReady,
      onlineAuthorizedHighWater: 0,
      connectionJoins: 0,
      connectionLeaves: 0,
      eulaAuthorized: true,
      providerCalls: 0,
      jfr: { state: 'pending-sanitize', maxBytesPerEpoch: 16 * 1024 * 1024 },
      jfrComplete: false,
      expectedPhases: epochNumber === 1
        ? ['idle', 'normal', 'overload', 'unavailable']
        : ['recovery', 'repeat', 'stop-backlog'],
    };
    Object.defineProperty(epochRecord, 'paths', { value: paths, enumerable: false });
    Object.defineProperty(epochRecord, 'jfrExecutable', { value: jfrExecutable(options.java), enumerable: false });
    record.currentRunEpochStartedAtUtc = new Date().toISOString();
    return { peer, server, paths, controlPath, record: epochRecord, statusPath: epochPeerStatusPath, serverPort, companionPort };
  } catch (error) {
    let cleanupFailure = null;
    if (server) {
      try { await (dependencies.stopServer ?? stopServer)(server); } catch { cleanupFailure = 'minecraft-stop-timeout'; }
    }
    if (peer) {
      try { await (dependencies.stopPeer ?? stopPeer)(peer); } catch { cleanupFailure ??= 'fixture-peer-stop-timeout'; }
    }
    const { unlink } = await import('node:fs/promises');
    await unlink(paths.jfrPath).catch(() => {});
    if (cleanupFailure) throw codedError(cleanupFailure);
    throw error;
  }
}

async function setPhaseAndWait(epoch, phaseName, warmup, durationMs, runStarted, totalStarted, dependencies = {}) {
  if (!PHASE_NAMES.has(phaseName)) throw codedError('phase-name-invalid');
  await (dependencies.writeControl ?? writeControl)(epoch.controlPath, epoch.record.epochId, phaseName, warmup);
  await waitForFixturePhase(epoch, phaseName, runStarted, dependencies);
  await delayAndCheck(durationMs, { server: epoch.server, peer: epoch.peer, runStarted }, dependencies);
  const status = await (dependencies.readHarnessStatus ?? readHarnessStatus)(epoch.paths.statusPath);
  if (status) {
    epoch.record.authorizedListLoaded = status.authorizedListLoaded === true;
    epoch.record.integrationReadyObserved ||= status.integrationReady === true;
    epoch.record.onlineAuthorizedHighWater = Math.max(epoch.record.onlineAuthorizedHighWater, status.onlineAuthorizedHighWater ?? 0);
  }
  epoch.record.phases.push({
    name: phaseName,
    warmup,
    durationMs,
    integrationReadyAtEnd: status?.integrationReady === true,
    onlineAuthorizedAtEnd: status?.onlineAuthorized ?? 0,
    authorizedCount: status?.authorizedCount ?? 0,
  });
  if (phaseName !== 'idle' && !warmup && !status) throw codedError('harness-status-missing');
}

async function writeControl(path, runId, phaseName, warmup) {
  const body = JSON.stringify({ schemaVersion: 1, runId, phase: phaseName, warmup }) + '\n';
  await atomicWrite(path, body, 0o600);
}

async function writePeerRunId(configPath, runId) {
  const info = await lstat(configPath);
  if (!info.isFile() || info.isSymbolicLink() || (info.mode & 0o077) !== 0 || info.size > 4096) {
    throw codedError('fixture-peer-config-invalid');
  }
  const config = JSON.parse(await readFile(configPath, 'utf8'));
  config.runId = runId;
  await writePrivateJson(configPath, config, true);
}

async function startPeerProcess({ node, configPath, controlPath, statusPath, cwd, spawnImpl = spawn }) {
  const child = spawnImpl(node, [FIXTURE_PEER_PATH, '--config', configPath, '--control', controlPath, '--status', statusPath], {
    cwd,
    env: process.env.PATH ? { PATH: process.env.PATH } : {},
    stdio: 'ignore',
    windowsHide: true,
  });
  child.exitCode = null;
  child.signalCode = null;
  child.once('error', () => { child.exitCode = -1; });
  child.closePromise = new Promise((resolveClose) => child.once('close', (code, signal) => {
    child.exitCode = code;
    child.signalCode = signal;
    resolveClose({ code, signal });
  }));
  return child;
}

async function waitForPeerReady(peer, statusPath, timeoutMs, runStarted, totalStarted) {
  const deadline = Math.min(performance.now() + timeoutMs, runStarted + RUN_LIMIT_MS);
  while (performance.now() < deadline) {
    throwIfAborted();
    if (peer.exitCode !== null) throw codedError('fixture-peer-exited-before-ready');
    const status = await readJsonMaybe(statusPath);
    if (status?.listening === true && status.event === 'listening') return status;
    await delay(100);
  }
  throw codedError('fixture-peer-startup-timeout');
}

async function waitForIntegrationReady(statusPath, server, timeoutMs, runStarted, totalStarted) {
  const deadline = Math.min(performance.now() + timeoutMs, runStarted + RUN_LIMIT_MS);
  let latest = null;
  while (performance.now() < deadline) {
    throwIfAborted();
    if (server.exitCode !== null) throw codedError('minecraft-exited-before-integration-ready');
    latest = await readHarnessStatus(statusPath);
    if (latest?.authorizedListLoaded === true && latest.integrationReady === true) return latest;
    await delay(100);
  }
  if (!latest) throw codedError('harness-status-not-created');
  if (!latest.authorizedListLoaded) throw codedError('authorized-player-list-not-loaded');
  throw codedError('companion-integration-not-ready');
}

export async function waitForServerMarker(server, marker, timeoutMs, runStarted, dependencies = {}) {
  if (!server || typeof server.output !== 'string' || typeof marker !== 'string' || marker.length === 0) {
    throw codedError('minecraft-process-invalid');
  }
  if (!Number.isFinite(timeoutMs) || timeoutMs < 0) throw codedError('minecraft-startup-timeout-invalid');
  const runLimitMs = dependencies.runLimitMs ?? RUN_LIMIT_MS;
  if (!Number.isFinite(runLimitMs) || runLimitMs <= 0) throw codedError('run-time-limit-invalid');
  const now = dependencies.now ?? (() => performance.now());
  const wait = dependencies.delay ?? delay;
  const checkAbort = dependencies.throwIfAborted ?? throwIfAborted;
  const pollIntervalMs = dependencies.pollIntervalMs ?? 100;
  if (typeof now !== 'function' || typeof wait !== 'function' || typeof checkAbort !== 'function' ||
      !Number.isFinite(pollIntervalMs) || pollIntervalMs <= 0) {
    throw codedError('minecraft-startup-dependencies-invalid');
  }

  let lastNow = readMonotonicTime(runStarted, now());
  const runDeadline = runStarted + runLimitMs;
  const startupDeadline = lastNow + timeoutMs;
  if (!Number.isFinite(runDeadline) || !Number.isFinite(startupDeadline)) {
    throw codedError('monotonic-time-origin-invalid');
  }
  const deadline = Math.min(startupDeadline, runDeadline);
  while (true) {
    checkAbort();
    const currentNow = readMonotonicTime(runStarted, now(), lastNow);
    lastNow = currentNow;
    if (server.exitCode !== null) return false;
    if (currentNow >= deadline) return false;
    if (server.output.includes(marker)) return true;
    await wait(Math.min(pollIntervalMs, deadline - currentNow));
  }
}

export async function waitForServerStartup(server, runStarted, dependencies = {}) {
  const now = dependencies.now ?? (() => performance.now());
  const startupTimeoutMs = dependencies.startupTimeoutMs ?? SERVER_START_MS;
  const runLimitMs = dependencies.runLimitMs ?? RUN_LIMIT_MS;
  let lastNow = Number.NEGATIVE_INFINITY;
  const readNow = () => {
    lastNow = readMonotonicTime(runStarted, now(), lastNow);
    return lastNow;
  };
  const ready = await waitForServerMarker(server, 'Done (', startupTimeoutMs, runStarted, {
    ...dependencies,
    now: readNow,
    runLimitMs,
  });
  if (server.exitCode !== null) throw codedError('minecraft-exited-before-ready');
  if (ready) return true;
  const currentNow = readNow();
  if (currentNow >= runStarted + runLimitMs) throw codedError('run-time-limit-exceeded');
  throw codedError('minecraft-startup-timeout');
}

async function readHarnessStatus(path) {
  const value = await readJsonMaybe(path);
  if (!value || value.schemaVersion !== 1 || typeof value.authorizedListLoaded !== 'boolean' ||
      typeof value.integrationReady !== 'boolean' || !Number.isInteger(value.onlineAuthorized) ||
      !Number.isInteger(value.authorizedCount)) return null;
  return value;
}

async function waitForFixturePhase(epoch, phaseName, runStarted, dependencies = {}) {
  const now = dependencies.now ?? (() => performance.now());
  const wait = dependencies.delay ?? delay;
  const checkAbort = dependencies.throwIfAborted ?? throwIfAborted;
  const readPeerStatus = dependencies.readPeerStatus ?? readJsonMaybe;
  const runLimitMs = dependencies.runLimitMs ?? RUN_LIMIT_MS;
  if (!Number.isFinite(runLimitMs) || runLimitMs <= 0) throw codedError('run-time-limit-invalid');
  const startedAt = readMonotonicTime(runStarted, now());
  let lastNow = startedAt;
  const runDeadline = runStarted + runLimitMs;
  const deadline = Math.min(startedAt + 5_000, runDeadline);
  while (true) {
    checkAbort();
    const currentNow = readMonotonicTime(runStarted, now(), lastNow);
    lastNow = currentNow;
    if (currentNow >= runDeadline) throw codedError('run-time-limit-exceeded');
    if (currentNow >= deadline) throw codedError('fixture-peer-phase-transition-timeout');
    if (epoch.peer.exitCode !== null) throw codedError('fixture-peer-exited-during-phase');
    const status = await readPeerStatus(epoch.statusPath);
    if (status?.schemaVersion === 1 && status.phase === phaseName && status.listening === true) return status;
    await wait(Math.min(50, deadline - currentNow));
  }
}

async function readJsonMaybe(path) {
  if (!path) return null;
  try {
    const info = await stat(path);
    if (!info.isFile() || info.size > 16 * 1024) return null;
    return JSON.parse(await readFile(path, 'utf8'));
  } catch { return null; }
}

async function stopPeer(peer) {
  if (!peer || peer.exitCode !== null) return;
  peer.kill('SIGTERM');
  const stopped = await Promise.race([peer.closePromise.then(() => true), delay(PEER_STOP_MS).then(() => false)]);
  if (stopped) return;
  peer.kill('SIGKILL');
  await Promise.race([peer.closePromise.then(() => true), delay(PEER_STOP_MS).then(() => false)]);
  if (peer.exitCode === null) throw codedError('fixture-peer-stop-timeout');
}

async function stopServer(server) {
  if (!server) return;
  const result = await stopManagedProcess(server, SERVER_STOP, true);
  if (!result.stopped) throw codedError('minecraft-stop-timeout');
}

async function delayAndCheck(durationMs, { server, peer, runStarted }, dependencies = {}) {
  if (!Number.isFinite(durationMs) || durationMs < 0) throw codedError('phase-duration-invalid');
  const now = dependencies.now ?? (() => performance.now());
  const wait = dependencies.delay ?? delay;
  const checkAbort = dependencies.throwIfAborted ?? throwIfAborted;
  let lastNow = readMonotonicTime(runStarted, now());
  const end = lastNow + durationMs;
  while (lastNow < end) {
    checkAbort();
    assertRunTime(runStarted, dependencies);
    if (server.exitCode !== null) throw codedError('minecraft-exited-during-phase');
    if (peer.exitCode !== null) throw codedError('fixture-peer-exited-during-phase');
    const remainingMs = remainingRunMs(runStarted, dependencies);
    if (remainingMs <= 0) throw codedError('run-time-limit-exceeded');
    await wait(Math.min(1000, end - lastNow, remainingMs));
    lastNow = readMonotonicTime(runStarted, now(), lastNow);
  }
  assertRunTime(runStarted, dependencies);
}

async function collectEpochEvidence(epoch, paths) {
  if (!paths) return;
  await finalizeJfr(epoch, paths);
  const summary = await readJsonMaybe(paths.summaryPath);
  const samples = await readFile(paths.samplesPath, 'utf8').catch(() => '');
  const peerStatus = await readJsonMaybe(paths.peerStatusPath);
  const evaluation = assessRecordedEpochEvidence({
    summary, samples, peerStatus, expectedPhases: epoch.expectedPhases ?? [],
  });
  const phaseSummaries = evaluation.phaseSummaries;
  epoch.tickHarness = {
    schemaVersion: summary?.schemaVersion ?? null,
    warmupTicks: summary?.warmupTicks ?? 0,
    warmupElapsedNanos: summary?.warmupElapsedNanos ?? 0,
    warmupSampleRecords: summary?.warmupSampleRecords ?? 0,
    sampleRecords: summary?.sampleRecords ?? 0,
    sampleQueueHighWater: summary?.sampleQueueHighWater ?? 0,
    evidenceTaskHighWater: summary?.evidenceTaskHighWater ?? 0,
    authorizedListLoaded: summary?.authorizedListLoaded === true,
    authorizedCount: summary?.authorizedPlayerCount ?? 0,
    onlineAuthorizedHighWater: summary?.onlineAuthorizedHighWater ?? 0,
    captureIncomplete: summary?.captureIncomplete !== false,
    workloadContract: summary?.workloadContract ?? null,
    tickHistogramUpperNanos: summary?.tickHistogramUpperNanos ?? [],
    firstPublicDispatchMetricsAvailable: summary?.firstPublicDispatchMetricsAvailable === true,
    firstPublicDispatchSourceNames: summary?.firstPublicDispatchSourceNames ?? [],
    firstPublicDispatchDispositionNames: summary?.firstPublicDispatchDispositionNames ?? [],
    firstPublicDispatchUpperNanos: summary?.firstPublicDispatchUpperNanos ?? [],
    firstPublicDispatchLatencySource: 'server-invocation-captured-monotonic-to-first-successful-public-player-dispatch',
    maxFirstPublicDispatchNanos: summary?.maxFirstPublicDispatchNanos ?? null,
    maxFirstPublicDispatchScope: 'maximum observed over the server metrics lifetime; not a histogram bucket ceiling',
    firstReplyDeadline: evaluation.firstReplyDeadline,
    integrationCounterNames: summary?.integrationCounterNames ?? [],
  };
  epoch.onlineAuthorizedHighWater = summary?.onlineAuthorizedHighWater ?? 0;
  epoch.connectionJoins = sumByIndex(summary?.phases ?? [], 'authorizedJoins');
  epoch.connectionLeaves = sumByIndex(summary?.phases ?? [], 'authorizedLeaves');
  epoch.authorizedRejoins = sumByIndex(summary?.phases ?? [], 'authorizedRejoins');
  epoch.phaseSummaries = phaseSummaries;
  epoch.sampledTickIntervals = summarizeIntervals(samples);
  const outagePhase = phaseSummaries.find((item) => item.name === 'unavailable');
  epoch.outageRetryExhaustionObserved = (outagePhase?.reconnectExhausted ?? 0) > 0;
  epoch.capacityEvidence = evaluation.capacityEvidence;
  epoch.fixturePeerEvidence = peerStatus ? {
    event: peerStatus.event,
    requestsByPhase: peerStatus.requestsByPhase,
    delayedRequestsByPhase: peerStatus.delayedRequestsByPhase,
    delayedPendingHighWaterByPhase: peerStatus.delayedPendingHighWaterByPhase,
  } : null;
  epoch.evidenceFailures = [...evaluation.failures];
  if (epoch.jfrComplete !== true) epoch.evidenceFailures.push('jfr-trace-missing-or-not-sanitized');
  epoch.evidenceComplete = evaluation.complete && epoch.jfrComplete === true;
  if (!epoch.evidenceComplete) epoch.evidenceFailure = epoch.evidenceFailures[0] ?? 'capture-incomplete';
  epoch.providerCalls = 0;
  epoch.playerIdentityValuesRecorded = false;
}

async function finalizeJfr(epoch, paths) {
  const { unlink } = await import('node:fs/promises');
  try {
    const info = await stat(paths.jfrPath);
    if (!info.isFile() || info.size < 1 || info.size > 16 * 1024 * 1024) throw codedError('jfr-recording-size-invalid');
    await chmod(paths.jfrPath, 0o600);
    const jsonBytes = await readJfrJson(epoch.jfrExecutable, paths.jfrPath);
    let parsed;
    try { parsed = JSON.parse(jsonBytes.toString('utf8')); } catch { throw codedError('jfr-json-invalid'); }
    const sanitized = sanitizeJfrEvents(parsed?.recording?.events);
    const output = sanitized.bytes;
    await writeFile(paths.jfrSanitizedPath, output, { mode: 0o600, flag: 'wx' });
    await chmod(paths.jfrSanitizedPath, 0o600);
    epoch.jfr = {
      state: 'sanitized',
      bytes: output.byteLength,
      sha256: createHash('sha256').update(output).digest('hex'),
      eventCount: sanitized.eventCount,
      eventCounts: sanitized.eventCounts,
      maxBytesPerEpoch: 16 * 1024 * 1024,
      endpointPathBodyFieldsExcluded: true,
    };
    epoch.jfrComplete = true;
  } catch (error) {
    epoch.jfr = { state: 'incomplete', failure: safeCode(error?.code), maxBytesPerEpoch: 16 * 1024 * 1024 };
    epoch.jfrComplete = false;
  } finally {
    await unlink(paths.jfrPath).catch(() => {});
  }
}

export function sanitizeJfrEvents(events) {
  if (!Array.isArray(events) || events.length > 100_000) throw codedError('jfr-event-count-invalid');
  const allowedTypes = new Set([
    'jdk.ExecutionSample', 'jdk.ThreadPark', 'jdk.JavaMonitorEnter',
    'jdk.SocketRead', 'jdk.SocketWrite', 'jdk.FileRead', 'jdk.FileWrite',
    'jdk.GarbageCollection', 'jdk.GCHeapSummary',
  ]);
  const eventCounts = Object.create(null);
  const records = [];
  for (const event of events) {
    if (!allowedTypes.has(event?.type)) continue;
    const values = event.values;
    if (!values || typeof values !== 'object' || Array.isArray(values)) continue;
    eventCounts[event.type] = (eventCounts[event.type] ?? 0) + 1;
    const thread = values.eventThread ?? values.sampledThread;
    records.push(JSON.stringify({
      type: event.type,
      startTime: safeScalar(values.startTime),
      duration: safeScalar(values.duration),
      threadId: safeInteger(thread?.javaThreadId),
      bytesRead: safeInteger(values.bytesRead),
      bytesWritten: safeInteger(values.bytesWritten),
      stack: sanitizeStack(values.stackTrace),
    }));
  }
  if (records.length === 0) throw codedError('jfr-no-approved-events');
  const bytes = Buffer.from(`${records.join('\n')}\n`, 'utf8');
  if (bytes.byteLength > 16 * 1024 * 1024) throw codedError('jfr-redacted-output-size-limit');
  return { bytes, eventCount: records.length, eventCounts };
}

async function readJfrJson(jfrCommand, recordingPath) {
  const child = spawn(jfrCommand, ['print', '--json', '--stack-depth', '32', recordingPath], {
    env: process.env.PATH ? { PATH: process.env.PATH } : {},
    stdio: ['ignore', 'pipe', 'ignore'],
    windowsHide: true,
  });
  const chunks = [];
  let bytes = 0;
  let tooLarge = false;
  child.stdout.on('data', (chunk) => {
    bytes += chunk.byteLength;
    if (bytes > 64 * 1024 * 1024) {
      tooLarge = true;
      child.kill('SIGKILL');
      return;
    }
    chunks.push(chunk);
  });
  const closed = new Promise((resolveClose, reject) => {
    child.once('error', () => reject(codedError('jfr-tool-unavailable')));
    child.once('close', (code) => resolveClose(code));
  });
  const timeout = setTimeout(() => child.kill('SIGKILL'), 30_000);
  try {
    const code = await closed;
    if (tooLarge) throw codedError('jfr-json-output-size-limit');
    if (code !== 0) throw codedError('jfr-json-export-failed');
    return Buffer.concat(chunks, bytes);
  } finally {
    clearTimeout(timeout);
  }
}

function sanitizeStack(stackTrace) {
  if (!stackTrace || typeof stackTrace !== 'object' || !Array.isArray(stackTrace.frames)) return [];
  return stackTrace.frames.slice(0, 32).map((frame) => ({
    class: safeName(frame?.method?.type?.name),
    method: safeName(frame?.method?.name),
    line: safeInteger(frame?.lineNumber),
  }));
}

function safeName(value) {
  return typeof value === 'string' && value.length <= 192 && /^[A-Za-z0-9_.$<>/-]*$/u.test(value) ? value : null;
}

function safeScalar(value) {
  if (typeof value === 'string' && value.length <= 64 && /^[A-Za-z0-9:+._-]*$/u.test(value)) return value;
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  return null;
}

function safeInteger(value) {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return value;
  if (typeof value === 'string' && /^\d{1,16}$/u.test(value)) {
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) ? parsed : null;
  }
  return null;
}

export function summarizePhase(item, summary, samples) {
  const hist = Array.isArray(item.tickHistogram) ? item.tickHistogram : [];
  const upper = Array.isArray(summary.tickHistogramUpperNanos) ? summary.tickHistogramUpperNanos : [];
  const p = (quantile) => histogramPercentile(hist, upper, quantile, item.maxTickNanos ?? 0);
  const firstPublicDispatch = summarizeFirstPublicDispatch(item, summary);
  const sampled = parseSampleRows(samples).filter((row) => row.phase === item.name && row.warmup === false);
  const startIntervals = sampled.map((row) => row.startIntervalNanos).filter((value) => value > 0);
  const elapsedMs = Number.isSafeInteger(item.elapsedNanos) && item.elapsedNanos >= 0 ? item.elapsedNanos / 1_000_000 : 0;
  const elapsedSeconds = elapsedMs / 1000;
  const counterValue = (name) => {
    const index = Array.isArray(summary.integrationCounterNames) ? summary.integrationCounterNames.indexOf(name) : -1;
    return index >= 0 ? item.integrationCounters?.[index] ?? 0 : 0;
  };
  return {
    name: item.name,
    ticks: item.ticks,
    elapsedMs,
    sampleRecords: sampled.length,
    attempts: item.attempts,
    distinctInvocations: item.distinctInvocations,
    expectedAttemptedPerSecond: item.name === 'overload' ? OVERLOAD_INVOCATIONS_PER_SECOND : null,
    expectedDistinctPerSecond: item.name === 'overload' ? OVERLOAD_INVOCATIONS_PER_SECOND : null,
    expectedAttemptedInvocations: item.name === 'overload' ? EXPECTED_OVERLOAD_ATTEMPTS : null,
    expectedDistinctInvocations: item.name === 'overload' ? EXPECTED_OVERLOAD_ATTEMPTS : null,
    attemptedRatePerSecond: elapsedSeconds > 0 ? item.attempts / elapsedSeconds : null,
    distinctInvocationRatePerSecond: elapsedSeconds > 0 ? item.distinctInvocations / elapsedSeconds : null,
    acceptedCommands: item.commands,
    rejectedCommands: item.rejected,
    distinctOtherRequests: item.distinctOtherRequests,
    distinctOtherAttempts: item.distinctOtherAttempts,
    repeatAttempts: item.repeatAttempts,
    repeatAccepted: item.repeatAccepted,
    repeatRejected: item.repeatRejected,
    outstandingAdmissionHighWater: item.outstandingAdmissionHighWater,
    perPlayerAdmissionHighWater: item.perPlayerAdmissionHighWater,
    playersAtTwoAdmissionsHighWater: item.playersAtTwoAdmissionsHighWater,
    capacityOnlineHighWater: item.capacityOnlineHighWater,
    capacityPerPlayerAdmissionHighWater: item.capacityPerPlayerAdmissionHighWater,
    capacityPlayersAtTwoAdmissionsHighWater: item.capacityPlayersAtTwoAdmissionsHighWater,
    capacityObservationTicks: item.capacityObservationTicks,
    integrationReadyTicks: item.integrationReadyTicks,
    authorizedOnlineHighWater: item.authorizedOnlineHighWater,
    authorizedJoins: item.authorizedJoins,
    authorizedLeaves: item.authorizedLeaves,
    authorizedRejoins: item.authorizedRejoins,
    maxTickNanos: item.maxTickNanos,
    overFiftyMillis: item.overFiftyMillis,
    tickHistogram: hist,
    tickDurationP50UpperBoundNanos: p(0.50),
    tickDurationP95UpperBoundNanos: p(0.95),
    tickDurationP99UpperBoundNanos: p(0.99),
    sampledStartIntervalP50Nanos: percentile(startIntervals, 0.50),
    sampledStartIntervalP95Nanos: percentile(startIntervals, 0.95),
    sampledStartIntervalP99Nanos: percentile(startIntervals, 0.99),
    integrationCounters: item.integrationCounters,
    integrationCounterNames: summary.integrationCounterNames,
    requestsAdmitted: counterValue('REQUESTS_ADMITTED'),
    pendingExpired: counterValue('PENDING_EXPIRED'),
    answersDelivered: counterValue('ANSWERS_DELIVERED'),
    upstreamRejections: counterValue('UPSTREAM_REJECTIONS'),
    unavailableDispositions: counterValue('UNAVAILABLE_DISPOSITIONS'),
    connectionFailures: counterValue('CONNECTION_FAILURES'),
    reconnectExhausted: counterValue('RECONNECT_EXHAUSTED'),
    firstPublicDispatchMetricsAvailable: firstPublicDispatch.available,
    firstPublicDispatchHistograms: firstPublicDispatch.histograms,
    firstPublicDispatchBySourceDisposition: firstPublicDispatch.bySourceDisposition,
    firstPublicDispatchCount: firstPublicDispatch.count,
    firstPublicDispatchOverflowCount: firstPublicDispatch.overflowCount,
    firstPublicDispatchP50UpperBoundNanos: firstPublicDispatch.p50UpperBoundNanos,
    firstPublicDispatchP95UpperBoundNanos: firstPublicDispatch.p95UpperBoundNanos,
    firstPublicDispatchP99UpperBoundNanos: firstPublicDispatch.p99UpperBoundNanos,
    firstReplyDeadline: summarizeFirstReplyDeadline(item, summary),
  };
}

function summarizeFirstReplyDeadline(item, summary) {
  const counters = item.integrationCounters;
  const names = summary.integrationCounterNames;
  const count = (name) => {
    const index = Array.isArray(names) ? names.indexOf(name) : -1;
    const value = index >= 0 ? counters?.[index] : null;
    return Number.isSafeInteger(value) && value >= 0 ? value : null;
  };
  const captured = count('INVOCATIONS_CAPTURED');
  const onTime = count('FIRST_REPLY_ON_TIME');
  const late = count('FIRST_REPLY_LATE');
  const noDispatch = count('FIRST_REPLY_NO_DISPATCH');
  const noDispatchSettled = count('FIRST_REPLY_NO_DISPATCH_SETTLED');
  const captureEvidenceMissing = count('INTERACTION_RECEIPT_EVIDENCE_MISSING');
  const misses = count('FIRST_REPLY_DEADLINE_MISSES');
  const noDispatchDeadline = noDispatch !== null && noDispatchSettled !== null && noDispatchSettled <= noDispatch
    ? noDispatch - noDispatchSettled : null;
  const linkedMisses = late !== null && noDispatchDeadline !== null ? late + noDispatchDeadline : null;
  const resolved = [onTime, late, noDispatch, captureEvidenceMissing];
  const resolvedCount = resolved.every((value) => Number.isSafeInteger(value) && value >= 0)
    ? resolved.reduce((sum, value) => sum + value, 0) : null;
  const pending = captured !== null && resolvedCount !== null && resolvedCount <= captured
    ? captured - resolvedCount : null;
  const reconciliationStatus = misses === null || linkedMisses === null ? 'unavailable'
    : misses === linkedMisses ? 'matches_linked_all_outcome_misses' : 'aggregate_linked_miss_mismatch';
  const actualMaximumMetricAvailable = Number.isSafeInteger(summary.maxFirstPublicDispatchNanos) &&
    summary.maxFirstPublicDispatchNanos >= 0;
  const hasAnyDispatchSample = Array.isArray(summary.phases)
    ? summary.phases.some((phase) => histogramTotal(phase?.firstPublicDispatchHistograms) > 0)
    : item.firstPublicDispatchCount > 0;
  const actualMaximumDispatchNanos = actualMaximumMetricAvailable && hasAnyDispatchSample
    ? summary.maxFirstPublicDispatchNanos : null;
  const result = {
    invocationCount: captured,
    onTimeCount: onTime,
    lateDispatchCount: late,
    noDispatchCount: noDispatch,
    noDispatchDeadlineCount: noDispatchDeadline,
    noDispatchSettledCount: noDispatchSettled,
    pendingCount: pending,
    captureEvidenceMissingCount: captureEvidenceMissing,
    deadlineMissCount: misses,
    linkedKnownMissCount: linkedMisses,
    definitelyUnsentCount: count('FAILED_PUBLIC_DISPATCHES'),
    unknownSendCount: count('UNKNOWN_PUBLIC_DISPATCHES'),
    missRateOverCapturedInvocations: captured === null || misses === null || captured === 0
      ? null : misses / captured,
    actualMaximumDispatchNanos,
    actualMaximumMetricAvailable,
    reconciliationStatus,
    status: misses !== null && misses > 0 ? 'FAILED'
      : pending === 0 && captureEvidenceMissing === 0 &&
        reconciliationStatus === 'matches_linked_all_outcome_misses'
      ? 'PASS' : 'INCONCLUSIVE',
    maximumScope: 'server-metrics-lifetime; not phase-specific and not inferred from histogram buckets',
  };
  result.available = Object.entries(result).filter(([key]) => key.endsWith('Count') || key === 'invocationCount')
    .every(([, value]) => Number.isSafeInteger(value) && value >= 0) && actualMaximumMetricAvailable &&
    reconciliationStatus === 'matches_linked_all_outcome_misses';
  return result;
}

function histogramTotal(histograms) {
  if (!Array.isArray(histograms)) return 0;
  let total = 0;
  for (const source of histograms) {
    if (!Array.isArray(source)) continue;
    for (const disposition of source) {
      if (!Array.isArray(disposition)) continue;
      for (const bucket of disposition) if (Number.isSafeInteger(bucket) && bucket >= 0) total += bucket;
    }
  }
  return total;
}

function summarizeFirstPublicDispatch(item, summary) {
  const histograms = item.firstPublicDispatchHistograms;
  const upper = summary.firstPublicDispatchUpperNanos;
  const valid = summary.firstPublicDispatchMetricsAvailable === true &&
    arraysEqual(summary.firstPublicDispatchSourceNames, FIRST_PUBLIC_DISPATCH_SOURCES) &&
    arraysEqual(summary.firstPublicDispatchDispositionNames, FIRST_PUBLIC_DISPATCH_DISPOSITIONS) &&
    isValidHistogramBounds(upper) && isValidFirstPublicDispatchHistograms(histograms, upper);
  if (!valid) {
    return {
      available: false, histograms: null, bySourceDisposition: null, count: null, overflowCount: null,
      p50UpperBoundNanos: null, p95UpperBoundNanos: null, p99UpperBoundNanos: null,
    };
  }
  const bySourceDisposition = {};
  let count = 0;
  let overflowCount = 0;
  const aggregate = Array(upper.length).fill(0);
  for (let source = 0; source < FIRST_PUBLIC_DISPATCH_SOURCES.length; source += 1) {
    const sourceName = FIRST_PUBLIC_DISPATCH_SOURCES[source];
    bySourceDisposition[sourceName] = {};
    for (let disposition = 0; disposition < FIRST_PUBLIC_DISPATCH_DISPOSITIONS.length; disposition += 1) {
      const dispositionName = FIRST_PUBLIC_DISPATCH_DISPOSITIONS[disposition];
      const histogram = histograms[source][disposition];
      const cellCount = histogram.reduce((sum, value) => sum + value, 0);
      bySourceDisposition[sourceName][dispositionName] = {
        histogram,
        count: cellCount,
        p50UpperBoundNanos: histogramPercentile(histogram, upper, 0.50, null),
        p95UpperBoundNanos: histogramPercentile(histogram, upper, 0.95, null),
        p99UpperBoundNanos: histogramPercentile(histogram, upper, 0.99, null),
      };
      count += cellCount;
      overflowCount += histogram.at(-1) ?? 0;
      for (let bucket = 0; bucket < aggregate.length; bucket += 1) aggregate[bucket] += histogram[bucket];
    }
  }
  return {
    available: true,
    histograms,
    bySourceDisposition,
    count,
    overflowCount,
    p50UpperBoundNanos: histogramPercentile(aggregate, upper, 0.50, null),
    p95UpperBoundNanos: histogramPercentile(aggregate, upper, 0.95, null),
    p99UpperBoundNanos: histogramPercentile(aggregate, upper, 0.99, null),
  };
}

export function assessRecordedEpochEvidence({ summary, samples, peerStatus, expectedPhases }) {
  const failures = [];
  const failed = (code) => { if (!failures.includes(code)) failures.push(code); };
  if (!summary || summary.schemaVersion !== 3 || !Array.isArray(summary.phases) || summary.phases.length !== 7) {
    return { complete: false, failures: ['tick-summary-missing-or-invalid'], phaseSummaries: [], capacityEvidence: null };
  }
  if (!isValidHistogramBounds(summary.tickHistogramUpperNanos) ||
      !isValidHistogramBounds(summary.firstPublicDispatchUpperNanos) ||
      summary.firstPublicDispatchMetricsAvailable !== true ||
      !arraysEqual(summary.firstPublicDispatchSourceNames, FIRST_PUBLIC_DISPATCH_SOURCES) ||
      !arraysEqual(summary.firstPublicDispatchDispositionNames, FIRST_PUBLIC_DISPATCH_DISPOSITIONS) ||
      !arraysEqual(summary.integrationCounterNames, INTEGRATION_COUNTER_NAMES) ||
      !Number.isSafeInteger(summary.maxFirstPublicDispatchNanos) || summary.maxFirstPublicDispatchNanos < 0 ||
      summary.phases.some((item) => !item || !isValidHistogram(item.tickHistogram, summary.tickHistogramUpperNanos) ||
        !isValidFirstPublicDispatchHistograms(item.firstPublicDispatchHistograms, summary.firstPublicDispatchUpperNanos))) {
    failed('tick-summary-histogram-invalid');
  }
  if (summary.firstPublicDispatchMetricsAvailable !== true ||
      summary.phases.some((item) => !isValidFirstPublicDispatchHistograms(
        item?.firstPublicDispatchHistograms, summary.firstPublicDispatchUpperNanos))) {
    failed('firstpublic-dispatch-metrics-missing-or-invalid');
  }
  if (summary.phases.some((item) => !item || typeof item.name !== 'string' || !Number.isSafeInteger(item.ticks))) {
    return { complete: false, failures: [...failures, 'tick-summary-phase-record-invalid'], phaseSummaries: [], capacityEvidence: null };
  }
  const phaseSummaries = summary.phases.map((item) => summarizePhase(item, summary, samples));
  if (phaseSummaries.some((item) => !item.firstReplyDeadline.available)) {
    failed('firstreply-deadline-evidence-missing');
  }
  const rows = parseSampleRows(samples);
  if (peerStatus?.schemaVersion !== 1 || peerStatus.event !== 'stopped' || peerStatus.listening !== false ||
      !Number.isSafeInteger(peerStatus.requestCount) || !Number.isSafeInteger(peerStatus.answerCount) ||
      !peerStatus.requestsByPhase || !peerStatus.delayedRequestsByPhase || !peerStatus.delayedPendingHighWaterByPhase) {
    failed('fixture-peer-capture-incomplete');
  }
  if (!Array.isArray(summary.integrationCounterNames) ||
      summary.phases.some((item) => !Array.isArray(item.integrationCounters) ||
        item.integrationCounters.length !== summary.integrationCounterNames.length ||
        item.integrationCounters.some((value) => !Number.isSafeInteger(value) || value < 0))) {
    failed('tick-summary-counter-record-invalid');
  }
  if ((expectedPhases ?? []).includes('overload') &&
      (summary.workloadContract?.overloadAttemptedPerSecond !== OVERLOAD_INVOCATIONS_PER_SECOND ||
       summary.workloadContract?.overloadDistinctPerSecond !== OVERLOAD_INVOCATIONS_PER_SECOND ||
       summary.workloadContract?.overloadDurationMs !== MEASURED_PHASE_MS ||
       summary.workloadContract?.overloadExpectedAttempts !== EXPECTED_OVERLOAD_ATTEMPTS ||
       summary.workloadContract?.overloadExpectedDistinctInvocations !== EXPECTED_OVERLOAD_ATTEMPTS ||
       summary.workloadContract?.repeatAttempts !== 7 ||
       summary.workloadContract?.activeCapacity !== ACTIVE_REQUEST_CAPACITY ||
       summary.workloadContract?.globalPendingCapacity !== GLOBAL_PENDING_CAPACITY ||
       summary.workloadContract?.perPlayerPendingCapacity !== PER_PLAYER_PENDING_CAPACITY)) {
    failed('recorded-workload-contract-mismatch');
  }
  if (summary.captureIncomplete !== false || !Number.isSafeInteger(summary.sampleRecords) || summary.sampleRecords < 1 ||
      !Number.isSafeInteger(summary.warmupSampleRecords) || summary.warmupSampleRecords < 1 ||
      rows.length !== summary.sampleRecords + summary.warmupSampleRecords ||
      !Number.isSafeInteger(summary.warmupTicks) || summary.warmupTicks < 1 ||
      !Number.isSafeInteger(summary.warmupElapsedNanos) ||
      summary.warmupElapsedNanos < (120_000 - REQUIRED_PHASE_DURATION_TOLERANCE_MS) * 1_000_000 ||
      summary.authorizedListLoaded !== true) {
    failed('capture-incomplete-or-authorized-list-not-loaded');
  }
  for (const phaseName of expectedPhases ?? []) {
    const phase = phaseSummaries.find((item) => item.name === phaseName);
    const expectedDuration = EXPECTED_PHASE_DURATION_MS[phaseName];
    if (!phase || phase.ticks < 1 || phase.sampleRecords < 1 || !Number.isFinite(expectedDuration) ||
        phase.elapsedMs < expectedDuration - REQUIRED_PHASE_DURATION_TOLERANCE_MS) {
      failed(`required-phase-incomplete-${phaseName}`);
    }
  }

  const overload = phaseSummaries.find((item) => item.name === 'overload');
  let capacityEvidence = null;
  if ((expectedPhases ?? []).includes('overload')) {
    const overloadNumericFields = [
      overload?.attempts, overload?.distinctInvocations, overload?.rejectedCommands,
      overload?.pendingExpired, overload?.outstandingAdmissionHighWater,
      overload?.capacityPerPlayerAdmissionHighWater, overload?.capacityPlayersAtTwoAdmissionsHighWater,
      overload?.capacityOnlineHighWater, overload?.capacityObservationTicks,
      overload?.authorizedLeaves,
    ];
    if (overloadNumericFields.some((value) => !Number.isSafeInteger(value) || value < 0)) {
      failed('overload-record-invalid');
    }
    const peerActiveHighWater = peerStatus?.delayedPendingHighWaterByPhase?.overload;
    const globalPendingHighWater = Number.isSafeInteger(overload?.outstandingAdmissionHighWater)
      ? Math.max(0, Math.min(GLOBAL_PENDING_CAPACITY,
        overload.outstandingAdmissionHighWater - ACTIVE_REQUEST_CAPACITY)) : 0;
    const perPlayerPendingHighWater = Number.isSafeInteger(overload?.capacityPlayersAtTwoAdmissionsHighWater) &&
      overload.capacityPlayersAtTwoAdmissionsHighWater >= 2 && peerActiveHighWater >= ACTIVE_REQUEST_CAPACITY
      ? PER_PLAYER_PENDING_CAPACITY : 0;
    capacityEvidence = {
      basis: 'lower-bound inferred from successful harness submissions, production admission/terminal counters, and fixture delayed in-flight requests; not a direct production queue gauge',
      expected: {
        active: ACTIVE_REQUEST_CAPACITY,
        globalPending: GLOBAL_PENDING_CAPACITY,
        perPlayerPending: PER_PLAYER_PENDING_CAPACITY,
        authorizedPlayers: CAPACITY_PLAYER_COUNT,
      },
      observedHighWater: {
        active: peerActiveHighWater ?? 0,
        globalPending: globalPendingHighWater,
        perPlayerPending: perPlayerPendingHighWater,
        outstandingAdmissions: overload?.outstandingAdmissionHighWater ?? 0,
        maximumAdmissionsForOnePlayer: overload?.capacityPerPlayerAdmissionHighWater ?? 0,
        playersAtTwoAdmissions: overload?.capacityPlayersAtTwoAdmissionsHighWater ?? 0,
        authorizedOnline: overload?.capacityOnlineHighWater ?? 0,
      },
      exceeded: (peerActiveHighWater ?? 0) > ACTIVE_REQUEST_CAPACITY ||
        globalPendingHighWater > GLOBAL_PENDING_CAPACITY ||
        (overload?.capacityPerPlayerAdmissionHighWater ?? 0) > PER_PLAYER_PENDING_CAPACITY + ACTIVE_REQUEST_CAPACITY ||
        (overload?.outstandingAdmissionHighWater ?? 0) > ACTIVE_REQUEST_CAPACITY + GLOBAL_PENDING_CAPACITY,
    };
    if (!Number.isSafeInteger(peerStatus?.requestsByPhase?.overload) || peerStatus.requestsByPhase.overload < 1 ||
        !Number.isSafeInteger(peerStatus?.delayedRequestsByPhase?.overload) ||
        !Number.isSafeInteger(peerActiveHighWater)) failed('overload-peer-capture-incomplete');
    if (overload?.capacityOnlineHighWater < CAPACITY_PLAYER_COUNT ||
        overload?.capacityPlayersAtTwoAdmissionsHighWater < CAPACITY_PLAYER_COUNT || overload?.authorizedLeaves > 0) {
      failed('overload-authorized-capacity-not-reached');
    }
    if (overload?.elapsedMs < MEASURED_PHASE_MS - REQUIRED_PHASE_DURATION_TOLERANCE_MS ||
        overload?.attempts < EXPECTED_OVERLOAD_ATTEMPTS ||
        overload?.distinctInvocations < EXPECTED_OVERLOAD_ATTEMPTS ||
        overload?.attemptedRatePerSecond < MIN_OVERLOAD_RATE_PER_SECOND ||
        overload?.distinctInvocationRatePerSecond < MIN_OVERLOAD_RATE_PER_SECOND) {
      failed('overload-distinct-workload-volume-incomplete');
    }
    if (overload?.capacityObservationTicks < 1 ||
        overload?.outstandingAdmissionHighWater < ACTIVE_REQUEST_CAPACITY + GLOBAL_PENDING_CAPACITY ||
        overload?.outstandingAdmissionHighWater > ACTIVE_REQUEST_CAPACITY + GLOBAL_PENDING_CAPACITY ||
        globalPendingHighWater < GLOBAL_PENDING_CAPACITY ||
        perPlayerPendingHighWater < PER_PLAYER_PENDING_CAPACITY ||
        peerActiveHighWater !== ACTIVE_REQUEST_CAPACITY || capacityEvidence.exceeded) {
      failed('overload-capacity-high-water-incomplete-or-exceeded');
    }
    if (overload?.rejectedCommands < 1 || overload?.pendingExpired < 1) {
      failed('overload-rejection-or-expiry-outcome-missing');
    }
  }

  const repeat = phaseSummaries.find((item) => item.name === 'repeat');
  if ((expectedPhases ?? []).includes('repeat')) {
    if (![repeat?.repeatAttempts, repeat?.distinctOtherRequests, repeat?.repeatRejected].every(Number.isSafeInteger) ||
        repeat.repeatAttempts !== 7 || repeat.distinctOtherRequests < 1 || repeat.repeatRejected < 1) {
      failed('repeat-regression-incomplete');
    }
  }
  for (const usefulPhaseName of ['normal', 'recovery']) {
    if ((expectedPhases ?? []).includes(usefulPhaseName)) {
      const phase = phaseSummaries.find((item) => item.name === usefulPhaseName);
      if (phase?.answersDelivered < 1) failed(`${usefulPhaseName}-answer-outcome-missing`);
      if (phase?.answersDelivered > 0 &&
          (!Number.isSafeInteger(phase.firstPublicDispatchCount) || phase.firstPublicDispatchCount < 1)) {
        failed(`${usefulPhaseName}-firstpublic-dispatch-outcome-missing`);
      }
    }
  }
  const outage = phaseSummaries.find((item) => item.name === 'unavailable');
  if ((expectedPhases ?? []).includes('unavailable') && outage?.reconnectExhausted < 1) {
    failed('outage-retry-exhaustion-not-observed');
  }
  const deadlineMissCount = phaseSummaries.reduce((sum, item) =>
    sum + (item.firstReplyDeadline.deadlineMissCount ?? 0), 0);
  const deadlinePendingCount = phaseSummaries.reduce((sum, item) =>
    sum + (item.firstReplyDeadline.pendingCount ?? 0), 0);
  const receiptEvidenceMissingCount = phaseSummaries.reduce((sum, item) =>
    sum + (item.firstReplyDeadline.captureEvidenceMissingCount ?? 0), 0);
  const invocationCount = phaseSummaries.reduce((sum, item) =>
    sum + (item.firstReplyDeadline.invocationCount ?? 0), 0);
  if (deadlineMissCount > 0) failed('firstreply-deadline-miss');
  if (deadlinePendingCount > 0) failed('firstreply-deadline-pending-at-settlement');
  if (receiptEvidenceMissingCount > 0) failed('firstreply-receipt-evidence-missing');
  const deadlineEvidenceAvailable = phaseSummaries.every((item) => item.firstReplyDeadline.available);
  return {
    complete: failures.length === 0,
    failures,
    phaseSummaries,
    capacityEvidence,
    firstReplyDeadline: {
      thresholdMs: 1000,
      maxFirstPublicDispatchNanos: phaseSummaries.some((item) => item.firstPublicDispatchCount > 0)
        ? summary.maxFirstPublicDispatchNanos : null,
      actualMaximumScope: 'server-metrics-lifetime; not phase-specific and not inferred from histogram buckets',
      invocationCount,
      onTimeCount: phaseSummaries.reduce((sum, item) => sum + (item.firstReplyDeadline.onTimeCount ?? 0), 0),
      lateDispatchCount: phaseSummaries.reduce((sum, item) => sum + (item.firstReplyDeadline.lateDispatchCount ?? 0), 0),
      noDispatchDeadlineCount: phaseSummaries.reduce((sum, item) => sum + (item.firstReplyDeadline.noDispatchDeadlineCount ?? 0), 0),
      noDispatchSettledCount: phaseSummaries.reduce((sum, item) => sum + (item.firstReplyDeadline.noDispatchSettledCount ?? 0), 0),
      pendingCount: deadlinePendingCount,
      captureEvidenceMissingCount: receiptEvidenceMissingCount,
      deadlineMissCount,
      status: deadlineMissCount > 0 ? 'FAILED'
        : !deadlineEvidenceAvailable || deadlinePendingCount > 0 || receiptEvidenceMissingCount > 0 || invocationCount === 0
          ? 'INCONCLUSIVE' : 'PASS',
      criterion: 'any invocation deadline miss fails this run; server submission is not client display',
    },
  };
}

function isValidHistogramBounds(bounds) {
  return Array.isArray(bounds) && bounds.length >= 2 && bounds.length <= 32 &&
    bounds.every((value, index) => Number.isFinite(value) && Number.isInteger(value) && value > 0 &&
      (index === bounds.length - 1 || Number.isSafeInteger(value)) &&
      (index === 0 || value > bounds[index - 1]));
}

function isValidHistogram(histogram, bounds) {
  return Array.isArray(histogram) && histogram.length === bounds.length &&
    histogram.every((value) => Number.isSafeInteger(value) && value >= 0);
}

function isValidFirstPublicDispatchHistograms(histograms, bounds) {
  return isValidHistogramBounds(bounds) && Array.isArray(histograms) &&
    histograms.length === FIRST_PUBLIC_DISPATCH_SOURCES.length &&
    histograms.every((source) => Array.isArray(source) && source.length === FIRST_PUBLIC_DISPATCH_DISPOSITIONS.length &&
      source.every((disposition) => isValidHistogram(disposition, bounds)));
}

function arraysEqual(actual, expected) {
  return Array.isArray(actual) && actual.length === expected.length &&
    actual.every((value, index) => value === expected[index]);
}

function histogramPercentile(histogram, upper, quantile, maxValue) {
  const total = histogram.reduce((sum, value) => sum + value, 0);
  if (!total || histogram.length !== upper.length) return null;
  const target = Math.max(1, Math.ceil(total * quantile));
  let seen = 0;
  for (let index = 0; index < histogram.length; index += 1) {
    seen += histogram[index];
    if (seen >= target) return upper[index] === Number.MAX_SAFE_INTEGER || upper[index] > 1e15 ? maxValue : upper[index];
  }
  return maxValue;
}

function summarizeIntervals(samples) {
  const intervals = parseSampleRows(samples).map((row) => row.startIntervalNanos).filter((value) => value > 0);
  return {
    sampleCount: intervals.length,
    p50Nanos: percentile(intervals, 0.50),
    p95Nanos: percentile(intervals, 0.95),
    p99Nanos: percentile(intervals, 0.99),
    maxNanos: intervals.length ? Math.max(...intervals) : null,
  };
}

function parseSampleRows(text) {
  const rows = [];
  for (const line of text.split('\n')) {
    if (!line.startsWith('sample,')) continue;
    const fields = line.split(',');
    if (fields.length !== 18 || !PHASE_NAMES.has(fields[2])) continue;
    const startIntervalNanos = Number(fields[7]);
    const durationNanos = Number(fields[8]);
    if (!Number.isFinite(startIntervalNanos) || !Number.isFinite(durationNanos)) continue;
    rows.push({ phase: fields[2], warmup: fields[3] === 'true', startIntervalNanos, durationNanos });
  }
  return rows;
}

function percentile(values, quantile) {
  if (!values.length) return null;
  const ordered = [...values].sort((left, right) => left - right);
  return ordered[Math.max(0, Math.ceil(ordered.length * quantile) - 1)];
}

function sumByIndex(phases, key) {
  return phases.reduce((sum, phaseValue) => sum + (Number.isFinite(phaseValue[key]) ? phaseValue[key] : 0), 0);
}

async function writeServerProperties(directory, serverPort) {
  const lines = [
    'server-ip=127.0.0.1',
    `server-port=${serverPort}`,
    'online-mode=true',
    'white-list=true',
    'enforce-whitelist=true',
    'enable-rcon=false',
    'enable-query=false',
    'enable-command-block=false',
    'pvp=false',
    'allow-nether=false',
    'gamemode=adventure',
    'force-gamemode=true',
    'spawn-protection=64',
    'view-distance=4',
    'simulation-distance=4',
    'max-world-size=128',
    'max-players=8',
    'motd=Isolated Worldspeaker COMPAT-03 load fixture',
  ];
  const path = join(directory, 'server.properties');
  try {
    const existing = await lstat(path);
    if (!existing.isFile() || existing.isSymbolicLink()) throw codedError('server-properties-file-type-invalid');
    await writeFile(path, `${lines.join('\n')}\n`, { encoding: 'utf8', mode: 0o600 });
    await chmod(path, 0o600);
  } catch (error) {
    if (error?.code !== 'ENOENT') throw error;
    await writeFile(path, `${lines.join('\n')}\n`, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
  }
}

async function writeWhitelist(directory, players) {
  const entries = players.map(({ id, name }) => ({ uuid: id, name }));
  await writePrivateJson(join(directory, 'whitelist.json'), entries);
}

async function assertLoopbackProfile(directory, companionPort, serverPort) {
  const properties = await readFile(join(directory, 'server.properties'), 'utf8');
  if (!/^server-ip=127\.0\.0\.1$/mu.test(properties) ||
      Number(properties.match(/^server-port=(\d+)$/mu)?.[1]) !== serverPort ||
      !/^online-mode=true$/mu.test(properties) || !/^white-list=true$/mu.test(properties) ||
      !/^enable-rcon=false$/mu.test(properties) || !/^enable-query=false$/mu.test(properties) ||
      !/^enable-command-block=false$/mu.test(properties) || !/^pvp=false$/mu.test(properties) ||
      !/^allow-nether=false$/mu.test(properties) || !/^gamemode=adventure$/mu.test(properties) ||
      !/^force-gamemode=true$/mu.test(properties) || !/^max-world-size=128$/mu.test(properties)) {
    throw codedError('server-scope-not-loopback-or-authenticated');
  }
  validateLoopbackEndpoint(`ws://127.0.0.1:${companionPort}`);
}

async function ensureExplicitEula(path) {
  try {
    await writeFile(path, '# Explicit operator approval supplied with paired start/EULA flags\neula=true\n', {
      encoding: 'utf8', mode: 0o600, flag: 'wx',
    });
    await chmod(path, 0o600);
  } catch (error) {
    if (error?.code !== 'EEXIST') throw codedError('eula-file-write-failed');
    const current = await readFile(path, 'utf8').catch(() => '');
    if (!/^eula=true$/mu.test(current)) throw codedError('existing-eula-file-not-accepted');
  }
  await chmod(path, 0o600);
}

async function writePrivateJson(path, value, replace = false) {
  const body = `${JSON.stringify(value, null, 2)}\n`;
  if (!replace) {
    await writeFile(path, body, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
    await chmod(path, 0o600);
    return;
  }
  await atomicWrite(path, body, 0o600, replace);
}

async function atomicWrite(path, content, mode, replace = true) {
  const temporary = `${path}.${randomUUID()}.tmp`;
  await writeFile(temporary, content, { encoding: typeof content === 'string' ? 'utf8' : undefined, mode, flag: 'wx' });
  await chmod(temporary, mode);
  try {
    await rename(temporary, path);
  } catch (error) {
    const { unlink } = await import('node:fs/promises');
    await unlink(temporary).catch(() => {});
    throw error;
  }
}

async function saveRecord(path, record) {
  const content = `${JSON.stringify(record, null, 2)}\n`;
  await atomicWrite(path, content, 0o600, true);
}

function makeRecord(preflight) {
  return {
    schemaVersion: 1,
    deploymentId: preflight.deployment.deploymentId,
    deploymentManifestSha256: preflight.deploymentSha256,
    qualification: 'not-claimed',
    mode: 'loopback-fixture-with-explicit-eula-approval',
    peer: 'fixture-synthetic-not-openclaw',
    providerCalls: 0,
    credentialsCopied: false,
    playerIdentityValuesRecorded: false,
    eulaAuthorized: true,
    eulaFileWritten: false,
    evidenceComplete: true,
    artifactHashes: {
      modJarSha256: preflight.productionJar.sha256,
      harnessJarSha256: preflight.harness.sha256,
      harnessSourceSha256: preflight.sourceSha256,
      deploymentManifestSha256: preflight.deploymentSha256,
    },
    artifacts: [
      ...preflight.candidate.artifacts.map(({ name, bytes, sha256 }) => ({
        name, bytes, sha256, used: name.endsWith('.jar'),
      })),
      { name: `${preflight.deployment.mod.id}-${preflight.deployment.mod.version}-compat-load-harness.jar`, bytes: preflight.harness.bytes, sha256: preflight.harness.sha256, used: true },
    ],
    runtime: null,
    phases: {
      preflight: phase('passed', 'candidate-and-harness-freshness-verified'),
      runtime: phase('pending', 'not-run'),
      downloads: phase('pending', 'not-run'),
      load: phase('pending', 'not-run'),
    },
    runs: [],
  };
}

function phase(state, code) {
  return { state, code, finishedAtUtc: new Date().toISOString() };
}

function redactRuntime(facts) {
  return {
    platform: facts.platform,
    kernel: facts.kernel,
    cpu: facts.cpu,
    ramBytes: facts.ramBytes,
    helperNode: facts.helperNode,
    selectedNode: facts.selectedNode,
    npm: facts.npm,
    javaVendor: facts.javaVendor,
    javaRuntimeVersion: facts.javaRuntimeVersion,
    javaExitCode: facts.javaExitCode,
    nodeExitCode: facts.nodeExitCode,
    npmExitCode: facts.npmExitCode,
  };
}

function nextPendingPhase(record) {
  return ['runtime', 'downloads', 'load'].find((name) => record.phases[name]?.state === 'failed') ??
    ['runtime', 'downloads', 'load'].find((name) => record.phases[name]?.state === 'running' || record.phases[name]?.state === 'pending') ?? 'load';
}

async function measureRunEvidence(directory) {
  const evidenceFiles = [
    'epoch-1/tick-samples.csv', 'epoch-1/tick-summary.json', 'epoch-1/harness-status.json',
    'epoch-1/fixture-peer-status.json', 'epoch-1/thread-io-trace-redacted.jsonl',
    'epoch-2/tick-samples.csv', 'epoch-2/tick-summary.json', 'epoch-2/harness-status.json',
    'epoch-2/fixture-peer-status.json', 'epoch-2/thread-io-trace-redacted.jsonl',
  ];
  let total = 0;
  for (const name of evidenceFiles) {
    const path = join(directory, name);
    let info;
    try { info = await lstat(path); } catch (error) { if (error?.code === 'ENOENT') continue; throw error; }
    if (!info.isFile() || info.isSymbolicLink()) throw codedError('evidence-file-type-invalid');
    total += info.size;
    if (total > MAX_EVIDENCE_BYTES_PER_RUN) return total;
  }
  return total;
}

async function walkFiles(directory) {
  const result = [];
  async function visit(current) {
    const entries = await readdir(current, { withFileTypes: true });
    for (const entry of entries) {
      const path = join(current, entry.name);
      if (entry.isSymbolicLink()) throw codedError('unexpected-symlink-in-private-workdir');
      if (entry.isDirectory()) await visit(path);
      else if (entry.isFile()) result.push(path);
    }
  }
  await visit(directory);
  return result;
}

async function sha256File(path) {
  return createHash('sha256').update(await readFile(path)).digest('hex');
}

function isAbsolutePath(value) {
  return typeof value === 'string' && (value.startsWith('/') || /^[A-Za-z]:[\\/]/u.test(value));
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;

function safeOption(value) {
  return typeof value === 'string' ? value.replace(/[^a-z0-9-]/giu, '').slice(2, 42) || 'invalid' : 'invalid';
}

function safeCode(code) {
  return typeof code === 'string' && /^[a-z0-9-]{1,80}$/u.test(code) ? code : 'tick-load-run-failed';
}

function jfrExecutable(javaCommand) {
  return isAbsolutePath(javaCommand) || javaCommand.includes('/')
    ? join(dirname(resolve(javaCommand)), 'jfr')
    : 'jfr';
}

function codedError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

function remainingRunMs(runStarted, dependencies = {}) {
  const now = dependencies.now ?? (() => performance.now());
  const runLimitMs = dependencies.runLimitMs ?? RUN_LIMIT_MS;
  if (!Number.isFinite(runLimitMs) || runLimitMs <= 0) throw codedError('run-time-limit-invalid');
  const currentNow = readMonotonicTime(runStarted, now());
  return Math.max(0, runLimitMs - (currentNow - runStarted));
}

function readMonotonicTime(runStarted, currentNow, previousNow = Number.NEGATIVE_INFINITY) {
  if (!Number.isFinite(runStarted) || runStarted < 0 || !Number.isFinite(currentNow) ||
      currentNow < runStarted || currentNow < previousNow) {
    throw codedError('monotonic-time-origin-invalid');
  }
  return currentNow;
}

function assertRunTime(runStarted, dependencies = {}) {
  if (remainingRunMs(runStarted, dependencies) <= 0) throw codedError('run-time-limit-exceeded');
}

function throwIfAborted() {
  if (activeAbortController?.signal.aborted) throw codedError('run-interrupted');
}

function delay(milliseconds) {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, Math.max(1, Math.ceil(milliseconds))));
}

function isLoopbackPortOpen(port) {
  return new Promise((resolveOpen) => {
    const socket = connect({ host: '127.0.0.1', port });
    const finish = (open) => { socket.destroy(); resolveOpen(open); };
    socket.setTimeout(500);
    socket.once('connect', () => finish(true));
    socket.once('error', () => finish(false));
    socket.once('timeout', () => finish(false));
  });
}

function makeUsage() {
  return [
    'Usage: node tools/compat/tick-load.mjs --distribution <candidate-dir> --workdir <new-absolute-path> --players-file <private-absolute-file> --peer fixture --start-server --accept-eula',
    'Options: --harness <fresh-compat-load-harness.jar> --java <executable> --node <executable> --npm <executable>',
    'Runs three bounded isolated Minecraft fixture workloads, each capped at 40 minutes. The fixture does not call OpenClaw or a provider.',
    'Starting Minecraft requires both --start-server and explicit --accept-eula authorization.',
  ].join('\n');
}

async function main() {
  const options = parseTickLoadArgs(process.argv.slice(2));
  if (options.help) {
    process.stdout.write(`${makeUsage()}\n`);
    return;
  }
  activeAbortController = new AbortController();
  const abort = () => activeAbortController?.abort();
  process.once('SIGINT', abort);
  process.once('SIGTERM', abort);
  const result = await runTickLoad(options);
  process.stdout.write(`WS-TICK-LOAD-${result.ok ? 'FIXTURE-COMPLETE' : 'INCOMPLETE'} ${result.recordPath}\n`);
  process.stdout.write('Synthetic fixture only: no OpenClaw/provider qualification or useful-conversation evidence is claimed.\n');
  if (!result.ok) process.exitCode = 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    process.stderr.write(`WS-TICK-LOAD-REFUSED ${safeCode(error?.code)}\n`);
    process.exitCode = 2;
  });
}
