package org.worldspeaker.bootstrap;

import org.worldspeaker.compat.ConfiguredBinding;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable, server-captured event facts. Player text is data and never supplies these fields. */
public record AttributedEvent(
        String serverId,
        String worldId,
        String characterId,
        String agentId,
        UUID eventId,
        UUID serverEpochId,
        long occurredAtUnixMillis,
        long capturedAtMonotonicNanos,
        String eventOrigin,
        String inputKind,
        UUID playerId,
        String playerName,
        String dimension,
        String text) {

    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public AttributedEvent {
        requireBinding(serverId);
        requireBinding(worldId);
        requireBinding(characterId);
        requireBinding(agentId);
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(serverEpochId, "serverEpochId");
        Objects.requireNonNull(playerId, "playerId");
        if (occurredAtUnixMillis < 0 || occurredAtUnixMillis > 9_007_199_254_740_991L) {
            throw new IllegalArgumentException("captured wall time is outside the safe integer range");
        }
        if (!("live".equals(eventOrigin) || "history".equals(eventOrigin))) {
            throw new IllegalArgumentException("event origin is invalid");
        }
        if (!("command".equals(inputKind) || "natural_address".equals(inputKind) || "followup".equals(inputKind))) {
            throw new IllegalArgumentException("input kind is invalid");
        }
        if (ProtocolText.isEcmaScriptBlank(playerName) || playerName.length() > 32 || hasControls(playerName)) {
            throw new IllegalArgumentException("authenticated player name is invalid");
        }
        if (dimension == null || dimension.length() < 3 || dimension.length() > 256 || !DIMENSION.matcher(dimension).matches()) {
            throw new IllegalArgumentException("server dimension is invalid");
        }
        if (ProtocolText.isEcmaScriptBlank(text) || text.length() > InteractionIngress.MAX_INPUT_LENGTH || hasUnsafeTextControls(text)) {
            throw new IllegalArgumentException("captured input is outside the bounded text contract");
        }
    }

    public static AttributedEvent live(InteractionIngress.Captured captured, String serverEpochId,
                                       InteractionIngress.SourceFacts speaker, ConfiguredBinding binding,
                                       String dimension, String text) {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(binding, "binding");
        if (!speaker.authenticatedPlayer() || !binding.isComplete()) {
            throw new IllegalArgumentException("authenticated event attribution is required");
        }
        return new AttributedEvent(binding.serverId(), binding.worldId(), binding.characterId(), binding.agentId(),
                captured.eventId(), UUID.fromString(serverEpochId), captured.occurredAtUnixMillis(),
                captured.capturedAtNanos(), "live", inputKind(captured.origin()), speaker.playerId(),
                speaker.playerName(), dimension, text);
    }

    public static String inputKind(InteractionIngress.Origin origin) {
        return switch (origin) {
            case COMMAND -> "command";
            case NATURAL_ADDRESS -> "natural_address";
            case FOLLOWUP -> "followup";
        };
    }

    private static void requireBinding(String value) {
        if (ProtocolText.isEcmaScriptBlank(value) || value.length() > 512) {
            throw new IllegalArgumentException("configured binding is invalid");
        }
    }

    private static boolean hasControls(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static boolean hasUnsafeTextControls(String value) {
        return value.codePoints().anyMatch(codePoint -> codePoint <= 0x08 || codePoint == 0x0b || codePoint == 0x0c ||
                codePoint >= 0x0e && codePoint <= 0x1f || codePoint == 0x7f);
    }
}
