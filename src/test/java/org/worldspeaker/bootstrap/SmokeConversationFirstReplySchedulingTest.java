package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.runtime.GameLoopMailbox;
import org.worldspeaker.runtime.TickMetrics;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration of the production invocation slot, mailbox arbitration, metrics, and shared tick budget. */
class SmokeConversationFirstReplySchedulingTest {
    private static final long CUTOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(750);
    private static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(1);

    @Test
    void completeMailboxTerminalReadyAtFallbackCutoffClaimsFirstAndRecordsOneDispatch() {
        AtomicLong clock = new AtomicLong(10_000);
        FirstReplyScheduler scheduler = new FirstReplyScheduler(clock::get, clock::get);
        FirstReplyScheduler.Slot slot = scheduler.createSlot(10_000, "LIVE_PACKET");
        GameLoopMailbox mailbox = new GameLoopMailbox();
        mailbox.publishActive(new GameLoopMailbox.ActiveRequest(
                "request-1", "invocation-1", "character-1", "player-1", 7, scheduler, slot));
        TickMetrics metrics = new TickMetrics();
        TickMetrics.InvocationEvidence evidence = metrics.recordInteractionInvocation(
                UUID.fromString("00000000-0000-4000-8000-000000000001"), 10_000, 1_793_275_200_000L,
                10_100, "LIVE_PACKET", new Object(), 1,
                UUID.fromString("00000000-0000-4000-8000-000000000002"), "minecraft:overworld",
                null, "COMMAND", "ADMITTED", slot, 10_200, "sha256:template", "UNSIGNED_COMMAND");

        clock.set(10_000 + CUTOFF_NANOS);
        assertTrue(scheduler.fallbackDue(slot, clock.get(), 10_000 + CUTOFF_NANOS));
        GameLoopMailbox.TerminalFrame answer = new GameLoopMailbox.TerminalFrame(
                "request-1", "invocation-1", "character-1", "player-1", "message-1",
                "answer", "A complete answer.", 7, clock.get());
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerTerminal(answer));
        assertEquals(FirstReplyScheduler.Claim.TERMINAL_READY, scheduler.claimAcknowledgement(slot));

        assertEquals(answer, mailbox.takeTerminal());
        FirstReplyScheduler.Claim claim = scheduler.claimTerminal(slot);
        assertEquals(FirstReplyScheduler.Claim.CLAIMED_FIRST, claim);
        assertNotNull(slot.publicationId());
        scheduler.complete(slot, claim, FirstReplyScheduler.PublicationResult.SUBMITTED,
                FirstReplyScheduler.PublicationKind.TERMINAL);
        metrics.recordInteractionDispatch(evidence, clock.get() - slot.capturedAtNanos(),
                TickMetrics.FirstPublicSource.INTEGRATION_ANSWER,
                TickMetrics.FirstPublicDisposition.ANSWERED, clock.get(), slot.publicationId());

        assertTrue(slot.hasFirstPublication());
        assertFalse(scheduler.settleNoDispatch(slot));
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_ON_TIME.ordinal()]);
        assertEquals(0, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);
        assertEquals(750_000_000L, metrics.maxFirstPublicDispatchNanos());
    }

    @Test
    void ackClaimBeforeTerminalOfferKeepsTheLateAnswerAvailableWithoutASecondFirstReply() {
        AtomicLong clock = new AtomicLong(20_000);
        FirstReplyScheduler scheduler = new FirstReplyScheduler(clock::get, clock::get);
        FirstReplyScheduler.Slot slot = scheduler.createSlot(20_000, "LIVE_PACKET");
        GameLoopMailbox mailbox = new GameLoopMailbox();
        mailbox.publishActive(new GameLoopMailbox.ActiveRequest(
                "request-2", "invocation-2", "character-1", "player-1", 8, scheduler, slot));
        TickMetrics metrics = new TickMetrics();
        TickMetrics.InvocationEvidence evidence = metrics.recordInteractionInvocation(
                UUID.fromString("00000000-0000-4000-8000-000000000003"), 20_000, 1_793_275_200_001L,
                20_100, "LIVE_PACKET", new Object(), 2,
                UUID.fromString("00000000-0000-4000-8000-000000000002"), "minecraft:overworld",
                null, "COMMAND", "ADMITTED", slot, 20_200, "sha256:template", "UNSIGNED_COMMAND");

        FirstReplyScheduler.Claim acknowledgement = scheduler.claimAcknowledgement(slot);
        assertEquals(FirstReplyScheduler.Claim.CLAIMED_FIRST, acknowledgement);
        clock.set(20_000 + CUTOFF_NANOS);
        GameLoopMailbox.TerminalFrame answer = new GameLoopMailbox.TerminalFrame(
                "request-2", "invocation-2", "character-1", "player-1", "message-2",
                "answer", "A later complete answer.", 8, clock.get());
        assertEquals(GameLoopMailbox.Offer.LATE_TERMINAL, mailbox.offerTerminal(answer));
        scheduler.complete(slot, acknowledgement, FirstReplyScheduler.PublicationResult.SUBMITTED,
                FirstReplyScheduler.PublicationKind.ACKNOWLEDGEMENT);
        metrics.recordInteractionDispatch(evidence, clock.get() - slot.capturedAtNanos(),
                TickMetrics.FirstPublicSource.LOCAL_ACKNOWLEDGEMENT,
                TickMetrics.FirstPublicDisposition.ACKNOWLEDGED, clock.get(), slot.publicationId());

        assertEquals(answer, mailbox.takeTerminal(), "the late final is retained for later dialogue");
        assertEquals(FirstReplyScheduler.Claim.LATE_TERMINAL, scheduler.claimTerminal(slot));
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_ON_TIME.ordinal()]);
        assertEquals(1, metrics.firstPublicDispatchHistogramSnapshot()
                [TickMetrics.FirstPublicSource.LOCAL_ACKNOWLEDGEMENT.ordinal()]
                [TickMetrics.FirstPublicDisposition.ACKNOWLEDGED.ordinal()][2]);
        assertEquals(0, metrics.firstPublicDispatchHistogramSnapshot()
                [TickMetrics.FirstPublicSource.INTEGRATION_ANSWER.ordinal()]
                [TickMetrics.FirstPublicDisposition.ANSWERED.ordinal()][2]);
    }

    @Test
    void sharedProductionBudgetEnforcesSixReplyTwoReservedSplitAndOneMillisecondCutoff() {
        SmokeTickBudget budget = new SmokeTickBudget(1_000);
        for (int operation = 0; operation < 6; operation++) {
            assertTrue(budget.mayStartOperation(1_000 + operation, SmokeTickBudget.WorkClass.REPLY, 6));
            budget.operationStarted(SmokeTickBudget.WorkClass.REPLY);
        }
        assertFalse(budget.mayStartOperation(1_006, SmokeTickBudget.WorkClass.REPLY, 6));
        for (int operation = 0; operation < 2; operation++) {
            assertTrue(budget.mayStartOperation(1_006 + operation, SmokeTickBudget.WorkClass.RESERVED, 6));
            budget.operationStarted(SmokeTickBudget.WorkClass.RESERVED);
        }
        assertFalse(budget.mayStartOperation(1_008, SmokeTickBudget.WorkClass.LOAN, 0));
        assertEquals(8, budget.operationsStarted());
        assertFalse(budget.mayStartOperation(1_001_000, SmokeTickBudget.WorkClass.LOAN, 0));

        FirstReplyScheduler scheduler = new FirstReplyScheduler(() -> 0, () -> 0);
        FirstReplyScheduler.Slot slot = scheduler.createSlot(0, "LIVE_PACKET");
        assertFalse(slot.deadlineMissed(DEADLINE_NANOS - 1));
        assertTrue(slot.deadlineMissed(DEADLINE_NANOS));
        assertTrue(scheduler.settleNoDispatch(slot));
        assertFalse(scheduler.settleNoDispatch(slot), "one invocation cannot settle the deadline twice");
    }
}
