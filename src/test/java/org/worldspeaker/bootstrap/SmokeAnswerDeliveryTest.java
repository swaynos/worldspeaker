package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeAnswerDeliveryTest {
    private static final String ACK_MESSAGE_ID = "00000000-0000-4000-8000-000000000041";
    private static final String ACK_TEXT = "[Worldspeaker] Archivist: I have your request.";

    @Test
    void bidiOnlyAnswerIsNotPublishedOrCompletedAndKeepsAcknowledgementContext() {
        Fixture fixture = new Fixture();
        AtomicInteger sinkCalls = new AtomicInteger();
        AtomicInteger completionCalls = new AtomicInteger();
        AtomicInteger nextTurnCalls = new AtomicInteger();

        SmokeAnswerDelivery.Outcome outcome = SmokeAnswerDelivery.publishThenComplete("Archivist", "\u202e",
                rendered -> VanillaDialogue.publishRendered(rendered, (line, overlay) -> sinkCalls.incrementAndGet()),
                fixture.complete(completionCalls), nextTurnCalls::incrementAndGet);

        assertEquals(SmokeAnswerDelivery.Outcome.ANSWER_NOT_PUBLISHED, outcome);
        assertEquals(0, sinkCalls.get());
        assertEquals(0, completionCalls.get());
        assertEquals(0, nextTurnCalls.get());
        assertTrue(fixture.lifecycle.isActive(fixture.request), "the answer timeout remains in control of the active request");
        assertEquals(fixture.acknowledgement, fixture.acknowledgementState.locallyDispatched());
        assertTrue(fixture.acknowledgementState.contextDeliveryPending(), "unusable output must not clear acknowledgement delivery");
        assertFalse(fixture.acknowledgementState.canStartNextTurn());
    }

    @Test
    void legacyFormattingOnlyAnswerDoesNotBecomeACompletedRequest() {
        Fixture fixture = new Fixture();
        AtomicInteger sinkCalls = new AtomicInteger();
        AtomicInteger completionCalls = new AtomicInteger();
        AtomicInteger nextTurnCalls = new AtomicInteger();

        SmokeAnswerDelivery.Outcome outcome = SmokeAnswerDelivery.publishThenComplete("Archivist", "\u00a7c",
                rendered -> VanillaDialogue.publishRendered(rendered, (line, overlay) -> sinkCalls.incrementAndGet()),
                fixture.complete(completionCalls), nextTurnCalls::incrementAndGet);

        assertEquals(SmokeAnswerDelivery.Outcome.ANSWER_NOT_PUBLISHED, outcome);
        assertEquals(0, sinkCalls.get());
        assertEquals(0, completionCalls.get());
        assertEquals(0, nextTurnCalls.get());
        assertTrue(fixture.lifecycle.isActive(fixture.request));
    }

    @Test
    void validAnswerIsPublishedOnceBeforeRequestCompletionAndNextTurn() {
        Fixture fixture = new Fixture();
        AtomicReference<String> observedLine = new AtomicReference<>();
        AtomicReference<Boolean> observedOverlay = new AtomicReference<>();
        AtomicInteger sinkCalls = new AtomicInteger();
        AtomicInteger completionCalls = new AtomicInteger();
        AtomicInteger nextTurnCalls = new AtomicInteger();

        SmokeAnswerDelivery.Outcome outcome = SmokeAnswerDelivery.publishThenComplete("Archivist", "hello",
                rendered -> VanillaDialogue.publishRendered(rendered, (line, overlay) -> {
                    sinkCalls.incrementAndGet();
                    observedLine.set(line);
                    observedOverlay.set(overlay);
                }),
                fixture.complete(completionCalls), nextTurnCalls::incrementAndGet);

        assertEquals(SmokeAnswerDelivery.Outcome.COMPLETED, outcome);
        assertEquals("[Worldspeaker] Archivist: hello", observedLine.get());
        assertEquals(Boolean.FALSE, observedOverlay.get());
        assertEquals(1, sinkCalls.get());
        assertEquals(1, completionCalls.get());
        assertEquals(1, nextTurnCalls.get());
        assertFalse(fixture.lifecycle.isActive(fixture.request));
        assertEquals(fixture.acknowledgement, fixture.acknowledgementState.locallyDispatched(),
                "request completion must not erase an acknowledgement already published locally");
        assertTrue(fixture.acknowledgementState.contextDeliveryPending(),
                "completed request retains the locally published acknowledgement for context delivery");
        assertFalse(fixture.acknowledgementState.canStartNextTurn());
    }

    @Test
    void failedVanillaDispatchRetainsTheActiveRequest() {
        Fixture fixture = new Fixture();
        AtomicInteger completionCalls = new AtomicInteger();
        AtomicInteger nextTurnCalls = new AtomicInteger();

        SmokeAnswerDelivery.Outcome outcome = SmokeAnswerDelivery.publishThenComplete("Archivist", "valid answer",
                rendered -> VanillaDialogue.publishRendered(rendered, (line, overlay) -> {
                    throw new IllegalStateException("deterministic test sink failure");
                }),
                fixture.complete(completionCalls), nextTurnCalls::incrementAndGet);

        assertEquals(SmokeAnswerDelivery.Outcome.ANSWER_NOT_PUBLISHED, outcome);
        assertEquals(0, completionCalls.get());
        assertEquals(0, nextTurnCalls.get());
        assertTrue(fixture.lifecycle.isActive(fixture.request));
    }

    @Test
    void completedAnswerBoundaryRejectsOversizeInsteadOfPublishingAClippedAnswer() {
        Fixture fixture = new Fixture();
        AtomicInteger sinkCalls = new AtomicInteger();
        AtomicInteger completionCalls = new AtomicInteger();

        SmokeAnswerDelivery.Outcome outcome = SmokeAnswerDelivery.publishThenComplete("Archivist", "x".repeat(1981),
                rendered -> { sinkCalls.incrementAndGet(); return true; }, fixture.complete(completionCalls), () -> {});

        assertEquals(SmokeAnswerDelivery.Outcome.ANSWER_NOT_PUBLISHED, outcome);
        assertEquals(0, sinkCalls.get());
        assertEquals(0, completionCalls.get());
        assertTrue(fixture.lifecycle.isActive(fixture.request));
    }

    @Test
    void completeAnswerAtTheUtf16LimitIsPublishedWithoutTruncation() {
        Fixture fixture = new Fixture();
        AtomicReference<String> sent = new AtomicReference<>();

        SmokeAnswerDelivery.Outcome outcome = SmokeAnswerDelivery.publishThenComplete("Archivist", "x".repeat(1980),
                rendered -> { sent.set(rendered); return true; }, fixture.complete(new AtomicInteger()), () -> {});

        assertEquals(SmokeAnswerDelivery.Outcome.COMPLETED, outcome);
        assertEquals(1980, sent.get().substring("[Worldspeaker] Archivist: ".length()).length());
    }

    @Test
    void unavailablePlayerCannotPublishOrCompleteTheRequest() {
        Fixture fixture = new Fixture();
        AtomicInteger completionCalls = new AtomicInteger();
        AtomicInteger nextTurnCalls = new AtomicInteger();

        SmokeAnswerDelivery.Outcome outcome = SmokeAnswerDelivery.publishThenComplete("Archivist", "valid answer", null,
                fixture.complete(completionCalls), nextTurnCalls::incrementAndGet);

        assertEquals(SmokeAnswerDelivery.Outcome.PLAYER_UNAVAILABLE, outcome);
        assertEquals(0, completionCalls.get());
        assertEquals(0, nextTurnCalls.get());
        assertTrue(fixture.lifecycle.isActive(fixture.request));
        assertEquals(fixture.acknowledgement, fixture.acknowledgementState.locallyDispatched());
        assertTrue(fixture.acknowledgementState.contextDeliveryPending());
    }

    private static final class Fixture {
        private final SmokeRequestLifecycle<Object> lifecycle = new SmokeRequestLifecycle<>();
        private final Object request = new Object();
        private final SmokeRequestAcknowledgement acknowledgementState = new SmokeRequestAcknowledgement();
        private final SmokeRequestAcknowledgement.Acknowledgement acknowledgement;

        private Fixture() {
            Object connection = new Object();
            lifecycle.register(request, connection);
            assertTrue(lifecycle.start(request));
            assertTrue(acknowledgementState.markForwarded());
            assertEquals(SmokeRequestAcknowledgement.Dispatch.SEND_AFTER_REQUEST,
                    acknowledgementState.recordDispatched(true, ACK_MESSAGE_ID, ACK_TEXT));
            acknowledgement = acknowledgementState.locallyDispatched();
            assertTrue(acknowledgementState.beginContextDelivery());
        }

        private java.util.function.BooleanSupplier complete(AtomicInteger completionCalls) {
            return () -> {
                completionCalls.incrementAndGet();
                boolean completed = lifecycle.complete(request);
                if (completed) acknowledgementState.close();
                return completed;
            };
        }
    }
}
