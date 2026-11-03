import type { MonotonicNow } from '../monotonic-clock.js';

export type ManualMonotonicClock = Readonly<{
  now: MonotonicNow;
  advance: (milliseconds: number) => void;
  readonly readCount: number;
}>;

export function createManualMonotonicClock(initialMilliseconds = 0): ManualMonotonicClock {
  if (!Number.isFinite(initialMilliseconds)) throw new Error('initial clock value must be finite');
  let currentMilliseconds = initialMilliseconds;
  let reads = 0;
  return {
    now: () => {
      reads += 1;
      return currentMilliseconds;
    },
    advance: (milliseconds) => {
      if (!Number.isFinite(milliseconds) || milliseconds < 0) throw new Error('clock advance must be finite and nonnegative');
      currentMilliseconds += milliseconds;
    },
    get readCount() {
      return reads;
    },
  };
}
