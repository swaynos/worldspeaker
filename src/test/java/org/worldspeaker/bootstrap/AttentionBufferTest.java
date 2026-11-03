package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.AttentionPolicy;
import org.worldspeaker.compat.ConfiguredBinding;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttentionBufferTest {
    private static final UUID A = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID EPOCH = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final ConfiguredBinding BINDING = new ConfiguredBinding("server", "world", "character", "agent");

    @Test
    void disabledAndOutOfScopeCaptureDoNotAllocatePayloads() {
        AttentionBuffer disabled = new AttentionBuffer(AttentionPolicy.disabled(), Set.of(A), BINDING, EPOCH);
        AtomicInteger factoryCalls = new AtomicInteger();
        assertFalse(disabled.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 0,
                () -> { factoryCalls.incrementAndGet(); return event(A, 1); }));
        assertFalse(disabled.capture(B, AttentionPolicy.Subscription.PLAYER_CONNECTION, 0,
                () -> { factoryCalls.incrementAndGet(); return event(B, 2); }));
        assertFalse(disabled.capture(A, AttentionPolicy.Subscription.PLAYER_DEATH, 0,
                () -> { factoryCalls.incrementAndGet(); return event(A, 3); }));
        assertEquals(0, factoryCalls.get());
        assertEquals("disabled", disabled.select(A, 0, 8, 8192).status().state());
        assertEquals(0, disabled.retainedCount());
    }

    @Test
    void selectionIsRequesterScopedAndCommitsOnlyItsExactLossSnapshot() {
        AttentionBuffer buffer = buffer(policy(16, 8192, 32, 8, 30), Set.of(A, B));
        assertTrue(buffer.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 10, () -> event(A, 1)));
        assertTrue(buffer.capture(B, AttentionPolicy.Subscription.PLAYER_CONNECTION, 10, () -> event(B, 2)));

        AttentionBuffer.Selection a = buffer.select(A, 10, 8, 8192);
        assertEquals(1, a.events().size());
        assertEquals(A, a.events().get(0).playerId());
        assertEquals("incomplete", a.status().state());
        assertEquals(List.of("epoch_start"), a.status().reasons());

        buffer.recordTransportLoss(A);
        buffer.commit(a);
        assertEquals(1, buffer.retainedCount(), "only A's accepted record was consumed");
        AttentionBuffer.ContextStatus afterNewLoss = buffer.select(A, 11, 8, 8192).status();
        assertEquals(List.of("epoch_start", "transport"), afterNewLoss.reasons());
        assertEquals("2", afterNewLoss.lossRevision());

        AttentionBuffer.Selection b = buffer.select(B, 11, 8, 8192);
        assertEquals(B, b.events().get(0).playerId());
        assertEquals(List.of("epoch_start"), b.status().reasons());
        buffer.commit(b);
        assertEquals(0, buffer.retainedCount());
    }

    @Test
    void tokensHonorGlobalAndPerPlayerRatesWithBackwardsAndExactRefillBoundaries() {
        AttentionBuffer buffer = buffer(policy(16, 8192, 2, 2, 30), Set.of(A, B));
        AtomicInteger factories = new AtomicInteger();
        assertTrue(buffer.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 0,
                () -> { factories.incrementAndGet(); return eventAt(A, 1, 0); }));
        assertTrue(buffer.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 0,
                () -> { factories.incrementAndGet(); return eventAt(A, 2, 0); }));
        assertFalse(buffer.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 0,
                () -> { factories.incrementAndGet(); return eventAt(A, 3, 0); }), "player bucket is empty");
        assertFalse(buffer.capture(B, AttentionPolicy.Subscription.PLAYER_CONNECTION, -1,
                () -> { factories.incrementAndGet(); return eventAt(B, 4, -1); }), "backwards time does not refill global tokens");
        assertEquals(2, factories.get(), "rate-limited events are rejected before payload allocation");

        assertTrue(buffer.capture(B, AttentionPolicy.Subscription.PLAYER_CONNECTION, TimeUnit.MILLISECONDS.toNanos(500),
                () -> { factories.incrementAndGet(); return eventAt(B, 5, TimeUnit.MILLISECONDS.toNanos(500)); }),
                "half a second refills exactly one global token");
        assertEquals(3, factories.get());
        assertEquals(2, buffer.countersSnapshot()[AttentionBuffer.Counter.RATE_DROP.ordinal()]);
    }

    @Test
    void enforcesFourRecordsPerPlayerGlobalCountAndGlobalUtf8ByteCaps() {
        AttentionBuffer perPlayer = buffer(policy(64, 8192, 32, 8, 30), Set.of(A));
        for (int index = 0; index < 5; index++) {
            int eventIndex = index;
            assertTrue(perPlayer.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, index,
                    () -> event(A, eventIndex)));
        }
        assertEquals(AttentionBuffer.MAX_EVENTS_PER_PLAYER, perPlayer.select(A, 5, 8, 8192).events().size());
        assertEquals(List.of("epoch_start", "capacity"), perPlayer.select(A, 5, 8, 8192).status().reasons());
        assertEquals(1, perPlayer.countersSnapshot()[AttentionBuffer.Counter.EVICTED.ordinal()]);

        AttentionBuffer countLimited = buffer(policy(2, 8192, 32, 8, 30), Set.of(A, B));
        assertTrue(countLimited.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 0, () -> eventAt(A, 1, 0)));
        assertTrue(countLimited.capture(B, AttentionPolicy.Subscription.PLAYER_CONNECTION, 0, () -> eventAt(B, 2, 0)));
        assertTrue(countLimited.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 0, () -> eventAt(A, 3, 0)));
        assertEquals(2, countLimited.retainedCount());
        assertEquals(List.of(2L), countLimited.select(B, 1, 8, 8192).events().stream()
                .map(AttentionEvent::occurredAtUnixMillis).toList());
        assertEquals(List.of(3L), countLimited.select(A, 1, 8, 8192).events().stream()
                .map(AttentionEvent::occurredAtUnixMillis).toList());

        AttentionBuffer byteLimited = buffer(policy(64, 1024, 32, 8, 30), Set.of(A));
        for (int index = 0; index < 8; index++) {
            int eventIndex = index;
            assertTrue(byteLimited.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, index,
                    () -> event(A, eventIndex)));
            assertTrue(byteLimited.retainedBytes() <= 1024);
        }
        assertTrue(byteLimited.countersSnapshot()[AttentionBuffer.Counter.EVICTED.ordinal()] > 0,
                "byte pressure evicts old optional records instead of growing the buffer");
    }

    @Test
    void expiresAtTheExactAgeBoundaryAndFailsClosedForBackwardsSelectionTime() {
        AttentionBuffer buffer = buffer(policy(16, 8192, 16, 8, 1), Set.of(A));
        assertTrue(buffer.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 100, () -> eventAt(A, 1, 100)));
        long expiry = 100 + TimeUnit.SECONDS.toNanos(1);
        assertEquals(1, buffer.expireOne(A, expiry));
        assertTrue(buffer.select(A, expiry, 8, 8192).events().isEmpty());
        assertEquals(List.of("epoch_start", "expiry"), buffer.select(A, expiry, 8, 8192).status().reasons());

        AttentionBuffer backwards = buffer(policy(16, 8192, 16, 8, 30), Set.of(A));
        assertTrue(backwards.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 100, () -> eventAt(A, 2, 100)));
        assertTrue(backwards.select(A, 99, 8, 8192).events().isEmpty());
        assertEquals(0, backwards.retainedCount());
        assertTrue(backwards.select(A, 101, 8, 8192).status().reasons().contains("expiry"));
    }

    @Test
    void rejectsWrongEpochBindingRequesterAndSubscriptionAndKeepsPrivateLossEvidenceBounded() {
        AttentionBuffer buffer = buffer(policy(16, 8192, 16, 8, 30), Set.of(A, B));
        assertFalse(buffer.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 0,
                () -> new AttentionEvent("other", "world", "character", "agent", UUID.randomUUID(), EPOCH,
                        0, 0, "live", "server_callback", A, "PlayerA", "minecraft:overworld", "joined",
                        AttentionEvent.Details.empty())));
        assertFalse(buffer.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 1, () -> event(B, 2)));
        assertFalse(buffer.capture(A, AttentionPolicy.Subscription.PLAYER_DEATH, 2, () -> event(A, 3)));
        assertEquals(0, buffer.retainedCount());
        assertEquals("incomplete", buffer.select(A, 2, 8, 8192).status().state());
        assertTrue(buffer.select(A, 2, 8, 8192).status().reasons().contains("source_unavailable"));

        buffer.recordFrameLoss(A);
        assertTrue(buffer.select(A, 3, 0, 8192).status().reasons().contains("frame"));
        buffer.recordTransportLoss(A);
        assertTrue(buffer.diagnosticSnapshot(3).startsWith("captures=0,attached=0"));
        assertNull(buffer.diagnosticSnapshot(TimeUnit.SECONDS.toNanos(5)), "diagnostics coalesce for ten seconds");
        buffer.recordFrameLoss(A);
        assertTrue(buffer.diagnosticSnapshot(TimeUnit.SECONDS.toNanos(13)) != null);
        buffer.clearPlayer(A);
        assertNull(buffer.diagnosticSnapshot(TimeUnit.SECONDS.toNanos(23)), "clear removes the requester's loss slot");
    }

    private static AttentionBuffer buffer(AttentionPolicy policy, Set<UUID> players) {
        return new AttentionBuffer(policy, players, BINDING, EPOCH);
    }

    private static AttentionPolicy policy(int events, int bytes, int rate, int playerRate, int ageSeconds) {
        return new AttentionPolicy(Set.of(AttentionPolicy.Subscription.PLAYER_CONNECTION), events, ageSeconds,
                bytes, rate, playerRate);
    }

    private static AttentionEvent event(UUID playerId, long wallMillis) {
        return eventAt(playerId, wallMillis, wallMillis);
    }

    private static AttentionEvent eventAt(UUID playerId, long wallMillis, long monotonicNanos) {
        return new AttentionEvent(BINDING.serverId(), BINDING.worldId(), BINDING.characterId(), BINDING.agentId(),
                UUID.randomUUID(), EPOCH, wallMillis, monotonicNanos, "live", "server_callback", playerId,
                playerId.equals(A) ? "PlayerA" : "PlayerB", "minecraft:overworld", "joined",
                AttentionEvent.Details.empty());
    }
}
