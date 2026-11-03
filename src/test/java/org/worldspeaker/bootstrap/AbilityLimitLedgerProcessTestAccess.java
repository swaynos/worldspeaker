package org.worldspeaker.bootstrap;

import java.util.concurrent.TimeUnit;

/** Test-package access to snapshots and controlled demands for the real process-owned ledger. */
public final class AbilityLimitLedgerProcessTestAccess {
    private static final String SERVER_EPOCH = id(62_999);
    private static final String REQUESTER = id(61_001);
    private static final String TARGET = id(61_002);
    private static final String OBSERVE_REQUESTER = id(61_011);

    private AbilityLimitLedgerProcessTestAccess() {}

    public static AbilityLimitLedger.Snapshot snapshot(AbilityLimitLedger ledger) {
        return ledger.snapshotForTests();
    }

    public static boolean bind(AbilityLimitLedger ledger, String serverId, String worldId, String characterId) {
        return ledger.bind(serverId, worldId, characterId);
    }

    public static UnknownLightning startUnknownLightning(AbilityLimitLedger ledger,
                                                        String serverId, String worldId, String characterId,
                                                        long serviceGeneration, long peerGeneration) {
        AbilityLimitLedger.Binding binding = new AbilityLimitLedger.Binding(serverId, worldId, characterId);
        Object owner = new Object();
        AbilityLimitLedger.Demand demand = demand(binding, REQUESTER, TARGET, id(61_003), id(61_004), id(61_005),
                serviceGeneration, peerGeneration, "a".repeat(64), SERVER_EPOCH);
        AbilityLimitLedger.ReserveResult held = ledger.reserve(demand, owner);
        if (!held.reserved()) throw new AssertionError("process ledger refused its initial evidence hold: " + held.reason());
        AbilityLimitLedger.StartResult started = ledger.consume(held.token(), owner, demand);
        if (!started.started()) throw new AssertionError("process ledger refused its initial evidence start: " + started.reason());
        AbilityLimitLedger.SettlementResult unknown = ledger.settle(held.token(), owner,
                AbilityLimitLedger.Settlement.UNKNOWN, false);
        if (unknown != AbilityLimitLedger.SettlementResult.UNKNOWN_PINNED) {
            throw new AssertionError("process ledger did not retain uncertain in-flight evidence: " + unknown);
        }
        return new UnknownLightning(ledger, binding, owner, held.token());
    }

    public enum SharedResource { REQUESTER, TARGET, CHARACTER }

    public record Outcome(boolean reserved, AbilityLimitLedger.Reason reason) {}

    public static final class UnknownLightning {
        private final AbilityLimitLedger ledger;
        private final AbilityLimitLedger.Binding binding;
        private final Object originalOwner;
        private final AbilityLimitLedger.Token token;

        private UnknownLightning(AbilityLimitLedger ledger, AbilityLimitLedger.Binding binding,
                                 Object originalOwner, AbilityLimitLedger.Token token) {
            this.ledger = ledger;
            this.binding = binding;
            this.originalOwner = originalOwner;
            this.token = token;
        }

        public String requesterId() { return REQUESTER; }
        public String targetId() { return TARGET; }

        public void seedCompletedObserve(long serviceGeneration, long peerGeneration) {
            AbilityLimitLedger.Demand demand = observeDemand(binding, OBSERVE_REQUESTER, id(61_012), id(61_013),
                    id(61_014), id(61_015), serviceGeneration, peerGeneration, "a".repeat(64), id(61_016));
            Object owner = new Object();
            AbilityLimitLedger.ReserveResult held = ledger.reserve(demand, owner);
            if (!held.reserved()) throw new AssertionError("process ledger refused the Observe charge: " + held.reason());
            AbilityLimitLedger.StartResult started = ledger.consume(held.token(), owner, demand);
            if (!started.started()) throw new AssertionError("process ledger refused the Observe start: " + started.reason());
            AbilityLimitLedger.SettlementResult settled = ledger.settle(held.token(), owner,
                    AbilityLimitLedger.Settlement.CONFIRMED, true);
            if (settled != AbilityLimitLedger.SettlementResult.SETTLED) {
                throw new AssertionError("process ledger failed to retain the confirmed charge: " + settled);
            }
        }

        public Attempt attempt(SharedResource sharedResource, long serviceGeneration,
                               long peerGeneration, int idBase) {
            String requester = sharedResource == SharedResource.REQUESTER ? REQUESTER : id(idBase + 1);
            String target = sharedResource == SharedResource.TARGET ? TARGET : id(idBase + 2);
            AbilityLimitLedger.Demand demand = demand(binding, requester, target, id(idBase + 3), id(idBase + 4),
                    id(idBase + 5), serviceGeneration, peerGeneration, "b".repeat(64), id(idBase + 6));
            Object contenderOwner = new Object();
            return new Attempt(ledger, contenderOwner, ledger.reserve(demand, contenderOwner));
        }

        public Attempt attemptObserve(boolean sameRequester, long serviceGeneration,
                                      long peerGeneration, int idBase) {
            String requester = sameRequester ? OBSERVE_REQUESTER : id(idBase + 1);
            AbilityLimitLedger.Demand demand = observeDemand(binding, requester, id(idBase + 2), id(idBase + 3),
                    id(idBase + 4), id(idBase + 5), serviceGeneration, peerGeneration,
                    "b".repeat(64), id(idBase + 6));
            Object contenderOwner = new Object();
            return new Attempt(ledger, contenderOwner, ledger.reserve(demand, contenderOwner));
        }

        public Attempt attemptWithBinding(String serverId, String worldId, String characterId,
                                          long serviceGeneration, long peerGeneration, int idBase) {
            AbilityLimitLedger.Binding changed = new AbilityLimitLedger.Binding(serverId, worldId, characterId);
            AbilityLimitLedger.Demand demand = demand(changed, id(idBase + 1), id(idBase + 2), id(idBase + 3),
                    id(idBase + 4), id(idBase + 5), serviceGeneration, peerGeneration,
                    "c".repeat(64), id(idBase + 6));
            Object contenderOwner = new Object();
            return new Attempt(ledger, contenderOwner, ledger.reserve(demand, contenderOwner));
        }

        public AbilityLimitLedger.SettlementResult proveCompletion() {
            return ledger.settle(token, originalOwner, AbilityLimitLedger.Settlement.UNKNOWN, true);
        }
    }

    public static final class Attempt {
        private final AbilityLimitLedger ledger;
        private final Object owner;
        private final Outcome result;
        private final AbilityLimitLedger.Token token;

        private Attempt(AbilityLimitLedger ledger, Object owner, AbilityLimitLedger.ReserveResult result) {
            this.ledger = ledger;
            this.owner = owner;
            this.result = new Outcome(result.reserved(), result.reason());
            this.token = result.token();
        }

        public Outcome result() { return result; }

        public boolean release() {
            return result.reserved() && ledger.release(token, owner);
        }
    }

    private static AbilityLimitLedger.Demand demand(AbilityLimitLedger.Binding binding,
                                                     String requester, String target,
                                                     String request, String invocation, String operation,
                                                     long serviceGeneration, long peerGeneration,
                                                     String configurationHash, String serverEpoch) {
        long now = System.nanoTime();
        AbilityWorkLimits.Placement placement = new AbilityWorkLimits.Placement("minecraft:overworld",
                0, 64, 0, 0.5, 64, 0.5, -4, 5, -4, 5, 4);
        return new AbilityLimitLedger.Demand(binding, configurationHash, serverEpoch, serviceGeneration, 1,
                peerGeneration, AbilityLimitLedger.Kind.LIGHTNING, requester, target, request, invocation,
                operation, "lightning-grant", 1, true, placement,
                TimeUnit.MILLISECONDS.toNanos(30_000), TimeUnit.MILLISECONDS.toNanos(30_000),
                TimeUnit.MILLISECONDS.toNanos(1_000), now + TimeUnit.MINUTES.toNanos(5), 180);
    }

    private static AbilityLimitLedger.Demand observeDemand(AbilityLimitLedger.Binding binding,
                                                            String requester, String target,
                                                            String request, String invocation, String operation,
                                                            long serviceGeneration, long peerGeneration,
                                                            String configurationHash, String serverEpoch) {
        long now = System.nanoTime();
        return new AbilityLimitLedger.Demand(binding, configurationHash, serverEpoch, serviceGeneration, 1,
                peerGeneration, AbilityLimitLedger.Kind.OBSERVE, requester, target, request, invocation, operation,
                "observe-grant", 1, true, null, TimeUnit.MILLISECONDS.toNanos(60_000), 0,
                TimeUnit.MILLISECONDS.toNanos(60_000), now + TimeUnit.MINUTES.toNanos(5), 150);
    }

    private static String id(int suffix) {
        return "00000000-0000-4000-8000-%012d".formatted(suffix);
    }
}
