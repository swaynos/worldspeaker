package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmokeRequestAdmissionTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID OTHER_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @Test
    void rejectedSubmissionIsNotRememberedAsLiveDuplicate() {
        long firstAttempt = 1_000_000_000L;
        long repeat = firstAttempt + 100_000_000L;

        assertEquals(SmokeRequestAdmission.Decision.REJECT_CAPACITY,
                decide(List.of(), PLAYER, "fourth request", firstAttempt, 2, 16));
        // A rejected submission creates no live request, so the repeat is still rejected.
        assertEquals(SmokeRequestAdmission.Decision.REJECT_CAPACITY,
                decide(List.of(), PLAYER, "fourth request", repeat, 2, 16));
    }

    @Test
    void coalescesAnExactRepeatOfLiveWorkForItsWholeLiveLifetime() {
        List<SmokeRequestAdmission.LiveRequest> live = List.of(
                new SmokeRequestAdmission.LiveRequest(OTHER_PLAYER, "same text", 1_000_000_000L),
                new SmokeRequestAdmission.LiveRequest(PLAYER, "different text", 1_000_000_000L),
                new SmokeRequestAdmission.LiveRequest(PLAYER, "same text", 1_000_000_000L));

        // The matching live request may be active or pending; age does not end live coalescing.
        assertEquals(SmokeRequestAdmission.Decision.COALESCE,
                decide(live, PLAYER, "same text", 3_000_000_000L, 2, 16));
        assertEquals(SmokeRequestAdmission.Decision.ADMIT,
                decide(live, PLAYER, "Same text", 3_000_000_000L, 0, 0));
        assertEquals(SmokeRequestAdmission.Decision.ADMIT,
                decide(live, OTHER_PLAYER, "different text", 1_100_000_000L, 0, 0));
    }

    @Test
    void bothPlayerAndGlobalPendingBoundsStillRejectDistinctWork() {
        assertEquals(SmokeRequestAdmission.Decision.REJECT_CAPACITY,
                decide(List.of(), PLAYER, "distinct", 10L, 2, 0));
        assertEquals(SmokeRequestAdmission.Decision.REJECT_CAPACITY,
                decide(List.of(), PLAYER, "distinct", 10L, 1, 16));
        assertEquals(SmokeRequestAdmission.Decision.ADMIT,
                decide(List.of(), PLAYER, "distinct", 10L, 1, 15));
    }

    @Test
    void aLiveDuplicateCoalescesWithoutConsumingAnotherPendingSlot() {
        List<SmokeRequestAdmission.LiveRequest> live = List.of(
                new SmokeRequestAdmission.LiveRequest(PLAYER, "work", 10L));

        assertEquals(SmokeRequestAdmission.Decision.COALESCE,
                decide(live, PLAYER, "work", 20L, 2, 16));
    }

    private static SmokeRequestAdmission.Decision decide(
            List<SmokeRequestAdmission.LiveRequest> live, UUID playerId, String text, long capturedAtNanos,
            int playerPending, int globalPending) {
        return SmokeRequestAdmission.decide(playerId, text, capturedAtNanos, live,
                playerPending, globalPending, 2, 16);
    }
}
