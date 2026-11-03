package org.worldspeaker.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Pure monotonic scheduling state for the finite progress notices owned by one request. */
public final class ConversationProgress {
    public static final long INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    public static final int MAX_NOTICES = 3;

    private final long capturedAtNanos;
    private boolean queuedAttempted;
    private long forwardedAtNanos = Long.MIN_VALUE;
    private int awaitingAnswerAttempts;
    private final ArrayList<DialogueProgress> dispatched = new ArrayList<>(MAX_NOTICES);

    public ConversationProgress(long capturedAtNanos) {
        this.capturedAtNanos = capturedAtNanos;
    }

    /** Returns only the next due notice; its caller must consume it before starting work. */
    public Phase duePhase(long nowNanos, boolean pending, boolean active,
                          boolean firstReplyDispatched, boolean terminalReady) {
        if (!firstReplyDispatched || terminalReady) return null;
        if (pending && !queuedAttempted && due(nowNanos, capturedAtNanos + INTERVAL_NANOS)) return Phase.QUEUED;
        if (active && forwardedAtNanos != Long.MIN_VALUE && awaitingAnswerAttempts < 2 &&
                due(nowNanos, forwardedAtNanos + (long) (awaitingAnswerAttempts + 1) * INTERVAL_NANOS)) {
            return Phase.AWAITING_ANSWER;
        }
        return null;
    }

    /** Earliest lifecycle deadline that may create progress, or Long.MAX_VALUE when ineligible. */
    public long nextDeadline(boolean pending, boolean active, boolean firstReplyDispatched) {
        if (!firstReplyDispatched) return Long.MAX_VALUE;
        if (pending && !queuedAttempted) return capturedAtNanos + INTERVAL_NANOS;
        if (active && forwardedAtNanos != Long.MIN_VALUE && awaitingAnswerAttempts < 2) {
            return forwardedAtNanos + (long) (awaitingAnswerAttempts + 1) * INTERVAL_NANOS;
        }
        return Long.MAX_VALUE;
    }

    /** A due notice is consumed even when local publication fails; retrying could duplicate it. */
    public boolean markAttempted(Phase phase) {
        if (phase == null) return false;
        if (phase == Phase.QUEUED) {
            if (queuedAttempted || dispatched.size() >= MAX_NOTICES) return false;
            queuedAttempted = true;
            return true;
        }
        if (forwardedAtNanos == Long.MIN_VALUE || awaitingAnswerAttempts >= 2 || dispatched.size() >= MAX_NOTICES) return false;
        awaitingAnswerAttempts++;
        return true;
    }

    /** Called only after the request frame was accepted by the bounded outbound queue. */
    public boolean markForwarded(long nowNanos, boolean firstReplyDispatched) {
        if (forwardedAtNanos != Long.MIN_VALUE) return false;
        forwardedAtNanos = nowNanos;
        // A queued message is no longer truthful after forwarding. If its deadline
        // passed while the request was still pending, record it as missed, never replay it.
        if (firstReplyDispatched && !queuedAttempted && due(nowNanos, capturedAtNanos + INTERVAL_NANOS)) {
            queuedAttempted = true;
            return true;
        }
        return false;
    }

    /** Production transition: an overdue queued opportunity is recorded only when it was eligible. */
    public boolean markForwarded(long nowNanos, boolean firstReplyDispatched,
                                 Consumer<Phase> missedRecorder) {
        Objects.requireNonNull(missedRecorder, "missedRecorder");
        boolean missed = markForwarded(nowNanos, firstReplyDispatched);
        if (missed) missedRecorder.accept(Phase.QUEUED);
        return missed;
    }

    public boolean recordDispatched(DialogueProgress progress) {
        if (progress == null || dispatched.size() >= MAX_NOTICES ||
                (progress.phase() == Phase.QUEUED && (!queuedAttempted || dispatched.stream().anyMatch(p -> p.phase() == Phase.QUEUED))) ||
                (progress.phase() == Phase.AWAITING_ANSWER && dispatched.stream().filter(p -> p.phase() == Phase.AWAITING_ANSWER).count() >= 2)) {
            return false;
        }
        if (dispatched.stream().anyMatch(existing -> existing.messageId().equals(progress.messageId()))) return false;
        dispatched.add(progress);
        return true;
    }

    /** Marks already-due opportunities as missed when the request can no longer emit them. */
    public List<Phase> markDueMissed(long nowNanos, boolean firstReplyDispatched, boolean pending, boolean active) {
        ArrayList<Phase> missed = new ArrayList<>(MAX_NOTICES);
        if (!firstReplyDispatched) return missed;
        if (pending && !queuedAttempted && due(nowNanos, capturedAtNanos + INTERVAL_NANOS)) {
            queuedAttempted = true;
            missed.add(Phase.QUEUED);
        }
        if (active && forwardedAtNanos != Long.MIN_VALUE) {
            while (awaitingAnswerAttempts < 2 && due(nowNanos,
                    forwardedAtNanos + (long) (awaitingAnswerAttempts + 1) * INTERVAL_NANOS)) {
                awaitingAnswerAttempts++;
                missed.add(Phase.AWAITING_ANSWER);
            }
        }
        return List.copyOf(missed);
    }

    /** Production transition: records each opportunity consumed by retirement exactly once. */
    public void markDueMissed(long nowNanos, boolean firstReplyDispatched, boolean pending, boolean active,
                              Consumer<Phase> missedRecorder) {
        Objects.requireNonNull(missedRecorder, "missedRecorder");
        for (Phase phase : markDueMissed(nowNanos, firstReplyDispatched, pending, active)) {
            missedRecorder.accept(phase);
        }
    }

    public List<DialogueProgress> dispatched() { return List.copyOf(dispatched); }
    public long capturedAtNanos() { return capturedAtNanos; }
    public long forwardedAtNanos() { return forwardedAtNanos; }
    public int attemptedCount() { return (queuedAttempted ? 1 : 0) + awaitingAnswerAttempts; }

    private static boolean due(long nowNanos, long deadlineNanos) {
        return nowNanos - deadlineNanos >= 0;
    }

    public enum Phase { QUEUED, AWAITING_ANSWER }

    /** Wire-safe dispatched progress record. The text is the exact rendered public line. */
    public record DialogueProgress(String messageId, String text, long dispatchedAtUnixMillis,
                                  long dispatchedAtMonotonicNanos, String audiencePlayerId, Phase phase) {
        public DialogueProgress {
            if (messageId == null || text == null || audiencePlayerId == null || phase == null ||
                    dispatchedAtUnixMillis < 0 || dispatchedAtUnixMillis > 9_007_199_254_740_991L) {
                throw new IllegalArgumentException("progress attribution and dispatch time are required");
            }
        }
    }
}
