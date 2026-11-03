package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.integration.StrictJsonParser;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaDialogueSurrogateTest {
    @Test
    void removesJsonEscapedUnpairedHighAndLowSurrogates() {
        String high = jsonText("\\uD800");
        String low = jsonText("\\uDC00");

        assertEquals(1, high.length());
        assertTrue(Character.isHighSurrogate(high.charAt(0)));
        assertEquals(1, low.length());
        assertTrue(Character.isLowSurrogate(low.charAt(0)));
        assertEquals(VanillaDialogue.LABEL + "AB", VanillaDialogue.renderLine("A" + high + "B"));
        assertEquals(VanillaDialogue.LABEL + "AB", VanillaDialogue.renderLine("A" + low + "B"));
    }

    @Test
    void removesMultipleIsolatedSurrogatesAndPreservesValidPair() {
        String high = String.valueOf(Character.MIN_SURROGATE);
        String low = String.valueOf(Character.MAX_SURROGATE);
        String pair = new String(Character.toChars(0x1f642));
        String body = "before" + high + pair + low + high + "after" + low;

        assertEquals(VanillaDialogue.LABEL + "before" + pair + "after", VanillaDialogue.renderLine(body));
    }

    @Test
    void validPairAfterRemovedSurrogateFitsExactlyAtUtf16RenderCap() {
        int bodyLimit = VanillaDialogue.MAX_RENDERED_LENGTH - VanillaDialogue.LABEL.length();
        String high = String.valueOf(Character.MIN_SURROGATE);
        String pair = new String(Character.toChars(0x1f642));
        String body = "x".repeat(bodyLimit - pair.length()) + high + pair;
        String rendered = VanillaDialogue.renderLine(body);

        assertEquals(VanillaDialogue.MAX_RENDERED_LENGTH, rendered.length());
        assertEquals(VanillaDialogue.LABEL + "x".repeat(bodyLimit - pair.length()) + pair, rendered);
        assertTrue(rendered.endsWith(pair));
    }

    @Test
    void surrogateOnlyAnswerStaysNoncompleteAfterSanitizingToEmpty() {
        String high = String.valueOf(Character.MIN_SURROGATE);
        String body = high + high;
        AtomicInteger publishCalls = new AtomicInteger();
        AtomicInteger completionCalls = new AtomicInteger();
        AtomicInteger nextTurnCalls = new AtomicInteger();

        assertEquals("", VanillaDialogue.renderLine(body));
        SmokeAnswerDelivery.Outcome outcome = SmokeAnswerDelivery.publishThenComplete("Archivist", body, rendered -> {
            publishCalls.incrementAndGet();
            return VanillaDialogue.publishRendered(rendered, (line, overlay) -> {});
        }, () -> {
            completionCalls.incrementAndGet();
            return true;
        }, nextTurnCalls::incrementAndGet);

        assertEquals(SmokeAnswerDelivery.Outcome.ANSWER_NOT_PUBLISHED, outcome);
        assertEquals(0, publishCalls.get());
        assertEquals(0, completionCalls.get());
        assertEquals(0, nextTurnCalls.get());
    }

    private static String jsonText(String escapedValue) {
        return StrictJsonParser.parse("{\"text\":\"" + escapedValue + "\"}")
                .getAsJsonObject().get("text").getAsString();
    }
}
