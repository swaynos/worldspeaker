package org.worldspeaker.bootstrap;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.network.ServerPlayerEntity;
import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.InteractionPolicy;
import org.worldspeaker.runtime.TickMetrics;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Callback policy tests with a fake Access adapter. Envelope reads and sender facts are injected,
 * not actual Minecraft message objects or production player authentication.
 */
class NaturalAddressListenerTest {
    private static final UUID SENDER_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final ConfiguredBinding BINDING = new ConfiguredBinding("server", "world", "stable-character", "agent");

    @Test
    void productionCallbackUsesSignedContentFactAndIgnoresDecoratedContent() {
        TestAccess access = new TestAccess(config(List.of()));
        String signedText = "Could you, Archivist, read this exact sentence?";
        access.signedMessageContent = signedText;
        access.decoratedContent = "decorated private sentinel";

        callback(access).onChatMessage(null, null, null);

        assertEquals(1, access.port.submissions.size());
        assertEquals(signedText, access.port.submissions.get(0).text());
        assertFalse(access.port.submissions.get(0).text().contains(access.decoratedContent));
        assertEquals(InteractionIngress.Origin.NATURAL_ADDRESS, access.port.submissions.get(0).invocation().origin());
        assertEquals(1, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);
        assertEquals(0, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_INVALID.ordinal()]);
        assertTrue(access.calls.indexOf("clock") < access.calls.indexOf("server-thread"));
    }

    @Test
    void capacityRejectedNaturalReceiptGetsBoundedSafeOutputWithoutReadingOrRoutingChatText() {
        TestConnection connection = new TestConnection(81);
        List<CaptureHandoffBudget.Lease> held = new ArrayList<>();
        try {
            for (int index = 0; index < CaptureHandoffBudget.MAX_PER_CONNECTION; index++) {
                held.add(CaptureHandoffBudget.tryAcquire(connection));
            }
            InvocationCapture overflow = InvocationCapture.captureHandoff(() -> 80_000L,
                    () -> 1_793_275_200_000L, connection, 81, InvocationCapture.Source.SIGNED_CHAT);
            TestCarrier carrier = new TestCarrier();
            assertTrue(overflow.handoffCapacityRejected());
            assertTrue(carrier.worldspeaker$setInvocationCapture(overflow));

            TestAccess access = new TestAccess(config(List.of()));
            access.sourceFacts = facts(SENDER_ID, "Sender", connection);
            access.port.facts = access.sourceFacts;
            access.port.rateLimiter = new InvocationRateLimiter();
            access.nextCapture = InteractionIngress.Captured.fromPacket(carrier.worldspeaker$getInvocationCapture(),
                    InteractionIngress.Origin.NATURAL_ADDRESS, 80_500);
            access.signedMessageContent = "Archivist, private-sentinel";

            callback(access).onChatMessage(null, null, null);

            assertTrue(access.calls.contains("source"), "the original signed sender is authenticated before refusal");
            assertTrue(access.calls.contains("ingress"));
            assertFalse(access.calls.contains("config"), "overflow handling needs no grant or model configuration");
            assertFalse(access.calls.contains("roster"), "the overflow path cannot inspect follow-up context");
            assertFalse(access.calls.contains("signed-content"), "packet text is not retained or routed");
            assertTrue(access.port.submissions.isEmpty(), "no model/context admission occurs");
            assertEquals(1, access.port.scheduler.pendingDispositionCount(), "output is deferred to the safe queue");
            assertTrue(access.port.playerMessages.isEmpty(), "the receive callback does not inline-send");

            access.port.drainAllDispositions();

            assertEquals(1, access.port.playerMessages.size());
            assertEquals(List.of(connection), access.port.deliveredConnections);
            assertTrue(access.port.playerMessages.get(0).contains("packet handoff capacity was full"));
            assertFalse(access.port.playerMessages.get(0).contains("private-sentinel"));
            assertEquals(0, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);
            assertEquals(1, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_BUSY.ordinal()]);
        } finally {
            for (CaptureHandoffBudget.Lease lease : held) if (lease != null) lease.release();
            CaptureHandoffBudget.retireConnection(connection);
        }
        assertEquals(0, CaptureHandoffBudget.outstanding(connection));
    }

    @Test
    void filteredNaturalChatReleasesItsCaptureLeaseWithoutChangingVanillaDelivery() {
        TestConnection connection = new TestConnection(82);
        InvocationCapture packet = InvocationCapture.captureHandoff(() -> 82_000L,
                () -> 1_793_275_200_000L, connection, 82, InvocationCapture.Source.SIGNED_CHAT);
        TestAccess access = new TestAccess(config(List.of()));
        access.passThrough = false;
        access.nextCapture = InteractionIngress.Captured.fromPacket(packet,
                InteractionIngress.Origin.NATURAL_ADDRESS, 82_500);

        callback(access).onChatMessage(null, null, null);

        assertTrue(packet.handoffLease().released(), "filtered observer return releases the packet lease");
        assertEquals(0, CaptureHandoffBudget.outstanding(connection));
        assertFalse(access.calls.contains("ingress"));
        assertTrue(access.port.submissions.isEmpty());
        assertTrue(access.port.playerMessages.isEmpty(), "filter observation never changes ordinary chat delivery");
    }

    @Test
    void onlySignedUnfilteredOrdinaryChatWithTheCurrentSenderUuidIsRouted() {
        List<TestAccess> messages = new ArrayList<>();
        TestAccess unsigned = new TestAccess(config(List.of()));
        unsigned.signed = false;
        unsigned.signedMessageContent = "Archivist: unsigned";
        messages.add(unsigned);
        TestAccess mismatched = new TestAccess(config(List.of()));
        mismatched.signedMessageSenderId = OTHER_ID;
        mismatched.signedMessageContent = "Archivist: mismatched sender UUID";
        messages.add(mismatched);
        TestAccess filtered = new TestAccess(config(List.of()));
        filtered.passThrough = false;
        filtered.signedMessageContent = "Archivist: filtered";
        messages.add(filtered);
        TestAccess partiallyFiltered = new TestAccess(config(List.of()));
        partiallyFiltered.passThrough = false;
        partiallyFiltered.signedMessageContent = "Archivist: partial";
        messages.add(partiallyFiltered);
        for (TestAccess access : messages) {
            callback(access).onChatMessage(null, null, null);
            assertTrue(access.port.submissions.isEmpty());
            assertEquals(0, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
        }

        TestAccess commandMessage = new TestAccess(config(List.of()));
        commandMessage.ordinaryChat = false;
        commandMessage.signedMessageContent = "Archivist: /say-style event";
        callback(commandMessage).onChatMessage(null, null, null);
        assertTrue(commandMessage.port.submissions.isEmpty(), "player command broadcasts are not ordinary chat");
        assertEquals(0, commandMessage.serverReads.get(), "type rejection precedes player/roster reads");
    }

    @Test
    void wrongThreadAndStaleEpochReturnBeforeAnyGameOrMessageReads() {
        TestAccess wrongThread = new TestAccess(config(List.of()));
        wrongThread.serverThread = false;
        callback(wrongThread).onChatMessage(null, null, null);
        assertEquals(List.of("clock", "server-thread"), wrongThread.calls);
        assertEquals(0, wrongThread.serverReads.get());
        assertEquals(0, wrongThread.port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);

        TestAccess staleEpoch = new TestAccess(config(List.of()));
        staleEpoch.epochCurrent = false;
        callback(staleEpoch).onChatMessage(null, null, null);
        assertEquals(List.of("clock", "server-thread", "active-epoch", "epoch-current"), staleEpoch.calls);
        assertEquals(0, staleEpoch.serverReads.get());
        assertEquals(0, staleEpoch.port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
    }

    @Test
    void absentConfigurationAmbientChatAndOfflineOrSelfPrefixesCreateNoTurn() {
        TestAccess absent = new TestAccess(null);
        run(absent, "ambient discussion", SENDER_ID);
        assertTrue(absent.port.submissions.isEmpty());
        assertEquals(0, absent.rosterReads.get(), "configuration absence leaves ordinary chat alone");

        TestAccess ambient = new TestAccess(config(List.of()));
        run(ambient, "ambient discussion", SENDER_ID);
        assertTrue(ambient.port.submissions.isEmpty());
        assertEquals(0, ambient.port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);

        TestAccess self = new TestAccess(config(List.of("Alex")));
        self.sourceFacts = facts(SENDER_ID, "Alex");
        self.roster = List.of(new NaturalAddressListener.PlayerIdentity(SENDER_ID, "Alex"));
        run(self, "Alex, are you there?", SENDER_ID);
        assertEquals(1, self.port.submissions.size(), "the sender's own prefix does not suppress character addressing");

        TestAccess offline = new TestAccess(config(List.of("Alex")));
        offline.roster = List.of(new NaturalAddressListener.PlayerIdentity(SENDER_ID, "Sender"));
        run(offline, "Alex, are you there?", SENDER_ID);
        assertEquals(1, offline.port.submissions.size(), "an offline name does not suppress an invocation");
    }

    @Test
    void anotherOnlinePlayersPrefixWinsEvenWhenThatTokenIsACharacterAlias() {
        TestAccess access = new TestAccess(config(List.of("Alex")));
        access.roster = List.of(new NaturalAddressListener.PlayerIdentity(SENDER_ID, "Sender"),
                new NaturalAddressListener.PlayerIdentity(OTHER_ID, "Alex"));

        run(access, "@aLeX: Alex, please answer", SENDER_ID);

        assertTrue(access.port.submissions.isEmpty());
        assertEquals(0, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
    }

    @Test
    void rosterSizeIsCheckedBeforeBoundedCopyAndOversizedRostersDisableRouting() {
        TestAccess capped = new TestAccess(config(List.of()));
        seedDeliveredFollowup(capped);
        capped.rosterSizeOverride = NaturalAddressParser.MAX_ONLINE_PLAYERS + 1;
        run(capped, "Archivist, answer", SENDER_ID);
        assertEquals(0, capped.playerAtReads.get(), "257 size is rejected before copying or scanning");
        assertTrue(capped.port.submissions.isEmpty());
        assertEquals(0, capped.followupWindow.size(), "an oversized roster closes the sender's delivered-answer window");

        TestAccess atLimit = new TestAccess(config(List.of()));
        atLimit.roster = new ArrayList<>();
        atLimit.roster.add(new NaturalAddressListener.PlayerIdentity(SENDER_ID, "Sender"));
        for (int index = 1; index < NaturalAddressParser.MAX_ONLINE_PLAYERS; index++) {
            atLimit.roster.add(new NaturalAddressListener.PlayerIdentity(UUID.randomUUID(), "Player" + index));
        }
        run(atLimit, "Archivist, answer", SENDER_ID);
        assertEquals(NaturalAddressParser.MAX_ONLINE_PLAYERS, atLimit.playerAtReads.get());
        assertEquals(1, atLimit.port.submissions.size());
    }

    @Test
    void rosterAcquisitionSizeIndexAndIdentityReadFailuresFailClosedAfterSenderResolution() {
        for (RosterFailure failure : List.of(RosterFailure.GETTER, RosterFailure.SIZE, RosterFailure.INDEX,
                RosterFailure.UUID, RosterFailure.NAME)) {
            TestAccess access = new TestAccess(config(List.of()));
            seedDeliveredFollowup(access);
            FollowupWindow.Context otherContext = otherFollowupContext(access);
            FollowupWindow.Candidate otherCandidate = access.followupWindow.beginExplicit(otherContext,
                    UUID.randomUUID(), InteractionIngress.Origin.COMMAND, access.clock.get() - 10);
            assertTrue(access.followupWindow.open(otherCandidate, otherContext, access.clock.get() - 5));
            access.calls.clear();
            access.rosterFailure = failure;

            run(access, "why?", SENDER_ID);

            assertTrue(access.calls.indexOf("source") >= 0, failure + " is handled after sender resolution");
            assertTrue(access.calls.indexOf("source") < access.calls.indexOf("roster"), failure.toString());
            assertTrue(access.calls.indexOf("roster") < access.calls.indexOf("invalidate"), failure.toString());
            assertFalse(access.calls.contains("signed-content"), failure + " exits before reading/routing the chat body");
            assertFalse(access.calls.contains("ingress"), failure + " never reaches shared ingress");
            assertTrue(access.port.submissions.isEmpty(), failure + " must not route partial or ambient text");
            assertEquals(0, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
            assertEquals(1, access.followupWindow.size(), failure + " closes only the authenticated sender's state");
            assertFalse(access.followupWindow.isOpen(access.followupContext(), access.clock.get(), access.clock.get()));
            assertTrue(access.followupWindow.isOpen(otherContext, access.clock.get(), access.clock.get()),
                    failure + " leaves another player's window untouched");
            assertTrue(access.port.playerMessages.isEmpty(), "roster exception details are never sent to chat");

            access.rosterFailure = RosterFailure.NONE;
            run(access, "why?", SENDER_ID);
            assertTrue(access.port.submissions.isEmpty(), "a later normal callback cannot use the failed line's stale window");
            assertTrue(access.followupWindow.isOpen(otherContext, access.clock.get(), access.clock.get()));
        }
    }

    @Test
    void rosterFailureInvalidatesPendingOpeningTokenAndLaterCallbackCannotReopenIt() {
        TestAccess access = new TestAccess(config(List.of()));
        seedDeliveredFollowup(access);
        FollowupWindow.Candidate delayedOpening = access.followupWindow.beginExplicit(access.followupContext(),
                UUID.randomUUID(), InteractionIngress.Origin.NATURAL_ADDRESS, access.clock.get() - 10);
        assertEquals(1, access.followupWindow.size());
        access.rosterFailure = RosterFailure.SIZE;

        run(access, "why?", SENDER_ID);

        assertEquals(0, access.followupWindow.size());
        assertFalse(access.followupWindow.open(delayedOpening, access.followupContext(), access.clock.get()),
                "a delayed production answer cannot reopen the invalidated candidate");
        access.rosterFailure = RosterFailure.NONE;
        run(access, "why?", SENDER_ID);
        assertTrue(access.port.submissions.isEmpty(), "a later normal callback remains ambient");
        assertEquals(0, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
    }

    @Test
    void staleRosterEntryInvalidatesTheSenderWithoutRoutingAPartialSnapshot() {
        TestAccess access = new TestAccess(config(List.of()));
        seedDeliveredFollowup(access);
        access.rosterEntryMissing = true;

        run(access, "Archivist, answer", SENDER_ID);

        assertEquals(0, access.followupWindow.size());
        assertTrue(access.port.submissions.isEmpty());
        assertEquals(0, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
    }

    @Test
    void rosterErrorIsNotSwallowedAsAnExpectedDataAccessFailure() {
        TestAccess access = new TestAccess(config(List.of()));
        seedDeliveredFollowup(access);
        access.rosterFailure = RosterFailure.ERROR;

        assertThrows(AssertionError.class, () -> callback(access).onChatMessage(null, null, null));
        assertEquals(1, access.followupWindow.size(), "Error is not converted into ordinary roster failure handling");
    }

    @Test
    void safelyAddressedMalformedInputGetsOnePrivateBoundedInvalidDispositionWithoutEcho() {
        TestAccess access = new TestAccess(config(List.of()));
        access.clock.set(5_000);
        access.simulatedWorkNanos = TimeUnit.MILLISECONDS.toNanos(1_001);
        String privateSentinel = "private invalid sentinel";

        run(access, "Archivist, hide \u202e " + privateSentinel, SENDER_ID);
        access.port.drainAllDispositions();

        assertTrue(access.port.submissions.isEmpty());
        assertEquals(1, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
        assertEquals(1, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_INVALID.ordinal()]);
        assertEquals(0, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);
        assertEquals(1, access.port.playerMessages.size());
        assertFalse(access.port.playerMessages.get(0).contains(privateSentinel));
        assertTrue(access.port.playerMessages.get(0).contains("could not be routed safely"));
        assertEquals(1, histogramCount(access.port.metrics, TickMetrics.FirstPublicSource.NATURAL_INVALID,
                TickMetrics.FirstPublicDisposition.INVALID));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1_001), access.port.metrics.maxFirstPublicDispatchNanos());
        assertEquals(1, access.port.metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);
    }

    @Test
    void overBoundInputAndUnsafeFakeBoundaryAreNotForwardedOrCountedAsInvocation() {
        TestAccess tooLong = new TestAccess(config(List.of()));
        run(tooLong, "Archivist" + " ".repeat(NaturalAddressParser.MAX_INPUT_LENGTH - 8), SENDER_ID);
        assertTrue(tooLong.port.submissions.isEmpty());
        assertEquals(0, tooLong.port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);

        TestAccess hidden = new TestAccess(config(List.of()));
        run(hidden, "Archi\u200bvist: do not route", SENDER_ID);
        assertTrue(hidden.port.submissions.isEmpty());
        assertEquals(0, hidden.port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
    }

    @Test
    void realCommandRegistrationAndNaturalCallbackUseTheSameIngressAndPreserveTheirOwnCaptureTimes() throws Exception {
        TestAccess access = new TestAccess(config(List.of()));
        access.clock.set(10_000);
        CommandDispatcher<ServerPlayerEntity> dispatcher = new CommandDispatcher<>();
        AskCommand.register(dispatcher, new AskCommand.Access<>() {
            @Override public InteractionIngress.Captured capture() {
                return InteractionIngress.Captured.capture(access.clock::getAndIncrement, InteractionIngress.Origin.COMMAND);
            }

            @Override public int execute(ServerPlayerEntity source, String input, InteractionIngress.Route route,
                                         String text, InteractionIngress.Captured captured) {
                return InteractionIngress.execute(captured, source, input, route, text, access.port);
            }
        });
        assertEquals(1, dispatcher.execute("worldspeaker ask same question", null));

        access.clock.set(20_000);
        String completeNaturalText = "Could you, Archivist, ask the same question?";
        run(access, completeNaturalText, SENDER_ID);

        assertEquals(2, access.port.submissions.size());
        Submission command = access.port.submissions.get(0);
        Submission natural = access.port.submissions.get(1);
        assertEquals(command.source().playerId(), natural.source().playerId());
        assertEquals(command.source().connection(), natural.source().connection());
        assertEquals(command.source().worldId(), natural.source().worldId());
        assertEquals(command.invocation().binding(), natural.invocation().binding());
        assertEquals("same question", command.text());
        assertEquals(completeNaturalText, natural.text(), "the complete original message is forwarded without normalization");
        assertEquals(10_000, command.invocation().capturedAtNanos());
        assertEquals(20_000, natural.invocation().capturedAtNanos());
        assertEquals(InteractionIngress.Origin.COMMAND, command.invocation().origin());
        assertEquals(InteractionIngress.Origin.NATURAL_ADDRESS, natural.invocation().origin());
        assertEquals(2, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);
        assertEquals(2, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
    }

    @Test
    void exactContinuationUsesOriginalSignedTextAndCallbackTimeThroughTheSameIngress() {
        TestAccess access = new TestAccess(config(List.of()));
        openFollowup(access);
        String signedText = "\u2003WHY?\u00a0";

        run(access, signedText, SENDER_ID);

        assertEquals(1, access.port.submissions.size());
        Submission submission = access.port.submissions.get(0);
        assertEquals(signedText, submission.text(), "recognition never rewrites the signed input");
        assertEquals(InteractionIngress.Origin.FOLLOWUP, submission.invocation().origin());
        assertEquals(1_000, submission.invocation().capturedAtNanos(), "callback-entry t0 is preserved");
        assertEquals(access.sourceFacts.playerId(), submission.source().playerId());
        assertEquals(access.sourceFacts.connection(), submission.source().connection());
        assertEquals(1, access.port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);
        assertEquals(1, access.port.submissions.size(), "one signed line creates one ingress invocation");
    }

    @Test
    void unrelatedAmbientLineInvalidatesTheWindowAndPendingOpeningTokenWithoutForwarding() {
        TestAccess access = new TestAccess(config(List.of()));
        FollowupWindow.Candidate delayed = pendingCandidate(access);

        run(access, "I found iron", SENDER_ID);

        assertTrue(access.port.submissions.isEmpty());
        assertEquals(0, access.followupWindow.size());
        assertFalse(access.followupWindow.open(delayed, access.followupContext(), 1_001),
                "a delayed complete answer cannot reopen a superseded revision");
    }

    @Test
    void otherOnlinePlayerPrefixWinsBeforeContinuationRecognitionAndClosesTheWindow() {
        TestAccess access = new TestAccess(config(List.of()));
        openFollowup(access);
        access.roster.add(new NaturalAddressListener.PlayerIdentity(OTHER_ID, "Alex"));

        run(access, "@Alex: why?", SENDER_ID);

        assertTrue(access.port.submissions.isEmpty());
        assertEquals(0, access.followupWindow.size());
    }

    @Test
    void callbackTimeAndPreIngressTimeBothMustBeInsideTheHardWindow() {
        TestAccess access = new TestAccess(config(List.of()));
        access.clock.set(TimeUnit.SECONDS.toNanos(29));
        FollowupWindow.Context context = access.followupContext();
        FollowupWindow.Candidate candidate = access.followupWindow.beginExplicit(context, UUID.randomUUID(),
                InteractionIngress.Origin.COMMAND, 0);
        assertTrue(access.followupWindow.open(candidate, context, 0));
        access.simulatedWorkNanos = TimeUnit.SECONDS.toNanos(2);

        run(access, "why?", SENDER_ID);

        assertTrue(access.port.submissions.isEmpty(), "parsing/source work cannot rescue a callback captured before expiry");
        assertEquals(0, access.followupWindow.size());
    }

    @Test
    void independentCallbacksRemainBoundedAndDoNotShareParserState() throws Exception {
        int count = 24;
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<NaturalAddressParser.Result>> work = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                String text = index % 2 == 0 ? "Archivist, turn " + index : "ambient " + index;
                work.add(() -> NaturalAddressParser.parse(text, List.of("Archivist")));
            }
            var results = executor.invokeAll(work, 5, TimeUnit.SECONDS);
            assertEquals(count, results.size());
            for (int index = 0; index < count; index++) {
                NaturalAddressParser.Disposition expected = index % 2 == 0
                        ? NaturalAddressParser.Disposition.ADDRESSED : NaturalAddressParser.Disposition.AMBIENT;
                assertEquals(expected, results.get(index).get().disposition());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ChatMessage callback(TestAccess access) {
        return NaturalAddressListener.createCallback(access);
    }

    private static void run(TestAccess access, String content, UUID signedSender) {
        access.signedMessageSenderId = signedSender;
        access.signedMessageContent = content;
        callback(access).onChatMessage(null, null, null);
    }

    private static CompanionConfig config(List<String> aliases) {
        try {
            return new CompanionConfig(URI.create("ws://127.0.0.1:1"), new byte[32], BINDING,
                    "Hello {player}", "Archivist", InteractionPolicy.parse(SENDER_ID.toString()), aliases);
        } catch (InteractionPolicy.InvalidInteractionPolicyException invalidPolicy) {
            throw new IllegalStateException(invalidPolicy);
        }
    }

    private static InteractionIngress.SourceFacts facts(UUID id, String name) {
        return new InteractionIngress.SourceFacts(true, true, true, id, name, "minecraft:overworld",
                new Object(), new Object());
    }

    private static InteractionIngress.SourceFacts facts(UUID id, String name, Object connection) {
        return new InteractionIngress.SourceFacts(true, true, true, id, name, "minecraft:overworld",
                new Object(), connection);
    }

    private static long histogramCount(TickMetrics metrics, TickMetrics.FirstPublicSource source,
                                       TickMetrics.FirstPublicDisposition disposition) {
        long count = 0;
        for (long bucket : metrics.firstPublicDispatchHistogramSnapshot()[source.ordinal()][disposition.ordinal()]) {
            count += bucket;
        }
        return count;
    }

    private static final class TestAccess implements NaturalAddressListener.Access {
        private final Object epoch = new Object();
        private final Object server = new Object();
        private final AtomicLong clock = new AtomicLong(1_000);
        private final AtomicInteger serverReads = new AtomicInteger();
        private final AtomicInteger rosterReads = new AtomicInteger();
        private final AtomicInteger playerAtReads = new AtomicInteger();
        private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        private final TestIngressPort port;
        private final FollowupWindow followupWindow = new FollowupWindow(30);
        private boolean serverThread = true;
        private boolean epochCurrent = true;
        private InteractionIngress.SourceFacts sourceFacts = facts(SENDER_ID, "Sender");
        private List<NaturalAddressListener.PlayerIdentity> roster = new ArrayList<>(List.of(
                new NaturalAddressListener.PlayerIdentity(SENDER_ID, "Sender")));
        private int rosterSizeOverride = -1;
        private RosterFailure rosterFailure = RosterFailure.NONE;
        private boolean rosterEntryMissing;
        private long simulatedWorkNanos;
        private boolean ordinaryChat = true;
        private boolean signed = true;
        private boolean passThrough = true;
        private UUID signedMessageSenderId = SENDER_ID;
        private String signedMessageContent;
        private String decoratedContent = "decorated";
        private InteractionIngress.Captured nextCapture;

        private TestAccess(CompanionConfig config) {
            port = new TestIngressPort(config, clock);
            port.facts = sourceFacts;
        }

        @Override public InteractionIngress.Captured capture(net.minecraft.network.message.SignedMessage message,
                                                              InteractionIngress.Origin origin) {
            return nextCapture == null ? NaturalAddressListener.Access.super.capture(message, origin) : nextCapture;
        }

        @Override public long monotonicNanos() { calls.add("clock"); return clock.get(); }
        @Override public boolean isServerThread() { calls.add("server-thread"); return serverThread; }
        @Override public Object activeEpoch() { calls.add("active-epoch"); return epoch; }
        @Override public boolean isCurrentEpoch(Object candidate) {
            calls.add("epoch-current");
            return candidate == epoch && epochCurrent;
        }
        @Override public Object currentServer(Object candidate, ServerPlayerEntity sender) {
            calls.add("server");
            serverReads.incrementAndGet();
            return isCurrentEpoch(candidate) ? server : null;
        }
        @Override public CompanionConfig config(Object candidate) {
            calls.add("config");
            return isCurrentEpoch(candidate) ? port.config : null;
        }
        @Override public InteractionIngress.SourceFacts resolveSender(Object candidate, Object currentServer,
                                                                      ServerPlayerEntity sender) {
            calls.add("source");
            clock.addAndGet(simulatedWorkNanos);
            // Synthetic source facts from this fake access port are not production player authentication.
            return isCurrentEpoch(candidate) && currentServer == server ? sourceFacts : InteractionIngress.SourceFacts.nonPlayer();
        }
        @Override public NaturalAddressListener.PlayerRoster onlineRoster(Object candidate, Object currentServer) {
            calls.add("roster");
            rosterReads.incrementAndGet();
            if (!isCurrentEpoch(candidate) || currentServer != server) return null;
            if (rosterFailure == RosterFailure.GETTER) throw new IllegalStateException("private roster details");
            return new NaturalAddressListener.PlayerRoster() {
                @Override public int size() {
                    if (rosterFailure == RosterFailure.SIZE) throw new IllegalStateException("private roster details");
                    if (rosterFailure == RosterFailure.ERROR) throw new AssertionError("test-only fatal roster failure");
                    return rosterSizeOverride >= 0 ? rosterSizeOverride : roster.size();
                }
                @Override public NaturalAddressListener.PlayerIdentity playerAt(int index) {
                    playerAtReads.incrementAndGet();
                    if (rosterFailure == RosterFailure.INDEX) throw new IllegalStateException("private roster details");
                    if (rosterEntryMissing) return null;
                    NaturalAddressListener.PlayerIdentity identity = roster.get(index);
                    if (rosterFailure == RosterFailure.UUID) {
                        return new NaturalAddressListener.PlayerIdentity(SENDER_ID, "Sender") {
                            @Override public UUID playerId() { throw new IllegalStateException("private roster details"); }
                        };
                    }
                    if (rosterFailure == RosterFailure.NAME) {
                        return new NaturalAddressListener.PlayerIdentity(SENDER_ID, "Sender") {
                            @Override public String profileName() { throw new IllegalStateException("private roster details"); }
                        };
                    }
                    return identity;
                }
            };
        }
        @Override public InteractionIngress.Port<ServerPlayerEntity> ingress(Object candidate, Object currentServer) {
            calls.add("ingress");
            return isCurrentEpoch(candidate) && currentServer == server ? port : null;
        }

        @Override public boolean followupOpen(Object candidate, Object currentServer,
                                              InteractionIngress.SourceFacts source,
                                              long capturedAtNanos, long beforeIngressNanos) {
            return isCurrentEpoch(candidate) && currentServer == server && source.playerId().equals(SENDER_ID) &&
                    followupWindow.isOpen(followupContext(), capturedAtNanos, beforeIngressNanos);
        }

        @Override public FollowupWindow.Anchor followupAnchor(Object candidate, Object currentServer,
                                                               InteractionIngress.SourceFacts source,
                                                               long capturedAtNanos, long beforeIngressNanos) {
            return isCurrentEpoch(candidate) && currentServer == server && source.playerId().equals(SENDER_ID)
                    ? followupWindow.anchor(followupContext(), capturedAtNanos, beforeIngressNanos) : null;
        }

        @Override public void invalidateFollowup(Object candidate, UUID playerId) {
            calls.add("invalidate");
            followupWindow.invalidate(playerId);
        }

        private FollowupWindow.Context followupContext() {
            return new FollowupWindow.Context(sourceFacts.playerId(), server, sourceFacts.player(), sourceFacts.connection(),
                    BINDING.serverId(), BINDING.worldId(), BINDING.characterId(), BINDING.agentId(),
                    "minecraft:overworld", epoch, 7, 3);
        }

        @Override public boolean isOrdinaryChat(net.minecraft.network.message.MessageType.Parameters params) {
            calls.add("chat-type");
            return ordinaryChat;
        }
        @Override public boolean hasSignature(net.minecraft.network.message.SignedMessage message) {
            calls.add("signature");
            return signed;
        }
        @Override public UUID signedSenderId(net.minecraft.network.message.SignedMessage message) {
            calls.add("signed-sender");
            return signedMessageSenderId;
        }
        @Override public boolean isPassThrough(net.minecraft.network.message.SignedMessage message) {
            calls.add("filter");
            return passThrough;
        }
        @Override public String signedContent(net.minecraft.network.message.SignedMessage message) {
            calls.add("signed-content");
            return signedMessageContent;
        }

    }

    private static FollowupWindow.Candidate pendingCandidate(TestAccess access) {
        return access.followupWindow.beginExplicit(access.followupContext(), UUID.randomUUID(),
                InteractionIngress.Origin.COMMAND, access.clock.get() - 10);
    }

    private static void openFollowup(TestAccess access) { seedDeliveredFollowup(access); }

    /** Seed listener tests through the same successful delivery callback production uses to open a window. */
    private static void seedDeliveredFollowup(TestAccess access) {
        FollowupWindow.Candidate candidate = pendingCandidate(access);
        SmokeAnswerDelivery.Outcome outcome = SmokeAnswerDelivery.publishThenComplete("Archivist", "answer",
                rendered -> {
                    assertEquals(VanillaDialogue.renderCharacterLine("Archivist", "answer"), rendered);
                    return true;
                }, () -> true,
                () -> assertTrue(access.followupWindow.open(candidate, access.followupContext(), access.clock.get())),
                () -> {});
        assertEquals(SmokeAnswerDelivery.Outcome.COMPLETED, outcome);
    }

    private static FollowupWindow.Context otherFollowupContext(TestAccess access) {
        return new FollowupWindow.Context(OTHER_ID, access.server, new Object(), new Object(),
                BINDING.serverId(), BINDING.worldId(), BINDING.characterId(), BINDING.agentId(),
                "minecraft:overworld", access.epoch, 7, 3);
    }

    private enum RosterFailure { NONE, GETTER, SIZE, INDEX, UUID, NAME, ERROR }

    private record Submission(InteractionIngress.SourceFacts source, String text,
                              InteractionIngress.Invocation invocation) {}

    private static final class TestIngressPort implements InteractionIngress.Port<ServerPlayerEntity> {
        private final CompanionConfig config;
        private final AtomicLong clock;
        private final TickMetrics metrics = new TickMetrics();
        private final List<Submission> submissions = Collections.synchronizedList(new ArrayList<>());
        private final List<String> playerMessages = Collections.synchronizedList(new ArrayList<>());
        private final List<Object> deliveredConnections = Collections.synchronizedList(new ArrayList<>());
        private final FirstReplyScheduler scheduler;
        private InteractionIngress.SourceFacts facts = facts(SENDER_ID, "Sender");
        private InvocationRateLimiter rateLimiter;

        private TestIngressPort(CompanionConfig config, AtomicLong clock) {
            this.config = config;
            this.clock = clock;
            this.scheduler = new FirstReplyScheduler(clock::get, System::currentTimeMillis);
        }

        @Override public InteractionIngress.SourceFacts resolve(ServerPlayerEntity source) { return facts; }
        @Override public InteractionIngress.SourceFacts resolve(ServerPlayerEntity source, String input) { return facts; }
        @Override public CompanionConfig config() { return config; }
        @Override public TickMetrics metrics() { return metrics; }
        @Override public FirstReplyScheduler firstReplyScheduler() { return scheduler; }
        @Override public InvocationRateLimiter rateLimiter() { return rateLimiter; }
        @Override public boolean serviceActive() { return config != null; }
        @Override public long monotonicNanos() { return clock.get(); }
        @Override public InteractionIngress.Outcome submit(InteractionIngress.SourceFacts source, String text,
                                                             InteractionIngress.Invocation invocation) {
            submissions.add(new Submission(source, text, invocation));
            if (!invocation.commitEvidenceAdmission(metrics)) return InteractionIngress.Outcome.UNAVAILABLE;
            return InteractionIngress.Outcome.ADMITTED;
        }
        @Override public boolean publishPlayer(InteractionIngress.SourceFacts source, String message,
                                               boolean characterMessage, String characterDisplayName) {
            playerMessages.add(characterMessage
                    ? VanillaDialogue.renderCharacterLine(characterDisplayName, message)
                    : VanillaDialogue.renderLine(message));
            return true;
        }
        @Override public FirstReplyScheduler.PublicationResult submitPlayerDisposition(
                InteractionIngress.SourceFacts source, String message, boolean characterMessage,
                String characterDisplayName, boolean requireCurrentGrant) {
            deliveredConnections.add(source.connection());
            return InteractionIngress.Port.super.submitPlayerDisposition(source, message, characterMessage,
                    characterDisplayName, requireCurrentGrant);
        }
        @Override public boolean publishSource(ServerPlayerEntity source, String message) { return false; }

        private int drainAllDispositions() {
            int total = 0;
            while (scheduler.pendingDispositionCount() > 0) {
                total += scheduler.drainDispositions(new SmokeTickBudget(scheduler.monotonicNanos()), 0);
            }
            return total;
        }
    }

    private record TestConnection(long worldspeaker$connectionGeneration) implements ServerConnectionGeneration {}

    private static final class TestCarrier implements PacketCaptureAccess {
        private InvocationCapture capture;

        @Override public InvocationCapture worldspeaker$getInvocationCapture() { return capture; }

        @Override public boolean worldspeaker$setInvocationCapture(InvocationCapture next) {
            if (capture != null || next == null) return false;
            capture = next;
            return true;
        }
    }
}
