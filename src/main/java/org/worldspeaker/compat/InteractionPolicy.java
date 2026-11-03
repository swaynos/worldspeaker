package org.worldspeaker.compat;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Explicit, bounded player grants for the one supported conversation permission. */
public record InteractionPolicy(Set<UUID> conversationAllowedPlayers) {
    public static final String CONVERSE_PERMISSION = "worldspeaker.converse";
    public static final int MAX_ALLOWED_PLAYERS = 32;

    public InteractionPolicy {
        if (conversationAllowedPlayers == null || conversationAllowedPlayers.size() > MAX_ALLOWED_PLAYERS ||
                conversationAllowedPlayers.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("conversationAllowedPlayers must contain at most 32 UUIDs");
        }
        conversationAllowedPlayers = Set.copyOf(conversationAllowedPlayers);
    }

    public static InteractionPolicy denyAll() {
        return new InteractionPolicy(Set.of());
    }

    /** Parses only canonical UUIDs; names, wildcards, duplicates, and excess entries are invalid. */
    public static InteractionPolicy parse(String value) throws InvalidInteractionPolicyException {
        if (value == null || value.isBlank()) return denyAll();

        String[] entries = value.split(",", -1);
        if (entries.length > MAX_ALLOWED_PLAYERS) throw new InvalidInteractionPolicyException();
        LinkedHashSet<UUID> grants = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry.isBlank()) throw new InvalidInteractionPolicyException();
            try {
                UUID uuid = UUID.fromString(entry);
                if (!uuid.toString().equals(entry) || !grants.add(uuid)) {
                    throw new InvalidInteractionPolicyException();
                }
            } catch (IllegalArgumentException invalidUuid) {
                throw new InvalidInteractionPolicyException();
            }
        }
        return new InteractionPolicy(grants);
    }

    public boolean allows(UUID playerId, String permission) {
        return CONVERSE_PERMISSION.equals(permission) && playerId != null && conversationAllowedPlayers.contains(playerId);
    }

    public static final class InvalidInteractionPolicyException extends Exception {
        private static final long serialVersionUID = 1L;

        private InvalidInteractionPolicyException() {
            super("conversationAllowedPlayers must be empty or contain at most 32 unique canonical UUIDs");
        }
    }
}
