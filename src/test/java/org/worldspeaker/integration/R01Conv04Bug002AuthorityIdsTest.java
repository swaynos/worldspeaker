package org.worldspeaker.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.ConfiguredBinding;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R01Conv04Bug002AuthorityIdsTest {
    private static final String PLAYER_ID = "00000000-0000-4000-8000-000000000005";
    private static final ConfiguredBinding BINDING = new ConfiguredBinding(
            "fixture-server", "fixture-world", "fixture-character", "fixture-agent");

    @Test
    void sharedAuthorityIdCorpusRejectsEveryNonStringOnJavaInboundRequestAndResponsePaths() throws Exception {
        Path root = Path.of("compatibility");
        JsonObject corpus = JsonParser.parseString(Files.readString(
                root.resolve("dialogue-v2-authority-id-corpus.json"))).getAsJsonObject();
        JsonObject fixtures = JsonParser.parseString(Files.readString(
                root.resolve("dialogue-v5-fixtures.json"))).getAsJsonObject();
        JsonArray values = corpus.getAsJsonArray("nonStringValues");
        JsonArray paths = corpus.getAsJsonArray("authorityIdPaths");
        int javaPaths = 0;
        int checkedCases = 0;

        for (JsonElement pathElement : paths) {
            JsonObject item = pathElement.getAsJsonObject();
            JsonElement validatorElement = item.get("javaValidator");
            if (validatorElement == null || validatorElement.isJsonNull()) continue;
            javaPaths++;
            String validator = validatorElement.getAsString();
            for (JsonElement valueElement : values) {
                JsonObject frame = fixtures.getAsJsonObject(item.get("fixture").getAsString()).deepCopy();
                if ("followup".equals(item.has("setup") ? item.get("setup").getAsString() : null)) {
                    frame.getAsJsonObject("event").addProperty("inputKind", "followup");
                    frame.getAsJsonObject("conversation").addProperty(
                            "replyToMessageId", "00000000-0000-4000-8000-00000000000d");
                }
                setPath(frame, item.getAsJsonArray("path"), valueElement.getAsJsonObject().get("value").deepCopy());

                String name = item.get("name").getAsString() + " rejects " +
                        valueElement.getAsJsonObject().get("name").getAsString();
                if ("request".equals(validator)) {
                    assertThrows(RuntimeException.class,
                            () -> DialogueEnvelope.validateRequest(frame, PLAYER_ID, BINDING), name);
                } else if ("response".equals(validator)) {
                    assertThrows(RuntimeException.class, () -> DialogueEnvelope.validateResponse(frame), name);
                } else {
                    throw new IllegalArgumentException("unsupported Java validator in authority-ID corpus");
                }
                checkedCases++;
            }
        }

        assertTrue(javaPaths > 0, "corpus must exercise Java's inbound validators");
        assertEquals(javaPaths * values.size(), checkedCases);
    }

    private static void setPath(JsonObject root, JsonArray path, JsonElement value) {
        JsonElement parent = root;
        for (int index = 0; index < path.size() - 1; index++) {
            JsonElement segment = path.get(index);
            parent = segment.isJsonPrimitive() && segment.getAsJsonPrimitive().isNumber()
                    ? parent.getAsJsonArray().get(segment.getAsInt())
                    : parent.getAsJsonObject().get(segment.getAsString());
        }
        JsonElement last = path.get(path.size() - 1);
        if (last.isJsonPrimitive() && last.getAsJsonPrimitive().isNumber()) {
            parent.getAsJsonArray().set(last.getAsInt(), value);
        } else {
            parent.getAsJsonObject().add(last.getAsString(), value);
        }
    }
}
