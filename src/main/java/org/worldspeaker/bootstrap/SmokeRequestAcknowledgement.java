package org.worldspeaker.bootstrap;

/** Tracks whether a locally dispatched acknowledgement belongs on the request or a later frame. */
final class SmokeRequestAcknowledgement {
    private Acknowledgement acknowledgement;
    private boolean forwarded;
    private boolean closed;
    private boolean contextDeliveryAttempted;
    private boolean contextDeliveryPending;
    private boolean contextDeliveryFailed;

    Dispatch recordDispatched(boolean locallyDispatched, String messageId, String text, long wallMillis,
                              long monotonicNanos, String audiencePlayerId) {
        if (!locallyDispatched || closed || acknowledgement != null || messageId == null || text == null || text.isBlank()) {
            return Dispatch.NONE;
        }
        acknowledgement = new Acknowledgement(messageId, text, wallMillis, monotonicNanos, audiencePlayerId);
        return forwarded ? Dispatch.SEND_AFTER_REQUEST : Dispatch.ATTACH_TO_REQUEST;
    }

    Dispatch recordDispatched(boolean locallyDispatched, String messageId, String text) {
        return recordDispatched(locallyDispatched, messageId, text, System.currentTimeMillis(), System.nanoTime(), "00000000-0000-4000-8000-000000000000");
    }

    Acknowledgement forRequest() {
        return closed || forwarded ? null : acknowledgement;
    }

    boolean markForwarded() {
        if (closed || forwarded) return false;
        forwarded = true;
        return true;
    }

    boolean isForwarded() {
        return forwarded;
    }

    /** Begins the one bounded post-forward transport attempt for a locally published acknowledgement. */
    boolean beginContextDelivery() {
        if (closed || acknowledgement == null || !forwarded || contextDeliveryAttempted) return false;
        contextDeliveryAttempted = true;
        contextDeliveryPending = true;
        return true;
    }

    /** Completes the local WebSocket send operation; this is not proof of peer receipt or consumption. */
    boolean finishContextDelivery(boolean socketSendCompleted) {
        if (!contextDeliveryPending) return false;
        contextDeliveryPending = false;
        contextDeliveryFailed = !socketSendCompleted;
        return true;
    }

    boolean canStartNextTurn() {
        return !contextDeliveryPending && !contextDeliveryFailed;
    }

    boolean contextDeliveryPending() {
        return contextDeliveryPending;
    }

    boolean contextDeliveryFailed() {
        return contextDeliveryFailed;
    }

    Acknowledgement locallyDispatched() {
        return acknowledgement;
    }

    void close() {
        // Keep an already-started context attempt pending; its reserved completion owns the barrier.
        closed = true;
    }

    enum Dispatch {
        NONE,
        ATTACH_TO_REQUEST,
        SEND_AFTER_REQUEST
    }

    record Acknowledgement(String messageId, String text, long dispatchedAtUnixMillis,
                           long dispatchedAtMonotonicNanos, String audiencePlayerId) {}
}
