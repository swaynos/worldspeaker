package org.worldspeaker.bootstrap;

import java.util.Objects;

/** Scoped bridge for vanilla command runnables that close over their original packet. */
public final class InvocationCaptureContext {
    private static final ThreadLocal<InvocationCapture> CURRENT = new ThreadLocal<>();

    private InvocationCaptureContext() {}

    public static InvocationCapture current() {
        return CURRENT.get();
    }

    public static Runnable bind(InvocationCapture capture, Runnable action) {
        Objects.requireNonNull(action, "action");
        return () -> {
            InvocationCapture previous = CURRENT.get();
            if (capture == null) CURRENT.remove();
            else CURRENT.set(capture);
            try {
                action.run();
            } finally {
                if (previous == null) CURRENT.remove();
                else CURRENT.set(previous);
                if (capture != null) capture.releaseHandoff();
            }
        };
    }
}
