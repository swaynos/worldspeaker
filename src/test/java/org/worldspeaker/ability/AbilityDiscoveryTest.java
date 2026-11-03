package org.worldspeaker.ability;

import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.AbilityPolicy;
import org.worldspeaker.bootstrap.AbilityAuthorityFixture;
import org.worldspeaker.integration.StrictJsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AbilityDiscoveryTest {
    private static final String REQUESTER = "00000000-0000-4000-8000-000000000061";
    private static final String OTHER_REQUESTER = "00000000-0000-4000-8000-000000000062";
    private static final String TARGET_A = "00000000-0000-4000-8000-000000000063";
    private static final String TARGET_B = "00000000-0000-4000-8000-000000000064";
    private static final String REQUEST = "00000000-0000-4000-8000-000000000065";
    private static final String INVOCATION = "00000000-0000-4000-8000-000000000066";
    private static final String EPOCH = "00000000-0000-4000-8000-000000000067";
    private static final String CHARACTER = "character-test";
    private static final AbilityValidation.Binding BINDING = new AbilityValidation.Binding(
            "server-test", "world-test", CHARACTER, "agent-test");

    @Test
    void serializesBothUnavailableContractsAndOnlyRequesterMatchingCompleteGrantsInStableOrder() {
        AbilityPolicy policy = policy(9, List.of(
                grant("z-observe-other-target", AbilityPolicy.OBSERVE, REQUESTER, List.of(TARGET_B),
                        List.of("minecraft:the_nether"), 12, false, false, null),
                grant("private-other-requester", AbilityPolicy.OBSERVE, OTHER_REQUESTER, List.of(TARGET_A),
                        List.of("minecraft:overworld"), 64, false, false, null),
                grant("a-observe-self", AbilityPolicy.OBSERVE, REQUESTER, List.of(),
                        List.of("minecraft:overworld"), 48, false, false, null),
                grant("lightning-conditional", AbilityPolicy.LIGHTNING, REQUESTER, List.of(TARGET_A),
                        List.of("minecraft:overworld"), 20, true, true, 4.5)), List.of());

        AbilityValidation.AbilityTrustedContextHandle context = context(policy, new AtomicBoolean(true));
        AbilityDiscovery.Snapshot discovery = AbilityDiscovery.build(context, AbilityAuthorityFixture.expected(context));

        assertEquals(1, discovery.catalogueVersion());
        assertEquals(BINDING, discovery.binding());
        assertEquals(REQUEST, discovery.requestId());
        assertEquals(INVOCATION, discovery.invocationId());
        assertEquals(9, discovery.policyRevision());
        assertEquals(List.of(AbilityPolicy.OBSERVE, AbilityPolicy.LIGHTNING),
                discovery.entries().stream().map(entry -> entry.contract().abilityId()).toList());
        assertTrue(discovery.entries().stream().allMatch(entry ->
                entry.implementationStatus() == AbilityCatalogue.ImplementationStatus.UNIMPLEMENTED &&
                        entry.qualificationStatus() == AbilityCatalogue.QualificationStatus.UNQUALIFIED &&
                        entry.availability() == AbilityCatalogue.Availability.UNAVAILABLE));

        AbilityDiscovery.Entry observe = discovery.entries().getFirst();
        assertEquals(AbilityDiscovery.PolicyDisposition.CONDITIONAL, observe.policyDisposition());
        assertEquals(List.of("a-observe-self", "z-observe-other-target"),
                observe.effectiveGrants().stream().map(AbilityDiscovery.GrantSummary::grantId).toList());
        assertTrue(observe.effectiveGrants().getFirst().selfTargeting());
        assertEquals(List.of(), observe.effectiveGrants().getFirst().otherTargetIds());
        assertEquals(List.of(TARGET_B), observe.effectiveGrants().getLast().otherTargetIds());
        assertEquals(List.of("minecraft:the_nether"), observe.effectiveGrants().getLast().dimensions());
        assertEquals(12, observe.effectiveGrants().getLast().maxRangeBlocks());

        AbilityDiscovery.Entry lightning = discovery.entries().getLast();
        assertEquals(AbilityDiscovery.PolicyDisposition.CONDITIONAL, lightning.policyDisposition());
        assertEquals(true, lightning.effectiveGrants().getFirst().allowDamage());
        assertEquals(4.5, lightning.effectiveGrants().getFirst().maxDamage());
        String json = discovery.toJson();
        assertFalse(json.contains(OTHER_REQUESTER));
        assertFalse(json.contains("private-other-requester"));
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= AbilityCatalogue.MAX_DISCOVERY_BYTES);
    }

    @Test
    void noGrantRequesterMismatchAndExplicitDenialRemainDistinctAndDenialWins() {
        var noGrantContext = context(AbilityPolicy.denyAll(), new AtomicBoolean(true));
        AbilityDiscovery.Snapshot noGrant = AbilityDiscovery.build(noGrantContext,
                AbilityAuthorityFixture.expected(noGrantContext));
        assertEquals(List.of(AbilityDiscovery.DisabledReason.UNIMPLEMENTED,
                        AbilityDiscovery.DisabledReason.UNQUALIFIED, AbilityDiscovery.DisabledReason.NO_GRANT),
                noGrant.entries().getFirst().disabledReasons());

        AbilityPolicy otherRequesterPolicy = policy(3, List.of(
                grant("observe-other", AbilityPolicy.OBSERVE, OTHER_REQUESTER, List.of(TARGET_A),
                        List.of("minecraft:overworld"), 64, false, false, null)), List.of());
        var requesterMismatchContext = context(otherRequesterPolicy, new AtomicBoolean(true));
        AbilityDiscovery.Entry requesterMismatch = AbilityDiscovery.build(requesterMismatchContext,
                AbilityAuthorityFixture.expected(requesterMismatchContext)).entries().getFirst();
        assertEquals(List.of(AbilityDiscovery.DisabledReason.UNIMPLEMENTED,
                        AbilityDiscovery.DisabledReason.UNQUALIFIED, AbilityDiscovery.DisabledReason.REQUESTER_NOT_GRANTED),
                requesterMismatch.disabledReasons());
        assertTrue(requesterMismatch.effectiveGrants().isEmpty());

        AbilityPolicy deniedPolicy = policy(4, List.of(
                grant("observe-allowed", AbilityPolicy.OBSERVE, REQUESTER, List.of(TARGET_A),
                        List.of("minecraft:overworld"), 64, false, false, null)),
                List.of(new AbilityPolicy.Denial("deny-observe", AbilityPolicy.OBSERVE, 1)));
        var deniedContext = context(deniedPolicy, new AtomicBoolean(true));
        AbilityDiscovery.Entry denied = AbilityDiscovery.build(deniedContext,
                AbilityAuthorityFixture.expected(deniedContext)).entries().getFirst();
        assertEquals(AbilityDiscovery.PolicyDisposition.DENIED, denied.policyDisposition());
        assertEquals(List.of(AbilityDiscovery.DisabledReason.UNIMPLEMENTED,
                        AbilityDiscovery.DisabledReason.UNQUALIFIED, AbilityDiscovery.DisabledReason.EXPLICIT_DENIAL),
                denied.disabledReasons());
        assertTrue(denied.effectiveGrants().isEmpty());
    }

    @Test
    void staleTrustedContextCannotBuildDiscoveryAndSerializedContractsAreDefensivelyCopied() {
        AtomicBoolean current = new AtomicBoolean(true);
        AbilityValidation.AbilityTrustedContextHandle trusted = context(AbilityPolicy.denyAll(), current);
        var expected = AbilityAuthorityFixture.expected(trusted);
        AbilityDiscovery.Snapshot snapshot = AbilityDiscovery.build(trusted, expected);
        var contract = snapshot.entries().getFirst().contract();
        var mutated = contract.bounds();
        mutated.addProperty("targetCount", 99);
        assertEquals(1, contract.bounds().get("targetCount").getAsInt());
        assertTrue(snapshot.toJson().contains("\"targetCount\":1"));

        current.set(false);
        assertThrows(IllegalArgumentException.class, () -> AbilityDiscovery.build(trusted, expected));
    }

    @Test
    void serializesExactHashedSchemaDependenciesAndOneSharedCatalogueDefinitionsResource() throws Exception {
        var deniedContext = context(AbilityPolicy.denyAll(), new AtomicBoolean(true));
        AbilityDiscovery.Snapshot discovery = AbilityDiscovery.build(deniedContext,
                AbilityAuthorityFixture.expected(deniedContext));
        var root = StrictJsonParser.parse(discovery.toJson()).getAsJsonObject();
        var resources = root.getAsJsonArray("schemaResources");
        assertEquals(3, resources.size());
        assertEquals(List.of("https://worldspeaker.invalid/schema/ability-predicates-v1.schema.json",
                        "https://worldspeaker.invalid/schema/ability-discovery-v1.schema.json",
                        "https://worldspeaker.invalid/schema/ability-contracts-v1.json"),
                resources.asList().stream().map(value -> value.getAsJsonObject().get("$id").getAsString()).toList());
        for (var element : resources) {
            var resource = element.getAsJsonObject();
            byte[] schemaBytes = resource.get("schemaJson").getAsString().getBytes(StandardCharsets.UTF_8);
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(schemaBytes)),
                    resource.get("sha256").getAsString());
            assertEquals(resource.get("$id").getAsString(),
                    StrictJsonParser.parse(new String(schemaBytes, StandardCharsets.UTF_8)).getAsJsonObject()
                            .get("$id").getAsString());
        }
        var catalogueResource = StrictJsonParser.parse(resources.get(2).getAsJsonObject().get("schemaJson").getAsString())
                .getAsJsonObject();
        assertEquals(java.util.Set.of("$schema", "$id", "$defs"), catalogueResource.keySet());
        assertTrue(catalogueResource.getAsJsonObject("$defs").has("baseResult"));
        assertTrue(catalogueResource.getAsJsonObject("$defs").has("observeResult"));
        assertTrue(catalogueResource.getAsJsonObject("$defs").has("lightningResult"));
        assertTrue(discovery.entries().stream().allMatch(entry ->
                entry.contract().resultSchema().get("$ref").getAsString().startsWith(
                        "https://worldspeaker.invalid/schema/ability-contracts-v1.json#/$defs/")));
        assertTrue(discovery.toJson().getBytes(StandardCharsets.UTF_8).length <= AbilityCatalogue.MAX_DISCOVERY_BYTES);
    }

    private static AbilityPolicy policy(long revision, List<AbilityPolicy.Grant> grants,
                                        List<AbilityPolicy.Denial> denials) {
        return new AbilityPolicy(revision, grants, denials, "REQUIRED");
    }

    private static AbilityPolicy.Grant grant(String id, String abilityId, String requester, List<String> targets,
                                             List<String> dimensions, double range, boolean visual,
                                             boolean damage, Double maxDamage) {
        return new AbilityPolicy.Grant(id, CHARACTER, abilityId, 1, List.of(requester), targets, dimensions,
                range, visual, damage, maxDamage);
    }

    private static AbilityValidation.AbilityTrustedContextHandle context(AbilityPolicy policy, AtomicBoolean current) {
        return AbilityAuthorityFixture.issue(BINDING, REQUEST, INVOCATION, REQUESTER, EPOCH, policy, current::get);
    }
}
