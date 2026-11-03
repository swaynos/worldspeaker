package org.worldspeaker.ability;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.AbilityPolicy;
import org.worldspeaker.bootstrap.AbilityAuthorityFixture;
import org.worldspeaker.bootstrap.AbilityRequestAuthority;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AbilityPolicyResolverTest {
    private static final String REQUEST = "00000000-0000-4000-8000-000000000065";
    private static final String INVOCATION = "00000000-0000-4000-8000-000000000066";
    private static final String EPOCH = "00000000-0000-4000-8000-000000000067";

    @Test
    void productionJavaResolverMatchesTheSharedTypeScriptEvaluationCorpus() throws Exception {
        JsonObject corpus = JsonParser.parseString(Files.readString(
                Path.of("compatibility/ability-policy-evaluation-v1-corpus.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        AbilityValidation.Binding defaultBinding = binding(corpus.getAsJsonObject("binding"));
        String requester = corpus.get("requesterId").getAsString();
        String invocation = corpus.get("invocationId").getAsString();
        JsonObject defaultFacts = corpus.getAsJsonObject("facts");

        for (JsonElement raw : corpus.getAsJsonArray("vectors")) {
            JsonObject vector = raw.getAsJsonObject();
            JsonObject policyJson = vector.getAsJsonObject("policy");
            AbilityPolicy policy = AbilityPolicy.parse(policyJson, defaultBinding.characterId());
            AbilityValidation.Binding selectedBinding = vector.has("binding")
                    ? binding(vector.getAsJsonObject("binding")) : defaultBinding;
            AtomicBoolean current = new AtomicBoolean(!vector.has("authorityCurrent") ||
                    vector.get("authorityCurrent").getAsBoolean());
            JsonArray profiles = vector.has("factProfiles") ? vector.getAsJsonArray("factProfiles") : new JsonArray();
            if (profiles.isEmpty()) profiles.add(new JsonObject());
            for (JsonElement rawProfile : profiles) {
                JsonObject profile = rawProfile.getAsJsonObject();
                JsonObject factsOverride = new JsonObject();
                if (vector.has("factsOverrides")) {
                    for (String key : vector.getAsJsonObject("factsOverrides").keySet()) {
                        factsOverride.add(key, vector.getAsJsonObject("factsOverrides").get(key));
                    }
                }
                if (profile.has("factsOverrides")) {
                    for (String key : profile.getAsJsonObject("factsOverrides").keySet()) {
                        factsOverride.add(key, profile.getAsJsonObject("factsOverrides").get(key));
                    }
                }
                String name = vector.get("name").getAsString() + (profile.has("name") ? ":" + profile.get("name").getAsString() : "");
                AbilityValidation.AbilityTrustedContextHandle context = AbilityAuthorityFixture.issue(
                        selectedBinding, REQUEST, invocation, requester, EPOCH, policy, current::get);
                AbilityRequestAuthority.ExpectedContext expectedContext = AbilityAuthorityFixture.expected(context);
                assertNotNull(context, name);
                AbilityPolicyResolver.WorldFacts facts = worldFacts(defaultFacts, factsOverride);
                AbilityPolicyResolver.Decision decision = vector.has("mode") && "discovery".equals(vector.get("mode").getAsString())
                        ? AbilityPolicyResolver.resolveDiscovery(context, expectedContext, vector.get("abilityId").getAsString())
                        : AbilityPolicyResolver.resolveIntent(context, vector.get("abilityId").getAsString(), expectedContext,
                                vector.get("contractVersion").getAsInt(), vector.get("targetId").getAsString(),
                                vector.get("visualOnly").getAsBoolean(), facts);
                JsonObject expected = vector.getAsJsonObject("expected");
                assertEquals(expected.get("status").getAsString(), decision.status().name(), name);
                assertEquals(vector.get("abilityId").getAsString(), decision.abilityId(), name);
                assertEquals(vector.get("contractVersion").getAsInt(), decision.contractVersion(), name);
                if (expected.has("policyRevision")) assertEquals(expected.get("policyRevision").getAsLong(), decision.policyRevision(), name);
                assertEquals(expected.get("reasonCode").getAsString(), decision.reason().name().toLowerCase(Locale.ROOT), name);
                assertEquals(expected.get("selectedGrantId").isJsonNull() ? null : expected.get("selectedGrantId").getAsString(),
                        decision.selectedGrantId(), name);
                if (expected.has("effectiveRestrictions")) {
                    JsonElement expectedRestrictions = expected.get("effectiveRestrictions");
                    if (expectedRestrictions.isJsonNull()) assertNull(decision.effectiveRestrictions(), name);
                    else assertEffectiveRestrictions(expectedRestrictions.getAsJsonObject(), decision.effectiveRestrictions(), name);
                }
                if (expected.has("candidateGrantIds")) {
                    assertEquals(expected.getAsJsonArray("candidateGrantIds").asList().stream().map(JsonElement::getAsString).toList(),
                            decision.candidateGrants().stream().map(AbilityPolicy.Grant::id).toList(), name);
                }
                if (expected.has("hasEffectiveRestrictions")) {
                    assertEquals(expected.get("hasEffectiveRestrictions").getAsBoolean(), decision.effectiveRestrictions() != null, name);
                }
                if (expected.has("hasLimitDemand")) {
                    assertEquals(expected.get("hasLimitDemand").getAsBoolean(), decision.limitDemand() != null, name);
                }
                if (expected.has("limitDemand") && expected.get("limitDemand").isJsonNull()) assertNull(decision.limitDemand(), name);
                assertTrue(decision.candidateGrants().size() <= AbilityPolicy.MAX_ENTRIES, name);
                if (expected.has("maxRangeBlocks")) assertEquals(expected.get("maxRangeBlocks").getAsDouble(),
                        decision.effectiveRestrictions().maxRangeBlocks(), name);
                if (expected.has("mode")) assertEquals(expected.get("mode").getAsString(), decision.effectiveRestrictions().mode(), name);
                if (expected.has("maxDamage")) {
                    assertEquals(expected.get("maxDamage").isJsonNull() ? null : expected.get("maxDamage").getAsDouble(),
                            decision.effectiveRestrictions().maxDamage(), name);
                }
                if (expected.has("limits")) assertLimits(expected.getAsJsonObject("limits"), decision.effectiveRestrictions(), name);
                if (expected.has("unresolvedChecks")) {
                    assertEquals(expected.getAsJsonArray("unresolvedChecks").asList().stream().map(JsonElement::getAsString).toList(),
                            decision.unresolvedChecks().stream().map(check -> check.name().toLowerCase(Locale.ROOT)).toList(), name);
                }
                assertThrows(UnsupportedOperationException.class, () -> decision.candidateGrants().clear(), name);
                if (decision.status() == AbilityPolicyResolver.Status.CONDITIONAL) {
                    assertFalse(decision.reason() != AbilityPolicyResolver.Reason.NONE, name);
                    assertNotNull(decision.unresolvedChecks(), name);
                }
            }
        }
    }

    @Test
    void requesterDiscoveryIsConditionalMetadataWithCandidatesAndNoSelectedGrant() {
        AbilityPolicy policy = new AbilityPolicy(4, List.of(new AbilityPolicy.Grant("observe-self", "character-test",
                AbilityPolicy.OBSERVE, 1, List.of("00000000-0000-4000-8000-000000000041"), List.of(),
                List.of("minecraft:overworld"), 64, false, false, null)), List.of(), "REQUIRED");
        AtomicBoolean current = new AtomicBoolean(true);
        AbilityValidation.AbilityTrustedContextHandle context = context(policy, current);
        AbilityRequestAuthority.ExpectedContext expected = AbilityAuthorityFixture.expected(context);
        AbilityPolicyResolver.Decision result = AbilityPolicyResolver.resolveDiscovery(context, expected, AbilityPolicy.OBSERVE);
        assertEquals(AbilityPolicyResolver.Status.CONDITIONAL, result.status());
        assertNull(result.selectedGrantId());
        assertEquals(List.of("observe-self"), result.candidateGrants().stream().map(AbilityPolicy.Grant::id).toList());
        assertNull(result.effectiveRestrictions());
        current.set(false);
        assertEquals(AbilityPolicyResolver.Reason.STALE_CONTEXT,
                AbilityPolicyResolver.resolveDiscovery(context, expected, AbilityPolicy.OBSERVE).reason());
    }

    private static void assertLimits(JsonObject expected, AbilityPolicyResolver.EffectiveRestrictions restrictions, String name) {
        if (expected.has("requesterIntervalMs")) {
            assertEquals(expected.get("requesterIntervalMs").getAsLong(), restrictions.observeLimits().requesterIntervalMs(), name);
            assertEquals(expected.get("characterIntervalMs").getAsLong(), restrictions.observeLimits().characterIntervalMs(), name);
        } else {
            assertEquals(expected.get("requesterCooldownMs").getAsLong(), restrictions.lightningLimits().requesterCooldownMs(), name);
            assertEquals(expected.get("targetCooldownMs").getAsLong(), restrictions.lightningLimits().targetCooldownMs(), name);
            assertEquals(expected.get("characterIntervalMs").getAsLong(), restrictions.lightningLimits().characterIntervalMs(), name);
        }
    }

    private static void assertEffectiveRestrictions(JsonObject expected,
                                                   AbilityPolicyResolver.EffectiveRestrictions actual, String name) {
        assertNotNull(actual, name);
        AbilityPolicy.ObserveLimits observeLimits = null;
        AbilityPolicy.LightningLimits lightningLimits = null;
        JsonObject limits = expected.getAsJsonObject("limits");
        if (limits.has("requesterIntervalMs")) {
            observeLimits = new AbilityPolicy.ObserveLimits(limits.get("requesterIntervalMs").getAsLong(),
                    limits.get("characterIntervalMs").getAsLong());
        } else {
            lightningLimits = new AbilityPolicy.LightningLimits(limits.get("requesterCooldownMs").getAsLong(),
                    limits.get("targetCooldownMs").getAsLong(), limits.get("characterIntervalMs").getAsLong());
        }
        JsonElement maxDamage = expected.get("maxDamage");
        AbilityPolicyResolver.EffectiveRestrictions expectedRestrictions = new AbilityPolicyResolver.EffectiveRestrictions(
                expected.get("maxRangeBlocks").getAsDouble(), policyRegion(expected.get("region")),
                border(expected.get("worldBorder")), spatialBounds(expected.get("effectiveSpatialBounds")),
                expected.get("mode").getAsString(), maxDamage == null || maxDamage.isJsonNull() ? null : maxDamage.getAsDouble(),
                observeLimits, lightningLimits);
        assertEquals(expectedRestrictions, actual, name);
    }

    private static AbilityPolicy.Region policyRegion(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        JsonObject region = value.getAsJsonObject();
        return new AbilityPolicy.Region(region.get("minX").getAsLong(), region.get("maxXExclusive").getAsLong(),
                region.get("minZ").getAsLong(), region.get("maxZExclusive").getAsLong());
    }

    private static AbilityPolicyResolver.Border border(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        JsonObject border = value.getAsJsonObject();
        return new AbilityPolicyResolver.Border(border.get("minX").getAsDouble(), border.get("maxXExclusive").getAsDouble(),
                border.get("minZ").getAsDouble(), border.get("maxZExclusive").getAsDouble());
    }

    private static AbilityPolicyResolver.EffectiveSpatialBounds spatialBounds(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        JsonObject bounds = value.getAsJsonObject();
        return new AbilityPolicyResolver.EffectiveSpatialBounds(bounds.get("minX").getAsDouble(),
                bounds.get("maxXExclusive").getAsDouble(), bounds.get("minZ").getAsDouble(),
                bounds.get("maxZExclusive").getAsDouble());
    }

    private static AbilityPolicyResolver.WorldFacts worldFacts(JsonObject base, JsonObject overrides) {
        return new AbilityPolicyResolver.WorldFacts(
                bool(base, overrides, "currentWorldMatches"), bool(base, overrides, "requestLive"),
                bool(base, overrides, "requesterConnected"), bool(base, overrides, "targetResolved"),
                text(base, overrides, "targetPlayerId"), bool(base, overrides, "targetConnected"),
                bool(base, overrides, "targetAlive"), bool(base, overrides, "targetNotSpectating"),
                bool(base, overrides, "targetChunkLoadedAndTicking"), text(base, overrides, "requesterDimension"),
                text(base, overrides, "targetDimension"), position(base, overrides, "requesterFeet"),
                position(base, overrides, "targetFeet"), border(base, overrides),
                bool(base, overrides, "lightningSkyAccess"), bool(base, overrides, "protectedSpawnClear"),
                bool(base, overrides, "protectedClaimsClear"));
    }

    private static Boolean bool(JsonObject base, JsonObject overrides, String key) {
        JsonElement value = overrides.has(key) ? overrides.get(key) : base.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsBoolean();
    }

    private static String text(JsonObject base, JsonObject overrides, String key) {
        JsonElement value = overrides.has(key) ? overrides.get(key) : base.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static AbilityPolicyResolver.Position position(JsonObject base, JsonObject overrides, String key) {
        JsonElement value = overrides.has(key) ? overrides.get(key) : base.get(key);
        if (value == null || value.isJsonNull()) return null;
        JsonObject position = value.getAsJsonObject();
        return new AbilityPolicyResolver.Position(position.get("x").getAsDouble(), position.get("y").getAsDouble(),
                position.get("z").getAsDouble());
    }

    private static AbilityPolicyResolver.Border border(JsonObject base, JsonObject overrides) {
        JsonElement value = overrides.has("worldBorder") ? overrides.get("worldBorder") : base.get("worldBorder");
        if (value == null || value.isJsonNull()) return null;
        JsonObject border = value.getAsJsonObject();
        return new AbilityPolicyResolver.Border(border.get("minX").getAsDouble(), border.get("maxXExclusive").getAsDouble(),
                border.get("minZ").getAsDouble(), border.get("maxZExclusive").getAsDouble());
    }

    private static AbilityValidation.Binding binding(JsonObject value) {
        return new AbilityValidation.Binding(value.get("serverId").getAsString(), value.get("worldId").getAsString(),
                value.get("characterId").getAsString(), value.get("agentId").getAsString());
    }

    private static AbilityValidation.AbilityTrustedContextHandle context(AbilityPolicy policy, AtomicBoolean current) {
        AbilityValidation.Binding binding = new AbilityValidation.Binding("server-test", "world-test", "character-test", "agent-test");
        return AbilityAuthorityFixture.issue(binding, REQUEST, INVOCATION,
                "00000000-0000-4000-8000-000000000041", EPOCH, policy, current::get);
    }
}
