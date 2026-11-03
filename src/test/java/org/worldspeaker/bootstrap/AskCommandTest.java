package org.worldspeaker.bootstrap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestion;
import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.InteractionPolicy;
import org.worldspeaker.runtime.TickMetrics;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskCommandTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Test
    void productionBrigadierTreeExplainsSharedContextAndTargetedOutputWithoutGrant() throws Exception {
        TestPort port = new TestPort(config("The Archivist", InteractionPolicy.denyAll()));
        CommandDispatcher<TestSource> dispatcher = dispatcher(port, new AtomicLong(1_000_000));
        TestSource player = TestSource.authenticated(PLAYER_ID);

        assertEquals(1, dispatcher.execute("worldspeaker", player));
        port.drainAllDispositions();
        assertEquals("[Worldspeaker] Worldspeaker: The Archivist — use /worldspeaker ask <text> " +
                "(up to 512 UTF-16 units); it is not broadcast as chat, but enters shared character context and is not confidential. " +
                "Replies target only your current connection. Natural-address and follow-up chat is public.", port.playerMessages.get(0));
        assertTrue(port.playerMessages.get(0).contains("The Archivist"));
        assertTrue(port.playerMessages.get(0).contains("/worldspeaker ask <text>"));
        assertTrue(port.playerMessages.get(0).contains("not confidential"));
        assertTrue(port.playerMessages.get(0).contains("only your current connection"));
        assertFalse(port.playerMessages.get(0).contains("/pray"));
        assertEquals(InteractionIngress.Outcome.HELP, port.lastOutcome());
        assertEquals(0, port.submissions.size());

        List<String> suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("worldspeaker ", player))
                .get().getList().stream().map(Suggestion::getText).toList();
        assertTrue(suggestions.contains("help"));
        assertTrue(suggestions.contains("ask"));
        assertTrue(suggestions.contains("end"));

        port.playerMessages.clear();
        assertEquals(1, dispatcher.execute("worldspeaker help", player));
        port.drainAllDispositions();
        assertTrue(port.playerMessages.get(0).contains("The Archivist"));
        assertEquals(2, port.captureCalls);
    }

    @Test
    void missingBlankUnknownAndInvalidHelpSyntaxGiveConciseNonEchoingGuidance() throws Exception {
        TestPort port = new TestPort(config("Cartographer", InteractionPolicy.denyAll()));
        CommandDispatcher<TestSource> dispatcher = dispatcher(port, new AtomicLong(2_000_000));
        TestSource player = TestSource.authenticated(PLAYER_ID);
        List<String> commands = List.of(
                "worldspeaker ask",
                "worldspeaker ask    ",
                "worldspeaker ask \t",
                "worldspeaker help private-sentinel",
                "worldspeaker unknown private-sentinel");

        for (String command : commands) {
            assertEquals(0, dispatcher.execute(command, player), command);
            port.drainAllDispositions();
            String message = port.playerMessages.get(port.playerMessages.size() - 1);
            assertEquals("[Worldspeaker] Usage: /worldspeaker ask <text> (up to 512 UTF-16 units), " +
                    "/worldspeaker end, or /worldspeaker help.", message);
            assertTrue(message.contains("/worldspeaker ask <text>") || message.contains("/worldspeaker help"));
            assertFalse(message.contains("private-sentinel"), "unknown arguments are never echoed");
        }
        assertEquals(0, port.submissions.size());
        assertEquals(commands.size(), port.captureCalls);
        assertEquals(commands.size(), port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_INVALID.ordinal()]);
    }

    @Test
    void helpWithoutValidatedConfigurationDoesNotInventAnInstalledCharacterName() throws Exception {
        TestPort port = new TestPort(null);
        CommandDispatcher<TestSource> dispatcher = dispatcher(port, new AtomicLong(2_500_000));

        assertEquals(1, dispatcher.execute("worldspeaker help", TestSource.authenticated(PLAYER_ID)));
        port.drainAllDispositions();

        String help = port.playerMessages.get(0);
        assertEquals("[Worldspeaker] Worldspeaker: use /worldspeaker ask <text> " +
                "(up to 512 UTF-16 units); this is not broadcast as chat, but enters shared character context and is not confidential. " +
                "Replies target only your current connection. Natural-address and follow-up chat is public. " +
                "The configured character name is " +
                "unavailable until valid server configuration is loaded.", help);
        assertTrue(help.contains("/worldspeaker ask <text>"));
        assertTrue(help.contains("configured character name is unavailable"));
        assertFalse(help.contains("Archivist"));
        assertEquals(0, port.submissions.size());
        assertEquals(1, port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_HELP.ordinal()]);
    }

    @Test
    void askAcceptsOneAndFiveHundredTwelveUnitsAndRejectsFiveHundredThirteen() throws Exception {
        UUID granted = PLAYER_ID;
        TestPort port = new TestPort(config("Cartographer", new InteractionPolicy(java.util.Set.of(granted))));
        CommandDispatcher<TestSource> dispatcher = dispatcher(port, new AtomicLong(3_000_000));
        TestSource player = TestSource.authenticated(granted);

        assertEquals(1, dispatcher.execute("worldspeaker ask x", player));
        assertEquals(1, dispatcher.execute("worldspeaker ask " + "a".repeat(512), player));
        assertEquals(0, dispatcher.execute("worldspeaker ask " + "b".repeat(513), player));
        port.drainAllDispositions();
        assertEquals(List.of(1, 512), port.submissions.stream().map(submission -> submission.text.length()).toList());
        assertEquals(List.of(3_000_000L, 3_000_001L),
                port.submissions.stream().map(Submission::capturedAtNanos).toList());
        assertEquals("[Worldspeaker] Usage: /worldspeaker ask <text> (up to 512 UTF-16 units), " +
                "/worldspeaker end, or /worldspeaker help.", port.playerMessages.get(port.playerMessages.size() - 1));
        assertEquals(InteractionIngress.Outcome.ADMITTED, port.lastOutcome());
        assertEquals(1, port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_INVALID.ordinal()]);
        assertEquals(2, port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);
    }

    @Test
    void renamingDoesNotChangeStableBindingOrGenericCommandAndNeverRequiresGodAlias() throws Exception {
        ConfiguredBinding binding = new ConfiguredBinding("server", "world", "stable-character", "agent");
        TestSource player = TestSource.authenticated(PLAYER_ID);
        TestPort first = new TestPort(config(binding, "Tidekeeper", InteractionPolicy.denyAll()));
        TestPort renamed = new TestPort(config(binding, "Stone Reader", InteractionPolicy.denyAll()));
        CommandDispatcher<TestSource> firstDispatcher = dispatcher(first, new AtomicLong(4_000_000));
        CommandDispatcher<TestSource> renamedDispatcher = dispatcher(renamed, new AtomicLong(5_000_000));

        assertEquals(1, firstDispatcher.execute("worldspeaker help", player));
        assertEquals(1, renamedDispatcher.execute("worldspeaker help", player));
        first.drainAllDispositions();
        renamed.drainAllDispositions();
        assertTrue(first.playerMessages.get(0).contains("Tidekeeper"));
        assertTrue(renamed.playerMessages.get(0).contains("Stone Reader"));
        assertEquals(binding, first.config.binding());
        assertEquals(binding, renamed.config.binding());
        assertTrue(firstDispatcher.getRoot().getChild("pray") == null);
        assertTrue(renamedDispatcher.getRoot().getChild("pray") == null);
    }

    @Test
    void endIsAGrantedLocalPrivateResetAndDoesNotSubmitCharacterWork() throws Exception {
        TestPort port = new TestPort(config("Cartographer", new InteractionPolicy(java.util.Set.of(PLAYER_ID))));
        CommandDispatcher<TestSource> dispatcher = dispatcher(port, new AtomicLong(6_000_000));

        assertEquals(1, dispatcher.execute("worldspeaker end", TestSource.authenticated(PLAYER_ID)));
        port.drainAllDispositions();

        assertEquals(1, port.endCalls);
        assertTrue(port.submissions.isEmpty(), "end is a local reset, not an agent turn");
        assertEquals(List.of("[Worldspeaker] Follow-up recognition ended locally. This does not clear OpenClaw memory or cancel work already admitted."),
                port.playerMessages);
        assertEquals(1, port.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_FOLLOWUP_ENDED.ordinal()]);

        TestPort denied = new TestPort(config("Cartographer", InteractionPolicy.denyAll()));
        CommandDispatcher<TestSource> deniedDispatcher = dispatcher(denied, new AtomicLong(7_000_000));
        assertEquals(0, deniedDispatcher.execute("worldspeaker end", TestSource.authenticated(PLAYER_ID)));
        denied.drainAllDispositions();
        assertEquals(0, denied.endCalls, "an ungranted player cannot reset another conversation state");
        assertTrue(denied.playerMessages.get(0).contains("not authorized"));
        assertTrue(denied.submissions.isEmpty());
    }

    private static CommandDispatcher<TestSource> dispatcher(TestPort port, AtomicLong clock) {
        CommandDispatcher<TestSource> dispatcher = new CommandDispatcher<>();
        AskCommand.register(dispatcher, new AskCommand.Access<>() {
            @Override
            public InteractionIngress.Captured capture() {
                port.captureCalls++;
                return InteractionIngress.Captured.capture(clock::getAndIncrement, InteractionIngress.Origin.COMMAND);
            }

            @Override
            public int execute(TestSource source, String input, InteractionIngress.Route route, String text,
                               InteractionIngress.Captured captured) {
                return InteractionIngress.execute(captured, source, input, route, text, port);
            }
        });
        return dispatcher;
    }

    private static CompanionConfig config(String name, InteractionPolicy policy) {
        return config(new ConfiguredBinding("server", "world", "stable-character", "agent"), name, policy);
    }

    private static CompanionConfig config(ConfiguredBinding binding, String name, InteractionPolicy policy) {
        return new CompanionConfig(URI.create("ws://127.0.0.1:1"), new byte[32], binding,
                "Hello {player}", name, policy);
    }

    record TestSource(InteractionIngress.SourceFacts facts, boolean operator) {
        static TestSource authenticated(UUID playerId) {
            return new TestSource(new InteractionIngress.SourceFacts(true, true, true, playerId,
                    "TestPlayer", "minecraft:overworld", new Object(), new Object()), false);
        }
    }

    static final class TestPort implements InteractionIngress.Port<TestSource> {
        private final CompanionConfig config;
        private final TickMetrics metrics = new TickMetrics();
        private final List<String> playerMessages = new ArrayList<>();
        private final List<String> sourceMessages = new ArrayList<>();
        private final List<Submission> submissions = new ArrayList<>();
        private final List<InteractionIngress.Outcome> outcomes = new ArrayList<>();
        private InteractionIngress.Outcome nextAdmission = InteractionIngress.Outcome.ADMITTED;
        private int captureCalls;
        private int resolveCalls;
        private int endCalls;
        private final FirstReplyScheduler scheduler = new FirstReplyScheduler(System::nanoTime, System::currentTimeMillis);

        private TestPort(CompanionConfig config) {
            this.config = config;
        }

        @Override public InteractionIngress.SourceFacts resolve(TestSource source) {
            resolveCalls++;
            return source.facts();
        }
        @Override public InteractionIngress.SourceFacts resolve(TestSource source, String input) {
            return resolve(source);
        }
        @Override public CompanionConfig config() { return config; }
        @Override public TickMetrics metrics() { return metrics; }
        @Override public FirstReplyScheduler firstReplyScheduler() { return scheduler; }
        @Override public boolean serviceActive() { return true; }
        @Override public InteractionIngress.Outcome submit(InteractionIngress.SourceFacts source, String text,
                                                             InteractionIngress.Invocation invocation) {
            submissions.add(new Submission(source.playerId(), source.worldId(), invocation.binding(),
                    invocation.capturedAtNanos(), invocation.origin(), text));
            if (nextAdmission == InteractionIngress.Outcome.ADMITTED && !invocation.commitEvidenceAdmission(metrics)) {
                return InteractionIngress.Outcome.UNAVAILABLE;
            }
            return nextAdmission;
        }
        @Override public void endFollowup(InteractionIngress.SourceFacts source) { endCalls++; }
        @Override public boolean publishPlayer(InteractionIngress.SourceFacts source, String message,
                                               boolean characterMessage, String characterDisplayName) {
            playerMessages.add(characterMessage
                    ? VanillaDialogue.renderCharacterLine(characterDisplayName, message)
                    : VanillaDialogue.renderLine(message));
            return true;
        }
        @Override public boolean publishSource(TestSource source, String message) {
            sourceMessages.add(message);
            return true;
        }

        private int drainAllDispositions() {
            int total = 0;
            while (scheduler.pendingDispositionCount() > 0) {
                total += scheduler.drainDispositions(new SmokeTickBudget(scheduler.monotonicNanos()), 0);
            }
            return total;
        }

        private InteractionIngress.Outcome lastOutcome() {
            InteractionIngress.Outcome[] outcomes = InteractionIngress.Outcome.values();
            for (int i = outcomes.length - 1; i >= 0; i--) {
                if (outcomeCount(outcomes[i]) > 0) return outcomes[i];
            }
            return null;
        }

        private long outcomeCount(InteractionIngress.Outcome outcome) {
            return switch (outcome) {
                case HELP -> counters(TickMetrics.Counter.INTERACTION_HELP);
                case INVALID -> counters(TickMetrics.Counter.INTERACTION_INVALID);
                case DENIED -> counters(TickMetrics.Counter.INTERACTION_DENIED);
                case UNAUTHENTICATED -> counters(TickMetrics.Counter.INTERACTION_UNAUTHENTICATED);
                case UNAVAILABLE_BEFORE_BOOTSTRAP -> counters(TickMetrics.Counter.INTERACTION_UNAVAILABLE_BEFORE_BOOTSTRAP);
                case RECEIPT_EVIDENCE_MISSING -> counters(TickMetrics.Counter.INTERACTION_RECEIPT_EVIDENCE_MISSING);
                case UNAVAILABLE -> counters(TickMetrics.Counter.INTERACTION_UNAVAILABLE);
                case BUSY -> counters(TickMetrics.Counter.INTERACTION_BUSY);
                case COALESCED -> counters(TickMetrics.Counter.INTERACTION_COALESCED);
                case FOLLOWUP_ENDED -> counters(TickMetrics.Counter.INTERACTION_FOLLOWUP_ENDED);
                case RATE_LIMITED -> counters(TickMetrics.Counter.INTERACTION_RATE_LIMITED);
                case EXPIRED -> counters(TickMetrics.Counter.INTERACTION_EXPIRED);
                case ADMITTED -> counters(TickMetrics.Counter.INTERACTION_ADMITTED);
            };
        }

        private long counters(TickMetrics.Counter counter) {
            return metrics.countersSnapshot()[counter.ordinal()];
        }
    }

    record Submission(UUID playerId, String worldId, ConfiguredBinding binding, long capturedAtNanos,
                      InteractionIngress.Origin origin, String text) {}
}
