import assert from 'node:assert/strict';
import test from 'node:test';
import {
  exactFloat32SumAtMost,
  exactFloat32SumIsPositive,
  exactFloat32SumIsZero,
} from '../exact-float32-sum.js';
import { normalizeFloat32WireNumber } from '../protocol-text.js';

function normalized(token: string): number {
  const value = normalizeFloat32WireNumber(Number(token));
  if (value === null) throw new Error(`expected canonical float32 token: ${token}`);
  return value;
}

test('exact float32 damage sums distinguish a minimum subnormal at five and smaller limits', () => {
  const minimumSubnormal = normalized('1.4E-45');
  const largestFloatBelowFive = normalized('4.9999995');

  assert.equal(exactFloat32SumAtMost(5, 0, 5), true);
  assert.equal(exactFloat32SumAtMost(5, minimumSubnormal, 5), false);
  assert.equal(exactFloat32SumAtMost(largestFloatBelowFive, minimumSubnormal, 5), true);
  assert.equal(exactFloat32SumAtMost(3, minimumSubnormal, 3), false,
    'a positive minimum subnormal exceeds the exact attempted-damage limit of three');
  assert.equal(exactFloat32SumAtMost(3, 0, 3), true);
});

test('exact float32 damage disposition distinguishes signed zero from positive loss', () => {
  const negativeZero = normalized('-0.0');
  const positiveZero = normalized('0');
  const minimumSubnormal = normalized('1.4E-45');

  assert.equal(Object.is(negativeZero, -0), true);
  assert.equal(exactFloat32SumIsZero(negativeZero, positiveZero), true);
  assert.equal(exactFloat32SumIsPositive(negativeZero, positiveZero), false);
  assert.equal(exactFloat32SumIsZero(negativeZero, minimumSubnormal), false);
  assert.equal(exactFloat32SumIsPositive(negativeZero, minimumSubnormal), true);
});

test('exact float32 sum arithmetic rejects invalid or out-of-contract limits', () => {
  assert.equal(exactFloat32SumAtMost(Number.NaN, 0, 5), false);
  assert.equal(exactFloat32SumAtMost(0, 0, Number.POSITIVE_INFINITY), false);
  assert.equal(exactFloat32SumAtMost(0, 0, 5.000000000000001), false);
  assert.equal(exactFloat32SumAtMost(normalized('3.4028235E38'), 0, 5), false,
    'the largest finite float32 is decoded with fixed-width arithmetic');
});
