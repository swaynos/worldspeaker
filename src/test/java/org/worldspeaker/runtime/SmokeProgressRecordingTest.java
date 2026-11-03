package org.worldspeaker.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.bootstrap.ConversationProgress;
import org.worldspeaker.bootstrap.FirstReplyScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeProgressRecordingTest {
    private static final long T0 = TimeUnit.SECONDS.toNanos(5);
    private static final UUID INVOCATION = UUID.fromString("00000000-0000-4000-8000-000000000030");

    @TempDir Path directory;

    @Test
    void productionForwardTransitionRecordsTheInclusiveTenSecondAndElevenSecondQueuedMissOnce() throws Exception {
        assertForwardedMiss(TimeUnit.SECONDS.toNanos(10), "boundary");
        assertForwardedMiss(TimeUnit.SECONDS.toNanos(11), "overdue");
    }

    @Test
    void forwardingWithoutAnAcknowledgementDoesNotRecordAnIneligibleQueuedOpportunity() throws Exception {
        TickMetrics metrics = new TickMetrics();
        TickMetrics.InvocationEvidence evidence = evidence(metrics);
        ConversationProgress progress = new ConversationProgress(T0);
        AtomicInteger recorded = new AtomicInteger();
        long forwardedAt = T0 + TimeUnit.SECONDS.toNanos(11);

        assertFalse(progress.markForwarded(forwardedAt, false,
                phase -> record(metrics, evidence, phase, "missed", forwardedAt, recorded)));
        assertFalse(progress.markForwarded(forwardedAt + 1, true,
                phase -> record(metrics, evidence, phase, "missed", forwardedAt + 1, recorded)));
        assertEquals(0, recorded.get());
        assertEquals(0, progress.attemptedCount());
        assertEquals(0, count(metrics, TickMetrics.Counter.PROGRESS_MISSED));
        assertSnapshot(metrics, "no-ack.jsonl", "\"progress\":[]");
    }

    @Test
    void expiryAndActiveMissedBoundsUseTheSameRecordingSinkAndRespectThreeNoticeMaximum() throws Exception {
        TickMetrics metrics = new TickMetrics();
        TickMetrics.InvocationEvidence evidence = evidence(metrics);
        ConversationProgress pending = new ConversationProgress(T0);
        AtomicInteger recorded = new AtomicInteger();
        long pendingExpiry = T0 + TimeUnit.SECONDS.toNanos(15);

        pending.markDueMissed(pendingExpiry, true, true, false,
                phase -> record(metrics, evidence, phase, "missed", pendingExpiry, recorded));
        pending.markDueMissed(pendingExpiry, true, true, false,
                phase -> record(metrics, evidence, phase, "missed", pendingExpiry, recorded));
        assertEquals(1, recorded.get(), "an expired queued opportunity is consumed once");
        assertEquals(1, count(metrics, TickMetrics.Counter.PROGRESS_MISSED));

        ConversationProgress active = new ConversationProgress(T0);
        long forwardedAt = T0 + TimeUnit.SECONDS.toNanos(1);
        assertFalse(active.markForwarded(forwardedAt, true));
        long activeExpiry = forwardedAt + TimeUnit.SECONDS.toNanos(30);
        active.markDueMissed(activeExpiry, true, false, true,
                phase -> record(metrics, evidence, phase, "missed", activeExpiry, recorded));
        active.markDueMissed(activeExpiry, true, false, true,
                phase -> record(metrics, evidence, phase, "missed", activeExpiry, recorded));

        assertEquals(3, recorded.get(), "each of the two active bounds is consumed once");
        assertEquals(2, active.attemptedCount());
        assertEquals(3, count(metrics, TickMetrics.Counter.PROGRESS_MISSED));
        String contents = assertSnapshot(metrics, "missed-bounds.jsonl",
                "\"phase\":\"queued\",\"state\":\"queued\",\"status\":\"missed\"",
                "\"phase\":\"awaiting_answer\",\"state\":\"forwarded_waiting\",\"status\":\"missed\"");
        assertEquals(3, occurrences(contents, "\"status\":\"missed\""));
    }

    @Test
    void queuedMissAndActiveSuccessOrFailureSharePerInvocationAndAggregateRecording() throws Exception {
        TickMetrics metrics = new TickMetrics();
        TickMetrics.InvocationEvidence evidence = evidence(metrics);
        ConversationProgress progress = new ConversationProgress(T0);
        long forwardedAt = T0 + TimeUnit.SECONDS.toNanos(11);
        AtomicInteger recorded = new AtomicInteger();

        assertTrue(progress.markForwarded(forwardedAt, true,
                phase -> record(metrics, evidence, phase, "missed", forwardedAt, recorded)));
        assertTrue(progress.markAttempted(ConversationProgress.Phase.AWAITING_ANSWER));
        assertTrue(progress.recordDispatched(item("active-success", 1, ConversationProgress.Phase.AWAITING_ANSWER)));
        record(metrics, evidence, ConversationProgress.Phase.AWAITING_ANSWER, "dispatched",
                forwardedAt + ConversationProgress.INTERVAL_NANOS, recorded);
        assertTrue(progress.markAttempted(ConversationProgress.Phase.AWAITING_ANSWER));
        assertTrue(progress.recordDispatched(item("active-failure", 2, ConversationProgress.Phase.AWAITING_ANSWER)));
        record(metrics, evidence, ConversationProgress.Phase.AWAITING_ANSWER, "failed",
                forwardedAt + 2 * ConversationProgress.INTERVAL_NANOS, recorded);

        assertEquals(3, progress.attemptedCount());
        assertEquals(2, progress.dispatched().size(), "only the active public notices were dispatched");
        assertEquals(3, recorded.get());
        assertEquals(1, count(metrics, TickMetrics.Counter.PROGRESS_MISSED));
        assertEquals(1, count(metrics, TickMetrics.Counter.PROGRESS_DISPATCHES));
        assertEquals(1, count(metrics, TickMetrics.Counter.PROGRESS_FAILURES));
        String contents = assertSnapshot(metrics, "forward-outcomes.jsonl",
                "\"phase\":\"queued\",\"state\":\"queued\",\"status\":\"missed\"",
                "\"phase\":\"awaiting_answer\",\"state\":\"forwarded_waiting\",\"status\":\"dispatched\"",
                "\"phase\":\"awaiting_answer\",\"state\":\"forwarded_waiting\",\"status\":\"failed\"");
        assertEquals(1, occurrences(contents, "\"status\":\"missed\""));
        assertEquals(1, occurrences(contents, "\"status\":\"dispatched\""));
        assertEquals(1, occurrences(contents, "\"status\":\"failed\""));
    }

    private void assertForwardedMiss(long elapsedNanos, String name) throws Exception {
        TickMetrics metrics = new TickMetrics();
        TickMetrics.InvocationEvidence evidence = evidence(metrics);
        ConversationProgress progress = new ConversationProgress(T0);
        AtomicInteger recorded = new AtomicInteger();
        long forwardedAt = T0 + elapsedNanos;

        assertTrue(progress.markForwarded(forwardedAt, true,
                phase -> record(metrics, evidence, phase, "missed", forwardedAt, recorded)));
        assertFalse(progress.markForwarded(forwardedAt + 1, true,
                phase -> record(metrics, evidence, phase, "missed", forwardedAt + 1, recorded)));
        assertEquals(1, recorded.get());
        assertEquals(1, progress.attemptedCount());
        assertTrue(progress.dispatched().isEmpty(), "a missed public notice is not evidence of a dispatch");
        assertEquals(1, count(metrics, TickMetrics.Counter.PROGRESS_MISSED));
        assertSnapshot(metrics, name + ".jsonl",
                "\"phase\":\"queued\",\"state\":\"queued\",\"status\":\"missed\"",
                "\"latencyNanos\":\"" + elapsedNanos + "\"");
    }

    private static TickMetrics.InvocationEvidence evidence(TickMetrics metrics) {
        return metrics.recordInteractionInvocation(INVOCATION, T0,
                UUID.fromString("00000000-0000-4000-8000-000000000031"), "minecraft:overworld",
                null, "COMMAND", "ADMITTED");
    }

    private static void record(TickMetrics metrics, TickMetrics.InvocationEvidence evidence,
                               ConversationProgress.Phase phase, String status, long atNanos,
                               AtomicInteger recorded) {
        String phaseName = phase == ConversationProgress.Phase.QUEUED ? "queued" : "awaiting_answer";
        String state = phase == ConversationProgress.Phase.QUEUED ? "queued" : "forwarded_waiting";
        long latencyNanos = Math.max(0, atNanos - T0);
        if ("missed".equals(status)) {
            metrics.recordInteractionProgressMissed(evidence, phaseName, state, latencyNanos);
        } else {
            FirstReplyScheduler.PublicationResult result = switch (status) {
                case "dispatched" -> FirstReplyScheduler.PublicationResult.SUBMITTED;
                case "failed" -> FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
                case "unknown" -> FirstReplyScheduler.PublicationResult.UNKNOWN;
                default -> throw new IllegalArgumentException("unexpected progress status");
            };
            boolean dispatched = result == FirstReplyScheduler.PublicationResult.SUBMITTED;
            metrics.recordInteractionProgress(evidence, phaseName, state, result, latencyNanos,
                    dispatched ? atNanos : null, dispatched ? "progress-test-" + recorded.get() : null);
        }
        recorded.incrementAndGet();
    }

    private static ConversationProgress.DialogueProgress item(String id, long nanos,
                                                               ConversationProgress.Phase phase) {
        return new ConversationProgress.DialogueProgress(id, "[Worldspeaker] A bounded progress notice.",
                1_729_500_000_000L, nanos, "00000000-0000-4000-8000-000000000031", phase);
    }

    private static long count(TickMetrics metrics, TickMetrics.Counter counter) {
        return metrics.countersSnapshot()[counter.ordinal()];
    }

    private String assertSnapshot(TickMetrics metrics, String name, String... expected) throws Exception {
        Path output = directory.resolve(name);
        assertTrue(metrics.offerNumericSnapshot(output));
        assertTrue(metrics.awaitTerminationForTests(3, TimeUnit.SECONDS));
        String contents = Files.readString(output);
        for (String fragment : expected) assertTrue(contents.contains(fragment), contents);
        return contents;
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(fragment, from)) >= 0) {
            count++;
            from += fragment.length();
        }
        return count;
    }
}
