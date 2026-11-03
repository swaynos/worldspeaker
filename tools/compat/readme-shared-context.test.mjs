import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const readme = readFileSync(resolve(root, 'README.md'), 'utf8');
const playerGuide = readFileSync(resolve(root, 'docs/player-compatibility.md'), 'utf8');
const guidePrivacyHeading = '## Conversation context and privacy';
const guidePrivacyStart = playerGuide.indexOf(guidePrivacyHeading);
const guideNextSection = playerGuide.indexOf('\n## ', guidePrivacyStart + guidePrivacyHeading.length);
const guidePrivacySection = guidePrivacyStart < 0
  ? ''
  : playerGuide.slice(guidePrivacyStart, guideNextSection < 0 ? undefined : guideNextSection);

test('README states the shared-context limits and links to current player guidance', () => {
  assert.match(readme, /`\/worldspeaker ask <text>` keeps its input out of public\s+chat/);
  assert.match(readme, /command input, local acknowledgements, and requester-targeted answers\s+enter the same shared character\/game context/);
  assert.match(readme, /not confidential between\s+players/);
  assert.match(readme, /Natural-address and recognized follow-up input remains its original\s+public Minecraft chat line, visible to other players/);
  assert.match(readme, /Reply targeting is not a\s+privacy guarantee/);
  assert.match(readme, /Private administration is intended to stay separate and is\s+not intentionally copied into gameplay/);
  assert.match(readme, /actual OpenClaw\s+workspace\/bootstrap\/memory isolation is unverified/);
  assert.match(readme, /stop deployment until\s+actual-host evidence verifies it/);
  assert.match(readme, /\[conversation context and privacy\s+section of the player guide\]\(docs\/player-compatibility\.md#conversation-context-and-privacy\)/);
  assert.doesNotMatch(readme, /docs\/r01\/(?:qa|build)\/CONV-04\.md/);

  assert.ok(guidePrivacySection, 'the linked guide privacy heading must exist');
  assert.match(guidePrivacySection, /command input, local acknowledgements, and requester-targeted answers/);
  assert.match(guidePrivacySection, /not confidential between players/);
  assert.match(guidePrivacySection, /original public\s+Minecraft chat line/);
  assert.match(guidePrivacySection, /requesting player's current connection does not make the conversation\s+private/);
  assert.match(guidePrivacySection, /not intentionally copied into gameplay/);
  assert.match(guidePrivacySection, /workspace, bootstrap, and memory from private\s+administration is unverified/);
  assert.match(guidePrivacySection, /Stop before\s+deployment until actual-host evidence verifies\s+that isolation/);
});
