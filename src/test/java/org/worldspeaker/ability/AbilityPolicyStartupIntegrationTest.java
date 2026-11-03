package org.worldspeaker.ability;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.compat.AbilityPolicy;
import org.worldspeaker.bootstrap.AbilityAuthorityFixture;
import org.worldspeaker.bootstrap.AbilityRequestAuthority;
import org.worldspeaker.compat.StartupConfig;
import org.worldspeaker.compat.StartupConfigTestFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises real private startup bytes through the production policy and deny-only seams. */
class AbilityPolicyStartupIntegrationTest {
    private static final String REQUESTER_A = "00000000-0000-4000-8000-000000000041";
    private static final String REQUESTER_B = "00000000-0000-4000-8000-000000000042";
    private static final String TARGET = "00000000-0000-4000-8000-000000000043";
    private static final String EPOCH = "00000000-0000-4000-8000-000000000044";
    private static final String CHARACTER = "fixture-character";
    private static final AbilityValidation.Binding BINDING = new AbilityValidation.Binding(
            "fixture-server", "fixture-world", CHARACTER, "fixture-agent");

    @TempDir Path temporaryDirectory;

    @Test
    void actualStartupSnapshotResolvesRequesterScopedGrantsAndAllValidIntentsRemainDenyOnly() throws Exception {
        Path bootstrap = createPolicyBundle(23, false);
        StartupConfig snapshot = StartupConfig.load(bootstrap);
        assertEquals(23, snapshot.abilityPolicy().revision());

        AtomicBoolean currentA = new AtomicBoolean(true);
        AtomicBoolean currentB = new AtomicBoolean(true);
        AbilityValidation.AbilityTrustedContextHandle contextA = context(snapshot, REQUESTER_A, currentA);
        AbilityValidation.AbilityTrustedContextHandle contextB = context(snapshot, REQUESTER_B, currentB);
        AbilityRequestAuthority.ExpectedContext expectedA = AbilityAuthorityFixture.expected(contextA);
        AbilityRequestAuthority.ExpectedContext expectedB = AbilityAuthorityFixture.expected(contextB);

        AbilityPolicyResolver.Decision resolvedA = AbilityPolicyResolver.resolveIntent(contextA, expectedA,
                new AbilityValidation.ObserveInput(REQUESTER_A), facts(REQUESTER_A, 3));
        assertEquals(AbilityPolicyResolver.Status.CONDITIONAL, resolvedA.status());
        assertEquals("a-observe-requester", resolvedA.selectedGrantId());
        assertEquals(41, resolvedA.effectiveRestrictions().maxRangeBlocks());
        assertEquals(new AbilityPolicy.Region(0, 20, -5, 5), resolvedA.effectiveRestrictions().region());
        assertEquals(new AbilityPolicyResolver.Border(-100, 100, -100, 100), resolvedA.effectiveRestrictions().worldBorder());
        assertEquals(new AbilityPolicyResolver.EffectiveSpatialBounds(0, 20, -5, 5),
                resolvedA.effectiveRestrictions().effectiveSpatialBounds());
        assertEquals(new AbilityPolicy.ObserveLimits(5_000, 500), resolvedA.effectiveRestrictions().observeLimits());
        assertEquals("a-observe-requester", resolvedA.limitDemand().grantId());
        assertEquals(5_000, resolvedA.limitDemand().requesterIntervalMs());
        assertTrue(resolvedA.unresolvedChecks().contains(AbilityPolicyResolver.UnresolvedCheck.NORMATIVE_WORLD_IDENTITY_MAPPING));
        assertTrue(resolvedA.unresolvedChecks().contains(AbilityPolicyResolver.UnresolvedCheck.ATOMIC_LIMIT_ADMISSION));

        AbilityPolicyResolver.Decision resolvedB = AbilityPolicyResolver.resolveIntent(contextB, expectedB,
                new AbilityValidation.ObserveInput(REQUESTER_B), facts(REQUESTER_B, 3));
        assertEquals("b-observe-requester", resolvedB.selectedGrantId());
        assertEquals(12, resolvedB.effectiveRestrictions().maxRangeBlocks());

        AbilityDiscovery.Snapshot discoveryA = AbilityDiscovery.build(contextA, expectedA);
        AbilityDiscovery.Snapshot discoveryB = AbilityDiscovery.build(contextB, expectedB);
        assertEquals(23, discoveryA.policyRevision());
        assertEquals(List.of("a-observe-requester"), discoveryA.entries().getFirst().effectiveGrants().stream()
                .map(AbilityDiscovery.GrantSummary::grantId).toList());
        assertEquals(List.of("b-observe-requester"), discoveryB.entries().getFirst().effectiveGrants().stream()
                .map(AbilityDiscovery.GrantSummary::grantId).toList());
        assertFalse(discoveryA.toJson().contains("\"worldBorder\""));
        assertFalse(discoveryA.toJson().contains("\"effectiveSpatialBounds\""));
        assertFalse(discoveryA.toJson().contains("b-observe-requester"));
        assertFalse(discoveryB.toJson().contains("a-observe-requester"));
        assertTrue(discoveryA.entries().stream().allMatch(entry ->
                entry.implementationStatus() == AbilityCatalogue.ImplementationStatus.UNIMPLEMENTED &&
                        entry.qualificationStatus() == AbilityCatalogue.QualificationStatus.UNQUALIFIED &&
                        entry.availability() == AbilityCatalogue.Availability.UNAVAILABLE));

        AbilityExecutionGate.Decision allowedPolicyStillUnavailable = AbilityExecutionGate.evaluate(contextA, expectedA,
                new AbilityValidation.ObserveInput(REQUESTER_A));
        assertEquals(AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE, allowedPolicyStillUnavailable.reason());
        assertFalse(allowedPolicyStillUnavailable.effectStarted());
        AbilityValidation.Decision validated = AbilityValidation.validate(
                ("{\"abilityId\":\"" + AbilityPolicy.OBSERVE + "\",\"contractVersion\":1," +
                        "\"arguments\":{\"targetPlayerId\":\"" + REQUESTER_A + "\"}}").getBytes(StandardCharsets.UTF_8), contextA, expectedA);
        assertEquals(AbilityValidation.DecisionStatus.VALID, validated.status());

        assertEquals(AbilityExecutionGate.Reason.PERMISSION_DENIED,
                AbilityExecutionGate.evaluate(contextA, expectedA, new AbilityValidation.ObserveInput(TARGET)).reason(),
                "an unlisted other target is denied before the unavailable-execution fallback");
        assertFalse(AbilityExecutionGate.evaluate(contextA, expectedA, new AbilityValidation.ObserveInput(TARGET)).effectStarted());
    }

    @Test
    void restartRevocationInvalidatesOldAuthorityAndSameRevisionDifferentBytesRemainDistinct() throws Exception {
        Path bootstrap = createPolicyBundle(23, false);
        StartupConfig oldSnapshot = StartupConfig.load(bootstrap);
        AtomicBoolean oldGenerationCurrent = new AtomicBoolean(true);
        AbilityValidation.AbilityTrustedContextHandle oldContext = context(oldSnapshot, REQUESTER_A, oldGenerationCurrent);
        AbilityRequestAuthority.ExpectedContext oldExpected = AbilityAuthorityFixture.expected(oldContext);
        assertEquals(AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                AbilityExecutionGate.evaluate(oldContext, oldExpected, new AbilityValidation.ObserveInput(REQUESTER_A)).reason());

        Path bundlePath = bootstrap.getParent().resolve("startup.json");
        JsonObject sameRevisionBundle = JsonParser.parseString(Files.readString(bundlePath)).getAsJsonObject();
        sameRevisionBundle.getAsJsonObject("server").addProperty("characterDisplayName", "The Archivist");
        Files.writeString(bundlePath, new Gson().toJson(sameRevisionBundle), StandardCharsets.UTF_8);
        StartupConfig sameRevisionSnapshot = StartupConfig.load(bootstrap);
        assertEquals(oldSnapshot.abilityPolicy().revision(), sameRevisionSnapshot.abilityPolicy().revision());
        assertNotEquals(oldSnapshot.configSetSha256(), sameRevisionSnapshot.configSetSha256(),
                "same policy revision does not identify the exact authenticated bundle bytes");
        assertEquals(23, oldSnapshot.abilityPolicy().revision(), "editing files does not mutate the running snapshot");

        JsonObject revokedBundle = JsonParser.parseString(Files.readString(bundlePath)).getAsJsonObject();
        JsonObject policy = revokedBundle.getAsJsonObject("abilityPolicy");
        policy.addProperty("revision", 24);
        JsonArray denials = new JsonArray();
        JsonObject denial = new JsonObject();
        denial.addProperty("id", "deny-observe-after-restart");
        denial.addProperty("abilityId", AbilityPolicy.OBSERVE);
        denial.addProperty("contractVersion", 1);
        denials.add(denial);
        policy.add("denials", denials);
        Files.writeString(bundlePath, new Gson().toJson(revokedBundle), StandardCharsets.UTF_8);
        StartupConfig newSnapshot = StartupConfig.load(bootstrap);

        oldGenerationCurrent.set(false); // coordinated restart retires old handles before new admission
        assertThrows(IllegalArgumentException.class, () -> AbilityDiscovery.build(oldContext, oldExpected));
        assertEquals(AbilityExecutionGate.Reason.STALE_CONTEXT,
                AbilityExecutionGate.evaluate(oldContext, oldExpected, new AbilityValidation.ObserveInput(REQUESTER_A)).reason());

        AbilityValidation.AbilityTrustedContextHandle newContext = context(newSnapshot, REQUESTER_A, new AtomicBoolean(true));
        AbilityRequestAuthority.ExpectedContext newExpected = AbilityAuthorityFixture.expected(newContext);
        assertEquals(AbilityPolicyResolver.Reason.EXPLICIT_DENIAL,
                AbilityPolicyResolver.resolveIntent(newContext, newExpected,
                        new AbilityValidation.ObserveInput(REQUESTER_A), facts(REQUESTER_A, 3)).reason());
        assertEquals(AbilityExecutionGate.Reason.PERMISSION_DENIED,
                AbilityExecutionGate.evaluate(newContext, newExpected, new AbilityValidation.ObserveInput(REQUESTER_A)).reason());
        assertFalse(AbilityExecutionGate.evaluate(newContext, newExpected,
                new AbilityValidation.ObserveInput(REQUESTER_A)).effectStarted());
        assertEquals(24, newSnapshot.abilityPolicy().revision());
        assertNotEquals(oldSnapshot.configSetSha256(), newSnapshot.configSetSha256());
    }

    private Path createPolicyBundle(long revision, boolean denyObserve) throws Exception {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        Path bundlePath = bootstrap.getParent().resolve("startup.json");
        JsonObject bundle = JsonParser.parseString(Files.readString(bundlePath)).getAsJsonObject();
        JsonObject policy = new JsonObject();
        policy.addProperty("schemaVersion", 1);
        policy.addProperty("revision", revision);
        JsonArray grants = new JsonArray();
        grants.add(observeGrant("a-observe-requester", REQUESTER_A, 41, 0, 20, -5, 5, 5_000, 500));
        grants.add(observeGrant("b-observe-requester", REQUESTER_B, 12, -20, 20, -20, 20, 2_000, 250));
        policy.add("grants", grants);
        JsonArray denials = new JsonArray();
        if (denyObserve) {
            JsonObject denial = new JsonObject();
            denial.addProperty("id", "deny-observe");
            denial.addProperty("abilityId", AbilityPolicy.OBSERVE);
            denial.addProperty("contractVersion", 1);
            denials.add(denial);
        }
        policy.add("denials", denials);
        policy.add("protection", new JsonObject());
        bundle.add("abilityPolicy", policy);
        writeBundle(bundlePath, bundle);
        return bootstrap;
    }

    private static JsonObject observeGrant(String id, String requester, double range,
                                           long minX, long maxX, long minZ, long maxZ,
                                           long requesterInterval, long characterInterval) {
        JsonObject grant = new JsonObject();
        grant.addProperty("id", id);
        grant.addProperty("characterId", CHARACTER);
        grant.addProperty("abilityId", AbilityPolicy.OBSERVE);
        grant.addProperty("contractVersion", 1);
        JsonArray requesterIds = new JsonArray();
        requesterIds.add(requester);
        grant.add("requesterIds", requesterIds);
        JsonArray dimensions = new JsonArray();
        dimensions.add("minecraft:overworld");
        grant.add("dimensions", dimensions);
        grant.addProperty("maxRangeBlocks", range);
        JsonObject region = new JsonObject();
        region.addProperty("minX", minX);
        region.addProperty("maxXExclusive", maxX);
        region.addProperty("minZ", minZ);
        region.addProperty("maxZExclusive", maxZ);
        grant.add("region", region);
        JsonObject limits = new JsonObject();
        limits.addProperty("requesterIntervalMs", requesterInterval);
        limits.addProperty("characterIntervalMs", characterInterval);
        grant.add("limits", limits);
        return grant;
    }

    private static void writeBundle(Path path, JsonObject bundle) throws Exception {
        Files.writeString(path, new Gson().toJson(bundle), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }

    private static AbilityValidation.AbilityTrustedContextHandle context(StartupConfig snapshot, String requester,
                                                                         AtomicBoolean current) {
        var binding = snapshot.serverConfig().binding();
        return AbilityAuthorityFixture.issue(
                new AbilityValidation.Binding(binding.serverId(), binding.worldId(), binding.characterId(), binding.agentId()),
                java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(), requester, EPOCH,
                snapshot.abilityPolicy(), current::get);
    }

    private static AbilityPolicyResolver.WorldFacts facts(String target, double targetX) {
        return new AbilityPolicyResolver.WorldFacts(true, true, true, true, target, true, true, true, true,
                "minecraft:overworld", "minecraft:overworld", new AbilityPolicyResolver.Position(0, 64, 0),
                new AbilityPolicyResolver.Position(targetX, 64, 0),
                new AbilityPolicyResolver.Border(-100, 100, -100, 100), true, true, true);
    }
}
