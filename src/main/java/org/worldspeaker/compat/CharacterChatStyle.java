package org.worldspeaker.compat;

/** The only supported character-name envelopes for vanilla system-chat output. */
public enum CharacterChatStyle {
    PLAIN,
    BRACKETED;

    public static CharacterChatStyle parse(String value) {
        if ("plain".equals(value)) return PLAIN;
        if ("bracketed".equals(value)) return BRACKETED;
        throw new IllegalArgumentException("characterChatStyle must be plain or bracketed");
    }

    public String configValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
