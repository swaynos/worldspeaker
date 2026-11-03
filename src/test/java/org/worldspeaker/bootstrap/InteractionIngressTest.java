package org.worldspeaker.bootstrap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.InteractionPolicy;
import org.worldspeaker.runtime.TickMetrics;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionIngressTest {
    private static final UUID GRANTED_PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OPERATOR_WITHOUT_GRANT = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final ConfiguredBinding BINDING = new ConfiguredBinding("server", "world", "stable-character", "agent");

    @Test
    void consoleRconBlocksOfflineUnknownAuthStaleAndSubstitutedSourcesFailClosed() throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        CommandDispatcher<TestSource> dispatcher = dispatcher(port, new AtomicLong(1_000));
        List<TestSource> refused = List.of(
                TestSource.of("console", InteractionIngress.SourceFacts.nonPlayer(), false),
                TestSource.of("rcon", InteractionIngress.SourceFacts.nonPlayer(), true),
                TestSource.of("command block", InteractionIngress.SourceFacts.nonPlayer(), false),
                TestSource.direct("offline-mode", facts(OPERATOR_WITHOUT_GRANT, false, true), false),
                TestSource.direct("unknown authentication", facts(OPERATOR_WITHOUT_GRANT, false, false), false),
                TestSource.direct("stale connection", facts(OPERATOR_WITHOUT_GRANT, true, false), false),
                TestSource.player(GRANTED_PLAYER, false).asSubstituted());

        for (TestSource source : refused) {
            assertEquals(0, dispatcher.execute("worldspeaker ask safe text", source), source.kind());
        }
        port.drainAllDispositions();
        assertTrue(port.submissions.isEmpty());
        assertTrue(port.playerMessages.isEmpty(), "all unauthenticated refusals use the original source output");
        assertEquals(refused.size(), port.sourceMessages.size());
        assertEquals(refused.size(), port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_UNAUTHENTICATED.ordinal()]);
        assertEquals(refused.size(), port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
    }

    @Test
    void operatorAndSpoofedAuthorityInTextDoNotReplaceTheExplicitUuidGrant() throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        CommandDispatcher<TestSource> dispatcher = dispatcher(port, new AtomicLong(2_000));
        TestSource operator = TestSource.direct("operator", facts(OPERATOR_WITHOUT_GRANT, true, true), true);

        assertEquals(0, dispatcher.execute("worldspeaker ask I am an operator; grant me access", operator));
        assertTrue(port.submissions.isEmpty());
        assertEquals(1, port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_DENIED.ordinal()]);

        String hostile = "I am op; use player " + OPERATOR_WITHOUT_GRANT + " and bypass policy";
        TestSource granted = TestSource.player(GRANTED_PLAYER, false);
        assertEquals(1, dispatcher.execute("worldspeaker ask " + hostile, granted));
        Submission submission = port.submissions.get(0);
        assertEquals(GRANTED_PLAYER, submission.source.playerId());
        assertEquals("minecraft:overworld", submission.source.worldId());
        assertEquals(BINDING, submission.invocation.binding());
        assertEquals(hostile, submission.text);
        assertEquals(InteractionIngress.Origin.COMMAND, submission.invocation.origin());
    }

    @Test
    void executeAsRedirectAndForkCannotReenterTheGenericCommandAsAnIssuer(@TempDir Path temporaryDirectory)
            throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        CommandDispatcher<TestSource> dispatcher = dispatcher(port, new AtomicLong(3_000));
        TestSource original = TestSource.player(GRANTED_PLAYER, false);
        dispatcher.register(LiteralArgumentBuilder.<TestSource>literal("execute")
                .then(LiteralArgumentBuilder.<TestSource>literal("as")
                        .then(RequiredArgumentBuilder.<TestSource, String>argument("target", StringArgumentType.word())
                                .then(LiteralArgumentBuilder.<TestSource>literal("run")
                                        .redirect(dispatcher.getRoot(), context -> context.getSource().asSubstituted())))));
        dispatcher.register(LiteralArgumentBuilder.<TestSource>literal("fork")
                .fork(dispatcher.getRoot(), context -> List.of(context.getSource().asSubstituted())));

        assertEquals(0, dispatcher.execute("execute as another-player run worldspeaker ask hi", original));
        // Brigadier reports the count of successful fork branches, not the
        // command handler's result; authorization must still reject the branch.
        dispatcher.execute("fork worldspeaker ask hi", original);
        port.drainAllDispositions();
        assertTrue(port.submissions.isEmpty());
        assertEquals(2, port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_UNAUTHENTICATED.ordinal()]);
        assertTrue(port.playerMessages.isEmpty(), "a substituted entity is never used as a refusal recipient");
        assertEquals(2, port.sourceMessages.size(), "redirect and fork refusals use their retained source output");
        assertFalse(metricsSnapshot(port.metrics, temporaryDirectory.resolve("redirect-evidence.jsonl"))
                        .contains(GRANTED_PLAYER.toString()),
                "untrusted target identity must not be bound into invocation evidence");
    }

    @Test
    void directLookingFunctionLineCannotUseTheGrantedTargetWhenRetainedOutputNamesTheIssuer(@TempDir Path temporaryDirectory)
            throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        CommandDispatcher<TestSource> dispatcher = dispatcher(port, new AtomicLong(3_500));
        TestSource functionLineSource = TestSource.direct("execute-as function target",
                facts(GRANTED_PLAYER, true, true), false).asSubstituted();

        assertEquals(0, dispatcher.execute("worldspeaker ask private-sentinel", functionLineSource));
        port.drainAllDispositions();

        assertTrue(port.submissions.isEmpty(), "a direct-looking function line is not proof of its issuer");
        assertTrue(port.playerMessages.isEmpty(), "the substituted target is never selected as a recipient");
        assertEquals(1, port.sourceMessages.size(), "refusal uses the retained original CommandOutput");
        long[] counters = port.metrics.countersSnapshot();
        assertEquals(1, counters[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
        assertEquals(1, counters[TickMetrics.Counter.INTERACTION_UNAUTHENTICATED.ordinal()]);
        String evidence = metricsSnapshot(port.metrics, temporaryDirectory.resolve("function-line-evidence.jsonl"));
        assertTrue(evidence.contains("\"player\":null"));
        assertTrue(evidence.contains("\"actualWorld\":null"));
        assertFalse(evidence.contains(GRANTED_PLAYER.toString()), "the substituted target is absent from evidence");
    }

    @Test
    void sourceOnlyPortsCannotAuthenticateByReturningPlayerFacts() throws Exception {
        TestPort sourceOnlyPort = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        sourceOnlyPort.useDefaultInputResolution = true;
        CommandDispatcher<TestSource> dispatcher = dispatcher(sourceOnlyPort, new AtomicLong(3_750));

        assertEquals(0, dispatcher.execute("worldspeaker ask private-sentinel",
                TestSource.player(GRANTED_PLAYER, false)));
        sourceOnlyPort.drainAllDispositions();

        assertTrue(sourceOnlyPort.submissions.isEmpty(), "the common ingress default fails closed without issuer evidence");
        assertTrue(sourceOnlyPort.playerMessages.isEmpty());
        assertEquals(1, sourceOnlyPort.sourceMessages.size());
        assertEquals(1, sourceOnlyPort.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_UNAUTHENTICATED.ordinal()]);
    }

    @Test
    void untrustedPlayerFactsAreStrippedBeforeRefusalRecipientAndMetrics(@TempDir Path temporaryDirectory)
            throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        CommandDispatcher<TestSource> dispatcher = dispatcher(port, new AtomicLong(4_500));

        assertEquals(0, dispatcher.execute("worldspeaker ask private-sentinel",
                TestSource.player(GRANTED_PLAYER, false).asSubstituted()));
        port.drainAllDispositions();

        assertTrue(port.submissions.isEmpty());
        assertTrue(port.playerMessages.isEmpty());
        assertEquals(1, port.sourceMessages.size());
        long[] counters = port.metrics.countersSnapshot();
        assertEquals(1, counters[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
        assertEquals(1, counters[TickMetrics.Counter.INTERACTION_UNAUTHENTICATED.ordinal()]);
        String evidence = metricsSnapshot(port.metrics, temporaryDirectory.resolve("untrusted-evidence.jsonl"));
        assertTrue(evidence.contains("\"t0Nanos\":\"4500\""), "the command-handler capture remains the measured t0");
        assertTrue(evidence.contains("\"player\":null"));
        assertTrue(evidence.contains("\"actualWorld\":null"));
        assertFalse(evidence.contains(GRANTED_PLAYER.toString()));
    }

    @Test
    void commandAndSyntheticNaturalAddressUseTheSameCapturedEnvelopeAndAdmissionPort() throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        AtomicLong clock = new AtomicLong(10_000);
        CommandDispatcher<TestSource> dispatcher = dispatcher(port, clock);
        TestSource source = TestSource.player(GRANTED_PLAYER, false);

        assertEquals(1, dispatcher.execute("worldspeaker ask same question", source));
        InteractionIngress.Captured natural = new InteractionIngress.Captured(
                UUID.fromString("00000000-0000-4000-8000-000000000099"), 20_000,
                InteractionIngress.Origin.NATURAL_ADDRESS);
        assertEquals(1, InteractionIngress.execute(natural, source, "a sentence addressed to the character",
                InteractionIngress.Route.ASK, "same question", port));

        assertEquals(2, port.submissions.size());
        Submission command = port.submissions.get(0);
        Submission naturalAddress = port.submissions.get(1);
        assertEquals(command.source.playerId(), naturalAddress.source.playerId());
        assertEquals(command.source.connection(), naturalAddress.source.connection());
        assertEquals(command.source.worldId(), naturalAddress.source.worldId());
        assertEquals(command.invocation.binding(), naturalAddress.invocation.binding());
        assertEquals(command.text, naturalAddress.text);
        assertEquals(10_000, command.invocation.capturedAtNanos());
        assertEquals(20_000, naturalAddress.invocation.capturedAtNanos());
        assertEquals(InteractionIngress.Origin.COMMAND, command.invocation.origin());
        assertEquals(InteractionIngress.Origin.NATURAL_ADDRESS, naturalAddress.invocation.origin());
        assertEquals(2, port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);
        assertEquals(2, port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
    }

    @Test
    void originalCaptureTimeDrivesOneSuccessfulDispatchAndOneDeadlineMiss() throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        CommandDispatcher<TestSource> dispatcher = dispatcher(port, new AtomicLong(100));
        TestSource source = TestSource.player(GRANTED_PLAYER, false);

        assertEquals(1, dispatcher.execute("worldspeaker ask measured", source));
        InteractionIngress.Invocation invocation = port.submissions.get(0).invocation;
        assertEquals(100, invocation.capturedAtNanos());

        long dispatchedAt = 1_000_000_101L;
        assertTrue(invocation.firstPublicDispatch().recordDispatch(true, port.metrics, dispatchedAt,
                TickMetrics.FirstPublicSource.LOCAL_ACKNOWLEDGEMENT,
                TickMetrics.FirstPublicDisposition.ACKNOWLEDGED));
        assertFalse(invocation.firstPublicDispatch().recordDispatch(true, port.metrics, dispatchedAt + 1,
                TickMetrics.FirstPublicSource.LOCAL_ACKNOWLEDGEMENT,
                TickMetrics.FirstPublicDisposition.ACKNOWLEDGED));

        assertEquals(1_000_000_001L, port.metrics.maxFirstPublicDispatchNanos());
        assertEquals(1, port.metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);
        assertEquals(1, port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);
        assertEquals(1, histogramCount(port.metrics, TickMetrics.FirstPublicSource.LOCAL_ACKNOWLEDGEMENT,
                TickMetrics.FirstPublicDisposition.ACKNOWLEDGED));
    }

    @Test
    void failedImmediateDispatchIsCountedWithoutClaimingAReplyOrDeadlineSuccess() throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        port.playerDispatchSucceeds = false;
        port.dispatchClock = () -> 9_000;
        InteractionIngress.Captured captured = new InteractionIngress.Captured(
                UUID.fromString("00000000-0000-4000-8000-000000000100"), 8_000,
                InteractionIngress.Origin.COMMAND);

        assertEquals(1, InteractionIngress.execute(captured, TestSource.player(GRANTED_PLAYER, false),
                "worldspeaker help", InteractionIngress.Route.HELP, null, port));
        port.drainAllDispositions();

        assertEquals(1, port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_HELP.ordinal()]);
        assertEquals(1, port.metrics.countersSnapshot()[TickMetrics.Counter.FAILED_PUBLIC_DISPATCHES.ordinal()]);
        assertEquals(0, port.metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);
        assertEquals(0, port.metrics.maxFirstPublicDispatchNanos());
    }

    @Test
    void packetReceiptStampSurvivesIngressAndSameTextFromAnotherConnectionIsNotCorrelated() throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        TestConnection firstConnection = new TestConnection(31);
        TestConnection secondConnection = new TestConnection(32);
        InteractionIngress.Captured first = InteractionIngress.Captured.fromPacket(
                new InvocationCapture(UUID.randomUUID(), UUID.randomUUID(), 100, 1_793_275_200_000L,
                        firstConnection, 31, InvocationCapture.Source.UNSIGNED_COMMAND),
                InteractionIngress.Origin.COMMAND, 500);
        InteractionIngress.Captured second = InteractionIngress.Captured.fromPacket(
                new InvocationCapture(UUID.randomUUID(), UUID.randomUUID(), 400, 1_793_275_200_001L,
                        secondConnection, 32, InvocationCapture.Source.SIGNED_COMMAND),
                InteractionIngress.Origin.COMMAND, 749);
        TestSource firstSource = TestSource.direct("packet-one", facts(GRANTED_PLAYER, true, true, firstConnection), false);
        TestSource secondSource = TestSource.direct("packet-two", facts(GRANTED_PLAYER, true, true, secondConnection), false);

        assertEquals(1, InteractionIngress.execute(first, firstSource, "worldspeaker ask same text",
                InteractionIngress.Route.ASK, "same text", port));
        assertEquals(1, InteractionIngress.execute(second, secondSource, "worldspeaker ask same text",
                InteractionIngress.Route.ASK, "same text", port));

        assertEquals(2, port.submissions.size());
        assertEquals(100, port.submissions.get(0).invocation.capturedAtNanos());
        assertEquals(400, port.submissions.get(1).invocation.capturedAtNanos());
        assertEquals(500, port.submissions.get(0).invocation.captured().handlerEnteredAtNanos());
        assertEquals(749, port.submissions.get(1).invocation.captured().handlerEnteredAtNanos());
        assertEquals(first.invocationId(), port.submissions.get(0).invocation.invocationId());
        assertEquals(second.invocationId(), port.submissions.get(1).invocation.invocationId());
        assertEquals(2, port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);
    }

    @Test
    void absentPacketReceiptIsRecordedAsEvidenceFailureAndNeverRecapturedAtHandlerEntry() throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        InteractionIngress.Captured missing = InteractionIngress.Captured.fromPacket(
                null, InteractionIngress.Origin.COMMAND, 749);

        assertFalse(missing.hasReceiptEvidence());
        assertEquals(Long.MIN_VALUE, missing.capturedAtNanos());
        assertEquals(0, InteractionIngress.execute(missing, TestSource.player(GRANTED_PLAYER, false),
                "worldspeaker ask must-not-be-timed-from-handler", InteractionIngress.Route.ASK,
                "must-not-be-timed-from-handler", port));

        long[] counters = port.metrics.countersSnapshot();
        assertEquals(1, counters[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
        assertEquals(1, counters[TickMetrics.Counter.INTERACTION_RECEIPT_EVIDENCE_MISSING.ordinal()]);
        assertEquals(0, counters[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);
        assertTrue(port.submissions.isEmpty());
        assertTrue(port.playerMessages.isEmpty());
        assertTrue(port.sourceMessages.isEmpty());
    }

    @Test
    void capacityRejectedCommandReceiptKeepsT0AndGetsOnlyAScheduledTargetedSafeRefusal() {
        TestConnection connection = new TestConnection(73);
        List<InvocationCapture> captures = new ArrayList<>();
        try {
            for (int index = 0; index < CaptureHandoffBudget.MAX_PER_CONNECTION + 2; index++) {
                long receiptNanos = 50_000L + index;
                captures.add(InvocationCapture.captureHandoff(() -> receiptNanos,
                        () -> 1_793_275_200_000L, connection, 73, InvocationCapture.Source.UNSIGNED_COMMAND));
            }
            InvocationCapture overflow = captures.get(CaptureHandoffBudget.MAX_PER_CONNECTION);
            TestPacketCarrier carrier = new TestPacketCarrier();
            assertTrue(carrier.worldspeaker$setInvocationCapture(overflow));

            InteractionIngress.Captured captured = InteractionIngress.Captured.fromPacket(
                    carrier.worldspeaker$getInvocationCapture(), InteractionIngress.Origin.COMMAND, 60_000);
            TestSource source = TestSource.direct("original", facts(GRANTED_PLAYER, true, true, connection), false);
            TestPort port = new TestPort(config(InteractionPolicy.denyAll()));
            port.rateLimiter = new InvocationRateLimiter();

            assertTrue(captured.handoffCapacityRejected());
            assertSame(overflow, captured.packetCapture());
            assertEquals(50_000 + CaptureHandoffBudget.MAX_PER_CONNECTION, captured.capturedAtNanos());
            assertEquals(60_000, captured.handlerEnteredAtNanos());
            assertEquals(0, InteractionIngress.execute(captured, source, "worldspeaker ask private-sentinel",
                    InteractionIngress.Route.ASK, "private-sentinel", port));
            InteractionIngress.Captured repeatedOverflow = InteractionIngress.Captured.fromPacket(
                    captures.get(captures.size() - 1), InteractionIngress.Origin.COMMAND, 60_001);
            assertEquals(0, InteractionIngress.execute(repeatedOverflow, source, "worldspeaker ask second-sentinel",
                    InteractionIngress.Route.ASK, "second-sentinel", port));

            assertTrue(port.submissions.isEmpty(), "overflow is refused before model admission");
            assertEquals(1, port.scheduler.pendingDispositionCount(),
                    "the duplicate shares one bounded notice callback and publication");
            assertTrue(port.playerMessages.isEmpty());
            assertTrue(port.sourceMessages.isEmpty());
            assertEquals(CaptureHandoffBudget.MAX_PER_CONNECTION,
                    CaptureHandoffBudget.outstanding(connection), "the unleased overflow stamp uses no capacity");

            port.drainAllDispositions();

            assertEquals(1, port.playerMessages.size());
            assertEquals(List.of(connection), port.deliveredConnections);
            assertTrue(port.playerMessages.get(0).contains("packet handoff capacity was full"));
            assertFalse(port.playerMessages.get(0).contains("private-sentinel"), "packet text is never forwarded");
            assertEquals(2, port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_BUSY.ordinal()]);
            assertEquals(0, port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);
        } finally {
            for (InvocationCapture capture : captures) capture.releaseHandoff();
            CaptureHandoffBudget.retireConnection(connection);
        }
        assertEquals(0, CaptureHandoffBudget.outstanding(connection));
        CaptureHandoffBudget.Lease recovered = CaptureHandoffBudget.tryAcquire(connection);
        assertTrue(recovered != null, "handling and dropping the unleased stamp does not leak connection capacity");
        recovered.release();
    }

    @Test
    void batchedNoticeEvidenceRetainsUnknownAndReplacedConnectionOutcomes(@TempDir Path temporaryDirectory) throws Exception {
        TestPort unknown = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        unknown.rateLimiter = new InvocationRateLimiter();
        unknown.dispatchClock = () -> 400;
        unknown.throwDuringPlayerSubmit = true;
        TestSource unknownSource = TestSource.player(GRANTED_PLAYER, false);
        InteractionIngress.Captured unknownCapture = new InteractionIngress.Captured(
                UUID.randomUUID(), 300, InteractionIngress.Origin.COMMAND);
        assertEquals(1, InteractionIngress.execute(unknownCapture, unknownSource, "worldspeaker help",
                InteractionIngress.Route.HELP, null, unknown));
        assertEquals(1, unknown.scheduler.pendingDispositionCount());
        unknown.drainAllDispositions();
        String unknownEvidence = metricsSnapshot(unknown.metrics,
                temporaryDirectory.resolve("notice-batch-unknown.jsonl"));
        assertTrue(unknownEvidence.contains("\"noticeBatchResult\":\"UNKNOWN\""));
        assertTrue(unknownEvidence.contains("\"noticeBatchAttemptedAtNanos\":\"400\""));
        assertTrue(unknownEvidence.contains("\"noticeStatus\":\"UNKNOWN\""));

        TestPort replaced = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        replaced.rateLimiter = new InvocationRateLimiter();
        replaced.dispatchClock = () -> 500;
        TestSource original = TestSource.player(GRANTED_PLAYER, false);
        Object originalConnection = original.facts().connection();
        replaced.enforceCurrentConnection = true;
        replaced.currentConnection = originalConnection;
        InteractionIngress.Captured replacedCapture = new InteractionIngress.Captured(
                UUID.randomUUID(), 350, InteractionIngress.Origin.COMMAND);
        assertEquals(1, InteractionIngress.execute(replacedCapture, original, "worldspeaker help",
                InteractionIngress.Route.HELP, null, replaced));
        replaced.currentConnection = new Object();
        replaced.drainAllDispositions();
        assertEquals(List.of(originalConnection), replaced.deliveredConnections,
                "the batch attempts only the immutable connection captured by ingress");
        assertTrue(replaced.playerMessages.isEmpty(), "a replaced connection receives no notice");
        String unsentEvidence = metricsSnapshot(replaced.metrics,
                temporaryDirectory.resolve("notice-batch-replaced-connection.jsonl"));
        assertTrue(unsentEvidence.contains("\"noticeBatchResult\":\"DEFINITELY_UNSENT\""));
        assertTrue(unsentEvidence.contains("\"noticeBatchAttemptedAtNanos\":\"500\""));
        assertTrue(unsentEvidence.contains("\"noticeStatus\":\"DEFINITELY_UNSENT\""));
    }

    @Test
    void mixedHelpDenialAndUnavailableCausesComposeOneExplicitSafeNotice(@TempDir Path temporaryDirectory)
            throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        port.rateLimiter = new InvocationRateLimiter();
        port.dispatchClock = () -> 1_000;
        TestSource source = TestSource.player(GRANTED_PLAYER, false);

        InteractionIngress.Captured help = new InteractionIngress.Captured(
                UUID.randomUUID(), 900, InteractionIngress.Origin.COMMAND);
        assertEquals(1, InteractionIngress.execute(help, source, "worldspeaker help",
                InteractionIngress.Route.HELP, null, port));
        port.config = config(InteractionPolicy.denyAll());
        InteractionIngress.Captured denied = new InteractionIngress.Captured(
                UUID.randomUUID(), 901, InteractionIngress.Origin.COMMAND);
        assertEquals(0, InteractionIngress.execute(denied, source, "worldspeaker ask private",
                InteractionIngress.Route.ASK, "private", port));
        port.config = null;
        InteractionIngress.Captured unavailable = new InteractionIngress.Captured(
                UUID.randomUUID(), 902, InteractionIngress.Origin.COMMAND);
        assertEquals(0, InteractionIngress.execute(unavailable, source, "worldspeaker ask unavailable",
                InteractionIngress.Route.ASK, "unavailable", port));

        assertEquals(1, port.scheduler.pendingDispositionCount(),
                "bounded composition retains one queued callback for the sender");
        port.drainAllDispositions();
        assertEquals(1, port.playerMessages.size());
        assertTrue(port.playerMessages.get(0).contains("use /worldspeaker ask"));
        assertTrue(port.playerMessages.get(0).contains("not authorized to converse"));
        assertTrue(port.playerMessages.get(0).contains("service is unavailable"));
        assertTrue(port.playerMessages.get(0).contains("no request was accepted"));
        assertFalse(port.playerMessages.get(0).contains("Archivist:"));

        String evidence = metricsSnapshot(port.metrics,
                temporaryDirectory.resolve("mixed-safe-notice-causes.jsonl"));
        assertTrue(evidence.contains("\"outcome\":\"HELP\""));
        assertTrue(evidence.contains("\"outcome\":\"DENIED\""));
        assertTrue(evidence.contains("\"outcome\":\"UNAVAILABLE_BEFORE_BOOTSTRAP\""));
        assertFalse(evidence.contains("\"noticeStatus\":\"SUPPRESSED\""),
                "each compatible row receives the new composed publication rather than a borrowed or dropped result");
        assertTrue(evidence.contains("\"noticeBatchResult\":\"SUBMITTED\""));
    }

    @Test
    void ordinaryAndOperatorPlayersReceiveOnlySafeAvailabilityWhenPrivateConfigurationIsInvalid() {
        List<String> privateCanaries = List.of("credentialCanaryValueNeverPublic", "unknownKeyCanaryMustNotBeLogged",
                "unknownValueCanary", "unknownAbilityCanaryMustNotBeLogged",
                "styleValueCanary\nWS-COMPAT-INJECTED", "agentValueCanary\nWS-COMPAT-INJECTED");
        for (boolean operator : List.of(false, true)) {
            TestPort port = new TestPort(null);
            port.rateLimiter = new InvocationRateLimiter();
            port.dispatchClock = () -> 1_000;
            TestSource source = TestSource.player(GRANTED_PLAYER, operator);
            InteractionIngress.Captured captured = new InteractionIngress.Captured(
                    UUID.randomUUID(), 900, InteractionIngress.Origin.COMMAND);

            assertEquals(0, InteractionIngress.execute(captured, source, "worldspeaker ask unavailable",
                    InteractionIngress.Route.ASK, "unavailable", port));
            assertEquals(1, port.drainAllDispositions());
            assertEquals(1, port.playerMessages.size());
            String message = port.playerMessages.getFirst();
            assertTrue(message.contains("Worldspeaker is unavailable right now"));
            assertTrue(message.contains("no validated character configuration is loaded"));
            for (String canary : privateCanaries) assertFalse(message.contains(canary));
        }
    }

    @Test
    void pendingHelpAndUnavailableRowsReceiveTheNewComposedPublicationBeforeTheirDeadlines(
            @TempDir Path temporaryDirectory) throws Exception {
        AtomicLong now = new AtomicLong(3_000_000_000L);
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        port.dispatchClock = now::get;
        port.rateLimiter = new InvocationRateLimiter();
        TestSource source = TestSource.player(GRANTED_PLAYER, false);
        InteractionIngress.Captured help = new InteractionIngress.Captured(
                UUID.randomUUID(), now.get(), InteractionIngress.Origin.COMMAND);

        assertEquals(1, InteractionIngress.execute(help, source, "worldspeaker help",
                InteractionIngress.Route.HELP, null, port));
        now.set(3_100_000_000L);
        port.config = null;
        InteractionIngress.Captured unavailable = new InteractionIngress.Captured(
                UUID.randomUUID(), now.get(), InteractionIngress.Origin.COMMAND);
        assertEquals(0, InteractionIngress.execute(unavailable, source, "worldspeaker ask unavailable",
                InteractionIngress.Route.ASK, "unavailable", port));
        assertEquals(1, port.scheduler.pendingDispositionCount());

        now.set(3_200_000_000L);
        assertEquals(1, port.scheduler.drainDispositions(new SmokeTickBudget(now.get()), 0));
        assertEquals(0, port.rateLimiter.pendingNoticeMembers(), "the first batch must complete before cooldown reservation");
        assertEquals(1, port.playerMessages.size());
        assertTrue(port.playerMessages.get(0).contains("use /worldspeaker ask"));
        assertTrue(port.playerMessages.get(0).contains("service is unavailable"));
        assertEquals(0, port.metrics.countersSnapshot()[TickMetrics.Counter.DISPOSITION_QUEUE_OVERFLOWS.ordinal()]);

        String evidence = metricsSnapshot(port.metrics, temporaryDirectory.resolve("help-unavailable-future.jsonl"));
        assertSubmittedNoticeBeforeDeadline(evidence, help.invocationId(), 3_200_000_000L,
                3_000_000_000L, 1_000_000_000L);
        assertSubmittedNoticeBeforeDeadline(evidence, unavailable.invocationId(), 3_200_000_000L,
                3_100_000_000L, 1_000_000_000L);
        assertEquals(stringField(evidenceRow(evidence, help.invocationId()), "noticeBatchPublicationId"),
                stringField(evidenceRow(evidence, unavailable.invocationId()), "noticeBatchPublicationId"));
        assertFalse(evidence.contains("\"noticeStatus\":\"SUPPRESSED\""));
    }

    @Test
    void cooldownCreatesDistinctFuturePublicationAtItsEligibleBoundary(@TempDir Path temporaryDirectory)
            throws Exception {
        AtomicLong now = new AtomicLong(3_000_000_000L);
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        port.dispatchClock = now::get;
        port.rateLimiter = new InvocationRateLimiter();
        TestSource source = TestSource.player(GRANTED_PLAYER, false);
        InteractionIngress.Captured first = new InteractionIngress.Captured(
                UUID.randomUUID(), now.get(), InteractionIngress.Origin.COMMAND);
        assertEquals(1, InteractionIngress.execute(first, source, "worldspeaker help",
                InteractionIngress.Route.HELP, null, port));

        now.set(3_200_000_000L);
        assertEquals(1, port.scheduler.drainDispositions(new SmokeTickBudget(now.get()), 0));

        now.set(3_201_000_000L);
        InteractionIngress.Captured cooldown = new InteractionIngress.Captured(
                UUID.randomUUID(), now.get(), InteractionIngress.Origin.COMMAND);
        assertEquals(1, InteractionIngress.execute(cooldown, source, "worldspeaker help",
                InteractionIngress.Route.HELP, null, port));
        assertEquals(1, port.scheduler.pendingDispositionCount());
        assertEquals(0, port.scheduler.drainDispositions(new SmokeTickBudget(now.get()), 0));
        assertTrue(port.playerMessages.size() == 1, "a future publication is not sent before notBefore");

        now.set(4_199_000_000L);
        assertEquals(0, port.scheduler.drainDispositions(new SmokeTickBudget(now.get()), 0));
        now.set(4_200_000_000L);
        assertEquals(1, port.scheduler.drainDispositions(new SmokeTickBudget(now.get()), 0));
        assertEquals(2, port.playerMessages.size());
        assertEquals(0, port.rateLimiter.pendingNoticeMembers());
        assertEquals(0, port.metrics.countersSnapshot()[TickMetrics.Counter.DISPOSITION_QUEUE_OVERFLOWS.ordinal()]);

        String evidence = metricsSnapshot(port.metrics, temporaryDirectory.resolve("cooldown-publications.jsonl"));
        String firstRow = evidenceRow(evidence, first.invocationId());
        String secondRow = evidenceRow(evidence, cooldown.invocationId());
        assertTrue(firstRow.contains("\"noticeBatchResult\":\"SUBMITTED\""));
        assertTrue(firstRow.contains("\"noticeBatchAttemptedAtNanos\":\"3200000000\""));
        assertTrue(secondRow.contains("\"noticeBatchResult\":\"SUBMITTED\""));
        assertTrue(secondRow.contains("\"noticeBatchAttemptedAtNanos\":\"4200000000\""));
        String previousPublication = stringField(firstRow, "noticeBatchPublicationId");
        String nextPublication = stringField(secondRow, "noticeBatchPublicationId");
        assertNotNull(nextPublication);
        assertNotNull(previousPublication);
        assertNotEquals(previousPublication, nextPublication,
                "a later invocation cannot borrow the previously published batch's t1; " +
                        firstRow + " / " + secondRow);
        assertSubmittedNoticeBeforeDeadline(evidence, first.invocationId(), 3_200_000_000L,
                3_000_000_000L, 1_000_000_000L);
        assertSubmittedNoticeBeforeDeadline(evidence, cooldown.invocationId(), 4_200_000_000L,
                3_201_000_000L, 1_000_000_000L);
    }

    @Test
    void composedNoticeCanCarryASeparateRateWarningCause() throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        port.dispatchClock = () -> 5_000;
        port.rateLimiter = new InvocationRateLimiter();
        TestSource source = TestSource.player(GRANTED_PLAYER, false);
        for (int index = 0; index < InvocationRateLimiter.INVOCATION_BURST; index++) {
            InteractionIngress.Captured help = new InteractionIngress.Captured(
                    UUID.randomUUID(), 4_000 + index, InteractionIngress.Origin.COMMAND);
            assertEquals(1, InteractionIngress.execute(help, source, "worldspeaker help",
                    InteractionIngress.Route.HELP, null, port));
        }
        InteractionIngress.Captured limited = new InteractionIngress.Captured(
                UUID.randomUUID(), 4_100, InteractionIngress.Origin.COMMAND);
        assertEquals(0, InteractionIngress.execute(limited, source, "worldspeaker ask limited",
                InteractionIngress.Route.ASK, "limited", port));
        assertTrue(port.submissions.isEmpty());
        assertEquals(1, port.scheduler.pendingDispositionCount());

        port.drainAllDispositions();
        assertEquals(1, port.playerMessages.size());
        assertTrue(port.playerMessages.get(0).contains("use /worldspeaker ask"));
        assertTrue(port.playerMessages.get(0).contains("Please wait before trying again"));

        long invalidRateMask = 1L << InvocationRateLimiter.NoticeCause.GUIDANCE_RATE_LIMITED.ordinal();
        String invalidRateComposition = InteractionIngress.composeNotice(invalidRateMask,
                "No new request was accepted. Please wait before trying again.");
        assertTrue(invalidRateComposition.contains("Usage: /worldspeaker ask"));
        assertTrue(invalidRateComposition.contains("Please wait before trying again"));
    }

    @Test
    void sixteenInvalidInputsKeepTheirOutcomesAndComposeOnePrivateThrottleNotice(@TempDir Path temporaryDirectory)
            throws Exception {
        long firstCaptureNanos = 3_000_000_000L;
        long oneMillisecond = TimeUnit.MILLISECONDS.toNanos(1);
        AtomicLong now = new AtomicLong(firstCaptureNanos);
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        port.dispatchClock = now::get;
        port.rateLimiter = new InvocationRateLimiter();
        TestSource source = TestSource.player(GRANTED_PLAYER, false);
        List<InteractionIngress.Captured> captures = new ArrayList<>();

        for (int index = 0; index < 16; index++) {
            InteractionIngress.Captured captured = new InteractionIngress.Captured(
                    UUID.randomUUID(), now.get(), InteractionIngress.Origin.COMMAND);
            captures.add(captured);
            assertEquals(0, InteractionIngress.execute(captured, source,
                    "worldspeaker unknown private-sentinel-" + index,
                    InteractionIngress.Route.INVALID, "private-sentinel-" + index, port));
            now.addAndGet(oneMillisecond);
        }

        assertEquals(1, port.scheduler.pendingDispositionCount(), "all sixteen members share one callback");
        assertEquals(16, port.rateLimiter.pendingNoticeMembers());
        assertTrue(port.submissions.isEmpty(), "invalid and throttled input never reaches model admission");
        port.drainAllDispositions();

        String usage = "Usage: /worldspeaker ask <text> (up to 512 UTF-16 units), /worldspeaker end, or /worldspeaker help.";
        String throttled = "Your requests are being throttled. No new work was accepted. Please wait before trying again.";
        assertEquals(List.of(VanillaDialogue.LABEL + usage + " " + throttled), port.playerMessages);
        assertTrue(port.playerMessages.get(0).length() <= VanillaDialogue.MAX_RENDERED_LENGTH);
        assertFalse(port.playerMessages.get(0).contains("private-sentinel"), "raw input is never echoed");
        assertFalse(port.playerMessages.get(0).contains("Archivist:"), "a rejected input never gets a persona ACK");

        long[] counters = port.metrics.countersSnapshot();
        assertEquals(16, counters[TickMetrics.Counter.INTERACTION_INVALID.ordinal()]);
        assertEquals(8, counters[TickMetrics.Counter.INVOCATION_RATE_LIMITED.ordinal()]);
        assertEquals(0, counters[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);

        String evidence = metricsSnapshot(port.metrics, temporaryDirectory.resolve("sixteen-invalid-notices.jsonl"));
        String publicationId = null;
        for (int index = 0; index < captures.size(); index++) {
            String row = evidenceRow(evidence, captures.get(index).invocationId());
            assertTrue(row.contains("\"outcome\":\"INVALID\""), row);
            assertEquals(index < 8 ? "ALLOWED" : "INVOCATION_LIMITED", stringField(row, "rateLimitStatus"));
            assertTrue(row.contains("\"noticeBatchResult\":\"SUBMITTED\""), row);
            String memberPublication = stringField(row, "noticeBatchPublicationId");
            if (publicationId == null) publicationId = memberPublication;
            assertEquals(publicationId, memberPublication, "every invalid row links to the one actual batch send");
        }
        assertNotNull(publicationId);
        assertFalse(evidence.contains("private-sentinel"), "body text is absent from retained numeric evidence");
    }

    @Test
    void limitedEarlyHelpEndAndDenialKeepTheirDispositionAndExposeTheThrottle(@TempDir Path temporaryDirectory)
            throws Exception {
        record EarlyCase(String name, InteractionIngress.Route route, String text,
                         InteractionIngress.Outcome outcome, String visibleCause, boolean granted,
                         boolean operator) {}
        List<EarlyCase> cases = List.of(
                new EarlyCase("help", InteractionIngress.Route.HELP, null,
                        InteractionIngress.Outcome.HELP, "Help:", true, true),
                new EarlyCase("end", InteractionIngress.Route.END, null,
                        InteractionIngress.Outcome.FOLLOWUP_ENDED, "Follow-up recognition ended locally", true, false),
                new EarlyCase("denied", InteractionIngress.Route.ASK, "private-sentinel",
                        InteractionIngress.Outcome.DENIED, "not authorized to converse", false, true));
        long oneMillisecond = TimeUnit.MILLISECONDS.toNanos(1);

        for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
            EarlyCase testCase = cases.get(caseIndex);
            long firstCaptureNanos = 10_000_000_000L + caseIndex * TimeUnit.SECONDS.toNanos(2);
            AtomicLong now = new AtomicLong(firstCaptureNanos);
            UUID playerId = new UUID(10, caseIndex + 1);
            TestPort port = new TestPort(config(testCase.granted()
                    ? InteractionPolicy.parse(playerId.toString()) : InteractionPolicy.denyAll()));
            port.dispatchClock = now::get;
            port.rateLimiter = new InvocationRateLimiter();
            TestSource source = TestSource.player(playerId, testCase.operator());
            List<InteractionIngress.Captured> captures = new ArrayList<>();

            for (int index = 0; index < InvocationRateLimiter.INVOCATION_BURST; index++) {
                InteractionIngress.Captured captured = new InteractionIngress.Captured(
                        UUID.randomUUID(), now.get(), InteractionIngress.Origin.COMMAND);
                captures.add(captured);
                assertEquals(0, InteractionIngress.execute(captured, source,
                        "worldspeaker unknown", InteractionIngress.Route.INVALID, "unknown", port));
                now.addAndGet(oneMillisecond);
            }

            InteractionIngress.Captured limited = new InteractionIngress.Captured(
                    UUID.randomUUID(), now.get(), InteractionIngress.Origin.COMMAND);
            captures.add(limited);
            String command = testCase.route() == InteractionIngress.Route.ASK
                    ? "worldspeaker ask private-sentinel" : "worldspeaker " + testCase.route().name().toLowerCase();
            int expectedResult = testCase.outcome() == InteractionIngress.Outcome.DENIED ? 0 : 1;
            assertEquals(expectedResult, InteractionIngress.execute(limited, source, command,
                    testCase.route(), testCase.text(), port));
            assertEquals(1, port.scheduler.pendingDispositionCount());
            assertTrue(port.submissions.isEmpty());
            port.drainAllDispositions();

            assertEquals(1, port.playerMessages.size());
            assertTrue(port.playerMessages.get(0).contains(testCase.visibleCause()), port.playerMessages.get(0));
            assertTrue(port.playerMessages.get(0).contains("Your requests are being throttled"),
                    "the safe notice exposes the consumed invocation limit");
            assertTrue(port.playerMessages.get(0).contains("No new work was accepted"));
            assertFalse(port.playerMessages.get(0).contains("Archivist:"));

            String evidence = metricsSnapshot(port.metrics,
                    temporaryDirectory.resolve("limited-early-" + testCase.name() + ".jsonl"));
            String row = evidenceRow(evidence, limited.invocationId());
            assertTrue(row.contains("\"outcome\":\"" + testCase.outcome().name() + "\""), row);
            assertEquals("INVOCATION_LIMITED", stringField(row, "rateLimitStatus"));
            assertEquals(1, port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATION_RATE_LIMITED.ordinal()]);
        }

        TestPort unauthenticated = new TestPort(config(InteractionPolicy.denyAll()));
        unauthenticated.rateLimiter = new InvocationRateLimiter();
        TestSource console = TestSource.of("console", InteractionIngress.SourceFacts.nonPlayer(), true);
        InteractionIngress.Captured authCapture = new InteractionIngress.Captured(
                UUID.randomUUID(), 20_000_000_000L, InteractionIngress.Origin.COMMAND);
        assertEquals(0, InteractionIngress.execute(authCapture, console, "worldspeaker ask private-sentinel",
                InteractionIngress.Route.ASK, "private-sentinel", unauthenticated));
        assertEquals(0, unauthenticated.rateLimiter.trackedSubjects(), "unauthenticated sources are not charged");
        unauthenticated.drainAllDispositions();
        assertTrue(unauthenticated.playerMessages.isEmpty());
        assertEquals(1, unauthenticated.sourceMessages.size());
        assertFalse(unauthenticated.sourceMessages.get(0).contains("throttled"));
        String authEvidence = metricsSnapshot(unauthenticated.metrics,
                temporaryDirectory.resolve("unauthenticated-notice.jsonl"));
        String authRow = evidenceRow(authEvidence, authCapture.invocationId());
        assertTrue(authRow.contains("\"outcome\":\"UNAUTHENTICATED\""));
        assertEquals("NOT_CHECKED", stringField(authRow, "rateLimitStatus"));
    }

    @Test
    void limitedInvalidJoiningAfterPublicationGetsANewCooldownBatchAndTruthfulFutureSend(
            @TempDir Path temporaryDirectory) throws Exception {
        long firstCaptureNanos = 3_000_000_000L;
        long oneMillisecond = TimeUnit.MILLISECONDS.toNanos(1);
        AtomicLong now = new AtomicLong(firstCaptureNanos);
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        port.dispatchClock = now::get;
        port.rateLimiter = new InvocationRateLimiter();
        TestSource source = TestSource.player(GRANTED_PLAYER, false);

        InteractionIngress.Captured first = new InteractionIngress.Captured(
                UUID.randomUUID(), now.get(), InteractionIngress.Origin.COMMAND);
        assertEquals(0, InteractionIngress.execute(first, source, "worldspeaker unknown",
                InteractionIngress.Route.INVALID, "unknown", port));
        now.set(3_200_000_000L);
        assertEquals(1, port.scheduler.drainDispositions(new SmokeTickBudget(now.get()), 0));
        String usage = "Usage: /worldspeaker ask <text> (up to 512 UTF-16 units), /worldspeaker end, or /worldspeaker help.";
        assertEquals(VanillaDialogue.LABEL + usage, port.playerMessages.get(0),
                "a nonlimited input remains usage-only");

        now.set(3_201_000_000L);
        List<InteractionIngress.Captured> futureMembers = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            InteractionIngress.Captured captured = new InteractionIngress.Captured(
                    UUID.randomUUID(), now.get(), InteractionIngress.Origin.COMMAND);
            futureMembers.add(captured);
            assertEquals(0, InteractionIngress.execute(captured, source, "worldspeaker unknown",
                    InteractionIngress.Route.INVALID, "unknown", port));
            now.addAndGet(oneMillisecond);
        }
        assertEquals(1, port.scheduler.pendingDispositionCount(), "cooldown owns one future callback");
        assertEquals(8, port.rateLimiter.pendingNoticeMembers());
        assertEquals(0, port.scheduler.drainDispositions(new SmokeTickBudget(4_199_000_000L), 0));
        assertEquals(1, port.playerMessages.size(), "the future batch cannot borrow the earlier publication");

        now.set(4_200_000_000L);
        assertEquals(1, port.scheduler.drainDispositions(new SmokeTickBudget(now.get()), 0));
        assertEquals(2, port.playerMessages.size());
        assertEquals(VanillaDialogue.LABEL + usage + " " +
                        "Your requests are being throttled. No new work was accepted. Please wait before trying again.",
                port.playerMessages.get(1));
        assertEquals(0, port.rateLimiter.pendingNoticeMembers());

        long[] counters = port.metrics.countersSnapshot();
        assertEquals(1, counters[TickMetrics.Counter.INVOCATION_RATE_LIMITED.ordinal()]);
        String evidence = metricsSnapshot(port.metrics,
                temporaryDirectory.resolve("invalid-cooldown-publications.jsonl"));
        String firstRow = evidenceRow(evidence, first.invocationId());
        assertTrue(firstRow.contains("\"noticeBatchAttemptedAtNanos\":\"3200000000\""));
        String previousPublication = stringField(firstRow, "noticeBatchPublicationId");
        String futurePublication = stringField(evidenceRow(evidence,
                futureMembers.get(futureMembers.size() - 1).invocationId()), "noticeBatchPublicationId");
        assertNotEquals(previousPublication, futurePublication);
        assertSubmittedNoticeBeforeDeadline(evidence, first.invocationId(), 3_200_000_000L,
                firstCaptureNanos, 1_000_000_000L);
        assertSubmittedNoticeBeforeDeadline(evidence, futureMembers.get(0).invocationId(),
                4_200_000_000L, 3_201_000_000L, 1_000_000_000L);
        for (int index = 0; index < futureMembers.size(); index++) {
            String row = evidenceRow(evidence, futureMembers.get(index).invocationId());
            assertTrue(row.contains("\"outcome\":\"INVALID\""), row);
            assertEquals(index < 7 ? "ALLOWED" : "INVOCATION_LIMITED", stringField(row, "rateLimitStatus"));
            assertEquals(futurePublication, stringField(row, "noticeBatchPublicationId"));
        }
    }

    @Test
    void batchQueueOverflowClosesAdmissionAndLeavesTheOverflowRowExplicit(@TempDir Path temporaryDirectory)
            throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        port.rateLimiter = new InvocationRateLimiter();
        port.dispatchClock = () -> 1_000;
        InteractionIngress.Captured overflowCapture = null;
        for (int index = 0; index <= FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS; index++) {
            UUID playerId = new UUID(2, index + 1);
            TestSource source = TestSource.player(playerId, false);
            InteractionIngress.Captured captured = new InteractionIngress.Captured(
                    UUID.randomUUID(), 900 + index, InteractionIngress.Origin.COMMAND);
            int result = InteractionIngress.execute(captured, source, "worldspeaker help",
                    InteractionIngress.Route.HELP, null, port);
            assertEquals(index < FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS ? 1 : 0, result);
            if (index == FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS) overflowCapture = captured;
        }
        assertEquals(FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS, port.scheduler.pendingDispositionCount());
        assertTrue(port.admissionClosedForDispositionOverflow);
        assertEquals(1, port.metrics.countersSnapshot()[TickMetrics.Counter.DISPOSITION_QUEUE_OVERFLOWS.ordinal()]);
        port.drainAllDispositions();

        String evidence = metricsSnapshot(port.metrics, temporaryDirectory.resolve("notice-batch-overflow.jsonl"));
        String overflowId = overflowCapture.invocationId().toString();
        String row = evidence.lines()
                .filter(line -> line.contains("\"id\":\"" + overflowId + "\""))
                .findFirst().orElseThrow();
        assertTrue(row.contains("\"outcome\":\"HELP\""));
        assertTrue(row.contains("\"noticeStatus\":\"OVERFLOW\""));
        assertTrue(row.contains("\"sendResult\":\"DEFINITELY_UNSENT\""));
    }

    @Test
    void fullQueueRejectsCooldownFutureAsAnExplicitMissWithoutHidingIt(@TempDir Path temporaryDirectory)
            throws Exception {
        AtomicLong now = new AtomicLong();
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        port.dispatchClock = now::get;
        port.rateLimiter = new InvocationRateLimiter();
        TestSource repeatSender = TestSource.player(GRANTED_PLAYER, false);
        InteractionIngress.Captured first = new InteractionIngress.Captured(
                UUID.randomUUID(), 0, InteractionIngress.Origin.COMMAND);
        assertEquals(1, InteractionIngress.execute(first, repeatSender, "worldspeaker help",
                InteractionIngress.Route.HELP, null, port));
        assertEquals(1, port.scheduler.drainDispositions(new SmokeTickBudget(0), 0));

        now.set(100_000_000L);
        for (int index = 0; index < FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS; index++) {
            UUID playerId = new UUID(3, index + 1);
            TestSource otherPlayer = TestSource.player(playerId, false);
            InteractionIngress.Captured captured = new InteractionIngress.Captured(
                    UUID.randomUUID(), now.get(), InteractionIngress.Origin.COMMAND);
            assertEquals(1, InteractionIngress.execute(captured, otherPlayer, "worldspeaker help",
                    InteractionIngress.Route.HELP, null, port));
        }
        assertEquals(FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS, port.scheduler.pendingDispositionCount());

        InteractionIngress.Captured future = new InteractionIngress.Captured(
                UUID.randomUUID(), now.get(), InteractionIngress.Origin.COMMAND);
        assertEquals(0, InteractionIngress.execute(future, repeatSender, "worldspeaker help",
                InteractionIngress.Route.HELP, null, port));
        assertEquals(FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS, port.scheduler.pendingDispositionCount());
        assertTrue(port.admissionClosedForDispositionOverflow);

        assertEquals(SmokeTickBudget.MAX_OPERATIONS,
                port.scheduler.drainDispositions(new SmokeTickBudget(now.get()), 0));
        assertEquals(FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS - SmokeTickBudget.MAX_OPERATIONS,
                port.scheduler.drainDispositions(new SmokeTickBudget(now.get()), 0));
        String evidence = metricsSnapshot(port.metrics, temporaryDirectory.resolve("cooldown-overflow.jsonl"));
        String row = evidenceRow(evidence, future.invocationId());
        assertTrue(row.contains("\"noticeStatus\":\"OVERFLOW\""));
        assertTrue(row.contains("\"sendResult\":\"DEFINITELY_UNSENT\""));
        assertFalse(row.contains("\"noticeBatchPublicationId\":\""),
                "a queue-full future is an explicit miss, never a fabricated publication");
    }

    @Test
    void transformedCommandSourceCannotUseOverflowReceiptToSelectOrNotifyItsTarget(@TempDir Path temporaryDirectory)
            throws Exception {
        TestConnection connection = new TestConnection(74);
        List<InvocationCapture> captures = new ArrayList<>();
        try {
            for (int index = 0; index < CaptureHandoffBudget.MAX_PER_CONNECTION + 1; index++) {
                long receiptNanos = 70_000L + index;
                captures.add(InvocationCapture.captureHandoff(() -> receiptNanos,
                        () -> 1_793_275_200_000L, connection, 74, InvocationCapture.Source.SIGNED_COMMAND));
            }
            InteractionIngress.Captured captured = InteractionIngress.Captured.fromPacket(
                    captures.get(captures.size() - 1), InteractionIngress.Origin.COMMAND, 75_000);
            TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
            TestSource transformed = TestSource.direct("execute-as", facts(GRANTED_PLAYER, true, true, connection), false)
                    .asSubstituted();

            assertEquals(0, InteractionIngress.execute(captured, transformed, "worldspeaker ask private-sentinel",
                    InteractionIngress.Route.ASK, "private-sentinel", port));
            assertTrue(port.submissions.isEmpty());
            assertEquals(0, port.scheduler.pendingDispositionCount());
            assertTrue(port.playerMessages.isEmpty(), "an authenticated-looking target is not the original issuer");
            assertEquals(1, port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_UNAUTHENTICATED.ordinal()]);
            String evidence = metricsSnapshot(port.metrics,
                    temporaryDirectory.resolve("overflow-transformed-source-evidence.jsonl"));
            assertTrue(evidence.contains("\"player\":null"));
            assertTrue(evidence.contains("\"actualWorld\":null"));
            assertFalse(evidence.contains(GRANTED_PLAYER.toString()),
                    "the substituted target is not attributed as the original sender");
        } finally {
            for (InvocationCapture capture : captures) capture.releaseHandoff();
            CaptureHandoffBudget.retireConnection(connection);
        }
    }

    @Test
    void requestLinkAndQueueTimestampRecordedDuringAdmissionAreAppliedAfterInvocationEvidenceExists(
            @TempDir Path temporaryDirectory) throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        port.linkDuringSubmit = true;
        port.scheduler = new FirstReplyScheduler(() -> 350, () -> 1_793_275_200_000L);
        InteractionIngress.Captured captured = new InteractionIngress.Captured(
                UUID.fromString("00000000-0000-4000-8000-000000000120"), 100,
                InteractionIngress.Origin.COMMAND);

        assertEquals(1, InteractionIngress.execute(captured, TestSource.player(GRANTED_PLAYER, false),
                "worldspeaker ask linked", InteractionIngress.Route.ASK, "linked", port));

        String evidence = metricsSnapshot(port.metrics, temporaryDirectory.resolve("ingress-link-evidence.jsonl"));
        assertTrue(evidence.contains("\"requestId\":\"request-during-submit\""));
        assertTrue(evidence.contains("\"queuedAtNanos\":\"350\""));
        assertTrue(evidence.contains("\"queueDepthAtAdmission\":1"));
        assertTrue(evidence.contains("\"queueDelayNanos\":\"250\""));
    }

    private static long histogramCount(TickMetrics metrics, TickMetrics.FirstPublicSource source,
                                       TickMetrics.FirstPublicDisposition disposition) {
        long count = 0;
        for (long bucket : metrics.firstPublicDispatchHistogramSnapshot()[source.ordinal()][disposition.ordinal()]) {
            count += bucket;
        }
        return count;
    }

    private static String metricsSnapshot(TickMetrics metrics, Path path) throws Exception {
        assertTrue(metrics.offerNumericSnapshot(path));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Files.exists(path) && Files.size(path) > 0) {
                String snapshot = Files.readString(path);
                if (snapshot.endsWith("\n")) {
                    metrics.close();
                    return snapshot;
                }
            }
            Thread.sleep(10);
        }
        metrics.close();
        throw new AssertionError("metrics snapshot did not complete within the bounded wait");
    }

    private static String evidenceRow(String evidence, UUID invocationId) {
        String marker = "\"id\":\"" + invocationId + "\"";
        int idPosition = evidence.indexOf(marker);
        if (idPosition < 0) throw new AssertionError("missing evidence row for " + invocationId);
        int objectStart = evidence.lastIndexOf('{', idPosition);
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = objectStart; index < evidence.length(); index++) {
            char current = evidence.charAt(index);
            if (inString) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') inString = false;
                continue;
            }
            if (current == '"') inString = true;
            else if (current == '{') depth++;
            else if (current == '}' && --depth == 0) return evidence.substring(objectStart, index + 1);
        }
        throw new AssertionError("unterminated evidence row for " + invocationId);
    }

    private static String stringField(String row, String field) {
        String marker = "\"" + field + "\":\"";
        int valueStart = row.indexOf(marker);
        if (valueStart < 0) throw new AssertionError("missing string field " + field);
        valueStart += marker.length();
        int end = row.indexOf('"', valueStart);
        if (end < 0) throw new AssertionError("unterminated string field " + field);
        return row.substring(valueStart, end);
    }

    private static void assertSubmittedNoticeBeforeDeadline(String evidence, UUID invocationId,
                                                             long attemptedAtNanos, long capturedAtNanos,
                                                             long maximumLatencyNanos) {
        String row = evidenceRow(evidence, invocationId);
        assertTrue(row.contains("\"noticeBatchResult\":\"SUBMITTED\""));
        long actualAttempt = Long.parseLong(stringField(row, "noticeBatchAttemptedAtNanos"));
        assertEquals(attemptedAtNanos, actualAttempt);
        assertTrue(actualAttempt - capturedAtNanos <= maximumLatencyNanos,
                "the new physical publication meets this row's original deadline");
    }

    @Test
    void everyEarlyAndAdmissionDispositionIsCountedOnceAndHelpIsNotAnAgentTurn() throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        CommandDispatcher<TestSource> dispatcher = dispatcher(port, new AtomicLong(4_000));
        TestSource player = TestSource.player(GRANTED_PLAYER, false);
        assertEquals(1, dispatcher.execute("worldspeaker help", player));
        assertEquals(0, dispatcher.execute("worldspeaker help tail", player));
        assertEquals(0, dispatcher.execute("worldspeaker ask no grant", TestSource.player(OPERATOR_WITHOUT_GRANT, true)));
        port.config = null;
        assertEquals(0, dispatcher.execute("worldspeaker ask unavailable", player));
        port.config = config(InteractionPolicy.parse(GRANTED_PLAYER.toString()));
        port.nextAdmission = InteractionIngress.Outcome.UNAVAILABLE;
        assertEquals(0, dispatcher.execute("worldspeaker ask upstream unavailable", player));
        port.nextAdmission = InteractionIngress.Outcome.BUSY;
        assertEquals(0, dispatcher.execute("worldspeaker ask busy", player));
        port.nextAdmission = InteractionIngress.Outcome.COALESCED;
        assertEquals(0, dispatcher.execute("worldspeaker ask repeat", player));
        port.nextAdmission = InteractionIngress.Outcome.ADMITTED;
        assertEquals(1, dispatcher.execute("worldspeaker ask accepted", player));
        port.drainAllDispositions();

        long[] counts = port.metrics.countersSnapshot();
        assertEquals(1, counts[TickMetrics.Counter.INTERACTION_HELP.ordinal()]);
        assertEquals(1, counts[TickMetrics.Counter.INTERACTION_INVALID.ordinal()]);
        assertEquals(1, counts[TickMetrics.Counter.INTERACTION_DENIED.ordinal()]);
        assertEquals(1, counts[TickMetrics.Counter.INTERACTION_UNAVAILABLE_BEFORE_BOOTSTRAP.ordinal()]);
        assertEquals(1, counts[TickMetrics.Counter.INTERACTION_UNAVAILABLE.ordinal()]);
        assertEquals(1, counts[TickMetrics.Counter.INTERACTION_BUSY.ordinal()]);
        assertEquals(1, counts[TickMetrics.Counter.INTERACTION_COALESCED.ordinal()]);
        assertEquals(1, counts[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);
        assertEquals(8, counts[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
        assertEquals(List.of(false, false, false, false, true, true, true), port.dispositionGrantChecks,
                "only dispositions reached after a grant-checked admission recheck that grant at publication");
        assertFalse(port.playerMessages.stream().anyMatch(line -> line.contains("Archivist:")),
                "refusals and guidance never use the character acknowledgement persona");
        assertTrue(port.playerMessages.stream().anyMatch(line -> line.startsWith("[Worldspeaker] Worldspeaker:")),
                "help and command guidance retain the fixed Worldspeaker origin rather than posing as the character");
    }

    @Test
    void nineIngressDispositionsShareOneEarliestDeadlineTickBudgetAndPreserveCarryoverT0() throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        AtomicLong now = new AtomicLong();
        port.dispatchClock = now::get;
        port.playerSendWork = () -> now.addAndGet(125_000);
        TestSource[] sources = new TestSource[9];
        long[] captureTimes = {800, 200, 900, 100, 700, 300, 600, 400, 500};

        for (int index = 0; index < captureTimes.length; index++) {
            sources[index] = TestSource.player(GRANTED_PLAYER, false);
            InteractionIngress.Captured captured = new InteractionIngress.Captured(
                    UUID.randomUUID(), captureTimes[index], InteractionIngress.Origin.COMMAND);
            assertEquals(1, InteractionIngress.execute(captured, sources[index], "worldspeaker help",
                    InteractionIngress.Route.HELP, null, port));
        }

        assertEquals(9, port.scheduler.pendingDispositionCount());
        assertTrue(port.playerMessages.isEmpty(), "enqueue is not a publication result");
        assertEquals(0, port.metrics.maxFirstPublicDispatchNanos(), "enqueue must not record t1");
        assertEquals(0, histogramCount(port.metrics, TickMetrics.FirstPublicSource.COMMAND_HELP,
                TickMetrics.FirstPublicDisposition.HELP));

        SmokeTickBudget firstFrame = new SmokeTickBudget(now.get());
        SmokeDeadlineScan.Scan<String> noRequestDeadlines = SmokeDeadlineScan.scan(
                List.of(), now::get, 0, firstFrame);
        assertFalse(SmokeDispositionTick.forEachDueAction(noRequestDeadlines, port.scheduler, firstFrame,
                now::get, 0, ignored -> true), "frame exhaustion leaves the ninth item for the next tick");
        assertEquals(8, firstFrame.operationsStarted());
        assertEquals(1, firstFrame.deadlineRecordsChecked(), "one record owns the ordered queue head");
        assertTrue(now.get() <= SmokeTickBudget.MAX_START_NANOS,
                "the shared frame stops at its original one-millisecond boundary");
        assertEquals(1, port.scheduler.pendingDispositionCount());
        assertEquals(List.of(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L),
                port.deliveredConnections.stream().map(connection -> captureTimeFor(sources, captureTimes, connection)).toList(),
                "the fixed queue drains original first-reply deadlines, not callback insertion time");

        SmokeTickBudget carryoverFrame = new SmokeTickBudget(now.get());
        noRequestDeadlines = SmokeDeadlineScan.scan(List.of(), now::get, 0, carryoverFrame);
        assertTrue(SmokeDispositionTick.forEachDueAction(noRequestDeadlines, port.scheduler, carryoverFrame,
                now::get, 0, ignored -> true));
        assertEquals(1, carryoverFrame.operationsStarted());
        assertEquals(1, carryoverFrame.deadlineRecordsChecked());
        assertEquals(0, port.scheduler.pendingDispositionCount());
        assertEquals(9, port.playerMessages.size());
        assertEquals(900L, captureTimeFor(sources, captureTimes, port.deliveredConnections.get(8)),
                "carryover retains its original deadline rather than receiving a fresh allowance");
        assertEquals(9, histogramCount(port.metrics, TickMetrics.FirstPublicSource.COMMAND_HELP,
                TickMetrics.FirstPublicDisposition.HELP));
        assertEquals(1_124_100L, port.metrics.maxFirstPublicDispatchNanos(),
                "t1 is recorded after the ninth actual send, not when it was enqueued");
    }

    @Test
    void dispositionOverflowIsBoundedAndNeverClaimsAQueuedOrSuccessfulSend() throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        TestSource source = TestSource.player(GRANTED_PLAYER, false);

        for (int index = 0; index < FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS + 1; index++) {
            InteractionIngress.Captured captured = new InteractionIngress.Captured(
                    UUID.randomUUID(), 10_000 + index, InteractionIngress.Origin.COMMAND);
            int result = InteractionIngress.execute(captured, source, "worldspeaker help",
                    InteractionIngress.Route.HELP, null, port);
            assertEquals(index == FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS ? 0 : 1, result);
        }

        assertEquals(FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS, port.scheduler.pendingDispositionCount());
        assertTrue(port.playerMessages.isEmpty());
        assertEquals(1, port.metrics.countersSnapshot()[TickMetrics.Counter.DISPOSITION_QUEUE_OVERFLOWS.ordinal()]);
        assertEquals(1, port.metrics.countersSnapshot()[TickMetrics.Counter.FAILED_PUBLIC_DISPATCHES.ordinal()]);
        assertEquals(0, port.metrics.maxFirstPublicDispatchNanos());

        port.drainAllDispositions();
        assertEquals(FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS, port.playerMessages.size());
        assertEquals(FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS, histogramCount(port.metrics,
                TickMetrics.FirstPublicSource.COMMAND_HELP, TickMetrics.FirstPublicDisposition.HELP));
    }

    @Test
    void queuedIngressWorkSharesTheExistingEightStartsAndSeventeenDeadlineRecordFrame() throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        AtomicLong now = new AtomicLong();
        port.dispatchClock = now::get;
        TestSource source = TestSource.player(GRANTED_PLAYER, false);
        for (int index = 0; index < 9; index++) {
            InteractionIngress.Captured captured = new InteractionIngress.Captured(
                    UUID.randomUUID(), 30_000 + index, InteractionIngress.Origin.COMMAND);
            assertEquals(1, InteractionIngress.execute(captured, source, "worldspeaker help",
                    InteractionIngress.Route.HELP, null, port));
        }

        SmokeTickBudget activeFrame = new SmokeTickBudget(now.get());
        for (int index = 0; index < SmokeTickBudget.MAX_DEADLINE_RECORDS - 1; index++) {
            activeFrame.deadlineChecked();
        }
        activeFrame.deadlineChecked(); // ordered queue head shares the seventeenth record
        activeFrame.operationStarted(SmokeTickBudget.WorkClass.RESERVED);
        activeFrame.operationStarted(SmokeTickBudget.WorkClass.RESERVED);

        assertEquals(6, port.scheduler.drainDispositions(activeFrame, 1));
        assertEquals(SmokeTickBudget.MAX_OPERATIONS, activeFrame.operationsStarted());
        assertEquals(SmokeTickBudget.MAX_DEADLINE_RECORDS, activeFrame.deadlineRecordsChecked());
        assertEquals(3, port.scheduler.pendingDispositionCount(),
                "the scheduler carries the original three queued items into a later frame");
    }

    @Test
    void nonPlayerRefusalIsDeferredToItsRetainedPrivateSourceOutput() {
        TestPort port = new TestPort(config(InteractionPolicy.denyAll()));
        TestSource originalConsole = TestSource.of("console", InteractionIngress.SourceFacts.nonPlayer(), true);
        InteractionIngress.Captured captured = new InteractionIngress.Captured(
                UUID.randomUUID(), 20_000, InteractionIngress.Origin.COMMAND);

        assertEquals(0, InteractionIngress.execute(captured, originalConsole, "worldspeaker ask private",
                InteractionIngress.Route.ASK, "private", port));
        assertTrue(port.sourceMessages.isEmpty(), "the refusal is queued, not broadcast from ingress");
        assertTrue(port.playerMessages.isEmpty());
        assertEquals(1, port.scheduler.pendingDispositionCount());

        port.drainAllDispositions();

        assertEquals(List.of("console:Worldspeaker conversation commands require an authenticated player source."),
                port.sourceMessages);
        assertSame(originalConsole, port.sourceTargets.get(0), "the retained command output is never replaced by a player lookup");
    }

    @Test
    void anUnknownDeferredSendIsPreservedAndNeverReplayed() throws Exception {
        TestPort port = new TestPort(config(InteractionPolicy.parse(GRANTED_PLAYER.toString())));
        port.throwDuringPlayerSubmit = true;
        TestSource source = TestSource.player(GRANTED_PLAYER, false);
        InteractionIngress.Captured captured = new InteractionIngress.Captured(
                UUID.randomUUID(), 40_000, InteractionIngress.Origin.COMMAND);

        assertEquals(1, InteractionIngress.execute(captured, source, "worldspeaker help",
                InteractionIngress.Route.HELP, null, port));
        assertEquals(0, port.metrics.countersSnapshot()[TickMetrics.Counter.UNKNOWN_PUBLIC_DISPATCHES.ordinal()],
                "the deferred API has not run yet");
        assertEquals(1, port.drainAllDispositions());
        assertEquals(1, port.metrics.countersSnapshot()[TickMetrics.Counter.UNKNOWN_PUBLIC_DISPATCHES.ordinal()]);
        assertEquals(0, port.metrics.countersSnapshot()[TickMetrics.Counter.FAILED_PUBLIC_DISPATCHES.ordinal()]);
        assertEquals(0, port.metrics.maxFirstPublicDispatchNanos());
        assertEquals(0, port.drainAllDispositions(), "an unknown result is not automatically replayed");
    }

    private static long captureTimeFor(TestSource[] sources, long[] captureTimes, Object connection) {
        for (int index = 0; index < sources.length; index++) {
            if (sources[index].facts().connection() == connection) return captureTimes[index];
        }
        throw new AssertionError("queued publisher used an unowned connection");
    }

    private static CommandDispatcher<TestSource> dispatcher(TestPort port, AtomicLong clock) {
        CommandDispatcher<TestSource> dispatcher = new CommandDispatcher<>();
        AskCommand.register(dispatcher, new AskCommand.Access<>() {
            @Override public InteractionIngress.Captured capture() {
                return InteractionIngress.Captured.capture(clock::getAndIncrement, InteractionIngress.Origin.COMMAND);
            }
            @Override public int execute(TestSource source, String input, InteractionIngress.Route route, String text,
                                         InteractionIngress.Captured captured) {
                return InteractionIngress.execute(captured, source, input, route, text, port);
            }
        });
        return dispatcher;
    }

    private static CompanionConfig config(InteractionPolicy policy) {
        return new CompanionConfig(URI.create("ws://127.0.0.1:1"), new byte[32], BINDING,
                "Hello {player}", "Archivist", policy);
    }

    private static InteractionIngress.SourceFacts facts(UUID id, boolean onlineMode, boolean current) {
        return new InteractionIngress.SourceFacts(true, onlineMode, current, id, "TestPlayer",
                "minecraft:overworld", new Object(), new Object());
    }

    private static InteractionIngress.SourceFacts facts(UUID id, boolean onlineMode, boolean current,
                                                        Object connection) {
        return new InteractionIngress.SourceFacts(true, onlineMode, current, id, "TestPlayer",
                "minecraft:overworld", new Object(), connection);
    }

    private record TestSource(String kind, InteractionIngress.SourceFacts facts, boolean operator,
                              Object commandOutput, Object currentEntity) {
        static TestSource of(String kind, InteractionIngress.SourceFacts facts, boolean operator) {
            return new TestSource(kind, facts, operator, null, null);
        }

        static TestSource direct(String kind, InteractionIngress.SourceFacts facts, boolean operator) {
            Object issuer = new Object();
            return new TestSource(kind, facts, operator, issuer, issuer);
        }

        static TestSource player(UUID id, boolean operator) {
            return direct("player", InteractionIngressTest.facts(id, true, true), operator);
        }

        TestSource asSubstituted() {
            return new TestSource("execute-as", facts, operator, commandOutput, new Object());
        }
    }

    private record TestConnection(long worldspeaker$connectionGeneration) implements ServerConnectionGeneration {}

    private static final class TestPacketCarrier implements PacketCaptureAccess {
        private InvocationCapture capture;

        @Override public InvocationCapture worldspeaker$getInvocationCapture() { return capture; }

        @Override public boolean worldspeaker$setInvocationCapture(InvocationCapture next) {
            if (capture != null || next == null) return false;
            capture = next;
            return true;
        }
    }

    private static final class TestPort implements InteractionIngress.Port<TestSource> {
        private CompanionConfig config;
        private final TickMetrics metrics = new TickMetrics();
        private final List<String> playerMessages = new ArrayList<>();
        private final List<String> sourceMessages = new ArrayList<>();
        private final List<Object> deliveredConnections = new ArrayList<>();
        private final List<TestSource> sourceTargets = new ArrayList<>();
        private final List<Boolean> dispositionGrantChecks = new ArrayList<>();
        private final List<Submission> submissions = new ArrayList<>();
        private InteractionIngress.Outcome nextAdmission = InteractionIngress.Outcome.ADMITTED;
        private LongSupplier dispatchClock = System::nanoTime;
        private FirstReplyScheduler scheduler = FirstReplyScheduler.system();
        private boolean playerDispatchSucceeds = true;
        private boolean throwDuringPlayerSubmit;
        private boolean enforceCurrentConnection;
        private Object currentConnection;
        private boolean useDefaultInputResolution;
        private boolean linkDuringSubmit;
        private boolean admissionClosedForDispositionOverflow;
        private Runnable playerSendWork = () -> {};
        private InvocationRateLimiter rateLimiter;

        private TestPort(CompanionConfig config) {
            this.config = config;
            this.scheduler = new FirstReplyScheduler(() -> dispatchClock.getAsLong(), System::currentTimeMillis);
        }
        @Override public InteractionIngress.SourceFacts resolve(TestSource source) {
            return CommandSourceOutputProvenance.outputIsCurrentEntity(source.commandOutput(), source.currentEntity())
                    ? source.facts()
                    : InteractionIngress.SourceFacts.nonPlayer();
        }
        @Override public InteractionIngress.SourceFacts resolve(TestSource source, String input) {
            return useDefaultInputResolution
                    ? InteractionIngress.Port.super.resolve(source, input)
                    : resolve(source);
        }
        @Override public CompanionConfig config() { return config; }
        @Override public TickMetrics metrics() { return metrics; }
        @Override public boolean serviceActive() { return config != null; }
        @Override public long monotonicNanos() { return dispatchClock.getAsLong(); }
        @Override public InvocationRateLimiter rateLimiter() { return rateLimiter; }
        @Override public FirstReplyScheduler firstReplyScheduler() { return scheduler; }
        @Override public InteractionIngress.Outcome submit(InteractionIngress.SourceFacts source, String text,
                                                              InteractionIngress.Invocation invocation) {
            submissions.add(new Submission(source, text, invocation));
            if (nextAdmission == InteractionIngress.Outcome.ADMITTED && !invocation.commitEvidenceAdmission(metrics)) {
                return InteractionIngress.Outcome.UNAVAILABLE;
            }
            if (linkDuringSubmit) invocation.recordRequestLink("request-during-submit", 1, true);
            return nextAdmission;
        }
        @Override public boolean publishPlayer(InteractionIngress.SourceFacts source, String message,
                                               boolean characterMessage, String characterDisplayName) {
            playerMessages.add(characterMessage
                    ? VanillaDialogue.renderCharacterLine(characterDisplayName, message)
                    : VanillaDialogue.renderLine(message));
            return playerDispatchSucceeds;
        }
        @Override public FirstReplyScheduler.PublicationResult submitPlayerDisposition(
                InteractionIngress.SourceFacts source, String message, boolean characterMessage,
                String characterDisplayName, boolean requireCurrentGrant) {
            deliveredConnections.add(source.connection());
            if (throwDuringPlayerSubmit) throw new IllegalStateException("send result unknown");
            if (enforceCurrentConnection && source.connection() != currentConnection) {
                return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
            }
            dispositionGrantChecks.add(requireCurrentGrant);
            FirstReplyScheduler.PublicationResult result = InteractionIngress.Port.super.submitPlayerDisposition(
                    source, message, characterMessage, characterDisplayName, requireCurrentGrant);
            if (result == FirstReplyScheduler.PublicationResult.SUBMITTED) playerSendWork.run();
            return result;
        }
        @Override public boolean publishSource(TestSource source, String message) {
            sourceMessages.add(source.kind() + ":" + message);
            sourceTargets.add(source);
            return true;
        }
        @Override public void closeAdmissionForDispositionOverflow() {
            admissionClosedForDispositionOverflow = true;
        }

        private int drainAllDispositions() {
            int total = 0;
            while (scheduler.pendingDispositionCount() > 0) {
                total += scheduler.drainDispositions(new SmokeTickBudget(scheduler.monotonicNanos()), 0);
            }
            return total;
        }
    }

    private record Submission(InteractionIngress.SourceFacts source, String text,
                              InteractionIngress.Invocation invocation) {}
}
