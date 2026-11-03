import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(resolve(root, 'companion/package.json'));
const Ajv2020 = require('ajv/dist/2020').default;
const addFormats = require('ajv-formats').default;
const args = parseArgs(process.argv.slice(2));
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);

const deploymentPath = resolve(root, args.deployment ?? 'compatibility/deployment.json');
const deployment = JSON.parse(await readFile(deploymentPath, 'utf8'));
const deploymentSchema = JSON.parse(await readFile(resolve(root, 'compatibility/deployment.schema.json'), 'utf8'));
const validateDeployment = ajv.compile(deploymentSchema);
if (!validateDeployment(deployment)) fail('deployment manifest does not satisfy its strict schema', validateDeployment.errors);
if (deployment.qualification.state === 'unqualified' &&
    (deployment.qualification.evidence.length !== 0 || deployment.availableFeatures.length !== 0)) {
  fail('unqualified candidate must remain without evidence claims or available features');
}
if (JSON.stringify(deployment.unavailableAbilities) !== JSON.stringify([
  'worldspeaker.core.observe_player@1',
  'worldspeaker.core.strike_lightning@1',
])) fail('selected game abilities must remain unavailable');

if (args.qualification) {
  const recordPath = resolve(root, args.qualification);
  const schemaPath = resolve(root, 'compatibility/qualification.schema.json');
  let record;
  let schema;
  try {
    record = JSON.parse(await readFile(recordPath, 'utf8'));
    schema = JSON.parse(await readFile(schemaPath, 'utf8'));
  } catch {
    fail('qualification record or schema could not be read as valid JSON; check the file path and JSON syntax');
  }

  let validateRecord;
  try {
    validateRecord = ajv.compile(schema);
  } catch (error) {
    fail('qualification schema could not be prepared for strict validation', [
      { instancePath: '/', message: safeErrorMessage(error) },
    ]);
  }

  try {
    if (!validateRecord(record)) fail('qualification record does not satisfy its strict schema', validateRecord.errors);
  } catch (error) {
    fail('qualification record validation could not be completed; check its fields and registered formats', [
      { instancePath: '/', message: safeErrorMessage(error) },
    ]);
  }
}

process.stdout.write(`WS-CONTRACT-VALID deployment=${deployment.deploymentId} revision=${deployment.manifestRevision} state=${deployment.qualification.state}\n`);

function parseArgs(tokens) {
  const result = {};
  for (let index = 0; index < tokens.length; index += 1) {
    const option = tokens[index];
    if (option === '--deployment' || option === '--qualification') {
      const value = tokens[++index];
      if (!value || value.startsWith('--')) throw new Error(`${option} requires a path`);
      result[option.slice(2)] = value;
    } else throw new Error(`unknown option: ${option}`);
  }
  return result;
}

function fail(message, errors = undefined) {
  process.stderr.write(`WS-CONTRACT-INVALID: ${message}\n`);
  if (errors) for (const error of errors) process.stderr.write(`  ${error.instancePath || '/'} ${error.message}\n`);
  process.exit(1);
}

function safeErrorMessage(error) {
  return error instanceof Error ? error.message : 'unknown validation error';
}
