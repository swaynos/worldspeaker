package org.worldspeaker.bootstrap;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** One-shot, request-owned safe terminal disposition for an outage or context failure. */
final class SmokeOutageRetirement {
    static final String TERMINAL_TEXT =
            "Worldspeaker became unavailable before completing this request. This request is closed and will not resume automatically.";

    private State state = State.NEW;
    private long outageGeneration = -1;
    private String terminalNoticeId;

    /** Captures the gate epoch once; later gate changes cannot reschedule this request. */
    boolean schedule(long generation, BooleanSupplier unfinishedRequest) {
        Objects.requireNonNull(unfinishedRequest, "unfinishedRequest");
        if (state != State.NEW || generation < 0 || !unfinishedRequest.getAsBoolean()) return false;
        outageGeneration = generation;
        terminalNoticeId = UUID.randomUUID().toString();
        state = State.PENDING;
        return true;
    }

    boolean isPending() {
        return state == State.PENDING;
    }

    long outageGeneration() {
        return outageGeneration;
    }

    String terminalNoticeId() {
        return terminalNoticeId;
    }

    /** Used only when bounded queue admission fails; the request is still retired by its caller. */
    void suppress() {
        if (state == State.PENDING) state = State.SUPPRESSED;
    }

    /**
     * Retires and publishes within the caller's already-budgeted tick operation. The late-terminal
     * path deliberately bypasses first-reply accounting and answer freshness, but still receives the
     * current-original-audience check supplied by production.
     */
    Dispatch retireAndPublish(BooleanSupplier unfinishedRequest, BooleanSupplier retireRequest,
                              FirstReplyScheduler scheduler, FirstReplyScheduler.Slot firstReplySlot,
                              BooleanSupplier currentOriginalAudience,
                              Supplier<FirstReplyScheduler.PublicationResult> firstDispositionPublisher,
                              Function<String, FirstReplyScheduler.PublicationResult> lateTerminalPublisher) {
        Objects.requireNonNull(unfinishedRequest, "unfinishedRequest");
        Objects.requireNonNull(retireRequest, "retireRequest");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(firstReplySlot, "firstReplySlot");
        Objects.requireNonNull(currentOriginalAudience, "currentOriginalAudience");
        Objects.requireNonNull(firstDispositionPublisher, "firstDispositionPublisher");
        Objects.requireNonNull(lateTerminalPublisher, "lateTerminalPublisher");

        if (state != State.PENDING) return dispatch(Outcome.ALREADY_HANDLED, Path.NONE, null);
        if (!unfinishedRequest.getAsBoolean()) {
            state = State.SUPPRESSED;
            return dispatch(Outcome.REQUEST_FINISHED, Path.NONE, null);
        }
        if (!retireRequest.getAsBoolean()) {
            state = State.SUPPRESSED;
            return dispatch(Outcome.RETIREMENT_FAILED, Path.NONE, null);
        }

        // Once retirement starts, no reconnect or repeated outage callback may send a second notice.
        state = State.SUPPRESSED;
        if (!currentOriginalAudience.getAsBoolean()) {
            return dispatch(Outcome.NO_AUDIENCE, Path.NONE, null);
        }

        FirstReplyScheduler.Claim claim = scheduler.claimDisposition(firstReplySlot, true);
        if (claim == FirstReplyScheduler.Claim.CLAIMED_FIRST) {
            FirstReplyScheduler.PublicationResult result = publish(firstDispositionPublisher);
            scheduler.complete(firstReplySlot, claim, result, FirstReplyScheduler.PublicationKind.DISPOSITION);
            state = stateFor(result);
            return dispatch(outcomeFor(result), Path.FIRST_PUBLICATION, result);
        }

        // A committed or unknown first send must not suppress a distinct known-outcome terminal.
        // This notice has its own stable ID and never replays the ACK or an integration answer.
        FirstReplyScheduler.PublicationResult result = publish(() -> lateTerminalPublisher.apply(terminalNoticeId));
        state = stateFor(result);
        return dispatch(outcomeFor(result), Path.TERMINAL, result);
    }

    private FirstReplyScheduler.PublicationResult publish(Supplier<FirstReplyScheduler.PublicationResult> publisher) {
        try {
            FirstReplyScheduler.PublicationResult result = publisher.get();
            return result == null ? FirstReplyScheduler.PublicationResult.UNKNOWN : result;
        } catch (RuntimeException publicationUnknown) {
            return FirstReplyScheduler.PublicationResult.UNKNOWN;
        }
    }

    private Dispatch dispatch(Outcome outcome, Path path, FirstReplyScheduler.PublicationResult result) {
        return new Dispatch(outageGeneration, terminalNoticeId, outcome, path, result);
    }

    private static State stateFor(FirstReplyScheduler.PublicationResult result) {
        return switch (result) {
            case SUBMITTED -> State.SUBMITTED;
            case UNKNOWN -> State.UNKNOWN;
            case DEFINITELY_UNSENT -> State.DEFINITELY_UNSENT;
        };
    }

    private static Outcome outcomeFor(FirstReplyScheduler.PublicationResult result) {
        return switch (result) {
            case SUBMITTED -> Outcome.SUBMITTED;
            case UNKNOWN -> Outcome.UNKNOWN;
            case DEFINITELY_UNSENT -> Outcome.DEFINITELY_UNSENT;
        };
    }

    enum State { NEW, PENDING, SUPPRESSED, SUBMITTED, UNKNOWN, DEFINITELY_UNSENT }
    enum Outcome { SUBMITTED, UNKNOWN, DEFINITELY_UNSENT, NO_AUDIENCE, REQUEST_FINISHED, RETIREMENT_FAILED, ALREADY_HANDLED }
    enum Path { NONE, FIRST_PUBLICATION, TERMINAL }

    record Dispatch(long outageGeneration, String terminalNoticeId, Outcome outcome, Path path,
                    FirstReplyScheduler.PublicationResult publicationResult) {}
}
