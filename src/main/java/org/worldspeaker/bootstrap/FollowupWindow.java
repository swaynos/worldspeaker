package org.worldspeaker.bootstrap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Bounded, answer-anchored recognition state for one local conversation service. */
public final class FollowupWindow {
    public static final int MAX_RECORDS = 32;
    public static final int MAX_SECONDS = 30;

    private final int durationSeconds;
    private final long durationNanos;
    private final Map<UUID, State> states = new HashMap<>();
    private int cleanupCursor;

    public FollowupWindow(int durationSeconds) {
        if (durationSeconds < 0 || durationSeconds > MAX_SECONDS) {
            throw new IllegalArgumentException("followupWindowSeconds must be between 0 and 30");
        }
        this.durationSeconds = durationSeconds;
        this.durationNanos = TimeUnit.SECONDS.toNanos(durationSeconds);
    }

    public int durationSeconds() {
        return durationSeconds;
    }

    /** Starts a new local revision and invalidates any previous window/candidate for this player. */
    public Candidate beginExplicit(Context context, UUID invocationId, InteractionIngress.Origin origin,
                                   long capturedAtNanos) {
        return beginExplicit(context, invocationId, origin, capturedAtNanos, UUID.randomUUID().toString());
    }

    public Candidate beginExplicit(Context context, UUID invocationId, InteractionIngress.Origin origin,
                                   long capturedAtNanos, String conversationId) {
        if (origin == InteractionIngress.Origin.FOLLOWUP) return null;
        if (context == null || invocationId == null || origin == null ||
                origin != InteractionIngress.Origin.COMMAND && origin != InteractionIngress.Origin.NATURAL_ADDRESS ||
                !isUuid(conversationId)) {
            throw new IllegalArgumentException("an attributed explicit invocation is required");
        }
        State previous = states.get(context.playerId());
        if (durationSeconds == 0) {
            states.remove(context.playerId());
            return null;
        }
        if (previous == null && states.size() >= MAX_RECORDS) return null;

        long revision = previous == null ? 1 : nextRevision(previous.revision);
        State current = new State(context, revision);
        states.put(context.playerId(), current);
        return new Candidate(current, revision, invocationId, origin, capturedAtNanos, conversationId);
    }

    /** Opens only the still-current explicit candidate after its complete answer was delivered. */
    public boolean open(Candidate candidate, Context currentContext, long openedAtNanos) {
        return open(candidate, currentContext, openedAtNanos, UUID.randomUUID().toString());
    }

    /** Opens an answer-anchored window after that complete answer was successfully delivered. */
    public boolean open(Candidate candidate, Context currentContext, long openedAtNanos, String answerMessageId) {
        if (candidate == null || currentContext == null || durationSeconds == 0) return false;
        if (!isUuid(answerMessageId)) return false;
        State current = states.get(candidate.context.playerId());
        if (current != candidate.state || current.revision != candidate.revision || current.window != null ||
                !current.context.sameIdentity(currentContext)) {
            if (current == candidate.state) states.remove(candidate.context.playerId());
            return false;
        }
        long sinceInvocation = openedAtNanos - candidate.capturedAtNanos;
        if (sinceInvocation < 0) {
            states.remove(candidate.context.playerId());
            return false;
        }
        current.window = new Window(candidate.invocationId, candidate.origin, candidate.capturedAtNanos,
                openedAtNanos, durationNanos, candidate.conversationId, answerMessageId);
        return true;
    }

    /** Checks both callback-capture time and the later pre-ingress time against the fixed deadline. */
    public boolean isOpen(Context currentContext, long capturedAtNanos, long beforeIngressNanos) {
        if (currentContext == null || durationSeconds == 0) return false;
        UUID playerId = currentContext.playerId();
        State current = states.get(playerId);
        if (current == null) return false;
        if (!current.context.sameIdentity(currentContext)) {
            states.remove(playerId);
            return false;
        }
        Window window = current.window;
        if (window == null) return false;
        if (!within(window.openedAtNanos, capturedAtNanos, window.durationNanos) ||
                !within(window.openedAtNanos, beforeIngressNanos, window.durationNanos)) {
            states.remove(playerId);
            return false;
        }
        return true;
    }

    /** Returns the immutable conversation/answer anchor only after full live-context and time checks. */
    public Anchor anchor(Context currentContext, long capturedAtNanos, long beforeIngressNanos) {
        if (!isOpen(currentContext, capturedAtNanos, beforeIngressNanos)) return null;
        Window window = states.get(currentContext.playerId()).window;
        return window == null ? null : new Anchor(window.conversationId, window.answerMessageId);
    }

    /** Invalidates both an open window and any delayed answer candidate. */
    public void invalidate(UUID playerId) {
        if (playerId != null) states.remove(playerId);
    }

    /** Drops a failed/rejected delayed answer candidate without affecting a newer revision. */
    public void abandon(Candidate candidate) {
        if (candidate == null) return;
        State current = states.get(candidate.context.playerId());
        if (current == candidate.state && current.revision == candidate.revision) {
            states.remove(candidate.context.playerId());
        }
    }

    /** Connection departure has a bounded scan of at most MAX_RECORDS entries. */
    public void invalidateConnection(Object connection) {
        if (connection == null) return;
        Iterator<Map.Entry<UUID, State>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().context.connection() == connection) iterator.remove();
        }
    }

    public void clear() {
        states.clear();
        cleanupCursor = 0;
    }

    /** Removes at most one expired record; callers charge this to the existing tick budget. */
    public boolean cleanupOne(long nowNanos) {
        int size = states.size();
        if (size == 0) {
            cleanupCursor = 0;
            return false;
        }
        int target = Math.floorMod(cleanupCursor++, size);
        Iterator<Map.Entry<UUID, State>> iterator = states.entrySet().iterator();
        for (int index = 0; index < target; index++) iterator.next();
        Map.Entry<UUID, State> entry = iterator.next();
        Window window = entry.getValue().window;
        if (window == null || within(window.openedAtNanos, nowNanos, window.durationNanos)) return false;
        iterator.remove();
        return true;
    }

    public int size() {
        return states.size();
    }

    private static long nextRevision(long previous) {
        return previous == Long.MAX_VALUE ? 1 : previous + 1;
    }

    private static boolean within(long openedAtNanos, long nowNanos, long durationNanos) {
        long elapsed = nowNanos - openedAtNanos;
        return elapsed >= 0 && elapsed < durationNanos;
    }

    private static boolean isUuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public record Anchor(String conversationId, String replyToMessageId) {
        public Anchor {
            if (!isUuid(conversationId) || !isUuid(replyToMessageId)) {
                throw new IllegalArgumentException("complete answer anchor is required");
            }
        }
    }

    /** Identity-sensitive facts; object equality is deliberately insufficient for server/player/connection. */
    public record Context(UUID playerId, Object server, Object player, Object connection,
                          String serverId, String worldId, String characterId, String agentId,
                          String dimension, Object serviceEpoch, long serviceGeneration, long gateGeneration) {
        public Context {
            if (playerId == null || server == null || player == null || connection == null ||
                    serverId == null || worldId == null || characterId == null || agentId == null || dimension == null ||
                    serviceEpoch == null || serviceGeneration < 0 || gateGeneration < 0) {
                throw new IllegalArgumentException("complete follow-up attribution is required");
            }
        }

        private boolean sameIdentity(Context other) {
            return other != null && playerId.equals(other.playerId) && server == other.server && player == other.player &&
                    connection == other.connection && serverId.equals(other.serverId) && worldId.equals(other.worldId) &&
                    characterId.equals(other.characterId) && agentId.equals(other.agentId) && dimension.equals(other.dimension) &&
                    serviceEpoch == other.serviceEpoch && serviceGeneration == other.serviceGeneration &&
                    gateGeneration == other.gateGeneration;
        }
    }

    public static final class Candidate {
        private final State state;
        private final Context context;
        private final long revision;
        private final UUID invocationId;
        private final InteractionIngress.Origin origin;
        private final long capturedAtNanos;
        private final String conversationId;

        private Candidate(State state, long revision, UUID invocationId, InteractionIngress.Origin origin,
                          long capturedAtNanos, String conversationId) {
            this.state = state;
            this.context = state.context;
            this.revision = revision;
            this.invocationId = invocationId;
            this.origin = origin;
            this.capturedAtNanos = capturedAtNanos;
            this.conversationId = conversationId;
        }

        public long revision() { return revision; }
        public UUID invocationId() { return invocationId; }
        public InteractionIngress.Origin origin() { return origin; }
        public long capturedAtNanos() { return capturedAtNanos; }
        public String conversationId() { return conversationId; }
    }

    private static final class State {
        private final Context context;
        private final long revision;
        private Window window;

        private State(Context context, long revision) {
            this.context = context;
            this.revision = revision;
        }
    }

    private record Window(UUID invocationId, InteractionIngress.Origin origin, long capturedAtNanos,
                          long openedAtNanos, long durationNanos, String conversationId, String answerMessageId) {}
}
