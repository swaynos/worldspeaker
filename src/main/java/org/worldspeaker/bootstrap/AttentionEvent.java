package org.worldspeaker.bootstrap;

import com.google.gson.JsonObject;
import org.worldspeaker.compat.ConfiguredBinding;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable, inert S event captured from an observational server callback or bounded sample. */
public record AttentionEvent(String serverId, String worldId, String characterId, String agentId,
                             UUID eventId, UUID serverEpochId, long occurredAtUnixMillis,
                             long capturedAtMonotonicNanos, String eventOrigin, String source,
                             UUID playerId, String playerName, String dimension, String eventKind,
                             Details details) {
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public AttentionEvent {
        requireBinding(serverId);
        requireBinding(worldId);
        requireBinding(characterId);
        requireBinding(agentId);
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(serverEpochId, "serverEpochId");
        Objects.requireNonNull(playerId, "playerId");
        if (occurredAtUnixMillis < 0 || occurredAtUnixMillis > 9_007_199_254_740_991L ||
                !("live".equals(eventOrigin) || "history".equals(eventOrigin))) {
            throw new IllegalArgumentException("attention event time or origin is invalid");
        }
        if (!("server_callback".equals(source) || "server_sample".equals(source))) {
            throw new IllegalArgumentException("attention event source is invalid");
        }
        if (playerName == null || ProtocolText.isEcmaScriptBlank(playerName) || playerName.length() > 32 || hasControls(playerName)) {
            throw new IllegalArgumentException("attention subject name is invalid");
        }
        if (dimension == null || dimension.length() > 256 || !DIMENSION.matcher(dimension).matches()) {
            throw new IllegalArgumentException("attention dimension is invalid");
        }
        Objects.requireNonNull(details, "details");
        EventKind kind = EventKind.fromWire(eventKind);
        if ((kind == EventKind.MOVED) != "server_sample".equals(source)) {
            throw new IllegalArgumentException("attention event kind and source differ");
        }
        if (!details.matches(kind)) throw new IllegalArgumentException("attention details do not match event kind");
    }

    public JsonObject toJson() {
        JsonObject value = new JsonObject();
        value.addProperty("serverId", serverId);
        value.addProperty("worldId", worldId);
        value.addProperty("characterId", characterId);
        value.addProperty("agentId", agentId);
        value.addProperty("eventId", eventId.toString());
        value.addProperty("serverEpochId", serverEpochId.toString());
        value.addProperty("occurredAtUnixMillis", occurredAtUnixMillis);
        value.addProperty("capturedAtMonotonicNanos", Long.toString(capturedAtMonotonicNanos));
        value.addProperty("eventOrigin", eventOrigin);
        value.addProperty("source", source);
        JsonObject subject = new JsonObject();
        subject.addProperty("playerId", playerId.toString());
        subject.addProperty("playerName", playerName);
        value.add("subject", subject);
        value.addProperty("dimension", dimension);
        value.addProperty("eventKind", eventKind);
        value.add("details", details.toJson());
        return value;
    }

    public int encodedBytes() {
        return toJson().toString().getBytes(StandardCharsets.UTF_8).length;
    }

    public boolean hasBinding(ConfiguredBinding binding) {
        return binding != null && binding.isComplete() && serverId.equals(binding.serverId()) &&
                worldId.equals(binding.worldId()) && characterId.equals(binding.characterId()) &&
                agentId.equals(binding.agentId());
    }

    public static String subscriptionName(EventKind kind) {
        return kind.subscription().configName();
    }

    private static void requireBinding(String value) {
        if (ProtocolText.isEcmaScriptBlank(value) || value.length() > 512) throw new IllegalArgumentException("attention binding is invalid");
    }

    private static boolean hasControls(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    public enum EventKind {
        JOINED("joined", org.worldspeaker.compat.AttentionPolicy.Subscription.PLAYER_CONNECTION),
        LEFT("left", org.worldspeaker.compat.AttentionPolicy.Subscription.PLAYER_CONNECTION),
        DIED("died", org.worldspeaker.compat.AttentionPolicy.Subscription.PLAYER_DEATH),
        RESPAWNED("respawned", org.worldspeaker.compat.AttentionPolicy.Subscription.PLAYER_RESPAWN),
        DIMENSION_CHANGED("dimension_changed", org.worldspeaker.compat.AttentionPolicy.Subscription.PLAYER_DIMENSION),
        MOVED("moved", org.worldspeaker.compat.AttentionPolicy.Subscription.PLAYER_MOVEMENT);

        private final String wireName;
        private final org.worldspeaker.compat.AttentionPolicy.Subscription subscription;

        EventKind(String wireName, org.worldspeaker.compat.AttentionPolicy.Subscription subscription) {
            this.wireName = wireName;
            this.subscription = subscription;
        }

        public String wireName() { return wireName; }
        public org.worldspeaker.compat.AttentionPolicy.Subscription subscription() { return subscription; }

        public static EventKind fromWire(String value) {
            for (EventKind kind : values()) if (kind.wireName.equals(value)) return kind;
            throw new IllegalArgumentException("attention event kind is invalid");
        }
    }

    /** Exactly one of the four contract-defined details shapes. */
    public record Details(String originDimension, Boolean copiedAlive, String basis) {
        public Details {
            int present = (originDimension == null ? 0 : 1) + (copiedAlive == null ? 0 : 1) + (basis == null ? 0 : 1);
            if (present > 1 || originDimension != null && (originDimension.length() > 256 || !DIMENSION.matcher(originDimension).matches()) ||
                    basis != null && !"sampled_block_change".equals(basis)) {
                throw new IllegalArgumentException("attention event details are invalid");
            }
        }

        public static Details empty() { return new Details(null, null, null); }
        public static Details dimension(String originDimension) { return new Details(originDimension, null, null); }
        public static Details respawn(boolean copiedAlive) { return new Details(null, copiedAlive, null); }
        public static Details moved() { return new Details(null, null, "sampled_block_change"); }

        private boolean matches(EventKind kind) {
            return switch (kind) {
                case DIMENSION_CHANGED -> originDimension != null && copiedAlive == null && basis == null;
                case RESPAWNED -> originDimension == null && copiedAlive != null && basis == null;
                case MOVED -> originDimension == null && copiedAlive == null && "sampled_block_change".equals(basis);
                case JOINED, LEFT, DIED -> originDimension == null && copiedAlive == null && basis == null;
            };
        }

        private JsonObject toJson() {
            JsonObject value = new JsonObject();
            if (originDimension != null) value.addProperty("originDimension", originDimension);
            else if (copiedAlive != null) value.addProperty("copiedAlive", copiedAlive);
            else if (basis != null) value.addProperty("basis", basis);
            return value;
        }
    }
}
