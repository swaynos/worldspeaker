package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.GateState;
import org.worldspeaker.compat.PeerFacts;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the production request-owned outage retirement publisher and its first/late paths. */
class SmokeOutageRetirementTest {
    @Test
    void outageBeforeAcknowledgementUsesTheFirstPublicationSlotForATerminalDisposition() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.schedule(4));

        SmokeOutageRetirement.Dispatch dispatch = fixture.dispatch(true);

        assertEquals(SmokeOutageRetirement.Outcome.SUBMITTED, dispatch.outcome());
        assertEquals(SmokeOutageRetirement.Path.FIRST_PUBLICATION, dispatch.path());
        assertEquals(1, fixture.firstSends.get());
        assertEquals(0, fixture.terminalSends.get());
        assertTrue(fixture.slot.hasFirstPublication());
        assertFalse(fixture.lifecycle.isLive(fixture.request));
    }

    @Test
    void outageAfterAcknowledgementPublishesOneDistinctLateTerminal() {
        Fixture fixture = new Fixture();
        fixture.acknowledge(FirstReplyScheduler.PublicationResult.SUBMITTED);
        String acknowledgementId = fixture.slot.publicationId();
        assertTrue(fixture.schedule(7));

        SmokeOutageRetirement.Dispatch dispatch = fixture.dispatch(true);
        SmokeOutageRetirement.Dispatch repeated = fixture.dispatch(true);

        assertEquals(SmokeOutageRetirement.Outcome.SUBMITTED, dispatch.outcome());
        assertEquals(SmokeOutageRetirement.Path.TERMINAL, dispatch.path());
        assertNotNull(dispatch.terminalNoticeId());
        assertNotEquals(acknowledgementId, dispatch.terminalNoticeId());
        assertEquals(dispatch.terminalNoticeId(), fixture.lastTerminalId.get());
        assertEquals(SmokeOutageRetirement.Outcome.ALREADY_HANDLED, repeated.outcome());
        assertEquals(0, fixture.firstSends.get(), "a committed ACK is never replayed as the outage terminal");
        assertEquals(1, fixture.terminalSends.get());
        assertEquals(acknowledgementId, fixture.slot.publicationId(), "late dialogue does not rewrite first-publication evidence");
    }

    @Test
    void outageAfterDispatchedProgressStillGetsTheSameSingleTerminalPath() {
        Fixture fixture = new Fixture();
        fixture.acknowledge(FirstReplyScheduler.PublicationResult.SUBMITTED);
        ConversationProgress progress = new ConversationProgress(0);
        progress.markForwarded(1, true);
        assertEquals(1, progress.forwardedAtNanos());
        assertTrue(progress.markAttempted(ConversationProgress.Phase.AWAITING_ANSWER));
        assertTrue(progress.recordDispatched(new ConversationProgress.DialogueProgress(
                "progress-id", "[Worldspeaker] Archivist: still waiting", 1, 1,
                "player-id", ConversationProgress.Phase.AWAITING_ANSWER)));
        assertEquals(1, progress.dispatched().size());
        assertTrue(fixture.schedule(8));

        SmokeOutageRetirement.Dispatch dispatch = fixture.dispatch(true);

        assertEquals(SmokeOutageRetirement.Path.TERMINAL, dispatch.path());
        assertEquals(1, fixture.terminalSends.get());
        assertEquals(0, fixture.firstSends.get());
    }

    @Test
    void unknownFirstSendGetsAnExplicitKnownOutcomeWithItsOwnIdWithoutReplayingAck() {
        Fixture fixture = new Fixture();
        fixture.acknowledge(FirstReplyScheduler.PublicationResult.UNKNOWN);
        String unknownAcknowledgementId = fixture.slot.publicationId();
        assertTrue(fixture.slot.unknown());
        assertTrue(fixture.schedule(9));

        SmokeOutageRetirement.Dispatch dispatch = fixture.dispatch(true);

        assertEquals(SmokeOutageRetirement.Path.TERMINAL, dispatch.path());
        assertEquals(SmokeOutageRetirement.Outcome.SUBMITTED, dispatch.outcome());
        assertNotNull(dispatch.terminalNoticeId());
        assertNotEquals(unknownAcknowledgementId, dispatch.terminalNoticeId());
        assertEquals(0, fixture.firstSends.get());
        assertEquals(1, fixture.terminalSends.get());
        assertTrue(fixture.slot.unknown(), "the ambiguous first send remains unknown; it is not rewritten as delivered");
        assertEquals("Worldspeaker became unavailable before completing this request. This request is closed and will not resume automatically.",
                SmokeOutageRetirement.TERMINAL_TEXT);
        String rendered = VanillaDialogue.renderCharacterLine("Archivist", SmokeOutageRetirement.TERMINAL_TEXT);
        assertTrue(VanillaDialogue.isSafeRenderedLine(rendered));
        assertFalse(SmokeOutageRetirement.TERMINAL_TEXT.toLowerCase().contains("accepted"));
        assertFalse(SmokeOutageRetirement.TERMINAL_TEXT.toLowerCase().contains("will resume"));
    }

    @Test
    void unknownTerminalSubmissionIsNotRetriedByRepeatedOutageCallbacks() {
        Fixture fixture = new Fixture();
        fixture.acknowledge(FirstReplyScheduler.PublicationResult.SUBMITTED);
        assertTrue(fixture.schedule(10));
        fixture.terminalResult = FirstReplyScheduler.PublicationResult.UNKNOWN;

        SmokeOutageRetirement.Dispatch dispatch = fixture.dispatch(true);
        SmokeOutageRetirement.Dispatch repeated = fixture.dispatch(true);

        assertEquals(SmokeOutageRetirement.Outcome.UNKNOWN, dispatch.outcome());
        assertFalse(fixture.retirement.isPending());
        assertEquals(SmokeOutageRetirement.Outcome.ALREADY_HANDLED, repeated.outcome());
        assertEquals(1, fixture.terminalSends.get());
        assertEquals(0, fixture.firstSends.get());
    }

    @Test
    void completedAnswerIsNotScheduledForAnOutageRetirement() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.lifecycle.complete(fixture.request));

        assertFalse(fixture.schedule(10));
        SmokeOutageRetirement.Dispatch dispatch = fixture.dispatch(true);

        assertEquals(SmokeOutageRetirement.Outcome.ALREADY_HANDLED, dispatch.outcome());
        assertEquals(0, fixture.firstSends.get());
        assertEquals(0, fixture.terminalSends.get());
    }

    @Test
    void cancellationAndDisconnectBeforeTheBudgetedDispatchDoNotPublish() {
        Fixture cancelled = new Fixture();
        assertTrue(cancelled.schedule(11));
        assertTrue(cancelled.lifecycle.retire(cancelled.request));
        SmokeOutageRetirement.Dispatch cancelledDispatch = cancelled.dispatch(true);
        assertEquals(SmokeOutageRetirement.Outcome.REQUEST_FINISHED, cancelledDispatch.outcome());
        assertEquals(0, cancelled.terminalSends.get());

        Fixture disconnected = new Fixture();
        assertTrue(disconnected.schedule(12));
        disconnected.lifecycle.retireConnection(disconnected.originalConnection);
        AtomicInteger audienceChecks = new AtomicInteger();
        SmokeOutageRetirement.Dispatch disconnectDispatch = disconnected.dispatch(() -> {
            audienceChecks.incrementAndGet();
            return disconnected.originalConnection == disconnected.currentConnection;
        });
        assertEquals(SmokeOutageRetirement.Outcome.REQUEST_FINISHED, disconnectDispatch.outcome());
        assertEquals(0, audienceChecks.get());
        assertEquals(0, disconnected.terminalSends.get());
    }

    @Test
    void reconnectedPlayerOnANewConnectionFailsTheCurrentOriginalAudienceGate() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.schedule(13));
        fixture.currentConnection = new Object();
        AtomicInteger audienceChecks = new AtomicInteger();

        SmokeOutageRetirement.Dispatch dispatch = fixture.dispatch(() -> {
            audienceChecks.incrementAndGet();
            return fixture.originalConnection == fixture.currentConnection && fixture.currentPermission;
        });

        assertEquals(SmokeOutageRetirement.Outcome.NO_AUDIENCE, dispatch.outcome());
        assertEquals(1, audienceChecks.get());
        assertEquals(0, fixture.firstSends.get());
        assertEquals(0, fixture.terminalSends.get());
    }

    @Test
    void revokedConversationPermissionBlocksTheOutageTerminal() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.schedule(14));
        fixture.currentPermission = false;

        SmokeOutageRetirement.Dispatch dispatch = fixture.dispatch(() ->
                fixture.originalConnection == fixture.currentConnection && fixture.currentPermission);

        assertEquals(SmokeOutageRetirement.Outcome.NO_AUDIENCE, dispatch.outcome());
        assertEquals(0, fixture.firstSends.get());
        assertEquals(0, fixture.terminalSends.get());
    }

    @Test
    void capturedOutageGenerationSurvivesReconnectWithoutResumingOrDuplicatingTheRequest() {
        Fixture fixture = new Fixture();
        CompatibilityGate gate = gate();
        long outageGeneration = gate.checkLocal(candidateRuntime()).generation();
        assertEquals(GateState.UNAVAILABLE, gate.snapshot().state());
        assertTrue(fixture.schedule(outageGeneration));

        gate.companionAuthenticated(candidatePeer());
        assertEquals(GateState.READY, gate.snapshot().state());
        assertNotEquals(outageGeneration, gate.snapshot().generation());
        assertFalse(fixture.schedule(gate.snapshot().generation()), "a reconnect cannot create a second outage ticket");

        SmokeOutageRetirement.Dispatch dispatch = fixture.dispatch(true);

        assertEquals(outageGeneration, dispatch.outageGeneration());
        assertEquals(SmokeOutageRetirement.Path.FIRST_PUBLICATION, dispatch.path());
        assertEquals(1, fixture.firstSends.get());
        assertEquals(0, fixture.terminalSends.get());
        assertFalse(fixture.lifecycle.isLive(fixture.request), "retirement is terminal and is not resumed on reconnect");
    }

    private static RuntimeFacts candidateRuntime() {
        return new RuntimeFacts(DeploymentFacts.MINECRAFT_VERSION, DeploymentFacts.FABRIC_LOADER_VERSION,
                DeploymentFacts.FABRIC_API_VERSION, Integer.parseInt(DeploymentFacts.JAVA_MAJOR),
                DeploymentFacts.JAVA_VENDOR, DeploymentFacts.JAVA_RUNTIME_VERSION);
    }

    private static PeerFacts candidatePeer() {
        return new PeerFacts(DeploymentFacts.DEPLOYMENT_ID, DeploymentFacts.MANIFEST_SHA256,
                DeploymentFacts.PROTOCOL, DeploymentFacts.OPENCLAW_VERSION, DeploymentFacts.NODE_VERSION,
                DeploymentFacts.COMPANION_VERSION, "server-test", "world-test", "character-test", "agent-test");
    }

    private static CompatibilityGate gate() {
        return new CompatibilityGate(new ConfiguredBinding("server-test", "world-test", "character-test", "agent-test"));
    }

    private static final class Fixture {
        private final AtomicLong clock = new AtomicLong(1_000);
        private final FirstReplyScheduler scheduler = new FirstReplyScheduler(clock::get, clock::get);
        private final FirstReplyScheduler.Slot slot = scheduler.createSlot(1_000, "LIVE_PACKET");
        private final SmokeOutageRetirement retirement = new SmokeOutageRetirement();
        private final SmokeRequestLifecycle<Object> lifecycle = new SmokeRequestLifecycle<>();
        private final Object request = new Object();
        private final Object originalConnection = new Object();
        private Object currentConnection = originalConnection;
        private boolean currentPermission = true;
        private FirstReplyScheduler.PublicationResult terminalResult = FirstReplyScheduler.PublicationResult.SUBMITTED;
        private final AtomicInteger firstSends = new AtomicInteger();
        private final AtomicInteger terminalSends = new AtomicInteger();
        private final AtomicReference<String> lastTerminalId = new AtomicReference<>();

        private Fixture() {
            lifecycle.register(request, originalConnection);
            assertTrue(lifecycle.start(request));
        }

        private boolean schedule(long generation) {
            return retirement.schedule(generation, () -> lifecycle.isLive(request));
        }

        private void acknowledge(FirstReplyScheduler.PublicationResult result) {
            FirstReplyScheduler.Claim claim = scheduler.claimAcknowledgement(slot);
            assertEquals(FirstReplyScheduler.Claim.CLAIMED_FIRST, claim);
            scheduler.complete(slot, claim, result, FirstReplyScheduler.PublicationKind.ACKNOWLEDGEMENT);
        }

        private SmokeOutageRetirement.Dispatch dispatch(boolean audience) {
            return dispatch(() -> audience);
        }

        private SmokeOutageRetirement.Dispatch dispatch(java.util.function.BooleanSupplier audience) {
            return retirement.retireAndPublish(() -> lifecycle.isLive(request), () -> lifecycle.retire(request),
                    scheduler, slot, audience, () -> {
                firstSends.incrementAndGet();
                return FirstReplyScheduler.PublicationResult.SUBMITTED;
            }, noticeId -> {
                lastTerminalId.set(noticeId);
                terminalSends.incrementAndGet();
                return terminalResult;
            });
        }
    }
}
