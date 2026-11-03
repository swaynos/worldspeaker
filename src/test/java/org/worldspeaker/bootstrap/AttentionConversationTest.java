package org.worldspeaker.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.AttentionPolicy;
import org.worldspeaker.compat.ConfiguredBinding;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the same production attachment seam used by SmokeConversation.startNextOne. */
class AttentionConversationTest {
    private static final UUID A = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID EPOCH = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final ConfiguredBinding BINDING = new ConfiguredBinding("server", "world", "character", "agent");

    @Test
    void onlyTheAdmittedRequestersRecordsEnterTheirNextFrameAndRefusalDoesNotConsumeThem() {
        AttentionBuffer buffer = new AttentionBuffer(new AttentionPolicy(
                Set.of(AttentionPolicy.Subscription.PLAYER_CONNECTION), 32, 30, 8192, 16, 4),
                Set.of(A, B), BINDING, EPOCH);
        assertTrue(buffer.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 10, () -> event(A, 1)));
        assertTrue(buffer.capture(B, AttentionPolicy.Subscription.PLAYER_CONNECTION, 10, () -> event(B, 2)));

        AttentionAttachment.Prepared refused = prepare(buffer, A, 10);
        JsonArray attachedA = refused.frame().getAsJsonArray("contextBatch");
        assertEquals(1, attachedA.size());
        assertEquals(A.toString(), attachedA.get(0).getAsJsonObject().getAsJsonObject("subject")
                .get("playerId").getAsString());
        assertEquals("incomplete", refused.frame().getAsJsonObject("contextStatus").get("state").getAsString());
        assertFalse(refused.enqueue(() -> false));
        assertEquals(2, buffer.retainedCount(), "queue refusal preserves A and B records");

        AttentionAttachment.Prepared accepted = prepare(buffer, A, 11);
        AtomicInteger enqueueCalls = new AtomicInteger();
        assertTrue(accepted.enqueue(() -> { enqueueCalls.incrementAndGet(); return true; }));
        assertEquals(1, enqueueCalls.get());
        assertEquals(1, buffer.retainedCount(), "only A's selected record is removed after acceptance");
        assertTrue(buffer.select(A, 11, 8, 8192).events().isEmpty());
        assertEquals(B, buffer.select(B, 11, 8, 8192).events().get(0).playerId());

        AttentionAttachment.Prepared forB = prepare(buffer, B, 11);
        JsonObject bFrame = forB.frame();
        assertEquals(B.toString(), bFrame.getAsJsonArray("contextBatch").get(0).getAsJsonObject()
                .getAsJsonObject("subject").get("playerId").getAsString());
        assertTrue(forB.enqueue(() -> true));
        assertEquals(0, buffer.retainedCount());
        assertThrows(IllegalStateException.class, () -> forB.enqueue(() -> true),
                "the queue seam cannot accidentally consume twice");
    }

    @Test
    void framePressureOmitsOptionalRecordsAndReportsLossWithoutChangingPrimaryInput() {
        AttentionBuffer buffer = new AttentionBuffer(new AttentionPolicy(
                Set.of(AttentionPolicy.Subscription.PLAYER_CONNECTION), 32, 30, 8192, 16, 4),
                Set.of(A), BINDING, EPOCH);
        assertTrue(buffer.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 10, () -> event(A, 1)));

        AttentionAttachment.Prepared prepared = AttentionAttachment.prepare(buffer, A, 10, (events, status) -> {
            if (!events.isEmpty()) throw new IllegalArgumentException("synthetic complete-frame bound");
            JsonObject frame = new JsonObject();
            frame.addProperty("primaryText", "immutable original prompt");
            frame.add("contextBatch", new JsonArray());
            JsonObject wireStatus = new JsonObject();
            wireStatus.addProperty("state", status.state());
            wireStatus.addProperty("lossRevision", status.lossRevision());
            JsonArray reasons = new JsonArray();
            status.reasons().forEach(reasons::add);
            wireStatus.add("reasons", reasons);
            frame.add("contextStatus", wireStatus);
            return frame;
        });
        JsonObject frame = prepared.frame();
        assertEquals("immutable original prompt", frame.get("primaryText").getAsString());
        assertTrue(frame.getAsJsonArray("contextBatch").isEmpty());
        assertEquals("incomplete", frame.getAsJsonObject("contextStatus").get("state").getAsString());
        assertEquals(List.of("epoch_start", "frame"), frame.getAsJsonObject("contextStatus").getAsJsonArray("reasons")
                .asList().stream().map(com.google.gson.JsonElement::getAsString).toList());
        assertEquals(1, buffer.retainedCount(), "omitted optional context stays available until a request is accepted");
    }

    private static AttentionAttachment.Prepared prepare(AttentionBuffer buffer, UUID requester, long now) {
        return AttentionAttachment.prepare(buffer, requester, now, (events, status) -> {
            JsonObject frame = new JsonObject();
            frame.addProperty("requester", requester.toString());
            JsonArray batch = new JsonArray();
            events.forEach(event -> batch.add(event.toJson()));
            frame.add("contextBatch", batch);
            JsonObject wireStatus = new JsonObject();
            wireStatus.addProperty("state", status.state());
            wireStatus.addProperty("lossRevision", status.lossRevision());
            JsonArray reasons = new JsonArray();
            status.reasons().forEach(reasons::add);
            wireStatus.add("reasons", reasons);
            frame.add("contextStatus", wireStatus);
            return frame;
        });
    }

    private static AttentionEvent event(UUID playerId, long wallMillis) {
        return new AttentionEvent(BINDING.serverId(), BINDING.worldId(), BINDING.characterId(), BINDING.agentId(),
                UUID.randomUUID(), EPOCH, wallMillis, 10, "live", "server_callback", playerId,
                playerId.equals(A) ? "PlayerA" : "PlayerB", "minecraft:overworld", "joined",
                AttentionEvent.Details.empty());
    }
}
