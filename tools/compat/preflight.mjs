import { readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const args = parseArgs(process.argv.slice(2));
const manifest = JSON.parse(await readFile(resolve(root, args.deployment ?? 'compatibility/deployment.json'), 'utf8'));
const results = [];
const java = args.java ?? 'java';
const javaVersion = run(java, ['-version']);
const javaSettings = run(java, ['-XshowSettings:properties', '-version']);
const javaOutput = `${javaVersion.stdout}\n${javaVersion.stderr}`;
const settingsOutput = `${javaSettings.stdout}\n${javaSettings.stderr}`;
const javaVendor = property(settingsOutput, 'java.vendor');
const javaRuntime = property(settingsOutput, 'java.runtime.version');
const javaMajor = property(settingsOutput, 'java.specification.version');

check('java-command', javaVersion.status === 0, javaVersion.status === 0 ? 'java -version ran' : 'java -version failed');
check('java-vendor', javaVendor === manifest.target.java.vendor, `observed ${javaVendor ?? 'unavailable'}; expected ${manifest.target.java.vendor}`);
check('java-build', javaRuntime === manifest.target.java.runtimeVersion,
  `observed ${javaRuntime ?? firstVersion(javaOutput)}; expected ${manifest.target.java.runtimeVersion}`);
check('java-major', javaMajor === String(manifest.target.java.major), `observed ${javaMajor ?? 'unavailable'}; expected ${manifest.target.java.major}`);
const actualPlatform = `${process.platform}/${process.arch}`;
check('qualification-platform', process.platform === 'linux' && process.arch === 'x64',
  `observed ${actualPlatform}; candidate requires Linux/x64`);
check('node-runtime', process.versions.node === manifest.openclaw.node,
  `observed ${process.versions.node}; expected ${manifest.openclaw.node}`);
const npm = run(args.npm ?? 'npm', ['--version']);
const npmVersion = npm.status === 0 ? npm.stdout.trim() : 'unavailable';
check('npm-runtime', npmVersion === manifest.openclaw.npm, `observed ${npmVersion}; expected ${manifest.openclaw.npm}`);

if (args.jar) {
  const jarPath = resolve(args.jar);
  const metadata = run('unzip', ['-p', jarPath, 'fabric.mod.json']);
  if (metadata.status !== 0) check('mod-metadata', false, 'fabric.mod.json could not be read from the supplied archive');
  else {
    try {
      const fabric = JSON.parse(metadata.stdout);
      check('minecraft-metadata', fabric.depends?.minecraft === manifest.target.minecraft,
        `observed ${fabric.depends?.minecraft ?? 'missing'}; expected ${manifest.target.minecraft}`);
      check('loader-metadata', fabric.depends?.fabricloader === manifest.runtimeDependencies.fabricLoader,
        `observed ${fabric.depends?.fabricloader ?? 'missing'}; expected ${manifest.runtimeDependencies.fabricLoader}`);
      check('fabric-api-metadata', fabric.depends?.['fabric-api'] === manifest.runtimeDependencies.fabricApi,
        `observed ${fabric.depends?.['fabric-api'] ?? 'missing'}; expected ${manifest.runtimeDependencies.fabricApi}`);
      check('server-only-metadata', fabric.environment === 'server' && !('client' in (fabric.entrypoints ?? {})),
        'mod must declare a server-only environment without a client entrypoint');
    } catch {
      check('mod-metadata', false, 'fabric.mod.json is malformed');
    }
  }
}

if (args.companion) {
  const companion = JSON.parse(await readFile(resolve(args.companion), 'utf8'));
  check('companion-version', companion.version === manifest.companion.version,
    `observed ${companion.version ?? 'missing'}; expected ${manifest.companion.version}`);
  check('openclaw-peer', companion.peerDependencies?.openclaw === manifest.openclaw.version,
    `observed ${companion.peerDependencies?.openclaw ?? 'missing'}; expected ${manifest.openclaw.version}`);
}

for (const result of results) process.stdout.write(`${result.ok ? 'PASS' : 'FAIL'} ${result.name}: ${result.detail}\n`);
process.stdout.write(`${results.every((result) => result.ok) ? 'WS-PREFLIGHT-PASS' : 'WS-PREFLIGHT-UNAVAILABLE'}; no server or game actions were started.\n`);
if (results.some((result) => !result.ok)) process.exitCode = 1;

function check(name, ok, detail) {
  results.push({ name, ok, detail: sanitize(detail) });
}

function run(command, commandArgs) {
  const result = spawnSync(command, commandArgs, { encoding: 'utf8', timeout: 10_000, maxBuffer: 1024 * 1024 });
  return { status: result.status ?? 1, stdout: result.stdout ?? '', stderr: result.stderr ?? '' };
}

function property(output, key) {
  const match = output.match(new RegExp(`^\\s*${key.replaceAll('.', '\\.')}\\s*=\\s*(.*?)\\s*$`, 'm'));
  return match?.[1];
}

function firstVersion(output) {
  return output.match(/version\s+"([^"]+)"/i)?.[1] ?? 'unavailable';
}

function sanitize(value) {
  return value.replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, 256);
}

function parseArgs(tokens) {
  const values = {};
  for (let index = 0; index < tokens.length; index += 1) {
    const option = tokens[index];
    if (!['--deployment', '--java', '--npm', '--jar', '--companion'].includes(option)) throw new Error(`unknown option: ${option}`);
    const value = tokens[++index];
    if (!value || value.startsWith('--')) throw new Error(`${option} requires a value`);
    values[option.slice(2)] = value;
  }
  return values;
}
