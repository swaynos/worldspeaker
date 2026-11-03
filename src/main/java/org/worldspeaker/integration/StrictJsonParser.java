package org.worldspeaker.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Parses a bounded protocol frame only after detecting duplicate decoded object names. */
public final class StrictJsonParser {
    private static final int MAX_NESTING = 128;

    private StrictJsonParser() {}

    public static JsonElement parse(String source) {
        return parse(source, MAX_NESTING);
    }

    public static JsonElement parse(String source, int maxNesting) {
        return parseWithNumberTokens(source, maxNesting).value();
    }

    /**
     * Parses strict JSON while retaining each number's original token by node
     * identity. BigDecimal retains mathematical precision, but alone loses the
     * sign of negative zero; result validation needs both facts.
     */
    public static ParsedDocument parseWithNumberTokens(String source, int maxNesting) {
        if (source == null || maxNesting < 1 || maxNesting > MAX_NESTING) {
            throw new IllegalArgumentException("JSON nesting limit is invalid");
        }
        IdentityHashMap<JsonPrimitive, String> numberTokens = new IdentityHashMap<>();
        try (JsonReader reader = new JsonReader(new StringReader(source))) {
            reader.setLenient(false);
            JsonElement result = readValue(reader, 0, maxNesting, numberTokens);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("trailing JSON content");
            return new ParsedDocument(result, numberTokens);
        } catch (IOException | NumberFormatException malformed) {
            throw new IllegalArgumentException("malformed strict JSON");
        }
    }

    private static JsonElement readValue(JsonReader reader, int depth, int maxNesting,
                                         IdentityHashMap<JsonPrimitive, String> numberTokens) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                if (depth >= maxNesting) throw new IllegalArgumentException("JSON nesting limit exceeded");
                reader.beginObject();
                JsonObject object = new JsonObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) throw new IllegalArgumentException("duplicate JSON object key");
                    object.add(name, readValue(reader, depth + 1, maxNesting, numberTokens));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                if (depth >= maxNesting) throw new IllegalArgumentException("JSON nesting limit exceeded");
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) array.add(readValue(reader, depth + 1, maxNesting, numberTokens));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                String token = reader.nextString();
                JsonPrimitive value = new JsonPrimitive(new BigDecimal(token));
                numberTokens.put(value, token);
                yield value;
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException("malformed strict JSON value");
        };
    }

    public static final class ParsedDocument {
        private final JsonElement value;
        private final Map<JsonPrimitive, String> numberTokens;

        private ParsedDocument(JsonElement value, IdentityHashMap<JsonPrimitive, String> numberTokens) {
            this.value = value;
            this.numberTokens = Collections.unmodifiableMap(new IdentityHashMap<>(numberTokens));
        }

        public JsonElement value() { return value; }

        /** Returns the exact token only for number nodes belonging to this document. */
        public String numberToken(JsonPrimitive value) { return numberTokens.get(value); }
    }
}
