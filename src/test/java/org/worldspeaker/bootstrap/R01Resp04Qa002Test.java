package org.worldspeaker.bootstrap;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.runtime.TickMetrics;
import org.worldspeaker.runtime.TickMetricsTestAccess;
import org.worldspeaker.runtime.ThreadBoundaryTestAccess;
import org.worldspeaker.integration.BoundCompanionClientTestAccess;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sustained synthetic round-robin load through the shared production-path fixture. */
class R01Resp04Qa002Test {
    private static final long T0 = 3_000_000_000L;
    private static final long WALL_MILLIS = 1_793_275_200_000L;
    private static final long STEP_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long ONE_SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long THREE_SECONDS = TimeUnit.SECONDS.toNanos(3);
    private static final long FOUR_SECONDS = TimeUnit.SECONDS.toNanos(4);
    private static final long FIFTEEN_SECONDS = TimeUnit.SECONDS.toNanos(15);
    private static final long SIXTY_SECONDS = TimeUnit.SECONDS.toNanos(60);
    private static final String A0 = "A-0000 distinct sustained input";

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearSharedServerThreadBinding() {
        ThreadBoundaryTestAccess.clearForTests();
    }

    @Test
    void sustainedSixtySecondIngressKeepsQueuedBandCFifoAndEveryFirstReplyBounded() throws Exception {
        try (SmokeConversationLifecycleTest.Harness harness =
                     new SmokeConversationLifecycleTest.Harness(30, false, new InvocationRateLimiter())) {
            List<CapturedInput> captured = new ArrayList<>(1_213);
            Map<String, CapturedInput> inputByText = new HashMap<>();
            List<RequestObservation> forwarded = new ArrayList<>();
            Set<String> answeredTexts = new HashSet<>();
            List<JsonObject> endTickTrace = new ArrayList<>(48);
            int[] admittedCount = {0};

            harness.socket.holdAcknowledgementSends(true);
            CapturedInput firstA = invoke(harness, captured, inputByText, admittedCount,
                    harness.player, harness.playerId, T0, InteractionIngress.Origin.COMMAND, A0);
            assertEquals(1, firstA.acceptedByIngress());
            harness.tickAt(T0);

            DrainState initial = drainForwarded(harness, T0, 0, admittedCount[0], A0,
                    captured, inputByText, forwarded, answeredTexts);
            assertEquals(1, initial.observedRequests());
            assertNotNull(initial.heldRequest(), "A's first request is held as deterministic in-flight work");
            JsonObject heldA = initial.heldRequest();

            // Inclusive 50 ms arrivals at t0 through t0 + 60 s: 1,201 distinct A asks.
            // B and C each arrive every ten seconds on separate authenticated queues.
            for (int step = 1; step <= 1_200; step++) {
                long at = T0 + step * STEP_NANOS;
                if (step >= 1_160) awaitStableTransportOwner(harness);
                harness.clock.set(at);
                String aText = String.format(Locale.ROOT, "A-%04d distinct sustained input", step);
                invoke(harness, captured, inputByText, admittedCount, harness.player, harness.playerId,
                        at, InteractionIngress.Origin.COMMAND, aText);

                if (at >= T0 + TimeUnit.MILLISECONDS.toNanos(250) &&
                        (at - (T0 + TimeUnit.MILLISECONDS.toNanos(250))) % TimeUnit.SECONDS.toNanos(10) == 0) {
                    int sequence = (int) ((at - T0 - TimeUnit.MILLISECONDS.toNanos(250)) /
                            TimeUnit.SECONDS.toNanos(10));
                    invoke(harness, captured, inputByText, admittedCount, harness.secondPlayer,
                            harness.secondPlayerId, at, InteractionIngress.Origin.NATURAL_ADDRESS,
                            "B-" + sequence + " distinct queued workload");
                }
                if (at >= T0 + TimeUnit.MILLISECONDS.toNanos(500) &&
                        (at - (T0 + TimeUnit.MILLISECONDS.toNanos(500))) % TimeUnit.SECONDS.toNanos(10) == 0) {
                    int sequence = (int) ((at - T0 - TimeUnit.MILLISECONDS.toNanos(500)) /
                            TimeUnit.SECONDS.toNanos(10));
                    invoke(harness, captured, inputByText, admittedCount, harness.thirdPlayer,
                            harness.thirdPlayerId, at, InteractionIngress.Origin.COMMAND,
                            "C-" + sequence + " distinct queued workload");
                }

                int budgetBeforeTick = (int) harness.metrics.countersSnapshot()[TickMetrics.Counter.TICK_BUDGET_EXHAUSTED.ordinal()];
                harness.tickAt(at);
                if (step >= 1_160) {
                    awaitStableTransportOwner(harness);
                    JsonObject observation = tickObservation(harness, at, budgetBeforeTick);
                    if (endTickTrace.size() == 48) endTickTrace.removeFirst();
                    endTickTrace.add(observation);
                }
                if (step == 35) {
                    // A's dialogue.ack future is held exactly one second from the 750 ms fallback cutoff.
                    var acknowledgement = harness.awaitSend("dialogue.ack", 1);
                    assertFalse(acknowledgement.completion().isDone(),
                            "the controlled acknowledgement context future remains held before release");
                    assertEquals(ONE_SECOND, at - (T0 + TimeUnit.MILLISECONDS.toNanos(750)),
                            "the context barrier is released at the one-second fixture-service bound");
                    harness.socket.complete(acknowledgement);
                    harness.awaitAcknowledgementOffers(1);
                    assertEquals(org.worldspeaker.runtime.GameLoopMailbox.Offer.ACCEPTED,
                            harness.acknowledgementOfferOutcome.get());
                    harness.tickAt(at);
                    awaitStableTransportOwner(harness);
                    harness.socket.holdAcknowledgementSends(false);
                }

                if (step == 60) {
                    // B and C have been queued behind A. Complete A at t0 + 3 s, then drain real FIFO/RR.
                    assertTrue(answeredTexts.add(A0));
                    harness.sendAnswer(heldA, "Synthetic answer for " + A0);
                    harness.tickAt(at);
                    assertEquals(1, harness.game.attempts().stream().filter(attempt -> attempt.target() == harness.player &&
                                    attempt.result() == FirstReplyScheduler.PublicationResult.SUBMITTED &&
                                    attempt.renderedLine().equals("[Worldspeaker] Archivist: Synthetic answer for " + A0))
                                    .count());
                    DrainState drained = drainForwarded(harness, at, initial.observedRequests(),
                            admittedCount[0], null, captured, inputByText, forwarded, answeredTexts);
                    initial = new DrainState(drained.observedRequests(), heldA);
                } else if (step > 60) {
                    DrainState drained = drainForwarded(harness, at, initial.observedRequests(),
                            admittedCount[0], null, captured, inputByText, forwarded, answeredTexts);
                    initial = new DrainState(drained.observedRequests(), heldA);
                }

                assertTrue(harness.scheduler.pendingDispositionCount() <=
                                FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS,
                        "notice and first-reply work stays inside the real deferred-disposition bound");
                assertTrue(harness.rateLimiter.pendingNoticeMembers() <= TickMetrics.MAX_INTERACTION_RECORDS,
                        "the shared notice batches retain no more members than the evidence cap");
            }

            long settleAt = T0 + SIXTY_SECONDS;
            for (int flushStep = 1; flushStep <= 20; flushStep++) {
                settleAt = T0 + SIXTY_SECONDS + flushStep * STEP_NANOS;
                if (!endTickTrace.isEmpty()) awaitStableTransportOwner(harness);
                int budgetBeforeTick = (int) harness.metrics.countersSnapshot()[TickMetrics.Counter.TICK_BUDGET_EXHAUSTED.ordinal()];
                harness.tickAt(settleAt);
                if (!endTickTrace.isEmpty()) {
                    awaitStableTransportOwner(harness);
                    JsonObject observation = tickObservation(harness, settleAt, budgetBeforeTick);
                    if (endTickTrace.size() == 48) endTickTrace.removeFirst();
                    endTickTrace.add(observation);
                }
                DrainState drained = drainForwarded(harness, settleAt, initial.observedRequests(),
                        admittedCount[0], null, captured, inputByText, forwarded, answeredTexts);
                initial = new DrainState(drained.observedRequests(), heldA);
            }
            DrainState settled = drainForwarded(harness, settleAt, initial.observedRequests(),
                    admittedCount[0], null, captured, inputByText, forwarded, answeredTexts);
            assertEquals(admittedCount[0], settled.observedRequests(),
                    "every admitted invocation reached the actual companion socket parser path");
            assertEquals(1_213, captured.size(), "1,201 A arrivals plus six B and six C arrivals");
            assertEquals(captured.size(), harness.metrics.reservedInteractionCount(),
                    "all synthetic captures retain their individual evidence row");
            assertEquals(0, harness.rateLimiter.pendingNoticeMembers());
            assertEquals(0, harness.scheduler.pendingDispositionCount());

            assertEquals(admittedCount[0], harness.socket.sendCount("dialogue.request"));
            assertEquals(admittedCount[0], forwarded.size());
            assertEquals(admittedCount[0], answeredTexts.size());
            assertEquals(admittedCount[0],
                    harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);

            List<String> requestOrder = forwarded.stream().map(item -> item.input().text()).toList();
            assertTrue(requestOrder.indexOf("A-0001 distinct sustained input") < requestOrder.indexOf("B-0 distinct queued workload"));
            assertTrue(requestOrder.indexOf("B-0 distinct queued workload") < requestOrder.indexOf("C-0 distinct queued workload"));
            int firstBIndex = requestOrder.indexOf("B-0 distinct queued workload");
            int firstCIndex = requestOrder.indexOf("C-0 distinct queued workload");
            assertTrue(requestOrder.subList(firstBIndex + 1, firstCIndex).stream().noneMatch(text -> text.startsWith("A-")),
                    "the first queued B and C heads are not overtaken by a later A arrival");
            assertEquals(captured.stream().filter(item -> item.text().startsWith("B-")).map(CapturedInput::text).toList(),
                    forwarded.stream().map(RequestObservation::input).filter(item -> item.text().startsWith("B-"))
                            .map(CapturedInput::text).toList(), "B's admitted queue remains FIFO");
            assertEquals(captured.stream().filter(item -> item.text().startsWith("C-")).map(CapturedInput::text).toList(),
                    forwarded.stream().map(RequestObservation::input).filter(item -> item.text().startsWith("C-"))
                            .map(CapturedInput::text).toList(), "C's admitted queue remains FIFO");

            Path evidence = temporaryDirectory.resolve("r01-resp-04-qa-002-sustained.jsonl");
            saveEvidence(harness, evidence);
            List<JsonObject> rows = evidenceRows(evidence);
            JsonObject manifest = JsonParser.parseString(Files.readAllLines(evidence).getFirst()).getAsJsonObject();
            assertEquals(captured.size(), manifest.get("sourceInvocationCount").getAsInt());
            assertEquals(captured.size(), manifest.get("retainedInvocationCount").getAsInt());
            assertEquals(0, manifest.get("omittedInvocationCount").getAsInt());
            assertEquals("complete", manifest.get("captureStatus").getAsString());
            assertEquals(captured.size(), rows.size());

            Map<String, JsonObject> rowById = new HashMap<>();
            for (JsonObject row : rows) rowById.put(row.get("id").getAsString(), row);
            assertEquals(captured.size(), rowById.size());
            int admittedRows = 0;
            int refusedRows = 0;
            for (int ordinal = 0; ordinal < captured.size(); ordinal++) {
                CapturedInput input = captured.get(ordinal);
                JsonObject row = rowById.get(input.captured().invocationId().toString());
                assertNotNull(row, "evidence retains captured invocation ordinal " + (ordinal + 1));
                assertEquals(input.origin().name(), row.get("origin").getAsString(),
                        "the captured origin is not overwritten during throttling or forwarding");
                assertEquals(Long.toString(input.t0Nanos()), row.get("t0Nanos").getAsString());
                assertEquals(ONE_SECOND, longValue(row, "deadlineAtNanos") - input.t0Nanos());
                long firstDispatchNanos = longValue(row, "firstDispatchNanos");
                if (firstDispatchNanos > ONE_SECOND) {
                    persistFirstReplyFailure(ordinal + 1, row, endTickTrace);
                }
                assertTrue(firstDispatchNanos <= ONE_SECOND,
                        "every invocation has a first successful public dispatch within 1,000 ms; ordinal=" +
                                (ordinal + 1) + "; latency=" + firstDispatchNanos + "; source=" +
                                row.get("firstDispatchSource") + "; outcome=" + row.get("outcome") +
                                "; batchAt=" + row.get("noticeBatchAttemptedAtNanos") +
                                "; batchLatency=" + row.get("noticeBatchLatencyNanos") +
                                "; notice=" + row.get("noticeStatus") + "; batchResult=" +
                                row.get("noticeBatchResult") + "; firstAt=" + row.get("firstDispatchAtNanos") +
                                "; deadline=" + row.get("deadlineAtNanos"));
                assertEquals("ON_TIME", row.get("firstReplyResult").getAsString());
                assertEquals(0, row.get("failedDispatches").getAsInt());
                assertEquals(0, row.get("unknownDispatches").getAsInt());

                if (input.acceptedByIngress() == 1) {
                    admittedRows++;
                    assertEquals("ADMITTED", row.get("outcome").getAsString());
                    assertEquals(input.captured().invocationId().toString(),
                            forwarded.stream().filter(item -> item.input().equals(input)).findFirst().orElseThrow()
                                    .frame().get("invocationId").getAsString());
                    assertEquals("ANSWER", row.get("terminalOutcome").getAsString());
                    assertEquals("SUBMITTED", row.get("terminalDeliveryResult").getAsString());
                    assertEquals(row.get("requestLinkAtNanos").getAsString(), row.get("queuedAtNanos").getAsString());
                    assertEquals(FIFTEEN_SECONDS,
                            longValue(row, "pendingDeadlineAtNanos") - input.t0Nanos(),
                            "the original 15 s pending deadline is retained");
                    assertEquals(30, row.get("executionBudgetSeconds").getAsInt());
                    assertTrue(longValue(row, "activeDeadlineAtNanos") <=
                                    longValue(row, "absoluteExecutionDeadlineAtNanos"),
                            "the active deadline never renews beyond the original absolute cutoff");
                    assertTrue(longValue(row, "activeDeadlineAtNanos") - longValue(row, "forwardedAtNanos") <=
                                    TimeUnit.SECONDS.toNanos(30),
                            "forwarded active time remains inside the configured 30 s execution budget");
                    assertNotNull(row.get("forwardedAtNanos"));
                    assertEquals(longValue(row, "forwardedAtNanos") - input.t0Nanos(),
                            longValue(row, "pendingResidenceNanos"));
                } else {
                    refusedRows++;
                    assertEquals(0, input.acceptedByIngress());
                    assertEquals("RATE_LIMITED", row.get("outcome").getAsString(),
                            "the only non-admissions in this workload are explicit limiter refusals");
                    assertTrue(row.get("requestId").isJsonNull(), "a refused distinct ask has no request link");
                    assertEquals("SUBMITTED", row.get("noticeStatus").getAsString());
                    assertEquals("SUBMITTED", row.get("noticeBatchResult").getAsString());
                    assertEquals(row.get("noticeBatchPublicationId").getAsString(),
                            row.get("publicationId").getAsString());
                    assertTrue(forwarded.stream().noneMatch(item -> item.input().equals(input)),
                            "an explicitly refused ask is never changed into companion work");
                }
            }

            assertTrue(admittedRows > 0);
            assertTrue(refusedRows > 0);
            assertTrue(harness.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATION_RATE_LIMITED.ordinal()] > 0,
                    "the 20/s A load exercises the real per-player invocation bucket");
            assertTrue(harness.metrics.countersSnapshot()[TickMetrics.Counter.NEW_WORK_RATE_LIMITED.ordinal()] > 0,
                    "distinct A inputs also exercise the real new-work token bucket");
            assertEquals(admittedRows, harness.metrics.countersSnapshot()[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()]);
            assertEquals(refusedRows, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_RATE_LIMITED.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_BUSY.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_COALESCED.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_EVIDENCE_DROPPED.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.FAILED_PUBLIC_DISPATCHES.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.UNKNOWN_PUBLIC_DISPATCHES.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.MAILBOX_OVERFLOWS.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.STALE_OUTPUTS.ordinal()]);
            assertFalse(harness.metrics.evidenceIncomplete());

            JsonObject queue = manifest.getAsJsonObject("queueEvidence");
            assertTrue(queue.get("pendingQueueScans").getAsLong() > 0);
            assertTrue(queue.get("maxPendingGlobal").getAsInt() <= 16);
            assertTrue(queue.get("maxPendingPerPlayer").getAsInt() <= 2);
            assertTrue(longValue(queue, "maxPendingAgeNanos") <= FIFTEEN_SECONDS);
            assertEquals(0, longValue(queue, "maxPendingCleanupDelayNanos"));
            assertTrue(queue.get("maxRecordsPerScan").getAsInt() <= 17);
            assertTrue(queue.get("maxPlayerQueuesPerScan").getAsInt() <= 16);

            List<String> bodyFreeSnapshot = Files.readAllLines(evidence);
            for (CapturedInput input : captured) {
                assertFalse(String.join("\n", bodyFreeSnapshot).contains(input.text()),
                        "numeric evidence does not persist ask bodies");
            }

            for (RequestObservation observation : forwarded) {
                if (observation.input().text().startsWith("B-") || observation.input().text().startsWith("C-")) {
                    JsonObject row = rowById.get(observation.input().captured().invocationId().toString());
                    long wait = longValue(row, "forwardedAtNanos") - observation.input().t0Nanos();
                    assertTrue(wait <= THREE_SECONDS, "B/C forwarding headstart is at most 3 s");
                    assertTrue(longValue(row, "terminalAtNanos") - observation.input().t0Nanos() <= FOUR_SECONDS,
                            "B/C receives its actual answer within 4 s of original t0");
                }
            }

            JsonObject firstB = findRow(captured, rowById, "B-0 distinct queued workload");
            JsonObject firstC = findRow(captured, rowById, "C-0 distinct queued workload");
            assertTrue(longValue(firstB, "pendingResidenceNanos") > 0);
            assertTrue(longValue(firstC, "pendingResidenceNanos") > 0);
            assertQueuedAcknowledgement(forwarded, "B-0 distinct queued workload", harness.secondPlayerId,
                    "Hello TesterTwo");
            assertQueuedAcknowledgement(forwarded, "C-0 distinct queued workload", harness.thirdPlayerId,
                    "Hello TesterThree");
        }
    }

    @Test
    void endTickCooldownNoticeKeepsItsOwnDeadlineAcrossTenStableAdversarialSchedules() throws Exception {
        long endTickCaptureAt = T0 + 1_183 * STEP_NANOS;
        for (int schedule = 0; schedule < 10; schedule++) {
            try (SmokeConversationLifecycleTest.Harness harness =
                         new SmokeConversationLifecycleTest.Harness(30, false, new InvocationRateLimiter())) {
                List<CapturedInput> captured = new ArrayList<>();
                Map<String, CapturedInput> inputByText = new HashMap<>();
                int[] admittedCount = {0};

                CapturedInput firstA = invoke(harness, captured, inputByText, admittedCount, harness.player,
                        harness.playerId, endTickCaptureAt, InteractionIngress.Origin.COMMAND,
                        "synthetic endtick A-1183 admitted turn one");
                CapturedInput secondA = invoke(harness, captured, inputByText, admittedCount, harness.player,
                        harness.playerId, endTickCaptureAt, InteractionIngress.Origin.COMMAND,
                        "synthetic endtick A-1183 admitted turn two");
                assertEquals(1, firstA.acceptedByIngress());
                assertEquals(1, secondA.acceptedByIngress());

                awaitStableTransportOwner(harness);
                harness.tickAt(endTickCaptureAt);
                JsonObject firstRequest = harness.awaitFrame("dialogue.request", 1);
                awaitStableTransportOwner(harness);
                long firstAnswerAt = endTickCaptureAt + 1;
                harness.sendAnswer(firstRequest, "synthetic answer one");
                harness.tickAt(firstAnswerAt);
                JsonObject secondRequest = harness.awaitFrame("dialogue.request", 2);
                awaitStableTransportOwner(harness);
                long secondAnswerAt = endTickCaptureAt + 2;
                harness.sendAnswer(secondRequest, "synthetic answer two");
                harness.tickAt(secondAnswerAt);
                awaitStableTransportOwner(harness);

                long refusalAt = secondAnswerAt;
                CapturedInput b = invoke(harness, captured, inputByText, admittedCount, harness.secondPlayer,
                        harness.secondPlayerId, refusalAt, InteractionIngress.Origin.COMMAND,
                        "synthetic B request for controlled acknowledgement I/O");
                assertEquals(1, b.acceptedByIngress());
                CapturedInput firstRefusal = invoke(harness, captured, inputByText, admittedCount, harness.player,
                        harness.playerId, refusalAt, InteractionIngress.Origin.COMMAND,
                        "synthetic endtick notice batch first member");
                CapturedInput joinedRefusal = invoke(harness, captured, inputByText, admittedCount, harness.player,
                        harness.playerId, refusalAt, InteractionIngress.Origin.COMMAND,
                        "synthetic endtick notice batch joined member");
                assertEquals(0, firstRefusal.acceptedByIngress());
                assertEquals(0, joinedRefusal.acceptedByIngress());

                harness.socket.holdAcknowledgementSends(true);
                awaitStableTransportOwner(harness);
                harness.tickAt(refusalAt);
                awaitStableTransportOwner(harness);
                assertEquals(1, countContaining(harness.game.publishedLines(),
                        "Your requests are being throttled."), "the first safe notice publishes at this t0");

                CapturedInput deadlineRefusal = invoke(harness, captured, inputByText, admittedCount,
                        harness.player, harness.playerId, refusalAt, InteractionIngress.Origin.COMMAND,
                        "synthetic endtick notice with exact cooldown deadline");
                assertEquals(0, deadlineRefusal.acceptedByIngress());
                JsonObject queuedState = tickObservation(harness, refusalAt,
                        (int) harness.metrics.countersSnapshot()[TickMetrics.Counter.TICK_BUDGET_EXHAUSTED.ordinal()]);
                JsonObject queueHead = queuedState.getAsJsonObject("schedulerQueueHead");
                assertNotNull(queueHead, "the second shared notice remains queued until its fixed cooldown opens");
                assertEquals(refusalAt + ONE_SECOND,
                        Long.parseLong(queueHead.get("notBeforeNanos").getAsString()));
                assertEquals(refusalAt + ONE_SECOND,
                        Long.parseLong(queueHead.get("originalDeadlineNanos").getAsString()));
                JsonObject noticeHead = queuedState.getAsJsonArray("pendingNoticeCauses").get(0).getAsJsonObject();
                assertEquals("RATE_LIMITED", noticeHead.get("cause").getAsString());
                assertEquals(1, noticeHead.get("memberCount").getAsInt());

                long acknowledgementAt = refusalAt + TimeUnit.MILLISECONDS.toNanos(750);
                harness.tickAt(acknowledgementAt);
                awaitStableTransportOwner(harness);
                var acknowledgement = harness.awaitSend("dialogue.ack", 1);
                assertFalse(acknowledgement.completion().isDone());

                harness.socket.complete(acknowledgement);
                harness.awaitAcknowledgementOffers(1);
                long beforeDeadline = refusalAt + ONE_SECOND - 1;
                harness.tickAt(beforeDeadline);
                awaitStableTransportOwner(harness);
                harness.socket.holdAcknowledgementSends(false);

                long exactDeadline = refusalAt + ONE_SECOND;
                harness.tickAt(exactDeadline);
                awaitStableTransportOwner(harness);

                Path evidence = temporaryDirectory.resolve("r01-resp-04-qa-005-edge-" + schedule + ".jsonl");
                saveEvidence(harness, evidence);
                Map<String, JsonObject> rows = new HashMap<>();
                for (JsonObject row : evidenceRows(evidence)) rows.put(row.get("id").getAsString(), row);
                JsonObject firstRow = rows.get(firstRefusal.captured().invocationId().toString());
                JsonObject joinedRow = rows.get(joinedRefusal.captured().invocationId().toString());
                JsonObject deadlineRow = rows.get(deadlineRefusal.captured().invocationId().toString());
                assertNotNull(firstRow);
                assertNotNull(joinedRow);
                assertNotNull(deadlineRow);
                assertEquals("SUBMITTED", firstRow.get("noticeStatus").getAsString());
                assertEquals("SUBMITTED", joinedRow.get("noticeStatus").getAsString());
                assertEquals(firstRow.get("noticeBatchPublicationId").getAsString(),
                        joinedRow.get("noticeBatchPublicationId").getAsString());
                assertEquals("SUBMITTED", deadlineRow.get("noticeStatus").getAsString());
                assertNotEquals(firstRow.get("noticeBatchPublicationId").getAsString(),
                        deadlineRow.get("noticeBatchPublicationId").getAsString(),
                        "a cooldown-ready notice is a new batch, not a standing t1 reuse");
                assertEquals(exactDeadline, longValue(deadlineRow, "deadlineAtNanos"));
                assertEquals(exactDeadline, longValue(deadlineRow, "noticeBatchAttemptedAtNanos"));
                assertEquals(exactDeadline, longValue(deadlineRow, "firstDispatchAtNanos"));
                assertEquals(ONE_SECOND, longValue(deadlineRow, "firstDispatchNanos"));
                assertEquals("ON_TIME", deadlineRow.get("firstReplyResult").getAsString());
                assertEquals(0, harness.rateLimiter.pendingNoticeMembers());
                assertEquals(0, harness.scheduler.pendingDispositionCount());
                assertEquals(0,
                        harness.metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);
                assertTrue(longValue(rows.get(b.captured().invocationId().toString()), "firstDispatchNanos") <= ONE_SECOND,
                        "the controlled asynchronous ACK keeps B's independent first reply bounded");
            }
        }
    }

    @Test
    void acknowledgedButExpiredPendingControlIsNotCountedAsFairnessSuccess() throws Exception {
        try (SmokeConversationLifecycleTest.Harness harness =
                     new SmokeConversationLifecycleTest.Harness(30, false, new InvocationRateLimiter())) {
            List<CapturedInput> captured = new ArrayList<>();
            Map<String, CapturedInput> inputByText = new HashMap<>();
            int[] admittedCount = {0};
            String controlA = "A-expiry-control active held request";
            String controlB = "B-expiry-control queued request";

            CapturedInput a = invoke(harness, captured, inputByText, admittedCount, harness.player,
                    harness.playerId, T0, InteractionIngress.Origin.COMMAND, controlA);
            assertEquals(1, a.acceptedByIngress());
            harness.tickAt(T0);
            JsonObject activeA = harness.awaitFrame("dialogue.request", 1);

            long bAt = T0 + TimeUnit.MILLISECONDS.toNanos(100);
            CapturedInput b = invoke(harness, captured, inputByText, admittedCount, harness.secondPlayer,
                    harness.secondPlayerId, bAt, InteractionIngress.Origin.NATURAL_ADDRESS, controlB);
            assertEquals(1, b.acceptedByIngress());
            harness.tickAt(bAt);

            long ackAt = T0 + ONE_SECOND;
            harness.tickAt(ackAt);
            assertEquals(1, countContaining(harness.game.publishedLines(), "Hello TesterTwo"),
                    "B's queued first public reply is acknowledged within its original one-second deadline");
            assertEquals(1, harness.socket.sendCount("dialogue.request"),
                    "A remains the only forwarded request while B is waiting");

            long bExpiry = bAt + FIFTEEN_SECONDS;
            harness.tickAt(bExpiry);
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.PENDING_EXPIRED.ordinal()]);
            assertEquals(1, harness.socket.sendCount("dialogue.request"),
                    "the expired B never crosses the actual companion request socket");
            assertEquals(activeA.get("requestId").getAsString(), harness.mailbox.activeRequest().requestId(),
                    "A remains the actual active mailbox request when B expires");
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()],
                    "no completion exists yet when B reaches its fixed pending cutoff");

            // A remains active past B's 15 s pending cutoff; only A then completes with synthetic content.
            harness.sendAnswer(activeA, "Synthetic answer for " + controlA);
            harness.tickAt(bExpiry + TimeUnit.MILLISECONDS.toNanos(100));
            assertEquals(1, harness.socket.sendCount("dialogue.request"));
            assertEquals(List.of("[Worldspeaker] Archivist: Synthetic answer for " + controlA),
                    harness.game.publishedLines().stream()
                            .filter(line -> line.contains("Synthetic answer for ")).toList());

            Path evidence = temporaryDirectory.resolve("r01-resp-04-qa-002-expiry-control.jsonl");
            saveEvidence(harness, evidence);
            Map<String, JsonObject> rowById = new HashMap<>();
            for (JsonObject row : evidenceRows(evidence)) rowById.put(row.get("id").getAsString(), row);
            JsonObject bRow = rowById.get(b.captured().invocationId().toString());
            assertNotNull(bRow);
            assertEquals("NATURAL_ADDRESS", bRow.get("origin").getAsString());
            assertEquals("ADMITTED", bRow.get("outcome").getAsString());
            assertTrue(longValue(bRow, "firstDispatchNanos") <= ONE_SECOND);
            assertEquals("ON_TIME", bRow.get("firstReplyResult").getAsString());
            assertEquals("LOCAL_ACKNOWLEDGEMENT", bRow.get("firstDispatchSource").getAsString());
            assertEquals("EXPIRED", bRow.get("terminalOutcome").getAsString());
            assertTrue(bRow.get("forwardedAtNanos").isJsonNull(), "the expired B has no forwarded-at metric");
            assertEquals(FIFTEEN_SECONDS,
                    longValue(bRow, "pendingDeadlineAtNanos") - bAt,
                    "expiry uses B's original t0 + 15 s cutoff, not a renewed deadline");
            JsonObject aRow = rowById.get(a.captured().invocationId().toString());
            assertEquals("ANSWER", aRow.get("terminalOutcome").getAsString());
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()],
                    "the expired B is not counted as a fairness completion despite its timely acknowledgement");
            assertEquals(2, harness.metrics.countersSnapshot()[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()],
                    "the admitted count includes expired B; answer fairness does not");
            assertFalse(harness.metrics.evidenceIncomplete());
        }
    }

    private static CapturedInput invoke(SmokeConversationLifecycleTest.Harness harness,
                                        List<CapturedInput> captured,
                                        Map<String, CapturedInput> inputByText,
                                        int[] admittedCount,
                                        SmokeConversationLifecycleTest.FakePlayer player,
                                        UUID playerId, long atNanos, InteractionIngress.Origin origin,
                                        String text) {
        harness.clock.set(atNanos);
        InteractionIngress.Captured receipt = InteractionIngress.Captured.capture(() -> atNanos,
                () -> WALL_MILLIS, origin);
        String input = origin == InteractionIngress.Origin.COMMAND ? "worldspeaker ask" : "natural address";
        int accepted = InteractionIngress.execute(receipt, player, input,
                InteractionIngress.Route.ASK, text, harness.ingress);
        CapturedInput observation = new CapturedInput(receipt, origin, text, player, playerId, atNanos, accepted);
        assertNull(inputByText.put(text, observation), "all generated asks are distinct at ordinal " +
                (captured.size() + 1));
        captured.add(observation);
        if (accepted == 1) admittedCount[0]++;
        else assertEquals(0, accepted, "only a completed admission has a positive ingress return");
        return observation;
    }

    private static DrainState drainForwarded(SmokeConversationLifecycleTest.Harness harness, long nowNanos,
                                              int observedRequests, int admittedCount, String heldText,
                                              List<CapturedInput> captured, Map<String, CapturedInput> inputByText,
                                              List<RequestObservation> forwarded, Set<String> answeredTexts) {
        JsonObject held = null;
        while (observedRequests < admittedCount) {
            int acknowledgementOffersBefore = harness.acknowledgementOfferCount.get();
            JsonObject frame;
            try {
                frame = harness.awaitFrame("dialogue.request", observedRequests + 1);
            } catch (AssertionError missingForward) {
                long notYetForwarded = captured.stream().filter(input -> input.acceptedByIngress() == 1)
                        .skip(observedRequests).count();
                throw new AssertionError("admitted request was not forwarded at fake t=" + nowNanos +
                        "; observed=" + observedRequests + "; admitted=" + admittedCount +
                        "; pendingInputs=" + notYetForwarded + "; ackSends=" +
                        harness.socket.sendCount("dialogue.ack") + "; ackOffers=" +
                        harness.acknowledgementOfferCount.get() + "; mailboxAck=" +
                        harness.mailbox.peekAcknowledgement() + "; pendingDispositions=" +
                        harness.scheduler.pendingDispositionCount(), missingForward);
            }
            observedRequests++;
            assertEquals(observedRequests, harness.socket.sendCount("dialogue.request"),
                    "the real lifecycle has at most one active forwarded request");
            String requestId = frame.get("requestId").getAsString();
            String invocationId = frame.get("invocationId").getAsString();
            JsonObject event = frame.getAsJsonObject("event");
            String text = event.get("text").getAsString();
            CapturedInput input = inputByText.get(text);
            assertNotNull(input, "no invented or rewritten ask reaches the companion socket");
            assertEquals(input.captured().invocationId().toString(), invocationId);
            assertEquals(input.playerId().toString(), frame.getAsJsonObject("replyAudience")
                    .get("playerId").getAsString());
            assertEquals(AttributedEvent.inputKind(input.origin()), event.get("inputKind").getAsString());
            assertTrue(forwarded.stream().noneMatch(item -> item.frame().get("requestId").getAsString().equals(requestId)),
                    "request IDs are not replayed");
            forwarded.add(new RequestObservation(input, frame));

            if (heldText != null && heldText.equals(text)) {
                assertNull(held, "only A0 is deliberately held in flight");
                held = frame;
                continue;
            }

            awaitAcknowledgementContext(harness, nowNanos, acknowledgementOffersBefore);
            String answer = "Synthetic answer for " + text;
            assertTrue(answeredTexts.add(text), "a distinct request receives one deterministic answer");
            harness.sendAnswer(frame, answer);
            harness.tickAt(nowNanos);
            assertEquals(1, harness.game.attempts().stream().filter(attempt -> attempt.target() == input.player() &&
                            attempt.result() == FirstReplyScheduler.PublicationResult.SUBMITTED &&
                            attempt.renderedLine().equals("[Worldspeaker] Archivist: " + answer)).count(),
                    "the actual rendered answer keeps the requested content and original player audience");
        }
        return new DrainState(observedRequests, held);
    }

    private static void awaitAcknowledgementContext(SmokeConversationLifecycleTest.Harness harness, long nowNanos,
                                                     int offersBeforeRequest) {
        int sends = harness.socket.sendCount("dialogue.ack");
        int offers = harness.acknowledgementOfferCount.get();
        if (sends > offersBeforeRequest || sends > offers) {
            harness.awaitAcknowledgementOffers(sends);
            assertEquals(sends, harness.acknowledgementOfferCount.get());
            assertEquals(org.worldspeaker.runtime.GameLoopMailbox.Offer.ACCEPTED,
                    harness.acknowledgementOfferOutcome.get());
            harness.tickAt(nowNanos);
        }
    }

    private static void assertQueuedAcknowledgement(List<RequestObservation> forwarded, String text,
                                                     UUID audience, String expectedGreeting) {
        RequestObservation observation = forwarded.stream()
                .filter(item -> item.input().text().equals(text)).findFirst().orElseThrow();
        JsonObject acknowledgement = observation.frame().getAsJsonObject("acknowledgement");
        assertNotNull(acknowledgement, "the queued local acknowledgement is forwarded as original context");
        assertTrue(acknowledgement.get("text").getAsString().contains(expectedGreeting));
        assertEquals(audience.toString(), acknowledgement.getAsJsonObject("replyAudience")
                .get("playerId").getAsString());
        assertTrue(Long.parseLong(acknowledgement.get("dispatchedAtMonotonicNanos").getAsString()) -
                observation.input().t0Nanos() <= ONE_SECOND,
                "the forwarded context preserves the original timely public acknowledgement timestamp");
    }

    private static JsonObject findRow(List<CapturedInput> captured, Map<String, JsonObject> rows, String text) {
        CapturedInput input = captured.stream().filter(item -> item.text().equals(text)).findFirst().orElseThrow();
        return rows.get(input.captured().invocationId().toString());
    }

    private static void saveEvidence(SmokeConversationLifecycleTest.Harness harness, Path path) throws Exception {
        assertTrue(harness.metrics.offerNumericSnapshot(path));
        assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));
    }

    private static List<JsonObject> evidenceRows(Path path) throws Exception {
        List<JsonObject> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            JsonObject frame = JsonParser.parseString(line).getAsJsonObject();
            if (!frame.has("records")) continue;
            for (var item : frame.getAsJsonArray("records")) rows.add(item.getAsJsonObject());
        }
        return List.copyOf(rows);
    }

    private static long longValue(JsonObject row, String field) {
        return Long.parseLong(row.get(field).getAsString());
    }

    private static int countContaining(List<String> lines, String text) {
        return (int) lines.stream().filter(line -> line.contains(text)).count();
    }

    private static void awaitStableTransportOwner(SmokeConversationLifecycleTest.Harness harness) {
        BoundCompanionClientTestAccess.awaitOwnerBarrier(harness.companion, 3, TimeUnit.SECONDS);
        BoundCompanionClientTestAccess.awaitOwnerBarrier(harness.companion, 3, TimeUnit.SECONDS);
    }

    private static JsonObject tickObservation(SmokeConversationLifecycleTest.Harness harness, long nowNanos,
                                             int budgetExhaustionsBeforeTick) {
        JsonObject row = new JsonObject();
        row.addProperty("fakeNowNanos", Long.toString(nowNanos));
        row.addProperty("pendingDispositionCount", harness.scheduler.pendingDispositionCount());
        row.addProperty("pendingNoticeMembers", harness.rateLimiter.pendingNoticeMembers());
        row.addProperty("readyDispositionDeadlineNanos", nullableLong(harness.scheduler.nextDispositionDeadlineNanos()));
        row.addProperty("budgetMaximumOperations", SmokeTickBudget.MAX_OPERATIONS);
        row.addProperty("budgetMaximumStartNanos", Long.toString(SmokeTickBudget.MAX_START_NANOS));
        int budgetExhaustions = (int) harness.metrics.countersSnapshot()[TickMetrics.Counter.TICK_BUDGET_EXHAUSTED.ordinal()];
        row.addProperty("budgetExhaustions", budgetExhaustions);
        row.addProperty("budgetExhaustedThisTick", budgetExhaustions > budgetExhaustionsBeforeTick);
        row.add("operationsStartedExact", com.google.gson.JsonNull.INSTANCE);
        row.addProperty("operationsStartedLowerBound", budgetExhaustions > budgetExhaustionsBeforeTick
                ? SmokeTickBudget.MAX_OPERATIONS : 0);
        row.addProperty("operationsStartedUpperBound", SmokeTickBudget.MAX_OPERATIONS);
        row.addProperty("requestSendCount", harness.socket.sendCount("dialogue.request"));
        row.addProperty("ackSendCount", harness.socket.sendCount("dialogue.ack"));
        row.addProperty("renderedAttemptCount", harness.game.attempts().size());
        addPendingQueueEvidence(row, harness);
        return row;
    }

    /** Test-only structural snapshot: queue data contains bounded times, enum causes, and counts only. */
    private static void addPendingQueueEvidence(JsonObject observation,
                                               SmokeConversationLifecycleTest.Harness harness) {
        try {
            Object queue = field(FirstReplyScheduler.class, "dispositions").get(harness.scheduler);
            int size = field(queue.getClass(), "size").getInt(queue);
            Object entries = field(queue.getClass(), "entries").get(queue);
            observation.addProperty("schedulerQueueSize", size);
            if (size > 0) {
                Object head = Array.get(entries, 0);
                JsonObject headRow = new JsonObject();
                headRow.addProperty("notBeforeNanos", Long.toString(numberField(head, "notBeforeNanos")));
                headRow.addProperty("originalDeadlineNanos", Long.toString(numberField(head, "originalDeadlineNanos")));
                headRow.addProperty("stableOrder", Long.toString(numberField(head, "stableOrder")));
                observation.add("schedulerQueueHead", headRow);
            } else {
                observation.add("schedulerQueueHead", com.google.gson.JsonNull.INSTANCE);
            }

            Map<?, ?> subjects = (Map<?, ?>) field(InvocationRateLimiter.class, "subjects")
                    .get(harness.rateLimiter);
            JsonArray pendingNotices = new JsonArray();
            int pendingBatchCount = 0;
            for (Object subject : subjects.values()) {
                Object batch = field(subject.getClass(), "noticeBatch").get(subject);
                if (batch == null) continue;
                pendingBatchCount++;
                if (pendingNotices.size() >= FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS) continue;
                JsonObject batchRow = new JsonObject();
                batchRow.addProperty("cause", noticeCauseNames(numberField(batch, "causeMask")));
                batchRow.addProperty("state", field(batch.getClass(), "state").get(batch).toString());
                batchRow.addProperty("notBeforeNanos", Long.toString(numberField(batch, "notBeforeNanos")));
                batchRow.addProperty("originalDeadlineNanos", Long.toString(numberField(batch, "earliestDeadlineNanos")));
                batchRow.addProperty("memberCount", (int) numberField(batch, "memberCount"));
                pendingNotices.add(batchRow);
            }
            observation.addProperty("pendingNoticeBatchCount", pendingBatchCount);
            observation.add("pendingNoticeCauses", pendingNotices);
        } catch (ReflectiveOperationException | SecurityException failure) {
            observation.addProperty("queueStateCapture", "unavailable:" + failure.getClass().getSimpleName());
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static long numberField(Object target, String name) throws ReflectiveOperationException {
        return field(target.getClass(), name).getLong(target);
    }

    private static String noticeCauseNames(long mask) {
        List<String> causes = new ArrayList<>();
        for (InvocationRateLimiter.NoticeCause cause : InvocationRateLimiter.NoticeCause.values()) {
            if ((mask & (1L << cause.ordinal())) != 0) causes.add(cause.name());
        }
        return String.join(",", causes);
    }

    private static String nullableLong(Long value) {
        return value == null ? "none" : Long.toString(value);
    }

    private static void persistFirstReplyFailure(int ordinal, JsonObject row, List<JsonObject> trace) {
        try {
            Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            Path ownerPrivate = root.resolve("docs/r01/ownerprivate");
            Path failureDirectory = ownerPrivate.resolve("testfailure");
            createOwnerOnlyDirectory(ownerPrivate);
            createOwnerOnlyDirectory(failureDirectory);

            JsonObject evidence = new JsonObject();
            evidence.addProperty("kind", "R01-RESP-04-QA-005 first-reply failure capture");
            evidence.addProperty("capturedAtUtc", java.time.Instant.now().toString());
            evidence.addProperty("failedInvocationOrdinal", ordinal);
            evidence.addProperty("containsPromptOrAnswerBodies", false);
            evidence.addProperty("containsInvocationOrRequestIds", false);
            evidence.addProperty("tickBudgetOperationCountAvailability",
                    "Exhaustion proves 8 starts; otherwise production exposes only the 0..7 range, not the exact count.");
            evidence.add("failedNumericRow", sanitizedFailureRow(row));
            JsonArray timeline = new JsonArray();
            for (JsonObject tick : trace) timeline.add(tick.deepCopy());
            evidence.add("endTickTimeline", timeline);
            byte[] bytes = evidence.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > 32 * 1024) throw new IllegalStateException("failure capture exceeded 32 KiB bound");

            Path file = Files.createTempFile(failureDirectory, "qa005-failure-", ".json",
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(ownerFilePermissions()));
            Files.write(file, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception captureFailure) {
            throw new AssertionError("first-reply failure evidence could not be stored owner-private", captureFailure);
        }
    }

    private static JsonObject sanitizedFailureRow(JsonObject row) throws Exception {
        JsonObject sanitized = new JsonObject();
        for (String field : List.of("origin", "outcome", "rateLimitStatus", "t0Nanos", "deadlineAtNanos",
                "firstDispatchAtNanos", "firstDispatchNanos", "firstDispatchSource", "firstReplyResult",
                "noticeStatus", "noticeBatchResult", "noticeBatchAttemptedAtNanos", "noticeBatchLatencyNanos",
                "terminalOutcome", "failedDispatches", "unknownDispatches")) {
            if (row.has(field)) sanitized.add(field, row.get(field).deepCopy());
        }
        if (row.has("noticeBatchPublicationId") && !row.get("noticeBatchPublicationId").isJsonNull()) {
            sanitized.addProperty("noticeBatchFingerprint",
                    shortFingerprint(row.get("noticeBatchPublicationId").getAsString()));
        }
        return sanitized;
    }

    private static String shortFingerprint(String value) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest, 0, 8);
    }

    private static void createOwnerOnlyDirectory(Path directory) throws Exception {
        if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory,
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(ownerDirectoryPermissions()));
        }
        if (Files.isSymbolicLink(directory) ||
                !Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                !Files.getPosixFilePermissions(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        .equals(ownerDirectoryPermissions())) {
            throw new IllegalStateException("refusing non-owner-private testfailure directory");
        }
    }

    private static Set<PosixFilePermission> ownerDirectoryPermissions() {
        return EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
    }

    private static Set<PosixFilePermission> ownerFilePermissions() {
        return EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    private record CapturedInput(InteractionIngress.Captured captured,
                                 InteractionIngress.Origin origin,
                                 String text,
                                 SmokeConversationLifecycleTest.FakePlayer player,
                                 UUID playerId,
                                 long t0Nanos,
                                 int acceptedByIngress) {}

    private record RequestObservation(CapturedInput input, JsonObject frame) {}

    private record DrainState(int observedRequests, JsonObject heldRequest) {}
}
