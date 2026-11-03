package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.bootstrap.NaturalAddressParser;
import org.worldspeaker.compat.CharacterChatStyle;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaDialogueTest {
    @Test
    void rendersHostileLookingContentAsBoundedLiteralData() {
        String body = "<Player> {\"text\":\"run command\"} /op @a";

        assertEquals("[Worldspeaker] " + body, VanillaDialogue.renderLine(body));
    }

    @Test
    void acknowledgementUsesConfiguredUnicodeTextWithoutReplacingItWithADefault() {
        String rendered = VanillaDialogue.renderAcknowledgement("你好, {player}! 🙂", "TestPlayer", "Test Character");

        assertEquals("[Worldspeaker] Test Character: 你好, TestPlayer! 🙂", rendered);
        assertNotEquals("[Worldspeaker] I have your request.", rendered);
        assertTrue(VanillaDialogue.isUsableAcknowledgementTemplate("你好, {player}! 🙂"));
    }

    @Test
    void acknowledgementRejectsEveryRendererEmptyClass() {
        String highSurrogate = String.valueOf(Character.MIN_SURROGATE);
        String lowSurrogate = String.valueOf(Character.MAX_SURROGATE);
        String bidiControls = "\u061c\u200e\u200f\u202a\u202b\u202c\u202d\u202e" +
                "\u2066\u2067\u2068\u2069\u206a\u206b\u206c\u206d\u206e\u206f";
        String legacyCodes = "0123456789abcdefklmnorxABCDEFKLMNORX";

        assertEquals("", VanillaDialogue.renderAcknowledgement(bidiControls, "TestPlayer", "Test Character"));
        assertEquals("", VanillaDialogue.renderAcknowledgement("\u00a7", "TestPlayer", "Test Character"));
        for (int index = 0; index < legacyCodes.length(); index++) {
            assertEquals("", VanillaDialogue.renderAcknowledgement("\u00a7" + legacyCodes.charAt(index), "TestPlayer", "Test Character"));
        }
        assertEquals("", VanillaDialogue.renderAcknowledgement(highSurrogate, "TestPlayer", "Test Character"));
        assertEquals("", VanillaDialogue.renderAcknowledgement(lowSurrogate, "TestPlayer", "Test Character"));
        assertEquals("", VanillaDialogue.renderAcknowledgement("\u2028\u2029", "TestPlayer", "Test Character"));
        assertEquals("", VanillaDialogue.renderAcknowledgement("\u200b\ufeff\u00a0", "TestPlayer", "Test Character"));
        assertFalse(VanillaDialogue.isUsableAcknowledgementTemplate(bidiControls));
        assertFalse(VanillaDialogue.isUsableAcknowledgementTemplate("\u00a7a"));
        assertFalse(VanillaDialogue.isUsableAcknowledgementTemplate(highSurrogate));
        assertFalse(VanillaDialogue.isUsableAcknowledgementTemplate(lowSurrogate));
        assertFalse(VanillaDialogue.isUsableAcknowledgementTemplate("\u200b\ufeff\u00a0"));
    }

    @Test
    void sharedVisibilityRuleRejectsUnicodeTextWithoutAVisibleCandidate() {
        List<String> invisible = List.of(
                "\u2800", "\u2800\u2800", " \t\u00a0\u1680\u2000\u202f\u205f\u3000",
                "\u200b\ufeff\u2060", "\u115f\u1160\u3164\uffa0", "\u00ad\u034f",
                "\u0301\u0903\u20dd", "🏻🏼🏽🏾🏿",
                "\u2028\u2029", "\n\t");

        for (String text : invisible) {
            assertEquals("", VanillaDialogue.renderLine(text), text);
            assertEquals("", VanillaDialogue.renderCharacterLine("Archivist", text), text);
            assertEquals("", VanillaDialogue.renderAcknowledgement(text, "TestPlayer", "Archivist"), text);
            assertFalse(VanillaDialogue.isValidCharacterDisplayName(text), text);
            assertFalse(VanillaDialogue.isUsableAcknowledgementTemplate(text), text);
            assertFalse(VanillaDialogue.isRenderableAnswerBody(text), text);
        }
        String blankBraille = String.valueOf((char) 0x2800);
        assertEquals("", VanillaDialogue.renderCharacterLine(blankBraille, blankBraille));
    }

    @Test
    void visibilityRulePreservesUnicodeLettersEmojiModifiersAndCombiningSequences() {
        List<String> visible = List.of(
                "Привет", "你好", "ب\u064e", "कि", "e\u0301", "👩‍🚀", "👍🏽", "1\ufe0f\u20e3");

        for (String text : visible) {
            assertEquals(VanillaDialogue.LABEL + text, VanillaDialogue.renderLine(text), text);
            assertTrue(VanillaDialogue.isRenderableAnswerBody(text), text);
            assertTrue(VanillaDialogue.isUsableAcknowledgementTemplate(text), text);
        }
        assertFalse(VanillaDialogue.isValidCharacterDisplayName("👩‍🚀"),
                "joined emoji are visible output but are not accepted as natural-address names");
        assertTrue(VanillaDialogue.isValidCharacterDisplayName("ب\u064e"));
        assertEquals("[Worldspeaker] Astronomer: 👩‍🚀",
                VanillaDialogue.renderCharacterLine("Astronomer", "👩‍🚀"));
        assertEquals("[Worldspeaker] Astronomer: Hello 👩‍🚀, TestPlayer",
                VanillaDialogue.renderAcknowledgement("Hello 👩‍🚀, {player}", "TestPlayer", "Astronomer"));
    }

    @Test
    void acknowledgementUsesTheBoundedPublicationEnvelope() {
        String rendered = VanillaDialogue.renderAcknowledgement("{player}", "🙂".repeat(3000), "Test Character");

        assertTrue(rendered.length() <= VanillaDialogue.MAX_RENDERED_LENGTH);
        assertTrue(rendered.startsWith(VanillaDialogue.LABEL));
        assertFalse(Character.isHighSurrogate(rendered.charAt(rendered.length() - 1)));
    }

    @Test
    void removesLineBreaksControlsLegacyFormattingAndBidiControls() {
        String hostile = "first\nsecond\u2028third\u2029fourth\u0000\u00a7cRED\u202ehidden\u2066isolated";

        assertEquals("[Worldspeaker] first second third fourthREDhiddenisolated", VanillaDialogue.renderLine(hostile));
    }

    @Test
    void completeRenderedLineIsLimitedWithoutSplittingSupplementaryCharacters() {
        String rendered = VanillaDialogue.renderLine("🙂".repeat(VanillaDialogue.MAX_RENDERED_LENGTH));

        assertTrue(rendered.length() <= VanillaDialogue.MAX_RENDERED_LENGTH);
        assertTrue(rendered.startsWith(VanillaDialogue.LABEL));
        assertFalse(Character.isHighSurrogate(rendered.charAt(rendered.length() - 1)));
    }

    @Test
    void characterEnvelopeKeepsTheFixedWorldspeakerOriginAndCannotMakeAnEmptyBodyValid() {
        assertEquals("[Worldspeaker] Archivist: hello",
                VanillaDialogue.renderCharacterLine("Archivist", "hello"));
        assertEquals("", VanillaDialogue.renderCharacterLine("Archivist", ""));
        assertEquals("", VanillaDialogue.renderCharacterLine("Archivist", "\u202e"));
        assertEquals("", VanillaDialogue.renderCharacterLine("\u202e", "a visible body"));
        assertTrue(VanillaDialogue.isValidCharacterDisplayName("Sage of Tides"));
        assertFalse(VanillaDialogue.isValidCharacterDisplayName("name\nforged"));
        assertFalse(VanillaDialogue.isValidCharacterDisplayName("\u00a7cName"));
        assertFalse(VanillaDialogue.isValidCharacterDisplayName("\u200b\ufeff"));
        assertFalse(VanillaDialogue.isValidCharacterDisplayName("می\u200cروم"),
                "addressable names keep the stricter identifier policy rather than public-body text rules");
    }

    @Test
    void plainAndBracketedCharacterEnvelopesAreExactLiteralOrigins() {
        assertEquals("[Worldspeaker] Archivist: hello",
                VanillaDialogue.renderCharacterLine("Archivist", "hello", CharacterChatStyle.PLAIN));
        assertEquals("[Worldspeaker] [Archivist]: hello",
                VanillaDialogue.renderCharacterLine("Archivist", "hello", CharacterChatStyle.BRACKETED));
        assertEquals("[Worldspeaker] [Archivist]: Hello Tester",
                VanillaDialogue.renderAcknowledgement("Hello {player}", "Tester", "Archivist",
                        CharacterChatStyle.BRACKETED));
        assertEquals("", VanillaDialogue.renderCharacterLine("Archivist", "hello", null));
        assertEquals("", VanillaDialogue.renderCharacterLine("Archivist", "", CharacterChatStyle.BRACKETED));
        assertEquals("[Worldspeaker] [@a /op]: {\"text\":\"literal\"}",
                VanillaDialogue.renderCharacterLine("@a /op", "{\"text\":\"literal\"}", CharacterChatStyle.BRACKETED),
                "accepted names and command/JSON-looking content stay inert literal data under the fixed origin");
    }

    @Test
    void bracketedLongestNameAndCompleteMaximumAnswerFitWithoutTruncation() {
        String name = "A".repeat(NaturalAddressParser.MAX_CONFIGURED_NAME_LENGTH);
        String body = "x".repeat(VanillaDialogue.MAX_COMPLETED_ANSWER_LENGTH);
        String expected = "[Worldspeaker] [" + name + "]: " + body;
        String rendered = VanillaDialogue.renderCharacterLine(name, body, CharacterChatStyle.BRACKETED);

        assertEquals(expected, rendered);
        assertTrue(rendered.length() <= VanillaDialogue.MAX_RENDERED_LENGTH);
        assertTrue(VanillaDialogue.isRenderableAnswerBody(body));
        assertFalse(VanillaDialogue.isRenderableAnswerBody(body + "x"), "an oversized complete answer is rejected rather than cut");
        assertFalse(Character.isHighSurrogate(rendered.charAt(rendered.length() - 1)));

        AtomicInteger sends = new AtomicInteger();
        SmokeAnswerDelivery.Outcome rejected = SmokeAnswerDelivery.publishThenComplete(name, body + "x",
                CharacterChatStyle.BRACKETED, line -> {
                    sends.incrementAndGet();
                    return VanillaDialogue.publishRendered(line, (ignored, overlay) -> {});
                }, () -> true, () -> {}, () -> {});
        assertEquals(SmokeAnswerDelivery.Outcome.ANSWER_NOT_PUBLISHED, rejected);
        assertEquals(0, sends.get());
    }

    @Test
    void characterEnvelopeUsesTheFull2048UnitCapWithoutSplittingAstralText() {
        String rendered = VanillaDialogue.renderCharacterLine("Archivist", "🙂".repeat(3000));

        assertEquals(VanillaDialogue.MAX_RENDERED_LENGTH, rendered.length());
        assertTrue(rendered.startsWith("[Worldspeaker] Archivist: "));
        assertFalse(Character.isHighSurrogate(rendered.charAt(rendered.length() - 1)));
    }

    @Test
    void publicationUsesOneFixedLabelAndVanillaSystemChatNotOverlay() {
        AtomicReference<String> observedLine = new AtomicReference<>();
        AtomicReference<Boolean> observedOverlay = new AtomicReference<>();
        AtomicInteger sends = new AtomicInteger();
        String expected = "[Worldspeaker] hello";

        boolean dispatched = VanillaDialogue.publishRendered(expected, (line, overlay) -> {
            sends.incrementAndGet();
            observedLine.set(line);
            observedOverlay.set(overlay);
        });

        assertTrue(dispatched);
        assertEquals(1, sends.get());
        assertEquals(expected, observedLine.get());
        assertEquals(Boolean.FALSE, observedOverlay.get());
    }

    @Test
    void unsafeOrFailedDispatchIsNotRecordedAsSuccessful() {
        AtomicInteger sends = new AtomicInteger();

        assertFalse(VanillaDialogue.publishRendered("[Worldspeaker] x\nforged", (line, overlay) -> sends.incrementAndGet()));
        assertFalse(VanillaDialogue.publishRendered("", (line, overlay) -> sends.incrementAndGet()));
        assertEquals(0, sends.get());
        assertFalse(VanillaDialogue.publishRendered("[Worldspeaker] valid", (line, overlay) -> {
            throw new IllegalStateException("test sink failure");
        }));
    }
}
