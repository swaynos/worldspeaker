package org.worldspeaker.integration;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.worldspeaker.bootstrap.AttributedEvent;
import org.worldspeaker.bootstrap.AttentionBuffer;
import org.worldspeaker.bootstrap.AttentionEvent;
import org.worldspeaker.bootstrap.ConversationProgress;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.runtime.GameLoopMailbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DialogueEnvelopeTest {
    private static final String REQUEST_ID = "00000000-0000-4000-8000-000000000031";
    private static final String INVOCATION_ID = "00000000-0000-4000-8000-000000000032";
    private static final String PLAYER_ID = "00000000-0000-4000-8000-000000000033";
    private static final String EVENT_ID = "00000000-0000-4000-8000-000000000034";
    private static final String EPOCH_ID = "00000000-0000-4000-8000-000000000035";
    private static final String CONVERSATION_ID = "00000000-0000-4000-8000-000000000036";
    private static final String MESSAGE_ID = "00000000-0000-4000-8000-000000000037";
    private static final String FIXTURE_PLAYER_ID = "00000000-0000-4000-8000-000000000005";
    private static final ConfiguredBinding BINDING = new ConfiguredBinding("server", "world", "character", "agent");
    private static final ConfiguredBinding FIXTURE_BINDING = new ConfiguredBinding(
            "fixture-server", "fixture-world", "fixture-character", "fixture-agent");

    @Test
    void requestPreservesTheImmutableCaptureAndExactRequesterAudience() {
        AttributedEvent event = event("live", "followup", "Why?");
        JsonObject acknowledgement = DialogueEnvelope.acknowledgement(MESSAGE_ID, "I have your request.",
                1_729_500_000_001L, Long.MIN_VALUE + 1, PLAYER_ID);

        JsonObject request = DialogueEnvelope.request(REQUEST_ID, INVOCATION_ID, event,
                CONVERSATION_ID, MESSAGE_ID, acknowledgement);
        DialogueEnvelope.validateRequest(request, PLAYER_ID, BINDING);

        assertEquals(DialogueEnvelope.PROTOCOL, request.get("protocol").getAsString());
        assertEquals(EVENT_ID, request.getAsJsonObject("event").get("eventId").getAsString());
        assertEquals(EPOCH_ID, request.getAsJsonObject("event").get("serverEpochId").getAsString());
        assertEquals(Long.toString(Long.MIN_VALUE), request.getAsJsonObject("event")
                .get("capturedAtMonotonicNanos").getAsString());
        assertEquals("TestPlayer", request.getAsJsonObject("event").get("playerName").getAsString());
        assertEquals(CONVERSATION_ID, request.getAsJsonObject("conversation").get("conversationId").getAsString());
        assertEquals(MESSAGE_ID, request.getAsJsonObject("conversation").get("replyToMessageId").getAsString());
        assertEquals(PLAYER_ID, request.getAsJsonObject("replyAudience").get("playerId").getAsString());
    }

    @Test
    void requestCarriesStrictInertAttentionContextAndLossStatusWithoutChangingThePrimary() {
        AttributedEvent primary = event("live", "command", "Original prompt");
        AttentionEvent observation = new AttentionEvent(BINDING.serverId(), BINDING.worldId(), BINDING.characterId(),
                BINDING.agentId(), UUID.fromString("00000000-0000-4000-8000-000000000039"), UUID.fromString(EPOCH_ID),
                1_729_500_000_123L, Long.MIN_VALUE + 3, "history", "server_callback", UUID.fromString(PLAYER_ID),
                "TestPlayer", "minecraft:overworld", "respawned", AttentionEvent.Details.respawn(false));
        AttentionBuffer.ContextStatus status = new AttentionBuffer.ContextStatus("incomplete", "1", 1,
                List.of("epoch_start"), Set.of(AttentionBuffer.LossReason.EPOCH_START));
        JsonObject request = DialogueEnvelope.request(REQUEST_ID, INVOCATION_ID, primary, CONVERSATION_ID,
                null, null, List.of(observation), status);

        DialogueEnvelope.validateRequest(request, PLAYER_ID, BINDING);
        assertEquals("Original prompt", request.getAsJsonObject("event").get("text").getAsString());
        JsonObject context = request.getAsJsonArray("contextBatch").get(0).getAsJsonObject();
        assertEquals("respawned", context.get("eventKind").getAsString());
        assertEquals("history", context.get("eventOrigin").getAsString());
        assertEquals("live", request.getAsJsonObject("event").get("eventOrigin").getAsString(),
                "historical context cannot become the primary dispatched event");
        assertEquals("-9223372036854775805", context.get("capturedAtMonotonicNanos").getAsString());
        assertFalse(context.has("playerId"), "attention identity is nested under subject, not authority");
        assertFalse(context.has("coordinates"));
        assertFalse(context.getAsJsonObject("details").get("copiedAlive").getAsBoolean());
        assertEquals("incomplete", request.getAsJsonObject("contextStatus").get("state").getAsString());
        assertEquals("1", request.getAsJsonObject("contextStatus").get("lossRevision").getAsString());

        JsonObject extraDetail = request.deepCopy();
        extraDetail.getAsJsonArray("contextBatch").get(0).getAsJsonObject().getAsJsonObject("details")
                .addProperty("coordinates", "not-allowed");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(extraDetail, PLAYER_ID, BINDING));

        JsonObject invalidStatus = request.deepCopy();
        invalidStatus.getAsJsonObject("contextStatus").getAsJsonArray("reasons").add("epoch_start");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(invalidStatus, PLAYER_ID, BINDING));

        JsonObject wrongSource = request.deepCopy();
        wrongSource.getAsJsonArray("contextBatch").get(0).getAsJsonObject().addProperty("source", "server_sample");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(wrongSource, PLAYER_ID, BINDING));
    }

    @Test
    void rejectsVersionAudienceBindingAndUnknownAuthorityFields() {
        JsonObject request = DialogueEnvelope.request(REQUEST_ID, INVOCATION_ID,
                event("live", "command", "A prompt"), CONVERSATION_ID, null, null);
        JsonObject wrongVersion = request.deepCopy();
        wrongVersion.addProperty("protocol", "worldspeaker.compat.v1");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(wrongVersion, PLAYER_ID, BINDING));
        JsonObject wrongAudience = request.deepCopy();
        wrongAudience.getAsJsonObject("replyAudience").addProperty("playerId", "00000000-0000-4000-8000-000000000038");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(wrongAudience, PLAYER_ID, BINDING));
        JsonObject extra = request.deepCopy();
        extra.add("policy", new JsonObject());
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(extra, PLAYER_ID, BINDING));
    }

    @Test
    void requestAndActiveProgressUseExactV5AttributionPhaseAndDistinctIds() {
        ConversationProgress.DialogueProgress queued = new ConversationProgress.DialogueProgress(
                MESSAGE_ID, "[Worldspeaker] Archivist: The request is still queued.",
                1_729_500_000_001L, Long.MIN_VALUE + 1, PLAYER_ID, ConversationProgress.Phase.QUEUED);
        JsonObject request = DialogueEnvelope.request(REQUEST_ID, INVOCATION_ID,
                event("live", "command", "Original prompt"), CONVERSATION_ID, null, null,
                List.of(), new AttentionBuffer.ContextStatus("disabled", "0", 0, List.of(), Set.of()), List.of(queued));
        DialogueEnvelope.validateRequest(request, PLAYER_ID, BINDING);
        assertEquals("queued", request.getAsJsonArray("progressContext").get(0).getAsJsonObject().get("phase").getAsString());

        JsonObject active = DialogueEnvelope.progress(REQUEST_ID, INVOCATION_ID,
                new ConversationProgress.DialogueProgress("00000000-0000-4000-8000-000000000040",
                        "[Worldspeaker] Archivist: Your request has been forwarded and I am still waiting for a complete answer.",
                        1_729_500_000_002L, Long.MIN_VALUE + 2, PLAYER_ID, ConversationProgress.Phase.AWAITING_ANSWER));
        DialogueEnvelope.validateProgressFrame(active);
        JsonObject wrongPhase = active.deepCopy();
        wrongPhase.getAsJsonObject("progress").addProperty("phase", "queued");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateProgressFrame(wrongPhase));

        JsonObject duplicate = request.deepCopy();
        duplicate.getAsJsonArray("progressContext").get(0).getAsJsonObject().addProperty("messageId", MESSAGE_ID);
        duplicate.add("acknowledgement", DialogueEnvelope.acknowledgement(MESSAGE_ID, "[Worldspeaker] Archivist: I have your request.",
                1_729_500_000_000L, Long.MIN_VALUE, PLAYER_ID));
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(duplicate, PLAYER_ID, BINDING));

        JsonObject wrongPlayer = active.deepCopy();
        wrongPlayer.addProperty("playerId", "00000000-0000-4000-8000-000000000038");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateProgressFrame(wrongPlayer));
        JsonObject extra = active.deepCopy();
        extra.addProperty("text", "not an allowed root field");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateProgressFrame(extra));
    }

    @Test
    void responseRetainsInvocationCharacterAndReplyAudienceBeforeMailboxAdmission() {
        JsonObject response = new JsonObject();
        response.addProperty("type", "dialogue.message");
        response.addProperty("protocol", DialogueEnvelope.PROTOCOL);
        response.addProperty("messageId", MESSAGE_ID);
        response.addProperty("requestId", REQUEST_ID);
        response.addProperty("invocationId", INVOCATION_ID);
        response.addProperty("characterId", "character");
        JsonObject audience = new JsonObject();
        audience.addProperty("kind", "player");
        audience.addProperty("playerId", PLAYER_ID);
        response.add("replyAudience", audience);
        response.addProperty("kind", "answer");
        response.addProperty("text", "A complete answer");

        GameLoopMailbox.TerminalFrame terminal = DialogueEnvelope.terminal(response,
                new GameLoopMailbox.ActiveRequest(REQUEST_ID, INVOCATION_ID, "character", PLAYER_ID, 7), 7, 99);
        assertEquals(REQUEST_ID, terminal.requestId());
        assertEquals(INVOCATION_ID, terminal.invocationId());
        assertEquals("character", terminal.characterId());
        assertEquals(PLAYER_ID, terminal.audiencePlayerId());
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.terminal(response,
                new GameLoopMailbox.ActiveRequest(REQUEST_ID, INVOCATION_ID, "character", "00000000-0000-4000-8000-000000000038", 7), 7, 99));
    }

    @Test
    void sharedV5FixturesPassTheJavaProductionParserAndEnvelopeValidators() throws Exception {
        JsonObject fixtures = StrictJsonParser.parse(Files.readString(
                Path.of("compatibility/dialogue-v5-fixtures.json"))).getAsJsonObject();
        JsonObject request = fixtures.getAsJsonObject("request");
        assertDoesNotThrow(() -> DialogueEnvelope.validateRequest(request, FIXTURE_PLAYER_ID, FIXTURE_BINDING));

        GameLoopMailbox.ActiveRequest active = new GameLoopMailbox.ActiveRequest(
                request.get("requestId").getAsString(), request.get("invocationId").getAsString(),
                request.get("characterId").getAsString(), FIXTURE_PLAYER_ID, 7);
        GameLoopMailbox.TerminalFrame message = DialogueEnvelope.terminal(
                fixtures.getAsJsonObject("message"), active, 7, 99);
        GameLoopMailbox.TerminalFrame failure = DialogueEnvelope.terminal(
                fixtures.getAsJsonObject("failure"), active, 7, 100);
        assertEquals("answer", message.kind());
        assertEquals("It was beside the river.", message.text());
        assertEquals("failure", failure.kind());

        assertDoesNotThrow(() -> DialogueEnvelope.validateProgressFrame(fixtures.getAsJsonObject("progress")));
    }

    @Test
    void sharedPublicBodyUnicodeCorpusAgreesWithTheJavaTerminalParser() throws Exception {
        JsonObject fixtures = StrictJsonParser.parse(Files.readString(
                Path.of("compatibility/dialogue-v5-fixtures.json"))).getAsJsonObject();
        JsonObject corpus = StrictJsonParser.parse(Files.readString(
                Path.of("compatibility/dialogue-v4-public-body-corpus.json"))).getAsJsonObject();
        JsonObject request = fixtures.getAsJsonObject("request");
        GameLoopMailbox.ActiveRequest active = new GameLoopMailbox.ActiveRequest(
                request.get("requestId").getAsString(), request.get("invocationId").getAsString(),
                request.get("characterId").getAsString(), FIXTURE_PLAYER_ID, 7);

        for (var item : corpus.getAsJsonArray("cases")) {
            JsonObject testCase = item.getAsJsonObject();
            String name = testCase.get("name").getAsString();
            String text = testCase.get("text").getAsString();
            boolean accepted = testCase.get("accepted").getAsBoolean();
            JsonObject message = fixtures.getAsJsonObject("message").deepCopy();
            message.addProperty("text", text);

            assertEquals(accepted, org.worldspeaker.bootstrap.VanillaDialogue.isRenderableAnswerBody(text),
                    name + ": Java public-body predicate");
            if (accepted) {
                assertDoesNotThrow(() -> DialogueEnvelope.terminal(message, active, 7, 99), name);
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> DialogueEnvelope.terminal(message, active, 7, 99), name);
            }
        }
    }

    @Test
    void terminalParserUsesTheFullUtf16BodyLimitWithoutTruncation() throws Exception {
        JsonObject fixtures = StrictJsonParser.parse(Files.readString(
                Path.of("compatibility/dialogue-v5-fixtures.json"))).getAsJsonObject();
        JsonObject request = fixtures.getAsJsonObject("request");
        GameLoopMailbox.ActiveRequest active = new GameLoopMailbox.ActiveRequest(
                request.get("requestId").getAsString(), request.get("invocationId").getAsString(),
                request.get("characterId").getAsString(), FIXTURE_PLAYER_ID, 7);

        JsonObject atLimit = fixtures.getAsJsonObject("message").deepCopy();
        String exactBody = "👩‍🚀".repeat(396);
        assertEquals(1980, exactBody.length());
        atLimit.addProperty("text", exactBody);
        assertEquals(exactBody, DialogueEnvelope.terminal(atLimit, active, 7, 99).text());

        JsonObject overLimit = fixtures.getAsJsonObject("message").deepCopy();
        overLimit.addProperty("text", "👩‍🚀".repeat(397));
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.terminal(overLimit, active, 7, 99));
    }

    @Test
    void javaProductionRequestValidatorRejectsCrossFieldAuthorityAndReplayConflicts() throws Exception {
        JsonObject fixture = StrictJsonParser.parse(Files.readString(
                Path.of("compatibility/dialogue-v5-fixtures.json"))).getAsJsonObject().getAsJsonObject("request");

        JsonObject protocol = fixture.deepCopy();
        protocol.addProperty("protocol", "worldspeaker.compat.v1");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(protocol, FIXTURE_PLAYER_ID, FIXTURE_BINDING));

        JsonObject v2Peer = fixture.deepCopy();
        v2Peer.addProperty("protocol", "worldspeaker.compat.v2");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(v2Peer, FIXTURE_PLAYER_ID, FIXTURE_BINDING));

        JsonObject v3Peer = fixture.deepCopy();
        v3Peer.addProperty("protocol", "worldspeaker.compat.v3");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(v3Peer, FIXTURE_PLAYER_ID, FIXTURE_BINDING));

        JsonObject v4Peer = fixture.deepCopy();
        v4Peer.addProperty("protocol", "worldspeaker.compat.v4");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(v4Peer, FIXTURE_PLAYER_ID, FIXTURE_BINDING));

        JsonObject extra = fixture.deepCopy();
        extra.add("policy", new JsonObject());
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(extra, FIXTURE_PLAYER_ID, FIXTURE_BINDING));

        JsonObject duplicateIdentity = fixture.deepCopy();
        duplicateIdentity.addProperty("invocationId", duplicateIdentity.get("requestId").getAsString());
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(duplicateIdentity, FIXTURE_PLAYER_ID, FIXTURE_BINDING));

        JsonObject historyPrimary = fixture.deepCopy();
        historyPrimary.getAsJsonObject("event").addProperty("eventOrigin", "history");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(historyPrimary, FIXTURE_PLAYER_ID, FIXTURE_BINDING));

        JsonObject historicalContext = fixture.deepCopy();
        historicalContext.getAsJsonArray("contextBatch").get(1).getAsJsonObject().addProperty("eventOrigin", "history");
        assertDoesNotThrow(() -> DialogueEnvelope.validateRequest(historicalContext, FIXTURE_PLAYER_ID, FIXTURE_BINDING));
        assertEquals("live", historicalContext.getAsJsonObject("event").get("eventOrigin").getAsString());

        JsonObject wrongAudience = fixture.deepCopy();
        wrongAudience.getAsJsonObject("replyAudience").addProperty("playerId", "00000000-0000-4000-8000-00000000000c");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(wrongAudience, FIXTURE_PLAYER_ID, FIXTURE_BINDING));

        JsonObject duplicateContextEvent = fixture.deepCopy();
        duplicateContextEvent.getAsJsonArray("contextBatch").get(0).getAsJsonObject()
                .addProperty("eventId", duplicateContextEvent.getAsJsonObject("event").get("eventId").getAsString());
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(duplicateContextEvent, FIXTURE_PLAYER_ID, FIXTURE_BINDING));

        JsonObject crossBindingContext = fixture.deepCopy();
        crossBindingContext.getAsJsonArray("contextBatch").get(0).getAsJsonObject().addProperty("worldId", "another-world");
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(crossBindingContext, FIXTURE_PLAYER_ID, FIXTURE_BINDING));

        JsonObject wrongAcknowledgementAudience = fixture.deepCopy();
        wrongAcknowledgementAudience.getAsJsonObject("acknowledgement").getAsJsonObject("replyAudience")
                .addProperty("playerId", "00000000-0000-4000-8000-00000000000c");
        assertThrows(IllegalArgumentException.class,
                () -> DialogueEnvelope.validateRequest(wrongAcknowledgementAudience, FIXTURE_PLAYER_ID, FIXTURE_BINDING));
    }

    @Test
    void retainedV4RequestFixtureIsRejectedWithoutProtocolDowngrade() throws Exception {
        JsonObject fixtures = StrictJsonParser.parse(Files.readString(
                Path.of("compatibility/dialogue-v4-fixtures.json"))).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(
                fixtures.getAsJsonObject("request"), FIXTURE_PLAYER_ID, FIXTURE_BINDING));
    }

    private static AttributedEvent event(String origin, String inputKind, String text) {
        return new AttributedEvent("server", "world", "character", "agent", UUID.fromString(EVENT_ID), UUID.fromString(EPOCH_ID),
                1_729_500_000_000L, Long.MIN_VALUE, origin, inputKind, UUID.fromString(PLAYER_ID), "TestPlayer",
                "minecraft:overworld", text);
    }
}
