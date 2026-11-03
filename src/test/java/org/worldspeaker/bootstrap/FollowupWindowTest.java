package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowupWindowTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Test
    void configurationIsBoundedAndZeroDisablesOpening() {
        assertEquals(0, new FollowupWindow(0).durationSeconds());
        assertEquals(30, new FollowupWindow(30).durationSeconds());
        for (int invalid : List.of(-1, 31, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
            assertThrows(IllegalArgumentException.class, () -> new FollowupWindow(invalid));
        }

        FollowupWindow disabled = new FollowupWindow(0);
        FollowupWindow.Context context = context(PLAYER);
        assertNull(disabled.beginExplicit(context, UUID.randomUUID(), InteractionIngress.Origin.COMMAND, 10));
        assertEquals(0, disabled.size());
    }

    @Test
    void deliveredAnswerOpensAtTheHardExpiryBoundaryAndTheBoundaryIsClosed() {
        FollowupWindow window = new FollowupWindow(30);
        FollowupWindow.Context context = context(PLAYER);
        FollowupWindow.Candidate candidate = candidate(window, context, 10);
        long openedAt = 20;
        assertTrue(window.open(candidate, context, openedAt));

        long deadline = openedAt + TimeUnit.SECONDS.toNanos(30);
        assertTrue(window.isOpen(context, deadline - 1, deadline - 1));
        assertFalse(window.isOpen(context, deadline, deadline), "exactly 30 seconds is expired");
        assertFalse(window.isOpen(context, deadline + 1, deadline + 1));
        assertEquals(0, window.size());
    }

    @Test
    void repeatedRecognizedLinesDoNotRenewTheOriginalDispatchDeadline() {
        FollowupWindow window = new FollowupWindow(30);
        FollowupWindow.Context context = context(PLAYER);
        assertTrue(window.open(candidate(window, context, 100), context, 200));

        assertTrue(window.isOpen(context, 200 + SECOND, 200 + SECOND));
        assertTrue(window.isOpen(context, 200 + TimeUnit.SECONDS.toNanos(29), 200 + TimeUnit.SECONDS.toNanos(29)));
        assertFalse(window.isOpen(context, 200 + TimeUnit.SECONDS.toNanos(30), 200 + TimeUnit.SECONDS.toNanos(30)));
    }

    @Test
    void backwardsClockClosesButSignedNanoTimeWraparoundAndWallClockShiftsDoNotExtendTheWindow() {
        FollowupWindow backwards = new FollowupWindow(30);
        FollowupWindow.Context context = context(PLAYER);
        assertTrue(backwards.open(candidate(backwards, context, 10), context, 20));
        assertFalse(backwards.isOpen(context, 19, 21));

        FollowupWindow wrapped = new FollowupWindow(30);
        long openedAt = Long.MAX_VALUE - 5;
        assertTrue(wrapped.open(candidate(wrapped, context, openedAt - 10), context, openedAt));
        assertTrue(wrapped.isOpen(context, Long.MIN_VALUE + 4, Long.MIN_VALUE + 4),
                "subtraction preserves a short monotonic interval across signed wraparound");
        assertFalse(wrapped.isOpen(context, Long.MIN_VALUE + TimeUnit.SECONDS.toNanos(30) - 5,
                Long.MIN_VALUE + TimeUnit.SECONDS.toNanos(30) - 5));

        FollowupWindow wallClockIndependent = new FollowupWindow(30);
        assertTrue(wallClockIndependent.open(candidate(wallClockIndependent, context, 10_000), context, 10_001));
        assertTrue(wallClockIndependent.isOpen(context, 10_002, 10_002),
                "the state contains no wall-clock input to shift or renew");
    }

    @Test
    void everyAuthenticatedAndRuntimeIdentityComponentMustStillMatch() {
        FollowupWindow.Context original = context(PLAYER);
        List<UnaryOperator<FollowupWindow.Context>> substitutions = List.of(
                value -> new FollowupWindow.Context(value.playerId(), new Object(), value.player(), value.connection(),
                        value.serverId(), value.worldId(), value.characterId(), value.agentId(), value.dimension(),
                        value.serviceEpoch(), value.serviceGeneration(), value.gateGeneration()),
                value -> new FollowupWindow.Context(value.playerId(), value.server(), new Object(), value.connection(),
                        value.serverId(), value.worldId(), value.characterId(), value.agentId(), value.dimension(),
                        value.serviceEpoch(), value.serviceGeneration(), value.gateGeneration()),
                value -> new FollowupWindow.Context(value.playerId(), value.server(), value.player(), new Object(),
                        value.serverId(), value.worldId(), value.characterId(), value.agentId(), value.dimension(),
                        value.serviceEpoch(), value.serviceGeneration(), value.gateGeneration()),
                value -> new FollowupWindow.Context(value.playerId(), value.server(), value.player(), value.connection(),
                        "other-server", value.worldId(), value.characterId(), value.agentId(), value.dimension(),
                        value.serviceEpoch(), value.serviceGeneration(), value.gateGeneration()),
                value -> new FollowupWindow.Context(value.playerId(), value.server(), value.player(), value.connection(),
                        value.serverId(), "other-world", value.characterId(), value.agentId(), value.dimension(),
                        value.serviceEpoch(), value.serviceGeneration(), value.gateGeneration()),
                value -> new FollowupWindow.Context(value.playerId(), value.server(), value.player(), value.connection(),
                        value.serverId(), value.worldId(), "other-character", value.agentId(), value.dimension(),
                        value.serviceEpoch(), value.serviceGeneration(), value.gateGeneration()),
                value -> new FollowupWindow.Context(value.playerId(), value.server(), value.player(), value.connection(),
                        value.serverId(), value.worldId(), value.characterId(), "other-agent", value.dimension(),
                        value.serviceEpoch(), value.serviceGeneration(), value.gateGeneration()),
                value -> new FollowupWindow.Context(value.playerId(), value.server(), value.player(), value.connection(),
                        value.serverId(), value.worldId(), value.characterId(), value.agentId(), "minecraft:the_nether",
                        value.serviceEpoch(), value.serviceGeneration(), value.gateGeneration()),
                value -> new FollowupWindow.Context(value.playerId(), value.server(), value.player(), value.connection(),
                        value.serverId(), value.worldId(), value.characterId(), value.agentId(), value.dimension(),
                        new Object(), value.serviceGeneration(), value.gateGeneration()),
                value -> new FollowupWindow.Context(value.playerId(), value.server(), value.player(), value.connection(),
                        value.serverId(), value.worldId(), value.characterId(), value.agentId(), value.dimension(),
                        value.serviceEpoch(), value.serviceGeneration() + 1, value.gateGeneration()),
                value -> new FollowupWindow.Context(value.playerId(), value.server(), value.player(), value.connection(),
                        value.serverId(), value.worldId(), value.characterId(), value.agentId(), value.dimension(),
                        value.serviceEpoch(), value.serviceGeneration(), value.gateGeneration() + 1),
                value -> new FollowupWindow.Context(UUID.randomUUID(), value.server(), value.player(), value.connection(),
                        value.serverId(), value.worldId(), value.characterId(), value.agentId(), value.dimension(),
                        value.serviceEpoch(), value.serviceGeneration(), value.gateGeneration()));

        for (int index = 0; index < substitutions.size(); index++) {
            UnaryOperator<FollowupWindow.Context> substitute = substitutions.get(index);
            FollowupWindow window = new FollowupWindow(30);
            assertTrue(window.open(candidate(window, original, 10), original, 20));
            assertFalse(window.isOpen(substitute.apply(original), 21, 21));
            assertEquals(index == substitutions.size() - 1 ? 1 : 0, window.size(),
                    "a different UUID cannot inspect or close this player's state");
        }
    }

    @Test
    void newExplicitRevisionAndInvalidationPreventDelayedOldAnswersFromReopening() {
        FollowupWindow window = new FollowupWindow(30);
        FollowupWindow.Context context = context(PLAYER);
        FollowupWindow.Candidate old = candidate(window, context, 10);
        FollowupWindow.Candidate current = candidate(window, context, 11);

        assertFalse(window.open(old, context, 20));
        assertTrue(window.open(current, context, 20));
        window.invalidate(PLAYER);
        assertFalse(window.open(current, context, 21));
        assertEquals(0, window.size());

        FollowupWindow.Candidate followup = window.beginExplicit(context, UUID.randomUUID(),
                InteractionIngress.Origin.FOLLOWUP, 22);
        assertNull(followup, "unnamed continuation work cannot become a new opening candidate");
    }

    @Test
    void recordCapacityIsBoundedAndCleanupRemovesAtMostOneRecord() {
        FollowupWindow window = new FollowupWindow(30);
        for (int index = 0; index < FollowupWindow.MAX_RECORDS; index++) {
            FollowupWindow.Context context = context(UUID.nameUUIDFromBytes(new byte[] {(byte) index}));
            assertTrue(window.open(candidate(window, context, 10), context, 20));
        }
        assertEquals(FollowupWindow.MAX_RECORDS, window.size());
        assertNull(window.beginExplicit(context(UUID.randomUUID()), UUID.randomUUID(),
                InteractionIngress.Origin.COMMAND, 20));
        assertEquals(FollowupWindow.MAX_RECORDS, window.size());
        assertTrue(window.cleanupOne(20 + TimeUnit.SECONDS.toNanos(30)));
        assertEquals(FollowupWindow.MAX_RECORDS - 1, window.size());
    }

    @Test
    void productionAnswerDeliveryOpensOnlyAfterPublishAndCompletionInsideTheSuccessCallback() {
        FollowupWindow window = new FollowupWindow(30);
        FollowupWindow.Context context = context(PLAYER);
        FollowupWindow.Candidate candidate = candidate(window, context, 10);
        List<String> order = new ArrayList<>();

        SmokeAnswerDelivery.Outcome result = SmokeAnswerDelivery.publishThenComplete("Archivist", "answer",
                rendered -> { order.add("publish"); return true; },
                () -> { order.add("complete"); return true; },
                () -> { order.add("open"); assertTrue(window.open(candidate, context, 20)); },
                () -> order.add("next"));

        assertEquals(SmokeAnswerDelivery.Outcome.COMPLETED, result);
        assertEquals(List.of("publish", "complete", "open", "next"), order);
        assertTrue(window.isOpen(context, 21, 21));

        FollowupWindow failed = new FollowupWindow(30);
        FollowupWindow.Candidate failedCandidate = candidate(failed, context, 10);
        SmokeAnswerDelivery.Outcome notDelivered = SmokeAnswerDelivery.publishThenComplete("Archivist", "answer",
                rendered -> false, () -> true, () -> assertTrue(failed.open(failedCandidate, context, 20)), () -> {});
        assertEquals(SmokeAnswerDelivery.Outcome.ANSWER_NOT_PUBLISHED, notDelivered);
        assertFalse(failed.isOpen(context, 21, 21));

        FollowupWindow ackOnly = new FollowupWindow(30);
        assertNull(ackOnly.beginExplicit(context, UUID.randomUUID(), InteractionIngress.Origin.FOLLOWUP, 10));
        assertFalse(ackOnly.isOpen(context, 20, 20));
    }

    private static FollowupWindow.Candidate candidate(FollowupWindow window, FollowupWindow.Context context,
                                                       long capturedAtNanos) {
        return window.beginExplicit(context, UUID.randomUUID(), InteractionIngress.Origin.COMMAND, capturedAtNanos);
    }

    private static FollowupWindow.Context context(UUID playerId) {
        return new FollowupWindow.Context(playerId, new Object(), new Object(), new Object(),
                "server", "binding-world", "character", "agent", "minecraft:overworld", new Object(), 7, 3);
    }
}
