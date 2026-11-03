package org.worldspeaker.bootstrap;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/** Tracks only live request ownership and the single active conversation slot. */
final class SmokeRequestLifecycle<T> {
    private final IdentityHashMap<T, Entry> live = new IdentityHashMap<>();
    private T active;

    void register(T request, Object connection) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(connection, "connection");
        if (live.containsKey(request)) {
            throw new IllegalArgumentException("request is already registered");
        }
        live.put(request, new Entry(connection, State.PENDING));
    }

    boolean start(T request) {
        Entry entry = live.get(request);
        if (active != null || entry == null || entry.state != State.PENDING) return false;
        entry.state = State.ACTIVE;
        active = request;
        return true;
    }

    T active() {
        return active;
    }

    boolean isPending(T request) {
        Entry entry = live.get(request);
        return entry != null && entry.state == State.PENDING;
    }

    boolean isActive(T request) {
        Entry entry = live.get(request);
        return active == request && entry != null && entry.state == State.ACTIVE;
    }

    boolean isLive(T request) {
        return live.containsKey(request);
    }

    boolean isBoundTo(T request, Object connection) {
        Entry entry = live.get(request);
        return entry != null && entry.connection == connection;
    }

    boolean isActiveOn(T request, Object connection) {
        return isActive(request) && isBoundTo(request, connection);
    }

    boolean complete(T request) {
        return finish(request, State.COMPLETED, true);
    }

    boolean retire(T request) {
        return finish(request, State.RETIRED, false);
    }

    List<T> retireConnection(Object connection) {
        List<T> retired = new ArrayList<>();
        live.entrySet().removeIf(entry -> {
            if (entry.getValue().connection != connection) return false;
            entry.getValue().state = State.RETIRED;
            retired.add(entry.getKey());
            if (active == entry.getKey()) active = null;
            return true;
        });
        return retired;
    }

    List<T> liveForConnection(Object connection) {
        Objects.requireNonNull(connection, "connection");
        List<T> result = new ArrayList<>();
        for (var entry : live.entrySet()) {
            if (entry.getValue().connection == connection) result.add(entry.getKey());
        }
        return result;
    }

    List<T> liveRequests() {
        return new ArrayList<>(live.keySet());
    }

    List<T> retireAll() {
        List<T> retired = new ArrayList<>(live.keySet());
        live.values().forEach(entry -> entry.state = State.RETIRED);
        live.clear();
        active = null;
        return retired;
    }

    private boolean finish(T request, State terminalState, boolean activeOnly) {
        Entry entry = live.get(request);
        if (entry == null || (activeOnly && (active != request || entry.state != State.ACTIVE))) return false;
        entry.state = terminalState;
        live.remove(request);
        if (active == request) active = null;
        return true;
    }

    private enum State {
        PENDING,
        ACTIVE,
        COMPLETED,
        RETIRED
    }

    private static final class Entry {
        private final Object connection;
        private State state;

        private Entry(Object connection, State state) {
            this.connection = connection;
            this.state = state;
        }
    }
}
