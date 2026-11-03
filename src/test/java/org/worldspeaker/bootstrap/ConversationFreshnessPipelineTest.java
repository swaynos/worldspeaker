package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.AttentionPolicy;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.GateState;
import org.worldspeaker.compat.PeerFacts;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the same immutable-snapshot pipeline wired into SmokeConversation's production hooks. */
class ConversationFreshnessPipelineTest {
    private static final UUID A = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID EPOCH_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final ConfiguredBinding BINDING = new ConfiguredBinding("server", "world", "character", "agent");
    private static final Object SERVICE_EPOCH = new Object();
    private static final long SERVICE_GENERATION = 7;

    @Test
    void lifecycleInvalidationPrecedesDisabledRateLimitedFullBufferAndFailedOptionalCapture() {
        assertOptionalFailureStillInvalidates(AttentionPolicy.disabled(), OptionalCase.DISABLED);
        assertOptionalFailureStillInvalidates(policy(16, 8192, 1, 1), OptionalCase.RATE_EXHAUSTED);
        assertOptionalFailureStillInvalidates(policy(1, 8192, 16, 4), OptionalCase.FULL_BUFFER);
        assertOptionalFailureStillInvalidates(policy(16, 8192, 16, 4), OptionalCase.SOURCE_FAILURE);
    }

    @Test
    void deathAndDimensionRoundTripRotateTokensAndObsoleteBothPendingAndActiveWork() {
        Harness death = new Harness(Set.of(A));
        PlayerFreshness.Observation alive = observation(new Object(), new Object(), "minecraft:overworld", true, 0);
        PlayerFreshness.Snapshot beforeDeath = death.seed(A, alive);
        TestRequest[] deathRequests = death.addPendingAndActive(A);
        PlayerFreshness.Observation dead = observation(alive.entity(), alive.connection(), "minecraft:overworld", false, 0);
        death.current.set(snapshot(A, dead));
        death.pipeline.onCallback(List.of(change(A, dead, alive.entity(), alive.connection())), null);

        assertTrue(deathRequests[0].obsolete);
        assertTrue(deathRequests[1].obsolete);
        assertFalse(death.isFresh(stamp(A, beforeDeath, death.gate.snapshot().generation(), false), death.gate));

        Object respawnEntity = new Object();
        Object respawnConnection = new Object();
        PlayerFreshness.Observation respawned = observation(respawnEntity, respawnConnection,
                "minecraft:overworld", true, 0);
        death.current.set(snapshot(A, respawned));
        death.pipeline.onCallback(List.of(change(A, respawned, respawnEntity, respawnConnection)), null);
        PlayerFreshness.Snapshot afterRespawn = death.seed(A, respawned);
        assertTrue(death.isFresh(stamp(A, afterRespawn, death.gate.snapshot().generation(), false), death.gate));
        assertFalse(death.isFresh(stamp(A, beforeDeath, death.gate.snapshot().generation(), false), death.gate));

        Harness dimensions = new Harness(Set.of(A));
        PlayerFreshness.Observation overworld = observation(new Object(), new Object(), "minecraft:overworld", true, 5);
        PlayerFreshness.Snapshot beforeTravel = dimensions.seed(A, overworld);
        TestRequest[] travelRequests = dimensions.addPendingAndActive(A);
        PlayerFreshness.Observation nether = observation(overworld.entity(), overworld.connection(), "minecraft:the_nether", true, 5);
        dimensions.current.set(snapshot(A, nether));
        dimensions.pipeline.onCallback(List.of(change(A, nether, overworld.entity(), overworld.connection())), null);
        PlayerFreshness.Observation returned = observation(overworld.entity(), overworld.connection(), "minecraft:overworld", true, 5);
        dimensions.current.set(snapshot(A, returned));
        dimensions.pipeline.onCallback(List.of(change(A, returned, overworld.entity(), overworld.connection())), null);

        assertTrue(travelRequests[0].obsolete);
        assertTrue(travelRequests[1].obsolete);
        assertFalse(dimensions.isFresh(stamp(A, beforeTravel, dimensions.gate.snapshot().generation(), false), dimensions.gate),
                "returning to the original dimension cannot restore the admission token");
    }

    @Test
    void respawnDepartureAndDisconnectRejoinAreIdentitySensitive() {
        Harness harness = new Harness(Set.of(A, B));
        PlayerFreshness.Observation oldA = observation(new Object(), new Object(), "minecraft:overworld", true, 0);
        PlayerFreshness.Observation oldB = observation(new Object(), new Object(), "minecraft:overworld", true, 0);
        PlayerFreshness.Snapshot aBeforeRespawn = harness.seed(A, oldA);
        PlayerFreshness.Snapshot bBeforeRespawn = harness.seed(B, oldB);
        TestRequest[] oldARequests = harness.addPendingAndActive(A);
        TestRequest[] oldBRequests = harness.addPendingAndActive(B);
        PlayerFreshness.Observation newB = observation(new Object(), new Object(), "minecraft:the_nether", true, 2);
        harness.current.set(snapshot(B, newB));
        harness.pipeline.onCallback(List.of(
                new ConversationFreshnessPipeline.LifecycleChange(A, null, oldA.entity(), oldA.connection()),
                change(B, newB, newB.entity(), newB.connection())), null);

        assertTrue(oldARequests[0].obsolete && oldARequests[1].obsolete);
        assertTrue(oldBRequests[0].obsolete && oldBRequests[1].obsolete);
        assertFalse(harness.isFresh(stamp(A, aBeforeRespawn, harness.gate.snapshot().generation(), false), harness.gate));
        assertFalse(harness.isFresh(stamp(B, bBeforeRespawn, harness.gate.snapshot().generation(), false), harness.gate));

        Harness reconnect = new Harness(Set.of(A));
        Object oldEntity = new Object();
        Object oldConnection = new Object();
        PlayerFreshness.Observation oldSession = observation(oldEntity, oldConnection, "minecraft:overworld", true, 0);
        PlayerFreshness.Snapshot oldToken = reconnect.seed(A, oldSession);
        TestRequest[] departedRequests = reconnect.addPendingAndActive(A);
        reconnect.pipeline.onCallback(List.of(new ConversationFreshnessPipeline.LifecycleChange(A, null,
                oldEntity, oldConnection)), null);
        Object newEntity = new Object();
        Object newConnection = new Object();
        PlayerFreshness.Observation newSession = observation(newEntity, newConnection, "minecraft:overworld", true, 0);
        reconnect.current.set(snapshot(A, newSession));
        reconnect.pipeline.onCallback(List.of(change(A, newSession, newEntity, newConnection)), null);
        PlayerFreshness.Snapshot newToken = reconnect.seed(A, newSession);
        assertTrue(departedRequests[0].obsolete && departedRequests[1].obsolete);
        assertFalse(reconnect.isFresh(stamp(A, oldToken, reconnect.gate.snapshot().generation(), false), reconnect.gate));
        assertTrue(reconnect.isFresh(stamp(A, newToken, reconnect.gate.snapshot().generation(), false), reconnect.gate));

        TestRequest[] rejoinedRequests = reconnect.addPendingAndActive(A);
        reconnect.pipeline.onCallback(List.of(new ConversationFreshnessPipeline.LifecycleChange(A, null,
                oldEntity, oldConnection)), null);
        assertFalse(rejoinedRequests[0].obsolete || rejoinedRequests[1].obsolete,
                "a delayed old-connection disconnect cannot obsolete the same-UUID rejoin");
        assertTrue(reconnect.isFresh(stamp(A, newToken, reconnect.gate.snapshot().generation(), false), reconnect.gate));
    }

    @Test
    void movementSamplingInvalidatesBeforeOptionalMovedCapture() {
        Harness harness = new Harness(Set.of(A));
        PlayerFreshness.Observation initial = observation(new Object(), new Object(), "minecraft:overworld", true, 3);
        harness.seed(A, initial);
        TestRequest[] requests = harness.addPendingAndActive(A);
        PlayerFreshness.Observation moved = observation(initial.entity(), initial.connection(), "minecraft:overworld", true, 4);
        harness.current.set(snapshot(A, moved));
        List<String> order = new ArrayList<>();
        harness.onObsolete(id -> order.add("obsolete:" + id));
        AtomicInteger captured = new AtomicInteger();

        ConversationFreshnessPipeline.SampleResult result = harness.pipeline.sample(A, snapshot(A, moved), () -> {
            order.add("moved-capture");
            captured.incrementAndGet();
        });

        assertTrue(result.moved());
        assertFalse(result.sourceUnavailable());
        assertTrue(requests[0].obsolete && requests[1].obsolete);
        assertEquals(1, captured.get());
        assertEquals(List.of("obsolete:" + A, "moved-capture"), order);
    }

    @Test
    void snapshotSourceFailureFailsClosedAndInvalidatesLiveRequests() {
        Harness harness = new Harness(Set.of(A));
        PlayerFreshness.Snapshot token = harness.seed(A,
                observation(new Object(), new Object(), "minecraft:overworld", true, 0));
        TestRequest[] requests = harness.addPendingAndActive(A);
        int invalidationsBeforeFailure = harness.obsoleteCalls.get();
        harness.current.set(snapshot(A, null));

        assertFalse(harness.isFresh(stamp(A, token, harness.gate.snapshot().generation(), false), harness.gate));
        assertTrue(requests[0].obsolete && requests[1].obsolete);
        assertEquals(invalidationsBeforeFailure + 1, harness.obsoleteCalls.get());
    }

    @Test
    void preForwardAndFinalOutputGatesRejectFreshnessOrGenerationChangesAndSerializeGateRace() throws Exception {
        Harness harness = new Harness(Set.of(A));
        PlayerFreshness.Observation observation = observation(new Object(), new Object(), "minecraft:overworld", true, 0);
        PlayerFreshness.Snapshot token = harness.seed(A, observation);
        TestRequest active = harness.add(A, Phase.ACTIVE);
        long initialGateGeneration = harness.gate.snapshot().generation();
        ConversationFreshnessPipeline.RequestSnapshot request = stamp(A, token, initialGateGeneration, false);

        assertTrue(harness.isFresh(request, harness.gate), "the production pre-forward freshness seam accepts current evidence");
        harness.gate.disconnected();
        int readsAtGateRace = harness.snapshotReads.get();
        assertFalse(harness.isFresh(request, harness.gate), "a gate generation race fails before a player snapshot read");
        assertEquals(readsAtGateRace, harness.snapshotReads.get());
        assertEquals(GateState.UNAVAILABLE, harness.gate.snapshot().state());

        authenticate(harness.gate);
        long readyGeneration = harness.gate.snapshot().generation();
        ConversationFreshnessPipeline.RequestSnapshot readyRequest = stamp(A, token, readyGeneration, false);
        ConversationFreshnessPipeline.RequestSnapshot staleFinalRequest = readyRequest;
        AtomicInteger publications = new AtomicInteger();
        boolean staleFinal = SmokeCompanionOutputEligibility.executeIfEligible(harness.gate, readyGeneration, active,
                candidate -> candidate.phase == Phase.ACTIVE,
                candidate -> {
                    harness.current.set(snapshot(A, observation(new Object(), observation.connection(),
                            "minecraft:overworld", true, 0)));
                    return harness.pipeline.isFresh(staleFinalRequest, runtime(harness.gate)) ? observation.connection() : null;
                },
                (candidate, connection) -> publications.incrementAndGet());
        assertFalse(staleFinal);
        assertEquals(0, publications.get(), "a replaced entity cannot pass the final gate");
        assertTrue(active.obsolete);

        harness.current.set(snapshot(A, observation));
        readyRequest = stamp(A, harness.seed(A, observation), readyGeneration, false);
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch disconnectAttempting = new CountDownLatch(1);
        CountDownLatch disconnectFinished = new CountDownLatch(1);
        AtomicBoolean transitionWasBlocked = new AtomicBoolean();
        ConversationFreshnessPipeline.RequestSnapshot finalRequest = readyRequest;
        Thread gateTransition = new Thread(() -> {
            try {
                assertTrue(actionStarted.await(2, TimeUnit.SECONDS));
                disconnectAttempting.countDown();
                harness.gate.disconnected();
                disconnectFinished.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "freshness-gate-race");
        gateTransition.start();

        boolean finalExecuted = SmokeCompanionOutputEligibility.executeIfEligible(harness.gate, readyGeneration, active,
                candidate -> candidate.phase == Phase.ACTIVE,
                candidate -> harness.pipeline.isFresh(finalRequest, runtime(harness.gate)) ? observation.connection() : null,
                (candidate, connection) -> {
                    actionStarted.countDown();
                    try {
                        assertTrue(disconnectAttempting.await(2, TimeUnit.SECONDS));
                        transitionWasBlocked.set(!disconnectFinished.await(50, TimeUnit.MILLISECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    publications.incrementAndGet();
                });
        gateTransition.join(2_000);

        assertFalse(gateTransition.isAlive(), "the gate transition completes after the final action releases the gate lock");
        assertTrue(finalExecuted);
        assertTrue(transitionWasBlocked.get(), "generation invalidation cannot interleave with the protected output action");
        assertEquals(1, publications.get());
        assertFalse(SmokeCompanionOutputEligibility.executeIfEligible(harness.gate, readyGeneration, active,
                candidate -> candidate.phase == Phase.ACTIVE,
                candidate -> observation.connection(),
                (candidate, connection) -> publications.incrementAndGet()),
                "a later frame cannot cross the now-invalid generation");
        assertEquals(1, publications.get());
    }

    private static void assertOptionalFailureStillInvalidates(AttentionPolicy policy, OptionalCase optionalCase) {
        Harness harness = new Harness(Set.of(A));
        PlayerFreshness.Observation before = observation(new Object(), new Object(), "minecraft:overworld", true, 0);
        PlayerFreshness.Snapshot token = harness.seed(A, before);
        TestRequest[] requests = harness.addPendingAndActive(A);
        AttentionBuffer buffer = new AttentionBuffer(policy, Set.of(A), BINDING, EPOCH_ID);
        AtomicInteger payloadFactories = new AtomicInteger();
        AtomicBoolean captured = new AtomicBoolean();
        if (optionalCase == OptionalCase.RATE_EXHAUSTED || optionalCase == OptionalCase.FULL_BUFFER) {
            assertTrue(buffer.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 10,
                    () -> event(A, 1, 10)));
        }
        PlayerFreshness.Observation after = observation(before.entity(), before.connection(), "minecraft:the_nether", true, 0);
        harness.current.set(snapshot(A, after));
        List<String> order = new ArrayList<>();
        harness.onObsolete(id -> order.add("mandatory-invalidation"));

        harness.pipeline.onCallback(List.of(change(A, after, before.entity(), before.connection())), () -> {
            order.add("optional-capture");
            boolean accepted = buffer.capture(A, AttentionPolicy.Subscription.PLAYER_CONNECTION, 10, () -> {
                payloadFactories.incrementAndGet();
                if (optionalCase == OptionalCase.SOURCE_FAILURE) throw new IllegalArgumentException("unavailable fixture");
                return event(A, 2, 10);
            });
            captured.set(accepted);
        });

        assertTrue(requests[0].obsolete && requests[1].obsolete, optionalCase + " attention does not suppress freshness invalidation");
        assertEquals(List.of("mandatory-invalidation", "optional-capture"), order);
        assertFalse(harness.isFresh(stamp(A, token, harness.gate.snapshot().generation(), false), harness.gate));
        switch (optionalCase) {
            case DISABLED -> {
                assertFalse(captured.get());
                assertEquals(0, payloadFactories.get(), "disabled capture allocates no event payload");
                assertEquals("disabled", buffer.select(A, 10, 8, 8192).status().state());
            }
            case RATE_EXHAUSTED -> {
                assertFalse(captured.get());
                assertEquals(0, payloadFactories.get(), "the limited callback does not allocate its payload");
                assertTrue(buffer.select(A, 10, 8, 8192).status().reasons().contains("rate"));
            }
            case FULL_BUFFER -> {
                assertTrue(captured.get(), "the bounded buffer evicts the oldest record rather than blocking freshness");
                assertEquals(1, buffer.retainedCount());
                assertTrue(buffer.select(A, 10, 8, 8192).status().reasons().contains("capacity"));
            }
            case SOURCE_FAILURE -> {
                assertFalse(captured.get());
                assertTrue(buffer.select(A, 10, 8, 8192).status().reasons().contains("source_unavailable"));
            }
        }
    }

    private static ConversationFreshnessPipeline.LifecycleChange change(UUID id,
                                                                          PlayerFreshness.Observation observation,
                                                                          Object oldEntity, Object oldConnection) {
        return new ConversationFreshnessPipeline.LifecycleChange(id, snapshot(id, observation), oldEntity, oldConnection);
    }

    private static ConversationFreshnessPipeline.CurrentSnapshot snapshot(UUID id,
                                                                            PlayerFreshness.Observation observation) {
        return new ConversationFreshnessPipeline.CurrentSnapshot(id, observation);
    }

    private static PlayerFreshness.Observation observation(Object entity, Object connection, String dimension,
                                                           boolean alive, int x) {
        return new PlayerFreshness.Observation(entity, connection, dimension, alive, x, 64, 0);
    }

    private static AttentionPolicy policy(int maxEvents, int maxBytes, int rate, int playerRate) {
        return new AttentionPolicy(Set.of(AttentionPolicy.Subscription.PLAYER_CONNECTION), maxEvents, 30,
                maxBytes, rate, playerRate);
    }

    private static AttentionEvent event(UUID id, long wallTime, long monotonicTime) {
        return new AttentionEvent(BINDING.serverId(), BINDING.worldId(), BINDING.characterId(), BINDING.agentId(),
                UUID.randomUUID(), EPOCH_ID, wallTime, monotonicTime, "live", "server_callback", id,
                "PlayerA", "minecraft:overworld", "joined", AttentionEvent.Details.empty());
    }

    private static ConversationFreshnessPipeline.RequestSnapshot stamp(UUID id, PlayerFreshness.Snapshot freshness,
                                                                         long gateGeneration, boolean obsolete) {
        return new ConversationFreshnessPipeline.RequestSnapshot(id, freshness, SERVICE_EPOCH,
                SERVICE_GENERATION, gateGeneration, obsolete);
    }

    private static ConversationFreshnessPipeline.RuntimeSnapshot runtime(CompatibilityGate gate) {
        return new ConversationFreshnessPipeline.RuntimeSnapshot(SERVICE_EPOCH, SERVICE_GENERATION,
                gate.snapshot().generation());
    }

    private static void authenticate(CompatibilityGate gate) {
        gate.checkLocal(new RuntimeFacts(DeploymentFacts.MINECRAFT_VERSION, DeploymentFacts.FABRIC_LOADER_VERSION,
                DeploymentFacts.FABRIC_API_VERSION, Integer.parseInt(DeploymentFacts.JAVA_MAJOR),
                DeploymentFacts.JAVA_VENDOR, DeploymentFacts.JAVA_RUNTIME_VERSION));
        gate.companionAuthenticated(new PeerFacts(DeploymentFacts.DEPLOYMENT_ID, DeploymentFacts.MANIFEST_SHA256,
                DeploymentFacts.PROTOCOL, DeploymentFacts.OPENCLAW_VERSION, DeploymentFacts.NODE_VERSION,
                DeploymentFacts.COMPANION_VERSION, BINDING.serverId(), BINDING.worldId(), BINDING.characterId(),
                BINDING.agentId()));
    }

    private enum OptionalCase { DISABLED, RATE_EXHAUSTED, FULL_BUFFER, SOURCE_FAILURE }

    private enum Phase { PENDING, ACTIVE }

    private static final class TestRequest {
        private final UUID playerId;
        private final Phase phase;
        private boolean obsolete;

        private TestRequest(UUID playerId, Phase phase) {
            this.playerId = playerId;
            this.phase = phase;
        }
    }

    private static final class Harness {
        private final CompatibilityGate gate = new CompatibilityGate(BINDING);
        private final PlayerFreshness freshness;
        private final AtomicReference<ConversationFreshnessPipeline.CurrentSnapshot> current = new AtomicReference<>();
        private final AtomicInteger snapshotReads = new AtomicInteger();
        private final AtomicInteger obsoleteCalls = new AtomicInteger();
        private final List<TestRequest> requests = new ArrayList<>();
        private final ConversationFreshnessPipeline pipeline;
        private java.util.function.Consumer<UUID> obsoleteObserver = ignored -> {};

        private Harness(Set<UUID> allowed) {
            authenticate(gate);
            freshness = new PlayerFreshness(allowed, SERVICE_EPOCH, SERVICE_GENERATION, BINDING);
            pipeline = new ConversationFreshnessPipeline(freshness, playerId -> {
                snapshotReads.incrementAndGet();
                ConversationFreshnessPipeline.CurrentSnapshot value = current.get();
                return value == null ? snapshot(playerId, null) : value;
            }, (playerId, cause) -> {
                obsoleteCalls.incrementAndGet();
                for (TestRequest request : requests) if (request.playerId.equals(playerId)) request.obsolete = true;
                obsoleteObserver.accept(playerId);
            });
        }

        private PlayerFreshness.Snapshot seed(UUID playerId, PlayerFreshness.Observation observation) {
            current.set(snapshot(playerId, observation));
            PlayerFreshness.Snapshot value = pipeline.captureAdmissionSnapshot(playerId, observation);
            assertNotNull(value);
            return value;
        }

        private TestRequest[] addPendingAndActive(UUID playerId) {
            return new TestRequest[] {add(playerId, Phase.PENDING), add(playerId, Phase.ACTIVE)};
        }

        private TestRequest add(UUID playerId, Phase phase) {
            TestRequest request = new TestRequest(playerId, phase);
            requests.add(request);
            return request;
        }

        private void onObsolete(java.util.function.Consumer<UUID> observer) {
            obsoleteObserver = observer;
        }

        private boolean isFresh(ConversationFreshnessPipeline.RequestSnapshot request, CompatibilityGate targetGate) {
            return pipeline.isFresh(request, runtime(targetGate));
        }
    }
}
