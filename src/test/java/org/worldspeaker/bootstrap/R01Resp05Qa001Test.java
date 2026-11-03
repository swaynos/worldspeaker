package org.worldspeaker.bootstrap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.compat.AttentionPolicy;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.InteractionPolicy;
import org.worldspeaker.compat.PeerFacts;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;
import org.worldspeaker.integration.BoundCompanionClient;
import org.worldspeaker.integration.BoundCompanionClientTestAccess;
import org.worldspeaker.integration.CrossPeerFixtureProcess;
import org.worldspeaker.runtime.GameLoopMailbox;
import org.worldspeaker.runtime.ThreadBoundary;
import org.worldspeaker.runtime.ThreadBoundaryTestAccess;
import org.worldspeaker.runtime.TickMetrics;
import org.worldspeaker.runtime.TickMetricsTestAccess;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integrated freshness regressions through real ingress, SmokeConversation, Java client, and TS peer. */
class R01Resp05Qa001Test {
    private static final long T0 = 3_000_000_000L;
    private static final long WALL = 1_793_275_200_000L;
    private static final long FIRST_REPLY = TimeUnit.MILLISECONDS.toNanos(750);
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final UUID PLAYER_C = UUID.fromString("00000000-0000-4000-8000-000000000103");
    private static final String SERVER_EPOCH = "00000000-0000-4000-8000-000000000119";
    private static final ConfiguredBinding BINDING = new ConfiguredBinding(
            "server-test", "world-test", "character-test", "agent-test");

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearSharedServerThreadBinding() {
        ThreadBoundaryTestAccess.clearForTests();
    }

    @TestFactory
    Stream<DynamicTest> lifecycleRetirementAcrossEveryStateAndAttentionCondition() {
        List<MatrixCell> cells = Stream.of(LifecycleCause.values())
                .flatMap(cause -> Stream.of(LifecycleState.values())
                        .flatMap(state -> Stream.of(AttentionMode.values())
                                .map(attention -> new MatrixCell(cause, state, attention))))
                .toList();
        assertEquals(56, cells.size(), "seven causes × two request states × four attention conditions are generated");
        assertEquals(56, cells.stream().map(MatrixCell::caseId).distinct().count(),
                "every generated matrix cell has a unique stable ID");
        return cells.stream().map(cell -> DynamicTest.dynamicTest(cell.displayName(), () -> verifyMatrixCell(cell)));
    }

    private void verifyMatrixCell(MatrixCell cell) throws Exception {
        UUID targetId = cell.state() == LifecycleState.ACTIVE ? PLAYER_A : PLAYER_B;
        UUID otherId = targetId.equals(PLAYER_A) ? PLAYER_B : PLAYER_A;
        AttentionPolicy attentionPolicy = matrixPolicy(cell.attention());
        try (IntegratedHarness harness = new IntegratedHarness(temporaryDirectory, attentionPolicy)) {
            TestPlayer target = harness.game.current(targetId);
            TestPlayer other = harness.game.current(otherId);
            TestPlayer seedPlayer = cell.attention() == AttentionMode.FULL
                    ? harness.game.current(PLAYER_C) : target;
            prepareAttentionCondition(harness, cell, target, seedPlayer);

            InteractionIngress.Invocation targetInvocation;
            JsonObject targetRequest = null;
            InteractionIngress.Invocation blockerInvocation = null;
            JsonObject blockerRequest = null;
            long originalTransportGeneration = -1;
            if (cell.state() == LifecycleState.ACTIVE) {
                targetInvocation = harness.admit(target, "R01 matrix active " + cell.caseId());
                assertEquals(1, harness.conversation.followupRecordCountForTesting(),
                        "the real ingress opened the target's pending follow-up candidate");
                harness.tickAt(T0);
                targetRequest = harness.awaitRequest(targetInvocation);
                assertForwardedIdentity(harness, targetRequest, targetInvocation, targetId);
                originalTransportGeneration = BoundCompanionClientTestAccess
                        .connectionGenerationForTests(harness.companion);
                assertTrue(originalTransportGeneration >= 0);
                assertEquals(originalTransportGeneration,
                        harness.conversation.transportGenerationForTesting(targetInvocation.invocationId()),
                        "the live request retained the Java transport generation that forwarded it");
                harness.publishAcknowledgement(targetInvocation, targetRequest);
            } else {
                blockerInvocation = harness.admit(other, "R01 matrix active blocker " + cell.caseId());
                harness.tickAt(T0);
                blockerRequest = harness.awaitRequest(blockerInvocation);
                assertForwardedIdentity(harness, blockerRequest, blockerInvocation, otherId);

                targetInvocation = harness.admit(target, "R01 matrix pending " + cell.caseId());
                assertEquals(2, harness.conversation.followupRecordCountForTesting(),
                        "both accepted commands own candidates while only the first is active");
                harness.publishAcknowledgement(blockerInvocation, blockerRequest);
                harness.tickAt(T0 + FIRST_REPLY + TimeUnit.MILLISECONDS.toNanos(50));
                assertTrue(harness.game.publishedLines().stream()
                                .anyMatch(line -> line.endsWith("Hello " + target.name)),
                        "pending ingress can receive its local first reply without companion forwarding");
                JsonObject beforeRetirement = readSnapshot(harness);
                assertEquals(List.of(text(blockerRequest, "requestId")),
                        strings(beforeRetirement.getAsJsonArray("peerRequestIds")),
                        "the companion has only the unrelated blocker; the target remains genuinely pending");
                assertEquals(-1, harness.conversation.transportGenerationForTesting(targetInvocation.invocationId()),
                        "unforwarded pending work has no transport generation");
            }

            if (cell.attention() == AttentionMode.FULL) {
                captureJoin(harness, seedPlayer, seedPlayer);
                assertEquals(1, attentionCount(harness, AttentionBuffer.Counter.CAPTURED));
            }
            assertAttentionPreconditionAtCause(harness, cell, targetId, seedPlayer);
            long[] attentionBeforeCause = harness.conversation.attentionForTesting().countersSnapshot();
            long retirementAt = T0 + FIRST_REPLY + TimeUnit.MILLISECONDS.toNanos(50);
            harness.clock.set(retirementAt);
            applyMatrixCause(harness, cell, target, attentionSource(cell.attention(), target));
            harness.tickAt(retirementAt);
            assertAttentionOutcome(harness, cell, targetId, seedPlayer, attentionBeforeCause);

            List<RenderedAttempt> notices = harness.noticeAttempts();
            if (cell.cause().noticeEligible()) {
                assertEquals(1, notices.size(), "one finite lifecycle cause submits one terminal notice");
                assertSameConnection(target.connection, notices.getFirst());
                assertTrue(notices.getFirst().player() == target ||
                                harness.game.current(targetId) == notices.getFirst().player(),
                        "the notice uses the original connection's current entity, never a bystander");
                assertTrue(harness.noticeAttemptsFor(otherId).isEmpty());
            } else {
                assertTrue(notices.isEmpty(), "a departed/replaced connection has no eligible old-request audience");
                assertTrue(harness.noticeAttemptsFor(targetId).isEmpty(),
                        "the same-UUID replacement is not a recipient for the old turn");
            }

            if (cell.state() == LifecycleState.ACTIVE) {
                assertNotNull(targetRequest);
                JsonObject cancel = harness.awaitCancel(targetRequest);
                assertEquals(targetRequest.get("peerSerial").getAsInt(), cancel.get("peerId").getAsInt(),
                        "the current-protocol cancellation arrived on the peer generation that accepted the request");
                assertEquals("dialogue.cancel", text(cancel, "type"));
                assertEquals(DeploymentFacts.PROTOCOL, text(cancel, "protocol"));
                assertEquals(text(targetRequest, "requestId"), text(cancel, "requestId"));
                assertEquals(text(targetRequest, "invocationId"), text(cancel, "invocationId"));
                assertEquals(targetId.toString(), text(cancel, "playerId"));
                assertEquals(List.of("invocationId", "playerId", "protocol", "requestId", "type"),
                        strings(cancel.getAsJsonArray("fields")));
                BoundCompanionClientTestAccess.awaitOwnerBarrier(harness.companion, 3, TimeUnit.SECONDS);
                assertEquals(originalTransportGeneration,
                        BoundCompanionClientTestAccess.connectionGenerationForTests(harness.companion),
                        "the original Java transport generation remained installed through cancellation");
                assertEquals(0, harness.conversation.followupRecordCountForTesting(),
                        "retirement abandoned the target candidate; no old FOLLOWUP can reopen");

                JsonObject cancelledRequest = targetRequest;
                harness.node.command("CHECK " + text(cancelledRequest, "requestId"));
                JsonObject liveness = harness.node.awaitEvent(event ->
                        "liveness".equals(text(event, "kind")) &&
                                text(cancelledRequest, "requestId").equals(text(event, "requestId")), 3);
                assertFalse(liveness.get("live").getAsBoolean());
                assertTrue(liveness.get("cancelledRetained").getAsBoolean());
                assertFalse(liveness.get("requestRetained").getAsBoolean());
                assertEquals(1, harness.inboundTerminal.getCount(),
                        "no terminal from the canceled TypeScript handler reached Java");

                harness.sendFinalAndClear(targetRequest, false);
                harness.awaitAcceptedAckHistory(targetRequest);
                assertEquals(0, harness.conversation.followupRecordCountForTesting(),
                        "the rejected delayed TypeScript final does not recreate the old FOLLOWUP");
                assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);

                JsonObject afterCancel = readSnapshot(harness);
                assertTrue(afterCancel.getAsJsonArray("peerRequestIds").isEmpty());
                assertTrue(strings(afterCancel.getAsJsonArray("peerCancelledRequestIds"))
                        .contains(text(targetRequest, "requestId")));
                assertEquals(List.of(1), integers(afterCancel.getAsJsonArray("cancelPeerIds")));
                assertRetentionBounds(afterCancel);
                assertTerminalEvidence(harness, targetInvocation, cell, true);

                InteractionIngress.Invocation next = harness.admit(other, "R01 matrix next turn " + cell.caseId());
                long nextForwardAt = Math.max(retirementAt + 1, next.capturedAtNanos());
                harness.tickAt(nextForwardAt);
                JsonObject nextRequest = harness.awaitRequest(next);
                assertForwardedIdentity(harness, nextRequest, next, otherId);
                if (cell.attention() == AttentionMode.DISABLED) {
                    assertEquals("disabled", nextRequest.getAsJsonObject("contextStatus").get("state").getAsString());
                }
                harness.publishAcknowledgement(next, nextRequest);
                harness.sendFinalAndClear(nextRequest, true);
                assertTrue(harness.awaitInbound(text(nextRequest, "requestId"), 3));
                harness.awaitAcceptedAckHistory(nextRequest);
                harness.tickAt(harness.clock.get() + 1);
                assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);
                assertEquals(1, harness.game.publishedLines().stream()
                        .filter(line -> line.contains("delayed answer")).count());
                assertEquals(0, harness.conversation.followupRecordCountForTesting());
            } else {
                assertEquals(1, harness.conversation.followupRecordCountForTesting(),
                        "only the unrelated active blocker candidate remains after pending-target retirement");
                JsonObject afterRetirement = readSnapshot(harness);
                assertEquals(List.of(text(blockerRequest, "requestId")),
                        strings(afterRetirement.getAsJsonArray("peerRequestIds")));
                assertTrue(afterRetirement.getAsJsonArray("cancelPeerIds").isEmpty(),
                        "an unforwarded pending target has no synthetic provider cancel");
                assertTerminalEvidence(harness, targetInvocation, cell, false);

                harness.sendFinalAndClear(blockerRequest, true);
                assertTrue(harness.inboundTerminal.await(3, TimeUnit.SECONDS),
                        "the existing live request completes after its locally submitted ACK");
                harness.tickAt(harness.clock.get() + 1);
                harness.awaitAcceptedAckHistory(blockerRequest);
                assertEquals(0, harness.conversation.followupRecordCountForTesting());

                InteractionIngress.Invocation next = harness.admit(other,
                        "R01 matrix next turn after pending " + cell.caseId());
                harness.tickAt(harness.clock.get() + 1);
                JsonObject nextRequest = harness.awaitRequest(next);
                assertForwardedIdentity(harness, nextRequest, next, otherId);
                harness.publishAcknowledgement(next, nextRequest);
                harness.sendFinalAndClear(nextRequest, true);
                assertTrue(harness.awaitInbound(text(nextRequest, "requestId"), 3),
                        "the next technical answer follows accepted ACK history");
                harness.awaitAcceptedAckHistory(nextRequest);
                harness.tickAt(harness.clock.get() + 1);
                assertEquals(2, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);
                assertEquals(2, harness.game.publishedLines().stream()
                        .filter(line -> line.contains("delayed answer")).count());
                assertEquals(0, harness.conversation.followupRecordCountForTesting());
                assertTrue(readSnapshot(harness).getAsJsonArray("peerRequestIds").isEmpty());
            }

            assertRetentionBounds(readSnapshot(harness));
        }
    }

    private void prepareAttentionCondition(IntegratedHarness harness, MatrixCell cell,
                                          TestPlayer target, TestPlayer seedPlayer) {
        switch (cell.attention()) {
            case DISABLED -> {
                assertFalse(harness.conversation.attentionForTesting().enabled());
                assertEquals("disabled", attentionStatus(harness, target.playerId).state());
            }
            case FULL -> {
                assertEquals(0, harness.conversation.attentionForTesting().retainedCount(),
                        "the matrix fills attention only after work is genuinely pending/active");
            }
            case RATE_LIMITED -> {
                captureJoin(harness, target, target);
                captureJoin(harness, target, target);
                assertEquals(1, attentionCount(harness, AttentionBuffer.Counter.CAPTURED));
                assertEquals(1, attentionCount(harness, AttentionBuffer.Counter.RATE_DROP),
                        "the real token bucket rejected a second callback before the lifecycle under test");
                assertTrue(attentionStatus(harness, target.playerId).reasons().contains("rate"));
            }
            case SOURCE_FAILURE -> {
                assertTrue(harness.conversation.attentionForTesting().enabled());
                assertEquals(0, attentionCount(harness, AttentionBuffer.Counter.SOURCE_UNAVAILABLE),
                        "the configured source-failure policy is not fabricated when snapshot loss has no event source");
            }
        }
    }

    private void assertAttentionPreconditionAtCause(IntegratedHarness harness, MatrixCell cell,
                                                    UUID targetId, TestPlayer seedPlayer) {
        AttentionBuffer buffer = harness.conversation.attentionForTesting();
        switch (cell.attention()) {
            case DISABLED -> {
                assertFalse(buffer.enabled());
                assertEquals("disabled", buffer.select(targetId, harness.clock.get(),
                        AttentionBuffer.MAX_BATCH_EVENTS, AttentionBuffer.MAX_BATCH_BYTES).status().state());
            }
            case FULL -> assertEquals(1, buffer.retainedCount(),
                    "attention remains at the real global retention limit immediately before the cause");
            case RATE_LIMITED -> assertTrue(attentionCount(harness, AttentionBuffer.Counter.RATE_DROP) >= 1,
                    "the real limiter has already rejected optional capture for this player");
            case SOURCE_FAILURE -> {
                if (cell.cause() == LifecycleCause.SNAPSHOT_UNAVAILABLE) {
                    assertEquals(0, attentionCount(harness, AttentionBuffer.Counter.SOURCE_UNAVAILABLE),
                            "optional event capture is N/A before an unavailable required snapshot");
                }
            }
        }
        if (cell.attention() == AttentionMode.FULL) {
            assertEquals(1, buffer.select(seedPlayer.playerId, harness.clock.get(),
                    AttentionBuffer.MAX_BATCH_EVENTS, AttentionBuffer.MAX_BATCH_BYTES).events().size(),
                    "the retained slot is occupied, not merely configured with a one-event policy");
        }
    }

    private void assertAttentionOutcome(IntegratedHarness harness, MatrixCell cell, UUID targetId,
                                        TestPlayer seedPlayer, long[] before) {
        AttentionBuffer buffer = harness.conversation.attentionForTesting();
        long[] after = buffer.countersSnapshot();
        if (cell.cause() == LifecycleCause.SNAPSHOT_UNAVAILABLE) {
            assertEquals(before[AttentionBuffer.Counter.CAPTURED.ordinal()],
                    after[AttentionBuffer.Counter.CAPTURED.ordinal()],
                    "an unavailable required snapshot has no optional event to capture");
            assertEquals(before[AttentionBuffer.Counter.RATE_DROP.ordinal()],
                    after[AttentionBuffer.Counter.RATE_DROP.ordinal()],
                    "an unavailable required snapshot has no optional capture to rate-limit");
            assertEquals(before[AttentionBuffer.Counter.EVICTED.ordinal()],
                    after[AttentionBuffer.Counter.EVICTED.ordinal()],
                    "an unavailable required snapshot has no optional event to evict into a full buffer");
            long mandatoryLoss = buffer.enabled() ? 1 : 0;
            assertEquals(before[AttentionBuffer.Counter.SOURCE_UNAVAILABLE.ordinal()] + mandatoryLoss,
                    after[AttentionBuffer.Counter.SOURCE_UNAVAILABLE.ordinal()],
                    "enabled attention may retain one mandatory snapshot-loss status; this is not optional event capture");
        }
        switch (cell.attention()) {
            case DISABLED -> {
                assertEquals(0, after[AttentionBuffer.Counter.CAPTURED.ordinal()]);
                assertEquals("disabled", attentionStatus(harness, targetId).state());
            }
            case FULL -> {
                assertEquals(1, buffer.retainedCount());
                if (cell.cause() == LifecycleCause.SNAPSHOT_UNAVAILABLE) {
                    assertEquals(before[AttentionBuffer.Counter.CAPTURED.ordinal()],
                            after[AttentionBuffer.Counter.CAPTURED.ordinal()],
                            "snapshot-unavailable carries no optional event payload to capture");
                    assertEquals(before[AttentionBuffer.Counter.EVICTED.ordinal()],
                            after[AttentionBuffer.Counter.EVICTED.ordinal()],
                            "the no-event sample cannot evict the genuinely full buffer");
                } else {
                    assertTrue(after[AttentionBuffer.Counter.EVICTED.ordinal()] >
                                    before[AttentionBuffer.Counter.EVICTED.ordinal()],
                            "the lifecycle callback/sample reached capture while retention was genuinely full");
                    assertTrue(attentionStatus(harness, seedPlayer.playerId).reasons().contains("capacity"));
                }
            }
            case RATE_LIMITED -> {
                if (cell.cause() == LifecycleCause.SNAPSHOT_UNAVAILABLE) {
                    assertEquals(before[AttentionBuffer.Counter.RATE_DROP.ordinal()],
                            after[AttentionBuffer.Counter.RATE_DROP.ordinal()],
                            "snapshot-unavailable has no optional event capture to rate-limit");
                    assertTrue(after[AttentionBuffer.Counter.SOURCE_UNAVAILABLE.ordinal()] >=
                                    before[AttentionBuffer.Counter.SOURCE_UNAVAILABLE.ordinal()],
                            "mandatory snapshot failure is recorded independently of the optional rate bucket");
                } else {
                    assertTrue(after[AttentionBuffer.Counter.RATE_DROP.ordinal()] >
                                    before[AttentionBuffer.Counter.RATE_DROP.ordinal()],
                            "the lifecycle's actual optional event was rejected by the exhausted token bucket");
                    assertTrue(attentionStatus(harness, targetId).reasons().contains("rate"));
                }
            }
            case SOURCE_FAILURE -> {
                if (cell.cause() == LifecycleCause.SNAPSHOT_UNAVAILABLE) {
                    assertEquals(before[AttentionBuffer.Counter.SOURCE_UNAVAILABLE.ordinal()] + 1,
                            after[AttentionBuffer.Counter.SOURCE_UNAVAILABLE.ordinal()],
                            "the required snapshot-loss status is recorded; optional source-failure capture is N/A");
                } else {
                    assertTrue(after[AttentionBuffer.Counter.SOURCE_UNAVAILABLE.ordinal()] >
                                    before[AttentionBuffer.Counter.SOURCE_UNAVAILABLE.ordinal()],
                            "the lifecycle's actual optional event factory failed on its invalid source");
                }
                assertTrue(attentionStatus(harness, targetId).reasons().contains("source_unavailable"));
            }
        }
    }

    private void applyMatrixCause(IntegratedHarness harness, MatrixCell cell, TestPlayer original,
                                  Object attentionSource) {
        switch (cell.cause()) {
            case DEATH -> {
                original.alive = false;
                harness.callback(TrustedLifecycleContext.Cause.DEATH, original, original, original.connection,
                        AttentionEvent.EventKind.DIED, original.dimension, AttentionEvent.Details.empty(),
                        attentionSource);
            }
            case RESPAWN_ENTITY_REPLACEMENT -> {
                TestPlayer replacement = harness.game.replaceEntity(original.playerId, original.connection);
                assertTrue(replacement != original);
                harness.callback(TrustedLifecycleContext.Cause.RESPAWN, replacement, original, original.connection,
                        AttentionEvent.EventKind.RESPAWNED, replacement.dimension,
                        AttentionEvent.Details.respawn(true), attentionSource);
            }
            case DIMENSION_ROUND_TRIP -> {
                String origin = original.dimension;
                original.dimension = "minecraft:the_nether";
                harness.callback(TrustedLifecycleContext.Cause.DIMENSION_CHANGED, original, original,
                        original.connection, AttentionEvent.EventKind.DIMENSION_CHANGED, original.dimension,
                        AttentionEvent.Details.dimension(origin), attentionSource);
                origin = original.dimension;
                original.dimension = "minecraft:overworld";
                harness.callback(TrustedLifecycleContext.Cause.DIMENSION_CHANGED, original, original,
                        original.connection, AttentionEvent.EventKind.DIMENSION_CHANGED, original.dimension,
                        AttentionEvent.Details.dimension(origin), attentionSource);
                assertEquals("minecraft:overworld", original.dimension);
            }
            case DISCONNECT_SAME_CONNECTION -> {
                TestPlayer departed = harness.game.disconnect(original.playerId);
                assertTrue(departed == original);
                assertTrue(departed.connection == original.connection,
                        "disconnect retains the exact connection identity held by the admitted request");
                harness.callback(TrustedLifecycleContext.Cause.DISCONNECT, null, departed, departed.connection,
                        AttentionEvent.EventKind.LEFT, departed.dimension, AttentionEvent.Details.empty(),
                        attentionSource);
            }
            case SAME_UUID_REJOIN -> {
                TestPlayer replacement = harness.game.rejoin(original.playerId, "TesterRejoined");
                assertTrue(replacement.playerId.equals(original.playerId));
                assertTrue(replacement.connection != original.connection,
                        "the same UUID rejoin has a different authenticated connection");
                harness.callback(TrustedLifecycleContext.Cause.PLAYER_JOINED, replacement, original,
                        original.connection, AttentionEvent.EventKind.JOINED, replacement.dimension,
                        AttentionEvent.Details.empty(), attentionSource);
            }
            case MOVEMENT_RETURN -> {
                PlayerFreshness.Observation origin = harness.game.observation(original);
                original.blockX++;
                harness.sample(TrustedLifecycleContext.Cause.BLOCK_MOVEMENT, original,
                        harness.game.observation(original), AttentionEvent.EventKind.MOVED,
                        AttentionEvent.Details.moved(), attentionSource);
                original.blockX = origin.blockX();
                harness.sample(TrustedLifecycleContext.Cause.BLOCK_MOVEMENT, original,
                        harness.game.observation(original), AttentionEvent.EventKind.MOVED,
                        AttentionEvent.Details.moved(), attentionSource);
                assertEquals(origin.blockX(), original.blockX,
                        "the actual sampled location returned to its admission block");
            }
            case SNAPSHOT_UNAVAILABLE -> harness.sample(TrustedLifecycleContext.Cause.SNAPSHOT_UNAVAILABLE,
                    original, null, null, null, null);
        }
    }

    private Object attentionSource(AttentionMode mode, TestPlayer player) {
        return mode == AttentionMode.SOURCE_FAILURE ? new Object() : player;
    }

    private void captureJoin(IntegratedHarness harness, TestPlayer player, Object attentionSource) {
        harness.callback(TrustedLifecycleContext.Cause.PLAYER_JOINED, player, player, player.connection,
                AttentionEvent.EventKind.JOINED, player.dimension, AttentionEvent.Details.empty(), attentionSource);
    }

    private AttentionPolicy matrixPolicy(AttentionMode mode) {
        Set<AttentionPolicy.Subscription> subscriptions = Set.of(
                AttentionPolicy.Subscription.PLAYER_CONNECTION,
                AttentionPolicy.Subscription.PLAYER_DIMENSION,
                AttentionPolicy.Subscription.PLAYER_DEATH,
                AttentionPolicy.Subscription.PLAYER_RESPAWN,
                AttentionPolicy.Subscription.PLAYER_MOVEMENT);
        return switch (mode) {
            case DISABLED -> AttentionPolicy.disabled();
            case FULL -> new AttentionPolicy(subscriptions, 1, 30, 8192, 16, 4);
            case RATE_LIMITED -> new AttentionPolicy(subscriptions, 16, 30, 8192, 1, 1);
            case SOURCE_FAILURE -> new AttentionPolicy(subscriptions, 16, 30, 8192, 16, 4);
        };
    }

    private AttentionBuffer.ContextStatus attentionStatus(IntegratedHarness harness, UUID playerId) {
        return harness.conversation.attentionForTesting().select(playerId, harness.clock.get(),
                AttentionBuffer.MAX_BATCH_EVENTS, AttentionBuffer.MAX_BATCH_BYTES).status();
    }

    private long attentionCount(IntegratedHarness harness, AttentionBuffer.Counter counter) {
        return harness.conversation.attentionForTesting().countersSnapshot()[counter.ordinal()];
    }

    private void assertForwardedIdentity(IntegratedHarness harness, JsonObject request,
                                         InteractionIngress.Invocation invocation, UUID playerId) {
        assertEquals(DeploymentFacts.PROTOCOL, text(request, "protocol"));
        assertEquals(harness.config.abilityPolicy().revision(), request.get("abilityPolicyRevision").getAsLong());
        assertEquals(invocation.invocationId().toString(), text(request, "invocationId"));
        assertEquals(playerId.toString(), text(request, "playerId"));
        assertEquals("command", text(request, "inputKind"));
        assertEquals(1, request.get("peerSerial").getAsInt());
    }

    private void assertTerminalEvidence(IntegratedHarness harness, InteractionIngress.Invocation invocation,
                                        MatrixCell cell, boolean active) throws Exception {
        JsonObject row = harness.exportRow(invocation, cell.fileSuffix() + ".jsonl");
        assertEquals("CANCELLED", text(row, "terminalOutcome"));
        assertEquals(cell.cause().terminalCause(), text(row, "terminalCause"));
        if (cell.cause().noticeEligible()) {
            assertEquals("SUBMITTED", text(row, "terminalDeliveryResult"));
            assertEquals("SUBMITTED", text(row, "retirementNoticeResult"));
        } else {
            assertEquals("NO_ATTEMPT", text(row, "terminalDeliveryResult"));
            assertEquals("NO_AUDIENCE", text(row, "retirementNoticeResult"));
        }
        if (active) {
            assertEquals("SUBMITTED", text(row, "localCancelStatus"));
            assertEquals("SUBMITTED", text(row, "localCancelSendResult"));
            assertFalse(row.get("providerCancel").getAsBoolean(),
                    "a successful local cancellation does not claim provider cancellation");
        } else {
            assertEquals("NOT_ATTEMPTED", text(row, "localCancelStatus"));
            assertTrue(row.get("providerCancel").isJsonNull(),
                    "a request never forwarded has no provider-cancellation result");
        }
    }

    private void assertRetentionBounds(JsonObject snapshot) {
        assertTrue(snapshot.getAsJsonArray("peerRequestIds").size() <= 16,
                "the real TypeScript peer never exceeds the 16-live-request bound");
        assertTrue(snapshot.get("retainedEventCount").getAsInt() <= 256);
        assertTrue(snapshot.get("retainedInvocationCount").getAsInt() <= 256);
        assertTrue(snapshot.get("retainedRequestCount").getAsInt() <= 256);
    }

    private static JsonObject readSnapshot(IntegratedHarness harness) throws Exception {
        harness.node.command("SNAPSHOT");
        return harness.node.awaitEvent(event -> "snapshot".equals(text(event, "kind")), 3);
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void deathRespawnDimensionRoundTripMovementReturnAndSnapshotFailureRetireTheRealActiveRequest() throws Exception {
        verifyActiveRetirement("death", "DEATH", harness -> {
            TestPlayer player = harness.game.current(PLAYER_A);
            player.alive = false;
            harness.callback(TrustedLifecycleContext.Cause.DEATH, player, player, player.connection,
                    AttentionEvent.EventKind.DIED, player.dimension, AttentionEvent.Details.empty());
        });

        verifyActiveRetirement("respawn-entity-replacement", "RESPAWN", harness -> {
            TestPlayer old = harness.game.current(PLAYER_A);
            TestPlayer replacement = harness.game.replaceEntity(PLAYER_A, old.connection);
            harness.callback(TrustedLifecycleContext.Cause.RESPAWN, replacement, old, old.connection,
                    AttentionEvent.EventKind.RESPAWNED, replacement.dimension,
                    AttentionEvent.Details.respawn(true));
        });

        verifyActiveRetirement("dimension-round-trip", "DIMENSION_CHANGED", harness -> {
            TestPlayer player = harness.game.current(PLAYER_A);
            String origin = player.dimension;
            player.dimension = "minecraft:the_nether";
            harness.callback(TrustedLifecycleContext.Cause.DIMENSION_CHANGED, player, player, player.connection,
                    AttentionEvent.EventKind.DIMENSION_CHANGED, player.dimension,
                    AttentionEvent.Details.dimension(origin));
            origin = player.dimension;
            player.dimension = "minecraft:overworld";
            harness.callback(TrustedLifecycleContext.Cause.DIMENSION_CHANGED, player, player, player.connection,
                    AttentionEvent.EventKind.DIMENSION_CHANGED, player.dimension,
                    AttentionEvent.Details.dimension(origin));
        });

        verifyActiveRetirement("observed-movement-return", "BLOCK_MOVEMENT", harness -> {
            TestPlayer player = harness.game.current(PLAYER_A);
            PlayerFreshness.Observation origin = harness.game.observation(player);
            player.blockX++;
            harness.sample(TrustedLifecycleContext.Cause.BLOCK_MOVEMENT, player,
                    harness.game.observation(player), AttentionEvent.EventKind.MOVED,
                    AttentionEvent.Details.moved());
            player.blockX = origin.blockX();
            harness.sample(TrustedLifecycleContext.Cause.BLOCK_MOVEMENT, player,
                    harness.game.observation(player), AttentionEvent.EventKind.MOVED,
                    AttentionEvent.Details.moved());
        });

        verifyActiveRetirement("required-snapshot-failure", "SNAPSHOT_UNAVAILABLE", harness ->
                harness.sample(TrustedLifecycleContext.Cause.SNAPSHOT_UNAVAILABLE,
                        harness.game.current(PLAYER_A), null, null, null));
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void disconnectThenSameUuidRejoinCannotReceiveTheOldRequestNoticeOrFinal() throws Exception {
        try (IntegratedHarness harness = new IntegratedHarness(temporaryDirectory, AttentionPolicy.disabled())) {
            TestPlayer original = harness.game.current(PLAYER_A);
            InteractionIngress.Invocation invocation = harness.admit(original, "Disconnect A while its turn is held.");
            harness.tickAt(T0);
            JsonObject request = harness.awaitRequest(invocation);
            harness.publishAcknowledgement(invocation, request);

            harness.game.disconnect(PLAYER_A);
            harness.callback(TrustedLifecycleContext.Cause.DISCONNECT, null, original, original.connection,
                    AttentionEvent.EventKind.LEFT, original.dimension, AttentionEvent.Details.empty());
            harness.tickAt(T0 + FIRST_REPLY + TimeUnit.MILLISECONDS.toNanos(50));
            JsonObject cancel = harness.awaitCancel(request);
            assertEquals(1, cancel.get("peerId").getAsInt(), "disconnect cancellation stays on the original peer generation");

            TestPlayer rejoined = harness.game.rejoin(PLAYER_A, "TesterRejoined");
            harness.callback(TrustedLifecycleContext.Cause.PLAYER_JOINED, rejoined, rejoined, rejoined.connection,
                    AttentionEvent.EventKind.JOINED, rejoined.dimension, AttentionEvent.Details.empty());
            assertEquals(0, harness.conversation.followupRecordCountForTesting());

            harness.sendFinalAndClear(request, false);
            int noticeCount = harness.noticeAttempts().size();
            harness.tickAt(T0 + TimeUnit.SECONDS.toNanos(1));
            assertEquals(noticeCount, harness.noticeAttempts().size(), "a late final and same-UUID login add no old notice");
            assertTrue(harness.noticeAttempts().isEmpty(), "disconnect has no old or new login audience");
            assertEquals(0, harness.game.publishedLines().stream().filter(line -> line.contains("delayed answer")).count());

            JsonObject row = harness.exportRow(invocation, "disconnect-same-uuid-rejoin.jsonl");
            assertEquals("CANCELLED", row.get("terminalOutcome").getAsString());
            assertEquals("DISCONNECT", row.get("terminalCause").getAsString());
            assertEquals("NO_ATTEMPT", row.get("terminalDeliveryResult").getAsString());
            assertEquals("NO_AUDIENCE", row.get("retirementNoticeResult").getAsString());
            assertEquals("SUBMITTED", row.get("localCancelSendResult").getAsString());
            assertFalse(row.get("providerCancel").getAsBoolean());
        }
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void sameUuidJoinAloneRetiresTheOldRequestWithoutNotifyingTheReplacementConnection() throws Exception {
        try (IntegratedHarness harness = new IntegratedHarness(temporaryDirectory, AttentionPolicy.disabled())) {
            TestPlayer original = harness.game.current(PLAYER_A);
            InteractionIngress.Invocation invocation = harness.admit(original, "Rejoin A while its turn is held.");
            harness.tickAt(T0);
            JsonObject request = harness.awaitRequest(invocation);
            harness.publishAcknowledgement(invocation, request);

            TestPlayer rejoined = harness.game.rejoin(PLAYER_A, "TesterRejoined");
            harness.callback(TrustedLifecycleContext.Cause.PLAYER_JOINED, rejoined, original, original.connection,
                    AttentionEvent.EventKind.JOINED, rejoined.dimension, AttentionEvent.Details.empty(), rejoined);
            harness.tickAt(T0 + FIRST_REPLY + TimeUnit.MILLISECONDS.toNanos(50));

            JsonObject cancel = harness.awaitCancel(request);
            assertEquals(1, cancel.get("peerId").getAsInt(), "join-triggered cancellation stays on the original peer");
            assertEquals(0, harness.conversation.followupRecordCountForTesting());
            assertTrue(harness.noticeAttempts().isEmpty(), "the departed original connection cannot receive the notice");
            assertTrue(harness.noticeAttemptsFor(PLAYER_A).isEmpty(), "the same-UUID replacement cannot receive the old notice");

            harness.node.command("CHECK " + request.get("requestId").getAsString());
            JsonObject liveness = harness.node.awaitEvent(event -> "liveness".equals(text(event, "kind")) &&
                    request.get("requestId").getAsString().equals(text(event, "requestId")), 3);
            assertFalse(liveness.get("live").getAsBoolean());
            assertTrue(liveness.get("cancelledRetained").getAsBoolean());
            harness.sendFinalAndClear(request, false);
            harness.awaitAcceptedAckHistory(request);
            harness.tickAt(T0 + TimeUnit.SECONDS.toNanos(1));
            assertTrue(harness.noticeAttempts().isEmpty(), "the rejected delayed final cannot create a second notice");
            assertEquals(0, harness.game.publishedLines().stream().filter(line -> line.contains("delayed answer")).count());

            JsonObject row = harness.exportRow(invocation, "join-same-uuid-no-replacement-audience.jsonl");
            assertEquals("CANCELLED", row.get("terminalOutcome").getAsString());
            assertEquals("PLAYER_JOINED", row.get("terminalCause").getAsString());
            assertEquals("NO_AUDIENCE", row.get("retirementNoticeResult").getAsString());
            assertEquals("SUBMITTED", row.get("localCancelSendResult").getAsString());
            assertFalse(row.get("providerCancel").getAsBoolean());
        }
    }

    @Test
    @Timeout(value = 75, unit = TimeUnit.SECONDS)
    void attentionDisabledFullRateLimitedAndSourceFailureNeverSuppressLifecycleInvalidation() throws Exception {
        verifyAttention("disabled", AttentionPolicy.disabled(), AttentionMode.DISABLED);
        verifyAttention("full", policy(1, 16), AttentionMode.FULL);
        verifyAttention("rate-limited", policy(16, 1), AttentionMode.RATE_LIMITED);
        verifyAttention("source-failure", policy(16, 16), AttentionMode.SOURCE_FAILURE);
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    void validPendingRetirementIsNotForwardedAndOnlyTheActiveOriginalConnectionGetsItsNotice() throws Exception {
        try (IntegratedHarness harness = new IntegratedHarness(temporaryDirectory, AttentionPolicy.disabled())) {
            TestPlayer activePlayer = harness.game.current(PLAYER_A);
            TestPlayer pendingPlayer = harness.game.current(PLAYER_B);
            InteractionIngress.Invocation active = harness.admit(activePlayer, "Keep A active.");
            harness.tickAt(T0);
            JsonObject activeRequest = harness.awaitRequest(active);
            InteractionIngress.Invocation pending = harness.admit(pendingPlayer, "Retire B before forwarding.");
            harness.tickAt(T0 + FIRST_REPLY + TimeUnit.MILLISECONDS.toNanos(50));
            assertEquals(1, harness.game.publishedLines().stream().filter(line -> line.endsWith("Hello TesterTwo")).count());

            harness.node.command("SNAPSHOT");
            JsonObject before = harness.node.awaitEvent(event -> "snapshot".equals(text(event, "kind")), 3);
            assertEquals(List.of(activeRequest.get("requestId").getAsString()), strings(before.getAsJsonArray("peerRequestIds")),
                    "pending B is not sent to the companion before it is active");

            pendingPlayer.alive = false;
            harness.callback(TrustedLifecycleContext.Cause.DEATH, pendingPlayer, pendingPlayer,
                    pendingPlayer.connection, AttentionEvent.EventKind.DIED, pendingPlayer.dimension,
                    AttentionEvent.Details.empty());
            harness.tickAt(T0 + FIRST_REPLY + TimeUnit.MILLISECONDS.toNanos(100));
            assertEquals(0, harness.nodeCancelCount(), "pending B has no forwarded provider request to cancel");
            assertEquals(1, harness.noticeAttempts().size());
            assertSameConnection(pendingPlayer.connection, harness.noticeAttempts().getFirst());

            activePlayer.alive = false;
            harness.callback(TrustedLifecycleContext.Cause.DEATH, activePlayer, activePlayer,
                    activePlayer.connection, AttentionEvent.EventKind.DIED, activePlayer.dimension,
                    AttentionEvent.Details.empty());
            harness.tickAt(T0 + FIRST_REPLY + TimeUnit.MILLISECONDS.toNanos(150));
            JsonObject cancel = harness.awaitCancel(activeRequest);
            assertEquals(1, cancel.get("peerId").getAsInt());
            assertEquals(2, harness.noticeAttempts().size());
            assertSameConnection(activePlayer.connection, harness.noticeAttempts().getLast());

            harness.node.command("CHECK " + activeRequest.get("requestId").getAsString());
            JsonObject liveness = harness.node.awaitEvent(event -> "liveness".equals(text(event, "kind")), 3);
            assertFalse(liveness.get("live").getAsBoolean());
            assertTrue(liveness.get("cancelledRetained").getAsBoolean());
            harness.sendFinalAndClear(activeRequest, false);

            Path evidence = harness.exportSnapshot("pending-and-active-retirement.jsonl");
            JsonObject pendingRow = evidenceRow(evidence, pending.invocationId());
            assertEquals("CANCELLED", pendingRow.get("terminalOutcome").getAsString());
            assertEquals("DEATH", pendingRow.get("terminalCause").getAsString());
            assertEquals("NOT_ATTEMPTED", pendingRow.get("localCancelStatus").getAsString());
            assertTrue(pendingRow.get("providerCancel").isJsonNull(),
                    "an unforwarded pending request has no provider-cancellation result");
            JsonObject activeRow = evidenceRow(evidence, active.invocationId());
            assertEquals("CANCELLED", activeRow.get("terminalOutcome").getAsString());
            assertEquals("DEATH", activeRow.get("terminalCause").getAsString());
            assertEquals("SUBMITTED", activeRow.get("terminalDeliveryResult").getAsString());
            assertFalse(activeRow.get("providerCancel").getAsBoolean());
        }
    }

    private void verifyActiveRetirement(String name, String expectedCause, LifecycleAction action) throws Exception {
        try (IntegratedHarness harness = new IntegratedHarness(temporaryDirectory, AttentionPolicy.disabled())) {
            TestPlayer original = harness.game.current(PLAYER_A);
            InteractionIngress.Invocation invocation = harness.admit(original, "Lifecycle cause: " + name);
            assertEquals(1, harness.conversation.followupRecordCountForTesting(),
                    "the admitted command owns a real pending follow-up candidate before its answer");
            harness.tickAt(T0);
            JsonObject request = harness.awaitRequest(invocation);
            assertEquals("command", request.get("inputKind").getAsString());
            assertEquals(1, harness.nodeReadyPeerSerial());
            harness.publishAcknowledgement(invocation, request);

            action.apply(harness);
            harness.tickAt(T0 + FIRST_REPLY + TimeUnit.MILLISECONDS.toNanos(50));
            JsonObject cancel = harness.awaitCancel(request);
            assertEquals(1, cancel.get("peerId").getAsInt(), "the cancellation targets the request's original peer");
            assertEquals(request.get("invocationId").getAsString(), cancel.get("invocationId").getAsString());
            assertEquals(0, harness.conversation.followupRecordCountForTesting(),
                    "retirement abandons the answer candidate instead of opening FOLLOWUP");

            List<RenderedAttempt> notices = harness.noticeAttempts();
            assertEquals(1, notices.size(), "one lifecycle cause produces one bounded terminal notice");
            assertSameConnection(original.connection, notices.getFirst());
            assertTrue(notices.getFirst().player() == original ||
                            harness.game.current(PLAYER_A) == notices.getFirst().player(),
                    "the notice is submitted only through the original connection's current player object");
            assertTrue(harness.noticeAttemptsFor(PLAYER_B).isEmpty(), "a bystander receives no terminal notice");

            harness.node.command("CHECK " + request.get("requestId").getAsString());
            JsonObject liveness = harness.node.awaitEvent(event -> "liveness".equals(text(event, "kind")) &&
                    request.get("requestId").getAsString().equals(text(event, "requestId")), 3);
            assertFalse(liveness.get("live").getAsBoolean());
            assertTrue(liveness.get("cancelledRetained").getAsBoolean(), "the TS peer retains the canceled identity");
            assertFalse(liveness.get("requestRetained").getAsBoolean());
            harness.sendFinalAndClear(request, false);
            harness.awaitAcceptedAckHistory(request);

            int noticeCount = harness.noticeAttempts().size();
            int lineCount = harness.game.publishedLines().size();
            harness.tickAt(T0 + TimeUnit.SECONDS.toNanos(1));
            assertEquals(noticeCount, harness.noticeAttempts().size(), "duplicate callback/late final cannot add a terminal");
            assertEquals(lineCount, harness.game.publishedLines().size(), "late final cannot reverse or replay the notice");

            harness.node.command("SNAPSHOT");
            JsonObject snapshot = harness.node.awaitEvent(event -> "snapshot".equals(text(event, "kind")), 3);
            assertEquals(1, snapshot.get("readyPeerSerial").getAsInt());
            assertEquals(List.of(1), integers(snapshot.getAsJsonArray("cancelPeerIds")));
            assertTrue(snapshot.get("peerRequestIds").getAsJsonArray().isEmpty());
            assertTrue(snapshot.get("peerCancelledRequestIds").getAsJsonArray().contains(request.get("requestId")));
            assertTrue(snapshot.get("retainedEventCount").getAsInt() <= 256);
            assertTrue(snapshot.get("retainedInvocationCount").getAsInt() <= 256);
            assertTrue(snapshot.get("retainedRequestCount").getAsInt() <= 256);

            JsonObject row = harness.exportRow(invocation, name + ".jsonl");
            assertEquals("CANCELLED", row.get("terminalOutcome").getAsString());
            assertEquals(expectedCause, row.get("terminalCause").getAsString());
            assertEquals("SUBMITTED", row.get("terminalDeliveryResult").getAsString());
            assertEquals("SUBMITTED", row.get("retirementNoticeResult").getAsString());
            assertEquals("SUBMITTED", row.get("localCancelStatus").getAsString());
            assertEquals("SUBMITTED", row.get("localCancelSendResult").getAsString());
            assertFalse(row.get("providerCancel").getAsBoolean(), "local cancellation never claims provider cancellation");
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()],
                    "a rejected late final never increments delivered answers");
        }
    }

    private void verifyAttention(String name, AttentionPolicy attentionPolicy, AttentionMode mode) throws Exception {
        try (IntegratedHarness harness = new IntegratedHarness(temporaryDirectory, attentionPolicy)) {
            if (mode == AttentionMode.RATE_LIMITED) {
                TestPlayer bystander = harness.game.current(PLAYER_B);
                harness.callback(TrustedLifecycleContext.Cause.PLAYER_JOINED, bystander, bystander,
                        bystander.connection, AttentionEvent.EventKind.JOINED, bystander.dimension,
                        AttentionEvent.Details.empty());
            }
            TestPlayer player = harness.game.current(PLAYER_A);
            InteractionIngress.Invocation canceled = harness.admit(player, "Attention mode: " + name);
            harness.tickAt(T0);
            JsonObject request = harness.awaitRequest(canceled);
            harness.publishAcknowledgement(canceled, request);

            fireAttentionCause(harness, mode, player);
            harness.tickAt(T0 + FIRST_REPLY + TimeUnit.MILLISECONDS.toNanos(50));
            JsonObject cancel = harness.awaitCancel(request);
            assertEquals(1, cancel.get("peerId").getAsInt());
            assertEquals(0, harness.conversation.followupRecordCountForTesting());
            harness.sendFinalAndClear(request, false);
            harness.awaitAcceptedAckHistory(request);

            InteractionIngress.Invocation after = harness.admit(player, "Fresh request after " + name);
            harness.tickAt(T0 + TimeUnit.SECONDS.toNanos(1));
            JsonObject freshRequest = harness.awaitRequest(after);
            JsonObject status = freshRequest.getAsJsonObject("contextStatus");
            switch (mode) {
                case DISABLED -> {
                    assertEquals("disabled", status.get("state").getAsString());
                    assertTrue(freshRequest.getAsJsonArray("contextEventKinds").isEmpty());
                }
                case FULL -> {
                    assertTrue(strings(status.getAsJsonArray("reasons")).contains("capacity"), status.toString());
                    assertEquals(1, freshRequest.getAsJsonArray("contextEventKinds").size());
                }
                case RATE_LIMITED -> {
                    assertTrue(strings(status.getAsJsonArray("reasons")).contains("rate"), status.toString());
                    assertTrue(freshRequest.getAsJsonArray("contextEventKinds").isEmpty());
                }
                case SOURCE_FAILURE -> {
                    assertTrue(strings(status.getAsJsonArray("reasons")).contains("source_unavailable"), status.toString());
                    assertTrue(freshRequest.getAsJsonArray("contextEventKinds").isEmpty());
                }
            }

            harness.node.command("FINAL " + freshRequest.get("requestId").getAsString());
            JsonObject finalResult = harness.node.awaitEvent(event -> "final".equals(text(event, "kind")) &&
                    freshRequest.get("requestId").getAsString().equals(text(event, "requestId")), 3);
            assertTrue(finalResult.get("accepted").getAsBoolean(), "a fresh request still receives a final");
            assertTrue(harness.inboundTerminal.await(3, TimeUnit.SECONDS), "the fresh final reaches Java's actual sink");
            harness.tickAt(T0 + TimeUnit.SECONDS.toNanos(1) + 1);
            assertEquals(1, harness.game.publishedLines().stream().filter(line -> line.contains("delayed answer")).count(),
                    "the new request accepts the fixture final while the canceled final remains rejected");
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);
            assertEquals(0, harness.conversation.followupRecordCountForTesting(),
                    "a reply cannot open a follow-up without current server-side Fabric context");
            JsonObject row = harness.exportRow(canceled, name + "-cancelled.jsonl");
            assertEquals("CANCELLED", row.get("terminalOutcome").getAsString());
            assertEquals("DIMENSION_CHANGED", row.get("terminalCause").getAsString());
            assertEquals("SUBMITTED", row.get("terminalDeliveryResult").getAsString());
            assertFalse(row.get("providerCancel").getAsBoolean());
        }
    }

    private static void fireAttentionCause(IntegratedHarness harness, AttentionMode mode, TestPlayer player) {
        switch (mode) {
            case DISABLED, RATE_LIMITED, SOURCE_FAILURE -> {
                String origin = player.dimension;
                player.dimension = "minecraft:the_nether";
                Object source = mode == AttentionMode.SOURCE_FAILURE ? new Object() : player;
                harness.callback(TrustedLifecycleContext.Cause.DIMENSION_CHANGED, player, player,
                        player.connection, AttentionEvent.EventKind.DIMENSION_CHANGED, player.dimension,
                        AttentionEvent.Details.dimension(origin), source);
            }
            case FULL -> {
                String origin = player.dimension;
                player.dimension = "minecraft:the_nether";
                harness.callback(TrustedLifecycleContext.Cause.DIMENSION_CHANGED, player, player,
                        player.connection, AttentionEvent.EventKind.DIMENSION_CHANGED, player.dimension,
                        AttentionEvent.Details.dimension(origin));
                origin = player.dimension;
                player.dimension = "minecraft:overworld";
                harness.callback(TrustedLifecycleContext.Cause.DIMENSION_CHANGED, player, player,
                        player.connection, AttentionEvent.EventKind.DIMENSION_CHANGED, player.dimension,
                        AttentionEvent.Details.dimension(origin));
            }
        }
    }

    private static AttentionPolicy policy(int maxEvents, int maxEventsPerSecond) {
        return new AttentionPolicy(Set.of(AttentionPolicy.Subscription.PLAYER_CONNECTION,
                AttentionPolicy.Subscription.PLAYER_DIMENSION), maxEvents, 30, 8192,
                maxEventsPerSecond, Math.min(maxEventsPerSecond, 4));
    }

    private static void assertSameConnection(Object expected, RenderedAttempt attempt) {
        assertTrue(attempt.connection() == expected, "the notice must retain the request's original connection identity");
    }

    private static String text(JsonObject object, String field) {
        return object.get(field).getAsString();
    }

    private static List<String> strings(com.google.gson.JsonArray values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsString()));
        return result;
    }

    private static List<Integer> integers(com.google.gson.JsonArray values) {
        List<Integer> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsInt()));
        return result;
    }

    private static JsonObject evidenceRow(Path path, UUID invocationId) throws Exception {
        for (String line : Files.readAllLines(path)) {
            JsonObject frame = JsonParser.parseString(line).getAsJsonObject();
            if (!frame.has("records")) continue;
            for (var element : frame.getAsJsonArray("records")) {
                JsonObject row = element.getAsJsonObject();
                if (row.has("id") && invocationId.toString().equals(row.get("id").getAsString())) return row;
            }
        }
        throw new AssertionError("no retained evidence row for " + invocationId);
    }

    private enum AttentionMode { DISABLED, FULL, RATE_LIMITED, SOURCE_FAILURE }

    private enum LifecycleState { PENDING, ACTIVE }

    private enum LifecycleCause {
        DEATH("death", "DEATH", true),
        RESPAWN_ENTITY_REPLACEMENT("respawn-entity-replacement", "RESPAWN", true),
        DIMENSION_ROUND_TRIP("dimension-round-trip", "DIMENSION_CHANGED", true),
        DISCONNECT_SAME_CONNECTION("disconnect-same-connection", "DISCONNECT", false),
        SAME_UUID_REJOIN("same-uuid-rejoin", "PLAYER_JOINED", false),
        MOVEMENT_RETURN("movement-return", "BLOCK_MOVEMENT", true),
        SNAPSHOT_UNAVAILABLE("snapshot-unavailable", "SNAPSHOT_UNAVAILABLE", true);

        private final String caseId;
        private final String terminalCause;
        private final boolean noticeEligible;

        LifecycleCause(String caseId, String terminalCause, boolean noticeEligible) {
            this.caseId = caseId;
            this.terminalCause = terminalCause;
            this.noticeEligible = noticeEligible;
        }

        String caseId() { return caseId; }
        String terminalCause() { return terminalCause; }
        boolean noticeEligible() { return noticeEligible; }
    }

    private record MatrixCell(LifecycleCause cause, LifecycleState state, AttentionMode attention) {
        String caseId() {
            return cause.caseId() + "-" + state.name().toLowerCase(java.util.Locale.ROOT) + "-" +
                    attention.name().toLowerCase(java.util.Locale.ROOT);
        }

        String displayName() { return caseId(); }

        String fileSuffix() { return "r01-matrix-" + caseId(); }
    }

    @FunctionalInterface
    private interface LifecycleAction {
        void apply(IntegratedHarness harness);
    }

    private record RenderedAttempt(TestPlayer player, Object connection, String line) {}

    private static final class TestPlayer {
        private final UUID playerId;
        private final String name;
        private final Object connection;
        private String dimension = "minecraft:overworld";
        private boolean alive = true;
        private int blockX;
        private int blockY = 64;
        private int blockZ;

        private TestPlayer(UUID playerId, String name, Object connection) {
            this.playerId = playerId;
            this.name = name;
            this.connection = connection;
        }
    }

    private static final class TestGamePort implements SmokeConversation.GamePort {
        private final Map<UUID, TestPlayer> current = new HashMap<>();
        private final List<RenderedAttempt> attempts = new ArrayList<>();
        private final List<String> published = new ArrayList<>();

        private TestGamePort() {
            current.put(PLAYER_A, new TestPlayer(PLAYER_A, "Tester", new Object()));
            current.put(PLAYER_B, new TestPlayer(PLAYER_B, "TesterTwo", new Object()));
            current.put(PLAYER_C, new TestPlayer(PLAYER_C, "TesterThree", new Object()));
        }

        TestPlayer current(UUID id) { return current.get(id); }

        @Override public SmokeConversation.PlayerView inspect(Object value) {
            if (!(value instanceof TestPlayer player)) return null;
            return new SmokeConversation.PlayerView(player, player.connection, player.playerId, player.name,
                    player.dimension, true, current.get(player.playerId) == player, true, true, player.alive,
                    player.blockX, player.blockY, player.blockZ);
        }

        @Override public Object currentPlayer(UUID playerId) { return current.get(playerId); }

        @Override public boolean onlineMode() { return true; }

        @Override public FirstReplyScheduler.PublicationResult submitRendered(Object target, String renderedLine) {
            if (!(target instanceof TestPlayer player) || current.get(player.playerId) != player ||
                    !VanillaDialogue.isSafeRenderedLine(renderedLine)) {
                return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
            }
            attempts.add(new RenderedAttempt(player, player.connection, renderedLine));
            published.add(renderedLine);
            return FirstReplyScheduler.PublicationResult.SUBMITTED;
        }

        PlayerFreshness.Observation observation(TestPlayer player) {
            return new PlayerFreshness.Observation(player, player.connection, player.dimension,
                    player.alive, player.blockX, player.blockY, player.blockZ);
        }

        TestPlayer replaceEntity(UUID id, Object connection) {
            TestPlayer replacement = new TestPlayer(id, "TesterRespawned", connection);
            current.put(id, replacement);
            return replacement;
        }

        TestPlayer disconnect(UUID id) { return current.remove(id); }

        TestPlayer rejoin(UUID id, String name) {
            TestPlayer replacement = new TestPlayer(id, name, new Object());
            current.put(id, replacement);
            return replacement;
        }

        List<RenderedAttempt> attempts() { return List.copyOf(attempts); }
        List<String> publishedLines() { return List.copyOf(published); }
    }

    private static final class IntegratedHarness implements AutoCloseable {
        private final AtomicLong clock = new AtomicLong(T0);
        private final TestGamePort game = new TestGamePort();
        private final AtomicReference<SmokeConversation> conversationRef = new AtomicReference<>();
        private final Map<UUID, InteractionIngress.Invocation> invocations = new HashMap<>();
        private final CountDownLatch inboundTerminal = new CountDownLatch(1);
        private final Map<String, CountDownLatch> inboundByRequest = new ConcurrentHashMap<>();
        private final Object serviceEpoch = new Object();
        private final FirstReplyScheduler scheduler = new FirstReplyScheduler(clock::get, () -> WALL);
        private final TickMetrics metrics = new TickMetrics();
        private final GameLoopMailbox mailbox = new GameLoopMailbox();
        private final CompatibilityGate gate = new CompatibilityGate(BINDING);
        private final CompanionConfig config;
        private final BoundCompanionClient companion;
        private final SmokeConversation conversation;
        private final CrossPeerFixtureProcess node;
        private final InteractionIngress.Port<Object> ingress;
        private final Path temporaryDirectory;

        private IntegratedHarness(Path temporaryDirectory, AttentionPolicy attentionPolicy) throws Exception {
            this.temporaryDirectory = temporaryDirectory;
            ThreadBoundary.markServerThread();
            int port = availablePort();
            byte[] secret = new byte[32];
            java.security.SecureRandom.getInstanceStrong().nextBytes(secret);
            String encodedSecret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            node = CrossPeerFixtureProcess.start(Path.of(System.getProperty("user.dir")), port, encodedSecret);
            boolean complete = false;
            BoundCompanionClient createdClient = null;
            try {
                node.awaitStarted();
                config = new CompanionConfig(URI.create("ws://127.0.0.1:" + port), secret, BINDING,
                        "Hello {player}", "Archivist",
                        new InteractionPolicy(Set.of(PLAYER_A, PLAYER_B, PLAYER_C)), List.of(), 30,
                        attentionPolicy, 30);
                RuntimeFacts local = new RuntimeFacts(DeploymentFacts.MINECRAFT_VERSION,
                        DeploymentFacts.FABRIC_LOADER_VERSION, DeploymentFacts.FABRIC_API_VERSION,
                        Integer.parseInt(DeploymentFacts.JAVA_MAJOR), DeploymentFacts.JAVA_VENDOR,
                        DeploymentFacts.JAVA_RUNTIME_VERSION);
                gate.checkLocal(local);
                createdClient = new BoundCompanionClient(config, local, gate, frame -> {
                    SmokeConversation current = conversationRef.get();
                    if (current != null) current.onDialogueMessage(frame);
                    noteInbound(frame);
                    inboundTerminal.countDown();
                }, ignored -> {});
                companion = createdClient;
                conversation = new SmokeConversation(null, config, gate, companion, mailbox, metrics, 7,
                        serviceEpoch, SERVER_EPOCH, ignored -> {}, scheduler, game);
                conversationRef.set(conversation);
                node.command("SNAPSHOT");
                node.awaitEvent(event -> "snapshot".equals(text(event, "kind")), 3);
                companion.start();
                JsonObject ready = node.awaitEvent(event -> "ready".equals(text(event, "kind")) &&
                        event.get("peerSerial").getAsInt() == 1, 3);
                assertEquals(DeploymentFacts.PROTOCOL, text(ready, "protocol"));
                awaitReady(companion);
                ingress = createIngress();
                complete = true;
            } finally {
                if (!complete) {
                    if (createdClient != null) {
                        createdClient.close();
                        BoundCompanionClientTestAccess.awaitOwnerTermination(createdClient, 3, TimeUnit.SECONDS);
                    }
                    metrics.close();
                    node.close();
                }
            }
        }

        private InteractionIngress.Port<Object> createIngress() {
            return new InteractionIngress.Port<>() {
                @Override public InteractionIngress.SourceFacts resolve(Object source) {
                    return InteractionIngress.SourceFacts.nonPlayer();
                }
                @Override public InteractionIngress.SourceFacts resolve(Object source, String input) {
                    return game.sourceFacts(source);
                }
                @Override public CompanionConfig config() { return config; }
                @Override public TickMetrics metrics() { return metrics; }
                @Override public boolean serviceActive() { return true; }
                @Override public FirstReplyScheduler firstReplyScheduler() { return scheduler; }
                @Override public FollowupWindow.Candidate beginExplicit(InteractionIngress.SourceFacts source,
                                                                         InteractionIngress.Captured captured) {
                    TestPlayer player = game.current(source.playerId());
                    var binding = config.binding();
                    var context = new FollowupWindow.Context(source.playerId(), game, player, source.connection(),
                            binding.serverId(), binding.worldId(), binding.characterId(), binding.agentId(),
                            player.dimension, serviceEpoch, 7, gate.snapshot().generation());
                    return conversation.beginExplicitForTesting(source, captured, serviceEpoch, context);
                }
                @Override public void invalidateFollowup(InteractionIngress.SourceFacts source) {
                    if (source != null) conversation.invalidateFollowup(source.playerId());
                }
                @Override public InteractionIngress.Outcome submit(InteractionIngress.SourceFacts source, String text,
                                                                    InteractionIngress.Invocation invocation) {
                    invocations.put(invocation.invocationId(), invocation);
                    return InteractionIngress.Outcome.valueOf(conversation.submit(source, text, invocation).name());
                }
                @Override public boolean publishPlayer(InteractionIngress.SourceFacts source, String message,
                                                       boolean characterMessage, String characterDisplayName) { return false; }
                @Override public FirstReplyScheduler.PublicationResult submitPlayerDisposition(
                        InteractionIngress.SourceFacts source, String message, boolean characterMessage,
                        String characterDisplayName, boolean requireCurrentGrant) {
                    return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
                }
                @Override public boolean publishSource(Object source, String message) { return false; }
                @Override public void rejectOpeningCandidate(InteractionIngress.SourceFacts source,
                                                             FollowupWindow.Candidate candidate) {
                    conversation.rejectOpeningCandidate(candidate);
                }
                @Override public void endFollowup(InteractionIngress.SourceFacts source) {
                    conversation.endFollowup(source);
                }
            };
        }

        InteractionIngress.Invocation admit(TestPlayer player, String text) {
            clock.set(Math.max(clock.get(), T0));
            InteractionIngress.Captured captured = InteractionIngress.Captured.capture(clock::get, () -> WALL,
                    InteractionIngress.Origin.COMMAND);
            int accepted = InteractionIngress.execute(captured, player, "worldspeaker ask", InteractionIngress.Route.ASK,
                    text, ingress);
            assertEquals(1, accepted, "the actual common ingress must admit this synthetic test input");
            return invocation(captured.invocationId());
        }

        private InteractionIngress.Invocation invocation(UUID id) {
            InteractionIngress.Invocation invocation = invocations.get(id);
            assertNotNull(invocation, "the real ingress submit port retained the admitted invocation");
            return invocation;
        }

        void tickAt(long atNanos) {
            clock.set(atNanos);
            conversation.tick();
        }

        JsonObject awaitRequest(InteractionIngress.Invocation invocation) throws Exception {
            return node.awaitEvent(event -> "request".equals(text(event, "kind")) &&
                    invocation.invocationId().toString().equals(text(event, "invocationId")), 3);
        }

        void publishAcknowledgement(InteractionIngress.Invocation invocation, JsonObject request) throws Exception {
            tickAt(invocation.capturedAtNanos() + FIRST_REPLY);
            assertTrue(game.publishedLines().stream().anyMatch(line -> line.endsWith("Hello " + invocation.source().playerName())),
                    "the active request gets its actual local first-reply acknowledgement");
            node.awaitEvent(event -> "ack".equals(text(event, "kind")) &&
                    text(request, "requestId").equals(text(event, "requestId")), 3);
        }

        void callback(TrustedLifecycleContext.Cause cause, TestPlayer current, TestPlayer departed,
                      Object departedConnection, AttentionEvent.EventKind eventKind,
                      String dimension, AttentionEvent.Details details) {
            callback(cause, current, departed, departedConnection, eventKind, dimension, details, current);
        }

        void callback(TrustedLifecycleContext.Cause cause, TestPlayer current, TestPlayer departed,
                      Object departedConnection, AttentionEvent.EventKind eventKind,
                      String dimension, AttentionEvent.Details details, Object attentionSource) {
            UUID id = current == null ? departed.playerId : current.playerId;
            conversation.applyLifecycleContext(TrustedLifecycleContext.forTestingCallback(cause, id,
                    current == null ? null : game.observation(current),
                    departed == null ? null : departed, departedConnection, attentionSource,
                    dimension, eventKind, details, Math.max(0, clock.get()), WALL));
        }

        void sample(TrustedLifecycleContext.Cause cause, TestPlayer player,
                    PlayerFreshness.Observation observation, AttentionEvent.EventKind eventKind,
                    AttentionEvent.Details details) {
            sample(cause, player, observation, eventKind, details, player);
        }

        void sample(TrustedLifecycleContext.Cause cause, TestPlayer player,
                    PlayerFreshness.Observation observation, AttentionEvent.EventKind eventKind,
                    AttentionEvent.Details details, Object attentionSource) {
            conversation.applyLifecycleContext(TrustedLifecycleContext.forTestingSample(cause, player.playerId,
                    observation, attentionSource, observation == null ? null : observation.dimension(), eventKind, details,
                    Math.max(0, clock.get()), WALL));
        }

        JsonObject awaitCancel(JsonObject request) throws Exception {
            return node.awaitEvent(event -> "cancel".equals(text(event, "kind")) &&
                    text(request, "requestId").equals(text(event, "requestId")), 3);
        }

        void awaitAcceptedAckHistory(JsonObject request) throws Exception {
            node.awaitEvent(event -> "context".equals(text(event, "kind")) &&
                    "ack".equals(text(event, "contextKind")) &&
                    text(request, "requestId").equals(text(event, "requestId")), 3);
        }

        boolean awaitInbound(String requestId, int timeoutSeconds) throws InterruptedException {
            return inboundByRequest.computeIfAbsent(requestId, ignored -> new CountDownLatch(1))
                    .await(timeoutSeconds, TimeUnit.SECONDS);
        }

        void expectInbound(String requestId) {
            inboundByRequest.computeIfAbsent(requestId, ignored -> new CountDownLatch(1));
        }

        private void noteInbound(JsonObject value) {
            if (value != null && value.has("requestId")) {
                CountDownLatch latch = inboundByRequest.get(value.get("requestId").getAsString());
                if (latch != null) latch.countDown();
            }
        }

        void sendFinalAndClear(JsonObject request, boolean expectedAccepted) throws Exception {
            if (expectedAccepted) expectInbound(text(request, "requestId"));
            node.command("FINAL " + text(request, "requestId"));
            JsonObject result = node.awaitEvent(event -> "final".equals(text(event, "kind")) &&
                    text(request, "requestId").equals(text(event, "requestId")), 3);
            assertEquals(expectedAccepted, result.get("accepted").getAsBoolean());
            if (!expectedAccepted) {
                node.command("CLEAR " + text(request, "requestId"));
                node.awaitEvent(event -> "cleared".equals(text(event, "kind")) &&
                        text(request, "requestId").equals(text(event, "requestId")), 3);
            }
        }

        JsonObject exportRow(InteractionIngress.Invocation invocation, String fileName) throws Exception {
            Path path = exportSnapshot(fileName);
            return evidenceRow(path, invocation.invocationId());
        }

        Path exportSnapshot(String fileName) throws Exception {
            Path path = temporaryDirectory.resolve(fileName);
            assertTrue(metrics.offerNumericSnapshot(path));
            assertTrue(TickMetricsTestAccess.awaitPersistence(metrics, 3, TimeUnit.SECONDS));
            return path;
        }

        List<RenderedAttempt> noticeAttempts() {
            return game.attempts().stream().filter(attempt -> attempt.line().contains("stopped this request")).toList();
        }

        List<RenderedAttempt> noticeAttemptsFor(UUID playerId) {
            return noticeAttempts().stream().filter(attempt -> attempt.player().playerId.equals(playerId)).toList();
        }

        int nodeCancelCount() {
            try {
                node.command("SNAPSHOT");
                JsonObject snapshot = node.awaitEvent(event -> "snapshot".equals(text(event, "kind")), 3);
                return snapshot.getAsJsonArray("cancelPeerIds").size();
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        int nodeReadyPeerSerial() {
            return 1;
        }

        private static void awaitReady(BoundCompanionClient client) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!client.isReady() && System.nanoTime() - deadline < 0) Thread.sleep(5);
            assertTrue(client.isReady(), "Java authenticated the local compiled TypeScript peer within three seconds");
        }

        private static int availablePort() throws IOException {
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                return socket.getLocalPort();
            }
        }

        @Override public void close() {
            try {
                conversation.close();
            } finally {
                try {
                    companion.close();
                    BoundCompanionClientTestAccess.awaitOwnerTermination(companion, 3, TimeUnit.SECONDS);
                } finally {
                    metrics.close();
                    node.close();
                }
            }
        }
    }
}
