package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.runtime.GameLoopMailbox;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeRequestAcknowledgementTest {
    private static final String MESSAGE_ID = "00000000-0000-4000-8000-000000000031";
    private static final String ACK = "I have your request.";

    @Test
    void acknowledgementDispatchedWhilePendingIsAttachedToTheForwardedRequest() {
        SmokeRequestAcknowledgement state = new SmokeRequestAcknowledgement();

        assertEquals(SmokeRequestAcknowledgement.Dispatch.ATTACH_TO_REQUEST, state.recordDispatched(true, MESSAGE_ID, ACK));
        assertEquals(MESSAGE_ID, state.forRequest().messageId());
        assertEquals(ACK, state.forRequest().text());
        assertTrue(state.markForwarded());
        assertEquals(SmokeRequestAcknowledgement.Dispatch.NONE, state.recordDispatched(true,
                "00000000-0000-4000-8000-000000000032", "duplicate"));
    }

    @Test
    void acknowledgementDispatchedAfterForwardingUsesTheContextUpdateFrame() {
        SmokeRequestAcknowledgement state = new SmokeRequestAcknowledgement();
        assertTrue(state.markForwarded());

        assertEquals(SmokeRequestAcknowledgement.Dispatch.SEND_AFTER_REQUEST, state.recordDispatched(true, MESSAGE_ID, ACK));
        assertNull(state.forRequest(), "already-forwarded request cannot be retroactively changed");
    }

    @Test
    void anAnswerOrCancellationThatWinsBeforeAcknowledgementCreatesNoContextEntry() {
        SmokeRequestAcknowledgement completed = new SmokeRequestAcknowledgement();
        completed.close();
        assertEquals(SmokeRequestAcknowledgement.Dispatch.NONE, completed.recordDispatched(true, MESSAGE_ID, ACK));
        assertNull(completed.forRequest());

        SmokeRequestAcknowledgement disconnected = new SmokeRequestAcknowledgement();
        disconnected.close();
        assertEquals(SmokeRequestAcknowledgement.Dispatch.NONE, disconnected.recordDispatched(true, MESSAGE_ID, ACK));
        assertFalse(disconnected.markForwarded());
    }

    @Test
    void callerRecordsOnlySuccessfulLocalPublication() {
        SmokeRequestAcknowledgement state = new SmokeRequestAcknowledgement();
        boolean locallyDispatched = false;

        assertEquals(SmokeRequestAcknowledgement.Dispatch.NONE, state.recordDispatched(locallyDispatched, MESSAGE_ID, ACK));

        assertNull(state.forRequest(), "an acknowledgement that was not sent locally is not remembered");
    }

    @Test
    void locallyPublishedAcknowledgementBlocksNextTurnUntilSocketSendCompletesEvenIfRequestIsCancelled() {
        SmokeRequestAcknowledgement state = new SmokeRequestAcknowledgement();
        assertTrue(state.markForwarded());
        assertEquals(SmokeRequestAcknowledgement.Dispatch.SEND_AFTER_REQUEST,
                state.recordDispatched(true, MESSAGE_ID, ACK));

        assertTrue(state.beginContextDelivery());
        assertFalse(state.canStartNextTurn());
        state.close(); // completion/cancellation cannot retract a locally published acknowledgement

        assertTrue(state.contextDeliveryPending());
        assertTrue(state.finishContextDelivery(true));
        assertTrue(state.canStartNextTurn());
        assertEquals(MESSAGE_ID, state.locallyDispatched().messageId());
        assertEquals(ACK, state.locallyDispatched().text());
        assertFalse(state.finishContextDelivery(true), "completion is accepted exactly once");
    }

    @Test
    void permanentContextSendFailureRetainsLocalOutcomeAndFailsClosedWithoutRetry() {
        SmokeRequestAcknowledgement state = new SmokeRequestAcknowledgement();
        assertTrue(state.markForwarded());
        assertEquals(SmokeRequestAcknowledgement.Dispatch.SEND_AFTER_REQUEST,
                state.recordDispatched(true, MESSAGE_ID, ACK));

        assertTrue(state.beginContextDelivery());
        assertTrue(state.finishContextDelivery(false));
        assertTrue(state.contextDeliveryFailed());
        assertFalse(state.canStartNextTurn(), "a later turn must not proceed without the missing context");
        assertEquals(MESSAGE_ID, state.locallyDispatched().messageId(),
                "failure to attach context does not undo the truthful local publication");
        assertFalse(state.beginContextDelivery(), "the bounded policy makes one transport attempt only");
    }

    @Test
    void cancellationBeforeLocalAcknowledgementPublicationDoesNotCreateDeliveryWork() {
        SmokeRequestAcknowledgement state = new SmokeRequestAcknowledgement();
        state.close();

        assertEquals(SmokeRequestAcknowledgement.Dispatch.NONE, state.recordDispatched(true, MESSAGE_ID, ACK));
        assertFalse(state.beginContextDelivery());
        assertTrue(state.canStartNextTurn());
        assertNull(state.locallyDispatched());
    }

    @Test
    void exactBudgetBoundaryLeavesReservedCompletionForTheNextTickAndProcessesItOnce() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        SmokeRequestAcknowledgement state = forwardedAcknowledgement();
        GameLoopMailbox.AcknowledgementCompletion completion =
                new GameLoopMailbox.AcknowledgementCompletion("request", 4, true);
        assertTrue(mailbox.expectAcknowledgement("request", 4));
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerAcknowledgement(completion));
        AtomicInteger handled = new AtomicInteger();

        SmokeTickBudget expiredTick = new SmokeTickBudget(0);
        assertFalse(SmokeConversation.drainAcknowledgementCompletion(mailbox, expiredTick,
                () -> SmokeTickBudget.MAX_START_NANOS,
                delivered -> {
                    handled.incrementAndGet();
                    assertTrue(state.finishContextDelivery(delivered.delivered()));
                }));
        assertEquals(0, expiredTick.operationsStarted());
        assertEquals(completion, mailbox.peekAcknowledgement(), "the reserved completion survives budget expiry");
        assertTrue(state.contextDeliveryPending());
        assertFalse(state.canStartNextTurn());

        SmokeTickBudget nextTick = new SmokeTickBudget(SmokeTickBudget.MAX_START_NANOS);
        assertTrue(SmokeConversation.drainAcknowledgementCompletion(mailbox, nextTick,
                () -> SmokeTickBudget.MAX_START_NANOS,
                delivered -> {
                    handled.incrementAndGet();
                    assertTrue(state.finishContextDelivery(delivered.delivered()));
                }));
        assertEquals(1, nextTick.operationsStarted());
        assertNull(mailbox.peekAcknowledgement());
        assertFalse(state.contextDeliveryPending());
        assertTrue(state.canStartNextTurn());
        assertEquals(1, handled.get());
        assertFalse(SmokeConversation.drainAcknowledgementCompletion(mailbox, nextTick,
                () -> SmokeTickBudget.MAX_START_NANOS, ignored -> handled.incrementAndGet()));
        assertEquals(1, handled.get(), "the completion is handled exactly once");
    }

    @Test
    void dueFirstReplyUsesTheLastOperationSlotBeforeAcknowledgementCompletion() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        GameLoopMailbox.AcknowledgementCompletion completion =
                new GameLoopMailbox.AcknowledgementCompletion("request", 4, true);
        assertTrue(mailbox.expectAcknowledgement("request", 4));
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerAcknowledgement(completion));

        SmokeTickBudget budget = new SmokeTickBudget(0);
        for (int operation = 0; operation < SmokeTickBudget.MAX_OPERATIONS - 1; operation++) {
            budget.operationStarted();
        }
        SmokeDeadlineScan.Scan<String> deadlines = SmokeDeadlineScan.scan(List.of(
                new SmokeDeadlineScan.Deadline<>("first-reply", true, 1,
                        SmokeDeadlineScan.Expiry.NONE, 0)), () -> 2, 0, budget);
        assertTrue(deadlines.forEachDueAction(evaluation -> {
            if (evaluation.action() != SmokeDeadlineScan.Action.NONE) budget.operationStarted();
            return true;
        }));
        assertEquals(SmokeTickBudget.MAX_OPERATIONS, budget.operationsStarted());

        assertFalse(SmokeConversation.drainAcknowledgementCompletion(mailbox, budget, () -> 1,
                ignored -> { throw new AssertionError("completion must wait behind the due first reply"); }));
        assertEquals(completion, mailbox.peekAcknowledgement());
    }

    @Test
    void completionAfterActiveExpiryCanResolveClosedBarrierSuccessfully() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        SmokeRequestAcknowledgement expired = forwardedAcknowledgement();
        assertTrue(mailbox.expectAcknowledgement("expired", 5));
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerAcknowledgement(
                new GameLoopMailbox.AcknowledgementCompletion("expired", 5, true)));

        expired.close(); // Active timeout/retirement does not discard an already-started context send.
        assertTrue(expired.contextDeliveryPending());
        SmokeTickBudget tick = new SmokeTickBudget(0);
        assertTrue(SmokeConversation.drainAcknowledgementCompletion(mailbox, tick, () -> 0,
                completion -> assertTrue(expired.finishContextDelivery(completion.delivered()))));
        assertTrue(expired.canStartNextTurn());
        assertFalse(expired.contextDeliveryPending());
    }

    @Test
    void failedCompletionAfterActiveExpiryKeepsTheClosedBarrierFailingClosed() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        SmokeRequestAcknowledgement expired = forwardedAcknowledgement();
        assertTrue(mailbox.expectAcknowledgement("expired-failure", 5));
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerAcknowledgement(
                new GameLoopMailbox.AcknowledgementCompletion("expired-failure", 5, false)));

        expired.close();
        SmokeTickBudget tick = new SmokeTickBudget(0);
        assertTrue(SmokeConversation.drainAcknowledgementCompletion(mailbox, tick, () -> 0,
                completion -> assertTrue(expired.finishContextDelivery(completion.delivered()))));
        assertTrue(expired.contextDeliveryFailed());
        assertFalse(expired.canStartNextTurn());
    }

    @Test
    void successfulCompletionAfterDisconnectCanReleaseOnlyItsRetainedBarrier() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        SmokeRequestAcknowledgement disconnected = forwardedAcknowledgement();
        assertTrue(mailbox.expectAcknowledgement("disconnected-success", 6));
        GameLoopMailbox.AcknowledgementCompletion completion =
                new GameLoopMailbox.AcknowledgementCompletion("disconnected-success", 6, true);
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerAcknowledgement(completion));

        disconnected.close();
        assertTrue(disconnected.contextDeliveryPending());
        assertEquals(completion, mailbox.peekAcknowledgement(), "disconnect does not clear its reserved completion");
        SmokeTickBudget tick = new SmokeTickBudget(0);
        assertTrue(SmokeConversation.drainAcknowledgementCompletion(mailbox, tick, () -> 0,
                result -> assertTrue(disconnected.finishContextDelivery(result.delivered()))));
        assertTrue(disconnected.canStartNextTurn());
        assertNull(mailbox.peekAcknowledgement());
    }

    @Test
    void disconnectRetainsPendingBarrierAndFailedCompletionRemainsFailClosedAfterClose() {
        GameLoopMailbox mailbox = new GameLoopMailbox();
        SmokeRequestAcknowledgement disconnected = forwardedAcknowledgement();
        assertTrue(mailbox.expectAcknowledgement("disconnected", 6));
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, mailbox.offerAcknowledgement(
                new GameLoopMailbox.AcknowledgementCompletion("disconnected", 6, false)));

        disconnected.close(); // Request lifecycle ends; pending transport completion remains meaningful.
        assertTrue(disconnected.contextDeliveryPending());
        assertEquals("disconnected", mailbox.peekAcknowledgement().requestId());
        SmokeTickBudget tick = new SmokeTickBudget(0);
        assertTrue(SmokeConversation.drainAcknowledgementCompletion(mailbox, tick, () -> 0,
                completion -> assertTrue(disconnected.finishContextDelivery(completion.delivered()))));
        assertTrue(disconnected.contextDeliveryFailed());
        assertFalse(disconnected.canStartNextTurn(), "a failed context update never opens the barrier");
    }

    @Test
    void closingBeforeContextDeliveryStartsCannotCreateNewTransportWork() {
        SmokeRequestAcknowledgement state = new SmokeRequestAcknowledgement();
        assertTrue(state.markForwarded());
        assertEquals(SmokeRequestAcknowledgement.Dispatch.SEND_AFTER_REQUEST,
                state.recordDispatched(true, MESSAGE_ID, ACK));
        state.close();

        assertFalse(state.beginContextDelivery());
        assertFalse(state.contextDeliveryPending());
        assertTrue(state.canStartNextTurn());
    }

    @Test
    void oldServiceCompletionCannotReviveItsClosedBarrierOrReachTheNewGeneration() {
        GameLoopMailbox oldMailbox = new GameLoopMailbox();
        SmokeRequestAcknowledgement oldBarrier = forwardedAcknowledgement();
        assertTrue(oldMailbox.expectAcknowledgement("old-request", 7));
        oldMailbox.clearAll(); // Service close retires the barrier and closes its mailbox.
        oldBarrier.close();

        GameLoopMailbox newMailbox = new GameLoopMailbox();
        SmokeRequestAcknowledgement newBarrier = forwardedAcknowledgement();
        assertTrue(newMailbox.expectAcknowledgement("new-request", 8));
        assertEquals(GameLoopMailbox.Offer.STALE, oldMailbox.offerAcknowledgement(
                new GameLoopMailbox.AcknowledgementCompletion("old-request", 7, true)));
        GameLoopMailbox.AcknowledgementCompletion current =
                new GameLoopMailbox.AcknowledgementCompletion("new-request", 8, true);
        assertEquals(GameLoopMailbox.Offer.ACCEPTED, newMailbox.offerAcknowledgement(current));

        SmokeTickBudget tick = new SmokeTickBudget(0);
        assertTrue(SmokeConversation.drainAcknowledgementCompletion(newMailbox, tick, () -> 0,
                completion -> assertTrue(newBarrier.finishContextDelivery(completion.delivered()))));
        assertTrue(oldBarrier.contextDeliveryPending(), "the closed old barrier is not completed by a new generation");
        assertFalse(oldBarrier.canStartNextTurn());
        assertTrue(newBarrier.canStartNextTurn());
    }

    private static SmokeRequestAcknowledgement forwardedAcknowledgement() {
        SmokeRequestAcknowledgement state = new SmokeRequestAcknowledgement();
        assertTrue(state.markForwarded());
        assertEquals(SmokeRequestAcknowledgement.Dispatch.SEND_AFTER_REQUEST,
                state.recordDispatched(true, MESSAGE_ID, ACK));
        assertTrue(state.beginContextDelivery());
        return state;
    }
}
