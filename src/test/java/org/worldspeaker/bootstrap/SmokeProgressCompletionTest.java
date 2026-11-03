package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.runtime.GameLoopMailbox;
import org.worldspeaker.runtime.TickMetrics;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeProgressCompletionTest {
    @Test
    void progressCompletionWaitsForBudgetAndNeverConsumesAckOrTerminalSlots() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        GameLoopMailbox.ProgressCompletion progress = new GameLoopMailbox.ProgressCompletion(
                "request", "progress", 7, GameLoopMailbox.ProgressDeliveryOutcome.SEND_COMPLETED);
        GameLoopMailbox.AcknowledgementCompletion acknowledgement =
                new GameLoopMailbox.AcknowledgementCompletion("request", 7, true);
        GameLoopMailbox.ActiveRequest active = new GameLoopMailbox.ActiveRequest("request", "player", 7);
        GameLoopMailbox.TerminalFrame terminal = new GameLoopMailbox.TerminalFrame(
                "request", "player", "answer", "answer", "A complete answer.", 7, 5);
        assertTrue(mailbox.expectProgress("request", "progress", 7));
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerProgress(progress));
        assertTrue(mailbox.expectAcknowledgement("request", 7));
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerAcknowledgement(acknowledgement));
        mailbox.publishActive(active);
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerTerminal(terminal));

        SmokeTickBudget exhausted = new SmokeTickBudget(0);
        for (int index = 0; index < SmokeTickBudget.MAX_OPERATIONS; index++) exhausted.operationStarted();
        AtomicInteger completions = new AtomicInteger();
        assertFalse(SmokeConversation.drainProgressCompletion(mailbox, exhausted, () -> 1,
                ignored -> completions.incrementAndGet()));
        assertEquals(progress, mailbox.peekProgress(), "no completion is consumed when the current tick budget is exhausted");
        assertEquals(acknowledgement, mailbox.peekAcknowledgement());
        assertEquals(terminal, mailbox.peekTerminal());

        SmokeTickBudget nextTick = new SmokeTickBudget(10);
        assertTrue(SmokeConversation.drainProgressCompletion(mailbox, nextTick, () -> 10,
                ignored -> completions.incrementAndGet()));
        assertEquals(1, nextTick.operationsStarted());
        assertEquals(1, completions.get());
        assertEquals(acknowledgement, mailbox.peekAcknowledgement());
        assertEquals(terminal, mailbox.peekTerminal());
    }

    @Test
    void contextCompletionsAreRecordedOnceAndNeverBecomePublicDispatchResults() throws Exception {
        for (GameLoopMailbox.ProgressDeliveryOutcome outcome : new GameLoopMailbox.ProgressDeliveryOutcome[] {
                GameLoopMailbox.ProgressDeliveryOutcome.SEND_COMPLETED,
                GameLoopMailbox.ProgressDeliveryOutcome.FAILED
        }) {
            GameLoopMailbox mailbox = new GameLoopMailbox();
            TickMetrics metrics = new TickMetrics();
            TickMetrics.InvocationEvidence evidence = metrics.recordInteractionInvocation(
                    java.util.UUID.randomUUID(), 0, null, null, null, "COMMAND", "ADMITTED");
            metrics.recordInteractionProgress(evidence, "awaiting_answer", "forwarded_waiting",
                    FirstReplyScheduler.PublicationResult.SUBMITTED, 10, 10L, "progress");
            GameLoopMailbox.ProgressCompletion completion = new GameLoopMailbox.ProgressCompletion(
                    "request", "progress", 7, outcome);
            assertTrue(mailbox.expectProgress("request", "progress", 7));
            assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerProgress(completion));
            assertEquals(GameLoopMailbox.Offer.DUPLICATE, mailbox.offerProgress(completion));

            String contextResult = outcome == GameLoopMailbox.ProgressDeliveryOutcome.SEND_COMPLETED
                    ? "SEND_COMPLETED" : "FAILED";
            AtomicInteger handled = new AtomicInteger();
            SmokeTickBudget budget = new SmokeTickBudget(10);
            assertTrue(SmokeConversation.drainProgressCompletion(mailbox, budget, () -> 10, claimed -> {
                handled.incrementAndGet();
                metrics.recordInteractionProgressContext(evidence, claimed.messageId(), contextResult, 20);
            }));
            assertFalse(SmokeConversation.drainProgressCompletion(mailbox, budget, () -> 10, ignored ->
                    metrics.recordInteractionProgressContext(evidence, "progress", contextResult, 20)));

            assertEquals(1, handled.get());
            assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.PROGRESS_DISPATCHES.ordinal()]);
            assertEquals(0, metrics.countersSnapshot()[TickMetrics.Counter.PROGRESS_FAILURES.ordinal()]);
            metrics.close();
        }
    }
}
