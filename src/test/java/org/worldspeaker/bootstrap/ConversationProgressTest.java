package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationProgressTest {
    private static final String PLAYER = "00000000-0000-4000-8000-000000000005";
    private static final long T0 = TimeUnit.SECONDS.toNanos(5);

    @Test
    void queuedProgressIsDueFromCaptureOnlyAfterTheFirstLocalReply() {
        ConversationProgress progress = new ConversationProgress(T0);
        assertEquals(Long.MAX_VALUE, progress.nextDeadline(true, false, false));
        assertNull(progress.duePhase(T0 + ConversationProgress.INTERVAL_NANOS, true, false, false, false));
        assertEquals(ConversationProgress.Phase.QUEUED,
                progress.duePhase(T0 + ConversationProgress.INTERVAL_NANOS, true, false, true, false));
        assertTrue(progress.markAttempted(ConversationProgress.Phase.QUEUED));
        assertFalse(progress.markAttempted(ConversationProgress.Phase.QUEUED));
        assertNull(progress.duePhase(T0 + 2 * ConversationProgress.INTERVAL_NANOS, true, false, true, false));

        ConversationProgress.DialogueProgress queued = item("queued-1", ConversationProgress.Phase.QUEUED, T0 + 10);
        assertTrue(progress.recordDispatched(queued));
        assertFalse(progress.recordDispatched(queued));
        assertEquals(List.of(queued), progress.dispatched());
        assertEquals(1, progress.attemptedCount());
    }

    @Test
    void activeNoticesAreScheduledTenAndTwentySecondsAfterForwardingAndStopAtThreeTotal() {
        ConversationProgress progress = new ConversationProgress(T0);
        assertTrue(progress.markAttempted(ConversationProgress.Phase.QUEUED));
        assertTrue(progress.recordDispatched(item("queued", ConversationProgress.Phase.QUEUED, T0 + 1)));
        long forwardedAt = T0 + TimeUnit.SECONDS.toNanos(12);
        assertFalse(progress.markForwarded(forwardedAt, true));
        assertEquals(forwardedAt + ConversationProgress.INTERVAL_NANOS,
                progress.nextDeadline(false, true, true));
        assertNull(progress.duePhase(forwardedAt + ConversationProgress.INTERVAL_NANOS - 1, false, true, true, false));

        long ten = forwardedAt + ConversationProgress.INTERVAL_NANOS;
        assertEquals(ConversationProgress.Phase.AWAITING_ANSWER, progress.duePhase(ten, false, true, true, false));
        assertTrue(progress.markAttempted(ConversationProgress.Phase.AWAITING_ANSWER));
        assertTrue(progress.recordDispatched(item("active-1", ConversationProgress.Phase.AWAITING_ANSWER, ten)));
        long twenty = forwardedAt + 2 * ConversationProgress.INTERVAL_NANOS;
        assertEquals(ConversationProgress.Phase.AWAITING_ANSWER, progress.duePhase(twenty, false, true, true, false));
        assertTrue(progress.markAttempted(ConversationProgress.Phase.AWAITING_ANSWER));
        assertTrue(progress.recordDispatched(item("active-2", ConversationProgress.Phase.AWAITING_ANSWER, twenty)));
        assertNull(progress.duePhase(forwardedAt + TimeUnit.SECONDS.toNanos(30), false, true, true, false));
        assertEquals(3, progress.attemptedCount());
        assertEquals(3, progress.dispatched().size());
        assertEquals(Long.MAX_VALUE, progress.nextDeadline(false, true, true));
    }

    @Test
    void forwardingMissesAnOverdueQueuedNoticeInsteadOfClaimingItWasDispatched() {
        ConversationProgress progress = new ConversationProgress(T0);
        long forwardedAt = T0 + TimeUnit.SECONDS.toNanos(11);
        assertTrue(progress.markForwarded(forwardedAt, true));
        assertNull(progress.duePhase(forwardedAt, false, true, true, false));
        assertTrue(progress.dispatched().isEmpty());
        assertEquals(1, progress.attemptedCount());
    }

    @Test
    void expiryMarksOnlyDueOpportunitiesMissedAndTerminalReadinessSuppressesProgress() {
        ConversationProgress pending = new ConversationProgress(T0);
        assertEquals(List.of(), pending.markDueMissed(T0 + TimeUnit.SECONDS.toNanos(9), true, true, false));
        assertEquals(List.of(ConversationProgress.Phase.QUEUED),
                pending.markDueMissed(T0 + TimeUnit.SECONDS.toNanos(15), true, true, false));
        assertNull(pending.duePhase(T0 + TimeUnit.SECONDS.toNanos(15), true, false, true, false));

        ConversationProgress active = new ConversationProgress(T0);
        long forwardedAt = T0 + TimeUnit.SECONDS.toNanos(1);
        assertFalse(active.markForwarded(forwardedAt, true));
        assertNull(active.duePhase(forwardedAt + ConversationProgress.INTERVAL_NANOS,
                false, true, true, true));
        assertEquals(List.of(ConversationProgress.Phase.AWAITING_ANSWER, ConversationProgress.Phase.AWAITING_ANSWER),
                active.markDueMissed(forwardedAt + TimeUnit.SECONDS.toNanos(30), true, false, true));
        assertEquals(2, active.attemptedCount());
        assertEquals(List.of(), active.markDueMissed(forwardedAt + TimeUnit.SECONDS.toNanos(40), true, false, true));
    }

    @Test
    void deadlineArithmeticRetainsMonotonicWraparoundForShortIntervals() {
        long captured = Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(5);
        ConversationProgress progress = new ConversationProgress(captured);
        long due = captured + ConversationProgress.INTERVAL_NANOS;
        assertTrue(due < 0, "the deadline wraps the signed long boundary in this fixture");
        assertEquals(ConversationProgress.Phase.QUEUED, progress.duePhase(due, true, false, true, false));
    }

    private static ConversationProgress.DialogueProgress item(String id, ConversationProgress.Phase phase, long nanos) {
        return new ConversationProgress.DialogueProgress(id, "[Worldspeaker] A bounded progress notice.",
                1_729_500_000_000L, nanos, PLAYER, phase);
    }
}
