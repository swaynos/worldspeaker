package org.worldspeaker.runtime;

import org.junit.jupiter.api.Test;
import org.worldspeaker.bootstrap.FirstReplyScheduler;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameLoopMailboxTest {
    @Test
    void terminalOfferAndAcknowledgementShareTheInvocationOwnedFirstSlot() {
        AtomicLong clock = new AtomicLong();
        FirstReplyScheduler scheduler = new FirstReplyScheduler(clock::get, clock::get);
        FirstReplyScheduler.Slot slot = scheduler.createSlot(10, "LIVE_PACKET");
        GameLoopMailbox mailbox = new GameLoopMailbox();
        GameLoopMailbox.ActiveRequest active = new GameLoopMailbox.ActiveRequest(
                "r1", "i1", "character", "p1", 7, scheduler, slot);
        mailbox.publishActive(active);

        GameLoopMailbox.TerminalFrame answer = new GameLoopMailbox.TerminalFrame(
                "r1", "i1", "character", "p1", "m1", "answer", "complete", 7, 999);
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerTerminal(answer));
        assertEquals(FirstReplyScheduler.Claim.TERMINAL_READY, scheduler.claimAcknowledgement(slot));
        assertEquals(answer, mailbox.takeTerminal());
        FirstReplyScheduler.Claim terminal = scheduler.claimTerminal(slot);
        assertEquals(FirstReplyScheduler.Claim.CLAIMED_FIRST, terminal);
        scheduler.complete(slot, terminal, FirstReplyScheduler.PublicationResult.SUBMITTED,
                FirstReplyScheduler.PublicationKind.TERMINAL);

        FirstReplyScheduler.Slot ackWon = scheduler.createSlot(20, "LIVE_PACKET");
        mailbox.publishActive(new GameLoopMailbox.ActiveRequest("r2", "i2", "character", "p1", 8, scheduler, ackWon));
        FirstReplyScheduler.Claim ack = scheduler.claimAcknowledgement(ackWon);
        assertEquals(FirstReplyScheduler.Claim.CLAIMED_FIRST, ack);
        GameLoopMailbox.TerminalFrame later = new GameLoopMailbox.TerminalFrame(
                "r2", "i2", "character", "p1", "m2", "answer", "later", 8, 1_000);
        assertEquals(GameLoopMailbox.Offer.LATE_TERMINAL, mailbox.offerTerminal(later));
        scheduler.complete(ackWon, ack, FirstReplyScheduler.PublicationResult.SUBMITTED,
                FirstReplyScheduler.PublicationKind.ACKNOWLEDGEMENT);
        assertEquals(later, mailbox.takeTerminal(), "the late answer remains available as later dialogue");
        assertEquals(FirstReplyScheduler.Claim.LATE_TERMINAL, scheduler.claimTerminal(ackWon));
    }

    @Test
    void terminalSlotAcceptsOneBoundedResultAndRejectsDuplicatesOrConflicts() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        GameLoopMailbox.ActiveRequest active = new GameLoopMailbox.ActiveRequest("r1", "p1", 7);
        GameLoopMailbox.TerminalFrame answer = new GameLoopMailbox.TerminalFrame("r1", "p1", "m1", "answer", "ok", 7, 10);
        mailbox.publishActive(active);

        assertEquals(GameLoopMailbox.Offer.STALE, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                "old", "p1", "m0", "answer", "stale", 7, 9)));
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerTerminal(answer));
        assertEquals(GameLoopMailbox.Offer.DUPLICATE, mailbox.offerTerminal(answer));
        assertEquals(GameLoopMailbox.Offer.CONFLICT, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                "r1", "p1", "m1", "answer", "different body", 7, 11)));
        assertTrue(mailbox.takeUnavailable());
        assertFalse(mailbox.takeUnavailable());
        assertEquals(answer, mailbox.takeTerminal());
        assertNull(mailbox.takeTerminal());
        assertEquals(GameLoopMailbox.Offer.DUPLICATE, mailbox.offerTerminal(answer), "a consumed terminal remains deduplicated for the active request");
    }

    @Test
    void invalidOrUnrenderableTerminalDoesNotReserveTheActiveSlot() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        mailbox.publishActive(new GameLoopMailbox.ActiveRequest("r1", "p1", 7));

        assertEquals(GameLoopMailbox.Offer.INVALID, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                "r1", "p1", "control", "answer", "bad\u0001answer", 7, 10)));
        assertEquals(GameLoopMailbox.Offer.INVALID, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                "r1", "p1", "line-break", "answer", "bad\nanswer", 7, 10)));
        assertEquals(GameLoopMailbox.Offer.INVALID, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                "r1", "p1", "c1-control", "answer", "bad\u0085answer", 7, 10)));
        assertEquals(GameLoopMailbox.Offer.INVALID, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                "r1", "p1", "bidi-only", "answer", "\u202e", 7, 11)),
                "a body that the vanilla renderer sanitizes to empty must not reserve the slot");
        assertEquals(GameLoopMailbox.Offer.INVALID, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                "r1", "p1", "format-only", "answer", "\u00a7c", 7, 12)));
        assertEquals(GameLoopMailbox.Offer.INVALID, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                "r1", "p1", "too-long", "answer", "x".repeat(2049), 7, 13)));
        assertEquals(GameLoopMailbox.Offer.INVALID, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                "r\u0001", "p1", "bad-id", "answer", "valid body", 7, 14)));
        assertEquals(GameLoopMailbox.Offer.INVALID, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                "r".repeat(2049), "p1", "oversized-id", "answer", "valid body", 7, 14)));
        assertEquals(GameLoopMailbox.Offer.INVALID, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                "r1", "p1", "negative-generation", "answer", "valid body", -1, 14)));
        assertEquals(GameLoopMailbox.Offer.STALE, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                "r1", "other-player", "wrong-audience", "answer", "valid body", 7, 15)));
        assertEquals(GameLoopMailbox.Offer.STALE, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                "r1", "p1", "wrong-generation", "answer", "valid body", 8, 16)));
        assertNull(mailbox.peekTerminal());
        assertFalse(mailbox.takeUnavailable(), "rejected frames are not valid-terminal conflicts");

        GameLoopMailbox.TerminalFrame laterValid = new GameLoopMailbox.TerminalFrame(
                "r1", "p1", "valid-after-invalid", "answer", "later valid answer", 7, 17);
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerTerminal(laterValid));
        assertEquals(laterValid, mailbox.takeTerminal());
        assertEquals(GameLoopMailbox.Offer.DUPLICATE, mailbox.offerTerminal(laterValid));
        assertEquals(GameLoopMailbox.Offer.CONFLICT, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                "r1", "p1", "another-valid", "answer", "another valid answer", 7, 18)));
        assertTrue(mailbox.takeUnavailable(), "distinct conflicting valid terminals still fail closed");
    }

    @Test
    void acknowledgementBootstrapAndUnavailableHaveIndependentReservedSlots() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        GameLoopMailbox.AcknowledgementCompletion sent = new GameLoopMailbox.AcknowledgementCompletion("r1", 2, true);
        assertTrue(mailbox.expectAcknowledgement("r1", 2));
        // An unexpected first offer is stale and does not reserve or consume the slot.
        assertEquals(GameLoopMailbox.Offer.STALE, mailbox.offerAcknowledgement(
                new GameLoopMailbox.AcknowledgementCompletion("other", 2, true)));
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerAcknowledgement(sent));
        assertEquals(GameLoopMailbox.Offer.DUPLICATE, mailbox.offerAcknowledgement(sent));
        assertEquals(GameLoopMailbox.Offer.CONFLICT,
                mailbox.offerAcknowledgement(new GameLoopMailbox.AcknowledgementCompletion("r1", 2, false)));

        GameLoopMailbox.BootstrapResult error = new GameLoopMailbox.BootstrapResult(null, "WS-COMPAT-CONFIG-INVALID");
        assertTrue(mailbox.offerBootstrap(error));
        assertFalse(mailbox.offerBootstrap(error));
        assertEquals(error, mailbox.takeBootstrap());
        assertEquals(sent, mailbox.takeAcknowledgement());
        assertEquals(GameLoopMailbox.Offer.STALE, mailbox.offerAcknowledgement(sent), "late callbacks cannot repopulate a consumed slot");
        mailbox.markUnavailable();
        mailbox.markUnavailable();
        assertTrue(mailbox.takeUnavailable());
        assertFalse(mailbox.takeUnavailable());
        mailbox.clearAll();
        assertFalse(mailbox.offerBootstrap(error), "late bootstrap workers cannot revive a stopped service mailbox");
        assertFalse(mailbox.expectAcknowledgement("r2", 3));
        mailbox.markUnavailable();
        assertFalse(mailbox.takeUnavailable());
    }

    @Test
    void acknowledgementCanBeInspectedBeforeBudgetAndClaimedOnlyByItsExactValue() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        GameLoopMailbox.AcknowledgementCompletion sent = new GameLoopMailbox.AcknowledgementCompletion("r1", 2, true);
        GameLoopMailbox.AcknowledgementCompletion failed = new GameLoopMailbox.AcknowledgementCompletion("r1", 2, false);
        assertTrue(mailbox.expectAcknowledgement("r1", 2));
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerAcknowledgement(sent));

        assertEquals(sent, mailbox.peekAcknowledgement());
        assertNull(mailbox.takeAcknowledgement(failed), "a mismatched claim cannot consume the reserved value");
        assertEquals(sent, mailbox.peekAcknowledgement());
        assertEquals(sent, mailbox.takeAcknowledgement(sent));
        assertNull(mailbox.peekAcknowledgement());
        assertNull(mailbox.takeAcknowledgement(sent), "a completion has one successful claim");
    }

    @Test
    void aClearedOldGenerationCannotReplaceOrBeClaimedAsANewerCompletion() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        GameLoopMailbox.AcknowledgementCompletion old = new GameLoopMailbox.AcknowledgementCompletion("old", 7, true);
        GameLoopMailbox.AcknowledgementCompletion current = new GameLoopMailbox.AcknowledgementCompletion("new", 8, true);
        assertTrue(mailbox.expectAcknowledgement("old", 7));
        mailbox.clearAcknowledgement("old", 7);
        assertTrue(mailbox.expectAcknowledgement("new", 8));

        assertEquals(GameLoopMailbox.Offer.STALE, mailbox.offerAcknowledgement(old));
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerAcknowledgement(current));
        assertNull(mailbox.takeAcknowledgement(old));
        assertEquals(current, mailbox.peekAcknowledgement());
        assertEquals(current, mailbox.takeAcknowledgement(current));
        assertNull(mailbox.peekAcknowledgement());
    }

    @Test
    void progressCompletionHasAnIndependentBoundedSlotAndExactClaim() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        GameLoopMailbox.ProgressCompletion sent = new GameLoopMailbox.ProgressCompletion(
                "request", "progress-1", 7, GameLoopMailbox.ProgressDeliveryOutcome.SEND_COMPLETED);
        GameLoopMailbox.ProgressCompletion failed = new GameLoopMailbox.ProgressCompletion(
                "request", "progress-1", 7, GameLoopMailbox.ProgressDeliveryOutcome.FAILED);

        assertTrue(mailbox.expectProgress("request", "progress-1", 7));
        assertEquals(GameLoopMailbox.Offer.STALE, mailbox.offerProgress(new GameLoopMailbox.ProgressCompletion(
                "other", "progress-1", 7, GameLoopMailbox.ProgressDeliveryOutcome.SEND_COMPLETED)));
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerProgress(sent));
        assertEquals(GameLoopMailbox.Offer.DUPLICATE, mailbox.offerProgress(sent));
        assertEquals(GameLoopMailbox.Offer.CONFLICT, mailbox.offerProgress(failed));
        assertTrue(mailbox.takeUnavailable());
        assertEquals(sent, mailbox.peekProgress());
        assertNull(mailbox.takeProgress(failed));
        assertEquals(sent, mailbox.takeProgress(sent));
        assertNull(mailbox.peekProgress());
        assertEquals(GameLoopMailbox.Offer.STALE, mailbox.offerProgress(sent));
        assertTrue(mailbox.expectProgress("request", "progress-2", 7),
                "a claimed completion slot can be reused for the same request's next bounded notice");
        GameLoopMailbox.ProgressCompletion nextNotice = new GameLoopMailbox.ProgressCompletion(
                "request", "progress-2", 7, GameLoopMailbox.ProgressDeliveryOutcome.UNKNOWN);
        assertEquals(GameLoopMailbox.Offer.STALE, mailbox.offerProgress(sent));
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerProgress(nextNotice));
        assertEquals(nextNotice, mailbox.takeProgress(nextNotice));

        assertTrue(mailbox.expectProgress("next", "progress-2", 8));
        mailbox.clearProgress("next", "progress-2", 8);
        assertEquals(GameLoopMailbox.Offer.STALE, mailbox.offerProgress(new GameLoopMailbox.ProgressCompletion(
                "next", "progress-2", 8, GameLoopMailbox.ProgressDeliveryOutcome.UNKNOWN)));
    }

    @Test
    void activeGenerationChangeClearsPreviousFrameAndLateWorkerCannotReviveIt() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        GameLoopMailbox.ActiveRequest oldRequest = new GameLoopMailbox.ActiveRequest("r1", "p1", 1);
        GameLoopMailbox.ActiveRequest newRequest = new GameLoopMailbox.ActiveRequest("r2", "p1", 2);
        mailbox.publishActive(oldRequest);
        mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame("r1", "p1", "m1", "answer", "old", 1, 1));
        mailbox.publishActive(newRequest);

        assertEquals(GameLoopMailbox.Offer.STALE,
                mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame("r1", "p1", "m1", "answer", "old", 1, 2)));
        assertNull(mailbox.peekTerminal());
        mailbox.clearActive(oldRequest);
        assertEquals(newRequest, mailbox.activeRequest());
    }

    @Test
    void oneHundredThousandStaleFramesDoNotAllocateTerminalState() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        mailbox.publishActive(new GameLoopMailbox.ActiveRequest("current", "p1", 1));
        for (int index = 0; index < 100_000; index++) {
            assertEquals(GameLoopMailbox.Offer.STALE, mailbox.offerTerminal(new GameLoopMailbox.TerminalFrame(
                    "invalid-" + index, "p1", "m" + index, "answer", "bounded", 1, index)));
        }
        assertNull(mailbox.peekTerminal());
    }
}
