package org.worldspeaker.bootstrap;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Production first-publication arbiter. The invocation slot is shared by ingress, mailbox, and dispatch. */
public final class FirstReplyScheduler {
    public static final int MAX_DEFERRED_DISPOSITIONS = SmokeDispositionQueue.CAPACITY;
    private static final FirstReplyScheduler SYSTEM = new FirstReplyScheduler(System::nanoTime, System::currentTimeMillis);
    private static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final LongSupplier monotonicClock;
    private final LongSupplier wallClock;
    // Enqueue and drain are server-thread operations. Worker threads only use the atomic
    // publication slots above and never acquire or mutate this queue.
    private final SmokeDispositionQueue<QueuedDisposition> dispositions = new SmokeDispositionQueue<>();
    private long nextDispositionOrder;

    public FirstReplyScheduler(LongSupplier monotonicClock, LongSupplier wallClock) {
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
    }

    public static FirstReplyScheduler system() { return SYSTEM; }
    public long monotonicNanos() { return monotonicClock.getAsLong(); }
    public long wallTimeMillis() { return wallClock.getAsLong(); }
    public Slot createSlot(long capturedAtNanos, String captureProvenance) {
        return new Slot(capturedAtNanos, captureProvenance);
    }

    /** Enqueues one bounded callback disposition without claiming publication or reporting a send. */
    public boolean enqueueDisposition(long originalDeadlineNanos, Runnable dispatch) {
        return enqueueDispositionAt(monotonicNanos(), originalDeadlineNanos, dispatch);
    }

    /** Queues a future disposition with separate eligibility and original first-reply deadline times. */
    public boolean enqueueDispositionAt(long notBeforeNanos, long originalDeadlineNanos, Runnable dispatch) {
        Objects.requireNonNull(dispatch, "dispatch");
        if (nextDispositionOrder == Long.MAX_VALUE) return false;
        long stableOrder = nextDispositionOrder;
        QueuedDisposition queued = new QueuedDisposition(dispatch);
        if (!dispositions.offer(queued, notBeforeNanos, originalDeadlineNanos, stableOrder)) return false;
        nextDispositionOrder++;
        return true;
    }

    /** Moves a joined notice batch earlier without granting it a fresh first-reply window. */
    boolean updateDispositionDeadline(Runnable dispatch, long originalDeadlineNanos) {
        if (dispatch == null) return false;
        return dispositions.updateDeadlineMatching(queued -> queued.dispatch() == dispatch, originalDeadlineNanos);
    }

    /** Removes an unstarted queued batch during bounded reservation rollback. */
    boolean cancelDisposition(Runnable dispatch) {
        if (dispatch == null) return false;
        return dispositions.removeMatching(queued -> queued.dispatch() == dispatch);
    }

    /**
     * Drains only work that can start within the caller's existing tick allowance. The
     * queue is deadline ordered, and each send consumes one start from that shared budget.
     */
    public int drainDispositions(SmokeTickBudget budget, int opposingWorkPending) {
        Objects.requireNonNull(budget, "budget");
        if (opposingWorkPending < 0) throw new IllegalArgumentException("opposing work count cannot be negative");
        int started = 0;
        while (dispositions.peekReadyDeadline(monotonicNanos()) != null &&
                budget.mayStartOperation(monotonicNanos(), SmokeTickBudget.WorkClass.REPLY,
                        opposingWorkPending)) {
            budget.operationStarted(SmokeTickBudget.WorkClass.REPLY);
            if (!dispatchNextDisposition()) break;
            started++;
        }
        return started;
    }

    public int pendingDispositionCount() { return dispositions.size(); }

    /** Original deadline of the earliest eligible item, or null if all pending items are still deferred. */
    public Long nextDispositionDeadlineNanos() {
        return dispositions.peekReadyDeadline(monotonicNanos());
    }

    /** Caller must first charge the send to its active shared tick budget. */
    boolean dispatchNextDisposition() {
        QueuedDisposition queued = dispositions.removeReady(monotonicNanos());
        if (queued == null) return false;
        queued.dispatch().run();
        return true;
    }

    public static long firstReplyDeadlineNanos(long capturedAtNanos) {
        return capturedAtNanos + DEADLINE_NANOS;
    }

    /** Fallback may be considered at the 750 ms cutoff, but never claims before that instant. */
    public boolean fallbackDue(Slot slot, long nowNanos, long dueAtNanos) {
        Objects.requireNonNull(slot, "slot");
        return nowNanos - dueAtNanos >= 0 && slot.openForFirstPublication();
    }

    /** Called after a complete bounded terminal validates and before it enters the mailbox. */
    public Claim offerTerminal(Slot slot) {
        Objects.requireNonNull(slot, "slot");
        while (true) {
            SlotState current = slot.state.get();
            if (current.state == State.OPEN) {
                if (slot.state.compareAndSet(current, new SlotState(State.TERMINAL_READY, null))) {
                    return Claim.FIRST_AVAILABLE;
                }
                continue;
            }
            return current.state == State.TERMINAL_READY ? Claim.FIRST_AVAILABLE : Claim.LATE_TERMINAL;
        }
    }

    /** Must run on the server publication path immediately before submitting the ACK. */
    public Claim claimAcknowledgement(Slot slot) {
        return claim(slot, State.ACK_CLAIMED, false);
    }

    /** A complete ready terminal can claim first publication; a post-ACK terminal remains later dialogue. */
    public Claim claimTerminal(Slot slot) {
        Objects.requireNonNull(slot, "slot");
        while (true) {
            SlotState current = slot.state.get();
            if (current.state == State.OPEN || current.state == State.TERMINAL_READY) {
                String publicationId = UUID.randomUUID().toString();
                if (slot.state.compareAndSet(current, new SlotState(State.TERMINAL_CLAIMED, publicationId))) {
                    return Claim.CLAIMED_FIRST;
                }
                continue;
            }
            return Claim.LATE_TERMINAL;
        }
    }

    /** Safe failure/outage publication may replace an unclaimed ready answer, but cannot race an ACK claim. */
    public Claim claimDisposition(Slot slot, boolean outage) {
        Objects.requireNonNull(slot, "slot");
        while (true) {
            SlotState current = slot.state.get();
            if (current.state == State.OPEN || (outage && current.state == State.TERMINAL_READY)) {
                String publicationId = UUID.randomUUID().toString();
                if (slot.state.compareAndSet(current, new SlotState(State.DISPOSITION_CLAIMED, publicationId))) {
                    return Claim.CLAIMED_FIRST;
                }
                continue;
            }
            if (current.state == State.TERMINAL_READY) return Claim.TERMINAL_READY;
            return current.state == State.SETTLED_NO_DISPATCH ? Claim.SETTLED : Claim.ALREADY_CLAIMED;
        }
    }

    private Claim claim(Slot slot, State claimed, boolean replaceTerminalReady) {
        Objects.requireNonNull(slot, "slot");
        while (true) {
            SlotState current = slot.state.get();
            if (current.state == State.TERMINAL_READY && !replaceTerminalReady) return Claim.TERMINAL_READY;
            if (current.state == State.OPEN) {
                String publicationId = UUID.randomUUID().toString();
                if (slot.state.compareAndSet(current, new SlotState(claimed, publicationId))) return Claim.CLAIMED_FIRST;
                continue;
            }
            return current.state == State.SETTLED_NO_DISPATCH ? Claim.SETTLED : Claim.ALREADY_CLAIMED;
        }
    }

    /** Result is recorded only after the local connection submission returned successfully. */
    public void complete(Slot slot, Claim claim, PublicationResult result, PublicationKind kind) {
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(kind, "kind");
        if (claim != Claim.CLAIMED_FIRST) return;
        State claimed = switch (kind) {
            case ACKNOWLEDGEMENT -> State.ACK_CLAIMED;
            case TERMINAL -> State.TERMINAL_CLAIMED;
            case DISPOSITION -> State.DISPOSITION_CLAIMED;
        };
        State submitted = switch (kind) {
            case ACKNOWLEDGEMENT -> State.ACK_SUBMITTED;
            case TERMINAL -> State.TERMINAL_SUBMITTED;
            case DISPOSITION -> State.DISPOSITION_SUBMITTED;
        };
        while (true) {
            SlotState current = slot.state.get();
            if (current.state != claimed) return;
            SlotState next = switch (result) {
                case SUBMITTED -> new SlotState(submitted, current.publicationId);
                case DEFINITELY_UNSENT -> new SlotState(State.OPEN, null);
                case UNKNOWN -> new SlotState(State.UNKNOWN, current.publicationId);
            };
            if (slot.state.compareAndSet(current, next)) return;
        }
    }

    /** Exactly-once outcome when the invocation's one-second deadline is settled without confirmed t1. */
    public boolean settleNoDispatch(Slot slot) {
        Objects.requireNonNull(slot, "slot");
        while (true) {
            SlotState current = slot.state.get();
            if (current.state != State.OPEN && current.state != State.TERMINAL_READY &&
                    current.state != State.NOTICE_PENDING) return false;
            if (current.state == State.NOTICE_PENDING && slot.hasFirstPublication()) return false;
            if (slot.state.compareAndSet(current, new SlotState(State.SETTLED_NO_DISPATCH, null))) return true;
        }
    }

    public enum Claim { CLAIMED_FIRST, TERMINAL_READY, FIRST_AVAILABLE, LATE_TERMINAL, ALREADY_CLAIMED, SETTLED }
    public enum PublicationKind { ACKNOWLEDGEMENT, TERMINAL, DISPOSITION }
    public enum PublicationResult { SUBMITTED, DEFINITELY_UNSENT, UNKNOWN }

    /** One identity shared by a bounded notice batch and all of its retained evidence rows. */
    public static final class NoticePublication {
        private final String publicationId = UUID.randomUUID().toString();
        private final AtomicReference<NoticeResult> result = new AtomicReference<>(
                new NoticeResult(NoticeState.PENDING, null, null));

        public String publicationId() { return publicationId; }
        public NoticeResult result() { return result.get(); }

        boolean claim() {
            while (true) {
                NoticeResult current = result.get();
                if (current.state() != NoticeState.PENDING) return false;
                if (result.compareAndSet(current, new NoticeResult(NoticeState.CLAIMED, null, null))) return true;
            }
        }

        boolean complete(PublicationResult sendResult, long attemptedAtNanos) {
            Objects.requireNonNull(sendResult, "sendResult");
            while (true) {
                NoticeResult current = result.get();
                if (current.state() != NoticeState.CLAIMED) return false;
                if (result.compareAndSet(current, new NoticeResult(stateFor(sendResult), sendResult,
                        attemptedAtNanos))) return true;
            }
        }

        boolean cancel() {
            while (true) {
                NoticeResult current = result.get();
                if (current.state() != NoticeState.PENDING) return false;
                if (result.compareAndSet(current, new NoticeResult(NoticeState.CANCELLED, null, null))) return true;
            }
        }

        private static NoticeState stateFor(PublicationResult result) {
            return switch (result) {
                case SUBMITTED -> NoticeState.SUBMITTED;
                case DEFINITELY_UNSENT -> NoticeState.DEFINITELY_UNSENT;
                case UNKNOWN -> NoticeState.UNKNOWN;
            };
        }
    }

    public enum NoticeState { PENDING, CLAIMED, SUBMITTED, DEFINITELY_UNSENT, UNKNOWN, CANCELLED }

    /** attemptedAtNanos is null unless the publication port was actually invoked. */
    public record NoticeResult(NoticeState state, PublicationResult sendResult, Long attemptedAtNanos) {}

    private enum State {
        OPEN,
        TERMINAL_READY,
        NOTICE_PENDING,
        ACK_CLAIMED,
        TERMINAL_CLAIMED,
        DISPOSITION_CLAIMED,
        ACK_SUBMITTED,
        TERMINAL_SUBMITTED,
        DISPOSITION_SUBMITTED,
        UNKNOWN,
        SETTLED_NO_DISPATCH
    }

    private record SlotState(State state, String publicationId) {}

    private record QueuedDisposition(Runnable dispatch) {}

    public static final class Slot {
        private final long capturedAtNanos;
        private final String captureProvenance;
        private final AtomicReference<SlotState> state = new AtomicReference<>(new SlotState(State.OPEN, null));
        private volatile NoticePublication noticePublication;

        public Slot(long capturedAtNanos, String captureProvenance) {
            this.capturedAtNanos = capturedAtNanos;
            this.captureProvenance = Objects.requireNonNull(captureProvenance, "captureProvenance");
        }

        public long capturedAtNanos() { return capturedAtNanos; }
        public String captureProvenance() { return captureProvenance; }
        public String publicationId() {
            String currentId = state.get().publicationId;
            if (currentId != null) return currentId;
            NoticePublication linked = noticePublication;
            if (linked == null) return null;
            NoticeState noticeState = linked.result().state();
            return noticeState == NoticeState.SUBMITTED || noticeState == NoticeState.UNKNOWN
                    ? linked.publicationId() : null;
        }
        public boolean terminalReady() { return state.get().state == State.TERMINAL_READY; }
        public boolean openForFirstPublication() {
            State value = state.get().state;
            return value == State.OPEN || value == State.TERMINAL_READY;
        }
        public boolean hasFirstPublication() {
            State value = state.get().state;
            if (value == State.ACK_SUBMITTED || value == State.TERMINAL_SUBMITTED ||
                    value == State.DISPOSITION_SUBMITTED || value == State.UNKNOWN) return true;
            if (value == State.SETTLED_NO_DISPATCH) return false;
            NoticePublication linked = noticePublication;
            if (linked == null) return false;
            NoticeState noticeState = linked.result().state();
            return noticeState == NoticeState.SUBMITTED || noticeState == NoticeState.UNKNOWN;
        }
        public boolean unknown() { return state.get().state == State.UNKNOWN; }
        public boolean settledNoDispatch() { return state.get().state == State.SETTLED_NO_DISPATCH; }
        public boolean deadlineMissed(long nowNanos) { return nowNanos - (capturedAtNanos + DEADLINE_NANOS) >= 0; }

        boolean linkNoticePublication(NoticePublication publication) {
            Objects.requireNonNull(publication, "publication");
            if (noticePublication != null) return false;
            while (true) {
                SlotState current = state.get();
                if (current.state != State.OPEN) return false;
                if (state.compareAndSet(current, new SlotState(State.NOTICE_PENDING, null))) {
                    noticePublication = publication;
                    return true;
                }
            }
        }
    }
}
