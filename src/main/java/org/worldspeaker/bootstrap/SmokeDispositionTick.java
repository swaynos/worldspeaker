package org.worldspeaker.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Interleaves the ordered callback queue with request deadlines under one tick budget. */
final class SmokeDispositionTick {
    private SmokeDispositionTick() {}

    static <T> boolean forEachDueAction(SmokeDeadlineScan.Scan<T> deadlines,
                                        FirstReplyScheduler scheduler,
                                        SmokeTickBudget budget,
                                        LongSupplier clock,
                                        int opposingWorkPending,
                                        Predicate<SmokeDeadlineScan.Evaluation<T>> visitor) {
        Objects.requireNonNull(deadlines, "deadlines");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(visitor, "visitor");

        if (scheduler.pendingDispositionCount() > 0 && budget.mayCheckDeadline()) budget.deadlineChecked();
        List<SmokeDeadlineScan.Evaluation<T>> ordered = deadlines.orderedEvaluations();
        int index = 0;
        while (true) {
            while (index < ordered.size() && ordered.get(index).action() == SmokeDeadlineScan.Action.NONE) index++;
            SmokeDeadlineScan.Evaluation<T> evaluation = index < ordered.size() ? ordered.get(index) : null;
            Long dispositionDeadline = scheduler.nextDispositionDeadlineNanos();
            boolean dispositionIsNext = dispositionDeadline != null && (evaluation == null ||
                    SmokeDeadlineScan.Scan.priority(evaluation.action()) > 0 ||
                    SmokeDeadlineScan.Scan.priority(evaluation.action()) == 0 &&
                            dispositionDeadline - SmokeDeadlineScan.actionDeadline(evaluation) <= 0);
            if (dispositionIsNext) {
                if (!budget.mayStartOperation(clock.getAsLong(), SmokeTickBudget.WorkClass.REPLY,
                        opposingWorkPending)) return false;
                budget.operationStarted(SmokeTickBudget.WorkClass.REPLY);
                scheduler.dispatchNextDisposition();
                continue;
            }
            if (evaluation == null) return true;
            index++;
            if (!visitor.test(evaluation)) return false;
        }
    }
}
