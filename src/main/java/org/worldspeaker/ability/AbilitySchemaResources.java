package org.worldspeaker.ability;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.worldspeaker.integration.StrictJsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Exact, bounded JSON Schema resources delivered with each requester-scoped discovery. */
final class AbilitySchemaResources {
    private static final List<Descriptor> DESCRIPTORS = List.of(
            new Descriptor("/worldspeaker-ability-predicates-v1.schema.json",
                    "https://worldspeaker.invalid/schema/ability-predicates-v1.schema.json"),
            new Descriptor("/worldspeaker-ability-discovery-v1.schema.json",
                    "https://worldspeaker.invalid/schema/ability-discovery-v1.schema.json"),
            new Descriptor("/worldspeaker-ability-contracts-v1-schema-resource.json",
                    "https://worldspeaker.invalid/schema/ability-contracts-v1.json"));
    private static final List<AbilityDiscovery.SchemaResource> RESOURCES = load();

    private AbilitySchemaResources() {}

    static List<AbilityDiscovery.SchemaResource> selected() {
        return RESOURCES;
    }

    static JsonArray json(List<AbilityDiscovery.SchemaResource> resources) {
        JsonArray values = new JsonArray();
        for (AbilityDiscovery.SchemaResource resource : resources) {
            JsonObject value = new JsonObject();
            value.addProperty("$id", resource.id());
            value.addProperty("sha256", resource.sha256());
            value.addProperty("schemaJson", resource.schemaJson());
            values.add(value);
        }
        return values;
    }

    private static List<AbilityDiscovery.SchemaResource> load() {
        return DESCRIPTORS.stream().map(AbilitySchemaResources::load).toList();
    }

    private static AbilityDiscovery.SchemaResource load(Descriptor descriptor) {
        try (InputStream input = AbilitySchemaResources.class.getResourceAsStream(descriptor.path())) {
            if (input == null) throw invalidResource();
            byte[] bytes = input.readAllBytes();
            String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (content.startsWith("\ufeff") || bytes.length == 0 || bytes.length > 32_768) throw invalidResource();
            JsonObject parsed = StrictJsonParser.parse(content, 32).getAsJsonObject();
            if (!parsed.has("$id") || !parsed.get("$id").isJsonPrimitive() ||
                    !parsed.getAsJsonPrimitive("$id").isString() ||
                    !descriptor.id().equals(parsed.getAsJsonPrimitive("$id").getAsString()) ||
                    !parsed.has("$schema") || !parsed.get("$schema").isJsonPrimitive() ||
                    !parsed.getAsJsonPrimitive("$schema").isString()) throw invalidResource();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new AbilityDiscovery.SchemaResource(descriptor.id(), hash, content);
        } catch (IOException | NoSuchAlgorithmException | RuntimeException failure) {
            if (failure instanceof IllegalStateException state && state.getMessage() != null &&
                    state.getMessage().startsWith("invalid packaged ability schema resource")) throw state;
            throw new IllegalStateException("invalid packaged ability schema resource", failure);
        }
    }

    private static IllegalStateException invalidResource() {
        return new IllegalStateException("invalid packaged ability schema resource");
    }

    private record Descriptor(String path, String id) {}
}
