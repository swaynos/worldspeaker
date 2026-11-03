package org.worldspeaker.bootstrap;

import org.worldspeaker.ability.AbilityPolicyResolver;
import org.worldspeaker.ability.AbilityValidation;
import org.worldspeaker.compat.AbilityPolicy;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Test-source-only issuer for synthetic authority; these handles cannot be issued by production code. */
public final class AbilityAuthorityFixture {
    private static final Map<AbilityValidation.AbilityTrustedContextHandle,
            AbilityRequestAuthority.ExpectedContext> EXPECTED = new WeakHashMap<>();
    private static final AtomicLong NEXT = new AtomicLong(100);

    private AbilityAuthorityFixture() {}

    public static AbilityValidation.AbilityTrustedContextHandle issue(
            AbilityValidation.Binding binding, String requestId, String invocationId, String requesterId,
            String serverEpochId, AbilityPolicy policy, BooleanSupplier current,
            Function<AbilityValidation.Input, AbilityPolicyResolver.WorldFacts> freshFacts) {
        Object requestIdentity = new Object();
        Object invocationIdentity = new Object();
        Object configurationSnapshot = new Object();
        Object connection = new Object();
        Object originalPlayer = new Object();
        Object originalWorld = new Object();
        long receivedAtNanos = NEXT.getAndIncrement();
        UUID invocation = UUID.fromString(invocationId);
        UUID eventId = UUID.randomUUID();
        InvocationCapture packet = new InvocationCapture(invocation, eventId, receivedAtNanos,
                1_800_000_000_000L + receivedAtNanos, null, 0,
                InvocationCapture.Source.SIGNED_CHAT);
        AbilityRequestAuthority.Admission admission = new AbilityRequestAuthority.Admission(
                requestIdentity, invocationIdentity, configurationSnapshot, "a".repeat(64), binding, policy,
                policy.revision(), requestId, invocationId, requesterId, requesterId, serverEpochId,
                1, 1, 1, receivedAtNanos, false, InteractionIngress.CaptureProvenance.SYNTHETIC_HARNESS,
                packet, connection, originalPlayer, originalWorld);
        AbilityRequestAuthority.IsolatedOwner owner = AbilityRequestAuthority.isolatedOwner(
                new AbilityRequestAuthority.ReadOnlyAuthorityGamePort() {
                    @Override public boolean isCurrent(AbilityRequestAuthority.Admission candidate) {
                        return candidate == admission && current.getAsBoolean();
                    }

                    @Override public boolean requesterMatches(AbilityRequestAuthority.Admission candidate) {
                        return candidate == admission && !candidate.liveOrigin() &&
                                candidate.captureProvenance() == InteractionIngress.CaptureProvenance.SYNTHETIC_HARNESS &&
                                candidate.requesterId().equals(candidate.audiencePlayerId());
                    }

                    @Override public AbilityPolicyResolver.WorldFacts freshFacts(
                            AbilityRequestAuthority.Admission candidate, AbilityValidation.Input intent) {
                        return candidate == admission && freshFacts != null ? freshFacts.apply(intent) : null;
                    }
                });
        owner.register(requestIdentity);
        AbilityRequestAuthority.Handle handle = owner.issue(requestIdentity, admission);
        if (handle == null) throw new IllegalArgumentException("test authority fixture could not issue handle");
        AbilityRequestAuthority.ExpectedContext expected = new AbilityRequestAuthority.ExpectedContext(
                requestIdentity, configurationSnapshot, admission.configurationHash(), binding, requestId,
                invocationId, requesterId, requesterId, serverEpochId, 1, 1, 1, receivedAtNanos);
        synchronized (EXPECTED) { EXPECTED.put(handle, expected); }
        return handle;
    }

    public static AbilityValidation.AbilityTrustedContextHandle issue(
            AbilityValidation.Binding binding, String requestId, String invocationId, String requesterId,
            String serverEpochId, AbilityPolicy policy, BooleanSupplier current) {
        return issue(binding, requestId, invocationId, requesterId, serverEpochId, policy, current, null);
    }

    public static AbilityRequestAuthority.ExpectedContext expected(
            AbilityValidation.AbilityTrustedContextHandle handle) {
        synchronized (EXPECTED) { return EXPECTED.get(handle); }
    }
}
