package org.worldspeaker.runtime;

/** Test-package bridge for clearing the process-wide server-thread marker after cross-package fixtures. */
public final class ThreadBoundaryTestAccess {
    private ThreadBoundaryTestAccess() {}

    public static void clearForTests() {
        ThreadBoundary.clearForTests();
    }
}
