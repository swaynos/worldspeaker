package org.worldspeaker.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.bootstrap.FirstReplyScheduler;
import org.worldspeaker.bootstrap.NoticePublicationTestAccess;
import org.worldspeaker.compat.ConfiguredBinding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstReplyMetricsMaterializationTest {
    private static final long WALL_TIME_MILLIS = 1_793_275_200_000L;
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-4000-8000-000000000200");
    private static final ConfiguredBinding BINDING = new ConfiguredBinding("server", "world", "character", "agent");

    @TempDir Path directory;

    @Test
    void stagedDeadlineThenSubmittedNoticeExportsOneLateSampleAndOneMissAcrossRepeatedSnapshots() throws Exception {
        TickMetrics metrics = new TickMetrics();
        Path evidenceDirectory = privateDirectory("late-after-deadline");
        Path evidencePath = evidenceDirectory.resolve("tick-metrics.jsonl");
        FirstReplyScheduler scheduler = new FirstReplyScheduler(() -> 0, () -> 0);
        long oldT0 = 3_000_000_000L;
        FirstReplyScheduler.Slot oldSlot = scheduler.createSlot(oldT0, "SYNTHETIC_HARNESS");
        TickMetrics.InvocationEvidence oldEvidence = invocation(metrics, "00000000-0000-4000-8000-000000000101",
                oldT0, oldSlot, "ADMITTED");
        metrics.recordInteractionRequest(oldEvidence, "request-old", oldT0 + 30, 1);

        FirstReplyScheduler.NoticePublication publication = new FirstReplyScheduler.NoticePublication();
        assertTrue(NoticePublicationTestAccess.linkAndClaim(oldSlot, publication),
                "the physical batch is already claimed before the deadline observation");
        metrics.recordNoticePublication(oldEvidence, publication,
                TickMetrics.FirstPublicSource.LOCAL_ACKNOWLEDGEMENT,
                TickMetrics.FirstPublicDisposition.ACKNOWLEDGED);

        long deadlineSettlementNanos = 4_001_000_000L;
        assertEquals(1, metrics.settleFirstReplyDeadlines(deadlineSettlementNanos));
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);
        assertTrue(oldSlot.settledNoDispatch());

        long firstT1 = 4_200_000_000L;
        assertTrue(NoticePublicationTestAccess.complete(publication,
                FirstReplyScheduler.PublicationResult.SUBMITTED, firstT1));

        long fastT0 = 4_100_000_000L;
        TickMetrics.InvocationEvidence fastEvidence = invocation(metrics, "00000000-0000-4000-8000-000000000102",
                fastT0, null, "ADMITTED");
        metrics.recordInteractionRequest(fastEvidence, "request-fast", fastT0 + 30, 1);
        metrics.recordInteractionDispatch(fastEvidence, 100_000_000L,
                TickMetrics.FirstPublicSource.INTEGRATION_ANSWER,
                TickMetrics.FirstPublicDisposition.ANSWERED, fastT0 + 100_000_000L,
                "00000000-0000-4000-8000-000000000103");

        assertEquals(1_200_000_000L, metrics.maxFirstPublicDispatchNanos());
        metrics.settleFirstReplyDeadlines(4_002_000_000L);
        assertTrue(oldSlot.settledNoDispatch(), "metrics materialization never reopens or replays the slot");
        assertEquals(FirstReplyScheduler.Claim.SETTLED, scheduler.claimAcknowledgement(oldSlot));
        assertEquals(1, metrics.firstPublicDispatchHistogramSnapshot()
                [TickMetrics.FirstPublicSource.LOCAL_ACKNOWLEDGEMENT.ordinal()]
                [TickMetrics.FirstPublicDisposition.ACKNOWLEDGED.ordinal()][4]);
        assertEquals(1, metrics.firstPublicDispatchHistogramSnapshot()
                [TickMetrics.FirstPublicSource.INTEGRATION_ANSWER.ordinal()]
                [TickMetrics.FirstPublicDisposition.ANSWERED.ordinal()][0]);
        assertEquals(2L, histogramSampleCount(metrics));
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_LATE.ordinal()]);
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_ON_TIME.ordinal()]);
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_NO_DISPATCH.ordinal()]);

        assertTrue(metrics.offerNumericSnapshot(evidencePath));
        assertTrue(metrics.maxFirstPublicDispatchNanos() == 1_200_000_000L);
        assertTrue(metrics.firstPublicDispatchHistogramSnapshot()
                [TickMetrics.FirstPublicSource.LOCAL_ACKNOWLEDGEMENT.ordinal()]
                [TickMetrics.FirstPublicDisposition.ACKNOWLEDGED.ordinal()][4] == 1);
        assertTrue(metrics.offerNumericSnapshot(evidencePath), "a repeated export must preserve counts and samples");
        assertTrue(metrics.awaitTerminationForTests(3, TimeUnit.SECONDS));

        String raw = Files.readString(evidencePath);
        assertTrue(raw.contains("\"firstDispatchNanos\":\"1200000000\""));
        assertTrue(raw.contains("\"firstDispatchNanos\":\"100000000\""));
        assertTrue(raw.contains("\"firstDispatchAtNanos\":\"4200000000\""));
        assertTrue(raw.contains("\"deadlineSettledAtNanos\":\"4001000000\""));
        assertTrue(raw.contains("\"firstReplyResult\":\"LATE\",\"sendResult\":\"SUBMITTED\""));
        assertTrue(raw.contains("\"firstDispatchSource\":\"LOCAL_ACKNOWLEDGEMENT\""));
        assertTrue(raw.contains("\"firstDispatchDisposition\":\"ACKNOWLEDGED\""));
        assertTrue(raw.contains("\"publicationId\":\"" + publication.publicationId() + "\""));
        assertTrue(raw.contains("\"noticeBatchPublicationId\":\"" + publication.publicationId() + "\""));
        assertTrue(raw.contains("\"noticeBatchResult\":\"SUBMITTED\""));
        assertTrue(raw.contains("\"noticeBatchLatencyNanos\":\"1200000000\""));
        assertTrue(raw.contains("\"noticeStatus\":\"SUBMITTED\""));

        Path reportDirectory = evidenceDirectory.resolve("report");
        runOperationalReport(evidencePath, reportDirectory);
        String report = Files.readString(reportDirectory.resolve("report.json"));
        assertTrue(report.contains("\"status\": \"COMPLETE\""), report);
        assertTrue(report.contains("\"snapshotCount\": 2"));
        var snapshots = JsonParser.parseString(report).getAsJsonObject().getAsJsonArray("snapshots");
        assertEquals(2, snapshots.size());
        for (var element : snapshots) {
            JsonObject firstDispatch = element.getAsJsonObject().getAsJsonObject("firstDispatch");
            assertEquals(2, firstDispatch.get("sampleCount").getAsInt());
            assertEquals(1200, firstDispatch.get("maxMs").getAsInt());
            assertEquals(1, firstDispatch.get("over1000msCount").getAsInt());
            assertEquals(1, firstDispatch.get("noDispatchAtDeadlineCount").getAsInt());
            assertEquals(0, firstDispatch.get("eventualNoDispatchCount").getAsInt());
            assertEquals(1, firstDispatch.get("knownDeadlineMissCount").getAsInt());
            assertEquals(1, firstDispatch.get("aggregateDeadlineMissCount").getAsInt());
            assertTrue(firstDispatch.get("deadlineCounterMatches").getAsBoolean());
            assertTrue(firstDispatch.get("noDispatchCounterMatches").getAsBoolean());
            assertTrue(firstDispatch.get("allFirstReplyCountersMatch").getAsBoolean());
        }
        String markdown = Files.readString(reportDirectory.resolve("report.md"));
        assertTrue(markdown.contains("1 unique linked misses (1 late first dispatches; 1 no-dispatch observations at/after deadline, with overlap counted once"));
    }

    @Test
    void unknownUnsentAndPreClaimCancellationNeverInventSuccessfulFirstDispatch() throws Exception {
        TickMetrics metrics = new TickMetrics();
        Path evidenceDirectory = privateDirectory("no-first-dispatch");
        Path evidencePath = evidenceDirectory.resolve("tick-metrics.jsonl");
        FirstReplyScheduler scheduler = new FirstReplyScheduler(() -> 0, () -> 0);

        long unknownT0 = -3_000_000_000L;
        TickMetrics.InvocationEvidence unknown = invocation(metrics, "00000000-0000-4000-8000-000000000111",
                unknownT0, null, "BUSY");
        metrics.recordUnknownInteractionDispatch(unknown, "00000000-0000-4000-8000-000000000114");
        metrics.recordNoFirstDispatch(unknown, unknownT0 + TimeUnit.SECONDS.toNanos(1), true);

        long unsentT0 = -1_000_000_000L;
        TickMetrics.InvocationEvidence unsent = invocation(metrics, "00000000-0000-4000-8000-000000000112",
                unsentT0, null, "BUSY");
        metrics.recordFailedInteractionDispatch(unsent);
        metrics.recordNoFirstDispatch(unsent, unsentT0 + TimeUnit.MILLISECONDS.toNanos(500), false);

        long cancelledT0 = -1_000_000_000L;
        FirstReplyScheduler.Slot cancelledSlot = scheduler.createSlot(cancelledT0, "SYNTHETIC_HARNESS");
        TickMetrics.InvocationEvidence cancelled = invocation(metrics, "00000000-0000-4000-8000-000000000113",
                cancelledT0, cancelledSlot, "BUSY");
        FirstReplyScheduler.NoticePublication cancelledPublication = new FirstReplyScheduler.NoticePublication();
        assertTrue(NoticePublicationTestAccess.link(cancelledSlot, cancelledPublication));
        metrics.recordNoticePublication(cancelled, cancelledPublication,
                TickMetrics.FirstPublicSource.LOCAL_SAFE_RESPONSE,
                TickMetrics.FirstPublicDisposition.FAILED);
        assertTrue(NoticePublicationTestAccess.cancel(cancelledPublication),
                "cancellation wins before the batch claims the publication");

        assertEquals(3, metrics.settleFirstReplyDeadlines(0));
        assertTrue(cancelledSlot.settledNoDispatch());
        assertEquals(0L, metrics.maxFirstPublicDispatchNanos());
        assertEquals(0L, histogramSampleCount(metrics));
        assertEquals(2, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);
        assertEquals(3, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_NO_DISPATCH.ordinal()]);
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_NO_DISPATCH_SETTLED.ordinal()]);

        assertTrue(metrics.offerNumericSnapshot(evidencePath));
        assertTrue(metrics.awaitTerminationForTests(3, TimeUnit.SECONDS));
        String raw = Files.readString(evidencePath);
        assertTrue(raw.contains("\"sendResult\":\"UNKNOWN\""));
        assertTrue(raw.contains("\"sendResult\":\"DEFINITELY_UNSENT\""));
        assertTrue(raw.contains("\"noticeStatus\":\"CANCELLED\""));
        assertTrue(raw.contains("\"firstReplyResult\":\"NO_DISPATCH_SETTLED\""));
        assertFalse(raw.contains("\"firstDispatchAtNanos\":\""), "no unknown, unsent, or cancelled result gets a t1");

        Path reportDirectory = evidenceDirectory.resolve("report");
        runOperationalReport(evidencePath, reportDirectory);
        String report = Files.readString(reportDirectory.resolve("report.json"));
        assertTrue(report.contains("\"status\": \"COMPLETE\""), report);
        assertTrue(report.contains("\"sampleCount\": 0"));
        assertTrue(report.contains("\"knownDeadlineMissCount\": 2"));
        assertTrue(report.contains("\"eventualNoDispatchCount\": 3"));
        assertTrue(report.contains("\"noDispatchCounterMatches\": true"));
        assertTrue(report.contains("\"deadlineCounterMatches\": true"));
    }

    @Test
    void stageEvidenceHandlesSignedMonotonicTimestampsAndARealLongWrap() throws Exception {
        TickMetrics metrics = new TickMetrics();
        Path evidenceDirectory = privateDirectory("wrapped-negative-clock");
        Path evidencePath = evidenceDirectory.resolve("tick-metrics.jsonl");
        FirstReplyScheduler scheduler = new FirstReplyScheduler(() -> 0, () -> 0);

        long wrapT0 = Long.MAX_VALUE - 600_000_000L;
        long wrappedT1 = wrapT0 + 1_200_000_000L;
        long wrappedDeadline = wrapT0 + TimeUnit.SECONDS.toNanos(1);
        FirstReplyScheduler.Slot wrapSlot = scheduler.createSlot(wrapT0, "SYNTHETIC_HARNESS");
        TickMetrics.InvocationEvidence wrapped = invocation(metrics, "00000000-0000-4000-8000-000000000121",
                wrapT0, wrapSlot, "BUSY");
        assertEquals(1, metrics.settleFirstReplyDeadlines(wrappedDeadline));
        metrics.recordInteractionDispatch(wrapped, wrappedT1 - wrapT0,
                TickMetrics.FirstPublicSource.LOCAL_SAFE_RESPONSE,
                TickMetrics.FirstPublicDisposition.FAILED, wrappedT1,
                "00000000-0000-4000-8000-000000000123");

        long negativeT0 = -5_000_000_000L;
        long negativeT1 = -4_900_000_000L;
        TickMetrics.InvocationEvidence negative = invocation(metrics, "00000000-0000-4000-8000-000000000122",
                negativeT0, null, "HELP");
        metrics.recordInteractionDispatch(negative, negativeT1 - negativeT0,
                TickMetrics.FirstPublicSource.COMMAND_HELP,
                TickMetrics.FirstPublicDisposition.HELP, negativeT1,
                "00000000-0000-4000-8000-000000000124");

        assertEquals(1_200_000_000L, metrics.maxFirstPublicDispatchNanos());
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_LATE.ordinal()]);
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_ON_TIME.ordinal()]);
        assertTrue(metrics.offerNumericSnapshot(evidencePath));
        assertTrue(metrics.awaitTerminationForTests(3, TimeUnit.SECONDS));

        String raw = Files.readString(evidencePath);
        assertTrue(raw.contains("\"t0Nanos\":\"" + wrapT0 + "\""));
        assertTrue(raw.contains("\"firstDispatchAtNanos\":\"" + wrappedT1 + "\""));
        assertTrue(raw.contains("\"deadlineSettledAtNanos\":\"" + wrappedDeadline + "\""));
        assertTrue(raw.contains("\"t0Nanos\":\"-5000000000\""));
        assertTrue(raw.contains("\"firstDispatchAtNanos\":\"-4900000000\""));

        Path reportDirectory = evidenceDirectory.resolve("report");
        runOperationalReport(evidencePath, reportDirectory);
        String report = Files.readString(reportDirectory.resolve("report.json"));
        assertTrue(report.contains("\"status\": \"COMPLETE\""), report);
        assertTrue(report.contains("\"maxMs\": 1200"));
        assertTrue(report.contains("\"knownDeadlineMissCount\": 1"));
        assertTrue(report.contains("\"deadlineCounterMatches\": true"));
    }

    private TickMetrics.InvocationEvidence invocation(TickMetrics metrics, String invocationId, long capturedAtNanos,
                                                      FirstReplyScheduler.Slot slot, String outcome) {
        return metrics.recordInteractionInvocation(UUID.fromString(invocationId), capturedAtNanos,
                WALL_TIME_MILLIS, capturedAtNanos + 10, "SYNTHETIC_HARNESS", null, 1, PLAYER_ID,
                "minecraft:overworld", BINDING, "COMMAND", outcome, slot, capturedAtNanos + 20,
                "sha256:abcd", "SYNTHETIC_HARNESS");
    }

    private Path privateDirectory(String name) throws Exception {
        Path path = directory.toRealPath().resolve(name);
        Files.createDirectory(path);
        Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return path;
    }

    private static long histogramSampleCount(TickMetrics metrics) {
        long count = 0;
        for (long[][] source : metrics.firstPublicDispatchHistogramSnapshot()) {
            for (long[] disposition : source) {
                for (long bucket : disposition) count += bucket;
            }
        }
        return count;
    }

    private static void runOperationalReport(Path evidencePath, Path reportDirectory) throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        List<String> command = List.of("node", projectRoot.resolve("tools/dialogue/evaluate.mjs").toString(),
                "operational-report", "--source", evidencePath.toString(), "--out", reportDirectory.toString());
        Process process = new ProcessBuilder(command).directory(projectRoot.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int status = process.waitFor();
        assertEquals(0, status, String.join(" ", command) + " failed: " + output);
    }
}
