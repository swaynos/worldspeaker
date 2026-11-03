package org.worldspeaker.bootstrap;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Pure, bounded Unicode matching for explicitly configured natural addresses. */
public final class NaturalAddressParser {
    public static final int MAX_INPUT_LENGTH = 512;
    public static final int MAX_NORMALIZED_LENGTH = 2048;
    public static final int MAX_ONLINE_PLAYERS = 256;
    public static final int MAX_CONFIGURED_NAME_LENGTH = 48;

    private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final List<String> FOLLOWUP_PHRASES = List.of(
            "why", "how so", "what do you mean", "can you explain that", "tell me more");

    private NaturalAddressParser() {}

    public enum Disposition {
        AMBIENT,
        THIRD_PARTY,
        ADDRESSED,
        INVALID
    }

    public record Result(Disposition disposition, String matchedName) {
        public Result {
            if (disposition == null) throw new IllegalArgumentException("a routing disposition is required");
            if ((disposition == Disposition.ADDRESSED) != (matchedName != null)) {
                throw new IllegalArgumentException("only an addressed result has a matched name");
            }
        }
    }

    public record OnlinePlayer(UUID playerId, String profileName) {}

    /**
     * Classifies a bounded input without modifying it. Configured names must pass
     * {@link #isValidConfiguredName(String)}; invalid names are ignored. Invalid
     * input is only addressable when an intact name with safe boundaries is still
     * present in the original text.
     */
    public static Result parse(String input, List<String> configuredNames) {
        if (input == null || input.length() > MAX_INPUT_LENGTH || configuredNames == null || configuredNames.isEmpty()) {
            return ambient();
        }

        String normalizedInput = normalize(input);
        if (normalizedInput.length() > MAX_NORMALIZED_LENGTH) return ambient();

        Match match = findMatch(normalizedInput, configuredNames);
        if (!isSafeVisibleInput(input)) return match == null ? ambient() : new Result(Disposition.INVALID, null);
        return match == null ? ambient() : new Result(Disposition.ADDRESSED, match.name());
    }

    /** Returns whether the leading complete profile token names another online player. */
    public static boolean hasOtherOnlinePlayerPrefix(String input, UUID senderId, List<OnlinePlayer> onlinePlayers) {
        if (input == null || senderId == null || onlinePlayers == null || onlinePlayers.size() > MAX_ONLINE_PLAYERS) return false;
        String prefix = leadingProfileToken(input);
        if (prefix == null) return false;
        for (OnlinePlayer player : onlinePlayers) {
            if (player == null || player.playerId() == null || player.profileName() == null ||
                    !isProfileName(player.profileName())) continue;
            if (!player.playerId().equals(senderId) && prefix.equalsIgnoreCase(player.profileName())) return true;
        }
        return false;
    }

    /** NFC, root-locale lowercase, then NFC again; deliberately not full case folding. */
    public static String normalize(String value) {
        return Normalizer.normalize(
                Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT),
                Normalizer.Form.NFC);
    }

    public static boolean isProfileName(String value) {
        return value != null && PROFILE_NAME.matcher(value).matches();
    }

    /**
     * Deliberately limited English continuation grammar. This is exact whole-line
     * matching, not semantic recognition; caller still applies the finite window.
     */
    public static boolean isContinuationPhrase(String input) {
        if (input == null || input.length() > MAX_INPUT_LENGTH || !isSafeVisibleInput(input)) return false;
        String normalized = normalize(input);
        if (normalized.length() > MAX_NORMALIZED_LENGTH) return false;
        normalized = stripUnicodeSpace(normalized);
        if (normalized.isEmpty()) return false;
        char last = normalized.charAt(normalized.length() - 1);
        if (last == '.' || last == '?' || last == '!') normalized = normalized.substring(0, normalized.length() - 1);
        return FOLLOWUP_PHRASES.contains(normalized);
    }

    /** Shared input safety check for name routing and conservative follow-up recognition. */
    public static boolean isSafeVisibleInput(String text) {
        if (text == null) return false;
        boolean visible = false;
        for (int offset = 0; offset < text.length();) {
            char current = text.charAt(offset);
            if (Character.isHighSurrogate(current)) {
                if (offset + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(offset + 1))) return false;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
            int codePoint = text.codePointAt(offset);
            if (isUnsafeAddressCodePoint(codePoint)) return false;
            if (isVisibleCodePoint(codePoint)) visible = true;
            offset += Character.charCount(codePoint);
        }
        return visible;
    }

    /** Removes only Unicode space characters at the edges; internal spacing remains exact. */
    public static String stripUnicodeSpace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isUnicodeWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isUnicodeWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    /**
     * The shared display/configuration rule for names that must be usable by natural
     * addressing. Names containing code points rejected from address input are not
     * installed; input is never repaired by deleting or ignoring those characters.
     */
    public static boolean isValidConfiguredName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_CONFIGURED_NAME_LENGTH ||
                !name.equals(name.strip())) return false;

        boolean visible = false;
        for (int offset = 0; offset < name.length();) {
            char current = name.charAt(offset);
            if (Character.isHighSurrogate(current)) {
                if (offset + 1 >= name.length() || !Character.isLowSurrogate(name.charAt(offset + 1))) return false;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }

            int codePoint = name.codePointAt(offset);
            if (isUnsafeAddressCodePoint(codePoint)) return false;
            if (isVisibleNameCodePoint(codePoint)) visible = true;
            offset += Character.charCount(codePoint);
        }
        return visible && normalize(name).length() <= MAX_NORMALIZED_LENGTH;
    }

    private static String leadingProfileToken(String input) {
        int offset = 0;
        while (offset < input.length()) {
            int codePoint = input.codePointAt(offset);
            if (!isUnicodeWhitespace(codePoint)) break;
            offset += Character.charCount(codePoint);
        }
        if (offset < input.length() && input.charAt(offset) == '@') offset++;
        int start = offset;
        int count = 0;
        while (offset < input.length() && count < 16) {
            char current = input.charAt(offset);
            if (!isAsciiProfileCharacter(current)) break;
            offset++;
            count++;
        }
        if (count == 0) return null;
        if (offset < input.length() && isWordConstituent(input.codePointAt(offset))) return null;
        return input.substring(start, offset);
    }

    private static Match findMatch(String text, List<String> configuredNames) {
        List<NormalizedName> names = new ArrayList<>(Math.min(configuredNames.size(), 9));
        for (String name : configuredNames) {
            if (!isValidConfiguredName(name)) continue;
            String normalized = normalize(name);
            if (normalized.isEmpty()) continue;
            names.add(new NormalizedName(name, normalized));
        }

        Match best = null;
        for (NormalizedName name : names) {
            int from = 0;
            while (from <= text.length() - name.normalized().length()) {
                int start = text.indexOf(name.normalized(), from);
                if (start < 0) break;
                int end = start + name.normalized().length();
                from = start + 1;
                if (!hasSafeWordBoundaries(text, start, end)) continue;
                if (best == null || start < best.start() ||
                        (start == best.start() && name.normalized().length() > best.normalizedLength())) {
                    best = new Match(name.name(), start, name.normalized().length());
                }
            }
        }
        return best;
    }

    private static boolean hasSafeWordBoundaries(String text, int start, int end) {
        if (start > 0) {
            int previous = text.codePointBefore(start);
            if (isWordConstituent(previous) || isUnsafeAddressCodePoint(previous)) return false;
        }
        if (end < text.length()) {
            int next = text.codePointAt(end);
            if (isWordConstituent(next) || isUnsafeAddressCodePoint(next)) return false;
        }
        return true;
    }

    private static boolean isUnsafeAddressCodePoint(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint) || type == Character.FORMAT || codePoint == '\u00a7' ||
                codePoint == 0x00ad || codePoint == 0x034f || codePoint == 0x2800 ||
                codePoint == 0x115f || codePoint == 0x1160 || codePoint == 0x3164 ||
                codePoint == 0xfeff || codePoint == 0xffa0 || codePoint == 0x2028 || codePoint == 0x2029 ||
                codePoint >= 0x17b4 && codePoint <= 0x17b5 ||
                codePoint >= 0x180b && codePoint <= 0x180f ||
                codePoint >= 0xfe00 && codePoint <= 0xfe0f ||
                codePoint >= 0xfff0 && codePoint <= 0xfff8 ||
                codePoint >= 0x1bca0 && codePoint <= 0x1bca3 ||
                codePoint >= 0x1d173 && codePoint <= 0x1d17a ||
                codePoint >= 0x13430 && codePoint <= 0x1343f ||
                codePoint >= 0xe0000 && codePoint <= 0xe0fff;
    }

    private static boolean isVisibleNameCodePoint(int codePoint) {
        if (isUnicodeWhitespace(codePoint) || isUnsafeAddressCodePoint(codePoint) ||
                isCombiningMark(codePoint) || isEmojiModifier(codePoint)) return false;
        int type = Character.getType(codePoint);
        return type >= Character.UPPERCASE_LETTER && type <= Character.OTHER_LETTER ||
                type >= Character.DECIMAL_DIGIT_NUMBER && type <= Character.OTHER_NUMBER ||
                type >= Character.DASH_PUNCTUATION && type <= Character.OTHER_PUNCTUATION ||
                type >= Character.MATH_SYMBOL && type <= Character.OTHER_SYMBOL;
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK ||
                type == Character.ENCLOSING_MARK;
    }

    private static boolean isEmojiModifier(int codePoint) {
        return codePoint >= 0x1f3fb && codePoint <= 0x1f3ff;
    }

    private static boolean isVisibleCodePoint(int codePoint) {
        if (isUnicodeWhitespace(codePoint) || isUnsafeAddressCodePoint(codePoint)) return false;
        int type = Character.getType(codePoint);
        return type >= Character.UPPERCASE_LETTER && type <= Character.OTHER_LETTER ||
                type >= Character.DECIMAL_DIGIT_NUMBER && type <= Character.OTHER_NUMBER ||
                type >= Character.DASH_PUNCTUATION && type <= Character.OTHER_PUNCTUATION ||
                type >= Character.MATH_SYMBOL && type <= Character.OTHER_SYMBOL;
    }

    private static boolean isWordConstituent(int codePoint) {
        int type = Character.getType(codePoint);
        return type >= Character.UPPERCASE_LETTER && type <= Character.OTHER_LETTER ||
                type >= Character.DECIMAL_DIGIT_NUMBER && type <= Character.OTHER_NUMBER ||
                type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK ||
                type == Character.ENCLOSING_MARK || type == Character.CONNECTOR_PUNCTUATION;
    }

    private static boolean isUnicodeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isAsciiProfileCharacter(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z' ||
                value >= '0' && value <= '9' || value == '_';
    }

    private static Result ambient() {
        return new Result(Disposition.AMBIENT, null);
    }

    private record NormalizedName(String name, String normalized) {}
    private record Match(String name, int start, int normalizedLength) {}
}
