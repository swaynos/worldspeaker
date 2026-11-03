package org.worldspeaker.bootstrap;

/** Finite per-tick start and combined-deadline-record budgets; started work is non-preemptible. */
public final class SmokeTickBudget {
    public static final int MAX_OPERATIONS = 8;
    public static final int MAX_DEADLINE_RECORDS = 17;
    public static final long MAX_START_NANOS = 1_000_000L;

    private final long startedAtNanos;
    private int operations;
    private int deadlineRecords;
    private int replyOperations;
    private int reservedOperations;

    public SmokeTickBudget(long startedAtNanos) {
        this.startedAtNanos = startedAtNanos;
    }

    public boolean mayStartOperation(long nowNanos) {
        return operations < MAX_OPERATIONS && nowNanos - startedAtNanos < MAX_START_NANOS;
    }

    /** Reserve up to six starts for first replies and two for expiry/context/forwarding when both are due. */
    public boolean mayStartOperation(long nowNanos, WorkClass workClass, int opposingWorkPending) {
        if (!mayStartOperation(nowNanos)) return false;
        if (opposingWorkPending <= 0) return true;
        return switch (workClass) {
            case REPLY -> replyOperations < MAX_OPERATIONS - Math.min(2, opposingWorkPending);
            case RESERVED -> reservedOperations < MAX_OPERATIONS - Math.min(6, opposingWorkPending);
            case LOAN -> true;
        };
    }

    public boolean mayCheckDeadline() {
        return deadlineRecords < MAX_DEADLINE_RECORDS;
    }

    public void operationStarted() {
        operationStarted(WorkClass.LOAN);
    }

    public void operationStarted(WorkClass workClass) {
        if (operations >= MAX_OPERATIONS) throw new IllegalStateException("per-tick operation budget exhausted");
        operations++;
        switch (workClass) {
            case REPLY -> replyOperations++;
            case RESERVED -> reservedOperations++;
            case LOAN -> { }
        }
    }

    public void deadlineChecked() {
        if (deadlineRecords >= MAX_DEADLINE_RECORDS) throw new IllegalStateException("per-tick deadline scan exhausted");
        deadlineRecords++;
    }

    public int operationsStarted() { return operations; }
    public int deadlineRecordsChecked() { return deadlineRecords; }
    public int replyOperationsStarted() { return replyOperations; }
    public int reservedOperationsStarted() { return reservedOperations; }

    public enum WorkClass { REPLY, RESERVED, LOAN }
}
