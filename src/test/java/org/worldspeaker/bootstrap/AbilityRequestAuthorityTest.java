package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.ability.AbilityExecutionGate;
import org.worldspeaker.ability.AbilityPolicyResolver;
import org.worldspeaker.ability.AbilityValidation;
import org.worldspeaker.compat.AbilityPolicy;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AbilityRequestAuthorityTest {
    private static final String REQUESTER = "00000000-0000-4000-8000-000000000041";
    private static final String TARGET = "00000000-0000-4000-8000-000000000042";
    private static final String REQUEST = "00000000-0000-4000-8000-000000000043";
    private static final String INVOCATION = "00000000-0000-4000-8000-000000000044";
    private static final String EPOCH = "00000000-0000-4000-8000-000000000045";
    private static final AbilityValidation.Binding BINDING = new AbilityValidation.Binding(
            "server-test", "world-test", "character-test", "agent-test");

    @Test
    void productionOwnerRequiresSmokeConversationPrivateProvenanceAndIsNotPubliclyConstructible() {
        AbilityRequestAuthority.ReadOnlyAuthorityGamePort port = new AbilityRequestAuthority.ReadOnlyAuthorityGamePort() {
            @Override public boolean isCurrent(AbilityRequestAuthority.Admission admission) { return false; }
            @Override public boolean requesterMatches(AbilityRequestAuthority.Admission admission) { return false; }
            @Override public AbilityPolicyResolver.WorldFacts freshFacts(
                    AbilityRequestAuthority.Admission admission, AbilityValidation.Input intent) { return null; }
        };
        assertThrows(IllegalArgumentException.class,
                () -> AbilityRequestAuthority.productionOwner(new Object(), null, port));
        assertEquals(0, AbilityRequestAuthority.IsolatedOwner.class.getConstructors().length);
        assertEquals(0, AbilityRequestAuthority.OperationProposal.class.getConstructors().length,
                "correlated operation envelopes have no public constructor");
        assertFalse(java.util.Arrays.stream(AbilityRequestAuthority.class.getMethods())
                .anyMatch(method -> method.getName().equals("issue")),
                "there is no public live-issuance method or factory");
        assertFalse(java.util.Arrays.stream(AbilityRequestAuthority.class.getMethods())
                .anyMatch(method -> method.getName().equals("propose")),
                "there is no public operation-proposal factory");
    }

    @Test
    void guardedProductionGamePortChecksOwnerAndThreadBeforeEveryRead() {
        List<String> calls = new ArrayList<>();
        AtomicBoolean allowReads = new AtomicBoolean(true);
        AbilityRequestAuthority.ReadOnlyAuthorityGamePort game = new AbilityRequestAuthority.ReadOnlyAuthorityGamePort() {
            @Override public boolean isCurrent(AbilityRequestAuthority.Admission admission) {
                calls.add("game.current");
                return true;
            }

            @Override public boolean requesterMatches(AbilityRequestAuthority.Admission admission) {
                calls.add("game.requester");
                return true;
            }

            @Override public AbilityPolicyResolver.WorldFacts freshFacts(
                    AbilityRequestAuthority.Admission admission, AbilityValidation.Input intent) {
                calls.add("game.facts");
                return null;
            }
        };
        var guarded = AbilityRequestAuthority.guarded(game, () -> {
            calls.add("service-owner/thread-epoch/server.isOnThread");
            if (!allowReads.get()) throw new IllegalStateException("server owner/thread guard rejected");
        });
        AbilityRequestAuthority.Admission admission = new AbilityRequestAuthority.Admission(
                new Object(), new Object(), new Object(), "a".repeat(64), BINDING, AbilityPolicy.denyAll(), 0,
                REQUEST, INVOCATION, REQUESTER, REQUESTER, EPOCH, 1, 0, 1, 1, false,
                InteractionIngress.CaptureProvenance.SYNTHETIC_HARNESS,
                new InvocationCapture(java.util.UUID.fromString(INVOCATION), java.util.UUID.randomUUID(), 1,
                        1_800_000_000_001L, null, 0, InvocationCapture.Source.UNSIGNED_COMMAND),
                new Object(), new Object(), new Object());

        assertTrue(guarded.isCurrent(admission));
        assertTrue(guarded.requesterMatches(admission));
        guarded.freshFacts(admission, new AbilityValidation.ObserveInput(REQUESTER));
        assertEquals(List.of(
                "service-owner/thread-epoch/server.isOnThread", "game.current",
                "service-owner/thread-epoch/server.isOnThread", "game.requester",
                "service-owner/thread-epoch/server.isOnThread", "game.facts"), calls);

        calls.clear();
        allowReads.set(false);
        assertThrows(IllegalStateException.class, () -> guarded.requesterMatches(admission));
        assertTrue(calls.contains("service-owner/thread-epoch/server.isOnThread"));
        assertFalse(calls.stream().anyMatch(call -> call.startsWith("game.")),
                "a failed service/thread guard must prevent all identity and facts reads");
    }

    @Test
    void everyCheckedConsumptionRequiresTheExactRequestSnapshotAndAudience() {
        AtomicInteger freshReads = new AtomicInteger();
        AbilityPolicy policy = new AbilityPolicy(7, List.of(new AbilityPolicy.Grant("observe-self",
                "character-test", AbilityPolicy.OBSERVE, 1, List.of(REQUESTER), List.of(),
                List.of("minecraft:overworld"), 64, false, false, null)), List.of(), "REQUIRED");
        var handle = AbilityAuthorityFixture.issue(BINDING, REQUEST, INVOCATION, REQUESTER, EPOCH,
                policy, () -> true, intent -> {
                    freshReads.incrementAndGet();
                    return new AbilityPolicyResolver.WorldFacts(true, true, true, true, REQUESTER,
                            true, true, true, true, "minecraft:overworld", "minecraft:overworld",
                            new AbilityPolicyResolver.Position(0, 64, 0),
                            new AbilityPolicyResolver.Position(0, 64, 0),
                            new AbilityPolicyResolver.Border(-10, 10, -10, 10), null, null, null);
                });
        AbilityRequestAuthority.ExpectedContext expected = AbilityAuthorityFixture.expected(handle);
        assertTrue(AbilityRequestAuthority.isCurrent(handle, expected));

        AbilityRequestAuthority.ExpectedContext wrongRequest = copy(expected, new Object(),
                expected.configurationSnapshot(), expected.configurationHash(), expected.binding(), expected.requestId(),
                expected.audiencePlayerId());
        assertFalse(AbilityRequestAuthority.isCurrent(handle, wrongRequest));
        assertNull(AbilityRequestAuthority.currentSnapshot(handle, wrongRequest));
        assertEquals(AbilityPolicyResolver.Reason.STALE_CONTEXT,
                AbilityRequestAuthority.resolveCurrentIntent(handle, wrongRequest,
                        new AbilityValidation.ObserveInput(REQUESTER)).reason());
        assertEquals(0, freshReads.get(), "a mismatched request is rejected before any world read");

        assertFalse(AbilityRequestAuthority.isCurrent(handle, copy(expected, expected.requestIdentity(),
                new Object(), expected.configurationHash(), expected.binding(), expected.requestId(),
                expected.audiencePlayerId())), "same policy revision cannot substitute a different configuration object");
        assertFalse(AbilityRequestAuthority.isCurrent(handle, copy(expected, expected.requestIdentity(),
                expected.configurationSnapshot(), "b".repeat(64), expected.binding(), expected.requestId(),
                expected.audiencePlayerId())), "the exact configuration hash is bound too");
        assertFalse(AbilityRequestAuthority.isCurrent(handle, copy(expected, expected.requestIdentity(),
                expected.configurationSnapshot(), expected.configurationHash(),
                new AbilityValidation.Binding("other-server", "world-test", "character-test", "agent-test"),
                expected.requestId(), expected.audiencePlayerId())));
        assertFalse(AbilityRequestAuthority.isCurrent(handle, copy(expected, expected.requestIdentity(),
                expected.configurationSnapshot(), expected.configurationHash(), expected.binding(),
                "00000000-0000-4000-8000-000000000046", expected.audiencePlayerId())));
        assertFalse(AbilityRequestAuthority.isCurrent(handle, copy(expected, expected.requestIdentity(),
                expected.configurationSnapshot(), expected.configurationHash(), expected.binding(), expected.requestId(),
                TARGET)), "a handle cannot be consumed for a different private audience");
        assertFalse(AbilityRequestAuthority.isCurrent(handle, copySession(expected, expected.requestIdentity(),
                "00000000-0000-4000-8000-000000000046", REQUESTER, EPOCH)),
                "the same request identity cannot be rebound to another invocation/session");
        assertFalse(AbilityRequestAuthority.isCurrent(handle, copySession(expected, expected.requestIdentity(),
                expected.invocationId(), REQUESTER, "00000000-0000-4000-8000-000000000051")),
                "the original session cannot be replayed into a different server epoch");
        assertEquals(0, freshReads.get(), "context and audience mismatches never read private facts");

        var deniedTarget = AbilityRequestAuthority.resolveCurrentIntent(handle, expected,
                new AbilityValidation.ObserveInput(TARGET));
        assertEquals(AbilityPolicyResolver.Reason.TARGET_NOT_GRANTED, deniedTarget.reason());
        assertEquals(0, freshReads.get(), "a target outside the complete grant is rejected before game reads");

        AbilityExecutionGate.Decision allowedPolicy = AbilityExecutionGate.evaluate(handle, expected,
                new AbilityValidation.ObserveInput(REQUESTER));
        assertEquals(AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE, allowedPolicy.reason());
        assertFalse(allowedPolicy.effectStarted());
        assertEquals(1, freshReads.get());
    }

    @Test
    void copiedForeignReflectedAndRevokedHandlesFailClosed() throws Exception {
        AbilityPolicy policy = AbilityPolicy.denyAll();
        var first = AbilityAuthorityFixture.issue(BINDING, REQUEST, INVOCATION, REQUESTER, EPOCH,
                policy, () -> true);
        var firstExpected = AbilityAuthorityFixture.expected(first);
        var second = AbilityAuthorityFixture.issue(BINDING,
                "00000000-0000-4000-8000-000000000047", "00000000-0000-4000-8000-000000000048",
                REQUESTER, EPOCH, policy, () -> true);
        var secondExpected = AbilityAuthorityFixture.expected(second);

        assertFalse(AbilityRequestAuthority.isCurrent(first, secondExpected));
        assertFalse(AbilityRequestAuthority.isCurrent(second, firstExpected));
        assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.STALE_CONTEXT,
                org.worldspeaker.ability.AbilityExecutionGate.evaluate(first, secondExpected,
                        new AbilityValidation.ObserveInput(REQUESTER)).reason(),
                "owner A's private handle cannot consume owner B's request context");
        assertEquals(0, AbilityRequestAuthority.Handle.class.getConstructors().length);
        Constructor<?> constructor = AbilityRequestAuthority.Handle.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var reflected = (AbilityRequestAuthority.Handle) constructor.newInstance();
        assertFalse(AbilityRequestAuthority.isCurrent(reflected, firstExpected));

        AbilityRequestAuthority.revoke(first);
        assertFalse(AbilityRequestAuthority.isCurrent(first, firstExpected));
        AtomicBoolean current = new AtomicBoolean(true);
        var old = AbilityAuthorityFixture.issue(BINDING,
                "00000000-0000-4000-8000-000000000049", "00000000-0000-4000-8000-000000000050",
                REQUESTER, EPOCH, policy, current::get);
        var oldExpected = AbilityAuthorityFixture.expected(old);
        current.set(false);
        assertFalse(AbilityRequestAuthority.isCurrent(old, oldExpected));
    }

    private static AbilityRequestAuthority.ExpectedContext copy(
            AbilityRequestAuthority.ExpectedContext original, Object requestIdentity, Object configSnapshot,
            String configHash, AbilityValidation.Binding binding, String requestId, String audience) {
        return new AbilityRequestAuthority.ExpectedContext(requestIdentity, configSnapshot, configHash,
                binding, requestId, original.invocationId(), original.requesterId(), audience,
                original.serverEpochId(), original.serviceGeneration(), original.gateGeneration(),
                original.peerGeneration(), original.capturedAtNanos());
    }

    private static AbilityRequestAuthority.ExpectedContext copySession(
            AbilityRequestAuthority.ExpectedContext original, Object requestIdentity,
            String invocationId, String requesterId, String serverEpochId) {
        return new AbilityRequestAuthority.ExpectedContext(requestIdentity, original.configurationSnapshot(),
                original.configurationHash(), original.binding(), original.requestId(), invocationId,
                requesterId, original.audiencePlayerId(), serverEpochId, original.serviceGeneration(),
                original.gateGeneration(), original.peerGeneration(), original.capturedAtNanos());
    }
}
