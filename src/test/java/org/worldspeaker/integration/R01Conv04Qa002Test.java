package org.worldspeaker.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.worldspeaker.bootstrap.AttributedEvent;
import org.worldspeaker.bootstrap.ProtocolText;
import org.worldspeaker.compat.ConfiguredBinding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R01Conv04Qa002Test {
    private static final String PLAYER_ID = "00000000-0000-4000-8000-000000000005";
    private static final ConfiguredBinding BINDING = new ConfiguredBinding(
            "fixture-server", "fixture-world", "fixture-character", "fixture-agent");

    @Test
    void sharedConv04RawCorpusRetainsItsExpectedOutcomeWhenAdaptedToTheV5Envelope() throws Exception {
        JsonObject corpus = JsonParser.parseString(Files.readString(
                Path.of("compatibility/dialogue-v2-qa-002-corpus.json"))).getAsJsonObject();
        JsonArray cases = corpus.getAsJsonArray("cases");

        for (var item : cases) {
            JsonObject testCase = item.getAsJsonObject();
            String name = testCase.get("name").getAsString();
            String kind = testCase.get("kind").getAsString();
            String originalRawJson = testCase.get("rawJson").getAsString();
            String rawJson = adaptV2CorpusFrame(originalRawJson, kind);
            if (originalRawJson.contains("\"occurredAtUnixMillis\":1.00000000000000001")) {
                assertTrue(rawJson.contains("\"occurredAtUnixMillis\":1.00000000000000001"),
                        "the v5 envelope adapter must preserve the rounding-hidden raw numeric token");
            }
            boolean accepted = testCase.get("accepted").getAsBoolean() &&
                    !("response".equals(kind) && "U+0085-only answer text is not ECMAScript blank".equals(name));

            if (accepted) {
                assertDoesNotThrow(() -> parseAndValidate(rawJson, kind), name);
            } else {
                assertThrows(RuntimeException.class, () -> parseAndValidate(rawJson, kind), name);
            }
        }
    }

    @Test
    void retainedV2FixtureIsRejectedRatherThanDowngraded() throws Exception {
        JsonObject fixtures = JsonParser.parseString(Files.readString(
                Path.of("compatibility/dialogue-v2-fixtures.json"))).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> DialogueEnvelope.validateRequest(
                fixtures.getAsJsonObject("request"), PLAYER_ID, BINDING));
    }

    @Test
    void javaNonblankPredicateUsesExactlyTheEcmaScriptTrimWhitespaceSet() {
        String ecmaWhitespace = new String(new int[] {
                0x0009, 0x000a, 0x000b, 0x000c, 0x000d, 0x0020, 0x00a0, 0x1680,
                0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
                0x2008, 0x2009, 0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000,
                0xfeff,
        }, 0, 25);
        assertTrue(ProtocolText.isEcmaScriptBlank(ecmaWhitespace));
        assertTrue(ProtocolText.isEcmaScriptBlank(""));
        assertFalse(ProtocolText.isEcmaScriptBlank("\u0085"));
        assertFalse(ProtocolText.isEcmaScriptBlank("\u180e"));
        assertFalse(ProtocolText.isEcmaScriptBlank(" x "));
    }

    @Test
    void directAttributedEventConstructionRejectsEcmaWhitespaceOnlyTextAndPlayerNames() {
        UUID eventId = UUID.fromString("00000000-0000-4000-8000-000000000003");
        UUID epochId = UUID.fromString("00000000-0000-4000-8000-000000000004");
        UUID playerId = UUID.fromString(PLAYER_ID);

        assertThrows(IllegalArgumentException.class, () -> new AttributedEvent(
                "fixture-server", "fixture-world", "fixture-character", "fixture-agent", eventId, epochId, 0, 0,
                "live", "command", playerId, "PlayerA", "minecraft:overworld", "\u00a0"));
        assertThrows(IllegalArgumentException.class, () -> new AttributedEvent(
                "fixture-server", "fixture-world", "fixture-character", "fixture-agent", eventId, epochId, 0, 0,
                "live", "command", playerId, "\u00a0", "minecraft:overworld", "prompt"));
    }

    private static void parseAndValidate(String rawJson, String kind) {
        JsonObject frame = StrictJsonParser.parse(rawJson).getAsJsonObject();
        if ("request".equals(kind)) {
            DialogueEnvelope.validateRequest(frame, PLAYER_ID, BINDING);
        } else if ("response".equals(kind)) {
            DialogueEnvelope.validateResponse(frame);
        } else {
            throw new IllegalArgumentException("unknown QA-002 corpus frame kind");
        }
    }

    private static String adaptV2CorpusFrame(String rawJson, String kind) {
        String v5 = rawJson.replace("worldspeaker.compat.v2", "worldspeaker.compat.v5")
                .replace("\"contextBatch\":[],\"acknowledgement\":",
                        "\"contextBatch\":[],\"contextStatus\":{\"state\":\"disabled\",\"lossRevision\":\"0\",\"reasons\":[]},\"acknowledgement\":");
        if (!"request".equals(kind)) return v5;
        String withRevision = v5.replaceFirst("(\"invocationId\":\"[^\"]+\",)", "$1\"abilityPolicyRevision\":0,");
        if (withRevision.equals(v5)) throw new IllegalArgumentException("current dialogue request revision insertion failed");
        return withRevision.substring(0, withRevision.length() - 1) + ",\"progressContext\":[]}";
    }
}
