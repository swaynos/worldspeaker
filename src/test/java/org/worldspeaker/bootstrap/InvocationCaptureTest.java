package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InvocationCaptureTest {
    @Test
    void captureReadsMonotonicReceiptBeforeWallClockAndBindsItToOneConnectionGeneration() {
        List<String> reads = new ArrayList<>();
        Object connection = new Object();

        InvocationCapture capture = InvocationCapture.capture(
                () -> { reads.add("monotonic"); return 400; },
                () -> { reads.add("wall"); return 1_793_275_200_000L; },
                connection, 17, InvocationCapture.Source.SIGNED_CHAT);

        assertEquals(List.of("monotonic", "wall"), reads);
        assertEquals(400, capture.receivedAtNanos());
        assertEquals(1_793_275_200_000L, capture.receivedAtUnixMillis());
        assertTrue(capture.belongsTo(connection, 17));
        assertFalse(capture.belongsTo(new Object(), 17));
        assertFalse(capture.belongsTo(connection, 18));
        assertFalse(capture.belongsTo(connection, 0));
    }

    @Test
    void scopedCommandRunnableCarriesPacketIdentityAndRestoresNestedContextEvenOnFailure() {
        InvocationCapture outer = capture(100, new Object(), 1, InvocationCapture.Source.UNSIGNED_COMMAND);
        InvocationCapture inner = capture(200, new Object(), 2, InvocationCapture.Source.SIGNED_COMMAND);
        List<InvocationCapture> observed = new ArrayList<>();

        Runnable wrapped = InvocationCaptureContext.bind(outer, () -> {
            observed.add(InvocationCaptureContext.current());
            InvocationCaptureContext.bind(inner, () -> observed.add(InvocationCaptureContext.current())).run();
            observed.add(InvocationCaptureContext.current());
        });
        wrapped.run();

        assertEquals(List.of(outer, inner, outer), observed);
        assertEquals(null, InvocationCaptureContext.current());

        IllegalStateException expected = new IllegalStateException("fixture failure");
        Runnable failing = InvocationCaptureContext.bind(inner, () -> {
            assertSame(inner, InvocationCaptureContext.current());
            throw expected;
        });
        assertSame(expected, assertThrows(IllegalStateException.class, failing::run));
        assertEquals(null, InvocationCaptureContext.current(), "the receipt stamp never leaks to a later task");
    }

    @Test
    void ingressCaptureRetainsOriginalReceiptAcrossFollowupCopyAndRejectsWrongPacketKind() {
        Object connection = new TestConnection(23);
        InvocationCapture packet = capture(749, connection, 23, InvocationCapture.Source.SIGNED_CHAT);

        InteractionIngress.Captured natural = InteractionIngress.Captured.fromPacket(
                packet, InteractionIngress.Origin.NATURAL_ADDRESS, 1_149);
        InteractionIngress.Captured followup = natural.asFollowup("conversation-1", "message-1");

        assertEquals(749, followup.capturedAtNanos());
        assertEquals(1_149, followup.handlerEnteredAtNanos());
        assertEquals(packet.invocationId(), followup.invocationId());
        assertEquals(packet.eventId(), followup.eventId());
        assertSame(packet, followup.packetCapture());
        assertEquals(InteractionIngress.Origin.FOLLOWUP, followup.origin());
        assertEquals("conversation-1", followup.conversationId());
        assertEquals("message-1", followup.replyToMessageId());
        assertEquals(InteractionIngress.CaptureProvenance.LIVE_PACKET, followup.provenance());
        assertTrue(followup.receiptMatches(connection));
        assertFalse(followup.receiptMatches(new TestConnection(23)));
        assertFalse(followup.receiptMatches(new TestConnection(24)));

        InteractionIngress.Captured wrongOrigin = InteractionIngress.Captured.fromPacket(
                packet, InteractionIngress.Origin.COMMAND, 1_150);
        assertFalse(wrongOrigin.hasReceiptEvidence(), "chat receipt metadata cannot be relabeled as a command packet");
    }

    @Test
    void rejectedPacketKindDropsItsOwnedLeaseExactlyOnce() {
        Object connection = new TestConnection(24);
        CaptureHandoffBudget.Lease lease = CaptureHandoffBudget.tryAcquire(connection);
        InvocationCapture packet = new InvocationCapture(UUID.randomUUID(), UUID.randomUUID(), 900,
                1_793_275_200_000L, connection, 24, InvocationCapture.Source.SIGNED_CHAT, lease);

        InteractionIngress.Captured rejected = InteractionIngress.Captured.fromPacket(
                packet, InteractionIngress.Origin.COMMAND, 1_000);

        assertFalse(rejected.hasReceiptEvidence());
        assertTrue(lease.released());
        packet.releaseHandoff();
        assertEquals(0, CaptureHandoffBudget.outstanding(connection));
    }

    private static InvocationCapture capture(long receivedAtNanos, Object connection, long generation,
                                             InvocationCapture.Source source) {
        return new InvocationCapture(UUID.randomUUID(), UUID.randomUUID(), receivedAtNanos,
                1_793_275_200_000L, connection, generation, source);
    }

    private record TestConnection(long worldspeaker$connectionGeneration) implements ServerConnectionGeneration {}
}
