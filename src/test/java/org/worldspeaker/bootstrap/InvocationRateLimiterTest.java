package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvocationRateLimiterTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    @Test
    void invocationBurstChargesEveryRecognizedInputAndRefillsAtTheExactBoundary() {
        InvocationRateLimiter limiter = new InvocationRateLimiter();
        UUID subject = UUID.randomUUID();

        for (int index = 0; index < InvocationRateLimiter.INVOCATION_BURST; index++) {
            assertEquals(InvocationRateLimiter.Charge.ALLOWED, limiter.chargeInvocation(subject, 0));
        }
        assertEquals(InvocationRateLimiter.Charge.LIMITED, limiter.chargeInvocation(subject, 0));
        assertEquals(InvocationRateLimiter.Charge.LIMITED,
                limiter.chargeInvocation(subject, SECOND / 2 - 1));
        assertEquals(InvocationRateLimiter.Charge.ALLOWED,
                limiter.chargeInvocation(subject, SECOND / 2));
    }

    @Test
    void playerAndGlobalNewWorkBucketsDebitAtomicallyWithoutCancellationRefunds() {
        InvocationRateLimiter limiter = new InvocationRateLimiter();
        UUID subject = UUID.randomUUID();
        assertEquals(InvocationRateLimiter.Charge.ALLOWED, limiter.commitNewWork(subject, 0));
        assertEquals(InvocationRateLimiter.Charge.ALLOWED, limiter.commitNewWork(subject, 0));
        assertEquals(InvocationRateLimiter.Charge.LIMITED, limiter.commitNewWork(subject, 0));
        assertEquals(InvocationRateLimiter.Charge.ALLOWED,
                limiter.commitNewWork(subject, TimeUnit.SECONDS.toNanos(3)));

        InvocationRateLimiter global = new InvocationRateLimiter();
        for (int index = 0; index < InvocationRateLimiter.GLOBAL_WORK_BURST; index++) {
            UUID player = UUID.randomUUID();
            assertEquals(InvocationRateLimiter.Charge.ALLOWED, global.commitNewWork(player, 0));
        }
        UUID last = UUID.randomUUID();
        assertEquals(InvocationRateLimiter.Charge.LIMITED, global.commitNewWork(last, 0));
        assertEquals(InvocationRateLimiter.Charge.ALLOWED,
                global.commitNewWork(last, SECOND / 4));
    }

    @Test
    void subjectTableIsBoundedAndOnlyFullyRefilledIdleSubjectsExpire() {
        InvocationRateLimiter limiter = new InvocationRateLimiter();
        for (int index = 0; index < InvocationRateLimiter.MAX_SUBJECTS; index++) {
            assertEquals(InvocationRateLimiter.Charge.ALLOWED,
                    limiter.chargeInvocation(new UUID(0, index + 1), 0));
        }
        assertEquals(InvocationRateLimiter.MAX_SUBJECTS, limiter.trackedSubjects());
        assertEquals(InvocationRateLimiter.Charge.SUBJECT_CAPACITY,
                limiter.chargeInvocation(new UUID(1, 1), 0));

        assertEquals(InvocationRateLimiter.Charge.ALLOWED,
                limiter.chargeInvocation(new UUID(1, 1), InvocationRateLimiter.SUBJECT_IDLE_NANOS));
        assertEquals(1, limiter.trackedSubjects(),
                "the idle fully-refilled table is reclaimed before admitting its replacement");
    }

    @Test
    void compatibleNoticeBatchRetainsRowsAndStartsCooldownAtItsActualSendBoundary() {
        InvocationRateLimiter limiter = new InvocationRateLimiter();
        UUID subject = UUID.randomUUID();
        limiter.chargeInvocation(subject, 0);

        var first = limiter.reserveNoticeBatch(subject, 0, "same-safe-phrase", SECOND);
        assertEquals(InvocationRateLimiter.NoticeBatchStatus.CREATED, first.status());
        var joined = limiter.reserveNoticeBatch(subject, 10, "same-safe-phrase", SECOND + 10);
        assertEquals(InvocationRateLimiter.NoticeBatchStatus.JOINED, joined.status());
        assertEquals(first.batch(), joined.batch());
        assertEquals(2, joined.batch().memberCount());
        assertEquals(2, limiter.pendingNoticeMembers());
        assertEquals(InvocationRateLimiter.NoticeBatchStatus.INCOMPATIBLE,
                limiter.reserveNoticeBatch(subject, 20, "different-cause", SECOND + 20).status());

        assertTrue(joined.batch().beginDispatch());
        assertTrue(joined.batch().complete(FirstReplyScheduler.PublicationResult.SUBMITTED, 500, true));
        assertEquals(0, limiter.pendingNoticeMembers());
        var deferred = limiter.reserveNoticeBatch(subject, SECOND + 500 - 1,
                "same-safe-phrase", 2 * SECOND);
        assertEquals(InvocationRateLimiter.NoticeBatchStatus.CREATED, deferred.status());
        assertEquals(SECOND + 500, deferred.batch().notBeforeNanos(),
                "cooldown creates a future batch without changing the original deadline");
        assertTrue(deferred.batch().cancelBeforeDispatch());
        var boundary = limiter.reserveNoticeBatch(subject, SECOND + 500,
                "same-safe-phrase", 2 * SECOND);
        assertEquals(InvocationRateLimiter.NoticeBatchStatus.CREATED, boundary.status());
        assertEquals(SECOND + 500, boundary.batch().notBeforeNanos());
    }

    @Test
    void noticeBatchEarliestDeadlineAndCooldownRemainCorrectAcrossNanoTimeRollover() {
        InvocationRateLimiter limiter = new InvocationRateLimiter();
        UUID subject = UUID.randomUUID();
        long firstCapture = Long.MAX_VALUE - 900_000_000L;
        long firstDeadline = firstCapture + SECOND;
        limiter.chargeInvocation(subject, firstCapture);
        var first = limiter.reserveNoticeBatch(subject, firstCapture, "same", firstDeadline);
        long laterNow = Long.MIN_VALUE + 200_000_000L;
        long earlierDeadline = Long.MIN_VALUE + 50_000_000L;
        var earlier = limiter.reserveNoticeBatch(subject, laterNow, "same", earlierDeadline);
        assertEquals(InvocationRateLimiter.NoticeBatchStatus.JOINED, earlier.status());
        assertTrue(earlier.deadlineMovedEarlier());
        assertEquals(earlierDeadline, earlier.batch().earliestDeadlineNanos());

        assertTrue(first.batch().beginDispatch());
        long sendAt = Long.MIN_VALUE + 250_000_000L;
        assertTrue(first.batch().complete(FirstReplyScheduler.PublicationResult.UNKNOWN, sendAt, true));
        long justBefore = sendAt + SECOND - 1;
        var deferred = limiter.reserveNoticeBatch(subject, justBefore, "same", justBefore + SECOND);
        assertEquals(InvocationRateLimiter.NoticeBatchStatus.CREATED, deferred.status());
        long exactBoundary = sendAt + SECOND;
        assertEquals(exactBoundary, deferred.batch().notBeforeNanos());
        assertTrue(deferred.batch().cancelBeforeDispatch());
        var boundary = limiter.reserveNoticeBatch(subject, exactBoundary, "same", exactBoundary + SECOND);
        assertEquals(InvocationRateLimiter.NoticeBatchStatus.CREATED, boundary.status());
        assertEquals(exactBoundary, boundary.batch().notBeforeNanos());
    }

    @Test
    void noticeBatchMembershipIsCappedByReservedEvidenceAndCancellationDoesNotSpendCooldown() {
        InvocationRateLimiter limiter = new InvocationRateLimiter();
        UUID subject = UUID.randomUUID();
        limiter.chargeInvocation(subject, 0);
        var first = limiter.reserveNoticeBatch(subject, 0, "same", SECOND);
        for (int index = 1; index < InvocationRateLimiter.MAX_PENDING_NOTICE_MEMBERS; index++) {
            assertEquals(InvocationRateLimiter.NoticeBatchStatus.JOINED,
                    limiter.reserveNoticeBatch(subject, 0, "same", SECOND).status());
        }
        assertEquals(InvocationRateLimiter.MAX_PENDING_NOTICE_MEMBERS, limiter.pendingNoticeMembers());
        assertEquals(InvocationRateLimiter.NoticeBatchStatus.CAPACITY,
                limiter.reserveNoticeBatch(subject, 0, "same", SECOND).status());
        assertTrue(first.batch().cancelBeforeDispatch());
        assertEquals(0, limiter.pendingNoticeMembers());
        assertEquals(FirstReplyScheduler.NoticeState.CANCELLED, first.batch().publication().result().state());
        assertEquals(InvocationRateLimiter.NoticeBatchStatus.CREATED,
                limiter.reserveNoticeBatch(subject, 0, "same", SECOND).status(),
                "a never-attempted cancelled batch does not start the one-second send cooldown");
    }

    @Test
    void impossibleCooldownDeadlineIsAnExplicitMissAndExactBoundaryRemainsSchedulable() {
        InvocationRateLimiter limiter = new InvocationRateLimiter();
        UUID subject = UUID.randomUUID();
        limiter.chargeInvocation(subject, 0);
        var first = limiter.reserveNoticeBatch(subject, 0, "recipient", SECOND);
        assertTrue(first.batch().beginDispatch());
        assertTrue(first.batch().complete(FirstReplyScheduler.PublicationResult.SUBMITTED, 0, true));

        assertEquals(InvocationRateLimiter.NoticeBatchStatus.MISSED,
                limiter.reserveNoticeBatch(subject, 10, "recipient", SECOND - 1).status());
        assertEquals(0, limiter.pendingNoticeMembers());

        var exactBoundary = limiter.reserveNoticeBatch(subject, 10, "recipient", SECOND);
        assertEquals(InvocationRateLimiter.NoticeBatchStatus.CREATED, exactBoundary.status());
        assertEquals(SECOND, exactBoundary.batch().notBeforeNanos());
    }

    @Test
    void queuedBatchCancellationRemovesItsSingleSchedulerCallback() {
        InvocationRateLimiter limiter = new InvocationRateLimiter();
        UUID subject = UUID.randomUUID();
        limiter.chargeInvocation(subject, 0);
        var reservation = limiter.reserveNoticeBatch(subject, 0, "safe", SECOND);
        Runnable callback = () -> {};
        reservation.batch().queuedCallback(callback);
        FirstReplyScheduler scheduler = new FirstReplyScheduler(() -> 0, () -> 0);
        assertTrue(scheduler.enqueueDisposition(reservation.batch().earliestDeadlineNanos(), callback));
        assertEquals(1, scheduler.pendingDispositionCount());
        assertTrue(scheduler.cancelDisposition(callback));
        assertTrue(reservation.batch().cancelBeforeDispatch());
        assertEquals(0, scheduler.pendingDispositionCount());
        assertEquals(0, limiter.pendingNoticeMembers());
    }

    @Test
    void signedNanoTimeWrapRefillsButAClockRegressionDoesNot() {
        InvocationRateLimiter regression = new InvocationRateLimiter();
        UUID player = UUID.randomUUID();
        for (int index = 0; index < InvocationRateLimiter.INVOCATION_BURST; index++) {
            regression.chargeInvocation(player, 100);
        }
        assertEquals(InvocationRateLimiter.Charge.LIMITED, regression.chargeInvocation(player, 50));

        InvocationRateLimiter wrapped = new InvocationRateLimiter();
        UUID wrappingPlayer = UUID.randomUUID();
        long beforeWrap = Long.MAX_VALUE - SECOND / 2;
        for (int index = 0; index < InvocationRateLimiter.INVOCATION_BURST; index++) {
            wrapped.chargeInvocation(wrappingPlayer, beforeWrap);
        }
        long afterWrap = Long.MIN_VALUE + SECOND / 2;
        assertEquals(InvocationRateLimiter.Charge.ALLOWED,
                wrapped.chargeInvocation(wrappingPlayer, afterWrap));
    }
}
