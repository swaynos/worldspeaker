package org.worldspeaker.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Production orchestration for mandatory conversation freshness. Game reads stop at the
 * immutable snapshot port; callback invalidation always precedes optional attention capture.
 */
final class ConversationFreshnessPipeline {
    private final PlayerFreshness freshness;
    private final CurrentSnapshotPort snapshots;
    private final BiConsumer<UUID, String> obsoleteRequests;

    ConversationFreshnessPipeline(PlayerFreshness freshness, CurrentSnapshotPort snapshots,
                                  BiConsumer<UUID, String> obsoleteRequests) {
        this.freshness = Objects.requireNonNull(freshness, "freshness");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.obsoleteRequests = Objects.requireNonNull(obsoleteRequests, "obsoleteRequests");
    }

    /** Captures the admission token and retires older work if lazy seeding detects a change. */
    PlayerFreshness.Snapshot captureAdmissionSnapshot(UUID playerId, PlayerFreshness.Observation observation) {
        if (!freshness.isScoped(playerId) || observation == null) return null;
        boolean changed = freshness.wouldChange(playerId, observation);
        PlayerFreshness.Snapshot snapshot = freshness.observe(playerId, observation);
        if (changed && snapshot != null) obsoleteRequests.accept(playerId, "CONTEXT_CHANGED");
        return snapshot;
    }

    /**
     * Applies one or more trusted lifecycle snapshots before attempting optional capture.
     * A stale disconnect only departs the exact entity/connection it retained.
     */
    void onCallback(List<LifecycleChange> changes, Runnable optionalCapture) {
        Objects.requireNonNull(changes, "changes");
        for (LifecycleChange change : changes) {
            if (change == null || !freshness.isScoped(change.playerId())) continue;
            CurrentSnapshot current = change.current();
            if (current != null && change.playerId().equals(current.playerId()) && current.observation() != null) {
                if (freshness.rotate(change.playerId(), current.observation()) != null) {
                    obsoleteRequests.accept(change.playerId(), change.reason());
                }
            } else if (freshness.depart(change.playerId(), change.departedEntity(), change.departedConnection())) {
                obsoleteRequests.accept(change.playerId(), change.reason());
            }
        }
        if (optionalCapture != null) optionalCapture.run();
    }

    /** Refreshes a sampled player before optionally recording the sampled block displacement. */
    SampleResult sample(UUID playerId, CurrentSnapshot current, Runnable movedCapture) {
        if (!freshness.isScoped(playerId)) return new SampleResult(false, false);
        if (current == null || !playerId.equals(current.playerId()) || current.observation() == null) {
            return new SampleResult(false, sourceUnavailable(playerId, null, null));
        }

        PlayerFreshness.Observation observation = current.observation();
        PlayerFreshness.Snapshot previous = freshness.current(playerId);
        boolean moved = previous != null && previous.dimension().equals(observation.dimension()) &&
                (previous.blockX() != observation.blockX() || previous.blockY() != observation.blockY() ||
                        previous.blockZ() != observation.blockZ());
        boolean changed = freshness.wouldChange(playerId, observation);
        PlayerFreshness.Snapshot updated = freshness.observe(playerId, observation);
        if (changed && updated != null) obsoleteRequests.accept(playerId, moved ? "BLOCK_MOVEMENT" : "CONTEXT_CHANGED");
        if (moved && movedCapture != null) movedCapture.run();
        return new SampleResult(moved, false);
    }

    /** Shared production check used immediately before forwarding and again at final output. */
    boolean isFresh(RequestSnapshot request, RuntimeSnapshot runtime) {
        if (request == null || runtime == null || request.obsolete() || request.freshnessSnapshot() == null ||
                !request.playerId().equals(request.freshnessSnapshot().playerId()) ||
                !freshness.isScoped(request.playerId()) ||
                request.serviceEpoch() != runtime.serviceEpoch() ||
                request.serviceGeneration() != runtime.serviceGeneration() ||
                request.gateGeneration() != runtime.gateGeneration()) return false;

        CurrentSnapshot current = snapshots.current(request.playerId());
        if (current == null || !request.playerId().equals(current.playerId()) || current.observation() == null) {
            sourceUnavailable(request.playerId(), null, null);
            return false;
        }
        if (freshness.isFresh(request.freshnessSnapshot(), current.observation())) return true;
        obsoleteRequests.accept(request.playerId(), "CONTEXT_CHANGED");
        return false;
    }

    private boolean sourceUnavailable(UUID playerId, Object entity, Object connection) {
        if (!freshness.depart(playerId, entity, connection)) return false;
        obsoleteRequests.accept(playerId, "SNAPSHOT_UNAVAILABLE");
        return true;
    }

    @FunctionalInterface
    interface CurrentSnapshotPort {
        CurrentSnapshot current(UUID playerId);
    }

    record CurrentSnapshot(UUID playerId, PlayerFreshness.Observation observation) {}

    record LifecycleChange(UUID playerId, CurrentSnapshot current, Object departedEntity,
                           Object departedConnection, String reason) {
        LifecycleChange(UUID playerId, CurrentSnapshot current, Object departedEntity, Object departedConnection) {
            this(playerId, current, departedEntity, departedConnection, "CONTEXT_CHANGED");
        }

        LifecycleChange {
            if (reason == null || !reason.matches("[A-Z_]{1,32}")) {
                throw new IllegalArgumentException("freshness cause is invalid");
            }
        }
    }

    record RequestSnapshot(UUID playerId, PlayerFreshness.Snapshot freshnessSnapshot, Object serviceEpoch,
                           long serviceGeneration, long gateGeneration, boolean obsolete) {}

    record RuntimeSnapshot(Object serviceEpoch, long serviceGeneration, long gateGeneration) {}

    record SampleResult(boolean moved, boolean sourceUnavailable) {}
}
