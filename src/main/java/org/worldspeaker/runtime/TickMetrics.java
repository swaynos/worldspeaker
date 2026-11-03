package org.worldspeaker.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.attribute.FileAttribute;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import org.worldspeaker.bootstrap.FirstReplyScheduler;
import org.worldspeaker.compat.ConfiguredBinding;

/** Fixed in-memory counters and an opt-in, finite private evidence writer. */
public final class TickMetrics implements AutoCloseable {
    public static final int NUMERIC_FORMAT_VERSION = 5;
    public static final int MAX_BATCH_RECORDS = 4096;
    public static final int MAX_CHUNK_RECORDS = 64;
    public static final int MAX_BATCHES = 2;
    public static final long MAX_RUN_BYTES = 64L * 1024 * 1024;
    public static final int MAX_INTERACTION_RECORDS = 4096;
    private static final long MAX_SNAPSHOT_BYTES = MAX_BATCH_RECORDS * 2048L;
    private static final int COUNTER_COUNT = Counter.values().length;
    private static final long[] HISTOGRAM_UPPER_NANOS = {
            250_000, 500_000, 1_000_000, 2_000_000, 5_000_000,
            10_000_000, 20_000_000, 50_000_000, 100_000_000, Long.MAX_VALUE
    };
    private static final long[] FIRST_PUBLIC_DISPATCH_UPPER_NANOS = {
            250_000_000, 500_000_000, 750_000_000, 1_000_000_000, 2_000_000_000,
            5_000_000_000L, 10_000_000_000L, 15_000_000_000L, 30_000_000_000L, Long.MAX_VALUE
    };
    private static final FirstPublicSource[] FIRST_PUBLIC_SOURCES = FirstPublicSource.values();
    private static final FirstPublicDisposition[] FIRST_PUBLIC_DISPOSITIONS = FirstPublicDisposition.values();

    private final AtomicLongArray counters = new AtomicLongArray(COUNTER_COUNT);
    private final AtomicLongArray tickHistogram = new AtomicLongArray(HISTOGRAM_UPPER_NANOS.length);
    private final java.util.concurrent.atomic.AtomicLong maxFirstPublicDispatchNanos = new java.util.concurrent.atomic.AtomicLong();
    private final AtomicLongArray[][] firstPublicDispatchHistograms = new AtomicLongArray[FIRST_PUBLIC_SOURCES.length][FIRST_PUBLIC_DISPOSITIONS.length];
    private final InteractionRecord[] interactionRecords = new InteractionRecord[MAX_INTERACTION_RECORDS];
    private final AtomicReference<EvidenceAuthority> evidenceAuthority =
            new AtomicReference<>(new EvidenceAuthority(0, 0, 0, false));
    private final AtomicInteger interactionSettlementCursor = new AtomicInteger();
    private final AtomicInteger maxPendingGlobal = new AtomicInteger();
    private final AtomicInteger maxPendingPerPlayer = new AtomicInteger();
    private final AtomicInteger maxPendingRecordsPerScan = new AtomicInteger();
    private final AtomicInteger maxPlayerQueuesPerScan = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong pendingQueueScans = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong pendingQueueRecordSamples = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong maxPendingAgeNanos = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong maxPendingCleanupDelayNanos = new java.util.concurrent.atomic.AtomicLong();
    private final ArrayBlockingQueue<byte[]> batches = new ArrayBlockingQueue<>(MAX_BATCHES);
    private final AtomicBoolean evidenceIncomplete = new AtomicBoolean();
    private final AtomicBoolean persistenceFailed = new AtomicBoolean();
    private final AtomicInteger acceptedBatches = new AtomicInteger();
    private final BoundedIoLane writerLane = new BoundedIoLane("worldspeaker-metrics-io", MAX_BATCHES);
    private volatile long persistedBytes;
    private volatile int persistedBatches;
    private Path persistedPath;

    public TickMetrics() {
        for (int source = 0; source < FIRST_PUBLIC_SOURCES.length; source++) {
            for (int disposition = 0; disposition < FIRST_PUBLIC_DISPOSITIONS.length; disposition++) {
                firstPublicDispatchHistograms[source][disposition] =
                        new AtomicLongArray(FIRST_PUBLIC_DISPATCH_UPPER_NANOS.length);
            }
        }
    }

    public void increment(Counter counter) {
        incrementSaturated(counters, counter.ordinal());
    }

    public void recordTickNanos(long durationNanos) {
        long value = Math.max(0, durationNanos);
        for (int index = 0; index < HISTOGRAM_UPPER_NANOS.length; index++) {
            if (value <= HISTOGRAM_UPPER_NANOS[index]) {
                incrementSaturated(tickHistogram, index);
                return;
            }
        }
    }

    /** Records capture-to-successful-public-dispatch latency for one invocation and its first public output. */
    public void recordFirstPublicDispatchNanos(long durationNanos, FirstPublicSource source,
                                              FirstPublicDisposition disposition) {
        if (source == null || disposition == null) throw new IllegalArgumentException("public dispatch labels are required");
        long value = Math.max(0, durationNanos);
        recordFirstPublicDispatchNanos(value, source, disposition,
                value > TimeUnit.SECONDS.toNanos(1));
    }

    private void recordFirstPublicDispatchNanos(long value, FirstPublicSource source,
                                                FirstPublicDisposition disposition,
                                                boolean countDeadlineMiss) {
        maxFirstPublicDispatchNanos.accumulateAndGet(value, Math::max);
        AtomicLongArray histogram = firstPublicDispatchHistograms[source.ordinal()][disposition.ordinal()];
        for (int index = 0; index < FIRST_PUBLIC_DISPATCH_UPPER_NANOS.length; index++) {
            if (value <= FIRST_PUBLIC_DISPATCH_UPPER_NANOS[index]) {
                incrementSaturated(histogram, index);
                break;
            }
        }
        if (value > TimeUnit.SECONDS.toNanos(1)) {
            increment(Counter.FIRST_REPLY_LATE);
            if (countDeadlineMiss) increment(Counter.FIRST_REPLY_DEADLINE_MISSES);
        } else {
            increment(Counter.FIRST_REPLY_ON_TIME);
        }
    }

    public static long[] firstPublicDispatchUpperNanos() {
        return FIRST_PUBLIC_DISPATCH_UPPER_NANOS.clone();
    }

    public static String[] firstPublicDispatchSourceNames() {
        return java.util.Arrays.stream(FIRST_PUBLIC_SOURCES).map(Enum::name).toArray(String[]::new);
    }

    public static String[] firstPublicDispatchDispositionNames() {
        return java.util.Arrays.stream(FIRST_PUBLIC_DISPOSITIONS).map(Enum::name).toArray(String[]::new);
    }

    public long maxFirstPublicDispatchNanos() {
        materializeAllNoticePublications();
        return maxFirstPublicDispatchNanos.get();
    }

    /** One bounded body-free record is reserved per invocation, including coalesced and refused work. */
    public InvocationEvidence recordInteractionInvocation(UUID invocationId, long capturedAtNanos,
                                                           UUID playerId, String actualWorldId,
                                                           ConfiguredBinding binding, String origin, String outcome) {
        return recordInteractionInvocation(invocationId, capturedAtNanos, 0, capturedAtNanos,
                "SYNTHETIC_HARNESS", null, 0, playerId, actualWorldId, binding, origin, outcome);
    }

    public InvocationEvidence recordInteractionInvocation(UUID invocationId, long capturedAtNanos,
                                                           long capturedAtUnixMillis, long handlerEnteredAtNanos,
                                                           String captureProvenance, Object originalConnection,
                                                           long connectionGeneration, UUID playerId, String actualWorldId,
                                                           ConfiguredBinding binding, String origin, String outcome) {
        return recordInteractionInvocation(invocationId, capturedAtNanos, capturedAtUnixMillis,
                handlerEnteredAtNanos, captureProvenance, originalConnection, connectionGeneration,
                playerId, actualWorldId, binding, origin, outcome, null, handlerEnteredAtNanos, null);
    }

    public InvocationEvidence recordInteractionInvocation(UUID invocationId, long capturedAtNanos,
                                                           long capturedAtUnixMillis, long handlerEnteredAtNanos,
                                                           String captureProvenance, Object originalConnection,
                                                           long connectionGeneration, UUID playerId, String actualWorldId,
                                                           ConfiguredBinding binding, String origin, String outcome,
                                                           FirstReplyScheduler.Slot firstReplySlot,
                                                           long admittedAtNanos, String templateRevision) {
        return recordInteractionInvocation(invocationId, capturedAtNanos, capturedAtUnixMillis,
                handlerEnteredAtNanos, captureProvenance, originalConnection, connectionGeneration,
                playerId, actualWorldId, binding, origin, outcome, firstReplySlot, admittedAtNanos,
                templateRevision, captureProvenance);
    }

    public InvocationEvidence recordInteractionInvocation(UUID invocationId, long capturedAtNanos,
                                                           long capturedAtUnixMillis, long handlerEnteredAtNanos,
                                                           String captureProvenance, Object originalConnection,
                                                           long connectionGeneration, UUID playerId, String actualWorldId,
                                                           ConfiguredBinding binding, String origin, String outcome,
                                                           FirstReplyScheduler.Slot firstReplySlot,
                                                           long admittedAtNanos, String templateRevision, String producer) {
        InvocationEvidence evidence = reserveInteractionInvocation(invocationId, capturedAtNanos,
                capturedAtUnixMillis, handlerEnteredAtNanos, captureProvenance, originalConnection,
                connectionGeneration, playerId, actualWorldId, binding, origin, firstReplySlot,
                admittedAtNanos, templateRevision, producer);
        recordInteractionDecision(evidence, outcome, admittedAtNanos);
        return evidence;
    }

    /** Reserves the bounded body-free row before admission can commit. */
    public InvocationEvidence reserveInteractionInvocation(UUID invocationId, long capturedAtNanos,
                                                            long capturedAtUnixMillis, long handlerEnteredAtNanos,
                                                            String captureProvenance, Object originalConnection,
                                                            long connectionGeneration, UUID playerId, String actualWorldId,
                                                            ConfiguredBinding binding, String origin,
                                                            FirstReplyScheduler.Slot firstReplySlot,
                                                            long admittedAtNanos, String templateRevision,
                                                            String producer) {
        if (!boundedInvocationFields(invocationId, actualWorldId, binding, captureProvenance,
                origin, templateRevision, producer)) {
            recordDroppedInvocation();
            return null;
        }
        EvidenceReservation reservation = reserveEvidenceRow();
        if (reservation == null) {
            recordDroppedInvocation();
            return null;
        }
        InteractionRecord record = new InteractionRecord(invocationId, capturedAtNanos, capturedAtUnixMillis,
                handlerEnteredAtNanos, captureProvenance, connectionGeneration, playerId,
                actualWorldId, binding, origin, firstReplySlot, admittedAtNanos, templateRevision, producer,
                reservation.authorityVersion());
        interactionRecords[reservation.index()] = record;
        // Publish the source count only after the bounded row itself is visible to snapshots.
        increment(Counter.INVOCATIONS_CAPTURED);
        return new InvocationEvidence(record);
    }

    /** Records the immutable admission decision after its row was already reserved. */
    public void recordInteractionDecision(InvocationEvidence evidence, String outcome, long decisionAtNanos) {
        incrementOutcome(outcome);
        if (evidence != null) evidence.record.recordDecision(outcome, decisionAtNanos);
    }

    public void recordRateLimitStatus(InvocationEvidence evidence, String status) {
        if (status == null || !Set.of("NOT_CHECKED", "ALLOWED", "INVOCATION_LIMITED", "SUBJECT_CAPACITY",
                "NEW_WORK_LIMITED", "EVIDENCE_UNAVAILABLE", "REPEAT_GUARD", "REPEAT_GUARD_CAPACITY").contains(status)) {
            throw new IllegalArgumentException("rate-limit status is invalid");
        }
        if (evidence != null) evidence.record.recordRateLimitStatus(status);
    }

    public void recordNoticeStatus(InvocationEvidence evidence, String status) {
        if (status == null || !Set.of("NOT_REQUIRED", "QUEUED", "SUPPRESSED", "SUBMITTED",
                "DEFINITELY_UNSENT", "UNKNOWN", "OVERFLOW", "CANCELLED").contains(status)) {
            throw new IllegalArgumentException("notice status is invalid");
        }
        if (evidence != null) evidence.record.recordNoticeStatus(status);
    }

    /** Links one invocation row to a shared future notice publication in constant time. */
    public void recordNoticePublication(InvocationEvidence evidence,
                                        FirstReplyScheduler.NoticePublication publication,
                                        FirstPublicSource source, FirstPublicDisposition disposition) {
        if (publication == null || source == null || disposition == null) {
            throw new IllegalArgumentException("notice publication attribution is required");
        }
        if (evidence != null) evidence.record.linkNoticePublication(publication, source, disposition);
    }

    /** True only while another invocation row can still be reserved without loss. */
    public boolean canReserveInteraction() {
        EvidenceAuthority authority = evidenceAuthority.get();
        return !authority.unavailable() && authority.reservedRows() < MAX_INTERACTION_RECORDS;
    }

    public int reservedInteractionCount() {
        return evidenceAuthority.get().reservedRows();
    }

    /** Current-row check for the direct submit boundary; no new work is committed here. */
    public boolean hasCurrentInteractionReservation(UUID invocationId) {
        if (invocationId == null) return false;
        EvidenceAuthority authority = evidenceAuthority.get();
        if (authority.unavailable()) return false;
        InteractionRecord record = findInteractionRecord(invocationId, authority.reservedRows());
        return record != null && record.authorityVersion == authority.version() &&
                containsInteractionRecord(record, authority.reservedRows());
    }

    /** Identity-checked counterpart used by an invocation so another owner/version cannot be consumed. */
    public boolean hasCurrentInteractionReservation(InvocationEvidence evidence) {
        if (evidence == null) return false;
        EvidenceAuthority authority = evidenceAuthority.get();
        return !authority.unavailable() && evidence.record.authorityVersion == authority.version() &&
                containsInteractionRecord(evidence.record, authority.reservedRows());
    }

    /**
     * Linearizes a new request admission against asynchronous evidence-writer failure.
     * The successful CAS is the admission commit point; an already committed request
     * remains valid if the writer fails afterwards.
     */
    /** Commits only the exact row token owned by this metrics instance. */
    public boolean commitInteractionAdmission(InvocationEvidence evidence) {
        if (evidence == null) return false;
        EvidenceAuthority initial = evidenceAuthority.get();
        if (initial.unavailable() || !containsInteractionRecord(evidence.record, initial.reservedRows())) return false;
        return commitInteractionAdmission(evidence.record, initial.version());
    }

    /** Already-committed work remains accepted if evidence fails afterwards. */
    public boolean isInteractionAdmissionCommitted(InvocationEvidence evidence) {
        if (evidence == null) return false;
        EvidenceAuthority authority = evidenceAuthority.get();
        return containsInteractionRecord(evidence.record, authority.reservedRows()) &&
                evidence.record.admissionCommitState.get() == 2;
    }

    private boolean commitInteractionAdmission(InteractionRecord record, long expectedVersion) {
        if (record.authorityVersion != expectedVersion || !record.admissionCommitState.compareAndSet(0, 1)) {
            return false;
        }
        boolean committed = false;
        try {
            while (true) {
                EvidenceAuthority current = evidenceAuthority.get();
                if (current.unavailable() || current.version() != expectedVersion) return false;
                EvidenceAuthority next = new EvidenceAuthority(current.version(), current.reservedRows(),
                        current.committedAdmissions() + 1, false);
                if (evidenceAuthority.compareAndSet(current, next)) {
                    committed = true;
                    return true;
                }
            }
        } finally {
            record.admissionCommitState.set(committed ? 2 : 0);
        }
    }

    public void recordInteractionDispatch(InvocationEvidence evidence, long latencyNanos,
                                          FirstPublicSource source, FirstPublicDisposition disposition) {
        recordInteractionDispatch(evidence, latencyNanos, source, disposition, (Long) null, null);
    }

    public void recordInteractionDispatch(InvocationEvidence evidence, long latencyNanos,
                                          FirstPublicSource source, FirstPublicDisposition disposition,
                                          long dispatchedAtNanos, String publicationId) {
        recordInteractionDispatch(evidence, latencyNanos, source, disposition,
                Long.valueOf(dispatchedAtNanos), publicationId);
    }

    private void recordInteractionDispatch(InvocationEvidence evidence, long latencyNanos,
                                           FirstPublicSource source, FirstPublicDisposition disposition,
                                           Long dispatchedAtNanos, String publicationId) {
        if (source == null || disposition == null) throw new IllegalArgumentException("public dispatch labels are required");
        long value = Math.max(0, latencyNanos);
        if (evidence == null) {
            recordFirstPublicDispatchNanos(value, source, disposition);
            return;
        }
        FirstDispatchMaterialization recorded = evidence.record.recordFirstDispatch(value, source, disposition,
                dispatchedAtNanos, publicationId);
        if (recorded == null) return;
        recordFirstPublicDispatchNanos(value, source, disposition, recorded.countDeadlineMiss());
    }

    /** Marks a due invocation with no successful first connection submission exactly once. */
    public void recordNoFirstDispatch(InvocationEvidence evidence, long settledAtNanos, boolean deadline) {
        if (evidence == null) return;
        NoDispatchMaterialization recorded = evidence.record.recordNoFirstDispatch(settledAtNanos, deadline);
        if (recorded == null) return;
        increment(Counter.FIRST_REPLY_NO_DISPATCH);
        if (recorded.deadlineMiss()) {
            if (recorded.countDeadlineMiss()) increment(Counter.FIRST_REPLY_DEADLINE_MISSES);
        } else {
            increment(Counter.FIRST_REPLY_NO_DISPATCH_SETTLED);
        }
    }

    /** Rotating 17-record evidence settlement; callers charge this to their shared tick start budget. */
    public int settleFirstReplyDeadlines(long nowNanos) {
        int count = reservedInteractionCount();
        if (count == 0) return 0;
        int start = Math.floorMod(interactionSettlementCursor.getAndUpdate(current -> current + 17), count);
        int visited = Math.min(17, count);
        for (int offset = 0; offset < visited; offset++) {
            InteractionRecord record = interactionRecords[(start + offset) % count];
            if (record == null) continue;
            materializeNoticePublication(record);
            if (record.captureMissing() || !record.deadlineDue(nowNanos)) continue;
            FirstReplyScheduler.Slot slot = record.firstReplySlot;
            if (slot != null && !slot.hasFirstPublication() && !slot.settledNoDispatch()) {
                FirstReplyScheduler.system().settleNoDispatch(slot);
            }
            recordNoFirstDispatch(new InvocationEvidence(record), nowNanos, true);
        }
        return visited;
    }

    public void recordFailedInteractionDispatch(InvocationEvidence evidence) {
        increment(Counter.FAILED_PUBLIC_DISPATCHES);
        if (evidence != null) evidence.record.recordSendResult("DEFINITELY_UNSENT", false);
    }

    public void recordUnknownInteractionDispatch(InvocationEvidence evidence) {
        recordUnknownInteractionDispatch(evidence, null);
    }

    public void recordUnknownInteractionDispatch(InvocationEvidence evidence, String publicationId) {
        increment(Counter.UNKNOWN_PUBLIC_DISPATCHES);
        if (evidence != null) evidence.record.recordSendResult("UNKNOWN", true, publicationId);
    }

    public void recordInteractionRequest(InvocationEvidence evidence, String requestId) {
        recordInteractionRequest(evidence, requestId, System.nanoTime());
    }

    public void recordInteractionRequest(InvocationEvidence evidence, String requestId, long queuedAtNanos) {
        recordInteractionRequest(evidence, requestId, queuedAtNanos, 0);
    }

    public void recordInteractionRequest(InvocationEvidence evidence, String requestId, long queuedAtNanos,
                                         int queueDepth) {
        recordInteractionRequest(evidence, requestId, queuedAtNanos, queueDepth, true);
    }

    /** Records an admitted or coalesced link without labeling a coalesced repeat as queued work. */
    public void recordInteractionRequest(InvocationEvidence evidence, String requestId, long linkedAtNanos,
                                         int queueDepth, boolean queued) {
        recordInteractionRequest(evidence, requestId, linkedAtNanos, queueDepth, 0, queued);
    }

    public void recordInteractionRequest(InvocationEvidence evidence, String requestId, long linkedAtNanos,
                                         int queueDepth, int playerQueueDepth, boolean queued) {
        if (queueDepth < 0 || queueDepth > 16 || playerQueueDepth < 0 || playerQueueDepth > 2) {
            throw new IllegalArgumentException("queue depth is outside the live bound");
        }
        if (evidence != null) {
            evidence.record.requestId = safeEvidenceId(requestId);
            evidence.record.requestLinkAtNanos = linkedAtNanos;
            if (queued) evidence.record.queuedAtNanos = linkedAtNanos;
            evidence.record.queueDepthAtAdmission = queueDepth;
            evidence.record.playerQueueDepthAtAdmission = playerQueueDepth;
        }
    }

    /** One bounded FIFO/round-robin scan sample; maxima describe observed samples only. */
    public void recordPendingQueueScan(int globalPending, int maxPlayerPending, long oldestPendingAgeNanos,
                                       long oldestCleanupDelayNanos, int scannedRecords, int scannedPlayerQueues) {
        if (globalPending < 0 || globalPending > 16 || maxPlayerPending < 0 || maxPlayerPending > 2 ||
                scannedRecords < 0 || scannedRecords > 17 || scannedPlayerQueues < 0 || scannedPlayerQueues > 16) {
            throw new IllegalArgumentException("pending queue scan exceeds the live bound");
        }
        pendingQueueScans.updateAndGet(current -> current == Long.MAX_VALUE ? current : current + 1);
        pendingQueueRecordSamples.updateAndGet(current -> current > Long.MAX_VALUE - scannedRecords
                ? Long.MAX_VALUE : current + scannedRecords);
        maxPendingGlobal.accumulateAndGet(globalPending, Math::max);
        maxPendingPerPlayer.accumulateAndGet(maxPlayerPending, Math::max);
        maxPendingRecordsPerScan.accumulateAndGet(scannedRecords, Math::max);
        maxPlayerQueuesPerScan.accumulateAndGet(scannedPlayerQueues, Math::max);
        maxPendingAgeNanos.accumulateAndGet(Math.max(0, oldestPendingAgeNanos), Math::max);
        maxPendingCleanupDelayNanos.accumulateAndGet(Math.max(0, oldestCleanupDelayNanos), Math::max);
    }

    /** Records local outbound-queue acceptance, not model execution or client receipt. */
    public void recordInteractionForwarded(InvocationEvidence evidence, long forwardedAtNanos,
                                           long pendingResidenceNanos, int globalPending,
                                           int playerPending) {
        if (globalPending < 0 || globalPending > 16 || playerPending < 0 || playerPending > 2 ||
                pendingResidenceNanos < 0) throw new IllegalArgumentException("forwarding queue evidence is invalid");
        if (evidence != null) evidence.record.recordForwardedAt(forwardedAtNanos, pendingResidenceNanos,
                globalPending, playerPending);
    }

    /** Stores immutable per-request timeout policy plus the original, receipt-anchored deadlines. */
    public void recordExecutionBudget(InvocationEvidence evidence, int executionBudgetSeconds,
                                      long pendingDeadlineAtNanos, long absoluteDeadlineAtNanos) {
        if (executionBudgetSeconds < 1 || executionBudgetSeconds > 30) {
            throw new IllegalArgumentException("execution budget is outside the supported range");
        }
        if (evidence != null) evidence.record.recordExecutionBudget(executionBudgetSeconds,
                pendingDeadlineAtNanos, absoluteDeadlineAtNanos);
    }

    /** Records the activation deadline once; later callbacks cannot renew it. */
    public void recordActiveExecutionDeadline(InvocationEvidence evidence, long activeDeadlineAtNanos) {
        if (evidence != null) evidence.record.recordActiveExecutionDeadline(activeDeadlineAtNanos);
    }

    /** Records an unsent/unknown progress outcome; confirmed publication requires the richer identity/time overload. */
    public void recordInteractionProgress(InvocationEvidence evidence, String phase, String status, long latencyNanos) {
        if (!("queued".equals(phase) || "awaiting_answer".equals(phase)) ||
                !("failed".equals(status) || "missed".equals(status) || "unknown".equals(status))) {
            throw new IllegalArgumentException("progress evidence labels are invalid");
        }
        Counter outcomeCounter = switch (status) {
            case "dispatched" -> Counter.PROGRESS_DISPATCHES;
            case "failed" -> Counter.PROGRESS_FAILURES;
            case "missed" -> Counter.PROGRESS_MISSED;
            case "unknown" -> Counter.PROGRESS_UNKNOWN;
            default -> throw new IllegalArgumentException("progress evidence status is invalid");
        };
        increment(outcomeCounter);
        if (evidence != null) {
            String state = "queued".equals(phase) ? "queued" : "forwarded_waiting";
            String publicResult = switch (status) {
                case "failed" -> "DEFINITELY_UNSENT";
                case "unknown" -> "UNKNOWN";
                default -> "NO_ATTEMPT";
            };
            evidence.record.recordProgress(phase, state, status, Math.max(0, latencyNanos),
                    publicResult, null, null, null);
        }
    }

    /** Records the public send result at the publication decision, separately from context transport. */
    public void recordInteractionProgress(InvocationEvidence evidence, String phase, String state,
                                          FirstReplyScheduler.PublicationResult publicResult,
                                          long latencyNanos, Long publicDispatchAtNanos, String noticeId) {
        if (!("queued".equals(phase) || "awaiting_answer".equals(phase)) ||
                !("queued".equals(state) || "forwarded_waiting".equals(state) || "context_blocked".equals(state)) ||
                publicResult == null) throw new IllegalArgumentException("progress dispatch evidence is invalid");
        String status = switch (publicResult) {
            case SUBMITTED -> "dispatched";
            case DEFINITELY_UNSENT -> "failed";
            case UNKNOWN -> "unknown";
        };
        Counter counter = switch (status) {
            case "dispatched" -> Counter.PROGRESS_DISPATCHES;
            case "failed" -> Counter.PROGRESS_FAILURES;
            case "unknown" -> Counter.PROGRESS_UNKNOWN;
            default -> throw new IllegalStateException("unmapped progress result");
        };
        increment(counter);
        if (evidence != null) evidence.record.recordProgress(phase, state, status, Math.max(0, latencyNanos),
                publicResult.name(), publicDispatchAtNanos, noticeId, null);
    }

    public void recordInteractionProgressMissed(InvocationEvidence evidence, String phase, String state,
                                                long latencyNanos) {
        if (!("queued".equals(phase) || "awaiting_answer".equals(phase)) ||
                !("queued".equals(state) || "forwarded_waiting".equals(state) || "context_blocked".equals(state))) {
            throw new IllegalArgumentException("progress miss evidence is invalid");
        }
        increment(Counter.PROGRESS_MISSED);
        if (evidence != null) evidence.record.recordProgress(phase, state, "missed", Math.max(0, latencyNanos),
                "NO_ATTEMPT", null, null, null);
    }

    public void recordInteractionProgressContext(InvocationEvidence evidence, String noticeId,
                                                 String contextSendResult, long completedAtNanos) {
        if (!Set.of("SEND_COMPLETED", "FAILED", "UNKNOWN", "QUEUED_WITH_REQUEST").contains(contextSendResult)) {
            throw new IllegalArgumentException("progress context result is invalid");
        }
        if (evidence != null) evidence.record.recordProgressContext(noticeId, contextSendResult, completedAtNanos);
    }

    public void recordInteractionTerminal(InvocationEvidence evidence, String outcome, long latencyNanos) {
        recordInteractionTerminal(evidence, outcome, null, null, latencyNanos);
    }

    /** Terminal reason and its delivery outcome are independent evidence. */
    public void recordInteractionTerminal(InvocationEvidence evidence, String reason, String deliveryResult,
                                          Long terminalAtNanos, long latencyNanos) {
        recordInteractionTerminal(evidence, reason, null, deliveryResult, terminalAtNanos, latencyNanos);
    }

    public void recordInteractionTerminal(InvocationEvidence evidence, String reason, String cause,
                                          String deliveryResult, Long terminalAtNanos, long latencyNanos) {
        if (evidence == null) return;
        if (reason == null || !reason.matches("[A-Z_]{1,32}") || deliveryResult != null &&
                !Set.of("SUBMITTED", "DEFINITELY_UNSENT", "UNKNOWN", "NO_ATTEMPT").contains(deliveryResult) ||
                cause != null && !cause.matches("[A-Z_]{1,32}")) {
            throw new IllegalArgumentException("terminal evidence is invalid");
        }
        long value = Math.max(0, latencyNanos);
        boolean noDispatchDeadline = value >= TimeUnit.SECONDS.toNanos(1);
        if (evidence.record.recordTerminal(reason, cause, value, deliveryResult, terminalAtNanos) &&
                evidence.record.firstDispatchNanos < 0) {
            recordNoFirstDispatch(evidence, evidence.record.capturedAtNanos + value, noDispatchDeadline);
        }
    }

    public void recordInteractionRetirementNotice(InvocationEvidence evidence, String result) {
        if (result == null || !Set.of("SUBMITTED", "DEFINITELY_UNSENT", "UNKNOWN", "NO_AUDIENCE",
                "SUPPRESSED", "SHUTDOWN").contains(result)) {
            throw new IllegalArgumentException("retirement notice result is invalid");
        }
        if (evidence != null) evidence.record.recordRetirementNotice(result);
    }

    public void recordInteractionLocalCancel(InvocationEvidence evidence, String status,
                                             String sendResult, boolean providerCancel) {
        if (status == null || !Set.of("NOT_ATTEMPTED", "QUEUED", "DEFINITELY_UNSENT", "SUBMITTED",
                "UNKNOWN", "SHUTDOWN").contains(status) || sendResult != null &&
                !Set.of("SUBMITTED", "UNKNOWN").contains(sendResult) || providerCancel) {
            throw new IllegalArgumentException("local cancellation evidence is invalid");
        }
        if (evidence != null) evidence.record.recordLocalCancel(status, sendResult, false);
    }

    public void recordInteractionLateOutputRejected(InvocationEvidence evidence) {
        increment(Counter.LATE_OUTPUT_REJECTIONS);
        if (evidence != null) evidence.record.recordLateOutputRejected();
    }

    public long[][][] firstPublicDispatchHistogramSnapshot() {
        materializeAllNoticePublications();
        long[][][] snapshot = new long[FIRST_PUBLIC_SOURCES.length][FIRST_PUBLIC_DISPOSITIONS.length][];
        for (int source = 0; source < snapshot.length; source++) {
            for (int disposition = 0; disposition < snapshot[source].length; disposition++) {
                AtomicLongArray histogram = firstPublicDispatchHistograms[source][disposition];
                snapshot[source][disposition] = new long[FIRST_PUBLIC_DISPATCH_UPPER_NANOS.length];
                for (int index = 0; index < snapshot[source][disposition].length; index++) {
                    snapshot[source][disposition][index] = histogram.get(index);
                }
            }
        }
        return snapshot;
    }

    /** Called off tick by an authorized evidence owner; contents are fixed numeric snapshots only. */
    public boolean offerNumericSnapshot(Path privateFile) {
        if (privateFile == null || persistenceFailed.get() || !reserveBatch()) return rejectEvidence();
        byte[] snapshot = renderSnapshot();
        if (snapshot.length > MAX_SNAPSHOT_BYTES || persistedBytes + snapshot.length > MAX_RUN_BYTES) return rejectEvidence();
        if (!batches.offer(snapshot)) return rejectEvidence();
        if (!writerLane.tryExecute(() -> persistNext(privateFile))) {
            batches.remove(snapshot);
            return rejectEvidence();
        }
        return true;
    }

    public boolean evidenceIncomplete() { return evidenceIncomplete.get(); }
    public long persistedBytes() { return persistedBytes; }
    public int persistedBatches() { return persistedBatches; }

    public long[] countersSnapshot() {
        materializeAllNoticePublications();
        long[] snapshot = new long[COUNTER_COUNT];
        for (int index = 0; index < snapshot.length; index++) snapshot[index] = counters.get(index);
        return snapshot;
    }

    private byte[] renderSnapshot() {
        long[] counterSnapshot = countersSnapshot();
        int reserved = reservedInteractionCount();
        List<InteractionRecord> retained = new ArrayList<>(reserved);
        for (int index = 0; index < reserved; index++) {
            InteractionRecord record = interactionRecords[index];
            if (record != null) retained.add(record);
        }
        long captureDropped = counterSnapshot[Counter.INTERACTION_EVIDENCE_DROPPED.ordinal()];
        long sourceCount = Math.max(counterSnapshot[Counter.INVOCATIONS_CAPTURED.ordinal()],
                (long) reserved + captureDropped);
        long omitted = Math.max(0L, sourceCount - retained.size());
        boolean unsettledReservation = retained.size() != reserved || retained.stream().anyMatch(record -> !record.decisionRecorded);
        int chunkCount = (retained.size() + MAX_CHUNK_RECORDS - 1) / MAX_CHUNK_RECORDS;
        String snapshotId = UUID.randomUUID().toString();
        StringBuilder output = new StringBuilder(1024);
        output.append("{\"type\":\"numeric_snapshot_manifest\",\"numericFormatVersion\":")
                .append(NUMERIC_FORMAT_VERSION).append(",\"snapshotId\":");
        appendJsonString(output, snapshotId);
        output.append(",\"sourceInvocationCount\":").append(sourceCount)
                .append(",\"retainedInvocationCount\":").append(retained.size())
                .append(",\"omittedInvocationCount\":").append(omitted)
                .append(",\"captureDroppedInvocationCount\":").append(captureDropped)
                .append(",\"captureStatus\":");
        appendJsonString(output, sourceCount == retained.size() && captureDropped == 0 && !unsettledReservation
                ? "complete" : "incomplete");
        output.append(",\"chunkSize\":").append(MAX_CHUNK_RECORDS)
                .append(",\"chunkCount\":").append(chunkCount).append(",\"counters\":[");
        for (int index = 0; index < COUNTER_COUNT; index++) {
            if (index != 0) output.append(',');
            output.append(counterSnapshot[index]);
        }
        output.append("],\"tickHistogram\":[");
        for (int index = 0; index < HISTOGRAM_UPPER_NANOS.length; index++) {
            if (index != 0) output.append(',');
            output.append(tickHistogram.get(index));
        }
        output.append("],\"firstPublicDispatchHistograms\":[");
        for (int source = 0; source < FIRST_PUBLIC_SOURCES.length; source++) {
            if (source != 0) output.append(',');
            output.append('[');
            for (int disposition = 0; disposition < FIRST_PUBLIC_DISPOSITIONS.length; disposition++) {
                if (disposition != 0) output.append(',');
                output.append('[');
                AtomicLongArray histogram = firstPublicDispatchHistograms[source][disposition];
                for (int index = 0; index < FIRST_PUBLIC_DISPATCH_UPPER_NANOS.length; index++) {
                    if (index != 0) output.append(',');
                    output.append(histogram.get(index));
                }
                output.append(']');
            }
            output.append(']');
        }
        output.append("],\"maxFirstPublicDispatchNanos\":");
        appendJsonLongString(output, maxFirstPublicDispatchNanos.get());
        output.append(",\"queueEvidence\":{\"pendingQueueScans\":").append(pendingQueueScans.get())
                .append(",\"pendingQueueRecordSamples\":").append(pendingQueueRecordSamples.get())
                .append(",\"maxPendingGlobal\":").append(maxPendingGlobal.get())
                .append(",\"maxPendingPerPlayer\":").append(maxPendingPerPlayer.get())
                .append(",\"maxPendingAgeNanos\":");
        appendJsonLongString(output, maxPendingAgeNanos.get());
        output.append(",\"maxPendingCleanupDelayNanos\":");
        appendJsonLongString(output, maxPendingCleanupDelayNanos.get());
        output.append(",\"maxRecordsPerScan\":").append(maxPendingRecordsPerScan.get())
                .append(",\"maxPlayerQueuesPerScan\":").append(maxPlayerQueuesPerScan.get())
                .append(",\"recordLimitPerScan\":17,\"playerQueueLimitPerScan\":16}");
        output.append("}\n");
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int start = chunkIndex * MAX_CHUNK_RECORDS;
            int end = Math.min(start + MAX_CHUNK_RECORDS, retained.size());
            output.append("{\"type\":\"numeric_snapshot_chunk\",\"numericFormatVersion\":")
                    .append(NUMERIC_FORMAT_VERSION).append(",\"snapshotId\":");
            appendJsonString(output, snapshotId);
            output.append(",\"index\":").append(chunkIndex)
                    .append(",\"start\":").append(start).append(",\"records\":[");
            for (int index = start; index < end; index++) {
                if (index > start) output.append(',');
                retained.get(index).appendJson(output);
            }
            output.append("]}\n");
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void materializeAllNoticePublications() {
        int limit = reservedInteractionCount();
        for (int index = 0; index < limit; index++) {
            InteractionRecord record = interactionRecords[index];
            if (record != null) materializeNoticePublication(record);
        }
    }

    private void materializeNoticePublication(InteractionRecord record) {
        NoticeMaterialization completed = record.materializeNoticePublication();
        if (completed == null) return;
        switch (completed.result().state()) {
            case SUBMITTED -> {
                if (completed.firstDispatchRecorded()) {
                    recordFirstPublicDispatchNanos(completed.latencyNanos(), completed.source(),
                            completed.disposition(), completed.countDeadlineMiss());
                }
            }
            case DEFINITELY_UNSENT -> recordFailedInteractionDispatch(new InvocationEvidence(record));
            case UNKNOWN -> recordUnknownInteractionDispatch(new InvocationEvidence(record), record.noticePublicationId());
            case PENDING, CLAIMED, CANCELLED -> { }
        }
    }

    private void incrementOutcome(String outcome) {
        switch (outcome) {
            case "HELP" -> increment(Counter.INTERACTION_HELP);
            case "INVALID" -> increment(Counter.INTERACTION_INVALID);
            case "DENIED" -> increment(Counter.INTERACTION_DENIED);
            case "UNAUTHENTICATED" -> increment(Counter.INTERACTION_UNAUTHENTICATED);
            case "UNAVAILABLE_BEFORE_BOOTSTRAP" -> increment(Counter.INTERACTION_UNAVAILABLE_BEFORE_BOOTSTRAP);
            case "RECEIPT_EVIDENCE_MISSING" -> increment(Counter.INTERACTION_RECEIPT_EVIDENCE_MISSING);
            case "UNAVAILABLE" -> increment(Counter.INTERACTION_UNAVAILABLE);
            case "BUSY" -> increment(Counter.INTERACTION_BUSY);
            case "COALESCED" -> increment(Counter.INTERACTION_COALESCED);
            case "FOLLOWUP_ENDED" -> increment(Counter.INTERACTION_FOLLOWUP_ENDED);
            case "ADMITTED" -> increment(Counter.INTERACTION_ADMITTED);
            case "RATE_LIMITED" -> increment(Counter.INTERACTION_RATE_LIMITED);
            case "EXPIRED" -> increment(Counter.INTERACTION_EXPIRED);
            default -> throw new IllegalArgumentException("unknown interaction disposition");
        }
    }

    private static void appendNullableJsonString(StringBuilder output, String value) {
        if (value == null) output.append("null");
        else appendJsonString(output, value);
    }

    private static void appendJsonLongString(StringBuilder output, long value) {
        appendJsonString(output, Long.toString(value));
    }

    private static void appendNullableJsonLongString(StringBuilder output, Long value) {
        if (value == null) output.append("null");
        else appendJsonLongString(output, value);
    }

    private static void appendJsonString(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (current < 0x20) {
                        output.append("\\u");
                        String hex = Integer.toHexString(current);
                        output.append("0".repeat(4 - hex.length())).append(hex);
                    } else output.append(current);
                }
            }
        }
        output.append('"');
    }

    private static String safeEvidenceId(String value) {
        if (value == null || value.length() > 128 || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("request evidence identity is invalid");
        }
        return value;
    }

    private static String safeEvidenceIdOrNull(String value) {
        if (value == null) return null;
        return safeEvidenceId(value);
    }

    private static boolean boundedInvocationFields(UUID invocationId, String actualWorldId,
                                                  ConfiguredBinding binding, String captureProvenance,
                                                  String origin, String templateRevision, String producer) {
        return invocationId != null &&
                ("LIVE_PACKET".equals(captureProvenance) || "SYNTHETIC_HARNESS".equals(captureProvenance) ||
                        "MISSING_PACKET_RECEIPT".equals(captureProvenance)) &&
                ("COMMAND".equals(origin) || "NATURAL_ADDRESS".equals(origin) || "FOLLOWUP".equals(origin)) &&
                ("SIGNED_CHAT".equals(producer) || "UNSIGNED_COMMAND".equals(producer) ||
                        "SIGNED_COMMAND".equals(producer) || "SYNTHETIC_HARNESS".equals(producer) ||
                        "MISSING_PACKET_RECEIPT".equals(producer) || "LIVE_PACKET".equals(producer)) &&
                isSafeWorldIdOrNull(actualWorldId) && isSafeEvidenceIdOrNull(templateRevision) &&
                (binding == null || isSafeEvidenceIdOrNull(binding.serverId()) &&
                        isSafeEvidenceIdOrNull(binding.worldId()) && isSafeEvidenceIdOrNull(binding.characterId()));
    }

    private static boolean isSafeEvidenceIdOrNull(String value) {
        return value == null || value.length() <= 128 && value.matches("[A-Za-z0-9._:-]+");
    }

    private static boolean isSafeWorldIdOrNull(String value) {
        return value == null || value.length() <= 128 && value.matches("[A-Za-z0-9._:/-]+");
    }

    private void persistNext(Path privateFile) {
        ThreadBoundary.assertNotServerThread();
        byte[] next = batches.poll();
        if (next == null) return;
        try {
            if (persistedPath == null) {
                FileAttribute<Set<PosixFilePermission>> permissions = PosixFilePermissions.asFileAttribute(
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                Files.createFile(privateFile, permissions);
                Files.write(privateFile, next, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                persistedPath = privateFile.toAbsolutePath().normalize();
            } else {
                if (!persistedPath.equals(privateFile.toAbsolutePath().normalize())) {
                    rejectEvidence();
                    return;
                }
                Files.write(privateFile, next, StandardOpenOption.APPEND);
            }
            persistedBytes += next.length;
            persistedBatches++;
            if (persistedBytes > MAX_RUN_BYTES || persistedBatches > MAX_BATCHES) rejectEvidence();
        } catch (IOException | SecurityException failure) {
            rejectEvidence();
        }
    }

    private boolean rejectEvidence() {
        failEvidenceAuthority(true);
        return false;
    }

    private EvidenceReservation reserveEvidenceRow() {
        while (true) {
            EvidenceAuthority current = evidenceAuthority.get();
            if (current.unavailable() || current.reservedRows() >= MAX_INTERACTION_RECORDS) {
                failEvidenceAuthority(false);
                return null;
            }
            EvidenceAuthority next = new EvidenceAuthority(current.version(), current.reservedRows() + 1,
                    current.committedAdmissions(), false);
            if (evidenceAuthority.compareAndSet(current, next)) {
                return new EvidenceReservation(current.reservedRows(), current.version());
            }
        }
    }

    private void recordDroppedInvocation() {
        failEvidenceAuthority(false);
        increment(Counter.INVOCATIONS_CAPTURED);
        increment(Counter.INTERACTION_EVIDENCE_DROPPED);
    }

    private void failEvidenceAuthority(boolean persistenceFailure) {
        while (true) {
            EvidenceAuthority current = evidenceAuthority.get();
            if (current.unavailable()) break;
            EvidenceAuthority failed = new EvidenceAuthority(current.version() + 1, current.reservedRows(),
                    current.committedAdmissions(), true);
            if (evidenceAuthority.compareAndSet(current, failed)) break;
        }
        evidenceIncomplete.set(true);
        if (persistenceFailure) persistenceFailed.set(true);
    }

    private InteractionRecord findInteractionRecord(UUID invocationId, int limit) {
        int count = Math.min(limit, MAX_INTERACTION_RECORDS);
        for (int index = 0; index < count; index++) {
            InteractionRecord record = interactionRecords[index];
            if (record != null && record.invocationId.equals(invocationId)) return record;
        }
        return null;
    }

    private boolean containsInteractionRecord(InteractionRecord expected, int limit) {
        int count = Math.min(limit, MAX_INTERACTION_RECORDS);
        for (int index = 0; index < count; index++) {
            if (interactionRecords[index] == expected) return true;
        }
        return false;
    }

    private boolean reserveBatch() {
        while (true) {
            int current = acceptedBatches.get();
            if (current >= MAX_BATCHES) return false;
            if (acceptedBatches.compareAndSet(current, current + 1)) return true;
        }
    }

    private static void incrementSaturated(AtomicLongArray values, int index) {
        values.getAndUpdate(index, current -> current == Long.MAX_VALUE ? current : current + 1);
    }

    private record EvidenceAuthority(long version, int reservedRows, int committedAdmissions, boolean unavailable) {}

    private record EvidenceReservation(int index, long authorityVersion) {}

    boolean awaitTerminationForTests(long timeout, TimeUnit unit) throws InterruptedException {
        writerLane.shutdown();
        return writerLane.awaitTermination(timeout, unit);
    }

    @Override
    public void close() {
        writerLane.shutdown();
    }

    public enum Counter {
        FIRST_REPLY_DEADLINE_MISSES,
        TERMINAL_DUPLICATES,
        MAILBOX_OVERFLOWS,
        STALE_OUTPUTS,
        TICK_BUDGET_EXHAUSTED,
        PENDING_EXPIRED,
        UPSTREAM_REJECTIONS,
        ACK_CONTEXT_FAILURES,
        CONNECTION_FAILURES,
        RECONNECT_EXHAUSTED,
        REQUESTS_ADMITTED,
        ANSWERS_DELIVERED,
        PROGRESS_DISPATCHES,
        PROGRESS_FAILURES,
        PROGRESS_MISSED,
        PROGRESS_UNKNOWN,
        PROGRESS_CONTEXT_FAILURES,
        UNAVAILABLE_DISPOSITIONS,
        INVOCATIONS_CAPTURED,
        INTERACTION_HELP,
        INTERACTION_INVALID,
        INTERACTION_DENIED,
        INTERACTION_UNAUTHENTICATED,
        INTERACTION_UNAVAILABLE_BEFORE_BOOTSTRAP,
        INTERACTION_RECEIPT_EVIDENCE_MISSING,
        INTERACTION_UNAVAILABLE,
        INTERACTION_BUSY,
        INTERACTION_COALESCED,
        INTERACTION_ADMITTED,
        INTERACTION_FOLLOWUP_ENDED,
        FAILED_PUBLIC_DISPATCHES,
        INTERACTION_EVIDENCE_DROPPED,
        FIRST_REPLY_ON_TIME,
        FIRST_REPLY_LATE,
        FIRST_REPLY_NO_DISPATCH,
        FIRST_REPLY_NO_DISPATCH_SETTLED,
        UNKNOWN_PUBLIC_DISPATCHES,
        CONTROL_QUEUE_OVERFLOWS,
        DISPOSITION_QUEUE_OVERFLOWS,
        INTERACTION_RATE_LIMITED,
        INTERACTION_EXPIRED,
        INVOCATION_RATE_LIMITED,
        NEW_WORK_RATE_LIMITED,
        RATE_LIMIT_SUBJECT_OVERFLOWS,
        RATE_LIMIT_NOTICES_SUPPRESSED,
        CAPTURE_HANDOFF_OVERFLOWS,
        LATE_OUTPUT_REJECTIONS
    }

    public enum FirstPublicSource {
        LOCAL_ACKNOWLEDGEMENT,
        INTEGRATION_ANSWER,
        INTEGRATION_FAILURE,
        LOCAL_SAFE_RESPONSE,
        LOCAL_REJECTION,
        LOCAL_UNAVAILABLE,
        LOCAL_COALESCED,
        COMMAND_HELP,
        LOCAL_FOLLOWUP_END,
        COMMAND_GUIDANCE,
        NATURAL_INVALID,
        LOCAL_UNAUTHENTICATED,
        LOCAL_DENIED,
        LOCAL_BUSY
    }

    public enum FirstPublicDisposition {
        ACKNOWLEDGED,
        ANSWERED,
        FAILED,
        REJECTED,
        UNAVAILABLE,
        COALESCED,
        HELP,
        FOLLOWUP_ENDED,
        INVALID,
        DENIED,
        UNAUTHENTICATED,
        BUSY
    }

    public static final class InvocationEvidence {
        private final InteractionRecord record;

        private InvocationEvidence(InteractionRecord record) {
            this.record = record;
        }
    }

    private record NoticeMaterialization(FirstReplyScheduler.NoticeResult result, long latencyNanos,
                                          FirstPublicSource source, FirstPublicDisposition disposition,
                                          boolean firstDispatchRecorded, boolean countDeadlineMiss) {}

    private record FirstDispatchMaterialization(boolean countDeadlineMiss) {}

    private record NoDispatchMaterialization(boolean deadlineMiss, boolean countDeadlineMiss) {}

    private static final class InteractionRecord {
        private final UUID invocationId;
        private final long capturedAtNanos;
        private final long capturedAtUnixMillis;
        private final long handlerEnteredAtNanos;
        private final String captureProvenance;
        private final long connectionGeneration;
        private final UUID playerId;
        private final String actualWorldId;
        private final String serverBinding;
        private final String worldBinding;
        private final String characterBinding;
        private final String origin;
        private volatile String outcome;
        private final FirstReplyScheduler.Slot firstReplySlot;
        private final long admittedAtNanos;
        private final String templateRevision;
        private final String producer;
        private final long authorityVersion;
        private final AtomicInteger admissionCommitState = new AtomicInteger();
        private volatile Long decisionAtNanos;
        private volatile boolean decisionRecorded;
        private volatile String rateLimitStatus = "NOT_CHECKED";
        private volatile String noticeStatus = "NOT_REQUIRED";
        private volatile FirstReplyScheduler.NoticePublication noticePublication;
        private volatile FirstPublicSource noticeBatchSource;
        private volatile FirstPublicDisposition noticeBatchDisposition;
        private boolean noticePublicationMaterialized;
        private volatile long firstDispatchNanos = -1;
        private volatile Long firstDispatchAtNanos;
        private volatile String publicationId;
        private volatile String firstReplyResult;
        private volatile String sendResult;
        private volatile Long deadlineSettledAtNanos;
        private volatile String firstDispatchSource;
        private volatile String firstDispatchDisposition;
        private boolean noDispatchSettlementRecorded;
        private boolean deadlineMissRecorded;
        private final AtomicInteger failedDispatches = new AtomicInteger();
        private final AtomicInteger unknownDispatches = new AtomicInteger();
        private volatile String requestId;
        private volatile Long requestLinkAtNanos;
        private volatile Long queuedAtNanos;
        private volatile int queueDepthAtAdmission;
        private volatile int playerQueueDepthAtAdmission;
        private volatile Long forwardedAtNanos;
        private volatile Long pendingResidenceNanos;
        private volatile Integer globalPendingAtForward;
        private volatile Integer playerPendingAtForward;
        private volatile String terminalOutcome;
        private volatile String terminalCause;
        private volatile long terminalNanos = -1;
        private volatile Long terminalAtNanos;
        private volatile String terminalDeliveryResult;
        private volatile String retirementNoticeResult;
        private volatile String localCancelStatus = "NOT_ATTEMPTED";
        private volatile String localCancelSendResult;
        private volatile Boolean providerCancel;
        private volatile long lateOutputRejections;
        private volatile Integer executionBudgetSeconds;
        private volatile Long pendingDeadlineAtNanos;
        private volatile Long activeDeadlineAtNanos;
        private volatile Long absoluteExecutionDeadlineAtNanos;
        private final ProgressEvidence[] progress = new ProgressEvidence[ConversationProgressLimit.VALUE];
        private int progressCount;

        private InteractionRecord(UUID invocationId, long capturedAtNanos, long capturedAtUnixMillis,
                                   long handlerEnteredAtNanos, String captureProvenance, long connectionGeneration,
                                   UUID playerId, String actualWorldId,
                                    ConfiguredBinding binding, String origin,
                                     FirstReplyScheduler.Slot firstReplySlot, long admittedAtNanos,
                                     String templateRevision, String producer, long authorityVersion) {
            this.invocationId = invocationId;
            this.capturedAtNanos = capturedAtNanos;
            this.capturedAtUnixMillis = capturedAtUnixMillis;
            this.handlerEnteredAtNanos = handlerEnteredAtNanos;
            this.captureProvenance = captureProvenance;
            this.connectionGeneration = connectionGeneration;
            this.playerId = playerId;
            this.actualWorldId = actualWorldId;
            this.serverBinding = binding == null ? null : binding.serverId();
            this.worldBinding = binding == null ? null : binding.worldId();
            this.characterBinding = binding == null ? null : binding.characterId();
            this.origin = origin;
            this.outcome = "UNAVAILABLE";
            this.firstReplySlot = firstReplySlot;
            this.admittedAtNanos = admittedAtNanos;
            this.templateRevision = templateRevision;
            this.producer = producer;
            this.authorityVersion = authorityVersion;
            if ("MISSING_PACKET_RECEIPT".equals(captureProvenance)) {
                firstReplyResult = "EVIDENCE_MISSING";
                sendResult = "NO_DISPATCH";
            }
        }

        private synchronized void recordDecision(String outcome, long decisionAtNanos) {
            if (outcome == null || !outcome.matches("[A-Z_]{1,32}")) {
                throw new IllegalArgumentException("interaction decision label is invalid");
            }
            if (decisionRecorded) {
                if (!this.outcome.equals(outcome)) throw new IllegalStateException("interaction decision changed");
                return;
            }
            this.outcome = outcome;
            this.decisionAtNanos = decisionAtNanos;
            decisionRecorded = true;
        }

        private synchronized void recordRateLimitStatus(String status) {
            boolean allowedTransition = "ALLOWED".equals(rateLimitStatus) &&
                    Set.of("NEW_WORK_LIMITED", "SUBJECT_CAPACITY", "EVIDENCE_UNAVAILABLE",
                            "REPEAT_GUARD", "REPEAT_GUARD_CAPACITY").contains(status);
            boolean evidenceLossTransition = "EVIDENCE_UNAVAILABLE".equals(status);
            if (!"NOT_CHECKED".equals(rateLimitStatus) && !rateLimitStatus.equals(status) &&
                    !allowedTransition && !evidenceLossTransition) {
                throw new IllegalStateException("rate-limit status changed");
            }
            rateLimitStatus = status;
        }

        private synchronized void recordNoticeStatus(String status) {
            if ("SUBMITTED".equals(noticeStatus) || "DEFINITELY_UNSENT".equals(noticeStatus) ||
                    "UNKNOWN".equals(noticeStatus) || "OVERFLOW".equals(noticeStatus) ||
                    "CANCELLED".equals(noticeStatus) ||
                    "SUPPRESSED".equals(noticeStatus)) {
                if (!noticeStatus.equals(status)) throw new IllegalStateException("notice result changed");
                return;
            }
            noticeStatus = status;
        }

        private synchronized void linkNoticePublication(FirstReplyScheduler.NoticePublication publication,
                                                        FirstPublicSource source,
                                                        FirstPublicDisposition disposition) {
            if (noticePublication != null && noticePublication != publication) {
                throw new IllegalStateException("invocation notice publication changed");
            }
            noticePublication = publication;
            noticeBatchSource = source;
            noticeBatchDisposition = disposition;
        }

        private synchronized NoticeMaterialization materializeNoticePublication() {
            if (noticePublication == null || noticePublicationMaterialized) return null;
            FirstReplyScheduler.NoticeResult result = noticePublication.result();
            if (result.state() == FirstReplyScheduler.NoticeState.PENDING ||
                    result.state() == FirstReplyScheduler.NoticeState.CLAIMED) return null;
            noticePublicationMaterialized = true;
            switch (result.state()) {
                case SUBMITTED -> {
                    long latency = Math.max(0, result.attemptedAtNanos() - capturedAtNanos);
                    FirstDispatchMaterialization dispatch = recordFirstDispatch(latency, noticeBatchSource, noticeBatchDisposition,
                            result.attemptedAtNanos(), noticePublication.publicationId());
                    noticeStatus = "SUBMITTED";
                    return new NoticeMaterialization(result, latency, noticeBatchSource, noticeBatchDisposition,
                            dispatch != null, dispatch != null && dispatch.countDeadlineMiss());
                }
                case DEFINITELY_UNSENT -> {
                    noticeStatus = "DEFINITELY_UNSENT";
                    recordSendResult("DEFINITELY_UNSENT", false, noticePublication.publicationId());
                }
                case UNKNOWN -> {
                    noticeStatus = "UNKNOWN";
                    recordSendResult("UNKNOWN", true, noticePublication.publicationId());
                }
                case CANCELLED -> noticeStatus = "CANCELLED";
                case PENDING, CLAIMED -> throw new IllegalStateException("pending notice result was materialized");
            }
            return new NoticeMaterialization(result, 0, noticeBatchSource, noticeBatchDisposition, false, false);
        }

        private String noticePublicationId() {
            FirstReplyScheduler.NoticePublication publication = noticePublication;
            return publication == null ? null : publication.publicationId();
        }

        private String effectiveNoticeStatus() {
            FirstReplyScheduler.NoticePublication publication = noticePublication;
            if (publication == null) return noticeStatus;
            return switch (publication.result().state()) {
                case PENDING, CLAIMED -> "QUEUED";
                case SUBMITTED -> "SUBMITTED";
                case DEFINITELY_UNSENT -> "DEFINITELY_UNSENT";
                case UNKNOWN -> "UNKNOWN";
                case CANCELLED -> "CANCELLED";
            };
        }

        private synchronized FirstDispatchMaterialization recordFirstDispatch(long latencyNanos,
                                                                              FirstPublicSource source,
                                                                              FirstPublicDisposition disposition,
                                                                              Long dispatchedAtNanos,
                                                                              String publicationId) {
            if (firstDispatchNanos >= 0 || captureMissing()) return null;
            boolean late = latencyNanos > TimeUnit.SECONDS.toNanos(1);
            boolean countDeadlineMiss = late && !deadlineMissRecorded;
            if (late) deadlineMissRecorded = true;
            firstDispatchNanos = latencyNanos;
            firstDispatchAtNanos = dispatchedAtNanos;
            this.publicationId = safeEvidenceIdOrNull(publicationId);
            firstDispatchSource = source.name();
            firstDispatchDisposition = disposition.name();
            firstReplyResult = late ? "LATE" : "ON_TIME";
            sendResult = "SUBMITTED";
            return new FirstDispatchMaterialization(countDeadlineMiss);
        }

        private synchronized NoDispatchMaterialization recordNoFirstDispatch(long settledAtNanos, boolean deadline) {
            if (firstDispatchNanos >= 0 || captureMissing() || noDispatchSettlementRecorded) return null;
            noDispatchSettlementRecorded = true;
            firstReplyResult = deadline ? "NO_DISPATCH_DEADLINE" : "NO_DISPATCH_SETTLED";
            deadlineSettledAtNanos = settledAtNanos;
            boolean countDeadlineMiss = deadline && !deadlineMissRecorded;
            if (deadline) deadlineMissRecorded = true;
            if (sendResult == null) sendResult = "NO_DISPATCH";
            return new NoDispatchMaterialization(deadline, countDeadlineMiss);
        }

        private synchronized void recordSendResult(String result, boolean unknown) {
            recordSendResult(result, unknown, null);
        }

        private synchronized void recordSendResult(String result, boolean unknown, String publicationId) {
            if (unknown) unknownDispatches.incrementAndGet();
            else failedDispatches.incrementAndGet();
            if (firstDispatchNanos < 0 && !captureMissing()) sendResult = result;
            if (unknown && this.publicationId == null) this.publicationId = safeEvidenceIdOrNull(publicationId);
        }

        private boolean captureMissing() {
            return "MISSING_PACKET_RECEIPT".equals(captureProvenance);
        }

        private boolean deadlineDue(long nowNanos) {
            return !captureMissing() && nowNanos - (capturedAtNanos + TimeUnit.SECONDS.toNanos(1)) >= 0;
        }

        private synchronized void recordProgress(String phase, String state, String status, long latencyNanos,
                                                 String publicSendResult, Long publicDispatchAtNanos,
                                                 String noticeId, String contextSendResult) {
            if (progressCount >= progress.length) return;
            progress[progressCount++] = new ProgressEvidence(phase, state, status, latencyNanos,
                    publicSendResult, publicDispatchAtNanos, safeEvidenceIdOrNull(noticeId), contextSendResult, null);
        }

        private synchronized void recordExecutionBudget(int seconds, long pendingDeadline, long absoluteDeadline) {
            if (executionBudgetSeconds != null) return;
            executionBudgetSeconds = seconds;
            pendingDeadlineAtNanos = pendingDeadline;
            absoluteExecutionDeadlineAtNanos = absoluteDeadline;
        }

        private synchronized void recordForwardedAt(long forwardedAt, long pendingResidence,
                                                    int globalPending, int playerPending) {
            if (forwardedAtNanos != null) return;
            forwardedAtNanos = forwardedAt;
            pendingResidenceNanos = pendingResidence;
            globalPendingAtForward = globalPending;
            playerPendingAtForward = playerPending;
        }

        private synchronized void recordActiveExecutionDeadline(long activeDeadline) {
            if (activeDeadlineAtNanos == null) activeDeadlineAtNanos = activeDeadline;
        }

        private synchronized void recordProgressContext(String noticeId, String result, long completedAtNanos) {
            if (noticeId == null) return;
            for (int index = 0; index < progressCount; index++) {
                if (noticeId.equals(progress[index].noticeId)) {
                    progress[index].contextSendResult = result;
                    progress[index].contextCompletedAtNanos = completedAtNanos;
                    return;
                }
            }
        }

        private synchronized boolean recordTerminal(String reason, String cause, long latencyNanos,
                                                    String deliveryResult, Long atNanos) {
            if (terminalOutcome == null || "UNRESOLVED".equals(terminalOutcome)) {
                terminalOutcome = reason;
                terminalCause = cause;
                terminalNanos = latencyNanos;
                terminalDeliveryResult = deliveryResult;
                terminalAtNanos = atNanos;
                return true;
            }
            if (terminalCause == null && cause != null) terminalCause = cause;
            if (terminalDeliveryResult == null || "NO_ATTEMPT".equals(terminalDeliveryResult) &&
                    deliveryResult != null && !"NO_ATTEMPT".equals(deliveryResult)) {
                if (deliveryResult != null) terminalDeliveryResult = deliveryResult;
            }
            return false;
        }

        private synchronized void recordRetirementNotice(String result) {
            if (retirementNoticeResult == null || "QUEUED".equals(retirementNoticeResult)) {
                retirementNoticeResult = result;
            } else if (!retirementNoticeResult.equals(result)) {
                throw new IllegalStateException("retirement notice result changed");
            }
        }

        private synchronized void recordLocalCancel(String status, String sendResult, boolean providerCancel) {
            if (this.providerCancel != null && this.providerCancel != providerCancel) {
                throw new IllegalStateException("provider cancellation evidence changed");
            }
            this.providerCancel = providerCancel;
            String current = localCancelStatus;
            boolean initialEvidence = "NOT_ATTEMPTED".equals(current);
            boolean queuedResolution = "QUEUED".equals(current) &&
                    ("SUBMITTED".equals(status) || "UNKNOWN".equals(status));
            boolean callbackBeforeQueueResult = initialEvidence &&
                    ("SUBMITTED".equals(status) || "UNKNOWN".equals(status));
            boolean queueResultAfterCallback = "QUEUED".equals(status) &&
                    ("SUBMITTED".equals(current) || "UNKNOWN".equals(current));
            if (!current.equals(status) && !initialEvidence && !queuedResolution && !callbackBeforeQueueResult &&
                    !queueResultAfterCallback) {
                throw new IllegalStateException("local cancellation status changed");
            }
            if (!queueResultAfterCallback) localCancelStatus = status;
            if (sendResult != null) {
                if (localCancelSendResult != null && !localCancelSendResult.equals(sendResult)) {
                    throw new IllegalStateException("local cancellation send result changed");
                }
                localCancelSendResult = sendResult;
            }
        }

        private synchronized void recordLateOutputRejected() {
            if (lateOutputRejections < Long.MAX_VALUE) lateOutputRejections++;
        }

        private synchronized void appendJson(StringBuilder output) {
            output.append("{\"id\":"); appendJsonString(output, invocationId.toString());
            output.append(",\"t0Nanos\":");
            if (captureMissing()) output.append("null");
            else appendJsonLongString(output, capturedAtNanos);
            output.append(",\"receiptWallTimeUnixMillis\":");
            if ("MISSING_PACKET_RECEIPT".equals(captureProvenance)) output.append("null");
            else output.append(capturedAtUnixMillis);
            output.append(",\"handlerEnteredAtNanos\":"); appendJsonLongString(output, handlerEnteredAtNanos);
            output.append(",\"handlerDelayNanos\":");
            if (captureMissing()) output.append("null");
            else appendJsonLongString(output, Math.max(0, handlerEnteredAtNanos - capturedAtNanos));
             output.append(",\"admittedAtNanos\":"); appendJsonLongString(output, admittedAtNanos);
             output.append(",\"decisionAtNanos\":"); appendNullableJsonLongString(output, decisionAtNanos);
             output.append(",\"decisionRecorded\":").append(decisionRecorded);
             output.append(",\"admissionDelayNanos\":");
            if (captureMissing()) output.append("null");
            else appendJsonLongString(output, Math.max(0, admittedAtNanos - capturedAtNanos));
             output.append(",\"requestLinkAtNanos\":"); appendNullableJsonLongString(output, requestLinkAtNanos);
             output.append(",\"queuedAtNanos\":"); appendNullableJsonLongString(output, queuedAtNanos);
            output.append(",\"queueDelayNanos\":");
            if (queuedAtNanos == null || captureMissing()) output.append("null");
            else appendJsonLongString(output, Math.max(0, queuedAtNanos - capturedAtNanos));
             output.append(",\"fallbackCutoffAtNanos\":");
            if (captureMissing()) output.append("null");
            else appendJsonLongString(output, capturedAtNanos + TimeUnit.MILLISECONDS.toNanos(750));
             output.append(",\"deadlineAtNanos\":");
            if (captureMissing()) output.append("null");
             else appendJsonLongString(output, capturedAtNanos + TimeUnit.SECONDS.toNanos(1));
             output.append(",\"executionBudgetSeconds\":").append(executionBudgetSeconds == null ? "null" : executionBudgetSeconds);
             output.append(",\"pendingDeadlineAtNanos\":"); appendNullableJsonLongString(output, pendingDeadlineAtNanos);
             output.append(",\"activeDeadlineAtNanos\":"); appendNullableJsonLongString(output, activeDeadlineAtNanos);
             output.append(",\"absoluteExecutionDeadlineAtNanos\":"); appendNullableJsonLongString(output, absoluteExecutionDeadlineAtNanos);
             output.append(",\"progressIntervalNanos\":"); appendJsonLongString(output, TimeUnit.SECONDS.toNanos(10));
             output.append(",\"progressNoticeLimit\":3");
            output.append(",\"captureProvenance\":"); appendJsonString(output, captureProvenance);
            output.append(",\"producer\":"); appendNullableJsonString(output, producer);
            output.append(",\"connectionGeneration\":").append(connectionGeneration);
            output.append(",\"player\":"); appendNullableJsonString(output, playerId == null ? null : playerId.toString());
            output.append(",\"actualWorld\":"); appendNullableJsonString(output, actualWorldId);
            output.append(",\"serverBinding\":"); appendNullableJsonString(output, serverBinding);
            output.append(",\"worldBinding\":"); appendNullableJsonString(output, worldBinding);
            output.append(",\"characterBinding\":"); appendNullableJsonString(output, characterBinding);
             output.append(",\"origin\":"); appendJsonString(output, origin);
             output.append(",\"outcome\":"); appendJsonString(output, outcome);
             output.append(",\"rateLimitStatus\":"); appendJsonString(output, rateLimitStatus);
              output.append(",\"noticeStatus\":"); appendJsonString(output, effectiveNoticeStatus());
            output.append(",\"templateRevision\":"); appendNullableJsonString(output, templateRevision);
            output.append(",\"requestId\":"); appendNullableJsonString(output, requestId);
            output.append(",\"queueDepthAtAdmission\":").append(queueDepthAtAdmission);
            output.append(",\"playerQueueDepthAtAdmission\":").append(playerQueueDepthAtAdmission);
            output.append(",\"forwardedAtNanos\":"); appendNullableJsonLongString(output, forwardedAtNanos);
            output.append(",\"pendingResidenceNanos\":"); appendNullableJsonLongString(output, pendingResidenceNanos);
            output.append(",\"globalPendingAtForward\":").append(globalPendingAtForward);
            output.append(",\"playerPendingAtForward\":").append(playerPendingAtForward);
            output.append(",\"firstDispatchNanos\":"); appendJsonLongString(output, firstDispatchNanos);
            output.append(",\"firstDispatchAtNanos\":"); appendNullableJsonLongString(output, firstDispatchAtNanos);
            output.append(",\"queueAgeAtFirstDispatchNanos\":");
            if (firstDispatchAtNanos == null || queuedAtNanos == null) output.append("null");
            else appendJsonLongString(output, Math.max(0, firstDispatchAtNanos - queuedAtNanos));
             output.append(",\"publicationId\":"); appendNullableJsonString(output, publicationId);
             FirstReplyScheduler.NoticePublication linkedNotice = noticePublication;
             FirstReplyScheduler.NoticeResult linkedNoticeResult = linkedNotice == null ? null : linkedNotice.result();
             output.append(",\"noticeBatchPublicationId\":"); appendNullableJsonString(output,
                     linkedNotice == null ? null : linkedNotice.publicationId());
             output.append(",\"noticeBatchResult\":");
             appendJsonString(output, linkedNoticeResult == null ? "NOT_BATCHED" :
                     linkedNoticeResult.sendResult() == null ? linkedNoticeResult.state().name() :
                             linkedNoticeResult.sendResult().name());
             output.append(",\"noticeBatchAttemptedAtNanos\":");
             appendNullableJsonLongString(output, linkedNoticeResult == null ? null : linkedNoticeResult.attemptedAtNanos());
             output.append(",\"noticeBatchLatencyNanos\":");
             if (linkedNoticeResult == null || linkedNoticeResult.attemptedAtNanos() == null) output.append("null");
             else appendJsonLongString(output, Math.max(0, linkedNoticeResult.attemptedAtNanos() - capturedAtNanos));
             output.append(",\"firstDispatchSource\":"); appendNullableJsonString(output, firstDispatchSource);
            output.append(",\"firstDispatchDisposition\":"); appendNullableJsonString(output, firstDispatchDisposition);
            output.append(",\"firstReplyResult\":"); appendJsonString(output, firstReplyResult == null ? "PENDING" : firstReplyResult);
            output.append(",\"sendResult\":"); appendNullableJsonString(output, sendResult);
            output.append(",\"deadlineSettledAtNanos\":"); appendNullableJsonLongString(output, deadlineSettledAtNanos);
            output.append(",\"failedDispatches\":").append(failedDispatches.get());
            output.append(",\"unknownDispatches\":").append(unknownDispatches.get());
            output.append(",\"progress\":[");
             for (int index = 0; index < progressCount; index++) {
                 if (index > 0) output.append(',');
                 ProgressEvidence item = progress[index];
                  output.append("{\"phase\":"); appendJsonString(output, item.phase);
                  output.append(",\"state\":"); appendJsonString(output, item.state);
                  output.append(",\"status\":"); appendJsonString(output, item.status);
                  output.append(",\"noticeId\":"); appendNullableJsonString(output, item.noticeId);
                  output.append(",\"publicSendResult\":"); appendNullableJsonString(output, item.publicSendResult);
                 output.append(",\"publicDispatchAtNanos\":"); appendNullableJsonLongString(output, item.publicDispatchAtNanos);
                 output.append(",\"contextSendResult\":"); appendNullableJsonString(output, item.contextSendResult);
                 output.append(",\"contextCompletedAtNanos\":"); appendNullableJsonLongString(output, item.contextCompletedAtNanos);
                 output.append(",\"latencyNanos\":"); appendJsonLongString(output, item.latencyNanos); output.append('}');
             }
              output.append("],\"terminalOutcome\":"); appendNullableJsonString(output, terminalOutcome);
              output.append(",\"terminalCause\":"); appendNullableJsonString(output, terminalCause);
              output.append(",\"terminalDeliveryResult\":"); appendNullableJsonString(output, terminalDeliveryResult);
              output.append(",\"retirementNoticeResult\":"); appendNullableJsonString(output, retirementNoticeResult);
              output.append(",\"localCancelStatus\":"); appendJsonString(output, localCancelStatus);
              output.append(",\"localCancelSendResult\":"); appendNullableJsonString(output, localCancelSendResult);
              output.append(",\"providerCancel\":");
              if (providerCancel == null) output.append("null"); else output.append(providerCancel);
              output.append(",\"lateOutputRejections\":").append(lateOutputRejections);
              output.append(",\"terminalAtNanos\":"); appendNullableJsonLongString(output, terminalAtNanos);
             output.append(",\"terminalNanos\":"); appendJsonLongString(output, terminalNanos); output.append('}');
        }
    }

    private static final class ProgressEvidence {
        private final String phase;
        private final String state;
        private final String status;
        private final long latencyNanos;
        private final String publicSendResult;
        private final Long publicDispatchAtNanos;
        private final String noticeId;
        private volatile String contextSendResult;
        private volatile Long contextCompletedAtNanos;

        private ProgressEvidence(String phase, String state, String status, long latencyNanos,
                                 String publicSendResult, Long publicDispatchAtNanos,
                                 String noticeId, String contextSendResult, Long contextCompletedAtNanos) {
            this.phase = phase;
            this.state = state;
            this.status = status;
            this.latencyNanos = latencyNanos;
            this.publicSendResult = publicSendResult;
            this.publicDispatchAtNanos = publicDispatchAtNanos;
            this.noticeId = noticeId;
            this.contextSendResult = contextSendResult;
            this.contextCompletedAtNanos = contextCompletedAtNanos;
        }
    }

    private static final class ConversationProgressLimit {
        private static final int VALUE = 3;
    }
}
