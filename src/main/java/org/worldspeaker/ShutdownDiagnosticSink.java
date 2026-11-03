package org.worldspeaker;

import org.worldspeaker.runtime.BoundedIoLane;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One-shot, value-free diagnostics that may arrive after a server lifecycle retires. */
final class ShutdownDiagnosticSink implements AutoCloseable {
    static final String TIMEOUT = "WS-COMPAT-SHUTDOWN-TIMEOUT";
    static final String INCOMPLETE = "WS-COMPAT-SHUTDOWN-INCOMPLETE";
    private static final long DRAIN_MILLIS = BoundedIoLane.SHUTDOWN_TIMEOUT_MILLIS + 1_000;

    private final BoundedIoLane lane = new BoundedIoLane("worldspeaker-shutdown-diagnostic", 1);
    private final Consumer<String> writer;
    private final AtomicBoolean reportClaimed = new AtomicBoolean();
    private final AtomicBoolean drainStarted = new AtomicBoolean();
    private volatile Thread drainThread;

    ShutdownDiagnosticSink(Consumer<String> writer) {
        this.writer = java.util.Objects.requireNonNull(writer, "writer");
    }

    boolean report(String code) {
        if (!TIMEOUT.equals(code) && !INCOMPLETE.equals(code)) return false;
        if (!reportClaimed.compareAndSet(false, true)) return false;
        boolean accepted;
        try {
            accepted = lane.tryExecute(() -> writer.accept(code));
        } finally {
            // Close admission after the one bounded report is queued; accepted work still drains.
            lane.shutdown();
        }
        return accepted;
    }

    /** Keeps this isolated sink alive past the transport's five-second watchdog, then closes it. */
    void beginDrain() {
        if (!drainStarted.compareAndSet(false, true)) return;
        Thread drain = new Thread(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(DRAIN_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                lane.shutdown();
            }
        }, "worldspeaker-shutdown-diagnostic-drain");
        drain.setDaemon(true);
        drainThread = drain;
        drain.start();
    }

    @Override
    public void close() {
        lane.shutdown();
    }

    boolean isClosedForTests() {
        return lane.isClosed();
    }

    boolean drainThreadAliveForTests() {
        Thread thread = drainThread;
        return thread != null && thread.isAlive();
    }

    boolean awaitTerminationForTests(long timeout, TimeUnit unit) throws InterruptedException {
        return lane.awaitTermination(timeout, unit);
    }
}
