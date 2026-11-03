package org.worldspeaker.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadBoundaryTest {
    @AfterEach
    void clearTestBinding() {
        ThreadBoundary.clearForTests();
    }

    @Test
    void serverAndWorkerAssertionsDistinguishTheCapturedThread() throws Exception {
        Thread server = Thread.currentThread();
        ThreadBoundary.markServerThread();
        ThreadBoundary.assertServerThread();
        assertTrue(ThreadBoundary.isServerThread());
        AtomicReference<Throwable> serverAssertion = new AtomicReference<>();
        AtomicReference<Throwable> ioAssertion = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { ThreadBoundary.assertServerThread(); } catch (Throwable failure) { serverAssertion.set(failure); }
            try { ThreadBoundary.assertNotServerThread(); } catch (Throwable failure) { ioAssertion.set(failure); }
        }, "worldspeaker-boundary-test");
        worker.start();
        worker.join(2_000);

        assertFalse(worker.isAlive(), "worker should finish within the bounded join");
        assertInstanceOf(IllegalStateException.class, serverAssertion.get());
        assertNull(ioAssertion.get());
        assertTrue(ThreadBoundary.isServerThread(), "the worker must not replace the server binding");
        assertTrue(server == Thread.currentThread());
    }

    @Test
    void secondServerThreadIsRejected() throws Exception {
        ThreadBoundary.markServerThread();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try { ThreadBoundary.markServerThread(); } catch (Throwable thrown) { failure.set(thrown); }
        });
        other.start();
        other.join(2_000);
        assertInstanceOf(IllegalStateException.class, failure.get());
    }

    @Test
    void retiredEpochCannotRebindOrClearTheNextServerThread() throws Exception {
        ThreadBoundary.ServerEpoch oldEpoch = ThreadBoundary.newServerEpochToken();
        ThreadBoundary.ServerEpoch newEpoch = ThreadBoundary.newServerEpochToken();
        CountDownLatch oldBound = new CountDownLatch(1);
        CountDownLatch resumeOld = new CountDownLatch(1);
        CountDownLatch oldFinished = new CountDownLatch(1);
        CountDownLatch newBound = new CountDownLatch(1);
        CountDownLatch newVerified = new CountDownLatch(1);
        AtomicReference<Throwable> oldFailure = new AtomicReference<>();
        AtomicReference<Throwable> newFailure = new AtomicReference<>();
        AtomicBoolean oldRetirementCleared = new AtomicBoolean(true);
        AtomicBoolean newRetirementCleared = new AtomicBoolean();

        Thread oldServer = new Thread(() -> {
            try {
                ThreadBoundary.beginServerEpoch(oldEpoch);
                oldBound.countDown();
                if (!resumeOld.await(2, TimeUnit.SECONDS)) throw new AssertionError("old server was not released");
                try {
                    ThreadBoundary.beginServerEpoch(oldEpoch);
                    oldFailure.set(new AssertionError("retired epoch rebound itself"));
                } catch (IllegalStateException expected) {
                    oldFailure.set(expected);
                }
                try {
                    ThreadBoundary.assertServerThread(oldEpoch);
                    oldFailure.set(new AssertionError("retired epoch remained bound"));
                } catch (IllegalStateException expected) {
                    oldFailure.set(expected);
                }
                oldRetirementCleared.set(ThreadBoundary.retireServerEpoch(oldEpoch));
            } catch (Throwable failure) {
                oldFailure.set(failure);
            } finally {
                oldFinished.countDown();
            }
        }, "worldspeaker-old-server-test");
        oldServer.start();
        assertTrue(oldBound.await(2, TimeUnit.SECONDS), "old server binds its epoch");
        assertTrue(ThreadBoundary.retireServerEpoch(oldEpoch), "retirement clears only the old binding");

        Thread newServer = new Thread(() -> {
            try {
                ThreadBoundary.beginServerEpoch(newEpoch);
                newBound.countDown();
                if (!oldFinished.await(2, TimeUnit.SECONDS)) throw new AssertionError("old server did not finish");
                ThreadBoundary.assertServerThread(newEpoch);
                newRetirementCleared.set(ThreadBoundary.retireServerEpoch(newEpoch));
                newVerified.countDown();
            } catch (Throwable failure) {
                newFailure.set(failure);
            }
        }, "worldspeaker-new-server-test");
        newServer.start();
        assertTrue(newBound.await(2, TimeUnit.SECONDS), "new server binds its fresh epoch");
        resumeOld.countDown();
        assertTrue(oldFinished.await(2, TimeUnit.SECONDS), "old server callback finishes without a blocking join");
        assertTrue(newVerified.await(2, TimeUnit.SECONDS), "late old callback cannot replace the new server binding");
        oldServer.join(2_000);
        newServer.join(2_000);

        assertFalse(oldServer.isAlive());
        assertFalse(newServer.isAlive());
        assertInstanceOf(IllegalStateException.class, oldFailure.get());
        assertNull(newFailure.get());
        assertFalse(oldRetirementCleared.get(), "late retirement for the old epoch cannot clear the new binding");
        assertTrue(newRetirementCleared.get(), "the new server can retire its own binding");
    }
}
