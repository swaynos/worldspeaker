package org.worldspeaker.bootstrap;

import org.worldspeaker.ability.AbilityPolicyResolver;
import org.worldspeaker.ability.AbilityValidation;
import org.worldspeaker.compat.AbilityPolicy;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Internal request-owner bridge from fresh policy/facts into the private in-memory ledger. */
final class AbilityLimitOwner {
    private final AbilityLimitLedger ledger;
    private final AbilityLimitLedger.Binding binding;
    private final Object ledgerOwnerIdentity = new Object();
    private final boolean isolatedSimulation;
    private final boolean retainForIsolatedObservation;
    private final IdentityHashMap<AbilityLimitLedger.Token, Retained> tokens = new IdentityHashMap<>();

    AbilityLimitOwner(AbilityLimitLedger ledger, AbilityValidation.Binding configuredBinding,
                      boolean isolatedSimulation, boolean retainForIsolatedObservation) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(configuredBinding, "configuredBinding");
        this.binding = new AbilityLimitLedger.Binding(configuredBinding.serverId(),
                configuredBinding.worldId(), configuredBinding.characterId());
        this.isolatedSimulation = isolatedSimulation;
        this.retainForIsolatedObservation = retainForIsolatedObservation;
    }

    boolean isBoundTo(AbilityLimitLedger.Binding requested) {
        return binding.same(requested) && ledger.isBoundTo(requested);
    }

    AbilityLimitLedger.KnownResult lookupKnown(AbilityRequestAuthority.Admission admission,
                                                AbilityValidation.Input intent, String operationId) {
        if (admission == null || intent == null || !canonicalOperationId(operationId)) {
            return new AbilityLimitLedger.KnownResult(false, false,
                    AbilityLimitLedger.Reason.INVALID_DEMAND, null);
        }
        AbilityLimitLedger.Kind kind = kind(intent);
        boolean visualOnly = !(intent instanceof AbilityValidation.StrikeInput strike) || strike.visualOnly();
        AbilityLimitLedger.Binding requestBinding = bindingFor(admission);
        if (requestBinding == null || !ledger.isBoundTo(requestBinding)) {
            return new AbilityLimitLedger.KnownResult(false, false, AbilityLimitLedger.Reason.BINDING_CLOSED, null);
        }
        return ledger.lookupKnown(kind, operationId, admission.invocationId(), admission.requestId(),
                requestBinding, admission.requesterId(), intent.targetPlayerId(), visualOnly,
                admission.configurationHash(), admission.serverEpochId(), admission.serverGeneration(),
                admission.gateGeneration(), admission.peerGeneration());
    }

    Outcome reserve(AbilityRequestAuthority.Admission admission, AbilityValidation.Input intent,
                    AbilityPolicyResolver.Decision resolution,
                    AbilityPolicyResolver.WorldFacts facts, String operationId, long nowNanos) {
        if (admission == null || intent == null || resolution == null ||
                !canonicalOperationId(operationId) ||
                resolution.status() != AbilityPolicyResolver.Status.CONDITIONAL ||
                resolution.selectedGrantId() == null) {
            return Outcome.unavailable(AbilityLimitLedger.Reason.INVALID_DEMAND);
        }
        if (!ledger.isBoundTo(binding)) return Outcome.unavailable(AbilityLimitLedger.Reason.BINDING_CLOSED);
        if (!unresolvedChecksPermitAccountingHold(resolution.unresolvedChecks())) {
            // Only named later-phase checks may remain unresolved across a transient accounting hold.
            return Outcome.unavailable(AbilityLimitLedger.Reason.EXECUTION_UNAVAILABLE);
        }

        AbilityPolicy.Grant grant = selectedGrant(admission.policy(), resolution, intent, admission.requesterId());
        if (grant == null) return Outcome.unavailable(AbilityLimitLedger.Reason.INVALID_DEMAND);
        AbilityWorkLimits.WorkPlan plan = plan(intent);
        AbilityWorkLimits.WorkDecision work = AbilityWorkLimits.validateCurrentFacts(intent, grant, facts,
                admission.policy().externalClaimsMode(), plan);
        if (!work.allowedForBoundedHold()) {
            return Outcome.unavailable(work.rejection() == AbilityWorkLimits.Rejection.INVALID_BOUNDS
                    || work.rejection() == AbilityWorkLimits.Rejection.INVALID_PLACEMENT ||
                    work.rejection() == AbilityWorkLimits.Rejection.INVALID_FOOTPRINT
                    ? AbilityLimitLedger.Reason.INVALID_DEMAND : AbilityLimitLedger.Reason.EXECUTION_UNAVAILABLE);
        }
        if (intent instanceof AbilityValidation.StrikeInput strike && !strike.visualOnly()) {
            // Current World facts contain no qualified base-health, mitigation, or suppression evidence.
            return Outcome.unavailable(AbilityLimitLedger.Reason.EXECUTION_UNAVAILABLE);
        }
        final AbilityLimitLedger.Demand demand;
        try { demand = demand(admission, intent, grant, work.placement(), operationId, nowNanos, nowNanos); }
        catch (ArithmeticException | IllegalArgumentException invalid) {
            return Outcome.unavailable(AbilityLimitLedger.Reason.INVALID_DEMAND);
        }
        if (tokens.size() >= AbilityLimitLedger.MAX_LIVE_HOLDS) {
            return Outcome.unavailable(AbilityLimitLedger.Reason.CAPACITY);
        }
        AbilityLimitLedger.ReserveResult result = ledger.reserve(demand, ledgerOwnerIdentity);
        if (result.reserved()) {
            tokens.put(result.token(), new Retained(admission, intent, operationId, result.token(),
                    nowNanos, false));
            if (!retainForIsolatedObservation) {
                // Normal owners have no read/start seam: release the accounting hold synchronously and deny.
                boolean released = ledger.release(result.token(), ledgerOwnerIdentity);
                tokens.remove(result.token());
                if (!released) return Outcome.unavailable(AbilityLimitLedger.Reason.STALE_TOKEN);
                return Outcome.heldAndReleased();
            }
            return Outcome.heldForObservation();
        }
        if (result.knownState() != null) return Outcome.known(result.knownState());
        return Outcome.unavailable(result.reason(), result.retryAfterMillis());
    }

    AbilityLimitLedger.StartResult startRetained(AbilityLimitLedger.Token token,
                                                  AbilityRequestAuthority.Admission currentAdmission,
                                                  AbilityValidation.Input intent,
                                                  AbilityPolicyResolver.Decision currentResolution,
                                                  AbilityPolicyResolver.WorldFacts currentFacts,
                                                  long nowNanos) {
        if (!isolatedSimulation || !retainForIsolatedObservation || token == null) {
            return AbilityLimitLedger.StartResult.denied(AbilityLimitLedger.Reason.STALE_TOKEN);
        }
        Retained retained = tokens.get(token);
        if (retained == null || retained.started() || retained.admission() != currentAdmission ||
                !sameInput(retained.intent(), intent)) {
            return AbilityLimitLedger.StartResult.denied(AbilityLimitLedger.Reason.STALE_TOKEN);
        }
        if (currentResolution == null || currentResolution.status() != AbilityPolicyResolver.Status.CONDITIONAL ||
                currentResolution.selectedGrantId() == null) {
            ledger.release(token, ledgerOwnerIdentity);
            tokens.remove(token);
            return AbilityLimitLedger.StartResult.denied(AbilityLimitLedger.Reason.IDENTITY_CONFLICT);
        }
        AbilityPolicy.Grant grant = selectedGrant(currentAdmission.policy(), currentResolution, intent,
                currentAdmission.requesterId());
        if (grant == null) {
            ledger.release(token, ledgerOwnerIdentity);
            tokens.remove(token);
            return AbilityLimitLedger.StartResult.denied(AbilityLimitLedger.Reason.IDENTITY_CONFLICT);
        }
        AbilityWorkLimits.WorkDecision work = AbilityWorkLimits.validateCurrentFacts(intent, grant, currentFacts,
                currentAdmission.policy().externalClaimsMode(), plan(intent));
        if (!work.allowedForBoundedHold()) {
            ledger.release(token, ledgerOwnerIdentity);
            tokens.remove(token);
            return AbilityLimitLedger.StartResult.denied(AbilityLimitLedger.Reason.INVALID_DEMAND);
        }
        try {
            AbilityLimitLedger.Demand current = demand(currentAdmission, intent, grant, work.placement(),
                    retained.operationId(), retained.firstAdmissionAtNanos(), nowNanos);
            AbilityLimitLedger.StartResult started = ledger.consume(token, ledgerOwnerIdentity, current);
            if (started.started()) tokens.put(token, retained.markStarted());
            else tokens.remove(token);
            return started;
        } catch (ArithmeticException | IllegalArgumentException invalid) {
            ledger.release(token, ledgerOwnerIdentity);
            tokens.remove(token);
            return AbilityLimitLedger.StartResult.denied(AbilityLimitLedger.Reason.INVALID_DEMAND);
        }
    }

    boolean releaseRetained(AbilityLimitLedger.Token token) {
        if (!isolatedSimulation || !retainForIsolatedObservation || token == null || !tokens.containsKey(token)) {
            return false;
        }
        boolean released = ledger.release(token, ledgerOwnerIdentity);
        if (released) tokens.remove(token);
        return released;
    }

    int releaseRequest(Object requestIdentity, String requestId) {
        if (requestId == null) return 0;
        int released = 0;
        var iterator = tokens.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Retained retained = entry.getValue();
            if (retained.admission().requestIdentity() != requestIdentity ||
                    !retained.admission().requestId().equals(requestId)) continue;
            if (ledger.release(entry.getKey(), ledgerOwnerIdentity)) {
                iterator.remove();
                released++;
            }
        }
        ledger.retireRequest(requestId);
        return released;
    }

    void cleanupIncremental() {
        ledger.cleanupIncremental(2);
        tokens.entrySet().removeIf(entry -> !ledger.isActive(entry.getKey(), ledgerOwnerIdentity));
    }

    int retainedCountForTesting() {
        return isolatedSimulation && retainForIsolatedObservation ? tokens.size() : 0;
    }

    AbilityLimitLedger.StartResult startFirstRetainedForTesting(
            java.util.function.Function<Retained, AbilityLimitLedger.StartResult> start) {
        if (!isolatedSimulation || !retainForIsolatedObservation || start == null || tokens.isEmpty()) {
            return AbilityLimitLedger.StartResult.denied(AbilityLimitLedger.Reason.STALE_TOKEN);
        }
        Retained retained = tokens.values().iterator().next();
        return start.apply(retained);
    }

    int releaseAllRetainedForTesting() {
        if (!isolatedSimulation || !retainForIsolatedObservation) return 0;
        int released = 0;
        var iterator = tokens.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (ledger.release(entry.getKey(), ledgerOwnerIdentity)) released++;
            iterator.remove();
        }
        return released;
    }

    private AbilityLimitLedger.Demand demand(AbilityRequestAuthority.Admission admission,
                                             AbilityValidation.Input intent,
                                             AbilityPolicy.Grant grant,
                                             AbilityWorkLimits.Placement placement,
                                             String operationId,
                                             long firstAdmissionAtNanos,
                                             long nowNanos) {
        boolean observe = intent instanceof AbilityValidation.ObserveInput;
        long requesterInterval;
        long targetInterval = 0;
        long characterInterval;
        if (observe) {
            requesterInterval = Math.max(2_000, grant.observeLimits().requesterIntervalMs());
            characterInterval = Math.max(250, grant.observeLimits().characterIntervalMs());
        } else {
            requesterInterval = Math.max(30_000, grant.lightningLimits().requesterCooldownMs());
            targetInterval = Math.max(30_000, grant.lightningLimits().targetCooldownMs());
            characterInterval = Math.max(1_000, grant.lightningLimits().characterIntervalMs());
        }
        long abilityAgeMillis = observe ? AbilityWorkLimits.MAX_OBSERVE_READ_AGE_MILLIS :
                AbilityWorkLimits.MAX_LIGHTNING_FIRST_ADMISSION_AGE_MILLIS;
        long abilityAgeNanos = TimeUnit.MILLISECONDS.toNanos(abilityAgeMillis);
        long originalAgeNanos = TimeUnit.MILLISECONDS.toNanos(
                AbilityWorkLimits.MAX_LIGHTNING_INVOCATION_AGE_MILLIS);
        if (!AbilityWorkLimits.ageWithinInclusiveNanos(nowNanos, admission.capturedAtNanos(), originalAgeNanos) ||
                !AbilityWorkLimits.ageWithinInclusiveNanos(firstAdmissionAtNanos, admission.capturedAtNanos(), originalAgeNanos) ||
                !AbilityWorkLimits.ageWithinInclusiveNanos(nowNanos, firstAdmissionAtNanos, abilityAgeNanos)) {
            throw new IllegalArgumentException("ability deadline expired or clock is ambiguous");
        }
        long abilityDeadline = Math.addExact(firstAdmissionAtNanos, abilityAgeNanos);
        long originalDeadline = Math.addExact(admission.capturedAtNanos(), originalAgeNanos);
        long deadline = Math.min(abilityDeadline, originalDeadline);
        AbilityLimitLedger.Binding requestBinding = bindingFor(admission);
        if (requestBinding == null) throw new IllegalArgumentException("invalid request binding");
        if (!binding.same(requestBinding)) throw new IllegalArgumentException("ability binding changed");
        boolean visualOnly = !(intent instanceof AbilityValidation.StrikeInput strike) || strike.visualOnly();
        return new AbilityLimitLedger.Demand(requestBinding, admission.configurationHash(), admission.serverEpochId(),
                admission.serverGeneration(), admission.gateGeneration(), admission.peerGeneration(),
                observe ? AbilityLimitLedger.Kind.OBSERVE :
                AbilityLimitLedger.Kind.LIGHTNING, admission.requesterId(), intent.targetPlayerId(),
                admission.requestId(), admission.invocationId(), operationId, grant.id(),
                admission.policyRevision(), visualOnly, placement, nanos(requesterInterval), nanos(targetInterval),
                nanos(characterInterval), deadline, canonicalIntentBytes(intent));
    }

    private static boolean canonicalOperationId(String operationId) {
        if (operationId == null || !operationId.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            return false;
        }
        try { return java.util.UUID.fromString(operationId).toString().equals(operationId); }
        catch (IllegalArgumentException malformed) { return false; }
    }

    private static AbilityPolicy.Grant selectedGrant(AbilityPolicy policy,
                                                      AbilityPolicyResolver.Decision resolution,
                                                      AbilityValidation.Input intent,
                                                      String requesterId) {
        if (policy == null) return null;
        return policy.grants().stream().filter(grant -> grant.id().equals(resolution.selectedGrantId()))
                .filter(grant -> grant.abilityId().equals(intent.abilityId()))
                .filter(grant -> grant.contractVersion() == 1)
                .filter(grant -> grant.requesterIds().contains(requesterId))
                .filter(grant -> intent.targetPlayerId().equals(requesterId) ||
                        grant.otherTargetIds().contains(intent.targetPlayerId()))
                .findFirst().orElse(null);
    }

    /**
     * Classifies unresolved pure-policy checks for this accounting-only phase. This is not an execution
     * qualification: required current facts still pass validateCurrentFacts before reserve, and every
     * deferred check still blocks any future read/start/effect boundary.
     */
    static boolean unresolvedChecksPermitAccountingHold(
            List<AbilityPolicyResolver.UnresolvedCheck> unresolvedChecks) {
        if (unresolvedChecks == null) return false;
        for (AbilityPolicyResolver.UnresolvedCheck check : unresolvedChecks) {
            switch (accountingHoldPhase(check)) {
                case REQUIRED_BEFORE_HOLD -> { return false; }
                case CHECKED_BY_HOLD, DEFERRED_AFTER_HOLD -> { }
            }
        }
        return true;
    }

    /** A null or future unclassified check is fail-closed by the default branch. */
    static AccountingHoldPhase accountingHoldPhase(AbilityPolicyResolver.UnresolvedCheck check) {
        if (check == null) return AccountingHoldPhase.REQUIRED_BEFORE_HOLD;
        return switch (check) {
            case AUTHENTICATED_REQUEST_ELIGIBILITY,
                    REQUESTER_CONNECTED,
                    CURRENT_WORLD_INSTANCE,
                    TARGET_RESOLUTION,
                    TARGET_CONNECTED_ALIVE_NON_SPECTATING,
                    SAME_DIMENSION,
                    REQUESTER_TARGET_RANGE,
                    WORLD_BORDER,
                    GRANT_REGION,
                    TARGET_CHUNK_LOADED_AND_TICKING,
                    LIGHTNING_SKY_ACCESS,
                    PROTECTED_SPAWN,
                    EXTERNAL_CLAIMS_AUTHORITY,
                    DAMAGE_NONLETHAL_QUALIFICATION -> AccountingHoldPhase.REQUIRED_BEFORE_HOLD;
            case ATOMIC_LIMIT_ADMISSION -> AccountingHoldPhase.CHECKED_BY_HOLD;
            case NORMATIVE_WORLD_IDENTITY_MAPPING,
                    OBSERVATION_FRESHNESS,
                    EXECUTION_BOUNDARY_POLICY_RECHECK,
                    DURABLE_PREPARATION,
                    TICK_THREAD_REVALIDATION,
                    NATIVE_LIGHTNING_SIDE_EFFECT_SUPPRESSION,
                    AUTHORITATIVE_OUTCOME_IDENTITY_MAPPING,
                    UNKNOWN_OUTCOME_PERSISTENCE -> AccountingHoldPhase.DEFERRED_AFTER_HOLD;
            default -> AccountingHoldPhase.REQUIRED_BEFORE_HOLD;
        };
    }

    enum AccountingHoldPhase { REQUIRED_BEFORE_HOLD, CHECKED_BY_HOLD, DEFERRED_AFTER_HOLD }

    private static AbilityWorkLimits.WorkPlan plan(AbilityValidation.Input intent) {
        if (intent instanceof AbilityValidation.ObserveInput) {
            return AbilityWorkLimits.observePlan(canonicalIntentBytes(intent), AbilityWorkLimits.MAX_RESULT_BYTES, 3);
        }
        AbilityValidation.StrikeInput strike = (AbilityValidation.StrikeInput) intent;
        return AbilityWorkLimits.lightningPlan(canonicalIntentBytes(intent), AbilityWorkLimits.MAX_RESULT_BYTES,
                3, !strike.visualOnly(), 0, false);
    }

    private static int canonicalIntentBytes(AbilityValidation.Input intent) {
        String json;
        if (intent instanceof AbilityValidation.ObserveInput observe) {
            json = "{\"abilityId\":\"" + AbilityPolicy.OBSERVE + "\",\"contractVersion\":1," +
                    "\"arguments\":{\"targetPlayerId\":\"" + observe.targetPlayerId() + "\"}}";
        } else {
            AbilityValidation.StrikeInput strike = (AbilityValidation.StrikeInput) intent;
            json = "{\"abilityId\":\"" + AbilityPolicy.LIGHTNING + "\",\"contractVersion\":1," +
                    "\"arguments\":{\"targetPlayerId\":\"" + strike.targetPlayerId() + "\",\"visualOnly\":" +
                    strike.visualOnly() + "}}";
        }
        return AbilityWorkLimits.utf8Bytes(json);
    }

    private static AbilityLimitLedger.Kind kind(AbilityValidation.Input intent) {
        return intent instanceof AbilityValidation.ObserveInput ? AbilityLimitLedger.Kind.OBSERVE :
                AbilityLimitLedger.Kind.LIGHTNING;
    }

    private static long nanos(long millis) { return Math.multiplyExact(millis, 1_000_000L); }

    private AbilityLimitLedger.Binding bindingFor(AbilityRequestAuthority.Admission admission) {
        if (admission == null || admission.binding() == null) return null;
        try {
            return new AbilityLimitLedger.Binding(admission.binding().serverId(),
                    admission.binding().worldId(), admission.binding().characterId());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean sameInput(AbilityValidation.Input left, AbilityValidation.Input right) {
        if (left == null || right == null || !left.abilityId().equals(right.abilityId()) ||
                !left.targetPlayerId().equals(right.targetPlayerId())) return false;
        return !(left instanceof AbilityValidation.StrikeInput leftStrike) ||
                right instanceof AbilityValidation.StrikeInput rightStrike &&
                        leftStrike.visualOnly() == rightStrike.visualOnly();
    }

    record Outcome(boolean held, boolean released, boolean known, AbilityLimitLedger.IdentityState knownState,
                   AbilityLimitLedger.Reason reason, Long retryAfterMillis) {
        static Outcome heldAndReleased() { return new Outcome(false, true, false, null, AbilityLimitLedger.Reason.NONE, null); }
        static Outcome heldForObservation() { return new Outcome(true, false, false, null, AbilityLimitLedger.Reason.NONE, null); }
        static Outcome known(AbilityLimitLedger.IdentityState state) {
            return new Outcome(false, false, true, state, AbilityLimitLedger.Reason.NONE, null);
        }
        static Outcome unavailable(AbilityLimitLedger.Reason reason) {
            return new Outcome(false, false, false, null, reason, null);
        }
        static Outcome unavailable(AbilityLimitLedger.Reason reason, Long retry) {
            return new Outcome(false, false, false, null, reason, retry);
        }
    }

    record Retained(AbilityRequestAuthority.Admission admission, AbilityValidation.Input intent, String operationId,
                    AbilityLimitLedger.Token token, long firstAdmissionAtNanos, boolean started) {
        Retained markStarted() {
            return new Retained(admission, intent, operationId, token, firstAdmissionAtNanos, true);
        }
    }
}
