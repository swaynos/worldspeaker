package org.worldspeaker.integration;

import java.util.concurrent.TimeUnit;

/** Narrow cross-package bridge for deterministic test handoffs to the companion I/O owner. */
public final class BoundCompanionClientTestAccess {
    private BoundCompanionClientTestAccess() {}

    public static void awaitOwnerBarrier(BoundCompanionClient client, long timeout, TimeUnit unit) {
        try {
            long timeoutNanos = unit.toNanos(timeout);
            long startedAt = System.nanoTime();
            while (System.nanoTime() - startedAt < timeoutNanos) {
                long remaining = timeoutNanos - (System.nanoTime() - startedAt);
                long attemptNanos = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50));
                if (client.awaitOwnerBarrierForTests(attemptNanos, TimeUnit.NANOSECONDS)) return;
                Thread.yield();
            }
            throw new AssertionError("companion I/O owner did not reach its bounded test barrier");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for companion I/O owner test barrier", interrupted);
        }
    }

    public static void awaitOwnerTermination(BoundCompanionClient client, long timeout, TimeUnit unit) {
        try {
            if (!client.awaitOwnerTerminationForTests(timeout, unit)) {
                throw new AssertionError("companion I/O owner did not terminate within its bounded test cleanup");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for companion I/O owner cleanup", interrupted);
        }
    }

    public static long connectionGenerationForTests(BoundCompanionClient client) {
        return client.connectionGenerationForTests();
    }
}
