package org.worldspeaker.integration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.PeerFacts;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundCompanionClientJdkLoopbackTest {
    private static final String CHALLENGE = "ERERERERERERERERERERERERERERERERERERERERERE";
    private static final int PEER_READ_LIMIT = 32;

    @RepeatedTest(5)
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void actualJdkWebSocketClientStaysReadyWhenLoopbackPeerPausesAfterThirtyTwoLargeTextFrames() throws Exception {
        Fixture fixture = fixture();
        try (PausedLoopbackPeer peer = new PausedLoopbackPeer(fixture.config(), fixture.peer())) {
            peer.start();
            CompanionConfig config = new CompanionConfig(
                    URI.create("ws://127.0.0.1:" + peer.port()), fixture.secret(), fixture.binding(), "Hello {player}",
                    "Archivist", org.worldspeaker.compat.InteractionPolicy.denyAll());
            CompatibilityGate gate = new CompatibilityGate(fixture.binding());
            gate.checkLocal(fixture.local());
            BoundCompanionClient client = new BoundCompanionClient(config, fixture.local(), gate, ignored -> {}, ignored -> {});
            try {
                client.start();
                awaitReady(client);

                String largePlayerName = "p".repeat(14_000);
                int accepted = 0;
                int rejected = 0;
                int rejectedInARowAfterPause = 0;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
                for (int index = 0; index < 5_000 && System.nanoTime() < deadline; index++) {
                    boolean submitted = client.sendDialogueRequest(
                            "loopback-" + index, "player", largePlayerName, "1", "turn", null, null);
                    if (submitted) {
                        accepted++;
                        rejectedInARowAfterPause = 0;
                    } else {
                        rejected++;
                        if (peer.pausedAtReadLimit.getCount() == 0) rejectedInARowAfterPause++;
                        Thread.sleep(1);
                    }
                    assertTrue(client.isReady(), "a JDK IllegalStateException must not tear down the authenticated connection");
                    if (peer.pausedAtReadLimit.getCount() == 0 && rejectedInARowAfterPause >= 40) break;
                }

                assertTrue(peer.pausedAtReadLimit.await(5, TimeUnit.SECONDS), "loopback peer reached its deliberate no-read state");
                assertEquals(PEER_READ_LIMIT, peer.readFrames.get());
                assertTrue(accepted >= PEER_READ_LIMIT, "the real JDK WebSocket delivered the pre-pause frames");
                assertTrue(rejectedInARowAfterPause >= 40,
                        "the real JDK send remains pending long enough to fill the bounded waiter queue");
                assertTrue(rejected > 0, "bounded waiting capacity rejects excess frames rather than growing without limit");
                assertTrue(client.isReady(), "the authenticated JDK connection survives a stalled peer reader");
            } finally {
                client.close();
            }
        }
    }

    private static void awaitReady(BoundCompanionClient client) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!client.isReady() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(client.isReady(), "JDK loopback handshake completes");
    }

    private static Fixture fixture() {
        byte[] secret = new byte[32];
        for (int index = 0; index < secret.length; index++) secret[index] = (byte) index;
        ConfiguredBinding binding = new ConfiguredBinding("server-test", "world-test", "character-test", "agent-test");
        RuntimeFacts local = new RuntimeFacts(
                DeploymentFacts.MINECRAFT_VERSION, DeploymentFacts.FABRIC_LOADER_VERSION, DeploymentFacts.FABRIC_API_VERSION,
                Integer.parseInt(DeploymentFacts.JAVA_MAJOR), DeploymentFacts.JAVA_VENDOR, DeploymentFacts.JAVA_RUNTIME_VERSION);
        PeerFacts peer = new PeerFacts(
                DeploymentFacts.DEPLOYMENT_ID, DeploymentFacts.MANIFEST_SHA256, DeploymentFacts.PROTOCOL,
                DeploymentFacts.OPENCLAW_VERSION, DeploymentFacts.NODE_VERSION, DeploymentFacts.COMPANION_VERSION,
                binding.serverId(), binding.worldId(), binding.characterId(), binding.agentId());
        CompanionConfig config = new CompanionConfig(URI.create("ws://127.0.0.1:1"), secret, binding, "Hello {player}",
                "Archivist", org.worldspeaker.compat.InteractionPolicy.denyAll());
        return new Fixture(secret, binding, local, peer, config);
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

    private record Fixture(byte[] secret, ConfiguredBinding binding, RuntimeFacts local, PeerFacts peer, CompanionConfig config) {}

    private static final class PausedLoopbackPeer implements AutoCloseable {
        private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        private final CompanionConfig config;
        private final PeerFacts peer;
        private final ServerSocket listener;
        private final CountDownLatch pausedAtReadLimit = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger readFrames = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile Socket acceptedSocket;
        private Thread thread;

        private PausedLoopbackPeer(CompanionConfig config, PeerFacts peer) throws IOException {
            this.config = config;
            this.peer = peer;
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            listener.setReceiveBufferSize(1_024);
        }

        private int port() {
            return listener.getLocalPort();
        }

        private void start() {
            thread = new Thread(this::serve, "worldspeaker-jdk-loopback-peer");
            thread.setDaemon(true);
            thread.start();
        }

        private void serve() {
            try (Socket socket = listener.accept()) {
                acceptedSocket = socket;
                socket.setReceiveBufferSize(1_024);
                socket.setTcpNoDelay(true);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                OutputStream output = socket.getOutputStream();
                String request = readHttpHeaders(input);
                String key = headerValue(request, "Sec-WebSocket-Key");
                String accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII)));
                output.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();

                JsonObject challenge = peerFields(peer);
                challenge.addProperty("type", "challenge");
                challenge.addProperty("challenge", CHALLENGE);
                challenge.addProperty("proof", PeerHandshake.serverChallengeProof(config, CHALLENGE, peer));
                writeServerText(output, challenge.toString());

                JsonObject hello = JsonParser.parseString(readClientText(input)).getAsJsonObject();
                String nonce = hello.get("nonce").getAsString();
                JsonObject welcome = peerFields(peer);
                welcome.addProperty("type", "welcome");
                welcome.addProperty("challenge", CHALLENGE);
                welcome.addProperty("nonce", nonce);
                welcome.addProperty("proof", PeerHandshake.serverProof(config, CHALLENGE, nonce, peer, peer.nodeVersion()));
                writeServerText(output, welcome.toString());

                for (int index = 0; index < PEER_READ_LIMIT; index++) {
                    readClientText(input);
                    readFrames.incrementAndGet();
                }
                pausedAtReadLimit.countDown();
                release.await(); // Deliberately do not read any more client data.
            } catch (Throwable exception) {
                if (release.getCount() != 0) failure.compareAndSet(null, exception);
                pausedAtReadLimit.countDown();
            }
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            listener.close();
            Socket socket = acceptedSocket;
            if (socket != null) socket.close();
            if (thread != null) thread.join(2_000);
            Throwable thrown = failure.get();
            if (thrown != null) throw new AssertionError("loopback peer failed", thrown);
        }

        private static String readHttpHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int matched = 0;
            while (bytes.size() < 16 * 1024) {
                int value = input.read();
                if (value < 0) throw new EOFException("HTTP upgrade request ended early");
                bytes.write(value);
                matched = switch (matched) {
                    case 0 -> value == '\r' ? 1 : 0;
                    case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                    case 2 -> value == '\r' ? 3 : 0;
                    case 3 -> value == '\n' ? 4 : 0;
                    default -> matched;
                };
                if (matched == 4) return bytes.toString(StandardCharsets.US_ASCII);
            }
            throw new IOException("HTTP upgrade headers exceed test limit");
        }

        private static String headerValue(String headers, String name) throws IOException {
            for (String line : headers.split("\\r\\n")) {
                int separator = line.indexOf(':');
                if (separator > 0 && line.substring(0, separator).equalsIgnoreCase(name)) return line.substring(separator + 1).trim();
            }
            throw new IOException("missing HTTP header " + name);
        }

        private static String readClientText(DataInputStream input) throws IOException {
            while (true) {
                int first = input.readUnsignedByte();
                int second = input.readUnsignedByte();
                int opcode = first & 0x0f;
                boolean masked = (second & 0x80) != 0;
                long length = second & 0x7f;
                if (length == 126) length = input.readUnsignedShort();
                else if (length == 127) length = input.readLong();
                if (length < 0 || length > 64 * 1024) throw new IOException("invalid test frame length " + length);
                byte[] mask = masked ? input.readNBytes(4) : new byte[0];
                if (masked && mask.length != 4) throw new EOFException("truncated client mask");
                byte[] payload = input.readNBytes((int) length);
                if (payload.length != length) throw new EOFException("truncated client frame");
                if (masked) for (int index = 0; index < payload.length; index++) payload[index] ^= mask[index & 3];
                if (opcode == 0x1) return new String(payload, StandardCharsets.UTF_8);
                if (opcode == 0x8) throw new EOFException("client closed before peer read limit");
            }
        }

        private static void writeServerText(OutputStream output, String text) throws IOException {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            output.write(0x81);
            if (payload.length < 126) {
                output.write(payload.length);
            } else if (payload.length <= 0xffff) {
                output.write(126);
                output.write((payload.length >>> 8) & 0xff);
                output.write(payload.length & 0xff);
            } else {
                output.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) output.write((int) (payload.length >>> shift) & 0xff);
            }
            output.write(payload);
            output.flush();
        }
    }
}
