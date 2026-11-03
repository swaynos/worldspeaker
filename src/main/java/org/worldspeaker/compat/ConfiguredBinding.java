package org.worldspeaker.compat;

import java.util.regex.Pattern;

public record ConfiguredBinding(String serverId, String worldId, String characterId, String agentId) {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public boolean isComplete() {
        return valid(serverId) && valid(worldId) && valid(characterId) && valid(agentId);
    }

    /** Stable future state namespace: exact configured server/world/character IDs only. */
    public CharacterKey characterKey() {
        if (!isComplete()) throw new IllegalStateException("configured binding is incomplete");
        return new CharacterKey(serverId, worldId, characterId);
    }

    static boolean validIdentifier(String value) {
        return valid(value);
    }

    private static boolean valid(String value) {
        return value != null && ID.matcher(value).matches();
    }

    public record CharacterKey(String serverId, String worldId, String characterId) {
        public CharacterKey {
            if (!valid(serverId) || !valid(worldId) || !valid(characterId)) {
                throw new IllegalArgumentException("character key identifiers are invalid");
            }
        }
    }
}
