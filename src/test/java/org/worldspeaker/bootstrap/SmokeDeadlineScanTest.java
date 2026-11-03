package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeDeadlineScanTest {
    @Test
    void fullCapacityChecksOneCombinedRecordForEachOfSeventeenRequests() {
        List<SmokeDeadlineScan.Deadline<Integer>> deadlines = new ArrayList<>();
        for (int request = 0; request < 17; request++) {
            deadlines.add(new SmokeDeadlineScan.Deadline<>(request, true, 10_000,
                    request == 0 ? SmokeDeadlineScan.Expiry.ACTIVE : SmokeDeadlineScan.Expiry.PENDING,
                    20_000));
        }
        SmokeTickBudget budget = new SmokeTickBudget(1);

        SmokeDeadlineScan.Scan<Integer> scan = SmokeDeadlineScan.scan(deadlines, () -> 1, 0, budget);

        assertEquals(17, scan.recordCount());
        assertEquals(17, budget.deadlineRecordsChecked());
        Set<Integer> checked = new HashSet<>();
        for (SmokeDeadlineScan.Evaluation<Integer> evaluation : scan.evaluations()) {
            checked.add(evaluation.deadline().request());
        }
        assertEquals(17, checked.size());
        assertEquals(0, scan.evaluations().stream().filter(evaluation -> evaluation.action() != SmokeDeadlineScan.Action.NONE).count());
    }

    @Test
    void anExpiredRequestChoosesExpiryInsteadOfPublishingItsOverdueFirstReply() {
        SmokeDeadlineScan.Deadline<String> deadline = new SmokeDeadlineScan.Deadline<>(
                "pending", true, 750, SmokeDeadlineScan.Expiry.PENDING, 15_000);
        SmokeTickBudget budget = new SmokeTickBudget(0);
        List<SmokeDeadlineScan.Action> actions = new ArrayList<>();

        SmokeDeadlineScan.Scan<String> scan = SmokeDeadlineScan.scan(List.of(deadline), () -> 15_000, 0, budget);
        scan.forEachDueAction(evaluation -> {
            actions.add(evaluation.action());
            return true;
        });

        assertEquals(List.of(SmokeDeadlineScan.Action.PENDING_EXPIRY), actions);
        assertEquals(750, deadline.firstReplyDeadlineNanos());
        assertEquals(15_000, deadline.expiryDeadlineNanos());

        SmokeDeadlineScan.Deadline<String> acknowledgedRequest = new SmokeDeadlineScan.Deadline<>(
                "already-acknowledged", false, 750, SmokeDeadlineScan.Expiry.PENDING, 15_000);
        assertEquals(SmokeDeadlineScan.Action.PENDING_EXPIRY,
                actionAt(acknowledgedRequest, 15_000));
    }

    @Test
    void firstRepliesRunBeforePendingAndActiveExpiriesWithoutStarvingOtherRecords() {
        List<SmokeDeadlineScan.Deadline<String>> deadlines = List.of(
                due("pending-expiry", SmokeDeadlineScan.Expiry.PENDING, 1),
                due("active-expiry", SmokeDeadlineScan.Expiry.ACTIVE, 1),
                due("first-reply", SmokeDeadlineScan.Expiry.NONE, 1));
        SmokeDeadlineScan.Scan<String> scan = SmokeDeadlineScan.scan(deadlines, () -> 2, 0,
                new SmokeTickBudget(0));
        List<String> actionOrder = new ArrayList<>();

        scan.forEachDueAction(evaluation -> {
            actionOrder.add(evaluation.deadline().request());
            return true;
        });

        assertEquals(List.of("first-reply", "pending-expiry", "active-expiry"), actionOrder);
    }

    @Test
    void rotatingCursorCoversOverflowCandidatesOnTheNextBoundedScan() {
        List<SmokeDeadlineScan.Deadline<Integer>> deadlines = new ArrayList<>();
        for (int request = 0; request < 20; request++) {
            deadlines.add(due(request, SmokeDeadlineScan.Expiry.PENDING, 1));
        }
        SmokeDeadlineScan.Scan<Integer> first = SmokeDeadlineScan.scan(deadlines, () -> 2, 0,
                new SmokeTickBudget(0));
        SmokeDeadlineScan.Scan<Integer> second = SmokeDeadlineScan.scan(deadlines, () -> 2, first.nextCursor(),
                new SmokeTickBudget(0));
        Set<Integer> checked = new HashSet<>();
        for (SmokeDeadlineScan.Evaluation<Integer> evaluation : first.evaluations()) {
            checked.add(evaluation.deadline().request());
        }
        for (SmokeDeadlineScan.Evaluation<Integer> evaluation : second.evaluations()) {
            checked.add(evaluation.deadline().request());
        }

        assertEquals(17, first.recordCount());
        assertEquals(17, second.recordCount());
        assertEquals(20, checked.size());
        assertTrue(checked.contains(19));
    }

    @Test
    void rotatingPriorityScanDoesNotStarveDueActionsAtTheEightOperationLimit() {
        List<SmokeDeadlineScan.Deadline<Integer>> deadlines = new ArrayList<>();
        for (int request = 0; request < 20; request++) {
            deadlines.add(due(request, SmokeDeadlineScan.Expiry.PENDING, 1));
        }
        Set<Integer> serviced = new HashSet<>();
        int cursor = 0;

        for (int tick = 0; tick < 6; tick++) {
            SmokeDeadlineScan.Scan<Integer> scan = SmokeDeadlineScan.scan(deadlines, () -> 2, cursor,
                    new SmokeTickBudget(0));
            int[] operations = {0};
            scan.forEachDueAction(evaluation -> {
                operations[0]++;
                serviced.add(evaluation.deadline().request());
                return operations[0] < SmokeTickBudget.MAX_OPERATIONS;
            });
            assertEquals(SmokeTickBudget.MAX_OPERATIONS, operations[0]);
            cursor = scan.nextCursor();
        }

        assertEquals(20, serviced.size());
    }

    @Test
    void firstReplyCutoffAndExpiryUseInclusiveMonotonicBoundaries() {
        SmokeDeadlineScan.Deadline<String> deadline = new SmokeDeadlineScan.Deadline<>(
                "request", true, 750_000_000L, SmokeDeadlineScan.Expiry.PENDING, 15_000_000_000L);

        assertEquals(SmokeDeadlineScan.Action.NONE, actionAt(deadline, 749_999_999L));
        assertEquals(SmokeDeadlineScan.Action.FIRST_REPLY, actionAt(deadline, 750_000_000L));
        assertEquals(SmokeDeadlineScan.Action.FIRST_REPLY, actionAt(deadline, 1_000_000_000L));
        assertEquals(SmokeDeadlineScan.Action.PENDING_EXPIRY, actionAt(deadline, 15_000_000_000L));
        assertEquals(750_000_000L, deadline.firstReplyDeadlineNanos());
        assertEquals(15_000_000_000L, deadline.expiryDeadlineNanos());
    }

    @Test
    void clockCrossingTheFirstReplyCutoffDuringTheScanIsObservedPerRecord() {
        List<SmokeDeadlineScan.Deadline<Integer>> deadlines = List.of(
                new SmokeDeadlineScan.Deadline<>(0, true, 750, SmokeDeadlineScan.Expiry.PENDING, 15_000),
                new SmokeDeadlineScan.Deadline<>(1, true, 750, SmokeDeadlineScan.Expiry.PENDING, 15_000));
        AtomicInteger clockReads = new AtomicInteger();

        SmokeDeadlineScan.Scan<Integer> scan = SmokeDeadlineScan.scan(deadlines,
                () -> clockReads.getAndIncrement() == 0 ? 749 : 750, 0, new SmokeTickBudget(0));

        assertEquals(SmokeDeadlineScan.Action.NONE, scan.evaluations().get(0).action());
        assertEquals(SmokeDeadlineScan.Action.FIRST_REPLY, scan.evaluations().get(1).action());
        assertEquals(2, clockReads.get());
    }

    private static <T> SmokeDeadlineScan.Deadline<T> due(T request, SmokeDeadlineScan.Expiry expiry,
                                                         long expiryAt) {
        boolean firstReplyPending = "first-reply".equals(request);
        return new SmokeDeadlineScan.Deadline<>(request, firstReplyPending, 1, expiry, expiryAt);
    }

    private static SmokeDeadlineScan.Action actionAt(SmokeDeadlineScan.Deadline<String> deadline, long now) {
        return SmokeDeadlineScan.scan(List.of(deadline), () -> now, 0, new SmokeTickBudget(0))
                .evaluations().getFirst().action();
    }
}
