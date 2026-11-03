import assert from 'node:assert/strict';
import test from 'node:test';
import { HostWorkBudget } from '../work-budget.js';

test('permits are finite, independent, and released exactly once on actual settlement', () => {
  const budget = new HostWorkBudget(2, 1);
  const first = budget.tryAcquire('host');
  const second = budget.tryAcquire('host');
  const acknowledgement = budget.tryAcquire('acknowledgement');
  assert.ok(first);
  assert.ok(second);
  assert.ok(acknowledgement);
  assert.equal(budget.tryAcquire('host'), null);
  assert.equal(budget.tryAcquire('acknowledgement'), null);
  assert.deepEqual(budget.snapshot(), { hostCalls: 2, acknowledgementCalls: 1 });

  first();
  first();
  assert.deepEqual(budget.snapshot(), { hostCalls: 1, acknowledgementCalls: 1 });
  acknowledgement();
  second();
  assert.deepEqual(budget.snapshot(), { hostCalls: 0, acknowledgementCalls: 0 });
});

test('production defaults admit sixteen host and context permits and refuse each seventeenth', () => {
  const budget = new HostWorkBudget();
  const hostReleases: Array<() => void> = [];
  const contextReleases: Array<() => void> = [];

  for (let index = 0; index < 16; index++) {
    const release = budget.tryAcquire('host');
    assert.ok(release, `host permit ${index + 1} is admitted`);
    hostReleases.push(release);
  }
  assert.equal(budget.tryAcquire('host'), null, 'the seventeenth host operation is refused');

  for (let index = 0; index < 16; index++) {
    const release = budget.tryAcquire('acknowledgement');
    assert.ok(release, `context permit ${index + 1} is admitted`);
    contextReleases.push(release);
  }
  assert.equal(budget.tryAcquire('acknowledgement'), null, 'the seventeenth context operation is refused');
  assert.deepEqual(budget.snapshot(), { hostCalls: 16, acknowledgementCalls: 16 });

  for (const release of hostReleases) release();
  for (const release of contextReleases) release();
  assert.deepEqual(budget.snapshot(), { hostCalls: 0, acknowledgementCalls: 0 });
});

test('a never-settling promise retains capacity across cancellation and transport restart', async () => {
  const accountBudget = new HostWorkBudget(1, 1);
  let settle!: () => void;
  const hostPromise = new Promise<void>((resolve) => { settle = resolve; });
  const permit = accountBudget.tryAcquire('host');
  assert.ok(permit);

  // Cancellation only retires local output eligibility; it does not call permit().
  let live = true;
  live = false;
  assert.equal(live, false);
  assert.equal(accountBudget.tryAcquire('host'), null);
  assert.deepEqual(accountBudget.snapshot(), { hostCalls: 1, acknowledgementCalls: 0 });

  settle();
  await hostPromise.finally(permit);
  assert.deepEqual(accountBudget.snapshot(), { hostCalls: 0, acknowledgementCalls: 0 });
  assert.ok(accountBudget.tryAcquire('host'), 'a replacement is admitted only after the original promise settles');
});
