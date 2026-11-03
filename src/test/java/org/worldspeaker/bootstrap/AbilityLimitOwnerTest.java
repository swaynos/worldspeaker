package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.ability.AbilityPolicyResolver;
import org.worldspeaker.ability.AbilityValidation;
import org.worldspeaker.compat.AbilityPolicy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AbilityLimitOwnerTest {
    private static final String REQUESTER = "00000000-0000-4000-8000-000000000101";
    private static final String EPOCH = "00000000-0000-4000-8000-000000000102";
    private static final AbilityValidation.Binding BINDING = new AbilityValidation.Binding(
            "server-test", "world-test", "character-test", "agent-test");

    @Test
    void everyUnresolvedPolicyCheckHasAnExplicitFailClosedAccountingHoldPhase() {
        var required = EnumSet.of(
                AbilityPolicyResolver.UnresolvedCheck.AUTHENTICATED_REQUEST_ELIGIBILITY,
                AbilityPolicyResolver.UnresolvedCheck.REQUESTER_CONNECTED,
                AbilityPolicyResolver.UnresolvedCheck.CURRENT_WORLD_INSTANCE,
                AbilityPolicyResolver.UnresolvedCheck.TARGET_RESOLUTION,
                AbilityPolicyResolver.UnresolvedCheck.TARGET_CONNECTED_ALIVE_NON_SPECTATING,
                AbilityPolicyResolver.UnresolvedCheck.SAME_DIMENSION,
                AbilityPolicyResolver.UnresolvedCheck.REQUESTER_TARGET_RANGE,
                AbilityPolicyResolver.UnresolvedCheck.WORLD_BORDER,
                AbilityPolicyResolver.UnresolvedCheck.GRANT_REGION,
                AbilityPolicyResolver.UnresolvedCheck.TARGET_CHUNK_LOADED_AND_TICKING,
                AbilityPolicyResolver.UnresolvedCheck.LIGHTNING_SKY_ACCESS,
                AbilityPolicyResolver.UnresolvedCheck.PROTECTED_SPAWN,
                AbilityPolicyResolver.UnresolvedCheck.EXTERNAL_CLAIMS_AUTHORITY,
                AbilityPolicyResolver.UnresolvedCheck.DAMAGE_NONLETHAL_QUALIFICATION);
        var deferred = EnumSet.of(
                AbilityPolicyResolver.UnresolvedCheck.NORMATIVE_WORLD_IDENTITY_MAPPING,
                AbilityPolicyResolver.UnresolvedCheck.OBSERVATION_FRESHNESS,
                AbilityPolicyResolver.UnresolvedCheck.EXECUTION_BOUNDARY_POLICY_RECHECK,
                AbilityPolicyResolver.UnresolvedCheck.DURABLE_PREPARATION,
                AbilityPolicyResolver.UnresolvedCheck.TICK_THREAD_REVALIDATION,
                AbilityPolicyResolver.UnresolvedCheck.NATIVE_LIGHTNING_SIDE_EFFECT_SUPPRESSION,
                AbilityPolicyResolver.UnresolvedCheck.AUTHORITATIVE_OUTCOME_IDENTITY_MAPPING,
                AbilityPolicyResolver.UnresolvedCheck.UNKNOWN_OUTCOME_PERSISTENCE);
        var checkedByHold = EnumSet.of(AbilityPolicyResolver.UnresolvedCheck.ATOMIC_LIMIT_ADMISSION);
        var classified = EnumSet.noneOf(AbilityPolicyResolver.UnresolvedCheck.class);

        for (AbilityPolicyResolver.UnresolvedCheck check : AbilityPolicyResolver.UnresolvedCheck.values()) {
            AbilityLimitOwner.AccountingHoldPhase phase = AbilityLimitOwner.accountingHoldPhase(check);
            assertNotNull(phase, check.name());
            classified.add(check);
            assertEquals(required.contains(check) ? AbilityLimitOwner.AccountingHoldPhase.REQUIRED_BEFORE_HOLD :
                            deferred.contains(check) ? AbilityLimitOwner.AccountingHoldPhase.DEFERRED_AFTER_HOLD :
                                    AbilityLimitOwner.AccountingHoldPhase.CHECKED_BY_HOLD,
                    phase, check.name());
        }
        assertEquals(EnumSet.allOf(AbilityPolicyResolver.UnresolvedCheck.class), classified);
        assertEquals(EnumSet.allOf(AbilityPolicyResolver.UnresolvedCheck.class),
                union(required, deferred, checkedByHold));
        assertEquals(AbilityLimitOwner.AccountingHoldPhase.REQUIRED_BEFORE_HOLD,
                AbilityLimitOwner.accountingHoldPhase(null), "unknown/null checks fail closed");
        assertFalse(AbilityLimitOwner.unresolvedChecksPermitAccountingHold(null));
        assertFalse(AbilityLimitOwner.unresolvedChecksPermitAccountingHold(List.of(
                AbilityPolicyResolver.UnresolvedCheck.ATOMIC_LIMIT_ADMISSION,
                AbilityPolicyResolver.UnresolvedCheck.WORLD_BORDER)));
        List<AbilityPolicyResolver.UnresolvedCheck> allowlisted = new ArrayList<>(deferred);
        allowlisted.add(AbilityPolicyResolver.UnresolvedCheck.ATOMIC_LIMIT_ADMISSION);
        assertTrue(AbilityLimitOwner.unresolvedChecksPermitAccountingHold(allowlisted));
        assertFalse(AbilityLimitOwner.unresolvedChecksPermitAccountingHold(
                java.util.Arrays.asList((AbilityPolicyResolver.UnresolvedCheck) null)));
    }

    @Test
    void isolatedOwnerDerivesDemandHoldsCompetingRequestAndReleasesForLaterAdmission() {
        AtomicLong clock = new AtomicLong(10_000_000_000L);
        AtomicBoolean current = new AtomicBoolean(true);
        AbilityLimitLedger ledger = new AbilityLimitLedger(clock::get);
        AbilityPolicy policy = observePolicy();
        SimulatedPort port = new SimulatedPort(current);
        AbilityRequestAuthority.IsolatedOwner owner = AbilityRequestAuthority.isolatedSimulationOwner(
                port, ledger, true);

        Admitted first = admit(owner, policy, clock.get(), 110);
        Admitted second = admit(owner, policy, clock.get(), 111);
        AbilityRequestAuthority.GateCheck firstCheck = AbilityRequestAuthority.evaluateGate(
                first.handle(), first.expected(), new AbilityValidation.ObserveInput(REQUESTER), clock.get());
        assertFalse(firstCheck.staleContext());
        assertFalse(firstCheck.permissionDenied());
        assertEquals(1, owner.retainedHoldsForTesting());
        assertEquals(1, ledger.snapshotForTests().liveHolds());

        AbilityRequestAuthority.GateCheck blocked = AbilityRequestAuthority.evaluateGate(
                second.handle(), second.expected(), new AbilityValidation.ObserveInput(REQUESTER), clock.get());
        assertEquals(AbilityRequestAuthority.GateCause.CONCURRENCY_LIMITED, blocked.cause());
        assertEquals(1, ledger.snapshotForTests().liveHolds(), "the competing request installs no partial hold");

        assertEquals(1, owner.releaseRetainedHoldsForTesting());
        assertEquals(0, ledger.snapshotForTests().liveHolds());
        AbilityRequestAuthority.GateCheck afterRelease = AbilityRequestAuthority.evaluateGate(
                second.handle(), second.expected(), new AbilityValidation.ObserveInput(REQUESTER), clock.get());
        assertFalse(afterRelease.staleContext());
        assertFalse(afterRelease.permissionDenied());
        assertEquals(1, owner.retainedHoldsForTesting(), "release restores pre-start allowance for a later request");
        assertEquals(1, owner.retainedHoldsForTesting());
        owner.retire(second.admission().requestIdentity());
        owner.retire(first.admission().requestIdentity());
        assertEquals(0, ledger.snapshotForTests().liveHolds());
        assertEquals(0, ledger.snapshotForTests().chargedNodes());
        assertEquals(0, port.livePacketObservations);
    }

    @Test
    void isolatedRevocationBetweenHoldAndStartReleasesWithoutCharging() {
        AtomicLong clock = new AtomicLong(20_000_000_000L);
        AtomicBoolean current = new AtomicBoolean(true);
        AbilityLimitLedger ledger = new AbilityLimitLedger(clock::get);
        SimulatedPort port = new SimulatedPort(current);
        AbilityRequestAuthority.IsolatedOwner owner = AbilityRequestAuthority.isolatedSimulationOwner(
                port, ledger, true);
        Admitted request = admit(owner, observePolicy(), clock.get(), 120);

        AbilityRequestAuthority.GateCheck check = AbilityRequestAuthority.evaluateGate(request.handle(),
                request.expected(), new AbilityValidation.ObserveInput(REQUESTER), clock.get());
        assertFalse(check.permissionDenied());
        assertEquals(1, owner.retainedHoldsForTesting());
        current.set(false);
        AbilityLimitLedger.StartResult start = owner.startFirstRetainedForTesting(clock::get);
        assertFalse(start.started());
        assertEquals(AbilityLimitLedger.Reason.STALE_TOKEN, start.reason());
        assertEquals(0, owner.retainedHoldsForTesting());
        assertEquals(0, ledger.snapshotForTests().liveHolds());
        assertEquals(0, ledger.snapshotForTests().chargedNodes());
        owner.retire(request.admission().requestIdentity());
    }

    @Test
    void retainedReadAndLightningStartUseTheirFirstAdmissionAndInclusiveNanosecondEdges() {
        assertObserveStartBoundary(TimeUnit.MILLISECONDS.toNanos(1_000) - 1, true);
        assertObserveStartBoundary(TimeUnit.MILLISECONDS.toNanos(1_000), true);
        assertObserveStartBoundary(TimeUnit.MILLISECONDS.toNanos(1_000) + 1, false);

        assertLightningStartBoundary(TimeUnit.MILLISECONDS.toNanos(5_000) - 1, true);
        assertLightningStartBoundary(TimeUnit.MILLISECONDS.toNanos(5_000), true);
        assertLightningStartBoundary(TimeUnit.MILLISECONDS.toNanos(5_000) + 1, false);
    }

    @Test
    void strictDialogueOwnerExpiryCanPrecedeInclusiveOriginalInvocationLimit() {
        long capturedAt = 30_000_000_000L;
        AtomicLong clock = new AtomicLong(capturedAt);
        AtomicBoolean current = new AtomicBoolean(true);
        AbilityLimitLedger ledger = new AbilityLimitLedger(clock::get);
        SimulatedPort port = new SimulatedPort(current, clock);
        AbilityRequestAuthority.IsolatedOwner owner = AbilityRequestAuthority.isolatedSimulationOwner(
                port, ledger, true);
        Admitted request = admit(owner, observePolicy(), capturedAt, 130);

        clock.set(capturedAt + TimeUnit.SECONDS.toNanos(15) - 1);
        AbilityRequestAuthority.GateCheck before = AbilityRequestAuthority.evaluateGate(request.handle(),
                request.expected(), new AbilityValidation.ObserveInput(REQUESTER), clock.get());
        assertFalse(before.staleContext(), "the request owner is live one nanosecond before its strict expiry");
        assertEquals(1, owner.retainedHoldsForTesting());

        clock.set(capturedAt + TimeUnit.SECONDS.toNanos(15));
        AbilityRequestAuthority.GateCheck atOwnerExpiry = AbilityRequestAuthority.evaluateGate(request.handle(),
                request.expected(), new AbilityValidation.ObserveInput(REQUESTER), clock.get());
        assertTrue(atOwnerExpiry.staleContext(),
                "the stricter live-request owner may deny at 15 seconds despite the inclusive ability age");
        assertTrue(AbilityWorkLimits.ageWithinInclusiveNanos(clock.get(), capturedAt,
                TimeUnit.SECONDS.toNanos(15)));
        owner.retire(request.admission().requestIdentity());
        assertEquals(0, ledger.snapshotForTests().chargedNodes());
    }

    @Test
    void workFactsAreValidatedBeforeReservationAndRefreshedAtIsolatedStart() {
        long capturedAt = 35_000_000_000L;
        AtomicLong reserveClock = new AtomicLong(capturedAt);
        AtomicBoolean requesterDimensionAvailable = new AtomicBoolean(false);
        SimulatedPort reservationPort = new SimulatedPort(new AtomicBoolean(true), null,
                requesterDimensionAvailable);
        AbilityLimitLedger reservationLedger = new AbilityLimitLedger(reserveClock::get);
        AbilityRequestAuthority.IsolatedOwner reservationOwner = AbilityRequestAuthority.isolatedSimulationOwner(
                reservationPort, reservationLedger, true);
        Admitted reservation = admit(reservationOwner, observePolicy(), capturedAt, 135);
        AbilityRequestAuthority.GateCheck missingAtReservation = AbilityRequestAuthority.evaluateGate(
                reservation.handle(), reservation.expected(), new AbilityValidation.ObserveInput(REQUESTER),
                reserveClock.get());
        assertEquals(AbilityRequestAuthority.GateCause.EXECUTION_UNAVAILABLE, missingAtReservation.cause());
        assertEquals(1, reservationPort.freshFactReads);
        assertEquals(0, reservationLedger.snapshotForTests().liveHolds(),
                "incomplete bounded-work facts install no reservation");
        reservationOwner.retire(reservation.admission().requestIdentity());

        AtomicLong startClock = new AtomicLong(capturedAt);
        AtomicBoolean dimensionAtStart = new AtomicBoolean(true);
        SimulatedPort startPort = new SimulatedPort(new AtomicBoolean(true), null, dimensionAtStart);
        AbilityLimitLedger startLedger = new AbilityLimitLedger(startClock::get);
        AbilityRequestAuthority.IsolatedOwner startOwner = AbilityRequestAuthority.isolatedSimulationOwner(
                startPort, startLedger, true);
        Admitted startRequest = admit(startOwner, observePolicy(), capturedAt, 136);
        AbilityRequestAuthority.GateCheck held = AbilityRequestAuthority.evaluateGate(startRequest.handle(),
                startRequest.expected(), new AbilityValidation.ObserveInput(REQUESTER), startClock.get());
        assertFalse(held.permissionDenied());
        assertEquals(1, startOwner.retainedHoldsForTesting());
        dimensionAtStart.set(false);

        AbilityLimitLedger.StartResult start = startOwner.startFirstRetainedForTesting(startClock::get);
        assertFalse(start.started());
        assertEquals(AbilityLimitLedger.Reason.INVALID_DEMAND, start.reason());
        assertEquals(2, startPort.freshFactReads,
                "the isolated start seam captures fresh work facts instead of reusing reservation facts");
        assertEquals(0, startLedger.snapshotForTests().liveHolds());
        assertEquals(0, startLedger.snapshotForTests().chargedNodes());
        startOwner.retire(startRequest.admission().requestIdentity());
    }

    private static void assertObserveStartBoundary(long startAgeNanos, boolean expectedStarted) {
        long capturedAt = 40_000_000_000L;
        long firstAdmissionAt = capturedAt + TimeUnit.SECONDS.toNanos(3);
        AtomicLong clock = new AtomicLong(capturedAt);
        AbilityLimitLedger ledger = new AbilityLimitLedger(clock::get);
        AbilityRequestAuthority.IsolatedOwner owner = AbilityRequestAuthority.isolatedSimulationOwner(
                new SimulatedPort(new AtomicBoolean(true)), ledger, true);
        Admitted request = admit(owner, observePolicy(), capturedAt, 140);

        clock.set(firstAdmissionAt);
        AbilityRequestAuthority.GateCheck held = AbilityRequestAuthority.evaluateGate(request.handle(),
                request.expected(), new AbilityValidation.ObserveInput(REQUESTER), clock.get());
        assertFalse(held.staleContext());
        assertFalse(held.permissionDenied(), "query acceptance is the origin for the read-age window");
        assertEquals(1, owner.retainedHoldsForTesting());

        clock.set(firstAdmissionAt + startAgeNanos);
        AbilityLimitLedger.StartResult start = owner.startFirstRetainedForTesting(clock::get);
        assertEquals(expectedStarted, start.started());
        if (expectedStarted) {
            assertEquals(AbilityLimitLedger.Reason.NONE, start.reason());
        } else {
            assertEquals(AbilityLimitLedger.Reason.INVALID_DEMAND, start.reason());
            assertEquals(0, ledger.snapshotForTests().chargedNodes());
            assertEquals(0, ledger.snapshotForTests().liveHolds());
        }
    }

    private static void assertLightningStartBoundary(long startAgeNanos, boolean expectedStarted) {
        long capturedAt = 50_000_000_000L;
        long firstAdmissionAt = capturedAt + TimeUnit.SECONDS.toNanos(3);
        AtomicLong clock = new AtomicLong(capturedAt);
        AbilityLimitLedger ledger = new AbilityLimitLedger(clock::get);
        AbilityRequestAuthority.IsolatedOwner owner = AbilityRequestAuthority.isolatedSimulationOwner(
                new SimulatedPort(new AtomicBoolean(true)), ledger, true);
        Admitted request = admit(owner, lightningPolicy(), capturedAt, 150);

        clock.set(firstAdmissionAt);
        AbilityRequestAuthority.GateCheck held = AbilityRequestAuthority.evaluateGate(request.handle(),
                request.expected(), new AbilityValidation.StrikeInput(REQUESTER, true), clock.get());
        assertFalse(held.staleContext());
        assertFalse(held.permissionDenied(), "the five-second action window starts at first server admission");
        assertEquals(1, owner.retainedHoldsForTesting());

        clock.set(firstAdmissionAt + startAgeNanos);
        AbilityLimitLedger.StartResult start = owner.startFirstRetainedForTesting(clock::get);
        assertEquals(expectedStarted, start.started());
        if (expectedStarted) {
            assertEquals(AbilityLimitLedger.Reason.NONE, start.reason());
        } else {
            assertEquals(AbilityLimitLedger.Reason.INVALID_DEMAND, start.reason());
            assertEquals(0, ledger.snapshotForTests().chargedNodes());
            assertEquals(0, ledger.snapshotForTests().liveHolds());
        }
    }

    private static AbilityPolicy observePolicy() {
        return new AbilityPolicy(4, List.of(new AbilityPolicy.Grant("observe-self", "character-test",
                AbilityPolicy.OBSERVE, 1, List.of(REQUESTER), List.of(), List.of("minecraft:overworld"),
                64, false, false, null)), List.of(), "REQUIRED");
    }

    private static AbilityPolicy lightningPolicy() {
        return new AbilityPolicy(4, List.of(new AbilityPolicy.Grant("lightning-self", "character-test",
                AbilityPolicy.LIGHTNING, 1, List.of(REQUESTER), List.of(), List.of("minecraft:overworld"),
                64, true, false, null)), List.of(), "NONE");
    }

    private static Admitted admit(AbilityRequestAuthority.IsolatedOwner owner, AbilityPolicy policy,
                                  long capturedAt, int suffix) {
        Object requestIdentity = new Object();
        Object invocationIdentity = new Object();
        Object config = new Object();
        Object connection = new Object();
        Object player = new Object();
        Object world = new Object();
        String requestId = id(suffix);
        String invocationId = id(suffix + 100);
        InvocationCapture capture = new InvocationCapture(UUID.fromString(invocationId), UUID.randomUUID(),
                capturedAt, 1_800_000_000_000L + suffix, null, 0, InvocationCapture.Source.SIGNED_CHAT);
        AbilityRequestAuthority.Admission admission = new AbilityRequestAuthority.Admission(requestIdentity,
                invocationIdentity, config, "b".repeat(64), BINDING, policy, policy.revision(), requestId,
                invocationId, REQUESTER, REQUESTER, EPOCH, 1, 0, 1, capturedAt, false,
                InteractionIngress.CaptureProvenance.SYNTHETIC_HARNESS, capture, connection, player, world);
        assertTrue(owner.register(requestIdentity));
        AbilityRequestAuthority.Handle handle = owner.issue(requestIdentity, admission);
        assertNotNull(handle);
        AbilityRequestAuthority.ExpectedContext expected = new AbilityRequestAuthority.ExpectedContext(
                requestIdentity, config, admission.configurationHash(), BINDING, requestId, invocationId,
                REQUESTER, REQUESTER, EPOCH, 1, 0, 1, capturedAt);
        return new Admitted(admission, handle, expected);
    }

    private static String id(int suffix) {
        return "00000000-0000-4000-8000-%012d".formatted(suffix);
    }

    @SafeVarargs
    private static <E extends Enum<E>> EnumSet<E> union(EnumSet<E>... values) {
        EnumSet<E> result = EnumSet.noneOf(values[0].iterator().next().getDeclaringClass());
        for (EnumSet<E> value : values) result.addAll(value);
        return result;
    }

    private record Admitted(AbilityRequestAuthority.Admission admission, AbilityRequestAuthority.Handle handle,
                            AbilityRequestAuthority.ExpectedContext expected) {}

    private static final class SimulatedPort implements AbilityRequestAuthority.ReadOnlyAuthorityGamePort {
        private final AtomicBoolean current;
        private final AtomicLong clock;
        private final AtomicBoolean requesterDimensionAvailable;
        private int livePacketObservations;
        private int freshFactReads;

        private SimulatedPort(AtomicBoolean current) { this(current, null, new AtomicBoolean(true)); }

        private SimulatedPort(AtomicBoolean current, AtomicLong clock) {
            this(current, clock, new AtomicBoolean(true));
        }

        private SimulatedPort(AtomicBoolean current, AtomicLong clock,
                              AtomicBoolean requesterDimensionAvailable) {
            this.current = current;
            this.clock = clock;
            this.requesterDimensionAvailable = requesterDimensionAvailable;
        }

        @Override public boolean isCurrent(AbilityRequestAuthority.Admission admission) {
            if (!current.get() || admission.captureProvenance() !=
                    InteractionIngress.CaptureProvenance.SYNTHETIC_HARNESS || admission.liveOrigin()) return false;
            if (clock == null) return true;
            try {
                long age = Math.subtractExact(clock.get(), admission.capturedAtNanos());
                return age >= 0 && age < TimeUnit.SECONDS.toNanos(15);
            } catch (ArithmeticException ambiguous) {
                return false;
            }
        }

        @Override public boolean requesterMatches(AbilityRequestAuthority.Admission admission) {
            if (admission.liveOrigin() || admission.captureProvenance() !=
                    InteractionIngress.CaptureProvenance.SYNTHETIC_HARNESS) {
                livePacketObservations++;
                return false;
            }
            return isCurrent(admission);
        }

        @Override public AbilityPolicyResolver.WorldFacts freshFacts(
                AbilityRequestAuthority.Admission admission, AbilityValidation.Input intent) {
            if (!isCurrent(admission) || intent == null) return null;
            freshFactReads++;
            return new AbilityPolicyResolver.WorldFacts(true, true, true, true, intent.targetPlayerId(),
                    true, true, true, true,
                    requesterDimensionAvailable.get() ? "minecraft:overworld" : null, "minecraft:overworld",
                    new AbilityPolicyResolver.Position(0, 64, 0), new AbilityPolicyResolver.Position(0, 64, 0),
                    new AbilityPolicyResolver.Border(-100, 100, -100, 100), true, true, true);
        }
    }
}
