package org.worldspeaker.compat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttentionPolicyTest {
    @Test
    void defaultsToDisabledAndCopiesExplicitSubscriptions() {
        AttentionPolicy disabled = AttentionPolicy.disabled();
        assertFalse(disabled.enabled());
        assertEquals(32, disabled.maxEvents());
        assertEquals(30, disabled.maxAgeSeconds());
        assertEquals(8192, disabled.maxBytes());
        assertEquals(16, disabled.maxEventsPerSecond());
        assertEquals(4, disabled.maxEventsPerPlayerSecond());
        assertFalse(AttentionPolicy.parse(null, null, null, null, null, null).enabled());
        assertFalse(AttentionPolicy.parse("", null, null, null, null, null).enabled());

        LinkedHashSet<AttentionPolicy.Subscription> mutable = new LinkedHashSet<>(List.of(
                AttentionPolicy.Subscription.PLAYER_DEATH,
                AttentionPolicy.Subscription.PLAYER_CONNECTION));
        AttentionPolicy direct = new AttentionPolicy(mutable, 64, 60, 8192, 32, 8);
        mutable.clear();
        assertEquals(Set.of(AttentionPolicy.Subscription.PLAYER_DEATH,
                AttentionPolicy.Subscription.PLAYER_CONNECTION), direct.subscriptions());
        assertTrue(direct.enabled());
    }

    @Test
    void parsesOnlyUniqueExactLowercaseSubscriptionNamesAndInclusiveBounds() {
        AttentionPolicy policy = AttentionPolicy.parse(
                "player_connection,player_dimension,player_death,player_respawn,player_movement",
                "1", "1", "1024", "1", "1");
        assertEquals(5, policy.subscriptions().size());
        assertEquals(1, policy.maxEvents());
        assertEquals(1, policy.maxAgeSeconds());
        assertEquals(1024, policy.maxBytes());
        assertEquals(1, policy.maxEventsPerSecond());
        assertEquals(1, policy.maxEventsPerPlayerSecond());

        AttentionPolicy maximum = AttentionPolicy.parse("player_movement", "64", "60", "8192", "32", "8");
        assertEquals(64, maximum.maxEvents());
        assertEquals(60, maximum.maxAgeSeconds());
        assertEquals(8192, maximum.maxBytes());
        assertEquals(32, maximum.maxEventsPerSecond());
        assertEquals(8, maximum.maxEventsPerPlayerSecond());
    }

    @Test
    void rejectsMalformedNamesNumbersAndRatesWithoutEchoingValues() {
        for (String subscriptions : List.of(",player_death", "player_death,", "player_death,,player_respawn",
                "player_death,player_death", "PLAYER_DEATH", " player_death", "player_death ", "*", "all", "player_chat")) {
            assertInvalid(subscriptions, null, null, null, null, null);
        }
        for (String value : List.of("", " ", "+1", "-1", "1.0", "0", "65", "999999999999999999999999")) {
            assertInvalid("", value, null, null, null, null);
        }
        for (String value : List.of("0", "61", "1.5", "-1", "999999999999999999999")) {
            assertInvalid("", null, value, null, null, null);
        }
        for (String value : List.of("1023", "8193", "+1024", "1024.0", "999999999999999999999")) {
            assertInvalid("", null, null, value, null, null);
        }
        for (String value : List.of("0", "33", "+1", "1.0", "999999999999999999999")) {
            assertInvalid("", null, null, null, value, null);
        }
        for (String value : List.of("0", "9", "-1", "1.0", "999999999999999999999")) {
            assertInvalid("", null, null, null, null, value);
        }

        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> AttentionPolicy.parse("PRIVATE_UNKNOWN_SUBSCRIPTION", null, null, null, null, null));
        assertTrue(invalid.getMessage().contains("server.attention"));
        assertFalse(invalid.getMessage().contains("PRIVATE_UNKNOWN_SUBSCRIPTION"));
    }

    @Test
    void directConstructionCannotBypassBoundsOrPlayerRateRelation() {
        for (int[] values : List.of(
                new int[] {0, 30, 8192, 16, 4}, new int[] {65, 30, 8192, 16, 4},
                new int[] {32, 0, 8192, 16, 4}, new int[] {32, 61, 8192, 16, 4},
                new int[] {32, 30, 1023, 16, 4}, new int[] {32, 30, 8193, 16, 4},
                new int[] {32, 30, 8192, 0, 1}, new int[] {32, 30, 8192, 32, 9},
                new int[] {32, 30, 8192, 2, 3})) {
            assertThrows(IllegalArgumentException.class, () -> new AttentionPolicy(
                    Set.of(AttentionPolicy.Subscription.PLAYER_CONNECTION), values[0], values[1], values[2],
                    values[3], values[4]));
        }
        assertThrows(IllegalArgumentException.class, () -> new AttentionPolicy(null, 32, 30, 8192, 16, 4));
        Set<AttentionPolicy.Subscription> withNull = new java.util.HashSet<>();
        withNull.add(AttentionPolicy.Subscription.PLAYER_CONNECTION);
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> new AttentionPolicy(
                withNull, 32, 30, 8192, 16, 4));
    }

    private static void assertInvalid(String subscriptions, String events, String age, String bytes,
                                      String globalRate, String playerRate) {
        assertThrows(IllegalArgumentException.class, () -> AttentionPolicy.parse(
                subscriptions, events, age, bytes, globalRate, playerRate));
    }
}
