package org.worldspeaker.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.bootstrap.FirstReplyScheduler;
import org.worldspeaker.compat.ConfiguredBinding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickMetricsTest {
    @TempDir Path directory;

    @Test
    void writesOnlyNumericSnapshotsToAnOwnerOnlyNewFileAndCapsBatches() throws Exception {
        TickMetrics metrics = new TickMetrics();
        Path output = directory.resolve("tick-metrics.jsonl");
        metrics.increment(TickMetrics.Counter.REQUESTS_ADMITTED);
        metrics.recordTickNanos(42_000_000);
        metrics.recordFirstPublicDispatchNanos(600_000_000,
                TickMetrics.FirstPublicSource.LOCAL_ACKNOWLEDGEMENT,
                TickMetrics.FirstPublicDisposition.ACKNOWLEDGED);
        assertEquals(1, metrics.firstPublicDispatchHistogramSnapshot()
                [TickMetrics.FirstPublicSource.LOCAL_ACKNOWLEDGEMENT.ordinal()]
                [TickMetrics.FirstPublicDisposition.ACKNOWLEDGED.ordinal()][2]);
        assertTrue(metrics.offerNumericSnapshot(output));
        assertTrue(metrics.offerNumericSnapshot(output));
        assertFalse(metrics.offerNumericSnapshot(output), "a run owns at most two evidence batches");
        assertTrue(metrics.evidenceIncomplete(), "refusal beyond the finite evidence cap is visible");
        assertTrue(metrics.awaitTerminationForTests(3, TimeUnit.SECONDS));
        assertTrue(Files.size(output) > 0);
        String contents = Files.readString(output);
        assertTrue(contents.contains("\"counters\""));
        assertTrue(contents.contains("\"tickHistogram\""));
        assertTrue(contents.contains("\"firstPublicDispatchHistograms\""));
        assertFalse(contents.contains("REQUESTS_ADMITTED"), "snapshots do not contain labels or request data");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(output);
        assertTrue(permissions.contains(PosixFilePermission.OWNER_READ));
        assertTrue(permissions.contains(PosixFilePermission.OWNER_WRITE));
        assertFalse(permissions.contains(PosixFilePermission.GROUP_READ));
        assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ));
    }

    @Test
    void refusesPreexistingEvidenceFileAndMarksCaptureIncomplete() throws Exception {
        TickMetrics metrics = new TickMetrics();
        Path output = directory.resolve("existing.jsonl");
        Files.writeString(output, "keep existing\n");
        assertTrue(metrics.offerNumericSnapshot(output));
        assertTrue(metrics.awaitTerminationForTests(3, TimeUnit.SECONDS));
        assertTrue(metrics.evidenceIncomplete());
        assertTrue(Files.readString(output).equals("keep existing\n"));
    }

    @Test
    void actualDifferentPathWriterFailureClosesReservationAndAdmissionAuthority() throws Exception {
        TickMetrics metrics = new TickMetrics();
        UUID invocationId = UUID.fromString("00000000-0000-4000-8000-000000000050");
        TickMetrics.InvocationEvidence reserved = metrics.reserveInteractionInvocation(invocationId,
                100, 200, 120, "SYNTHETIC_HARNESS", null, 0,
                UUID.fromString("00000000-0000-4000-8000-000000000051"), "minecraft:overworld",
                new ConfiguredBinding("server", "world", "character", "agent"), "COMMAND", null,
                300, null, "SYNTHETIC_HARNESS");
        assertTrue(metrics.hasCurrentInteractionReservation(invocationId));
        assertTrue(metrics.canReserveInteraction());

        Path firstPath = directory.resolve("first-owner-path.jsonl");
        Path differentPath = directory.resolve("different-owner-path.jsonl");
        assertTrue(metrics.offerNumericSnapshot(firstPath));
        assertTrue(metrics.offerNumericSnapshot(differentPath));
        assertTrue(metrics.awaitTerminationForTests(3, TimeUnit.SECONDS));

        assertTrue(Files.size(firstPath) > 0);
        assertFalse(Files.exists(differentPath), "the writer does not switch ownership after its first file");
        assertEquals(1, metrics.persistedBatches(), "the successful first write is not erased by the later failure");
        assertTrue(metrics.evidenceIncomplete());
        assertFalse(metrics.canReserveInteraction());
        assertFalse(metrics.hasCurrentInteractionReservation(invocationId),
                "a reservation from the prior authority version cannot commit after writer failure");
        assertFalse(metrics.commitInteractionAdmission(reserved));

        assertEquals(null, metrics.reserveInteractionInvocation(UUID.fromString(
                        "00000000-0000-4000-8000-000000000052"), 400, 500, 410,
                "SYNTHETIC_HARNESS", null, 0, null, "minecraft:overworld", null, "COMMAND",
                null, 420, null, "SYNTHETIC_HARNESS"));
        long[] counts = metrics.countersSnapshot();
        assertEquals(2, counts[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
        assertEquals(1, counts[TickMetrics.Counter.INTERACTION_EVIDENCE_DROPPED.ordinal()]);
        assertEquals(0, counts[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()]);
        assertEquals(1, metrics.reservedInteractionCount(), "the known reserved row remains retained in memory");
        assertTrue(reserved != null);
    }

    @Test
    void evidenceTokenCannotBeConsumedByAnotherMetricsOwnerOrCommittedTwice() {
        UUID invocationId = UUID.fromString("00000000-0000-4000-8000-000000000053");
        TickMetrics owner = new TickMetrics();
        TickMetrics otherOwner = new TickMetrics();
        TickMetrics.InvocationEvidence ownerEvidence = owner.reserveInteractionInvocation(invocationId,
                100, 200, 120, "SYNTHETIC_HARNESS", null, 0, null, "minecraft:overworld",
                null, "COMMAND", null, 300, null, "SYNTHETIC_HARNESS");
        TickMetrics.InvocationEvidence otherEvidence = otherOwner.reserveInteractionInvocation(invocationId,
                100, 200, 120, "SYNTHETIC_HARNESS", null, 0, null, "minecraft:overworld",
                null, "COMMAND", null, 300, null, "SYNTHETIC_HARNESS");

        assertTrue(owner.hasCurrentInteractionReservation(ownerEvidence));
        assertFalse(owner.hasCurrentInteractionReservation(otherEvidence));
        assertFalse(otherOwner.hasCurrentInteractionReservation(ownerEvidence));
        assertFalse(otherOwner.commitInteractionAdmission(ownerEvidence));
        assertTrue(owner.commitInteractionAdmission(ownerEvidence));
        assertFalse(owner.commitInteractionAdmission(ownerEvidence), "one row can authorize at most one admission");
        assertTrue(otherOwner.commitInteractionAdmission(otherEvidence));
    }

    @Test
    void recordsBoundedBodyFreeInvocationAttributionAndOriginalDispatchDuration() throws Exception {
        TickMetrics metrics = new TickMetrics();
        Path output = directory.resolve("interaction-evidence.jsonl");
        UUID invocationId = UUID.fromString("00000000-0000-4000-8000-000000000010");
        UUID playerId = UUID.fromString("00000000-0000-4000-8000-000000000011");
        ConfiguredBinding binding = new ConfiguredBinding("server", "world", "stable-character", "agent-private-id");
        TickMetrics.InvocationEvidence evidence = metrics.recordInteractionInvocation(invocationId, 100,
                playerId, "minecraft:overworld", binding, "COMMAND", "COALESCED");
        metrics.recordInteractionDispatch(evidence, 1_250_000_000L,
                TickMetrics.FirstPublicSource.LOCAL_COALESCED,
                TickMetrics.FirstPublicDisposition.COALESCED);
        metrics.recordFailedInteractionDispatch(evidence);

        assertEquals(1_250_000_000L, metrics.maxFirstPublicDispatchNanos());
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_COALESCED.ordinal()]);
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FAILED_PUBLIC_DISPATCHES.ordinal()]);
        assertTrue(metrics.offerNumericSnapshot(output));
        assertTrue(metrics.awaitTerminationForTests(3, TimeUnit.SECONDS));
        String contents = Files.readString(output);
        assertTrue(contents.contains(invocationId.toString()));
        assertTrue(contents.contains(playerId.toString()));
        assertTrue(contents.contains("stable-character"));
        assertTrue(contents.contains("COALESCED"));
        assertTrue(contents.contains("1250000000"));
        assertFalse(contents.contains("agent-private-id"), "unneeded agent binding and private data are not serialized");
        assertFalse(contents.contains("request body"));
    }

    @Test
    void recordsProgressAndTerminalOutcomeWithRequestLinkageButNeverDialogueBodies() throws Exception {
        TickMetrics metrics = new TickMetrics();
        Path output = directory.resolve("progress-evidence.jsonl");
        TickMetrics.InvocationEvidence evidence = metrics.recordInteractionInvocation(
                UUID.fromString("00000000-0000-4000-8000-000000000020"), 1_000,
                UUID.fromString("00000000-0000-4000-8000-000000000021"), "minecraft:overworld",
                new ConfiguredBinding("server", "world", "character", "agent"), "COMMAND", "ADMITTED");
        metrics.recordInteractionRequest(evidence, "00000000-0000-4000-8000-000000000022");
        metrics.recordInteractionProgress(evidence, "queued", "queued",
                FirstReplyScheduler.PublicationResult.SUBMITTED, 10_000_000_000L,
                10_000_001_000L, "progress-queued");
        metrics.recordInteractionProgressContext(evidence, "progress-queued", "SEND_COMPLETED",
                10_000_002_000L);
        metrics.recordInteractionProgress(evidence, "awaiting_answer", "unknown", 30_000_000_000L);
        metrics.recordInteractionTerminal(evidence, "ANSWER", "SUBMITTED", 31_000_001_000L,
                31_000_000_000L);

        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.PROGRESS_DISPATCHES.ordinal()]);
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.PROGRESS_UNKNOWN.ordinal()]);
        assertEquals(0, metrics.countersSnapshot()[TickMetrics.Counter.PROGRESS_MISSED.ordinal()]);
        assertEquals(0, metrics.countersSnapshot()[TickMetrics.Counter.PROGRESS_FAILURES.ordinal()]);

        assertTrue(metrics.offerNumericSnapshot(output));
        assertTrue(metrics.awaitTerminationForTests(3, TimeUnit.SECONDS));
        String contents = Files.readString(output);
        assertTrue(contents.contains("\"requestId\":\"00000000-0000-4000-8000-000000000022\""));
        assertTrue(contents.contains("\"phase\":\"queued\",\"state\":\"queued\",\"status\":\"dispatched\",\"noticeId\":\"progress-queued\""));
        assertTrue(contents.contains("\"contextSendResult\":\"SEND_COMPLETED\",\"contextCompletedAtNanos\":\"10000002000\""));
        assertTrue(contents.contains("\"phase\":\"awaiting_answer\",\"state\":\"forwarded_waiting\",\"status\":\"unknown\""));
        assertTrue(contents.contains("\"terminalOutcome\":\"ANSWER\",\"terminalCause\":null,\"terminalDeliveryResult\":\"SUBMITTED\",\"retirementNoticeResult\":null,\"localCancelStatus\":\"NOT_ATTEMPTED\",\"localCancelSendResult\":null,\"providerCancel\":null,\"lateOutputRejections\":0,\"terminalAtNanos\":\"31000001000\",\"terminalNanos\":\"31000000000\""));
        assertFalse(contents.contains("still waiting"));
        assertFalse(contents.contains("a player question"));
    }

    @Test
    void deadlineSettlementIsExactOnceAndIncludesStageProvenanceWithoutBodies() throws Exception {
        TickMetrics metrics = new TickMetrics();
        Path output = directory.resolve("deadline-evidence.jsonl");
        FirstReplyScheduler scheduler = new FirstReplyScheduler(() -> 0, () -> 0);
        FirstReplyScheduler.Slot slot = scheduler.createSlot(100, "LIVE_PACKET");
        TickMetrics.InvocationEvidence evidence = metrics.recordInteractionInvocation(
                UUID.fromString("00000000-0000-4000-8000-000000000030"), 100, 200, 120,
                "LIVE_PACKET", new Object(), 8,
                UUID.fromString("00000000-0000-4000-8000-000000000031"), "minecraft:overworld",
                new ConfiguredBinding("server", "world", "character", "agent"), "COMMAND", "BUSY",
                slot, 300, "sha256:abcd");
        metrics.recordInteractionRequest(evidence, "request-1", 301);

        metrics.settleFirstReplyDeadlines(100 + TimeUnit.SECONDS.toNanos(1) - 1);
        assertEquals(0, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_NO_DISPATCH.ordinal()]);
        metrics.settleFirstReplyDeadlines(100 + TimeUnit.SECONDS.toNanos(1));
        metrics.settleFirstReplyDeadlines(100 + TimeUnit.SECONDS.toNanos(2));
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_NO_DISPATCH.ordinal()]);
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);

        assertTrue(metrics.offerNumericSnapshot(output));
        assertTrue(metrics.awaitTerminationForTests(3, TimeUnit.SECONDS));
        String contents = Files.readString(output);
        assertTrue(contents.contains("\"handlerDelayNanos\":\"20\""));
        assertTrue(contents.contains("\"admissionDelayNanos\":\"200\""));
        assertTrue(contents.contains("\"queueDelayNanos\":\"201\""));
        assertTrue(contents.contains("\"templateRevision\":\"sha256:abcd\""));
        assertTrue(contents.contains("\"firstReplyResult\":\"NO_DISPATCH_DEADLINE\""));
        assertTrue(contents.contains("\"deadlineSettledAtNanos\":\"1000000100\""));
        assertFalse(contents.contains("dialogue body"));
    }

    @Test
    void exportedFullRangeMonotonicTimesRoundTripThroughTheActualDialogueCliWithoutInventingTiming() throws Exception {
        TickMetrics metrics = new TickMetrics();
        Path evidenceDirectory = directory.toRealPath().resolve("java-cli-evidence");
        Files.createDirectory(evidenceDirectory);
        Files.setPosixFilePermissions(evidenceDirectory, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Path evidencePath = evidenceDirectory.resolve("tick-metrics.jsonl");

        UUID missingId = UUID.fromString("00000000-0000-4000-8000-000000000040");
        UUID largeId = UUID.fromString("00000000-0000-4000-8000-000000000041");
        UUID negativeId = UUID.fromString("00000000-0000-4000-8000-000000000042");
        UUID wrappedId = UUID.fromString("00000000-0000-4000-8000-000000000043");
        UUID playerId = UUID.fromString("00000000-0000-4000-8000-000000000044");
        ConfiguredBinding binding = new ConfiguredBinding("server", "world", "character", "agent-private-id");

        metrics.recordInteractionInvocation(missingId, Long.MIN_VALUE, 0, 55,
                "MISSING_PACKET_RECEIPT", null, 0, playerId, "minecraft:overworld", binding,
                "COMMAND", "RECEIPT_EVIDENCE_MISSING");
        long largeT0 = 9_007_199_254_740_993L;
        TickMetrics.InvocationEvidence large = metrics.recordInteractionInvocation(largeId, largeT0, 1_000,
                largeT0 + 25, "LIVE_PACKET", new Object(), 1, playerId, "minecraft:overworld", binding,
                "COMMAND", "ADMITTED", null, largeT0 + 25, null, "UNSIGNED_COMMAND");
        metrics.recordInteractionRequest(large, "request-large", largeT0 + 100, 1);
        long negativeT0 = Long.MIN_VALUE + 500;
        metrics.recordInteractionInvocation(negativeId, negativeT0, 1_001, negativeT0 + 30,
                "LIVE_PACKET", new Object(), 2, playerId, "minecraft:overworld", binding,
                "COMMAND", "BUSY", null, negativeT0 + 30, null, "SIGNED_CHAT");
        long wrappedT0 = Long.MAX_VALUE - 10;
        metrics.recordInteractionInvocation(wrappedId, wrappedT0, 1_002, Long.MIN_VALUE + 10,
                "LIVE_PACKET", new Object(), 3, playerId, "minecraft:overworld", binding,
                "COMMAND", "HELP", null, Long.MIN_VALUE + 10, null, "UNSIGNED_COMMAND");

        assertTrue(metrics.offerNumericSnapshot(evidencePath));
        assertTrue(metrics.awaitTerminationForTests(3, TimeUnit.SECONDS));
        String raw = Files.readString(evidencePath);
        assertTrue(raw.contains("\"numericFormatVersion\":" + TickMetrics.NUMERIC_FORMAT_VERSION));
        assertTrue(raw.contains("\"t0Nanos\":null,\"receiptWallTimeUnixMillis\":null"));
        assertTrue(raw.contains("\"captureProvenance\":\"MISSING_PACKET_RECEIPT\""));
        assertTrue(raw.contains("\"t0Nanos\":\"9007199254740993\""));
        assertTrue(raw.contains("\"t0Nanos\":\"-9223372036854775308\""));
        assertTrue(raw.contains("\"t0Nanos\":\"9223372036854775797\""));
        assertTrue(raw.contains("\"handlerDelayNanos\":\"21\""));
        assertFalse(raw.contains("\"t0Nanos\":-9223372036854775808"));

        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path reportDirectory = evidenceDirectory.resolve("report");
        runNodeCli(projectRoot, "operational-report", "--source", evidencePath.toString(), "--out", reportDirectory.toString());

        String report = Files.readString(reportDirectory.resolve("report.json"));
        assertTrue(report.contains("\"status\": \"COMPLETE\""));
        assertTrue(report.contains("\"sourceInvocationCount\": 4"));
        assertTrue(report.contains("\"captureEvidenceMissingCount\": 1"));
        assertTrue(report.contains("\"aggregateMaximumNanos\": null"));
        assertTrue(report.contains("\"ADMITTED\": 1"));
        assertTrue(report.contains("\"BUSY\": 1"));
        assertTrue(report.contains("\"HELP\": 1"));
        assertTrue(report.contains("\"RECEIPT_EVIDENCE_MISSING\": 1"));
        assertTrue(report.contains("\"INTERACTION_RECEIPT_EVIDENCE_MISSING\": 1"));
        assertTrue(report.contains("\"maxTurns\": 64"));
        assertTrue(report.contains("\"coversOperationalSource\": false"));
        assertFalse(report.contains("agent-private-id"));
        String markdown = Files.readString(reportDirectory.resolve("report.md"));
        assertTrue(markdown.contains("EVIDENCE_MISSING: 1"));
        assertTrue(markdown.contains("not a human evaluation"));
    }

    @Test
    void perInvocationEvidenceSaturatesAtItsFiniteCapAndMarksCompletenessFalse() {
        TickMetrics metrics = new TickMetrics();
        for (int index = 0; index < TickMetrics.MAX_INTERACTION_RECORDS + 1; index++) {
            metrics.recordInteractionInvocation(UUID.randomUUID(), index, null, null, null, "COMMAND", "HELP");
        }
        assertTrue(metrics.evidenceIncomplete());
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_EVIDENCE_DROPPED.ordinal()]);
        assertEquals(TickMetrics.MAX_INTERACTION_RECORDS + 1L,
                metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
    }

    @Test
    void actualJavaExportKeepsSixtyFiveRowsInOrderedChunksAndOperationalReportCoversEveryOutcome() throws Exception {
        TickMetrics metrics = new TickMetrics();
        Path evidenceDirectory = directory.toRealPath().resolve("operational-65");
        Files.createDirectory(evidenceDirectory);
        Files.setPosixFilePermissions(evidenceDirectory, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Path source = evidenceDirectory.resolve("tick-metrics.jsonl");
        String[] outcomes = { "HELP", "INVALID", "DENIED", "UNAUTHENTICATED", "UNAVAILABLE_BEFORE_BOOTSTRAP",
                "RECEIPT_EVIDENCE_MISSING", "UNAVAILABLE", "BUSY", "COALESCED", "ADMITTED", "FOLLOWUP_ENDED" };
        for (int index = 0; index < 65; index++) {
            String outcome = outcomes[index % outcomes.length];
            String provenance = "RECEIPT_EVIDENCE_MISSING".equals(outcome) ? "MISSING_PACKET_RECEIPT" : "SYNTHETIC_HARNESS";
            UUID id = UUID.fromString(String.format("00000000-0000-4000-8000-%012d", index + 1));
            TickMetrics.InvocationEvidence evidence = metrics.recordInteractionInvocation(id, index, index,
                    index + 1, provenance, null, 0, null, "example:dimension/path", null, "COMMAND", outcome);
            if ("COALESCED".equals(outcome) || "ADMITTED".equals(outcome)) {
                int requestGroup = index / outcomes.length;
                metrics.recordInteractionRequest(evidence, "request-group-" + requestGroup,
                        index + 10, 1, "ADMITTED".equals(outcome));
            }
            if (index == 0) {
                metrics.recordInteractionDispatch(evidence, 1_250_000_000L,
                        TickMetrics.FirstPublicSource.LOCAL_SAFE_RESPONSE,
                        TickMetrics.FirstPublicDisposition.FAILED);
            } else if (index == 1) {
                long terminalLatencyNanos = TimeUnit.SECONDS.toNanos(2);
                metrics.recordInteractionTerminal(evidence, "UNRESOLVED", "NO_ATTEMPT",
                        index + terminalLatencyNanos, terminalLatencyNanos);
            }
        }
        metrics.increment(TickMetrics.Counter.MAILBOX_OVERFLOWS);
        metrics.increment(TickMetrics.Counter.CONTROL_QUEUE_OVERFLOWS);
        metrics.increment(TickMetrics.Counter.DISPOSITION_QUEUE_OVERFLOWS);

        assertTrue(metrics.offerNumericSnapshot(source));
        assertTrue(metrics.awaitTerminationForTests(3, TimeUnit.SECONDS));
        String raw = Files.readString(source);
        assertTrue(raw.contains("\"sourceInvocationCount\":65"));
        assertTrue(raw.contains("\"retainedInvocationCount\":65"));
        assertTrue(raw.contains("\"chunkCount\":2"));
        assertTrue(raw.contains("\"index\":0,"));
        assertTrue(raw.contains("\"index\":1,"));
        assertTrue(Files.size(source) < 1024 * 1024);

        Path reportDirectory = evidenceDirectory.resolve("report");
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        runNodeCli(projectRoot, "operational-report", "--source", source.toString(), "--out", reportDirectory.toString());
        String report = Files.readString(reportDirectory.resolve("report.json"));
        assertTrue(report.contains("\"status\": \"COMPLETE\""));
        assertTrue(report.contains("\"sourceInvocationCount\": 65"));
        assertTrue(report.contains("\"observedInvocationCount\": 65"));
        assertTrue(report.contains("\"omittedInvocationCount\": 0"));
        assertTrue(report.contains("\"knownDeadlineMissCount\": 2"));
        assertTrue(report.contains("\"noDispatchDeadlineCount\": 1"));
        assertTrue(report.contains("\"deadlineCounterMatches\": true"));
        assertTrue(report.contains("\"aggregateMaximumNanos\": \"1250000000\""));
        assertTrue(report.contains("\"aggregateMaximumMatchesLinked\": true"));
        assertTrue(report.contains("\"actualMaximumDispatchNanos\": \"1250000000\""));
        assertTrue(report.contains("\"actualMaximumMatchesLinked\": true"));
        assertTrue(report.contains("\"allOutcomeCountersMatch\": true"));
        assertTrue(report.contains("\"MAILBOX_OVERFLOWS\": 1"));
        assertTrue(report.contains("\"CONTROL_QUEUE_OVERFLOWS\": 1"));
        assertTrue(report.contains("\"DISPOSITION_QUEUE_OVERFLOWS\": 1"));
        for (int index = 0; index < outcomes.length; index++) {
            int expected = index < outcomes.length - 1 ? 6 : 5;
            assertTrue(report.contains("\"" + outcomes[index] + "\": " + expected), outcomes[index]);
        }
        assertTrue(report.contains("\"automaticPass\": false"));
        assertTrue(report.contains("\"maxTurns\": 64"));
        assertFalse(report.contains("00000000-0000-4000-8000-000000000001"));

        // Reuse the exact stream emitted by the Java producer, append its actual
        // final chunk, and verify the CLI invalidates that snapshot without mutating input.
        Path repeatedChunkSource = evidenceDirectory.resolve("tick-metrics-appended-chunk.jsonl");
        String finalChunk = raw.stripTrailing().lines().reduce((first, last) -> last).orElseThrow();
        Files.writeString(repeatedChunkSource, raw, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.setPosixFilePermissions(repeatedChunkSource, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Files.writeString(repeatedChunkSource, finalChunk + "\n", StandardOpenOption.APPEND);
        String repeatedChunkOriginal = Files.readString(repeatedChunkSource);
        Path repeatedChunkReportDirectory = evidenceDirectory.resolve("appended-chunk-report");
        runNodeCli(projectRoot, "operational-report", "--source", repeatedChunkSource.toString(),
                "--out", repeatedChunkReportDirectory.toString());
        assertEquals(repeatedChunkOriginal, Files.readString(repeatedChunkSource), "the importer preserves exact source bytes");
        String repeatedChunkReport = Files.readString(repeatedChunkReportDirectory.resolve("report.json"));
        assertTrue(repeatedChunkReport.contains("\"status\": \"INCOMPLETE\""));
        assertTrue(repeatedChunkReport.contains("operational-chunk-duplicate-late"));
        assertTrue(repeatedChunkReport.contains("\"sourceInvocationCount\": 65"));
        assertTrue(repeatedChunkReport.contains("\"retainedInvocationCount\": 65"));
        assertTrue(repeatedChunkReport.contains("\"observedInvocationCount\": 65"));
        assertTrue(repeatedChunkReport.contains("\"omittedInvocationCount\": 0"));
        assertTrue(repeatedChunkReport.contains("\"declaredOmittedInvocationCount\": 0"));
        assertTrue(repeatedChunkReport.contains("\"ADMITTED\": 6"), "the repeated row is never counted twice");
        assertTrue(repeatedChunkReport.contains("\"maxTurns\": 64"));
        assertTrue(repeatedChunkReport.contains("\"automaticPass\": false"));
    }

    @Test
    void overRetentionCapStillExportsExplicitIncompleteCountsWithoutClaimingThePrefixComplete() throws Exception {
        TickMetrics metrics = new TickMetrics();
        Path evidenceDirectory = directory.toRealPath().resolve("operational-over-cap");
        Files.createDirectory(evidenceDirectory);
        Files.setPosixFilePermissions(evidenceDirectory, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Path source = evidenceDirectory.resolve("tick-metrics.jsonl");
        for (int index = 0; index < TickMetrics.MAX_INTERACTION_RECORDS + 1; index++) {
            TickMetrics.InvocationEvidence evidence = metrics.recordInteractionInvocation(UUID.randomUUID(), index,
                    null, null, null, "COMMAND", "HELP");
            if (index == TickMetrics.MAX_INTERACTION_RECORDS) assertEquals(null, evidence, "the 4097th row is refused");
        }
        assertTrue(metrics.evidenceIncomplete());
        assertTrue(metrics.offerNumericSnapshot(source), "capture loss is exported as explicit loss metadata");
        assertTrue(metrics.awaitTerminationForTests(3, TimeUnit.SECONDS));
        String raw = Files.readString(source);
        assertTrue(raw.contains("\"sourceInvocationCount\":4097"));
        assertTrue(raw.contains("\"retainedInvocationCount\":4096"));
        assertTrue(raw.contains("\"omittedInvocationCount\":1"));
        assertTrue(raw.contains("\"captureDroppedInvocationCount\":1"));

        Path reportDirectory = evidenceDirectory.resolve("report");
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        runNodeCli(projectRoot, "operational-report", "--source", source.toString(), "--out", reportDirectory.toString());
        String report = Files.readString(reportDirectory.resolve("report.json"));
        assertTrue(report.contains("\"status\": \"INCOMPLETE\""));
        assertTrue(report.contains("\"sourceInvocationCount\": 4097"));
        assertTrue(report.contains("\"observedInvocationCount\": 4096"));
        assertTrue(report.contains("\"omittedInvocationCount\": 1"));
        assertTrue(report.contains("operational-source-rows-omitted"));
        assertTrue(report.contains("\"complete\": false"));
    }

    @Test
    void oversizedInvocationMetadataIsDroppedBeforeItCanExpandBoundedEvidenceRetention() throws Exception {
        TickMetrics metrics = new TickMetrics();
        Path evidenceDirectory = directory.toRealPath().resolve("oversized-metadata");
        Files.createDirectory(evidenceDirectory);
        Files.setPosixFilePermissions(evidenceDirectory, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Path source = evidenceDirectory.resolve("tick-metrics.jsonl");
        String oversizedWorldId = "x".repeat(1_000_000);
        assertEquals(null, metrics.recordInteractionInvocation(UUID.randomUUID(), 1, null, oversizedWorldId,
                null, "COMMAND", "HELP"));
        assertTrue(metrics.evidenceIncomplete());
        assertEquals(1, metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_EVIDENCE_DROPPED.ordinal()]);

        assertTrue(metrics.offerNumericSnapshot(source));
        assertTrue(metrics.awaitTerminationForTests(3, TimeUnit.SECONDS));
        String raw = Files.readString(source);
        assertTrue(raw.contains("\"sourceInvocationCount\":1"));
        assertTrue(raw.contains("\"retainedInvocationCount\":0"));
        assertTrue(raw.contains("\"omittedInvocationCount\":1"));
        assertFalse(raw.contains(oversizedWorldId), "unbounded metadata is not retained or exported");

        Path reportDirectory = evidenceDirectory.resolve("report");
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        runNodeCli(projectRoot, "operational-report", "--source", source.toString(), "--out", reportDirectory.toString());
        String report = Files.readString(reportDirectory.resolve("report.json"));
        assertTrue(report.contains("\"status\": \"INCOMPLETE\""));
        assertTrue(report.contains("\"sourceInvocationCount\": 1"));
        assertTrue(report.contains("\"observedInvocationCount\": 0"));
        assertTrue(report.contains("\"omittedInvocationCount\": 1"));
        assertFalse(report.contains(oversizedWorldId));
    }

    private static void runNodeCli(Path projectRoot, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("node");
        command.add(projectRoot.resolve("tools/dialogue/evaluate.mjs").toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(projectRoot.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int status = process.waitFor();
        assertEquals(0, status, String.join(" ", command) + " failed: " + output);
    }
}
