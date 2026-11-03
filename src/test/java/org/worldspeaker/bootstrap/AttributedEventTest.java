package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.ConfiguredBinding;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AttributedEventTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000021");
    private static final ConfiguredBinding BINDING = new ConfiguredBinding("server", "world", "character", "agent");

    @Test
    void capturesOriginalIdentityWallTimeAndMonotonicTimeBeforeAnyLaterWork() {
        AtomicInteger order = new AtomicInteger();
        InteractionIngress.Captured captured = InteractionIngress.Captured.capture(
                () -> { assertEquals(0, order.getAndIncrement()); return Long.MIN_VALUE; },
                () -> { assertEquals(1, order.getAndIncrement()); return 1_729_500_000_000L; },
                InteractionIngress.Origin.COMMAND);
        InteractionIngress.SourceFacts speaker = new InteractionIngress.SourceFacts(
                true, true, true, PLAYER, "TestPlayer", "minecraft:overworld", new Object(), new Object());

        AttributedEvent event = AttributedEvent.live(captured, "00000000-0000-4000-8000-000000000022",
                speaker, BINDING, "minecraft:overworld", "A captured prompt");

        assertEquals(captured.eventId(), event.eventId());
        assertEquals(1_729_500_000_000L, event.occurredAtUnixMillis());
        assertEquals(Long.MIN_VALUE, event.capturedAtMonotonicNanos());
        assertEquals(PLAYER, event.playerId());
        assertEquals("TestPlayer", event.playerName());
        assertEquals("command", event.inputKind());
        assertEquals("live", event.eventOrigin());
    }

    @Test
    void refusesUntrustedOrOutOfBoundCaptureFacts() {
        UUID eventId = UUID.fromString("00000000-0000-4000-8000-000000000023");
        UUID epochId = UUID.fromString("00000000-0000-4000-8000-000000000024");
        AttributedEvent historical = new AttributedEvent("server", "world", "character", "agent", eventId, epochId, 0, 0,
                "history", "followup", PLAYER, "TestPlayer", "minecraft:overworld", "captured earlier");
        assertEquals("history", historical.eventOrigin());
        assertThrows(IllegalArgumentException.class, () -> new AttributedEvent(
                "server", "world", "character", "agent", eventId, epochId, 0, 0,
                "live", "command", PLAYER, "TestPlayer", "minecraft:overworld", "\u0001unsafe"));
        assertThrows(IllegalArgumentException.class, () -> new AttributedEvent(
                "server", "world", "character", "agent", eventId, epochId, 0, 0,
                "live", "command", PLAYER, "TestPlayer", "minecraft:overworld", " "));
    }
}
