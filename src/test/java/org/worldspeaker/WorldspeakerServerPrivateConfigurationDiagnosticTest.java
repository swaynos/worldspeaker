package org.worldspeaker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.compat.PrivateConfigurationException;
import org.worldspeaker.compat.PrivateConfigurationProblem;
import org.worldspeaker.compat.StartupConfig;
import org.worldspeaker.compat.StartupConfigTestFiles;
import org.worldspeaker.runtime.GameLoopMailbox;
import org.worldspeaker.runtime.ThreadBoundaryTestAccess;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Actual private-file loader -> bootstrap mailbox -> production diagnostic conversion/lane; no Fabric server is claimed. */
class WorldspeakerServerPrivateConfigurationDiagnosticTest {
    @TempDir Path temporaryDirectory;

    @Test
    void actualV1FailuresReachOnlyTheBoundedPrivateDiagnosticLaneWithSafeAllowlistedFields() throws Exception {
        ThreadBoundaryTestAccess.clearForTests();
        String credentialCanary = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(randomCredentialBytes());
        String unknownKeyCanary = "unknownKeyCanaryMustNotBeLogged";
        String agentCanary = "agentValueCanary\nWS-COMPAT-INJECTED";
        String styleCanary = "styleValueCanary\nWS-COMPAT-INJECTED";
        List<String> privateLogs = new ArrayList<>();
        List<Profile> profiles = List.of(
                new Profile("port", bundle -> bundle.getAsJsonObject("companion").addProperty("port", 65_536),
                        "companion.port", "integer 1024..65535"),
                new Profile("agent-type", bundle -> bundle.getAsJsonObject("binding").add("agentId", new JsonArray()),
                        "binding.agentId", "ASCII string [A-Za-z0-9._:-], 1..128 characters"),
                new Profile("attention-bound", bundle -> {
                    JsonObject attention = new JsonObject();
                    attention.addProperty("maxEvents", 65);
                    bundle.getAsJsonObject("server").add("attention", attention);
                }, "server.attention.maxEvents", "integer 1..64"),
                new Profile("denial-ability", bundle -> {
                    JsonObject denial = new JsonObject();
                    denial.addProperty("id", "denial-fixture");
                    denial.addProperty("abilityId", "unknownAbilityCanaryMustNotBeLogged");
                    denial.addProperty("contractVersion", 1);
                    JsonArray denials = new JsonArray();
                    denials.add(denial);
                    bundle.getAsJsonObject("abilityPolicy").add("denials", denials);
                }, "abilityPolicy.denials[].abilityId", "known observe_player or strike_lightning ability v1"),
                new Profile("unknown-key", bundle -> bundle.addProperty(unknownKeyCanary, "unknownValueCanary"),
                        "unknown-key", "only fields defined by startup schema v1"),
                new Profile("injected-style", bundle -> bundle.getAsJsonObject("server").addProperty("characterChatStyle", styleCanary),
                        "server.characterChatStyle", "plain or bracketed"),
                new Profile("agent-value", bundle -> bundle.getAsJsonObject("binding").addProperty("agentId", agentCanary),
                        "binding.agentId", "ASCII string [A-Za-z0-9._:-], 1..128 characters"));

        ServerServices services = new ServerServices(temporaryDirectory.resolve("unused.properties"), new Object());
        WorldspeakerServer.activateServices(services);
        try {
            for (Profile profile : profiles) {
                Path bootstrapPath = StartupConfigTestFiles.createPrivate(temporaryDirectory,
                        Map.of("bearerSecret", credentialCanary));
                Path bundlePath = bootstrapPath.getParent().resolve("startup.json");
                JsonObject bundle = JsonParser.parseString(Files.readString(bundlePath, StandardCharsets.UTF_8)).getAsJsonObject();
                profile.mutate.accept(bundle);
                Files.writeString(bundlePath, new Gson().toJson(bundle), StandardCharsets.UTF_8);
                Files.setPosixFilePermissions(bundlePath, PosixFilePermissions.fromString("rw-------"));

                PrivateConfigurationException failure;
                try {
                    StartupConfig.load(bootstrapPath);
                    fail("invalid profile unexpectedly loaded: " + profile.name);
                    return;
                } catch (PrivateConfigurationException invalid) {
                    failure = invalid;
                }
                PrivateConfigurationProblem problem = failure.problem();
                assertEquals(profile.field, problem.field().label(), profile.name);
                assertEquals(profile.expected, problem.expected().description(), profile.name);
                assertTrue(failure.getMessage().contains(problem.code().wireCode()), profile.name);
                assertFalse(failure.getMessage().contains(credentialCanary), profile.name);
                assertFalse(failure.getMessage().contains(unknownKeyCanary), profile.name);
                assertFalse(failure.getMessage().contains(agentCanary), profile.name);
                assertFalse(failure.getMessage().contains(styleCanary), profile.name);

                AtomicReference<String> loggedLine = new AtomicReference<>();
                CountDownLatch logPublished = new CountDownLatch(1);
                Consumer<String> privateSink = line -> {
                    loggedLine.set(line);
                    logPublished.countDown();
                };
                GameLoopMailbox.BootstrapResult offered = GameLoopMailbox.BootstrapResult.problem(problem);
                assertTrue(services.mailbox().offerBootstrap(offered), profile.name + " typed problem enters mailbox");
                GameLoopMailbox.BootstrapResult delivered = services.mailbox().takeBootstrap();
                assertNotNull(delivered, profile.name);
                assertSame(problem, delivered.problem(), profile.name + " mailbox carries immutable problem data, not a Throwable");
                assertTrue(WorldspeakerServer.queueBootstrapDiagnostic(services, delivered, privateSink), profile.name);
                assertTrue(logPublished.await(2, TimeUnit.SECONDS), profile.name + " private log is published off-thread");
                String line = loggedLine.get();
                assertNotNull(line, profile.name);
                assertTrue(line.contains("field=" + profile.field), profile.name);
                assertTrue(line.contains(profile.expected), profile.name);
                assertTrue(line.contains("coordinate a restart") || line.contains("remove unknown fields"), profile.name);
                assertFalse(line.contains(credentialCanary), profile.name);
                assertFalse(line.contains(unknownKeyCanary), profile.name);
                assertFalse(line.contains("unknownValueCanary"), profile.name);
                assertFalse(line.contains("unknownAbilityCanaryMustNotBeLogged"), profile.name);
                assertFalse(line.contains(agentCanary), profile.name);
                assertFalse(line.contains(styleCanary), profile.name);
                privateLogs.add(line);
            }
            String allPrivateOutput = String.join("\n", privateLogs);
            for (String canary : List.of(credentialCanary, unknownKeyCanary, agentCanary, styleCanary,
                    "unknownValueCanary", "unknownAbilityCanaryMustNotBeLogged")) {
                assertFalse(allPrivateOutput.contains(canary), "private diagnostic/logger output excludes " + canary);
            }
            assertFalse(allPrivateOutput.contains(temporaryDirectory.toString()), "private diagnostics never include real paths");
            assertFalse(services.diagnosticIo().isOwnerThread(), "test caller is not the private logging lane owner");
        } finally {
            WorldspeakerServer.activateServices(null);
            services.retire();
            assertTrue(services.diagnosticIo().awaitTermination(2, TimeUnit.SECONDS));
            ThreadBoundaryTestAccess.clearForTests();
        }
    }

    @Test
    void credentialHelperFailureNamesOnlyFileKindBoundsAndPinnedRuntimeAction() throws Exception {
        ThreadBoundaryTestAccess.clearForTests();
        String credentialCanary = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(randomCredentialBytes());
        Path bootstrapPath = StartupConfigTestFiles.createPrivate(temporaryDirectory,
                Map.of("bearerSecret", credentialCanary));
        Path credentialPath = bootstrapPath.getParent().resolve("credential.txt");
        StartupConfig verifiedSnapshot = StartupConfig.load(bootstrapPath);
        assertEquals("StartupConfig[validated local startup snapshot]", verifiedSnapshot.toString());
        Files.setPosixFilePermissions(credentialPath, PosixFilePermissions.fromString("rw-r-----"));

        AtomicReference<ProcessHandle> credentialChild = new AtomicReference<>();
        PrivateConfigurationException failure = StartupConfigTestFiles.loadWithVerifiedFilesAndCredentialFailure(
                bootstrapPath, verifiedSnapshot, credentialChild::set);
        PrivateConfigurationProblem problem = failure.problem();
        assertNotNull(credentialChild.get(), "the one configured credential read launches the actual packaged helper");
        assertFalse(credentialChild.get().isAlive(), "the actual helper child is reaped after credential rejection");
        assertEquals(PrivateConfigurationProblem.Code.PRIVATE_FILE_INVALID, problem.code());
        assertEquals("credentialFile", problem.field().label());
        assertTrue(problem.expected().description().contains("0600 regular single-link file"));
        assertTrue(problem.expected().description().contains("0700 parent"));
        assertTrue(problem.expected().description().contains("1..86 bytes"));
        assertTrue(problem.action().instruction().contains("Node.js 24.22.1"));
        assertFalse(failure.getMessage().contains(credentialPath.toString()));
        assertFalse(failure.getMessage().contains(credentialCanary));

        ServerServices services = new ServerServices(temporaryDirectory.resolve("unused-credential.properties"), new Object());
        WorldspeakerServer.activateServices(services);
        try {
            CountDownLatch published = new CountDownLatch(1);
            AtomicReference<String> logLine = new AtomicReference<>();
            assertTrue(services.mailbox().offerBootstrap(GameLoopMailbox.BootstrapResult.problem(problem)));
            GameLoopMailbox.BootstrapResult delivered = services.mailbox().takeBootstrap();
            assertNotNull(delivered);
            assertSame(problem, delivered.problem());
            assertTrue(WorldspeakerServer.queueBootstrapDiagnostic(services, delivered, line -> {
                logLine.set(line);
                published.countDown();
            }));
            assertTrue(published.await(2, TimeUnit.SECONDS));
            assertTrue(logLine.get().contains("field=credentialFile"));
            assertTrue(logLine.get().contains("0600 regular single-link file, 0700 parent, 1..86 bytes"));
            assertTrue(logLine.get().contains("Node.js 24.22.1"));
            assertFalse(logLine.get().contains(credentialPath.toString()));
            assertFalse(logLine.get().contains(credentialCanary));
        } finally {
            WorldspeakerServer.activateServices(null);
            services.retire();
            assertTrue(services.diagnosticIo().awaitTermination(2, TimeUnit.SECONDS));
            ThreadBoundaryTestAccess.clearForTests();
        }
    }

    @Test
    void productionOffTickLoadReportsDeterministicConfigFileFailureOnlyToPrivateLane() throws Exception {
        ThreadBoundaryTestAccess.clearForTests();
        String credentialCanary = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(randomCredentialBytes());
        Path bootstrapPath = StartupConfigTestFiles.createPrivate(temporaryDirectory,
                Map.of("bearerSecret", credentialCanary));
        Path bundlePath = bootstrapPath.getParent().resolve("startup.json");
        // The bootstrap remains valid and is read first by the actual Node helper. The next
        // configured file is deliberately non-owner-only, making the loader's field result
        // deterministic without relying on process-start scheduling or an injected timeout.
        Files.setPosixFilePermissions(bundlePath, PosixFilePermissions.fromString("rw-r-----"));

        AtomicInteger privateLogCount = new AtomicInteger();
        AtomicInteger shutdownLogCount = new AtomicInteger();
        AtomicReference<String> privateLog = new AtomicReference<>();
        AtomicReference<Boolean> privateLogOnOwner = new AtomicReference<>(false);
        CountDownLatch privateLogPublished = new CountDownLatch(1);
        ServerServices services = new ServerServices(bootstrapPath, new Object(), ignored -> shutdownLogCount.incrementAndGet());
        WorldspeakerServer.activateServices(services);
        try {
            WorldspeakerServer.loadConfigOffThread(services);
            GameLoopMailbox.BootstrapResult result = awaitBootstrapResult(services);
            assertNull(result.config());
            assertNotNull(result.problem());
            assertEquals(PrivateConfigurationProblem.Code.PRIVATE_FILE_INVALID, result.problem().code());
            assertEquals("configFile", result.problem().field().label());
            assertTrue(result.problem().expected().description().contains("0600 regular single-link file"));
            assertTrue(result.problem().action().instruction().contains("Node.js 24.22.1"));

            assertTrue(WorldspeakerServer.queueBootstrapDiagnostic(services, result, line -> {
                privateLogCount.incrementAndGet();
                privateLog.set(line);
                privateLogOnOwner.set(services.diagnosticIo().isOwnerThread());
                privateLogPublished.countDown();
            }));
            assertTrue(privateLogPublished.await(2, TimeUnit.SECONDS));
            String line = privateLog.get();
            assertNotNull(line);
            assertTrue(privateLogOnOwner.get(), "private diagnostic publication stays on the bounded owner lane");
            assertTrue(line.contains("field=configFile"));
            assertTrue(line.contains("0600 regular single-link file, 0700 parent, 1..32768 bytes"));
            assertTrue(line.contains("repair private startup bundle file"));
            assertFalse(line.contains(bootstrapPath.toString()));
            assertFalse(line.contains(bundlePath.toString()));
            assertFalse(line.contains(credentialCanary));
            assertEquals(1, privateLogCount.get(), "only the private diagnostic sink receives the failure");
            assertEquals(0, shutdownLogCount.get(), "ordinary config diagnostics do not touch the shutdown-only sink");

            services.retire();
            assertFalse(WorldspeakerServer.queueBootstrapDiagnostic(services, result, ignored -> privateLogCount.incrementAndGet()),
                    "retired normal diagnostics are dropped");
            assertEquals(1, privateLogCount.get(), "retirement does not replay a normal diagnostic");
            assertEquals(0, shutdownLogCount.get(), "normal retirement leaves the one-shot shutdown sink untouched");
        } finally {
            WorldspeakerServer.activateServices(null);
            services.retire();
            assertTrue(services.diagnosticIo().awaitTermination(2, TimeUnit.SECONDS));
            ThreadBoundaryTestAccess.clearForTests();
        }
    }

    private static GameLoopMailbox.BootstrapResult awaitBootstrapResult(ServerServices services) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            GameLoopMailbox.BootstrapResult result = services.mailbox().takeBootstrap();
            if (result != null) return result;
            Thread.onSpinWait();
        }
        return fail("the actual off-tick configuration reader did not publish its bounded result");
    }

    private static byte[] randomCredentialBytes() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private record Profile(String name, Consumer<JsonObject> mutate, String field, String expected) {}
}
