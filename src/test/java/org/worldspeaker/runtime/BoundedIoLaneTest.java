package org.worldspeaker.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedIoLaneTest {
    @Test
    void detailedSubmissionSeparatesTransientLockContentionFromCapacitySaturation() throws Exception {
        BoundedIoLane lane = new BoundedIoLane("worldspeaker-test-contention", 1);
        Field field = BoundedIoLane.class.getDeclaredField("submissionLock");
        field.setAccessible(true);
        ReentrantLock submissionLock = (ReentrantLock) field.get(lane);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch ran = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            submissionLock.lock();
            try {
                lockHeld.countDown();
                await(releaseLock);
            } finally {
                submissionLock.unlock();
            }
        }, "worldspeaker-test-submission-lock-holder");
        try {
            holder.start();
            assertTrue(lockHeld.await(2, TimeUnit.SECONDS));
            assertEquals(BoundedIoLane.SubmissionResult.CONTENDED,
                    lane.tryExecuteWithResult(() -> { throw new AssertionError("contended work was not accepted"); }));
            releaseLock.countDown();
            holder.join(2_000);
            assertFalse(holder.isAlive());

            assertEquals(BoundedIoLane.SubmissionResult.ACCEPTED, lane.tryExecuteWithResult(ran::countDown));
            assertTrue(ran.await(2, TimeUnit.SECONDS));
            lane.shutdown();
            assertTrue(lane.awaitTermination(2, TimeUnit.SECONDS));
        } finally {
            releaseLock.countDown();
            holder.join(2_000);
            lane.shutdown();
        }
    }

    @Test
    void heldIoDoesNotBlockSubmitterAndCapacityRejectsWithoutCallerRuns() throws Exception {
        BoundedIoLane lane = new BoundedIoLane("worldspeaker-test-io", 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        try {
            assertTrue(lane.tryExecute(() -> {
                firstThread.set(Thread.currentThread());
                entered.countDown();
                await(release);
                finished.countDown();
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            Thread caller = Thread.currentThread();
            assertTrue(lane.tryExecute(() -> {
                assertEquals("worldspeaker-test-io", Thread.currentThread().getName());
                finished.countDown();
            }));
            assertEquals(BoundedIoLane.SubmissionResult.SATURATED,
                    lane.tryExecuteWithResult(() -> { throw new AssertionError("rejected operation ran on caller"); }));
            assertEquals(caller, Thread.currentThread());
            assertTrue(firstThread.get() != caller);
            assertEquals(1, lane.queuedTasks());
            assertEquals(1, lane.queueCapacity());

            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            lane.shutdown();
            assertTrue(lane.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void shutdownIsIdempotentAndRejectsNewWork() throws Exception {
        BoundedIoLane lane = new BoundedIoLane("worldspeaker-test-close", 1);
        CountDownLatch ran = new CountDownLatch(1);
        assertTrue(lane.tryExecute(ran::countDown));
        lane.shutdown();
        lane.shutdown();
        assertFalse(lane.tryExecute(() -> {}));
        assertTrue(ran.await(2, TimeUnit.SECONDS));
        assertTrue(lane.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    void shutdownControlUsesItsReservedSlotWhenNormalQueueIsFull() throws Exception {
        BoundedIoLane lane = new BoundedIoLane("worldspeaker-test-reserved-control", 2, false, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(3);
        try {
            assertTrue(lane.tryExecute(() -> {
                entered.countDown();
                await(release);
                finished.countDown();
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(lane.tryExecute(finished::countDown));
            assertFalse(lane.tryExecute(() -> { throw new AssertionError("normal queue must stay bounded"); }));
            assertEquals(1, lane.queueCapacity());
            assertTrue(lane.tryExecuteControl(() -> {
                assertEquals("worldspeaker-test-reserved-control", Thread.currentThread().getName());
                finished.countDown();
            }));
            assertFalse(lane.tryExecuteControl(() -> { throw new AssertionError("control slot is exactly once"); }));
            assertEquals(2, lane.queuedTasks());

            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            lane.shutdown();
            assertTrue(lane.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void shutdownControlRunsOnOwnerAndRepeatedShutdownDoesNotCreateAnotherWatchdog() throws Exception {
        BoundedIoLane lane = new BoundedIoLane("worldspeaker-test-close-control", 2, true, 1);
        CountDownLatch cleanupFinished = new CountDownLatch(1);
        AtomicReference<Thread> cleanupThread = new AtomicReference<>();
        AtomicReference<Thread> failureThread = new AtomicReference<>();
        try {
            assertTrue(lane.shutdownWithControl(() -> {
                cleanupThread.set(Thread.currentThread());
                cleanupFinished.countDown();
            }, () -> failureThread.set(Thread.currentThread())));
            assertFalse(lane.shutdownWithControl(() -> { throw new AssertionError("cleanup must be submitted once"); },
                    () -> failureThread.set(Thread.currentThread())));
            assertTrue(lane.isClosed(), "admission closes synchronously");
            assertFalse(lane.tryExecute(() -> { throw new AssertionError("closed lane accepted normal work"); }));

            assertTrue(cleanupFinished.await(2, TimeUnit.SECONDS));
            assertTrue(lane.awaitTermination(2, TimeUnit.SECONDS));
            awaitStatus(lane, BoundedIoLane.ShutdownStatus.TERMINATED);
            assertEquals(BoundedIoLane.ShutdownStatus.TERMINATED, lane.shutdownStatus());
            assertTrue(cleanupThread.get() != Thread.currentThread());
            assertNull(failureThread.get(), "successful cleanup does not report a timeout");
            awaitWatchdogExit(lane);
        } finally {
            lane.shutdown();
        }
    }

    @Test
    void stalledOwnerTimesOutOffTickInterruptsAndNeverClaimsTermination() throws Exception {
        AtomicLong clock = new AtomicLong();
        BoundedIoLane lane = new BoundedIoLane("worldspeaker-test-stalled-close", 1, true, 0,
                5, TimeUnit.SECONDS, clock::get);
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch ownerInterrupted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch timeoutReported = new CountDownLatch(1);
        AtomicReference<Thread> ownerThread = new AtomicReference<>();
        AtomicReference<Thread> timeoutThread = new AtomicReference<>();
        AtomicReference<Boolean> cleanupRan = new AtomicReference<>(false);
        try {
            assertTrue(lane.tryExecute(() -> {
                ownerThread.set(Thread.currentThread());
                ownerEntered.countDown();
                while (true) {
                    try {
                        releaseOwner.await();
                        return;
                    } catch (InterruptedException ignored) {
                        ownerInterrupted.countDown();
                        // Deliberately model a native/JDK operation that ignores interruption.
                    }
                }
            }));
            assertTrue(ownerEntered.await(2, TimeUnit.SECONDS));

            Thread caller = Thread.currentThread();
            assertTrue(lane.shutdownWithControl(() -> cleanupRan.set(true), () -> {
                timeoutThread.set(Thread.currentThread());
                timeoutReported.countDown();
            }));
            assertEquals(BoundedIoLane.ShutdownStatus.CLOSING, lane.shutdownStatus());
            assertFalse(lane.tryExecute(() -> { throw new AssertionError("shutdown must close admission immediately"); }));
            assertEquals(caller, Thread.currentThread(), "shutdown did not wait for the owner");

            clock.set(TimeUnit.SECONDS.toNanos(5));
            lane.signalShutdownClockForTests();
            assertTrue(timeoutReported.await(2, TimeUnit.SECONDS), "independent watchdog observes the injected deadline");
            assertTrue(ownerInterrupted.await(2, TimeUnit.SECONDS), "watchdog interrupts the stalled owner");
            assertEquals(BoundedIoLane.ShutdownStatus.TIMED_OUT, lane.shutdownStatus());
            assertFalse(lane.awaitTermination(1, TimeUnit.MILLISECONDS),
                    "an interruption-ignoring operation is not reported as terminated");
            assertFalse(cleanupRan.get(), "queued cleanup cannot run behind the stalled owner");
            assertTrue(timeoutThread.get() != caller);
            assertTrue(timeoutThread.get() != ownerThread.get());
            awaitWatchdogExit(lane);

            releaseOwner.countDown();
            assertTrue(lane.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(BoundedIoLane.ShutdownStatus.TIMED_OUT, lane.shutdownStatus(),
                    "late executor exit cannot rewrite a previously reported timeout as success");
        } finally {
            releaseOwner.countDown();
            lane.shutdown();
        }
    }

    private static void awaitWatchdogExit(BoundedIoLane lane) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (lane.shutdownWatchdogAliveForTests() && System.nanoTime() < deadline) Thread.yield();
        assertFalse(lane.shutdownWatchdogAliveForTests(), "shutdown watchdog exits after its terminal outcome");
    }

    private static void awaitStatus(BoundedIoLane lane, BoundedIoLane.ShutdownStatus expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (lane.shutdownStatus() != expected && System.nanoTime() < deadline) Thread.yield();
        assertEquals(expected, lane.shutdownStatus());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("test I/O release timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
