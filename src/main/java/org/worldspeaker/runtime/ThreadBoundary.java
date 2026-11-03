package org.worldspeaker.runtime;

import java.util.concurrent.atomic.AtomicReference;

/** Runtime assertions for the boundary between Fabric's server thread and owned I/O workers. */
public final class ThreadBoundary {
    private static final AtomicReference<Binding> SERVER_THREAD = new AtomicReference<>();

    private ThreadBoundary() {}

    /** Called by Fabric lifecycle/tick callbacks, never by an I/O worker. */
    public static void markServerThread() {
        Thread current = Thread.currentThread();
        while (true) {
            Binding existing = SERVER_THREAD.get();
            if (existing == null) {
                if (SERVER_THREAD.compareAndSet(null, new Binding(current, null))) return;
                continue;
            }
            if (existing.thread != current) {
                throw new IllegalStateException("Worldspeaker observed more than one server thread");
            }
            return;
        }
    }

    /** Binds the server thread to one lifecycle epoch. A retired epoch cannot rebind itself. */
    public static void beginServerEpoch(ServerEpoch epoch) {
        if (epoch == null) throw new NullPointerException("epoch");
        synchronized (epoch) {
            if (epoch.retired) throw new IllegalStateException("Worldspeaker cannot bind a retired server epoch");
            Thread current = Thread.currentThread();
            Binding next = new Binding(current, epoch);
            Binding existing = SERVER_THREAD.get();
            if (existing == null) {
                if (SERVER_THREAD.compareAndSet(null, next)) return;
                existing = SERVER_THREAD.get();
            }
            if (existing.epoch == epoch && existing.thread == current) return;
            throw new IllegalStateException("Worldspeaker observed an overlapping server lifecycle epoch");
        }
    }

    public static boolean isServerThread() {
        Binding binding = SERVER_THREAD.get();
        return binding != null && binding.thread == Thread.currentThread();
    }

    public static boolean isServerThread(ServerEpoch epoch) {
        Binding binding = SERVER_THREAD.get();
        return binding != null && binding.epoch == epoch && binding.thread == Thread.currentThread();
    }

    public static void assertServerThread() {
        if (!isServerThread()) throw new IllegalStateException("game access outside the Fabric server thread");
    }

    public static void assertServerThread(ServerEpoch epoch) {
        if (!isServerThread(epoch)) throw new IllegalStateException("game access outside the active server lifecycle thread");
    }

    /** Clears only the exact retired epoch; a late stop callback cannot clear a newer binding. */
    public static boolean retireServerEpoch(ServerEpoch epoch) {
        if (epoch == null) return false;
        synchronized (epoch) {
            if (epoch.retired) return false;
            epoch.retired = true;
            while (true) {
                Binding existing = SERVER_THREAD.get();
                if (existing == null || existing.epoch != epoch) return false;
                if (SERVER_THREAD.compareAndSet(existing, null)) return true;
            }
        }
    }

    public static void assertNotServerThread() {
        if (isServerThread()) throw new IllegalStateException("blocking or transport work on the Fabric server thread");
    }

    /** Test-only escape hatch; production retires through exact lifecycle epoch tokens. */
    static void clearForTests() {
        SERVER_THREAD.set(null);
    }

    public static ServerEpoch newServerEpochToken() {
        return new ServerEpoch();
    }

    public static final class ServerEpoch {
        private boolean retired;

        private ServerEpoch() {}
    }

    private record Binding(Thread thread, ServerEpoch epoch) {}
}
