package org.worldspeaker.bootstrap;

import org.worldspeaker.compat.ConfiguredBinding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Mandatory local dialogue-freshness tokens, independent of optional attention subscriptions. */
public final class PlayerFreshness {
    public static final int MAX_PLAYERS = 32;

    private final Set<UUID> allowedPlayers;
    private final Object serviceEpoch;
    private final long serviceGeneration;
    private final ConfiguredBinding binding;
    private final Map<UUID, Slot> slots = new LinkedHashMap<>();

    public PlayerFreshness(Set<UUID> allowedPlayers, Object serviceEpoch, long serviceGeneration,
                           ConfiguredBinding binding) {
        if (allowedPlayers == null || allowedPlayers.size() > MAX_PLAYERS ||
                allowedPlayers.stream().anyMatch(java.util.Objects::isNull) || serviceEpoch == null ||
                serviceGeneration < 0 || binding == null || !binding.isComplete()) {
            throw new IllegalArgumentException("player freshness scope is invalid");
        }
        this.allowedPlayers = Set.copyOf(allowedPlayers);
        this.serviceEpoch = serviceEpoch;
        this.serviceGeneration = serviceGeneration;
        this.binding = binding;
    }

    public boolean isScoped(UUID playerId) { return playerId != null && allowedPlayers.contains(playerId); }

    public List<UUID> playerIds() { return List.copyOf(slots.keySet()); }

    public Snapshot current(UUID playerId) {
        Slot slot = slots.get(playerId);
        return slot == null || slot.departed ? null : slot.snapshot(playerId, serviceEpoch, serviceGeneration, binding);
    }

    public boolean wouldChange(UUID playerId, Observation observation) {
        Slot slot = slots.get(playerId);
        return slot == null || slot.changed(observation);
    }

    /** Seed on admission/callback only; never enumerates the server roster. */
    public Snapshot observe(UUID playerId, Observation observation) {
        if (!isScoped(playerId) || observation == null) return null;
        Slot slot = slots.get(playerId);
        if (slot == null) {
            if (slots.size() >= MAX_PLAYERS) return null;
            slot = new Slot();
            slots.put(playerId, slot);
            slot.update(observation, true);
        } else {
            boolean changed = slot.changed(observation);
            if (changed) slot.update(observation, true);
            else slot.update(observation, false);
        }
        return slot.snapshot(playerId, serviceEpoch, serviceGeneration, binding);
    }

    /** A supported lifecycle signal rotates even if the replacement has the same UUID/dimension/position. */
    public Snapshot rotate(UUID playerId, Observation observation) {
        if (!isScoped(playerId) || observation == null) return null;
        Slot slot = slots.get(playerId);
        if (slot == null) {
            if (slots.size() >= MAX_PLAYERS) return null;
            slot = new Slot();
            slots.put(playerId, slot);
        }
        slot.update(observation, true);
        return slot.snapshot(playerId, serviceEpoch, serviceGeneration, binding);
    }

    /** Departure invalidates the old entity/connection and cannot be undone by a same-UUID rejoin. */
    public boolean depart(UUID playerId, Object entity, Object connection) {
        if (!isScoped(playerId)) return false;
        Slot slot = slots.get(playerId);
        if (slot == null) {
            if (slots.size() >= MAX_PLAYERS) return false;
            slot = new Slot();
            slots.put(playerId, slot);
        }
        if (slot.departed) return false;
        if (entity != null && slot.entity != null && slot.entity != entity) return false;
        if (connection != null && slot.connection != null && slot.connection != connection) return false;
        slot.token = new Object();
        slot.entity = null;
        slot.connection = null;
        slot.dimension = null;
        slot.alive = false;
        slot.departed = true;
        return true;
    }

    /** Direct pre-forward/final-gate read. Any displacement or lifecycle mismatch permanently rotates the token. */
    public boolean isFresh(Snapshot expected, Observation current) {
        if (expected == null || current == null || !isScoped(expected.playerId)) return false;
        Slot slot = slots.get(expected.playerId);
        if (slot == null || slot.departed || slot.changed(current)) {
            if (slot != null) slot.update(current, true);
            return false;
        }
        slot.update(current, false);
        return slot.token == expected.token && slot.entity == expected.entity &&
                slot.connection == expected.connection && slot.alive && current.alive &&
                slot.dimension.equals(current.dimension) && slot.x == current.blockX &&
                slot.y == current.blockY && slot.z == current.blockZ &&
                expected.serviceEpoch == serviceEpoch && expected.serviceGeneration == serviceGeneration &&
                expected.binding.equals(binding);
    }

    public void clear() { slots.clear(); }

    public record Observation(Object entity, Object connection, String dimension, boolean alive,
                              int blockX, int blockY, int blockZ) {
        public Observation {
            if (entity == null || connection == null || dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("freshness observation is incomplete");
            }
        }
    }

    public static final class Snapshot {
        private final UUID playerId;
        private final Object token;
        private final Object entity;
        private final Object connection;
        private final String dimension;
        private final int blockX;
        private final int blockY;
        private final int blockZ;
        private final long serviceGeneration;
        private final Object serviceEpoch;
        private final ConfiguredBinding binding;

        private Snapshot(UUID playerId, Object token, Observation observation, Object serviceEpoch,
                         long serviceGeneration, ConfiguredBinding binding) {
            this.playerId = playerId;
            this.token = token;
            this.entity = observation.entity;
            this.connection = observation.connection;
            this.dimension = observation.dimension;
            this.blockX = observation.blockX;
            this.blockY = observation.blockY;
            this.blockZ = observation.blockZ;
            this.serviceEpoch = serviceEpoch;
            this.serviceGeneration = serviceGeneration;
            this.binding = binding;
        }

        public UUID playerId() { return playerId; }
        public String dimension() { return dimension; }
        public int blockX() { return blockX; }
        public int blockY() { return blockY; }
        public int blockZ() { return blockZ; }
    }

    private static final class Slot {
        private Object token = new Object();
        private Object entity;
        private Object connection;
        private String dimension;
        private boolean alive;
        private int x;
        private int y;
        private int z;
        private boolean departed;

        private boolean changed(Observation value) {
            return departed || entity != value.entity || connection != value.connection ||
                    dimension == null || !dimension.equals(value.dimension) || alive != value.alive ||
                    x != value.blockX || y != value.blockY || z != value.blockZ;
        }

        private void update(Observation value, boolean rotate) {
            if (rotate) token = new Object();
            entity = value.entity;
            connection = value.connection;
            dimension = value.dimension;
            alive = value.alive;
            x = value.blockX;
            y = value.blockY;
            z = value.blockZ;
            departed = false;
        }

        private Snapshot snapshot(UUID playerId, Object serviceEpoch, long serviceGeneration,
                                  ConfiguredBinding binding) {
            if (entity == null || connection == null || dimension == null) return null;
            return new Snapshot(playerId, token,
                    new Observation(entity, connection, dimension, alive, x, y, z),
                    serviceEpoch, serviceGeneration, binding);
        }
    }
}
