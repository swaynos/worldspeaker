package org.worldspeaker.integration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.worldspeaker.bootstrap.AttributedEvent;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.InteractionPolicy;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises the Java client against the compiled production TypeScript transport on loopback. */
class BoundCompanionCancellationContractTest {
    private static final String SERVER_EPOCH = "00000000-0000-4000-8000-000000000019";
    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void javaCancellationUsesV5IdentityAndCannotReviveOrReplayAcrossReplacementPeer() throws Exception {
        String nodeVersion = readNodeVersion();
        assumeTrue(DeploymentFacts.NODE_VERSION.equals(nodeVersion),
                "the real transport handshake requires pinned Node " + DeploymentFacts.NODE_VERSION +
                        "; local Node is " + nodeVersion);
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        assertTrue(Files.isRegularFile(repository.resolve("companion/dist/transport.js")),
                "build the real TypeScript transport first with npm --prefix companion run build");

        int port = availablePort();
        byte[] secret = new byte[32];
        for (int index = 0; index < secret.length; index++) secret[index] = (byte) index;
        String encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        ConfiguredBinding binding = new ConfiguredBinding("server-test", "world-test", "character-test", "agent-test");
        RuntimeFacts local = new RuntimeFacts(DeploymentFacts.MINECRAFT_VERSION,
                DeploymentFacts.FABRIC_LOADER_VERSION, DeploymentFacts.FABRIC_API_VERSION,
                Integer.parseInt(DeploymentFacts.JAVA_MAJOR), DeploymentFacts.JAVA_VENDOR,
                DeploymentFacts.JAVA_RUNTIME_VERSION);
        CompanionConfig config = new CompanionConfig(URI.create("ws://127.0.0.1:" + port), secret,
                binding, "Hello {player}", "Archivist", InteractionPolicy.denyAll());
        CompatibilityGate gate = new CompatibilityGate(binding);
        gate.checkLocal(local);
        AtomicInteger inboundMessages = new AtomicInteger();
        NodeTransportPeer node = new NodeTransportPeer(repository, port, encodedSecret);
        BoundCompanionClient client = new BoundCompanionClient(config, local, gate,
                ignored -> inboundMessages.incrementAndGet(), ignored -> {});
        try {
            node.awaitEvent(event -> "started".equals(string(event, "kind")), 3);
            client.start();
            JsonObject firstReady = node.awaitEvent(event -> "ready".equals(string(event, "kind")) &&
                    integer(event, "peerSerial") == 1, 3);
            assertEquals(DeploymentFacts.PROTOCOL, string(firstReady, "protocol"));
            awaitReady(client, 3);
            long originalGeneration = client.connectionGenerationForTests();
            assertTrue(originalGeneration >= 0);

            UUID requestId = UUID.randomUUID();
            UUID invocationId = distinctUuid(requestId);
            UUID playerId = UUID.fromString("00000000-0000-4000-8000-000000000001");
            AttributedEvent event = new AttributedEvent(binding.serverId(), binding.worldId(), binding.characterId(),
                    binding.agentId(), UUID.randomUUID(), UUID.fromString(SERVER_EPOCH), 1_793_275_200_000L,
                    System.nanoTime(), "live", "command", playerId, "Tester", "minecraft:overworld",
                    "Cancel this bounded request.");
            assertTrue(client.sendDialogueRequest(requestId.toString(), invocationId.toString(), event,
                    UUID.randomUUID().toString(), null, null));
            JsonObject request = node.awaitEvent(item -> "request".equals(string(item, "kind")) &&
                    requestId.toString().equals(string(item, "requestId")), 3);
            assertEquals(1, integer(request, "peerSerial"));
            assertEquals(DeploymentFacts.PROTOCOL, string(request, "protocol"));
            assertEquals(invocationId.toString(), string(request, "invocationId"));
            assertEquals(playerId.toString(), string(request, "playerId"));

            AtomicReference<BoundCompanionClient.CancellationDelivery> cancellationDelivery = new AtomicReference<>();
            CountDownLatch cancellationSent = new CountDownLatch(1);
            assertTrue(client.cancelDialogueRequest(requestId.toString(), invocationId.toString(), playerId.toString(),
                    originalGeneration, delivery -> {
                        cancellationDelivery.set(delivery);
                        cancellationSent.countDown();
                    }));
            assertTrue(cancellationSent.await(3, TimeUnit.SECONDS), "the local JDK cancellation send completes");
            assertEquals(BoundCompanionClient.CancellationDelivery.SEND_COMPLETED, cancellationDelivery.get());
            JsonObject cancel = node.awaitEvent(item -> "cancel".equals(string(item, "kind")) &&
                    requestId.toString().equals(string(item, "requestId")), 3);
            assertEquals(1, integer(cancel, "peerId"));
            assertEquals("dialogue.cancel", string(cancel, "type"));
            assertEquals("worldspeaker.compat.v5", string(cancel, "protocol"));
            assertEquals(invocationId.toString(), string(cancel, "invocationId"));
            assertEquals(playerId.toString(), string(cancel, "playerId"));
            assertEquals(List.of("invocationId", "playerId", "protocol", "requestId", "type"),
                    strings(cancel.getAsJsonArray("fields")));

            node.command("CHECK " + requestId);
            JsonObject liveness = node.awaitEvent(item -> "liveness".equals(string(item, "kind")), 3);
            assertFalse(liveness.get("live").getAsBoolean());
            assertTrue(liveness.get("cancelledRetained").getAsBoolean(),
                    "the production transport retains the canceled request identity on its original peer");
            assertFalse(liveness.get("requestRetained").getAsBoolean());

            UUID conflictingInvocation = distinctUuid(requestId, invocationId);
            CountDownLatch mismatchSent = new CountDownLatch(1);
            assertTrue(client.cancelDialogueRequest(requestId.toString(), conflictingInvocation.toString(),
                    playerId.toString(), originalGeneration, ignored -> mismatchSent.countDown()));
            assertTrue(mismatchSent.await(3, TimeUnit.SECONDS), "the conflicting cancel reaches the original transport");
            JsonObject mismatch = node.awaitEvent(item -> "cancel".equals(string(item, "kind")) &&
                    conflictingInvocation.toString().equals(string(item, "invocationId")), 3);
            assertEquals(1, integer(mismatch, "peerId"));
            assertEquals(requestId.toString(), string(mismatch, "requestId"));
            assertNotEquals(invocationId.toString(), string(mismatch, "invocationId"));

            JsonObject replacementReady = node.awaitEvent(item -> "ready".equals(string(item, "kind")) &&
                    integer(item, "peerSerial") == 2, 3);
            assertEquals("worldspeaker.compat.v5", string(replacementReady, "protocol"));
            awaitReady(client, 3);
            long replacementGeneration = client.connectionGenerationForTests();
            assertNotEquals(originalGeneration, replacementGeneration,
                    "the rejected attribution closed the first peer and Java authenticated a replacement generation");

            AtomicInteger staleCancelCallbacks = new AtomicInteger();
            assertFalse(client.cancelDialogueRequest(requestId.toString(), invocationId.toString(), playerId.toString(),
                    originalGeneration, ignored -> staleCancelCallbacks.incrementAndGet()),
                    "the old transport generation cannot queue its cancellation into a replacement peer");
            assertEquals(0, staleCancelCallbacks.get());

            node.command("FINAL " + requestId);
            JsonObject delayedFinal = node.awaitEvent(item -> "final".equals(string(item, "kind")), 3);
            assertFalse(delayedFinal.get("accepted").getAsBoolean(),
                    "the delayed final closure remains revoked after the replacement peer authenticates");
            assertEquals(0, inboundMessages.get(), "the canceled delayed final never reaches the Java dialogue sink");

            node.command("SNAPSHOT");
            JsonObject replacementState = node.awaitEvent(item -> "snapshot".equals(string(item, "kind")), 3);
            assertEquals(2, integer(replacementState, "readyPeerSerial"));
            assertTrue(replacementState.getAsJsonArray("peerRequestIds").isEmpty(),
                    "Java does not replay the old substantive request to a new authenticated peer");
            assertTrue(replacementState.getAsJsonArray("peerCancelledRequestIds").isEmpty(),
                    "Java does not replay the old cancellation to a new authenticated peer");
            assertEquals(0, integer(replacementState, "latestPeerCancelCount"));
            assertEquals(List.of(1, 1), integers(replacementState.getAsJsonArray("cancelPeerIds")),
                    "both valid and conflicting cancellations were confined to the original peer");
        } finally {
            client.close();
            node.close();
        }
    }

    private static void awaitReady(BoundCompanionClient client, int timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!client.isReady() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(client.isReady(), "the Java client authenticated the compiled TypeScript peer");
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static UUID distinctUuid(UUID... identities) {
        while (true) {
            UUID candidate = UUID.randomUUID();
            boolean duplicate = false;
            for (UUID identity : identities) duplicate |= candidate.equals(identity);
            if (!duplicate) return candidate;
        }
    }

    private static String readNodeVersion() throws Exception {
        Process version = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
        byte[] output = version.getInputStream().readNBytes(128);
        if (!version.waitFor(3, TimeUnit.SECONDS)) {
            version.destroyForcibly();
            return "unavailable";
        }
        String value = new String(output, StandardCharsets.UTF_8).trim();
        return value.startsWith("v") ? value.substring(1) : value;
    }

    private static String string(JsonObject object, String field) {
        return object.get(field).getAsString();
    }

    private static int integer(JsonObject object, String field) {
        return object.get(field).getAsInt();
    }

    private static List<String> strings(com.google.gson.JsonArray array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.getAsString()));
        return values;
    }

    private static List<Integer> integers(com.google.gson.JsonArray array) {
        List<Integer> values = new ArrayList<>();
        array.forEach(value -> values.add(value.getAsInt()));
        return values;
    }

    private static final class NodeTransportPeer implements AutoCloseable {
        private final CrossPeerFixtureProcess fixture;

        private NodeTransportPeer(Path repository, int port, String secret) throws IOException {
            fixture = CrossPeerFixtureProcess.start(repository, port, secret);
        }

        private JsonObject awaitEvent(Predicate<JsonObject> predicate, int timeoutSeconds) throws Exception {
            return fixture.awaitEvent(predicate, timeoutSeconds);
        }

        private void command(String command) throws IOException {
            fixture.command(command);
        }

        @Override
        public void close() {
            fixture.close();
        }
    }
}
