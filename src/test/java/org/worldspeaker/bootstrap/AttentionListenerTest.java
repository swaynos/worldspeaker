package org.worldspeaker.bootstrap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AttentionListenerTest {
    @Test
    void productionFactoriesCaptureMonotonicThenWallTimeAndRejectMissingCallbackOwnership() {
        TestAccess access = new TestAccess();

        AttentionListener.createJoinCallback(access).onPlayReady(null, null, null);
        assertEquals(List.of("monotonic", "wall", "active-epoch"), access.calls);
        assertEquals(0, access.conversationReads);

        access.calls.clear();
        AttentionListener.createDisconnectCallback(access).onPlayDisconnect(null, null);
        assertEquals(List.of("monotonic", "wall", "active-epoch"), access.calls);
        assertEquals(0, access.conversationReads);

        access.calls.clear();
        AttentionListener.createDimensionCallback(access).afterChangeWorld(null, null, null);
        assertEquals(List.of("monotonic", "wall", "active-epoch"), access.calls);
        assertEquals(0, access.conversationReads);

        access.calls.clear();
        AttentionListener.createDeathCallback(access).afterDeath(null, null);
        assertEquals(List.of(), access.calls, "non-player/null death is rejected before time or game-object reads");

        access.calls.clear();
        AttentionListener.createRespawnCallback(access).afterRespawn(null, null, false);
        assertEquals(List.of("monotonic", "wall", "active-epoch"), access.calls);
        assertEquals(0, access.conversationReads);

        access.calls.clear();
        AttentionListener.createEndTickCallback(access, (server, epoch) -> access.tickCalls++).onEndTick(null);
        assertEquals(List.of("active-epoch"), access.calls);
        assertEquals(0, access.tickCalls);
    }

    @Test
    void callbackFactoriesAreTheFabricListenerTypesAndPreserveTimestampOrder() {
        TestAccess access = new TestAccess();
        assertNull(access.conversation(access.epoch));
        assertEquals(100, access.monotonicNanos());
        assertEquals(200, access.wallTimeMillis());
        assertEquals(1, access.conversationReads);
    }

    private static final class TestAccess implements AttentionListener.Access {
        private final Object epoch = new Object();
        private final List<String> calls = new ArrayList<>();
        private int conversationReads;
        private int tickCalls;

        @Override public long monotonicNanos() { calls.add("monotonic"); return 100; }
        @Override public long wallTimeMillis() { calls.add("wall"); return 200; }
        @Override public boolean isServerThread() { calls.add("server-thread"); return false; }
        @Override public Object activeEpoch() { calls.add("active-epoch"); return epoch; }
        @Override public boolean isCurrentEpoch(Object candidate) { calls.add("epoch-current"); return false; }
        @Override public boolean ownsServer(Object candidate, MinecraftServer server) { calls.add("owns-server"); return false; }
        @Override public MinecraftServer currentServer(Object candidate, ServerPlayerEntity player) {
            calls.add("current-server");
            return null;
        }
        @Override public SmokeConversation conversation(Object candidate) {
            calls.add("conversation");
            conversationReads++;
            return null;
        }
    }
}
