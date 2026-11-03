package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstReplySchedulerTest {
    private static final long CUTOFF = TimeUnit.MILLISECONDS.toNanos(750);
    private static final long DEADLINE = TimeUnit.SECONDS.toNanos(1);

    @Test
    void completeTerminalOfferedBeforeFallbackClaimWinsEvenAfterCutoff() {
        FirstReplyScheduler scheduler = scheduler();
        FirstReplyScheduler.Slot slot = scheduler.createSlot(10, "LIVE_PACKET");
        assertTrue(scheduler.fallbackDue(slot, 10 + CUTOFF, 10 + CUTOFF));
        assertEquals(FirstReplyScheduler.Claim.FIRST_AVAILABLE, scheduler.offerTerminal(slot));
        assertEquals(FirstReplyScheduler.Claim.TERMINAL_READY, scheduler.claimAcknowledgement(slot));

        FirstReplyScheduler.Claim claim = scheduler.claimTerminal(slot);
        assertEquals(FirstReplyScheduler.Claim.CLAIMED_FIRST, claim);
        assertNotNull(slot.publicationId());
        scheduler.complete(slot, claim, FirstReplyScheduler.PublicationResult.SUBMITTED,
                FirstReplyScheduler.PublicationKind.TERMINAL);
        assertTrue(slot.hasFirstPublication());
        assertFalse(scheduler.settleNoDispatch(slot));
    }

    @Test
    void ackClaimLinearizesBeforeAConcurrentLateTerminalOffer() throws Exception {
        FirstReplyScheduler scheduler = scheduler();
        FirstReplyScheduler.Slot slot = scheduler.createSlot(20, "LIVE_PACKET");
        CountDownLatch ackClaimed = new CountDownLatch(1);
        CountDownLatch releaseAck = new CountDownLatch(1);
        Thread dispatch = new Thread(() -> {
            FirstReplyScheduler.Claim claim = scheduler.claimAcknowledgement(slot);
            assertEquals(FirstReplyScheduler.Claim.CLAIMED_FIRST, claim);
            ackClaimed.countDown();
            await(releaseAck);
            scheduler.complete(slot, claim, FirstReplyScheduler.PublicationResult.SUBMITTED,
                    FirstReplyScheduler.PublicationKind.ACKNOWLEDGEMENT);
        });
        dispatch.start();
        try {
            assertTrue(ackClaimed.await(3, TimeUnit.SECONDS));
            assertEquals(FirstReplyScheduler.Claim.LATE_TERMINAL, scheduler.offerTerminal(slot));
        } finally {
            releaseAck.countDown();
            dispatch.join(TimeUnit.SECONDS.toMillis(3));
        }
        assertFalse(dispatch.isAlive());
        assertTrue(slot.hasFirstPublication());
        assertEquals(FirstReplyScheduler.Claim.LATE_TERMINAL, scheduler.claimTerminal(slot));
    }

    @Test
    void definiteUnsentReopensOnceButUnknownAndSettlementNeverReplay() {
        FirstReplyScheduler scheduler = scheduler();
        FirstReplyScheduler.Slot unsent = scheduler.createSlot(30, "LIVE_PACKET");
        FirstReplyScheduler.Claim first = scheduler.claimAcknowledgement(unsent);
        scheduler.complete(unsent, first, FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT,
                FirstReplyScheduler.PublicationKind.ACKNOWLEDGEMENT);
        FirstReplyScheduler.Claim alternative = scheduler.claimDisposition(unsent, false);
        assertEquals(FirstReplyScheduler.Claim.CLAIMED_FIRST, alternative);
        scheduler.complete(unsent, alternative, FirstReplyScheduler.PublicationResult.UNKNOWN,
                FirstReplyScheduler.PublicationKind.DISPOSITION);
        assertTrue(unsent.unknown());
        assertEquals(FirstReplyScheduler.Claim.ALREADY_CLAIMED, scheduler.claimAcknowledgement(unsent));
        assertFalse(scheduler.settleNoDispatch(unsent));

        FirstReplyScheduler.Slot noDispatch = scheduler.createSlot(40, "LIVE_PACKET");
        assertTrue(noDispatch.deadlineMissed(40 + DEADLINE));
        assertTrue(scheduler.settleNoDispatch(noDispatch));
        assertFalse(scheduler.settleNoDispatch(noDispatch));
        assertEquals(FirstReplyScheduler.Claim.SETTLED, scheduler.claimAcknowledgement(noDispatch));
    }

    @Test
    void fallbackDoesNotCrossTheExactCutoff() {
        FirstReplyScheduler scheduler = scheduler();
        FirstReplyScheduler.Slot slot = scheduler.createSlot(100, "LIVE_PACKET");
        assertFalse(scheduler.fallbackDue(slot, 100 + CUTOFF - 1, 100 + CUTOFF));
        assertTrue(scheduler.fallbackDue(slot, 100 + CUTOFF, 100 + CUTOFF));
    }

    @Test
    void noticeBatchReferenceClaimsAndPublishesOneSharedActualResult() {
        FirstReplyScheduler scheduler = scheduler();
        FirstReplyScheduler.Slot slot = scheduler.createSlot(100, "LIVE_PACKET");
        FirstReplyScheduler.NoticePublication publication = new FirstReplyScheduler.NoticePublication();
        assertTrue(slot.linkNoticePublication(publication));
        assertEquals(FirstReplyScheduler.Claim.ALREADY_CLAIMED, scheduler.claimAcknowledgement(slot));
        assertFalse(slot.hasFirstPublication());
        assertTrue(publication.claim());
        assertTrue(publication.complete(FirstReplyScheduler.PublicationResult.SUBMITTED, 175));
        assertTrue(slot.hasFirstPublication());
        assertEquals(publication.publicationId(), slot.publicationId());
        assertFalse(scheduler.settleNoDispatch(slot));
        assertEquals(175L, publication.result().attemptedAtNanos());
    }

    @Test
    void joinedNoticeDeadlineMovesOnlyEarlierAndQueueOrderingSurvivesNanoTimeRollover() {
        FirstReplyScheduler scheduler = scheduler();
        Runnable first = () -> {};
        Runnable batch = () -> {};
        long firstDeadline = Long.MAX_VALUE - 10;
        long initialBatchDeadline = Long.MIN_VALUE + 10;
        assertTrue(scheduler.enqueueDisposition(firstDeadline, first));
        assertTrue(scheduler.enqueueDisposition(initialBatchDeadline, batch));
        assertEquals(firstDeadline, scheduler.nextDispositionDeadlineNanos());

        long earlierBatchDeadline = Long.MIN_VALUE - 20;
        assertTrue(scheduler.updateDispositionDeadline(batch, earlierBatchDeadline));
        assertEquals(earlierBatchDeadline, scheduler.nextDispositionDeadlineNanos());
        assertTrue(scheduler.updateDispositionDeadline(batch, Long.MIN_VALUE + 50));
        assertEquals(earlierBatchDeadline, scheduler.nextDispositionDeadlineNanos(),
                "later repeats never slide the shared queue deadline");
    }

    @Test
    void futureNoticeWaitsForCooldownWithoutBlockingReadyOtherPlayerDisposition() {
        AtomicLong now = new AtomicLong();
        FirstReplyScheduler scheduler = new FirstReplyScheduler(now::get, now::get);
        List<String> sent = new ArrayList<>();
        assertTrue(scheduler.enqueueDispositionAt(100, 10, () -> sent.add("cooldown")));
        assertTrue(scheduler.enqueueDispositionAt(0, 20, () -> sent.add("ready")));

        assertEquals(20L, scheduler.nextDispositionDeadlineNanos());
        assertEquals(1, scheduler.drainDispositions(new SmokeTickBudget(0), 0));
        assertEquals(List.of("ready"), sent);
        assertEquals(0, scheduler.drainDispositions(new SmokeTickBudget(0), 0),
                "a future item does not consume an operation before notBefore");

        now.set(100);
        assertEquals(10L, scheduler.nextDispositionDeadlineNanos());
        assertEquals(1, scheduler.drainDispositions(new SmokeTickBudget(100), 0));
        assertEquals(List.of("ready", "cooldown"), sent);
    }

    private static FirstReplyScheduler scheduler() {
        AtomicLong monotonic = new AtomicLong();
        AtomicLong wall = new AtomicLong();
        return new FirstReplyScheduler(monotonic::get, wall::get);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
