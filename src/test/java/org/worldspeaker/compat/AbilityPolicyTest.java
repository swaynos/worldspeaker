package org.worldspeaker.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilityPolicyTest {
    private static final String CHARACTER = "fixture-character";
    private static final String REQUESTER = "00000000-0000-4000-8000-000000000041";

    @Test
    void sharedCompleteObserveAndLightningFixturesParseWithoutImplementingAnAbility() throws Exception {
        JsonObject fixtures = JsonParser.parseString(Files.readString(
                Path.of("compatibility/startup-config-v1-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        AbilityPolicy observe = AbilityPolicy.parse(findPolicy(fixtures, "complete-observe-grant"), CHARACTER);
        AbilityPolicy lightning = AbilityPolicy.parse(findPolicy(fixtures, "complete-visual-lightning-grant"), CHARACTER);
        assertEquals(AbilityPolicy.OBSERVE, observe.grants().getFirst().abilityId());
        assertEquals(48.0, observe.grants().getFirst().maxRangeBlocks());
        assertTrue(lightning.grants().getFirst().allowVisual());
        assertFalse(lightning.grants().getFirst().allowDamage());
        assertEquals(null, lightning.grants().getFirst().maxDamage());
        assertFalse(AbilityAvailability.isImplemented(AbilityPolicy.OBSERVE, 1));
        assertFalse(AbilityAvailability.isImplemented(AbilityPolicy.LIGHTNING, 1));
    }

    @Test
    void explicitDenialWinsAndCompleteGrantsSortStablyWithoutUnion() {
        JsonObject policy = policy();
        JsonArray grants = policy.getAsJsonArray("grants");
        grants.add(observeGrant("z-grant", 2, "minecraft:the_nether"));
        grants.add(observeGrant("a-grant", 1, "minecraft:overworld"));
        AbilityPolicy parsed = AbilityPolicy.parse(policy, CHARACTER);
        assertEquals(List.of("a-grant", "z-grant"), parsed.grantsFor(AbilityPolicy.OBSERVE).stream()
                .map(AbilityPolicy.Grant::id).toList());

        policy.getAsJsonArray("denials").add(denial("deny-observe"));
        assertEquals(List.of(), AbilityPolicy.parse(policy, CHARACTER).grantsFor(AbilityPolicy.OBSERVE));
    }

    @Test
    void rejectsUnknownAuthorityIncompleteGrantsDuplicateStableIdsAndOutOfBoundsValues() {
        JsonObject unknown = policy();
        JsonObject unknownGrant = observeGrant("unknown", 64, "minecraft:overworld");
        unknownGrant.addProperty("abilityId", "worldspeaker.core.execute_command");
        unknown.getAsJsonArray("grants").add(unknownGrant);
        assertThrows(IllegalArgumentException.class, () -> AbilityPolicy.parse(unknown, CHARACTER));

        JsonObject mismatch = policy();
        JsonObject wrongCharacter = observeGrant("wrong-character", 64, "minecraft:overworld");
        wrongCharacter.addProperty("characterId", "other-character");
        mismatch.getAsJsonArray("grants").add(wrongCharacter);
        assertThrows(IllegalArgumentException.class, () -> AbilityPolicy.parse(mismatch, CHARACTER));

        JsonObject duplicate = policy();
        duplicate.getAsJsonArray("grants").add(observeGrant("same", 64, "minecraft:overworld"));
        duplicate.getAsJsonArray("denials").add(denial("same"));
        assertThrows(IllegalArgumentException.class, () -> AbilityPolicy.parse(duplicate, CHARACTER));

        for (double range : new double[]{0.0, Double.NaN, Double.POSITIVE_INFINITY, 64.01}) {
            JsonObject invalid = policy();
            JsonObject grant = observeGrant("range", 64, "minecraft:overworld");
            grant.addProperty("maxRangeBlocks", range);
            invalid.getAsJsonArray("grants").add(grant);
            assertThrows(IllegalArgumentException.class, () -> AbilityPolicy.parse(invalid, CHARACTER));
        }
    }

    @Test
    void lightningDamageRequiresOneCompleteVisualDamageGrantAndAFinitePositiveCeiling() {
        for (double damage : new double[]{0.0, 5.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            JsonObject invalid = policy();
            JsonObject grant = lightningGrant("damage", true, damage);
            invalid.getAsJsonArray("grants").add(grant);
            assertThrows(IllegalArgumentException.class, () -> AbilityPolicy.parse(invalid, CHARACTER));
        }
        JsonObject valid = policy();
        valid.getAsJsonArray("grants").add(lightningGrant("damage", true, 5.0));
        AbilityPolicy.Grant grant = AbilityPolicy.parse(valid, CHARACTER).grants().getFirst();
        assertEquals(5.0, grant.maxDamage());

        JsonObject incomplete = policy();
        incomplete.getAsJsonArray("grants").add(lightningGrant("damage", true, null));
        assertThrows(IllegalArgumentException.class, () -> AbilityPolicy.parse(incomplete, CHARACTER));
    }

    @Test
    void revisionAndDirectFloatingPointValuesMustBeSafeAndFinite() {
        JsonObject policy = policy();
        policy.addProperty("revision", 9_007_199_254_740_991L);
        assertEquals(9_007_199_254_740_991L, AbilityPolicy.parse(policy, CHARACTER).revision());
        policy.add("revision", new JsonPrimitive(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> AbilityPolicy.parse(policy, CHARACTER));
        policy.add("revision", new JsonPrimitive(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> AbilityPolicy.parse(policy, CHARACTER));
    }

    @Test
    void sharedMathematicalJsonIntegerAndExactBoundCorpusMatchesThePolicy() throws Exception {
        JsonObject corpus = JsonParser.parseString(Files.readString(
                Path.of("compatibility/ability-policy-v1-numeric-corpus.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        for (var item : corpus.getAsJsonArray("valid")) {
            JsonObject testCase = item.getAsJsonObject();
            String rawJson = testCase.get("rawJson").getAsString();
            assertDoesNotThrow(() -> AbilityPolicy.parse(JsonParser.parseString(rawJson), CHARACTER),
                    testCase.get("name").getAsString());
        }
        for (var item : corpus.getAsJsonArray("invalid")) {
            JsonObject testCase = item.getAsJsonObject();
            String rawJson = testCase.get("rawJson").getAsString();
            assertThrows(IllegalArgumentException.class,
                    () -> AbilityPolicy.parse(JsonParser.parseString(rawJson), CHARACTER),
                    testCase.get("name").getAsString());
        }
    }

    @Test
    void optionalRegionsAndTypedIntervalsUseDefaultsAndStrictNarrowingBounds() {
        JsonObject absent = policy();
        absent.getAsJsonArray("grants").add(observeGrant("observe-defaults", 64, "minecraft:overworld"));
        AbilityPolicy.Grant defaults = AbilityPolicy.parse(absent, CHARACTER).grants().getFirst();
        assertNull(defaults.region());
        assertEquals(AbilityPolicy.ObserveLimits.defaults(), defaults.observeLimits());

        JsonObject narrowed = policy();
        JsonObject observe = observeGrant("observe-region", 48, "minecraft:overworld");
        observe.add("region", JsonParser.parseString("{\"minX\":-64,\"maxXExclusive\":0,\"minZ\":-8,\"maxZExclusive\":9}"));
        observe.add("limits", JsonParser.parseString("{\"requesterIntervalMs\":300000,\"characterIntervalMs\":250}"));
        narrowed.getAsJsonArray("grants").add(observe);
        AbilityPolicy.Grant parsed = AbilityPolicy.parse(narrowed, CHARACTER).grants().getFirst();
        assertEquals(new AbilityPolicy.Region(-64, 0, -8, 9), parsed.region());
        assertEquals(new AbilityPolicy.ObserveLimits(300_000, 250), parsed.observeLimits());

        JsonObject lightning = policy();
        JsonObject lightningGrant = lightningGrant("lightning-default-limits", false, null);
        lightningGrant.add("limits", new JsonObject());
        lightning.getAsJsonArray("grants").add(lightningGrant);
        assertEquals(AbilityPolicy.LightningLimits.defaults(), AbilityPolicy.parse(lightning, CHARACTER).grants().getFirst().lightningLimits());

        for (String invalidRegion : List.of(
                "{\"minX\":1,\"maxXExclusive\":1,\"minZ\":0,\"maxZExclusive\":1}",
                "{\"minX\":9007199254740992,\"maxXExclusive\":9007199254740993,\"minZ\":0,\"maxZExclusive\":1}",
                "{\"minX\":0,\"maxXExclusive\":1,\"minZ\":0,\"maxZExclusive\":1,\"y\":64}")) {
            JsonObject invalid = policy();
            JsonObject grant = observeGrant("invalid-region", 64, "minecraft:overworld");
            grant.add("region", JsonParser.parseString(invalidRegion));
            invalid.getAsJsonArray("grants").add(grant);
            assertThrows(IllegalArgumentException.class, () -> AbilityPolicy.parse(invalid, CHARACTER), invalidRegion);
        }
        for (String invalidLimits : List.of(
                "{\"requesterIntervalMs\":1999}",
                "{\"requesterIntervalMs\":300001}",
                "{\"requesterIntervalMs\":2000.0000000000001}",
                "{\"requesterCooldownMs\":30000}",
                "{\"arbitraryQuantity\":1}")) {
            JsonObject invalid = policy();
            JsonObject grant = observeGrant("invalid-limits", 64, "minecraft:overworld");
            grant.add("limits", JsonParser.parseString(invalidLimits));
            invalid.getAsJsonArray("grants").add(grant);
            assertThrows(IllegalArgumentException.class, () -> AbilityPolicy.parse(invalid, CHARACTER), invalidLimits);
        }
    }

    @Test
    void directGrantConstructionRetainsDefensiveCopiesOfAllMutableInputs() {
        ArrayList<String> requesters = new ArrayList<>(List.of(REQUESTER));
        ArrayList<String> dimensions = new ArrayList<>(List.of("minecraft:overworld"));
        ArrayList<String> targets = new ArrayList<>();
        AbilityPolicy.Grant grant = new AbilityPolicy.Grant("immutable-direct", CHARACTER, AbilityPolicy.OBSERVE, 1,
                requesters, targets, dimensions, 64, false, false, null,
                new AbilityPolicy.Region(-8, 8, -4, 4), new AbilityPolicy.ObserveLimits(4_000, 500), null);
        requesters.clear();
        dimensions.clear();
        targets.add(REQUESTER);
        assertEquals(List.of(REQUESTER), grant.requesterIds());
        assertEquals(List.of(), grant.otherTargetIds());
        assertEquals(List.of("minecraft:overworld"), grant.dimensions());
        assertEquals(new AbilityPolicy.Region(-8, 8, -4, 4), grant.region());
        assertThrows(UnsupportedOperationException.class, () -> grant.requesterIds().clear());
    }

    private static JsonObject findPolicy(JsonObject fixtures, String name) {
        for (var fixture : fixtures.getAsJsonArray("valid")) {
            JsonObject value = fixture.getAsJsonObject();
            if (name.equals(value.get("name").getAsString())) {
                return value.getAsJsonObject("bundle").getAsJsonObject("abilityPolicy");
            }
        }
        throw new AssertionError("missing shared ability policy fixture");
    }

    private static JsonObject policy() {
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("revision", 0);
        result.add("grants", new JsonArray());
        result.add("denials", new JsonArray());
        result.add("protection", new JsonObject());
        return result;
    }

    private static JsonObject observeGrant(String id, double range, String dimension) {
        JsonObject grant = new JsonObject();
        grant.addProperty("id", id);
        grant.addProperty("characterId", CHARACTER);
        grant.addProperty("abilityId", AbilityPolicy.OBSERVE);
        grant.addProperty("contractVersion", 1);
        JsonArray requesters = new JsonArray();
        requesters.add(REQUESTER);
        grant.add("requesterIds", requesters);
        JsonArray dimensions = new JsonArray();
        dimensions.add(dimension);
        grant.add("dimensions", dimensions);
        grant.addProperty("maxRangeBlocks", range);
        return grant;
    }

    private static JsonObject lightningGrant(String id, boolean allowDamage, Double maxDamage) {
        JsonObject grant = new JsonObject();
        grant.addProperty("id", id);
        grant.addProperty("characterId", CHARACTER);
        grant.addProperty("abilityId", AbilityPolicy.LIGHTNING);
        grant.addProperty("contractVersion", 1);
        JsonArray requesters = new JsonArray();
        requesters.add(REQUESTER);
        grant.add("requesterIds", requesters);
        JsonArray dimensions = new JsonArray();
        dimensions.add("minecraft:overworld");
        grant.add("dimensions", dimensions);
        grant.addProperty("allowVisual", true);
        grant.addProperty("allowDamage", allowDamage);
        if (maxDamage != null) grant.addProperty("maxDamage", maxDamage);
        return grant;
    }

    private static JsonObject denial(String id) {
        JsonObject denial = new JsonObject();
        denial.addProperty("id", id);
        denial.addProperty("abilityId", AbilityPolicy.OBSERVE);
        denial.addProperty("contractVersion", 1);
        return denial;
    }
}
