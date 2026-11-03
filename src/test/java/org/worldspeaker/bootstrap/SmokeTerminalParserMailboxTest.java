package org.worldspeaker.bootstrap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.PeerFacts;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;
import org.worldspeaker.integration.BoundCompanionClient;
import org.worldspeaker.integration.PeerHandshake;
import org.worldspeaker.runtime.GameLoopMailbox;
import org.worldspeaker.runtime.TickMetrics;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeTerminalParserMailboxTest {
    private static final String CHALLENGE = "ERERERERERERERERERERERERERERERERERERERERERE";
    private static final String REQUEST_ID = "00000000-0000-4000-8000-000000000071";
    private static final String INVOCATION_ID = "00000000-0000-4000-8000-000000000072";
    private static final String CHARACTER_ID = "character-test";
    private static final String PLAYER_ID = "00000000-0000-4000-8000-000000000001";
    private static final long SERVICE_GENERATION = 7;

    @Test
    void authenticatedParserRejectsInvalidAnswersBeforeMailboxAndLaterValidAnswerCompletes() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        TickMetrics metrics = new TickMetrics();
        mailbox.publishActive(new GameLoopMailbox.ActiveRequest(REQUEST_ID, INVOCATION_ID, CHARACTER_ID, PLAYER_ID, SERVICE_GENERATION));
        SmokeConversation conversation = new SmokeConversation(null, null, null, null, mailbox, metrics, SERVICE_GENERATION);

        AuthenticatedClient fixture = authenticatedClient(conversation);
        try {
            send(fixture, answerFrame("invalid-control", "bad\u0001answer"));
            assertNull(mailbox.peekTerminal(), "U+0001 is rejected before it can reserve the terminal slot");
            assertFalse(mailbox.takeUnavailable());

            send(fixture, answerFrame("bidi-only", "\u202e"));
            assertNull(mailbox.peekTerminal(), "the QA02 sanitizer-empty body is rejected before reservation");
            send(fixture, answerFrame("format-only", "\u00a7c"));
            assertNull(mailbox.peekTerminal(), "formatting-only output is not renderable");

            JsonObject wrongRequest = answerFrame("wrong-request", "otherwise valid answer");
            wrongRequest.addProperty("requestId", "00000000-0000-4000-8000-000000000073");
            send(fixture, wrongRequest);
            JsonObject wrongAudience = answerFrame("wrong-audience", "otherwise valid answer");
            wrongAudience.getAsJsonObject("replyAudience").addProperty("playerId", "00000000-0000-4000-8000-000000000074");
            send(fixture, wrongAudience);
            assertNull(mailbox.peekTerminal(), "wrong request/player identity cannot reserve the active slot");
            assertFalse(mailbox.takeUnavailable());

            JsonObject laterValidAnswer = answerFrame("valid-after-invalid", "later valid answer");
            send(fixture, laterValidAnswer);
            GameLoopMailbox.TerminalFrame accepted = mailbox.peekTerminal();
            assertNotNull(accepted);
            assertEquals(new GameLoopMailbox.TerminalFrame(REQUEST_ID, INVOCATION_ID, CHARACTER_ID, PLAYER_ID,
                    uuid("valid-after-invalid"), "answer",
                    "later valid answer", SERVICE_GENERATION, accepted.receivedAtNanos()), accepted);
            send(fixture, answerFrame("invalid-after-valid", "\u202e"));
            assertEquals(accepted, mailbox.peekTerminal(), "an invalid frame after a valid terminal is not a conflict");
            send(fixture, laterValidAnswer);
            assertEquals(accepted, mailbox.peekTerminal(), "an exact valid replay remains deduplicated");
            assertFalse(mailbox.takeUnavailable(), "rejected invalid frames did not create a valid-terminal conflict");

            GameLoopMailbox.TerminalFrame terminal = mailbox.takeTerminal();
            assertNotNull(terminal);
            SmokeRequestLifecycle<Object> lifecycle = new SmokeRequestLifecycle<>();
            Object request = new Object();
            lifecycle.register(request, new Object());
            assertTrue(lifecycle.start(request));
            AtomicReference<String> published = new AtomicReference<>();
            SmokeAnswerDelivery.Outcome outcome = SmokeAnswerDelivery.publishThenComplete("Archivist", terminal.text(),
                    rendered -> VanillaDialogue.publishRendered(rendered, (line, overlay) -> {
                        assertFalse(overlay);
                        published.set(line);
                    }),
                    () -> lifecycle.complete(request), () -> {});

            assertEquals(SmokeAnswerDelivery.Outcome.COMPLETED, outcome);
            assertEquals("[Worldspeaker] Archivist: later valid answer", published.get());
            assertFalse(lifecycle.isActive(request));
            assertFalse(mailbox.takeUnavailable());
        } finally {
            fixture.client.close();
        }
    }

    private static void send(AuthenticatedClient fixture, JsonObject frame) {
        fixture.client.onText(fixture.socket, frame.toString(), true).toCompletableFuture().join();
    }

    private static AuthenticatedClient authenticatedClient(SmokeConversation conversation) {
        byte[] secret = new byte[32];
        for (int index = 0; index < secret.length; index++) secret[index] = (byte) index;
        ConfiguredBinding binding = new ConfiguredBinding("server-test", "world-test", "character-test", "agent-test");
        CompanionConfig config = new CompanionConfig(URI.create("ws://127.0.0.1:18473"), secret, binding, "Hello {player}",
                "Archivist", org.worldspeaker.compat.InteractionPolicy.denyAll());
        RuntimeFacts local = new RuntimeFacts(
                DeploymentFacts.MINECRAFT_VERSION, DeploymentFacts.FABRIC_LOADER_VERSION, DeploymentFacts.FABRIC_API_VERSION,
                Integer.parseInt(DeploymentFacts.JAVA_MAJOR), DeploymentFacts.JAVA_VENDOR, DeploymentFacts.JAVA_RUNTIME_VERSION);
        PeerFacts peer = new PeerFacts(
                DeploymentFacts.DEPLOYMENT_ID, DeploymentFacts.MANIFEST_SHA256, DeploymentFacts.PROTOCOL,
                DeploymentFacts.OPENCLAW_VERSION, DeploymentFacts.NODE_VERSION, DeploymentFacts.COMPANION_VERSION,
                binding.serverId(), binding.worldId(), binding.characterId(), binding.agentId());
        CompatibilityGate gate = new CompatibilityGate(binding);
        gate.checkLocal(local);

        AtomicReference<String> sentFrame = new AtomicReference<>();
        WebSocket socket = fakeWebSocket(sentFrame);
        BoundCompanionClient client = new BoundCompanionClient(config, local, gate, conversation::onDialogueMessage, ignored -> {});
        client.onOpen(socket);
        client.onText(socket, challengeFrame(config, peer).toString(), true).toCompletableFuture().join();
        String helloText = awaitFrame(sentFrame);
        JsonObject hello = JsonParser.parseString(helloText).getAsJsonObject();
        String nonce = hello.get("nonce").getAsString();
        client.onText(socket, welcomeFrame(config, peer, nonce).toString(), true).toCompletableFuture().join();
        assertTrue(client.isReady(), "the fake peer completed the production authenticated parser setup");
        return new AuthenticatedClient(client, socket);
    }

    private static JsonObject challengeFrame(CompanionConfig config, PeerFacts peer) {
        JsonObject frame = peerFields(peer);
        frame.addProperty("type", "challenge");
        frame.addProperty("challenge", CHALLENGE);
        frame.addProperty("proof", PeerHandshake.serverChallengeProof(config, CHALLENGE, peer));
        return frame;
    }

    private static JsonObject welcomeFrame(CompanionConfig config, PeerFacts peer, String nonce) {
        JsonObject frame = peerFields(peer);
        frame.addProperty("type", "welcome");
        frame.addProperty("challenge", CHALLENGE);
        frame.addProperty("nonce", nonce);
        frame.addProperty("proof", PeerHandshake.serverProof(config, CHALLENGE, nonce, peer, peer.nodeVersion()));
        return frame;
    }

    private static JsonObject peerFields(PeerFacts peer) {
        JsonObject frame = new JsonObject();
        frame.addProperty("deploymentId", peer.deploymentId());
        frame.addProperty("manifestSha256", peer.manifestSha256());
        frame.addProperty("protocol", peer.protocol());
        frame.addProperty("hostVersion", peer.hostVersion());
        frame.addProperty("nodeVersion", peer.nodeVersion());
        frame.addProperty("companionVersion", peer.companionVersion());
        frame.addProperty("serverId", peer.serverId());
        frame.addProperty("worldId", peer.worldId());
        frame.addProperty("characterId", peer.characterId());
        frame.addProperty("agentId", peer.agentId());
        frame.addProperty("configurationHandshakeVersion", peer.configurationHandshakeVersion());
        frame.addProperty("configSetSha256", peer.configSetSha256());
        return frame;
    }

    private static JsonObject answerFrame(String messageId, String text) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "dialogue.message");
        frame.addProperty("protocol", DeploymentFacts.PROTOCOL);
        frame.addProperty("messageId", uuid(messageId));
        frame.addProperty("requestId", REQUEST_ID);
        frame.addProperty("invocationId", INVOCATION_ID);
        frame.addProperty("characterId", CHARACTER_ID);
        JsonObject audience = new JsonObject();
        audience.addProperty("kind", "player");
        audience.addProperty("playerId", PLAYER_ID);
        frame.add("replyAudience", audience);
        frame.addProperty("kind", "answer");
        frame.addProperty("text", text);
        return frame;
    }

    private static String uuid(String value) {
        return java.util.UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static WebSocket fakeWebSocket(AtomicReference<String> sentFrame) {
        return (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(), new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                    if ("sendText".equals(method.getName())) {
                        sentFrame.set(args[0].toString());
                        return CompletableFuture.completedFuture(proxy);
                    }
                    if ("request".equals(method.getName())) return proxy;
                    if ("sendClose".equals(method.getName())) return CompletableFuture.completedFuture(proxy);
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
    }

    private static String awaitFrame(AtomicReference<String> frame) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (frame.get() == null && System.nanoTime() < deadline) Thread.onSpinWait();
        assertNotNull(frame.get(), "the fake socket receives the production handshake frame");
        return frame.get();
    }

    private record AuthenticatedClient(BoundCompanionClient client, WebSocket socket) {}
}
