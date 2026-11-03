package org.worldspeaker.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.bootstrap.AttributedEvent;
import org.worldspeaker.bootstrap.AttentionBuffer;
import org.worldspeaker.compat.AbilityPolicy;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.GateState;
import org.worldspeaker.compat.StartupConfig;
import org.worldspeaker.compat.StartupConfigTestFiles;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Map;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Cross-runtime handshake tests using each runtime's actual strict file loader. */
class BoundCompanionStartupHandshakeContractTest {
    @TempDir Path temporaryDirectory;

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void javaAndTypescriptAuthenticateTheSameActualBundleAndCredentialFiles() throws Exception {
        Path repository = requireCompiledCompanion();
        int port = availablePort();
        Path bootstrap = createBundle(port, "Archivist");
        StartupConfig snapshot = StartupConfig.load(bootstrap);
        CrossPeerFixtureProcess node = CrossPeerFixtureProcess.start(repository, port, "", bundlePath(bootstrap));
        var gate = new CompatibilityGate(snapshot.serverConfig().binding(), snapshot.configSetSha256());
        gate.checkLocal(localFacts());
        AtomicReference<String> diagnostic = new AtomicReference<>();
        BoundCompanionClient javaClient = new BoundCompanionClient(snapshot.serverConfig(), localFacts(), gate,
                ignored -> {}, diagnostic::set);
        try {
            node.awaitStarted();
            javaClient.start();
            node.awaitEvent(event -> "ready".equals(event.get("kind").getAsString()), 5);
            awaitReady(javaClient, 5);
            assertEquals(GateState.READY, gate.snapshot().state());
            assertEquals(snapshot.configSetSha256(), snapshot.serverConfig().configSetSha256());
            assertEquals(null, diagnostic.get());
        } finally {
            javaClient.close();
            node.close();
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void authenticatedJavaRequestCarriesTheCapturedRevisionIntoRequesterScopedTypescriptDiscovery() throws Exception {
        Path repository = requireCompiledCompanion();
        int port = availablePort();
        Path bootstrap = createDiscoveryBundle(port);
        StartupConfig snapshot = StartupConfig.load(bootstrap);
        assertEquals(23, snapshot.abilityPolicy().revision());

        CrossPeerFixtureProcess node = CrossPeerFixtureProcess.start(repository, port, "", bundlePath(bootstrap));
        var gate = new CompatibilityGate(snapshot.serverConfig().binding(), snapshot.configSetSha256());
        gate.checkLocal(localFacts());
        BoundCompanionClient javaClient = new BoundCompanionClient(snapshot.serverConfig(), localFacts(), gate,
                ignored -> {}, ignored -> {});
        try {
            node.awaitStarted();
            javaClient.start();
            node.awaitEvent(event -> "ready".equals(event.get("kind").getAsString()), 5);
            awaitReady(javaClient, 5);

            AttributedEvent event = new AttributedEvent("fixture-server", "fixture-world", "fixture-character", "fixture-agent",
                    UUID.randomUUID(), UUID.randomUUID(), System.currentTimeMillis(), System.nanoTime(), "live", "command",
                    UUID.fromString(REQUESTER), "Requester", "minecraft:overworld", "Can you check this player?");
            JsonObject mismatchedRevision = DialogueEnvelope.request(REQUEST_ID, INVOCATION_ID, event,
                    CONVERSATION_ID, null, null, List.of(), disabledContext(), List.of(),
                    snapshot.abilityPolicy().revision() - 1);
            assertFalse(javaClient.sendDialogueRequest(mismatchedRevision, REQUESTER),
                    "the Java production send path refuses a frame from a different captured policy revision");

            assertTrue(javaClient.sendDialogueRequest(REQUEST_ID, INVOCATION_ID, event, CONVERSATION_ID, null, null));
            JsonObject received = node.awaitEvent(e -> "discovery".equals(e.get("kind").getAsString()) &&
                    REQUEST_ID.equals(e.get("requestId").getAsString()), 5);
            JsonObject discovery = received.getAsJsonObject("discovery");
            assertEquals(23, discovery.get("policyRevision").getAsLong());
            assertEquals(REQUEST_ID, discovery.get("requestId").getAsString());
            assertEquals(INVOCATION_ID, discovery.get("invocationId").getAsString());
            assertEquals("fixture-character", discovery.getAsJsonObject("binding").get("characterId").getAsString());

            JsonObject observe = discoveryEntry(discovery, AbilityPolicy.OBSERVE);
            assertEquals("UNIMPLEMENTED", observe.get("implementationStatus").getAsString());
            assertEquals("UNQUALIFIED", observe.get("qualificationStatus").getAsString());
            assertEquals("UNAVAILABLE", observe.get("availability").getAsString());
            assertEquals("conditional", observe.get("policyDisposition").getAsString());
            JsonArray grants = observe.getAsJsonArray("effectiveGrants");
            assertEquals(1, grants.size());
            assertEquals("observe-requester", grants.get(0).getAsJsonObject().get("grantId").getAsString());
            assertEquals(36, grants.get(0).getAsJsonObject().get("maxRangeBlocks").getAsInt());
            assertFalse(discovery.toString().contains(OTHER_REQUESTER));
            assertFalse(discovery.toString().contains("other-requester-private"));
            assertEquals(GateState.READY, gate.snapshot().state());
        } finally {
            javaClient.close();
            node.close();
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void actualNoGuidanceControlTemplateAuthenticatesAfterPresentationOnlyNonGodRename() throws Exception {
        Path repository = requireCompiledCompanion();
        int port = availablePort();
        Path bootstrap = createNoGuidanceControlTemplate(port, "Archivist");
        StartupConfig snapshot = StartupConfig.load(bootstrap);
        assertEquals("Archivist", snapshot.serverConfig().characterDisplayName());
        assertEquals("fixture-server", snapshot.serverConfig().binding().serverId());
        assertEquals("fixture-world", snapshot.serverConfig().binding().worldId());
        assertEquals("fixture-character", snapshot.serverConfig().binding().characterId());
        assertEquals("fixture-agent", snapshot.serverConfig().binding().agentId());
        assertTrue(snapshot.abilityPolicy().grants().isEmpty());
        assertFalse(snapshot.toString().contains("Archivist"), "configuration diagnostics do not carry presentation values");

        CrossPeerFixtureProcess node = CrossPeerFixtureProcess.start(repository, port, "", bundlePath(bootstrap));
        var gate = new CompatibilityGate(snapshot.serverConfig().binding(), snapshot.configSetSha256());
        gate.checkLocal(localFacts());
        AtomicReference<String> diagnostic = new AtomicReference<>();
        BoundCompanionClient javaClient = new BoundCompanionClient(snapshot.serverConfig(), localFacts(), gate,
                ignored -> {}, diagnostic::set);
        try {
            node.awaitStarted();
            javaClient.start();
            node.awaitEvent(event -> "ready".equals(event.get("kind").getAsString()), 5);
            awaitReady(javaClient, 5);
            assertEquals(GateState.READY, gate.snapshot().state());
            assertEquals(snapshot.configSetSha256(), snapshot.serverConfig().configSetSha256());
            assertEquals(null, diagnostic.get());
        } finally {
            javaClient.close();
            node.close();
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void sameCredentialWithDifferentActualBundleBytesNeverReachesReady() throws Exception {
        Path repository = requireCompiledCompanion();
        int port = availablePort();
        Path javaBootstrap = createBundle(port, "Archivist");
        Path typescriptBootstrap = createBundle(port, "The Archivist");
        StartupConfig javaSnapshot = StartupConfig.load(javaBootstrap);
        StartupConfig typescriptSnapshot = StartupConfig.load(typescriptBootstrap);
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(javaSnapshot.serverConfig().secret()),
                Base64.getUrlEncoder().withoutPadding().encodeToString(typescriptSnapshot.serverConfig().secret()));
        assertEquals(javaSnapshot.abilityPolicy().revision(), typescriptSnapshot.abilityPolicy().revision(),
                "the mismatch control keeps policy revision equal on both real startup loaders");
        assertFalse(javaSnapshot.configSetSha256().equals(typescriptSnapshot.configSetSha256()));

        CrossPeerFixtureProcess node = CrossPeerFixtureProcess.start(repository, port, "", bundlePath(typescriptBootstrap));
        var gate = new CompatibilityGate(javaSnapshot.serverConfig().binding(), javaSnapshot.configSetSha256());
        gate.checkLocal(localFacts());
        AtomicReference<String> diagnostic = new AtomicReference<>();
        BoundCompanionClient javaClient = new BoundCompanionClient(javaSnapshot.serverConfig(), localFacts(), gate,
                ignored -> {}, diagnostic::set);
        try {
            node.awaitStarted();
            javaClient.start();
            awaitDiagnostic(diagnostic, 5);
            assertFalse(javaClient.isReady());
            assertEquals(GateState.UNAVAILABLE, gate.snapshot().state());
            assertEquals("WS-COMPAT-HANDSHAKE-REJECTED", diagnostic.get());
        } finally {
            javaClient.close();
            node.close();
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void sameActualBundleWithDifferentCredentialBytesNeverReachesReady() throws Exception {
        Path repository = requireCompiledCompanion();
        int port = availablePort();
        Path bootstrap = createBundle(port, "Archivist");
        StartupConfig javaSnapshot = StartupConfig.load(bootstrap);
        Path credential = bootstrap.getParent().resolve("credential.txt");
        byte[] replacement = new byte[32];
        java.util.Arrays.fill(replacement, (byte) 0x5a);
        Files.writeString(credential, Base64.getUrlEncoder().withoutPadding().encodeToString(replacement));
        Files.setPosixFilePermissions(credential, PosixFilePermissions.fromString("rw-------"));
        assertEquals(javaSnapshot.configSetSha256(), StartupConfig.load(bootstrap).configSetSha256());

        CrossPeerFixtureProcess node = CrossPeerFixtureProcess.start(repository, port, "", bundlePath(bootstrap));
        var gate = new CompatibilityGate(javaSnapshot.serverConfig().binding(), javaSnapshot.configSetSha256());
        gate.checkLocal(localFacts());
        AtomicReference<String> diagnostic = new AtomicReference<>();
        BoundCompanionClient javaClient = new BoundCompanionClient(javaSnapshot.serverConfig(), localFacts(), gate,
                ignored -> {}, diagnostic::set);
        try {
            node.awaitStarted();
            javaClient.start();
            awaitDiagnostic(diagnostic, 5);
            assertFalse(javaClient.isReady());
            assertEquals(GateState.UNAVAILABLE, gate.snapshot().state());
            assertEquals("WS-COMPAT-HANDSHAKE-REJECTED", diagnostic.get());
        } finally {
            javaClient.close();
            node.close();
        }
    }

    private Path createBundle(int port, String displayName) throws Exception {
        return StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of(
                "endpoint", "ws://127.0.0.1:" + port,
                "companionPort", Integer.toString(port),
                "characterDisplayName", displayName));
    }

    private Path createNoGuidanceControlTemplate(int port, String displayName) throws Exception {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        Path directory = bootstrap.getParent();
        Path credential = directory.resolve("credential.txt");
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        Files.writeString(credential, Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        Files.setPosixFilePermissions(credential, PosixFilePermissions.fromString("rw-------"));

        String template = Files.readString(Path.of("presets/god/startup-config.no-guidance-control.json.template"));
        String rendered = template
                .replace("<SERVER_ID>", "fixture-server")
                .replace("<WORLD_ID>", "fixture-world")
                .replace("<CHARACTER_ID>", "fixture-character")
                .replace("<AGENT_ID>", "fixture-agent")
                .replace("<DEPLOYMENT_ID>", DeploymentFacts.DEPLOYMENT_ID)
                .replace("<DEPLOYMENT_MANIFEST_SHA256>", DeploymentFacts.MANIFEST_SHA256)
                .replace("/ABSOLUTE_PRIVATE_DIRECTORY/<CREDENTIAL_FILE>", credential.toString())
                .replace("ws://127.0.0.1:18473", "ws://127.0.0.1:" + port)
                .replace("\"port\": 18473", "\"port\": " + port)
                .replace("\"characterDisplayName\": \"God\"", "\"characterDisplayName\": \"" + displayName + "\"");
        Files.writeString(bundlePath(bootstrap), rendered);
        Files.setPosixFilePermissions(bundlePath(bootstrap), PosixFilePermissions.fromString("rw-------"));
        return bootstrap;
    }

    private Path createDiscoveryBundle(int port) throws Exception {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of(
                "endpoint", "ws://127.0.0.1:" + port,
                "companionPort", Integer.toString(port)));
        Path bundleFile = bundlePath(bootstrap);
        JsonObject bundle = JsonParser.parseString(Files.readString(bundleFile)).getAsJsonObject();
        JsonObject policy = new JsonObject();
        policy.addProperty("schemaVersion", 1);
        policy.addProperty("revision", 23);
        JsonObject requesterGrant = new JsonObject();
        requesterGrant.addProperty("id", "observe-requester");
        requesterGrant.addProperty("characterId", "fixture-character");
        requesterGrant.addProperty("abilityId", AbilityPolicy.OBSERVE);
        requesterGrant.addProperty("contractVersion", 1);
        JsonArray requesters = new JsonArray();
        requesters.add(REQUESTER);
        requesterGrant.add("requesterIds", requesters);
        JsonArray targets = new JsonArray();
        targets.add(TARGET);
        requesterGrant.add("otherTargetIds", targets);
        JsonArray dimensions = new JsonArray();
        dimensions.add("minecraft:overworld");
        requesterGrant.add("dimensions", dimensions);
        requesterGrant.addProperty("maxRangeBlocks", 36);

        JsonObject otherRequesterGrant = requesterGrant.deepCopy();
        otherRequesterGrant.addProperty("id", "other-requester-private");
        JsonArray otherRequesters = new JsonArray();
        otherRequesters.add(OTHER_REQUESTER);
        otherRequesterGrant.add("requesterIds", otherRequesters);
        JsonArray grants = new JsonArray();
        grants.add(requesterGrant);
        grants.add(otherRequesterGrant);
        policy.add("grants", grants);
        policy.add("denials", new JsonArray());
        JsonObject protection = new JsonObject();
        protection.addProperty("externalClaimsMode", "NONE");
        policy.add("protection", protection);
        bundle.add("abilityPolicy", policy);
        Files.writeString(bundleFile, bundle.toString());
        Files.setPosixFilePermissions(bundleFile, PosixFilePermissions.fromString("rw-------"));
        return bootstrap;
    }

    private static AttentionBuffer.ContextStatus disabledContext() {
        return new AttentionBuffer.ContextStatus("disabled", "0", 0, List.of(), java.util.Set.of());
    }

    private static JsonObject discoveryEntry(JsonObject discovery, String abilityId) {
        for (var rawEntry : discovery.getAsJsonArray("entries")) {
            JsonObject entry = rawEntry.getAsJsonObject();
            if (abilityId.equals(entry.getAsJsonObject("contract").get("abilityId").getAsString())) return entry;
        }
        throw new AssertionError("ability discovery did not include " + abilityId);
    }

    private static Path bundlePath(Path bootstrap) { return bootstrap.getParent().resolve("startup.json"); }

    private static final String REQUESTER = "00000000-0000-4000-8000-000000000041";
    private static final String OTHER_REQUESTER = "00000000-0000-4000-8000-000000000042";
    private static final String TARGET = "00000000-0000-4000-8000-000000000043";
    private static final String REQUEST_ID = "00000000-0000-4000-8000-000000000061";
    private static final String INVOCATION_ID = "00000000-0000-4000-8000-000000000062";
    private static final String CONVERSATION_ID = "00000000-0000-4000-8000-000000000063";

    private static Path requireCompiledCompanion() throws Exception {
        String nodeVersion = new String(new ProcessBuilder("node", "--version").redirectErrorStream(true).start()
                .getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim().replaceFirst("^v", "");
        assumeTrue(DeploymentFacts.NODE_VERSION.equals(nodeVersion),
                "cross-runtime configuration handshake requires pinned Node " + DeploymentFacts.NODE_VERSION + "; observed " + nodeVersion);
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        assumeTrue(Files.isRegularFile(repository.resolve("companion/dist/transport.js")),
                "build the actual TypeScript transport with npm --prefix companion test first");
        return repository;
    }

    private static RuntimeFacts localFacts() {
        return new RuntimeFacts(DeploymentFacts.MINECRAFT_VERSION, DeploymentFacts.FABRIC_LOADER_VERSION,
                DeploymentFacts.FABRIC_API_VERSION, Integer.parseInt(DeploymentFacts.JAVA_MAJOR),
                DeploymentFacts.JAVA_VENDOR, DeploymentFacts.JAVA_RUNTIME_VERSION);
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static void awaitReady(BoundCompanionClient client, int timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!client.isReady() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(client.isReady(), "real Java client reached authenticated READY");
    }

    private static void awaitDiagnostic(AtomicReference<String> diagnostic, int timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (diagnostic.get() == null && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(diagnostic.get() != null, "real Java client rejected the mismatched TypeScript peer");
    }
}
