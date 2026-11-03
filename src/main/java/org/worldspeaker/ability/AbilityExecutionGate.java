package org.worldspeaker.ability;

import org.worldspeaker.bootstrap.AbilityRequestAuthority;

/** Deliberately deny-only: this class has no game, scheduler, reservation, provider, or persistence dependency. */
public final class AbilityExecutionGate {
    private AbilityExecutionGate() {}

    public static Decision evaluate(AbilityValidation.AbilityTrustedContextHandle context,
                                    AbilityRequestAuthority.ExpectedContext expected,
                                    AbilityValidation.Input intent) {
        return evaluate(context, expected, intent, System.nanoTime());
    }

    public static Decision evaluate(AbilityValidation.AbilityTrustedContextHandle context,
                                    AbilityRequestAuthority.ExpectedContext expected,
                                    AbilityValidation.Input intent, long ownerMonotonicNanos) {
        return decision(AbilityRequestAuthority.evaluateGate(context, expected, intent, ownerMonotonicNanos));
    }

    /** Evaluates an owner-bound internal proposal; its query/action identity is not authority. */
    public static Decision evaluate(AbilityRequestAuthority.OperationProposal proposal,
                                    long ownerMonotonicNanos) {
        return decision(AbilityRequestAuthority.evaluateProposal(proposal, ownerMonotonicNanos));
    }

    private static Decision decision(AbilityRequestAuthority.GateCheck check) {
        if (check.staleContext()) return new Decision(State.REJECTED, Reason.STALE_CONTEXT, false);
        if (check.permissionDenied()) return new Decision(State.REJECTED, Reason.PERMISSION_DENIED, false);
        Reason reason = switch (check.cause()) {
            case RATE_LIMITED -> Reason.RATE_LIMITED;
            case CONCURRENCY_LIMITED -> Reason.CONCURRENCY_LIMITED;
            case DEADLINE_EXPIRED -> Reason.DEADLINE_EXPIRED;
            case CANCELLED -> Reason.CANCELLED;
            default -> Reason.EXECUTION_UNAVAILABLE;
        };
        // A hold is accounting only. No selected operation is implemented here.
        return new Decision(State.REJECTED, reason, false, check.retryAfterMillis());
    }

    public enum State { REJECTED }
    public enum Reason {
        PERMISSION_DENIED, STALE_CONTEXT, RATE_LIMITED, CONCURRENCY_LIMITED,
        DEADLINE_EXPIRED, CANCELLED, EXECUTION_UNAVAILABLE
    }
    public record Decision(State state, Reason reason, boolean effectStarted, Long retryAfterMillis) {
        public Decision(State state, Reason reason, boolean effectStarted) {
            this(state, reason, effectStarted, null);
        }
        public Decision {
            if (state != State.REJECTED || reason == null || effectStarted ||
                    retryAfterMillis != null && (reason != Reason.RATE_LIMITED || retryAfterMillis < 0 ||
                            retryAfterMillis > 30_000)) {
                throw new IllegalArgumentException("deny-only decision invariant failed");
            }
        }
    }
}
