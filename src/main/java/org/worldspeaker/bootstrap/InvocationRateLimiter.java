package org.worldspeaker.bootstrap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.worldspeaker.runtime.TickMetrics;

/** Shared, monotonic server-epoch limits for authenticated interaction subjects. */
public final class InvocationRateLimiter {
    public static final int MAX_SUBJECTS = 256;
    public static final int INVOCATION_BURST = 8;
    public static final int PLAYER_WORK_BURST = 2;
    public static final int GLOBAL_WORK_BURST = 16;
    public static final long SUBJECT_IDLE_NANOS = TimeUnit.SECONDS.toNanos(60);
    public static final long NOTICE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    public static final int MAX_PENDING_NOTICE_MEMBERS = TickMetrics.MAX_INTERACTION_RECORDS;

    private final Map<UUID, Subject> subjects = new HashMap<>();
    private Bucket globalWork;
    private int pendingNoticeMembers;

    /** Charges every recognized authenticated invocation, whether or not it asks for model work. */
    public synchronized Charge chargeInvocation(UUID playerId, long nowNanos) {
        if (playerId == null) return Charge.SUBJECT_CAPACITY;
        expireIdleSubjects(nowNanos);
        Subject subject = subjects.get(playerId);
        if (subject == null) {
            if (subjects.size() >= MAX_SUBJECTS) return Charge.SUBJECT_CAPACITY;
            subject = new Subject(nowNanos);
            subjects.put(playerId, subject);
        }
        subject.lastTouchedNanos = nowNanos;
        subject.invocations.refill(nowNanos);
        if (subject.invocations.tokens < 1.0) return Charge.LIMITED;
        subject.invocations.tokens -= 1.0;
        return Charge.ALLOWED;
    }

    /** Debits both new-work buckets atomically; callers invoke this only at the commit boundary. */
    public synchronized Charge commitNewWork(UUID playerId, long nowNanos) {
        return commitNewWork(playerId, nowNanos, () -> true, () -> {});
    }

    /**
     * Commits rate tokens, evidence admission, and the caller's bounded admission state as one boundary.
     * The callbacks run only after both token buckets are known to have capacity.
     */
    public synchronized Charge commitNewWork(UUID playerId, long nowNanos,
                                             BooleanSupplier commitEvidence, Runnable commitAdmissionState) {
        if (playerId == null) return Charge.SUBJECT_CAPACITY;
        Objects.requireNonNull(commitEvidence, "commitEvidence");
        Objects.requireNonNull(commitAdmissionState, "commitAdmissionState");
        expireIdleSubjects(nowNanos);
        Subject subject = subjects.get(playerId);
        if (subject == null) {
            if (subjects.size() >= MAX_SUBJECTS) return Charge.SUBJECT_CAPACITY;
            subject = new Subject(nowNanos);
            subjects.put(playerId, subject);
        }
        if (globalWork == null) globalWork = new Bucket(GLOBAL_WORK_BURST, nowNanos);
        globalWork.refill(nowNanos);
        subject.newWork.refill(nowNanos);
        subject.lastTouchedNanos = nowNanos;
        if (globalWork.tokens < 1.0 || subject.newWork.tokens < 1.0) return Charge.LIMITED;
        if (!commitEvidence.getAsBoolean()) return Charge.EVIDENCE_UNAVAILABLE;
        globalWork.tokens -= 1.0;
        subject.newWork.tokens -= 1.0;
        commitAdmissionState.run();
        return Charge.ALLOWED;
    }

    /**
     * Reserves one row in the sender's bounded safe-cause batch, or opens its only pending batch.
     * The caller queues one callback for CREATED and links every member to that batch's publication.
     */
    public synchronized NoticeBatchReservation reserveNoticeBatch(UUID playerId, long nowNanos,
                                                                     Object compatibilityKey,
                                                                     long originalDeadlineNanos) {
        return reserveNoticeBatch(playerId, nowNanos, compatibilityKey, originalDeadlineNanos,
                NoticeCause.GENERIC, false);
    }

    /** Reserves bounded safe content for this recipient, deferring a new batch through send cooldown. */
    public synchronized NoticeBatchReservation reserveNoticeBatch(UUID playerId, long nowNanos,
                                                                     Object compatibilityKey,
                                                                     long originalDeadlineNanos,
                                                                     NoticeCause cause,
                                                                     boolean requireCurrentGrant) {
        return reserveNoticeBatch(playerId, nowNanos, compatibilityKey, originalDeadlineNanos,
                cause, requireCurrentGrant, false);
    }

    /** Adds the invocation throttle as a fixed safe cause without changing its underlying disposition. */
    public synchronized NoticeBatchReservation reserveNoticeBatch(UUID playerId, long nowNanos,
                                                                     Object compatibilityKey,
                                                                     long originalDeadlineNanos,
                                                                     NoticeCause cause,
                                                                     boolean requireCurrentGrant,
                                                                     boolean invocationWasThrottled) {
        if (playerId == null || compatibilityKey == null || cause == null) {
            return NoticeBatchReservation.incompatible();
        }
        long causeMask = noticeCauseMask(cause, invocationWasThrottled);
        expireIdleSubjects(nowNanos);
        Subject subject = subjects.get(playerId);
        if (subject == null) return NoticeBatchReservation.incompatible();
        NoticeBatch current = subject.noticeBatch;
        if (current != null) {
            if (current.state != BatchState.PENDING || !Objects.equals(current.compatibilityKey, compatibilityKey)) {
                return NoticeBatchReservation.incompatible();
            }
            if (wouldMissCooldownDeadline(originalDeadlineNanos, current.notBeforeNanos, nowNanos)) {
                return NoticeBatchReservation.missed();
            }
            if (pendingNoticeMembers >= MAX_PENDING_NOTICE_MEMBERS) {
                return NoticeBatchReservation.capacity();
            }
            pendingNoticeMembers++;
            current.memberCount++;
            boolean earlier = deadlineBefore(originalDeadlineNanos, current.earliestDeadlineNanos);
            if (earlier) current.earliestDeadlineNanos = originalDeadlineNanos;
            current.causeMask |= causeMask;
            current.requireCurrentGrant |= requireCurrentGrant;
            subject.lastTouchedNanos = nowNanos;
            return NoticeBatchReservation.joined(current, earlier);
        }
        if (pendingNoticeMembers >= MAX_PENDING_NOTICE_MEMBERS) return NoticeBatchReservation.capacity();
        long notBeforeNanos = nextNoticeAt(subject, nowNanos);
        if (wouldMissCooldownDeadline(originalDeadlineNanos, notBeforeNanos, nowNanos)) {
            return NoticeBatchReservation.missed();
        }
        NoticeBatch created = new NoticeBatch(this, playerId, subject, compatibilityKey,
                originalDeadlineNanos, notBeforeNanos, causeMask, requireCurrentGrant);
        created.memberCount = 1;
        subject.noticeBatch = created;
        subject.lastTouchedNanos = nowNanos;
        pendingNoticeMembers++;
        return NoticeBatchReservation.created(created);
    }

    private static long noticeCauseMask(NoticeCause cause, boolean invocationWasThrottled) {
        if (!invocationWasThrottled || cause == NoticeCause.RATE_LIMITED ||
                cause == NoticeCause.GUIDANCE_RATE_LIMITED) {
            return cause.mask();
        }
        if (cause == NoticeCause.GUIDANCE) return NoticeCause.GUIDANCE_RATE_LIMITED.mask();
        return cause.mask() | NoticeCause.RATE_LIMITED.mask();
    }

    /** Rolls back only a not-yet-dispatched batch whose scheduler insertion failed. */
    public synchronized boolean cancelNoticeBatch(NoticeBatch batch) {
        if (!ownsPendingBatch(batch) || batch.state != BatchState.PENDING) return false;
        batch.state = BatchState.CANCELLED;
        batch.publication.cancel();
        batch.subject.noticeBatch = null;
        pendingNoticeMembers -= batch.memberCount;
        batch.memberCount = 0;
        return true;
    }

    private synchronized boolean releaseUnlinkedMember(NoticeBatch batch) {
        if (!ownsPendingBatch(batch) || batch.state != BatchState.PENDING || batch.memberCount == 0) return false;
        batch.memberCount--;
        pendingNoticeMembers--;
        if (batch.memberCount == 0) {
            batch.state = BatchState.CANCELLED;
            batch.publication.cancel();
            batch.subject.noticeBatch = null;
        }
        return true;
    }

    /** Completes the one physical publication and starts cooldown only if its port was invoked. */
    public synchronized boolean completeNoticeBatch(NoticeBatch batch, FirstReplyScheduler.PublicationResult result,
                                                      long attemptedAtNanos, boolean attempted) {
        if (!ownsPendingBatch(batch) || batch.state != BatchState.DISPATCHING) return false;
        if (!batch.publication.complete(result, attemptedAtNanos)) return false;
        batch.state = BatchState.COMPLETE;
        batch.subject.noticeBatch = null;
        pendingNoticeMembers -= batch.memberCount;
        batch.memberCount = 0;
        if (attempted) {
            batch.subject.lastNoticeAttemptNanos = attemptedAtNanos;
            batch.subject.hasNoticeAttempt = true;
            batch.subject.lastTouchedNanos = attemptedAtNanos;
        }
        return true;
    }

    private synchronized NoticeBatch.DispatchContent beginNoticeBatch(NoticeBatch batch) {
        if (!ownsPendingBatch(batch) || batch.state != BatchState.PENDING || !batch.publication.claim()) return null;
        batch.state = BatchState.DISPATCHING;
        return new NoticeBatch.DispatchContent(batch.causeMask, batch.requireCurrentGrant);
    }

    private static long nextNoticeAt(Subject subject, long nowNanos) {
        if (!subject.hasNoticeAttempt) return nowNanos;
        long elapsed = nowNanos - subject.lastNoticeAttemptNanos;
        return elapsed >= NOTICE_INTERVAL_NANOS ? nowNanos : subject.lastNoticeAttemptNanos + NOTICE_INTERVAL_NANOS;
    }

    private static boolean wouldMissCooldownDeadline(long deadlineNanos, long notBeforeNanos, long nowNanos) {
        return nowNanos - notBeforeNanos < 0 && deadlineBefore(deadlineNanos, notBeforeNanos);
    }

    private boolean ownsPendingBatch(NoticeBatch batch) {
        return batch != null && subjects.get(batch.playerId) == batch.subject && batch.subject.noticeBatch == batch;
    }

    private static boolean deadlineBefore(long candidate, long current) {
        return candidate - current < 0;
    }

    public synchronized int trackedSubjects() {
        return subjects.size();
    }

    public synchronized int pendingNoticeMembers() { return pendingNoticeMembers; }

    private void expireIdleSubjects(long nowNanos) {
        Iterator<Map.Entry<UUID, Subject>> iterator = subjects.entrySet().iterator();
        while (iterator.hasNext()) {
            Subject subject = iterator.next().getValue();
            subject.invocations.refill(nowNanos);
            subject.newWork.refill(nowNanos);
            long idle = nowNanos - subject.lastTouchedNanos;
            if (subject.noticeBatch == null && idle >= SUBJECT_IDLE_NANOS &&
                    subject.invocations.tokens >= INVOCATION_BURST && subject.newWork.tokens >= PLAYER_WORK_BURST) {
                iterator.remove();
            }
        }
    }

    public enum Charge { ALLOWED, LIMITED, SUBJECT_CAPACITY, EVIDENCE_UNAVAILABLE }

    private static final class Subject {
        private final Bucket invocations = new Bucket(INVOCATION_BURST, 0, 2.0 / TimeUnit.SECONDS.toNanos(1));
        private final Bucket newWork = new Bucket(PLAYER_WORK_BURST, 0, 1.0 / TimeUnit.SECONDS.toNanos(3));
        private long lastTouchedNanos;
        private long lastNoticeAttemptNanos;
        private boolean hasNoticeAttempt;
        private NoticeBatch noticeBatch;

        private Subject(long nowNanos) {
            invocations.tokens = INVOCATION_BURST;
            invocations.lastRefillNanos = nowNanos;
            newWork.tokens = PLAYER_WORK_BURST;
            newWork.lastRefillNanos = nowNanos;
            lastTouchedNanos = nowNanos;
        }
    }

    private static final class Bucket {
        private final int capacity;
        private final double refillPerNano;
        private double tokens;
        private long lastRefillNanos;

        private Bucket(int capacity, long nowNanos) {
            this(capacity, nowNanos, 4.0 / TimeUnit.SECONDS.toNanos(1));
            tokens = capacity;
        }

        private Bucket(int capacity, long nowNanos, double refillPerNano) {
            this.capacity = capacity;
            this.refillPerNano = refillPerNano;
            this.tokens = capacity;
            this.lastRefillNanos = nowNanos;
        }

        private void refill(long nowNanos) {
            long elapsed = nowNanos - lastRefillNanos;
            if (elapsed <= 0) return;
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            lastRefillNanos = nowNanos;
        }
    }

    public enum NoticeBatchStatus { CREATED, JOINED, SUPPRESSED, INCOMPATIBLE, MISSED, CAPACITY }

    /** Fixed categories only; the mask cannot grow with invocation text or batch membership. */
    public enum NoticeCause {
        HELP,
        DENIED,
        UNAVAILABLE,
        RATE_LIMITED,
        BUSY,
        COALESCED,
        GUIDANCE,
        GUIDANCE_RATE_LIMITED,
        FOLLOWUP_ENDED,
        EXPIRED,
        UNAUTHENTICATED,
        GENERIC;

        private long mask() { return 1L << ordinal(); }
    }

    public static final class NoticeBatchReservation {
        private final NoticeBatchStatus status;
        private final NoticeBatch batch;
        private final boolean deadlineMovedEarlier;

        private NoticeBatchReservation(NoticeBatchStatus status, NoticeBatch batch, boolean deadlineMovedEarlier) {
            this.status = status;
            this.batch = batch;
            this.deadlineMovedEarlier = deadlineMovedEarlier;
        }

        private static NoticeBatchReservation created(NoticeBatch batch) {
            return new NoticeBatchReservation(NoticeBatchStatus.CREATED, batch, false);
        }

        private static NoticeBatchReservation joined(NoticeBatch batch, boolean earlier) {
            return new NoticeBatchReservation(NoticeBatchStatus.JOINED, batch, earlier);
        }

        private static NoticeBatchReservation incompatible() {
            return new NoticeBatchReservation(NoticeBatchStatus.INCOMPATIBLE, null, false);
        }

        private static NoticeBatchReservation missed() {
            return new NoticeBatchReservation(NoticeBatchStatus.MISSED, null, false);
        }

        private static NoticeBatchReservation capacity() {
            return new NoticeBatchReservation(NoticeBatchStatus.CAPACITY, null, false);
        }

        public NoticeBatchStatus status() { return status; }
        public NoticeBatch batch() { return batch; }
        public boolean deadlineMovedEarlier() { return deadlineMovedEarlier; }
    }

    public static final class NoticeBatch {
        private final InvocationRateLimiter owner;
        private final UUID playerId;
        private final Subject subject;
        private final Object compatibilityKey;
        private final FirstReplyScheduler.NoticePublication publication = new FirstReplyScheduler.NoticePublication();
        private long earliestDeadlineNanos;
        private final long notBeforeNanos;
        private long causeMask;
        private boolean requireCurrentGrant;
        private int memberCount;
        private BatchState state = BatchState.PENDING;
        private Runnable queuedCallback;

        private NoticeBatch(InvocationRateLimiter owner, UUID playerId, Subject subject, Object compatibilityKey,
                            long earliestDeadlineNanos, long notBeforeNanos, long causeMask,
                            boolean requireCurrentGrant) {
            this.owner = owner;
            this.playerId = playerId;
            this.subject = subject;
            this.compatibilityKey = compatibilityKey;
            this.earliestDeadlineNanos = earliestDeadlineNanos;
            this.notBeforeNanos = notBeforeNanos;
            this.causeMask = causeMask;
            this.requireCurrentGrant = requireCurrentGrant;
        }

        public FirstReplyScheduler.NoticePublication publication() { return publication; }
        public long earliestDeadlineNanos() { return earliestDeadlineNanos; }
        public long notBeforeNanos() { return notBeforeNanos; }
        public int memberCount() { return memberCount; }
        Runnable queuedCallback() { return queuedCallback; }

        public boolean beginDispatch() { return owner.beginNoticeBatch(this) != null; }

        DispatchContent claimDispatchContent() { return owner.beginNoticeBatch(this); }

        public boolean complete(FirstReplyScheduler.PublicationResult result, long attemptedAtNanos, boolean attempted) {
            return owner.completeNoticeBatch(this, result, attemptedAtNanos, attempted);
        }

        public boolean cancelBeforeDispatch() { return owner.cancelNoticeBatch(this); }
        boolean releaseUnlinkedMember() { return owner.releaseUnlinkedMember(this); }

        record DispatchContent(long causeMask, boolean requireCurrentGrant) {}

        void queuedCallback(Runnable callback) {
            synchronized (owner) {
                if (state != BatchState.PENDING || queuedCallback != null) {
                    throw new IllegalStateException("notice batch callback is already fixed");
                }
                queuedCallback = Objects.requireNonNull(callback, "callback");
            }
        }
    }

    private enum BatchState { PENDING, DISPATCHING, COMPLETE, CANCELLED }
}
