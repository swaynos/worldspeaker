package org.worldspeaker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;
import org.worldspeaker.integration.BoundCompanionClient;
import org.worldspeaker.runtime.ThreadBoundary;
import org.worldspeaker.runtime.ThreadBoundaryTestAccess;
import org.worldspeaker.runtime.TickMetrics;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldspeakerServerShutdownDiagnosticTest {
    @TempDir Path directory;

    @Test
    void retiredProductionCallbackReportsOnceOffTickWithoutTouchingNewService() throws Exception {
        // Other server-thread fixtures may leave only a test marker (no live epoch).
        ThreadBoundaryTestAccess.clearForTests();
        CountDownLatch oldDiagnosticEntered = new CountDownLatch(1);
        CountDownLatch releaseOldDiagnostic = new CountDownLatch(1);
        CountDownLatch ownerRequestEntered = new CountDownLatch(1);
        CountDownLatch ownerInterrupted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch socketAborted = new CountDownLatch(1);
        CountDownLatch shutdownLogEntered = new CountDownLatch(1);
        CountDownLatch releaseShutdownLog = new CountDownLatch(1);
        AtomicInteger shutdownLogCount = new AtomicInteger();
        AtomicReference<String> shutdownLogCode = new AtomicReference<>();
        AtomicReference<Thread> shutdownLogThread = new AtomicReference<>();
        AtomicReference<Thread> ownerThread = new AtomicReference<>();
        AtomicReference<Thread> oldDiagnosticThread = new AtomicReference<>();

        ServerServices oldServices = new ServerServices(directory.resolve("worldspeaker.properties"), new Object(), code -> {
            shutdownLogCount.incrementAndGet();
            shutdownLogCode.set(code);
            shutdownLogThread.set(Thread.currentThread());
            shutdownLogEntered.countDown();
            awaitIgnoringInterrupts(releaseShutdownLog);
        });
        ServerServices newServices = null;
        BoundCompanionClient oldClient = null;
        boolean oldActivated = false;
        boolean newActivated = false;
        Consumer<String> oldDiagnosticCallback = WorldspeakerServer.privateDiagnosticCallback(oldServices);

        try {
            ThreadBoundary.beginServerEpoch(oldServices.threadBoundaryToken());
            WorldspeakerServer.activateServices(oldServices);
            oldActivated = true;

            assertTrue(oldServices.diagnosticIo().tryExecute(() -> {
                oldDiagnosticThread.set(Thread.currentThread());
                oldDiagnosticEntered.countDown();
                awaitIgnoringInterrupts(releaseOldDiagnostic);
            }));
            assertTrue(oldDiagnosticEntered.await(2, TimeUnit.SECONDS), "ordinary diagnostic I/O is deliberately blocked");

            ConfiguredBinding binding = new ConfiguredBinding("server-test", "world-test", "character-test", "agent-test");
            CompanionConfig config = new CompanionConfig(URI.create("ws://127.0.0.1:1"), new byte[32], binding,
                    "Hello {player}", "Archivist", org.worldspeaker.compat.InteractionPolicy.denyAll());
            CompatibilityGate gate = new CompatibilityGate(binding);
            gate.checkLocal(localFacts());
            oldClient = new BoundCompanionClient(config, localFacts(), gate, ignored -> {}, oldDiagnosticCallback);
            assertTrue(oldServices.setCompanion(oldClient));
            WebSocket socket = stalledSocket(ownerRequestEntered, ownerInterrupted, releaseOwner, socketAborted, ownerThread);
            oldClient.onOpen(socket);
            assertTrue(ownerRequestEntered.await(2, TimeUnit.SECONDS), "transport owner reaches the blocked socket operation");

            long stopStarted = System.nanoTime();
            assertTrue(WorldspeakerServer.stopServices(oldServices), "the production service-stop wrapper retires the active epoch");
            long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStarted);
            assertTrue(stopMillis < 500, "service retirement does not wait for blocked diagnostic or transport I/O");
            assertFalse(oldServices.isActive());
            assertTrue(oldServices.diagnosticIo().isClosed(), "normal diagnostic admission closes at retirement");
            assertFalse(oldServices.shutdownDiagnosticsClosedForTests(), "the independent shutdown sink remains open for the watchdog window");

            newServices = new ServerServices(directory.resolve("worldspeaker.properties"), new Object());
            ThreadBoundary.beginServerEpoch(newServices.threadBoundaryToken());
            WorldspeakerServer.activateServices(newServices);
            newActivated = true;
            newServices.metrics().increment(TickMetrics.Counter.REQUESTS_ADMITTED);
            assertNotEquals(oldServices.generation(), newServices.generation());

            assertTrue(shutdownLogEntered.await(8, TimeUnit.SECONDS), "the production watchdog callback reaches the old lifecycle sink after retirement");
            assertTrue(socketAborted.await(2, TimeUnit.SECONDS), "diagnostic I/O does not hold up watchdog abort");
            assertTrue(ownerInterrupted.await(2, TimeUnit.SECONDS), "the independent watchdog interrupts the old transport owner");
            assertEquals(1, shutdownLogCount.get(), "the shutdown evidence sink emits at most one report");
            assertEquals(ShutdownDiagnosticSink.TIMEOUT, shutdownLogCode.get());
            assertNotSame(Thread.currentThread(), shutdownLogThread.get());
            assertNotEquals(ownerThread.get(), shutdownLogThread.get());
            assertNotEquals(oldDiagnosticThread.get(), shutdownLogThread.get());
            assertTrue(oldServices.shutdownDiagnosticsClosedForTests(), "the one-slot lane closes after accepting its report");

            oldDiagnosticCallback.accept(ShutdownDiagnosticSink.TIMEOUT);
            oldDiagnosticCallback.accept(ShutdownDiagnosticSink.INCOMPLETE);
            oldDiagnosticCallback.accept("WS-COMPAT-RECONNECT-EXHAUSTED");
            assertEquals(1, shutdownLogCount.get(), "duplicate shutdown and retired normal callbacks cannot add old log activity");
            assertEquals(1L, WorldspeakerServer.compatibilityCountersSnapshot()[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()],
                    "a late old watchdog callback cannot alter the new service's admission metrics");
            assertEquals(0L, oldServices.metrics().countersSnapshot()[TickMetrics.Counter.RECONNECT_EXHAUSTED.ordinal()]);

            releaseOwner.countDown();
            releaseOldDiagnostic.countDown();
            releaseShutdownLog.countDown();
            assertTrue(oldServices.awaitShutdownDiagnosticsTerminationForTests(2, TimeUnit.SECONDS),
                    "the blocked bounded shutdown writer terminates after release");
            assertTrue(oldServices.diagnosticIo().awaitTermination(2, TimeUnit.SECONDS),
                    "the retired ordinary diagnostic lane terminates after its blocked I/O is released");
            ownerThread.get().join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(ownerThread.get().isAlive(), "the interrupted old transport owner exits after the fixture is released");

            long drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (oldServices.shutdownDiagnosticDrainAliveForTests() && System.nanoTime() < drainDeadline) Thread.yield();
            assertFalse(oldServices.shutdownDiagnosticDrainAliveForTests(), "the independent lifecycle drain exits finitely");
        } finally {
            releaseOwner.countDown();
            releaseOldDiagnostic.countDown();
            releaseShutdownLog.countDown();
            if (oldClient != null) oldClient.close();
            if (newActivated && newServices != null) WorldspeakerServer.stopServices(newServices);
            if (oldActivated && oldServices.isActive()) WorldspeakerServer.stopServices(oldServices);
            oldServices.retire();
            if (newServices != null) newServices.retire();
            ThreadBoundaryTestAccess.clearForTests();
        }
    }

    private static RuntimeFacts localFacts() {
        return new RuntimeFacts(
                DeploymentFacts.MINECRAFT_VERSION,
                DeploymentFacts.FABRIC_LOADER_VERSION,
                DeploymentFacts.FABRIC_API_VERSION,
                Integer.parseInt(DeploymentFacts.JAVA_MAJOR),
                DeploymentFacts.JAVA_VENDOR,
                DeploymentFacts.JAVA_RUNTIME_VERSION);
    }

    private static WebSocket stalledSocket(CountDownLatch entered, CountDownLatch interrupted,
                                           CountDownLatch release, CountDownLatch aborted,
                                           AtomicReference<Thread> ownerThread) {
        return (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(), new Class<?>[]{WebSocket.class},
                (proxy, method, args) -> {
                    if ("request".equals(method.getName())) {
                        ownerThread.set(Thread.currentThread());
                        entered.countDown();
                        awaitIgnoringInterrupts(release, interrupted);
                        return proxy;
                    }
                    if ("abort".equals(method.getName())) {
                        aborted.countDown();
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        awaitIgnoringInterrupts(latch, null);
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch, CountDownLatch interrupted) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                if (interrupted != null) interrupted.countDown();
            }
        }
    }
}
