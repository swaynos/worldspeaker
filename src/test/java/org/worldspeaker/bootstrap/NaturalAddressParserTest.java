package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaturalAddressParserTest {
    private static final List<String> NAMES = List.of("Archivist", "Archivist Prime", "Dr. Archivist", "Café",
            "İris", "Straße", "守り手", "The Writer's Room");

    @Test
    void findsACompleteNameAtBeginningMiddleOrEndWithLiteralPunctuationAndCaseNormalization() {
        assertAddressed("Archivist, tell me a story.", "Archivist");
        assertAddressed("Could you, ARCHIVIST, tell me a story?", "Archivist");
        assertAddressed("Thanks for the map, archivist!", "Archivist");
        assertAddressed("‘The Writer's Room’ is ready.", "The Writer's Room");
        assertAddressed("Ask the Dr. Archivist, please.", "Dr. Archivist");
        assertAddressed("Archivist Prime, please.", "Archivist Prime");
        assertAddressed("Caf\u0065\u0301, welcome.", "Café");
        assertAddressed("i\u0307RIS, welcome.", "İris");
        assertAddressed("STRAẞE, welcome.", "Straße");
        assertAddressed("守り手、聞いてください", "守り手");
    }

    @Test
    void everyConfiguredAddressableNameMatchesLiterallyAtEachPositionUnderUnicodeNormalization() {
        List<String> configuredNames = List.of("Archivist", "守り手", "Café", "İris", "𐐀", "ب\u064e",
                "The Writer's Room");
        List<String> literalInputs = List.of("ARCHIVIST", "守り手", "Cafe\u0301", "i\u0307RIS", "𐐀", "ب\u064e",
                "The Writer's Room");

        for (int index = 0; index < configuredNames.size(); index++) {
            String configuredName = configuredNames.get(index);
            String literalInput = literalInputs.get(index);
            assertTrue(NaturalAddressParser.isValidConfiguredName(configuredName), configuredName);
            assertAddressed(literalInput + ", help", configuredName, List.of(configuredName));
            assertAddressed("Please " + literalInput + ", help", configuredName, List.of(configuredName));
            assertAddressed("Please help, " + literalInput, configuredName, List.of(configuredName));
        }
    }

    @Test
    void configuredNamesContainingParserRejectedCharactersAreNotAddressable() {
        for (String unsafeName : List.of("A\u200dB", "A\u200cB", "A\u2800B", "A\u200bB", "A\ufe0fB")) {
            assertFalse(NaturalAddressParser.isValidConfiguredName(unsafeName), unsafeName);
            assertEquals(NaturalAddressParser.Disposition.AMBIENT,
                    NaturalAddressParser.parse(unsafeName + ", help", List.of(unsafeName)).disposition(), unsafeName);
        }
    }

    @Test
    void choosesTheEarliestNameThenTheLongestNameAtThatPositionAndInvokesOnlyOnce() {
        assertAddressed("Archivist Prime said hello to Archivist.", "Archivist Prime");
        assertAddressed("First Archivist, then Archivist Prime.", "Archivist");
        assertAddressed("Dr. Archivist and Archivist", "Dr. Archivist");
    }

    @Test
    void requiresUnicodeWordBoundariesIncludingMarksConnectorsAndSupplementaryLetters() {
        for (String input : List.of("xArchivist", "Archivists", "_Archivist", "Archivist_", "你Archivist",
                "Archivist你", "𐐀Archivist", "Archivist𐐀", "e\u0301Archivist", "Archivist\u0301")) {
            assertEquals(NaturalAddressParser.Disposition.AMBIENT,
                    NaturalAddressParser.parse(input, NAMES).disposition(), input);
        }
        assertEquals(NaturalAddressParser.Disposition.AMBIENT,
                NaturalAddressParser.parse("Cafeteria", NAMES).disposition());
        assertEquals(NaturalAddressParser.Disposition.AMBIENT,
                NaturalAddressParser.parse("Cafe", NAMES).disposition(), "matching does not remove accents");
        assertEquals(NaturalAddressParser.Disposition.AMBIENT,
                NaturalAddressParser.parse("Strasse", NAMES).disposition(), "root lowercase is not full case folding");
        assertEquals(NaturalAddressParser.Disposition.AMBIENT,
                NaturalAddressParser.parse("Аrchivist", NAMES).disposition(), "a Cyrillic homoglyph is not Latin A");
    }

    @Test
    void spacesAndInternalPunctuationMatchLiterallyWithoutCollapsing() {
        assertAddressed("The Writer's Room!", "The Writer's Room");
        assertEquals(NaturalAddressParser.Disposition.AMBIENT,
                NaturalAddressParser.parse("The  Writer's Room!", NAMES).disposition());
        assertEquals(NaturalAddressParser.Disposition.AMBIENT,
                NaturalAddressParser.parse("The Writers Room!", NAMES).disposition());
    }

    @Test
    void hiddenOrMalformedCharactersNeverCreateANameMatchAndOnlySafeAddressingGetsInvalid() {
        for (String input : List.of("Archi\u200bvist", "Archivist\u200b", "Archi\u202evist", "Archivist\u202e",
                "Archi\u2066vist", "§aArchivist", "Archivist\n", "\u2800Archivist")) {
            assertEquals(NaturalAddressParser.Disposition.AMBIENT,
                    NaturalAddressParser.parse(input, NAMES).disposition(), input);
        }
        assertEquals(NaturalAddressParser.Disposition.INVALID,
                NaturalAddressParser.parse("Archivist, then hide \u202e", NAMES).disposition());
        assertEquals(NaturalAddressParser.Disposition.INVALID,
                NaturalAddressParser.parse("\ud800 Archivist", NAMES).disposition());
        assertEquals(NaturalAddressParser.Disposition.INVALID,
                NaturalAddressParser.parse("\u2800 Archivist", NAMES).disposition());
        assertEquals(NaturalAddressParser.Disposition.AMBIENT,
                NaturalAddressParser.parse("\ud800Archi\u200bvist", NAMES).disposition());
        assertEquals(NaturalAddressParser.Disposition.AMBIENT,
                NaturalAddressParser.parse("\u200b\ufeff\u0301", NAMES).disposition());
    }

    @Test
    void originalInputAndNormalizedWorkBoundsAreNeverTruncatedForMatching() {
        String exactlyBounded = "Archivist" + " ".repeat(NaturalAddressParser.MAX_INPUT_LENGTH - 9);
        assertEquals(NaturalAddressParser.MAX_INPUT_LENGTH, exactlyBounded.length());
        assertAddressed(exactlyBounded, "Archivist");
        assertEquals(NaturalAddressParser.Disposition.AMBIENT,
                NaturalAddressParser.parse(exactlyBounded + " ", NAMES).disposition());
        assertEquals(NaturalAddressParser.Disposition.AMBIENT,
                NaturalAddressParser.parse("Archivist" + "x".repeat(503), NAMES).disposition());
        assertEquals(NaturalAddressParser.MAX_NORMALIZED_LENGTH, 2048);
        assertTrue(NaturalAddressParser.normalize("İ".repeat(512)).length() < NaturalAddressParser.MAX_NORMALIZED_LENGTH);

        String maximumConfiguredName = "İ".repeat(NaturalAddressParser.MAX_CONFIGURED_NAME_LENGTH);
        assertTrue(NaturalAddressParser.isValidConfiguredName(maximumConfiguredName));
        assertAddressed(maximumConfiguredName + ", help", maximumConfiguredName, List.of(maximumConfiguredName));
    }

    @Test
    void anotherOnlinePlayersCompleteLeadingPrefixWinsBeforeCharacterDetection() {
        UUID sender = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID alex = UUID.fromString("00000000-0000-4000-8000-000000000002");
        List<NaturalAddressParser.OnlinePlayer> online = List.of(
                new NaturalAddressParser.OnlinePlayer(sender, "Sender"),
                new NaturalAddressParser.OnlinePlayer(alex, "Alex"));

        assertTrue(NaturalAddressParser.hasOtherOnlinePlayerPrefix("Alex, ask Archivist", sender, online));
        assertTrue(NaturalAddressParser.hasOtherOnlinePlayerPrefix("\u2003@aLeX: Archivist?", sender, online));
        assertFalse(NaturalAddressParser.hasOtherOnlinePlayerPrefix("Alex, ask Archivist", alex, online),
                "the sender's own name is excluded");
        assertFalse(NaturalAddressParser.hasOtherOnlinePlayerPrefix("Alex, ask Archivist", sender,
                List.of(new NaturalAddressParser.OnlinePlayer(sender, "Sender"))), "offline names do not suppress");
        assertFalse(NaturalAddressParser.hasOtherOnlinePlayerPrefix("Alexandra, ask Archivist", sender, online));
        assertFalse(NaturalAddressParser.hasOtherOnlinePlayerPrefix("Alexé, ask Archivist", sender, online));
        assertFalse(NaturalAddressParser.hasOtherOnlinePlayerPrefix(", Alex, ask Archivist", sender, online),
                "leading punctuation is not skipped");
        assertFalse(NaturalAddressParser.hasOtherOnlinePlayerPrefix("@ Alex, ask Archivist", sender, online));
        assertFalse(NaturalAddressParser.hasOtherOnlinePlayerPrefix("@Alexandra, ask Archivist", sender, online));
        assertFalse(NaturalAddressParser.hasOtherOnlinePlayerPrefix("@Alex, ask Archivist", sender,
                List.of()));
    }

    @Test
    void rejectsAnOversizedPrefixRosterInsteadOfUsingAnIncompleteSet() {
        UUID sender = UUID.randomUUID();
        List<NaturalAddressParser.OnlinePlayer> roster = new java.util.ArrayList<>();
        for (int index = 0; index <= NaturalAddressParser.MAX_ONLINE_PLAYERS; index++) {
            roster.add(new NaturalAddressParser.OnlinePlayer(UUID.randomUUID(), "Player" + index));
        }
        assertFalse(NaturalAddressParser.hasOtherOnlinePlayerPrefix("Archivist", sender, roster));
    }

    @Test
    void followupRecognitionIsOnlyFiveExactSafeWholeLineEnglishPhrases() {
        for (String accepted : List.of("why", "WHY?", "how so", "How so!", "what do you mean.",
                "can you explain that?", "tell me more!", "\u2003Why?\u00a0")) {
            assertTrue(NaturalAddressParser.isContinuationPhrase(accepted), accepted);
        }
        for (String rejected : List.of("", "yes", "I found iron", "why is Alex leaving?", "why now", "why??",
                "why .", "how  so", "what do you mean exactly", "can you explain this", "tell me more please",
                "why\u200b", "\ud800why", "why\n", "why" + " ".repeat(512))) {
            assertFalse(NaturalAddressParser.isContinuationPhrase(rejected), rejected);
        }
        assertTrue(NaturalAddressParser.isContinuationPhrase("\u00a0CAN YOU EXPLAIN THAT?\u2003"),
                "NFC/root-lowercase matching strips only edge Unicode spaces");
        assertFalse(NaturalAddressParser.isContinuationPhrase("can  you explain that?"),
                "internal spaces are not collapsed");
    }

    private static void assertAddressed(String input, String name) {
        NaturalAddressParser.Result result = NaturalAddressParser.parse(input, NAMES);
        assertEquals(NaturalAddressParser.Disposition.ADDRESSED, result.disposition(), input);
        assertEquals(name, result.matchedName(), input);
    }

    private static void assertAddressed(String input, String name, List<String> configuredNames) {
        NaturalAddressParser.Result result = NaturalAddressParser.parse(input, configuredNames);
        assertEquals(NaturalAddressParser.Disposition.ADDRESSED, result.disposition(), input);
        assertEquals(name, result.matchedName(), input);
    }
}
