package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaptureHandoffBudgetTest {
    @Test
    void perConnectionAndGlobalHandoffBoundsReleaseExactlyOnce() {
        Object connection = new Object();
        CaptureHandoffBudget.Lease otherConnectionLease = null;
        List<CaptureHandoffBudget.Lease> leases = new ArrayList<>();
        try {
            for (int index = 0; index < CaptureHandoffBudget.MAX_PER_CONNECTION; index++) {
                CaptureHandoffBudget.Lease lease = CaptureHandoffBudget.tryAcquire(connection);
                assertNotNull(lease);
                leases.add(lease);
            }
            assertNull(CaptureHandoffBudget.tryAcquire(connection));
            assertEquals(CaptureHandoffBudget.MAX_PER_CONNECTION, CaptureHandoffBudget.outstanding(connection));
            otherConnectionLease = CaptureHandoffBudget.tryAcquire(new Object());
            assertNotNull(otherConnectionLease, "another connection retains its allowance");
        } finally {
            for (CaptureHandoffBudget.Lease lease : leases) lease.release();
            if (otherConnectionLease != null) otherConnectionLease.release();
            CaptureHandoffBudget.retireConnection(connection);
        }

        Object[] connections = new Object[CaptureHandoffBudget.MAX_GLOBAL];
        List<CaptureHandoffBudget.Lease> global = new ArrayList<>();
        try {
            for (int index = 0; index < connections.length; index++) {
                connections[index] = new Object();
                CaptureHandoffBudget.Lease lease = CaptureHandoffBudget.tryAcquire(connections[index]);
                assertNotNull(lease);
                global.add(lease);
            }
            assertEquals(CaptureHandoffBudget.MAX_GLOBAL, CaptureHandoffBudget.outstanding());
            assertNull(CaptureHandoffBudget.tryAcquire(new Object()));
        } finally {
            for (CaptureHandoffBudget.Lease lease : global) lease.release();
        }
        assertEquals(0, CaptureHandoffBudget.outstanding());
    }

    @Test
    void commandHandlingAndConnectionRetirementReleaseLeasesIdempotently() {
        Object connection = new Object();
        CaptureHandoffBudget.Lease handled = CaptureHandoffBudget.tryAcquire(connection);
        assertNotNull(handled);
        InvocationCapture capture = new InvocationCapture(UUID.randomUUID(), UUID.randomUUID(), 1, 2,
                connection, 1, InvocationCapture.Source.UNSIGNED_COMMAND, handled);
        InvocationCaptureContext.bind(capture, () -> assertSameCapture(capture)).run();
        assertTrue(handled.released());
        assertEquals(0, CaptureHandoffBudget.outstanding(connection));
        handled.release();
        assertEquals(0, CaptureHandoffBudget.outstanding());

        CaptureHandoffBudget.Lease pending = CaptureHandoffBudget.tryAcquire(connection);
        assertNotNull(pending);
        CaptureHandoffBudget.retireConnection(connection);
        assertTrue(pending.released());
        assertEquals(0, CaptureHandoffBudget.outstanding());
        assertFalse(pending.released() == false, "retirement releases every outstanding lease exactly once");
    }

    @Test
    void productionCaptureKeepsTheOriginalReceiptInPacketCarrierWhenEitherLeaseCapRejects() {
        Object connection = new TestConnection(44);
        List<InvocationCapture> perConnection = new ArrayList<>();
        try {
            for (int index = 0; index < CaptureHandoffBudget.MAX_PER_CONNECTION + 1; index++) {
                InvocationCapture capture = capture(connection, 44, 10_000 + index);
                TestCarrier carrier = new TestCarrier();
                assertTrue(carrier.worldspeaker$setInvocationCapture(capture));
                assertSame(capture, carrier.worldspeaker$getInvocationCapture());
                perConnection.add(capture);
            }

            InvocationCapture overflow = perConnection.get(perConnection.size() - 1);
            assertTrue(overflow.handoffCapacityRejected());
            assertEquals(10_000 + CaptureHandoffBudget.MAX_PER_CONNECTION,
                    overflow.receivedAtNanos(), "the original receipt clock survives the explicit capacity reject");
            assertEquals(connection, overflow.originalConnection());
            assertEquals(44, overflow.connectionGeneration());
            assertEquals(InvocationCapture.Source.SIGNED_CHAT, overflow.source());
            assertNull(overflow.handoffLease());
            assertEquals(CaptureHandoffBudget.MAX_PER_CONNECTION,
                    CaptureHandoffBudget.outstanding(connection), "a rejected stamp consumes no lease");

            for (InvocationCapture capture : perConnection) capture.releaseHandoff();
            for (InvocationCapture capture : perConnection) capture.releaseHandoff();
            assertEquals(0, CaptureHandoffBudget.outstanding(connection));
            CaptureHandoffBudget.Lease recovered = CaptureHandoffBudget.tryAcquire(connection);
            assertNotNull(recovered, "released connection capacity is reusable");
            recovered.release();
        } finally {
            for (InvocationCapture capture : perConnection) capture.releaseHandoff();
            CaptureHandoffBudget.retireConnection(connection);
        }

        Object[] connections = new Object[CaptureHandoffBudget.MAX_GLOBAL + 1];
        List<InvocationCapture> global = new ArrayList<>();
        try {
            for (int index = 0; index < connections.length; index++) {
                connections[index] = new TestConnection(index + 100L);
                global.add(capture(connections[index], index + 100L, 20_000 + index));
            }
            assertEquals(CaptureHandoffBudget.MAX_GLOBAL,
                    CaptureHandoffBudget.outstanding(), "the 257th packet remains stamped but unleased");
            InvocationCapture overflow = global.get(global.size() - 1);
            assertTrue(overflow.handoffCapacityRejected());
            assertEquals(20_000 + CaptureHandoffBudget.MAX_GLOBAL, overflow.receivedAtNanos());
            assertEquals(0, CaptureHandoffBudget.outstanding(connections[connections.length - 1]));
        } finally {
            for (InvocationCapture capture : global) capture.releaseHandoff();
        }
        assertEquals(0, CaptureHandoffBudget.outstanding());
        InvocationCapture recovered = capture(new TestConnection(999), 999, 30_000);
        assertFalse(recovered.handoffCapacityRejected(), "global capacity recovers after exact lease release");
        recovered.releaseHandoff();
    }

    @Test
    void failedCaptureConstructionReleasesTheLeaseAcquiredForItsReceipt() {
        Object connection = new TestConnection(1_001);

        assertThrows(IllegalArgumentException.class, () -> InvocationCapture.captureHandoff(
                () -> 1, () -> -1, connection, 1_001, InvocationCapture.Source.UNSIGNED_COMMAND));

        assertEquals(0, CaptureHandoffBudget.outstanding(connection));
    }

    private static InvocationCapture capture(Object connection, long generation, long receiptNanos) {
        return InvocationCapture.captureHandoff(() -> receiptNanos, () -> 1_793_275_200_000L,
                connection, generation, InvocationCapture.Source.SIGNED_CHAT);
    }

    private record TestConnection(long worldspeaker$connectionGeneration) implements ServerConnectionGeneration {}

    private static final class TestCarrier implements PacketCaptureAccess {
        private InvocationCapture capture;

        @Override public InvocationCapture worldspeaker$getInvocationCapture() { return capture; }

        @Override public boolean worldspeaker$setInvocationCapture(InvocationCapture next) {
            if (capture != null || next == null) return false;
            capture = next;
            return true;
        }
    }

    private static void assertSameCapture(InvocationCapture expected) {
        assertEquals(expected, InvocationCaptureContext.current());
    }
}
