package org.worldspeaker.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable boundary between trusted server lifecycle reads and conversation policy.
 * Only typed callbacks in this package and offline tests can construct one.
 */
record TrustedLifecycleContext(Mode mode, Cause cause, UUID playerId,
                               List<ConversationFreshnessPipeline.LifecycleChange> changes,
                               PlayerFreshness.Observation observation, Object attentionSource,
                               String dimension, AttentionEvent.EventKind eventKind,
                               AttentionEvent.Details details, long capturedAtNanos,
                               long occurredAtUnixMillis) {
    TrustedLifecycleContext {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(cause, "cause");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        if (playerId == null || capturedAtNanos < 0 || occurredAtUnixMillis < 0) {
            throw new IllegalArgumentException("trusted lifecycle identity or time is invalid");
        }
        if ((eventKind == null) != (details == null) || eventKind != null && dimension == null) {
            throw new IllegalArgumentException("trusted lifecycle attention context is incomplete");
        }
        if (mode == Mode.CALLBACK && changes.isEmpty() || mode == Mode.SAMPLE && !changes.isEmpty()) {
            throw new IllegalArgumentException("trusted lifecycle mode does not match its snapshots");
        }
        if (mode == Mode.SAMPLE && cause != Cause.BLOCK_MOVEMENT && cause != Cause.SNAPSHOT_UNAVAILABLE) {
            throw new IllegalArgumentException("trusted sample cause is invalid");
        }
        if (mode == Mode.CALLBACK && (cause == Cause.BLOCK_MOVEMENT || cause == Cause.SNAPSHOT_UNAVAILABLE)) {
            throw new IllegalArgumentException("trusted callback cause is invalid");
        }
    }

    static TrustedLifecycleContext callback(Cause cause, UUID playerId,
                                            List<ConversationFreshnessPipeline.LifecycleChange> changes,
                                            Object attentionSource, String dimension,
                                            AttentionEvent.EventKind eventKind, AttentionEvent.Details details,
                                            long capturedAtNanos, long occurredAtUnixMillis) {
        return new TrustedLifecycleContext(Mode.CALLBACK, cause, playerId, changes, null, attentionSource,
                dimension, eventKind, details, capturedAtNanos, occurredAtUnixMillis);
    }

    static TrustedLifecycleContext sample(Cause cause, UUID playerId,
                                          PlayerFreshness.Observation observation, Object attentionSource,
                                          String dimension, AttentionEvent.EventKind eventKind,
                                          AttentionEvent.Details details, long capturedAtNanos,
                                          long occurredAtUnixMillis) {
        return new TrustedLifecycleContext(Mode.SAMPLE, cause, playerId, List.of(), observation,
                attentionSource, dimension, eventKind, details, capturedAtNanos, occurredAtUnixMillis);
    }

    /** Test factory: supplies immutable game observations, never a server/player authority object. */
    static TrustedLifecycleContext forTestingCallback(Cause cause, UUID playerId,
                                                       PlayerFreshness.Observation current,
                                                       Object departedEntity, Object departedConnection,
                                                       Object attentionSource, String dimension,
                                                       AttentionEvent.EventKind eventKind,
                                                       AttentionEvent.Details details,
                                                       long capturedAtNanos, long occurredAtUnixMillis) {
        var change = new ConversationFreshnessPipeline.LifecycleChange(playerId,
                current == null ? null : new ConversationFreshnessPipeline.CurrentSnapshot(playerId, current),
                departedEntity, departedConnection, cause.reason());
        return callback(cause, playerId, List.of(change), attentionSource, dimension, eventKind, details,
                capturedAtNanos, occurredAtUnixMillis);
    }

    /** Test factory shares the production sampler and its fail-closed snapshot path. */
    static TrustedLifecycleContext forTestingSample(Cause cause, UUID playerId,
                                                     PlayerFreshness.Observation observation,
                                                     Object attentionSource, String dimension,
                                                     AttentionEvent.EventKind eventKind,
                                                     AttentionEvent.Details details,
                                                     long capturedAtNanos, long occurredAtUnixMillis) {
        return sample(cause, playerId, observation, attentionSource, dimension, eventKind, details,
                capturedAtNanos, occurredAtUnixMillis);
    }

    enum Mode { CALLBACK, SAMPLE }

    enum Cause {
        PLAYER_JOINED("PLAYER_JOINED"),
        DIMENSION_CHANGED("DIMENSION_CHANGED"),
        DEATH("DEATH"),
        RESPAWN("RESPAWN"),
        ENTITY_REPLACED("ENTITY_REPLACED"),
        DISCONNECT("DISCONNECT"),
        CONTEXT_CHANGED("CONTEXT_CHANGED"),
        BLOCK_MOVEMENT("BLOCK_MOVEMENT"),
        SNAPSHOT_UNAVAILABLE("SNAPSHOT_UNAVAILABLE");

        private final String reason;

        Cause(String reason) { this.reason = reason; }

        String reason() { return reason; }
    }
}
