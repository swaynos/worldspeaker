package org.worldspeaker.compat;

import org.junit.jupiter.api.Test;
import org.worldspeaker.generated.DeploymentFacts;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityGateTest {
    private static RuntimeFacts candidateRuntime() {
        return new RuntimeFacts(
                DeploymentFacts.MINECRAFT_VERSION,
                DeploymentFacts.FABRIC_LOADER_VERSION,
                DeploymentFacts.FABRIC_API_VERSION,
                Integer.parseInt(DeploymentFacts.JAVA_MAJOR),
                DeploymentFacts.JAVA_VENDOR,
                DeploymentFacts.JAVA_RUNTIME_VERSION);
    }

    private static ConfiguredBinding binding() {
        return new ConfiguredBinding("server-test", "world-test", "character-test", "agent-test");
    }

    private static PeerFacts candidatePeer() {
        return new PeerFacts(DeploymentFacts.DEPLOYMENT_ID, DeploymentFacts.MANIFEST_SHA256,
                DeploymentFacts.PROTOCOL, DeploymentFacts.OPENCLAW_VERSION, DeploymentFacts.NODE_VERSION,
                DeploymentFacts.COMPANION_VERSION, "server-test", "world-test", "character-test", "agent-test");
    }

    @Test
    void startsUnavailableUntilAllFactsAreValidated() {
        CompatibilityGate gate = new CompatibilityGate(binding());
        GateSnapshot local = gate.checkLocal(candidateRuntime());
        assertEquals(GateState.UNAVAILABLE, local.state());
        assertEquals("WS-COMPAT-COMPANION-MISSING", local.reasonCode());
        assertEquals(GateState.READY, gate.companionAuthenticated(candidatePeer()).state());
    }

    @Test
    void exactJavaBuildAndCompanionBindingAreRequired() {
        CompatibilityGate gate = new CompatibilityGate(binding());
        RuntimeFacts wrongJava = new RuntimeFacts("1.21.1", "0.20.1", "0.116.17+1.21.1", 21,
                "Eclipse Adoptium", "21.0.3+9-LTS");
        assertEquals("WS-COMPAT-JAVA-BUILD", gate.checkLocal(wrongJava).reasonCode());
        assertEquals(GateState.UNAVAILABLE, gate.companionAuthenticated(candidatePeer()).state());

        gate.checkLocal(candidateRuntime());
        PeerFacts wrongBinding = new PeerFacts(candidatePeer().deploymentId(), candidatePeer().manifestSha256(),
                candidatePeer().protocol(), candidatePeer().hostVersion(), candidatePeer().nodeVersion(),
                candidatePeer().companionVersion(), "other-server", "world-test", "character-test", "agent-test");
        assertEquals("WS-COMPAT-PEER-MISMATCH", gate.companionAuthenticated(wrongBinding).reasonCode());
    }

    @Test
    void disconnectInvalidatesQueuedWorkByGeneration() {
        CompatibilityGate gate = new CompatibilityGate(binding());
        gate.checkLocal(candidateRuntime());
        GateSnapshot ready = gate.companionAuthenticated(candidatePeer());
        AtomicInteger gameWork = new AtomicInteger();
        Runnable queued = () -> gate.executeIfReady(ready.generation(), gameWork::incrementAndGet);

        gate.disconnected();
        queued.run();

        assertEquals(0, gameWork.get());
        assertFalse(gate.executeIfReady(ready.generation(), gameWork::incrementAndGet));
    }

    @Test
    void onlyTheCurrentReadyGenerationCanCrossExecutionBoundary() {
        CompatibilityGate gate = new CompatibilityGate(binding());
        gate.checkLocal(candidateRuntime());
        GateSnapshot ready = gate.companionAuthenticated(candidatePeer());
        AtomicInteger work = new AtomicInteger();

        assertTrue(gate.executeIfReady(ready.generation(), work::incrementAndGet));
        assertEquals(1, work.get());
        gate.unavailable("WS-COMPAT-PEER-MISMATCH");
        assertFalse(gate.executeIfReady(ready.generation(), work::incrementAndGet));
        assertEquals(1, work.get());
    }

    @Test
    void allSelectedAbilitiesRemainUnavailable() {
        CompatibilityGate gate = new CompatibilityGate(binding());
        gate.checkLocal(candidateRuntime());
        assertEquals(GateState.READY, gate.companionAuthenticated(candidatePeer()).state());

        for (String ability : new String[] {"worldspeaker.core.observe_player", "worldspeaker.core.strike_lightning"}) {
            assertFalse(AbilityAvailability.isImplemented(ability, 1));
            assertFalse(AbilityAvailability.isImplemented(ability, 2));
            assertEquals("unsupported_state", AbilityAvailability.unavailableReason(ability, 1));
        }
        // Availability is independent of caller role; operator status is not an input or bypass.
        assertFalse(AbilityAvailability.isImplemented("anything.operator", 999));
    }
}
