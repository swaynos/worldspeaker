import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import test from 'node:test';
import { recordFailureEvidence, runBoundedSubprocess } from './test-subprocess.mjs';

const repository = resolve(new URL('../..', import.meta.url).pathname);
const deployment = JSON.parse(readFileSync(join(repository, 'compatibility/deployment.json'), 'utf8'));
const expectedRuntimeVersion = deployment.target.java.runtimeVersion;
const expectedJavaVendor = deployment.target.java.vendor;
const javaExecutable = process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  : 'java';
const javaVersionResult = spawnSync(javaExecutable, ['-XshowSettings:properties', '-version'], {
  encoding: 'utf8',
  timeout: 15_000,
});
assert.ifError(javaVersionResult.error);
const javaVersionOutput = `${javaVersionResult.stdout ?? ''}\n${javaVersionResult.stderr ?? ''}`;
const currentRuntimeVersion = javaVersionOutput.match(/java\.runtime\.version\s*=\s*([^\s]+)/)?.[1];
assert.ok(currentRuntimeVersion, `could not read the current Java runtime version:\n${javaVersionOutput}`);

function runGradle(args, testLabel) {
  return runBoundedSubprocess('./gradlew', ['--offline', '--no-daemon', '--console=plain', '--max-workers=1', ...args], {
    cwd: repository,
    timeoutMs: 300_000,
    maxBuffer: 16 * 1024 * 1024,
    env: { ...process.env },
    testLabel,
  });
}

function outputOf(result) {
  if (result.evidencePath) {
    return `${readFileSync(join(result.evidencePath, 'stdout.log'), 'utf8')}\n${readFileSync(join(result.evidencePath, 'stderr.log'), 'utf8')}`;
  }
  return `${result.stdout ?? ''}\n${result.stderr ?? ''}`;
}

function evidenceContext(result) {
  if (result.evidencePath) return `private child evidence: ${result.evidencePath}`;
  return result.evidenceCode ? `evidence unavailable (${result.evidenceCode})` : 'no private failure evidence';
}

test('candidate environment check refuses the host Java build exactly', {
  skip: currentRuntimeVersion === expectedRuntimeVersion
    ? 'the current JVM is the pinned candidate build; run this refusal check with a mismatched JDK'
    : false,
}, async () => {
  const result = await runGradle(['verifyCandidateBuildEnvironment'], 'toolchain-guard-runtime-refusal');
  recordFailureEvidence(result);
  const output = outputOf(result);

  assert.ifError(result.error);
  assert.notEqual(result.status, 0, `candidate environment unexpectedly accepted Java ${currentRuntimeVersion}; ${evidenceContext(result)}`);
  assert.ok(/WS-CANDIDATE-JAVA-RUNTIME-MISMATCH/.test(output), `missing fixed Java mismatch marker; ${evidenceContext(result)}`);
  assert.ok(new RegExp(`Gradle JVM is ${currentRuntimeVersion.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`).test(output),
    `missing expected JVM version marker; ${evidenceContext(result)}`);
  assert.ok(new RegExp(`expected ${expectedRuntimeVersion.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} \\(${expectedJavaVendor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\)`).test(output),
    `missing pinned runtime marker; ${evidenceContext(result)}`);
  assert.ok(/WS-CANDIDATE-PLATFORM-MISMATCH: expected Ubuntu 24\.04 Linux x86-64/.test(output),
    `missing fixed platform mismatch marker; ${evidenceContext(result)}`);
});

test('local-development labeling does not override candidate JDK enforcement', {
  skip: currentRuntimeVersion === expectedRuntimeVersion
    ? 'the current JVM is the pinned candidate build; refusal requires a mismatched JDK or platform'
    : false,
}, async () => {
  const result = await runGradle(['-PlocalDevelopment=true', 'verifyCandidateBuildEnvironment'], 'toolchain-guard-labeled-refusal');
  recordFailureEvidence(result);
  const output = outputOf(result);

  assert.ifError(result.error);
  assert.notEqual(result.status, 0, `localDevelopment unexpectedly bypassed the candidate guard; ${evidenceContext(result)}`);
  assert.ok(/WS-CANDIDATE-JAVA-RUNTIME-MISMATCH/.test(output), `missing fixed Java mismatch marker; ${evidenceContext(result)}`);
});

test('candidate assembly refuses before its build and packaging dependencies run', {
  skip: currentRuntimeVersion === expectedRuntimeVersion
    ? 'the current JVM is pinned; candidate assembly refusal requires a mismatched JDK or platform'
    : false,
}, async () => {
  const result = await runGradle(['-PlocalDevelopment=true', 'assembleCandidate'], 'toolchain-guard-assembly-refusal');
  recordFailureEvidence(result);
  const output = outputOf(result);

  assert.ifError(result.error);
  assert.notEqual(result.status, 0, `candidate assembly unexpectedly succeeded on Java ${currentRuntimeVersion}; ${evidenceContext(result)}`);
  assert.ok(/WS-CANDIDATE-JAVA-RUNTIME-MISMATCH/.test(output), `missing fixed Java mismatch marker; ${evidenceContext(result)}`);
  assert.ok(!/> Task :(?:build|packageCompanion|assembleCandidate)(?:\s|$)/.test(output),
    `environment gate ran a build/package task; ${evidenceContext(result)}`);
});

test('mismatched-host tests require an explicit local-development label', {
  skip: currentRuntimeVersion === expectedRuntimeVersion
    ? 'the current JVM is the pinned candidate build, so the local-development label is unnecessary'
    : false,
}, async () => {
  const result = await runGradle(['--rerun-tasks', 'test'], 'toolchain-guard-unlabeled-test-refusal');
  recordFailureEvidence(result);
  const output = outputOf(result);

  assert.ifError(result.error);
  assert.notEqual(result.status, 0, `unlabeled tests unexpectedly ran on Java ${currentRuntimeVersion}; ${evidenceContext(result)}`);
  assert.ok(/WS-LOCAL-DEV-LABEL-REQUIRED/.test(output), `missing fixed local-development label marker; ${evidenceContext(result)}`);
  assert.ok(/-PlocalDevelopment=true/.test(output), `missing local-development instruction; ${evidenceContext(result)}`);
});

test('explicit local-development test run remains available and labeled', async () => {
  const result = await runGradle(['-PlocalDevelopment=true', '--rerun-tasks', 'test'], 'toolchain-guard-labeled-tests');
  recordFailureEvidence(result);
  const output = outputOf(result);

  assert.ifError(result.error);
  assert.equal(result.status, 0, `local development test path failed; ${evidenceContext(result)}`);
  assert.ok(/WS-LOCAL-DEV-ONLY: .* compile\/test tasks only, not a pinned candidate build\./.test(output),
    `missing fixed local-development marker; ${evidenceContext(result)}`);
  assert.ok(/BUILD SUCCESSFUL/.test(output), `missing Gradle success marker; ${evidenceContext(result)}`);
});
