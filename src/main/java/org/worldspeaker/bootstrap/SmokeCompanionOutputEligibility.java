package org.worldspeaker.bootstrap;

import org.worldspeaker.compat.CompatibilityGate;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Applies companion output only while its gate generation, request, and original connection are current. */
final class SmokeCompanionOutputEligibility {
    private SmokeCompanionOutputEligibility() {}

    /**
     * Runs the bounded action inside the gate's generation check. Callers invoke this on the server thread;
     * holding the gate check through the action prevents a concurrent invalidation from crossing the check.
     */
    static <T, C> boolean executeIfEligible(CompatibilityGate gate, long expectedGeneration, T request,
                                            Predicate<T> activeLifecycle,
                                            Function<T, C> currentOriginalConnection,
                                            BiConsumer<T, C> action) {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(activeLifecycle, "activeLifecycle");
        Objects.requireNonNull(currentOriginalConnection, "currentOriginalConnection");
        Objects.requireNonNull(action, "action");

        boolean[] executed = {false};
        gate.executeIfReady(expectedGeneration, () -> {
            if (request == null || !activeLifecycle.test(request)) return;
            C connection = currentOriginalConnection.apply(request);
            if (connection == null) return;

            executed[0] = true;
            action.accept(request, connection);
        });
        return executed[0];
    }
}
