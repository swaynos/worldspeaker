package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.GateState;
import org.worldspeaker.compat.PeerFacts;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeCompanionOutputEligibilityTest {
    @Test
    void queuedFailureAndAnswerFromInvalidatedGenerationCannotActBeforeRetirementOrCrossIntoNextGeneration() {
        Harness harness = new Harness();
        TestRequest retiredRequest = harness.startRequest("old-request", new Object());

        harness.queueOutput(OutputKind.FAILURE, retiredRequest);
        harness.queueOutput(OutputKind.ANSWER, retiredRequest);
        harness.gate.disconnected();
        harness.server.execute(() -> {
            harness.invalidationRuns++;
            harness.lifecycle.retire(retiredRequest);
        });

        harness.server.runNext(); // failure callback is ahead of queued onUnavailable retirement
        assertTrue(harness.lifecycle.isActive(retiredRequest), "the failure must not complete the not-yet-retired request");
        assertEquals(0, harness.completions);
        assertEquals(0, harness.publications);
        assertEquals(0, harness.invalidationRuns);

        harness.server.runNext(); // answer callback uses the same gate/lifecycle/connection eligibility
        assertTrue(harness.lifecycle.isActive(retiredRequest));
        assertEquals(0, harness.completions);
        assertEquals(0, harness.publications);
        assertEquals(0, harness.invalidationRuns);

        harness.server.runNext(); // queued invalidation now retires the old request
        assertFalse(harness.lifecycle.isActive(retiredRequest));
        assertEquals(1, harness.invalidationRuns);
        assertEquals(0, harness.completions);
        assertEquals(0, harness.publications, "neither stale companion output may publish");

        assertEquals(GateState.READY, harness.gate.companionAuthenticated(candidatePeer()).state());
        TestRequest nextGenerationRequest = harness.startRequest("new-request", new Object());
        harness.queueOutput(OutputKind.FAILURE, retiredRequest);
        harness.queueOutput(OutputKind.ANSWER, retiredRequest);
        harness.server.runNext();
        harness.server.runNext();

        assertSame(nextGenerationRequest, harness.lifecycle.active(), "old frames cannot complete a new generation's request");
        assertEquals(0, harness.completions);
        assertEquals(0, harness.publications);
    }

    @Test
    void queuedOutputsRequireTheOriginalConnectionToStillBeCurrentAtExecution() {
        Harness harness = new Harness();
        TestRequest request = harness.startRequest("same-generation-request", new Object());

        harness.queueOutput(OutputKind.FAILURE, request);
        harness.queueOutput(OutputKind.ANSWER, request);
        harness.currentConnection.set(new Object());

        harness.server.runNext();
        harness.server.runNext();

        assertTrue(harness.lifecycle.isActive(request));
        assertEquals(0, harness.completions);
        assertEquals(0, harness.publications);
    }

    @Test
    void retiredLifecycleCannotPassEligibilityWhileGateAndConnectionRemainCurrent() {
        Harness harness = new Harness();
        TestRequest request = harness.startRequest("retired-request", new Object());
        assertTrue(harness.lifecycle.retire(request));

        boolean executed = SmokeCompanionOutputEligibility.executeIfEligible(harness.gate,
                request.gateGeneration(), request, harness.lifecycle::isActive,
                candidate -> harness.lifecycle.isActiveOn(candidate, harness.currentConnection.get())
                        ? harness.currentConnection.get() : null,
                (candidate, connection) -> harness.publications++);

        assertFalse(executed);
        assertEquals(0, harness.publications);
        assertEquals(0, harness.completions);
    }

    @Test
    void currentFailureStillCompletesAndPublishesItsTerminalNotice() {
        Harness harness = new Harness();
        TestRequest request = harness.startRequest("current-failure", new Object());

        harness.queueOutput(OutputKind.FAILURE, request);
        harness.server.runNext();

        assertFalse(harness.lifecycle.isActive(request));
        assertEquals(1, harness.completions);
        assertEquals(1, harness.publications);
    }

    private enum OutputKind {
        ANSWER,
        FAILURE
    }

    private record TestRequest(String requestId, String playerId, long gateGeneration, Object connection) {}

    private static final class Harness {
        private final CompatibilityGate gate = readyGate();
        private final SmokeRequestLifecycle<TestRequest> lifecycle = new SmokeRequestLifecycle<>();
        private final ScheduledServer server = new ScheduledServer();
        private final AtomicReference<Object> currentConnection = new AtomicReference<>();
        private int completions;
        private int publications;
        private int invalidationRuns;

        private TestRequest startRequest(String requestId, Object connection) {
            TestRequest request = new TestRequest(requestId, "player-1", gate.snapshot().generation(), connection);
            currentConnection.set(connection);
            lifecycle.register(request, connection);
            assertTrue(lifecycle.start(request));
            return request;
        }

        private void queueOutput(OutputKind kind, TestRequest frameRequest) {
            server.execute(() -> {
                TestRequest request = lifecycle.active();
                if (request == null || !request.requestId().equals(frameRequest.requestId()) ||
                        !request.playerId().equals(frameRequest.playerId())) return;

                SmokeCompanionOutputEligibility.executeIfEligible(gate, request.gateGeneration(), request,
                        lifecycle::isActive,
                        candidate -> lifecycle.isActiveOn(candidate, currentConnection.get())
                                ? currentConnection.get() : null,
                        (candidate, connection) -> {
                            if (kind == OutputKind.FAILURE) {
                                if (!lifecycle.complete(candidate)) return;
                                completions++;
                                VanillaDialogue.publishRendered(VanillaDialogue.renderLine("generic failure"),
                                        (line, overlay) -> publications++);
                                return;
                            }

                            SmokeAnswerDelivery.publishThenComplete("Archivist", "answer",
                                    rendered -> VanillaDialogue.publishRendered(rendered,
                                            (line, overlay) -> publications++),
                                    () -> {
                                        boolean completed = lifecycle.complete(candidate);
                                        if (completed) completions++;
                                        return completed;
                                    },
                                    () -> {});
                        });
            });
        }
    }

    private static final class ScheduledServer {
        private final Deque<Runnable> actions = new ArrayDeque<>();

        private void execute(Runnable action) {
            actions.addLast(action);
        }

        private void runNext() {
            actions.removeFirst().run();
        }
    }

    private static CompatibilityGate readyGate() {
        CompatibilityGate gate = new CompatibilityGate(
                new ConfiguredBinding("server-test", "world-test", "character-test", "agent-test"));
        gate.checkLocal(new RuntimeFacts(
                DeploymentFacts.MINECRAFT_VERSION,
                DeploymentFacts.FABRIC_LOADER_VERSION,
                DeploymentFacts.FABRIC_API_VERSION,
                Integer.parseInt(DeploymentFacts.JAVA_MAJOR),
                DeploymentFacts.JAVA_VENDOR,
                DeploymentFacts.JAVA_RUNTIME_VERSION));
        assertEquals(GateState.READY, gate.companionAuthenticated(candidatePeer()).state());
        return gate;
    }

    private static PeerFacts candidatePeer() {
        return new PeerFacts(DeploymentFacts.DEPLOYMENT_ID, DeploymentFacts.MANIFEST_SHA256,
                DeploymentFacts.PROTOCOL, DeploymentFacts.OPENCLAW_VERSION, DeploymentFacts.NODE_VERSION,
                DeploymentFacts.COMPANION_VERSION, "server-test", "world-test", "character-test", "agent-test");
    }
}
