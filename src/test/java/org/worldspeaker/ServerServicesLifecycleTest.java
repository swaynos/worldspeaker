package org.worldspeaker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.bootstrap.FirstReplyScheduler;
import org.worldspeaker.bootstrap.SmokeTickBudget;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.StartupConfigTestFiles;
import org.worldspeaker.runtime.GameLoopMailbox;
import org.worldspeaker.runtime.TickMetrics;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerServicesLifecycleTest {
    @TempDir Path directory;

    @Test
    void bootstrapOwnerDrainsDeferredDispositionsBeforeConversationExists() {
        ServerServices services = new ServerServices(directory.resolve("worldspeaker.properties"), new Object());
        try {
            assertNull(services.conversation(), "bootstrap owns a scheduler before a conversation is installed");
            FirstReplyScheduler scheduler = services.firstReplyScheduler();
            AtomicInteger dispatched = new AtomicInteger();
            for (int index = 0; index < FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS; index++) {
                assertTrue(scheduler.enqueueDisposition(1_000 + index, dispatched::incrementAndGet));
            }

            SmokeTickBudget firstFrame = new SmokeTickBudget(scheduler.monotonicNanos());
            firstFrame.deadlineChecked();
            assertEquals(SmokeTickBudget.MAX_OPERATIONS, scheduler.drainDispositions(firstFrame, 0));
            assertEquals(1, firstFrame.deadlineRecordsChecked());
            assertEquals(FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS - SmokeTickBudget.MAX_OPERATIONS,
                    scheduler.pendingDispositionCount());
            assertEquals(SmokeTickBudget.MAX_OPERATIONS, dispatched.get());

            SmokeTickBudget nextFrame = new SmokeTickBudget(scheduler.monotonicNanos());
            nextFrame.deadlineChecked();
            assertEquals(SmokeTickBudget.MAX_OPERATIONS, scheduler.drainDispositions(nextFrame, 0));
            assertEquals(1, nextFrame.deadlineRecordsChecked());
            assertEquals(FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS, dispatched.get());
            assertEquals(0, scheduler.pendingDispositionCount());
        } finally {
            services.retire();
        }
    }

    @Test
    void sameJvmRestartGetsFreshResourcesAndRejectsLateOldEpochConfig() throws Exception {
        Object oldServer = new Object();
        Object newServer = new Object();
        ServerServices oldServices = new ServerServices(directory.resolve("worldspeaker.properties"), oldServer);
        CountDownLatch configWorkerEntered = new CountDownLatch(1);
        CountDownLatch releaseConfigWorker = new CountDownLatch(1);
        CountDownLatch configWorkerFinished = new CountDownLatch(1);
        CountDownLatch freshConfigLaneExecuted = new CountDownLatch(1);
        CountDownLatch freshDiagnosticLaneExecuted = new CountDownLatch(1);
        AtomicBoolean oldMailboxAcceptedLateConfig = new AtomicBoolean(true);
        ServerServices newServices = null;

        try {
            CompanionConfig oldConfig = config("old-server");
            assertTrue(oldServices.installConfiguration(oldConfig, new CompatibilityGate(oldConfig.binding())));
            oldServices.metrics().increment(TickMetrics.Counter.REQUESTS_ADMITTED);

            assertTrue(oldServices.configIo().tryExecute(() -> {
                configWorkerEntered.countDown();
                try {
                    if (!releaseConfigWorker.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("controlled config worker was not released");
                    }
                    oldMailboxAcceptedLateConfig.set(oldServices.mailbox().offerBootstrap(
                            new GameLoopMailbox.BootstrapResult(oldConfig, null)));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                } finally {
                    configWorkerFinished.countDown();
                }
            }));
            assertTrue(configWorkerEntered.await(2, TimeUnit.SECONDS), "old config worker reaches the controlled stop boundary");

            oldServices.retire();
            assertEquals(1L, configWorkerFinished.getCount(), "retirement does not wait for an in-flight config callback");
            newServices = new ServerServices(directory.resolve("worldspeaker.properties"), newServer);
            CompanionConfig newConfig = config("new-server");

            assertNotEquals(oldServices.generation(), newServices.generation(), "each same-JVM start gets a fresh request epoch");
            assertNotSame(oldServices.mailbox(), newServices.mailbox());
            assertNotSame(oldServices.metrics(), newServices.metrics());
            assertNotSame(oldServices.configIo(), newServices.configIo());
            assertNotSame(oldServices.diagnosticIo(), newServices.diagnosticIo());
            assertTrue(oldServices.ownsServer(oldServer));
            assertFalse(oldServices.ownsServer(newServer), "a late old stop cannot be mistaken for the new server");
            assertTrue(newServices.ownsServer(newServer));
            assertFalse(newServices.ownsServer(oldServer));
            assertFalse(oldServices.isActive());
            assertTrue(oldServices.configIo().isClosed());
            assertTrue(oldServices.diagnosticIo().isClosed());
            assertFalse(newServices.configIo().isClosed());
            assertFalse(newServices.diagnosticIo().isClosed());
            assertTrue(newServices.configIo().tryExecute(freshConfigLaneExecuted::countDown));
            assertTrue(newServices.diagnosticIo().tryExecute(freshDiagnosticLaneExecuted::countDown));
            assertTrue(freshConfigLaneExecuted.await(2, TimeUnit.SECONDS), "new config lane accepts fresh work");
            assertTrue(freshDiagnosticLaneExecuted.await(2, TimeUnit.SECONDS), "new diagnostic lane accepts fresh work");
            assertNull(newServices.config(), "the new service cannot inherit the previous configuration");
            assertNull(newServices.mailbox().takeBootstrap(), "the new mailbox starts empty");
            assertEquals(0, newServices.metrics().countersSnapshot()[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()]);
            assertEquals(1, oldServices.metrics().countersSnapshot()[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()]);
            assertFalse(oldServices.installConfiguration(newConfig, new CompatibilityGate(newConfig.binding())),
                    "a retired epoch cannot apply a late config");
            assertTrue(newServices.installConfiguration(newConfig, new CompatibilityGate(newConfig.binding())));
            assertSame(newConfig, newServices.config());

            releaseConfigWorker.countDown();
            assertTrue(configWorkerFinished.await(2, TimeUnit.SECONDS), "old worker completes without being joined by the lifecycle path");
            assertFalse(oldMailboxAcceptedLateConfig.get(), "the retired mailbox rejects the old worker result");
            assertNull(newServices.mailbox().takeBootstrap(), "the old worker cannot publish into the new epoch");
        } finally {
            releaseConfigWorker.countDown();
            oldServices.retire();
            if (newServices != null) newServices.retire();
        }
    }

    @Test
    void loadedPresentationReloadUsesFreshEpochAndInvalidSnapshotCannotPartiallyInstall() throws Exception {
        Path configPath = StartupConfigTestFiles.createPrivate(directory, Map.of(
                "serverId", "lifecycle-server", "worldId", "lifecycle-world", "characterId", "lifecycle-character",
                "agentId", "lifecycle-agent", "characterDisplayName", "Archivist", "characterAliases", "Old Name",
                "characterChatStyle", "plain"));
        CompanionConfig before = CompanionConfig.load(configPath);
        Object oldServer = new Object();
        ServerServices oldServices = new ServerServices(configPath, oldServer);
        ServerServices newServices = new ServerServices(configPath, new Object());

        try {
            assertTrue(oldServices.installConfiguration(before, new CompatibilityGate(before.binding())));
            long oldGeneration = oldServices.generation();
            var oldCharacterKey = before.binding().characterKey();

            writeOwnerOnlyConfig(configPath, "The Archivist", "Old Name,Keeper", "invalid-private-style-value");
            CountDownLatch invalidLoadFinished = new CountDownLatch(1);
            AtomicBoolean invalidResultEnqueued = new AtomicBoolean();
            assertTrue(newServices.configIo().tryExecute(() -> {
                try {
                    CompanionConfig loaded = CompanionConfig.load(configPath);
                    invalidResultEnqueued.set(newServices.mailbox().offerBootstrap(
                            new GameLoopMailbox.BootstrapResult(loaded, null)));
                } catch (CompanionConfig.InvalidCharacterChatStyleConfigException invalidStyle) {
                    invalidResultEnqueued.set(newServices.mailbox().offerBootstrap(
                            new GameLoopMailbox.BootstrapResult(null, "WS-COMPAT-CHARACTER-CHAT-STYLE-INVALID")));
                } catch (IOException failure) {
                    throw new AssertionError("unexpected invalid configuration failure", failure);
                } finally {
                    invalidLoadFinished.countDown();
                }
            }));
            assertTrue(invalidLoadFinished.await(2, TimeUnit.SECONDS), "the bounded config lane completes the invalid load");
            assertTrue(invalidResultEnqueued.get());
            var invalidResult = newServices.mailbox().takeBootstrap();
            assertNotNull(invalidResult);
            assertNull(invalidResult.config());
            assertEquals("WS-COMPAT-CHARACTER-CHAT-STYLE-INVALID", invalidResult.diagnosticCode());
            assertNull(newServices.config(), "a failed new-epoch load cannot publish a partial identity snapshot");
            assertNull(newServices.gate(), "a failed new-epoch load cannot install a gate for partial fields");

            writeOwnerOnlyConfig(configPath, "The Archivist", "Old Name,Keeper", "bracketed");
            CompanionConfig after = CompanionConfig.load(configPath);
            assertTrue(newServices.installConfiguration(after, new CompatibilityGate(after.binding())));

            assertNotEquals(oldGeneration, newServices.generation(), "restart receives a fresh service epoch");
            assertEquals(oldCharacterKey, after.binding().characterKey(),
                    "presentation edits preserve the exact stable server/world/character tuple");
            assertEquals(before.binding(), after.binding(), "the configured agent association is unchanged");
            assertEquals("Archivist", oldServices.config().characterDisplayName(),
                    "the old active epoch retains its immutable display name");
            assertEquals("plain", oldServices.config().characterChatStyle().configValue());
            assertEquals("The Archivist", newServices.config().characterDisplayName());
            assertEquals("bracketed", newServices.config().characterChatStyle().configValue());
            assertEquals(java.util.List.of("Old Name"), oldServices.config().characterAliases());
            assertEquals(java.util.List.of("Old Name", "Keeper"), newServices.config().characterAliases());
        } finally {
            oldServices.retire();
            newServices.retire();
        }
    }

    private static void writeOwnerOnlyConfig(Path path, String displayName, String aliases, String style) throws IOException {
        StartupConfigTestFiles.write(path, Map.of(
                "serverId", "lifecycle-server", "worldId", "lifecycle-world", "characterId", "lifecycle-character",
                "agentId", "lifecycle-agent", "characterDisplayName", displayName, "characterAliases", aliases,
                "characterChatStyle", style));
    }

    private static CompanionConfig config(String serverId) {
        ConfiguredBinding binding = new ConfiguredBinding(serverId, "world", "character", "agent");
        return new CompanionConfig(URI.create("wss://example.invalid"), new byte[32], binding, "Hello {player}",
                "Archivist", org.worldspeaker.compat.InteractionPolicy.denyAll());
    }
}
