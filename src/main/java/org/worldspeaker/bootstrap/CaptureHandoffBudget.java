package org.worldspeaker.bootstrap;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed pre-schedule lease bound for packet-owned integration metadata. */
public final class CaptureHandoffBudget {
    public static final int MAX_PER_CONNECTION = 16;
    public static final int MAX_GLOBAL = 256;

    private static final Map<Object, ConnectionLeases> CONNECTIONS = new IdentityHashMap<>();
    private static int outstanding;

    private CaptureHandoffBudget() {}

    public static synchronized Lease tryAcquire(Object connection) {
        if (connection == null) return null;
        ConnectionLeases leases = CONNECTIONS.get(connection);
        if (outstanding >= MAX_GLOBAL || leases != null &&
                (leases.retired || leases.leases.size() >= MAX_PER_CONNECTION)) return null;
        if (leases == null) {
            leases = new ConnectionLeases();
            CONNECTIONS.put(connection, leases);
        }
        Lease lease = new Lease(connection);
        leases.leases.add(lease);
        outstanding++;
        return lease;
    }

    /** A disconnected handler retires every handed-off packet without cancelling vanilla work. */
    public static void retireConnection(Object connection) {
        if (connection == null) return;
        ArrayList<Lease> retired;
        synchronized (CaptureHandoffBudget.class) {
            ConnectionLeases leases = CONNECTIONS.get(connection);
            if (leases == null) return;
            leases.retired = true;
            retired = new ArrayList<>(leases.leases);
        }
        for (Lease lease : retired) lease.release();
    }

    public static synchronized int outstanding() { return outstanding; }

    public static synchronized int outstanding(Object connection) {
        ConnectionLeases leases = CONNECTIONS.get(connection);
        return leases == null ? 0 : leases.leases.size();
    }

    private static synchronized void release(Lease lease) {
        if (!lease.released.compareAndSet(false, true)) return;
        ConnectionLeases leases = CONNECTIONS.get(lease.connection);
        if (leases == null || !leases.leases.remove(lease)) return;
        outstanding--;
        if (leases.leases.isEmpty()) CONNECTIONS.remove(lease.connection);
    }

    private static final class ConnectionLeases {
        private final Set<Lease> leases = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean retired;
    }

    public static final class Lease {
        private final Object connection;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(Object connection) {
            this.connection = connection;
        }

        public void release() { CaptureHandoffBudget.release(this); }

        public boolean released() { return released.get(); }
    }
}
