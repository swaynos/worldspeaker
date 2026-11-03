package org.worldspeaker.bootstrap;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.world.ServerChunkManager;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.CharacterChatStyle;
import org.worldspeaker.compat.AttentionPolicy;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.GateState;
import org.worldspeaker.compat.GateSnapshot;
import org.worldspeaker.compat.InteractionPolicy;
import org.worldspeaker.integration.DialogueEnvelope;
import org.worldspeaker.integration.BoundCompanionClient;
import org.worldspeaker.runtime.GameLoopMailbox;
import org.worldspeaker.runtime.ThreadBoundary;
import org.worldspeaker.runtime.TickMetrics;
import org.worldspeaker.runtime.TickMetrics.FirstPublicDisposition;
import org.worldspeaker.runtime.TickMetrics.FirstPublicSource;
import org.worldspeaker.ServerServices;
import org.worldspeaker.WorldspeakerServer;
import org.worldspeaker.ability.AbilityExecutionGate;
import org.worldspeaker.ability.AbilityPolicyResolver;
import org.worldspeaker.ability.AbilityValidation;
import org.worldspeaker.compat.AbilityPolicy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/** Bounded explicit-command smoke path; it exposes no game abilities. */
public final class SmokeConversation implements AutoCloseable {
    private static final Object ABILITY_AUTHORITY_SERVICE_KEY = new Object();
    private static final long PENDING_MAX_AGE_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final long FIRST_REPLY_CUTOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(750);
    private static final int MAX_PENDING_GLOBAL = 16;
    private static final int MAX_PENDING_PLAYER = 2;
    private static final String QUEUED_PROGRESS_TEXT = "I am still waiting to start your request.";
    private static final String AWAITING_ANSWER_PROGRESS_TEXT =
            "Your request has been forwarded and I am still waiting for a complete answer.";
    private static final String ACK_CONTEXT_FAILURE_TEXT =
            "I delivered the acknowledgement locally, but could not add it to the conversation. Further requests are stopped to avoid missing context.";
    private static final String PROGRESS_CONTEXT_FAILURE_TEXT =
            "I delivered a progress notice, but could not preserve it in the conversation. Further requests are stopped to avoid missing context.";
    private static final String CONTEXT_BLOCKED_TERMINAL_TEXT =
            "I stopped this request because earlier delivery context could not be added to the conversation.";

    private final MinecraftServer server;
    private final CompanionConfig config;
    private final CompatibilityGate gate;
    private final BoundCompanionClient companion;
    private final GameLoopMailbox mailbox;
    private final TickMetrics metrics;
    private final long serviceGeneration;
    private final Object serviceEpoch;
    private final String serverEpochId;
    private final FollowupWindow followupWindow;
    private final PlayerFreshness freshness;
    private final ConversationFreshnessPipeline freshnessPipeline;
    private final AttentionBuffer attention;
    private final Consumer<String> attentionDiagnostic;
    private final FirstReplyScheduler firstReplyScheduler;
    private final GamePort gamePort;
    private final AbilityLimitLedger abilityLimitLedger;
    private final Map<UUID, ArrayDeque<Request>> byPlayer = new HashMap<>();
    private final LinkedHashMap<String, InteractionIngress.Invocation> retiredOutputOwners = new LinkedHashMap<>();
    private final ArrayDeque<UUID> roundRobin = new ArrayDeque<>();
    private final SmokeRepeatGuard repeatGuard = new SmokeRepeatGuard();
    private final SmokeRequestLifecycle<Request> requests = new SmokeRequestLifecycle<>();
    private final RequestAbilityIssuer abilityIssuer;
    private int pendingCount;
    private int deadlineScanCursor;
    private int freshnessScanCursor;
    private long observedGateGeneration;
    private Request acknowledgementDeliveryBarrier;
    private boolean acknowledgementContextFailed;
    private Request acknowledgementContextFailureRequest;
    private ProgressBarrier progressDeliveryBarrier;
    private boolean unavailableRetirementPending;
    private boolean admissionClosed;
    private boolean evidenceAdmissionClosed;
    private final SmokeDispositionQueue<Request> unavailableDispositions = new SmokeDispositionQueue<>();
    private Request activeUnavailableDisposition;
    private long nextRequestOrder;
    private boolean dispositionBackpressure;
    private boolean stopped;

    public SmokeConversation(MinecraftServer server, CompanionConfig config, CompatibilityGate gate,
                             BoundCompanionClient companion, GameLoopMailbox mailbox,
                             TickMetrics metrics, long serviceGeneration) {
        this(server, config, gate, companion, mailbox, metrics, serviceGeneration, new Object());
    }

    public SmokeConversation(MinecraftServer server, CompanionConfig config, CompatibilityGate gate,
                             BoundCompanionClient companion, GameLoopMailbox mailbox,
                             TickMetrics metrics, long serviceGeneration, Object serviceEpoch) {
        this(server, config, gate, companion, mailbox, metrics, serviceGeneration, serviceEpoch,
                UUID.randomUUID().toString());
    }

    public SmokeConversation(MinecraftServer server, CompanionConfig config, CompatibilityGate gate,
                             BoundCompanionClient companion, GameLoopMailbox mailbox,
                             TickMetrics metrics, long serviceGeneration, Object serviceEpoch, String serverEpochId) {
        this(server, config, gate, companion, mailbox, metrics, serviceGeneration, serviceEpoch, serverEpochId, ignored -> {});
    }

    public SmokeConversation(MinecraftServer server, CompanionConfig config, CompatibilityGate gate,
                             BoundCompanionClient companion, GameLoopMailbox mailbox,
                             TickMetrics metrics, long serviceGeneration, Object serviceEpoch, String serverEpochId,
                             Consumer<String> attentionDiagnostic) {
        this(server, config, gate, companion, mailbox, metrics, serviceGeneration, serviceEpoch, serverEpochId,
                attentionDiagnostic, FirstReplyScheduler.system(), new MinecraftGamePort(server));
    }

    public SmokeConversation(MinecraftServer server, CompanionConfig config, CompatibilityGate gate,
                             BoundCompanionClient companion, GameLoopMailbox mailbox,
                             TickMetrics metrics, long serviceGeneration, Object serviceEpoch, String serverEpochId,
                             Consumer<String> attentionDiagnostic, FirstReplyScheduler firstReplyScheduler) {
        this(server, config, gate, companion, mailbox, metrics, serviceGeneration, serviceEpoch, serverEpochId,
                attentionDiagnostic, firstReplyScheduler, new MinecraftGamePort(server));
    }

    /** Injects only the game-object boundary; lifecycle, ingress, scheduler, mailbox, and policy remain production code. */
    public SmokeConversation(MinecraftServer server, CompanionConfig config, CompatibilityGate gate,
                             BoundCompanionClient companion, GameLoopMailbox mailbox,
                             TickMetrics metrics, long serviceGeneration, Object serviceEpoch, String serverEpochId,
                             Consumer<String> attentionDiagnostic, FirstReplyScheduler firstReplyScheduler,
                             GamePort gamePort) {
        this(server, config, gate, companion, mailbox, metrics, serviceGeneration, serviceEpoch, serverEpochId,
                attentionDiagnostic, firstReplyScheduler, gamePort, null);
    }

    /** Isolated-owner injection is package-scoped and cannot create a packet-backed live owner. */
    SmokeConversation(MinecraftServer server, CompanionConfig config, CompatibilityGate gate,
                      BoundCompanionClient companion, GameLoopMailbox mailbox,
                      TickMetrics metrics, long serviceGeneration, Object serviceEpoch, String serverEpochId,
                      Consumer<String> attentionDiagnostic, FirstReplyScheduler firstReplyScheduler,
                      GamePort gamePort, AbilityRequestAuthority.IsolatedOwner isolatedAbilityOwner) {
        this.server = server;
        this.config = config;
        this.gate = gate;
        this.companion = companion;
        this.mailbox = mailbox;
        this.metrics = metrics;
        this.serviceGeneration = serviceGeneration;
        this.serviceEpoch = Objects.requireNonNull(serviceEpoch, "serviceEpoch");
        this.serverEpochId = Objects.requireNonNull(serverEpochId, "serverEpochId");
        UUID.fromString(serverEpochId);
        this.followupWindow = new FollowupWindow(config == null ? 30 : config.followupWindowSeconds());
        this.attentionDiagnostic = attentionDiagnostic == null ? ignored -> {} : attentionDiagnostic;
        this.firstReplyScheduler = Objects.requireNonNull(firstReplyScheduler, "firstReplyScheduler");
        this.gamePort = Objects.requireNonNull(gamePort, "gamePort");
        this.abilityLimitLedger = abilityLimitLedgerFor(serviceEpoch, server);
        if (config == null) {
            this.freshness = null;
            this.freshnessPipeline = null;
            this.attention = null;
        } else {
            Set<UUID> allowed = config.interactionPolicy().conversationAllowedPlayers();
            this.freshness = new PlayerFreshness(allowed, serviceEpoch, serviceGeneration, config.binding());
            this.freshnessPipeline = new ConversationFreshnessPipeline(
                    freshness, this::currentFreshnessSnapshot, this::markRequestsObsolete);
            this.attention = new AttentionBuffer(config.attentionPolicy(), allowed, config.binding(), UUID.fromString(serverEpochId));
        }
        this.observedGateGeneration = gate == null ? -1 : gate.snapshot().generation();
        AbilityRequestAuthority.Owner owner = isolatedAbilityOwner == null
                ? AbilityRequestAuthority.productionOwner(ABILITY_AUTHORITY_SERVICE_KEY, this,
                        productionAbilityAuthorityGamePort(), this.abilityLimitLedger)
                : isolatedAbilityOwner;
        this.abilityIssuer = new RequestAbilityIssuer(owner);
    }

    static boolean ownsAbilityAuthorityServiceKey(Object key, SmokeConversation conversation) {
        return key == ABILITY_AUTHORITY_SERVICE_KEY && conversation != null;
    }

    private static AbilityLimitLedger abilityLimitLedgerFor(Object serviceEpoch, MinecraftServer server) {
        if (serviceEpoch instanceof ServerServices services) {
            AbilityLimitLedger shared = services.abilityLimitLedgerFor(server);
            if (shared != null) return shared;
        }
        return AbilityLimitLedger.processOwnedLedger();
    }

    public FirstReplyScheduler firstReplyScheduler() { return firstReplyScheduler; }

    /** Common admission boundary. Identity, current connection, world, and the grant are rechecked here. */
    public SubmitOutcome submit(ServerPlayerEntity player, String text, InteractionIngress.Invocation invocation) {
        return submit(gamePort.sourceFacts(player), text, invocation);
    }

    /** Production ingress overload. Test ports substitute game objects only, not this lifecycle or its state. */
    SubmitOutcome submit(InteractionIngress.SourceFacts source, String text, InteractionIngress.Invocation invocation) {
        ThreadBoundary.assertServerThread();
        Object player = source == null ? null : source.player();
        PlayerView view = gamePort.inspect(player);
        if (player == null || invocation == null || !matchesSource(source, view) ||
                !sameSource(source, invocation.source()) || !view.validForConversation()) {
            return SubmitOutcome.UNAUTHENTICATED;
        }
        if (evidenceAdmissionClosed || metrics == null || !invocation.hasEvidenceReservation() ||
                !invocation.hasCurrentEvidenceReservation(metrics)) {
            evidenceAdmissionClosed = true;
            return SubmitOutcome.UNAVAILABLE;
        }
        if (!config.interactionPolicy().allows(view.playerId(), InteractionPolicy.CONVERSE_PERMISSION)) {
            return SubmitOutcome.DENIED;
        }
        if (stopped) {
            return SubmitOutcome.UNAVAILABLE;
        }
        if (admissionClosed) return SubmitOutcome.UNAVAILABLE;
        if (dispositionBackpressure) {
            if (firstReplyScheduler.pendingDispositionCount() >= FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS) {
                return SubmitOutcome.UNAVAILABLE;
            }
            dispositionBackpressure = false;
        }
        if (acknowledgementContextFailed) {
            return SubmitOutcome.UNAVAILABLE;
        }
        if (text == null || text.isBlank() || text.length() > InteractionIngress.MAX_INPUT_LENGTH) {
            return SubmitOutcome.INVALID;
        }
        if (!companion.isReady() || gate.snapshot().state() != GateState.READY) {
            return SubmitOutcome.UNAVAILABLE;
        }
        long capturedAtNanos = invocation.capturedAtNanos();
        long nowNanos = firstReplyScheduler.monotonicNanos();
        if (nowNanos - capturedAtNanos >= PENDING_MAX_AGE_NANOS) return SubmitOutcome.EXPIRED;
        String acknowledgementLine = VanillaDialogue.renderAcknowledgement(
                config.acknowledgementTemplate(), view.playerName(), config.characterDisplayName(),
                config.characterChatStyle());
        if (acknowledgementLine.isEmpty()) {
            return SubmitOutcome.UNAVAILABLE;
        }
        UUID playerId = view.playerId();
        AttributedEvent event;
        try {
            event = AttributedEvent.live(invocation.captured(), serverEpochId, invocation.source(), config.binding(),
                    view.worldId(), text);
        } catch (RuntimeException invalidAttribution) {
            return SubmitOutcome.UNAUTHENTICATED;
        }
        if (invocation.captured().conversationId() == null ||
                "followup".equals(event.inputKind()) && invocation.captured().replyToMessageId() == null) {
            return SubmitOutcome.INVALID;
        }

        ArrayDeque<Request> queue = byPlayer.get(playerId);
        Request active = requests.active();
        boolean blockedAtAdmission = active != null || pendingCount > 0 ||
                acknowledgementDeliveryBarrier != null || progressDeliveryBarrier != null;
        long gateGeneration = gate.snapshot().generation();
        List<SmokeRequestAdmission.LiveRequest> liveRequests = new ArrayList<>(3);
        if (active != null && active.playerId.equals(playerId)) {
            liveRequests.add(new SmokeRequestAdmission.LiveRequest(active.playerId, active.text, active.capturedAtNanos,
                    canCoalesce(active, view, invocation, event, capturedAtNanos, nowNanos, gateGeneration)));
        }
        if (queue != null) {
            for (Request request : queue) {
                liveRequests.add(new SmokeRequestAdmission.LiveRequest(request.playerId, request.text,
                        request.capturedAtNanos,
                        canCoalesce(request, view, invocation, event, capturedAtNanos, nowNanos, gateGeneration)));
            }
        }
        SmokeRequestAdmission.Decision decision = SmokeRequestAdmission.decide(
                playerId, text, capturedAtNanos, liveRequests,
                queue == null ? 0 : queue.size(), pendingCount, MAX_PENDING_PLAYER, MAX_PENDING_GLOBAL);
        if (decision == SmokeRequestAdmission.Decision.COALESCE) {
            Request linkedRequest = coalescedRequest(view, invocation, event, capturedAtNanos, nowNanos, gateGeneration);
            if (linkedRequest != null) invocation.recordRequestLink(linkedRequest.requestId, pendingCount,
                    queue == null ? 0 : queue.size(), false);
            return linkedRequest == null ? SubmitOutcome.BUSY : SubmitOutcome.COALESCED;
        }
        SmokeRepeatGuard.Key repeatKey = repeatGuardKey(view, invocation, event, gateGeneration);
        SmokeRepeatGuard.Check repeatCheck = repeatGuard.check(repeatKey, nowNanos);
        if (repeatCheck.status() == SmokeRepeatGuard.Status.REPEAT) {
            invocation.recordRepeatGuardCause(false);
            invocation.recordRequestLink(repeatCheck.requestId(), pendingCount, queue == null ? 0 : queue.size(), false);
            return SubmitOutcome.RATE_LIMITED;
        }
        if (repeatCheck.status() == SmokeRepeatGuard.Status.CAPACITY) {
            invocation.recordRepeatGuardCause(true);
            return SubmitOutcome.RATE_LIMITED;
        }
        if (decision == SmokeRequestAdmission.Decision.REJECT_CAPACITY) return SubmitOutcome.BUSY;

        PlayerFreshness.Snapshot freshnessSnapshot = captureFreshness(player);
        if (freshnessSnapshot == null) return SubmitOutcome.UNAUTHENTICATED;
        if (nextRequestOrder == Long.MAX_VALUE) {
            admissionClosed = true;
            return SubmitOutcome.UNAVAILABLE;
        }
        GateSnapshot finalGate = gate.snapshot();
        long commitAtNanos = firstReplyScheduler.monotonicNanos();
        if (finalGate.state() != GateState.READY || finalGate.generation() != gateGeneration || !companion.isReady()) {
            return SubmitOutcome.UNAVAILABLE;
        }
        if (commitAtNanos - capturedAtNanos >= PENDING_MAX_AGE_NANOS) return SubmitOutcome.EXPIRED;
        repeatCheck = repeatGuard.check(repeatKey, commitAtNanos);
        if (repeatCheck.status() == SmokeRepeatGuard.Status.REPEAT) {
            invocation.recordRepeatGuardCause(false);
            invocation.recordRequestLink(repeatCheck.requestId(), pendingCount, queue == null ? 0 : queue.size(), false);
            return SubmitOutcome.RATE_LIMITED;
        }
        if (repeatCheck.status() == SmokeRepeatGuard.Status.CAPACITY) {
            invocation.recordRepeatGuardCause(true);
            return SubmitOutcome.RATE_LIMITED;
        }
        Request request = new Request(
                distinctRequestId(invocation.invocationId()),
                playerId,
                view.connection(),
                view.player(),
                gamePort.worldIdentity(view.player()),
                view.playerName(),
                text,
                invocation,
                acknowledgementLine,
                config.characterDisplayName(), config.characterChatStyle(),
                gateGeneration,
                serviceGeneration,
                serviceEpoch,
                view.worldId(),
                invocation.followupCandidate(), event, freshnessSnapshot, invocation.captured().conversationId(),
                invocation.captured().replyToMessageId(), blockedAtAdmission, nextRequestOrder,
                config.executionBudgetSeconds());
        InvocationRateLimiter.Charge admissionCharge = invocation.commitNewWork(metrics, () ->
                repeatGuard.commit(repeatKey, request.requestId, capturedAtNanos, commitAtNanos));
        if (admissionCharge == InvocationRateLimiter.Charge.EVIDENCE_UNAVAILABLE) {
            evidenceAdmissionClosed = true;
            return SubmitOutcome.UNAVAILABLE;
        }
        if (admissionCharge != InvocationRateLimiter.Charge.ALLOWED) return SubmitOutcome.RATE_LIMITED;
        nextRequestOrder++;
        if (queue == null) {
            queue = new ArrayDeque<>();
            byPlayer.put(playerId, queue);
        }
        requests.register(request, request.connection);
        queue.addLast(request);
        pendingCount++;
        if (queue.size() == 1) roundRobin.addLast(playerId);
        abilityIssuer.register(request);
        abilityIssuer.issue(request);
        invocation.recordRequestLink(request.requestId, pendingCount, queue.size(), true);
        invocation.recordExecutionBudget(request.executionBudgetSeconds, request.pendingDeadlineNanos,
                request.absoluteExecutionDeadlineNanos);
        metrics.increment(TickMetrics.Counter.REQUESTS_ADMITTED);
        return SubmitOutcome.ADMITTED;
    }

    /** Called by shared ingress before policy/admission so even denied asks close an older window. */
    public FollowupWindow.Candidate beginExplicit(InteractionIngress.SourceFacts source,
                                                  InteractionIngress.Captured captured, Object activeServiceEpoch) {
        ThreadBoundary.assertServerThread();
        if (source == null || source.playerId() == null) return null;
        followupWindow.invalidate(source.playerId());
        observeGateGeneration();
        if (captured == null || activeServiceEpoch != serviceEpoch || stopped ||
                followupWindow.durationSeconds() == 0 || config == null ||
                !config.interactionPolicy().allows(source.playerId(), InteractionPolicy.CONVERSE_PERMISSION) ||
                gate == null || gate.snapshot().state() != GateState.READY || companion == null || !companion.isReady()) {
            return null;
        }
        FollowupWindow.Context context = currentFollowupContext(source);
        return beginExplicitInContext(source, captured, activeServiceEpoch, context);
    }

    /** Offline fixture entry into the same bounded candidate state; it accepts no text-derived authority. */
    FollowupWindow.Candidate beginExplicitForTesting(InteractionIngress.SourceFacts source,
                                                     InteractionIngress.Captured captured,
                                                     Object activeServiceEpoch,
                                                     FollowupWindow.Context trustedContext) {
        ThreadBoundary.assertServerThread();
        if (source != null) followupWindow.invalidate(source.playerId());
        observeGateGeneration();
        return beginExplicitInContext(source, captured, activeServiceEpoch, trustedContext);
    }

    private FollowupWindow.Candidate beginExplicitInContext(InteractionIngress.SourceFacts source,
                                                            InteractionIngress.Captured captured,
                                                            Object activeServiceEpoch,
                                                            FollowupWindow.Context context) {
        if (source == null || captured == null || context == null || source.playerId() == null ||
                activeServiceEpoch != serviceEpoch || stopped || followupWindow.durationSeconds() == 0 ||
                config == null || gate == null || gate.snapshot().state() != GateState.READY ||
                companion == null || !companion.isReady() ||
                !config.interactionPolicy().allows(source.playerId(), InteractionPolicy.CONVERSE_PERMISSION) ||
                !source.playerId().equals(context.playerId()) || source.player() != context.player() ||
                source.connection() != context.connection() || !Objects.equals(source.worldId(), context.dimension()) ||
                !config.binding().serverId().equals(context.serverId()) ||
                !config.binding().worldId().equals(context.worldId()) ||
                !config.binding().characterId().equals(context.characterId()) ||
                !config.binding().agentId().equals(context.agentId()) || context.serviceEpoch() != serviceEpoch ||
                context.serviceGeneration() != serviceGeneration || context.gateGeneration() != gate.snapshot().generation()) {
            return null;
        }
        return captured.conversationId() == null ? null : followupWindow.beginExplicit(context,
                captured.invocationId(), captured.origin(), captured.capturedAtNanos(), captured.conversationId());
    }

    int followupRecordCountForTesting() { return followupWindow.size(); }

    AttentionBuffer attentionForTesting() {
        ThreadBoundary.assertServerThread();
        return attention;
    }

    long transportGenerationForTesting(UUID invocationId) {
        ThreadBoundary.assertServerThread();
        if (invocationId == null) return -1;
        for (Request request : requests.liveRequests()) {
            if (invocationId.toString().equals(request.invocationId)) return request.transportGeneration;
        }
        return -1;
    }

    /** Internal deny-gate seam for a retained request; it does not publish a tool or return world facts. */
    AbilityExecutionGate.Decision evaluateAbilityIntent(String requestId, byte[] encodedIntent) {
        // Compatibility path has one conservative operation identity per request. Future WORLD work must
        // supply its own retained query/action ID through the owner-bound proposal path below.
        return evaluateAbilityIntent(requestId, encodedIntent, requestId);
    }

    /** Internal typed correlation input; operationId is deduplication data, not a model permission field. */
    AbilityExecutionGate.Decision evaluateAbilityIntent(String requestId, byte[] encodedIntent, String operationId) {
        ThreadBoundary.assertServerThread();
        Request request = requests.liveRequests().stream()
                .filter(candidate -> candidate.requestId.equals(requestId)).findFirst().orElse(null);
        if (request == null || request.abilityAuthority == null || request.abilityExpected == null) {
            return new AbilityExecutionGate.Decision(AbilityExecutionGate.State.REJECTED,
                    AbilityExecutionGate.Reason.STALE_CONTEXT, false);
        }
        AbilityValidation.Decision parsed = AbilityValidation.parse(encodedIntent);
        if (parsed.status() != AbilityValidation.DecisionStatus.VALID) {
            return new AbilityExecutionGate.Decision(AbilityExecutionGate.State.REJECTED,
                    AbilityExecutionGate.Reason.PERMISSION_DENIED, false);
        }
        AbilityValidation.Decision checked = AbilityValidation.validate(encodedIntent,
                request.abilityAuthority, request.abilityExpected);
        if (checked.status() != AbilityValidation.DecisionStatus.VALID) {
            return new AbilityExecutionGate.Decision(AbilityExecutionGate.State.REJECTED,
                    AbilityExecutionGate.Reason.STALE_CONTEXT, false);
        }
        AbilityRequestAuthority.OperationProposal proposal = abilityIssuer.propose(request, operationId,
                checked.intent());
        if (proposal == null) {
            return new AbilityExecutionGate.Decision(AbilityExecutionGate.State.REJECTED,
                    AbilityExecutionGate.Reason.STALE_CONTEXT, false);
        }
        return AbilityExecutionGate.evaluate(proposal, firstReplyScheduler.monotonicNanos());
    }

    int retainedAbilityHoldsForTesting() { return abilityIssuer.retainedHoldsForTesting(); }

    int releaseRetainedAbilityHoldsForTesting() { return abilityIssuer.releaseRetainedHoldsForTesting(); }

    AbilityLimitLedger.StartResult startFirstRetainedAbilityHoldForTesting() {
        return abilityIssuer.startFirstRetainedForTesting(firstReplyScheduler::monotonicNanos);
    }

    private void assertAbilityServerThread() {
        if (!(serviceEpoch instanceof ServerServices services) || services.generation() != serviceGeneration ||
                !services.ownsServer(server) || !WorldspeakerServer.isActiveServiceOwner(services) ||
                services.conversation() != this || services.config() != config || services.gate() != gate ||
                services.companion() != companion) {
            throw new IllegalStateException("ability request owner is not the active server service");
        }
        ThreadBoundary.assertServerThread(services.threadBoundaryToken());
        if (server == null || !server.isOnThread()) {
            throw new IllegalStateException("ability facts require the active Minecraft server thread");
        }
    }

    private boolean currentAbilityRequest(Request request, long peerGeneration) {
        try {
            assertAbilityServerThread();
            long now = firstReplyScheduler.monotonicNanos();
            long age = now - request.capturedAtNanos;
            InvocationCapture packet = request.invocation.captured().packetCapture();
            return !stopped && request.abilityPeerGeneration == peerGeneration && peerGeneration > 0 &&
                    request.serviceEpoch == serviceEpoch && request.serviceGeneration == serviceGeneration &&
                    request.gateGeneration == gate.snapshot().generation() && gate.snapshot().state() == GateState.READY &&
                    config != null && config.abilityPolicy() != null && request.abilityConfigurationSnapshot == config &&
                    request.abilityPolicySnapshot == config.abilityPolicy() && companion != null &&
                    companion.isAuthenticatedPeerGenerationCurrent(peerGeneration) &&
                    (request.transportGeneration < 0 || request.transportGeneration == peerGeneration) &&
                    requests.isLive(request) && !request.obsolete && request.terminalReason == null &&
                    !request.outageRetirement.isPending() && age >= 0 && age < PENDING_MAX_AGE_NANOS &&
                    request.invocation.captured().provenance() == InteractionIngress.CaptureProvenance.LIVE_PACKET &&
                    packet != null && packet.belongsTo(request.connection, packet.connectionGeneration()) &&
                    packet.connectionGeneration() > 0 &&
                    request.playerId.toString().equals(request.invocation.source().playerId().toString()) &&
                    isFresh(request);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private AbilityPolicyResolver.WorldFacts freshAbilityFacts(Request request, long peerGeneration,
                                                               AbilityValidation.Input intent) {
        assertAbilityServerThread();
        if (!currentAbilityRequest(request, peerGeneration)) return null;
        return MinecraftAbilityFacts.capture(gamePort, server,
                new MinecraftAbilityFacts.RequestContext(request.playerId.toString(), request.originalPlayer,
                        request.connection, request.originalWorld, request.invocation.captured().packetCapture(),
                        request.invocation.captured().provenance() == InteractionIngress.CaptureProvenance.LIVE_PACKET),
                intent, () -> currentAbilityRequest(request, peerGeneration));
    }

    private AbilityRequestAuthority.ReadOnlyAuthorityGamePort productionAbilityAuthorityGamePort() {
        AbilityRequestAuthority.ReadOnlyAuthorityGamePort minecraftPort =
                new AbilityRequestAuthority.ReadOnlyAuthorityGamePort() {
                    @Override public boolean isCurrent(AbilityRequestAuthority.Admission admission) {
                        Request request = admittedRequest(admission);
                        return request != null && currentAbilityRequest(request, admission.peerGeneration());
                    }

                    @Override public boolean requesterMatches(AbilityRequestAuthority.Admission admission) {
                        Request request = admittedRequest(admission);
                        if (request == null || !currentAbilityRequest(request, admission.peerGeneration())) return false;
                        return MinecraftAbilityFacts.requesterMatches(gamePort, server, abilityRequestContext(request));
                    }

                    @Override public AbilityPolicyResolver.WorldFacts freshFacts(
                            AbilityRequestAuthority.Admission admission, AbilityValidation.Input intent) {
                        Request request = admittedRequest(admission);
                        return request == null ? null : freshAbilityFacts(request, admission.peerGeneration(), intent);
                    }
                };
        return AbilityRequestAuthority.guarded(minecraftPort, this::assertAbilityServerThread);
    }

    private Request admittedRequest(AbilityRequestAuthority.Admission admission) {
        if (admission == null || !(admission.requestIdentity() instanceof Request request) ||
                request.invocation != admission.invocationIdentity() || request.abilityPeerGeneration != admission.peerGeneration()) {
            return null;
        }
        return request;
    }

    private MinecraftAbilityFacts.RequestContext abilityRequestContext(Request request) {
        return new MinecraftAbilityFacts.RequestContext(request.playerId.toString(), request.originalPlayer,
                request.connection, request.originalWorld, request.invocation.captured().packetCapture(),
                request.invocation.captured().provenance() == InteractionIngress.CaptureProvenance.LIVE_PACKET);
    }

    /** Performs the second, live source/grant/context check used by the chat callback before ingress. */
    public boolean isFollowupOpen(InteractionIngress.SourceFacts source, Object activeServiceEpoch,
                                  long capturedAtNanos, long beforeIngressNanos) {
        ThreadBoundary.assertServerThread();
        observeGateGeneration();
        if (source == null || source.playerId() == null) return false;
        if (activeServiceEpoch != serviceEpoch || stopped || config == null || gate == null ||
                gate.snapshot().state() != GateState.READY || companion == null || !companion.isReady() ||
                !config.interactionPolicy().allows(source.playerId(), InteractionPolicy.CONVERSE_PERMISSION)) {
            followupWindow.invalidate(source.playerId());
            return false;
        }
        FollowupWindow.Context context = currentFollowupContext(source);
        if (context == null) {
            followupWindow.invalidate(source.playerId());
            return false;
        }
        return followupWindow.isOpen(context, capturedAtNanos, beforeIngressNanos);
    }

    public FollowupWindow.Anchor followupAnchor(InteractionIngress.SourceFacts source, Object activeServiceEpoch,
                                                long capturedAtNanos, long beforeIngressNanos) {
        ThreadBoundary.assertServerThread();
        observeGateGeneration();
        if (source == null || source.playerId() == null || activeServiceEpoch != serviceEpoch || stopped ||
                config == null || gate == null || gate.snapshot().state() != GateState.READY || companion == null ||
                !companion.isReady() || !config.interactionPolicy().allows(source.playerId(), InteractionPolicy.CONVERSE_PERMISSION)) {
            if (source != null) followupWindow.invalidate(source.playerId());
            return null;
        }
        FollowupWindow.Context context = currentFollowupContext(source);
        return context == null ? null : followupWindow.anchor(context, capturedAtNanos, beforeIngressNanos);
    }

    public void invalidateFollowup(UUID playerId) {
        ThreadBoundary.assertServerThread();
        followupWindow.invalidate(playerId);
    }

    /** The command end route is local and never cancels admitted work or calls the companion. */
    public void endFollowup(InteractionIngress.SourceFacts source) {
        ThreadBoundary.assertServerThread();
        if (source != null) {
            followupWindow.invalidate(source.playerId());
            if (attention != null) attention.clearPlayer(source.playerId());
        }
    }

    public void rejectOpeningCandidate(FollowupWindow.Candidate candidate) {
        ThreadBoundary.assertServerThread();
        followupWindow.abandon(candidate);
    }

    public void onPlayerContextChanged(UUID playerId) {
        ThreadBoundary.assertServerThread();
        followupWindow.invalidate(playerId);
        if (freshnessPipeline == null || playerId == null || !freshness.isScoped(playerId)) return;
        ServerPlayerEntity player = server == null ? null : server.getPlayerManager().getPlayer(playerId);
        PlayerFreshness.Observation current = player == null ? null : observePlayer(player);
        applyLifecycleContext(TrustedLifecycleContext.callback(TrustedLifecycleContext.Cause.CONTEXT_CHANGED,
                playerId, List.of(lifecycleChange(TrustedLifecycleContext.Cause.CONTEXT_CHANGED, playerId,
                        current, null, null)), null, null, null, null,
                firstReplyScheduler.monotonicNanos(), firstReplyScheduler.wallTimeMillis()));
    }

    public void onPlayerJoined(ServerPlayerEntity player, long capturedAtNanos, long occurredAtUnixMillis) {
        ThreadBoundary.assertServerThread();
        if (stopped || player == null || server == null) return;
        UUID playerId = player.getUuid();
        PlayerFreshness.Observation observation = observePlayer(player);
        applyLifecycleContext(callbackContext(TrustedLifecycleContext.Cause.PLAYER_JOINED, playerId,
                observation, player, player, player.networkHandler, AttentionEvent.EventKind.JOINED,
                observation == null ? null : observation.dimension(), AttentionEvent.Details.empty(),
                capturedAtNanos, occurredAtUnixMillis));
    }

    public void onPlayerDimensionChanged(ServerPlayerEntity player, ServerWorld origin, ServerWorld destination,
                                         long capturedAtNanos, long occurredAtUnixMillis) {
        ThreadBoundary.assertServerThread();
        if (stopped || player == null || server == null) return;
        UUID playerId = player.getUuid();
        PlayerFreshness.Observation observation = observePlayer(player);
        String originDimension = origin == null ? null : origin.getRegistryKey().getValue().toString();
        applyLifecycleContext(callbackContext(TrustedLifecycleContext.Cause.DIMENSION_CHANGED, playerId,
                observation, player, player, player.networkHandler, AttentionEvent.EventKind.DIMENSION_CHANGED,
                observation == null ? null : observation.dimension(),
                originDimension == null ? null : AttentionEvent.Details.dimension(originDimension),
                capturedAtNanos, occurredAtUnixMillis));
    }

    public void onPlayerDied(ServerPlayerEntity player, long capturedAtNanos, long occurredAtUnixMillis) {
        ThreadBoundary.assertServerThread();
        if (stopped || player == null || server == null) return;
        UUID playerId = player.getUuid();
        PlayerFreshness.Observation observation = observePlayer(player);
        applyLifecycleContext(callbackContext(TrustedLifecycleContext.Cause.DEATH, playerId,
                observation, player, player, player.networkHandler, AttentionEvent.EventKind.DIED,
                observation == null ? null : observation.dimension(), AttentionEvent.Details.empty(),
                capturedAtNanos, occurredAtUnixMillis));
    }

    public void onPlayerRespawned(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer, boolean copiedAlive,
                                  long capturedAtNanos, long occurredAtUnixMillis) {
        ThreadBoundary.assertServerThread();
        if (stopped || newPlayer == null || server == null) return;
        UUID newId = newPlayer.getUuid();
        UUID oldId = oldPlayer == null ? newId : oldPlayer.getUuid();
        PlayerFreshness.Observation observation = observePlayer(newPlayer);
        List<ConversationFreshnessPipeline.LifecycleChange> changes = new ArrayList<>(2);
        if (!oldId.equals(newId)) {
            changes.add(lifecycleChange(TrustedLifecycleContext.Cause.ENTITY_REPLACED, oldId, null,
                    oldPlayer, oldPlayer == null ? null : oldPlayer.networkHandler));
        }
        changes.add(lifecycleChange(TrustedLifecycleContext.Cause.RESPAWN, newId, observation,
                newPlayer, newPlayer.networkHandler));
        applyLifecycleContext(TrustedLifecycleContext.callback(TrustedLifecycleContext.Cause.RESPAWN,
                newId, changes, newPlayer, observation == null ? null : observation.dimension(),
                observation == null ? null : AttentionEvent.EventKind.RESPAWNED,
                observation == null ? null : AttentionEvent.Details.respawn(copiedAlive),
                capturedAtNanos, occurredAtUnixMillis));
    }

    private TrustedLifecycleContext callbackContext(TrustedLifecycleContext.Cause cause, UUID playerId,
                                                    PlayerFreshness.Observation observation, Object subject,
                                                    Object departedEntity, Object departedConnection,
                                                    AttentionEvent.EventKind eventKind, String dimension,
                                                    AttentionEvent.Details details, long capturedAtNanos,
                                                    long occurredAtUnixMillis) {
        if (dimension == null) {
            eventKind = null;
            details = null;
        }
        return TrustedLifecycleContext.callback(cause, playerId,
                List.of(lifecycleChange(cause, playerId, observation, departedEntity, departedConnection)),
                subject, dimension, eventKind, details, capturedAtNanos, occurredAtUnixMillis);
    }

    private static ConversationFreshnessPipeline.LifecycleChange lifecycleChange(
            TrustedLifecycleContext.Cause cause, UUID playerId, PlayerFreshness.Observation observation,
            Object departedEntity, Object departedConnection) {
        return new ConversationFreshnessPipeline.LifecycleChange(playerId,
                observation == null ? null : new ConversationFreshnessPipeline.CurrentSnapshot(playerId, observation),
                departedEntity, departedConnection, cause.reason());
    }

    /** Shared orchestration for typed Fabric callbacks, bounded sampling, and offline lifecycle tests. */
    void applyLifecycleContext(TrustedLifecycleContext context) {
        ThreadBoundary.assertServerThread();
        Objects.requireNonNull(context, "context");
        if (context.mode() == TrustedLifecycleContext.Mode.CALLBACK) {
            for (ConversationFreshnessPipeline.LifecycleChange change : context.changes()) {
                followupWindow.invalidate(change.playerId());
                if (context.cause() == TrustedLifecycleContext.Cause.DISCONNECT) {
                    followupWindow.invalidateConnection(change.departedConnection());
                }
            }
            onLifecycleCallback(context.changes(), context.eventKind() == null ? null :
                    () -> captureLifecycleAttention(context));
            if (context.cause() == TrustedLifecycleContext.Cause.DISCONNECT) {
                context.changes().stream().map(ConversationFreshnessPipeline.LifecycleChange::departedConnection)
                        .filter(Objects::nonNull).findFirst().ifPresent(this::retireDisconnectedConnection);
            }
            return;
        }

        if (freshnessPipeline == null) return;
        ConversationFreshnessPipeline.SampleResult result = freshnessPipeline.sample(context.playerId(),
                new ConversationFreshnessPipeline.CurrentSnapshot(context.playerId(), context.observation()),
                context.eventKind() == null ? null : () -> captureLifecycleAttention(context));
        if (result.sourceUnavailable() && attention != null) attention.recordSourceUnavailable(context.playerId());
    }

    private void onLifecycleCallback(List<ConversationFreshnessPipeline.LifecycleChange> changes,
                                     Runnable optionalCapture) {
        if (freshnessPipeline == null) {
            if (optionalCapture != null) optionalCapture.run();
            return;
        }
        freshnessPipeline.onCallback(changes, optionalCapture);
    }

    private PlayerFreshness.Snapshot captureFreshness(Object player) {
        if (freshness == null || player == null) return null;
        PlayerView view = gamePort.inspect(player);
        if (view == null || !view.validForConversation()) return null;
        UUID playerId = view.playerId();
        PlayerFreshness.Observation observation = observePlayer(player);
        if (observation == null || !observation.alive()) return null;
        return freshnessPipeline.captureAdmissionSnapshot(playerId, observation);
    }

    /** Caller is on the guarded server thread; only direct identity/dimension/liveness/block reads are used. */
    private PlayerFreshness.Observation observePlayer(Object player) {
        if (player == null) return null;
        PlayerView view = gamePort.inspect(player);
        if (view == null || !view.currentPlayer() || !view.currentConnection() || !view.connectionOpen() ||
                !view.onlineMode() || view.worldId() == null) return null;
        return new PlayerFreshness.Observation(player, view.connection(), view.worldId(), view.alive(),
                view.blockX(), view.blockY(), view.blockZ());
    }

    /** Fabric player reads end here; the shared pipeline receives only this immutable observation. */
    private ConversationFreshnessPipeline.CurrentSnapshot currentFreshnessSnapshot(UUID playerId) {
        if (playerId == null) {
            return new ConversationFreshnessPipeline.CurrentSnapshot(playerId, null);
        }
        Object player = gamePort.currentPlayer(playerId);
        return new ConversationFreshnessPipeline.CurrentSnapshot(playerId, observePlayer(player));
    }

    private void captureLifecycleAttention(TrustedLifecycleContext context) {
        String source = context.eventKind() == AttentionEvent.EventKind.MOVED ? "server_sample" : "server_callback";
        captureAttention(context.attentionSource(), context.playerId(), context.dimension(), context.eventKind(),
                context.details(), source, context.capturedAtNanos(), context.occurredAtUnixMillis());
    }

    private void captureAttention(Object subject, UUID playerId, String dimension,
                                  AttentionEvent.EventKind kind, AttentionEvent.Details details,
                                  String source, long capturedAtNanos, long occurredAtUnixMillis) {
        if (attention == null || !attention.enabled() || stopped || gate == null || companion == null ||
                gate.snapshot().state() != GateState.READY || !companion.isReady() ||
                subject == null || playerId == null || kind == null || dimension == null || details == null) return;
        attention.capture(playerId, kind.subscription(), capturedAtNanos, () -> {
            String playerName;
            if (subject instanceof ServerPlayerEntity player) {
                var profile = player.getGameProfile();
                if (profile == null || !playerId.equals(profile.getId())) {
                    throw new IllegalArgumentException("attention subject unavailable");
                }
                playerName = profile.getName();
            } else {
                PlayerView view = gamePort.inspect(subject);
                if (view == null || !playerId.equals(view.playerId())) {
                    throw new IllegalArgumentException("attention subject unavailable");
                }
                playerName = view.playerName();
            }
            return new AttentionEvent(config.binding().serverId(), config.binding().worldId(),
                    config.binding().characterId(), config.binding().agentId(), UUID.randomUUID(),
                    UUID.fromString(serverEpochId), occurredAtUnixMillis, capturedAtNanos, "live", source,
                    playerId, playerName, dimension, kind.wireName(), details);
        });
    }

    private void markRequestsObsolete(UUID playerId) {
        markRequestsObsolete(playerId, "CONTEXT_CHANGED");
    }

    private void markRequestsObsolete(UUID playerId, String cause) {
        if (playerId == null) return;
        Request active = requests.active();
        if (active != null && active.playerId.equals(playerId)) markObsolete(active, cause);
        ArrayDeque<Request> queue = byPlayer.get(playerId);
        if (queue != null) for (Request request : queue) markObsolete(request, cause);
    }

    private void markObsolete(Request request, String cause) {
        if (!request.obsolete) {
            request.obsolete = true;
            request.obsoleteCause = cause;
            revokeAbilityAuthority(request);
        }
    }

    private void revokeAbilityAuthority(Request request) {
        if (request == null) return;
        abilityIssuer.retire(request);
        request.abilityAuthority = null;
        request.abilityExpected = null;
        request.abilityOwnerRegistered = false;
    }

    private boolean retireOneObsolete() {
        Request active = requests.active();
        if (active != null && active.obsolete) {
            retireObsolete(active);
            return true;
        }
        for (UUID playerId : new ArrayList<>(roundRobin)) {
            ArrayDeque<Request> queue = byPlayer.get(playerId);
            if (queue == null) continue;
            for (Request request : queue) {
                if (!request.obsolete) continue;
                retireObsolete(request);
                return true;
            }
        }
        return false;
    }

    private void retireObsolete(Request request) {
        if (!requests.isLive(request)) return;
        if (acknowledgementContextFailed || request.outageRetirement.isPending()) {
            if (acknowledgementContextFailed) {
                boolean failedRequest = request == acknowledgementContextFailureRequest;
                scheduleContextFailureRetirement(request, failedRequest
                        ? (request.progressContextFailure ? PROGRESS_CONTEXT_FAILURE_TEXT : ACK_CONTEXT_FAILURE_TEXT)
                        : CONTEXT_BLOCKED_TERMINAL_TEXT);
            }
            removeUnavailableDisposition(request);
            publishOutageRetirement(request);
            return;
        }
        GateSnapshot snapshot = gate == null ? null : gate.snapshot();
        request.retirementReason = snapshot == null || snapshot.state() != GateState.READY
                ? "UNAVAILABLE" : "CANCELLED";
        request.retirementCause = request.obsoleteCause == null ? "CONTEXT_CHANGED" : request.obsoleteCause;
        request.retirementText = "CANCELLED".equals(request.retirementReason)
                ? "I stopped this request because its context is no longer current."
                : SmokeOutageRetirement.TERMINAL_TEXT;
        commitTerminal(request, request.retirementReason, null, firstReplyScheduler.monotonicNanos());
        if (snapshot != null) request.outageRetirement.schedule(snapshot.generation(), () -> requests.isLive(request));
        if (requests.isPending(request)) removePending(request);
        publishOutageRetirement(request);
    }

    private FollowupWindow.Context currentFollowupContext(InteractionIngress.SourceFacts source) {
        if (source == null || !source.authenticatedPlayer() || server == null || config == null || gate == null ||
                !server.isOnlineMode() || !(source.player() instanceof ServerPlayerEntity player) ||
                source.playerId() == null || !source.playerId().equals(player.getUuid()) || player.getServer() != server ||
                server.getPlayerManager().getPlayer(source.playerId()) != player) return null;
        ServerPlayNetworkHandler connection = player.networkHandler;
        if (connection == null || connection != source.connection() || connection.player != player ||
                !connection.isConnectionOpen()) return null;
        var profile = player.getGameProfile();
        var world = player.getWorld();
        if (profile == null || !source.playerId().equals(profile.getId()) || world == null) return null;
        String worldId = world.getRegistryKey().getValue().toString();
        if (!worldId.equals(source.worldId())) return null;
        var binding = config.binding();
        if (binding == null || !binding.isComplete()) return null;
        GateSnapshot snapshot = gate.snapshot();
        return new FollowupWindow.Context(source.playerId(), server, player, connection,
                binding.serverId(), binding.worldId(), binding.characterId(), binding.agentId(),
                world.getRegistryKey().getValue().toString(), serviceEpoch, serviceGeneration, snapshot.generation());
    }

    private void observeGateGeneration() {
        if (gate == null) return;
        long currentGeneration = gate.snapshot().generation();
        if (currentGeneration != observedGateGeneration) {
            followupWindow.clear();
            if (freshness != null) freshness.clear();
            if (attention != null) attention.clear();
            markAllRequestsObsolete();
            observedGateGeneration = currentGeneration;
        }
    }

    private void revokeStaleAbilityAuthorities() {
        for (Request request : requests.liveRequests()) {
            if (request.abilityAuthority != null &&
                    !abilityIssuer.isCurrent(request)) {
                revokeAbilityAuthority(request);
            }
        }
    }

    private void markAllRequestsObsolete() {
        Request active = requests.active();
        if (active != null) markObsolete(active, "GATE_CHANGED");
        for (ArrayDeque<Request> queue : byPlayer.values()) {
            for (Request request : queue) markObsolete(request, "GATE_CHANGED");
        }
    }

    private void recordDueProgressMisses(Request request, long nowNanos, boolean pending, boolean active) {
        if (request == null) return;
        request.progress.markDueMissed(nowNanos, request.firstReplyPublished, pending, active,
                phase -> recordProgressMissed(request, phase, progressState(request, pending), nowNanos));
    }

    private void recordDueProgressMisses(Request request, long nowNanos) {
        boolean active = request != null && request.progress.forwardedAtNanos() != Long.MIN_VALUE;
        boolean pending = request != null && !active;
        recordDueProgressMisses(request, nowNanos, pending, active);
    }

    /** Fabric END_SERVER_TICK entry. Scans at most 17 records and starts at most eight bounded operations. */
    public void tick() {
        ThreadBoundary.markServerThread();
        ThreadBoundary.assertServerThread();
        long tickStarted = firstReplyScheduler.monotonicNanos();
        SmokeTickBudget budget = new SmokeTickBudget(tickStarted);
        try {
            observeGateGeneration();
            revokeStaleAbilityAuthorities();
            abilityIssuer.cleanupAbilityLimits();
            boolean[] progressStartedThisTick = {false};

            if (mailbox.takeUnavailable()) {
                unavailableRetirementPending = true;
                admissionClosed = true;
                if (attention != null) attention.clear();
                if (freshness != null) freshness.clear();
                markAllRequestsObsolete();
            } else if (gate != null && gate.snapshot().state() != GateState.READY && hasLiveRequests()) {
                unavailableRetirementPending = true;
                admissionClosed = true;
                markAllRequestsObsolete();
            }

            SmokeDeadlineScan.Scan<Request> deadlines = scanDeadlines(budget);

            GameLoopMailbox.TerminalFrame terminal = mailbox.peekTerminal();
            Request activeAtScan = requests.active();
            boolean matchingTerminal = terminal != null && activeAtScan != null &&
                    terminal.serviceGeneration() == serviceGeneration &&
                    terminal.requestId().equals(activeAtScan.requestId) &&
                    terminal.audiencePlayerId().equals(activeAtScan.playerId.toString()) &&
                    terminal.invocationId().equals(activeAtScan.invocationId) &&
                    terminal.characterId().equals(activeAtScan.event.characterId());
            int replyWorkPending = matchingTerminal ? 1 : 0;
            replyWorkPending += firstReplyScheduler.pendingDispositionCount();
            int reservedWorkPending = 0;
            for (SmokeDeadlineScan.Evaluation<Request> evaluation : deadlines.evaluations()) {
                if (evaluation.action() == SmokeDeadlineScan.Action.FIRST_REPLY ||
                        evaluation.action() == SmokeDeadlineScan.Action.DISPOSITION) replyWorkPending++;
                else if (evaluation.action() == SmokeDeadlineScan.Action.PENDING_EXPIRY ||
                        evaluation.action() == SmokeDeadlineScan.Action.ACTIVE_EXPIRY) reservedWorkPending++;
            }
            if (unavailableRetirementPending) replyWorkPending += unavailableDispositions.size() +
                    (activeUnavailableDisposition == null ? 0 : 1);
            if (mailbox.peekAcknowledgement() != null) reservedWorkPending++;
            if (mailbox.peekProgress() != null) reservedWorkPending++;
            if (acknowledgementContextFailed) reservedWorkPending++;
            if (!stopped && !admissionClosed && requests.active() == null && !roundRobin.isEmpty()) reservedWorkPending++;
            final int replyPendingForBudget = replyWorkPending;
            final int reservedPendingForBudget = reservedWorkPending;

            if (matchingTerminal && budget.mayStartOperation(firstReplyScheduler.monotonicNanos(),
                    SmokeTickBudget.WorkClass.REPLY, reservedWorkPending)) {
                budget.operationStarted(SmokeTickBudget.WorkClass.REPLY);
                processTerminal(mailbox.takeTerminal());
            }

            SmokeDispositionTick.forEachDueAction(deadlines, firstReplyScheduler, budget,
                    firstReplyScheduler::monotonicNanos, reservedPendingForBudget, evaluation -> {
                SmokeTickBudget.WorkClass workClass = switch (evaluation.action()) {
                    case DISPOSITION, FIRST_REPLY -> SmokeTickBudget.WorkClass.REPLY;
                    case PENDING_EXPIRY, ACTIVE_EXPIRY -> SmokeTickBudget.WorkClass.RESERVED;
                    case PROGRESS, NONE -> SmokeTickBudget.WorkClass.LOAN;
                };
                int opposingPending = workClass == SmokeTickBudget.WorkClass.REPLY
                        ? reservedPendingForBudget : replyPendingForBudget;
                if (evaluation.action() == SmokeDeadlineScan.Action.PROGRESS &&
                        (replyPendingForBudget > 0 || reservedPendingForBudget > 0)) return true;
                if (!budget.mayStartOperation(firstReplyScheduler.monotonicNanos(), workClass, opposingPending)) return false;
                Request request = evaluation.deadline().request();
                if (!requests.isLive(request)) {
                    if (evaluation.action() == SmokeDeadlineScan.Action.DISPOSITION) {
                        removeUnavailableDisposition(request);
                    }
                    return true;
                }
                switch (evaluation.action()) {
                    case DISPOSITION -> {
                        budget.operationStarted(SmokeTickBudget.WorkClass.REPLY);
                        publishUnavailableDisposition(request);
                    }
                    case FIRST_REPLY -> {
                        if (requests.isPending(request) || requests.isActive(request)) {
                            budget.operationStarted(SmokeTickBudget.WorkClass.REPLY);
                            publishFirstReply(request);
                        }
                    }
                    case PENDING_EXPIRY -> {
                        if (requests.isPending(request)) {
                            budget.operationStarted(SmokeTickBudget.WorkClass.RESERVED);
                            expirePending(request);
                        }
                    }
                    case ACTIVE_EXPIRY -> {
                        if (requests.isActive(request)) {
                            budget.operationStarted(SmokeTickBudget.WorkClass.RESERVED);
                            timeoutActive(request);
                        }
                    }
                    case PROGRESS -> {
                        if (progressStartedThisTick[0]) return false;
                        if (requests.isLive(request) && mailbox.peekTerminal() == null) {
                            progressStartedThisTick[0] = true;
                            budget.operationStarted(SmokeTickBudget.WorkClass.LOAN);
                            publishDueProgress(request, firstReplyScheduler.monotonicNanos());
                        }
                    }
                    case NONE -> throw new IllegalStateException("non-due deadline action");
                }
                return true;
            });

            // First-reply work has priority over this lower-urgency mailbox completion.
            drainAcknowledgementCompletion(mailbox, budget, firstReplyScheduler::monotonicNanos,
                    this::finishAcknowledgementCompletion, replyWorkPending);
            drainProgressCompletion(mailbox, budget, firstReplyScheduler::monotonicNanos, this::finishProgressCompletion,
                    replyWorkPending);

            // Resolve known context-delivery failures before assigning a generic outage terminal.
            if (unavailableRetirementPending) collectUnavailableDispositions();

            if (!unavailableRetirementPending && !acknowledgementContextFailed &&
                    budget.mayStartOperation(firstReplyScheduler.monotonicNanos(),
                    SmokeTickBudget.WorkClass.RESERVED, replyWorkPending) && hasObsoleteRequest()) {
                budget.operationStarted(SmokeTickBudget.WorkClass.RESERVED);
                retireOneObsolete();
            }

            if (mailbox.peekTerminal() != null && budget.mayStartOperation(firstReplyScheduler.monotonicNanos(),
                    SmokeTickBudget.WorkClass.REPLY, reservedWorkPending)) {
                budget.operationStarted(SmokeTickBudget.WorkClass.REPLY);
                processTerminal(mailbox.takeTerminal());
            }
            if (acknowledgementContextFailed && budget.mayStartOperation(firstReplyScheduler.monotonicNanos(),
                    SmokeTickBudget.WorkClass.RESERVED, replyWorkPending)) {
                budget.operationStarted(SmokeTickBudget.WorkClass.RESERVED);
                retireOneAfterAcknowledgementFailure();
            } else if (unavailableRetirementPending && budget.mayStartOperation(firstReplyScheduler.monotonicNanos(),
                    SmokeTickBudget.WorkClass.REPLY, reservedWorkPending)) {
                budget.operationStarted(SmokeTickBudget.WorkClass.REPLY);
                retireOneAsUnavailable();
            } else if (!stopped && budget.mayStartOperation(firstReplyScheduler.monotonicNanos(),
                    SmokeTickBudget.WorkClass.RESERVED, replyWorkPending)) {
                budget.operationStarted(SmokeTickBudget.WorkClass.RESERVED);
                startNextOne(firstReplyScheduler.monotonicNanos());
            }
            // Freshness sampling is lower priority than reply, expiry, context, and forwarding work.
            sampleFreshness(budget, firstReplyScheduler::monotonicNanos);
            if (followupWindow.size() > 0 && budget.mayStartOperation(firstReplyScheduler.monotonicNanos())) {
                budget.operationStarted();
                followupWindow.cleanupOne(firstReplyScheduler.monotonicNanos());
            }
            if (budget.mayStartOperation(firstReplyScheduler.monotonicNanos())) {
                budget.operationStarted();
                metrics.settleFirstReplyDeadlines(firstReplyScheduler.monotonicNanos());
            }
            if (budget.operationsStarted() == SmokeTickBudget.MAX_OPERATIONS) {
                metrics.increment(TickMetrics.Counter.TICK_BUDGET_EXHAUSTED);
            }
        } finally {
            metrics.recordTickNanos(firstReplyScheduler.monotonicNanos() - tickStarted);
            if (attention != null) {
                String diagnostic = attention.diagnosticSnapshot(firstReplyScheduler.monotonicNanos());
                if (diagnostic != null) attentionDiagnostic.accept(diagnostic);
            }
        }
    }

    private void sampleFreshness(SmokeTickBudget budget, LongSupplier clock) {
        if (freshness == null || stopped || server == null || !ThreadBoundary.isServerThread()) return;
        List<UUID> ids = freshness.playerIds();
        if (ids.isEmpty()) {
            freshnessScanCursor = 0;
            return;
        }
        int sampled = 0;
        while (sampled < 4 && sampled < ids.size() && budget.mayStartOperation(clock.getAsLong())) {
            budget.operationStarted();
            int playerIndex = Math.floorMod(freshnessScanCursor, ids.size());
            freshnessScanCursor = (playerIndex + 1) % ids.size();
            UUID playerId = ids.get(playerIndex);
            long capturedAtNanos = clock.getAsLong();
            long occurredAtUnixMillis = firstReplyScheduler.wallTimeMillis();
            if (attention != null) attention.expireOne(playerId, capturedAtNanos);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player == null) {
                applyLifecycleContext(TrustedLifecycleContext.sample(
                        TrustedLifecycleContext.Cause.SNAPSHOT_UNAVAILABLE, playerId, null, null,
                        null, null, null, capturedAtNanos, occurredAtUnixMillis));
                sampled++;
                continue;
            }
            PlayerFreshness.Observation observation = observePlayer(player);
            if (observation == null || !playerId.equals(player.getUuid())) {
                applyLifecycleContext(TrustedLifecycleContext.sample(
                        TrustedLifecycleContext.Cause.SNAPSHOT_UNAVAILABLE, playerId, null, player,
                        null, null, null, capturedAtNanos, occurredAtUnixMillis));
                sampled++;
                continue;
            }
            applyLifecycleContext(TrustedLifecycleContext.sample(TrustedLifecycleContext.Cause.BLOCK_MOVEMENT,
                    playerId, observation, player, observation.dimension(), AttentionEvent.EventKind.MOVED,
                    AttentionEvent.Details.moved(), capturedAtNanos, occurredAtUnixMillis));
            sampled++;
        }
    }

    private boolean hasObsoleteRequest() {
        Request active = requests.active();
        if (active != null && active.obsolete) return true;
        for (ArrayDeque<Request> queue : byPlayer.values()) {
            for (Request request : queue) if (request.obsolete) return true;
        }
        return false;
    }

    private boolean hasLiveRequests() {
        return requests.active() != null || pendingCount > 0;
    }

    /** Captures the bounded outage disposition work once; active work has one reserved slot. */
    private void collectUnavailableDispositions() {
        long outageGeneration = gate == null ? -1 : gate.snapshot().generation();
        Request active = requests.active();
        if (active != null && requests.isLive(active) && activeUnavailableDisposition == null &&
                !unavailableDispositions.contains(active) &&
                (active.outageRetirement.isPending() || active.outageRetirement.schedule(outageGeneration,
                        () -> requests.isLive(active)))) {
            prepareUnavailableRetirement(active);
            activeUnavailableDisposition = active;
        }
        for (UUID playerId : roundRobin) {
            ArrayDeque<Request> queue = byPlayer.get(playerId);
            if (queue == null) continue;
            for (Request request : queue) {
                if (!requests.isPending(request) || unavailableDispositions.contains(request)) continue;
                if (!request.outageRetirement.isPending() &&
                        !request.outageRetirement.schedule(outageGeneration, () -> requests.isPending(request))) continue;
                prepareUnavailableRetirement(request);
                if (!unavailableDispositions.offer(request, request.capturedAtNanos, request.stableOrder)) {
                    admissionClosed = true;
                    metrics.increment(TickMetrics.Counter.DISPOSITION_QUEUE_OVERFLOWS);
                    request.outageRetirement.suppress();
                    if (removePending(request)) retireForDispositionOverflow(request);
                    return;
                }
            }
        }
    }

    private void prepareUnavailableRetirement(Request request) {
        if (request.terminalReason != null) return;
        request.retirementReason = "UNAVAILABLE";
        request.retirementCause = "UPSTREAM_UNAVAILABLE";
        request.retirementText = SmokeOutageRetirement.TERMINAL_TEXT;
        commitTerminal(request, "UNAVAILABLE", null, firstReplyScheduler.monotonicNanos());
    }

    private void removeUnavailableDisposition(Request request) {
        if (activeUnavailableDisposition == request) activeUnavailableDisposition = null;
        else unavailableDispositions.remove(request);
        if (activeUnavailableDisposition == null && unavailableDispositions.size() == 0) {
            unavailableRetirementPending = false;
            admissionClosed = gate == null || gate.snapshot().state() != GateState.READY;
        }
    }

    private void publishUnavailableDisposition(Request request) {
        removeUnavailableDisposition(request);
        publishOutageRetirement(request);
    }

    private void retireForDispositionOverflow(Request request) {
        long nowNanos = firstReplyScheduler.monotonicNanos();
        request.retirementReason = "UNAVAILABLE";
        request.retirementCause = "DISPOSITION_QUEUE_OVERFLOW";
        request.invocation.recordRetirementNotice("SUPPRESSED");
        commitTerminal(request, "UNAVAILABLE", FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT, nowNanos);
        retire(request);
        firstReplyScheduler.settleNoDispatch(request.invocation.firstReplySlot());
    }

    private SmokeDeadlineScan.Scan<Request> scanDeadlines(SmokeTickBudget budget) {
        List<SmokeDeadlineScan.Deadline<Request>> records = new ArrayList<>(SmokeTickBudget.MAX_DEADLINE_RECORDS);
        int requestRecordLimit = SmokeTickBudget.MAX_DEADLINE_RECORDS -
                (firstReplyScheduler.pendingDispositionCount() > 0 ? 1 : 0);
        boolean outageRetiring = unavailableRetirementPending;
        Request active = requests.active();
        if (active != null && requests.isActive(active)) {
            long progressDeadline = active.progress.nextDeadline(false, true, active.firstReplyPublished);
                records.add(new SmokeDeadlineScan.Deadline<>(active,
                    !outageRetiring && !active.firstReplyPublished && !active.firstReplyUnknown,
                    active.firstReplyDueNanos, outageRetiring ? SmokeDeadlineScan.Expiry.NONE : SmokeDeadlineScan.Expiry.ACTIVE,
                    active.activeDeadlineNanos,
                    outageRetiring || progressDeliveryBarrier != null ? Long.MAX_VALUE : progressDeadline,
                    active.stableOrder));
        }
        int playerQueuesVisited = 0;
        for (UUID playerId : roundRobin) {
            if (records.size() >= requestRecordLimit ||
                    playerQueuesVisited >= MAX_PENDING_GLOBAL) break;
            playerQueuesVisited++;
            ArrayDeque<Request> queue = byPlayer.get(playerId);
            if (queue == null) continue;
            int requestsVisited = 0;
            for (Request request : queue) {
                if (records.size() >= requestRecordLimit ||
                        requestsVisited >= MAX_PENDING_PLAYER) break;
                requestsVisited++;
                if (requests.isPending(request)) {
                    long progressDeadline = request.progress.nextDeadline(true, false, request.firstReplyPublished);
                    records.add(new SmokeDeadlineScan.Deadline<>(request,
                            !outageRetiring && !request.firstReplyPublished && !request.firstReplyUnknown,
                            request.firstReplyDueNanos, outageRetiring ? SmokeDeadlineScan.Expiry.NONE : SmokeDeadlineScan.Expiry.PENDING,
                            request.pendingDeadlineNanos,
                            outageRetiring || progressDeliveryBarrier != null && requests.isActive(request)
                                    ? Long.MAX_VALUE : progressDeadline,
                            request.stableOrder));
                }
            }
        }
        SmokeDeadlineScan.Scan<Request> scan = SmokeDeadlineScan.scan(
                records, firstReplyScheduler::monotonicNanos, deadlineScanCursor, budget);
        deadlineScanCursor = scan.nextCursor();
        int maxPlayerPending = 0;
        for (ArrayDeque<Request> queue : byPlayer.values()) maxPlayerPending = Math.max(maxPlayerPending, queue.size());
        long sampledAtNanos = firstReplyScheduler.monotonicNanos();
        long oldestPendingAgeNanos = 0;
        long oldestCleanupDelayNanos = 0;
        for (SmokeDeadlineScan.Evaluation<Request> evaluation : scan.evaluations()) {
            Request request = evaluation.deadline().request();
            if (!requests.isPending(request)) continue;
            long age = sampledAtNanos - request.capturedAtNanos;
            if (age < 0) continue;
            oldestPendingAgeNanos = Math.max(oldestPendingAgeNanos, age);
            if (age >= PENDING_MAX_AGE_NANOS) {
                oldestCleanupDelayNanos = Math.max(oldestCleanupDelayNanos, age - PENDING_MAX_AGE_NANOS);
            }
        }
        metrics.recordPendingQueueScan(pendingCount, maxPlayerPending, oldestPendingAgeNanos,
                oldestCleanupDelayNanos, scan.recordCount(), playerQueuesVisited);
        return scan;
    }

    /** Production drain seam: never removes a reserved completion unless its operation can start. */
    static boolean drainAcknowledgementCompletion(GameLoopMailbox mailbox, SmokeTickBudget budget,
                                                   LongSupplier clock,
                                                   Consumer<GameLoopMailbox.AcknowledgementCompletion> completionHandler) {
        return drainAcknowledgementCompletion(mailbox, budget, clock, completionHandler, 0);
    }

    static boolean drainAcknowledgementCompletion(GameLoopMailbox mailbox, SmokeTickBudget budget,
                                                   LongSupplier clock,
                                                   Consumer<GameLoopMailbox.AcknowledgementCompletion> completionHandler,
                                                   int replyWorkPending) {
        Objects.requireNonNull(mailbox);
        Objects.requireNonNull(budget);
        Objects.requireNonNull(clock);
        Objects.requireNonNull(completionHandler);
        GameLoopMailbox.AcknowledgementCompletion pending = mailbox.peekAcknowledgement();
        if (pending == null || !budget.mayStartOperation(clock.getAsLong(),
                SmokeTickBudget.WorkClass.RESERVED, replyWorkPending)) return false;

        budget.operationStarted(SmokeTickBudget.WorkClass.RESERVED);
        GameLoopMailbox.AcknowledgementCompletion completion = mailbox.takeAcknowledgement(pending);
        if (completion == null) return false;
        completionHandler.accept(completion);
        return true;
    }

    /** Progress has its own bounded completion slot; terminal and ACK completions are independent. */
    static boolean drainProgressCompletion(GameLoopMailbox mailbox, SmokeTickBudget budget,
                                           LongSupplier clock,
                                           Consumer<GameLoopMailbox.ProgressCompletion> completionHandler) {
        return drainProgressCompletion(mailbox, budget, clock, completionHandler, 0);
    }

    static boolean drainProgressCompletion(GameLoopMailbox mailbox, SmokeTickBudget budget,
                                           LongSupplier clock,
                                           Consumer<GameLoopMailbox.ProgressCompletion> completionHandler,
                                           int replyWorkPending) {
        Objects.requireNonNull(mailbox);
        Objects.requireNonNull(budget);
        Objects.requireNonNull(clock);
        Objects.requireNonNull(completionHandler);
        GameLoopMailbox.ProgressCompletion pending = mailbox.peekProgress();
        if (pending == null || !budget.mayStartOperation(clock.getAsLong(),
                SmokeTickBudget.WorkClass.RESERVED, replyWorkPending)) return false;

        budget.operationStarted(SmokeTickBudget.WorkClass.RESERVED);
        GameLoopMailbox.ProgressCompletion completion = mailbox.takeProgress(pending);
        if (completion == null) return false;
        completionHandler.accept(completion);
        return true;
    }

    private boolean removePending(Request request) {
        ArrayDeque<Request> queue = byPlayer.get(request.playerId);
        if (queue == null || !queue.remove(request)) return false;
        pendingCount--;
        if (queue.isEmpty()) {
            byPlayer.remove(request.playerId);
            roundRobin.removeIf(request.playerId::equals);
        }
        return true;
    }

    private Request coalescedRequest(PlayerView view, InteractionIngress.Invocation invocation, AttributedEvent event,
                                    long capturedAtNanos, long nowNanos, long gateGeneration) {
        Request active = requests.active();
        if (canCoalesce(active, view, invocation, event, capturedAtNanos, nowNanos, gateGeneration)) return active;
        ArrayDeque<Request> queue = byPlayer.get(view.playerId());
        if (queue != null) {
            for (Request request : queue) {
                if (canCoalesce(request, view, invocation, event, capturedAtNanos, nowNanos, gateGeneration)) return request;
            }
        }
        return null;
    }

    private boolean canCoalesce(Request request, PlayerView view, InteractionIngress.Invocation invocation,
                                AttributedEvent event, long capturedAtNanos, long nowNanos,
                                long gateGeneration) {
        if (request == null || view == null || invocation == null || event == null || stopped || admissionClosed ||
                unavailableRetirementPending || acknowledgementContextFailed || gate == null || companion == null ||
                gate.snapshot().state() != GateState.READY || gate.snapshot().generation() != gateGeneration ||
                !companion.isReady() || !requests.isLive(request) || request.obsolete || isExpired(request, nowNanos)) {
            return false;
        }
        long elapsedNanos = capturedAtNanos - request.capturedAtNanos;
        if (elapsedNanos < 0 ||
                !request.playerId.equals(view.playerId()) || !request.text.equals(event.text()) ||
                request.connection != view.connection() || request.gateGeneration != gateGeneration ||
                request.serviceGeneration != serviceGeneration || request.serviceEpoch != serviceEpoch ||
                !request.dimension.equals(view.worldId()) || !request.invocation.origin().equals(invocation.origin()) ||
                !request.event.inputKind().equals(event.inputKind()) ||
                !request.event.serverId().equals(event.serverId()) ||
                !request.event.worldId().equals(event.worldId()) ||
                !request.event.characterId().equals(event.characterId()) ||
                !request.event.agentId().equals(event.agentId()) ||
                !sameCaptureAudience(request, invocation, view.connection())) return false;
        if (invocation.origin() == InteractionIngress.Origin.FOLLOWUP) {
            return Objects.equals(request.conversationId, invocation.captured().conversationId()) &&
                    Objects.equals(request.replyToMessageId, invocation.captured().replyToMessageId());
        }
        return request.replyToMessageId == null && invocation.captured().replyToMessageId() == null;
    }

    private SmokeRepeatGuard.Key repeatGuardKey(PlayerView view, InteractionIngress.Invocation invocation,
                                                AttributedEvent event, long gateGeneration) {
        InteractionIngress.Captured captured = invocation.captured();
        InvocationCapture packet = captured.packetCapture();
        boolean followup = invocation.origin() == InteractionIngress.Origin.FOLLOWUP;
        return new SmokeRepeatGuard.Key(view.playerId(), view.connection(),
                packet == null ? 0 : packet.connectionGeneration(), serviceEpoch, serviceGeneration,
                gateGeneration, event.serverId(), event.worldId(), event.characterId(), event.agentId(),
                view.worldId(), invocation.origin(), event.inputKind(),
                followup ? captured.conversationId() : null,
                followup ? captured.replyToMessageId() : null, event.text());
    }

    private static boolean sameCaptureAudience(Request request, InteractionIngress.Invocation incoming,
                                              Object currentConnection) {
        InteractionIngress.Captured previous = request.invocation.captured();
        InteractionIngress.Captured next = incoming.captured();
        if (previous.provenance() != next.provenance()) return false;
        if (previous.provenance() == InteractionIngress.CaptureProvenance.SYNTHETIC_HARNESS) return true;
        if (previous.provenance() != InteractionIngress.CaptureProvenance.LIVE_PACKET) return false;
        InvocationCapture previousCapture = previous.packetCapture();
        InvocationCapture nextCapture = next.packetCapture();
        return previousCapture != null && nextCapture != null &&
                previousCapture.originalConnection() == currentConnection &&
                nextCapture.originalConnection() == currentConnection &&
                previousCapture.connectionGeneration() == nextCapture.connectionGeneration();
    }

    private void expirePending(Request request) {
        if (!requests.isPending(request)) return;
        retireWithSafeTerminal(request, "EXPIRED", "PENDING_EXPIRY",
                "This request expired before I could start it.");
        metrics.increment(TickMetrics.Counter.PENDING_EXPIRED);
    }

    private void publishFirstReply(Request request) {
        if (stopped || request.firstReplyPublished || request.firstReplyUnknown || !isLive(request) ||
                !firstReplyScheduler.fallbackDue(request.invocation.firstReplySlot(),
                        firstReplyScheduler.monotonicNanos(), request.firstReplyDueNanos)) return;
        if (request.acknowledgementAttempted) {
            if (request.safeAlternativeAttempted) return;
            request.safeAlternativeAttempted = true;
            sendTerminal(request, "I couldn't deliver a reply right now.",
                    FirstPublicSource.LOCAL_SAFE_RESPONSE, FirstPublicDisposition.FAILED, "FAILURE");
            return;
        }
        if (gate.snapshot().state() != GateState.READY || !companion.isReady()) {
            retireUnavailable(request);
            return;
        }
        Object player = currentOriginalPlayer(request);
        if (player == null) return;
        String dispatchedText = request.acknowledgementLine;
        boolean[] submitted = {false};
        boolean[] expiredAtDispatch = {false};
        long[] submittedAtNanos = {0};
        boolean gateAccepted = gate.executeIfReady(request.gateGeneration, () -> {
            if (currentOriginalPlayer(request) != player || !isLive(request) || request.firstReplyPublished ||
                    !companion.isReady()) return;
            if (isExpired(request, firstReplyScheduler.monotonicNanos())) {
                expiredAtDispatch[0] = true;
                return;
            }
            FirstReplyScheduler.Claim claim = firstReplyScheduler.claimAcknowledgement(request.invocation.firstReplySlot());
            if (claim != FirstReplyScheduler.Claim.CLAIMED_FIRST) return;
            request.acknowledgementAttempted = true;
            FirstReplyScheduler.PublicationResult result = gamePort.submitRendered(player, dispatchedText);
            long resultAtNanos = firstReplyScheduler.monotonicNanos();
            firstReplyScheduler.complete(request.invocation.firstReplySlot(), claim, result,
                    FirstReplyScheduler.PublicationKind.ACKNOWLEDGEMENT);
            request.firstPublicDispatch.recordResult(result, metrics, resultAtNanos,
                    FirstPublicSource.LOCAL_ACKNOWLEDGEMENT, FirstPublicDisposition.ACKNOWLEDGED);
            if (result == FirstReplyScheduler.PublicationResult.UNKNOWN) {
                request.firstReplyUnknown = true;
                return;
            }
            if (result == FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT) {
                request.safeAlternativePending = true;
                return;
            }
            request.firstReplyPublished = true;
            submittedAtNanos[0] = resultAtNanos;
            submitted[0] = true;
        });
        if (!gateAccepted) {
            retireUnavailable(request);
            return;
        }
        if (expiredAtDispatch[0]) {
            retireExpired(request);
            return;
        }
        if (!submitted[0]) return;
        long dispatchedAtNanos = submittedAtNanos[0];
        SmokeRequestAcknowledgement.Acknowledgement localAcknowledgement = new SmokeRequestAcknowledgement.Acknowledgement(
                UUID.randomUUID().toString(), dispatchedText, firstReplyScheduler.wallTimeMillis(),
                dispatchedAtNanos, request.playerId.toString());
        SmokeRequestAcknowledgement.Dispatch dispatch = request.acknowledgement.recordDispatched(
                true, localAcknowledgement.messageId(), localAcknowledgement.text(), localAcknowledgement.dispatchedAtUnixMillis(),
                localAcknowledgement.dispatchedAtMonotonicNanos(), localAcknowledgement.audiencePlayerId());
        if (dispatch == SmokeRequestAcknowledgement.Dispatch.SEND_AFTER_REQUEST) {
            if (!request.acknowledgement.beginContextDelivery()) {
                failAcknowledgementContext(request);
                return;
            }
            if (!mailbox.expectAcknowledgement(request.requestId, request.serviceGeneration)) {
                failAcknowledgementContext(request);
                return;
            }
            acknowledgementDeliveryBarrier = request;
            BoundCompanionClient.AcknowledgementSubmission submission = companion.sendDialogueAcknowledgement(
                    request.requestId,
                    request.invocationId,
                    DialogueEnvelope.acknowledgement(localAcknowledgement.messageId(), localAcknowledgement.text(),
                            localAcknowledgement.dispatchedAtUnixMillis(), localAcknowledgement.dispatchedAtMonotonicNanos(),
                            localAcknowledgement.audiencePlayerId()),
                    delivery -> {
                        GameLoopMailbox.Offer offered = mailbox.offerAcknowledgement(
                                new GameLoopMailbox.AcknowledgementCompletion(request.requestId, request.serviceGeneration,
                                        delivery == BoundCompanionClient.AcknowledgementDelivery.SEND_COMPLETED));
                        if (offered == GameLoopMailbox.Offer.CONFLICT) metrics.increment(TickMetrics.Counter.MAILBOX_OVERFLOWS);
                        else if (offered == GameLoopMailbox.Offer.STALE) metrics.increment(TickMetrics.Counter.STALE_OUTPUTS);
                    });
            if (submission == BoundCompanionClient.AcknowledgementSubmission.REJECTED) {
                mailbox.offerAcknowledgement(new GameLoopMailbox.AcknowledgementCompletion(
                        request.requestId, request.serviceGeneration, false));
            }
        }
    }

    private void publishDueProgress(Request request, long nowNanos) {
        if (request == null) return;
        boolean pending = requests.isPending(request);
        boolean active = requests.isActive(request);
        ConversationProgress.Phase phase = request.progress.duePhase(nowNanos, pending, active,
                request.firstReplyPublished, mailbox.peekTerminal() != null);
        if (phase == null || !request.progress.markAttempted(phase)) return;

        if (stopped || !isLive(request) || isExpired(request, nowNanos) ||
                gate.snapshot().generation() != request.gateGeneration ||
                gate.snapshot().state() != GateState.READY || !companion.isReady() ||
                phase == ConversationProgress.Phase.AWAITING_ANSWER && progressDeliveryBarrier != null) {
            recordProgressMissed(request, phase, progressState(request, pending), nowNanos);
            return;
        }
        Object player = currentOriginalPlayer(request);
        PlayerView initialView = player == null ? null : gamePort.inspect(player);
        if (initialView == null || !request.dimension.equals(initialView.worldId())) {
            recordProgressMissed(request, phase, progressState(request, pending), nowNanos);
            return;
        }
        String state = progressState(request, pending);
        String body = switch (state) {
            case "queued" -> QUEUED_PROGRESS_TEXT;
            case "context_blocked" -> "I am holding this request until earlier conversation context is preserved.";
            default -> AWAITING_ANSWER_PROGRESS_TEXT;
        };
        String rendered = VanillaDialogue.renderCharacterLine(request.characterDisplayName, body,
                request.characterChatStyle);
        if (rendered.isEmpty()) {
            recordProgressOutcome(request, phase, state, FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT,
                    nowNanos, null);
            return;
        }
        FirstReplyScheduler.PublicationResult[] publication = {null};
        long[] dispatchedAt = {0};
        boolean gateAccepted = gate.executeIfReady(request.gateGeneration, () -> {
            long decisionAt = firstReplyScheduler.monotonicNanos();
            if (stopped || !isLive(request) || isExpired(request, decisionAt) ||
                    mailbox.peekTerminal() != null || request.invocation.firstReplySlot().terminalReady() ||
                    !companion.isReady()) return;
            Object current = currentOriginalPlayer(request);
            PlayerView currentView = current == null ? null : gamePort.inspect(current);
            if (current != player || currentView == null || !request.dimension.equals(currentView.worldId())) return;
            if (isExpired(request, firstReplyScheduler.monotonicNanos())) return;
            publication[0] = gamePort.submitRendered(current, rendered);
            dispatchedAt[0] = firstReplyScheduler.monotonicNanos();
        });
        if (!gateAccepted || publication[0] == null) {
            recordProgressMissed(request, phase, state, firstReplyScheduler.monotonicNanos());
            return;
        }
        long dispatchedAtNanos = dispatchedAt[0];
        if (publication[0] != FirstReplyScheduler.PublicationResult.SUBMITTED) {
            recordProgressOutcome(request, phase, state, publication[0], dispatchedAtNanos, null);
            if (publication[0] == FirstReplyScheduler.PublicationResult.UNKNOWN) failProgressContext(request);
            return;
        }
        ConversationProgress.DialogueProgress progress = new ConversationProgress.DialogueProgress(
                UUID.randomUUID().toString(), rendered, firstReplyScheduler.wallTimeMillis(), dispatchedAtNanos,
                request.playerId.toString(), phase);
        recordProgressOutcome(request, phase, state, publication[0], dispatchedAtNanos, progress.messageId());
        if (!request.progress.recordDispatched(progress)) {
            request.invocation.recordProgressContext(progress.messageId(), "UNKNOWN", dispatchedAtNanos);
            failProgressContext(request);
            return;
        }
        if (phase == ConversationProgress.Phase.QUEUED) {
            return;
        }
        sendActiveProgressContext(request, progress);
    }

    private void sendActiveProgressContext(Request request, ConversationProgress.DialogueProgress progress) {
        if (!mailbox.expectProgress(request.requestId, progress.messageId(), request.serviceGeneration)) {
            request.invocation.recordProgressContext(progress.messageId(), "FAILED", firstReplyScheduler.monotonicNanos());
            failProgressContext(request);
            return;
        }
        ProgressBarrier barrier = new ProgressBarrier(request, progress.messageId());
        progressDeliveryBarrier = barrier;
        BoundCompanionClient.ProgressSubmission submission = companion.sendDialogueProgress(
                request.requestId, request.invocationId, progress, delivery -> {
                    GameLoopMailbox.ProgressDeliveryOutcome outcome = delivery == BoundCompanionClient.ProgressDelivery.SEND_COMPLETED
                            ? GameLoopMailbox.ProgressDeliveryOutcome.SEND_COMPLETED
                            : GameLoopMailbox.ProgressDeliveryOutcome.UNKNOWN;
                    GameLoopMailbox.Offer offered = mailbox.offerProgress(new GameLoopMailbox.ProgressCompletion(
                            request.requestId, progress.messageId(), request.serviceGeneration, outcome));
                    if (offered == GameLoopMailbox.Offer.CONFLICT) metrics.increment(TickMetrics.Counter.MAILBOX_OVERFLOWS);
                    else if (offered == GameLoopMailbox.Offer.STALE) metrics.increment(TickMetrics.Counter.STALE_OUTPUTS);
                });
        if (submission == BoundCompanionClient.ProgressSubmission.REJECTED) {
            request.invocation.recordProgressContext(progress.messageId(), "FAILED", firstReplyScheduler.monotonicNanos());
            mailbox.offerProgress(new GameLoopMailbox.ProgressCompletion(request.requestId, progress.messageId(),
                    request.serviceGeneration, GameLoopMailbox.ProgressDeliveryOutcome.FAILED));
        }
    }

    private void recordProgressOutcome(Request request, ConversationProgress.Phase phase, String state,
                                       FirstReplyScheduler.PublicationResult result, long atNanos, String noticeId) {
        request.invocation.recordProgress(phase, state, result, atNanos, noticeId);
    }

    private void recordProgressMissed(Request request, ConversationProgress.Phase phase, String state, long atNanos) {
        request.invocation.recordProgressMissed(phase, state, atNanos);
    }

    private String progressState(Request request, boolean pending) {
        if (pending && (acknowledgementDeliveryBarrier != null || progressDeliveryBarrier != null ||
                acknowledgementContextFailed)) return "context_blocked";
        return pending ? "queued" : "forwarded_waiting";
    }

    private boolean isExpired(Request request, long nowNanos) {
        if (request == null) return true;
        if (requests.isPending(request)) return SmokeDeadlineScan.isDue(nowNanos, request.pendingDeadlineNanos);
        return requests.isActive(request) && SmokeDeadlineScan.isDue(nowNanos, request.activeDeadlineNanos);
    }

    private void startNextOne(long nowNanos) {
        if (stopped || admissionClosed || acknowledgementContextFailed || unavailableRetirementPending ||
                (acknowledgementDeliveryBarrier != null &&
                        !acknowledgementDeliveryBarrier.acknowledgement.canStartNextTurn()) ||
                progressDeliveryBarrier != null ||
                requests.active() != null || gate.snapshot().state() != GateState.READY || roundRobin.isEmpty()) return;
        UUID nextPlayer = roundRobin.removeFirst();
        ArrayDeque<Request> queue = byPlayer.get(nextPlayer);
        if (queue == null || queue.isEmpty()) {
            byPlayer.remove(nextPlayer);
            return;
        }
        Request request = queue.removeFirst();
        pendingCount--;
        if (queue.isEmpty()) byPlayer.remove(nextPlayer);
        else roundRobin.addLast(nextPlayer);
        if (SmokeDeadlineScan.isDue(nowNanos, request.pendingDeadlineNanos)) {
            retireWithSafeTerminal(request, "EXPIRED", "PENDING_EXPIRY",
                    "This request expired before I could start it.");
            metrics.increment(TickMetrics.Counter.PENDING_EXPIRED);
            return;
        }
        if (gate.snapshot().generation() != request.gateGeneration || !companion.isReady()) {
            retireUnavailable(request);
            return;
        }
        if (request.obsolete || !isFresh(request)) {
            request.obsolete = true;
            retireWithSafeTerminal(request, "CANCELLED", request.obsoleteCause == null
                    ? "CONTEXT_CHANGED" : request.obsoleteCause, "This request stopped because its context changed.");
            return;
        }
        if (!requests.start(request)) return;
        long activeBudgetNanos = TimeUnit.SECONDS.toNanos(request.executionBudgetSeconds);
        long configuredDeadline = nowNanos + activeBudgetNanos;
        request.activeDeadlineNanos = configuredDeadline - request.absoluteExecutionDeadlineNanos <= 0
                ? configuredDeadline : request.absoluteExecutionDeadlineNanos;
        request.invocation.recordActiveExecutionDeadline(request.activeDeadlineNanos);
        mailbox.publishActive(request.mailboxIdentity());
        SmokeRequestAcknowledgement.Acknowledgement acknowledgement = request.acknowledgement.forRequest();
        com.google.gson.JsonObject acknowledgementFrame = acknowledgement == null ? null :
                DialogueEnvelope.acknowledgement(acknowledgement.messageId(), acknowledgement.text(),
                        acknowledgement.dispatchedAtUnixMillis(), acknowledgement.dispatchedAtMonotonicNanos(),
                        acknowledgement.audiencePlayerId());
        AttentionAttachment.Prepared context = null;
        try {
                    context = AttentionAttachment.prepare(attention, request.playerId, nowNanos,
                    (events, status) -> DialogueEnvelope.request(request.requestId, request.invocationId, request.event,
                            request.conversationId, request.replyToMessageId, acknowledgementFrame, events, status,
                            request.progress.dispatched(), config.abilityPolicy().revision()));
        } catch (IllegalArgumentException invalidFrame) {
            // Even without optional observations, an invalid primary frame fails closed below.
        }
        AttentionAttachment.Prepared preparedContext = context;
        long[] forwardedGeneration = {-1};
        boolean forwarded = preparedContext != null && preparedContext.enqueue(() -> {
            if (isExpired(request, firstReplyScheduler.monotonicNanos()) || request.obsolete || !isFresh(request)) {
                return false;
            }
            forwardedGeneration[0] = companion.sendDialogueRequestGeneration(
                    preparedContext.frame(), request.playerId.toString());
            return forwardedGeneration[0] >= 0;
        });
        if (!forwarded) {
            if (request.progress.dispatched().stream().anyMatch(progress ->
                    progress.phase() == ConversationProgress.Phase.QUEUED)) {
                failProgressContext(request);
            }
            if (attention != null) attention.recordTransportLoss(request.playerId);
            if (attention != null) attention.clear();
            if (freshness != null) freshness.clear();
            retireWithSafeTerminal(request, "UNAVAILABLE", "FORWARD_UNAVAILABLE",
                    "Worldspeaker became unavailable before completing this request.");
            metrics.increment(TickMetrics.Counter.UPSTREAM_REJECTIONS);
            return;
        }
        request.transportGeneration = forwardedGeneration[0];
        request.acknowledgement.markForwarded();
        long forwardedAtNanos = firstReplyScheduler.monotonicNanos();
        ArrayDeque<Request> remainingPlayerQueue = byPlayer.get(request.playerId);
        request.invocation.recordForwardedAt(forwardedAtNanos,
                Math.max(0, forwardedAtNanos - request.capturedAtNanos), pendingCount,
                remainingPlayerQueue == null ? 0 : remainingPlayerQueue.size());
        request.progress.markForwarded(forwardedAtNanos, request.firstReplyPublished,
                phase -> recordProgressMissed(request, phase, "queued", forwardedAtNanos));
        for (ConversationProgress.DialogueProgress queuedProgress : request.progress.dispatched()) {
            if (queuedProgress.phase() == ConversationProgress.Phase.QUEUED) {
                request.invocation.recordProgressContext(queuedProgress.messageId(), "QUEUED_WITH_REQUEST", forwardedAtNanos);
            }
        }
    }

    private void timeoutActive(Request request) {
        if (stopped || !requests.isActive(request)) return;
        retireWithSafeTerminal(request, "EXPIRED", "ACTIVE_EXPIRY",
                "I couldn't complete that request in time.");
    }

    private void retireExpired(Request request) {
        if (requests.isActive(request)) timeoutActive(request);
        else if (requests.isPending(request)) expirePending(request);
    }

    /** Commits a finite local terminal before the live slot and forwarding state are retired. */
    private void retireWithSafeTerminal(Request request, String reason, String cause, String text) {
        if (request == null || !requests.isLive(request)) return;
        request.retirementReason = reason;
        request.retirementCause = cause;
        request.retirementText = text;
        commitTerminal(request, reason, null, firstReplyScheduler.monotonicNanos());
        GateSnapshot snapshot = gate == null ? null : gate.snapshot();
        if (!request.outageRetirement.isPending() && snapshot != null) {
            request.outageRetirement.schedule(snapshot.generation(), () -> requests.isLive(request));
        }
        publishOutageRetirement(request);
    }

    private boolean commitTerminal(Request request, String reason,
                                   FirstReplyScheduler.PublicationResult result, long atNanos) {
        if (request.terminalReason == null) {
            revokeAbilityAuthority(request);
            request.terminalReason = reason;
            request.terminalCause = request.retirementCause;
            request.terminalAtNanos = atNanos;
            request.invocation.recordTerminal(reason, request.terminalCause, result, atNanos);
            return true;
        }
        if (request.terminalReason.equals(reason) && result != null) {
            request.invocation.recordTerminal(reason, request.terminalCause, result, atNanos);
        }
        return false;
    }

    /** Called by the transport listener; only bounded wire data enters the mailbox. */
    public void onDialogueMessage(JsonObject frame) {
        GameLoopMailbox.ActiveRequest active = mailbox.activeRequest();
        String incomingRequestId = frame != null && frame.has("requestId") && frame.get("requestId").isJsonPrimitive() &&
                frame.get("requestId").getAsJsonPrimitive().isString()
                ? frame.get("requestId").getAsString() : null;
        if (active == null || incomingRequestId == null || !active.requestId().equals(incomingRequestId)) {
            recordLateOutput(incomingRequestId);
            metrics.increment(TickMetrics.Counter.STALE_OUTPUTS);
            return;
        }
        GameLoopMailbox.TerminalFrame terminal;
        try { terminal = DialogueEnvelope.terminal(frame, active, serviceGeneration, firstReplyScheduler.monotonicNanos()); }
        catch (RuntimeException invalidFrame) { metrics.increment(TickMetrics.Counter.STALE_OUTPUTS); return; }
        GameLoopMailbox.Offer offered = mailbox.offerTerminal(terminal);
        if (offered == GameLoopMailbox.Offer.DUPLICATE) metrics.increment(TickMetrics.Counter.TERMINAL_DUPLICATES);
        else if (offered == GameLoopMailbox.Offer.CONFLICT) metrics.increment(TickMetrics.Counter.MAILBOX_OVERFLOWS);
        else if (offered == GameLoopMailbox.Offer.STALE || offered == GameLoopMailbox.Offer.INVALID) {
            metrics.increment(TickMetrics.Counter.STALE_OUTPUTS);
        }
    }

    private void processTerminal(GameLoopMailbox.TerminalFrame frame) {
        if (frame == null || stopped || frame.serviceGeneration() != serviceGeneration) return;
        Request request = requests.active();
        if (request == null || !requests.isActive(request) || !request.requestId.equals(frame.requestId()) ||
                !request.invocationId.equals(frame.invocationId()) || !request.event.characterId().equals(frame.characterId()) ||
                !request.playerId.toString().equals(frame.audiencePlayerId())) {
            metrics.increment(TickMetrics.Counter.STALE_OUTPUTS);
            return;
        }
        if (isExpired(request, firstReplyScheduler.monotonicNanos())) {
            timeoutActive(request);
            return;
        }
        if ("failure".equals(frame.kind())) {
            FirstReplyScheduler.PublicationResult[] result = {null};
            boolean[] expiredAtDispatch = {false};
            SmokeCompanionOutputEligibility.executeIfEligible(gate, request.gateGeneration, request,
                    candidate -> !stopped && requests.isActive(candidate), this::currentOriginalOutputPlayer,
                    (candidate, player) -> {
                        if (isExpired(candidate, firstReplyScheduler.monotonicNanos())) {
                            expiredAtDispatch[0] = true;
                            return;
                        }
                         result[0] = submitTerminal(candidate, player, "I couldn't complete that request right now.",
                                 FirstPublicSource.INTEGRATION_FAILURE, FirstPublicDisposition.FAILED);
                         if (result[0] != FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT) {
                             commitTerminal(candidate, "FAILURE", result[0], firstReplyScheduler.monotonicNanos());
                             complete(candidate);
                         }
                    });
            if (expiredAtDispatch[0] || isExpired(request, firstReplyScheduler.monotonicNanos())) {
                if (requests.isActive(request)) timeoutActive(request);
                return;
            }
            if (result[0] == null) metrics.increment(TickMetrics.Counter.STALE_OUTPUTS);
            return;
        }
        String body = frame.text();
        if (!VanillaDialogue.isRenderableAnswerBody(body)) {
            metrics.increment(TickMetrics.Counter.STALE_OUTPUTS);
            return;
        }
        FirstReplyScheduler.PublicationResult[] result = {null};
        long[] answerDispatchNanos = {0};
        boolean[] completed = {false};
        boolean[] expiredAtDispatch = {false};
        SmokeCompanionOutputEligibility.executeIfEligible(gate, request.gateGeneration, request,
                candidate -> !stopped && requests.isActive(candidate), this::currentOriginalOutputPlayer,
                (candidate, player) -> {
                    if (isExpired(candidate, firstReplyScheduler.monotonicNanos())) {
                        expiredAtDispatch[0] = true;
                        return;
                    }
                    String rendered = VanillaDialogue.renderCharacterLine(candidate.characterDisplayName, body,
                            candidate.characterChatStyle);
                    if (rendered.isEmpty()) return;
                     result[0] = submitRendered(candidate, player, rendered,
                             FirstPublicSource.INTEGRATION_ANSWER, FirstPublicDisposition.ANSWERED);
                     if (result[0] == FirstReplyScheduler.PublicationResult.SUBMITTED) {
                         answerDispatchNanos[0] = firstReplyScheduler.monotonicNanos();
                         commitTerminal(candidate, "ANSWER", result[0], answerDispatchNanos[0]);
                         completed[0] = completeAfterAnswer(candidate);
                         if (completed[0]) openFollowupAfterAnswer(candidate, player, answerDispatchNanos[0], frame.messageId());
                     } else if (result[0] == FirstReplyScheduler.PublicationResult.UNKNOWN) {
                         candidate.firstReplyUnknown = true;
                         commitTerminal(candidate, "ANSWER", result[0], firstReplyScheduler.monotonicNanos());
                         completed[0] = complete(candidate);
                     }
                });
        if (expiredAtDispatch[0] || isExpired(request, firstReplyScheduler.monotonicNanos())) {
            if (requests.isActive(request)) timeoutActive(request);
            return;
        }
        if (result[0] == FirstReplyScheduler.PublicationResult.SUBMITTED && completed[0]) {
            metrics.increment(TickMetrics.Counter.ANSWERS_DELIVERED);
            request.invocation.recordTerminal("ANSWER", result[0], answerDispatchNanos[0]);
        } else if (result[0] == null) metrics.increment(TickMetrics.Counter.STALE_OUTPUTS);
    }

    private FirstReplyScheduler.PublicationResult submitTerminal(Request request, Object player, String body,
                                                                  FirstPublicSource source,
                                                                  FirstPublicDisposition disposition) {
        String rendered = VanillaDialogue.renderCharacterLine(request.characterDisplayName, body,
                request.characterChatStyle);
        if (rendered.isEmpty()) return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
        return submitRendered(request, player, rendered, source, disposition);
    }

    private FirstReplyScheduler.PublicationResult submitRendered(Request request, Object player,
                                                                   String renderedLine, FirstPublicSource source,
                                                                   FirstPublicDisposition disposition) {
        PlayerView view = player == null ? null : gamePort.inspect(player);
        if (request.terminalPublished || view == null || view.connection() != request.connection ||
                renderedLine.isEmpty() || !isFresh(request)) {
            return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
        }
        if ((source == FirstPublicSource.INTEGRATION_ANSWER || source == FirstPublicSource.INTEGRATION_FAILURE) &&
                isExpired(request, firstReplyScheduler.monotonicNanos())) {
            return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
        }
        FirstReplyScheduler.Claim claim = firstReplyScheduler.claimTerminal(request.invocation.firstReplySlot());
        if (claim != FirstReplyScheduler.Claim.CLAIMED_FIRST && claim != FirstReplyScheduler.Claim.LATE_TERMINAL) {
            return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
        }
        FirstReplyScheduler.PublicationResult result = gamePort.submitRendered(player, renderedLine);
        long resultAtNanos = firstReplyScheduler.monotonicNanos();
        if (claim == FirstReplyScheduler.Claim.CLAIMED_FIRST) {
            firstReplyScheduler.complete(request.invocation.firstReplySlot(), claim, result,
                    FirstReplyScheduler.PublicationKind.TERMINAL);
        }
        request.firstPublicDispatch.recordResult(result, metrics, resultAtNanos, source, disposition);
        if (result == FirstReplyScheduler.PublicationResult.SUBMITTED) {
            request.firstReplyPublished = true;
            request.terminalPublished = true;
        } else if (result == FirstReplyScheduler.PublicationResult.UNKNOWN) {
            request.terminalPublished = true;
            if (claim == FirstReplyScheduler.Claim.CLAIMED_FIRST) request.firstReplyUnknown = true;
        }
        return result;
    }

    private Object currentOriginalOutputPlayer(Request request) {
        Object player = originalPlayer(request);
        PlayerView view = player == null ? null : gamePort.inspect(player);
        if (view == null || !requests.isActiveOn(request, view.connection()) ||
                !request.dimension.equals(view.worldId()) || !isFresh(request)) return null;
        return player;
    }

    private Object currentOriginalPlayer(Request request) {
        Object player = originalPlayer(request);
        PlayerView view = player == null ? null : gamePort.inspect(player);
        if (view == null || !requests.isBoundTo(request, view.connection()) ||
                !request.dimension.equals(view.worldId()) || !isFresh(request)) return null;
        return player;
    }

    /** Direct UUID-only refresh at forwarding and final requester-output gates. */
    private boolean isFresh(Request request) {
        if (request == null || request.obsolete || freshness == null || stopped ||
                request.serviceGeneration != serviceGeneration || request.serviceEpoch != serviceEpoch ||
                gate == null || gate.snapshot().generation() != request.gateGeneration ||
                ThreadBoundary.isServerThread() == false) return false;
        return freshnessPipeline.isFresh(new ConversationFreshnessPipeline.RequestSnapshot(
                        request.playerId, request.freshnessSnapshot, request.serviceEpoch,
                        request.serviceGeneration, request.gateGeneration, request.obsolete),
                new ConversationFreshnessPipeline.RuntimeSnapshot(serviceEpoch, serviceGeneration,
                        gate.snapshot().generation()));
    }

    private void openFollowupAfterAnswer(Request request, Object player, long dispatchedAtNanos,
                                          String answerMessageId) {
        FollowupWindow.Candidate candidate = request.openingCandidate;
        if (candidate == null) return;
        InteractionIngress.SourceFacts source = player instanceof ServerPlayerEntity serverPlayer
                ? currentSourceFacts(serverPlayer) : gamePort.sourceFacts(player);
        if (source == null || config == null ||
                !config.interactionPolicy().allows(request.playerId, InteractionPolicy.CONVERSE_PERMISSION)) {
            followupWindow.abandon(candidate);
            return;
        }
        FollowupWindow.Context current = currentFollowupContext(source);
        if (current == null || !followupWindow.open(candidate, current, dispatchedAtNanos, answerMessageId)) {
            followupWindow.abandon(candidate);
        }
    }

    private InteractionIngress.SourceFacts currentSourceFacts(ServerPlayerEntity player) {
        if (player == null || player.getServer() != server || !server.isOnlineMode() || player.networkHandler == null ||
                player.networkHandler.player != player || !player.networkHandler.isConnectionOpen() ||
                server.getPlayerManager().getPlayer(player.getUuid()) != player) return null;
        var profile = player.getGameProfile();
        var world = player.getWorld();
        if (profile == null || world == null || !player.getUuid().equals(profile.getId())) return null;
        return new InteractionIngress.SourceFacts(true, true, true, player.getUuid(), profile.getName(),
                world.getRegistryKey().getValue().toString(), player, player.networkHandler);
    }

    /** Called by Fabric's play disconnect event on the server thread. */
    public void onPlayerDisconnected(ServerPlayNetworkHandler connection) {
        onPlayerDisconnected(connection, firstReplyScheduler.monotonicNanos(), firstReplyScheduler.wallTimeMillis());
    }

    public void onPlayerDisconnected(ServerPlayNetworkHandler connection, long capturedAtNanos,
                                     long occurredAtUnixMillis) {
        ThreadBoundary.assertServerThread();
        if (connection == null || connection.player == null) return;
        ServerPlayerEntity departed = connection.player;
        UUID playerId = departed.getUuid();
        followupWindow.invalidateConnection(connection);
        if (stopped) return;
        String dimension = departed.getWorld() == null ? null :
                departed.getWorld().getRegistryKey().getValue().toString();
        applyLifecycleContext(callbackContext(TrustedLifecycleContext.Cause.DISCONNECT, playerId,
                null, departed, departed, connection, AttentionEvent.EventKind.LEFT, dimension,
                AttentionEvent.Details.empty(), capturedAtNanos, occurredAtUnixMillis));
    }

    private void retireDisconnectedConnection(Object connection) {
        List<Request> retired = requests.liveForConnection(connection);
        if (retired.isEmpty()) return;
        for (Request request : retired) {
            request.retirementReason = "CANCELLED";
            request.retirementCause = "DISCONNECT";
            request.retirementText = null;
            commitTerminal(request, "CANCELLED", null, firstReplyScheduler.monotonicNanos());
            if (requests.isPending(request)) removePending(request);
            request.outageRetirement.suppress();
            removeUnavailableDisposition(request);
            request.invocation.recordRetirementNotice("NO_AUDIENCE");
            retire(request);
            boolean retainContextCompletion = acknowledgementDeliveryBarrier == request &&
                    request.acknowledgement.contextDeliveryPending();
            if (!retainContextCompletion) {
                if (acknowledgementDeliveryBarrier == request) acknowledgementDeliveryBarrier = null;
                mailbox.clearAcknowledgement(request.requestId, request.serviceGeneration);
            }
            if (acknowledgementContextFailureRequest == request) acknowledgementContextFailureRequest = null;
        }
    }

    /** Worker-side status signal: a coalesced bit, never a server.execute task. */
    public void onUnavailable() {
        mailbox.markUnavailable();
    }

    public void closeAdmissionForEvidenceLoss() {
        ThreadBoundary.assertServerThread();
        // Stop new admissions without stranding requests committed before evidence was lost.
        evidenceAdmissionClosed = true;
    }

    public void closeAdmissionForDispositionOverflow() {
        ThreadBoundary.assertServerThread();
        dispositionBackpressure = true;
    }

    private boolean isLive(Request request) {
        return !request.obsolete && requests.isLive(request) && (requests.isActive(request) ||
                byPlayer.getOrDefault(request.playerId, new ArrayDeque<>()).contains(request));
    }

    private boolean complete(Request request) {
        return complete(request, false);
    }

    private boolean completeAfterAnswer(Request request) {
        return complete(request, true);
    }

    private boolean complete(Request request, boolean preserveOpeningCandidate) {
        recordDueProgressMisses(request, firstReplyScheduler.monotonicNanos(), requests.isPending(request), requests.isActive(request));
        if (!requests.complete(request)) return false;
        revokeAbilityAuthority(request);
        mailbox.clearActive(request.mailboxIdentity());
        request.acknowledgement.close();
        if (!preserveOpeningCandidate) followupWindow.abandon(request.openingCandidate);
        return true;
    }

    private boolean retire(Request request) {
        recordDueProgressMisses(request, firstReplyScheduler.monotonicNanos(), requests.isPending(request), requests.isActive(request));
        String reason = request.retirementReason == null ? "CANCELLED" : request.retirementReason;
        if (request.terminalReason == null) {
            commitTerminal(request, reason, null, firstReplyScheduler.monotonicNanos());
        }
        if (request.acknowledgement.isForwarded()) rememberRetiredOutput(request);
        if (!requests.retire(request)) return false;
        revokeAbilityAuthority(request);
        followupWindow.abandon(request.openingCandidate);
        mailbox.clearActive(request.mailboxIdentity());
        cancelForwardedRequest(request);
        request.acknowledgement.close();
        return true;
    }

    private void cancelForwardedRequest(Request request) {
        if (request.acknowledgement.isForwarded() && !request.localCancelAttempted) {
            request.localCancelAttempted = true;
            request.providerCancel = false;
            if (stopped) {
                request.invocation.recordLocalCancelStatus("SHUTDOWN", false);
            } else {
                boolean accepted = companion.cancelDialogueRequest(request.requestId, request.invocationId,
                        request.playerId.toString(), request.transportGeneration,
                        delivery -> request.invocation.recordLocalCancelDelivery(delivery.name()));
                request.invocation.recordLocalCancelStatus(accepted ? "QUEUED" : "DEFINITELY_UNSENT", false);
            }
        }
    }

    private void rememberRetiredOutput(Request request) {
        synchronized (retiredOutputOwners) {
            retiredOutputOwners.put(request.requestId, request.invocation);
            while (retiredOutputOwners.size() > 256) {
                String oldest = retiredOutputOwners.keySet().iterator().next();
                retiredOutputOwners.remove(oldest);
            }
        }
    }

    private void recordLateOutput(String requestId) {
        InteractionIngress.Invocation owner;
        synchronized (retiredOutputOwners) {
            owner = requestId == null ? null : retiredOutputOwners.get(requestId);
        }
        if (owner == null) return;
        owner.recordLateOutputRejected();
    }

    private void finishAcknowledgementCompletion(GameLoopMailbox.AcknowledgementCompletion completion) {
        Request barrier = acknowledgementDeliveryBarrier;
        if (barrier == null || barrier.serviceGeneration != completion.serviceGeneration() ||
                !barrier.requestId.equals(completion.requestId())) return;
        if (!barrier.acknowledgement.finishContextDelivery(completion.delivered())) return;
        acknowledgementDeliveryBarrier = null;
        if (!completion.delivered()) failAcknowledgementContext(barrier);
    }

    private void finishProgressCompletion(GameLoopMailbox.ProgressCompletion completion) {
        ProgressBarrier barrier = progressDeliveryBarrier;
        if (barrier == null || barrier.request.serviceGeneration != completion.serviceGeneration() ||
                !barrier.request.requestId.equals(completion.requestId()) ||
                !barrier.messageId.equals(completion.messageId())) return;
        progressDeliveryBarrier = null;
        long completedAtNanos = firstReplyScheduler.monotonicNanos();
        if (completion.outcome() == GameLoopMailbox.ProgressDeliveryOutcome.SEND_COMPLETED) {
            barrier.request.invocation.recordProgressContext(barrier.messageId, "SEND_COMPLETED", completedAtNanos);
            return;
        }
        barrier.request.invocation.recordProgressContext(barrier.messageId,
                completion.outcome() == GameLoopMailbox.ProgressDeliveryOutcome.FAILED ? "FAILED" : "UNKNOWN",
                completedAtNanos);
        failProgressContext(barrier.request);
    }

    /** A locally published acknowledgement must not be forgotten or bypassed by a later turn. */
    private void failAcknowledgementContext(Request acknowledgementRequest) {
        if (acknowledgementContextFailed) return;
        acknowledgementContextFailed = true;
        acknowledgementDeliveryBarrier = null;
        if (acknowledgementRequest != null && requests.isLive(acknowledgementRequest)) {
            acknowledgementContextFailureRequest = acknowledgementRequest;
            scheduleContextFailureRetirement(acknowledgementRequest);
        } else {
            // A disconnect/completion may already have retired this request. Keep its recorded
            // cancellation, and never turn a later login into the old request's audience.
            acknowledgementContextFailureRequest = null;
        }
        metrics.increment(TickMetrics.Counter.ACK_CONTEXT_FAILURES);
    }

    private void failProgressContext(Request progressRequest) {
        if (acknowledgementContextFailed) return;
        acknowledgementContextFailed = true;
        progressDeliveryBarrier = null;
        if (progressRequest != null && requests.isLive(progressRequest)) {
            acknowledgementContextFailureRequest = progressRequest;
            progressRequest.progressContextFailure = true;
            scheduleContextFailureRetirement(progressRequest);
        } else {
            acknowledgementContextFailureRequest = null;
        }
        metrics.increment(TickMetrics.Counter.PROGRESS_CONTEXT_FAILURES);
    }

    /** Snapshots the one-shot terminal ticket before any retirement clears live lifecycle ownership. */
    private void scheduleContextFailureRetirement(Request request) {
        if (request == null) return;
        scheduleContextFailureRetirement(request, request.progressContextFailure
                ? PROGRESS_CONTEXT_FAILURE_TEXT : ACK_CONTEXT_FAILURE_TEXT);
    }

    private void scheduleContextFailureRetirement(Request request, String text) {
        if (request == null || !requests.isLive(request) || request.outageRetirement.isPending()) return;
        request.retirementCause = request.progressContextFailure ? "PROGRESS_CONTEXT_FAILED" : "ACK_CONTEXT_FAILED";
        if (gate == null) {
            request.retirementText = SmokeOutageRetirement.TERMINAL_TEXT;
            request.retirementReason = "UNAVAILABLE";
            return;
        }
        GateSnapshot snapshot = gate.snapshot();
        if (snapshot.state() == GateState.READY) {
            request.retirementText = text;
            request.retirementReason = "CONTEXT_FAILED";
        } else {
            request.retirementText = SmokeOutageRetirement.TERMINAL_TEXT;
            request.retirementReason = "UNAVAILABLE";
        }
        request.outageRetirement.schedule(snapshot.generation(), () -> requests.isLive(request));
    }

    private void retireOneAfterAcknowledgementFailure() {
        Request request = requests.active();
        if (request == null) request = takeOnePending();
        if (request != null) {
            boolean isFailedAcknowledgementRequest = request == acknowledgementContextFailureRequest;
            if (requests.isLive(request)) {
                if (!request.outageRetirement.isPending()) {
                    scheduleContextFailureRetirement(request, isFailedAcknowledgementRequest
                            ? (request.progressContextFailure ? PROGRESS_CONTEXT_FAILURE_TEXT : ACK_CONTEXT_FAILURE_TEXT)
                            : CONTEXT_BLOCKED_TERMINAL_TEXT);
                }
                publishOutageRetirement(request);
            }
            if (isFailedAcknowledgementRequest) acknowledgementContextFailureRequest = null;
            return;
        }
        if (acknowledgementContextFailureRequest != null) {
            // The request may have disconnected or completed while its transport callback was in
            // flight. Its earlier terminal/cancellation remains authoritative; do not rebind it.
            acknowledgementContextFailureRequest = null;
        }
        byPlayer.clear();
        roundRobin.clear();
        pendingCount = 0;
        if (attention != null) attention.clear();
        if (freshness != null) freshness.clear();
    }

    private void retireOneAsUnavailable() {
        Request queued = unavailableDispositions.peek();
        Request request = activeUnavailableDisposition;
        if (request == null || queued != null && compareDispositionOrder(queued, request) < 0) {
            request = unavailableDispositions.remove();
        } else {
            activeUnavailableDisposition = null;
        }
        if (request != null) {
            removeUnavailableDisposition(request);
            publishOutageRetirement(request);
            return;
        }
        unavailableRetirementPending = false;
        admissionClosed = gate == null || gate.snapshot().state() != GateState.READY;
    }

    private static int compareDispositionOrder(Request left, Request right) {
        int deadline = Long.compare(left.capturedAtNanos, right.capturedAtNanos);
        return deadline != 0 ? deadline : Long.compare(left.stableOrder, right.stableOrder);
    }

    private Request takeOnePending() {
        while (!roundRobin.isEmpty()) {
            UUID playerId = roundRobin.removeFirst();
            ArrayDeque<Request> queue = byPlayer.get(playerId);
            if (queue == null || queue.isEmpty()) {
                byPlayer.remove(playerId);
                continue;
            }
            Request request = queue.removeFirst();
            pendingCount--;
            if (queue.isEmpty()) byPlayer.remove(playerId);
            else roundRobin.addLast(playerId);
            return request;
        }
        return null;
    }

    private boolean publish(Request request, Object player, String text,
                            FirstPublicSource source, FirstPublicDisposition disposition) {
        PlayerView view = player == null ? null : gamePort.inspect(player);
        if (request.terminalPublished || view == null || view.connection() != request.connection) return false;
        String renderedLine = VanillaDialogue.renderCharacterLine(request.characterDisplayName, text,
                request.characterChatStyle);
        return publishRendered(request, player, renderedLine, source, disposition);
    }

    private boolean publishRendered(Request request, Object player, String renderedLine,
                                    FirstPublicSource source, FirstPublicDisposition disposition) {
        return publishRendered(request, player, renderedLine, source, disposition, ignored -> {});
    }

    private boolean publishRendered(Request request, Object player, String renderedLine,
                                    FirstPublicSource source, FirstPublicDisposition disposition,
                                    LongConsumer successfulDispatch) {
        FirstReplyScheduler.PublicationResult result = submitRendered(request, player, renderedLine, source, disposition);
        if (result != FirstReplyScheduler.PublicationResult.SUBMITTED) return false;
        successfulDispatch.accept(firstReplyScheduler.monotonicNanos());
        return true;
    }

    private FirstReplyScheduler.PublicationResult sendTerminal(Request request, String text,
                                                               FirstPublicSource source,
                                                               FirstPublicDisposition disposition,
                                                               String terminalReason) {
        Object player = originalPlayer(request);
        PlayerView view = player == null ? null : gamePort.inspect(player);
        FirstReplyScheduler.PublicationResult result = null;
        if (view != null && request.dimension.equals(view.worldId()) &&
                isFresh(request)) {
            String rendered = VanillaDialogue.renderCharacterLine(request.characterDisplayName, text,
                    request.characterChatStyle);
            result = rendered.isEmpty() ? FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT
                    : submitRendered(request, player, rendered, source, disposition);
        }
        if (result == null) request.terminalPublished = true;
        long atNanos = firstReplyScheduler.monotonicNanos();
        commitTerminal(request, terminalReason, result, atNanos);
        return result;
    }

    private void retireUnavailable(Request request) {
        if (request == null) return;
        request.retirementReason = "UNAVAILABLE";
        request.retirementCause = "GATE_UNAVAILABLE";
        request.retirementText = SmokeOutageRetirement.TERMINAL_TEXT;
        commitTerminal(request, "UNAVAILABLE", null, firstReplyScheduler.monotonicNanos());
        if (!request.outageRetirement.isPending() && gate != null) {
            GateSnapshot snapshot = gate.snapshot();
            if (snapshot.state() != GateState.READY) {
                request.outageRetirement.schedule(snapshot.generation(), () -> requests.isLive(request));
            }
        }
        removeUnavailableDisposition(request);
        publishOutageRetirement(request);
    }

    private void publishOutageRetirement(Request request) {
        if (request == null) return;
        removeUnavailableDisposition(request);
        if (request.terminalReason == null) {
            String reason = request.retirementReason == null ? "UNAVAILABLE" : request.retirementReason;
            if (request.retirementCause == null) request.retirementCause = "RETIREMENT_SUPPRESSED";
            commitTerminal(request, reason, null, firstReplyScheduler.monotonicNanos());
        }
        if (!request.outageRetirement.isPending()) {
            if (request.invocation != null) request.invocation.recordRetirementNotice("SUPPRESSED");
            if (requests.isPending(request)) removePending(request);
            retire(request);
            return;
        }

        String terminalText = request.retirementText == null
                ? SmokeOutageRetirement.TERMINAL_TEXT : request.retirementText;
        String terminalReason = request.retirementReason == null ? "UNAVAILABLE" : request.retirementReason;
        String rendered = VanillaDialogue.renderCharacterLine(request.characterDisplayName, terminalText,
                request.characterChatStyle);
        Object[] audience = {null};
        SmokeOutageRetirement.Dispatch dispatch = request.outageRetirement.retireAndPublish(
                () -> requests.isLive(request),
                () -> {
                    if (requests.isPending(request)) removePending(request);
                    return retire(request);
                },
                firstReplyScheduler,
                request.invocation.firstReplySlot(),
                () -> {
                    audience[0] = currentUnavailableAudience(request);
                    return audience[0] != null;
                },
                () -> rendered.isEmpty() ? FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT
                        : gamePort.submitRendered(audience[0], rendered),
                noticeId -> {
                    if (request.unavailableTerminalId != null || noticeId.equals(request.invocation.firstReplySlot().publicationId())) {
                        return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
                    }
                    request.unavailableTerminalId = noticeId;
                    return rendered.isEmpty() ? FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT
                            : gamePort.submitRendered(audience[0], rendered);
                });

        FirstReplyScheduler.PublicationResult result = dispatch.publicationResult();
        long resultAtNanos = firstReplyScheduler.monotonicNanos();
        if (result == null) {
            if (dispatch.outcome() == SmokeOutageRetirement.Outcome.NO_AUDIENCE) {
                request.invocation.recordRetirementNotice("NO_AUDIENCE");
            } else if (dispatch.outcome() == SmokeOutageRetirement.Outcome.REQUEST_FINISHED) {
                request.invocation.recordRetirementNotice("SUPPRESSED");
            }
            if (acknowledgementContextFailureRequest == request) acknowledgementContextFailureRequest = null;
            return;
        }
        if (dispatch.path() == SmokeOutageRetirement.Path.FIRST_PUBLICATION) {
            request.firstPublicDispatch.recordResult(result, metrics, resultAtNanos,
                    "CONTEXT_FAILED".equals(terminalReason)
                            ? FirstPublicSource.LOCAL_SAFE_RESPONSE : FirstPublicSource.LOCAL_UNAVAILABLE,
                    "CONTEXT_FAILED".equals(terminalReason)
                            ? FirstPublicDisposition.FAILED : FirstPublicDisposition.UNAVAILABLE);
        }
        request.invocation.recordRetirementNotice(result.name());
        if (result == FirstReplyScheduler.PublicationResult.SUBMITTED) {
            if (dispatch.path() == SmokeOutageRetirement.Path.FIRST_PUBLICATION) request.firstReplyPublished = true;
            request.terminalPublished = true;
            request.invocation.recordTerminal(terminalReason, result, resultAtNanos);
            if ("UNAVAILABLE".equals(terminalReason)) metrics.increment(TickMetrics.Counter.UNAVAILABLE_DISPOSITIONS);
        } else if (result == FirstReplyScheduler.PublicationResult.UNKNOWN) {
            if (dispatch.path() == SmokeOutageRetirement.Path.FIRST_PUBLICATION) request.firstReplyUnknown = true;
            request.terminalPublished = true;
            request.invocation.recordTerminal(terminalReason, result, resultAtNanos);
        } else {
            request.invocation.recordTerminal(terminalReason, result, resultAtNanos);
        }
        if (acknowledgementContextFailureRequest == request) acknowledgementContextFailureRequest = null;
    }

    /** Safe retirement notices bypass answer freshness, not the original connection or current permission. */
    private Object currentUnavailableAudience(Request request) {
        if (request == null || stopped || request.serviceEpoch != serviceEpoch ||
                request.serviceGeneration != serviceGeneration || !gamePort.onlineMode() ||
                config == null || !config.interactionPolicy().allows(request.playerId, InteractionPolicy.CONVERSE_PERMISSION)) {
            return null;
        }
        Object player = onlinePlayer(request.playerId);
        PlayerView view = player == null ? null : gamePort.inspect(player);
        return view != null && request.playerId.equals(view.playerId()) && view.currentPlayer() &&
                view.connection() == request.connection && view.currentConnection() && view.connectionOpen() ? player : null;
    }

    private Object onlinePlayer(UUID id) {
        return gamePort.currentPlayer(id);
    }

    private Object originalPlayer(Request request) {
        Object player = onlinePlayer(request.playerId);
        PlayerView view = player == null ? null : gamePort.inspect(player);
        return view != null && view.playerId().equals(request.playerId) && view.currentPlayer() &&
                view.currentConnection() && view.connectionOpen() && view.connection() == request.connection ? player : null;
    }

    private static String requiredFrameString(JsonObject frame, String field) {
        if (frame == null) throw new IllegalArgumentException("missing terminal frame");
        JsonElement value = frame.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("invalid terminal field");
        }
        String result = value.getAsString();
        if (result.isEmpty() || result.length() > VanillaDialogue.MAX_RENDERED_LENGTH) {
            throw new IllegalArgumentException("invalid terminal field length");
        }
        return result;
    }

    public enum SubmitOutcome {
        ADMITTED,
        COALESCED,
        BUSY,
        UNAVAILABLE,
        DENIED,
        UNAUTHENTICATED,
        INVALID,
        RATE_LIMITED,
        EXPIRED
    }

    private static boolean matchesSource(InteractionIngress.SourceFacts source, PlayerView view) {
        return source != null && view != null && source.authenticatedPlayer() && source.player() == view.player() &&
                source.connection() == view.connection() && source.playerId().equals(view.playerId()) &&
                source.playerName().equals(view.playerName()) && source.worldId().equals(view.worldId()) &&
                source.onlineMode() && source.currentConnection() && view.validForConversation();
    }

    private static boolean sameSource(InteractionIngress.SourceFacts left, InteractionIngress.SourceFacts right) {
        return left != null && right != null && left.player() == right.player() &&
                left.connection() == right.connection() && Objects.equals(left.playerId(), right.playerId()) &&
                Objects.equals(left.playerName(), right.playerName()) && Objects.equals(left.worldId(), right.worldId());
    }

    /** Minimal game-object boundary used by the production adapter and full-lifecycle offline tests. */
    public interface GamePort {
        PlayerView inspect(Object player);
        Object currentPlayer(UUID playerId);
        boolean onlineMode();
        FirstReplyScheduler.PublicationResult submitRendered(Object player, String renderedLine);

        /** Exact ServerWorld identity retained with an admitted request; null means unavailable. */
        default Object worldIdentity(Object player) { return null; }

        /** Direct, bounded ability facts for a roster-resolved player; no name/selector resolution is allowed. */
        default MinecraftAbilityFacts.PlayerState inspectAbilityPlayer(Object player) { return null; }

        default InteractionIngress.SourceFacts sourceFacts(Object player) {
            PlayerView view = inspect(player);
            if (view == null || !view.validForConversation()) return InteractionIngress.SourceFacts.nonPlayer();
            return new InteractionIngress.SourceFacts(true, true, true, view.playerId(), view.playerName(),
                    view.worldId(), player, view.connection());
        }
    }

    public record PlayerView(Object player, Object connection, UUID playerId, String playerName, String worldId,
                             boolean onlineMode, boolean currentPlayer, boolean currentConnection,
                             boolean connectionOpen, boolean alive, int blockX, int blockY, int blockZ) {
        public boolean validForConversation() {
            return player != null && connection != null && playerId != null && playerName != null &&
                    !playerName.isBlank() && worldId != null && !worldId.isBlank() && onlineMode &&
                    currentPlayer && currentConnection && connectionOpen && alive;
        }
    }

    private static final class MinecraftGamePort implements GamePort {
        private final MinecraftServer server;

        private MinecraftGamePort(MinecraftServer server) {
            this.server = server;
        }

        @Override
        public PlayerView inspect(Object candidate) {
            if (!(candidate instanceof ServerPlayerEntity player)) return null;
            UUID id = player.getUuid();
            ServerPlayNetworkHandler connection = player.networkHandler;
            var profile = player.getGameProfile();
            var world = player.getWorld();
            String worldId = world == null ? null : world.getRegistryKey().getValue().toString();
            boolean online = server != null && server.isOnlineMode();
            boolean current = server != null && id != null && player.getServer() == server &&
                    server.getPlayerManager().getPlayer(id) == player;
            return new PlayerView(player, connection, id, profile == null ? null : profile.getName(), worldId,
                    online, current, connection != null && connection.player == player,
                    connection != null && connection.isConnectionOpen(), player.isAlive(),
                    player.getBlockX(), player.getBlockY(), player.getBlockZ());
        }

        @Override
        public Object currentPlayer(UUID playerId) {
            return server == null || playerId == null ? null : server.getPlayerManager().getPlayer(playerId);
        }

        @Override
        public Object worldIdentity(Object candidate) {
            return candidate instanceof ServerPlayerEntity player ? player.getServerWorld() : null;
        }

        @Override
        public MinecraftAbilityFacts.PlayerState inspectAbilityPlayer(Object candidate) {
            if (!(candidate instanceof ServerPlayerEntity player) || server == null) return null;
            try {
                UUID id = player.getUuid();
                var profile = player.getGameProfile();
                UUID profileId = profile == null ? null : profile.getId();
                ServerPlayNetworkHandler connection = player.networkHandler;
                ServerWorld world = player.getServerWorld();
                boolean online = server.isOnlineMode();
                boolean onThisServer = player.getServer() == server;
                boolean handlerMatches = connection != null && connection.player == player;
                boolean connectionOpen = connection != null && connection.isConnectionOpen();
                boolean worldCurrent = world != null && onThisServer && server.getWorld(world.getRegistryKey()) == world;
                String dimension = worldCurrent ? world.getRegistryKey().getValue().toString() : null;
                var position = player.getPos();
                AbilityPolicyResolver.Position feet = position == null ? null :
                        new AbilityPolicyResolver.Position(position.getX(), position.getY(), position.getZ());
                AbilityPolicyResolver.Border border = null;
                Boolean chunkLoadedAndTicking = null;
                if (worldCurrent) {
                    var worldBorder = world.getWorldBorder();
                    if (worldBorder != null) border = new AbilityPolicyResolver.Border(
                            worldBorder.getBoundWest(), worldBorder.getBoundEast(),
                            worldBorder.getBoundNorth(), worldBorder.getBoundSouth());
                    if (position != null && Double.isFinite(position.getX()) && Double.isFinite(position.getY()) &&
                            Double.isFinite(position.getZ())) {
                        var blockPos = net.minecraft.util.math.BlockPos.ofFloored(
                                position.getX(), position.getY(), position.getZ());
                        int chunkX = blockPos.getX() >> 4;
                        int chunkZ = blockPos.getZ() >> 4;
                        ServerChunkManager chunks = world.getChunkManager();
                        boolean loaded = chunks.isChunkLoaded(chunkX, chunkZ);
                        boolean tickingReady = chunks.isTickingFutureReady(
                                net.minecraft.util.math.ChunkPos.toLong(chunkX, chunkZ));
                        boolean entityTicking = world.shouldTickEntity(blockPos);
                        chunkLoadedAndTicking = loaded && tickingReady && entityTicking;
                    }
                }
                long connectionGeneration = connection instanceof ServerConnectionGeneration generation
                        ? generation.worldspeaker$connectionGeneration() : 0;
                return new MinecraftAbilityFacts.PlayerState(player, connection, id, profileId, player.getServer(),
                        world, dimension, online, onThisServer && id != null, handlerMatches, connectionOpen,
                        player.isAlive(), player.isSpectator(), connectionGeneration, feet, border,
                        chunkLoadedAndTicking, worldCurrent);
            } catch (RuntimeException unavailable) {
                return null;
            }
        }

        @Override
        public boolean onlineMode() {
            return server != null && server.isOnlineMode();
        }

        @Override
        public FirstReplyScheduler.PublicationResult submitRendered(Object player, String renderedLine) {
            return player instanceof ServerPlayerEntity serverPlayer
                    ? VanillaDialogue.submitRendered(serverPlayer, renderedLine)
                    : FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
        }
    }

    @Override
    public void close() {
        ThreadBoundary.assertServerThread();
        stopped = true;
        followupWindow.clear();
        for (Request request : requests.liveRequests()) {
            request.retirementReason = "CANCELLED";
            request.retirementCause = "SHUTDOWN";
            request.retirementText = null;
            commitTerminal(request, "CANCELLED", null, firstReplyScheduler.monotonicNanos());
            request.outageRetirement.suppress();
            request.invocation.recordRetirementNotice("SHUTDOWN");
            if (requests.isPending(request)) removePending(request);
            retire(request);
        }
        mailbox.clearAll();
        acknowledgementDeliveryBarrier = null;
        progressDeliveryBarrier = null;
        acknowledgementContextFailureRequest = null;
        unavailableRetirementPending = false;
        admissionClosed = false;
        evidenceAdmissionClosed = false;
        dispositionBackpressure = false;
        unavailableDispositions.clear();
        activeUnavailableDisposition = null;
        byPlayer.clear();
        roundRobin.clear();
        repeatGuard.clear();
        pendingCount = 0;
        if (attention != null) attention.clear();
        if (freshness != null) freshness.clear();
        synchronized (retiredOutputOwners) { retiredOutputOwners.clear(); }
    }

    /** The only production issuer; it is invoked after exact request registration and admission. */
    private final class RequestAbilityIssuer {
        private final AbilityRequestAuthority.Owner owner;

        private RequestAbilityIssuer(AbilityRequestAuthority.Owner owner) {
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        private void register(Request request) {
            if (request == null || !requests.isLive(request)) return;
            request.abilityOwnerRegistered = owner.register(request);
        }

        private void issue(Request request) {
            if (request == null || !request.abilityOwnerRegistered || !requests.isLive(request) ||
                    config == null || companion == null) return;
            long peerGeneration = companion.authenticatedPeerGeneration();
            if (peerGeneration <= 0) return;
            request.abilityPeerGeneration = peerGeneration;
            AbilityValidation.Binding binding = new AbilityValidation.Binding(config.binding().serverId(),
                    config.binding().worldId(), config.binding().characterId(), config.binding().agentId());
            AbilityPolicy policy = config.abilityPolicy();
            String requesterId = request.playerId.toString();
            String configurationHash = config.configSetSha256();
            InvocationCapture packet = request.invocation.captured().packetCapture();
            request.abilityConfigurationSnapshot = config;
            request.abilityPolicySnapshot = policy;
            AbilityRequestAuthority.Admission admission = new AbilityRequestAuthority.Admission(
                    request, request.invocation, config, configurationHash, binding, policy, policy.revision(),
                    request.requestId, request.invocationId, requesterId, requesterId, serverEpochId,
                    serviceGeneration, request.gateGeneration, peerGeneration, request.capturedAtNanos,
                    request.invocation.captured().provenance() == InteractionIngress.CaptureProvenance.LIVE_PACKET,
                    request.invocation.captured().provenance(), packet, request.connection,
                    request.originalPlayer, request.originalWorld);
            AbilityRequestAuthority.Handle handle = owner.issue(request, admission);
            if (handle == null) {
                request.abilityPeerGeneration = -1;
                request.abilityConfigurationSnapshot = null;
                request.abilityPolicySnapshot = null;
                return;
            }
            request.abilityAuthority = handle;
            request.abilityExpected = new AbilityRequestAuthority.ExpectedContext(request, config,
                    configurationHash, binding, request.requestId, request.invocationId, requesterId,
                    requesterId, serverEpochId, serviceGeneration, request.gateGeneration,
                    peerGeneration, request.capturedAtNanos);
        }

        private boolean isCurrent(Request request) {
            return request != null && request.abilityOwnerRegistered && request.abilityAuthority != null &&
                    request.abilityExpected != null &&
                    AbilityRequestAuthority.isCurrent(request.abilityAuthority, request.abilityExpected);
        }

        private int retainedHoldsForTesting() { return owner.retainedHoldsForTesting(); }

        private int releaseRetainedHoldsForTesting() { return owner.releaseRetainedHoldsForTesting(); }

        private AbilityRequestAuthority.OperationProposal propose(Request request, String operationId,
                                                                  AbilityValidation.Input intent) {
            if (request == null || !request.abilityOwnerRegistered || !requests.isLive(request) ||
                    request.abilityAuthority == null || request.abilityExpected == null) return null;
            return owner.propose(request, request.abilityExpected, operationId, intent);
        }

        private int cleanupAbilityLimits() {
            owner.cleanupAbilityLimits();
            return 0;
        }

        private AbilityLimitLedger.StartResult startFirstRetainedForTesting(LongSupplier now) {
            return owner.startFirstRetainedForTesting(now);
        }

        private void retire(Request request) {
            if (request == null) return;
            owner.retire(request);
        }
    }

    private static final class Request {
        private final String requestId;
        private final UUID playerId;
        private final Object connection;
        private final Object originalPlayer;
        private final Object originalWorld;
        private final String playerName;
        private final String text;
        private final long capturedAtNanos;
        private final long firstReplyDueNanos;
        private final long pendingDeadlineNanos;
        private final int executionBudgetSeconds;
        private final long absoluteExecutionDeadlineNanos;
        private final long stableOrder;
        private final SmokeFirstPublicDispatch firstPublicDispatch;
        private final InteractionIngress.Invocation invocation;
        private final ConversationProgress progress;
        private final SmokeOutageRetirement outageRetirement = new SmokeOutageRetirement();
        private final String acknowledgementLine;
        private final String characterDisplayName;
        private final CharacterChatStyle characterChatStyle;
        private final SmokeRequestAcknowledgement acknowledgement = new SmokeRequestAcknowledgement();
        private final long gateGeneration;
        private final long serviceGeneration;
        private final Object serviceEpoch;
        private final String dimension;
        private final FollowupWindow.Candidate openingCandidate;
        private final AttributedEvent event;
        private final PlayerFreshness.Snapshot freshnessSnapshot;
        private final String conversationId;
        private final String replyToMessageId;
        private final String invocationId;
        private long activeDeadlineNanos;
        private boolean firstReplyPublished;
        private boolean firstReplyUnknown;
        private boolean acknowledgementAttempted;
        private boolean safeAlternativeAttempted;
        private boolean safeAlternativePending;
        private String unavailableTerminalId;
        private String retirementText;
        private String retirementReason;
        private String retirementCause;
        private String obsoleteCause;
        private String terminalReason;
        private String terminalCause;
        private long terminalAtNanos = -1;
        private long transportGeneration = -1;
        private long abilityPeerGeneration = -1;
        private boolean abilityOwnerRegistered;
        private Object abilityConfigurationSnapshot;
        private AbilityPolicy abilityPolicySnapshot;
        private AbilityRequestAuthority.Handle abilityAuthority;
        private AbilityRequestAuthority.ExpectedContext abilityExpected;
        private Boolean providerCancel;
        private boolean localCancelAttempted;
        private boolean terminalPublished;
        private boolean obsolete;
        private boolean progressContextFailure;

        private Request(String requestId, UUID playerId, Object connection, Object originalPlayer,
                           Object originalWorld, String playerName, String text,
                           InteractionIngress.Invocation invocation,
                           String acknowledgementLine,
                           String characterDisplayName, CharacterChatStyle characterChatStyle,
                           long gateGeneration, long serviceGeneration, Object serviceEpoch, String dimension,
                          FollowupWindow.Candidate openingCandidate, AttributedEvent event,
                           PlayerFreshness.Snapshot freshnessSnapshot, String conversationId, String replyToMessageId,
                             boolean blockedAtAdmission, long stableOrder, int executionBudgetSeconds) {
            this.requestId = requestId;
            this.invocationId = invocation.invocationId().toString();
            this.playerId = playerId;
            this.connection = connection;
            this.originalPlayer = originalPlayer;
            this.originalWorld = originalWorld;
            this.playerName = playerName;
            this.text = text;
            this.capturedAtNanos = invocation.capturedAtNanos();
            this.executionBudgetSeconds = executionBudgetSeconds;
            this.pendingDeadlineNanos = capturedAtNanos + PENDING_MAX_AGE_NANOS;
            this.absoluteExecutionDeadlineNanos = pendingDeadlineNanos + TimeUnit.SECONDS.toNanos(executionBudgetSeconds);
            this.firstReplyDueNanos = blockedAtAdmission ? capturedAtNanos : firstReplyDeadlineNanos();
            this.stableOrder = stableOrder;
            this.firstPublicDispatch = invocation.firstPublicDispatch();
            this.invocation = invocation;
            this.progress = new ConversationProgress(invocation.capturedAtNanos());
            this.acknowledgementLine = acknowledgementLine;
            this.characterDisplayName = characterDisplayName;
            this.characterChatStyle = Objects.requireNonNull(characterChatStyle, "characterChatStyle");
            this.gateGeneration = gateGeneration;
            this.serviceGeneration = serviceGeneration;
            this.serviceEpoch = serviceEpoch;
            this.dimension = dimension;
            this.openingCandidate = openingCandidate;
            this.event = event;
            this.freshnessSnapshot = freshnessSnapshot;
            this.conversationId = conversationId;
            this.replyToMessageId = replyToMessageId;
        }

        private long firstReplyDeadlineNanos() {
            return capturedAtNanos + FIRST_REPLY_CUTOFF_NANOS;
        }

        private GameLoopMailbox.ActiveRequest mailboxIdentity() {
            return new GameLoopMailbox.ActiveRequest(requestId, invocationId, event.characterId(), playerId.toString(),
                    serviceGeneration, invocation.scheduler(), invocation.firstReplySlot());
        }
    }

    private record ProgressBarrier(Request request, String messageId) {}

    private static String distinctRequestId(UUID invocationId) {
        String requestId;
        do { requestId = UUID.randomUUID().toString(); }
        while (requestId.equals(invocationId.toString()));
        return requestId;
    }

}
