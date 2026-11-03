package org.worldspeaker.bootstrap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.runtime.TickMetrics;
import org.worldspeaker.runtime.TickMetricsTestAccess;
import org.worldspeaker.runtime.ThreadBoundaryTestAccess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full production-ingress regression for the fixed post-terminal exact-repeat guard. */
class R01Resp04Qa001Test {
    private static final long START_NANOS = 3_000_000_000L;
    private static final long ONE_SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long SIX_SECONDS = TimeUnit.SECONDS.toNanos(6);
    private static final long FAST_TERMINAL_LATENCY = TimeUnit.MILLISECONDS.toNanos(50);
    private static final String ASK = "What is 7 * 8?";
    private static final String ORIGINAL_ANSWER = "56";

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearSharedServerThreadBinding() {
        ThreadBoundaryTestAccess.clearForTests();
    }

    @Test
    void completedAnswerKeepsOneInclusiveOriginalT0GuardAcrossSevenProductionInvocations() throws Exception {
        try (SmokeConversationLifecycleTest.Harness harness =
                     new SmokeConversationLifecycleTest.Harness(30, false, new InvocationRateLimiter())) {
            InteractionIngress.Invocation original = harness.admit(ASK);
            harness.tickAt(START_NANOS);
            JsonObject originalRequest = harness.awaitFrame("dialogue.request");

            harness.sendAnswer(originalRequest, ORIGINAL_ANSWER);
            harness.tickAt(START_NANOS + FAST_TERMINAL_LATENCY);
            assertEquals(1, countContaining(harness.game.publishedLines(), ORIGINAL_ANSWER),
                    "the fast terminal answer is already public before the first repeat at t0 + 100 ms");
            assertEquals(1, harness.socket.sendCount("dialogue.request"));

            long[] repeatOffsets = {
                    TimeUnit.MILLISECONDS.toNanos(100), ONE_SECOND,
                    TimeUnit.SECONDS.toNanos(2), TimeUnit.SECONDS.toNanos(3),
                    TimeUnit.SECONDS.toNanos(4), TimeUnit.SECONDS.toNanos(5), SIX_SECONDS
            };
            List<InteractionIngress.Invocation> refused = new ArrayList<>(repeatOffsets.length);
            for (int index = 0; index < repeatOffsets.length; index++) {
                long capturedAt = START_NANOS + repeatOffsets[index];
                harness.clock.set(capturedAt);
                assertEquals(0, harness.invokeAsk(harness.player, ASK, capturedAt),
                        "exact repeat " + index + " must be refused through common production ingress");
                InteractionIngress.Invocation repeat = harness.invocations.getLast();
                refused.add(repeat);

                // The held answer is terminal; exact repeats may neither replay it nor start provider work.
                assertEquals(1, harness.socket.sendCount("dialogue.request"));
                harness.tickAt(capturedAt);
                if (index < repeatOffsets.length - 1) {
                    // Notice cooldown makes each healthy first-public response due 100 ms later.
                    harness.tickAt(capturedAt + TimeUnit.MILLISECONDS.toNanos(100));
                    assertTrue(repeat.firstReplySlot().hasFirstPublication(),
                            "each repeat's safe notice must publish inside its own one-second deadline");
                }
                assertEquals(1, countContaining(harness.game.publishedLines(), ORIGINAL_ANSWER),
                        "a completed answer is not replayed for a refused invocation");
            }

            long justAfterGuard = START_NANOS + SIX_SECONDS + 1;
            assertEquals(1, harness.rateLimiter.pendingNoticeMembers(),
                    "only the final repeat notice remains pending until its cooldown opens");
            harness.clock.set(justAfterGuard);
            assertEquals(1, harness.invokeAsk(harness.player, ASK, justAfterGuard),
                    "t0 + 6 s + 1 ns is a normal fresh admission, not a sliding refusal");
            InteractionIngress.Invocation fresh = harness.invocations.getLast();
            harness.tickAt(justAfterGuard);
            JsonObject freshRequest = harness.awaitFrame("dialogue.request", 2);
            assertEquals(ASK, freshRequest.getAsJsonObject("event").get("text").getAsString());
            assertEquals(2, harness.socket.sendCount("dialogue.request"));

            long freshAnswerAt = justAfterGuard + TimeUnit.MILLISECONDS.toNanos(100);
            harness.sendAnswer(freshRequest, "56 after the guard expired");
            harness.tickAt(freshAnswerAt);

            assertTrue(refused.stream().allMatch(invocation -> invocation.firstReplySlot().hasFirstPublication()),
                    "all seven retained refusals receive their own timely shared-notice publication");
            assertTrue(original.firstReplySlot().hasFirstPublication());
            assertTrue(fresh.firstReplySlot().hasFirstPublication());
            assertEquals(0, harness.rateLimiter.pendingNoticeMembers());
            assertEquals(2, harness.metrics.countersSnapshot()[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()]);
            assertEquals(7, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_RATE_LIMITED.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.NEW_WORK_RATE_LIMITED.ordinal()],
                    "guard refusals are rejected before new-work admission is attempted");
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATION_RATE_LIMITED.ordinal()],
                    "all nine authenticated invocations remain within the invocation bucket");
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);

            List<SmokeConversationLifecycleTest.FakeGamePort.RenderedAttempt> rateLimitAttempts =
                    harness.game.attempts().stream()
                            .filter(attempt -> attempt.renderedLine().contains("Your requests are being throttled."))
                            .toList();
            assertEquals(7, rateLimitAttempts.size(), "each invocation receives one safe, sender-scoped notice");
            assertTrue(rateLimitAttempts.stream().allMatch(attempt -> attempt.target() == harness.player &&
                            attempt.result() == FirstReplyScheduler.PublicationResult.SUBMITTED &&
                            !attempt.renderedLine().contains("Archivist:")),
                    "refusals are submitted to the original connection without character-ACK formatting");
            assertEquals(1, countExact(harness.game.publishedLines(),
                    "[Worldspeaker] Archivist: " + ORIGINAL_ANSWER));
            assertEquals(1, countContaining(harness.game.publishedLines(), "56 after the guard expired"));

            Path evidence = temporaryDirectory.resolve("r01-resp-04-qa-001.jsonl");
            saveEvidence(harness, evidence);
            JsonObject originalRow = evidenceRow(evidence, original.invocationId().toString());
            assertEquals("ANSWER", originalRow.get("terminalOutcome").getAsString());
            assertEquals("SUBMITTED", originalRow.get("terminalDeliveryResult").getAsString());
            assertEquals(Long.toString(START_NANOS + FAST_TERMINAL_LATENCY),
                    originalRow.get("terminalAtNanos").getAsString());
            assertEquals(FAST_TERMINAL_LATENCY, longValue(originalRow, "firstDispatchNanos"));

            String originalRequestId = originalRow.get("requestId").getAsString();
            for (int index = 0; index < refused.size(); index++) {
                InteractionIngress.Invocation repeat = refused.get(index);
                JsonObject row = evidenceRow(evidence, repeat.invocationId().toString());
                assertEquals("RATE_LIMITED", row.get("outcome").getAsString(), "refusal row " + index);
                assertEquals("REPEAT_GUARD", row.get("rateLimitStatus").getAsString(), "refusal row " + index);
                assertEquals(originalRequestId, row.get("requestId").getAsString(),
                        "refusal row " + index + " remains linked to completed A");
                assertTrue(row.get("queuedAtNanos").isJsonNull(),
                        "refusal row " + index + " is linked but did not queue new work");
                assertEquals("SUBMITTED", row.get("noticeStatus").getAsString());
                assertEquals("SUBMITTED", row.get("noticeBatchResult").getAsString());

                long capturedAt = longValue(row, "t0Nanos");
                long dispatchedAt = longValue(row, "firstDispatchAtNanos");
                long latency = longValue(row, "firstDispatchNanos");
                assertEquals(latency, dispatchedAt - capturedAt);
                assertTrue(latency >= 0 && latency <= ONE_SECOND,
                        "repeat " + index + " first public reply is within 1,000 ms of its captured t0");
            }

            JsonObject freshRow = evidenceRow(evidence, fresh.invocationId().toString());
            assertEquals("ADMITTED", freshRow.get("outcome").getAsString());
            assertNotEquals(originalRequestId, freshRow.get("requestId").getAsString());
            assertTrue(longValue(freshRow, "firstDispatchNanos") <= ONE_SECOND,
                    "the fresh request also gets a public first reply within its one-second bound");
        }
    }

    @Test
    void workLimitedFailureCreatesNoRepeatKeyOrTokenDebitAndRetriesAtRefillBoundary() throws Exception {
        try (SmokeConversationLifecycleTest.Harness harness =
                     new SmokeConversationLifecycleTest.Harness(30, false, new InvocationRateLimiter())) {
            InteractionIngress.Invocation fillOne = harness.admit("Fill the first player work token");
            InteractionIngress.Invocation fillTwo = harness.admit("Fill the second player work token");
            harness.tickAt(START_NANOS);
            JsonObject firstRequest = harness.awaitFrame("dialogue.request", 1);
            harness.sendAnswer(firstRequest, "first work complete");
            long firstCompleteAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(100);
            harness.tickAt(firstCompleteAt);

            JsonObject secondRequest = harness.awaitFrame("dialogue.request", 2);
            harness.sendAnswer(secondRequest, "second work complete");
            long secondCompleteAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(200);
            harness.tickAt(secondCompleteAt);
            assertEquals(2, harness.metrics.countersSnapshot()[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()]);

            String retryText = "Retry after the work bucket refills";
            long firstRefusalAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(300);
            harness.clock.set(firstRefusalAt);
            assertEquals(0, harness.invokeAsk(harness.player, retryText, firstRefusalAt));
            InteractionIngress.Invocation firstRefusal = harness.invocations.getLast();
            harness.tickAt(firstRefusalAt);

            // Two successful admissions at t0 exhausted this player's burst. Probe immediately before
            // the first full refill; a failed probe must not insert the repeat key or charge a token.
            long justBeforeRefill = START_NANOS + TimeUnit.SECONDS.toNanos(3) - 1;
            harness.clock.set(justBeforeRefill);
            assertEquals(0, harness.invokeAsk(harness.player, retryText, justBeforeRefill));
            InteractionIngress.Invocation boundaryRefusal = harness.invocations.getLast();
            harness.tickAt(justBeforeRefill);

            long justAfterRefill = START_NANOS + TimeUnit.SECONDS.toNanos(3) + 10;
            harness.clock.set(justAfterRefill);
            assertEquals(1, harness.invokeAsk(harness.player, retryText, justAfterRefill),
                    "the same text becomes fresh work as soon as one work token has refilled");
            InteractionIngress.Invocation retry = harness.invocations.getLast();
            harness.tickAt(justAfterRefill);
            JsonObject retryRequest = harness.awaitFrame("dialogue.request", 3);
            harness.sendAnswer(retryRequest, "the retry was admitted");
            harness.tickAt(justAfterRefill + TimeUnit.MILLISECONDS.toNanos(100));

            assertEquals(3, harness.socket.sendCount("dialogue.request"),
                    "failed admissions never start companion requests; only the successful retry does");
            assertEquals(2, harness.metrics.countersSnapshot()[TickMetrics.Counter.NEW_WORK_RATE_LIMITED.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATION_RATE_LIMITED.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);

            Path evidence = temporaryDirectory.resolve("r01-resp-04-qa-001-work-refill.jsonl");
            saveEvidence(harness, evidence);
            for (InteractionIngress.Invocation failed : List.of(firstRefusal, boundaryRefusal)) {
                JsonObject row = evidenceRow(evidence, failed.invocationId().toString());
                assertEquals("RATE_LIMITED", row.get("outcome").getAsString());
                assertEquals("NEW_WORK_LIMITED", row.get("rateLimitStatus").getAsString());
                assertTrue(row.get("requestId").isJsonNull(), "a failed admission reserves no repeat-guard link");
                assertTrue(row.get("queuedAtNanos").isJsonNull(), "a failed admission does not enter the request queue");
                assertTrue(longValue(row, "firstDispatchNanos") <= ONE_SECOND,
                        "the refusal notice is sent within the invocation's first-reply deadline");
            }
            JsonObject retryRow = evidenceRow(evidence, retry.invocationId().toString());
            assertEquals("ADMITTED", retryRow.get("outcome").getAsString());
            assertNotNull(retryRow.get("requestId").getAsString());
            assertEquals(retryRow.get("requestLinkAtNanos").getAsString(),
                    retryRow.get("queuedAtNanos").getAsString());
            assertNotEquals(fillOne.invocationId().toString(), retryRow.get("requestId").getAsString());
            assertNotEquals(fillTwo.invocationId().toString(), retryRow.get("requestId").getAsString());
        }
    }

    private static void saveEvidence(SmokeConversationLifecycleTest.Harness harness, Path path) throws Exception {
        assertTrue(harness.metrics.offerNumericSnapshot(path));
        assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));
    }

    private static JsonObject evidenceRow(Path path, String invocationId) throws Exception {
        for (String line : Files.readAllLines(path)) {
            JsonObject frame = JsonParser.parseString(line).getAsJsonObject();
            if (!frame.has("records")) continue;
            for (var item : frame.getAsJsonArray("records")) {
                JsonObject row = item.getAsJsonObject();
                if (row.has("id") && invocationId.equals(row.get("id").getAsString())) return row;
            }
        }
        throw new AssertionError("no numeric evidence row for invocation " + invocationId);
    }

    private static long longValue(JsonObject row, String field) {
        return Long.parseLong(row.get(field).getAsString());
    }

    private static int countContaining(List<String> values, String text) {
        return (int) values.stream().filter(value -> value.contains(text)).count();
    }

    private static int countExact(List<String> values, String line) {
        return (int) values.stream().filter(line::equals).count();
    }
}
