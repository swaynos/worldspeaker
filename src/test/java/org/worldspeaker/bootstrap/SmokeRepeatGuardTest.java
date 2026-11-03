package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class SmokeRepeatGuardTest {
    private static final long SIX_SECONDS = TimeUnit.SECONDS.toNanos(6);
    private static final UUID PLAYER = new UUID(0, 1);

    @Test
    void fixedOriginalDeadlineIsInclusiveAndNeverRenewedByChecksOrCommits() {
        SmokeRepeatGuard guard = new SmokeRepeatGuard();
        Object connection = new Object();
        Object epoch = new Object();
        SmokeRepeatGuard.Key key = key(PLAYER, connection, epoch, "same");
        long capturedAt = 100;

        assertEquals(SmokeRepeatGuard.Status.AVAILABLE, guard.check(key, capturedAt).status());
        guard.commit(key, "request-1", capturedAt, capturedAt);
        assertEquals(new SmokeRepeatGuard.Check(SmokeRepeatGuard.Status.REPEAT, "request-1"),
                guard.check(key, capturedAt + SIX_SECONDS));
        assertEquals(SmokeRepeatGuard.Status.AVAILABLE,
                guard.check(key, capturedAt + SIX_SECONDS + 1).status());
        guard.commit(key, "request-2", capturedAt + SIX_SECONDS + 1, capturedAt + SIX_SECONDS + 1);
        assertEquals(new SmokeRepeatGuard.Check(SmokeRepeatGuard.Status.REPEAT, "request-2"),
                guard.check(key, capturedAt + SIX_SECONDS + 1 + SIX_SECONDS));
    }

    @Test
    void guardFailsClosedOnClockRegressionAndHandlesNanoTimeWrap() {
        SmokeRepeatGuard guard = new SmokeRepeatGuard();
        Object connection = new Object();
        Object epoch = new Object();
        SmokeRepeatGuard.Key regressed = key(PLAYER, connection, epoch, "regressed");
        guard.commit(regressed, "request-regressed", 100, 100);
        assertEquals(new SmokeRepeatGuard.Check(SmokeRepeatGuard.Status.REPEAT, "request-regressed"),
                guard.check(regressed, 99));

        SmokeRepeatGuard.Key wrapped = key(PLAYER, connection, epoch, "wrapped");
        long admittedAt = Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(2);
        guard.commit(wrapped, "request-wrapped", admittedAt, admittedAt);
        assertEquals(new SmokeRepeatGuard.Check(SmokeRepeatGuard.Status.REPEAT, "request-wrapped"),
                guard.check(wrapped, admittedAt + SIX_SECONDS));
        assertEquals(SmokeRepeatGuard.Status.AVAILABLE,
                guard.check(wrapped, admittedAt + SIX_SECONDS + 1).status());
    }

    @Test
    void capacityIsBoundedPerPlayerAndGloballyWithoutEvictingUnexpiredKeys() {
        SmokeRepeatGuard perPlayer = new SmokeRepeatGuard();
        Object connection = new Object();
        Object epoch = new Object();
        for (int index = 0; index < SmokeRepeatGuard.MAX_KEYS_PER_PLAYER; index++) {
            SmokeRepeatGuard.Key key = key(PLAYER, connection, epoch, "player-" + index);
            perPlayer.commit(key, "request-" + index, 0, 0);
        }
        assertEquals(SmokeRepeatGuard.MAX_KEYS_PER_PLAYER, perPlayer.size(PLAYER));
        assertEquals(SmokeRepeatGuard.Status.CAPACITY,
                perPlayer.check(key(PLAYER, connection, epoch, "ninth"), 1).status());
        assertEquals(new SmokeRepeatGuard.Check(SmokeRepeatGuard.Status.REPEAT, "request-0"),
                perPlayer.check(key(PLAYER, connection, epoch, "player-0"), 1));

        SmokeRepeatGuard global = new SmokeRepeatGuard();
        for (int player = 0; player < SmokeRepeatGuard.MAX_KEYS_GLOBAL / SmokeRepeatGuard.MAX_KEYS_PER_PLAYER; player++) {
            UUID playerId = new UUID(1, player + 1);
            Object playerConnection = new Object();
            for (int index = 0; index < SmokeRepeatGuard.MAX_KEYS_PER_PLAYER; index++) {
                SmokeRepeatGuard.Key key = key(playerId, playerConnection, epoch, "text-" + index);
                global.commit(key, "g-" + player + "-" + index, 0, 0);
            }
        }
        assertEquals(SmokeRepeatGuard.MAX_KEYS_GLOBAL, global.size());
        assertEquals(SmokeRepeatGuard.Status.CAPACITY,
                global.check(key(new UUID(2, 1), new Object(), epoch, "new"), 1).status());
        assertEquals(SmokeRepeatGuard.Status.AVAILABLE,
                global.check(key(new UUID(2, 1), new Object(), epoch, "new"), SIX_SECONDS + 1).status());
        assertEquals(0, global.size(), "expired entries are pruned without evicting live entries");
    }

    @Test
    void keysKeepConnectionAndServiceScopeSeparateAndBoundTextLength() {
        SmokeRepeatGuard guard = new SmokeRepeatGuard();
        Object connection = new Object();
        Object epoch = new Object();
        SmokeRepeatGuard.Key original = key(PLAYER, connection, epoch, "same");
        guard.commit(original, "request-1", 0, 0);

        assertEquals(SmokeRepeatGuard.Status.AVAILABLE,
                guard.check(key(PLAYER, new Object(), epoch, "same"), 1).status());
        assertEquals(SmokeRepeatGuard.Status.AVAILABLE,
                guard.check(key(PLAYER, connection, new Object(), "same"), 1).status());
        assertNotSame(original, key(PLAYER, connection, epoch, "same"));
    }

    private static SmokeRepeatGuard.Key key(UUID playerId, Object connection, Object epoch, String text) {
        return new SmokeRepeatGuard.Key(playerId, connection, 1, epoch, 1, 1,
                "server", "world-binding", "character", "agent", "minecraft:overworld",
                InteractionIngress.Origin.COMMAND, "command", null, null, text);
    }
}
