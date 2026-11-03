package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AbilityLimitLedgerTest {
    private static final String HASH = "a".repeat(64);
    private static final String EPOCH = id(900);
    private static final String TARGET = id(901);

    @Test
    void reservationsAreAtomicAndReleaseExactlyOnceWithoutCharging() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        AbilityLimitLedger.Demand first = observe(now, 1, 1, 1);
        AbilityLimitLedger.Demand competing = observe(now, 1, 2, 2);

        AbilityLimitLedger.ReserveResult held = ledger.reserve(first, owner);
        assertTrue(held.reserved());
        AbilityLimitLedger.Snapshot beforeReject = ledger.snapshotForTests();
        AbilityLimitLedger.ReserveResult rejected = ledger.reserve(competing, owner);
        assertFalse(rejected.reserved());
        assertEquals(AbilityLimitLedger.Reason.CONCURRENCY_LIMITED, rejected.reason());
        assertEquals(beforeReject, ledger.snapshotForTests(), "a rejected all-node transaction changes no counts");

        assertTrue(ledger.release(held.token(), owner));
        assertFalse(ledger.release(held.token(), owner), "release is one-shot");
        assertEquals(0, ledger.snapshotForTests().liveHolds());
        assertEquals(0, ledger.snapshotForTests().chargedNodes(), "pre-start release is not a charge");
        AbilityLimitLedger.ReserveResult retry = ledger.reserve(competing, owner);
        assertTrue(retry.reserved(), "the released hold did not consume requester or character allowance");
        assertTrue(ledger.release(retry.token(), owner));
    }

    @Test
    void startChargesIntervalsAndFailureDoesNotRefund() {
        AtomicLong now = new AtomicLong(2_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        AbilityLimitLedger.Demand first = observe(now, 3, 3, 3);
        AbilityLimitLedger.Token token = ledger.reserve(first, owner).token();

        AbilityLimitLedger.StartResult start = ledger.consume(token, owner, first);
        assertTrue(start.started());
        assertFalse(ledger.release(token, owner), "a consumed token cannot be refunded");
        assertEquals(AbilityLimitLedger.SettlementResult.SETTLED,
                ledger.settle(token, owner, AbilityLimitLedger.Settlement.FAILED, true));
        assertEquals(0, ledger.snapshotForTests().inFlight());
        assertEquals(2, ledger.snapshotForTests().chargedNodes());

        AbilityLimitLedger.ReserveResult immediate = ledger.reserve(observe(now, 3, 4, 4), owner);
        assertEquals(AbilityLimitLedger.Reason.REQUESTER_RATE, immediate.reason());
        assertEquals(2_000L, immediate.retryAfterMillis());
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1_999));
        assertEquals(1L, ledger.reserve(observe(now, 3, 5, 5), owner).retryAfterMillis());
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
        AbilityLimitLedger.ReserveResult exact = ledger.reserve(observe(now, 3, 6, 6), owner);
        assertTrue(exact.reserved(), "the exact inclusive interval boundary is admitted");
        assertTrue(ledger.release(exact.token(), owner));
    }

    @Test
    void characterAndRequesterSlotsAreSharedAndRejectedContendersHoldNothing() {
        AtomicLong now = new AtomicLong(3_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        List<AbilityLimitLedger.Token> holds = new ArrayList<>();
        for (int i = 10; i < 26; i++) {
            AbilityLimitLedger.ReserveResult result = ledger.reserve(observe(now, i, i, i), owner);
            assertTrue(result.reserved(), "observe allows sixteen shared character slots");
            holds.add(result.token());
        }
        AbilityLimitLedger.Snapshot full = ledger.snapshotForTests();
        AbilityLimitLedger.ReserveResult seventeenth = ledger.reserve(observe(now, 26, 26, 26), owner);
        assertEquals(AbilityLimitLedger.Reason.CONCURRENCY_LIMITED, seventeenth.reason());
        assertEquals(full, ledger.snapshotForTests());
        for (AbilityLimitLedger.Token token : holds) assertTrue(ledger.release(token, owner));
        ledger.cleanupIncremental(32);

        AbilityLimitLedger.Demand lightning = lightning(now, 40, 40, 40, TARGET, true);
        AbilityLimitLedger.Token lightningHold = ledger.reserve(lightning, owner).token();
        AbilityLimitLedger.Snapshot lightningFull = ledger.snapshotForTests();
        AbilityLimitLedger.ReserveResult visualDamageCompetitor = ledger.reserve(
                lightning(now, 41, 41, 41, id(902), false), owner);
        assertEquals(AbilityLimitLedger.Reason.CONCURRENCY_LIMITED, visualDamageCompetitor.reason());
        assertEquals(lightningFull, ledger.snapshotForTests(),
                "visual and damage modes share the character node and rejection installs no target node");
        assertTrue(ledger.release(lightningHold, owner));
    }

    @Test
    void concurrentLastSlotHasOneWinnerAndNoLosingReservation() throws Exception {
        for (int order = 0; order < 2; order++) {
            int arrivalOrder = order;
            AtomicLong now = new AtomicLong(4_000_000_000L);
            AbilityLimitLedger ledger = ledger(now);
            Object owner = new Object();
            List<AbilityLimitLedger.Token> preexisting = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                AbilityLimitLedger.ReserveResult admitted = ledger.reserve(observe(now, 100 + i,
                        100 + i, 100 + i), owner);
                assertTrue(admitted.reserved());
                preexisting.add(admitted.token());
            }
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                AbilityLimitLedger.Demand left = observe(now, 50, 50, 50);
                AbilityLimitLedger.Demand right = observe(now, 51, 51, 51);
                Future<AbilityLimitLedger.ReserveResult> first = workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
                    return ledger.reserve(arrivalOrder == 0 ? left : right, owner);
                });
                Future<AbilityLimitLedger.ReserveResult> second = workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
                    return ledger.reserve(arrivalOrder == 0 ? right : left, owner);
                });
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                AbilityLimitLedger.ReserveResult a = first.get(5, TimeUnit.SECONDS);
                AbilityLimitLedger.ReserveResult b = second.get(5, TimeUnit.SECONDS);
                assertNotEquals(a.reserved(), b.reserved());
                assertEquals(16, ledger.snapshotForTests().liveHolds());
                assertEquals(16, ledger.snapshotForTests().observeRequesterNodes());
                assertEquals(16, ledger.snapshotForTests().identityRecords());
                assertTrue(ledger.release(a.reserved() ? a.token() : b.token(), owner));
                for (AbilityLimitLedger.Token token : preexisting) assertTrue(ledger.release(token, owner));
                assertEquals(0, ledger.snapshotForTests().liveHolds());
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void invocationIdentityAndUnknownPinsAreConservative() {
        AtomicLong now = new AtomicLong(5_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        AbilityLimitLedger.Demand lightning = lightning(now, 60, 60, 60, TARGET, true);
        AbilityLimitLedger.Token token = ledger.reserve(lightning, owner).token();
        assertTrue(ledger.reserve(lightning, owner).knownState() == AbilityLimitLedger.IdentityState.HELD);
        AbilityLimitLedger.Demand invocationReuse = lightning(now, 61, 61, 60, TARGET, true);
        assertEquals(AbilityLimitLedger.Reason.IDENTITY_CONFLICT, ledger.reserve(invocationReuse, owner).reason());

        assertTrue(ledger.consume(token, owner, lightning).started());
        assertEquals(AbilityLimitLedger.SettlementResult.SETTLED,
                ledger.settle(token, owner, AbilityLimitLedger.Settlement.UNKNOWN, true));
        assertEquals(2, ledger.snapshotForTests().unknownPins());
        now.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertEquals(AbilityLimitLedger.Reason.CONCURRENCY_LIMITED,
                ledger.reserve(lightning(now, 60, 62, 62, id(903), true), owner).reason(),
                "unknown requester pin survives nominal cooldown");
        assertEquals(AbilityLimitLedger.Reason.CONCURRENCY_LIMITED,
                ledger.reserve(lightning(now, 63, 63, 63, TARGET, true), owner).reason(),
                "unknown target pin survives nominal cooldown");
        assertTrue(ledger.reserve(lightning(now, 64, 64, 64, id(904), true), owner).reserved(),
                "an unrelated requester/target can proceed after the global character interval");
    }

    @Test
    void deadlineClockFaultAndBindingChangeFailClosedWithoutRefundingState() {
        AtomicLong now = new AtomicLong(6_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        AbilityLimitLedger.Demand held = observe(now, 70, 70, 70);
        AbilityLimitLedger.Token token = ledger.reserve(held, owner).token();
        AbilityLimitLedger.ReserveResult inclusive = ledger.reserve(
                withDeadline(observe(now, 71, 71, 71), now.get()), owner);
        assertTrue(inclusive.reserved(), "the deadline sample itself is included");
        assertTrue(ledger.release(inclusive.token(), owner));
        now.incrementAndGet();
        assertEquals(AbilityLimitLedger.Reason.DEADLINE_EXPIRED,
                ledger.reserve(withDeadline(observe(now, 72, 72, 72), now.get() - 1), owner).reason());
        assertEquals(1, ledger.snapshotForTests().liveHolds());

        now.decrementAndGet();
        assertEquals(AbilityLimitLedger.Reason.CLOCK_UNSAFE,
                ledger.reserve(observe(now, 72, 72, 72), owner).reason());
        assertTrue(ledger.snapshotForTests().clockFault());
        assertEquals(1, ledger.snapshotForTests().liveHolds(), "ambiguous clock faults never expire a hold");
        assertTrue(ledger.release(token, owner));

        AtomicLong nearWrap = new AtomicLong(Long.MAX_VALUE - 100);
        AbilityLimitLedger wrapping = ledger(nearWrap);
        Object wrapOwner = new Object();
        AbilityLimitLedger.Demand nearEnd = withDeadline(observe(nearWrap, 73, 73, 73), Long.MAX_VALUE);
        AbilityLimitLedger.Token wrapToken = wrapping.reserve(nearEnd, wrapOwner).token();
        nearWrap.set(Long.MIN_VALUE);
        assertEquals(AbilityLimitLedger.Reason.CLOCK_UNSAFE,
                wrapping.reserve(observe(nearWrap, 74, 74, 74), wrapOwner).reason());
        assertEquals(1, wrapping.snapshotForTests().liveHolds());
        assertTrue(wrapping.release(wrapToken, wrapOwner));

        assertFalse(ledger.bind("server-other", "world-test", "character-test"));
        assertTrue(ledger.snapshotForTests().bindingClosed());
        assertEquals(AbilityLimitLedger.Reason.BINDING_CLOSED,
                ledger.reserve(observe(now, 75, 75, 75), owner).reason());
    }

    @Test
    void retainedStartDeadlineIsInclusiveAndExpiresAfterOneNanosecond() {
        AtomicLong exactNow = new AtomicLong(6_500_000_000L);
        AbilityLimitLedger exactLedger = ledger(exactNow);
        Object exactOwner = new Object();
        AbilityLimitLedger.Demand exactDemand = withDeadline(observe(exactNow, 76, 76, 76),
                exactNow.get() + 10);
        AbilityLimitLedger.Token exactToken = exactLedger.reserve(exactDemand, exactOwner).token();
        exactNow.addAndGet(10);
        assertTrue(exactLedger.consume(exactToken, exactOwner, exactDemand).started(),
                "the hold can start at its inclusive ability-age boundary");

        AtomicLong lateNow = new AtomicLong(6_600_000_000L);
        AbilityLimitLedger lateLedger = ledger(lateNow);
        Object lateOwner = new Object();
        AbilityLimitLedger.Demand lateDemand = withDeadline(observe(lateNow, 77, 77, 77), lateNow.get() + 10);
        AbilityLimitLedger.Token lateToken = lateLedger.reserve(lateDemand, lateOwner).token();
        lateNow.addAndGet(11);
        assertEquals(AbilityLimitLedger.Reason.DEADLINE_EXPIRED,
                lateLedger.consume(lateToken, lateOwner, lateDemand).reason());
        assertEquals(0, lateLedger.snapshotForTests().chargedNodes());
        assertEquals(0, lateLedger.snapshotForTests().liveHolds());
    }

    @Test
    void stableConfiguredBindingRetainsEvidenceAndEachChangedComponentClosesWithoutFlushing() {
        String[][] changedBindings = {
                {"server-other", "world-test", "character-test"},
                {"server-test", "world-other", "character-test"},
                {"server-test", "world-test", "character-other"}
        };
        for (int index = 0; index < changedBindings.length; index++) {
            AtomicLong now = new AtomicLong(6_500_000_000L + index);
            AbilityLimitLedger ledger = ledger(now);
            Object owner = new Object();
            AbilityLimitLedger.Demand demand = observe(now, 76 + index, 76 + index, 76 + index);
            AbilityLimitLedger.Token token = ledger.reserve(demand, owner).token();
            AbilityLimitLedger.Snapshot held = ledger.snapshotForTests();

            assertTrue(ledger.bind("server-test", "world-test", "character-test"),
                    "the same configured binding remains stable across service/server-object replacement");
            assertEquals(held, ledger.snapshotForTests(), "same-binding rebind does not migrate or flush state");
            assertFalse(ledger.bind(changedBindings[index][0], changedBindings[index][1], changedBindings[index][2]));

            AbilityLimitLedger.Snapshot closed = ledger.snapshotForTests();
            assertEquals(new AbilityLimitLedger.Snapshot(held.bound(), true, held.clockFault(), held.liveHolds(),
                    held.inFlight(), held.observeRequesterNodes(), held.lightningRequesterNodes(),
                    held.lightningTargetNodes(), held.identityRecords(), held.invocationRecords(), held.metadataBytes(),
                    held.chargedNodes(), held.unknownPins()), closed,
                    "a changed server, world, or character component closes admission without flushing evidence");
            assertEquals(AbilityLimitLedger.Reason.BINDING_CLOSED,
                    ledger.reserve(observe(now, 80 + index, 80 + index, 80 + index), owner).reason());
            assertEquals(closed, ledger.snapshotForTests(), "closed-binding rejection is mutation-free");
            assertTrue(ledger.release(token, owner), "the original owner may still release its unstarted hold");
        }
    }

    @Test
    void foreignTokensAreRejectedAndCompletedTokensAreOneShot() {
        AtomicLong now = new AtomicLong(7_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        Object foreignOwner = new Object();
        AbilityLimitLedger.Demand demand = observe(now, 80, 80, 80);
        AbilityLimitLedger.Token token = ledger.reserve(demand, owner).token();
        assertFalse(ledger.release(token, foreignOwner));
        assertTrue(ledger.consume(token, owner, demand).started());
        assertFalse(ledger.release(token, owner));
        assertFalse(ledger.consume(token, owner, demand).started());
        assertEquals(AbilityLimitLedger.SettlementResult.SETTLED,
                ledger.settle(token, owner, AbilityLimitLedger.Settlement.CONFIRMED, true));
        assertEquals(AbilityLimitLedger.SettlementResult.STALE,
                ledger.settle(token, owner, AbilityLimitLedger.Settlement.CONFIRMED, true));
    }

    @Test
    void idleRowsAreReclaimedOnlyAfterTrustedFiveMinuteAge() {
        AtomicLong now = new AtomicLong(8_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        AbilityLimitLedger.Demand demand = observe(now, 90, 90, 90);
        AbilityLimitLedger.Token token = ledger.reserve(demand, owner).token();
        assertTrue(ledger.consume(token, owner, demand).started());
        assertEquals(AbilityLimitLedger.SettlementResult.SETTLED,
                ledger.settle(token, owner, AbilityLimitLedger.Settlement.CONFIRMED, true));
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(AbilityLimitLedger.IDLE_RECLAIM_MILLIS - 1));
        for (int i = 0; i < 4; i++) ledger.cleanupIncremental(32);
        assertEquals(1, ledger.snapshotForTests().observeRequesterNodes());
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
        for (int i = 0; i < 4; i++) ledger.cleanupIncremental(32);
        assertEquals(0, ledger.snapshotForTests().observeRequesterNodes());
        assertEquals(0, ledger.snapshotForTests().chargedNodes());
    }

    @Test
    void distinctQueryIdentitiesAreBoundedPerRequestAndRetiredTogether() {
        AtomicLong now = new AtomicLong(9_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        String requestId = id(95);
        for (int query = 0; query < AbilityLimitLedger.MAX_QUERY_IDS_PER_LIVE_REQUEST; query++) {
            AbilityLimitLedger.Demand demand = observeWithIdentity(now, 96, requestId, 200 + query,
                    id(1_000 + query));
            AbilityLimitLedger.ReserveResult held = ledger.reserve(demand, owner);
            assertTrue(held.reserved(), "a distinct query identity is retained within the request bound");
            assertTrue(ledger.release(held.token(), owner), "pre-start query release does not charge rates");
        }
        AbilityLimitLedger.Snapshot full = ledger.snapshotForTests();
        assertEquals(AbilityLimitLedger.MAX_QUERY_IDS_PER_LIVE_REQUEST, full.identityRecords());
        AbilityLimitLedger.ReserveResult overflow = ledger.reserve(
                observeWithIdentity(now, 96, requestId, 300, id(1_100)), owner);
        assertEquals(AbilityLimitLedger.Reason.CAPACITY, overflow.reason());
        AbilityLimitLedger.Snapshot rejected = ledger.snapshotForTests();
        assertEquals(full.liveHolds(), rejected.liveHolds());
        assertEquals(full.identityRecords(), rejected.identityRecords());
        assertEquals(full.metadataBytes(), rejected.metadataBytes());
        assertEquals(full.chargedNodes(), rejected.chargedNodes());

        ledger.retireRequest(requestId);
        assertEquals(0, ledger.snapshotForTests().identityRecords());
        assertEquals(0, ledger.snapshotForTests().metadataBytes());
    }

    @Test
    void queryRetryIsDeduplicatedAndFreshIdsShareTheSameRequesterRateNode() {
        AtomicLong now = new AtomicLong(9_500_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        String requestId = id(96);
        AbilityLimitLedger.Demand firstQuery = observeWithIdentity(now, 97, requestId, 201, id(1_201));
        AbilityLimitLedger.Token token = ledger.reserve(firstQuery, owner).token();
        assertTrue(ledger.consume(token, owner, firstQuery).started());
        assertEquals(AbilityLimitLedger.SettlementResult.SETTLED,
                ledger.settle(token, owner, AbilityLimitLedger.Settlement.FAILED, true));

        AbilityLimitLedger.ReserveResult retry = ledger.reserve(firstQuery, owner);
        assertFalse(retry.reserved());
        assertEquals(AbilityLimitLedger.IdentityState.FAILED, retry.knownState());
        assertEquals(AbilityLimitLedger.Reason.IDENTITY_CONFLICT,
                ledger.reserve(withTarget(firstQuery, id(902)), owner).reason(),
                "the same query identity cannot be rebound to changed arguments");
        AbilityLimitLedger.Snapshot charged = ledger.snapshotForTests();

        AbilityLimitLedger.ReserveResult freshId = ledger.reserve(
                observeWithIdentity(now, 97, requestId, 202, id(1_202)), owner);
        assertEquals(AbilityLimitLedger.Reason.REQUESTER_RATE, freshId.reason(),
                "a new query identity does not reset the shared requester cooldown");
        AbilityLimitLedger.Snapshot afterReject = ledger.snapshotForTests();
        assertEquals(charged.liveHolds(), afterReject.liveHolds());
        assertEquals(charged.identityRecords(), afterReject.identityRecords());
        assertEquals(charged.metadataBytes(), afterReject.metadataBytes());
        assertEquals(charged.chargedNodes(), afterReject.chargedNodes());
    }

    @Test
    void requesterRateContendersPreserveLongerIntervalsAndAllowAnUnrelatedPair() {
        AtomicLong now = new AtomicLong(10_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        AbilityLimitLedger.Demand first = observeWithIntervals(now, 1_301, 1_401, 1_501, id(1_601),
                5_000, 250);
        AbilityLimitLedger.Token token = ledger.reserve(first, owner).token();
        assertTrue(ledger.consume(token, owner, first).started());
        assertEquals(AbilityLimitLedger.SettlementResult.SETTLED,
                ledger.settle(token, owner, AbilityLimitLedger.Settlement.CONFIRMED, true));

        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
        AbilityLimitLedger.Snapshot charged = ledger.snapshotForTests();
        AbilityLimitLedger.ReserveResult sameRequester = ledger.reserve(
                observeWithIntervals(now, 1_301, 1_402, 1_502, id(1_602), 2_000, 250), owner);
        assertEquals(AbilityLimitLedger.Reason.REQUESTER_RATE, sameRequester.reason());
        assertEquals(4_750L, sameRequester.retryAfterMillis(),
                "a shorter current policy does not erase the prior longer interval");
        assertEquals(charged, ledger.snapshotForTests(), "rate denial creates no identity, token, or node");

        AbilityLimitLedger.Demand unrelated = observeWithIntervals(now, 1_302, 1_403, 1_503, id(1_603),
                2_000, 250);
        AbilityLimitLedger.ReserveResult unrelatedPair = ledger.reserve(unrelated, owner);
        assertTrue(unrelatedPair.reserved(), "the unrelated requester passes at the shared character boundary");
        assertTrue(ledger.release(unrelatedPair.token(), owner));

        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(4_750));
        AbilityLimitLedger.ReserveResult exactPriorBoundary = ledger.reserve(
                observeWithIntervals(now, 1_301, 1_404, 1_504, id(1_604), 2_000, 250), owner);
        assertTrue(exactPriorBoundary.reserved(), "the exact maximum prior/current interval is inclusive");
        assertTrue(ledger.release(exactPriorBoundary.token(), owner));
    }

    @Test
    void targetRateContendersPreserveLongerIntervalsAndAllowAnUnrelatedPair() {
        AtomicLong now = new AtomicLong(11_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        String sharedTarget = id(1_701);
        AbilityLimitLedger.Demand first = lightningWithIntervals(now, 1_711, 1_721, 1_731, sharedTarget,
                id(1_741), 60_000, 90_000, 1_000);
        AbilityLimitLedger.Token token = ledger.reserve(first, owner).token();
        assertTrue(ledger.consume(token, owner, first).started());
        assertEquals(AbilityLimitLedger.SettlementResult.SETTLED,
                ledger.settle(token, owner, AbilityLimitLedger.Settlement.CONFIRMED, true));

        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        AbilityLimitLedger.Snapshot charged = ledger.snapshotForTests();
        AbilityLimitLedger.ReserveResult sameTarget = ledger.reserve(lightningWithIntervals(now,
                1_712, 1_722, 1_732, sharedTarget, id(1_742), 30_000, 30_000, 1_000), owner);
        assertEquals(AbilityLimitLedger.Reason.TARGET_RATE, sameTarget.reason());
        assertNull(sameTarget.retryAfterMillis(), "retry details stay omitted when the wait exceeds 30 seconds");
        assertEquals(charged, ledger.snapshotForTests(), "target-rate denial creates no partial state");

        AbilityLimitLedger.ReserveResult unrelatedPair = ledger.reserve(lightningWithIntervals(now,
                1_713, 1_723, 1_733, id(1_743), id(1_744), 30_000, 30_000, 1_000), owner);
        assertTrue(unrelatedPair.reserved(), "an unrelated requester/target passes after the shared character interval");
        assertTrue(ledger.release(unrelatedPair.token(), owner));
        AbilityLimitLedger.Snapshot afterUnrelatedRelease = ledger.snapshotForTests();

        now.set(11_000_000_000L + TimeUnit.SECONDS.toNanos(61));
        AbilityLimitLedger.ReserveResult retainedLongInterval = ledger.reserve(lightningWithIntervals(now,
                1_714, 1_724, 1_734, sharedTarget, id(1_745), 30_000, 30_000, 1_000), owner);
        assertEquals(AbilityLimitLedger.Reason.TARGET_RATE, retainedLongInterval.reason());
        assertEquals(29_000L, retainedLongInterval.retryAfterMillis(),
                "the previous 90-second target interval remains effective after the current 30-second interval");
        assertEquals(afterUnrelatedRelease, ledger.snapshotForTests());

        now.set(11_000_000_000L + TimeUnit.SECONDS.toNanos(90));
        AbilityLimitLedger.ReserveResult exactPriorBoundary = ledger.reserve(lightningWithIntervals(now,
                1_715, 1_725, 1_735, sharedTarget, id(1_746), 30_000, 30_000, 1_000), owner);
        assertTrue(exactPriorBoundary.reserved(), "the exact maximum prior/current target interval is inclusive");
        assertTrue(ledger.release(exactPriorBoundary.token(), owner));
    }

    @Test
    void requesterNodeLastSlotHasOneConcurrentWinnerAndNoLosingNode() throws Exception {
        AtomicLong now = new AtomicLong(12_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        for (int i = 0; i < AbilityLimitLedger.MAX_REQUESTERS_PER_KIND - 1; i++) {
            AbilityLimitLedger.Demand demand = observeWithIntervals(now, 2_000 + i, 3_000 + i,
                    4_000 + i, id(5_000 + i), 2_000, 250);
            AbilityLimitLedger.Token token = ledger.reserve(demand, owner).token();
            assertTrue(ledger.consume(token, owner, demand).started());
            assertEquals(AbilityLimitLedger.SettlementResult.SETTLED,
                    ledger.settle(token, owner, AbilityLimitLedger.Settlement.CONFIRMED, true));
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(250));
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            AbilityLimitLedger.Demand left = observeWithIntervals(now, 2_100, 3_100, 4_100, id(5_100),
                    2_000, 250);
            AbilityLimitLedger.Demand right = observeWithIntervals(now, 2_101, 3_101, 4_101, id(5_101),
                    2_000, 250);
            Future<AbilityLimitLedger.ReserveResult> first = workers.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
                return ledger.reserve(left, owner);
            });
            Future<AbilityLimitLedger.ReserveResult> second = workers.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
                return ledger.reserve(right, owner);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            AbilityLimitLedger.ReserveResult a = first.get(5, TimeUnit.SECONDS);
            AbilityLimitLedger.ReserveResult b = second.get(5, TimeUnit.SECONDS);
            assertNotEquals(a.reserved(), b.reserved());
            AbilityLimitLedger.ReserveResult winner = a.reserved() ? a : b;
            AbilityLimitLedger.ReserveResult loser = a.reserved() ? b : a;
            assertEquals(AbilityLimitLedger.Reason.CAPACITY, loser.reason());
            AbilityLimitLedger.Snapshot held = ledger.snapshotForTests();
            assertEquals(AbilityLimitLedger.MAX_REQUESTERS_PER_KIND, held.observeRequesterNodes());
            assertEquals(1, held.liveHolds());
            assertEquals(AbilityLimitLedger.MAX_REQUESTERS_PER_KIND, held.identityRecords());
            assertTrue(ledger.release(winner.token(), owner));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void targetNodeLastSlotCannotInstallTwoTargetsOrPartialState() throws Exception {
        AtomicLong now = new AtomicLong(13_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        for (int i = 0; i < AbilityLimitLedger.MAX_LIGHTNING_TARGETS - 1; i++) {
            int requester = 6_000 + i % AbilityLimitLedger.MAX_REQUESTERS_PER_KIND;
            AbilityLimitLedger.Demand demand = lightningWithIntervals(now, requester, 7_000 + i,
                    8_000 + i, id(9_000 + i), id(10_000 + i), 30_000, 30_000, 1_000);
            if (i > 0) now.addAndGet(TimeUnit.SECONDS.toNanos(1));
            AbilityLimitLedger.Token token = ledger.reserve(demand, owner).token();
            assertTrue(ledger.consume(token, owner, demand).started());
            assertEquals(AbilityLimitLedger.SettlementResult.SETTLED,
                    ledger.settle(token, owner, AbilityLimitLedger.Settlement.CONFIRMED, true));
        }
        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            AbilityLimitLedger.Demand left = lightningWithIntervals(now, 6_000, 7_300, 8_300,
                    id(11_000), id(11_100), 30_000, 30_000, 1_000);
            AbilityLimitLedger.Demand right = lightningWithIntervals(now, 6_001, 7_301, 8_301,
                    id(11_001), id(11_101), 30_000, 30_000, 1_000);
            Future<AbilityLimitLedger.ReserveResult> first = workers.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
                return ledger.reserve(left, owner);
            });
            Future<AbilityLimitLedger.ReserveResult> second = workers.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
                return ledger.reserve(right, owner);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            AbilityLimitLedger.ReserveResult a = first.get(5, TimeUnit.SECONDS);
            AbilityLimitLedger.ReserveResult b = second.get(5, TimeUnit.SECONDS);
            assertNotEquals(a.reserved(), b.reserved());
            AbilityLimitLedger.ReserveResult winner = a.reserved() ? a : b;
            AbilityLimitLedger.ReserveResult loser = a.reserved() ? b : a;
            assertEquals(AbilityLimitLedger.Reason.CONCURRENCY_LIMITED, loser.reason(),
                    "the atomic character slot rejects the losing reservation without adding its target");
            AbilityLimitLedger.Snapshot lastSlot = ledger.snapshotForTests();
            assertEquals(AbilityLimitLedger.MAX_LIGHTNING_TARGETS, lastSlot.lightningTargetNodes());
            assertEquals(AbilityLimitLedger.MAX_REQUESTERS_PER_KIND, lastSlot.lightningRequesterNodes());
            assertEquals(1, lastSlot.liveHolds());
            assertEquals(AbilityLimitLedger.MAX_LIGHTNING_TARGETS, lastSlot.identityRecords());
            assertTrue(ledger.release(winner.token(), owner));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }

        AbilityLimitLedger.Snapshot full = ledger.snapshotForTests();
        AbilityLimitLedger.Demand targetOverflow = lightningWithIntervals(now, 6_000, 7_302, 8_302,
                id(11_002), id(11_102), 30_000, 30_000, 1_000);
        assertEquals(AbilityLimitLedger.Reason.CAPACITY, ledger.reserve(targetOverflow, owner).reason());
        AbilityLimitLedger.Demand requesterOverflow = lightningWithIntervals(now, 6_032, 7_303, 8_303,
                id(9_000), id(11_103), 30_000, 30_000, 1_000);
        assertEquals(AbilityLimitLedger.Reason.CAPACITY, ledger.reserve(requesterOverflow, owner).reason(),
                "lightning also retains the 32-requester ceiling when the target row already exists");
        assertEquals(full, ledger.snapshotForTests(), "neither over-capacity demand installs partial state");
    }

    @Test
    void holdExpiryReleasesTheReservationExactlyOnceWithoutCharging() {
        AtomicLong now = new AtomicLong(14_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        AbilityLimitLedger.Demand held = withDeadline(observe(now, 12_001, 12_101, 12_201),
                now.get() + TimeUnit.SECONDS.toNanos(1));
        AbilityLimitLedger.Token token = ledger.reserve(held, owner).token();
        assertTrue(ledger.isActive(token, owner));

        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertTrue(ledger.isActive(token, owner), "the unstarted hold remains live at its inclusive edge");
        now.incrementAndGet();
        assertEquals(AbilityLimitLedger.Reason.DEADLINE_EXPIRED,
                ledger.consume(token, owner, held).reason());
        assertFalse(ledger.isActive(token, owner));
        assertFalse(ledger.release(token, owner), "expiry is a one-shot release");
        assertEquals(0, ledger.snapshotForTests().liveHolds());
        assertEquals(0, ledger.snapshotForTests().chargedNodes(), "expiry consumes no requester or character rate");

        AbilityLimitLedger.Demand later = observe(now, 12_001, 12_102, 12_202);
        AbilityLimitLedger.ReserveResult retry = ledger.reserve(later, owner);
        assertTrue(retry.reserved(), "an expired pre-start hold restores requester and character allowance");
        assertTrue(ledger.release(retry.token(), owner));
    }

    @Test
    void unprovedUnknownTaskRetainsInflightUntilProvenButKeepsEndpointPins() {
        AtomicLong now = new AtomicLong(15_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        String pinnedRequester = id(12_301);
        String pinnedTarget = id(12_302);
        AbilityLimitLedger.Demand first = lightningWithIntervals(now, 12_301, 12_401, 12_501,
                pinnedTarget, id(12_601), 30_000, 30_000, 1_000);
        AbilityLimitLedger.Token token = ledger.reserve(first, owner).token();
        assertTrue(ledger.consume(token, owner, first).started());
        assertEquals(AbilityLimitLedger.SettlementResult.UNKNOWN_PINNED,
                ledger.settle(token, owner, AbilityLimitLedger.Settlement.UNKNOWN, false));

        now.addAndGet(TimeUnit.SECONDS.toNanos(31));
        AbilityLimitLedger.Snapshot stillLive = ledger.snapshotForTests();
        assertEquals(1, stillLive.inFlight(), "unknown without completion evidence retains its character slot");
        assertEquals(2, stillLive.unknownPins());
        assertEquals(AbilityLimitLedger.Reason.CONCURRENCY_LIMITED, ledger.reserve(lightningWithIntervals(now,
                12_301, 12_402, 12_502, id(12_303), id(12_602), 30_000, 30_000, 1_000), owner).reason(),
                "the unknown requester endpoint stays pinned");
        assertEquals(AbilityLimitLedger.Reason.CONCURRENCY_LIMITED, ledger.reserve(lightningWithIntervals(now,
                12_304, 12_403, 12_503, pinnedTarget, id(12_603), 30_000, 30_000, 1_000), owner).reason(),
                "the unknown target endpoint stays pinned");
        assertEquals(AbilityLimitLedger.Reason.CONCURRENCY_LIMITED, ledger.reserve(lightningWithIntervals(now,
                12_305, 12_404, 12_504, id(12_306), id(12_604), 30_000, 30_000, 1_000), owner).reason(),
                "the unresolved in-flight task retains the shared character slot");

        assertEquals(AbilityLimitLedger.SettlementResult.SETTLED,
                ledger.settle(token, owner, AbilityLimitLedger.Settlement.UNKNOWN, true),
                "only trusted completion evidence releases the in-flight capacity");
        AbilityLimitLedger.Snapshot completedUnknown = ledger.snapshotForTests();
        assertEquals(0, completedUnknown.inFlight());
        assertEquals(2, completedUnknown.unknownPins(), "completion proof does not resolve unknown endpoint outcomes");
        AbilityLimitLedger.ReserveResult unrelated = ledger.reserve(lightningWithIntervals(now,
                12_305, 12_405, 12_505, id(12_306), id(12_605), 30_000, 30_000, 1_000), owner);
        assertTrue(unrelated.reserved(), "an unrelated pair may proceed once the unknown task is proven complete");
        assertTrue(ledger.release(unrelated.token(), owner));
        assertEquals(2, ledger.snapshotForTests().unknownPins());
    }

    @Test
    void seventeenHoldCeilingAndCanonicalMetadataUpperBoundAreEnforced() {
        AtomicLong now = new AtomicLong(16_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        List<AbilityLimitLedger.Token> holds = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            AbilityLimitLedger.ReserveResult result = ledger.reserve(observe(now, 13_000 + i,
                    13_100 + i, 13_200 + i), owner);
            assertTrue(result.reserved());
            holds.add(result.token());
        }
        AbilityLimitLedger.ReserveResult lightningHold = ledger.reserve(
                lightning(now, 13_300, 13_400, 13_500, id(13_600), true), owner);
        assertTrue(lightningHold.reserved());
        holds.add(lightningHold.token());
        AbilityLimitLedger.Snapshot full = ledger.snapshotForTests();
        assertEquals(AbilityLimitLedger.MAX_LIVE_HOLDS, full.liveHolds());
        assertEquals(AbilityLimitLedger.Reason.CAPACITY,
                ledger.reserve(observe(now, 13_301, 13_401, 13_501), owner).reason());
        assertEquals(full, ledger.snapshotForTests(), "the 18th hold changes no ledger state");
        for (AbilityLimitLedger.Token token : holds) assertTrue(ledger.release(token, owner));

        AbilityLimitLedger.Demand maximum = maximumCanonicalLightningDemand();
        assertTrue(maximum.valid());
        AbilityLimitLedger.Demand maximumObserve = maximumCanonicalObserveDemand();
        assertTrue(maximumObserve.valid());
        int lightningIdentityBytes = maximum.identityMetadataBytes();
        int lightningConsequenceBytes = maximum.consequenceMetadataBytes();
        int lightningHoldBytes = maximum.holdMetadataBytes();
        int observeIdentityBytes = maximumObserve.identityMetadataBytes();
        int observeHoldBytes = maximumObserve.holdMetadataBytes();
        assertEquals(1_237, lightningIdentityBytes);
        assertEquals(1_240, lightningConsequenceBytes);
        assertEquals(1_233, lightningHoldBytes);
        assertEquals(957, observeIdentityBytes);
        assertEquals(953, observeHoldBytes);
        assertTrue(lightningConsequenceBytes >= lightningIdentityBytes);
        assertTrue(lightningIdentityBytes >= observeIdentityBytes);
        assertTrue(2L * (observeIdentityBytes + observeHoldBytes) >=
                lightningIdentityBytes + lightningConsequenceBytes,
                "two maximal Observe holds use one fewer record unit than a completed Lightning pair");
        int observeHolds = 16;
        int lightningHolds = 1;
        int remainingRecordUnits = AbilityLimitLedger.MAX_IDENTITY_RECORDS - observeHolds -
                2 * lightningHolds;
        assertEquals(0, remainingRecordUnits % 2);
        long canonicalBound = (long) (remainingRecordUnits / 2 + lightningHolds) *
                (lightningIdentityBytes + lightningConsequenceBytes) +
                (long) observeHolds * (observeIdentityBytes + observeHoldBytes) + lightningHoldBytes;
        assertEquals(1_280_201L, canonicalBound);
        assertTrue(canonicalBound < AbilityLimitLedger.MAX_METADATA_BYTES,
                "the 8-MiB metadata ceiling is unreachable under the canonical demand and count bounds");
        assertThrows(ArithmeticException.class,
                () -> AbilityLimitLedger.checkedMetadataAdd(Integer.MAX_VALUE, 1),
                "metadata additions remain overflow-checked before ledger mutation");
        assertThrows(ArithmeticException.class,
                () -> AbilityLimitLedger.checkedMetadataAdd(-1, 0),
                "negative accounting inputs fail closed");
    }

    @Test
    void recordCeilingAccountsBothReservedLightningRecordsBeforeMutatingState() {
        AtomicLong now = new AtomicLong(17_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        for (int operation = 0; operation < AbilityLimitLedger.MAX_IDENTITY_RECORDS - 1; operation++) {
            int group = operation / AbilityLimitLedger.MAX_QUERY_IDS_PER_LIVE_REQUEST;
            AbilityLimitLedger.Demand demand = observeWithIdentity(now, 14_000, id(15_000 + group),
                    16_000 + operation, id(17_000 + operation));
            AbilityLimitLedger.ReserveResult result = ledger.reserve(demand, owner);
            assertTrue(result.reserved(), "observation identity " + operation);
            assertTrue(ledger.release(result.token(), owner));
        }
        for (int sweep = 0; sweep < 8; sweep++) ledger.cleanupIncremental(32);
        AbilityLimitLedger.Snapshot full = ledger.snapshotForTests();
        assertEquals(AbilityLimitLedger.MAX_IDENTITY_RECORDS - 1, full.identityRecords());
        assertEquals(0, full.invocationRecords());

        AbilityLimitLedger.Demand lightningPair = lightningWithIntervals(now, 14_001, 18_001,
                18_101, id(18_201), id(18_301), 30_000, 30_000, 1_000);
        assertEquals(AbilityLimitLedger.Reason.CAPACITY, ledger.reserve(lightningPair, owner).reason(),
                "one free record cannot partially install a lightning identity without its reserved consequence");
        assertEquals(full, ledger.snapshotForTests());

        AbilityLimitLedger.Demand finalObserve = observeWithIdentity(now, 14_000, id(18_401),
                18_402, id(18_403));
        AbilityLimitLedger.ReserveResult lastSlot = ledger.reserve(finalObserve, owner);
        assertTrue(lastSlot.reserved(), "the final single record slot remains usable by Observe");
        assertTrue(ledger.release(lastSlot.token(), owner));
        for (int sweep = 0; sweep < 8; sweep++) ledger.cleanupIncremental(32);
        AbilityLimitLedger.Snapshot atLimit = ledger.snapshotForTests();
        assertEquals(AbilityLimitLedger.MAX_IDENTITY_RECORDS, atLimit.identityRecords());
        assertEquals(AbilityLimitLedger.Reason.CAPACITY,
                ledger.reserve(observeWithIdentity(now, 14_000, id(18_404), 18_405, id(18_406)), owner).reason());
        assertEquals(atLimit, ledger.snapshotForTests(), "the 1,025th record is rejected without mutation");
    }

    @Test
    void identityAndInvocationPairCeilingReachesExactly1024Records() {
        AtomicLong now = new AtomicLong(18_000_000_000L);
        AbilityLimitLedger ledger = ledger(now);
        Object owner = new Object();
        for (int operation = 0; operation < AbilityLimitLedger.MAX_IDENTITY_RECORDS / 2; operation++) {
            int requester = 19_000 + operation % AbilityLimitLedger.MAX_REQUESTERS_PER_KIND;
            int target = 20_000 + operation % 32;
            AbilityLimitLedger.Demand demand = lightningWithIntervals(now, requester, 21_000 + operation,
                    22_000 + operation, id(target), id(23_000 + operation), 30_000, 30_000, 1_000);
            if (operation > 0) now.addAndGet(TimeUnit.SECONDS.toNanos(1));
            AbilityLimitLedger.Token token = ledger.reserve(demand, owner).token();
            assertNotNull(token, "lightning operation " + operation);
            assertTrue(ledger.consume(token, owner, demand).started());
            assertEquals(AbilityLimitLedger.SettlementResult.SETTLED,
                    ledger.settle(token, owner, AbilityLimitLedger.Settlement.CONFIRMED, true));
        }
        AbilityLimitLedger.Snapshot full = ledger.snapshotForTests();
        assertEquals(AbilityLimitLedger.MAX_IDENTITY_RECORDS / 2, full.identityRecords());
        assertEquals(AbilityLimitLedger.MAX_IDENTITY_RECORDS / 2, full.invocationRecords());
        assertEquals(AbilityLimitLedger.MAX_IDENTITY_RECORDS,
                full.identityRecords() + full.invocationRecords());
        now.addAndGet(TimeUnit.SECONDS.toNanos(1));
        AbilityLimitLedger.Demand overflow = lightningWithIntervals(now, 19_000, 21_600, 22_600,
                id(20_001), id(23_600), 30_000, 30_000, 1_000);
        assertEquals(AbilityLimitLedger.Reason.CAPACITY, ledger.reserve(overflow, owner).reason());
        assertEquals(full, ledger.snapshotForTests(), "the over-capacity lightning pair installs neither record nor hold");
    }

    private static AbilityLimitLedger ledger(AtomicLong now) {
        AbilityLimitLedger ledger = new AbilityLimitLedger(now::get);
        assertTrue(ledger.bind("server-test", "world-test", "character-test"));
        return ledger;
    }

    private static AbilityLimitLedger.Demand observe(AtomicLong now, int requester, int request, int invocation) {
        return observeWithIdentity(now, requester, id(request), invocation, id(request));
    }

    private static AbilityLimitLedger.Demand observeWithIdentity(AtomicLong now, int requester, String requestId,
                                                                 int invocation, String operationId) {
        return observeWithIntervals(now, requester, requestId, invocation, operationId, 2_000, 250);
    }

    private static AbilityLimitLedger.Demand observeWithIntervals(AtomicLong now, int requester, int request,
                                                                   int invocation, String operationId,
                                                                   long requesterIntervalMillis,
                                                                   long characterIntervalMillis) {
        return observeWithIntervals(now, requester, id(request), invocation, operationId,
                requesterIntervalMillis, characterIntervalMillis);
    }

    private static AbilityLimitLedger.Demand observeWithIntervals(AtomicLong now, int requester, String requestId,
                                                                   int invocation, String operationId,
                                                                   long requesterIntervalMillis,
                                                                   long characterIntervalMillis) {
        return new AbilityLimitLedger.Demand(bindingForLedger, HASH, EPOCH, 1, 1, 1,
                AbilityLimitLedger.Kind.OBSERVE, id(requester), id(901), requestId, id(invocation), operationId,
                "observe-grant", 1, true, null, TimeUnit.MILLISECONDS.toNanos(requesterIntervalMillis), 0,
                TimeUnit.MILLISECONDS.toNanos(characterIntervalMillis), now.get() + TimeUnit.SECONDS.toNanos(60), 150);
    }

    private static final AbilityLimitLedger.Binding bindingForLedger = new AbilityLimitLedger.Binding(
            "server-test", "world-test", "character-test");

    private static AbilityLimitLedger.Demand lightning(AtomicLong now, int requester, int request,
                                                        int invocation, String target, boolean visualOnly) {
        return lightningWithIntervals(now, requester, request, invocation, target, id(request),
                30_000, 30_000, 1_000, visualOnly);
    }

    private static AbilityLimitLedger.Demand lightningWithIntervals(AtomicLong now, int requester, int request,
                                                                     int invocation, String target,
                                                                     String operationId,
                                                                     long requesterIntervalMillis,
                                                                     long targetIntervalMillis,
                                                                     long characterIntervalMillis) {
        return lightningWithIntervals(now, requester, request, invocation, target, operationId,
                requesterIntervalMillis, targetIntervalMillis, characterIntervalMillis, true);
    }

    private static AbilityLimitLedger.Demand lightningWithIntervals(AtomicLong now, int requester, int request,
                                                                     int invocation, String target,
                                                                     String operationId,
                                                                     long requesterIntervalMillis,
                                                                     long targetIntervalMillis,
                                                                     long characterIntervalMillis,
                                                                     boolean visualOnly) {
        AbilityWorkLimits.Placement placement = new AbilityWorkLimits.Placement("minecraft:overworld",
                0, 64, 0, 0.5, 64, 0.5, -4, 5, -4, 5, 4);
        return new AbilityLimitLedger.Demand(bindingForLedger, HASH, EPOCH, 1, 1, 1,
                AbilityLimitLedger.Kind.LIGHTNING, id(requester), target, id(request), id(invocation), operationId,
                "lightning-grant", 1, visualOnly, placement,
                TimeUnit.MILLISECONDS.toNanos(requesterIntervalMillis),
                TimeUnit.MILLISECONDS.toNanos(targetIntervalMillis),
                TimeUnit.MILLISECONDS.toNanos(characterIntervalMillis),
                now.get() + TimeUnit.SECONDS.toNanos(60), 180);
    }

    private static AbilityLimitLedger.Demand maximumCanonicalLightningDemand() {
        AbilityLimitLedger.Binding maximumBinding = new AbilityLimitLedger.Binding(
                "s".repeat(128), "w".repeat(128), "c".repeat(128));
        int feet = Integer.MIN_VALUE + 4;
        int feetY = Integer.MIN_VALUE + 1;
        String dimension = "a".repeat(63) + ":" + "b".repeat(64);
        AbilityWorkLimits.Placement placement = new AbilityWorkLimits.Placement(dimension, feet,
                feetY, feet, feet + 0.5, feetY, feet + 0.5,
                Integer.MIN_VALUE, feet + 5, Integer.MIN_VALUE, feet + 5, 1);
        return new AbilityLimitLedger.Demand(maximumBinding, "f".repeat(64), EPOCH,
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, AbilityLimitLedger.Kind.LIGHTNING,
                id(24_001), id(24_002), id(24_003), id(24_004), id(24_005), "g".repeat(128),
                Long.MAX_VALUE, false, placement, TimeUnit.MILLISECONDS.toNanos(300_000),
                TimeUnit.MILLISECONDS.toNanos(300_000), TimeUnit.MILLISECONDS.toNanos(300_000),
                Long.MAX_VALUE, AbilityWorkLimits.MAX_REQUEST_BYTES);
    }

    private static AbilityLimitLedger.Demand maximumCanonicalObserveDemand() {
        AbilityLimitLedger.Binding maximumBinding = new AbilityLimitLedger.Binding(
                "s".repeat(128), "w".repeat(128), "c".repeat(128));
        return new AbilityLimitLedger.Demand(maximumBinding, "f".repeat(64), EPOCH,
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, AbilityLimitLedger.Kind.OBSERVE,
                id(24_011), id(24_012), id(24_013), id(24_014), id(24_015), "g".repeat(128),
                Long.MAX_VALUE, true, null, TimeUnit.MILLISECONDS.toNanos(300_000), 0,
                TimeUnit.MILLISECONDS.toNanos(300_000), Long.MAX_VALUE, AbilityWorkLimits.MAX_REQUEST_BYTES);
    }

    private static AbilityLimitLedger.Demand withDeadline(AbilityLimitLedger.Demand demand, long deadline) {
        return new AbilityLimitLedger.Demand(demand.binding(), demand.configurationHash(), demand.serverEpochId(),
                demand.serviceGeneration(), demand.gateGeneration(), demand.peerGeneration(), demand.kind(),
                demand.requesterId(), demand.targetId(), demand.requestId(), demand.invocationId(),
                demand.identityId(), demand.selectedGrantId(), demand.policyRevision(), demand.visualOnly(),
                demand.placement(), demand.requesterIntervalNanos(), demand.targetIntervalNanos(),
                demand.characterIntervalNanos(), deadline, demand.encodedIntentBytes());
    }

    private static AbilityLimitLedger.Demand withTarget(AbilityLimitLedger.Demand demand, String targetId) {
        return new AbilityLimitLedger.Demand(demand.binding(), demand.configurationHash(), demand.serverEpochId(),
                demand.serviceGeneration(), demand.gateGeneration(), demand.peerGeneration(), demand.kind(),
                demand.requesterId(), targetId, demand.requestId(), demand.invocationId(), demand.identityId(),
                demand.selectedGrantId(), demand.policyRevision(), demand.visualOnly(), demand.placement(),
                demand.requesterIntervalNanos(), demand.targetIntervalNanos(), demand.characterIntervalNanos(),
                demand.deadlineAtNanos(), demand.encodedIntentBytes());
    }

    private static String id(int suffix) {
        return "00000000-0000-4000-8000-%012d".formatted(suffix);
    }
}
