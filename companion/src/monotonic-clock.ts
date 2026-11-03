import { performance } from 'node:perf_hooks';

export type MonotonicNow = () => number;

export const systemMonotonicNow: MonotonicNow = () => performance.now();
