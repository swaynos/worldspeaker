package org.worldspeaker.bootstrap;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.worldspeaker.compat.CharacterChatStyle;

/** The only server-side publication boundary for Worldspeaker player messages. */
public final class VanillaDialogue {
    public static final String LABEL = "[Worldspeaker] ";
    public static final int MAX_RENDERED_LENGTH = 2048;
    public static final int MAX_COMPLETED_ANSWER_LENGTH = 1980;

    private static final int MAX_INPUT_SCAN = 8192;

    private VanillaDialogue() {}

    /** Renders untrusted text as a bounded, literal, server-owned system-chat line. */
    public static String renderLine(String body) {
        if (body == null || body.isEmpty()) return "";
        String safeBody = cleanBody(body, MAX_RENDERED_LENGTH - LABEL.length());
        return !hasVisibleText(safeBody) ? "" : LABEL + safeBody;
    }

    /** A character remains visibly a Worldspeaker publication, never another player's signed identity. */
    public static String renderCharacterLine(String characterDisplayName, String body) {
        return renderCharacterLine(characterDisplayName, body, CharacterChatStyle.PLAIN);
    }

    /** Renders the selected literal name envelope; the fixed Worldspeaker origin is never removable. */
    public static String renderCharacterLine(String characterDisplayName, String body, CharacterChatStyle style) {
        if (style == null) return "";
        if (!isValidCharacterDisplayName(characterDisplayName) || !isRenderableBodyForRendering(body)) return "";
        return renderCharacterEnvelope(characterDisplayName, body, style);
    }

    private static String renderCharacterEnvelope(String characterDisplayName, String body, CharacterChatStyle style) {
        if (style == null || !isValidCharacterDisplayName(characterDisplayName) || body == null || !hasVisibleText(body)) return "";
        String styledName = style == CharacterChatStyle.BRACKETED ? "[" + characterDisplayName + "]" : characterDisplayName;
        String prefix = LABEL + styledName + ": ";
        String safeBody = cleanBody(body, MAX_RENDERED_LENGTH - prefix.length());
        return !hasVisibleText(safeBody) ? "" : prefix + safeBody;
    }

    public static boolean isValidCharacterDisplayName(String name) {
        return NaturalAddressParser.isValidConfiguredName(name);
    }

    /** Checks the same bounded body rules used before an answer may enter the terminal mailbox. */
    public static boolean isRenderableAnswerBody(String body) {
        return body != null && !body.isEmpty() && body.length() <= MAX_COMPLETED_ANSWER_LENGTH &&
                ProtocolText.isSafePublicText(body);
    }

    private static boolean isRenderableBodyForRendering(String body) {
        return body != null && body.length() <= MAX_INPUT_SCAN &&
                ProtocolText.isSafePublicText(body) && !renderLine(body).isEmpty();
    }

    /**
     * Substitutes a local acknowledgement template through the same renderer used
     * for publication. An empty result is unusable; callers must not silently
     * replace configured persona text with a different acknowledgement.
     */
    public static String renderAcknowledgement(String template, String playerName, String characterDisplayName) {
        return renderAcknowledgement(template, playerName, characterDisplayName, CharacterChatStyle.PLAIN);
    }

    public static String renderAcknowledgement(String template, String playerName, String characterDisplayName,
                                               CharacterChatStyle style) {
        if (template == null || playerName == null) return "";
        String body = template.replace("{player}", playerName);
        if (!hasVisibleText(body)) return "";
        String rendered = renderCharacterEnvelope(characterDisplayName, body, style);
        return isSafeRenderedLine(rendered) ? rendered : "";
    }

    /** Checks a cached template using the production renderer and a representative player name. */
    public static boolean isUsableAcknowledgementTemplate(String template) {
        return !renderAcknowledgement(template, "TestPlayer", "Test Character").isEmpty();
    }

    /** Publishes only to the supplied player using vanilla non-overlay system chat. */
    public static boolean publish(ServerPlayerEntity player, String body) {
        if (player == null) return false;
        return submitRendered(player, renderLine(body)) == FirstReplyScheduler.PublicationResult.SUBMITTED;
    }

    public static boolean publishCharacter(ServerPlayerEntity player, String characterDisplayName, String body) {
        if (player == null) return false;
        return submitRendered(player, renderCharacterLine(characterDisplayName, body)) == FirstReplyScheduler.PublicationResult.SUBMITTED;
    }

    public static boolean publishCharacter(ServerPlayerEntity player, String characterDisplayName, String body,
                                            CharacterChatStyle style) {
        if (player == null) return false;
        return submitRendered(player, renderCharacterLine(characterDisplayName, body, style)) ==
                FirstReplyScheduler.PublicationResult.SUBMITTED;
    }

    static boolean publishRendered(ServerPlayerEntity player, String renderedLine) {
        return submitRendered(player, renderedLine) == FirstReplyScheduler.PublicationResult.SUBMITTED;
    }

    /** A throw after entering the server connection API is UNKNOWN, never a retryable unsent result. */
    public static FirstReplyScheduler.PublicationResult submitRendered(ServerPlayerEntity player, String renderedLine) {
        if (player == null || !isSafeRenderedLine(renderedLine)) {
            return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
        }
        final Text message;
        try {
            message = Text.literal(renderedLine);
        } catch (RuntimeException rejectedBeforeSubmission) {
            return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
        }
        try {
            player.sendMessage(message, false);
            return FirstReplyScheduler.PublicationResult.SUBMITTED;
        } catch (RuntimeException submissionUnknown) {
            return FirstReplyScheduler.PublicationResult.UNKNOWN;
        }
    }

    /** Package-private seam for exact-envelope tests; production uses the player overload. */
    static boolean publishRendered(String renderedLine, MessageSink sink) {
        if (!isSafeRenderedLine(renderedLine) || sink == null) return false;
        try {
            sink.send(renderedLine, false);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public static boolean isSafeRenderedLine(String line) {
        return line != null && line.startsWith(LABEL) && line.length() <= MAX_RENDERED_LENGTH &&
                line.equals(renderLine(line.substring(LABEL.length())));
    }

    /**
     * Bounded Unicode text-level visibility heuristic shared by rendering and validation.
     * It requires at least one assigned letter, number, punctuation mark, or symbol, and
     * excludes known invisible/default-ignorable code points, combining marks by themselves,
     * emoji skin-tone modifiers by themselves, separators, and BRAILLE PATTERN BLANK.
     * This is not proof that a particular client font will paint pixels for every code point.
     */
    private static boolean hasVisibleText(String text) {
        return ProtocolText.hasVisibleText(text);
    }

    private static String cleanBody(String body, int bodyLimit) {
        if (body == null || body.isEmpty() || bodyLimit <= 0) return "";
        StringBuilder clean = new StringBuilder(Math.min(body.length(), bodyLimit));
        int scanLimit = Math.min(body.length(), MAX_INPUT_SCAN);
        for (int offset = 0; offset < scanLimit && clean.length() < bodyLimit;) {
            int codePoint = body.codePointAt(offset);
            int width = Character.charCount(codePoint);
            offset += width;

            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) continue;
            if (codePoint == '\u00a7') {
                if (offset < scanLimit) {
                    int formatCode = body.codePointAt(offset);
                    if (isLegacyFormatCode(formatCode)) offset += Character.charCount(formatCode);
                }
                continue;
            }
            if (isBidiControl(codePoint)) continue;
            if (Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029) {
                if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t' ||
                        codePoint == 0x2028 || codePoint == 0x2029) {
                    appendIfFits(clean, ' ', bodyLimit);
                }
                continue;
            }
            if (clean.length() + width <= bodyLimit) clean.appendCodePoint(codePoint);
        }
        return clean.toString().strip();
    }

    private static boolean isLegacyFormatCode(int codePoint) {
        return codePoint >= '0' && codePoint <= '9' ||
                codePoint >= 'a' && codePoint <= 'f' ||
                codePoint >= 'k' && codePoint <= 'o' ||
                codePoint == 'r' || codePoint == 'x' ||
                codePoint >= 'A' && codePoint <= 'F' ||
                codePoint >= 'K' && codePoint <= 'O' ||
                codePoint == 'R' || codePoint == 'X';
    }

    private static boolean isBidiControl(int codePoint) {
        return codePoint == 0x061c || codePoint == 0x200e || codePoint == 0x200f ||
                codePoint >= 0x202a && codePoint <= 0x202e ||
                codePoint >= 0x2066 && codePoint <= 0x206f;
    }

    private static void appendIfFits(StringBuilder target, int codePoint, int limit) {
        if (target.length() + Character.charCount(codePoint) <= limit) target.appendCodePoint(codePoint);
    }

    @FunctionalInterface
    interface MessageSink {
        void send(String renderedLine, boolean overlay);
    }
}
