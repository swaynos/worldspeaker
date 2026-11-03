package org.worldspeaker.compat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionConfigAbilityPolicyTest {
    private static final URI ENDPOINT = URI.create("ws://127.0.0.1:65530");
    private static final String CHARACTER = "Character-01";
    private static final String OTHER_CHARACTER = "other-character";
    private static final String REQUESTER = "00000000-0000-4000-8000-000000000041";
    private static final String OTHER_REQUESTER = "00000000-0000-4000-8000-000000000042";

    @TempDir
    Path temporaryDirectory;

    @Test
    void canonicalConstructorRejectsAParsedGrantForAnotherExactCharacterWithoutEchoingItsValue() {
        AbilityPolicy otherCharacterPolicy = AbilityPolicy.parse(
                policyWithGrant(OTHER_CHARACTER, "other-grant", List.of("minecraft:overworld")), OTHER_CHARACTER);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> canonical(binding(CHARACTER), otherCharacterPolicy, CharacterChatStyle.PLAIN));

        assertTrue(failure.getMessage().contains("abilityPolicy"));
        assertFalse(failure.getMessage().contains(OTHER_CHARACTER));
    }

    @Test
    void matchingPolicyRemainsUnchangedAndOnlyTheExactCharacterIdDefinesGrantScope() {
        AbilityPolicy matching = AbilityPolicy.parse(policyWithGrant(CHARACTER, "observe-here",
                List.of("minecraft:the_nether", "minecraft:overworld")), CHARACTER);
        ConfiguredBinding binding = binding(CHARACTER);

        CompanionConfig config = canonical(binding, matching, CharacterChatStyle.BRACKETED);

        assertSame(matching, config.abilityPolicy());
        assertEquals(binding, config.binding());
        assertEquals(new ConfiguredBinding.CharacterKey("Server_A", "World-A", CHARACTER),
                config.binding().characterKey());
        assertEquals(CHARACTER, config.abilityPolicy().grants().getFirst().characterId());
        assertEquals(List.of("minecraft:the_nether", "minecraft:overworld"),
                config.abilityPolicy().grants().getFirst().dimensions(), "dimension identifiers and order are preserved");
        assertEquals(CharacterChatStyle.BRACKETED, config.characterChatStyle());

        ConfiguredBinding otherAssociation = new ConfiguredBinding("server_a", "World-A", CHARACTER, "agent-B");
        CompanionConfig associatedElsewhere = canonical(otherAssociation, matching, CharacterChatStyle.PLAIN);
        assertSame(matching, associatedElsewhere.abilityPolicy());
        assertNotEquals(config.binding().characterKey(), associatedElsewhere.binding().characterKey(),
                "server identity remains case-sensitive in the selected character key");
        assertEquals(config.binding().characterKey(),
                new ConfiguredBinding("Server_A", "World-A", CHARACTER, "agent-B").characterKey(),
                "agent association is separate from the server/world/character key");
        assertNotEquals(config.binding().characterKey(),
                new ConfiguredBinding("Server_A", "world-a", CHARACTER, "agent-A").characterKey(),
                "world identity remains case-sensitive");
        assertEquals(matching.grants(), associatedElsewhere.abilityPolicy().grants(),
                "server, world, and agent association do not synthesize or merge grants");

        ConfiguredBinding differentCase = binding("character-01");
        assertThrows(IllegalArgumentException.class,
                () -> canonical(differentCase, matching, CharacterChatStyle.PLAIN),
                "character IDs are compared exactly, without case folding");
        assertNotEquals(binding(CHARACTER).characterKey(), differentCase.characterKey());
    }

    @Test
    void denyAllAndDenialOnlyPoliciesRemainNonAuthorizingForDirectConstruction() {
        AbilityPolicy denyAll = AbilityPolicy.denyAll();
        CompanionConfig defaultPolicy = canonical(binding(CHARACTER), denyAll, CharacterChatStyle.PLAIN);
        assertSame(denyAll, defaultPolicy.abilityPolicy());
        assertTrue(defaultPolicy.abilityPolicy().grants().isEmpty());
        assertTrue(defaultPolicy.abilityPolicy().denials().isEmpty());

        AbilityPolicy denialsOnly = new AbilityPolicy(3, List.of(),
                List.of(new AbilityPolicy.Denial("deny-observe", AbilityPolicy.OBSERVE, 1)), "NONE");
        CompanionConfig denied = canonical(binding(CHARACTER), denialsOnly, CharacterChatStyle.BRACKETED);
        assertSame(denialsOnly, denied.abilityPolicy());
        assertTrue(denied.abilityPolicy().explicitlyDenies(AbilityPolicy.OBSERVE));
        assertTrue(denied.abilityPolicy().grants().isEmpty(), "denials do not create authority");
    }

    @Test
    void allSourceCompatibleDirectOverloadsDelegateWithDenyAllDefaultsAndPreserveExplicitStyle() {
        ConfiguredBinding binding = binding(CHARACTER);
        InteractionPolicy interaction = InteractionPolicy.denyAll();
        List<CompanionConfig> defaults = List.of(
                new CompanionConfig(ENDPOINT, new byte[32], binding, "Hello {player}", "Archivist", interaction),
                new CompanionConfig(ENDPOINT, new byte[32], binding, "Hello {player}", "Archivist", interaction,
                        List.of("The Scribe")),
                new CompanionConfig(ENDPOINT, new byte[32], binding, "Hello {player}", "Archivist", interaction,
                        List.of("The Scribe"), 12),
                new CompanionConfig(ENDPOINT, new byte[32], binding, "Hello {player}", "Archivist", interaction,
                        List.of("The Scribe"), 12, AttentionPolicy.disabled()),
                new CompanionConfig(ENDPOINT, new byte[32], binding, "Hello {player}", "Archivist", interaction,
                        List.of("The Scribe"), 12, AttentionPolicy.disabled(), 17));
        CompanionConfig explicitStyle = new CompanionConfig(ENDPOINT, new byte[32], binding, "Hello {player}",
                "Archivist", interaction, List.of("The Scribe"), 12, AttentionPolicy.disabled(), 17,
                CharacterChatStyle.BRACKETED);

        for (CompanionConfig config : defaults) {
            assertEquals(AbilityPolicy.denyAll(), config.abilityPolicy());
            assertEquals(CharacterChatStyle.PLAIN, config.characterChatStyle());
        }
        assertEquals(30, defaults.getFirst().followupWindowSeconds());
        assertEquals(30, defaults.getFirst().executionBudgetSeconds());
        assertEquals(12, explicitStyle.followupWindowSeconds());
        assertEquals(17, explicitStyle.executionBudgetSeconds());
        assertEquals(CharacterChatStyle.BRACKETED, explicitStyle.characterChatStyle());
        assertEquals(AbilityPolicy.denyAll(), explicitStyle.abilityPolicy());
    }

    @Test
    void abilityPolicyAndItsNestedCollectionsAreDefensivelyImmutable() {
        List<String> requesters = new ArrayList<>(List.of(REQUESTER));
        List<String> dimensions = new ArrayList<>(List.of("minecraft:the_nether"));
        AbilityPolicy.Grant grant = new AbilityPolicy.Grant("immutable-grant", CHARACTER, AbilityPolicy.OBSERVE,
                1, requesters, new ArrayList<>(), dimensions, 48.0, false, false, null);
        List<AbilityPolicy.Grant> grants = new ArrayList<>(List.of(grant));
        List<AbilityPolicy.Denial> denials = new ArrayList<>();
        AbilityPolicy policy = new AbilityPolicy(7, grants, denials, "REQUIRED");
        CompanionConfig config = canonical(binding(CHARACTER), policy, CharacterChatStyle.PLAIN);

        requesters.add(OTHER_REQUESTER);
        dimensions.add("minecraft:overworld");
        grants.clear();
        denials.add(new AbilityPolicy.Denial("late-denial", AbilityPolicy.OBSERVE, 1));

        assertEquals(List.of("immutable-grant"), config.abilityPolicy().grants().stream()
                .map(AbilityPolicy.Grant::id).toList());
        assertEquals(List.of(REQUESTER), config.abilityPolicy().grants().getFirst().requesterIds());
        assertEquals(List.of("minecraft:the_nether"), config.abilityPolicy().grants().getFirst().dimensions());
        assertTrue(config.abilityPolicy().denials().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> policy.grants().clear());
        assertThrows(UnsupportedOperationException.class, () -> policy.grants().getFirst().dimensions().clear());
        assertThrows(UnsupportedOperationException.class, () -> policy.denials().add(
                new AbilityPolicy.Denial("late-denial", AbilityPolicy.OBSERVE, 1)));
    }

    @Test
    void loadedPolicyRemainsAnImmutableSnapshotAfterTheBundleChanges() throws IOException {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of(
                "serverId", "Server_A", "worldId", "World-A", "characterId", CHARACTER, "agentId", "agent-A"));
        writeLoadedPolicy(bootstrap, "loaded-first", "minecraft:the_nether");

        CompanionConfig before = CompanionConfig.load(bootstrap);
        AbilityPolicy beforePolicy = before.abilityPolicy();
        writeLoadedPolicy(bootstrap, "loaded-second", "minecraft:overworld");
        CompanionConfig after = CompanionConfig.load(bootstrap);

        assertSame(beforePolicy, before.abilityPolicy());
        assertNotSame(before.abilityPolicy(), after.abilityPolicy());
        assertEquals(List.of("loaded-first"), before.abilityPolicy().grants().stream()
                .map(AbilityPolicy.Grant::id).toList());
        assertEquals(List.of("minecraft:the_nether"), before.abilityPolicy().grants().getFirst().dimensions());
        assertEquals(List.of("loaded-second"), after.abilityPolicy().grants().stream()
                .map(AbilityPolicy.Grant::id).toList());
        assertEquals(List.of("minecraft:overworld"), after.abilityPolicy().grants().getFirst().dimensions());
        assertEquals(new ConfiguredBinding.CharacterKey("Server_A", "World-A", CHARACTER),
                before.binding().characterKey());
        assertEquals(before.binding(), after.binding());
        assertNotEquals(before.configSetSha256(), after.configSetSha256());
    }

    private static CompanionConfig canonical(ConfiguredBinding binding, AbilityPolicy policy, CharacterChatStyle style) {
        return new CompanionConfig(ENDPOINT, new byte[32], binding, "Hello {player}", "Archivist",
                InteractionPolicy.denyAll(), List.of(), 30, AttentionPolicy.disabled(), 30, style, policy,
                "0".repeat(64));
    }

    private static ConfiguredBinding binding(String characterId) {
        return new ConfiguredBinding("Server_A", "World-A", characterId, "agent-A");
    }

    private static JsonObject policyWithGrant(String characterId, String id, List<String> dimensions) {
        JsonObject policy = new JsonObject();
        policy.addProperty("schemaVersion", 1);
        policy.addProperty("revision", 7);
        JsonArray grants = new JsonArray();
        JsonObject grant = new JsonObject();
        grant.addProperty("id", id);
        grant.addProperty("characterId", characterId);
        grant.addProperty("abilityId", AbilityPolicy.OBSERVE);
        grant.addProperty("contractVersion", 1);
        JsonArray requesterIds = new JsonArray();
        requesterIds.add(REQUESTER);
        grant.add("requesterIds", requesterIds);
        JsonArray dimensionIds = new JsonArray();
        dimensions.forEach(dimensionIds::add);
        grant.add("dimensions", dimensionIds);
        grant.addProperty("maxRangeBlocks", 48.0);
        grants.add(grant);
        policy.add("grants", grants);
        policy.add("denials", new JsonArray());
        policy.add("protection", new JsonObject());
        return policy;
    }

    private static void writeLoadedPolicy(Path bootstrap, String grantId, String dimension) throws IOException {
        Path bundlePath = bootstrap.resolveSibling("startup.json");
        JsonObject bundle = JsonParser.parseString(Files.readString(bundlePath)).getAsJsonObject();
        bundle.add("abilityPolicy", policyWithGrant(CHARACTER, grantId, List.of(dimension)));
        Files.writeString(bundlePath, new Gson().toJson(bundle));
    }
}
