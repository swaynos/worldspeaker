import { createHash } from 'node:crypto';
import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const manifestBytes = await readFile(resolve(repositoryRoot, 'compatibility/deployment.json'));
const deployment = JSON.parse(manifestBytes.toString('utf8'));
const digest = createHash('sha256').update(manifestBytes).digest('hex');
const packageJson = JSON.parse(await readFile(resolve(repositoryRoot, 'companion/package.json'), 'utf8'));
if (packageJson.version !== deployment.companion.version || packageJson.name !== deployment.companion.package) {
  throw new Error('companion package identity differs from the canonical deployment manifest');
}

const target = resolve(repositoryRoot, 'companion/src/generated/deployment.ts');
await mkdir(dirname(target), { recursive: true });
const values = {
  deploymentId: deployment.deploymentId,
  manifestRevision: deployment.manifestRevision,
  manifestSha256: digest,
  protocol: deployment.companion.protocol,
  minecraftVersion: deployment.target.minecraft,
  fabricLoaderVersion: deployment.runtimeDependencies.fabricLoader,
  fabricApiVersion: deployment.runtimeDependencies.fabricApi,
  javaMajor: deployment.target.java.major,
  javaVendor: deployment.target.java.vendor,
  javaRuntimeVersion: deployment.target.java.runtimeVersion,
  hostVersion: deployment.openclaw.version,
  nodeVersion: deployment.openclaw.node,
  companionVersion: deployment.companion.version,
};
await writeFile(target, `/** Generated from compatibility/deployment.json. */\nexport const DEPLOYMENT = ${JSON.stringify(values, null, 2)} as const;\n`, 'utf8');

// The startup schema and generic loader fixtures carry the manifest digest as a
// generated compatibility fact, not an independently maintained authority.
const startupSchemaPath = resolve(repositoryRoot, 'compatibility/startup-config-v1.schema.json');
const startupSchemaText = await readFile(startupSchemaPath, 'utf8');
const startupSchema = JSON.parse(startupSchemaText);
const previousSchemaDigest = startupSchema.$defs.companion.properties.manifestSha256.const;
if (typeof previousSchemaDigest !== 'string' || !/^[a-f0-9]{64}$/.test(previousSchemaDigest)) {
  throw new Error('startup schema manifest digest is not a generated SHA-256 fact');
}
const previousSchemaFact = `"manifestSha256": { "const": "${previousSchemaDigest}" }`;
if (!startupSchemaText.includes(previousSchemaFact)) throw new Error('startup schema digest fact has an unknown source shape');
await writeFile(startupSchemaPath, startupSchemaText.replace(previousSchemaFact,
  `"manifestSha256": { "const": "${digest}" }`), 'utf8');
const fixturesPath = resolve(repositoryRoot, 'compatibility/startup-config-v1-fixtures.json');
const fixturesText = await readFile(fixturesPath, 'utf8');
const fixtures = JSON.parse(fixturesText);
const fixtureDigests = new Set((fixtures.valid ?? []).map((fixture) => fixture.bundle?.companion?.manifestSha256)
  .filter((value) => typeof value === 'string'));
if (fixtureDigests.size !== 1 || !/^[a-f0-9]{64}$/.test([...fixtureDigests][0])) {
  throw new Error('generic startup fixture manifest digests are not coherent');
}
const previousFixtureDigest = [...fixtureDigests][0];
const fixtureFact = `"manifestSha256": "${previousFixtureDigest}"`;
if (!fixturesText.includes(fixtureFact)) throw new Error('startup fixture digest fact has an unknown source shape');
await writeFile(fixturesPath, fixturesText.replaceAll(fixtureFact, `"manifestSha256": "${digest}"`), 'utf8');
await copyFile(resolve(repositoryRoot, 'LICENSE'), resolve(repositoryRoot, 'companion/LICENSE'));
