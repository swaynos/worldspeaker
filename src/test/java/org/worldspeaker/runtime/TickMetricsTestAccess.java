package org.worldspeaker.runtime;

import java.util.concurrent.TimeUnit;

/** Test-package bridge for the package-scoped persistence termination barrier. */
public final class TickMetricsTestAccess {
    private TickMetricsTestAccess() {}

    public static boolean awaitPersistence(TickMetrics metrics, long timeout, TimeUnit unit)
            throws InterruptedException {
        return metrics.awaitTerminationForTests(timeout, unit);
    }
}
