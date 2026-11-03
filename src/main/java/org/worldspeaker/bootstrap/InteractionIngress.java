package org.worldspeaker.bootstrap;

import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.CharacterChatStyle;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.InteractionPolicy;
import org.worldspeaker.runtime.TickMetrics;

import java.util.UUID;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Shared, bounded entry point for explicit commands and the future natural-address seam. */
public final class InteractionIngress {
    public static final int MAX_INPUT_LENGTH = 512;
    private static final int MAX_COMPOSED_NOTICE_CHARS = 2_048;
    private static final String RATE_LIMIT_NOTICE =
            "Your requests are being throttled. No new work was accepted. Please wait before trying again.";

    private InteractionIngress() {}

    public enum Origin {
        COMMAND,
        NATURAL_ADDRESS,
        FOLLOWUP
    }

    public enum Route {
        HELP,
        ASK,
        END,
        INVALID
    }

    public enum Outcome {
        HELP,
        INVALID,
        DENIED,
        UNAUTHENTICATED,
        UNAVAILABLE_BEFORE_BOOTSTRAP,
        RECEIPT_EVIDENCE_MISSING,
        UNAVAILABLE,
        BUSY,
        COALESCED,
        FOLLOWUP_ENDED,
        RATE_LIMITED,
        EXPIRED,
        ADMITTED
    }

    public record Captured(UUID invocationId, UUID eventId, long capturedAtNanos, long occurredAtUnixMillis,
                           Origin origin, String conversationId, String replyToMessageId,
                           InvocationCapture packetCapture, long handlerEnteredAtNanos,
                           CaptureProvenance provenance) {
        public Captured {
            if (invocationId == null || eventId == null || invocationId.equals(eventId) || origin == null || provenance == null ||
                    occurredAtUnixMillis < 0 || occurredAtUnixMillis > 9_007_199_254_740_991L) {
                throw new IllegalArgumentException("captured event identity and time are required");
            }
            if (provenance == CaptureProvenance.LIVE_PACKET && (packetCapture == null ||
                    !packetCapture.invocationId().equals(invocationId) || !packetCapture.eventId().equals(eventId) ||
                    packetCapture.receivedAtNanos() != capturedAtNanos ||
                    packetCapture.receivedAtUnixMillis() != occurredAtUnixMillis)) {
                throw new IllegalArgumentException("live packet capture metadata is inconsistent");
            }
            if (provenance == CaptureProvenance.MISSING_PACKET_RECEIPT && packetCapture != null) {
                throw new IllegalArgumentException("missing receipt cannot contain packet metadata");
            }
            if (origin == Origin.FOLLOWUP) {
                if (conversationId == null || replyToMessageId == null) throw new IllegalArgumentException("follow-up anchor is required");
            } else if (replyToMessageId != null) {
                throw new IllegalArgumentException("only a follow-up may carry a reply anchor");
            }
        }

        /** Compatibility constructor for deterministic offline lifecycle tests; provenance stays synthetic. */
        public Captured(UUID invocationId, UUID eventId, long capturedAtNanos, long occurredAtUnixMillis,
                        Origin origin, String conversationId, String replyToMessageId) {
            this(invocationId, eventId, capturedAtNanos, occurredAtUnixMillis, origin, conversationId,
                    replyToMessageId, syntheticCapture(invocationId, eventId, capturedAtNanos,
                            occurredAtUnixMillis, origin), capturedAtNanos, CaptureProvenance.SYNTHETIC_HARNESS);
        }

        /** Compatibility constructor for deterministic offline lifecycle tests. */
        public Captured(UUID invocationId, long capturedAtNanos, Origin origin) {
            this(invocationId, UUID.randomUUID(), capturedAtNanos, System.currentTimeMillis(), origin,
                    origin == Origin.FOLLOWUP ? UUID.randomUUID().toString() : UUID.randomUUID().toString(),
                    origin == Origin.FOLLOWUP ? UUID.randomUUID().toString() : null);
        }

        /** The clock read is intentionally the first operation in this method. */
        public static Captured capture(LongSupplier clock, Origin origin) {
            long capturedAtNanos = clock.getAsLong();
            return capture(() -> capturedAtNanos, System::currentTimeMillis, origin);
        }

        /** Reads local monotonic t0 first, then the wall clock, before any source/roster lookup. */
        public static Captured capture(LongSupplier monotonicClock, LongSupplier wallClock, Origin origin) {
            long capturedAtNanos = monotonicClock.getAsLong();
            long occurredAtUnixMillis = wallClock.getAsLong();
            UUID invocationId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            InvocationCapture packetCapture = syntheticCapture(invocationId, eventId, capturedAtNanos,
                    occurredAtUnixMillis, origin);
            String conversationId = origin == Origin.FOLLOWUP ? null : UUID.randomUUID().toString();
            return new Captured(invocationId, eventId, capturedAtNanos, occurredAtUnixMillis,
                    origin, conversationId, null, packetCapture, capturedAtNanos, CaptureProvenance.SYNTHETIC_HARNESS);
        }

        public static Captured fromPacket(InvocationCapture packetCapture, Origin origin, long handlerEnteredAtNanos) {
            if (packetCapture == null) return missingReceipt(origin, handlerEnteredAtNanos, System.currentTimeMillis());
            boolean compatibleSource = origin == Origin.COMMAND
                    ? packetCapture.source() == InvocationCapture.Source.UNSIGNED_COMMAND ||
                            packetCapture.source() == InvocationCapture.Source.SIGNED_COMMAND
                    : packetCapture.source() == InvocationCapture.Source.SIGNED_CHAT;
            if (!compatibleSource) {
                packetCapture.releaseHandoff();
                return missingReceipt(origin, handlerEnteredAtNanos, System.currentTimeMillis());
            }
            String conversationId = origin == Origin.FOLLOWUP ? null : UUID.randomUUID().toString();
            return new Captured(packetCapture.invocationId(), packetCapture.eventId(), packetCapture.receivedAtNanos(),
                    packetCapture.receivedAtUnixMillis(), origin, conversationId, null, packetCapture,
                    handlerEnteredAtNanos, CaptureProvenance.LIVE_PACKET);
        }

        public static Captured missingReceipt(Origin origin, long handlerEnteredAtNanos, long handlerWallMillis) {
            return new Captured(UUID.randomUUID(), UUID.randomUUID(), Long.MIN_VALUE,
                    Math.max(0, handlerWallMillis), origin, UUID.randomUUID().toString(), null, null,
                    handlerEnteredAtNanos, CaptureProvenance.MISSING_PACKET_RECEIPT);
        }

        public Captured asFollowup(String conversationId, String replyToMessageId) {
            return new Captured(invocationId, eventId, capturedAtNanos, occurredAtUnixMillis,
                    Origin.FOLLOWUP, conversationId, replyToMessageId, packetCapture,
                    handlerEnteredAtNanos, provenance);
        }

        public Captured withOrigin(Origin next) {
            return new Captured(invocationId, eventId, capturedAtNanos, occurredAtUnixMillis, next,
                    conversationId, next == Origin.FOLLOWUP ? replyToMessageId : null, packetCapture,
                    handlerEnteredAtNanos, provenance);
        }

        public boolean hasReceiptEvidence() {
            return provenance != CaptureProvenance.MISSING_PACKET_RECEIPT;
        }

        public boolean handoffCapacityRejected() {
            return provenance == CaptureProvenance.LIVE_PACKET && packetCapture != null &&
                    packetCapture.handoffCapacityRejected();
        }

        public boolean receiptMatches(Object connection) {
            if (provenance == CaptureProvenance.SYNTHETIC_HARNESS) return true;
            return provenance == CaptureProvenance.LIVE_PACKET && packetCapture != null && connection != null &&
                    connection instanceof ServerConnectionGeneration generation &&
                    packetCapture.belongsTo(connection, generation.worldspeaker$connectionGeneration());
        }

        private static InvocationCapture syntheticCapture(UUID invocationId, UUID eventId, long capturedAtNanos,
                                                          long occurredAtUnixMillis, Origin origin) {
            InvocationCapture.Source source = switch (origin) {
                case COMMAND -> InvocationCapture.Source.UNSIGNED_COMMAND;
                case NATURAL_ADDRESS, FOLLOWUP -> InvocationCapture.Source.SIGNED_CHAT;
            };
            return new InvocationCapture(invocationId, eventId, capturedAtNanos, occurredAtUnixMillis,
                    null, 0, source);
        }
    }

    public enum CaptureProvenance { LIVE_PACKET, SYNTHETIC_HARNESS, MISSING_PACKET_RECEIPT }

    /** Facts resolved from the actual command source; object references are only used on the server thread. */
    public record SourceFacts(
            boolean playerSource,
            boolean onlineMode,
            boolean currentConnection,
            UUID playerId,
            String playerName,
            String worldId,
            Object player,
            Object connection) {

        public static SourceFacts nonPlayer() {
            return new SourceFacts(false, false, false, null, null, null, null, null);
        }

        public boolean authenticatedPlayer() {
            return playerSource && onlineMode && currentConnection && playerId != null &&
                    playerName != null && worldId != null && player != null && connection != null;
        }
    }

    public interface Port<S> {
        SourceFacts resolve(S source);
        /** A source-only adapter cannot prove original issuer provenance; implementations must opt in explicitly. */
        default SourceFacts resolve(S source, String input) { return SourceFacts.nonPlayer(); }
        CompanionConfig config();
        TickMetrics metrics();
        /** One server-epoch limiter shared by command, natural-address, and follow-up ingress. */
        default InvocationRateLimiter rateLimiter() { return null; }
        boolean serviceActive();
        default long monotonicNanos() { return System.nanoTime(); }
        default FirstReplyScheduler firstReplyScheduler() { return FirstReplyScheduler.system(); }
        Outcome submit(SourceFacts source, String text, Invocation invocation);
        /** Invalidates the previous local revision before a new explicit invocation is authorized/admitted. */
        default FollowupWindow.Candidate beginExplicit(SourceFacts source, Captured captured) { return null; }
        /** Invalidates a previous revision when a safely recognized address is malformed. */
        default void invalidateFollowup(SourceFacts source) {}
        /** Called for a candidate that did not become admitted work. */
        default void rejectOpeningCandidate(SourceFacts source, FollowupWindow.Candidate candidate) {}
        /** Local-only reset; implementations must not send a remote request. */
        default void endFollowup(SourceFacts source) {}
        /** Evidence loss and disposition overflow both fail closed at the owning service. */
        default void closeAdmissionForEvidenceLoss() {}
        default void closeAdmissionForDispositionOverflow() {}
        boolean publishPlayer(SourceFacts source, String message, boolean characterMessage, String characterDisplayName);
        boolean publishSource(S source, String message);
        default FirstReplyScheduler.PublicationResult submitPlayer(SourceFacts source, String message,
                                                                     boolean characterMessage, String characterDisplayName) {
            return publishPlayer(source, message, characterMessage, characterDisplayName)
                    ? FirstReplyScheduler.PublicationResult.SUBMITTED
                    : FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
        }
        default FirstReplyScheduler.PublicationResult submitPlayer(SourceFacts source, String message,
                                                                    boolean characterMessage, String characterDisplayName,
                                                                    CharacterChatStyle characterChatStyle) {
            return submitPlayer(source, message, characterMessage, characterDisplayName);
        }
        /** Runs on the shared tick queue and must retain/recheck this exact source connection. */
        default FirstReplyScheduler.PublicationResult submitPlayerDisposition(
                SourceFacts source, String message, boolean characterMessage, String characterDisplayName,
                boolean requireCurrentGrant) {
            return submitPlayer(source, message, characterMessage, characterDisplayName);
        }
        /** Style-aware port; legacy ports retain their explicitly plain behavior. */
        default FirstReplyScheduler.PublicationResult submitPlayerDisposition(
                SourceFacts source, String message, boolean characterMessage, String characterDisplayName,
                CharacterChatStyle characterChatStyle, boolean requireCurrentGrant) {
            return submitPlayerDisposition(source, message, characterMessage, characterDisplayName, requireCurrentGrant);
        }
        default FirstReplyScheduler.PublicationResult submitSource(S source, String message) {
            return publishSource(source, message)
                    ? FirstReplyScheduler.PublicationResult.SUBMITTED
                    : FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
        }
    }

    /** Immutable attribution plus one exactly-once decision and the invocation's first-dispatch tracker. */
    public static final class Invocation {
        private final Captured captured;
        private final SourceFacts source;
        private final ConfiguredBinding binding;
        private final TickMetrics metrics;
        private final SmokeFirstPublicDispatch firstPublicDispatch;
        private final FirstReplyScheduler scheduler;
        private final InvocationRateLimiter rateLimiter;
        private final FirstReplyScheduler.Slot firstReplySlot;
        private final String templateRevision;
        private final String characterDisplayName;
        private final CharacterChatStyle characterChatStyle;
        private TickMetrics.InvocationEvidence evidence;
        private String linkedRequestId;
        private int linkedQueueDepth;
        private int linkedPlayerQueueDepth;
        private long linkedAtNanos = -1;
        private boolean linkedRequestQueued;
        private Integer executionBudgetSeconds;
        private Long pendingDeadlineAtNanos;
        private Long absoluteExecutionDeadlineAtNanos;
        private boolean decisionRecorded;
        private Outcome outcome;
        private FollowupWindow.Candidate followupCandidate;
        private InvocationRateLimiter.Charge invocationCharge = InvocationRateLimiter.Charge.ALLOWED;
        private boolean invocationChargeChecked;
        private boolean newWorkRateLimited;

        private Invocation(Captured captured, SourceFacts source, ConfiguredBinding binding, TickMetrics metrics,
                           FirstReplyScheduler scheduler, String templateRevision,
                           String characterDisplayName, CharacterChatStyle characterChatStyle,
                           InvocationRateLimiter rateLimiter) {
            this.captured = captured;
            this.source = source;
            this.binding = binding;
            this.metrics = metrics;
            this.firstPublicDispatch = new SmokeFirstPublicDispatch(captured.capturedAtNanos(), this);
            this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
            this.rateLimiter = rateLimiter;
            this.firstReplySlot = scheduler.createSlot(captured.capturedAtNanos(), captured.provenance().name());
            this.templateRevision = templateRevision;
            this.characterDisplayName = characterDisplayName;
            this.characterChatStyle = characterChatStyle == null ? CharacterChatStyle.PLAIN : characterChatStyle;
            if (metrics != null) {
                evidence = metrics.reserveInteractionInvocation(invocationId(), capturedAtNanos(),
                        captured.occurredAtUnixMillis(), captured.handlerEnteredAtNanos(),
                        captured.provenance().name(),
                        captured.packetCapture() == null ? null : captured.packetCapture().originalConnection(),
                        captured.packetCapture() == null ? 0 : captured.packetCapture().connectionGeneration(),
                        source.playerId(), source.worldId(), binding, origin().name(), firstReplySlot,
                        scheduler.monotonicNanos(), templateRevision,
                        captured.packetCapture() == null ? captured.provenance().name() :
                                captured.packetCapture().source().name());
            }
        }

        public UUID invocationId() { return captured.invocationId(); }
        public Captured captured() { return captured; }
        public long capturedAtNanos() { return captured.capturedAtNanos(); }
        public Origin origin() { return captured.origin(); }
        public SourceFacts source() { return source; }
        public ConfiguredBinding binding() { return binding; }
        public String characterDisplayName() { return characterDisplayName; }
        public CharacterChatStyle characterChatStyle() { return characterChatStyle; }
        public SmokeFirstPublicDispatch firstPublicDispatch() { return firstPublicDispatch; }
        public FirstReplyScheduler scheduler() { return scheduler; }
        public FirstReplyScheduler.Slot firstReplySlot() { return firstReplySlot; }
        public FollowupWindow.Candidate followupCandidate() { return followupCandidate; }
        public boolean hasEvidenceReservation() { return metrics == null || evidence != null; }
        String linkedRequestIdForTesting() { return linkedRequestId; }
        boolean hasCurrentEvidenceReservation() {
            return metrics == null || evidence != null && metrics.hasCurrentInteractionReservation(evidence);
        }
        boolean hasCurrentEvidenceReservation(TickMetrics expectedMetrics) {
            return metrics == expectedMetrics && (expectedMetrics == null || evidence != null &&
                    expectedMetrics.hasCurrentInteractionReservation(evidence));
        }
        boolean commitEvidenceAdmission(TickMetrics expectedMetrics) {
            return metrics == expectedMetrics && (expectedMetrics == null || evidence != null &&
                    expectedMetrics.commitInteractionAdmission(evidence));
        }
        boolean admissionWasCommitted(TickMetrics expectedMetrics) {
            return metrics == expectedMetrics && (expectedMetrics == null || evidence != null &&
                    expectedMetrics.isInteractionAdmissionCommitted(evidence));
        }
        boolean hasRequestLink() { return linkedRequestId != null; }
        void recordEvidenceUnavailable() {
            if (metrics != null) metrics.recordRateLimitStatus(evidence, "EVIDENCE_UNAVAILABLE");
        }
        public boolean invocationRateLimited() {
            return invocationChargeChecked && invocationCharge != InvocationRateLimiter.Charge.ALLOWED;
        }

        boolean invocationWasThrottled() {
            return invocationChargeChecked && invocationCharge == InvocationRateLimiter.Charge.LIMITED;
        }

        public InvocationRateLimiter.Charge chargeInvocation() {
            if (invocationChargeChecked) return invocationCharge;
            invocationChargeChecked = true;
            invocationCharge = rateLimiter == null ? InvocationRateLimiter.Charge.ALLOWED :
                    rateLimiter.chargeInvocation(source.playerId(), scheduler.monotonicNanos());
            String status = switch (invocationCharge) {
                case ALLOWED -> "ALLOWED";
                case LIMITED -> "INVOCATION_LIMITED";
                case SUBJECT_CAPACITY -> "SUBJECT_CAPACITY";
                case EVIDENCE_UNAVAILABLE -> "EVIDENCE_UNAVAILABLE";
            };
            if (metrics != null) {
                metrics.recordRateLimitStatus(evidence, status);
                if (invocationCharge == InvocationRateLimiter.Charge.LIMITED) {
                    metrics.increment(TickMetrics.Counter.INVOCATION_RATE_LIMITED);
                } else if (invocationCharge == InvocationRateLimiter.Charge.SUBJECT_CAPACITY) {
                    metrics.increment(TickMetrics.Counter.RATE_LIMIT_SUBJECT_OVERFLOWS);
                }
            }
            return invocationCharge;
        }

        InvocationRateLimiter.Charge commitNewWork(TickMetrics expectedMetrics, Runnable commitAdmissionState) {
            InvocationRateLimiter.Charge result;
            if (rateLimiter == null) {
                result = commitEvidenceAdmission(expectedMetrics)
                        ? InvocationRateLimiter.Charge.ALLOWED : InvocationRateLimiter.Charge.EVIDENCE_UNAVAILABLE;
                if (result == InvocationRateLimiter.Charge.ALLOWED) commitAdmissionState.run();
            } else {
                result = rateLimiter.commitNewWork(source.playerId(), scheduler.monotonicNanos(),
                        () -> commitEvidenceAdmission(expectedMetrics), commitAdmissionState);
            }
            if (metrics != null && result != InvocationRateLimiter.Charge.ALLOWED) {
                newWorkRateLimited = result == InvocationRateLimiter.Charge.LIMITED ||
                        result == InvocationRateLimiter.Charge.SUBJECT_CAPACITY;
                metrics.recordRateLimitStatus(evidence, switch (result) {
                    case LIMITED -> "NEW_WORK_LIMITED";
                    case SUBJECT_CAPACITY -> "SUBJECT_CAPACITY";
                    case EVIDENCE_UNAVAILABLE -> "EVIDENCE_UNAVAILABLE";
                    case ALLOWED -> "ALLOWED";
                });
                if (result == InvocationRateLimiter.Charge.LIMITED) {
                    metrics.increment(TickMetrics.Counter.NEW_WORK_RATE_LIMITED);
                } else if (result == InvocationRateLimiter.Charge.SUBJECT_CAPACITY) {
                    metrics.increment(TickMetrics.Counter.RATE_LIMIT_SUBJECT_OVERFLOWS);
                }
            }
            return result;
        }

        void recordRepeatGuardCause(boolean capacity) {
            if (metrics != null) metrics.recordRateLimitStatus(evidence,
                    capacity ? "REPEAT_GUARD_CAPACITY" : "REPEAT_GUARD");
        }

        InvocationRateLimiter.NoticeBatchReservation reserveNoticeBatch(Object compatibilityKey,
                                                                         long originalDeadlineNanos,
                                                                         InvocationRateLimiter.NoticeCause cause,
                                                                         boolean requireCurrentGrant,
                                                                         boolean invocationWasThrottled) {
            if (rateLimiter == null || !source.authenticatedPlayer()) {
                return null;
            }
            return rateLimiter.reserveNoticeBatch(source.playerId(), scheduler.monotonicNanos(),
                    compatibilityKey, originalDeadlineNanos, cause, requireCurrentGrant, invocationWasThrottled);
        }

        boolean linkNoticePublication(InvocationRateLimiter.NoticeBatch batch,
                                       TickMetrics.FirstPublicSource source,
                                       TickMetrics.FirstPublicDisposition disposition) {
            if (batch == null || !firstReplySlot.linkNoticePublication(batch.publication())) return false;
            firstPublicDispatch.linkNoticePublication(batch.publication());
            if (metrics != null) metrics.recordNoticePublication(evidence, batch.publication(), source, disposition);
            recordNoticeStatus("QUEUED");
            return true;
        }

        boolean noticeLimitEnforced() { return rateLimiter != null && source.authenticatedPlayer(); }

        void recordNoticeStatus(String status) {
            if (metrics != null) metrics.recordNoticeStatus(evidence, status);
        }

        boolean rateLimited() { return invocationRateLimited() || newWorkRateLimited || outcome == Outcome.RATE_LIMITED; }

        private void followupCandidate(FollowupWindow.Candidate candidate) { this.followupCandidate = candidate; }

        private void recordDecision(Outcome decision) {
            if (decisionRecorded) {
                if (outcome != decision) throw new IllegalStateException("invocation disposition changed");
                return;
            }
            outcome = decision;
            decisionRecorded = true;
            if (metrics != null) metrics.recordInteractionDecision(evidence, decision.name(), scheduler.monotonicNanos());
            if (evidence != null && linkedRequestId != null) {
                metrics.recordInteractionRequest(evidence, linkedRequestId, linkedAtNanos,
                        linkedQueueDepth, linkedPlayerQueueDepth, linkedRequestQueued);
            }
            if (evidence != null && executionBudgetSeconds != null) {
                metrics.recordExecutionBudget(evidence, executionBudgetSeconds,
                        pendingDeadlineAtNanos, absoluteExecutionDeadlineAtNanos);
            }
        }

        void recordFirstPublicDispatch(TickMetrics targetMetrics, long dispatchedAtNanos,
                                       TickMetrics.FirstPublicSource dispatchSource,
                                       TickMetrics.FirstPublicDisposition disposition) {
            TickMetrics selectedMetrics = metrics == null ? targetMetrics : metrics;
            if (selectedMetrics == null || !decisionRecorded || !captured.hasReceiptEvidence()) return;
            long latency = Math.max(0, dispatchedAtNanos - capturedAtNanos());
            selectedMetrics.recordInteractionDispatch(evidence, latency, dispatchSource, disposition,
                    dispatchedAtNanos, firstReplySlot.publicationId());
        }

        void recordFailedDispatch(TickMetrics targetMetrics) {
            TickMetrics selectedMetrics = metrics == null ? targetMetrics : metrics;
            if (selectedMetrics != null) selectedMetrics.recordFailedInteractionDispatch(evidence);
        }

        void recordUnknownDispatch(TickMetrics targetMetrics) {
            TickMetrics selectedMetrics = metrics == null ? targetMetrics : metrics;
            if (selectedMetrics != null) selectedMetrics.recordUnknownInteractionDispatch(evidence,
                    firstReplySlot.publicationId());
        }

        void recordRequestLink(String requestId, int queueDepth, boolean queued) {
            recordRequestLink(requestId, queueDepth, 0, queued);
        }

        void recordRequestLink(String requestId, int queueDepth, int playerQueueDepth, boolean queued) {
            TickMetrics selectedMetrics = metrics;
            linkedRequestId = requestId;
            linkedAtNanos = scheduler.monotonicNanos();
            linkedQueueDepth = queueDepth;
            linkedPlayerQueueDepth = playerQueueDepth;
            linkedRequestQueued = queued;
            if (selectedMetrics != null && evidence != null) {
                selectedMetrics.recordInteractionRequest(evidence, linkedRequestId,
                        linkedAtNanos, linkedQueueDepth, linkedPlayerQueueDepth, linkedRequestQueued);
            }
        }

        void recordForwardedAt(long forwardedAtNanos, long pendingResidenceNanos,
                               int globalPending, int playerPending) {
            if (metrics != null && evidence != null) {
                metrics.recordInteractionForwarded(evidence, forwardedAtNanos, pendingResidenceNanos,
                        globalPending, playerPending);
            }
        }

        void recordExecutionBudget(int seconds, long pendingDeadlineAtNanos, long absoluteDeadlineAtNanos) {
            executionBudgetSeconds = seconds;
            this.pendingDeadlineAtNanos = pendingDeadlineAtNanos;
            this.absoluteExecutionDeadlineAtNanos = absoluteDeadlineAtNanos;
            TickMetrics selectedMetrics = metrics;
            if (selectedMetrics != null && evidence != null) selectedMetrics.recordExecutionBudget(evidence, seconds,
                    pendingDeadlineAtNanos, absoluteDeadlineAtNanos);
        }

        void recordActiveExecutionDeadline(long activeDeadlineAtNanos) {
            TickMetrics selectedMetrics = metrics;
            if (selectedMetrics != null) selectedMetrics.recordActiveExecutionDeadline(evidence, activeDeadlineAtNanos);
        }

        void recordProgress(ConversationProgress.Phase phase, String state,
                            FirstReplyScheduler.PublicationResult publicResult,
                            long publicAtNanos, String noticeId) {
            TickMetrics selectedMetrics = metrics;
            if (selectedMetrics != null && phase != null && publicResult != null) {
                selectedMetrics.recordInteractionProgress(evidence,
                        phase == ConversationProgress.Phase.QUEUED ? "queued" : "awaiting_answer",
                        state, publicResult, Math.max(0, publicAtNanos - capturedAtNanos()), publicAtNanos, noticeId);
            }
        }

        void recordProgressMissed(ConversationProgress.Phase phase, String state, long atNanos) {
            TickMetrics selectedMetrics = metrics;
            if (selectedMetrics != null && phase != null) {
                selectedMetrics.recordInteractionProgressMissed(evidence,
                        phase == ConversationProgress.Phase.QUEUED ? "queued" : "awaiting_answer",
                        state, Math.max(0, atNanos - capturedAtNanos()));
            }
        }

        void recordProgressContext(String noticeId, String contextResult, long completedAtNanos) {
            TickMetrics selectedMetrics = metrics;
            if (selectedMetrics != null) {
                selectedMetrics.recordInteractionProgressContext(evidence, noticeId, contextResult, completedAtNanos);
            }
        }

        void recordProgress(ConversationProgress.Phase phase, String status, long atNanos) {
            TickMetrics selectedMetrics = metrics;
            if (selectedMetrics != null && phase != null) {
                selectedMetrics.recordInteractionProgress(evidence,
                        phase == ConversationProgress.Phase.QUEUED ? "queued" : "awaiting_answer",
                        status, Math.max(0, atNanos - capturedAtNanos()));
            }
        }

        void recordTerminal(String outcome, long atNanos) {
            TickMetrics selectedMetrics = metrics;
            if (selectedMetrics != null) {
                selectedMetrics.recordInteractionTerminal(evidence, outcome, "NO_ATTEMPT", atNanos,
                        Math.max(0, atNanos - capturedAtNanos()));
            }
        }

        void recordTerminal(String reason, FirstReplyScheduler.PublicationResult deliveryResult, long atNanos) {
            recordTerminal(reason, null, deliveryResult, atNanos);
        }

        void recordTerminal(String reason, String cause,
                            FirstReplyScheduler.PublicationResult deliveryResult, long atNanos) {
            TickMetrics selectedMetrics = metrics;
            if (selectedMetrics != null) {
                selectedMetrics.recordInteractionTerminal(evidence, reason, cause,
                        deliveryResult == null ? "NO_ATTEMPT" : deliveryResult.name(), atNanos,
                        Math.max(0, atNanos - capturedAtNanos()));
            }
        }

        void recordRetirementNotice(String result) {
            if (metrics != null) metrics.recordInteractionRetirementNotice(evidence, result);
        }

        void recordLocalCancelStatus(String status, boolean providerCancel) {
            if (metrics != null) metrics.recordInteractionLocalCancel(evidence, status, null, providerCancel);
        }

        void recordLocalCancelDelivery(String delivery) {
            if (metrics == null) return;
            String result = "SEND_COMPLETED".equals(delivery) ? "SUBMITTED" : "UNKNOWN";
            metrics.recordInteractionLocalCancel(evidence, result, result, false);
        }

        void recordLateOutputRejected() {
            if (metrics != null) metrics.recordInteractionLateOutputRejected(evidence);
        }
    }

    /** Executes the common lifecycle after the command handler captured its timestamp. */
    public static <S> int execute(Captured captured, S rawSource, String input, Route route, String text, Port<S> port) {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(port, "port");
        if (captured.handoffCapacityRejected()) {
            return refuseCaptureHandoffOverflow(captured, rawSource, input, port);
        }
        if (!captured.hasReceiptEvidence()) {
            CompanionConfig config = port.config();
            TickMetrics metrics = port.metrics();
            Invocation invocation = new Invocation(captured, SourceFacts.nonPlayer(),
                    config == null ? null : config.binding(), metrics, port.firstReplyScheduler(), templateRevision(config),
                    config == null ? null : config.characterDisplayName(),
                    config == null ? CharacterChatStyle.PLAIN : config.characterChatStyle(),
                    port.rateLimiter());
            if (!invocation.hasEvidenceReservation()) port.closeAdmissionForEvidenceLoss();
            invocation.recordDecision(Outcome.RECEIPT_EVIDENCE_MISSING);
            return 0;
        }
        SourceFacts resolvedSource = port.resolve(rawSource, input);
        boolean directCommand = captured.origin() != Origin.COMMAND || isDirectCommandInput(input);
        boolean authenticated = resolvedSource.authenticatedPlayer() && directCommand;
        // Untrusted or redirected source facts may describe a substituted target. Never retain those
        // facts for attribution or use them to select a player-visible recipient.
        SourceFacts source = authenticated ? resolvedSource : SourceFacts.nonPlayer();
        CompanionConfig config = port.config();
        TickMetrics metrics = port.metrics();
        Invocation invocation = new Invocation(captured, source,
                config == null ? null : config.binding(), metrics, port.firstReplyScheduler(), templateRevision(config),
                config == null ? null : config.characterDisplayName(),
                config == null ? CharacterChatStyle.PLAIN : config.characterChatStyle(),
                port.rateLimiter());

        if (!invocation.hasEvidenceReservation() || !invocation.hasCurrentEvidenceReservation()) {
            return refuseForEvidenceUnavailable(captured, rawSource, source, invocation, port);
        }

        if (!authenticated) {
            invocation.recordDecision(Outcome.UNAUTHENTICATED);
            String message = resolvedSource.playerSource()
                    ? "Worldspeaker requires a direct command from this authenticated online-mode player."
                    : "Worldspeaker conversation commands require an authenticated player source.";
            publish(source, rawSource, invocation, port, message, false, null,
                    TickMetrics.FirstPublicSource.LOCAL_UNAUTHENTICATED,
                    TickMetrics.FirstPublicDisposition.UNAUTHENTICATED, false);
            return 0;
        }

        if (!captured.receiptMatches(resolvedSource.connection())) {
            invocation.recordDecision(Outcome.RECEIPT_EVIDENCE_MISSING);
            return 0;
        }

        invocation.chargeInvocation();

        if (route == Route.HELP) {
            invocation.recordDecision(Outcome.HELP);
            return publish(source, rawSource, invocation, port, help(config, port.serviceActive()), false, null,
                    TickMetrics.FirstPublicSource.COMMAND_HELP, TickMetrics.FirstPublicDisposition.HELP, false) ? 1 : 0;
        }

        if (captured.origin() == Origin.COMMAND && route == Route.ASK) {
            invocation.followupCandidate(port.beginExplicit(source, captured));
        } else if (captured.origin() == Origin.NATURAL_ADDRESS && route == Route.ASK) {
            invocation.followupCandidate(port.beginExplicit(source, captured));
        } else if (captured.origin() == Origin.NATURAL_ADDRESS && route == Route.INVALID) {
            port.invalidateFollowup(source);
        }

        if (route == Route.END) {
            if (config == null || !config.interactionPolicy().allows(source.playerId(), InteractionPolicy.CONVERSE_PERMISSION)) {
                invocation.recordDecision(Outcome.DENIED);
                publish(source, rawSource, invocation,
                        port, "Worldspeaker: you are not authorized to converse with this character.", false, null,
                        TickMetrics.FirstPublicSource.LOCAL_DENIED, TickMetrics.FirstPublicDisposition.DENIED, false);
                return 0;
            }
            port.endFollowup(source);
            invocation.recordDecision(Outcome.FOLLOWUP_ENDED);
            boolean queued = publish(source, rawSource, invocation,
                    port, "Follow-up recognition ended locally. This does not clear OpenClaw memory or cancel work already admitted.",
                    false, null, TickMetrics.FirstPublicSource.LOCAL_FOLLOWUP_END,
                    TickMetrics.FirstPublicDisposition.FOLLOWUP_ENDED, true);
            return queued ? 1 : 0;
        }

        boolean naturalInvalid = captured.origin() == Origin.NATURAL_ADDRESS && route == Route.INVALID;
        if (!naturalInvalid && (route != Route.ASK || text == null || text.isBlank() || text.length() > MAX_INPUT_LENGTH)) {
            rejectOpeningCandidate(source, invocation, port);
            invocation.recordDecision(Outcome.INVALID);
            publish(source, rawSource, invocation, port, usage(), false, null,
                    TickMetrics.FirstPublicSource.COMMAND_GUIDANCE, TickMetrics.FirstPublicDisposition.INVALID, false);
            return 0;
        }

        if (config == null || !port.serviceActive()) {
            rejectOpeningCandidate(source, invocation, port);
            Outcome unavailable = config == null ? Outcome.UNAVAILABLE_BEFORE_BOOTSTRAP : Outcome.UNAVAILABLE;
            invocation.recordDecision(unavailable);
            publish(source, rawSource, invocation, port,
                    config == null ? "Worldspeaker is unavailable right now; no validated character configuration is loaded."
                            : "Worldspeaker is unavailable right now.",
                    false, null,
                    TickMetrics.FirstPublicSource.LOCAL_UNAVAILABLE,
                    TickMetrics.FirstPublicDisposition.UNAVAILABLE, false);
            return 0;
        }

        if (!config.interactionPolicy().allows(source.playerId(), InteractionPolicy.CONVERSE_PERMISSION)) {
            rejectOpeningCandidate(source, invocation, port);
            invocation.recordDecision(Outcome.DENIED);
            publish(source, rawSource, invocation, port,
                    "Worldspeaker: you are not authorized to converse with this character.", false, null,
                    TickMetrics.FirstPublicSource.LOCAL_DENIED,
                    TickMetrics.FirstPublicDisposition.DENIED, false);
            return 0;
        }

        if (naturalInvalid) {
            rejectOpeningCandidate(source, invocation, port);
            invocation.recordDecision(Outcome.INVALID);
            publish(source, rawSource, invocation, port,
                    "This addressed message could not be routed safely. Use /worldspeaker ask <text>.",
                    false, null, TickMetrics.FirstPublicSource.NATURAL_INVALID,
                    TickMetrics.FirstPublicDisposition.INVALID, true);
            return 0;
        }

        if (invocation.invocationRateLimited()) {
            rejectOpeningCandidate(source, invocation, port);
            invocation.recordDecision(Outcome.RATE_LIMITED);
            publish(source, rawSource, invocation, port,
                    RATE_LIMIT_NOTICE, false,
                    null, TickMetrics.FirstPublicSource.LOCAL_BUSY,
                    TickMetrics.FirstPublicDisposition.BUSY, true);
            return 0;
        }

        if (!invocation.hasCurrentEvidenceReservation()) {
            return refuseForEvidenceUnavailable(captured, rawSource, source, invocation, port);
        }

        Outcome admission = port.submit(source, text, invocation);
        if (admission == null) admission = Outcome.UNAVAILABLE;
        if ((admission == Outcome.ADMITTED && !invocation.admissionWasCommitted(metrics)) ||
                (admission != Outcome.ADMITTED && !invocation.hasCurrentEvidenceReservation() &&
                        !invocation.admissionWasCommitted(metrics))) {
            return refuseForEvidenceUnavailable(captured, rawSource, source, invocation, port);
        }
        invocation.recordDecision(admission);
        if (admission != Outcome.ADMITTED) rejectOpeningCandidate(source, invocation, port);
        switch (admission) {
            case ADMITTED -> { return 1; }
            case COALESCED -> publish(source, rawSource, invocation, port, "I already have that request.", false,
                    null, TickMetrics.FirstPublicSource.LOCAL_COALESCED,
                    TickMetrics.FirstPublicDisposition.COALESCED, true);
            case BUSY -> publish(source, rawSource, invocation, port, "I can't take another request right now.", false,
                    null, TickMetrics.FirstPublicSource.LOCAL_BUSY,
                    TickMetrics.FirstPublicDisposition.BUSY, true);
            case RATE_LIMITED -> publish(source, rawSource, invocation, port,
                    RATE_LIMIT_NOTICE, false,
                    null, TickMetrics.FirstPublicSource.LOCAL_BUSY,
                    TickMetrics.FirstPublicDisposition.BUSY, true);
            case EXPIRED -> publish(source, rawSource, invocation, port,
                    "That request was already too old to start. Please send it again.", false,
                    null, TickMetrics.FirstPublicSource.LOCAL_SAFE_RESPONSE,
                    TickMetrics.FirstPublicDisposition.FAILED, true);
            case UNAVAILABLE, UNAVAILABLE_BEFORE_BOOTSTRAP -> publish(source, rawSource, invocation, port,
                    "I can't serve that request right now.", false, null,
                    TickMetrics.FirstPublicSource.LOCAL_UNAVAILABLE,
                    TickMetrics.FirstPublicDisposition.UNAVAILABLE, true);
            case DENIED -> publish(source, rawSource, invocation, port,
                    "Worldspeaker: you are not authorized to converse with this character.", false, null,
                    TickMetrics.FirstPublicSource.LOCAL_DENIED,
                    TickMetrics.FirstPublicDisposition.DENIED, false);
            case UNAUTHENTICATED -> publish(source, rawSource, invocation, port,
                    "Worldspeaker requires a direct command from this authenticated online-mode player.", false, null,
                    TickMetrics.FirstPublicSource.LOCAL_UNAUTHENTICATED,
                    TickMetrics.FirstPublicDisposition.UNAUTHENTICATED, false);
            case INVALID, HELP, FOLLOWUP_ENDED -> publish(source, rawSource, invocation, port, usage(), false, null,
                    TickMetrics.FirstPublicSource.COMMAND_GUIDANCE,
                    TickMetrics.FirstPublicDisposition.INVALID, false);
        }
        return 0;
    }

    private static <S> int refuseForEvidenceUnavailable(Captured captured, S rawSource, SourceFacts source,
                                                        Invocation invocation, Port<S> port) {
        invocation.recordEvidenceUnavailable();
        port.closeAdmissionForEvidenceLoss();
        invocation.recordDecision(Outcome.UNAVAILABLE);
        rejectOpeningCandidate(source, invocation, port);
        if (source == null || !source.authenticatedPlayer() ||
                !captured.receiptMatches(source.connection())) return 0;
        publish(source, rawSource, invocation, port,
                "Worldspeaker could not safely accept that request because evidence capture is unavailable. Please try again.",
                false, null, TickMetrics.FirstPublicSource.LOCAL_UNAVAILABLE,
                TickMetrics.FirstPublicDisposition.UNAVAILABLE, false);
        return 0;
    }

    /** Refuses an authentic packet whose immutable receipt could not acquire handoff capacity. */
    private static <S> int refuseCaptureHandoffOverflow(Captured captured, S rawSource, String input, Port<S> port) {
        SourceFacts resolvedSource = port.resolve(rawSource, input);
        boolean directCommand = captured.origin() != Origin.COMMAND || isDirectCommandInput(input);
        boolean authenticated = resolvedSource.authenticatedPlayer() && directCommand;
        SourceFacts source = authenticated ? resolvedSource : SourceFacts.nonPlayer();
        CompanionConfig config = port.config();
        TickMetrics metrics = port.metrics();
        Invocation invocation = new Invocation(captured, source,
                config == null ? null : config.binding(), metrics, port.firstReplyScheduler(), templateRevision(config),
                config == null ? null : config.characterDisplayName(),
                config == null ? CharacterChatStyle.PLAIN : config.characterChatStyle(),
                port.rateLimiter());

        if (!authenticated) {
            invocation.recordDecision(Outcome.UNAUTHENTICATED);
            return 0;
        }
        if (!captured.receiptMatches(resolvedSource.connection())) {
            invocation.recordDecision(Outcome.RECEIPT_EVIDENCE_MISSING);
            return 0;
        }

        invocation.chargeInvocation();
        invocation.recordDecision(Outcome.BUSY);
        publish(source, rawSource, invocation, port,
                "Worldspeaker could not safely accept that message because its packet handoff capacity was full. Please try again.",
                false, null, TickMetrics.FirstPublicSource.LOCAL_BUSY,
                TickMetrics.FirstPublicDisposition.BUSY, false);
        return 0;
    }

    public static boolean isDirectCommandInput(String input) {
        if (input == null || !input.startsWith("worldspeaker")) return false;
        return input.length() == "worldspeaker".length() ||
                Character.isWhitespace(input.charAt("worldspeaker".length()));
    }

    private static String help(CompanionConfig config, boolean serviceActive) {
        if (config == null) {
            return "Worldspeaker: use /worldspeaker ask <text> (up to 512 UTF-16 units); this is not broadcast as chat, " +
                    "but enters shared character context and is not confidential. Replies target only your current connection. " +
                    "Natural-address and follow-up chat is public. " +
                    "The configured character name is unavailable until valid server configuration is loaded.";
        }
        return "Worldspeaker: " + config.characterDisplayName() + " — use /worldspeaker ask <text> " +
                "(up to 512 UTF-16 units); it is not broadcast as chat, but enters shared character context and is not confidential. " +
                "Replies target only your current connection. Natural-address and follow-up chat is public." +
                (serviceActive ? "" : " The conversation service is currently unavailable.");
    }

    private static String usage() {
        return "Usage: /worldspeaker ask <text> (up to 512 UTF-16 units), /worldspeaker end, or /worldspeaker help.";
    }

    private static String templateRevision(CompanionConfig config) {
        if (config == null) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(config.acknowledgementTemplate().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static <S> void rejectOpeningCandidate(SourceFacts source, Invocation invocation, Port<S> port) {
        FollowupWindow.Candidate candidate = invocation.followupCandidate();
        if (candidate != null) port.rejectOpeningCandidate(source, candidate);
    }

    private static <S> boolean publish(SourceFacts source, S rawSource, Invocation invocation, Port<S> port,
                                       String message, boolean characterMessage, String characterDisplayName,
                                       TickMetrics.FirstPublicSource dispatchSource,
                                       TickMetrics.FirstPublicDisposition disposition,
                                       boolean requireCurrentGrant) {
        FirstReplyScheduler scheduler = invocation.scheduler();
        final String publishedMessage = message;
        final boolean publishedAsCharacter = characterMessage;
        final String publishedCharacterName = characterMessage ? invocation.characterDisplayName() : characterDisplayName;
        final CharacterChatStyle publishedCharacterChatStyle = invocation.characterChatStyle();
        final TickMetrics.FirstPublicSource publishedSource = dispatchSource;
        final TickMetrics.FirstPublicDisposition publishedDisposition = disposition;
        final boolean requireGrantAtSend = requireCurrentGrant;
        long originalDeadlineNanos = FirstReplyScheduler.firstReplyDeadlineNanos(invocation.capturedAtNanos());
        if (invocation.noticeLimitEnforced()) {
            NoticeKey key = new NoticeKey(source.player(), source.connection(), scheduler, port.metrics());
            InvocationRateLimiter.NoticeCause cause = noticeCause(publishedMessage, publishedSource);
            InvocationRateLimiter.NoticeBatchReservation reservation =
                    invocation.reserveNoticeBatch(key, originalDeadlineNanos, cause, requireGrantAtSend,
                            invocation.invocationWasThrottled());
            if (reservation == null || reservation.status() == InvocationRateLimiter.NoticeBatchStatus.SUPPRESSED) {
                noticeMissed(scheduler, invocation, port, publishedSource, publishedDisposition,
                        "DEFINITELY_UNSENT", "NOTICE_INCOMPATIBLE_CONTEXT");
                return false;
            }
            if (reservation.status() == InvocationRateLimiter.NoticeBatchStatus.INCOMPATIBLE) {
                noticeMissed(scheduler, invocation, port, publishedSource, publishedDisposition,
                        "DEFINITELY_UNSENT", "NOTICE_INCOMPATIBLE_CONTEXT");
                return false;
            }
            if (reservation.status() == InvocationRateLimiter.NoticeBatchStatus.MISSED) {
                noticeMissed(scheduler, invocation, port, publishedSource, publishedDisposition,
                        "DEFINITELY_UNSENT", "NOTICE_DEADLINE_MISSED");
                return false;
            }
            if (reservation.status() == InvocationRateLimiter.NoticeBatchStatus.CAPACITY) {
                invocation.recordNoticeStatus("OVERFLOW");
                return dispositionOverflow(scheduler, invocation, port, publishedSource, publishedDisposition);
            }

            InvocationRateLimiter.NoticeBatch batch = reservation.batch();
            if (reservation.status() == InvocationRateLimiter.NoticeBatchStatus.CREATED) {
                NoticeDelivery<S> delivery = new NoticeDelivery<>(source, rawSource, invocation, port,
                        publishedMessage, publishedAsCharacter, publishedCharacterName, publishedCharacterChatStyle,
                        publishedSource, publishedDisposition);
                Runnable callback = () -> dispatchNoticeBatch(batch, delivery);
                batch.queuedCallback(callback);
                if (!scheduler.enqueueDispositionAt(batch.notBeforeNanos(), batch.earliestDeadlineNanos(), callback)) {
                    batch.cancelBeforeDispatch();
                    invocation.recordNoticeStatus("OVERFLOW");
                    return dispositionOverflow(scheduler, invocation, port, publishedSource, publishedDisposition);
                }
            } else if (reservation.deadlineMovedEarlier() &&
                    !scheduler.updateDispositionDeadline(batch.queuedCallback(), batch.earliestDeadlineNanos())) {
                batch.releaseUnlinkedMember();
                noticeMissed(scheduler, invocation, port, publishedSource, publishedDisposition,
                        "DEFINITELY_UNSENT", "NOTICE_SCHEDULER_ENTRY_LOST");
                return false;
            }
            if (!invocation.linkNoticePublication(batch, publishedSource, publishedDisposition)) {
                batch.releaseUnlinkedMember();
                if (reservation.status() == InvocationRateLimiter.NoticeBatchStatus.CREATED) {
                    scheduler.cancelDisposition(batch.queuedCallback());
                    batch.cancelBeforeDispatch();
                }
                invocation.recordNoticeStatus("OVERFLOW");
                return dispositionOverflow(scheduler, invocation, port, publishedSource, publishedDisposition);
            }
            return true;
        }

        Runnable callback = () -> dispatchIndividualDisposition(source, rawSource, invocation, port,
                publishedMessage, publishedAsCharacter, publishedCharacterName, publishedCharacterChatStyle,
                publishedSource, publishedDisposition, requireGrantAtSend);
        boolean queued = scheduler.enqueueDisposition(originalDeadlineNanos, callback);
        if (queued) return true;
        return dispositionOverflow(scheduler, invocation, port, publishedSource, publishedDisposition);
    }

    private static <S> void dispatchIndividualDisposition(SourceFacts source, S rawSource, Invocation invocation,
                                                             Port<S> port, String message, boolean characterMessage,
                                                             String characterDisplayName,
                                                             CharacterChatStyle characterChatStyle,
                                                             TickMetrics.FirstPublicSource dispatchSource,
                                                            TickMetrics.FirstPublicDisposition disposition,
                                                            boolean requireCurrentGrant) {
        FirstReplyScheduler scheduler = invocation.scheduler();
        FirstReplyScheduler.Claim claim = scheduler.claimDisposition(invocation.firstReplySlot(), false);
        if (claim != FirstReplyScheduler.Claim.CLAIMED_FIRST) return;
        FirstReplyScheduler.PublicationResult result;
        try {
            result = source.playerSource() && source.player() != null
                    ? port.submitPlayerDisposition(source, message, characterMessage, characterDisplayName,
                            characterChatStyle,
                            requireCurrentGrant)
                    : invocation.captured().provenance() == CaptureProvenance.LIVE_PACKET
                    ? FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT
                    : port.submitSource(rawSource, message);
            if (result == null) result = FirstReplyScheduler.PublicationResult.UNKNOWN;
        } catch (RuntimeException unknownSubmission) {
            result = FirstReplyScheduler.PublicationResult.UNKNOWN;
        }
        long dispatchedAtNanos = scheduler.monotonicNanos();
        scheduler.complete(invocation.firstReplySlot(), claim, result, FirstReplyScheduler.PublicationKind.DISPOSITION);
        invocation.firstPublicDispatch().recordResult(result, port.metrics(), dispatchedAtNanos,
                dispatchSource, disposition);
        if (result == FirstReplyScheduler.PublicationResult.UNKNOWN ||
                result == FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT) {
            invocation.recordTerminal("FAILURE", result, dispatchedAtNanos);
        }
    }

    private static <S> void dispatchNoticeBatch(InvocationRateLimiter.NoticeBatch batch, NoticeDelivery<S> delivery) {
        InvocationRateLimiter.NoticeBatch.DispatchContent content = batch.claimDispatchContent();
        if (content == null) return;
        FirstReplyScheduler.PublicationResult result;
        boolean attempted = false;
        String message = composeNotice(content.causeMask(), delivery.message());
        try {
            if (delivery.source().playerSource() && delivery.source().player() != null) attempted = true;
            else if (delivery.invocation().captured().provenance() != CaptureProvenance.LIVE_PACKET) attempted = true;
            result = delivery.source().playerSource() && delivery.source().player() != null
                    ? delivery.port().submitPlayerDisposition(delivery.source(), message,
                            delivery.characterMessage(), delivery.characterDisplayName(), delivery.characterChatStyle(),
                            content.requireCurrentGrant())
                    : delivery.invocation().captured().provenance() == CaptureProvenance.LIVE_PACKET
                    ? FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT
                    : delivery.port().submitSource(delivery.rawSource(), message);
            if (result == null) result = FirstReplyScheduler.PublicationResult.UNKNOWN;
        } catch (RuntimeException unknownSubmission) {
            result = FirstReplyScheduler.PublicationResult.UNKNOWN;
        }
        long attemptedAtNanos = delivery.invocation().scheduler().monotonicNanos();
        batch.complete(result, attemptedAtNanos, attempted);
        if (result == FirstReplyScheduler.PublicationResult.UNKNOWN ||
                result == FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT) {
            delivery.invocation().recordTerminal("FAILURE", result, attemptedAtNanos);
        }
    }

    private static void noticeMissed(FirstReplyScheduler scheduler, Invocation invocation, Port<?> port,
                                     TickMetrics.FirstPublicSource source,
                                     TickMetrics.FirstPublicDisposition disposition,
                                     String noticeStatus, String terminal) {
        invocation.recordNoticeStatus(noticeStatus);
        scheduler.settleNoDispatch(invocation.firstReplySlot());
        long failedAtNanos = scheduler.monotonicNanos();
        invocation.firstPublicDispatch().recordResult(FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT,
                port.metrics(), failedAtNanos, source, disposition);
        invocation.recordTerminal(terminal, FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT, failedAtNanos);
    }

    private static InvocationRateLimiter.NoticeCause noticeCause(String message,
                                                                  TickMetrics.FirstPublicSource source) {
        return switch (source) {
            case COMMAND_HELP -> InvocationRateLimiter.NoticeCause.HELP;
            case LOCAL_DENIED -> InvocationRateLimiter.NoticeCause.DENIED;
            case LOCAL_UNAVAILABLE -> InvocationRateLimiter.NoticeCause.UNAVAILABLE;
            case LOCAL_BUSY -> RATE_LIMIT_NOTICE.equals(message)
                    ? InvocationRateLimiter.NoticeCause.RATE_LIMITED : InvocationRateLimiter.NoticeCause.BUSY;
            case LOCAL_COALESCED -> InvocationRateLimiter.NoticeCause.COALESCED;
            case COMMAND_GUIDANCE, NATURAL_INVALID -> RATE_LIMIT_NOTICE.equals(message)
                    ? InvocationRateLimiter.NoticeCause.GUIDANCE_RATE_LIMITED
                    : InvocationRateLimiter.NoticeCause.GUIDANCE;
            case LOCAL_FOLLOWUP_END -> InvocationRateLimiter.NoticeCause.FOLLOWUP_ENDED;
            case LOCAL_SAFE_RESPONSE -> InvocationRateLimiter.NoticeCause.EXPIRED;
            case LOCAL_UNAUTHENTICATED -> InvocationRateLimiter.NoticeCause.UNAUTHENTICATED;
            default -> InvocationRateLimiter.NoticeCause.GENERIC;
        };
    }

    /** Composes only a fixed set of safe phrases after the batch claim; work and output have hard bounds. */
    static String composeNotice(long causeMask, String singleCauseMessage) {
        long combinedGuidanceAndRate = 1L << InvocationRateLimiter.NoticeCause.GUIDANCE_RATE_LIMITED.ordinal();
        boolean guidanceAndRateCombined = (causeMask & combinedGuidanceAndRate) != 0;
        if (Long.bitCount(causeMask) <= 1 && (causeMask & combinedGuidanceAndRate) == 0) {
            return boundedNotice(singleCauseMessage);
        }
        StringBuilder message = new StringBuilder(MAX_COMPOSED_NOTICE_CHARS);
        for (InvocationRateLimiter.NoticeCause cause : InvocationRateLimiter.NoticeCause.values()) {
            if ((causeMask & (1L << cause.ordinal())) == 0) continue;
            if (guidanceAndRateCombined && (cause == InvocationRateLimiter.NoticeCause.GUIDANCE ||
                    cause == InvocationRateLimiter.NoticeCause.RATE_LIMITED)) continue;
            String line = switch (cause) {
                case HELP -> "Help: use /worldspeaker ask <text> (up to 512 UTF-16 units), /worldspeaker end, or /worldspeaker help. " +
                        "Requests enter shared character context and are not confidential; natural-address and follow-up chat is public.";
                case DENIED -> "You are not authorized to converse with this character.";
                case UNAVAILABLE -> "The conversation service is unavailable right now; no request was accepted.";
                case RATE_LIMITED -> RATE_LIMIT_NOTICE;
                case BUSY -> "Worldspeaker is busy; no request was accepted. Please try again later.";
                case COALESCED -> "That request is already in progress; no new request was accepted.";
                case GUIDANCE -> "Usage: /worldspeaker ask <text> (up to 512 UTF-16 units), /worldspeaker end, or /worldspeaker help.";
                case GUIDANCE_RATE_LIMITED -> "Usage: /worldspeaker ask <text> (up to 512 UTF-16 units), /worldspeaker end, or /worldspeaker help. " +
                        RATE_LIMIT_NOTICE;
                case FOLLOWUP_ENDED -> "Follow-up recognition ended locally. This does not clear OpenClaw memory or cancel work already admitted.";
                case EXPIRED -> "That request was already too old to start. Please send it again.";
                case UNAUTHENTICATED -> "Worldspeaker requires a direct command from this authenticated online-mode player.";
                case GENERIC -> "Worldspeaker could not complete this request; no acceptance is implied.";
            };
            if (message.length() > 0) message.append(' ');
            int remaining = MAX_COMPOSED_NOTICE_CHARS - message.length();
            if (remaining <= 0) break;
            message.append(line, 0, Math.min(line.length(), remaining));
        }
        return message.length() == 0 ? boundedNotice(singleCauseMessage) : message.toString();
    }

    private static String boundedNotice(String message) {
        if (message == null) return "Worldspeaker could not complete this request; no acceptance is implied.";
        return message.length() <= MAX_COMPOSED_NOTICE_CHARS
                ? message : message.substring(0, MAX_COMPOSED_NOTICE_CHARS);
    }

    private static boolean dispositionOverflow(FirstReplyScheduler scheduler, Invocation invocation, Port<?> port,
                                               TickMetrics.FirstPublicSource source,
                                               TickMetrics.FirstPublicDisposition disposition) {
        TickMetrics metrics = port.metrics();
        if (metrics != null) metrics.increment(TickMetrics.Counter.DISPOSITION_QUEUE_OVERFLOWS);
        port.closeAdmissionForDispositionOverflow();
        scheduler.settleNoDispatch(invocation.firstReplySlot());
        long failedAtNanos = scheduler.monotonicNanos();
        invocation.firstPublicDispatch().recordResult(FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT,
                metrics, failedAtNanos, source, disposition);
        invocation.recordTerminal("DISPOSITION_QUEUE_OVERFLOW",
                FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT, failedAtNanos);
        return false;
    }

    private static final class NoticeKey {
        private final Object player;
        private final Object connection;
        private final FirstReplyScheduler scheduler;
        private final TickMetrics metrics;

        private NoticeKey(Object player, Object connection, FirstReplyScheduler scheduler, TickMetrics metrics) {
            this.player = player;
            this.connection = connection;
            this.scheduler = scheduler;
            this.metrics = metrics;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof NoticeKey key)) return false;
            return player == key.player && connection == key.connection && scheduler == key.scheduler &&
                    metrics == key.metrics;
        }

        @Override public int hashCode() {
            return Objects.hash(System.identityHashCode(player), System.identityHashCode(connection),
                    System.identityHashCode(scheduler), System.identityHashCode(metrics));
        }
    }

    private record NoticeDelivery<S>(SourceFacts source, S rawSource, Invocation invocation, Port<S> port,
                                     String message, boolean characterMessage, String characterDisplayName,
                                     CharacterChatStyle characterChatStyle,
                                     TickMetrics.FirstPublicSource dispatchSource,
                                     TickMetrics.FirstPublicDisposition disposition) {}
}
