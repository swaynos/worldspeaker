package org.worldspeaker.ability;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.worldspeaker.integration.StrictJsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Immutable typed index of the two selected contracts; it grants no operation authority. */
public final class AbilityCatalogue {
    public static final int VERSION = 1;
    public static final int MAX_CONTRACTS = 2;
    public static final int MAX_REQUEST_BYTES = 4096;
    public static final int MAX_RESULT_BYTES = 4096;
    public static final int MAX_JSON_DEPTH = 8;
    public static final int MAX_DISCOVERY_BYTES = 65_536;

    private static final Set<String> CONTRACT_FIELDS = Set.of("abilityId", "contractVersion", "kind", "purpose",
            "inputSchema", "resultSchema", "permission", "bounds", "preconditions", "execution", "reliability",
            "privacy", "presentation");
    private static final Set<String> INPUT_REF_FIELDS = Set.of("$ref");
    private static final Set<String> PERMISSION_FIELDS = Set.of("node", "default", "requester", "target",
            "visualGrant", "damageGrant", "denialPrecedence", "provider");
    private static final Set<String> BOUNDS_FIELDS = Set.of("targetCount", "rangeBlocks", "grantRangeBlocks",
            "inFlightPerRequester", "inFlightPerCharacter", "snapshotsPerRequesterPer2000Ms",
            "snapshotsPerCharacterPerSecond", "readDeadlineAfterAdmissionMs", "snapshotMaxAgeAtDeliveryMs",
            "resultUtf8Bytes", "reads", "prohibitedWork", "oneEffectPerInvocation", "requestAgeMaxMs",
            "actionAdmissionAgeMaxMs", "presentationCount", "presentationLifetimeMaxMs", "damageAttemptsMax",
            "damageMaxHealthPoints", "attemptedDamage", "footprintColumns", "footprintSideBlocks",
            "footprintChunksMax", "cooldownRequesterMs", "cooldownTargetMs", "effectsPerCharacterPerSecond",
            "audioRangeBlocks", "audioVolumeMax", "audioRecipientsMax", "stepsMax",
            "prohibitedMutations");

    private static final AbilityCatalogue INSTANCE = loadPackaged();
    private final List<Contract> contracts;

    private AbilityCatalogue(List<Contract> contracts) {
        this.contracts = List.copyOf(contracts);
    }

    public static AbilityCatalogue selected() {
        return INSTANCE;
    }

    public List<Contract> contracts() {
        return contracts;
    }

    public Contract find(String abilityId) {
        return contracts.stream().filter(contract -> contract.abilityId().equals(abilityId)).findFirst().orElse(null);
    }

    private static AbilityCatalogue loadPackaged() {
        String resource = "/worldspeaker-ability-contracts-v1.json";
        try (InputStream input = AbilityCatalogue.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("packaged ability contract source is missing");
            byte[] bytes = input.readAllBytes();
            String source = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            JsonObject root = StrictJsonParser.parse(source, 16).getAsJsonObject();
            if (root.get("catalogueVersion").getAsInt() != VERSION) throw invalidCatalogue();
            JsonArray rawContracts = root.getAsJsonArray("contracts");
            if (rawContracts == null || rawContracts.size() != MAX_CONTRACTS) throw invalidCatalogue();
            List<Contract> parsed = List.of(parseContract(rawContracts.get(0).getAsJsonObject()),
                    parseContract(rawContracts.get(1).getAsJsonObject()));
            if (!parsed.get(0).abilityId().equals(AbilityPolicyIds.OBSERVE) ||
                    !parsed.get(1).abilityId().equals(AbilityPolicyIds.LIGHTNING)) throw invalidCatalogue();
            return new AbilityCatalogue(parsed);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalStateException state && state.getMessage() != null &&
                    state.getMessage().startsWith("invalid packaged ability catalogue")) throw state;
            throw new IllegalStateException("invalid packaged ability catalogue", failure);
        }
    }

    private static Contract parseContract(JsonObject value) {
        exact(value, CONTRACT_FIELDS);
        String abilityId = string(value, "abilityId");
        int version = integer(value, "contractVersion");
        Kind kind = switch (string(value, "kind")) {
            case "query" -> Kind.QUERY;
            case "effect" -> Kind.EFFECT;
            default -> throw invalidCatalogue();
        };
        String purpose = string(value, "purpose");
        JsonObject inputSchema = object(value, "inputSchema").deepCopy();
        JsonObject resultSchema = object(value, "resultSchema").deepCopy();
        String inputRef = schemaRef(inputSchema);
        String resultRef = schemaRef(resultSchema);
        JsonObject permission = object(value, "permission");
        only(permission, PERMISSION_FIELDS);
        String node = string(permission, "node");
        if (!"deny".equals(string(permission, "default"))) throw invalidCatalogue();
        JsonObject bounds = object(value, "bounds");
        only(bounds, BOUNDS_FIELDS);
        int targetCount = kind == Kind.QUERY ? integer(bounds, "targetCount") : 1;
        int rangeBlocks = kind == Kind.QUERY ? integer(bounds, "rangeBlocks") : 64;
        int resultBytes = integer(bounds, "resultUtf8Bytes");
        if (targetCount != 1 || rangeBlocks != 64 || resultBytes != MAX_RESULT_BYTES) throw invalidCatalogue();
        JsonObject execution = object(value, "execution");
        if (!"UNIMPLEMENTED".equals(string(execution, "implementationStatus")) ||
                !"UNQUALIFIED".equals(string(execution, "qualificationStatus")) ||
                !"UNAVAILABLE".equals(string(execution, "availability"))) throw invalidCatalogue();
        return new Contract(abilityId, version, kind == Kind.QUERY ? "query" : "effect", purpose,
                inputSchema, resultSchema, permission.deepCopy(), bounds.deepCopy(), object(value, "preconditions").deepCopy(),
                execution.deepCopy(), object(value, "reliability").deepCopy(), object(value, "privacy").deepCopy(),
                object(value, "presentation").deepCopy(), node, targetCount, rangeBlocks, resultBytes,
                ImplementationStatus.UNIMPLEMENTED, QualificationStatus.UNQUALIFIED, Availability.UNAVAILABLE);
    }

    private static String schemaRef(JsonObject value) {
        exact(value, INPUT_REF_FIELDS);
        String ref = string(value, "$ref");
        if (!ref.startsWith("https://worldspeaker.invalid/schema/ability-contracts-v1.json#/$defs/")) {
            throw invalidCatalogue();
        }
        return ref;
    }

    private static JsonObject object(JsonObject value, String field) {
        JsonElement element = value.get(field);
        if (element == null || !element.isJsonObject()) throw invalidCatalogue();
        return element.getAsJsonObject();
    }

    private static String string(JsonObject value, String field) {
        JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw invalidCatalogue();
        return element.getAsString();
    }

    private static int integer(JsonObject value, String field) {
        JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw invalidCatalogue();
        try { return new java.math.BigDecimal(element.getAsString()).intValueExact(); }
        catch (ArithmeticException | NumberFormatException malformed) { throw invalidCatalogue(); }
    }

    private static void exact(JsonObject value, Set<String> fields) {
        if (value == null || !value.keySet().equals(fields)) throw invalidCatalogue();
    }

    private static void only(JsonObject value, Set<String> fields) {
        if (value == null || !fields.containsAll(value.keySet())) throw invalidCatalogue();
    }

    private static IllegalStateException invalidCatalogue() {
        return new IllegalStateException("invalid packaged ability catalogue");
    }

    public enum Kind { QUERY, EFFECT }
    public enum ImplementationStatus { UNIMPLEMENTED }
    public enum QualificationStatus { UNQUALIFIED }
    public enum Availability { UNAVAILABLE }

    public record Contract(String abilityId, int contractVersion, String kind, String purpose,
                           JsonObject inputSchema, JsonObject resultSchema, JsonObject permission,
                           JsonObject bounds, JsonObject preconditions, JsonObject execution, JsonObject reliability,
                           JsonObject privacy, JsonObject presentation, String permissionNode,
                           int targetCount, int rangeBlocks, int resultUtf8Bytes,
                           ImplementationStatus implementationStatus,
                           QualificationStatus qualificationStatus, Availability availability) {
        public Contract {
            if (abilityId == null || contractVersion != 1 || !Set.of("query", "effect").contains(kind) || purpose == null ||
                    inputSchema == null || resultSchema == null || permission == null || bounds == null ||
                    preconditions == null || execution == null || reliability == null || privacy == null || presentation == null || permissionNode == null ||
                    implementationStatus != ImplementationStatus.UNIMPLEMENTED ||
                    qualificationStatus != QualificationStatus.UNQUALIFIED || availability != Availability.UNAVAILABLE) {
                throw invalidCatalogue();
            }
            inputSchema = inputSchema.deepCopy();
            resultSchema = resultSchema.deepCopy();
            permission = permission.deepCopy();
            bounds = bounds.deepCopy();
            preconditions = preconditions.deepCopy();
            execution = execution.deepCopy();
            reliability = reliability.deepCopy();
            privacy = privacy.deepCopy();
            presentation = presentation.deepCopy();
        }
        @Override public JsonObject inputSchema() { return inputSchema.deepCopy(); }
        @Override public JsonObject resultSchema() { return resultSchema.deepCopy(); }
        @Override public JsonObject permission() { return permission.deepCopy(); }
        @Override public JsonObject bounds() { return bounds.deepCopy(); }
        @Override public JsonObject preconditions() { return preconditions.deepCopy(); }
        @Override public JsonObject execution() { return execution.deepCopy(); }
        @Override public JsonObject reliability() { return reliability.deepCopy(); }
        @Override public JsonObject privacy() { return privacy.deepCopy(); }
        @Override public JsonObject presentation() { return presentation.deepCopy(); }
    }

    private static final class AbilityPolicyIds {
        private static final String OBSERVE = "worldspeaker.core.observe_player";
        private static final String LIGHTNING = "worldspeaker.core.strike_lightning";
    }
}
