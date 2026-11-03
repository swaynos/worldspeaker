package org.worldspeaker.bootstrap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Finite, in-memory suppression for exact asks after their live request has ended. */
final class SmokeRepeatGuard {
    static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(6);
    static final int MAX_KEYS_PER_PLAYER = 8;
    static final int MAX_KEYS_GLOBAL = 64;

    private final Map<Key, Entry> entries = new HashMap<>();

    synchronized Check check(Key key, long nowNanos) {
        Objects.requireNonNull(key, "key");
        pruneExpired(nowNanos);
        Entry previous = entries.get(key);
        if (previous != null && withinWindow(nowNanos, previous.admittedAtNanos)) {
            return new Check(Status.REPEAT, previous.requestId);
        }
        if (entries.size() >= MAX_KEYS_GLOBAL || countPlayer(key.playerId) >= MAX_KEYS_PER_PLAYER) {
            return new Check(Status.CAPACITY, null);
        }
        return new Check(Status.AVAILABLE, null);
    }

    /** Commit with evidence and new-work tokens at the server-thread admission boundary. */
    synchronized void commit(Key key, String requestId, long admittedAtNanos, long nowNanos) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestId, "requestId");
        pruneExpired(nowNanos);
        if (entries.containsKey(key) || entries.size() >= MAX_KEYS_GLOBAL ||
                countPlayer(key.playerId) >= MAX_KEYS_PER_PLAYER) {
            throw new IllegalStateException("repeat-guard capacity changed before admission commit");
        }
        entries.put(key, new Entry(requestId, admittedAtNanos));
    }

    synchronized int size() { return entries.size(); }
    synchronized int size(UUID playerId) { return countPlayer(playerId); }
    synchronized void clear() { entries.clear(); }

    private int countPlayer(UUID playerId) {
        int count = 0;
        for (Key existing : entries.keySet()) if (existing.playerId.equals(playerId)) count++;
        return count;
    }

    private void pruneExpired(long nowNanos) {
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            // Keep negative elapsed values conservatively; signed subtraction handles nanoTime wrap.
            if (nowNanos - entry.admittedAtNanos > WINDOW_NANOS) iterator.remove();
        }
    }

    private static boolean withinWindow(long nowNanos, long admittedAtNanos) {
        long elapsed = nowNanos - admittedAtNanos;
        // A regressed or otherwise ambiguous monotonic reading fails closed; the entry
        // remains until its fixed deadline can be established as elapsed.
        return elapsed <= WINDOW_NANOS;
    }

    enum Status { AVAILABLE, REPEAT, CAPACITY }
    record Check(Status status, String requestId) {}
    private record Entry(String requestId, long admittedAtNanos) {}

    /** Text is held only in this bounded process-memory key and is never rendered or exported. */
    static final class Key {
        private final UUID playerId;
        private final Object connection;
        private final long connectionGeneration;
        private final Object serviceEpoch;
        private final long serviceGeneration;
        private final long gateGeneration;
        private final String serverId;
        private final String worldBinding;
        private final String characterId;
        private final String agentId;
        private final String actualWorld;
        private final InteractionIngress.Origin origin;
        private final String inputKind;
        private final String followupConversationId;
        private final String followupMessageId;
        private final String text;

        Key(UUID playerId, Object connection, long connectionGeneration, Object serviceEpoch,
            long serviceGeneration, long gateGeneration, String serverId, String worldBinding,
            String characterId, String agentId, String actualWorld, InteractionIngress.Origin origin,
            String inputKind, String followupConversationId, String followupMessageId, String text) {
            this.playerId = Objects.requireNonNull(playerId, "playerId");
            this.connection = Objects.requireNonNull(connection, "connection");
            this.connectionGeneration = connectionGeneration;
            this.serviceEpoch = Objects.requireNonNull(serviceEpoch, "serviceEpoch");
            this.serviceGeneration = serviceGeneration;
            this.gateGeneration = gateGeneration;
            this.serverId = Objects.requireNonNull(serverId, "serverId");
            this.worldBinding = Objects.requireNonNull(worldBinding, "worldBinding");
            this.characterId = Objects.requireNonNull(characterId, "characterId");
            this.agentId = Objects.requireNonNull(agentId, "agentId");
            this.actualWorld = Objects.requireNonNull(actualWorld, "actualWorld");
            this.origin = Objects.requireNonNull(origin, "origin");
            this.inputKind = Objects.requireNonNull(inputKind, "inputKind");
            this.followupConversationId = followupConversationId;
            this.followupMessageId = followupMessageId;
            this.text = Objects.requireNonNull(text, "text");
            if (text.length() > InteractionIngress.MAX_INPUT_LENGTH) {
                throw new IllegalArgumentException("repeat-guard text exceeds the interaction bound");
            }
        }

        UUID playerId() { return playerId; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return connection == key.connection && serviceEpoch == key.serviceEpoch &&
                    connectionGeneration == key.connectionGeneration && serviceGeneration == key.serviceGeneration &&
                    gateGeneration == key.gateGeneration && playerId.equals(key.playerId) &&
                    serverId.equals(key.serverId) && worldBinding.equals(key.worldBinding) &&
                    characterId.equals(key.characterId) && agentId.equals(key.agentId) &&
                    actualWorld.equals(key.actualWorld) && origin == key.origin && inputKind.equals(key.inputKind) &&
                    Objects.equals(followupConversationId, key.followupConversationId) &&
                    Objects.equals(followupMessageId, key.followupMessageId) && text.equals(key.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, System.identityHashCode(connection), connectionGeneration,
                    System.identityHashCode(serviceEpoch), serviceGeneration, gateGeneration, serverId,
                    worldBinding, characterId, agentId, actualWorld, origin, inputKind,
                    followupConversationId, followupMessageId, text);
        }
    }
}
