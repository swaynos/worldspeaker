package org.worldspeaker.bootstrap;

import java.util.UUID;

/** Stateless admission policy; duplicate notices can only refer to live admitted work. */
final class SmokeRequestAdmission {
    private SmokeRequestAdmission() {}

    static Decision decide(UUID playerId, String text, long capturedAtNanos, Iterable<LiveRequest> liveRequests,
                           int playerPending, int globalPending, int maxPlayerPending, int maxGlobalPending) {
        for (LiveRequest request : liveRequests) {
            long elapsedNanos = capturedAtNanos - request.capturedAtNanos();
            if (request.serviceable() && request.playerId().equals(playerId) && request.text().equals(text) &&
                    elapsedNanos >= 0) {
                return Decision.COALESCE;
            }
        }
        if (playerPending >= maxPlayerPending || globalPending >= maxGlobalPending) {
            return Decision.REJECT_CAPACITY;
        }
        return Decision.ADMIT;
    }

    enum Decision {
        ADMIT,
        COALESCE,
        REJECT_CAPACITY
    }

    record LiveRequest(UUID playerId, String text, long capturedAtNanos, boolean serviceable) {
        LiveRequest(UUID playerId, String text, long capturedAtNanos) {
            this(playerId, text, capturedAtNanos, true);
        }
    }
}
