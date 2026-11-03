package org.worldspeaker.bootstrap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * In-memory accounting state only. Every decision and mutation is serialized by one bounded monitor;
 * no game, provider, model, persistence, scheduler, or callback is reachable from this class.
 */
public final class AbilityLimitLedger {
    public static final int MAX_LIVE_HOLDS = 17;
    public static final int MAX_REQUESTERS_PER_KIND = 32;
    public static final int MAX_LIGHTNING_TARGETS = 256;
    public static final int MAX_QUERY_IDS_PER_LIVE_REQUEST = 32;
    public static final int MAX_IDENTITY_RECORDS = 1_024;
    public static final int MAX_METADATA_BYTES = 8 * 1_024 * 1_024;
    public static final long IDLE_RECLAIM_MILLIS = 300_000;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long MAX_CONFIGURED_INTERVAL_NANOS = 300_000_000_000L;
    private static final long CLEANUP_BUDGET = 2;

    private final Object lock = new Object();
    private final LongSupplier monotonicNanos;
    private final IdentityHashMap<Token, Hold> holds = new IdentityHashMap<>();
    private final ArrayDeque<Token> expiryQueue = new ArrayDeque<>();
    private final LinkedHashMap<NodeKey, RateNode> nodes = new LinkedHashMap<>();
    private final ArrayDeque<NodeKey> cleanupQueue = new ArrayDeque<>();
    private final LinkedHashMap<IdentityKey, IdentityRecord> identities = new LinkedHashMap<>();
    private final LinkedHashMap<InvocationKey, IdentityRecord> invocations = new LinkedHashMap<>();
    private final Map<String, Integer> queryCountsByRequest = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<IdentityKey, Boolean>> identitiesByRequest = new LinkedHashMap<>();
    private final java.util.Set<String> retiredRequestsWithLiveHolds = new java.util.HashSet<>();
    private final Map<InvocationKey, Token> invocationHolds = new LinkedHashMap<>();

    private Binding binding;
    private boolean bindingClosed;
    private boolean clockFault;
    private boolean hasClockSample;
    private long lastClockSample;
    private long nextSequence;
    private int requesterNodeCountObserve;
    private int requesterNodeCountLightning;
    private int targetNodeCountLightning;
    private int metadataBytes;
    private int reservedConsequentialRecords;
    private int reservedConsequentialBytes;
    private long successfulReservations;
    private long preStartReleases;

    AbilityLimitLedger(LongSupplier monotonicNanos) {
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
    }

    /** A process-lifetime ledger. Its accounting is deliberately not persisted across JVM restart. */
    public static AbilityLimitLedger processOwnedLedger() {
        return new AbilityLimitLedger(System::nanoTime);
    }

    /** Bind to the stable configured server/world/character tuple, not a transient server object. */
    boolean bind(String serverId, String worldId, String characterId) {
        Binding requested = new Binding(serverId, worldId, characterId);
        synchronized (lock) {
            if (bindingClosed) return false;
            if (binding == null) {
                binding = requested;
                return true;
            }
            if (!binding.same(requested)) {
                // Do not flush, migrate, or reinterpret any old charge or uncertainty.
                bindingClosed = true;
                return false;
            }
            return true;
        }
    }

    boolean isBoundTo(Binding requested) {
        synchronized (lock) { return !bindingClosed && binding != null && binding.same(requested); }
    }

    ReserveResult reserve(Demand demand, Object ownerIdentity) {
        if (demand == null || ownerIdentity == null) return ReserveResult.denied(Reason.INVALID_DEMAND, null);
        synchronized (lock) {
            long now = sampleClockLocked();
            cleanupLocked(now, CLEANUP_BUDGET);
            expireUnstartedLocked(now, CLEANUP_BUDGET);
            if (bindingClosed || binding == null || !binding.same(demand.binding())) {
                return ReserveResult.denied(Reason.BINDING_CLOSED, null);
            }
            if (clockFault) return ReserveResult.denied(Reason.CLOCK_UNSAFE, null);
            if (!demand.valid() || now > demand.deadlineAtNanos()) {
                return ReserveResult.denied(now > demand.deadlineAtNanos() ? Reason.DEADLINE_EXPIRED :
                        Reason.INVALID_DEMAND, null);
            }

            IdentityKey identityKey = new IdentityKey(demand.kind(), demand.identityId());
            IdentityRecord known = identities.get(identityKey);
            if (known != null) {
                return known.matches(demand) ? ReserveResult.known(known.state()) :
                        ReserveResult.denied(Reason.IDENTITY_CONFLICT, null);
            }
            if (demand.kind() == Kind.LIGHTNING) {
                InvocationKey invocationKey = new InvocationKey(demand.invocationId());
                IdentityRecord priorInvocation = invocations.get(invocationKey);
                if (priorInvocation != null) {
                    return priorInvocation.sameEffect(demand) ? ReserveResult.known(priorInvocation.state()) :
                            ReserveResult.denied(Reason.IDENTITY_CONFLICT, null);
                }
                Token priorHold = invocationHolds.get(invocationKey);
                if (priorHold != null) {
                    Hold prior = holds.get(priorHold);
                    return prior != null && prior.demand().sameEffect(demand)
                            ? ReserveResult.known(IdentityState.HELD)
                            : ReserveResult.denied(Reason.IDENTITY_CONFLICT, null);
                }
            }

            if (holds.size() >= MAX_LIVE_HOLDS) return ReserveResult.denied(Reason.CAPACITY, null);
            long recordsInUse = (long) identities.size() + invocations.size() + reservedConsequentialRecords;
            // Lightning reserves identity and possible invocation/consequence records as one transaction.
            long recordCost = demand.kind() == Kind.LIGHTNING ? 2 : 1;
            if (recordsInUse + recordCost > MAX_IDENTITY_RECORDS) {
                return ReserveResult.denied(Reason.CAPACITY, null);
            }
            if (demand.kind() == Kind.OBSERVE &&
                    queryCountsByRequest.getOrDefault(demand.requestId(), 0) >= MAX_QUERY_IDS_PER_LIVE_REQUEST) {
                return ReserveResult.denied(Reason.CAPACITY, null);
            }

            NodeKey requesterKey = NodeKey.requester(demand.kind(), demand.requesterId());
            NodeKey characterKey = NodeKey.character(demand.kind(), binding.characterId());
            NodeKey targetKey = demand.kind() == Kind.LIGHTNING ?
                    NodeKey.target(demand.targetId()) : null;
            RateNode requester = nodes.get(requesterKey);
            RateNode character = nodes.get(characterKey);
            RateNode target = targetKey == null ? null : nodes.get(targetKey);
            Reason rateFailure = rateFailure(now, requester, demand.requesterIntervalNanos(), Reason.REQUESTER_RATE);
            if (rateFailure == null) rateFailure = rateFailure(now, character,
                    demand.characterIntervalNanos(), Reason.CHARACTER_RATE);
            if (rateFailure == null && demand.kind() == Kind.LIGHTNING) {
                rateFailure = rateFailure(now, target, demand.targetIntervalNanos(), Reason.TARGET_RATE);
            }
            if (rateFailure != null) return ReserveResult.denied(rateFailure,
                    retryAfterMillis(now, rateFailure == Reason.REQUESTER_RATE ? requester :
                            rateFailure == Reason.TARGET_RATE ? target : character,
                            intervalFor(rateFailure, demand)));

            int requesterUse = usage(requester);
            int characterUse = usage(character);
            if (demand.kind() == Kind.OBSERVE &&
                    (requesterUse >= 1 || characterUse >= 16)) {
                return ReserveResult.denied(Reason.CONCURRENCY_LIMITED, null);
            }
            if (demand.kind() == Kind.LIGHTNING && characterUse >= 1) {
                return ReserveResult.denied(Reason.CONCURRENCY_LIMITED, null);
            }
            if (target != null && target.unknownPins > 0) {
                return ReserveResult.denied(Reason.CONCURRENCY_LIMITED, null);
            }
            if (requester != null && requester.unknownPins > 0) {
                return ReserveResult.denied(Reason.CONCURRENCY_LIMITED, null);
            }

            int identityBytes = demand.identityMetadataBytes();
            int holdBytes = demand.holdMetadataBytes();
            int consequenceBytes = demand.kind() == Kind.LIGHTNING ? demand.consequenceMetadataBytes() : 0;
            final int nextMetadataBytes;
            final int nextReservedConsequentialBytes;
            final int nextQueryCount;
            try {
                nextMetadataBytes = checkedMetadataAdd(metadataBytes,
                        checkedMetadataAdd(identityBytes, holdBytes));
                nextReservedConsequentialBytes = checkedMetadataAdd(reservedConsequentialBytes, consequenceBytes);
                nextQueryCount = demand.kind() == Kind.OBSERVE
                        ? Math.addExact(queryCountsByRequest.getOrDefault(demand.requestId(), 0), 1) : 0;
            } catch (ArithmeticException overflow) {
                return ReserveResult.denied(Reason.CAPACITY, null);
            }
            if (!hasNodeCapacity(demand.kind(), requesterKey, targetKey) ||
                    (long) nextMetadataBytes + nextReservedConsequentialBytes > MAX_METADATA_BYTES) {
                return ReserveResult.denied(Reason.CAPACITY, null);
            }

            long sequence;
            try { sequence = Math.incrementExact(nextSequence); }
            catch (ArithmeticException exhausted) { return ReserveResult.denied(Reason.CAPACITY, null); }
            Token token = new Token(this, ownerIdentity, sequence);
            ensureNode(requesterKey, requester);
            ensureNode(characterKey, character);
            if (targetKey != null) ensureNode(targetKey, target);
            RateNode currentRequester = nodes.get(requesterKey);
            RateNode currentCharacter = nodes.get(characterKey);
            RateNode currentTarget = targetKey == null ? null : nodes.get(targetKey);
            currentRequester.reserved++;
            currentCharacter.reserved++;
            if (currentTarget != null) currentTarget.reserved++;
            Hold hold = new Hold(token, ownerIdentity, demand, requesterKey, characterKey, targetKey,
                    identityBytes, holdBytes, consequenceBytes);
            holds.put(token, hold);
            expiryQueue.addLast(token);
            identities.put(identityKey, IdentityRecord.from(demand, IdentityState.HELD, identityBytes));
            identitiesByRequest.computeIfAbsent(demand.requestId(), ignored -> new LinkedHashMap<>())
                    .put(identityKey, Boolean.TRUE);
            metadataBytes = nextMetadataBytes;
            if (demand.kind() == Kind.OBSERVE) {
                queryCountsByRequest.put(demand.requestId(), nextQueryCount);
            } else {
                reservedConsequentialRecords++;
                reservedConsequentialBytes = nextReservedConsequentialBytes;
                invocationHolds.put(new InvocationKey(demand.invocationId()), token);
            }
            nextSequence = sequence;
            successfulReservations = incrementSaturated(successfulReservations);
            return ReserveResult.reserved(token);
        }
    }

    StartResult consume(Token token, Object ownerIdentity, Demand currentDemand) {
        synchronized (lock) {
            long now = sampleClockLocked();
            cleanupLocked(now, CLEANUP_BUDGET);
            if (clockFault) return StartResult.denied(Reason.CLOCK_UNSAFE);
            Hold hold = ownedHold(token, ownerIdentity);
            if (hold == null || hold.started()) return StartResult.denied(Reason.STALE_TOKEN);
            if (currentDemand == null || !hold.demand().sameIntent(currentDemand) ||
                    !hold.demand().sameBindingAndRequest(currentDemand)) {
                releaseLocked(hold, IdentityState.RELEASED);
                return StartResult.denied(Reason.IDENTITY_CONFLICT);
            }
            if (bindingClosed || binding == null || !binding.same(currentDemand.binding())) {
                releaseLocked(hold, IdentityState.RELEASED);
                return StartResult.denied(Reason.BINDING_CLOSED);
            }
            if (now > hold.demand().deadlineAtNanos() || now > currentDemand.deadlineAtNanos()) {
                releaseLocked(hold, IdentityState.EXPIRED);
                return StartResult.denied(Reason.DEADLINE_EXPIRED);
            }
            RateNode requester = nodes.get(hold.requesterKey());
            RateNode character = nodes.get(hold.characterKey());
            RateNode target = hold.targetKey() == null ? null : nodes.get(hold.targetKey());
            Reason rateFailure = rateFailure(now, requester, currentDemand.requesterIntervalNanos(),
                    Reason.REQUESTER_RATE);
            if (rateFailure == null) rateFailure = rateFailure(now, character,
                    currentDemand.characterIntervalNanos(), Reason.CHARACTER_RATE);
            if (rateFailure == null && target != null) rateFailure = rateFailure(now, target,
                    currentDemand.targetIntervalNanos(), Reason.TARGET_RATE);
            if (rateFailure != null) {
                releaseLocked(hold, IdentityState.RELEASED);
                return StartResult.denied(rateFailure);
            }

            int nextMetadataBytes = metadataBytes;
            if (currentDemand.kind() == Kind.LIGHTNING) {
                long recordsInUse = (long) identities.size() + invocations.size() + reservedConsequentialRecords;
                try { nextMetadataBytes = checkedMetadataAdd(metadataBytes, currentDemand.consequenceMetadataBytes()); }
                catch (ArithmeticException overflow) {
                    releaseLocked(hold, IdentityState.RELEASED);
                    return StartResult.denied(Reason.CAPACITY);
                }
                long nextReservedBytes = (long) reservedConsequentialBytes - hold.reservedConsequenceBytes();
                if (recordsInUse > MAX_IDENTITY_RECORDS || nextReservedBytes < 0 ||
                        (long) nextMetadataBytes + nextReservedBytes > MAX_METADATA_BYTES) {
                    releaseLocked(hold, IdentityState.RELEASED);
                    return StartResult.denied(Reason.CAPACITY);
                }
            }

            IdentityRecord identity = identities.get(new IdentityKey(currentDemand.kind(), currentDemand.identityId()));
            if (identity == null || !identity.matches(currentDemand)) {
                releaseLocked(hold, IdentityState.RELEASED);
                return StartResult.denied(Reason.IDENTITY_CONFLICT);
            }
            if (currentDemand.kind() == Kind.LIGHTNING) {
                InvocationKey key = new InvocationKey(currentDemand.invocationId());
                IdentityRecord prior = invocations.get(key);
                if (prior != null) {
                    releaseLocked(hold, prior.sameEffect(currentDemand) ? prior.state() : IdentityState.RELEASED);
                    return StartResult.denied(Reason.IDENTITY_CONFLICT);
                }
            }

            decrementReserved(hold);
            requester.inFlight++;
            character.inFlight++;
            if (target != null) target.inFlight++;
            charge(requester, now, currentDemand.requesterIntervalNanos());
            charge(character, now, currentDemand.characterIntervalNanos());
            if (target != null) charge(target, now, currentDemand.targetIntervalNanos());
            hold.markStarted(now);
            identity.setState(IdentityState.STARTED);
            if (currentDemand.kind() == Kind.LIGHTNING) {
                identity.markConsequential();
                invocationHolds.remove(new InvocationKey(currentDemand.invocationId()), token);
                reservedConsequentialRecords--;
                reservedConsequentialBytes -= hold.reservedConsequenceBytes();
                IdentityRecord consequence = IdentityRecord.consequential(currentDemand, IdentityState.STARTED,
                        currentDemand.consequenceMetadataBytes());
                invocations.put(new InvocationKey(currentDemand.invocationId()), consequence);
                metadataBytes = nextMetadataBytes;
                if (reservedConsequentialRecords < 0 || reservedConsequentialBytes < 0) {
                    throw new IllegalStateException("consequential reservation underflow");
                }
            }
            return StartResult.started(now, hold.token().sequence());
        }
    }

    boolean release(Token token, Object ownerIdentity) {
        synchronized (lock) {
            Hold hold = ownedHold(token, ownerIdentity);
            if (hold == null || hold.started()) return false;
            releaseLocked(hold, IdentityState.RELEASED);
            return true;
        }
    }

    SettlementResult settle(Token token, Object ownerIdentity, Settlement settlement,
                            boolean taskCompletionProven) {
        if (settlement == null) return SettlementResult.STALE;
        synchronized (lock) {
            Hold hold = ownedHold(token, ownerIdentity);
            if (hold == null || !hold.started()) return SettlementResult.STALE;
            RateNode requester = nodes.get(hold.requesterKey());
            RateNode character = nodes.get(hold.characterKey());
            RateNode target = hold.targetKey() == null ? null : nodes.get(hold.targetKey());
            if (settlement == Settlement.UNKNOWN) {
                if (!hold.unknownRecorded()) {
                    if (requester != null) requester.unknownPins++;
                    if (target != null) target.unknownPins++;
                    hold.markUnknownRecorded();
                }
                IdentityRecord identity = identities.get(new IdentityKey(hold.demand().kind(),
                        hold.demand().identityId()));
                if (identity != null) identity.setState(IdentityState.UNKNOWN);
                if (hold.demand().kind() == Kind.LIGHTNING) {
                    IdentityRecord consequence = invocations.get(new InvocationKey(hold.demand().invocationId()));
                    if (consequence != null) consequence.setState(IdentityState.UNKNOWN);
                }
            } else {
                IdentityRecord identity = identities.get(new IdentityKey(hold.demand().kind(),
                        hold.demand().identityId()));
                if (identity != null) identity.setState(settlement == Settlement.CONFIRMED
                        ? IdentityState.COMPLETED : IdentityState.FAILED);
                if (hold.demand().kind() == Kind.LIGHTNING) {
                    IdentityRecord consequence = invocations.get(new InvocationKey(hold.demand().invocationId()));
                    if (consequence != null) consequence.setState(settlement == Settlement.CONFIRMED
                            ? IdentityState.COMPLETED : IdentityState.FAILED);
                }
            }
            if (taskCompletionProven) {
                if (requester != null && requester.inFlight > 0) requester.inFlight--;
                if (character != null && character.inFlight > 0) character.inFlight--;
                if (target != null && target.inFlight > 0) target.inFlight--;
                removeStartedHold(hold);
                finishRetiredRequestIfComplete(hold.demand().requestId());
                return SettlementResult.SETTLED;
            }
            return settlement == Settlement.UNKNOWN ? SettlementResult.UNKNOWN_PINNED :
                    SettlementResult.IN_FLIGHT_RETAINED;
        }
    }

    /** Remove only live query/action identity metadata when its owner request is retired; charges remain. */
    void retireRequest(String requestId) {
        if (requestId == null) return;
        synchronized (lock) {
            if (hasHoldForRequest(requestId)) {
                retiredRequestsWithLiveHolds.add(requestId);
                return;
            }
            removeRequestIdentities(requestId);
        }
    }

    /** Bounded cleanup; at most {@code budget} rows are inspected. It never removes charges or pins. */
    int cleanupIncremental(int budget) {
        if (budget < 0) return 0;
        synchronized (lock) {
            long now = sampleClockLocked();
            return cleanupLocked(now, Math.min(budget, 32));
        }
    }

    /** Identity lookup before facts/placement are refreshed; it never creates a reservation. */
    KnownResult lookupKnown(Kind kind, String identityId, String invocationId, String requestId,
                            Binding requestedBinding, String requesterId, String targetId, boolean visualOnly,
                            String configurationHash, String serverEpochId, long serviceGeneration,
                            long gateGeneration, long peerGeneration) {
        synchronized (lock) {
            if (bindingClosed || clockFault) {
                return new KnownResult(false, false, Reason.CLOCK_UNSAFE, null);
            }
            IdentityRecord record = identities.get(new IdentityKey(kind, identityId));
            if (record != null) {
                return record.sameRequestIntent(requestId, requestedBinding, requesterId, targetId, invocationId,
                        visualOnly, configurationHash, serverEpochId, serviceGeneration, gateGeneration, peerGeneration)
                        ? new KnownResult(true, true, Reason.NONE, record.state())
                        : new KnownResult(true, false, Reason.IDENTITY_CONFLICT, null);
            }
            if (kind == Kind.LIGHTNING && invocationId != null) {
                IdentityRecord prior = invocations.get(new InvocationKey(invocationId));
                if (prior == null) {
                    Token heldToken = invocationHolds.get(new InvocationKey(invocationId));
                    Hold held = heldToken == null ? null : holds.get(heldToken);
                    prior = held == null ? null : identities.get(new IdentityKey(Kind.LIGHTNING,
                            held.demand().identityId()));
                }
                if (prior != null) {
                    return prior.sameRequestIntent(requestId, requestedBinding, requesterId, targetId, invocationId,
                            visualOnly, configurationHash, serverEpochId, serviceGeneration, gateGeneration, peerGeneration)
                            ? new KnownResult(true, true, Reason.NONE, prior.state())
                            : new KnownResult(true, false, Reason.IDENTITY_CONFLICT, null);
                }
            }
            return new KnownResult(false, false, Reason.NONE, null);
        }
    }

    boolean isActive(Token token, Object ownerIdentity) {
        synchronized (lock) { return ownedHold(token, ownerIdentity) != null; }
    }

    private boolean hasHoldForRequest(String requestId) {
        for (Hold hold : holds.values()) {
            if (hold.demand().requestId().equals(requestId)) return true;
        }
        return false;
    }

    private void finishRetiredRequestIfComplete(String requestId) {
        if (retiredRequestsWithLiveHolds.contains(requestId) && !hasHoldForRequest(requestId)) {
            retiredRequestsWithLiveHolds.remove(requestId);
            removeRequestIdentities(requestId);
        }
    }

    private void removeRequestIdentities(String requestId) {
        LinkedHashMap<IdentityKey, Boolean> requestIdentities = identitiesByRequest.remove(requestId);
        if (requestIdentities == null) return;
        int removedQueries = 0;
        for (IdentityKey key : requestIdentities.keySet()) {
            IdentityRecord record = identities.get(key);
            if (record == null || record.consequential()) continue;
            identities.remove(key);
            metadataBytes -= record.metadataBytes();
            if (record.kind() == Kind.OBSERVE) removedQueries++;
        }
        if (removedQueries > 0) {
            int left = queryCountsByRequest.getOrDefault(requestId, 0) - removedQueries;
            if (left <= 0) queryCountsByRequest.remove(requestId);
            else queryCountsByRequest.put(requestId, left);
        }
        if (metadataBytes < 0) throw new IllegalStateException("metadata byte underflow");
    }

    Snapshot snapshotForTests() {
        synchronized (lock) {
            int reserved = 0;
            int inFlight = 0;
            for (Hold hold : holds.values()) {
                if (hold.started()) inFlight++;
                else reserved++;
            }
            return new Snapshot(binding != null, bindingClosed, clockFault, reserved, inFlight,
                    requesterNodeCountObserve, requesterNodeCountLightning, targetNodeCountLightning,
                    identities.size(), invocations.size(), metadataBytes + reservedConsequentialBytes,
                    chargedCount(), unknownPinCount());
        }
    }

    HoldCounters holdCountersForTests() {
        synchronized (lock) { return new HoldCounters(successfulReservations, preStartReleases); }
    }

    private long sampleClockLocked() {
        long now;
        try { now = monotonicNanos.getAsLong(); }
        catch (RuntimeException unavailable) { clockFault = true; return hasClockSample ? lastClockSample : 0; }
        if (hasClockSample && now < lastClockSample) {
            // Backward movement and signed wrap are ambiguous; neither can refund a charge.
            clockFault = true;
        }
        hasClockSample = true;
        lastClockSample = now;
        return now;
    }

    private int cleanupLocked(long now, long budget) {
        if (clockFault || budget <= 0) return 0;
        int checked = 0;
        while (checked < budget && !cleanupQueue.isEmpty()) {
            NodeKey key = cleanupQueue.removeFirst();
            RateNode node = nodes.get(key);
            if (node == null) continue;
            node.queuedForCleanup = false;
            checked++;
            if (node.reserved == 0 && node.inFlight == 0 && node.unknownPins == 0 && node.hasCharge) {
                long idle = safeElapsed(now, node.lastChargedAt);
                if (idle >= nanos(IDLE_RECLAIM_MILLIS)) {
                    removeNode(key, node);
                    continue;
                }
            } else if (node.reserved == 0 && node.inFlight == 0 && node.unknownPins == 0 && !node.hasCharge) {
                removeNode(key, node);
                continue;
            }
            queueForCleanup(key, node);
        }
        return checked;
    }

    private void expireUnstartedLocked(long now, long budget) {
        if (clockFault || budget <= 0) return;
        int checked = 0;
        while (!expiryQueue.isEmpty() && checked < budget) {
            Token token = expiryQueue.removeFirst();
            Hold hold = holds.get(token);
            if (hold == null || hold.started()) continue;
            checked++;
            if (now > hold.demand().deadlineAtNanos()) {
                decrementReserved(hold);
                holds.remove(token);
                removeProvisionalIdentity(hold, IdentityState.EXPIRED);
                releaseInvocationHold(hold);
                preStartReleases = incrementSaturated(preStartReleases);
                dropUnchargedEmptyNode(hold.requesterKey());
                dropUnchargedEmptyNode(hold.characterKey());
                if (hold.targetKey() != null) dropUnchargedEmptyNode(hold.targetKey());
                finishRetiredRequestIfComplete(hold.demand().requestId());
            } else {
                expiryQueue.addLast(token);
            }
        }
    }

    private boolean hasNodeCapacity(Kind kind, NodeKey requesterKey, NodeKey targetKey) {
        if (!nodes.containsKey(requesterKey)) {
            int current = kind == Kind.OBSERVE ? requesterNodeCountObserve : requesterNodeCountLightning;
            if (current >= MAX_REQUESTERS_PER_KIND) return false;
        }
        if (targetKey != null && !nodes.containsKey(targetKey) && targetNodeCountLightning >= MAX_LIGHTNING_TARGETS) {
            return false;
        }
        return true;
    }

    private void ensureNode(NodeKey key, RateNode existing) {
        if (existing != null) return;
        RateNode created = new RateNode(key);
        nodes.put(key, created);
        if (key.type() == NodeType.REQUESTER) {
            if (key.kind() == Kind.OBSERVE) requesterNodeCountObserve++;
            else requesterNodeCountLightning++;
        } else if (key.type() == NodeType.TARGET) {
            targetNodeCountLightning++;
        }
        queueForCleanup(key, created);
    }

    private void queueForCleanup(NodeKey key, RateNode node) {
        if (node != null && !node.queuedForCleanup) {
            node.queuedForCleanup = true;
            cleanupQueue.addLast(key);
        }
    }

    private void removeNode(NodeKey key, RateNode node) {
        if (!nodes.remove(key, node)) return;
        if (key.type() == NodeType.REQUESTER) {
            if (key.kind() == Kind.OBSERVE) requesterNodeCountObserve--;
            else requesterNodeCountLightning--;
        } else if (key.type() == NodeType.TARGET) targetNodeCountLightning--;
        // A stale queue key is harmless and is discarded on its bounded future visit.
    }

    private void dropUnchargedEmptyNode(NodeKey key) {
        RateNode node = nodes.get(key);
        if (node != null && node.reserved == 0 && node.inFlight == 0 && node.unknownPins == 0 && !node.hasCharge) {
            queueForCleanup(key, node);
        }
    }

    private Reason rateFailure(long now, RateNode node, long currentIntervalNanos, Reason rateReason) {
        if (node == null || !node.hasCharge) return null;
        long interval = Math.max(node.lastIntervalNanos, currentIntervalNanos);
        long elapsed = safeElapsed(now, node.lastChargedAt);
        if (elapsed < 0 || elapsed < interval) return rateReason;
        return null;
    }

    private long intervalFor(Reason reason, Demand demand) {
        return switch (reason) {
            case REQUESTER_RATE -> demand.requesterIntervalNanos();
            case TARGET_RATE -> demand.targetIntervalNanos();
            default -> demand.characterIntervalNanos();
        };
    }

    private Long retryAfterMillis(long now, RateNode node, long currentIntervalNanos) {
        if (node == null || !node.hasCharge || clockFault) return null;
        long interval = Math.max(node.lastIntervalNanos, currentIntervalNanos);
        long elapsed = safeElapsed(now, node.lastChargedAt);
        if (elapsed < 0 || elapsed >= interval) return null;
        long remaining = interval - elapsed;
        long millis = 1 + (remaining - 1) / NANOS_PER_MILLI;
        return millis <= 30_000 ? millis : null;
    }

    private int usage(RateNode node) {
        return node == null ? 0 : node.reserved + node.inFlight;
    }

    private void charge(RateNode node, long now, long intervalNanos) {
        node.hasCharge = true;
        node.lastChargedAt = now;
        node.lastIntervalNanos = intervalNanos;
        queueForCleanup(node.key, node);
    }

    private Hold ownedHold(Token token, Object ownerIdentity) {
        if (token == null || token.ledger() != this || token.ownerIdentity() != ownerIdentity) return null;
        Hold hold = holds.get(token);
        return hold != null && hold.ownerIdentity() == ownerIdentity ? hold : null;
    }

    private void decrementReserved(Hold hold) {
        decrement(nodes.get(hold.requesterKey()), true);
        decrement(nodes.get(hold.characterKey()), true);
        if (hold.targetKey() != null) decrement(nodes.get(hold.targetKey()), true);
    }

    private static void decrement(RateNode node, boolean reserved) {
        if (node == null) return;
        if (reserved) {
            if (node.reserved <= 0) throw new IllegalStateException("reservation underflow");
            node.reserved--;
        } else {
            if (node.inFlight <= 0) throw new IllegalStateException("in-flight underflow");
            node.inFlight--;
        }
    }

    private void releaseLocked(Hold hold, IdentityState state) {
        if (hold.started()) return;
        decrementReserved(hold);
        holds.remove(hold.token());
        expiryQueue.remove(hold.token());
        removeProvisionalIdentity(hold, state);
        releaseInvocationHold(hold);
        preStartReleases = incrementSaturated(preStartReleases);
        dropUnchargedEmptyNode(hold.requesterKey());
        dropUnchargedEmptyNode(hold.characterKey());
        if (hold.targetKey() != null) dropUnchargedEmptyNode(hold.targetKey());
        finishRetiredRequestIfComplete(hold.demand().requestId());
    }

    private void removeProvisionalIdentity(Hold hold, IdentityState state) {
        IdentityKey identityKey = new IdentityKey(hold.demand().kind(), hold.demand().identityId());
        IdentityRecord identity = identities.get(identityKey);
        if (identity == null) return;
        identity.setState(state);
        metadataBytes -= hold.holdMetadataBytes();
    }

    private void releaseInvocationHold(Hold hold) {
        if (hold.demand().kind() != Kind.LIGHTNING) return;
        invocationHolds.remove(new InvocationKey(hold.demand().invocationId()), hold.token());
        reservedConsequentialRecords--;
        reservedConsequentialBytes -= hold.reservedConsequenceBytes();
        if (reservedConsequentialRecords < 0 || reservedConsequentialBytes < 0) {
            throw new IllegalStateException("consequential hold underflow");
        }
    }

    private void removeStartedHold(Hold hold) {
        holds.remove(hold.token());
        expiryQueue.remove(hold.token());
        metadataBytes -= hold.holdMetadataBytes();
        if (metadataBytes < 0) throw new IllegalStateException("metadata byte underflow");
    }

    private long chargedCount() {
        long count = 0;
        for (RateNode node : nodes.values()) if (node.hasCharge) count++;
        return count;
    }

    private long unknownPinCount() {
        long count = 0;
        for (RateNode node : nodes.values()) count += node.unknownPins;
        return count;
    }

    private static long safeElapsed(long now, long then) {
        try { return Math.subtractExact(now, then); }
        catch (ArithmeticException ambiguous) { return -1; }
    }

    private static long incrementSaturated(long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }

    private static long nanos(long millis) {
        try { return Math.multiplyExact(millis, NANOS_PER_MILLI); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("duration overflow", overflow); }
    }

    /** Checked metadata accounting used before any reservation state is committed. */
    static int checkedMetadataAdd(int current, int addition) {
        if (current < 0 || addition < 0) throw new ArithmeticException("negative metadata byte count");
        return Math.addExact(current, addition);
    }

    public enum Kind { OBSERVE, LIGHTNING }
    public enum Reason {
        NONE, REQUESTER_RATE, TARGET_RATE, CHARACTER_RATE, CONCURRENCY_LIMITED,
        DEADLINE_EXPIRED, IDENTITY_CONFLICT, STALE_TOKEN, CLOCK_UNSAFE,
        BINDING_CLOSED, CAPACITY, INVALID_DEMAND, CANCELLED, EXECUTION_UNAVAILABLE
    }
    public enum IdentityState { HELD, STARTED, COMPLETED, FAILED, UNKNOWN, RELEASED, EXPIRED }
    public enum Settlement { CONFIRMED, FAILED, UNKNOWN }
    public enum SettlementResult { SETTLED, UNKNOWN_PINNED, IN_FLIGHT_RETAINED, STALE }

    public static final class Token {
        private final AbilityLimitLedger ledger;
        private final Object ownerIdentity;
        private final long sequence;
        private Token(AbilityLimitLedger ledger, Object ownerIdentity, long sequence) {
            this.ledger = ledger;
            this.ownerIdentity = ownerIdentity;
            this.sequence = sequence;
        }
        private AbilityLimitLedger ledger() { return ledger; }
        private Object ownerIdentity() { return ownerIdentity; }
        long sequence() { return sequence; }
    }

    public record Snapshot(boolean bound, boolean bindingClosed, boolean clockFault,
                           int liveHolds, int inFlight, int observeRequesterNodes,
                           int lightningRequesterNodes, int lightningTargetNodes,
                           int identityRecords, int invocationRecords, int metadataBytes,
                           long chargedNodes, long unknownPins) {}

    record HoldCounters(long successfulReservations, long preStartReleases) {}

    record KnownResult(boolean known, boolean matches, Reason reason, IdentityState state) {}

    record Binding(String serverId, String worldId, String characterId) {
        Binding {
            if (!stable(serverId) || !stable(worldId) || !stable(characterId)) {
                throw new IllegalArgumentException("invalid configured ability binding");
            }
        }
        boolean same(Binding other) {
            return other != null && serverId.equals(other.serverId) &&
                    worldId.equals(other.worldId) && characterId.equals(other.characterId);
        }
        private static boolean stable(String value) {
            return value != null && value.matches("[A-Za-z0-9._:-]{1,128}");
        }
    }

    /** deadlineAtNanos is an inclusive upper edge; holds expire only after that sample. */
    record Demand(Binding binding, String configurationHash, String serverEpochId,
                  long serviceGeneration, long gateGeneration, long peerGeneration,
                  Kind kind, String requesterId, String targetId,
                  String requestId, String invocationId, String identityId,
                  String selectedGrantId, long policyRevision,
                  boolean visualOnly, AbilityWorkLimits.Placement placement,
                  long requesterIntervalNanos, long targetIntervalNanos, long characterIntervalNanos,
                  long deadlineAtNanos, int encodedIntentBytes) {
        Demand {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(kind, "kind");
        }
        boolean valid() {
            return configurationHash != null && configurationHash.matches("[a-f0-9]{64}") &&
                    canonicalUuid(serverEpochId) && serviceGeneration > 0 && gateGeneration >= 0 && peerGeneration > 0 &&
                    canonicalUuid(requesterId) && canonicalUuid(targetId) && canonicalUuid(invocationId) &&
                    canonicalUuid(requestId) && canonicalUuid(identityId) &&
                    selectedGrantId != null && selectedGrantId.matches("[A-Za-z0-9._:-]{1,128}") &&
                    policyRevision >= 0 &&
                    validIntervals() &&
                    (kind == Kind.OBSERVE ? visualOnly && placement == null : validPlacement(placement)) &&
                    encodedIntentBytes > 0 && encodedIntentBytes <= AbilityWorkLimits.MAX_REQUEST_BYTES;
        }
        private boolean validIntervals() {
            if (kind == Kind.OBSERVE) {
                return targetIntervalNanos == 0 && requesterIntervalNanos >= nanos(2_000) &&
                        characterIntervalNanos >= nanos(250) &&
                        requesterIntervalNanos <= MAX_CONFIGURED_INTERVAL_NANOS &&
                        characterIntervalNanos <= MAX_CONFIGURED_INTERVAL_NANOS;
            }
            return requesterIntervalNanos >= nanos(30_000) && targetIntervalNanos >= nanos(30_000) &&
                    characterIntervalNanos >= nanos(1_000) &&
                    requesterIntervalNanos <= MAX_CONFIGURED_INTERVAL_NANOS &&
                    targetIntervalNanos <= MAX_CONFIGURED_INTERVAL_NANOS &&
                    characterIntervalNanos <= MAX_CONFIGURED_INTERVAL_NANOS;
        }
        private static boolean validPlacement(AbilityWorkLimits.Placement placement) {
            if (placement == null || placement.dimension() == null || placement.dimension().length() > 128 ||
                    !placement.dimension().matches("[A-Za-z0-9._/-]+:[A-Za-z0-9._/-]+") ||
                    (long) placement.maxColumnXExclusive() - placement.minColumnX() != 9 ||
                    (long) placement.maxColumnZExclusive() - placement.minColumnZ() != 9 ||
                    placement.distinctChunks() < 1 || placement.distinctChunks() > AbilityWorkLimits.MAX_LIGHTNING_CHUNKS ||
                    !Double.isFinite(placement.originX()) || !Double.isFinite(placement.originY()) ||
                    !Double.isFinite(placement.originZ()) || placement.originX() != placement.feetBlockX() + 0.5 ||
                    placement.originY() != placement.feetBlockY() || placement.originZ() != placement.feetBlockZ() + 0.5 ||
                    (long) placement.minColumnX() != (long) placement.feetBlockX() - 4 ||
                    (long) placement.minColumnZ() != (long) placement.feetBlockZ() - 4) return false;
            java.util.Set<Long> chunks = new java.util.HashSet<>(4);
            for (int x = placement.minColumnX(); x < placement.maxColumnXExclusive(); x++) {
                for (int z = placement.minColumnZ(); z < placement.maxColumnZExclusive(); z++) {
                    chunks.add((((long) Math.floorDiv(x, 16)) << 32) ^
                            (Math.floorDiv(z, 16) & 0xffff_ffffL));
                    if (chunks.size() > AbilityWorkLimits.MAX_LIGHTNING_CHUNKS) return false;
                }
            }
            return chunks.size() == placement.distinctChunks();
        }
        int identityMetadataBytes() { return metadataEncoding("identity", this).length; }
        int holdMetadataBytes() { return metadataEncoding("hold", this).length; }
        int consequenceMetadataBytes() { return metadataEncoding("consequence", this).length; }
        boolean sameIntent(Demand other) {
            return other != null && kind == other.kind && binding.same(other.binding) &&
                    Objects.equals(configurationHash, other.configurationHash) &&
                    Objects.equals(serverEpochId, other.serverEpochId) &&
                    serviceGeneration == other.serviceGeneration && gateGeneration == other.gateGeneration &&
                    peerGeneration == other.peerGeneration && Objects.equals(requestId, other.requestId) &&
                    Objects.equals(selectedGrantId, other.selectedGrantId) && policyRevision == other.policyRevision &&
                    Objects.equals(requesterId, other.requesterId) &&
                    Objects.equals(targetId, other.targetId) && Objects.equals(invocationId, other.invocationId) &&
                    visualOnly == other.visualOnly && Objects.equals(placement, other.placement) &&
                    Objects.equals(identityId, other.identityId) &&
                    requesterIntervalNanos == other.requesterIntervalNanos &&
                    targetIntervalNanos == other.targetIntervalNanos &&
                    characterIntervalNanos == other.characterIntervalNanos &&
                    deadlineAtNanos == other.deadlineAtNanos && encodedIntentBytes == other.encodedIntentBytes;
        }
        boolean sameEffect(Demand other) {
            return other != null && kind == Kind.LIGHTNING && other.kind == Kind.LIGHTNING &&
                    binding.same(other.binding) && Objects.equals(configurationHash, other.configurationHash) &&
                    Objects.equals(serverEpochId, other.serverEpochId) &&
                    serviceGeneration == other.serviceGeneration && gateGeneration == other.gateGeneration &&
                    peerGeneration == other.peerGeneration && Objects.equals(requestId, other.requestId) &&
                    Objects.equals(selectedGrantId, other.selectedGrantId) && policyRevision == other.policyRevision &&
                    Objects.equals(requesterId, other.requesterId) && Objects.equals(targetId, other.targetId) &&
                    visualOnly == other.visualOnly && Objects.equals(placement, other.placement);
        }
        boolean sameBindingAndRequest(Demand other) {
            return other != null && binding.same(other.binding) && requestId.equals(other.requestId) &&
                    invocationId.equals(other.invocationId) && selectedGrantId.equals(other.selectedGrantId) &&
                    Objects.equals(configurationHash, other.configurationHash) &&
                    Objects.equals(serverEpochId, other.serverEpochId) &&
                    serviceGeneration == other.serviceGeneration && gateGeneration == other.gateGeneration &&
                    peerGeneration == other.peerGeneration && policyRevision == other.policyRevision;
        }
        private static boolean canonicalUuid(String value) {
            if (value == null) return false;
            try { return java.util.UUID.fromString(value).toString().equals(value); }
            catch (IllegalArgumentException malformed) { return false; }
        }
    }

    record ReserveResult(boolean reserved, Token token, Reason reason, Long retryAfterMillis,
                         IdentityState knownState) {
        static ReserveResult reserved(Token token) { return new ReserveResult(true, token, Reason.NONE, null, null); }
        static ReserveResult denied(Reason reason, Long retry) {
            return new ReserveResult(false, null, reason, boundedRetry(retry), null);
        }
        static ReserveResult known(IdentityState state) { return new ReserveResult(false, null, Reason.NONE, null, state); }
        private static Long boundedRetry(Long value) { return value != null && value >= 0 && value <= 30_000 ? value : null; }
    }

    record StartResult(boolean started, Reason reason, long startedAtNanos, long sequence) {
        static StartResult started(long at, long sequence) { return new StartResult(true, Reason.NONE, at, sequence); }
        static StartResult denied(Reason reason) { return new StartResult(false, reason, -1, -1); }
    }

    private enum NodeType { REQUESTER, CHARACTER, TARGET }
    private record NodeKey(Kind kind, NodeType type, String identity) {
        static NodeKey requester(Kind kind, String id) { return new NodeKey(kind, NodeType.REQUESTER, id); }
        static NodeKey character(Kind kind, String id) { return new NodeKey(kind, NodeType.CHARACTER, id); }
        static NodeKey target(String id) { return new NodeKey(Kind.LIGHTNING, NodeType.TARGET, id); }
    }
    private record IdentityKey(Kind kind, String identityId) {}
    private record InvocationKey(String invocationId) {}

    private static byte[] metadataEncoding(String category, Demand demand) {
        StringBuilder value = new StringBuilder(512).append(category).append('\n').append(demand.kind()).append('\n')
                .append(demand.binding().serverId()).append('\n').append(demand.binding().worldId()).append('\n')
                .append(demand.binding().characterId()).append('\n').append(demand.configurationHash()).append('\n')
                .append(demand.serverEpochId()).append('\n').append(demand.serviceGeneration()).append('\n')
                .append(demand.gateGeneration()).append('\n').append(demand.peerGeneration()).append('\n')
                .append(demand.requestId()).append('\n')
                .append(demand.invocationId()).append('\n').append(demand.identityId()).append('\n')
                .append(demand.requesterId()).append('\n').append(demand.targetId()).append('\n')
                    .append(demand.selectedGrantId()).append('\n').append(demand.policyRevision()).append('\n')
                .append(demand.visualOnly()).append('\n')
                .append(demand.requesterIntervalNanos()).append('\n').append(demand.targetIntervalNanos()).append('\n')
                .append(demand.characterIntervalNanos()).append('\n').append(demand.deadlineAtNanos()).append('\n')
                .append(demand.encodedIntentBytes());
        if (demand.placement() != null) {
            value.append('\n').append(demand.placement().dimension()).append('\n')
                    .append(demand.placement().feetBlockX()).append(',').append(demand.placement().feetBlockY())
                    .append(',').append(demand.placement().feetBlockZ()).append('\n')
                    .append(demand.placement().minColumnX()).append(',').append(demand.placement().maxColumnXExclusive())
                    .append(',').append(demand.placement().minColumnZ()).append(',')
                    .append(demand.placement().maxColumnZExclusive()).append('\n')
                    .append(Double.toHexString(demand.placement().originX())).append(',')
                    .append(Double.toHexString(demand.placement().originY())).append(',')
                    .append(Double.toHexString(demand.placement().originZ())).append(',')
                    .append(demand.placement().distinctChunks());
        }
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final class RateNode {
        private final NodeKey key;
        private int reserved;
        private int inFlight;
        private int unknownPins;
        private boolean hasCharge;
        private long lastChargedAt;
        private long lastIntervalNanos;
        private boolean queuedForCleanup;
        private RateNode(NodeKey key) { this.key = key; }
    }

    private static final class Hold {
        private final Token token;
        private final Object ownerIdentity;
        private final Demand demand;
        private final NodeKey requesterKey;
        private final NodeKey characterKey;
        private final NodeKey targetKey;
        private final int identityMetadataBytes;
        private final int holdMetadataBytes;
        private final int reservedConsequenceBytes;
        private boolean started;
        private long startedAtNanos;
        private boolean unknownRecorded;
        private Hold(Token token, Object ownerIdentity, Demand demand, NodeKey requesterKey,
                     NodeKey characterKey, NodeKey targetKey, int identityMetadataBytes,
                     int holdMetadataBytes, int reservedConsequenceBytes) {
            this.token = token;
            this.ownerIdentity = ownerIdentity;
            this.demand = demand;
            this.requesterKey = requesterKey;
            this.characterKey = characterKey;
            this.targetKey = targetKey;
            this.identityMetadataBytes = identityMetadataBytes;
            this.holdMetadataBytes = holdMetadataBytes;
            this.reservedConsequenceBytes = reservedConsequenceBytes;
        }
        private Token token() { return token; }
        private Object ownerIdentity() { return ownerIdentity; }
        private Demand demand() { return demand; }
        private NodeKey requesterKey() { return requesterKey; }
        private NodeKey characterKey() { return characterKey; }
        private NodeKey targetKey() { return targetKey; }
        private int identityMetadataBytes() { return identityMetadataBytes; }
        private int holdMetadataBytes() { return holdMetadataBytes; }
        private int reservedConsequenceBytes() { return reservedConsequenceBytes; }
        private boolean started() { return started; }
        private void markStarted(long now) { started = true; startedAtNanos = now; }
        private boolean unknownRecorded() { return unknownRecorded; }
        private void markUnknownRecorded() { unknownRecorded = true; }
    }

    private static final class IdentityRecord {
        private final Demand demand;
        private final int metadataBytes;
        private IdentityState state;
        private boolean consequential;
        private IdentityRecord(Demand demand, IdentityState state, int metadataBytes, boolean consequential) {
            this.demand = demand;
            this.state = state;
            this.metadataBytes = metadataBytes;
            this.consequential = consequential;
        }
        private static IdentityRecord from(Demand demand, IdentityState state, int bytes) {
            return new IdentityRecord(demand, state, bytes, false);
        }
        private static IdentityRecord consequential(Demand demand, IdentityState state, int bytes) {
            return new IdentityRecord(demand, state, bytes, true);
        }
        private boolean matches(Demand other) { return demand.sameIntent(other); }
        private boolean sameEffect(Demand other) { return demand.sameEffect(other); }
        private boolean sameRequestIntent(String requestId, Binding binding, String requesterId, String targetId,
                                          String invocationId, boolean visualOnly, String configurationHash,
                                          String serverEpochId, long serviceGeneration, long gateGeneration,
                                          long peerGeneration) {
            return Objects.equals(demand.requestId(), requestId) && demand.binding().same(binding) &&
                    Objects.equals(demand.configurationHash(), configurationHash) &&
                    Objects.equals(demand.serverEpochId(), serverEpochId) &&
                    demand.serviceGeneration() == serviceGeneration && demand.gateGeneration() == gateGeneration &&
                    demand.peerGeneration() == peerGeneration && Objects.equals(demand.requesterId(), requesterId) &&
                    Objects.equals(demand.targetId(), targetId) &&
                    Objects.equals(demand.invocationId(), invocationId) &&
                    demand.visualOnly() == visualOnly;
        }
        private IdentityState state() { return state; }
        private void setState(IdentityState state) { this.state = state; }
        private String requestId() { return demand.requestId(); }
        private Kind kind() { return demand.kind(); }
        private int metadataBytes() { return metadataBytes; }
        private boolean consequential() { return consequential; }
        private void markConsequential() { consequential = true; }
    }
}
