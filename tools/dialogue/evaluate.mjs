#!/usr/bin/env node

import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  OPERATIONAL_FORMAT_VERSION,
  initRun,
  recordEntry,
  validateRunFile,
  writeOperationalReport,
  writeReport,
} from './evaluation-core.mjs';

const USAGE = [
  'Usage:',
  '  node tools/dialogue/evaluate.mjs init --out DIR',
  '  node tools/dialogue/evaluate.mjs record --run FILE --entry FILE [--legacy-numeric-v1]',
  '  node tools/dialogue/evaluate.mjs report --run FILE --out DIR',
  '  node tools/dialogue/evaluate.mjs operational-report --source FILE --out DIR',
  '  node tools/dialogue/evaluate.mjs validate --run FILE',
  '',
  'Review records use schema v3 closed-vocabulary codes; arbitrary review prose is rejected.',
  `Operational TickMetrics v${OPERATIONAL_FORMAT_VERSION} chunk streams use a separate numeric-only report and never expand the 64-turn human-evaluation limit.`,
  'The reader also accepts legacy v3/v4 streams; absent version-specific evidence remains unknown.',
  'Legacy schema v1/v2 runs are never reinterpreted or rewritten. Start a fresh v3 run and explicitly migrate only safe legacy numeric snapshots with --legacy-numeric-v1.',
  'Offline only. This tool does not start a game or host, submit prompts, or contact a provider.',
].join('\n');

function parseArgs(tokens) {
  const [command, ...rest] = tokens;
  if (command === '--help' || command === '-h' || command === undefined) return { help: true };
  if (!['init', 'record', 'report', 'operational-report', 'validate'].includes(command)) throw coded('command-unknown');
  const allowed = command === 'init' ? new Set(['--out'])
    : command === 'record' ? new Set(['--run', '--entry', '--legacy-numeric-v1'])
      : command === 'report' ? new Set(['--run', '--out'])
        : command === 'operational-report' ? new Set(['--source', '--out']) : new Set(['--run']);
  const options = Object.create(null);
  for (let index = 0; index < rest.length; index += 1) {
    const flag = rest[index];
    if (!allowed.has(flag)) throw coded('option-unknown');
    if (Object.hasOwn(options, flag)) throw coded('option-duplicate');
    if (flag === '--legacy-numeric-v1') {
      options[flag] = true;
      continue;
    }
    const value = rest[++index];
    if (!value || value.startsWith('--')) throw coded('option-value-required');
    options[flag] = value;
  }
  const expected = command === 'init' ? ['--out']
    : command === 'record' ? ['--run', '--entry']
      : command === 'report' ? ['--run', '--out']
        : command === 'operational-report' ? ['--source', '--out'] : ['--run'];
  if (expected.some((key) => !options[key])) throw coded('option-required');
  return { command, options };
}

async function main() {
  const parsed = parseArgs(process.argv.slice(2));
  if (parsed.help) {
    process.stdout.write(`${USAGE}\n`);
    return;
  }
  const { command, options } = parsed;
  let result;
  if (command === 'init') result = await initRun(options['--out']);
  else if (command === 'record') result = await recordEntry(options['--run'], options['--entry'], {
    legacyNumericV1: options['--legacy-numeric-v1'] === true,
  });
  else if (command === 'report') result = await writeReport(options['--run'], options['--out']);
  else if (command === 'operational-report') result = await writeOperationalReport(options['--source'], options['--out']);
  else result = await validateRunFile(options['--run']);
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}

function coded(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    const code = /^[a-z0-9-]+$/u.test(error?.code ?? '') ? error.code : 'evaluation-failed';
    const hint = code === 'run-lock-busy'
      ? '; another run operation is active; retry when it finishes, or inspect a stale lock after confirming no writer remains'
      : code === 'run-version-conflict'
        ? '; the run changed during this update; inspect the run before retrying'
        : code === 'run-lock-cleanup-failed'
          ? '; inspect the run and lock before retrying; the commit outcome may be unknown'
          : code === 'legacy-run-migration-required'
            ? '; schema v1/v2 run records are never reinterpreted or exported; preserve the old run, initialize a fresh v3 run, re-import authorized numeric evidence, and re-record coded reviews'
          : '';
    process.stderr.write(`WS-DIALOGUE-EVALUATION-FAILED ${code}${hint}\n`);
    process.exitCode = 2;
  });
}
