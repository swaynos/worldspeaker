package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.ConfiguredBinding;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttentionEventTest {
    private static final ConfiguredBinding BINDING = new ConfiguredBinding("server", "world", "character", "agent");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID EPOCH = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Test
    void serializesEachExactInertEventShapeAndPreservesOriginalTimes() {
        AttentionEvent joined = event("joined", "server_callback", AttentionEvent.Details.empty(), Long.MIN_VALUE, "history");
        assertEquals("history", joined.toJson().get("eventOrigin").getAsString());
        assertEquals(Long.toString(Long.MIN_VALUE), joined.toJson().get("capturedAtMonotonicNanos").getAsString());
        assertTrue(joined.toJson().getAsJsonObject("details").isEmpty());
        assertFalse(joined.toJson().has("coordinates"));
        assertTrue(joined.encodedBytes() <= AttentionBuffer.MAX_EVENT_BYTES);
        assertTrue(joined.hasBinding(BINDING));

        AttentionEvent dimension = event("dimension_changed", "server_callback",
                AttentionEvent.Details.dimension("minecraft:overworld"), 0, "live");
        assertEquals("minecraft:overworld", dimension.toJson().getAsJsonObject("details")
                .get("originDimension").getAsString());
        AttentionEvent respawn = event("respawned", "server_callback", AttentionEvent.Details.respawn(false),
                Long.MAX_VALUE, "live");
        assertFalse(respawn.toJson().getAsJsonObject("details").get("copiedAlive").getAsBoolean());
        AttentionEvent moved = event("moved", "server_sample", AttentionEvent.Details.moved(), 17, "live");
        assertEquals("sampled_block_change", moved.toJson().getAsJsonObject("details").get("basis").getAsString());
    }

    @Test
    void rejectsInvalidKindSourceOriginTimesAndSubjectWithoutLeakingValues() {
        assertThrows(IllegalArgumentException.class, () -> event("unknown", "server_callback",
                AttentionEvent.Details.empty(), 0, "live"));
        assertThrows(IllegalArgumentException.class, () -> event("moved", "server_callback",
                AttentionEvent.Details.moved(), 0, "live"));
        assertThrows(IllegalArgumentException.class, () -> event("joined", "server_sample",
                AttentionEvent.Details.empty(), 0, "live"));
        assertThrows(IllegalArgumentException.class, () -> new AttentionEvent(BINDING.serverId(), BINDING.worldId(),
                BINDING.characterId(), BINDING.agentId(), UUID.randomUUID(), EPOCH, -1, 0, "live",
                "server_callback", PLAYER, "PlayerA", "minecraft:overworld", "joined",
                AttentionEvent.Details.empty()));
        assertThrows(IllegalArgumentException.class, () -> event("joined", "server_callback",
                AttentionEvent.Details.empty(), 0, "invented-origin"));

        assertThrows(IllegalArgumentException.class, () -> new AttentionEvent("server", "world", "character", "agent",
                UUID.randomUUID(), EPOCH, 0, 0, "live", "server_callback", PLAYER, "\u00a0", "minecraft:overworld",
                "joined", AttentionEvent.Details.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AttentionEvent("server", "world", "character", "agent",
                UUID.randomUUID(), EPOCH, 0, 0, "live", "server_callback", PLAYER, "P".repeat(33), "minecraft:overworld",
                "joined", AttentionEvent.Details.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AttentionEvent("server", "world", "character", "agent",
                UUID.randomUUID(), EPOCH, 0, 0, "live", "server_callback", PLAYER, "Player\n", "minecraft:overworld",
                "joined", AttentionEvent.Details.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AttentionEvent("server", "world", "character", "agent",
                UUID.randomUUID(), EPOCH, 0, 0, "live", "server_callback", PLAYER, "Player", "Overworld",
                "joined", AttentionEvent.Details.empty()));
    }

    @Test
    void detailsAreOneOfTheFourContractShapes() {
        assertThrows(IllegalArgumentException.class,
                () -> new AttentionEvent.Details("minecraft:overworld", true, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AttentionEvent.Details(null, null, "block_change"));
        assertThrows(IllegalArgumentException.class, () -> event("joined", "server_callback",
                AttentionEvent.Details.respawn(true), 0, "live"));
        assertThrows(IllegalArgumentException.class, () -> event("dimension_changed", "server_callback",
                AttentionEvent.Details.empty(), 0, "live"));
    }

    private static AttentionEvent event(String kind, String source, AttentionEvent.Details details,
                                        long monotonic, String origin) {
        return new AttentionEvent(BINDING.serverId(), BINDING.worldId(), BINDING.characterId(), BINDING.agentId(),
                UUID.randomUUID(), EPOCH, 1_729_500_000_000L, monotonic, origin, source, PLAYER, "PlayerA",
                "minecraft:overworld", kind, details);
    }
}
