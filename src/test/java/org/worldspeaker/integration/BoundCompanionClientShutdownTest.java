package org.worldspeaker.integration;

import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundCompanionClientShutdownTest {
    @Test
    void closeReturnsAndWatchdogAbortsOffOwnerAfterBoundedTimeout() throws Exception {
        CountDownLatch requestEntered = new CountDownLatch(1);
        CountDownLatch requestInterrupted = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        CountDownLatch socketAborted = new CountDownLatch(1);
        CountDownLatch timeoutDiagnostic = new CountDownLatch(1);
        AtomicReference<Thread> ownerThread = new AtomicReference<>();
        AtomicReference<Thread> diagnosticThread = new AtomicReference<>();
        AtomicReference<String> diagnostic = new AtomicReference<>();
        AtomicInteger timeoutCount = new AtomicInteger();
        SocketFixture socket = socketFixture(requestEntered, requestInterrupted, releaseRequest, socketAborted, true);
        BoundCompanionClient client = client(reason -> {
            diagnostic.set(reason);
            diagnosticThread.set(Thread.currentThread());
            if ("WS-COMPAT-SHUTDOWN-TIMEOUT".equals(reason)) {
                timeoutCount.incrementAndGet();
                timeoutDiagnostic.countDown();
            }
        }, 1);
        Thread caller = Thread.currentThread();
        try {
            client.onOpen(socket.socket());
            assertTrue(requestEntered.await(2, TimeUnit.SECONDS), "owner is held inside a fake socket operation");
            ownerThread.set(socket.requestThread().get());

            long generationBeforeClose = client.connectionGenerationForTests();
            long started = System.nanoTime();
            client.close();
            assertEquals(generationBeforeClose + 1, client.connectionGenerationForTests(),
                    "close retires the connection epoch before returning");
            client.close();
            assertEquals(generationBeforeClose + 1, client.connectionGenerationForTests(),
                    "repeated close does not advance or revive a later epoch");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500,
                    "close never joins the owner or waits for its cleanup");
            client.start();
            assertFalse(client.sendDialogueRequest("after-close", "player", "Player", "1", "text", null, null));

            assertTrue(timeoutDiagnostic.await(2, TimeUnit.SECONDS), "the independent watchdog reports its configured deadline");
            assertTrue(socketAborted.await(2, TimeUnit.SECONDS), "the watchdog aborts the socket without queueing behind the stalled owner");
            assertTrue(requestInterrupted.await(2, TimeUnit.SECONDS), "the watchdog interrupts the transport owner");
            assertEquals("WS-COMPAT-SHUTDOWN-TIMEOUT", diagnostic.get());
            assertEquals(1, timeoutCount.get(), "repeated close does not register another timeout report");
            assertNotEquals(caller, diagnosticThread.get());
            assertNotEquals(ownerThread.get(), diagnosticThread.get());
            assertEquals("TIMED_OUT", client.shutdownStatusForTests().name());
            assertFalse(client.awaitOwnerTerminationForTests(1, TimeUnit.MILLISECONDS),
                    "a task that ignores interruption is not falsely reported as stopped");
            assertEquals(1, socket.requestCount().get(), "close plus a later start cannot reconnect or revive work");
            releaseRequest.countDown();
            assertTrue(client.awaitOwnerTerminationForTests(2, TimeUnit.SECONDS),
                    "the owner terminates when the deliberately uninterruptible fixture is released");
            assertEquals("TIMED_OUT", client.shutdownStatusForTests().name(),
                    "late owner termination never rewrites the timeout as successful cleanup");
        } finally {
            releaseRequest.countDown();
            client.close();
        }
    }

    @Test
    void successfulAndRepeatedCloseCleansSocketOnceAndTerminatesWatchdog() throws Exception {
        CountDownLatch requestEntered = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        CountDownLatch socketAborted = new CountDownLatch(1);
        SocketFixture socket = socketFixture(requestEntered, new CountDownLatch(0), releaseRequest, socketAborted, false);
        AtomicInteger diagnostics = new AtomicInteger();
        BoundCompanionClient client = client(ignored -> diagnostics.incrementAndGet(), 5_000);
        try {
            client.onOpen(socket.socket());
            assertTrue(requestEntered.await(2, TimeUnit.SECONDS));
            releaseRequest.countDown();
            assertTrue(client.awaitOwnerBarrierForTests(2, TimeUnit.SECONDS));

            client.close();
            client.close();
            assertTrue(client.awaitOwnerTerminationForTests(2, TimeUnit.SECONDS));
            awaitShutdownStatus(client, "TERMINATED");
            assertEquals("TERMINATED", client.shutdownStatusForTests().name());
            assertEquals(1, socket.closeCount().get(), "repeated close performs owner socket cleanup exactly once");
            assertEquals(0, socket.abortCount().get(), "successful cleanup uses graceful close rather than timeout abort");
            assertEquals(0, diagnostics.get(), "successful shutdown is not reported as a timeout");
            client.start();
            assertEquals(1, socket.requestCount().get(), "a closed client cannot revive through start");
        } finally {
            releaseRequest.countDown();
            client.close();
        }
    }

    private static BoundCompanionClient client(java.util.function.Consumer<String> diagnostic, long shutdownTimeoutMs) {
        ConfiguredBinding binding = new ConfiguredBinding("server-test", "world-test", "character-test", "agent-test");
        CompanionConfig config = new CompanionConfig(URI.create("ws://127.0.0.1:1"), new byte[32], binding,
                "Hello {player}", "Archivist", org.worldspeaker.compat.InteractionPolicy.denyAll());
        RuntimeFacts local = new RuntimeFacts(
                DeploymentFacts.MINECRAFT_VERSION, DeploymentFacts.FABRIC_LOADER_VERSION, DeploymentFacts.FABRIC_API_VERSION,
                Integer.parseInt(DeploymentFacts.JAVA_MAJOR), DeploymentFacts.JAVA_VENDOR, DeploymentFacts.JAVA_RUNTIME_VERSION);
        CompatibilityGate gate = new CompatibilityGate(binding);
        gate.checkLocal(local);
        return new BoundCompanionClient(config, local, gate, ignored -> {}, diagnostic,
                10_000, 10_000, shutdownTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private static SocketFixture socketFixture(CountDownLatch requestEntered, CountDownLatch requestInterrupted,
                                               CountDownLatch releaseRequest, CountDownLatch aborted, boolean stall) {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger aborts = new AtomicInteger();
        AtomicReference<Thread> requestThread = new AtomicReference<>();
        WebSocket socket = (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(), new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                    if ("request".equals(method.getName())) {
                        requests.incrementAndGet();
                        requestThread.set(Thread.currentThread());
                        requestEntered.countDown();
                        if (stall) {
                            while (true) {
                                try {
                                    releaseRequest.await();
                                    break;
                                } catch (InterruptedException ignored) {
                                    requestInterrupted.countDown();
                                    // Model a JDK/native operation that is not cancellable by interruption.
                                }
                            }
                        }
                        return proxy;
                    }
                    if ("sendClose".equals(method.getName())) {
                        closes.incrementAndGet();
                        return CompletableFuture.completedFuture(proxy);
                    }
                    if ("abort".equals(method.getName())) {
                        aborts.incrementAndGet();
                        aborted.countDown();
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
        return new SocketFixture(socket, requests, closes, aborts, requestThread);
    }

    private static void awaitShutdownStatus(BoundCompanionClient client, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!expected.equals(client.shutdownStatusForTests().name()) && System.nanoTime() < deadline) Thread.yield();
        assertEquals(expected, client.shutdownStatusForTests().name());
    }

    private record SocketFixture(WebSocket socket, AtomicInteger requestCount, AtomicInteger closeCount,
                                 AtomicInteger abortCount, AtomicReference<Thread> requestThread) {}

}
