package org.worldspeaker.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.worldspeaker.bootstrap.ProtocolText;
import org.worldspeaker.bootstrap.VanillaDialogue;
import org.worldspeaker.generated.PublicBodyUnicode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicBodyUnicodeTest {
    @Test
    void everyGeneratedJavaPropertyMatchesTheSharedNormativeTableForAllCodePoints() throws Exception {
        JsonObject root = StrictJsonParser.parse(Files.readString(
                Path.of("compatibility/unicode/public-body-unicode-15.0.0.json"))).getAsJsonObject();
        assertEquals(PublicBodyUnicode.VERSION, root.get("unicodeVersion").getAsString());
        assertEquals(PublicBodyUnicode.DATA_SHA256, root.get("propertiesSha256").getAsString());
        String productionText = Files.readString(Path.of("src/main/java/org/worldspeaker/bootstrap/ProtocolText.java"));
        assertTrue(productionText.contains("PublicBodyUnicode.isVisibleBase"));
        assertFalse(productionText.contains("Character.getType"),
                "body category decisions must not fall back to the host JDK tables");

        JsonObject properties = root.getAsJsonObject("properties");
        assertEquals(Set.of("letter", "mark", "number", "punctuation", "symbol", "other", "unassigned",
                        "visibleBase", "defaultIgnorable", "extendedPictographic", "emojiModifier",
                        "arabicLetter", "virama", "variationSelector"), properties.keySet());
        Map<String, int[]> normativeRanges = new LinkedHashMap<>();
        Map<String, Integer> rangeIndexes = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : properties.entrySet()) {
            JsonArray ranges = entry.getValue().getAsJsonArray();
            int[] flattened = new int[ranges.size() * 2];
            for (int index = 0; index < ranges.size(); index++) {
                JsonArray range = ranges.get(index).getAsJsonArray();
                flattened[index * 2] = range.get(0).getAsInt();
                flattened[index * 2 + 1] = range.get(1).getAsInt();
            }
            normativeRanges.put(entry.getKey(), flattened);
            rangeIndexes.put(entry.getKey(), 0);
        }

        for (int codePoint = Character.MIN_CODE_POINT; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            for (Map.Entry<String, int[]> property : normativeRanges.entrySet()) {
                int[] ranges = property.getValue();
                int rangeIndex = rangeIndexes.get(property.getKey());
                while (rangeIndex < ranges.length && codePoint > ranges[rangeIndex + 1]) rangeIndex += 2;
                rangeIndexes.put(property.getKey(), rangeIndex);
                boolean expected = rangeIndex < ranges.length && ranges[rangeIndex] <= codePoint;
                boolean actual = PublicBodyUnicode.hasProperty(property.getKey(), codePoint);
                if (actual != expected) {
                    throw new AssertionError(property.getKey() + " differs at U+" + Integer.toHexString(codePoint).toUpperCase());
                }
            }
        }
    }

    @Test
    void selectedUnicodeVersionRejectsNewerAssignmentsAndKeepsContextAndNameRulesDistinct() {
        assertFalse(ProtocolText.isSafePublicText("\u088f"), "U+088F is unassigned in Unicode 15.0");
        assertFalse(ProtocolText.isSafePublicText("visible\u088ftext"));
        assertTrue(ProtocolText.isSafePublicText(new String(Character.toChars(0x11f02))),
                "Unicode 15 Kawi content is accepted as a valid supplementary pair");
        assertTrue(VanillaDialogue.isRenderableAnswerBody("👩‍🚀"));
        assertTrue(VanillaDialogue.isRenderableAnswerBody("1️⃣"));
        assertTrue(VanillaDialogue.isRenderableAnswerBody("क्\u200dष"));
        assertFalse(VanillaDialogue.isValidCharacterDisplayName("می\u200cروم"),
                "the stricter natural-address name policy continues to reject ZWNJ");
        assertFalse(ProtocolText.isSafePublicText(String.valueOf(Character.MIN_SURROGATE)));
        assertTrue(ProtocolText.isSafePublicText("🫨"), "Unicode 15 emoji property data is used independent of the JDK tables");
    }
}
