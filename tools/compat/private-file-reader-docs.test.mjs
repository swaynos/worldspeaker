import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const generatorPath = join(root, 'tools/compat/generate-compatibility-docs.mjs');
const generatedPath = join(root, 'docs/compatibility.md');
const architecturePath = join(root, 'docs/architecture.md');
const decisionsPath = join(root, 'docs/open-decisions.md');
const javaPath = join(root, 'src/main/java/org/worldspeaker/compat/PrivateFileReader.java');
const helperPath = join(root, 'src/main/resources/org/worldspeaker/compat/private-file-reader.mjs');
const javaTestPath = join(root, 'src/test/java/org/worldspeaker/compat/PrivateFileReaderTest.java');

function normalized(text) {
  return text.replaceAll('\\`', '`').replace(/\s+/g, ' ').trim();
}

test('current requirements match the implemented bounded Node descriptor reader', () => {
  const generated = spawnSync(process.execPath, [generatorPath], { cwd: root, encoding: 'utf8' });
  assert.ifError(generated.error);
  assert.equal(generated.status, 0, generated.stderr);

  const generator = readFileSync(generatorPath, 'utf8');
  const requirements = readFileSync(generatedPath, 'utf8');
  const architecture = readFileSync(architecturePath, 'utf8');
  const decisions = readFileSync(decisionsPath, 'utf8');
  const java = readFileSync(javaPath, 'utf8');
  const helper = readFileSync(helperPath, 'utf8');
  const javaTests = readFileSync(javaTestPath, 'utf8');
  const generatedNode = JSON.parse(readFileSync(join(root, 'compatibility/deployment.json'), 'utf8')).openclaw.node;
  const currentGuidance = [architecture, decisions];

  for (const claim of [
    'OpenClaw and Java startup-helper runtime; exact candidate',
    'Java reads bootstrap, bundle, and credential references through the packaged Node descriptor helper.',
    'The helper is size-bounded and SHA-256 checked against the embedded pin before use;',
    'caps its request at 4,096 bytes, applies a two-second helper-exchange deadline',
    'checks file and directory metadata against the opened descriptor before and after bounded reads',
    'private configuration loading fails closed.',
    'Startup does not download Node or perform a global installation;',
    'This exact Node pin is also the OpenClaw host runtime candidate.',
    'These checks rely on the service-account trust boundary and do not isolate against a hostile process with the same service UID.',
    'These source/offline checks do not supply the required pinned-Linux T4 runtime matrix or qualify deployment.',
    'the CHAR-02 privacy gate, and runtime ability testing remain **BLOCKED**.',
    'a FIFO or race regression is not evidence that any of those gates passed.',
  ]) {
    assert.ok(normalized(generator).includes(normalized(claim)), `generator is missing claim: ${claim}`);
    assert.ok(normalized(requirements).includes(normalized(claim)), `generated guide is missing claim: ${claim}`);
  }

  const templateNodeRequirement = 'Java startup requires the actual Node.js `${deployment.openclaw.node}` executable to be reachable from the trusted service-account `PATH`.';
  const outputNodeRequirement = `Java startup requires the actual Node.js \`${generatedNode}\` executable to be reachable from the trusted service-account \`PATH\`.`;
  assert.ok(normalized(generator).includes(normalized(templateNodeRequirement)));
  assert.ok(normalized(requirements).includes(normalized(outputNodeRequirement)));
  const currentNodeRequirement = `actual Node.js \`${generatedNode}\` from the trusted service-account \`PATH\``;
  for (const [name, document] of currentGuidance.entries()) {
    assert.ok(normalized(document).includes(normalized(currentNodeRequirement)), `current guidance ${name} must state the exact startup Node requirement`);
    assert.ok(normalized(document).includes(normalized('These checks rely on the service-account trust boundary and do not isolate against a hostile process with the same service UID.')));
    assert.match(normalized(document), /O_NONBLOCK.*O_NOFOLLOW/);
    assert.match(normalized(document), /pinned-Linux T4|Pinned-Linux T4/);
    assert.match(normalized(document), /CHAR-02.*publication gate|CHAR-02.*privacy gate/);
    assert.match(normalized(document), /runtime ability testing remain(?:s)? blocked/i);
    assert.doesNotMatch(document, /Java loader still lacks a nonblocking descriptor open/i);
    assert.doesNotMatch(document, /does not yet request nonblocking descriptor access/i);
  }
  assert.doesNotMatch(generator, /current Java loader still lacks a nonblocking descriptor open/i);
  assert.doesNotMatch(requirements, /current Java loader still lacks a nonblocking descriptor open/i);
  assert.doesNotMatch(generator, /local-reference race safety is not yet fully closed/i);
  assert.doesNotMatch(requirements, /local-reference race safety is not yet fully closed/i);

  assert.match(java, /static final String HELPER_SHA256 = "([a-f0-9]{64})"/);
  const helperPin = java.match(/static final String HELPER_SHA256 = "([a-f0-9]{64})"/)[1];
  assert.equal(createHash('sha256').update(helper).digest('hex'), helperPin, 'packaged helper bytes must match the Java SHA-256 pin');
  assert.match(java, /MAX_HELPER_BYTES = 16_384/);
  assert.match(java, /MAX_IPC_BYTES = 4_096/);
  assert.match(java, /PROCESS_TIMEOUT_NANOS = Duration\.ofSeconds\(2\)/);
  assert.match(java, /System\.getenv\("PATH"\)/);
  assert.match(java, /if \(!directory\.isAbsolute\(\)\) continue;/);
  assert.match(java, /new ProcessBuilder\(node\.toString\(\), helper\.toString\(\)\)/);
  assert.match(java, /builder\.environment\(\)\.clear\(\)/);
  assert.doesNotMatch(java, /ProcessBuilder\([^;]*(?:npm|npx|corepack|curl|wget)/i);

  assert.match(helper, /const NODE_VERSION = '24\.22\.1'/);
  const helperNodeVersion = helper.match(/const NODE_VERSION = '([^']+)'/)[1];
  assert.equal(helperNodeVersion, generatedNode, 'Java startup helper and deployment must pin the same Node version');
  assert.match(helper, /process\.versions\.node !== NODE_VERSION/);
  assert.match(helper, /BigInt\(process\.getuid\(\)\)/);
  assert.match(helper, /stat\.uid !== serviceUid/);
  assert.match(helper, /fsConstants\.O_RDONLY \| fsConstants\.O_NONBLOCK \| fsConstants\.O_NOFOLLOW/);
  assert.match(helper, /sameIdentity\(descriptorBefore, descriptorAfter\)/);
  assert.match(helper, /sameIdentity\(descriptorBefore, sourcePathAfter\)/);
  assert.match(helper, /readBounded\(source, request\.maximum\)/);

  assert.match(javaTests, /rejectsFileReplacementGrowthAndNewHardLinkAfterDescriptorOpen/);
  assert.match(javaTests, /rejectsReplacementOfAPathAncestorAfterDescriptorOpen/);
  assert.match(javaTests, /fifoReplacementUsesNonblockingDescriptor/);
  assert.match(javaTests, /packagedHelperBytesMatchTheRuntimePinAndAreBounded/);
});
