package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.ConfiguredBinding;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerFreshnessTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final ConfiguredBinding BINDING = new ConfiguredBinding("server", "world", "character", "agent");

    @Test
    void capturesIdentityAndPositionAndRotatesOnEveryBlockDisplacement() {
        PlayerFreshness freshness = tracker(PLAYER);
        Object entity = new Object();
        Object connection = new Object();
        PlayerFreshness.Snapshot initial = freshness.observe(PLAYER, observation(entity, connection,
                "minecraft:overworld", true, 0, 64, 0));
        assertEquals(PLAYER, initial.playerId());
        assertEquals(0, initial.blockX());
        assertTrue(freshness.isFresh(initial, observation(entity, connection,
                "minecraft:overworld", true, 0, 64, 0)));

        assertFalse(freshness.isFresh(initial, observation(entity, connection,
                "minecraft:overworld", true, 1, 64, 0)), "current block displacement fails the old snapshot");
        assertFalse(freshness.isFresh(initial, observation(entity, connection,
                "minecraft:overworld", true, 0, 64, 0)), "a rotated token is not restored by returning");
        PlayerFreshness.Snapshot current = freshness.current(PLAYER);
        assertNotNull(current);
        assertEquals(0, current.blockX());
        assertFalse(freshness.isFresh(initial, observation(entity, connection,
                "minecraft:overworld", true, 0, 64, 0)));
    }

    @Test
    void rotatesOnDimensionDeathRespawnAndSameUuidEntityOrConnectionReplacement() {
        PlayerFreshness freshness = tracker(PLAYER);
        Object entity = new Object();
        Object connection = new Object();
        PlayerFreshness.Snapshot original = freshness.observe(PLAYER, observation(entity, connection,
                "minecraft:overworld", true, 0, 64, 0));

        PlayerFreshness.Snapshot dimension = freshness.rotate(PLAYER, observation(entity, connection,
                "minecraft:the_nether", true, 0, 64, 0));
        assertFalse(freshness.isFresh(original, observation(entity, connection,
                "minecraft:the_nether", true, 0, 64, 0)));
        PlayerFreshness.Snapshot dead = freshness.rotate(PLAYER, observation(entity, connection,
                "minecraft:the_nether", false, 0, 64, 0));
        assertFalse(freshness.isFresh(dimension, observation(entity, connection,
                "minecraft:the_nether", false, 0, 64, 0)));
        Object respawnEntity = new Object();
        Object respawnConnection = new Object();
        PlayerFreshness.Snapshot respawn = freshness.rotate(PLAYER, observation(respawnEntity, respawnConnection,
                "minecraft:overworld", true, 0, 64, 0));
        assertFalse(freshness.isFresh(dead, observation(respawnEntity, respawnConnection,
                "minecraft:overworld", true, 0, 64, 0)));
        assertTrue(freshness.isFresh(respawn, observation(respawnEntity, respawnConnection,
                "minecraft:overworld", true, 0, 64, 0)));
        assertFalse(freshness.isFresh(respawn, observation(new Object(), respawnConnection,
                "minecraft:overworld", true, 0, 64, 0)));
        assertFalse(freshness.isFresh(respawn, observation(respawnEntity, new Object(),
                "minecraft:overworld", true, 0, 64, 0)));
    }

    @Test
    void departureCannotBeRestoredBySameUuidRejoinAndRetiresOnlyMatchingIdentity() {
        PlayerFreshness freshness = tracker(PLAYER);
        Object oldEntity = new Object();
        Object oldConnection = new Object();
        PlayerFreshness.Snapshot beforeDeparture = freshness.observe(PLAYER, observation(oldEntity, oldConnection,
                "minecraft:overworld", true, 0, 64, 0));

        assertFalse(freshness.depart(PLAYER, new Object(), oldConnection), "replacement entity is not the old retained player");
        assertTrue(freshness.depart(PLAYER, oldEntity, oldConnection));
        assertNull(freshness.current(PLAYER));
        assertFalse(freshness.depart(PLAYER, oldEntity, oldConnection), "departure is idempotent");

        Object newEntity = new Object();
        Object newConnection = new Object();
        PlayerFreshness.Snapshot rejoined = freshness.observe(PLAYER, observation(newEntity, newConnection,
                "minecraft:overworld", true, 0, 64, 0));
        assertFalse(freshness.isFresh(beforeDeparture, observation(newEntity, newConnection,
                "minecraft:overworld", true, 0, 64, 0)));
        assertTrue(freshness.isFresh(rejoined, observation(newEntity, newConnection,
                "minecraft:overworld", true, 0, 64, 0)));
    }

    @Test
    void onlyGrantedUuidSlotsAreSeededAndTheThirtyTwoSlotBoundIsExact() {
        LinkedHashSet<UUID> grants = new LinkedHashSet<>();
        for (int index = 0; index < 32; index++) grants.add(UUID.nameUUIDFromBytes(new byte[] {(byte) index}));
        PlayerFreshness freshness = new PlayerFreshness(grants, new Object(), 1, BINDING);
        assertTrue(freshness.isScoped(grants.iterator().next()));
        assertFalse(freshness.isScoped(PLAYER));
        assertNull(freshness.observe(PLAYER, observation(new Object(), new Object(), "minecraft:overworld", true, 0, 0, 0)));
        for (UUID id : grants) assertNotNull(freshness.observe(id,
                observation(new Object(), new Object(), "minecraft:overworld", true, 0, 0, 0)));
        assertEquals(32, freshness.playerIds().size());

        assertThrows(IllegalArgumentException.class, () -> new PlayerFreshness(
                Set.of(PLAYER, OTHER), null, 1, BINDING));
        LinkedHashSet<UUID> tooMany = new LinkedHashSet<>(grants);
        tooMany.add(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> new PlayerFreshness(tooMany, new Object(), 1, BINDING));
        assertThrows(IllegalArgumentException.class, () -> new PlayerFreshness(Set.of(PLAYER), new Object(), -1, BINDING));
        freshness.clear();
        assertTrue(freshness.playerIds().isEmpty());
    }

    private static PlayerFreshness tracker(UUID... players) {
        return new PlayerFreshness(Set.of(players), new Object(), 7, BINDING);
    }

    private static PlayerFreshness.Observation observation(Object entity, Object connection, String dimension,
                                                           boolean alive, int x, int y, int z) {
        return new PlayerFreshness.Observation(entity, connection, dimension, alive, x, y, z);
    }

    private static void assertNotNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNotNull(value);
    }
}
