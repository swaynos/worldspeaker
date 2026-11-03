package org.worldspeaker.ability;

import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.AbilityPolicy;
import org.worldspeaker.bootstrap.AbilityAuthorityFixture;
import org.worldspeaker.bootstrap.AbilityRequestAuthority;
import org.worldspeaker.integration.StrictJsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AbilityCatalogueTest {
    private static final String REQUESTER = "00000000-0000-4000-8000-000000000061";
    private static final String TARGET = "00000000-0000-4000-8000-0000000000ab";
    private static final String REQUEST = "00000000-0000-4000-8000-000000000063";
    private static final String INVOCATION = "00000000-0000-4000-8000-000000000064";
    private static final String EPOCH = "00000000-0000-4000-8000-000000000065";
    private static final String CHARACTER = "character-test";
    private static final AbilityValidation.Binding BINDING = new AbilityValidation.Binding(
            "server-test", "world-test", CHARACTER, "agent-test");

    @Test
    void packagesTwoNonemptyContractsAndKeepsBothUnavailable() {
        AbilityCatalogue catalogue = AbilityCatalogue.selected();
        assertEquals(2, catalogue.contracts().size());
        assertEquals(AbilityPolicy.OBSERVE, catalogue.contracts().getFirst().abilityId());
        assertEquals(AbilityPolicy.LIGHTNING, catalogue.contracts().getLast().abilityId());
        assertTrue(catalogue.contracts().stream().allMatch(contract ->
                contract.implementationStatus() == AbilityCatalogue.ImplementationStatus.UNIMPLEMENTED &&
                        contract.qualificationStatus() == AbilityCatalogue.QualificationStatus.UNQUALIFIED &&
                        contract.availability() == AbilityCatalogue.Availability.UNAVAILABLE));
        assertEquals(AbilityCatalogue.MAX_REQUEST_BYTES, AbilityCatalogue.MAX_RESULT_BYTES);
        assertEquals(65_536, AbilityCatalogue.MAX_DISCOVERY_BYTES);
        var bounds = catalogue.contracts().getFirst().bounds();
        bounds.addProperty("targetCount", 99);
        assertEquals(1, catalogue.contracts().getFirst().bounds().get("targetCount").getAsInt(),
                "callers receive defensive copies of the packaged immutable catalogue");
    }

    @Test
    void strictValidationSeparatesMalformedInputUnknownContractsAndStaleTrustedContext() {
        assertEquals(AbilityValidation.Reason.INVALID_INPUT, validate("{}", null).reason());
        assertEquals(AbilityValidation.Reason.UNSUPPORTED_CONTRACT,
                validate("{\"abilityId\":\"worldspeaker.core.unknown\",\"contractVersion\":1,\"arguments\":{}}", null).reason());
        assertEquals(AbilityValidation.Reason.UNSUPPORTED_CONTRACT,
                validate("{\"abilityId\":\"worldspeaker.core.observe_player\",\"contractVersion\":2,\"arguments\":{\"targetPlayerId\":\"" + TARGET + "\"}}", null).reason());
        assertEquals(AbilityValidation.Reason.INVALID_INPUT,
                validate("{\"abilityId\":\"worldspeaker.core.observe_player\",\"contractVersion\":1,\"arguments\":{\"targetPlayerId\":\"" + TARGET + "\",\"operator\":true}}", null).reason());
        assertEquals(AbilityValidation.Reason.INVALID_INPUT,
                validate("{\"abilityId\":\"worldspeaker.core.observe_player\",\"contractVersion\":1,\"arguments\":{\"targetPlayerId\":\"" + TARGET.toUpperCase() + "\"}}", null).reason());
        assertEquals(AbilityValidation.Reason.INVALID_INPUT,
                AbilityValidation.parse(("{\"abilityId\":\"worldspeaker.core.observe_player\",\"contractVersion\":1," +
                        "\"arguments\":{\"targetPlayerId\":[\"" + TARGET + "\"]}}")
                        .getBytes(StandardCharsets.UTF_8)).reason(),
                "target lists are rejected by the complete-frame parser before an owner can reserve work");

        byte[] valid = observe(TARGET).getBytes(StandardCharsets.UTF_8);
        assertEquals(AbilityValidation.Reason.STALE_CONTEXT, AbilityValidation.validate(valid, null, null).reason());
        assertEquals(AbilityValidation.Reason.INVALID_INPUT,
                AbilityValidation.parse(new byte[]{(byte) 0xc3, 0x28}).reason());
        assertEquals(AbilityValidation.Reason.INVALID_INPUT,
                AbilityValidation.parse(("\ufeff" + observe(TARGET)).getBytes(StandardCharsets.UTF_8)).reason());
        assertEquals(AbilityValidation.Reason.INVALID_INPUT,
                AbilityValidation.parse((observe(TARGET).substring(0, observe(TARGET).length() - 1) + ",\"abilityId\":\"x\"}")
                        .getBytes(StandardCharsets.UTF_8)).reason());
        assertEquals(AbilityValidation.Reason.INVALID_INPUT,
                AbilityValidation.parse((" ".repeat(AbilityCatalogue.MAX_REQUEST_BYTES + 1)).getBytes(StandardCharsets.UTF_8)).reason());
        String exactFrame = observe(TARGET) + " ".repeat(
                AbilityCatalogue.MAX_REQUEST_BYTES - observe(TARGET).getBytes(StandardCharsets.UTF_8).length);
        assertEquals(AbilityCatalogue.MAX_REQUEST_BYTES, exactFrame.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(AbilityValidation.DecisionStatus.VALID,
                AbilityValidation.parse(exactFrame.getBytes(StandardCharsets.UTF_8)).status(),
                "the complete 4-KiB UTF-8 frame is accepted without truncation");
        assertEquals(AbilityValidation.Reason.INVALID_INPUT,
                AbilityValidation.parse((exactFrame + " ").getBytes(StandardCharsets.UTF_8)).reason());
        assertEquals(AbilityValidation.Reason.INVALID_INPUT,
                AbilityValidation.parse(observe("\\uD800").getBytes(StandardCharsets.UTF_8)).reason(),
                "an escaped unpaired surrogate cannot become a valid target identity");

        String nestedPrefix = observe(TARGET).substring(0, observe(TARGET).length() - 1) + ",\"ignored\":";
        String eightContainerFrame = nestedPrefix + "[".repeat(7) + "0" + "]".repeat(7) + "}";
        String nineContainerFrame = nestedPrefix + "[".repeat(8) + "0" + "]".repeat(8) + "}";
        assertDoesNotThrow(() -> StrictJsonParser.parse(eightContainerFrame, AbilityCatalogue.MAX_JSON_DEPTH),
                "the depth bound counts the complete envelope and includes its eighth container");
        assertThrows(IllegalArgumentException.class,
                () -> StrictJsonParser.parse(nineContainerFrame, AbilityCatalogue.MAX_JSON_DEPTH));
    }

    @Test
    void sharedRawIntentCorpusMatchesTheJavaProductionValidator() throws Exception {
        var corpus = StrictJsonParser.parse(Files.readString(Path.of("compatibility/ability-contracts-v1-corpus.json")))
                .getAsJsonObject();
        AbilityValidation.AbilityTrustedContextHandle context = context(policy(List.of(), List.of()), new AtomicBoolean(true));
        for (var vectorElement : corpus.getAsJsonArray("vectors")) {
            var vector = vectorElement.getAsJsonObject();
            String name = vector.get("name").getAsString();
            String expected = vector.get("reasonCode").getAsString();
            AbilityValidation.Decision decision = validate(vector.get("rawJson").getAsString(), context);
            if ("none".equals(expected)) {
                assertEquals(AbilityValidation.DecisionStatus.VALID, decision.status(), name);
                assertEquals(AbilityValidation.Reason.NONE, decision.reason(), name);
            } else {
                assertEquals(AbilityValidation.DecisionStatus.REJECTED, decision.status(), name);
                assertEquals(AbilityValidation.Reason.valueOf(expected.toUpperCase(java.util.Locale.ROOT)), decision.reason(), name);
            }
        }
    }

    @Test
    void validIntentIsNormalizedThenAlwaysDeniedWithoutWorldOrProviderWork() {
        AtomicBoolean current = new AtomicBoolean(true);
        AbilityPolicy policy = policy(List.of(observeGrant(REQUESTER, TARGET)), List.of());
        AbilityValidation.AbilityTrustedContextHandle context = context(policy, current);
        AbilityRequestAuthority.ExpectedContext expected = AbilityAuthorityFixture.expected(context);
        AbilityValidation.Decision decision = AbilityValidation.validate(
                observe(TARGET).getBytes(StandardCharsets.UTF_8), context, expected);
        assertEquals(AbilityValidation.DecisionStatus.VALID, decision.status());
        assertInstanceOf(AbilityValidation.ObserveInput.class, decision.intent());
        AbilityExecutionGate.Decision denied = AbilityExecutionGate.evaluate(context, expected, decision.intent());
        assertEquals(AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE, denied.reason());
        assertFalse(denied.effectStarted());

        current.set(false);
        assertEquals(AbilityExecutionGate.Reason.STALE_CONTEXT,
                AbilityExecutionGate.evaluate(context, expected, decision.intent()).reason());
    }

    @Test
    void explicitDenialWinsAndRequesterScopedDiscoveryNeverUnionsOrAuthorizes() {
        AbilityPolicy policy = policy(List.of(observeGrant(REQUESTER, TARGET)),
                List.of(new AbilityPolicy.Denial("deny-observe", AbilityPolicy.OBSERVE, 1)));
        AtomicBoolean current = new AtomicBoolean(true);
        AbilityValidation.AbilityTrustedContextHandle trusted = context(policy, current);
        AbilityRequestAuthority.ExpectedContext expected = AbilityAuthorityFixture.expected(trusted);
        AbilityExecutionGate.Decision decision = AbilityExecutionGate.evaluate(trusted, expected,
                new AbilityValidation.ObserveInput(TARGET));
        assertEquals(AbilityExecutionGate.Reason.PERMISSION_DENIED, decision.reason());

        AbilityDiscovery.Snapshot discovery = AbilityDiscovery.build(trusted, expected);
        assertEquals(2, discovery.entries().size());
        assertTrue(discovery.entries().stream().allMatch(entry ->
                entry.availability() == AbilityCatalogue.Availability.UNAVAILABLE));
        AbilityDiscovery.Entry observe = discovery.entries().getFirst();
        assertEquals(AbilityDiscovery.PolicyDisposition.DENIED, observe.policyDisposition());
        assertTrue(observe.disabledReasons().contains(AbilityDiscovery.DisabledReason.EXPLICIT_DENIAL));
        assertTrue(observe.effectiveGrants().isEmpty());
        assertTrue(discovery.toJson().getBytes(StandardCharsets.UTF_8).length <= AbilityCatalogue.MAX_DISCOVERY_BYTES);
        current.set(false);
        assertThrows(IllegalArgumentException.class, () -> AbilityDiscovery.build(trusted, expected),
                "stale request handles cannot be reused to publish discovery");
    }

    @Test
    void lightningRequiresTheSameCompleteGrantForDamageAndVisualPermission() {
        AbilityPolicy visualOnly = policy(List.of(new AbilityPolicy.Grant("lightning-a", CHARACTER,
                AbilityPolicy.LIGHTNING, 1, List.of(REQUESTER), List.of(TARGET), List.of("minecraft:overworld"),
                64, true, false, null)), List.of());
        AbilityValidation.AbilityTrustedContextHandle context = context(visualOnly, new AtomicBoolean(true));
        String damage = "{\"abilityId\":\"worldspeaker.core.strike_lightning\",\"contractVersion\":1," +
                "\"arguments\":{\"targetPlayerId\":\"" + TARGET + "\",\"visualOnly\":false}}";
        AbilityRequestAuthority.ExpectedContext expected = AbilityAuthorityFixture.expected(context);
        AbilityValidation.Decision parsed = AbilityValidation.validate(damage.getBytes(StandardCharsets.UTF_8), context, expected);
        assertEquals(AbilityValidation.DecisionStatus.VALID, parsed.status());
        assertEquals(AbilityExecutionGate.Reason.PERMISSION_DENIED,
                AbilityExecutionGate.evaluate(context, expected, parsed.intent()).reason());

        String visual = "{\"abilityId\":\"worldspeaker.core.strike_lightning\",\"contractVersion\":1," +
                "\"arguments\":{\"targetPlayerId\":\"" + TARGET + "\"}}";
        AbilityValidation.Decision visualParsed = AbilityValidation.validate(visual.getBytes(StandardCharsets.UTF_8), context, expected);
        assertInstanceOf(AbilityValidation.StrikeInput.class, visualParsed.intent());
        assertTrue(((AbilityValidation.StrikeInput) visualParsed.intent()).visualOnly());
        assertEquals(AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                AbilityExecutionGate.evaluate(context, expected, visualParsed.intent()).reason());
    }

    private static AbilityValidation.Decision validate(String json, AbilityValidation.AbilityTrustedContextHandle context) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        AbilityValidation.Decision parsed = AbilityValidation.parse(bytes);
        if (parsed.status() != AbilityValidation.DecisionStatus.VALID) return parsed;
        if (context == null) return new AbilityValidation.Decision(AbilityValidation.DecisionStatus.REJECTED,
                AbilityValidation.Reason.STALE_CONTEXT, null);
        return AbilityValidation.validate(bytes, context, AbilityAuthorityFixture.expected(context));
    }

    private static String observe(String target) {
        return "{\"abilityId\":\"worldspeaker.core.observe_player\",\"contractVersion\":1," +
                "\"arguments\":{\"targetPlayerId\":\"" + target + "\"}}";
    }

    private static AbilityPolicy policy(List<AbilityPolicy.Grant> grants, List<AbilityPolicy.Denial> denials) {
        return new AbilityPolicy(7, grants, denials, "REQUIRED");
    }

    private static AbilityPolicy.Grant observeGrant(String requester, String otherTarget) {
        return new AbilityPolicy.Grant("observe-a", CHARACTER, AbilityPolicy.OBSERVE, 1, List.of(requester),
                List.of(otherTarget), List.of("minecraft:overworld"), 64, false, false, null);
    }

    private static AbilityValidation.AbilityTrustedContextHandle context(AbilityPolicy policy, AtomicBoolean current) {
        return AbilityAuthorityFixture.issue(BINDING, REQUEST, INVOCATION, REQUESTER, EPOCH, policy, current::get);
    }
}
