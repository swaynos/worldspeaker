package org.worldspeaker.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.worldspeaker.bootstrap.AttributedEvent;
import org.worldspeaker.bootstrap.AttentionEvent;
import org.worldspeaker.bootstrap.AttentionBuffer;
import org.worldspeaker.bootstrap.ConversationProgress;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.GateSnapshot;
import org.worldspeaker.compat.PeerFacts;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;
import org.worldspeaker.runtime.BoundedIoLane;
import org.worldspeaker.runtime.ThreadBoundary;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Outbound, bounded JDK WebSocket connection to the installed native channel plugin. */
public final class BoundCompanionClient implements WebSocket.Listener, AutoCloseable {
    public static final int MAX_FRAME_BYTES = 16 * 1024;
    /** Maximum number of frames waiting behind the one active JDK send. */
    public static final int MAX_QUEUED_OUTBOUND_FRAMES = 16;
    private static final long OUTBOUND_RESIDENCE_TIMEOUT_MS = 10_000;
    private static final long OUTBOUND_SEND_TIMEOUT_MS = 10_000;
    private static final Set<String> CHALLENGE_FIELDS = Set.of("type", "challenge", "deploymentId", "manifestSha256", "protocol", "hostVersion", "nodeVersion", "companionVersion", "serverId", "worldId", "characterId", "agentId", "configurationHandshakeVersion", "configSetSha256", "proof");
    private static final Set<String> WELCOME_FIELDS = Set.of("type", "challenge", "nonce", "deploymentId", "manifestSha256", "protocol", "hostVersion", "nodeVersion", "companionVersion", "serverId", "worldId", "characterId", "agentId", "configurationHandshakeVersion", "configSetSha256", "proof");
    private static final Set<String> HEARTBEAT_FIELDS = Set.of("type", "protocol", "sequence");
    private static final Set<String> MESSAGE_FIELDS = Set.of("type", "protocol", "messageId", "requestId", "invocationId", "characterId", "replyAudience", "kind", "text");
    private static final Set<String> FAILURE_FIELDS = Set.of("type", "protocol", "requestId", "invocationId", "characterId", "replyAudience", "reason");
    private static final long[] RECONNECT_DELAY_MS = {1000, 2000, 4000};

    private final CompanionConfig config;
    private final RuntimeFacts localFacts;
    private final CompatibilityGate gate;
    private final Consumer<JsonObject> dialogueSink;
    private final Consumer<String> privateDiagnostic;
    private final BoundedIoLane owner;
    private final ThreadPoolExecutor httpExecutor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "worldspeaker-jdk-http");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private volatile HttpClient httpClient;
    private final ScheduledThreadPoolExecutor scheduler = newScheduler();
    private final AtomicLong heartbeatSequence = new AtomicLong();
    private final AtomicLong lastPeerHealthNanos = new AtomicLong();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicBoolean maintenanceQueued = new AtomicBoolean();
    private final AtomicBoolean ownerSaturated = new AtomicBoolean();
    private final AtomicBoolean ownerContentionReported = new AtomicBoolean();
    private final AtomicBoolean connectRequested = new AtomicBoolean();
    private final AtomicBoolean outboundPumpQueued = new AtomicBoolean();
    private final AtomicReference<WebSocket> deferredReceiveRequest = new AtomicReference<>();
    private final AtomicReference<OutboundCompletion> deferredOutboundCompletion = new AtomicReference<>();
    private final long outboundResidenceTimeoutNanos;
    private final long outboundSendTimeoutMs;
    private final long outboundSendTimeoutNanos;
    private final ReentrantLock outboundLock = new ReentrantLock();
    private final ArrayDeque<OutboundFrame> queuedOutboundFrames = new ArrayDeque<>();
    private volatile OutboundFrame activeOutboundFrame;
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final StringBuilder fragmentedText = new StringBuilder();
    private volatile WebSocket socket;
    private volatile WebSocket shutdownSocket;
    private volatile CompletableFuture<WebSocket> connectFuture;
    private volatile boolean authenticated;
    private volatile boolean permanentlyUnavailable;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String expectedChallenge;
    private volatile String expectedNonce;
    private volatile PeerFacts challengePeerFacts;
    private volatile long lastHeartbeatSent;
    private volatile long lastHeartbeatAcknowledged;
    private volatile long handshakeDeadlineNanos;
    private volatile long nextReconnectAtNanos;
    private volatile long nextHeartbeatAtNanos;

    private static ScheduledThreadPoolExecutor newScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "worldspeaker-companion");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    public BoundCompanionClient(
            CompanionConfig config,
            RuntimeFacts localFacts,
            CompatibilityGate gate,
            Consumer<JsonObject> dialogueSink,
            Consumer<String> privateDiagnostic) {
        this(config, localFacts, gate, dialogueSink, privateDiagnostic,
                OUTBOUND_RESIDENCE_TIMEOUT_MS, OUTBOUND_SEND_TIMEOUT_MS);
    }

    BoundCompanionClient(
            CompanionConfig config,
            RuntimeFacts localFacts,
            CompatibilityGate gate,
            Consumer<JsonObject> dialogueSink,
            Consumer<String> privateDiagnostic,
            long outboundResidenceTimeoutMs,
            long outboundSendTimeoutMs) {
        this(config, localFacts, gate, dialogueSink, privateDiagnostic,
                outboundResidenceTimeoutMs, outboundSendTimeoutMs,
                BoundedIoLane.SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    BoundCompanionClient(
            CompanionConfig config,
            RuntimeFacts localFacts,
            CompatibilityGate gate,
            Consumer<JsonObject> dialogueSink,
            Consumer<String> privateDiagnostic,
            long outboundResidenceTimeoutMs,
            long outboundSendTimeoutMs,
            long shutdownTimeout,
            TimeUnit shutdownTimeoutUnit) {
        if (outboundResidenceTimeoutMs <= 0 || outboundSendTimeoutMs <= 0) {
            throw new IllegalArgumentException("outbound deadlines must be positive");
        }
        owner = new BoundedIoLane("worldspeaker-transport-owner", 32, true, 1,
                shutdownTimeout, shutdownTimeoutUnit, System::nanoTime);
        this.config = config;
        this.localFacts = localFacts;
        this.gate = gate;
        this.dialogueSink = dialogueSink;
        this.privateDiagnostic = privateDiagnostic;
        this.outboundResidenceTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(outboundResidenceTimeoutMs);
        this.outboundSendTimeoutMs = outboundSendTimeoutMs;
        this.outboundSendTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(outboundSendTimeoutMs);
        scheduler.scheduleAtFixedRate(this::queueMaintenance, 25, 25, TimeUnit.MILLISECONDS);
    }

    public void start() {
        if (closed.get() || permanentlyUnavailable) return;
        connectRequested.set(true);
        trySubmitOwner(() -> {
            if (connectRequested.compareAndSet(true, false)) connect();
        });
    }

    public boolean isReady() {
        return readyGeneration() >= 0;
    }

    /** Current authenticated connection generation for owner-private request binding. */
    public long authenticatedPeerGeneration() {
        return readyGeneration();
    }

    /** Returns whether the exact authenticated peer generation is still current. */
    public boolean isAuthenticatedPeerGenerationCurrent(long expectedGeneration) {
        return expectedGeneration > 0 && readyGeneration() == expectedGeneration;
    }

    public boolean sendDialogueRequest(String requestId, String invocationId, AttributedEvent event,
                                       String conversationId, String replyToMessageId, JsonObject acknowledgement) {
        return sendDialogueRequest(requestId, invocationId, event, conversationId, replyToMessageId,
                acknowledgement, java.util.List.of(), new AttentionBuffer.ContextStatus("disabled", "0", 0,
                        java.util.List.of(), java.util.Set.of()));
    }

    public boolean sendDialogueRequest(String requestId, String invocationId, AttributedEvent event,
                                       String conversationId, String replyToMessageId, JsonObject acknowledgement,
                                       java.util.List<AttentionEvent> contextEvents,
                                       AttentionBuffer.ContextStatus contextStatus) {
        long generation = readyGeneration();
        if (generation < 0 || event == null || contextEvents == null || contextStatus == null) return false;
        try {
            JsonObject frame = DialogueEnvelope.request(requestId, invocationId, event, conversationId,
                    replyToMessageId, acknowledgement, contextEvents, contextStatus, List.of(),
                    config.abilityPolicy().revision());
            return sendDialogueRequestGeneration(frame, event.playerId().toString(), generation) >= 0;
        } catch (RuntimeException invalidEnvelope) {
            return false;
        }
    }

    /** Queue-accepts a prevalidated v3 request assembled with a bounded context snapshot. */
    public boolean sendDialogueRequest(JsonObject frame, String expectedPlayerId) {
        return sendDialogueRequestGeneration(frame, expectedPlayerId) >= 0;
    }

    /** Queue-accepts a request and returns the exact authenticated peer generation that owns it. */
    public long sendDialogueRequestGeneration(JsonObject frame, String expectedPlayerId) {
        long generation = readyGeneration();
        if (generation < 0 || frame == null || expectedPlayerId == null) return -1;
        return sendDialogueRequestGeneration(frame, expectedPlayerId, generation);
    }

    private long sendDialogueRequestGeneration(JsonObject frame, String expectedPlayerId, long generation) {
        try {
            DialogueEnvelope.validateRequest(frame, expectedPlayerId, config.binding(), config.abilityPolicy().revision());
            return enqueueOutbound(frame, null, true, generation) ? generation : -1;
        } catch (RuntimeException invalidEnvelope) {
            return -1;
        }
    }

    /** Legacy stress-test seam; it still emits a valid v3 envelope with generated attribution. */
    public boolean sendDialogueRequest(String requestId, String playerId, String playerName,
                                       String capturedAtMonotonicNanos, String text,
                                       String acknowledgementId, String acknowledgementText) {
        if (readyGeneration() < 0 || text == null || text.isBlank() || text.length() > 512) return false;
        java.util.UUID actualPlayerId = parseUuidOrRandom(playerId);
        java.util.UUID actualRequestId = parseUuidOrRandom(requestId);
        java.util.UUID invocationId = parseUuidOrRandom(null);
        while (invocationId.equals(actualRequestId)) invocationId = parseUuidOrRandom(null);
        long captured = parseLongOrZero(capturedAtMonotonicNanos);
        String actualName = playerName != null && !playerName.isBlank() && playerName.length() <= 32 &&
                playerName.codePoints().noneMatch(Character::isISOControl) ? playerName : "Player";
        AttributedEvent event;
        try {
            event = new AttributedEvent(config.binding().serverId(), config.binding().worldId(), config.binding().characterId(),
                    config.binding().agentId(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                    Math.max(0, System.currentTimeMillis()), captured, "live", "command", actualPlayerId,
                    actualName, "minecraft:overworld", text);
        } catch (RuntimeException invalidEvent) {
            return false;
        }
        JsonObject acknowledgement = null;
        if (acknowledgementId != null && acknowledgementText != null) {
            try {
                acknowledgement = DialogueEnvelope.acknowledgement(acknowledgementId, acknowledgementText,
                        Math.max(0, System.currentTimeMillis()), System.nanoTime(), actualPlayerId.toString());
            } catch (RuntimeException invalidAcknowledgement) {
                return false;
            }
        }
        return sendDialogueRequest(actualRequestId.toString(), invocationId.toString(), event,
                java.util.UUID.randomUUID().toString(), null, acknowledgement);
    }

    /**
     * Queues context for a locally dispatched first response to an already-forwarded request.
     * ACCEPTED means the bounded local outbound queue owns the frame; completion reports only
     * that the JDK WebSocket send operation completed, not peer receipt or agent consumption.
     */
    public AcknowledgementSubmission sendDialogueAcknowledgement(String requestId, String invocationId,
                                                                  JsonObject acknowledgement,
                                                                  Consumer<AcknowledgementDelivery> completion) {
        long generation = readyGeneration();
        if (completion == null || generation < 0 || requestId == null || invocationId == null || acknowledgement == null) {
            return AcknowledgementSubmission.REJECTED;
        }
        try {
            JsonObject audience = acknowledgement.getAsJsonObject("replyAudience");
            String playerId = audience.get("playerId").getAsString();
            java.util.UUID.fromString(requestId);
            java.util.UUID.fromString(invocationId);
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "dialogue.ack");
            frame.addProperty("protocol", DeploymentFacts.PROTOCOL);
            frame.addProperty("requestId", requestId);
            frame.addProperty("invocationId", invocationId);
            frame.addProperty("playerId", playerId);
            frame.add("acknowledgement", acknowledgement.deepCopy());
            return enqueueOutbound(frame, outboundDelivery -> completion.accept(
                            outboundDelivery == OutboundDelivery.SEND_COMPLETED
                                    ? AcknowledgementDelivery.SEND_COMPLETED : AcknowledgementDelivery.FAILED), true, generation)
                    ? AcknowledgementSubmission.ACCEPTED : AcknowledgementSubmission.REJECTED;
        } catch (RuntimeException invalidEnvelope) {
            return AcknowledgementSubmission.REJECTED;
        }
    }

    /** Queues active progress context separately from the terminal and ACK completion slots. */
    public ProgressSubmission sendDialogueProgress(String requestId, String invocationId,
                                                   ConversationProgress.DialogueProgress progress,
                                                   Consumer<ProgressDelivery> completion) {
        long generation = readyGeneration();
        if (completion == null || generation < 0 || requestId == null || invocationId == null || progress == null) {
            return ProgressSubmission.REJECTED;
        }
        try {
            JsonObject frame = DialogueEnvelope.progress(requestId, invocationId, progress);
            return enqueueOutbound(frame, outboundDelivery -> completion.accept(
                            outboundDelivery == OutboundDelivery.SEND_COMPLETED
                                    ? ProgressDelivery.SEND_COMPLETED : ProgressDelivery.FAILED), true, generation)
                    ? ProgressSubmission.ACCEPTED : ProgressSubmission.REJECTED;
        } catch (RuntimeException invalidEnvelope) {
            return ProgressSubmission.REJECTED;
        }
    }

    /** Compatibility test seam; new production code passes a fully attributed K record. */
    public AcknowledgementSubmission sendDialogueAcknowledgement(String requestId, String playerId,
                                                                  String messageId, String text,
                                                                  Consumer<AcknowledgementDelivery> completion) {
        try {
            return sendDialogueAcknowledgement(parseUuidOrRandom(requestId).toString(), parseUuidOrRandom(null).toString(),
                    DialogueEnvelope.acknowledgement(parseUuidOrRandom(messageId).toString(), text,
                            Math.max(0, System.currentTimeMillis()), System.nanoTime(), parseUuidOrRandom(playerId).toString()), completion);
        } catch (RuntimeException invalidEnvelope) {
            return AcknowledgementSubmission.REJECTED;
        }
    }

    /** Queues one cancellation only to the authenticated peer generation that received the request. */
    public boolean cancelDialogueRequest(String requestId, String invocationId, String playerId,
                                         long originalGeneration, Consumer<CancellationDelivery> completion) {
        if (originalGeneration < 0 || completion == null || !canonicalDistinctUuid(requestId, invocationId) ||
                !canonicalUuid(playerId)) return false;
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "dialogue.cancel");
        frame.addProperty("protocol", DeploymentFacts.PROTOCOL);
        frame.addProperty("requestId", requestId);
        frame.addProperty("invocationId", invocationId);
        frame.addProperty("playerId", playerId);
        return enqueueOutbound(frame, delivery -> completion.accept(delivery == OutboundDelivery.SEND_COMPLETED
                ? CancellationDelivery.SEND_COMPLETED : CancellationDelivery.FAILED), true, originalGeneration);
    }

    private static boolean canonicalUuid(String value) {
        if (value == null) return false;
        try { return java.util.UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean canonicalDistinctUuid(String first, String second) {
        return canonicalUuid(first) && canonicalUuid(second) && !first.equals(second);
    }

    private long readyGeneration() {
        long generation = connectionGeneration.get();
        if (!authenticated || closed.get() || socket == null || permanentlyUnavailable ||
                gate.snapshot().state() != org.worldspeaker.compat.GateState.READY || generation != connectionGeneration.get()) return -1;
        return generation;
    }

    private void connect() {
        owner.assertOwnerThread();
        if (closed.get() || permanentlyUnavailable) return;
        reconnectScheduled.set(false);
        long generation;
        List<OutboundFrame> failed;
        CompletableFuture<WebSocket> previousConnect;
        outboundLock.lock();
        try {
            generation = connectionGeneration.incrementAndGet();
            previousConnect = connectFuture;
            connectFuture = null;
            socket = null;
            authenticated = false;
            expectedChallenge = null;
            expectedNonce = null;
            challengePeerFacts = null;
            failed = drainOutboundLocked(null);
        } finally {
            outboundLock.unlock();
        }
        if (previousConnect != null) previousConnect.cancel(true);
        synchronized (fragmentedText) {
            fragmentedText.setLength(0);
        }
        notifyDelivery(failed, OutboundDelivery.FAILED);
        handshakeDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        HttpClient client = httpClient;
        if (client == null) {
            client = HttpClient.newBuilder().executor(httpExecutor).connectTimeout(Duration.ofMillis(5000)).build();
            httpClient = client;
        }
        try {
            CompletableFuture<WebSocket> attempt = client.newWebSocketBuilder().connectTimeout(Duration.ofMillis(5000))
                    .buildAsync(config.endpoint(), new GenerationListener(generation));
            connectFuture = attempt;
            attempt.whenComplete((connected, error) -> {
                if (error != null) {
                    trySubmitOwner(() -> {
                        if (connectFuture == attempt) connectFuture = null;
                        connectionLost(generation, null, "WS-COMPAT-CONNECTION-FAILED");
                    });
                } else {
                    BoundedIoLane.SubmissionResult result = trySubmitOwner(() -> {
                        if (connectFuture == attempt) connectFuture = null;
                        if (closed.get() || generation != connectionGeneration.get()) abortSocket(connected);
                    });
                    if (result != BoundedIoLane.SubmissionResult.ACCEPTED &&
                            (closed.get() || generation != connectionGeneration.get())) {
                        abortSocketDirect(connected);
                    }
                }
            });
        } catch (RuntimeException failure) {
            connectionLost(generation, null, "WS-COMPAT-CONNECTION-FAILED");
        }
    }

    private void scheduleReconnect() {
        if (!reconnectScheduled.compareAndSet(false, true)) return;
        int attempt = reconnectAttempts.getAndIncrement();
        if (attempt >= RECONNECT_DELAY_MS.length || closed.get() || permanentlyUnavailable) {
            reconnectScheduled.set(false);
            privateDiagnostic.accept("WS-COMPAT-RECONNECT-EXHAUSTED");
            return;
        }
        nextReconnectAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECONNECT_DELAY_MS[attempt]);
    }

    private void connectionLost(long generation, WebSocket lostSocket, String reason) {
        boolean wasAuthenticated;
        List<OutboundFrame> failedFrames;
        outboundLock.lock();
        try {
            if (closed.get() || generation != connectionGeneration.get() || (lostSocket != null && socket != lostSocket)) return;
            wasAuthenticated = authenticated;
            authenticated = false;
            if (lostSocket != null) socket = null;
            connectionGeneration.incrementAndGet();
            failedFrames = drainOutboundLocked(lostSocket);
        } finally {
            outboundLock.unlock();
        }
        if (!permanentlyUnavailable) gate.disconnected();
        if (wasAuthenticated) privateDiagnostic.accept(reason);
        if (!closed.get() && !permanentlyUnavailable && reconnectAttempts.get() < RECONNECT_DELAY_MS.length) scheduleReconnect();
        else if (!closed.get() && !permanentlyUnavailable) privateDiagnostic.accept("WS-COMPAT-RECONNECT-EXHAUSTED");
        notifyDelivery(failedFrames, OutboundDelivery.FAILED);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        long generation;
        outboundLock.lock();
        try {
            generation = connectionGeneration.get();
        } finally {
            outboundLock.unlock();
        }
        onOpen(generation, webSocket);
    }

    private void onOpen(long generation, WebSocket webSocket) {
        boolean reject;
        outboundLock.lock();
        try {
            reject = generation != connectionGeneration.get() || closed.get() || permanentlyUnavailable ||
                    (socket != null && socket != webSocket);
            if (!reject) {
                socket = webSocket;
                authenticated = false;
                expectedChallenge = null;
                expectedNonce = null;
                challengePeerFacts = null;
            }
        } finally {
            outboundLock.unlock();
        }
        if (reject) {
            abortSocket(webSocket);
            return;
        }
        synchronized (fragmentedText) {
            fragmentedText.setLength(0);
        }
        handshakeDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        requestOne(webSocket);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        long generation;
        outboundLock.lock();
        try {
            generation = socket == webSocket ? connectionGeneration.get() : -1;
        } finally {
            outboundLock.unlock();
        }
        return onText(generation, webSocket, data, last);
    }

    private CompletionStage<?> onText(long generation, WebSocket webSocket, CharSequence data, boolean last) {
        if (!isCurrent(generation, webSocket)) return CompletableFuture.completedFuture(null);
        synchronized (fragmentedText) {
            if (!isCurrent(generation, webSocket)) return CompletableFuture.completedFuture(null);
            fragmentedText.append(data);
            if (fragmentedText.toString().getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
                failAuthentication(generation, webSocket);
                fragmentedText.setLength(0);
                return CompletableFuture.completedFuture(null);
            }
            if (last) {
                String frame = fragmentedText.toString();
                fragmentedText.setLength(0);
                processFrame(generation, webSocket, frame);
            }
        }
        requestOne(webSocket);
        return CompletableFuture.completedFuture(null);
    }

    private void processFrame(long generation, WebSocket webSocket, String source) {
        if (!isCurrent(generation, webSocket)) return;
        try {
            JsonElement parsed = StrictJsonParser.parse(source);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("frame is not an object");
            JsonObject frame = parsed.getAsJsonObject();
            String type = requiredString(frame, "type");
            if (!authenticated) {
                if (expectedChallenge == null) acceptChallenge(generation, webSocket, frame);
                else acceptWelcome(generation, webSocket, frame);
                return;
            }
            if (!DeploymentFacts.PROTOCOL.equals(requiredString(frame, "protocol"))) {
                closeIncompatible(generation, webSocket);
                return;
            }
            if ("heartbeat.ack".equals(type)) {
                if (!HEARTBEAT_FIELDS.equals(frame.keySet()) || !frame.get("sequence").isJsonPrimitive() ||
                        !frame.get("sequence").getAsJsonPrimitive().isNumber()) {
                    closeIncompatible(generation, webSocket);
                    return;
                }
                long sequence = frame.get("sequence").getAsLong();
                if (sequence <= lastHeartbeatAcknowledged || sequence > lastHeartbeatSent) {
                    closeIncompatible(generation, webSocket);
                    return;
                }
                lastHeartbeatAcknowledged = sequence;
                lastPeerHealthNanos.set(System.nanoTime());
            } else if ("dialogue.message".equals(type)) {
                if (!MESSAGE_FIELDS.equals(frame.keySet())) {
                    closeIncompatible(generation, webSocket);
                    return;
                }
                try { DialogueEnvelope.validateResponse(frame); }
                catch (RuntimeException invalidTerminal) {
                    privateDiagnostic.accept("WS-COMPAT-PROTOCOL-FRAME-REJECTED");
                    return;
                }
                dialogueSink.accept(frame);
            } else if ("dialogue.failure".equals(type)) {
                if (!FAILURE_FIELDS.equals(frame.keySet())) {
                    closeIncompatible(generation, webSocket);
                    return;
                }
                try { DialogueEnvelope.validateResponse(frame); }
                catch (RuntimeException invalidTerminal) {
                    privateDiagnostic.accept("WS-COMPAT-PROTOCOL-FRAME-REJECTED");
                    return;
                }
                dialogueSink.accept(frame);
            } else {
                closeIncompatible(generation, webSocket);
            }
        } catch (RuntimeException exception) {
            closeIncompatible(generation, webSocket);
        }
    }

    private void acceptChallenge(long generation, WebSocket webSocket, JsonObject frame) {
        if (!CHALLENGE_FIELDS.equals(frame.keySet()) || !"challenge".equals(requiredString(frame, "type")) ||
                !DeploymentFacts.PROTOCOL.equals(requiredString(frame, "protocol"))) {
            failAuthentication(generation, webSocket);
            return;
        }
        String challenge = requiredString(frame, "challenge");
        if (!challenge.matches("[A-Za-z0-9_-]{43}")) {
            failAuthentication(generation, webSocket);
            return;
        }
        PeerFacts peer = peerFacts(frame);
        String proof = requiredString(frame, "proof");
        if (!PeerHandshake.verifyServerChallenge(config, challenge, peer, proof)) {
            failAuthentication(generation, webSocket);
            return;
        }

        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        String nonceText = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        outboundLock.lock();
        try {
            if (generation != connectionGeneration.get() || socket != webSocket || closed.get()) return;
            expectedChallenge = challenge;
            challengePeerFacts = peer;
            expectedNonce = nonceText;
        } finally {
            outboundLock.unlock();
        }
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("protocol", DeploymentFacts.PROTOCOL);
        hello.addProperty("challenge", challenge);
        hello.addProperty("nonce", nonceText);
        hello.addProperty("deploymentId", DeploymentFacts.DEPLOYMENT_ID);
        hello.addProperty("manifestSha256", DeploymentFacts.MANIFEST_SHA256);
        hello.addProperty("minecraftVersion", localFacts.minecraftVersion());
        hello.addProperty("fabricLoaderVersion", localFacts.fabricLoaderVersion());
        hello.addProperty("fabricApiVersion", localFacts.fabricApiVersion());
        hello.addProperty("javaMajor", localFacts.javaMajor());
        hello.addProperty("javaVendor", localFacts.javaVendor());
        hello.addProperty("javaRuntimeVersion", localFacts.javaRuntimeVersion());
        hello.addProperty("serverId", config.binding().serverId());
        hello.addProperty("worldId", config.binding().worldId());
        hello.addProperty("characterId", config.binding().characterId());
        hello.addProperty("agentId", config.binding().agentId());
        hello.addProperty("configurationHandshakeVersion", 1);
        hello.addProperty("configSetSha256", config.configSetSha256());
        hello.addProperty("proof", PeerHandshake.clientProof(config, challenge, nonceText, localFacts));
        if (!sendBounded(hello, generation)) failAuthentication(generation, webSocket);
    }

    private void acceptWelcome(long generation, WebSocket webSocket, JsonObject frame) {
        if (!WELCOME_FIELDS.equals(frame.keySet()) || !"welcome".equals(requiredString(frame, "type")) ||
                !DeploymentFacts.PROTOCOL.equals(requiredString(frame, "protocol")) ||
                !expectedChallenge.equals(requiredString(frame, "challenge")) ||
                !expectedNonce.equals(requiredString(frame, "nonce"))) {
            failAuthentication(generation, webSocket);
            return;
        }
        PeerFacts peer = peerFacts(frame);
        if (!peer.equals(challengePeerFacts)) {
            failAuthentication(generation, webSocket);
            return;
        }
        String proof = requiredString(frame, "proof");
        if (!PeerHandshake.verifyServerProof(config, expectedChallenge, expectedNonce, peer, peer.nodeVersion(), proof)) {
            failAuthentication(generation, webSocket);
            return;
        }
        GateSnapshot next;
        List<OutboundFrame> failedFrames = List.of();
        outboundLock.lock();
        try {
            if (generation != connectionGeneration.get() || socket != webSocket || closed.get()) return;
            next = gate.companionAuthenticated(peer);
            if (next.state() != org.worldspeaker.compat.GateState.READY) {
                permanentlyUnavailable = true;
                authenticated = false;
                failedFrames = drainOutboundLocked(webSocket);
            } else {
                authenticated = true;
                lastPeerHealthNanos.set(System.nanoTime());
            }
        } finally {
            outboundLock.unlock();
        }
        if (next.state() != org.worldspeaker.compat.GateState.READY) {
            closeSocket(webSocket, 4001, "compatibility mismatch");
            privateDiagnostic.accept(next.reasonCode());
            notifyDelivery(failedFrames, OutboundDelivery.FAILED);
            return;
        }
        startHeartbeat(generation, webSocket);
    }

    private static PeerFacts peerFacts(JsonObject frame) {
        return new PeerFacts(
                requiredString(frame, "deploymentId"), requiredString(frame, "manifestSha256"), requiredString(frame, "protocol"),
                requiredString(frame, "hostVersion"), requiredString(frame, "nodeVersion"), requiredString(frame, "companionVersion"),
                requiredString(frame, "serverId"), requiredString(frame, "worldId"), requiredString(frame, "characterId"), requiredString(frame, "agentId"),
                requiredInteger(frame, "configurationHandshakeVersion"), requiredSha256(frame, "configSetSha256"));
    }

    private void startHeartbeat(long generation, WebSocket webSocket) {
        nextHeartbeatAtNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        lastHeartbeatSent = 0;
        lastHeartbeatAcknowledged = 0;
    }

    private void failAuthentication(long generation, WebSocket webSocket) {
        List<OutboundFrame> failedFrames;
        outboundLock.lock();
        try {
            if (generation != connectionGeneration.get() || socket != webSocket || closed.get()) return;
            permanentlyUnavailable = true;
            authenticated = false;
            gate.unavailable("WS-COMPAT-HANDSHAKE-REJECTED");
            failedFrames = drainOutboundLocked(webSocket);
        } finally {
            outboundLock.unlock();
        }
        closeSocket(webSocket, 4001, "handshake rejected");
        privateDiagnostic.accept("WS-COMPAT-HANDSHAKE-REJECTED");
        notifyDelivery(failedFrames, OutboundDelivery.FAILED);
    }

    private void closeIncompatible(long generation, WebSocket webSocket) {
        List<OutboundFrame> failedFrames;
        outboundLock.lock();
        try {
            if (generation != connectionGeneration.get() || socket != webSocket || closed.get()) return;
            permanentlyUnavailable = true;
            authenticated = false;
            gate.unavailable("WS-COMPAT-PROTOCOL");
            failedFrames = drainOutboundLocked(webSocket);
        } finally {
            outboundLock.unlock();
        }
        closeSocket(webSocket, 4002, "protocol mismatch");
        privateDiagnostic.accept("WS-COMPAT-PROTOCOL");
        notifyDelivery(failedFrames, OutboundDelivery.FAILED);
    }

    private boolean sendBounded(JsonObject frame, long generation) {
        return enqueueOutbound(frame, null, false, generation);
    }

    /** Tick callers only serialize a bounded DTO and reserve a slot; the owner lane performs every socket call. */
    private boolean enqueueOutbound(JsonObject frame, Consumer<OutboundDelivery> completion,
                                    boolean requireReady, long expectedGeneration) {
        String text = frame.toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) return false;
        OutboundFrame outbound;
        if (!outboundLock.tryLock()) return false;
        try {
            WebSocket current = socket;
            long generation = connectionGeneration.get();
            if (current == null || closed.get() || expectedGeneration != generation ||
                    (requireReady && (!authenticated || permanentlyUnavailable ||
                            gate.snapshot().state() != org.worldspeaker.compat.GateState.READY))) return false;
            if (queuedOutboundFrames.size() + (activeOutboundFrame == null ? 0 : 1) >= MAX_QUEUED_OUTBOUND_FRAMES + 1) return false;
            outbound = new OutboundFrame(generation, current, text, completion, System.nanoTime());
            queuedOutboundFrames.addLast(outbound);
        } finally {
            outboundLock.unlock();
        }
        if (queueOutboundPump()) return true;
        outboundLock.lock();
        try {
            queuedOutboundFrames.remove(outbound);
        } finally {
            outboundLock.unlock();
        }
        return false;
    }

    private boolean queueOutboundPump() {
        // An active JDK send owns the lane until its completion; it will wake the
        // pump again after clearing this field. Avoid queuing no-op work per tick.
        if (activeOutboundFrame != null || closed.get()) return true;
        if (!outboundPumpQueued.compareAndSet(false, true)) return true;
        BoundedIoLane.SubmissionResult result = trySubmitOwner(this::pumpOutbound);
        if (result == BoundedIoLane.SubmissionResult.ACCEPTED) return true;
        outboundPumpQueued.set(false);
        // The frame already owns a bounded client-queue slot. Maintenance is the bounded
        // retry source when submission lost only the lane lock race; do not reject the
        // frame or turn expected contention into peer unavailability.
        return result == BoundedIoLane.SubmissionResult.CONTENDED;
    }

    private void pumpOutbound() {
        owner.assertOwnerThread();
        try {
            pumpOneOutboundFrame();
        } finally {
            outboundPumpQueued.set(false);
            if (hasPumpableOutbound()) queueOutboundPump();
        }
    }

    private void pumpOneOutboundFrame() {
        OutboundFrame outbound;
        boolean stale = false;
        outboundLock.lock();
        try {
            if (activeOutboundFrame != null || queuedOutboundFrames.isEmpty() || closed.get()) return;
            outbound = queuedOutboundFrames.peekFirst();
            if (outbound.generation != connectionGeneration.get() || outbound.socket != socket) {
                queuedOutboundFrames.removeFirst();
                stale = true;
            } else {
                queuedOutboundFrames.removeFirst();
                activeOutboundFrame = outbound;
                outbound.sendStartedAtNanos = System.nanoTime();
            }
        } finally {
            outboundLock.unlock();
        }
        if (stale) {
            notifyDelivery(List.of(outbound), OutboundDelivery.FAILED);
            return;
        }
        CompletionStage<?> stage;
        try {
            stage = outbound.socket.sendText(outbound.text, true);
            if (stage == null) throw new IllegalStateException("WebSocket returned no send completion stage");
            OutboundFrame sent = outbound;
            stage.whenComplete((ignored, error) -> {
                BoundedIoLane.SubmissionResult result = trySubmitOwner(() -> completeOutbound(sent, error));
                if (result == BoundedIoLane.SubmissionResult.CONTENDED) {
                    deferOutboundCompletion(sent, error);
                }
            });
        } catch (RuntimeException exception) {
            completeOutbound(outbound, exception);
        }
    }

    private void completeOutbound(OutboundFrame completed, Throwable error) {
        owner.assertOwnerThread();
        outboundLock.lock();
        try {
            if (activeOutboundFrame != completed || completed.generation != connectionGeneration.get() || socket != completed.socket) return;
            if (error != null || System.nanoTime() - completed.sendStartedAtNanos >= outboundSendTimeoutNanos) {
                error = error == null ? new java.util.concurrent.TimeoutException("outbound send deadline expired") : error;
            } else {
                activeOutboundFrame = null;
            }
        } finally {
            outboundLock.unlock();
        }
        if (error != null) {
            failSocketGeneration(completed.generation, completed.socket, "WS-COMPAT-CONNECTION-FAILED");
            return;
        }
        notifyDelivery(List.of(completed), OutboundDelivery.SEND_COMPLETED);
        if (hasPumpableOutbound()) queueOutboundPump();
    }

    private void failSocketGeneration(long generation, WebSocket target, String reason) {
        owner.assertOwnerThread();
        connectionLost(generation, target, reason);
        if (target != null) {
            try {
                target.abort();
            } catch (RuntimeException ignored) {
                // The generation is retired even if abort itself fails.
            }
        }
    }

    private List<OutboundFrame> drainOutboundLocked(WebSocket target) {
        List<OutboundFrame> drained = new ArrayList<>();
        OutboundCompletion deferred = deferredOutboundCompletion.get();
        if (deferred != null && (target == null || deferred.frame.socket == target)) {
            deferredOutboundCompletion.compareAndSet(deferred, null);
        }
        if (activeOutboundFrame != null && (target == null || activeOutboundFrame.socket == target)) {
            drained.add(activeOutboundFrame);
            activeOutboundFrame = null;
        }
        Iterator<OutboundFrame> queued = queuedOutboundFrames.iterator();
        while (queued.hasNext()) {
            OutboundFrame outbound = queued.next();
            if (target == null || outbound.socket == target) {
                queued.remove();
                drained.add(outbound);
            }
        }
        return drained;
    }

    private void deferOutboundCompletion(OutboundFrame frame, Throwable failure) {
        OutboundCompletion next = new OutboundCompletion(frame, failure);
        while (true) {
            OutboundCompletion current = deferredOutboundCompletion.get();
            if (current != null && current.frame.generation == frame.generation) return;
            if (deferredOutboundCompletion.compareAndSet(current, next)) return;
        }
    }

    private boolean isCurrent(long generation, WebSocket webSocket) {
        if (generation < 0) return false;
        outboundLock.lock();
        try {
            return !closed.get() && generation == connectionGeneration.get() && socket == webSocket;
        } finally {
            outboundLock.unlock();
        }
    }

    private boolean hasPumpableOutbound() {
        outboundLock.lock();
        try {
            return activeOutboundFrame == null && !queuedOutboundFrames.isEmpty() && !closed.get();
        } finally {
            outboundLock.unlock();
        }
    }

    private void notifyDelivery(List<OutboundFrame> frames, OutboundDelivery delivery) {
        for (OutboundFrame frame : frames) {
            if (frame.completion == null) continue;
            try {
                frame.completion.accept(delivery);
            } catch (RuntimeException exception) {
                try {
                    privateDiagnostic.accept("WS-COMPAT-CONTEXT-COMPLETION-FAILED");
                } catch (RuntimeException ignored) {
                    // A diagnostic callback cannot prevent remaining delivery callbacks.
                }
            }
        }
    }

    private void closeSocket(WebSocket webSocket, int statusCode, String reason) {
        Runnable action = () -> {
            owner.assertOwnerThread();
            try {
                webSocket.sendClose(statusCode, reason);
            } catch (RuntimeException exception) {
                safePrivateDiagnostic("WS-COMPAT-CLOSE-FAILED");
            }
        };
        if (owner.isOwnerThread()) action.run();
        else trySubmitOwner(action);
    }

    private void abortSocket(WebSocket webSocket) {
        if (owner.isClosed() && !owner.isOwnerThread()) {
            abortSocketDirect(webSocket);
            return;
        }
        Runnable action = () -> {
            owner.assertOwnerThread();
            abortSocketDirect(webSocket);
        };
        if (owner.isOwnerThread()) action.run();
        else trySubmitOwner(action);
    }

    private static void abortSocketDirect(WebSocket webSocket) {
        try {
            webSocket.abort();
        } catch (RuntimeException ignored) {
            // A generation remains retired even if an abort attempt fails.
        }
    }

    private void requestOne(WebSocket webSocket) {
        BoundedIoLane.SubmissionResult result = trySubmitOwner(() -> requestOneOnOwner(webSocket));
        if (result == BoundedIoLane.SubmissionResult.CONTENDED) {
            deferredReceiveRequest.updateAndGet(current -> webSocket == socket && !closed.get() ? webSocket : current);
        }
    }

    private void requestOneOnOwner(WebSocket webSocket) {
        owner.assertOwnerThread();
        deferredReceiveRequest.compareAndSet(webSocket, null);
        if (closed.get() || webSocket != socket) return;
        try {
            webSocket.request(1);
        } catch (RuntimeException failure) {
            connectionLost(connectionGeneration.get(), webSocket, "WS-COMPAT-CONNECTION-FAILED");
        }
    }

    private void queueMaintenance() {
        if (closed.get() || !maintenanceQueued.compareAndSet(false, true)) return;
        if (trySubmitOwner(() -> {
            maintenanceQueued.set(false);
            runMaintenance();
        }) != BoundedIoLane.SubmissionResult.ACCEPTED) {
            maintenanceQueued.set(false);
        }
    }

    private void runMaintenance() {
        owner.assertOwnerThread();
        long now = System.nanoTime();
        if (ownerSaturated.getAndSet(false)) {
            permanentlyUnavailable = true;
            gate.unavailable("WS-COMPAT-IO-OWNER-SATURATED");
            WebSocket current = socket;
            long generation = connectionGeneration.get();
            connectionLost(generation, current, "WS-COMPAT-IO-OWNER-SATURATED");
            if (current != null) abortSocket(current);
            privateDiagnostic.accept("WS-COMPAT-IO-OWNER-SATURATED");
            return;
        }
        if (connectRequested.compareAndSet(true, false)) {
            connect();
            return;
        }
        OutboundCompletion deferredCompletion = deferredOutboundCompletion.getAndSet(null);
        if (deferredCompletion != null) {
            completeOutbound(deferredCompletion.frame, deferredCompletion.failure);
            return;
        }
        WebSocket deferredRequest = deferredReceiveRequest.getAndSet(null);
        if (deferredRequest != null && deferredRequest == socket && !closed.get()) {
            requestOneOnOwner(deferredRequest);
            return;
        }
        if (reconnectScheduled.get() && now >= nextReconnectAtNanos && !closed.get() && !permanentlyUnavailable) {
            connect();
            return;
        }
        WebSocket current = socket;
        long generation = connectionGeneration.get();
        CompletableFuture<WebSocket> pendingConnect = connectFuture;
        if (current == null && pendingConnect != null && handshakeDeadlineNanos > 0 && now >= handshakeDeadlineNanos) {
            connectFuture = null;
            pendingConnect.cancel(true);
            connectionLost(generation, null, "WS-COMPAT-CONNECTION-FAILED");
            return;
        }
        if (current != null && !authenticated && handshakeDeadlineNanos > 0 && now >= handshakeDeadlineNanos) {
            failAuthentication(generation, current);
            return;
        }
        if (current != null && authenticated) {
            if (now - lastPeerHealthNanos.get() > TimeUnit.SECONDS.toNanos(10)) {
                failSocketGeneration(generation, current, "WS-COMPAT-COMPANION-DISCONNECTED");
                return;
            }
            if (now >= nextHeartbeatAtNanos) {
                long sequence = heartbeatSequence.incrementAndGet();
                lastHeartbeatSent = sequence;
                JsonObject heartbeat = new JsonObject();
                heartbeat.addProperty("type", "heartbeat");
                heartbeat.addProperty("protocol", DeploymentFacts.PROTOCOL);
                heartbeat.addProperty("sequence", sequence);
                sendBounded(heartbeat, generation);
                nextHeartbeatAtNanos = now + TimeUnit.SECONDS.toNanos(5);
            }
        }
        OutboundFrame active;
        OutboundFrame oldestQueued;
        outboundLock.lock();
        try {
            active = activeOutboundFrame;
            oldestQueued = queuedOutboundFrames.peekFirst();
        } finally {
            outboundLock.unlock();
        }
        if (active != null && now - active.sendStartedAtNanos >= outboundSendTimeoutNanos) {
            failSocketGeneration(active.generation, active.socket, "WS-COMPAT-OUTBOUND-SEND-TIMEOUT");
            return;
        }
        if (oldestQueued != null && now - oldestQueued.enqueuedAtNanos >= outboundResidenceTimeoutNanos) {
            failSocketGeneration(oldestQueued.generation, oldestQueued.socket, "WS-COMPAT-OUTBOUND-RESIDENCE-EXPIRED");
            return;
        }
        boolean outputPending;
        outboundLock.lock();
        try {
            outputPending = activeOutboundFrame == null && !queuedOutboundFrames.isEmpty();
        } finally {
            outboundLock.unlock();
        }
        if (outputPending) queueOutboundPump();
    }

    private void rejectOwnerSaturation() {
        ownerSaturated.set(true);
    }

    private BoundedIoLane.SubmissionResult trySubmitOwner(Runnable operation) {
        BoundedIoLane.SubmissionResult result = owner.tryExecuteWithResult(operation);
        if (result == BoundedIoLane.SubmissionResult.ACCEPTED) {
            ownerContentionReported.set(false);
        } else if (result == BoundedIoLane.SubmissionResult.CONTENDED) {
            if (ownerContentionReported.compareAndSet(false, true)) {
                safePrivateDiagnostic("WS-COMPAT-IO-OWNER-CONTENDED");
            }
        } else if (result == BoundedIoLane.SubmissionResult.SATURATED) {
            rejectOwnerSaturation();
        }
        return result;
    }

    private void closeTransportOnOwner() {
        owner.assertOwnerThread();
        WebSocket current;
        List<OutboundFrame> failedFrames;
        outboundLock.lock();
        try {
            current = socket;
            socket = null;
            shutdownSocket = current;
            authenticated = false;
            failedFrames = drainOutboundLocked(null);
        } finally {
            outboundLock.unlock();
        }
        gate.disconnected();
        CompletableFuture<WebSocket> pending = connectFuture;
        if (pending != null) pending.cancel(true);
        connectFuture = null;
        if (current != null) closeSocket(current, WebSocket.NORMAL_CLOSURE, "server stopping");
        httpExecutor.shutdownNow();
        notifyDelivery(failedFrames, OutboundDelivery.FAILED);
    }

    private void abortAfterShutdownTimeout() {
        BoundedIoLane.ShutdownStatus status = owner.shutdownStatus();
        safePrivateDiagnostic(status == BoundedIoLane.ShutdownStatus.TIMED_OUT
                ? "WS-COMPAT-SHUTDOWN-TIMEOUT"
                : "WS-COMPAT-SHUTDOWN-INCOMPLETE");

        CompletableFuture<WebSocket> pending = connectFuture;
        try {
            if (pending != null) pending.cancel(true);
        } catch (RuntimeException ignored) {
            // Continue best-effort resource cleanup after a failed future cancellation.
        }
        try {
            httpExecutor.shutdownNow();
        } catch (RuntimeException ignored) {
            // Continue to the independent socket abort attempt.
        }
        WebSocket target = socket != null ? socket : shutdownSocket;
        if (target != null) {
            try {
                target.abort();
            } catch (RuntimeException ignored) {
                // The shutdown deadline remains a failure even if the JDK abort call fails.
            }
        }
    }

    private void safePrivateDiagnostic(String reason) {
        try {
            privateDiagnostic.accept(reason);
        } catch (RuntimeException ignored) {
            // Shutdown reporting must not keep the watchdog alive.
        }
    }

    private static String requiredString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("invalid string field");
        String result = value.getAsString();
        if (result.isEmpty() || result.length() > 2048) throw new IllegalArgumentException("invalid string length");
        return result;
    }

    private static int requiredInteger(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() ||
                !value.getAsString().matches("(?:0|[1-9][0-9]*)")) throw new IllegalArgumentException("invalid integer field");
        return Integer.parseInt(value.getAsString());
    }

    private static String requiredSha256(JsonObject object, String key) {
        String value = requiredString(object, key);
        if (!value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid digest field");
        return value;
    }

    private static java.util.UUID parseUuidOrRandom(String value) {
        if (value != null) {
            try {
                java.util.UUID parsed = java.util.UUID.fromString(value);
                if (parsed.toString().equals(value)) return parsed;
            } catch (IllegalArgumentException ignored) {
                // Stress fixtures may use short labels; emitted v2 envelopes still use canonical UUIDs.
            }
        }
        return java.util.UUID.randomUUID();
    }

    private static long parseLongOrZero(String value) {
        if (value == null) return 0;
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { return 0; }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        long generation;
        outboundLock.lock();
        try {
            if (socket != webSocket) return CompletableFuture.completedFuture(null);
            generation = connectionGeneration.get();
        } finally {
            outboundLock.unlock();
        }
        connectionLost(generation, webSocket, "WS-COMPAT-COMPANION-DISCONNECTED");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        long generation;
        outboundLock.lock();
        try {
            if (socket != webSocket) return;
            generation = connectionGeneration.get();
        } finally {
            outboundLock.unlock();
        }
        connectionLost(generation, webSocket, "WS-COMPAT-CONNECTION-FAILED");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        connectionGeneration.incrementAndGet();
        authenticated = false;
        scheduler.shutdownNow();
        owner.shutdownWithControl(this::closeTransportOnOwner, this::abortAfterShutdownTimeout);
    }

    BoundedIoLane.ShutdownStatus shutdownStatusForTests() {
        return owner.shutdownStatus();
    }

    long connectionGenerationForTests() {
        return connectionGeneration.get();
    }

    boolean awaitOwnerTerminationForTests(long timeout, TimeUnit unit) throws InterruptedException {
        return owner.awaitTermination(timeout, unit);
    }

    boolean awaitOwnerBarrierForTests(long timeout, TimeUnit unit) throws InterruptedException {
        CountDownLatch barrier = new CountDownLatch(1);
        return owner.tryExecute(barrier::countDown) && barrier.await(timeout, unit);
    }

    public enum AcknowledgementSubmission {
        ACCEPTED,
        REJECTED
    }

    public enum AcknowledgementDelivery {
        /** The JDK WebSocket send future completed; this is not peer receipt or agent consumption. */
        SEND_COMPLETED,
        FAILED
    }

    public enum ProgressSubmission { ACCEPTED, REJECTED }

    public enum ProgressDelivery {
        /** The JDK WebSocket send future completed; this is not peer receipt or agent consumption. */
        SEND_COMPLETED,
        FAILED
    }

    public enum CancellationDelivery {
        /** The local JDK send operation completed; this does not prove companion receipt. */
        SEND_COMPLETED,
        FAILED
    }

    private enum OutboundDelivery { SEND_COMPLETED, FAILED }

    private static final class OutboundFrame {
        private final long generation;
        private final WebSocket socket;
        private final String text;
        private final Consumer<OutboundDelivery> completion;
        private final long enqueuedAtNanos;
        private long sendStartedAtNanos;

        private OutboundFrame(long generation, WebSocket socket, String text,
                              Consumer<OutboundDelivery> completion, long enqueuedAtNanos) {
            this.generation = generation;
            this.socket = socket;
            this.text = text;
            this.completion = completion;
            this.enqueuedAtNanos = enqueuedAtNanos;
        }
    }

    private record OutboundCompletion(OutboundFrame frame, Throwable failure) {}

    private final class GenerationListener implements WebSocket.Listener {
        private final long generation;

        private GenerationListener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            BoundCompanionClient.this.onOpen(generation, webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            return BoundCompanionClient.this.onText(generation, webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (isCurrent(generation, webSocket)) {
                connectionLost(generation, webSocket, "WS-COMPAT-COMPANION-DISCONNECTED");
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (isCurrent(generation, webSocket)) {
                connectionLost(generation, webSocket, "WS-COMPAT-CONNECTION-FAILED");
            }
        }
    }
}
