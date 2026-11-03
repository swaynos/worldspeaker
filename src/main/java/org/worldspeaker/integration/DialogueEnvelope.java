package org.worldspeaker.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.worldspeaker.bootstrap.AttributedEvent;
import org.worldspeaker.bootstrap.AttentionEvent;
import org.worldspeaker.bootstrap.AttentionBuffer;
import org.worldspeaker.bootstrap.ConversationProgress;
import org.worldspeaker.bootstrap.ProtocolText;
import org.worldspeaker.bootstrap.VanillaDialogue;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.runtime.GameLoopMailbox;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.regex.Pattern;

/** Production v5 wire DTO creation and validation shared by the JDK peer and mailbox. */
public final class DialogueEnvelope {
    public static final String PROTOCOL = "worldspeaker.compat.v5";
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private static final BigDecimal MAX_SAFE_MILLIS = BigDecimal.valueOf(9_007_199_254_740_991L);
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Set<String> EVENT_FIELDS = Set.of("serverId", "worldId", "characterId", "agentId", "eventId",
            "serverEpochId", "occurredAtUnixMillis", "capturedAtMonotonicNanos", "eventOrigin", "inputKind", "playerId",
            "playerName", "dimension", "text");
    private static final Set<String> ATTENTION_FIELDS = Set.of("serverId", "worldId", "characterId", "agentId", "eventId",
            "serverEpochId", "occurredAtUnixMillis", "capturedAtMonotonicNanos", "eventOrigin", "source", "subject",
            "dimension", "eventKind", "details");
    private static final Set<String> CONTEXT_STATUS_FIELDS = Set.of("state", "lossRevision", "reasons");
    private static final Set<String> LOSS_REASONS = Set.of("epoch_start", "expiry", "capacity", "rate", "bytes", "frame",
            "transport", "source_unavailable");
    private static final Set<String> AUDIENCE_FIELDS = Set.of("kind", "playerId");
    private static final Set<String> ACK_FIELDS = Set.of("messageId", "text", "dispatchedAtUnixMillis",
            "dispatchedAtMonotonicNanos", "replyAudience");
    private static final Set<String> PROGRESS_FIELDS = Set.of("messageId", "text", "dispatchedAtUnixMillis",
            "dispatchedAtMonotonicNanos", "replyAudience", "phase");
    private static final Set<String> PROGRESS_FRAME_FIELDS = Set.of("type", "protocol", "requestId", "invocationId",
            "playerId", "progress");
    private static final Set<String> MESSAGE_FIELDS = Set.of("type", "protocol", "messageId", "requestId", "invocationId",
            "characterId", "replyAudience", "kind", "text");
    private static final Set<String> FAILURE_FIELDS = Set.of("type", "protocol", "requestId", "invocationId",
            "characterId", "replyAudience", "reason");

    private DialogueEnvelope() {}

    public static JsonObject request(String requestId, String invocationId, AttributedEvent event,
                                     String conversationId, String replyToMessageId,
                                     JsonObject acknowledgement) {
        return request(requestId, invocationId, event, conversationId, replyToMessageId, acknowledgement,
                List.of(), new AttentionBuffer.ContextStatus("disabled", "0", 0, List.of(), Set.of()), List.of());
    }

    public static JsonObject request(String requestId, String invocationId, AttributedEvent event,
                                     String conversationId, String replyToMessageId,
                                     JsonObject acknowledgement, List<AttentionEvent> contextEvents,
                                     AttentionBuffer.ContextStatus contextStatus) {
        return request(requestId, invocationId, event, conversationId, replyToMessageId, acknowledgement,
                contextEvents, contextStatus, List.of());
    }

    public static JsonObject request(String requestId, String invocationId, AttributedEvent event,
                                     String conversationId, String replyToMessageId,
                                     JsonObject acknowledgement, List<AttentionEvent> contextEvents,
                                     AttentionBuffer.ContextStatus contextStatus,
                                     List<ConversationProgress.DialogueProgress> progressContext) {
        return request(requestId, invocationId, event, conversationId, replyToMessageId, acknowledgement,
                contextEvents, contextStatus, progressContext, 0);
    }

    public static JsonObject request(String requestId, String invocationId, AttributedEvent event,
                                     String conversationId, String replyToMessageId,
                                     JsonObject acknowledgement, List<AttentionEvent> contextEvents,
                                     AttentionBuffer.ContextStatus contextStatus,
                                     List<ConversationProgress.DialogueProgress> progressContext,
                                     long abilityPolicyRevision) {
        requireUuid(requestId);
        requireUuid(invocationId);
        if (requestId.equals(invocationId) || event == null || !"live".equals(event.eventOrigin()) ||
                abilityPolicyRevision < 0 || abilityPolicyRevision > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("live request identity is invalid");
        }
        requireUuid(conversationId);
        if ("followup".equals(event.inputKind())) requireUuid(replyToMessageId);
        else if (replyToMessageId != null) throw new IllegalArgumentException("only follow-ups may carry a reply anchor");

        JsonObject frame = new JsonObject();
        frame.addProperty("type", "dialogue.request");
        frame.addProperty("protocol", PROTOCOL);
        frame.addProperty("requestId", requestId);
        frame.addProperty("invocationId", invocationId);
        frame.addProperty("abilityPolicyRevision", abilityPolicyRevision);
        frame.addProperty("serverId", event.serverId());
        frame.addProperty("worldId", event.worldId());
        frame.addProperty("characterId", event.characterId());
        frame.addProperty("agentId", event.agentId());
        frame.add("event", eventJson(event));
        JsonObject conversation = new JsonObject();
        conversation.addProperty("conversationId", conversationId);
        if (replyToMessageId == null) conversation.add("replyToMessageId", com.google.gson.JsonNull.INSTANCE);
        else conversation.addProperty("replyToMessageId", replyToMessageId);
        frame.add("conversation", conversation);
        frame.add("replyAudience", audience(event.playerId().toString()));
        if (contextEvents == null || contextStatus == null || progressContext == null) throw new IllegalArgumentException("request context is required");
        JsonArray contextBatch = new JsonArray();
        for (AttentionEvent contextEvent : contextEvents) {
            if (contextEvent == null) throw new IllegalArgumentException("attention context event is missing");
            contextBatch.add(contextEvent.toJson());
        }
        frame.add("contextBatch", contextBatch);
        frame.add("contextStatus", contextStatusJson(contextStatus));
        frame.add("acknowledgement", acknowledgement == null ? com.google.gson.JsonNull.INSTANCE : acknowledgement.deepCopy());
        JsonArray progressBatch = new JsonArray();
        for (ConversationProgress.DialogueProgress progress : progressContext) {
            if (progress == null) throw new IllegalArgumentException("progress context record is missing");
            progressBatch.add(progressJson(progress));
        }
        frame.add("progressContext", progressBatch);
        validateRequest(frame, event.playerId().toString(), null, abilityPolicyRevision);
        if (frame.toString().getBytes(StandardCharsets.UTF_8).length > 16 * 1024) {
            throw new IllegalArgumentException("dialogue request exceeds the frame budget");
        }
        return frame;
    }

    public static JsonObject eventJson(AttributedEvent event) {
        JsonObject value = new JsonObject();
        value.addProperty("serverId", event.serverId());
        value.addProperty("worldId", event.worldId());
        value.addProperty("characterId", event.characterId());
        value.addProperty("agentId", event.agentId());
        value.addProperty("eventId", event.eventId().toString());
        value.addProperty("serverEpochId", event.serverEpochId().toString());
        value.addProperty("occurredAtUnixMillis", event.occurredAtUnixMillis());
        value.addProperty("capturedAtMonotonicNanos", Long.toString(event.capturedAtMonotonicNanos()));
        value.addProperty("eventOrigin", event.eventOrigin());
        value.addProperty("inputKind", event.inputKind());
        value.addProperty("playerId", event.playerId().toString());
        value.addProperty("playerName", event.playerName());
        value.addProperty("dimension", event.dimension());
        value.addProperty("text", event.text());
        return value;
    }

    public static void validateRequest(JsonObject frame, String expectedPlayerId, ConfiguredBinding binding) {
        if (frame == null || !frame.has("abilityPolicyRevision")) {
            throw new IllegalArgumentException("dialogue request policy revision is required");
        }
        JsonElement revision = frame.get("abilityPolicyRevision");
        if (!safeInteger(revision)) throw new IllegalArgumentException("dialogue request policy revision is invalid");
        validateRequest(frame, expectedPlayerId, binding, new BigDecimal(revision.getAsString()).longValueExact());
    }

    public static void validateRequest(JsonObject frame, String expectedPlayerId, ConfiguredBinding binding,
                                       long expectedPolicyRevision) {
        requireFields(frame, Set.of("type", "protocol", "requestId", "invocationId", "serverId", "worldId", "characterId",
                "agentId", "abilityPolicyRevision", "event", "conversation", "replyAudience", "contextBatch", "contextStatus", "acknowledgement",
                "progressContext"));
        require(frame, "dialogue.request", expectedPlayerId, binding);
        JsonElement revision = frame.get("abilityPolicyRevision");
        if (!safeInteger(revision) || new BigDecimal(revision.getAsString()).longValueExact() != expectedPolicyRevision) {
            throw new IllegalArgumentException("dialogue request policy revision mismatch");
        }
        String requestId = string(frame, "requestId");
        String invocationId = string(frame, "invocationId");
        requireUuid(requestId);
        requireUuid(invocationId);
        if (requestId.equals(invocationId)) throw new IllegalArgumentException("request and invocation ids must differ");
        JsonObject event = object(frame, "event");
        requireFields(event, EVENT_FIELDS);
        validateEvent(event, true, binding);
        for (String key : Set.of("serverId", "worldId", "characterId", "agentId")) {
            if (!string(frame, key).equals(string(event, key))) throw new IllegalArgumentException("event binding differs from request");
        }
        JsonObject replyAudience = object(frame, "replyAudience");
        validateAudience(replyAudience);
        if (!string(replyAudience, "playerId").equals(string(event, "playerId")) ||
                expectedPlayerId != null && !expectedPlayerId.equals(string(replyAudience, "playerId"))) {
            throw new IllegalArgumentException("request audience differs from authenticated speaker");
        }
        JsonObject conversation = object(frame, "conversation");
        requireFields(conversation, Set.of("conversationId", "replyToMessageId"));
        requireUuid(string(conversation, "conversationId"));
        JsonElement replyTo = conversation.get("replyToMessageId");
        boolean followup = "followup".equals(string(event, "inputKind"));
        if (replyTo == null || !(replyTo.isJsonNull() && !followup || replyTo.isJsonPrimitive() && replyTo.getAsJsonPrimitive().isString() && followup)) {
            throw new IllegalArgumentException("conversation follow-up anchor is invalid");
        }
        if (followup) requireUuid(replyTo.getAsString());
        JsonElement batchElement = frame.get("contextBatch");
        if (batchElement == null || !batchElement.isJsonArray() || batchElement.getAsJsonArray().size() > 8) {
            throw new IllegalArgumentException("context batch exceeds its fixed bound");
        }
        Set<String> seen = new java.util.HashSet<>();
        seen.add(string(event, "eventId"));
        for (JsonElement item : batchElement.getAsJsonArray()) {
            if (!item.isJsonObject()) throw new IllegalArgumentException("context event is not an object");
            JsonObject prior = item.getAsJsonObject();
            if (prior.keySet().equals(EVENT_FIELDS)) validateEvent(prior, false, binding);
            else if (prior.keySet().equals(ATTENTION_FIELDS)) validateAttentionEvent(prior, binding);
            else throw new IllegalArgumentException("context event field set mismatch");
            String eventId = string(prior, "eventId");
            if (!seen.add(eventId)) throw new IllegalArgumentException("duplicate context event id");
            for (String key : Set.of("serverId", "worldId", "characterId", "agentId")) {
                if (!string(frame, key).equals(string(prior, key))) throw new IllegalArgumentException("context event binding differs");
            }
        }
        if (batchElement.toString().getBytes(StandardCharsets.UTF_8).length > AttentionBuffer.MAX_BATCH_BYTES) {
            throw new IllegalArgumentException("context batch exceeds its byte bound");
        }
        validateContextStatus(object(frame, "contextStatus"));
        JsonElement acknowledgement = frame.get("acknowledgement");
        if (acknowledgement == null) throw new IllegalArgumentException("acknowledgement field is required");
        Set<String> progressIds = new java.util.HashSet<>();
        if (!acknowledgement.isJsonNull()) {
            validateAcknowledgement(acknowledgement.getAsJsonObject(), string(event, "playerId"));
            progressIds.add(string(acknowledgement.getAsJsonObject(), "messageId"));
        }
        JsonElement progressElement = frame.get("progressContext");
        if (progressElement == null || !progressElement.isJsonArray() || progressElement.getAsJsonArray().size() > 1) {
            throw new IllegalArgumentException("queued progress context exceeds its fixed bound");
        }
        for (JsonElement item : progressElement.getAsJsonArray()) {
            if (!item.isJsonObject()) throw new IllegalArgumentException("progress context record is invalid");
            validateProgressRecord(item.getAsJsonObject(), string(event, "playerId"), "queued");
            if (!progressIds.add(string(item.getAsJsonObject(), "messageId"))) {
                throw new IllegalArgumentException("progress message ids must be distinct");
            }
        }
        if (progressElement.toString().getBytes(StandardCharsets.UTF_8).length > 8 * 1024) {
            throw new IllegalArgumentException("progress context exceeds its byte bound");
        }
    }

    public static JsonObject progress(String requestId, String invocationId,
                                      ConversationProgress.DialogueProgress progress) {
        requireUuid(requestId);
        requireUuid(invocationId);
        if (requestId.equals(invocationId) || progress == null || progress.phase() != ConversationProgress.Phase.AWAITING_ANSWER) {
            throw new IllegalArgumentException("live progress identity or phase is invalid");
        }
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "dialogue.progress");
        frame.addProperty("protocol", PROTOCOL);
        frame.addProperty("requestId", requestId);
        frame.addProperty("invocationId", invocationId);
        frame.addProperty("playerId", progress.audiencePlayerId());
        frame.add("progress", progressJson(progress));
        validateProgressFrame(frame);
        if (frame.toString().getBytes(StandardCharsets.UTF_8).length > 16 * 1024) {
            throw new IllegalArgumentException("dialogue progress exceeds the frame budget");
        }
        return frame;
    }

    public static void validateProgressFrame(JsonObject frame) {
        requireFields(frame, PROGRESS_FRAME_FIELDS);
        require(frame, "dialogue.progress", null, null);
        requireUuid(string(frame, "requestId"));
        requireUuid(string(frame, "invocationId"));
        if (string(frame, "requestId").equals(string(frame, "invocationId"))) {
            throw new IllegalArgumentException("progress request and invocation ids must differ");
        }
        String playerId = string(frame, "playerId");
        requireUuid(playerId);
        validateProgressRecord(object(frame, "progress"), playerId, "awaiting_answer");
    }

    private static JsonObject progressJson(ConversationProgress.DialogueProgress progress) {
        JsonObject value = new JsonObject();
        value.addProperty("messageId", progress.messageId());
        value.addProperty("text", progress.text());
        value.addProperty("dispatchedAtUnixMillis", progress.dispatchedAtUnixMillis());
        value.addProperty("dispatchedAtMonotonicNanos", Long.toString(progress.dispatchedAtMonotonicNanos()));
        value.add("replyAudience", audience(progress.audiencePlayerId()));
        value.addProperty("phase", progress.phase() == ConversationProgress.Phase.QUEUED ? "queued" : "awaiting_answer");
        validateProgressRecord(value, progress.audiencePlayerId(),
                progress.phase() == ConversationProgress.Phase.QUEUED ? "queued" : "awaiting_answer");
        return value;
    }

    private static void validateProgressRecord(JsonObject value, String expectedPlayerId, String expectedPhase) {
        requireFields(value, PROGRESS_FIELDS);
        requireUuid(string(value, "messageId"));
        String text = string(value, "text");
        if (ProtocolText.isEcmaScriptBlank(text) || text.length() > VanillaDialogue.MAX_RENDERED_LENGTH ||
                !VanillaDialogue.isSafeRenderedLine(text)) {
            throw new IllegalArgumentException("progress text is invalid");
        }
        safeMillis(value.get("dispatchedAtUnixMillis"));
        longString(string(value, "dispatchedAtMonotonicNanos"));
        JsonObject audience = object(value, "replyAudience");
        validateAudience(audience);
        if (!expectedPlayerId.equals(string(audience, "playerId")) || !expectedPhase.equals(string(value, "phase"))) {
            throw new IllegalArgumentException("progress audience or phase mismatch");
        }
    }

    public static GameLoopMailbox.TerminalFrame terminal(JsonObject frame, GameLoopMailbox.ActiveRequest expected,
                                                          long serviceGeneration, long receivedAtNanos) {
        if (expected == null) throw new IllegalArgumentException("there is no live request attribution");
        validateResponse(frame);
        String type = string(frame, "type");
        require(frame, type, expected.audiencePlayerId(), null);
        String requestId = string(frame, "requestId");
        String invocationId = string(frame, "invocationId");
        String characterId = string(frame, "characterId");
        JsonObject audience = object(frame, "replyAudience");
        validateAudience(audience);
        String playerId = string(audience, "playerId");
        if (!expected.requestId().equals(requestId) || !expected.invocationId().equals(invocationId) ||
                !expected.characterId().equals(characterId) || !expected.audiencePlayerId().equals(playerId)) {
            throw new IllegalArgumentException("response does not match the retained request audience");
        }
        if ("dialogue.message".equals(type)) {
            requireFields(frame, MESSAGE_FIELDS);
            if (!"answer".equals(string(frame, "kind"))) throw new IllegalArgumentException("unsupported answer kind");
            String messageId = string(frame, "messageId");
            requireUuid(messageId);
            String text = string(frame, "text");
            if (!VanillaDialogue.isRenderableAnswerBody(text)) throw new IllegalArgumentException("unsafe answer text");
            return new GameLoopMailbox.TerminalFrame(requestId, invocationId, characterId, playerId,
                    messageId, "answer", text, serviceGeneration, receivedAtNanos);
        }
        if ("dialogue.failure".equals(type)) {
            requireFields(frame, FAILURE_FIELDS);
            if (!"unavailable".equals(string(frame, "reason"))) throw new IllegalArgumentException("unsupported failure reason");
            return new GameLoopMailbox.TerminalFrame(requestId, invocationId, characterId, playerId,
                    "failure:" + requestId, "failure", "", serviceGeneration, receivedAtNanos);
        }
        throw new IllegalArgumentException("unsupported dialogue response");
    }

    /** Shape-only inbound check; Java's game-thread request match is still required before publication. */
    public static void validateResponse(JsonObject frame) {
        if (frame == null) throw new IllegalArgumentException("dialogue response is missing");
        String type = string(frame, "type");
        require(frame, type, null, null);
        requireUuid(string(frame, "requestId"));
        requireUuid(string(frame, "invocationId"));
        String characterId = string(frame, "characterId");
        if (ProtocolText.isEcmaScriptBlank(characterId) || characterId.length() > 512) throw new IllegalArgumentException("character binding is invalid");
        validateAudience(object(frame, "replyAudience"));
        if ("dialogue.message".equals(type)) {
            requireFields(frame, MESSAGE_FIELDS);
            requireUuid(string(frame, "messageId"));
            String answer = string(frame, "text");
            if (!"answer".equals(string(frame, "kind")) || !VanillaDialogue.isRenderableAnswerBody(answer)) {
                throw new IllegalArgumentException("dialogue answer is invalid");
            }
        } else if ("dialogue.failure".equals(type)) {
            requireFields(frame, FAILURE_FIELDS);
            if (!"unavailable".equals(string(frame, "reason"))) throw new IllegalArgumentException("dialogue failure is invalid");
        } else throw new IllegalArgumentException("unsupported dialogue response");
    }

    public static JsonObject acknowledgement(String messageId, String text, long wallMillis, long monotonicNanos,
                                            String playerId) {
        JsonObject value = new JsonObject();
        value.addProperty("messageId", messageId);
        value.addProperty("text", text);
        value.addProperty("dispatchedAtUnixMillis", wallMillis);
        value.addProperty("dispatchedAtMonotonicNanos", Long.toString(monotonicNanos));
        value.add("replyAudience", audience(playerId));
        validateAcknowledgement(value, playerId);
        return value;
    }

    private static void validateAcknowledgement(JsonObject ack, String playerId) {
        requireFields(ack, ACK_FIELDS);
        requireUuid(string(ack, "messageId"));
        String text = string(ack, "text");
        if (ProtocolText.isEcmaScriptBlank(text) || text.length() > 2048 || unsafeControls(text, false)) throw new IllegalArgumentException("acknowledgement text is invalid");
        safeMillis(ack.get("dispatchedAtUnixMillis"));
        longString(string(ack, "dispatchedAtMonotonicNanos"));
        JsonObject audience = object(ack, "replyAudience");
        validateAudience(audience);
        if (!playerId.equals(string(audience, "playerId"))) throw new IllegalArgumentException("acknowledgement audience mismatch");
    }

    private static void validateEvent(JsonObject event, boolean primary, ConfiguredBinding binding) {
        for (String key : Set.of("serverId", "worldId", "characterId", "agentId", "playerName", "dimension", "text")) {
            String value = string(event, key);
            if (ProtocolText.isEcmaScriptBlank(value) || value.length() > ("playerName".equals(key) ? 32 : "dimension".equals(key) ? 256 : "text".equals(key) ? 512 : 512)) {
                throw new IllegalArgumentException("event field is outside its bound");
            }
        }
        requireUuid(string(event, "eventId"));
        requireUuid(string(event, "serverEpochId"));
        requireUuid(string(event, "playerId"));
        safeMillis(event.get("occurredAtUnixMillis"));
        longString(string(event, "capturedAtMonotonicNanos"));
        if (!("live".equals(string(event, "eventOrigin")) || "history".equals(string(event, "eventOrigin"))) ||
                !("command".equals(string(event, "inputKind")) || "natural_address".equals(string(event, "inputKind")) ||
                        "followup".equals(string(event, "inputKind")))) throw new IllegalArgumentException("event kind is invalid");
        if (primary && !"live".equals(string(event, "eventOrigin"))) throw new IllegalArgumentException("primary event must be live");
        String dimension = string(event, "dimension");
        if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("event dimension is invalid");
        if (unsafeControls(string(event, "playerName"), false) || unsafeControls(string(event, "text"), true)) {
            throw new IllegalArgumentException("event text contains unsafe controls");
        }
        if (binding != null && (!binding.serverId().equals(string(event, "serverId")) || !binding.worldId().equals(string(event, "worldId")) ||
                !binding.characterId().equals(string(event, "characterId")) || !binding.agentId().equals(string(event, "agentId")))) {
            throw new IllegalArgumentException("event differs from authenticated binding");
        }
    }

    private static void validateAttentionEvent(JsonObject event, ConfiguredBinding binding) {
        requireFields(event, ATTENTION_FIELDS);
        for (String key : Set.of("serverId", "worldId", "characterId", "agentId")) {
            String value = string(event, key);
            if (ProtocolText.isEcmaScriptBlank(value) || value.length() > 512) throw new IllegalArgumentException("attention binding is invalid");
        }
        requireUuid(string(event, "eventId"));
        requireUuid(string(event, "serverEpochId"));
        safeMillis(event.get("occurredAtUnixMillis"));
        longString(string(event, "capturedAtMonotonicNanos"));
        String origin = string(event, "eventOrigin");
        String source = string(event, "source");
        if (!("live".equals(origin) || "history".equals(origin)) ||
                !("server_callback".equals(source) || "server_sample".equals(source))) {
            throw new IllegalArgumentException("attention origin or source is invalid");
        }
        JsonObject subject = object(event, "subject");
        requireFields(subject, Set.of("playerId", "playerName"));
        requireUuid(string(subject, "playerId"));
        String playerName = string(subject, "playerName");
        if (ProtocolText.isEcmaScriptBlank(playerName) || playerName.length() > 32 || unsafeControls(playerName, false)) {
            throw new IllegalArgumentException("attention subject is invalid");
        }
        String dimension = string(event, "dimension");
        if (dimension.length() < 3 || dimension.length() > 256 ||
                !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("attention dimension is invalid");
        String kind = string(event, "eventKind");
        JsonObject details = object(event, "details");
        switch (kind) {
            case "joined", "left", "died" -> {
                requireFields(details, Set.of());
                if (!"server_callback".equals(source)) throw new IllegalArgumentException("callback event source is invalid");
            }
            case "dimension_changed" -> {
                requireFields(details, Set.of("originDimension"));
                String originDimension = string(details, "originDimension");
                if (!originDimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || !"server_callback".equals(source)) {
                    throw new IllegalArgumentException("dimension event details are invalid");
                }
            }
            case "respawned" -> {
                requireFields(details, Set.of("copiedAlive"));
                JsonElement copiedAlive = details.get("copiedAlive");
                if (copiedAlive == null || !copiedAlive.isJsonPrimitive() || !copiedAlive.getAsJsonPrimitive().isBoolean() ||
                        !"server_callback".equals(source)) throw new IllegalArgumentException("respawn event details are invalid");
            }
            case "moved" -> {
                requireFields(details, Set.of("basis"));
                if (!"sampled_block_change".equals(string(details, "basis")) || !"server_sample".equals(source)) {
                    throw new IllegalArgumentException("movement event details are invalid");
                }
            }
            default -> throw new IllegalArgumentException("attention event kind is invalid");
        }
        if (binding != null && (!binding.serverId().equals(string(event, "serverId")) ||
                !binding.worldId().equals(string(event, "worldId")) ||
                !binding.characterId().equals(string(event, "characterId")) ||
                !binding.agentId().equals(string(event, "agentId")))) {
            throw new IllegalArgumentException("attention event differs from authenticated binding");
        }
    }

    private static JsonObject contextStatusJson(AttentionBuffer.ContextStatus status) {
        JsonObject value = new JsonObject();
        value.addProperty("state", status.state());
        value.addProperty("lossRevision", status.lossRevision());
        JsonArray reasons = new JsonArray();
        for (String reason : status.reasons()) reasons.add(reason);
        value.add("reasons", reasons);
        validateContextStatus(value);
        return value;
    }

    private static void validateContextStatus(JsonObject value) {
        requireFields(value, CONTEXT_STATUS_FIELDS);
        String state = string(value, "state");
        if (!("disabled".equals(state) || "bounded".equals(state) || "incomplete".equals(state))) {
            throw new IllegalArgumentException("context status state is invalid");
        }
        String revision = string(value, "lossRevision");
        if (!revision.matches("(?:0|[1-9][0-9]*)")) throw new IllegalArgumentException("context loss revision is invalid");
        try { Long.parseLong(revision); }
        catch (NumberFormatException overflow) { throw new IllegalArgumentException("context loss revision is invalid", overflow); }
        JsonElement reasonsElement = value.get("reasons");
        if (reasonsElement == null || !reasonsElement.isJsonArray() || reasonsElement.getAsJsonArray().size() > 8) {
            throw new IllegalArgumentException("context loss reasons are invalid");
        }
        Set<String> reasons = new java.util.HashSet<>();
        for (JsonElement reason : reasonsElement.getAsJsonArray()) {
            if (!reason.isJsonPrimitive() || !reason.getAsJsonPrimitive().isString() ||
                    !LOSS_REASONS.contains(reason.getAsString()) || !reasons.add(reason.getAsString())) {
                throw new IllegalArgumentException("context loss reasons are invalid");
            }
        }
        if ("disabled".equals(state) && (!reasons.isEmpty() || !"0".equals(revision)) ||
                "bounded".equals(state) && !reasons.isEmpty() ||
                "incomplete".equals(state) && reasons.isEmpty()) {
            throw new IllegalArgumentException("context status state and losses differ");
        }
    }

    private static void require(JsonObject frame, String type, String expectedPlayerId, ConfiguredBinding binding) {
        if (!PROTOCOL.equals(string(frame, "protocol")) || !type.equals(string(frame, "type"))) {
            throw new IllegalArgumentException("dialogue protocol mismatch");
        }
        if (binding != null && (!binding.serverId().equals(string(frame, "serverId")) || !binding.worldId().equals(string(frame, "worldId")) ||
                !binding.characterId().equals(string(frame, "characterId")) || !binding.agentId().equals(string(frame, "agentId")))) {
            throw new IllegalArgumentException("dialogue binding mismatch");
        }
        if (expectedPlayerId != null && frame.has("playerId") && !expectedPlayerId.equals(string(frame, "playerId"))) {
            throw new IllegalArgumentException("dialogue speaker mismatch");
        }
    }

    private static JsonObject audience(String playerId) {
        JsonObject value = new JsonObject();
        value.addProperty("kind", "player");
        value.addProperty("playerId", playerId);
        return value;
    }

    private static void validateAudience(JsonObject value) {
        requireFields(value, AUDIENCE_FIELDS);
        if (!"player".equals(string(value, "kind"))) throw new IllegalArgumentException("only player audience is supported");
        requireUuid(string(value, "playerId"));
    }

    private static void requireFields(JsonObject object, Set<String> fields) {
        if (object == null || !object.keySet().equals(fields)) throw new IllegalArgumentException("dialogue field set mismatch");
    }

    private static JsonObject object(JsonObject parent, String field) {
        JsonElement value = parent == null ? null : parent.get(field);
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("dialogue object field is invalid");
        return value.getAsJsonObject();
    }

    private static String string(JsonObject parent, String field) {
        JsonElement value = parent == null ? null : parent.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("dialogue string field is invalid");
        }
        return value.getAsString();
    }

    private static void requireUuid(String value) {
        if (value == null || !UUID.matcher(value).matches()) throw new IllegalArgumentException("dialogue UUID is invalid");
    }

    private static void safeMillis(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("dialogue wall time is invalid");
        }
        try {
            String rawNumber = value.getAsString();
            if (!JSON_NUMBER.matcher(rawNumber).matches()) {
                throw new IllegalArgumentException("dialogue wall time is invalid");
            }
            BigDecimal millis = new BigDecimal(rawNumber);
            if (millis.signum() < 0 || millis.compareTo(MAX_SAFE_MILLIS) > 0 ||
                    millis.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("dialogue wall time is invalid");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("dialogue wall time is invalid", exception);
        }
    }

    private static boolean safeInteger(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
        try {
            BigDecimal number = new BigDecimal(value.getAsString());
            return number.signum() >= 0 && number.compareTo(MAX_SAFE_MILLIS) <= 0 &&
                    number.stripTrailingZeros().scale() <= 0;
        } catch (NumberFormatException malformed) {
            return false;
        }
    }

    private static void longString(String value) {
        if (value == null || !value.matches("(?:0|-?[1-9][0-9]*)")) throw new IllegalArgumentException("dialogue monotonic time is invalid");
        try { Long.parseLong(value); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("dialogue monotonic time is invalid", exception); }
    }

    private static boolean unsafeControls(String value, boolean allowLineWhitespace) {
        return value.codePoints().anyMatch(codePoint -> codePoint == 0x7f || codePoint <= 0x1f &&
                !(allowLineWhitespace && (codePoint == '\t' || codePoint == '\n' || codePoint == '\r')));
    }
}
