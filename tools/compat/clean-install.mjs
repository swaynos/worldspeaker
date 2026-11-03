import { mkdir, readFile, realpath, writeFile, chmod, copyFile, access, lstat } from 'node:fs/promises';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createServer, connect } from 'node:net';
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import os from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  assertEulaAuthorization,
  downloadBytes,
  downloadMavenArtifact,
  parseArgs,
  readZipJson as readZipArchiveJson,
  runBoundedCommand,
  setPhase,
  verifyCandidateDistribution,
} from './clean-install-core.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const manifestDefault = 'compatibility/deployment.json';
const limits = Object.freeze({
  downloadMs: 60_000,
  installerMs: 120_000,
  npmInstallMs: 300_000,
  pluginInstallMs: 180_000,
  gatewayStartupMs: 60_000,
  serverStartupMs: 120_000,
  handshakeMs: 60_000,
  gracefulStopMs: 10_000,
  forcedStopMs: 5_000,
});

export async function runCleanInstall(options, dependencies = {}) {
  assertEulaAuthorization(options);
  if (typeof options?.workdir !== 'string' || options.workdir.length === 0) throw codedError('workdir-required');
  if (options.workdir.includes('\0')) throw codedError('workdir-path-invalid');
  if (!isAbsolute(options.workdir)) throw codedError('workdir-must-be-absolute');
  const output = await ensureNewWorkdirOutsideRepo(resolve(options.workdir), root);

  const runId = randomUUID();
  const dirs = {
    downloads: join(output, 'downloads'),
    server: join(output, 'server'),
    openclaw: join(output, 'openclaw'),
    openclawHome: join(output, 'openclaw-home'),
    openclawState: join(output, 'openclaw-home', '.openclaw'),
    config: join(output, 'server', 'config'),
  };
  const recordPath = join(output, 'run-record.json');
  await mkdir(output, { recursive: false, mode: 0o700 });
  await Promise.all(Object.values(dirs).map((directory) => mkdir(directory, { recursive: true, mode: 0o700 })));
  const environment = buildIsolatedEnvironment({ ...dirs, node: options.node, npm: options.npm });
  await Promise.all([
    mkdir(environment.TMPDIR, { recursive: true, mode: 0o700 }),
    mkdir(environment.npm_config_cache, { recursive: true, mode: 0o700 }),
    mkdir(environment.XDG_CONFIG_HOME, { recursive: true, mode: 0o700 }),
    mkdir(environment.OPENCLAW_WORKSPACE_DIR, { recursive: true, mode: 0o700 }),
  ]);
  await writeFile(environment.npm_config_userconfig, '', { encoding: 'utf8', mode: 0o600, flag: 'wx' });

  const record = {
    schemaVersion: 1,
    runId,
    startedAtUtc: new Date().toISOString(),
    deploymentId: null,
    manifestRevision: null,
    deploymentManifestSha256: null,
    mode: options.startServer ? 'explicit-eula-approved-runtime-attempt' : 'staging-only',
    qualification: 'not-claimed',
    eulaApprovalFlagProvided: options.acceptEula === true,
    eulaFileWritten: false,
    gatewayProcessSpawned: false,
    gatewayListenerReady: false,
    serverProcessSpawned: false,
    serverStarted: false,
    authenticatedHandshake: false,
    artifacts: [],
    phases: {},
  };
  for (const name of [
    'contract', 'runtimePreflight', 'candidateArtifacts', 'pinnedDownloads', 'fabricInstall',
    'openclawInstall', 'pluginInstall', 'pluginConfiguration', 'gatewayStartup', 'serverStartup',
    'authenticatedHandshake', 'shutdown',
  ]) setPhase(record, name, 'skipped', { code: 'not-run' });
  await saveRecord(recordPath, record);

  let gateway = null;
  let server = null;
  let gatewayPort;
  let companionPort;

  try {
    const canonicalManifestBytes = await readFile(resolve(root, manifestDefault));
    const deploymentPath = resolve(root, options.deployment ?? manifestDefault);
    const manifestBytes = await readFile(deploymentPath);
    if (!manifestBytes.equals(canonicalManifestBytes)) throw codedError('deployment-manifest-not-canonical-copy');
    const manifest = JSON.parse(manifestBytes.toString('utf8'));
    const manifestSha256 = digest('sha256', manifestBytes);
    validateManifestPins(manifest);
    Object.assign(record, {
      deploymentId: manifest.deploymentId,
      manifestRevision: manifest.manifestRevision,
      deploymentManifestSha256: manifestSha256,
    });
    setPhase(record, 'contract', 'passed', { code: 'canonical-manifest-loaded' });
    await saveRecord(recordPath, record);

    const candidateDir = resolve(root, options.distribution ?? 'build/distributions/worldspeaker-candidate');
    const candidate = await verifyCandidateDistribution({
      distribution: candidateDir,
      manifest,
      manifestSha256,
      readZipJson: dependencies.readZipJson,
      readTarJson: dependencies.readTarJson,
    });
    record.artifacts.push(...candidate.artifacts);
    setPhase(record, 'candidateArtifacts', 'passed', { code: 'outer-manifest-and-package-metadata-verified' });
    await saveRecord(recordPath, record);

    const runtime = await inspectRuntime({
      manifest,
      java: options.java,
      node: options.node,
      npm: options.npm,
      environment,
      dependencies,
    });
    record.runtime = runtime.facts;
    setPhase(record, 'runtimePreflight', runtime.matches ? 'passed' : 'failed', {
      code: runtime.matches ? 'exact-pinned-runtime' : 'candidate-runtime-mismatch',
    });
    await saveRecord(recordPath, record);

    const downloads = await downloadPinnedInputs({ manifest, output: dirs.downloads, dependencies });
    setPhase(record, 'pinnedDownloads', 'passed', { code: 'all-pinned-downloads-verified' });
    record.artifacts.push(...downloads.artifacts);
    await saveRecord(recordPath, record);

    const fabricResult = await installFabric({
      manifest,
      downloads,
      candidate,
      dirs,
      java: options.java,
      environment,
      dependencies,
    });
    setPhase(record, 'fabricInstall', 'passed', { code: 'fabric-server-install-verified' });
    record.artifacts.push(...fabricResult.artifacts);
    await saveRecord(recordPath, record);

    const hostResult = await installOpenClaw({
      manifest,
      downloads,
      candidate,
      dirs,
      npm: options.npm,
      node: options.node,
      environment,
      dependencies,
    });
    setPhase(record, 'openclawInstall', 'passed', { code: 'pinned-openclaw-package-installed' });
    record.openclaw = hostResult.inventory;
    await saveRecord(recordPath, record);

    const secret = randomBytes(32).toString('base64url');
    gatewayPort = await getFreeLoopbackPort();
    companionPort = await getFreeLoopbackPort([gatewayPort]);
    const binding = {
      serverId: `clean-${runId.slice(0, 8)}`,
      worldId: `world-${runId.slice(0, 8)}`,
      characterId: `character-${runId.slice(0, 8)}`,
      agentId: 'main',
    };
    await writeWorldspeakerConfig(dirs.config, manifest, secret, companionPort, binding);
    await writeInitialOpenClawConfig({
      configPath: join(dirs.openclawState, 'openclaw.json'),
      gatewayPort,
      workspace: join(dirs.openclawState, 'workspace'),
    });

    const pluginResult = await installPlugin({
      node: options.node,
      cli: hostResult.cli,
      packageArchive: candidate.companionPath,
      cwd: dirs.openclaw,
      environment,
      dependencies,
    });
    setPhase(record, 'pluginInstall', 'passed', { code: 'npm-pack-plugin-install-command-passed' });
    record.openclaw.pluginInstall = pluginResult;
    await configureOpenClaw({
      configPath: join(dirs.openclawState, 'openclaw.json'),
      openclawRoot: hostResult.packageRoot,
      manifest,
      manifestSha256,
      secret,
      companionPort,
      gatewayPort,
      binding,
      workspace: join(dirs.openclawState, 'workspace'),
      dependencies,
    });
    setPhase(record, 'pluginConfiguration', 'passed', { code: 'loopback-only-secret-config-written-owner-only' });
    await saveRecord(recordPath, record);

    if (!options.startServer) {
      setPhase(record, 'gatewayStartup', 'skipped', { code: 'staging-only-no-host-start' });
      setPhase(record, 'serverStartup', 'skipped', { code: 'staging-only-no-server-start' });
      setPhase(record, 'authenticatedHandshake', 'skipped', { code: 'server-not-started' });
      setPhase(record, 'shutdown', 'skipped', { code: 'no-runtime-processes-started' });
      record.eulaFileWritten = false;
      record.serverStarted = false;
      record.authenticatedHandshake = false;
      return { ok: true, recordPath, record };
    }

    if (!runtime.matches) {
      setPhase(record, 'gatewayStartup', 'skipped', { code: 'pinned-runtime-preflight-failed' });
      setPhase(record, 'serverStartup', 'skipped', { code: 'pinned-runtime-preflight-failed' });
      setPhase(record, 'authenticatedHandshake', 'skipped', { code: 'pinned-runtime-preflight-failed' });
      setPhase(record, 'shutdown', 'skipped', { code: 'no-runtime-processes-started' });
      return { ok: false, recordPath, record };
    }

    await prepareServerProperties(dirs.server, [gatewayPort, companionPort], manifest);

    gateway = startManagedProcess(options.node, [hostResult.cli, 'gateway', 'run', '--port', String(gatewayPort), '--bind', 'loopback'], {
      cwd: dirs.openclaw,
      env: environment,
      spawnImpl: dependencies.spawn,
    });
    record.gatewayProcessSpawned = true;
    await saveRecord(recordPath, record);
    const gatewayReady = await waitForPort(gateway, companionPort, limits.gatewayStartupMs, dependencies);
    if (!gatewayReady) {
      setPhase(record, 'gatewayStartup', 'failed', {
        code: gateway.exitCode !== null ? 'openclaw-exited-before-channel-ready' : 'openclaw-channel-startup-timeout',
        exitCode: gateway.exitCode,
        diagnostic: classifyProcessOutput(gateway.output),
      });
      setPhase(record, 'serverStartup', 'skipped', { code: 'openclaw-channel-not-ready' });
      setPhase(record, 'authenticatedHandshake', 'skipped', { code: 'server-not-started' });
      await saveRecord(recordPath, record);
      return { ok: false, recordPath, record };
    }
    setPhase(record, 'gatewayStartup', 'passed', { code: 'openclaw-worldspeaker-listener-ready' });
    record.gatewayListenerReady = true;
    await saveRecord(recordPath, record);

    const eulaPath = join(dirs.server, 'eula.txt');
    await writeFile(eulaPath, '# Explicit approval supplied with --accept-eula\neula=true\n', { encoding: 'utf8', mode: 0o600, flag: 'wx' });
    record.eulaFileWritten = true;
    await saveRecord(recordPath, record);

    const serverProperties = await readFile(join(dirs.server, 'server.properties'), 'utf8');
    const serverPort = Number(serverProperties.match(/^server-port=(\d+)$/m)?.[1]);
    server = startManagedProcess(options.java, ['-Xms512M', '-Xmx2G', '-jar', 'fabric-server-launch.jar', 'nogui'], {
      cwd: dirs.server,
      env: environment,
      spawnImpl: dependencies.spawn,
    });
    record.serverProcessSpawned = true;
    await saveRecord(recordPath, record);
    const started = await waitForMarker(server, 'Done (', limits.serverStartupMs);
    if (!started || server.exitCode !== null) {
      setPhase(record, 'serverStartup', 'failed', {
        code: server.exitCode !== null ? 'minecraft-exited-before-ready' : 'minecraft-startup-timeout',
        exitCode: server.exitCode,
        diagnostic: classifyProcessOutput(server.output),
      });
      setPhase(record, 'authenticatedHandshake', 'skipped', { code: 'minecraft-server-not-ready' });
      await saveRecord(recordPath, record);
      return { ok: false, recordPath, record };
    }
    record.serverStarted = true;
    record.server = {
      bindHost: '127.0.0.1',
      port: serverPort,
      jvmArgs: ['-Xms512M', '-Xmx2G'],
      startedAtUtc: new Date().toISOString(),
    };
    setPhase(record, 'serverStartup', 'passed', { code: 'minecraft-server-ready-with-fabric-launcher' });
    await saveRecord(recordPath, record);

    const handshook = await waitForMarker(gateway, 'WS-COMPAT-READY', limits.handshakeMs);
    record.authenticatedHandshake = handshook;
    setPhase(record, 'authenticatedHandshake', handshook ? 'passed' : 'failed', {
      code: handshook ? 'authenticated-worldspeaker-peer-ready' : 'authenticated-handshake-timeout',
      diagnostic: handshook ? undefined : classifyProcessOutput(gateway.output),
    });
    await saveRecord(recordPath, record);
    return { ok: handshook, recordPath, record };
  } catch (error) {
    const phase = phaseForError(record);
    const cleanError = normalizeFailure(error);
    if (phase && record.phases[phase]?.state === 'skipped') {
      setPhase(record, phase, 'failed', { code: cleanError.code, exitCode: cleanError.exitCode, diagnostic: cleanError.diagnostic });
      markDownstreamSkipped(record, phase);
    }
    record.failure = {
      phase: phase ?? 'setup',
      code: cleanError.code,
      exitCode: cleanError.exitCode ?? null,
      ...(cleanError.diagnostic ? { diagnostic: cleanError.diagnostic } : {}),
    };
    return { ok: false, recordPath, record };
  } finally {
    const shutdownErrors = [];
    if (server) {
      const result = await stopManagedProcess(server, limits, true);
      record.shutdown = {
        ...(record.shutdown ?? {}),
        minecraft: result.mode,
        minecraftExitCode: server.exitCode,
        minecraftSignal: server.signalCode,
      };
      if (!result.stopped || result.mode === 'sigterm' || result.mode === 'sigkill') shutdownErrors.push(`minecraft-${result.mode}`);
      if (record.serverStarted && result.mode === 'already-exited') shutdownErrors.push('minecraft-exited-before-requested-stop');
    }
    if (gateway) {
      const result = await stopManagedProcess(gateway, limits, false);
      record.shutdown = {
        ...(record.shutdown ?? {}),
        openclaw: result.mode,
        openclawExitCode: gateway.exitCode,
        openclawSignal: gateway.signalCode,
      };
      if (!result.stopped || result.mode === 'sigkill') shutdownErrors.push(`openclaw-${result.mode}`);
      if (record.gatewayListenerReady && result.mode === 'already-exited') shutdownErrors.push('openclaw-exited-before-requested-stop');
    }
    if (server || gateway) {
      setPhase(record, 'shutdown', shutdownErrors.length ? 'failed' : 'passed', {
        code: shutdownErrors.length ? shutdownErrors.join(',') : 'spawned-processes-stopped',
      });
      if (shutdownErrors.length && !record.failure) record.failure = { phase: 'shutdown', code: shutdownErrors.join(',') };
    } else if (record.phases.shutdown?.state === 'skipped' && record.phases.shutdown.code === 'not-run') {
      setPhase(record, 'shutdown', 'skipped', { code: 'no-runtime-processes-started' });
    }
    record.finishedAtUtc = new Date().toISOString();
    await saveRecord(recordPath, record);
  }
}

async function downloadPinnedInputs({ manifest, output, dependencies }) {
  const fabric = await downloadFabricInputs({ manifest, output, dependencies });
  const npmMetadata = await fetchJson(`https://registry.npmjs.org/${manifest.openclaw.package}/${manifest.openclaw.version}`, ['registry.npmjs.org'], dependencies.fetch);
  if (npmMetadata.name !== manifest.openclaw.package || npmMetadata.version !== manifest.openclaw.version ||
      npmMetadata.dist?.integrity !== manifest.upstreamArtifacts.openclawNpmIntegrity) {
    throw codedError('openclaw-npm-integrity-mismatch');
  }
  const packageTarball = await downloadBytes(npmMetadata.dist.tarball, {
    allowedHosts: ['registry.npmjs.org'], maxBytes: 150 * 1024 * 1024,
    timeoutMs: limits.downloadMs, fetchImpl: dependencies.fetch,
  });
  const expectedSha512 = manifest.upstreamArtifacts.openclawNpmIntegrity.slice('sha512-'.length);
  if (digest('sha512', packageTarball, 'base64') !== expectedSha512) throw codedError('openclaw-package-integrity-mismatch');
  const openclawPath = join(output, `openclaw-${manifest.openclaw.version}.tgz`);
  await writeFile(openclawPath, packageTarball, { mode: 0o600, flag: 'wx' });
  return {
    ...fabric,
    openclawPath,
    artifacts: [
      ...fabric.artifacts,
      { ...artifactRecord(`openclaw-${manifest.openclaw.version}.tgz`, openclawPath, packageTarball),
        source: npmMetadata.dist.tarball, verifiedBy: 'deployment-openclawNpmIntegrity' },
    ],
  };
}

export async function downloadFabricInputs({ manifest, output, dependencies = {} }) {
  const officialJsonUrl = `https://piston-meta.mojang.com/v1/packages/018296bcb207bea6fd25b05f08eee3942dcec406/${manifest.target.minecraft}.json`;
  const gameMetadata = await fetchJson(officialJsonUrl, ['piston-meta.mojang.com'], dependencies.fetch);
  if (gameMetadata.id !== manifest.target.minecraft || gameMetadata.downloads?.server?.sha1 !== manifest.upstreamArtifacts.minecraftServerSha1) {
    throw codedError('official-minecraft-metadata-mismatch');
  }
  const gameServer = gameMetadata.downloads.server;
  const serverBytes = await downloadBytes(gameServer.url, {
    allowedHosts: ['piston-data.mojang.com'],
    maxBytes: 100 * 1024 * 1024,
    timeoutMs: limits.downloadMs,
    fetchImpl: dependencies.fetch,
  });
  const serverSha1 = digest('sha1', serverBytes);
  if (serverSha1 !== manifest.upstreamArtifacts.minecraftServerSha1 || serverBytes.byteLength !== gameServer.size) {
    throw codedError('minecraft-server-checksum-or-size-mismatch');
  }
  const serverPath = join(output, 'minecraft-server.jar');
  await writeFile(serverPath, serverBytes, { mode: 0o600, flag: 'wx' });

  const installerVersion = manifest.installationTools.fabricInstaller;
  const installerArtifact = await downloadMavenArtifact({
    path: `net/fabricmc/fabric-installer/${installerVersion}/fabric-installer-${installerVersion}.jar`,
    output: join(output, `fabric-installer-${installerVersion}.jar`),
    fetchImpl: dependencies.fetch,
    timeoutMs: limits.downloadMs,
  });
  const apiVersion = manifest.runtimeDependencies.fabricApi;
  const apiVersionPath = encodeURIComponent(apiVersion);
  const apiArtifact = await downloadMavenArtifact({
    path: `net/fabricmc/fabric-api/fabric-api/${apiVersionPath}/fabric-api-${apiVersionPath}.jar`,
    output: join(output, `fabric-api-${apiVersion}.jar`),
    fetchImpl: dependencies.fetch,
    timeoutMs: limits.downloadMs,
  });
  const apiMetadata = await (dependencies.readZipJson ?? readZipArchiveJson)(apiArtifact.path, 'fabric.mod.json');
  if (apiMetadata.id !== 'fabric-api' || apiMetadata.version !== apiVersion) throw codedError('downloaded-fabric-api-metadata-mismatch');
  const loaderVersion = manifest.runtimeDependencies.fabricLoader;
  const loaderArtifact = await downloadMavenArtifact({
    path: `net/fabricmc/fabric-loader/${loaderVersion}/fabric-loader-${loaderVersion}.jar`,
    output: join(output, `fabric-loader-${loaderVersion}.jar`),
    fetchImpl: dependencies.fetch,
    timeoutMs: limits.downloadMs,
  });
  const loaderMetadata = await fetchJson(`https://meta.fabricmc.net/v2/versions/loader/${encodeURIComponent(manifest.target.minecraft)}/${encodeURIComponent(manifest.runtimeDependencies.fabricLoader)}`, ['meta.fabricmc.net'], dependencies.fetch);
  if (loaderMetadata.loader?.version !== manifest.runtimeDependencies.fabricLoader || !Array.isArray(loaderMetadata.launcherMeta?.libraries?.common)) {
    throw codedError('fabric-loader-metadata-mismatch');
  }
  const loaderMetadataPath = join(output, 'fabric-loader-metadata.json');
  const loaderMetadataBytes = Buffer.from(JSON.stringify(loaderMetadata, null, 2) + '\n');
  await writeFile(loaderMetadataPath, loaderMetadataBytes, { mode: 0o600, flag: 'wx' });

  const artifacts = [
    { ...artifactRecord('minecraft-server.jar', serverPath, serverBytes), source: gameServer.url, verifiedBy: 'deployment-minecraftServerSha1-and-official-version-json' },
    { ...(await installerArtifact), name: `fabric-installer-${installerVersion}.jar` },
    { ...(await apiArtifact), name: `fabric-api-${apiVersion}.jar` },
    { ...(await loaderArtifact), name: `fabric-loader-${loaderVersion}.jar` },
    { ...artifactRecord('fabric-loader-metadata.json', loaderMetadataPath, loaderMetadataBytes), source: `https://meta.fabricmc.net/v2/versions/loader/${manifest.target.minecraft}/${manifest.runtimeDependencies.fabricLoader}` },
  ];
  return {
    serverPath,
    installerPath: installerArtifact.path,
    fabricApiPath: apiArtifact.path,
    fabricLoaderPath: loaderArtifact.path,
    fabricLoaderSha1: loaderArtifact.sha1,
    fabricLoaderSha256: loaderArtifact.sha256,
    loaderMetadata,
    artifacts,
  };
}

export async function installFabric({ manifest, downloads, candidate, dirs, java, environment, dependencies = {} }) {
  const serverDir = dirs.server;
  const installerArgs = [
    '-jar', downloads.installerPath, 'server',
    '-mcversion', manifest.target.minecraft,
    '-loader', manifest.runtimeDependencies.fabricLoader,
    '-dir', serverDir,
    '-downloadMinecraft',
  ];
  const install = await runBoundedCommand(java, installerArgs, {
    cwd: serverDir,
    env: environment,
    timeoutMs: limits.installerMs,
    spawnImpl: dependencies.spawn,
  });
  if (install.timedOut) throw codedError('fabric-installer-timeout');
  if (install.errorCode) throw codedError(install.errorCode);
  if (install.exitCode !== 0) throw codedError('fabric-installer-nonzero-exit', install.exitCode, classifyProcessOutput(`${install.stdout}\n${install.stderr}`));

  const serverPath = join(serverDir, 'server.jar');
  const vanilla = await readFile(serverPath).catch(() => null);
  if (!vanilla || digest('sha1', vanilla) !== manifest.upstreamArtifacts.minecraftServerSha1) {
    throw codedError('fabric-installed-minecraft-server-mismatch');
  }
  const launcherPath = join(serverDir, 'fabric-server-launch.jar');
  await access(launcherPath).catch(() => { throw codedError('fabric-launcher-artifact-missing'); });
  const installedLoaderPath = join(serverDir, 'libraries', 'net', 'fabricmc', 'fabric-loader', manifest.runtimeDependencies.fabricLoader, `fabric-loader-${manifest.runtimeDependencies.fabricLoader}.jar`);
  const installedLoader = await readFile(installedLoaderPath).catch(() => null);
  if (!installedLoader || digest('sha1', installedLoader) !== downloads.fabricLoaderSha1 || digest('sha256', installedLoader) !== downloads.fabricLoaderSha256) {
    throw codedError('fabric-loader-artifact-checksum-mismatch');
  }
  const loaderLibraries = await installFabricMetadataLibraries(manifest, downloads.loaderMetadata, serverDir);
  const modsDir = join(serverDir, 'mods');
  await mkdir(modsDir, { recursive: true, mode: 0o700 });
  const fabricApiPath = join(modsDir, `fabric-api-${manifest.runtimeDependencies.fabricApi}.jar`);
  const modPath = join(modsDir, `${manifest.mod.id}-${manifest.mod.version}.jar`);
  await copyFile(downloads.fabricApiPath, fabricApiPath);
  await copyFile(candidate.modPath, modPath);
  const [fabricApiBytes, modBytes] = await Promise.all([readFile(fabricApiPath), readFile(modPath)]);
  const artifacts = await Promise.all([
    ...await collectJarInventory(serverDir),
    ...loaderLibraries,
    artifactRecord(`mods/fabric-api-${manifest.runtimeDependencies.fabricApi}.jar`, fabricApiPath, fabricApiBytes),
    artifactRecord(`mods/${manifest.mod.id}-${manifest.mod.version}.jar`, modPath, modBytes),
  ]);
  return { artifacts };
}

async function installOpenClaw({ manifest, downloads, candidate, dirs, npm, node, environment, dependencies }) {
  await writeFile(join(dirs.openclaw, 'package.json'), JSON.stringify({ private: true, type: 'module' }, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  const npmInstall = await runBoundedCommand(npm, [
    'install', '--prefix', dirs.openclaw, '--no-audit', '--no-fund',
    '--save-exact', downloads.openclawPath,
  ], {
    cwd: dirs.openclaw,
    env: environment,
    timeoutMs: limits.npmInstallMs,
    spawnImpl: dependencies.spawn,
  });
  if (npmInstall.timedOut) throw codedError('openclaw-npm-install-timeout');
  if (npmInstall.errorCode) throw codedError(npmInstall.errorCode);
  if (npmInstall.exitCode !== 0) throw codedError('openclaw-npm-install-nonzero-exit', npmInstall.exitCode, classifyProcessOutput(`${npmInstall.stdout}\n${npmInstall.stderr}`));

  const packageRoot = join(dirs.openclaw, 'node_modules', 'openclaw');
  const packageJsonPath = join(packageRoot, 'package.json');
  const installedPackage = JSON.parse(await readFile(packageJsonPath, 'utf8'));
  if (installedPackage.name !== manifest.openclaw.package || installedPackage.version !== manifest.openclaw.version) {
    throw codedError('installed-openclaw-version-mismatch');
  }
  const cli = join(packageRoot, 'openclaw.mjs');
  await access(cli).catch(() => { throw codedError('openclaw-cli-missing'); });
  const version = await runBoundedCommand(node, [cli, '--version'], {
    cwd: dirs.openclaw, env: environment, timeoutMs: 15_000, spawnImpl: dependencies.spawn,
  });
  if (version.exitCode !== 0 || version.timedOut) throw codedError('openclaw-cli-version-check-failed', version.exitCode, classifyProcessOutput(`${version.stdout}\n${version.stderr}`));
  if (!version.stdout.includes(manifest.openclaw.version)) throw codedError('openclaw-cli-reported-version-mismatch');
  const packageLock = await readFile(join(dirs.openclaw, 'package-lock.json')).catch(() => null);
  let resolvedPackages = [];
  if (packageLock) {
    const lock = JSON.parse(packageLock.toString('utf8'));
    resolvedPackages = Object.entries(lock.packages ?? {})
      .filter(([path, item]) => path.includes('node_modules/') && item?.version)
      .map(([path, item]) => ({
        name: path.slice(path.lastIndexOf('node_modules/') + 'node_modules/'.length),
        version: item.version,
        integrity: item.integrity ?? null,
      }))
      .sort((left, right) => left.name.localeCompare(right.name) || left.version.localeCompare(right.version));
  }
  return {
    cli,
    packageRoot,
    inventory: {
      package: installedPackage.name,
      version: installedPackage.version,
      cliReportedVersion: version.stdout.trim().slice(0, 128),
      tarballSha512: manifest.upstreamArtifacts.openclawNpmIntegrity,
      packageLockSha256: packageLock ? digest('sha256', packageLock) : null,
      resolvedPackages,
      localInstall: true,
      providerCredentialsCopied: false,
    },
  };
}

async function installPlugin({ node, cli, packageArchive, cwd, environment, dependencies }) {
  const install = await runBoundedCommand(node, [cli, 'plugins', 'install', `npm-pack:${packageArchive}`, '--force'], {
    cwd,
    env: environment,
    timeoutMs: limits.pluginInstallMs,
    spawnImpl: dependencies.spawn,
  });
  if (install.timedOut) throw codedError('openclaw-plugin-install-timeout');
  if (install.errorCode) throw codedError(install.errorCode);
  if (install.exitCode !== 0) throw codedError('openclaw-plugin-install-nonzero-exit', install.exitCode, classifyProcessOutput(`${install.stdout}\n${install.stderr}`));
  const inspection = await runBoundedCommand(node, [cli, 'plugins', 'inspect', 'worldspeaker', '--runtime', '--json'], {
    cwd,
    env: environment,
    timeoutMs: limits.pluginInstallMs,
    spawnImpl: dependencies.spawn,
  });
  if (inspection.timedOut) throw codedError('openclaw-plugin-runtime-inspection-timeout');
  if (inspection.errorCode) throw codedError(inspection.errorCode);
  if (inspection.exitCode !== 0) throw codedError('openclaw-plugin-runtime-inspection-failed', inspection.exitCode, classifyProcessOutput(`${inspection.stdout}\n${inspection.stderr}`));
  return { installPath: 'openclaw-managed-npm-pack', installExitCode: install.exitCode, runtimeInspection: 'passed' };
}

async function configureOpenClaw({ configPath, openclawRoot, manifest, manifestSha256, secret, companionPort, gatewayPort, binding, workspace, dependencies }) {
  const configText = await readFile(configPath, 'utf8').catch(() => '{}');
  let config;
  try {
    config = await parseOpenClawConfig(configText, openclawRoot, dependencies);
  } catch {
    throw codedError('openclaw-config-unreadable-after-plugin-install');
  }
  config.gateway = { ...(config.gateway ?? {}), mode: 'local', bind: 'loopback', port: gatewayPort };
  config.plugins = {
    ...(config.plugins ?? {}),
    allow: ['worldspeaker'],
    entries: { ...(config.plugins?.entries ?? {}), worldspeaker: { ...(config.plugins?.entries?.worldspeaker ?? {}), enabled: true } },
  };
  config.channels = {
    ...(config.channels ?? {}),
    worldspeaker: {
      enabled: true,
      bindHost: '127.0.0.1',
      port: companionPort,
      bearerSecret: secret,
      deploymentId: manifest.deploymentId,
      manifestSha256,
      ...binding,
    },
  };
  config.agents = {
    ...(config.agents ?? {}),
    defaults: { ...(config.agents?.defaults ?? {}), workspace },
  };
  await writeFile(configPath, `${JSON.stringify(config, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
  await chmod(configPath, 0o600);
}

async function writeInitialOpenClawConfig({ configPath, gatewayPort, workspace }) {
  await mkdir(dirname(configPath), { recursive: true, mode: 0o700 });
  const minimal = { gateway: { mode: 'local', bind: 'loopback', port: gatewayPort }, agents: { defaults: { workspace } } };
  await writeFile(configPath, `${JSON.stringify(minimal, null, 2)}\n`, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
}

export async function writeWorldspeakerConfig(configDir, manifest, secret, port, binding) {
  const properties = [
    `endpoint=ws://127.0.0.1:${port}`,
    `bearerSecret=${secret}`,
    `serverId=${binding.serverId}`,
    `worldId=${binding.worldId}`,
    `characterId=${binding.characterId}`,
    `agentId=${binding.agentId}`,
    'acknowledgementTemplate=Worldspeaker is listening, {player}.',
  ].join('\n') + '\n';
  const path = join(configDir, 'worldspeaker.properties');
  await writeFile(path, properties, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
  await chmod(path, 0o600);
  if (manifest.target.selection !== 'selected') throw codedError('deployment-candidate-not-selected');
}

export async function prepareServerProperties(serverDir, excludedPorts, manifest) {
  const port = await getFreeLoopbackPort(excludedPorts);
  const properties = [
    'server-ip=127.0.0.1',
    `server-port=${port}`,
    'online-mode=false',
    'white-list=true',
    'enable-rcon=false',
    'enable-query=false',
    `motd=Isolated ${manifest.deploymentId} clean-install test`,
    'max-players=1',
  ].join('\n') + '\n';
  await writeFile(join(serverDir, 'server.properties'), properties, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
}

export async function inspectRuntime({ manifest, java, node, npm, environment, dependencies = {} }) {
  const [javaResult, nodeResult, npmResult] = await Promise.all([
    runBoundedCommand(java, ['-XshowSettings:properties', '-version'], { cwd: root, env: environment, timeoutMs: 15_000, spawnImpl: dependencies.spawn }),
    runBoundedCommand(node, ['--version'], { cwd: root, env: environment, timeoutMs: 10_000, spawnImpl: dependencies.spawn }),
    runBoundedCommand(npm, ['--version'], { cwd: root, env: environment, timeoutMs: 10_000, spawnImpl: dependencies.spawn }),
  ]);
  const javaOutput = `${javaResult.stdout}\n${javaResult.stderr}`;
  const facts = {
    platform: `${process.platform}/${process.arch}`,
    kernel: os.release(),
    cpu: os.cpus()[0]?.model ?? 'unknown',
    ramBytes: os.totalmem(),
    helperNode: process.versions.node,
    selectedNode: nodeResult.stdout.trim().replace(/^v/, ''),
    npm: npmResult.stdout.trim(),
    javaVendor: javaOutput.match(/^\s*java\.vendor\s*=\s*(.+)$/m)?.[1]?.trim() ?? null,
    javaRuntimeVersion: javaOutput.match(/^\s*java\.runtime\.version\s*=\s*(.+)$/m)?.[1]?.trim() ?? null,
    javaExitCode: javaResult.exitCode,
    nodeExitCode: nodeResult.exitCode,
    npmExitCode: npmResult.exitCode,
  };
  const matches = facts.platform === 'linux/x64' &&
    facts.javaVendor === manifest.target.java.vendor && facts.javaRuntimeVersion === manifest.target.java.runtimeVersion &&
    facts.selectedNode === manifest.openclaw.node && facts.npm === manifest.openclaw.npm &&
    javaResult.exitCode === 0 && nodeResult.exitCode === 0 && npmResult.exitCode === 0;
  return { facts, matches };
}

export function buildIsolatedEnvironment({ openclawHome, openclawState, openclaw, node, npm }) {
  const temp = join(openclawHome, 'tmp');
  const cache = join(openclawHome, 'npm-cache');
  const userConfig = join(openclawHome, 'empty.npmrc');
  const pathEntries = [node, npm]
    .filter((command) => isAbsolute(command) || command.includes(sep))
    .map((command) => dirname(resolve(command)))
    .concat(process.env.PATH ?? '/usr/bin:/bin');
  const env = {
    HOME: openclawHome,
    USERPROFILE: openclawHome,
    PATH: pathEntries.join(sep === '\\' ? ';' : ':'),
    TMPDIR: temp,
    XDG_CONFIG_HOME: join(openclawHome, '.config'),
    OPENCLAW_HOME: openclawHome,
    OPENCLAW_STATE_DIR: openclawState,
    OPENCLAW_CONFIG_PATH: join(openclawState, 'openclaw.json'),
    OPENCLAW_WORKSPACE_DIR: join(openclawState, 'workspace'),
    npm_config_cache: cache,
    npm_config_userconfig: userConfig,
    npm_config_registry: 'https://registry.npmjs.org/',
    OPENCLAW_TELEMETRY: '0',
    OPENCLAW_DISABLE_UPDATE_CHECK: '1',
  };
  if (process.env.SystemRoot) env.SystemRoot = process.env.SystemRoot;
  return env;
}

export async function ensureNewWorkdirOutsideRepo(output, repositoryRoot = root) {
  const parent = dirname(output);
  let realParent;
  try {
    realParent = await realpath(parent);
  } catch (error) {
    throw codedError(workdirPathFailure(error, 'parent'));
  }
  let realRoot;
  try {
    realRoot = await realpath(repositoryRoot);
  } catch {
    throw codedError('repository-root-unavailable');
  }
  const canonicalOutput = resolve(realParent, basename(output));
  const fromRoot = relative(realRoot, canonicalOutput);
  if (fromRoot === '' || (!fromRoot.startsWith(`..${sep}`) && fromRoot !== '..' && !isAbsolute(fromRoot))) {
    throw codedError('workdir-must-be-outside-repository');
  }
  try {
    await lstat(canonicalOutput);
    throw codedError('workdir-already-exists');
  } catch (error) {
    if (error?.code === 'workdir-already-exists') throw error;
    if (error?.code !== 'ENOENT') throw codedError(workdirPathFailure(error, 'path'));
  }
  return canonicalOutput;
}

function workdirPathFailure(error, kind) {
  if (kind === 'parent' && error?.code === 'ENOENT') return 'workdir-parent-must-exist';
  if (error?.code === 'ENOTDIR') return 'workdir-parent-not-directory';
  if (error?.code === 'EACCES' || error?.code === 'EPERM') return 'workdir-path-not-accessible';
  if (error?.code === 'EINVAL' || error?.code === 'ENAMETOOLONG') return 'workdir-path-invalid';
  return kind === 'parent' ? 'workdir-parent-unavailable' : 'workdir-path-unavailable';
}

export function validateManifestPins(manifest) {
  if (manifest?.schemaVersion !== 1 || manifest.deploymentId !== 'mc1211-fabric-r01' || manifest.target?.minecraft !== '1.21.1' ||
      manifest.target?.selection !== 'selected' || manifest.qualification?.state !== 'unqualified' ||
      manifest.availableFeatures?.length !== 0 || manifest.target?.serverOnly !== true || !manifest.upstreamArtifacts?.minecraftServerSha1 ||
      !manifest.installationTools?.fabricInstaller || !manifest.runtimeDependencies?.fabricLoader || !manifest.runtimeDependencies?.fabricApi ||
      !manifest.companion?.package || !manifest.companion?.version || !manifest.openclaw?.version || !manifest.upstreamArtifacts?.openclawNpmIntegrity) {
    throw codedError('deployment-manifest-missing-required-candidate-pins');
  }
}

async function fetchJson(url, allowedHosts, fetchImpl = fetch) {
  const bytes = await downloadBytes(url, { allowedHosts, maxBytes: 8 * 1024 * 1024, timeoutMs: limits.downloadMs, fetchImpl });
  try { return JSON.parse(bytes.toString('utf8')); } catch { throw codedError('pinned-metadata-json-invalid'); }
}

async function installFabricMetadataLibraries(manifest, metadata, serverDir) {
  if (metadata.loader?.version !== manifest.runtimeDependencies.fabricLoader) throw codedError('fabric-loader-version-metadata-mismatch');
  const expected = [...(metadata.launcherMeta?.libraries?.common ?? []), ...(metadata.launcherMeta?.libraries?.server ?? [])];
  const artifacts = [];
  for (const library of expected) {
    if (!library.name || !library.sha256 || !Number.isSafeInteger(library.size)) throw codedError('fabric-loader-library-metadata-invalid');
    const coordinate = library.name.split(':');
    if (coordinate.length < 3) throw codedError('fabric-loader-library-coordinate-invalid');
    const [group, artifact, version, classifier] = coordinate;
    const base = `${group.replaceAll('.', '/')}/${artifact}/${version}/${artifact}-${version}${classifier ? `-${classifier}` : ''}.jar`;
    const path = join(serverDir, 'libraries', ...base.split('/'));
    const bytes = await readFile(path).catch(() => null);
    if (!bytes || bytes.byteLength !== library.size || digest('sha256', bytes) !== library.sha256) {
      throw codedError('fabric-loader-library-checksum-mismatch');
    }
    artifacts.push(artifactRecord(`libraries/${base}`, path, bytes));
  }
  return artifacts;
}

async function collectJarInventory(directory) {
  const entries = await readdirRecursive(directory);
  const result = [];
  for (const relativePath of entries.filter((entry) => entry.endsWith('.jar'))) {
    const path = join(directory, relativePath);
    result.push(artifactRecord(relativePath, path, await readFile(path)));
  }
  return result;
}

async function readdirRecursive(directory, prefix = '') {
  const { readdir } = await import('node:fs/promises');
  const entries = await readdir(join(directory, prefix), { withFileTypes: true });
  const result = [];
  for (const entry of entries) {
    const relativePath = join(prefix, entry.name);
    if (entry.isSymbolicLink()) throw codedError('unexpected-symlink-in-clean-install');
    if (entry.isDirectory()) result.push(...await readdirRecursive(directory, relativePath));
    else if (entry.isFile()) result.push(relativePath);
  }
  return result;
}

export function startManagedProcess(command, args, { cwd, env, spawnImpl = spawn }) {
  const child = spawnImpl(command, args, { cwd, env, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
  child.exitCode = null;
  child.signalCode = null;
  child.output = '';
  child.spawnError = false;
  child.once('error', () => {
    child.spawnError = true;
    child.exitCode = -1;
  });
  child.closePromise = new Promise((resolveClose) => child.once('close', (code, signal) => {
    child.exitCode = code;
    child.signalCode = signal;
    resolveClose({ code, signal });
  }));
  const append = (chunk) => {
    child.output = (child.output + chunk.toString('utf8')).slice(-256 * 1024);
  };
  child.stdout.on('data', append);
  child.stderr.on('data', append);
  return child;
}

async function waitForPort(child, port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline && child.exitCode === null) {
    if (await isLoopbackPortOpen(port)) return true;
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 200));
  }
  return false;
}

export async function waitForMarker(child, marker, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline && child.exitCode === null) {
    if (child.output.includes(marker)) return true;
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 100));
  }
  return child.output.includes(marker);
}

async function isLoopbackPortOpen(port) {
  return new Promise((resolveOpen) => {
    const socket = connect({ host: '127.0.0.1', port });
    const finish = (open) => { socket.destroy(); resolveOpen(open); };
    socket.setTimeout(500);
    socket.once('connect', () => finish(true));
    socket.once('error', () => finish(false));
    socket.once('timeout', () => finish(false));
  });
}

export async function getFreeLoopbackPort(excludedPorts = []) {
  for (let attempt = 0; attempt < 32; attempt += 1) {
    const port = await getOneFreeLoopbackPort();
    if (!excludedPorts.includes(port)) return port;
  }
  throw codedError('loopback-port-allocation-failed');
}

async function getOneFreeLoopbackPort() {
  return new Promise((resolvePort, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      if (!address || typeof address === 'string') {
        server.close(() => reject(codedError('loopback-port-allocation-failed')));
        return;
      }
      const port = address.port;
      server.close((error) => error ? reject(error) : resolvePort(port));
    });
  });
}

export async function stopManagedProcess(child, timeouts, sendStop) {
  if (child.exitCode !== null) return { stopped: true, mode: 'already-exited' };
  if (sendStop && child.stdin.writable) {
    child.stdin.write('/stop\n');
    const graceful = await Promise.race([
      child.closePromise.then(() => true),
      new Promise((resolveDelay) => setTimeout(() => resolveDelay(false), timeouts.gracefulStopMs)),
    ]);
    if (graceful) return { stopped: true, mode: 'console-stop' };
  }
  child.kill('SIGTERM');
  const terminated = await Promise.race([
    child.closePromise.then(() => true),
    new Promise((resolveDelay) => setTimeout(() => resolveDelay(false), timeouts.forcedStopMs)),
  ]);
  if (terminated) return { stopped: true, mode: 'sigterm' };
  child.kill('SIGKILL');
  const killed = await Promise.race([
    child.closePromise.then(() => true),
    new Promise((resolveDelay) => setTimeout(() => resolveDelay(false), timeouts.forcedStopMs)),
  ]);
  return { stopped: killed, mode: 'sigkill' };
}

async function parseOpenClawConfig(text, packageRoot, dependencies) {
  if (dependencies.parseOpenClawConfig) return dependencies.parseOpenClawConfig(text);
  const require = (await import('node:module')).createRequire(pathToFileURL(join(packageRoot, 'package.json')));
  const json5 = require('json5');
  return json5.parse(text);
}

async function saveRecord(path, record) {
  const temporary = `${path}.${process.pid}.tmp`;
  await writeFile(temporary, `${JSON.stringify(record, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
  await chmod(temporary, 0o600);
  const { rename } = await import('node:fs/promises');
  await rename(temporary, path);
}

function digest(algorithm, data, encoding = 'hex') {
  return createHash(algorithm).update(data).digest(encoding);
}

function artifactRecord(name, path, bytes) {
  return { name, bytes: bytes?.byteLength ?? null, sha256: bytes ? digest('sha256', bytes) : null, path };
}

export function phaseForError(record) {
  const ordered = [
    'contract', 'candidateArtifacts', 'runtimePreflight', 'pinnedDownloads', 'fabricInstall', 'openclawInstall',
    'pluginInstall', 'pluginConfiguration', 'gatewayStartup', 'serverStartup', 'authenticatedHandshake',
  ];
  return ordered.find((phase) => record.phases[phase]?.state === 'skipped') ?? 'setup';
}

function markDownstreamSkipped(record, failedPhase) {
  const ordered = [
    'contract', 'candidateArtifacts', 'runtimePreflight', 'pinnedDownloads', 'fabricInstall', 'openclawInstall',
    'pluginInstall', 'pluginConfiguration', 'gatewayStartup', 'serverStartup', 'authenticatedHandshake', 'shutdown',
  ];
  const failedIndex = ordered.indexOf(failedPhase);
  for (const phase of ordered.slice(failedIndex + 1)) {
    if (record.phases[phase]?.state === 'skipped' && record.phases[phase].code === 'not-run') {
      setPhase(record, phase, 'skipped', { code: `blocked-by-${failedPhase}` });
    }
  }
}

function classifyProcessOutput(output) {
  const patterns = [
    [/\bws\b.{0,80}(not found|cannot find)|Cannot find package ['"]ws['"]/i, 'runtime-dependency-ws-missing'],
    [/ERR_MODULE_NOT_FOUND|Cannot find package|Cannot find module/i, 'runtime-module-not-found'],
    [/EADDRINUSE|address already in use/i, 'loopback-port-in-use'],
    [/WS-COMPAT-HOST-VERSION-MISMATCH/, 'companion-host-version-mismatch'],
    [/WS-COMPAT-HANDSHAKE-REJECTED/, 'companion-handshake-rejected'],
    [/WS-COMPAT-PROTOCOL/, 'companion-protocol-mismatch'],
    [/WS-COMPAT-CONNECTION-FAILED/, 'companion-connection-failed'],
    [/UnsupportedClassVersionError/, 'java-bytecode-version-mismatch'],
    [/NoClassDefFoundError|ClassNotFoundException/, 'java-runtime-class-missing'],
    [/incompatible mod set|failed to load mod|mod resolution failed/i, 'fabric-mod-resolution-failed'],
    [/EBADENGINE|Unsupported engine/i, 'node-or-npm-engine-mismatch'],
    [/ECONNRESET|ENOTFOUND|EAI_AGAIN|ETIMEDOUT/i, 'network-dependency-unavailable'],
    [/eula\.txt|agree to the EULA|You need to agree to the EULA/i, 'minecraft-eula-not-accepted'],
  ];
  return patterns.find(([pattern]) => pattern.test(output))?.[1];
}

function codedError(code, exitCode, diagnostic) {
  const error = new Error(code);
  error.code = code;
  if (exitCode !== undefined) error.exitCode = exitCode;
  if (diagnostic) error.diagnostic = diagnostic;
  return error;
}

function normalizeFailure(error) {
  const code = typeof error?.code === 'string' && /^[a-z0-9-]+$/.test(error.code) ? error.code : 'clean-install-setup-failed';
  return {
    code,
    exitCode: Number.isInteger(error?.exitCode) ? error.exitCode : null,
    diagnostic: typeof error?.diagnostic === 'string' && /^[a-z0-9-]+$/.test(error.diagnostic) ? error.diagnostic : null,
  };
}

async function main() {
  const options = parseArgs(process.argv.slice(2), root);
  if (options.help) {
    process.stdout.write(`${usage()}\n`);
    return;
  }
  assertEulaAuthorization(options);
  if (!options.workdir) throw new Error('workdir-required');
  const result = await runCleanInstall(options);
  const state = !result.ok || result.record.phases.shutdown?.state === 'failed'
    ? 'INCOMPLETE'
    : result.record.mode === 'staging-only' ? 'STAGED' : 'RUNTIME-ATTEMPT-COMPLETE';
  process.stdout.write(`WS-CLEAN-INSTALL-${state} ${result.recordPath}\n`);
  if (!result.ok || result.record.phases.shutdown?.state === 'failed') process.exitCode = 1;
  if (result.record.failure) process.stderr.write(`WS-CLEAN-INSTALL-FAILURE ${result.record.failure.phase}:${result.record.failure.code}\n`);
  const failed = Object.entries(result.record.phases).filter(([, phase]) => phase.state === 'failed');
  for (const [phase, value] of failed) process.stderr.write(`WS-CLEAN-INSTALL-PHASE-FAILED ${phase}:${value.code}\n`);
  for (const [phase, value] of Object.entries(result.record.phases)) {
    if (value.state === 'skipped') process.stdout.write(`WS-CLEAN-INSTALL-PHASE-SKIPPED ${phase}:${value.code}\n`);
  }
  process.stdout.write('Qualification is not claimed. Run-record contains no generated handshake secret or provider credentials.\n');
}

function usage() {
  return [
    'Usage: node tools/compat/clean-install.mjs --workdir <new-absolute-path> [options]',
    'Options: --deployment <manifest> --distribution <candidate-dir> --java <executable> --node <executable> --npm <executable>',
    '         --start-server --accept-eula',
    'Default stages verified pinned inputs, Fabric server files, and an isolated OpenClaw/plugin installation; it starts no server.',
    'Starting requires both --start-server and explicit --accept-eula.',
  ].join('\n');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    process.stderr.write(`WS-CLEAN-INSTALL-REFUSED ${normalizeFailure(error).code}\n`);
    process.exitCode = 2;
  });
}
