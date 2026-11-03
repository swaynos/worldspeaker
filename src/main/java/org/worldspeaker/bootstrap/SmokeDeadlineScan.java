package org.worldspeaker.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** One bounded scan record per live request, regardless of how many deadlines it owns. */
final class SmokeDeadlineScan {
    private SmokeDeadlineScan() {}

    enum Expiry { NONE, PENDING, ACTIVE }

    enum Action { NONE, DISPOSITION, FIRST_REPLY, PENDING_EXPIRY, ACTIVE_EXPIRY, PROGRESS }

    record Deadline<T>(T request, boolean firstReplyPending, long firstReplyDeadlineNanos,
                       Expiry expiry, long expiryDeadlineNanos, long progressDeadlineNanos,
                       long stableOrder, boolean dispositionPending) {
        Deadline {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(expiry, "expiry");
        }

        Deadline(T request, boolean firstReplyPending, long firstReplyDeadlineNanos,
                 Expiry expiry, long expiryDeadlineNanos) {
            this(request, firstReplyPending, firstReplyDeadlineNanos, expiry, expiryDeadlineNanos,
                    Long.MAX_VALUE, 0, false);
        }

        Deadline(T request, boolean firstReplyPending, long firstReplyDeadlineNanos,
                 Expiry expiry, long expiryDeadlineNanos, long progressDeadlineNanos) {
            this(request, firstReplyPending, firstReplyDeadlineNanos, expiry, expiryDeadlineNanos,
                    progressDeadlineNanos, 0, false);
        }

        Deadline(T request, boolean firstReplyPending, long firstReplyDeadlineNanos,
                 Expiry expiry, long expiryDeadlineNanos, long progressDeadlineNanos,
                 long stableOrder) {
            this(request, firstReplyPending, firstReplyDeadlineNanos, expiry, expiryDeadlineNanos,
                    progressDeadlineNanos, stableOrder, false);
        }
    }

    record Evaluation<T>(Deadline<T> deadline, Action action) {}

    static <T> Scan<T> scan(List<Deadline<T>> deadlines, LongSupplier clock, int cursor,
                            SmokeTickBudget budget) {
        Objects.requireNonNull(deadlines, "deadlines");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(budget, "budget");
        int candidateCount = deadlines.size();
        if (candidateCount == 0) return new Scan<>(List.of(), 0);

        int start = Math.floorMod(cursor, candidateCount);
        int limit = Math.min(candidateCount, SmokeTickBudget.MAX_DEADLINE_RECORDS);
        List<Evaluation<T>> evaluations = new ArrayList<>(limit);
        int index = start;
        for (int offset = 0; offset < limit && budget.mayCheckDeadline(); offset++) {
            Deadline<T> deadline = deadlines.get(index);
            budget.deadlineChecked();
            evaluations.add(new Evaluation<>(deadline, chooseAction(deadline, clock.getAsLong())));
            index = index + 1 == candidateCount ? 0 : index + 1;
        }
        return new Scan<>(evaluations, index);
    }

    private static int compareEvaluations(Evaluation<?> left, Evaluation<?> right) {
        int priority = Integer.compare(Scan.priority(left.action()), Scan.priority(right.action()));
        if (priority != 0) return priority;
        long leftDeadline = actionDeadline(left);
        long rightDeadline = actionDeadline(right);
        long difference = leftDeadline - rightDeadline;
        if (difference != 0) return difference < 0 ? -1 : 1;
        return Long.compare(left.deadline().stableOrder(), right.deadline().stableOrder());
    }

    static long actionDeadline(Evaluation<?> evaluation) {
        return switch (evaluation.action()) {
            case DISPOSITION -> evaluation.deadline().firstReplyDeadlineNanos();
            case FIRST_REPLY -> evaluation.deadline().firstReplyDeadlineNanos();
            case PENDING_EXPIRY, ACTIVE_EXPIRY -> evaluation.deadline().expiryDeadlineNanos();
            case PROGRESS -> evaluation.deadline().progressDeadlineNanos();
            case NONE -> Long.MAX_VALUE;
        };
    }

    private static Action chooseAction(Deadline<?> deadline, long nowNanos) {
        if (deadline.dispositionPending()) return Action.DISPOSITION;
        // Once a request has expired, do not publish an acknowledgement for work
        // that is already being retired. Among otherwise-live requests, first
        // replies are handled before either expiry kind.
        if (deadline.expiry() != Expiry.NONE && isDue(nowNanos, deadline.expiryDeadlineNanos())) {
            return deadline.expiry() == Expiry.PENDING ? Action.PENDING_EXPIRY : Action.ACTIVE_EXPIRY;
        }
        return deadline.firstReplyPending() && isDue(nowNanos, deadline.firstReplyDeadlineNanos())
                ? Action.FIRST_REPLY
                : isDue(nowNanos, deadline.progressDeadlineNanos()) ? Action.PROGRESS : Action.NONE;
    }

    static boolean isDue(long nowNanos, long deadlineNanos) {
        return nowNanos - deadlineNanos >= 0;
    }

    static final class Scan<T> {
        private final List<Evaluation<T>> evaluations;
        private final List<Evaluation<T>> orderedEvaluations;
        private final int nextCursor;

        private Scan(List<Evaluation<T>> evaluations, int nextCursor) {
            this.evaluations = List.copyOf(evaluations);
            List<Evaluation<T>> ordered = new ArrayList<>(evaluations);
            ordered.sort(SmokeDeadlineScan::compareEvaluations);
            this.orderedEvaluations = List.copyOf(ordered);
            this.nextCursor = nextCursor;
        }

        int recordCount() { return evaluations.size(); }

        int nextCursor() { return nextCursor; }

        List<Evaluation<T>> evaluations() { return evaluations; }

        List<Evaluation<T>> orderedEvaluations() { return orderedEvaluations; }

        /** Visits due actions by urgency without sorting or allocating another collection. */
        boolean forEachDueAction(Predicate<Evaluation<T>> visitor) {
            Objects.requireNonNull(visitor, "visitor");
            for (int priority = 0; priority < 4; priority++) {
                for (Evaluation<T> evaluation : orderedEvaluations) {
                    if (priority(evaluation.action()) == priority && evaluation.action() != Action.NONE &&
                            !visitor.test(evaluation)) return false;
                }
            }
            return true;
        }

        static int priority(Action action) {
            return switch (action) {
                case DISPOSITION, FIRST_REPLY -> 0;
                case PENDING_EXPIRY -> 1;
                case ACTIVE_EXPIRY -> 2;
                case PROGRESS -> 3;
                case NONE -> 4;
            };
        }
    }
}
