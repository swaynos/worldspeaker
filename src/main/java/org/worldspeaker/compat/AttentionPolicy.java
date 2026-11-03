package org.worldspeaker.compat;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Restart-only, explicit, bounded observational attention. It never grants an ability or dialogue turn. */
public record AttentionPolicy(Set<Subscription> subscriptions, int maxEvents, int maxAgeSeconds,
                              int maxBytes, int maxEventsPerSecond, int maxEventsPerPlayerSecond) {
    public static final int DEFAULT_MAX_EVENTS = 32;
    public static final int DEFAULT_MAX_AGE_SECONDS = 30;
    public static final int DEFAULT_MAX_BYTES = 8192;
    public static final int DEFAULT_MAX_EVENTS_PER_SECOND = 16;
    public static final int DEFAULT_MAX_EVENTS_PER_PLAYER_SECOND = 4;

    public AttentionPolicy {
        if (subscriptions == null || subscriptions.stream().anyMatch(java.util.Objects::isNull) ||
                maxEvents < 1 || maxEvents > 64 || maxAgeSeconds < 1 || maxAgeSeconds > 60 ||
                maxBytes < 1024 || maxBytes > 8192 || maxEventsPerSecond < 1 || maxEventsPerSecond > 32 ||
                maxEventsPerPlayerSecond < 1 || maxEventsPerPlayerSecond > 8 ||
                maxEventsPerPlayerSecond > maxEventsPerSecond) {
            throw new IllegalArgumentException("attention policy is outside its fixed bounds");
        }
        subscriptions = subscriptions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(subscriptions));
    }

    public static AttentionPolicy disabled() {
        return new AttentionPolicy(Set.of(), DEFAULT_MAX_EVENTS, DEFAULT_MAX_AGE_SECONDS, DEFAULT_MAX_BYTES,
                DEFAULT_MAX_EVENTS_PER_SECOND, DEFAULT_MAX_EVENTS_PER_PLAYER_SECOND);
    }

    /** Exact lowercase, comma-separated subscription names; empty disables optional capture. */
    public static AttentionPolicy parse(String subscriptions, String maxEvents, String maxAgeSeconds,
                                        String maxBytes, String maxEventsPerSecond,
                                        String maxEventsPerPlayerSecond) {
        LinkedHashSet<Subscription> parsed = new LinkedHashSet<>();
        if (subscriptions != null && !subscriptions.isEmpty()) {
            for (String name : subscriptions.split(",", -1)) {
                if (name.isEmpty()) throw invalid();
                Subscription subscription = Subscription.fromConfig(name);
                if (!parsed.add(subscription)) throw invalid();
            }
        }
        return new AttentionPolicy(parsed,
                parseBounded(maxEvents, DEFAULT_MAX_EVENTS, 1, 64),
                parseBounded(maxAgeSeconds, DEFAULT_MAX_AGE_SECONDS, 1, 60),
                parseBounded(maxBytes, DEFAULT_MAX_BYTES, 1024, 8192),
                parseBounded(maxEventsPerSecond, DEFAULT_MAX_EVENTS_PER_SECOND, 1, 32),
                parseBounded(maxEventsPerPlayerSecond, DEFAULT_MAX_EVENTS_PER_PLAYER_SECOND, 1, 8));
    }

    public boolean enabled() { return !subscriptions.isEmpty(); }

    public boolean subscribes(Subscription subscription) {
        return subscriptions.contains(subscription);
    }

    private static int parseBounded(String raw, int fallback, int minimum, int maximum) {
        if (raw == null) return fallback;
        if (!raw.matches("[0-9]+")) throw invalid();
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum || value > maximum) throw invalid();
            return value;
        } catch (NumberFormatException overflow) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("server.attention must use known subscriptions and bounded integer limits");
    }

    public enum Subscription {
        PLAYER_CONNECTION("player_connection"),
        PLAYER_DIMENSION("player_dimension"),
        PLAYER_DEATH("player_death"),
        PLAYER_RESPAWN("player_respawn"),
        PLAYER_MOVEMENT("player_movement");

        private final String configName;

        Subscription(String configName) { this.configName = configName; }

        public String configName() { return configName; }

        private static Subscription fromConfig(String value) {
            for (Subscription subscription : values()) {
                if (subscription.configName.equals(value)) return subscription;
            }
            throw invalid();
        }
    }
}
