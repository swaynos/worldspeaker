package org.worldspeaker.compat;

/** The first compatibility slice deliberately ships no game abilities. */
public final class AbilityAvailability {
    private AbilityAvailability() {}

    public static boolean isImplemented(String abilityId, int contractVersion) {
        return false;
    }

    public static String unavailableReason(String abilityId, int contractVersion) {
        return "unsupported_state";
    }
}
