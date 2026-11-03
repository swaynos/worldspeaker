package org.worldspeaker.bootstrap;

import org.worldspeaker.generated.PublicBodyUnicode;

/** Text rules shared by the Java protocol boundary and public-output checks. */
public final class ProtocolText {
    private ProtocolText() {}

    /** Matches ECMAScript String.prototype.trim() for protocol nonblank checks. */
    public static boolean isEcmaScriptBlank(String value) {
        return value == null || value.codePoints().allMatch(ProtocolText::isEcmaScriptWhitespace);
    }

    /** Validates exact public text without removing or normalizing unsafe code points. */
    public static boolean isSafePublicText(String value) {
        if (value == null || value.isEmpty() || isEcmaScriptBlank(value)) return false;
        int[] codePoints = value.codePoints().toArray();
        boolean visible = false;
        boolean visibleBaseInCluster = false;

        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            if (isControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029 ||
                    codePoint == 0x00a7 || codePoint == 0x034f || codePoint == 0x2800 || isBidiControl(codePoint)) {
                return false;
            }

            if (isVariationSelector(codePoint)) {
                if (index == 0 || !isVisibleBase(codePoints[index - 1])) return false;
                continue;
            }

            if (codePoint == 0x200c || codePoint == 0x200d) {
                if (!isSupportedJoinControl(codePoints, index)) return false;
                visibleBaseInCluster = false;
                continue;
            }

            if (isDefaultIgnorable(codePoint) || PublicBodyUnicode.isOther(codePoint)) return false;

            if (isEmojiModifier(codePoint)) {
                if (!hasEmojiBaseBefore(codePoints, index)) return false;
                visible = true;
                visibleBaseInCluster = true;
                continue;
            }

            if (isCombiningMark(codePoint)) {
                if (codePoint == 0x20e3) {
                    if (!isValidKeycap(codePoints, index)) return false;
                    visible = true;
                    visibleBaseInCluster = true;
                    continue;
                }
                if (!visibleBaseInCluster) return false;
                continue;
            }

            if (isVisibleBase(codePoint)) {
                visible = true;
                visibleBaseInCluster = true;
                continue;
            }

            visibleBaseInCluster = false;
        }
        return visible;
    }

    private static boolean isVisibleBase(int codePoint) {
        return PublicBodyUnicode.isVisibleBase(codePoint);
    }

    private static boolean isCombiningMark(int codePoint) {
        return PublicBodyUnicode.isMark(codePoint);
    }

    private static boolean isVariationSelector(int codePoint) {
        return PublicBodyUnicode.isVariationSelector(codePoint);
    }

    private static boolean isValidKeycap(int[] codePoints, int index) {
        if (index == 0) return false;
        int baseIndex = codePoints[index - 1] == 0xfe0f ? index - 2 : index - 1;
        if (baseIndex < 0 || codePoints[index - 1] == 0xfe0e) return false;
        int base = codePoints[baseIndex];
        return base == 0x23 || base == 0x2a || base >= 0x30 && base <= 0x39;
    }

    private static boolean isSupportedJoinControl(int[] codePoints, int index) {
        if (index == 0 || index + 1 >= codePoints.length) return false;
        int previous = codePoints[index - 1];
        int next = codePoints[index + 1];
        int pictographIndex = index - 1;
        while (pictographIndex >= 0 && (isEmojiModifier(codePoints[pictographIndex]) ||
                isVariationSelector(codePoints[pictographIndex]) || isCombiningMark(codePoints[pictographIndex]))) {
            pictographIndex--;
        }
        if (pictographIndex >= 0 && isExtendedPictographic(codePoints[pictographIndex]) && isExtendedPictographic(next)) {
            return true;
        }
        if (isArabicLetter(previous) && isArabicLetter(next)) return true;
        return index >= 2 && isVirama(previous) && isLetter(codePoints[index - 2]) && isLetter(next);
    }

    private static boolean hasEmojiBaseBefore(int[] codePoints, int index) {
        int baseIndex = index - 1;
        while (baseIndex >= 0 && isVariationSelector(codePoints[baseIndex])) baseIndex--;
        return baseIndex >= 0 && isExtendedPictographic(codePoints[baseIndex]);
    }

    private static boolean isArabicLetter(int codePoint) {
        return PublicBodyUnicode.isArabicLetter(codePoint);
    }

    private static boolean isLetter(int codePoint) {
        return PublicBodyUnicode.isLetter(codePoint);
    }

    private static boolean isVirama(int codePoint) {
        return PublicBodyUnicode.isVirama(codePoint);
    }

    private static boolean isExtendedPictographic(int codePoint) {
        return PublicBodyUnicode.isExtendedPictographic(codePoint);
    }

    private static boolean isEmojiModifier(int codePoint) {
        return PublicBodyUnicode.isEmojiModifier(codePoint);
    }

    private static boolean isBidiControl(int codePoint) {
        return codePoint == 0x061c || codePoint == 0x200e || codePoint == 0x200f ||
                codePoint >= 0x202a && codePoint <= 0x202e || codePoint >= 0x2066 && codePoint <= 0x206f;
    }

    private static boolean isDefaultIgnorable(int codePoint) {
        return PublicBodyUnicode.isDefaultIgnorable(codePoint);
    }

    private static boolean isControl(int codePoint) {
        return codePoint >= 0x00 && codePoint <= 0x1f || codePoint >= 0x7f && codePoint <= 0x9f;
    }

    static boolean hasVisibleText(String value) {
        return value != null && value.codePoints().anyMatch(ProtocolText::isVisibleBase);
    }

    private static boolean isEcmaScriptWhitespace(int codePoint) {
        return codePoint >= 0x0009 && codePoint <= 0x000d ||
                codePoint == 0x0020 || codePoint == 0x00a0 || codePoint == 0x1680 ||
                codePoint >= 0x2000 && codePoint <= 0x200a ||
                codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202f ||
                codePoint == 0x205f || codePoint == 0x3000 || codePoint == 0xfeff;
    }
}
