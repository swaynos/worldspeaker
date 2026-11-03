package org.worldspeaker.bootstrap;

import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Immutable identity and receive-time evidence attached to one decoded serverbound packet. */
public record InvocationCapture(
        UUID invocationId,
        UUID eventId,
        long receivedAtNanos,
        long receivedAtUnixMillis,
        Object originalConnection,
        long connectionGeneration,
        Source source,
        CaptureHandoffBudget.Lease handoffLease,
        HandoffDisposition handoffDisposition) {

    public InvocationCapture {
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(handoffDisposition, "handoffDisposition");
        // System.nanoTime() has an arbitrary origin and is permitted to be negative.
        // Only elapsed-time arithmetic is meaningful for this field.
        if (invocationId.equals(eventId) || receivedAtUnixMillis < 0 ||
                receivedAtUnixMillis > 9_007_199_254_740_991L || connectionGeneration < 0) {
            throw new IllegalArgumentException("packet receive evidence is invalid");
        }
        if ((handoffDisposition == HandoffDisposition.LEASED) != (handoffLease != null) ||
                handoffDisposition == HandoffDisposition.CAPACITY_REJECTED && handoffLease != null) {
            throw new IllegalArgumentException("capture handoff disposition is inconsistent");
        }
    }

    public InvocationCapture(UUID invocationId, UUID eventId, long receivedAtNanos, long receivedAtUnixMillis,
                             Object originalConnection, long connectionGeneration, Source source) {
        this(invocationId, eventId, receivedAtNanos, receivedAtUnixMillis,
                originalConnection, connectionGeneration, source, null, HandoffDisposition.NOT_TRACKED);
    }

    public InvocationCapture(UUID invocationId, UUID eventId, long receivedAtNanos, long receivedAtUnixMillis,
                             Object originalConnection, long connectionGeneration, Source source,
                             CaptureHandoffBudget.Lease handoffLease) {
        this(invocationId, eventId, receivedAtNanos, receivedAtUnixMillis, originalConnection,
                connectionGeneration, source, handoffLease, handoffLease == null
                        ? HandoffDisposition.NOT_TRACKED : HandoffDisposition.LEASED);
    }

    /** The first clock read is the receipt clock; this overload is also used by deterministic capture tests. */
    public static InvocationCapture capture(LongSupplier monotonicClock, LongSupplier wallClock,
                                            Object originalConnection, long connectionGeneration, Source source) {
        long receivedAtNanos = monotonicClock.getAsLong();
        long receivedAtUnixMillis = wallClock.getAsLong();
        return new InvocationCapture(UUID.randomUUID(), UUID.randomUUID(), receivedAtNanos,
                receivedAtUnixMillis, originalConnection, connectionGeneration, source);
    }

    /** Production packet-head capture: receipt clocks precede bounded handoff accounting. */
    public static InvocationCapture captureHandoff(LongSupplier monotonicClock, LongSupplier wallClock,
                                                   Object originalConnection, long connectionGeneration,
                                                   Source source) {
        long receivedAtNanos = monotonicClock.getAsLong();
        long receivedAtUnixMillis = wallClock.getAsLong();
        Objects.requireNonNull(originalConnection, "originalConnection");
        Objects.requireNonNull(source, "source");
        if (connectionGeneration <= 0) throw new IllegalArgumentException("connection generation is required");
        CaptureHandoffBudget.Lease lease = CaptureHandoffBudget.tryAcquire(originalConnection);
        try {
            return new InvocationCapture(UUID.randomUUID(), UUID.randomUUID(), receivedAtNanos,
                    receivedAtUnixMillis, originalConnection, connectionGeneration, source, lease,
                    lease == null ? HandoffDisposition.CAPACITY_REJECTED : HandoffDisposition.LEASED);
        } catch (RuntimeException | Error failure) {
            if (lease != null) lease.release();
            throw failure;
        }
    }

    public boolean belongsTo(Object connection, long generation) {
        return originalConnection != null && originalConnection == connection &&
                connectionGeneration > 0 && connectionGeneration == generation;
    }

    public void releaseHandoff() {
        if (handoffLease != null) handoffLease.release();
    }

    public boolean handoffCapacityRejected() {
        return handoffDisposition == HandoffDisposition.CAPACITY_REJECTED;
    }

    public enum HandoffDisposition { NOT_TRACKED, LEASED, CAPACITY_REJECTED }

    public enum Source { SIGNED_CHAT, UNSIGNED_COMMAND, SIGNED_COMMAND }
}
