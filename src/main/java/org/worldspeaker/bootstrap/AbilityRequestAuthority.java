package org.worldspeaker.bootstrap;

import org.worldspeaker.ability.AbilityPolicyResolver;
import org.worldspeaker.ability.AbilityValidation;
import org.worldspeaker.compat.AbilityPolicy;

import java.util.Map;
import java.util.Objects;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Owner-private request authority. Its opaque handles carry no state and cannot be constructed by callers. */
public final class AbilityRequestAuthority {
    private static final int MAX_LIVE_HANDLES = 17;
    private static final Map<Handle, State> STATES = new WeakHashMap<>();

    private AbilityRequestAuthority() {}

    /** Read-only owner port. A production adapter is server-thread guarded; isolated ports are never live proof. */
    interface ReadOnlyAuthorityGamePort {
        boolean isCurrent(Admission admission);
        boolean requesterMatches(Admission admission);
        AbilityPolicyResolver.WorldFacts freshFacts(Admission admission, AbilityValidation.Input intent);
    }

    /** Ensures even the production adapter checks service/server/thread ownership before delegating game reads. */
    static ReadOnlyAuthorityGamePort guarded(ReadOnlyAuthorityGamePort delegate, Runnable serverOwnerThreadGuard) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(serverOwnerThreadGuard, "serverOwnerThreadGuard");
        return new ReadOnlyAuthorityGamePort() {
            @Override public boolean isCurrent(Admission admission) {
                serverOwnerThreadGuard.run();
                return delegate.isCurrent(admission);
            }

            @Override public boolean requesterMatches(Admission admission) {
                serverOwnerThreadGuard.run();
                return delegate.requesterMatches(admission);
            }

            @Override public AbilityPolicyResolver.WorldFacts freshFacts(
                    Admission admission, AbilityValidation.Input intent) {
                serverOwnerThreadGuard.run();
                return delegate.freshFacts(admission, intent);
            }
        };
    }

    /** Per-conversation owner state. Live owners require SmokeConversation's private service key. */
    abstract static class Owner {
        private final Object serviceOwner;
        private final ReadOnlyAuthorityGamePort gamePort;
        private final AbilityLimitLedger limitLedger;
        private final IdentityHashMap<Object, Registration> registrations = new IdentityHashMap<>();
        private final boolean isolated;
        private final boolean simulatedWorkConditions;
        private final boolean retainForIsolatedObservation;
        private AbilityLimitOwner limitOwner;

        private Owner(Object serviceOwner, ReadOnlyAuthorityGamePort gamePort, AbilityLimitLedger limitLedger,
                      boolean isolated, boolean simulatedWorkConditions,
                      boolean retainForIsolatedObservation) {
            this.serviceOwner = Objects.requireNonNull(serviceOwner, "serviceOwner");
            this.gamePort = Objects.requireNonNull(gamePort, "gamePort");
            this.limitLedger = Objects.requireNonNull(limitLedger, "limitLedger");
            this.isolated = isolated;
            this.simulatedWorkConditions = simulatedWorkConditions;
            this.retainForIsolatedObservation = retainForIsolatedObservation;
        }

        synchronized boolean register(Object requestIdentity) {
            if (requestIdentity == null || registrations.containsKey(requestIdentity) ||
                    registrations.size() >= MAX_LIVE_HANDLES) return false;
            registrations.put(requestIdentity, new Registration());
            return true;
        }

        synchronized Handle issue(Object requestIdentity, Admission admission) {
            Registration registration = registrations.get(requestIdentity);
            if (registration == null || registration.admission != null || admission == null ||
                    admission.requestIdentity() != requestIdentity || !validAdmission(admission)) return null;
            registration.admission = admission;
            if (ensureLimitOwner(admission) == null) return null;
            if (!safeRequesterMatch(admission)) return null;
            Handle handle = issueOwned(this, admission,
                    () -> current(requestIdentity, admission),
                    intent -> freshFacts(requestIdentity, admission, intent));
            registration.handle = handle;
            return handle;
        }

        /** Wraps a bounded operation identity in this exact admitted context; this does not grant authority. */
        synchronized OperationProposal propose(Object requestIdentity, ExpectedContext expected,
                                                String operationId, AbilityValidation.Input intent) {
            Registration registration = registrations.get(requestIdentity);
            Admission admission = registration == null ? null : registration.admission;
            if (registration == null || registration.handle == null || admission == null || intent == null ||
                    !validOperationId(operationId) || !matches(expected, admission) ||
                    !current(requestIdentity, admission)) return null;
            return new OperationProposal(this, registration.handle, admission, expected, operationId, intent);
        }

        synchronized boolean isCurrent(Object requestIdentity, Admission admission) {
            return admission != null && current(requestIdentity, admission);
        }

        synchronized void retire(Object requestIdentity) {
            Registration registration = registrations.remove(requestIdentity);
            if (registration != null) {
                if (limitOwner != null && registration.admission != null) {
                    limitOwner.releaseRequest(requestIdentity, registration.admission.requestId());
                }
                if (registration.handle != null) AbilityRequestAuthority.revoke(registration.handle);
            }
        }

        private synchronized GateCheck evaluateForGate(Object requestIdentity, Admission admission,
                                                         ExpectedContext expected,
                                                         AbilityValidation.Input intent, String operationId,
                                                         long nowNanos) {
            Registration registration = registrations.get(requestIdentity);
            if (intent == null || registration == null || registration.admission != admission ||
                    !validOperationId(operationId) || !matches(expected, admission) ||
                    !current(requestIdentity, admission)) {
                return GateCheck.stale();
            }
            ContextSnapshot snapshot = snapshot(admission);
            AbilityPolicyResolver.Decision policyOnly = AbilityPolicyResolver.resolveIntent(snapshot, intent, null);
            if (policyOnly.status() == AbilityPolicyResolver.Status.DENIED) {
                return GateCheck.policy();
            }
            AbilityLimitOwner limits = ensureLimitOwner(admission);
            if (limits == null) return GateCheck.unavailable(
                    AbilityLimitLedger.Reason.BINDING_CLOSED);

            AbilityLimitLedger.KnownResult known = limits.lookupKnown(admission, intent, operationId);
            if (known.known()) {
                return known.matches() ? GateCheck.known(known.state()) :
                        GateCheck.unavailable(AbilityLimitLedger.Reason.IDENTITY_CONFLICT);
            }
            if (!safeRequesterMatch(admission)) return GateCheck.stale();
            AbilityPolicyResolver.WorldFacts facts = freshFacts(requestIdentity, admission, intent);
            if (!matches(expected, admission) || !current(requestIdentity, admission)) {
                return GateCheck.stale();
            }
            AbilityPolicyResolver.Decision resolution = AbilityPolicyResolver.resolveIntent(snapshot, intent, facts);
            if (resolution.status() == AbilityPolicyResolver.Status.DENIED) return GateCheck.policy();
            if (resolution.selectedGrantId() == null) {
                return GateCheck.unavailable(AbilityLimitLedger.Reason.EXECUTION_UNAVAILABLE);
            }
            AbilityLimitOwner.Outcome outcome = limits.reserve(admission, intent, resolution, facts,
                    operationId, nowNanos);
            return GateCheck.reserved(outcome);
        }

        private boolean matches(ExpectedContext expected, Admission admission) {
            return expected != null && admission != null && expected.requestIdentity() == admission.requestIdentity() &&
                    expected.configurationSnapshot() == admission.configurationSnapshot() &&
                    Objects.equals(expected.configurationHash(), admission.configurationHash()) &&
                    Objects.equals(expected.binding(), admission.binding()) &&
                    Objects.equals(expected.requestId(), admission.requestId()) &&
                    Objects.equals(expected.invocationId(), admission.invocationId()) &&
                    Objects.equals(expected.requesterId(), admission.requesterId()) &&
                    Objects.equals(expected.audiencePlayerId(), admission.audiencePlayerId()) &&
                    Objects.equals(expected.serverEpochId(), admission.serverEpochId()) &&
                    expected.serviceGeneration() == admission.serverGeneration() &&
                    expected.gateGeneration() == admission.gateGeneration() &&
                    expected.peerGeneration() == admission.peerGeneration() &&
                    expected.capturedAtNanos() == admission.capturedAtNanos();
        }

        private AbilityLimitOwner ensureLimitOwner(Admission admission) {
            if (admission == null || admission.binding() == null) return null;
            final AbilityLimitLedger.Binding currentBinding;
            try {
                if (!limitLedger.bind(admission.binding().serverId(),
                        admission.binding().worldId(), admission.binding().characterId())) return null;
                currentBinding = new AbilityLimitLedger.Binding(admission.binding().serverId(),
                        admission.binding().worldId(), admission.binding().characterId());
            } catch (RuntimeException invalidBinding) {
                return null;
            }
            if (limitOwner == null) {
                limitOwner = new AbilityLimitOwner(limitLedger, admission.binding(),
                        isolated && simulatedWorkConditions, isolated && retainForIsolatedObservation);
            } else if (!limitOwner.isBoundTo(currentBinding)) return null;
            return limitOwner;
        }

        private static ContextSnapshot snapshot(Admission admission) {
            return new ContextSnapshot(admission.binding(), admission.policy(), admission.policyRevision(),
                    admission.requestId(), admission.invocationId(), admission.requesterId(),
                    admission.serverEpochId(), admission.serverGeneration(), admission.gateGeneration(),
                    admission.peerGeneration(), admission.capturedAtNanos());
        }

        synchronized int retainedHoldsForTesting() {
            return isolated && limitOwner != null ? limitOwner.retainedCountForTesting() : 0;
        }

        synchronized int releaseRetainedHoldsForTesting() {
            return isolated && limitOwner != null ? limitOwner.releaseAllRetainedForTesting() : 0;
        }

        synchronized AbilityLimitLedger.StartResult startFirstRetainedForTesting(LongSupplier now) {
            if (!isolated || limitOwner == null || now == null) return AbilityLimitLedger.StartResult.denied(
                    AbilityLimitLedger.Reason.STALE_TOKEN);
            return limitOwner.startFirstRetainedForTesting(retained -> {
                Admission admission = retained.admission();
                Object requestIdentity = admission.requestIdentity();
                if (!current(requestIdentity, admission) || !safeRequesterMatch(admission)) {
                    limitOwner.releaseRetained(retained.token());
                    return AbilityLimitLedger.StartResult.denied(AbilityLimitLedger.Reason.STALE_TOKEN);
                }
                AbilityPolicyResolver.WorldFacts facts = freshFacts(requestIdentity, admission, retained.intent());
                if (!current(requestIdentity, admission)) {
                    limitOwner.releaseRetained(retained.token());
                    return AbilityLimitLedger.StartResult.denied(AbilityLimitLedger.Reason.STALE_TOKEN);
                }
                AbilityPolicyResolver.Decision resolution = AbilityPolicyResolver.resolveIntent(
                        snapshot(admission), retained.intent(), facts);
                return limitOwner.startRetained(retained.token(), admission, retained.intent(), resolution,
                        facts, now.getAsLong());
            });
        }

        synchronized void cleanupAbilityLimits() {
            if (limitOwner != null) limitOwner.cleanupIncremental();
        }

        private boolean validAdmission(Admission admission) {
            if (admission.requestIdentity() == null || admission.invocationIdentity() == null ||
                    admission.configurationSnapshot() == null || admission.configurationHash() == null ||
                    !admission.configurationHash().matches("[a-f0-9]{64}") || admission.policy() == null ||
                    admission.policy().revision() != admission.policyRevision() || admission.binding() == null ||
                    admission.requestId() == null || admission.invocationId() == null ||
                    admission.requesterId() == null || admission.audiencePlayerId() == null ||
                    !admission.requesterId().equals(admission.audiencePlayerId()) ||
                    admission.packetCapture() == null || admission.connection() == null ||
                    admission.originalPlayer() == null || admission.originalWorld() == null ||
                    admission.serverEpochId() == null || admission.serverGeneration() <= 0 ||
                    admission.gateGeneration() < 0 || admission.peerGeneration() <= 0) return false;
            if (isolated) {
                return !admission.liveOrigin() && admission.captureProvenance() ==
                        InteractionIngress.CaptureProvenance.SYNTHETIC_HARNESS;
            }
            return admission.liveOrigin() && admission.captureProvenance() ==
                    InteractionIngress.CaptureProvenance.LIVE_PACKET &&
                    admission.packetCapture().belongsTo(admission.connection(),
                            admission.packetCapture().connectionGeneration()) &&
                    admission.packetCapture().connectionGeneration() > 0;
        }

        private synchronized boolean current(Object requestIdentity, Admission admission) {
            Registration registration = registrations.get(requestIdentity);
            if (serviceOwner == null || registration == null || registration.admission != admission ||
                    !validAdmission(admission)) return false;
            try {
                return gamePort.isCurrent(admission);
            } catch (RuntimeException unavailable) {
                return false;
            }
        }

        private boolean safeRequesterMatch(Admission admission) {
            try {
                return gamePort.requesterMatches(admission);
            } catch (RuntimeException unavailable) {
                return false;
            }
        }

        private AbilityPolicyResolver.WorldFacts freshFacts(
                Object requestIdentity, Admission admission, AbilityValidation.Input intent) {
            if (!current(requestIdentity, admission)) return null;
            try {
                return gamePort.freshFacts(admission, intent);
            } catch (RuntimeException unavailable) {
                return null;
            }
        }

        private static final class Registration {
            private Admission admission;
            private Handle handle;
        }
    }

    /** Test/source-package-only owner. It accepts synthetic provenance and is not a live service owner. */
    static final class IsolatedOwner extends Owner {
        private IsolatedOwner(ReadOnlyAuthorityGamePort gamePort, AbilityLimitLedger ledger,
                              boolean simulatedWorkConditions,
                              boolean retainForIsolatedObservation) {
            super(new Object(), gamePort, ledger, true, simulatedWorkConditions,
                    retainForIsolatedObservation);
        }
    }

    static IsolatedOwner isolatedOwner(ReadOnlyAuthorityGamePort gamePort) {
        return new IsolatedOwner(gamePort, new AbilityLimitLedger(System::nanoTime), false, false);
    }

    static IsolatedOwner isolatedOwner(ReadOnlyAuthorityGamePort gamePort, AbilityLimitLedger ledger) {
        return new IsolatedOwner(gamePort, ledger, false, false);
    }

    static IsolatedOwner isolatedSimulationOwner(ReadOnlyAuthorityGamePort gamePort, AbilityLimitLedger ledger,
                                                  boolean retainForObservation) {
        return new IsolatedOwner(gamePort, ledger, true, retainForObservation);
    }

    private static final class ProductionOwner extends Owner {
        private ProductionOwner(Object serviceOwner, ReadOnlyAuthorityGamePort gamePort,
                                AbilityLimitLedger ledger) {
            super(serviceOwner, gamePort, ledger, false, false, false);
        }
    }

    static Owner productionOwner(Object serviceKey, SmokeConversation conversation,
                                 ReadOnlyAuthorityGamePort gamePort, AbilityLimitLedger ledger) {
        if (!SmokeConversation.ownsAbilityAuthorityServiceKey(serviceKey, conversation)) {
            throw new IllegalArgumentException("production ability owner requires SmokeConversation service provenance");
        }
        return new ProductionOwner(conversation, gamePort, ledger);
    }

    static Owner productionOwner(Object serviceKey, SmokeConversation conversation,
                                 ReadOnlyAuthorityGamePort gamePort) {
        if (!SmokeConversation.ownsAbilityAuthorityServiceKey(serviceKey, conversation)) {
            throw new IllegalArgumentException("production ability owner requires SmokeConversation service provenance");
        }
        return new ProductionOwner(conversation, gamePort, new AbilityLimitLedger(System::nanoTime));
    }

    /** Opaque capability; identity membership in the private registry is required for every use. */
    public static final class Handle implements AbilityValidation.AbilityTrustedContextHandle {
        private Handle() {}
    }

    /**
     * Immutable internal proposal envelope. Only the admitted private owner can construct one; it is not a permit.
     * Typed intent remains separate from untrusted JSON, and the canonical UUID is only an identity key.
     */
    public static final class OperationProposal {
        private final Owner owner;
        private final Handle handle;
        private final Admission admission;
        private final ExpectedContext expected;
        private final String operationId;
        private final AbilityValidation.Input intent;

        private OperationProposal(Owner owner, Handle handle, Admission admission, ExpectedContext expected,
                                  String operationId, AbilityValidation.Input intent) {
            this.owner = owner;
            this.handle = handle;
            this.admission = admission;
            this.expected = expected;
            this.operationId = operationId;
            this.intent = intent;
        }
    }

    /** Minimal checked view for pure discovery and policy resolution. It contains no lifecycle callbacks. */
    public record ContextSnapshot(AbilityValidation.Binding binding, AbilityPolicy policy, long policyRevision,
                                 String requestId, String invocationId, String requesterId,
                                 String serverEpochId, long serviceGeneration, long gateGeneration,
                                 long peerGeneration, long capturedAtNanos) {}

    /** Caller expectations are compared with owner-held identities; they never create authority. */
    public record ExpectedContext(Object requestIdentity, Object configurationSnapshot, String configurationHash,
                                  AbilityValidation.Binding binding, String requestId, String invocationId,
                                  String requesterId, String audiencePlayerId, String serverEpochId,
                                  long serviceGeneration, long gateGeneration, long peerGeneration,
                                  long capturedAtNanos) {}

    public enum GateCause {
        STALE_CONTEXT, POLICY_DENIED, RATE_LIMITED, CONCURRENCY_LIMITED,
        DEADLINE_EXPIRED, CANCELLED, EXECUTION_UNAVAILABLE
    }

    /** Deny-only accounting result. It exposes neither an accounting token nor a permit. */
    public record GateCheck(boolean staleContext, boolean permissionDenied,
                            GateCause cause, Long retryAfterMillis) {
        public GateCheck {
            if (cause == null || staleContext && permissionDenied ||
                    retryAfterMillis != null && (cause != GateCause.RATE_LIMITED || retryAfterMillis < 0 ||
                            retryAfterMillis > 30_000)) {
                throw new IllegalArgumentException("invalid deny-only gate result");
            }
        }
        static GateCheck stale() { return new GateCheck(true, false, GateCause.STALE_CONTEXT, null); }
        static GateCheck policy() { return new GateCheck(false, true, GateCause.POLICY_DENIED, null); }
        static GateCheck known(AbilityLimitLedger.IdentityState ignored) {
            return new GateCheck(false, false, GateCause.EXECUTION_UNAVAILABLE, null);
        }
        static GateCheck unavailable(AbilityLimitLedger.Reason reason) {
            return new GateCheck(false, false, cause(reason), null);
        }
        static GateCheck reserved(AbilityLimitOwner.Outcome outcome) {
            if (outcome == null) return unavailable(AbilityLimitLedger.Reason.EXECUTION_UNAVAILABLE);
            GateCause cause = outcome.held() || outcome.released() || outcome.known()
                    ? GateCause.EXECUTION_UNAVAILABLE : cause(outcome.reason());
            Long retry = cause == GateCause.RATE_LIMITED ? outcome.retryAfterMillis() : null;
            return new GateCheck(false, false, cause, retry);
        }
        private static GateCause cause(AbilityLimitLedger.Reason reason) {
            if (reason == null) return GateCause.EXECUTION_UNAVAILABLE;
            return switch (reason) {
                case REQUESTER_RATE, TARGET_RATE, CHARACTER_RATE -> GateCause.RATE_LIMITED;
                case CONCURRENCY_LIMITED -> GateCause.CONCURRENCY_LIMITED;
                case DEADLINE_EXPIRED -> GateCause.DEADLINE_EXPIRED;
                case CANCELLED -> GateCause.CANCELLED;
                default -> GateCause.EXECUTION_UNAVAILABLE;
            };
        }
    }

    private static Handle issueOwned(Owner owner, Admission admission, BooleanSupplier current,
                                     Function<AbilityValidation.Input, AbilityPolicyResolver.WorldFacts> freshFacts) {
        if (owner == null || admission == null || current == null || freshFacts == null ||
                !owner.validAdmission(admission)) return null;
        Handle handle = new Handle();
        synchronized (STATES) {
            STATES.size(); // Drain weak entries before enforcing the live-request bound.
            long ownerHandles = STATES.values().stream().filter(state -> state.owner() == owner).count();
            if (ownerHandles >= MAX_LIVE_HANDLES) return null;
            STATES.put(handle, new State(owner, admission, current, freshFacts));
        }
        return handle;
    }

    /** Checked consumption; expected values are matched against the exact owner-held request and snapshot. */
    public static boolean isCurrent(AbilityValidation.AbilityTrustedContextHandle handle, ExpectedContext expected) {
        State state = lookup(handle);
        return state != null && state.matches(expected) && state.isCurrent();
    }

    /** Owner-private gate bridge; the result can only deny and cannot be converted into a permit. */
    public static GateCheck evaluateGate(AbilityValidation.AbilityTrustedContextHandle handle,
                                         ExpectedContext expected, AbilityValidation.Input intent,
                                         long nowNanos) {
        State state = lookup(handle);
        if (state == null || intent == null || !state.matches(expected) || !state.isCurrent()) {
            return GateCheck.stale();
        }
        // Legacy no-correlation callers get one conservative identity: the admitted request ID.
        OperationProposal proposal = state.owner().propose(state.admission().requestIdentity(), expected,
                state.admission().requestId(), intent);
        return evaluateProposal(proposal, nowNanos);
    }

    /** Consumes an immutable, owner-bound proposal; its operation ID is deduplication data, never permission. */
    public static GateCheck evaluateProposal(OperationProposal proposal, long nowNanos) {
        if (proposal == null) return GateCheck.stale();
        State state = lookup(proposal.handle);
        if (state == null || proposal.owner != state.owner() || proposal.admission != state.admission() ||
                proposal.intent == null || !state.matches(proposal.expected) || !state.isCurrent()) {
            return GateCheck.stale();
        }
        return state.owner().evaluateForGate(proposal.admission.requestIdentity(), proposal.admission,
                proposal.expected, proposal.intent, proposal.operationId, nowNanos);
    }

    public static ContextSnapshot currentSnapshot(AbilityValidation.AbilityTrustedContextHandle handle,
                                                  ExpectedContext expected) {
        State state = lookup(handle);
        return state == null || !state.matches(expected) || !state.isCurrent() ? null : state.snapshot();
    }

    /** Re-reads owner-private facts immediately before pure policy evaluation. Facts never leave this call. */
    public static AbilityPolicyResolver.Decision resolveCurrentIntent(
            AbilityValidation.AbilityTrustedContextHandle handle, ExpectedContext expected,
            AbilityValidation.Input intent) {
        State state = lookup(handle);
        if (state == null || intent == null || !state.matches(expected) || !state.isCurrent()) {
            return stale(intent);
        }
        AbilityPolicyResolver.Decision policyCheck = AbilityPolicyResolver.resolveIntent(
                state.snapshot(), intent, null);
        if (policyCheck.status() == AbilityPolicyResolver.Status.DENIED) return policyCheck;
        AbilityPolicyResolver.WorldFacts facts = null;
        try {
            facts = state.freshFacts().apply(intent);
        } catch (RuntimeException unavailable) {
            // Unavailable world facts remain unresolved; the deny-only gate cannot turn them into authority.
        }
        if (!state.matches(expected) || !state.isCurrent()) return stale(intent);
        return AbilityPolicyResolver.resolveIntent(state.snapshot(), intent, facts);
    }

    private static AbilityPolicyResolver.Decision stale(AbilityValidation.Input intent) {
        return AbilityPolicyResolver.resolveIntent(null, intent, null);
    }

    /** Revokes a terminal request's handle immediately, independent of eventual garbage collection. */
    static void revoke(AbilityValidation.AbilityTrustedContextHandle handle) {
        if (!(handle instanceof Handle owned)) return;
        synchronized (STATES) { STATES.remove(owned); }
    }

    private static State lookup(AbilityValidation.AbilityTrustedContextHandle handle) {
        if (!(handle instanceof Handle owned)) return null;
        synchronized (STATES) { return STATES.get(owned); }
    }

    private static boolean validOperationId(String operationId) {
        if (operationId == null || !operationId.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) return false;
        try { return java.util.UUID.fromString(operationId).toString().equals(operationId); }
        catch (IllegalArgumentException malformed) { return false; }
    }

    static record Admission(Object requestIdentity, Object invocationIdentity, Object configurationSnapshot,
                           String configurationHash, AbilityValidation.Binding binding, AbilityPolicy policy,
                           long policyRevision, String requestId, String invocationId, String requesterId,
                           String audiencePlayerId, String serverEpochId, long serverGeneration,
                           long gateGeneration, long peerGeneration, long capturedAtNanos, boolean liveOrigin,
                           InteractionIngress.CaptureProvenance captureProvenance,
                           InvocationCapture packetCapture, Object connection, Object originalPlayer,
                           Object originalWorld) {}

    private record State(Owner owner, Admission admission, BooleanSupplier currentCheck,
                         Function<AbilityValidation.Input, AbilityPolicyResolver.WorldFacts> freshFacts) {
        private boolean matches(ExpectedContext expected) {
            return expected != null && expected.requestIdentity() == admission.requestIdentity() &&
                    expected.configurationSnapshot() == admission.configurationSnapshot() &&
                    Objects.equals(expected.configurationHash(), admission.configurationHash()) &&
                    Objects.equals(expected.binding(), admission.binding()) &&
                    Objects.equals(expected.requestId(), admission.requestId()) &&
                    Objects.equals(expected.invocationId(), admission.invocationId()) &&
                    Objects.equals(expected.requesterId(), admission.requesterId()) &&
                    Objects.equals(expected.audiencePlayerId(), admission.audiencePlayerId()) &&
                    Objects.equals(expected.serverEpochId(), admission.serverEpochId()) &&
                    expected.serviceGeneration() == admission.serverGeneration() &&
                    expected.gateGeneration() == admission.gateGeneration() &&
                    expected.peerGeneration() == admission.peerGeneration() &&
                    expected.capturedAtNanos() == admission.capturedAtNanos();
        }

        private boolean isCurrent() {
            try {
                return owner != null && owner.validAdmission(admission) && currentCheck.getAsBoolean();
            } catch (RuntimeException unavailable) {
                return false;
            }
        }

        private ContextSnapshot snapshot() {
            return new ContextSnapshot(admission.binding(), admission.policy(), admission.policyRevision(),
                    admission.requestId(), admission.invocationId(), admission.requesterId(),
                    admission.serverEpochId(), admission.serverGeneration(), admission.gateGeneration(),
                    admission.peerGeneration(), admission.capturedAtNanos());
        }
    }
}
