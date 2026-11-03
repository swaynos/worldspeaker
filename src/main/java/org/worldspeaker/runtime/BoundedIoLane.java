package org.worldspeaker.runtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** A one-owner, finite queue for blocking I/O. Saturation rejects; it never runs on the caller. */
public final class BoundedIoLane implements AutoCloseable {
    public static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean controlSubmitted = new AtomicBoolean();
    private final AtomicReference<Thread> ownerThread = new AtomicReference<>();
    private final AtomicReference<ShutdownStatus> shutdownStatus = new AtomicReference<>(ShutdownStatus.OPEN);
    private final AtomicBoolean cleanupCompleted = new AtomicBoolean();
    private final AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();
    private final ReentrantLock submissionLock = new ReentrantLock();
    private final Object shutdownMonitor = new Object();
    private final int waitingCapacity;
    private final int reservedControlSlots;
    private final String threadName;
    private final long shutdownTimeoutNanos;
    private final LongSupplier monotonicClock;
    private volatile Runnable shutdownFailureAction;
    private volatile Thread shutdownWatchdog;

    public BoundedIoLane(String threadName, int waitingCapacity) {
        this(threadName, waitingCapacity, true, 0);
    }

    /** Non-daemon owners are useful for finite final evidence writes that must survive JVM shutdown. */
    public BoundedIoLane(String threadName, int waitingCapacity, boolean daemon) {
        this(threadName, waitingCapacity, daemon, 0);
    }

    /** Reserves queue slots for shutdown/control work without weakening normal-work rejection. */
    public BoundedIoLane(String threadName, int waitingCapacity, boolean daemon, int reservedControlSlots) {
        this(threadName, waitingCapacity, daemon, reservedControlSlots,
                SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS, System::nanoTime);
    }

    /**
     * Configurable monotonic deadline for deterministic lifecycle validation. Production callers
     * should use the constructors with the five-second default.
     */
    public BoundedIoLane(String threadName, int waitingCapacity, boolean daemon, int reservedControlSlots,
                         long shutdownTimeout, TimeUnit shutdownTimeoutUnit, LongSupplier monotonicClock) {
        if (threadName == null || threadName.isBlank() || waitingCapacity < 1) {
            throw new IllegalArgumentException("lane name and positive queue capacity are required");
        }
        if (reservedControlSlots < 0 || reservedControlSlots >= waitingCapacity) {
            throw new IllegalArgumentException("reserved control capacity must be smaller than the waiting capacity");
        }
        if (shutdownTimeout <= 0 || shutdownTimeoutUnit == null || monotonicClock == null) {
            throw new IllegalArgumentException("a positive shutdown deadline and monotonic clock are required");
        }
        this.waitingCapacity = waitingCapacity;
        this.reservedControlSlots = reservedControlSlots;
        this.threadName = threadName;
        this.shutdownTimeoutNanos = shutdownTimeoutUnit.toNanos(shutdownTimeout);
        if (shutdownTimeoutNanos <= 0) throw new IllegalArgumentException("shutdown deadline is too small");
        this.monotonicClock = monotonicClock;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(() -> {
                ownerThread.set(Thread.currentThread());
                runnable.run();
            }, threadName);
            thread.setDaemon(daemon);
            return thread;
        };
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(waitingCapacity), factory, new ThreadPoolExecutor.AbortPolicy()) {
            @Override
            protected void terminated() {
                signalShutdownMonitor();
            }
        };
    }

    /** Returns false on close, contention, or capacity. Caller-runs is intentionally impossible. */
    public boolean tryExecute(Runnable operation) {
        return tryExecuteWithResult(operation) == SubmissionResult.ACCEPTED;
    }

    /** Uses one of the explicitly reserved slots for finite shutdown/control actions. */
    public boolean tryExecuteControl(Runnable operation) {
        return tryExecuteControlWithResult(operation) == SubmissionResult.ACCEPTED;
    }

    /**
     * Nonblocking submission with a reason suitable for distinguishing transient lock
     * contention from actual bounded-queue saturation.
     */
    public SubmissionResult tryExecuteWithResult(Runnable operation) {
        return submit(operation, false);
    }

    /** Detailed counterpart to {@link #tryExecuteControl(Runnable)}. */
    public SubmissionResult tryExecuteControlWithResult(Runnable operation) {
        return submit(operation, true);
    }

    private SubmissionResult submit(Runnable operation, boolean control) {
        if (operation == null) return SubmissionResult.INVALID;
        if (closed.get()) return SubmissionResult.CLOSED;
        boolean reserved = control && reservedControlSlots > 0 && controlSubmitted.compareAndSet(false, true);
        if (control && reservedControlSlots > 0 && !reserved) return SubmissionResult.SATURATED;
        if (!submissionLock.tryLock()) {
            if (reserved) controlSubmitted.set(false);
            return SubmissionResult.CONTENDED;
        }
        try {
            if (closed.get()) {
                if (reserved) controlSubmitted.set(false);
                return SubmissionResult.CLOSED;
            }
            if (!control && executor.getQueue().size() >= waitingCapacity - reservedControlSlots) {
                if (reserved) controlSubmitted.set(false);
                return SubmissionResult.SATURATED;
            }
            SubmissionResult result = enqueueReserved(operation);
            if (result != SubmissionResult.ACCEPTED && reserved) controlSubmitted.set(false);
            return result;
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            if (reserved) controlSubmitted.set(false);
            return closed.get() || executor.isShutdown()
                    ? SubmissionResult.CLOSED
                    : SubmissionResult.SATURATED;
        } finally {
            submissionLock.unlock();
        }
    }

    private SubmissionResult enqueueReserved(Runnable operation) {
        try {
            executor.execute(() -> {
                ThreadBoundary.assertNotServerThread();
                operation.run();
            });
            return SubmissionResult.ACCEPTED;
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            return closed.get() || executor.isShutdown()
                    ? SubmissionResult.CLOSED
                    : SubmissionResult.SATURATED;
        }
    }

    public int queuedTasks() {
        return executor.getQueue().size();
    }

    public int queueCapacity() {
        return waitingCapacity - reservedControlSlots;
    }

    public boolean isOwnerThread() {
        return ownerThread.get() == Thread.currentThread();
    }

    public void assertOwnerThread() {
        if (!isOwnerThread()) throw new IllegalStateException("I/O boundary called outside its owning lane");
        ThreadBoundary.assertNotServerThread();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public ShutdownStatus shutdownStatus() {
        return shutdownStatus.get();
    }

    /**
     * Closes admission immediately, then drains already accepted work off-caller. At the deadline,
     * an independent daemon interrupts the owner and reports unsuccessful termination. Java cannot
     * forcibly stop an operation that ignores interruption.
     */
    public void shutdown() {
        beginShutdown(null, null);
    }

    /**
     * Closes admission immediately and gives {@code ownerCleanup} priority over queued work. The
     * cleanup and executor must both terminate before the deadline to be reported as successful.
     * A timeout/failure callback runs on the independent shutdown watchdog, never on the caller.
     *
     * @return false when shutdown had already been initiated
     */
    public boolean shutdownWithControl(Runnable ownerCleanup, Runnable onUnsuccessfulTermination) {
        if (ownerCleanup == null) throw new IllegalArgumentException("owner cleanup is required");
        return beginShutdown(ownerCleanup, onUnsuccessfulTermination);
    }

    private boolean beginShutdown(Runnable ownerCleanup, Runnable onUnsuccessfulTermination) {
        if (!closed.compareAndSet(false, true)) return false;
        shutdownFailureAction = onUnsuccessfulTermination;
        cleanupCompleted.set(ownerCleanup == null);
        shutdownStatus.set(ShutdownStatus.CLOSING);
        long deadline = saturatingAdd(monotonicClock.getAsLong(), shutdownTimeoutNanos);
        if (ownerCleanup == null) {
            executor.shutdown();
            startShutdownWatchdog(() -> watchShutdown(deadline));
            return true;
        }
        Thread coordinator = new Thread(
                () -> coordinateShutdown(ownerCleanup, deadline),
                threadName + "-shutdown-watchdog");
        startShutdownWatchdog(coordinator);
        return true;
    }

    private void startShutdownWatchdog(Runnable action) {
        Thread watcher = new Thread(action, threadName + "-shutdown-watchdog");
        watcher.setDaemon(true);
        shutdownWatchdog = watcher;
        watcher.start();
    }

    private void coordinateShutdown(Runnable ownerCleanup, long deadline) {
        boolean locked = false;
        try {
            while (!(locked = submissionLock.tryLock())) {
                long remaining = deadline - monotonicClock.getAsLong();
                if (remaining <= 0) {
                    finishShutdown(ShutdownStatus.TIMED_OUT);
                    executor.shutdownNow();
                    Thread runningOwner = ownerThread.get();
                    if (runningOwner != null) runningOwner.interrupt();
                    runFailureAction();
                    return;
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
            }
            try {
                if (ownerCleanup != null) {
                    // Shutdown cleanup must not sit behind maintenance, sends, or reconnect work.
                    executor.getQueue().clear();
                    executor.execute(() -> {
                        try {
                            ThreadBoundary.assertNotServerThread();
                            ownerCleanup.run();
                        } catch (Throwable failure) {
                            cleanupFailure.set(failure);
                        } finally {
                            cleanupCompleted.set(true);
                            signalShutdownMonitor();
                        }
                    });
                }
                executor.shutdown();
            } finally {
                submissionLock.unlock();
                locked = false;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cleanupFailure.set(interrupted);
            finishShutdown(ShutdownStatus.FAILED);
            executor.shutdownNow();
            runFailureAction();
            return;
        } catch (RuntimeException failure) {
            cleanupFailure.set(failure);
            cleanupCompleted.set(ownerCleanup == null);
            executor.shutdownNow();
            finishShutdown(ShutdownStatus.FAILED);
            runFailureAction();
            return;
        } finally {
            if (locked) submissionLock.unlock();
        }

        watchShutdown(deadline);
    }

    private void watchShutdown(long deadline) {
        while (true) {
            if (executor.isTerminated()) {
                ShutdownStatus result = cleanupCompleted.get() && cleanupFailure.get() == null
                        ? ShutdownStatus.TERMINATED
                        : ShutdownStatus.FAILED;
                finishShutdown(result);
                if (result != ShutdownStatus.TERMINATED) runFailureAction();
                return;
            }

            long remaining = deadline - monotonicClock.getAsLong();
            if (remaining <= 0) {
                if (executor.isTerminated()) continue;
                finishShutdown(ShutdownStatus.TIMED_OUT);
                executor.shutdownNow();
                Thread runningOwner = ownerThread.get();
                if (runningOwner != null) runningOwner.interrupt();
                runFailureAction();
                return;
            }

            synchronized (shutdownMonitor) {
                if (executor.isTerminated() || deadline - monotonicClock.getAsLong() <= 0) continue;
                try {
                    // Recheck termination promptly after the executor's terminated hook.
                    TimeUnit.NANOSECONDS.timedWait(shutdownMonitor,
                            Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    finishShutdown(ShutdownStatus.FAILED);
                    executor.shutdownNow();
                    runFailureAction();
                    return;
                }
            }
        }
    }

    private void finishShutdown(ShutdownStatus result) {
        shutdownStatus.compareAndSet(ShutdownStatus.CLOSING, result);
    }

    private void runFailureAction() {
        Runnable action = shutdownFailureAction;
        if (action == null) return;
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // A diagnostic/timeout observer must not strand the independent watchdog.
        }
    }

    private void signalShutdownMonitor() {
        synchronized (shutdownMonitor) {
            shutdownMonitor.notifyAll();
        }
    }

    private static long saturatingAdd(long left, long right) {
        long result = left + right;
        return result < left ? Long.MAX_VALUE : result;
    }

    /** Test hook: wake the watchdog after advancing an injected monotonic clock. */
    void signalShutdownClockForTests() {
        signalShutdownMonitor();
    }

    boolean shutdownWatchdogAliveForTests() {
        Thread watcher = shutdownWatchdog;
        return watcher != null && watcher.isAlive();
    }

    /** Off-tick test/owner utility. Production shutdown does not wait here. */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        if (timeout < 0 || unit == null) throw new IllegalArgumentException("a non-negative timeout is required");
        long remaining = unit.toNanos(timeout);
        long deadline = saturatingAdd(System.nanoTime(), remaining);
        while (!executor.isShutdown() && remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(1)));
            remaining = deadline - System.nanoTime();
        }
        if (!executor.isShutdown()) return executor.isTerminated();
        return executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    @Override
    public void close() {
        shutdown();
    }

    public enum ShutdownStatus {
        OPEN,
        CLOSING,
        TERMINATED,
        TIMED_OUT,
        FAILED
    }

    public enum SubmissionResult {
        ACCEPTED,
        CLOSED,
        CONTENDED,
        SATURATED,
        INVALID
    }
}
