package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeRequestLifecycleTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000021");

    @Test
    void disconnectRetiresOldSessionAndReleasesActiveSlotBeforeDelayedReply() {
        Session originalConnection = new Session(PLAYER_ID, "minecraft:overworld", 1);
        Session rejoinedConnection = new Session(PLAYER_ID, "minecraft:overworld", 2);
        Object activeRequest = new Object();
        Object pendingRequest = new Object();
        Object rejoinedRequest = new Object();
        SmokeRequestLifecycle<Object> lifecycle = new SmokeRequestLifecycle<>();

        lifecycle.register(activeRequest, originalConnection);
        lifecycle.register(pendingRequest, originalConnection);
        assertTrue(lifecycle.start(activeRequest));
        assertSame(activeRequest, lifecycle.active());
        assertEquals(originalConnection.playerId(), rejoinedConnection.playerId());
        assertEquals(originalConnection.dimension(), rejoinedConnection.dimension());
        assertFalse(lifecycle.isBoundTo(activeRequest, rejoinedConnection));
        assertTrue(lifecycle.retireConnection(rejoinedConnection).isEmpty());
        assertSame(activeRequest, lifecycle.active(), "a new session must not retire the old session by UUID/world");

        List<Object> retired = lifecycle.retireConnection(originalConnection);

        assertEquals(2, retired.size());
        assertTrue(retired.contains(activeRequest));
        assertTrue(retired.contains(pendingRequest));
        assertNull(lifecycle.active());
        assertFalse(lifecycle.isLive(activeRequest));
        assertFalse(lifecycle.isLive(pendingRequest));
        assertFalse(lifecycle.isActiveOn(activeRequest, rejoinedConnection));

        lifecycle.register(rejoinedRequest, rejoinedConnection);
        assertTrue(lifecycle.start(rejoinedRequest), "disconnect must release the sole active slot");

        // A delayed callback for the old invocation cannot complete or clear the new session's work.
        assertFalse(lifecycle.complete(activeRequest));
        assertSame(rejoinedRequest, lifecycle.active());
        assertTrue(lifecycle.isActiveOn(rejoinedRequest, rejoinedConnection));
    }

    private record Session(UUID playerId, String dimension, int connectionGeneration) {}
}
