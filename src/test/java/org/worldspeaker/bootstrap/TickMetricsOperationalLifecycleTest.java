package org.worldspeaker.bootstrap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.runtime.TickMetrics;
import org.worldspeaker.runtime.TickMetricsTestAccess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual lifecycle-to-v5-export-to-operational-CLI coverage for cancellation evidence. */
class TickMetricsOperationalLifecycleTest {
    private static final String A_TEXT = "R01_RESP04_PRIVATE_PROFILE_PROMPT_A_7f13";
    private static final String A_REPEAT_TEXT = A_TEXT;
    private static final String B_TEXT = "R01_RESP04_PRIVATE_PROFILE_PROMPT_B_b82a";
    private static final String C_TEXT = "R01_RESP04_PRIVATE_PROFILE_PROMPT_C_0c91";
    private static final String E_TEXT = "R01_RESP04_PRIVATE_PROFILE_PROMPT_E_6d34";
    private static final String REFUSED_TEXT = "R01_RESP04_PRIVATE_PROFILE_PROMPT_REFUSED_4ea0";
    private static final String A_ANSWER = "R01_RESP04_PRIVATE_PROFILE_ANSWER_A_319d";
    private static final String B_ANSWER = "R01_RESP04_PRIVATE_PROFILE_ANSWER_B_5f7a";

    @TempDir
    Path temporaryDirectory;

    @Test
    void realLifecycleV5ProducerAndOperationalCliReconcileAnswersRepeatsCapacityAndQueueEvidence()
            throws Exception {
        Path evidenceDirectory = privateDirectory("resp04-evidence");
        Path source = evidenceDirectory.resolve("tick-metrics.jsonl");

        try (SmokeConversationLifecycleTest.Harness harness =
                     new SmokeConversationLifecycleTest.Harness(30, false, null, true)) {
            long t0A = harness.clock.get();
            InteractionIngress.Invocation originalA = harness.admit(A_TEXT);
            harness.tickAt(t0A);
            JsonObject requestA = harness.awaitFrame("dialogue.request", 1);

            long coalesceAt = t0A + TimeUnit.MILLISECONDS.toNanos(50);
            harness.clock.set(coalesceAt);
            assertEquals(0, harness.invokeAsk(harness.player, A_REPEAT_TEXT, coalesceAt));
            InteractionIngress.Invocation liveRepeat = harness.invocations.get(harness.invocations.size() - 1);

            long t0B = t0A + TimeUnit.MILLISECONDS.toNanos(200);
            harness.clock.set(t0B);
            InteractionIngress.Invocation originalB = harness.admit(B_TEXT);
            harness.tickAt(t0B);

            long t0C = t0A + TimeUnit.MILLISECONDS.toNanos(300);
            harness.clock.set(t0C);
            InteractionIngress.Invocation originalC = harness.admit(C_TEXT);
            harness.tickAt(t0C);

            long refusedAt = t0A + TimeUnit.MILLISECONDS.toNanos(400);
            harness.clock.set(refusedAt);
            assertEquals(0, harness.invokeAsk(harness.player, REFUSED_TEXT, refusedAt),
                    "a changed ask is refused while this player's two pending slots are full");
            InteractionIngress.Invocation capacityRefusal = harness.invocations.get(harness.invocations.size() - 1);
            harness.tickAt(refusedAt);

            long answerAAt = t0A + TimeUnit.MILLISECONDS.toNanos(500);
            harness.clock.set(answerAAt);
            harness.sendAnswer(requestA, A_ANSWER);
            harness.tickAt(answerAAt);
            JsonObject requestB = harness.awaitFrame("dialogue.request", 2);

            long answerBAt = t0A + TimeUnit.MILLISECONDS.toNanos(700);
            harness.clock.set(answerBAt);
            harness.sendAnswer(requestB, B_ANSWER);
            harness.tickAt(answerBAt);
            JsonObject requestC = harness.awaitFrame("dialogue.request", 3);

            long t0E = t0A + TimeUnit.MILLISECONDS.toNanos(850);
            harness.clock.set(t0E);
            InteractionIngress.Invocation otherPlayerPending = harness.admit(harness.secondPlayer, E_TEXT);
            harness.tickAt(t0E);

            long unknownAnswerAt = t0A + TimeUnit.SECONDS.toNanos(1);
            harness.clock.set(unknownAnswerAt);
            harness.game.enqueueResult(org.worldspeaker.bootstrap.FirstReplyScheduler.PublicationResult.UNKNOWN);
            harness.sendAnswer(requestC, "R01_RESP04_PRIVATE_PROFILE_ANSWER_UNKNOWN_c191");
            harness.tickAt(unknownAnswerAt);
            harness.awaitFrame("dialogue.request", 4);

            long repeatGuardAt = t0A + TimeUnit.SECONDS.toNanos(6);
            harness.clock.set(repeatGuardAt);
            assertEquals(0, harness.invokeAsk(harness.player, A_TEXT, repeatGuardAt),
                    "the original exact ask is still refused at the inclusive six-second guard boundary");
            InteractionIngress.Invocation guardedRepeat = harness.invocations.get(harness.invocations.size() - 1);
            harness.tickAt(repeatGuardAt);

            assertEquals(4, harness.socket.sendCount("dialogue.request"),
                    "only admitted A/B/C/E reach the companion; repeats/refusals add no work");
            assertEquals(2, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.PENDING_EXPIRED.ordinal()]);
            assertEquals(4, harness.metrics.countersSnapshot()[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()]);
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_COALESCED.ordinal()]);
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_BUSY.ordinal()]);
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_RATE_LIMITED.ordinal()]);
            assertEquals(2, harness.game.publishedLines().stream().filter(line ->
                    line.contains(A_ANSWER) || line.contains(B_ANSWER)).count());

            assertTrue(harness.metrics.offerNumericSnapshot(source));
            assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));

            String raw = Files.readString(source);
            assertTrue(raw.contains("\"numericFormatVersion\":5"));
            assertTrue(raw.contains(originalA.invocationId().toString()),
                    "the private producer stream retains UUID correlation for source-row assertions");
            assertFalse(raw.contains(A_TEXT));
            assertFalse(raw.contains(B_TEXT));
            assertFalse(raw.contains(C_TEXT));
            assertFalse(raw.contains(REFUSED_TEXT));
            assertFalse(raw.contains(A_ANSWER));
            assertFalse(raw.contains(B_ANSWER));
            assertFalse(raw.contains("R01_RESP04_PRIVATE_PROFILE_ANSWER_UNKNOWN_c191"));
            assertTrue(raw.contains(harness.playerId.toString()));
            assertTrue(raw.contains(harness.secondPlayerId.toString()));
            assertRow(raw, originalA.invocationId().toString(), row -> {
                assertEquals(Long.toString(t0A), row.get("t0Nanos").getAsString());
                assertEquals("ADMITTED", row.get("outcome").getAsString());
                assertEquals("ANSWER", row.get("terminalOutcome").getAsString());
                assertEquals("SUBMITTED", row.get("terminalDeliveryResult").getAsString());
                assertEquals(Long.toString(answerAAt), row.get("terminalAtNanos").getAsString());
                assertEquals(Long.toString(answerAAt), row.get("firstDispatchAtNanos").getAsString(),
                        "A's real submitted answer is also its first public dispatch");
                assertEquals("INTEGRATION_ANSWER", row.get("firstDispatchSource").getAsString());
                assertEquals("ANSWERED", row.get("firstDispatchDisposition").getAsString());
                assertEquals("SUBMITTED", row.get("sendResult").getAsString());
                assertEquals(Long.toString(answerAAt - t0A), row.get("terminalNanos").getAsString(),
                        "completion duration is measured from original receipt t0");
                assertEquals("NOT_ATTEMPTED", row.get("localCancelStatus").getAsString());
                assertTrue(row.get("providerCancel").isJsonNull());
                assertTrue(row.get("forwardedAtNanos").isJsonPrimitive());
                assertEquals(harness.playerId.toString(), row.get("player").getAsString());
            });
            assertRow(raw, liveRepeat.invocationId().toString(), row -> {
                assertEquals("COALESCED", row.get("outcome").getAsString());
                assertEquals(rowById(raw, originalA.invocationId().toString()).get("requestId").getAsString(),
                        row.get("requestId").getAsString());
                assertTrue(row.get("queuedAtNanos").isJsonNull(),
                        "a linked live repeat is not a second logical request");
            });
            assertRow(raw, originalB.invocationId().toString(), row -> {
                assertEquals(Long.toString(t0B), row.get("t0Nanos").getAsString());
                assertEquals("ANSWER", row.get("terminalOutcome").getAsString());
                assertEquals("SUBMITTED", row.get("terminalDeliveryResult").getAsString());
                assertEquals(Long.toString(answerBAt), row.get("terminalAtNanos").getAsString());
                long forwardedAt = Long.parseLong(row.get("forwardedAtNanos").getAsString());
                assertTrue(forwardedAt >= t0B);
                assertEquals(forwardedAt - t0B,
                        Long.parseLong(row.get("pendingResidenceNanos").getAsString()));
                assertTrue(Long.parseLong(row.get("firstDispatchAtNanos").getAsString()) >= t0B,
                        "B's t1 is an actual public submission, not an inferred timestamp");
                assertTrue(Long.parseLong(row.get("firstDispatchAtNanos").getAsString()) <= answerBAt);
                assertEquals(Long.toString(t0B), row.get("firstDispatchAtNanos").getAsString(),
                        "B's actual public acknowledgement t1 is recorded at the queued capture time");
                assertEquals("LOCAL_ACKNOWLEDGEMENT", row.get("firstDispatchSource").getAsString());
                assertEquals("ACKNOWLEDGED", row.get("firstDispatchDisposition").getAsString());
                assertEquals("SUBMITTED", row.get("sendResult").getAsString());
                assertEquals(Long.toString(answerBAt - t0B), row.get("terminalNanos").getAsString());
            });
            assertRow(raw, originalC.invocationId().toString(), row -> {
                assertEquals("ADMITTED", row.get("outcome").getAsString());
                assertEquals("ANSWER", row.get("terminalOutcome").getAsString());
                assertEquals("UNKNOWN", row.get("terminalDeliveryResult").getAsString(),
                        "the ambiguous terminal game-port send is retained as unknown");
                assertEquals(Long.toString(unknownAnswerAt), row.get("terminalAtNanos").getAsString());
                assertTrue(row.get("forwardedAtNanos").isJsonPrimitive());
                assertEquals(2, row.get("queueDepthAtAdmission").getAsInt());
                assertEquals(2, row.get("playerQueueDepthAtAdmission").getAsInt());
            });
            assertRow(raw, capacityRefusal.invocationId().toString(), row -> {
                assertEquals("BUSY", row.get("outcome").getAsString());
                assertTrue(row.get("requestId").isJsonNull(),
                        "capacity refusal has no fabricated logical-request link");
                assertTrue(row.get("terminalOutcome").isJsonNull());
            });
            assertRow(raw, otherPlayerPending.invocationId().toString(), row -> {
                assertEquals("ADMITTED", row.get("outcome").getAsString());
                assertTrue(row.get("terminalOutcome").isJsonNull(),
                        "E is still live at export and has no measured terminal result");
                assertTrue(row.get("forwardedAtNanos").isJsonPrimitive());
                assertTrue(Long.parseLong(row.get("forwardedAtNanos").getAsString()) >= t0E);
            });
            assertRow(raw, guardedRepeat.invocationId().toString(), row -> {
                assertEquals("RATE_LIMITED", row.get("outcome").getAsString());
                assertEquals("REPEAT_GUARD", row.get("rateLimitStatus").getAsString());
                assertEquals(rowById(raw, originalA.invocationId().toString()).get("requestId").getAsString(),
                        row.get("requestId").getAsString());
                assertTrue(row.get("queuedAtNanos").isJsonNull());
                assertTrue(row.get("terminalOutcome").isJsonNull());
            });

            Set<PosixFilePermission> sourceMode = Files.getPosixFilePermissions(source);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), sourceMode);

            String sourceHash = sha256(source);
            Path reportDirectory = evidenceDirectory.resolve("operational-report");
            runOperationalReport(source, reportDirectory);
            assertEquals(sourceHash, sha256(source), "the real importer leaves producer bytes unchanged");
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(evidenceDirectory));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(reportDirectory));
            try (var files = Files.list(reportDirectory)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                            Files.getPosixFilePermissions(file), "operational report is owner-only: " + file.getFileName());
                }
            }

            String reportText = Files.readString(reportDirectory.resolve("report.json"));
            JsonObject report = JsonParser.parseString(reportText).getAsJsonObject();
            assertEquals("COMPLETE", report.get("status").getAsString());
            assertEquals(sourceHash, report.get("sourceSha256").getAsString());
            assertEquals(7, report.get("sourceInvocationCount").getAsInt());
            assertEquals(7, report.get("observedInvocationCount").getAsInt());
            assertEquals(0, report.get("omittedInvocationCount").getAsInt());
            assertEquals("NOT_PERFORMED", report.getAsJsonObject("humanEvaluation").get("status").getAsString());
            assertFalse(report.getAsJsonObject("humanEvaluation").get("coversOperationalSource").getAsBoolean());
            assertEquals(64, report.getAsJsonObject("humanEvaluation").get("maxTurns").getAsInt());
            assertFalse(report.getAsJsonObject("qualityDecision").get("automaticPass").getAsBoolean());
            assertEquals("NOT_EVALUATED", report.getAsJsonObject("qualityDecision").get("status").getAsString());

            JsonObject snapshot = report.getAsJsonArray("snapshots").get(0).getAsJsonObject();
            assertEquals("COMPLETE", snapshot.get("status").getAsString());
            JsonObject outcomes = snapshot.getAsJsonObject("outcomeCounts");
            assertEquals(4, outcomes.get("ADMITTED").getAsInt());
            assertEquals(1, outcomes.get("COALESCED").getAsInt());
            assertEquals(1, outcomes.get("BUSY").getAsInt());
            assertEquals(1, outcomes.get("RATE_LIMITED").getAsInt());
            assertEquals(0, outcomes.get("EXPIRED").getAsInt(), "no request is fabricated as expired");
            assertEquals(0, snapshot.getAsJsonObject("counters").get("PENDING_EXPIRED").getAsInt());
            assertEquals(2, snapshot.getAsJsonObject("counters").get("ANSWERS_DELIVERED").getAsInt());
            assertEquals(0, snapshot.getAsJsonObject("counters").get("LATE_OUTPUT_REJECTIONS").getAsInt());
            JsonObject cancellation = snapshot.getAsJsonObject("cancellationEvidence");
            assertEquals("OBSERVED", cancellation.get("availability").getAsString());
            assertEquals(0, cancellation.get("lateOutputRejectionCount").getAsInt());
            assertEquals(true, cancellation.get("lateOutputCounterMatches").getAsBoolean());
            JsonObject terminalOutcomes = snapshot.getAsJsonObject("terminalOutcomeCounts");
            assertEquals(3, terminalOutcomes.get("ANSWER").getAsInt());
            assertEquals(4, terminalOutcomes.get("NO_TERMINAL").getAsInt(),
                    "pending, coalesced, and refused rows remain without a terminal timestamp");
            JsonObject terminalDelivery = snapshot.getAsJsonObject("terminalDeliveryResultCounts");
            assertEquals(2, terminalDelivery.get("SUBMITTED").getAsInt());
            assertEquals(1, terminalDelivery.get("UNKNOWN").getAsInt());
            assertEquals(4, terminalDelivery.get("NO_TERMINAL").getAsInt());

            JsonObject response = snapshot.getAsJsonObject("responseCompleteness");
            assertEquals(4, response.get("admittedCount").getAsInt());
            assertEquals(2, response.get("answerCompletedCount").getAsInt());
            assertEquals(3, response.get("answerTerminalAttemptCount").getAsInt());
            assertEquals(1, response.get("unconfirmedAnswerAttemptCount").getAsInt(),
                    "UNKNOWN is an answer attempt, not a confirmed completion");
            assertEquals(2, response.get("admittedWithoutMeasuredAnswer").getAsInt(),
                    "UNKNOWN C and still-live E remain without a confirmed submitted answer");

            JsonObject links = snapshot.getAsJsonObject("requestLinkReconciliation");
            assertEquals(4, links.get("requestGroupCount").getAsInt());
            assertEquals(1, links.get("coalescedCount").getAsInt());
            assertEquals(1, links.get("resolvedCoalescedCount").getAsInt());
            assertEquals(4, links.get("admittedAnchorCount").getAsInt());
            assertEquals(1, links.get("repeatGuardRefusedCount").getAsInt());
            assertEquals(1, links.get("resolvedRepeatGuardCount").getAsInt());
            assertEquals(0, links.get("unresolvedRepeatGuardCount").getAsInt());
            assertEquals(1, links.getAsJsonObject("repeatGuardOriginalTerminalOutcomeCounts")
                    .get("ANSWER").getAsInt());

            JsonObject queue = snapshot.getAsJsonObject("queueEvidence");
            assertEquals("OBSERVED", queue.get("availability").getAsString());
            assertTrue(queue.get("pendingQueueScans").getAsLong() > 0);
            assertTrue(queue.get("pendingQueueRecordSamples").getAsLong() > 0);
            assertEquals(4, queue.get("forwardedInvocationCount").getAsInt());
            assertEquals(0, queue.get("admittedWithoutForwardingCount").getAsInt());
            assertEquals(4, queue.getAsJsonObject("captureToForwardWait").get("sampleCount").getAsInt());
            JsonObject sampledMaxima = queue.getAsJsonObject("sampledMaxima");
            assertEquals(2, sampledMaxima.get("globalPending").getAsInt());
            assertEquals(2, sampledMaxima.get("perPlayerPending").getAsInt());
            assertTrue(sampledMaxima.get("recordsPerScan").getAsInt() >= 2);

            JsonObject player = snapshot.getAsJsonObject("perPlayer").getAsJsonObject("player-001");
            assertEquals(6, player.get("invocations").getAsInt());
            assertEquals(3, player.get("admittedLogicalRequests").getAsInt());
            assertEquals(1, player.get("coalescedCount").getAsInt());
            assertEquals(1, player.get("repeatGuardRefusedCount").getAsInt());
            assertEquals(3, player.get("forwardedCount").getAsInt());
            JsonObject modelStarts = player.getAsJsonObject("modelStarts");
            assertEquals("UNKNOWN_NOT_EXPOSED", modelStarts.get("status").getAsString());
            assertTrue(modelStarts.get("count").isJsonNull(),
                    "forwarded requests do not claim provider/model execution");
            assertEquals(2, player.getAsJsonObject("confirmedAnswerCompletion").get("count").getAsInt(),
                    "coalesced and repeat-guard links add no logical answer completions");
            JsonObject secondPlayer = snapshot.getAsJsonObject("perPlayer").getAsJsonObject("player-002");
            assertEquals(1, secondPlayer.get("invocations").getAsInt());
            assertEquals(1, secondPlayer.get("admittedLogicalRequests").getAsInt());
            assertEquals(1, secondPlayer.get("forwardedCount").getAsInt());
            assertEquals(0, secondPlayer.getAsJsonObject("confirmedAnswerCompletion").get("count").getAsInt());

            assertFalse(reportText.contains(harness.playerId.toString()));
            assertFalse(reportText.contains(harness.secondPlayerId.toString()));
            assertFalse(reportText.contains(originalA.invocationId().toString()));
            for (String privateText : new String[] { A_TEXT, B_TEXT, C_TEXT, E_TEXT, REFUSED_TEXT, A_ANSWER, B_ANSWER,
                    "R01_RESP04_PRIVATE_PROFILE_ANSWER_UNKNOWN_c191",
                    "server-test", "world-test", "character-test", "agent-test" }) {
                assertFalse(reportText.contains(privateText), "report excludes private prompt/binding data");
            }
            assertTrue(reportText.contains("player-001"), "the report exposes only an anonymous player alias");
        }
    }

    private Path privateDirectory(String name) throws Exception {
        Path directory = temporaryDirectory.toRealPath().resolve(name);
        Files.createDirectory(directory);
        Files.setPosixFilePermissions(directory, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return directory;
    }

    private static void runOperationalReport(Path source, Path outputDirectory) throws Exception {
        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        var command = java.util.List.of("node", projectRoot.resolve("tools/dialogue/evaluate.mjs").toString(),
                "operational-report", "--source", source.toString(), "--out", outputDirectory.toString());
        Process process = new ProcessBuilder(command).directory(projectRoot.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int status = process.waitFor();
        assertEquals(0, status, String.join(" ", command) + " failed: " + output);
    }

    private static JsonObject rowById(String raw, String invocationId) {
        for (String line : raw.lines().toList()) {
            JsonObject frame = JsonParser.parseString(line).getAsJsonObject();
            if (!frame.has("records")) continue;
            for (var item : frame.getAsJsonArray("records")) {
                JsonObject row = item.getAsJsonObject();
                if (row.has("id") && invocationId.equals(row.get("id").getAsString())) return row;
            }
        }
        throw new AssertionError("no producer row for invocation " + invocationId);
    }

    private static void assertRow(String raw, String invocationId,
                                  java.util.function.Consumer<JsonObject> assertion) {
        JsonObject row = rowById(raw, invocationId);
        assertion.accept(row);
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
