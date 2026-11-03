package org.worldspeaker.bootstrap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.compat.AttentionPolicy;
import org.worldspeaker.ability.AbilityPolicyResolver;
import org.worldspeaker.ability.AbilityValidation;
import org.worldspeaker.compat.AbilityPolicy;
import org.worldspeaker.compat.CharacterChatStyle;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.GateState;
import org.worldspeaker.compat.InteractionPolicy;
import org.worldspeaker.compat.PeerFacts;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;
import org.worldspeaker.integration.BoundCompanionClient;
import org.worldspeaker.integration.BoundCompanionClientTestAccess;
import org.worldspeaker.integration.PeerHandshake;
import org.worldspeaker.runtime.GameLoopMailbox;
import org.worldspeaker.runtime.GameLoopMailboxTestAccess;
import org.worldspeaker.runtime.ThreadBoundary;
import org.worldspeaker.runtime.TickMetrics;
import org.worldspeaker.runtime.TickMetricsTestAccess;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the actual ingress, SmokeConversation lifecycle, companion parser, mailbox, and output gate. */
class SmokeConversationLifecycleTest {
    private static final long START_NANOS = 3_000_000_000L;
    private static final long WALL_MILLIS = 1_793_275_200_000L;
    private static final String PLAYER_ID = "00000000-0000-4000-8000-000000000001";
    private static final String QUERY_A = "00000000-0000-4000-8000-000000000021";
    private static final String QUERY_B = "00000000-0000-4000-8000-000000000022";
    private static final String CHALLENGE = "ERERERERERERERERERERERERERERERERERERERERERE";
    private static final String SERVER_EPOCH = "00000000-0000-4000-8000-000000000019";

    @TempDir
    Path temporaryDirectory;

    @Test
    void admittedSmokeRequestIssuesConsumesAndRetiresThroughAnIsolatedOwner() {
        AbilityPolicy policy = abilityPolicy();
        String intent = observeIntent(PLAYER_ID);
        try (Harness harness = new Harness(30, false, policy)) {
            harness.admit("A request owned by the isolated lifecycle port");
            harness.tickAt(START_NANOS);
            GameLoopMailbox.ActiveRequest active = harness.mailbox.activeRequest();
            assertNotNull(active);

            var permittedButUnavailable = harness.conversation.evaluateAbilityIntent(
                    active.requestId(), intent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                    permittedButUnavailable.reason(), "simulated identity reads=" +
                            harness.abilityGame.simulatedIdentityReads.get() + ", current checks=" +
                            harness.abilityGame.currentChecks.get() + ", fresh facts reads=" +
                            harness.abilityGame.freshFactsReads.get());
            assertFalse(permittedButUnavailable.effectStarted());
            assertTrue(harness.abilityGame.currentChecks.get() > 0,
                    "consumption checks the isolated owner's current request state");
            assertEquals(1, harness.abilityGame.freshFactsReads.get(),
                    "a matched request and policy perform one isolated fresh-facts read");
            assertTrue(harness.abilityGame.simulatedIdentityReads.get() > 0);
            assertFalse(harness.abilityGame.livePacketEvidenceObserved,
                    "the isolated owner's simulated identity reads are not live authentication proof");
            assertEquals(1, harness.conversation.retainedAbilityHoldsForTesting(),
                    "the real ingress/registration/gate path retains only an isolated simulated hold");
            assertEquals(1, harness.conversation.releaseRetainedAbilityHoldsForTesting());
            assertEquals(0, harness.conversation.retainedAbilityHoldsForTesting());

            var unauthorizedTarget = harness.conversation.evaluateAbilityIntent(active.requestId(),
                    observeIntent(harness.secondPlayerId.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.PERMISSION_DENIED,
                    unauthorizedTarget.reason());
            assertEquals(1, harness.abilityGame.freshFactsReads.get(),
                    "pure target-policy rejection occurs before private world-facts reads");
            assertFalse(unauthorizedTarget.effectStarted());
            assertEquals(0, harness.socket.sendCount("ability.result"));
        }

        assertRetiredAuthorityIsStale("terminal-cancel", harness -> harness.conversation.close(), false);
        assertRetiredAuthorityIsStale("expiry", harness -> {
            harness.clock.set(START_NANOS + TimeUnit.SECONDS.toNanos(46));
        });
        assertRetiredAuthorityIsStale("disconnect", harness -> harness.game.disconnect(harness.playerId));
        assertRetiredAuthorityIsStale("entity-replacement", harness ->
                harness.game.replacePlayer(harness.playerId, new FakePlayer("Replacement")));
        assertRetiredAuthorityIsStale("dimension-change", harness ->
                harness.game.changeContext(harness.player, "minecraft:the_nether", 0, 64, 0));
        assertRetiredAuthorityIsStale("movement", harness ->
                harness.game.changeContext(harness.player, "minecraft:overworld", 1, 64, 0));
        assertRetiredAuthorityIsStale("service-epoch-revocation", harness -> harness.abilityGame.revokeEpoch());
        assertRetiredAuthorityIsStale("gate-generation-revocation",
                harness -> harness.gate.unavailable("WS-TEST-GATE-REVOKED"));
        assertRetiredAuthorityIsStale("authenticated-peer-restart", Harness::restartPeer);
    }

    @Test
    void correlatedObserveQueriesDeduplicatePerIdAndDistinctIdsStayOwnerBound() {
        AbilityPolicy policy = abilityPolicyWithOtherTarget();
        try (Harness harness = new Harness(30, false, policy)) {
            harness.admit("Correlate private Observe attempts");
            harness.tickAt(START_NANOS);
            GameLoopMailbox.ActiveRequest active = harness.mailbox.activeRequest();
            assertNotNull(active);

            byte[] selfIntent = observeIntent(PLAYER_ID).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var first = harness.conversation.evaluateAbilityIntent(active.requestId(), selfIntent, QUERY_A);
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                    first.reason());
            assertFalse(first.effectStarted());
            assertEquals(1, harness.abilityGame.freshFactsReads.get());
            assertEquals(1, harness.conversation.retainedAbilityHoldsForTesting());

            var duplicate = harness.conversation.evaluateAbilityIntent(active.requestId(), selfIntent, QUERY_A);
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                    duplicate.reason());
            assertEquals(1, harness.abilityGame.freshFactsReads.get(),
                    "an identical query ID returns its existing hold without another facts read");
            assertEquals(1, harness.conversation.retainedAbilityHoldsForTesting());

            byte[] changedIntent = observeIntent(harness.secondPlayerId.toString())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var conflict = harness.conversation.evaluateAbilityIntent(active.requestId(), changedIntent, QUERY_A);
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                    conflict.reason());
            assertEquals(1, harness.abilityGame.freshFactsReads.get(),
                    "a query ID rebound to changed arguments conflicts before a fresh facts read");
            assertEquals(1, harness.conversation.retainedAbilityHoldsForTesting());

            assertEquals(1, harness.conversation.releaseRetainedAbilityHoldsForTesting());
            var distinct = harness.conversation.evaluateAbilityIntent(active.requestId(), changedIntent, QUERY_B);
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                    distinct.reason());
            assertFalse(distinct.effectStarted());
            assertEquals(2, harness.abilityGame.freshFactsReads.get(),
                    "a distinct internal query ID reaches the same admitted owner and fresh-facts path");
            assertEquals(1, harness.conversation.retainedAbilityHoldsForTesting());

            assertEquals(1, harness.conversation.releaseRetainedAbilityHoldsForTesting());
            assertEquals(0, harness.conversation.retainedAbilityHoldsForTesting());
            assertEquals(0, harness.socket.sendCount("ability.result"));
            assertFalse(harness.abilityGame.livePacketEvidenceObserved,
                    "synthetic owner simulation is not live packet authority");

            var malformedIdentity = harness.conversation.evaluateAbilityIntent(active.requestId(), selfIntent,
                    "not-a-canonical-uuid");
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.STALE_CONTEXT,
                    malformedIdentity.reason());
            assertEquals(2, harness.abilityGame.freshFactsReads.get());
            assertEquals(0, harness.conversation.retainedAbilityHoldsForTesting());
        }
    }

    @Test
    void twoIngressRequestsShareTheRealIsolatedConversationHoldUntilRelease() {
        try (Harness harness = new Harness(30, false, abilityPolicy())) {
            InteractionIngress.Invocation firstInvocation = harness.admit("First admitted request for this requester");
            harness.tickAt(START_NANOS);
            String firstRequestId = firstInvocation.linkedRequestIdForTesting();
            assertNotNull(firstRequestId);
            assertNotNull(harness.mailbox.activeRequest());

            byte[] selfIntent = observeIntent(PLAYER_ID).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var first = harness.conversation.evaluateAbilityIntent(firstRequestId, selfIntent, QUERY_A);
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                    first.reason(), "the genuine isolated owner retains an accounting-only hold");
            assertFalse(first.effectStarted());
            AbilityLimitLedger.Snapshot heldByFirst = harness.abilityLedger.snapshotForTests();
            assertEquals(1, heldByFirst.liveHolds());
            assertEquals(0, heldByFirst.chargedNodes());

            InteractionIngress.Invocation secondInvocation = harness.admit("Second distinct admitted request");
            String secondRequestId = secondInvocation.linkedRequestIdForTesting();
            assertNotNull(secondRequestId);
            assertNotEquals(firstRequestId, secondRequestId);

            var contended = harness.conversation.evaluateAbilityIntent(secondRequestId, selfIntent, QUERY_B);
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.CONCURRENCY_LIMITED,
                    contended.reason(), "a separately admitted request shares the first request's requester slot");
            assertFalse(contended.effectStarted());
            assertEquals(heldByFirst, harness.abilityLedger.snapshotForTests(),
                    "rejected contention installs no hold, identity, charge, or partial rate state");
            assertEquals(2, harness.abilityGame.freshFactsReads.get(),
                    "both admitted requests traverse their own private fresh-facts path before ledger admission");

            assertEquals(1, harness.conversation.releaseRetainedAbilityHoldsForTesting());
            AbilityLimitLedger.Snapshot releasedFirst = harness.abilityLedger.snapshotForTests();
            assertEquals(0, releasedFirst.liveHolds());
            assertEquals(0, releasedFirst.chargedNodes(), "pre-start release restores admission without a charge");

            var later = harness.conversation.evaluateAbilityIntent(secondRequestId, selfIntent, QUERY_B);
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                    later.reason(), "the same admitted request and query can reserve after release");
            assertFalse(later.effectStarted());
            assertEquals(3, harness.abilityGame.freshFactsReads.get());
            assertEquals(1, harness.abilityLedger.snapshotForTests().liveHolds());
            assertEquals(0, harness.abilityLedger.snapshotForTests().chargedNodes());
            assertEquals(1, harness.conversation.releaseRetainedAbilityHoldsForTesting());
            assertEquals(0, harness.abilityLedger.snapshotForTests().liveHolds());
            assertEquals(0, harness.abilityLedger.snapshotForTests().chargedNodes());
            assertEquals(0, harness.socket.sendCount("ability.result"));
            assertFalse(harness.abilityGame.livePacketEvidenceObserved,
                    "synthetic ingress/facts remain isolated evidence, not live packet authority");
        }
    }

    @Test
    void normalPrivateIssuerPerformsAndImmediatelyReleasesTheProductionAccountingHold() {
        try (Harness harness = new Harness(30, false, abilityPolicy(), AbilityOwnerTestMode.NORMAL_ISOLATED)) {
            harness.admit("Use the normal isolated owner path for production hold classification");
            harness.tickAt(START_NANOS);
            GameLoopMailbox.ActiveRequest active = harness.mailbox.activeRequest();
            assertNotNull(active);

            byte[] intent = observeIntent(PLAYER_ID).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var first = harness.conversation.evaluateAbilityIntent(active.requestId(), intent, QUERY_A);
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                    first.reason());
            assertFalse(first.effectStarted());
            AbilityLimitLedger.Snapshot afterRelease = harness.abilityLedger.snapshotForTests();
            AbilityLimitLedger.HoldCounters holdCounters = harness.abilityLedger.holdCountersForTests();
            assertEquals(1, holdCounters.successfulReservations(),
                    "the private issuer reached the same real atomic ledger reserve path");
            assertEquals(1, holdCounters.preStartReleases(),
                    "the accounting-only hold was released immediately before any read/start seam");
            assertEquals(0, afterRelease.liveHolds());
            assertEquals(0, afterRelease.chargedNodes(), "transient hold admission does not charge Observe");
            assertEquals(0, afterRelease.inFlight());
            assertEquals(1, harness.abilityGame.freshFactsReads.get());
            assertEquals(0, harness.socket.sendCount("ability.result"));
            assertFalse(harness.abilityGame.livePacketEvidenceObserved,
                    "synthetic isolated facts are not live Minecraft authentication proof");

            var duplicate = harness.conversation.evaluateAbilityIntent(active.requestId(), intent, QUERY_A);
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                    duplicate.reason());
            assertEquals(1, harness.abilityLedger.holdCountersForTests().successfulReservations(),
                    "released identity lookup does not perform a second hold");
            assertEquals(1, harness.abilityGame.freshFactsReads.get(),
                    "released identity lookup performs no query read or new facts capture");

            var denied = harness.conversation.evaluateAbilityIntent(active.requestId(),
                    observeIntent(harness.secondPlayerId.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    QUERY_B);
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.PERMISSION_DENIED,
                    denied.reason());
            assertEquals(1, harness.abilityLedger.holdCountersForTests().successfulReservations(),
                    "pure permission denial installs no hold");
            assertEquals(1, harness.abilityGame.freshFactsReads.get(),
                    "permission denial happens before a fresh world-facts read");
        }
    }

    @Test
    void normalPrivateIssuerRejectsRevocationAndMissingFactsBeforeAnyReservation() {
        try (Harness revoked = new Harness(30, false, abilityPolicy(), AbilityOwnerTestMode.NORMAL_ISOLATED)) {
            revoked.admit("Revoke the synthetic owner before the gate check");
            revoked.tickAt(START_NANOS);
            GameLoopMailbox.ActiveRequest active = revoked.mailbox.activeRequest();
            assertNotNull(active);
            revoked.abilityGame.revokeEpoch();
            var denied = revoked.conversation.evaluateAbilityIntent(active.requestId(),
                    observeIntent(PLAYER_ID).getBytes(java.nio.charset.StandardCharsets.UTF_8), QUERY_A);
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.STALE_CONTEXT, denied.reason());
            assertEquals(0, revoked.abilityLedger.holdCountersForTests().successfulReservations());
            assertEquals(0, revoked.abilityLedger.snapshotForTests().liveHolds());
            assertEquals(0, revoked.abilityLedger.snapshotForTests().chargedNodes());
        }

        try (Harness missing = new Harness(30, false, abilityPolicy(), AbilityOwnerTestMode.NORMAL_ISOLATED)) {
            missing.admit("Omit fresh owner facts");
            missing.tickAt(START_NANOS);
            GameLoopMailbox.ActiveRequest active = missing.mailbox.activeRequest();
            assertNotNull(active);
            missing.abilityGame.omitFreshFacts = true;
            var denied = missing.conversation.evaluateAbilityIntent(active.requestId(),
                    observeIntent(PLAYER_ID).getBytes(java.nio.charset.StandardCharsets.UTF_8), QUERY_A);
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                    denied.reason());
            assertEquals(1, missing.abilityGame.freshFactsReads.get());
            assertEquals(0, missing.abilityLedger.holdCountersForTests().successfulReservations());
            assertEquals(0, missing.abilityLedger.snapshotForTests().liveHolds());
            assertEquals(0, missing.abilityLedger.snapshotForTests().chargedNodes());
        }

        try (Harness deniedPolicy = new Harness(30, false, abilityPolicy(), AbilityOwnerTestMode.NORMAL_ISOLATED)) {
            deniedPolicy.admit("A target outside the complete grant is denied before owner facts");
            deniedPolicy.tickAt(START_NANOS);
            GameLoopMailbox.ActiveRequest active = deniedPolicy.mailbox.activeRequest();
            assertNotNull(active);
            var denied = deniedPolicy.conversation.evaluateAbilityIntent(active.requestId(),
                    observeIntent(deniedPolicy.secondPlayerId.toString())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8), QUERY_A);
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.PERMISSION_DENIED,
                    denied.reason());
            assertEquals(0, deniedPolicy.abilityGame.freshFactsReads.get());
            assertEquals(0, deniedPolicy.abilityLedger.holdCountersForTests().successfulReservations());
            assertEquals(0, deniedPolicy.abilityLedger.snapshotForTests().liveHolds());
            assertEquals(0, deniedPolicy.abilityLedger.snapshotForTests().chargedNodes());
        }
    }

    @Test
    void normalPrivateIssuerDoesNotHoldLightningWithUnknownProtectionOrMissingDamageHealth() {
        for (String missingFact : List.of("sky", "spawn", "claims")) {
            try (Harness harness = new Harness(30, false, lightningPolicy(), AbilityOwnerTestMode.NORMAL_ISOLATED)) {
                harness.admit("Keep unknown lightning protection fail-closed: " + missingFact);
                harness.tickAt(START_NANOS);
                GameLoopMailbox.ActiveRequest active = harness.mailbox.activeRequest();
                assertNotNull(active);
                harness.abilityGame.omitLightningFact = missingFact;
                var denied = harness.conversation.evaluateAbilityIntent(active.requestId(),
                        lightningIntent(PLAYER_ID, true).getBytes(java.nio.charset.StandardCharsets.UTF_8), QUERY_A);
                assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                        denied.reason(), missingFact);
                assertEquals(0, harness.abilityLedger.holdCountersForTests().successfulReservations(), missingFact);
                assertEquals(0, harness.abilityLedger.snapshotForTests().liveHolds(), missingFact);
                assertEquals(0, harness.abilityLedger.snapshotForTests().chargedNodes(), missingFact);
            }
        }

        for (String deniedFact : List.of("sky-denied", "spawn-denied", "claims-denied")) {
            try (Harness harness = new Harness(30, false, lightningPolicy(), AbilityOwnerTestMode.NORMAL_ISOLATED)) {
                harness.admit("Known lightning protection veto stays a policy denial: " + deniedFact);
                harness.tickAt(START_NANOS);
                GameLoopMailbox.ActiveRequest active = harness.mailbox.activeRequest();
                assertNotNull(active);
                harness.abilityGame.omitLightningFact = deniedFact;
                var denied = harness.conversation.evaluateAbilityIntent(active.requestId(),
                        lightningIntent(PLAYER_ID, true).getBytes(java.nio.charset.StandardCharsets.UTF_8), QUERY_A);
                assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.PERMISSION_DENIED,
                        denied.reason(), deniedFact);
                assertEquals(0, harness.abilityLedger.holdCountersForTests().successfulReservations(), deniedFact);
                assertEquals(0, harness.abilityLedger.snapshotForTests().liveHolds(), deniedFact);
                assertEquals(0, harness.abilityLedger.snapshotForTests().chargedNodes(), deniedFact);
            }
        }

        try (Harness harness = new Harness(30, false, lightningDamagePolicy(), AbilityOwnerTestMode.NORMAL_ISOLATED)) {
            harness.admit("Damage health is not qualified");
            harness.tickAt(START_NANOS);
            GameLoopMailbox.ActiveRequest active = harness.mailbox.activeRequest();
            assertNotNull(active);
            var denied = harness.conversation.evaluateAbilityIntent(active.requestId(),
                    lightningIntent(PLAYER_ID, false).getBytes(java.nio.charset.StandardCharsets.UTF_8), QUERY_A);
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                    denied.reason());
            assertEquals(0, harness.abilityLedger.holdCountersForTests().successfulReservations());
            assertEquals(0, harness.abilityLedger.snapshotForTests().liveHolds());
            assertEquals(0, harness.abilityLedger.snapshotForTests().chargedNodes());
        }
    }

    @Test
    void syntheticIngressAndBatchOwnedRequestCannotMintOrReuseAbilityAuthority() {
        try (Harness harness = new Harness(30, false)) {
            harness.admit("Keep the ordinary request path isolated");
            harness.tickAt(START_NANOS);
            GameLoopMailbox.ActiveRequest active = harness.mailbox.activeRequest();
            assertNotNull(active);
            String intent = "{\"abilityId\":\"worldspeaker.core.observe_player\",\"contractVersion\":1," +
                    "\"arguments\":{\"targetPlayerId\":\"" + PLAYER_ID + "\"}}";
            var decision = harness.conversation.evaluateAbilityIntent(active.requestId(),
                    intent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.STALE_CONTEXT, decision.reason());
            assertFalse(decision.effectStarted());
            assertEquals(0, harness.socket.sendCount("ability.result"));
        }
    }

    private static AbilityPolicy abilityPolicy() {
        return new AbilityPolicy(7, List.of(new AbilityPolicy.Grant("observe-self", "character-test",
                AbilityPolicy.OBSERVE, 1, List.of(PLAYER_ID), List.of(), List.of("minecraft:overworld"),
                64, false, false, null)), List.of(), "REQUIRED");
    }

    private static AbilityPolicy lightningPolicy() {
        return new AbilityPolicy(7, List.of(new AbilityPolicy.Grant("lightning-self", "character-test",
                AbilityPolicy.LIGHTNING, 1, List.of(PLAYER_ID), List.of(), List.of("minecraft:overworld"),
                64, true, false, null)), List.of(), "REQUIRED");
    }

    private static AbilityPolicy lightningDamagePolicy() {
        return new AbilityPolicy(7, List.of(new AbilityPolicy.Grant("lightning-damage-self", "character-test",
                AbilityPolicy.LIGHTNING, 1, List.of(PLAYER_ID), List.of(), List.of("minecraft:overworld"),
                64, true, true, 2.0)), List.of(), "REQUIRED");
    }

    private static AbilityPolicy abilityPolicyWithOtherTarget() {
        return new AbilityPolicy(7, List.of(new AbilityPolicy.Grant("observe-self-and-listed-target",
                "character-test", AbilityPolicy.OBSERVE, 1, List.of(PLAYER_ID),
                List.of("00000000-0000-4000-8000-000000000002"), List.of("minecraft:overworld"),
                64, false, false, null)), List.of(), "REQUIRED");
    }

    private static String observeIntent(String targetId) {
        return "{\"abilityId\":\"worldspeaker.core.observe_player\",\"contractVersion\":1," +
                "\"arguments\":{\"targetPlayerId\":\"" + targetId + "\"}}";
    }

    private static String lightningIntent(String targetId, boolean visualOnly) {
        return "{\"abilityId\":\"worldspeaker.core.strike_lightning\",\"contractVersion\":1," +
                "\"arguments\":{\"targetPlayerId\":\"" + targetId + "\",\"visualOnly\":" +
                visualOnly + "}}";
    }

    private static void assertRetiredAuthorityIsStale(String boundary,
                                                       java.util.function.Consumer<Harness> change) {
        assertRetiredAuthorityIsStale(boundary, change, true);
    }

    private static void assertRetiredAuthorityIsStale(String boundary,
                                                       java.util.function.Consumer<Harness> change,
                                                       boolean tickAfterChange) {
        try (Harness harness = new Harness(30, false, abilityPolicy())) {
            harness.admit("A request that crosses " + boundary);
            harness.tickAt(START_NANOS);
            GameLoopMailbox.ActiveRequest active = harness.mailbox.activeRequest();
            String requestId = active == null ? null : active.requestId();
            assertNotNull(requestId, boundary + " fixture must retain the admitted request ID");
            var first = harness.conversation.evaluateAbilityIntent(requestId,
                    observeIntent(PLAYER_ID).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.EXECUTION_UNAVAILABLE,
                    first.reason(), boundary + " begins with a genuinely matched owner-issued context");
            assertEquals(1, harness.abilityGame.freshFactsReads.get());
            AbilityLimitLedger.Snapshot held = harness.abilityLedger.snapshotForTests();
            assertEquals(1, held.liveHolds(), boundary + " starts with a real isolated-owner hold");
            assertEquals(0, held.chargedNodes());

            change.accept(harness);
            if (tickAfterChange) harness.tickAt(harness.clock.get() + 1);
            AbilityLimitLedger.Snapshot afterRevocation = harness.abilityLedger.snapshotForTests();
            assertEquals(0, afterRevocation.inFlight(), boundary + " has no started task");
            assertEquals(0, afterRevocation.chargedNodes(), boundary + " has no prior charge");
            var staleStart = harness.conversation.startFirstRetainedAbilityHoldForTesting();
            assertFalse(staleStart.started(), boundary + " must not consume a revoked private token");
            assertEquals(AbilityLimitLedger.Reason.STALE_TOKEN, staleStart.reason(), boundary);
            AbilityLimitLedger.Snapshot afterStaleStart = harness.abilityLedger.snapshotForTests();
            assertEquals(0, afterStaleStart.liveHolds(), boundary + " releases an unstarted hold once non-runnable");
            assertEquals(0, afterStaleStart.inFlight());
            assertEquals(0, afterStaleStart.chargedNodes(), boundary + " creates no charge");
            assertEquals(0, harness.conversation.retainedAbilityHoldsForTesting());

            var stale = harness.conversation.evaluateAbilityIntent(requestId,
                    observeIntent(PLAYER_ID).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(org.worldspeaker.ability.AbilityExecutionGate.Reason.STALE_CONTEXT, stale.reason(), boundary);
            assertFalse(stale.effectStarted());
            assertEquals(1, harness.abilityGame.freshFactsReads.get(),
                    boundary + " rejects the retired/stale context before another facts read");
            assertEquals(afterStaleStart, harness.abilityLedger.snapshotForTests(),
                    boundary + " cannot replay the old private handle to mutate ledger state");
            var replayedStart = harness.conversation.startFirstRetainedAbilityHoldForTesting();
            assertFalse(replayedStart.started(), boundary + " cannot replay the old token");
            assertEquals(afterStaleStart, harness.abilityLedger.snapshotForTests(),
                    boundary + " stale-token replay is snapshot-identical");
            assertEquals(0, harness.socket.sendCount("ability.result"));
        }
    }

    @Test
    void realIngressTickCompanionMailboxAndOutputProduceOneCompleteAnswerAndV3Evidence() throws Exception {
        try (Harness harness = new Harness(30, false)) {
            InteractionIngress.Invocation invocation = harness.admit("Where did the lantern go?");
            harness.tickAt(START_NANOS);
            JsonObject request = harness.awaitFrame("dialogue.request");

            harness.sendAnswer(request, "The lantern is beside the old bridge.");
            harness.tickAt(START_NANOS + 100);

            assertEquals(List.of("[Worldspeaker] Archivist: The lantern is beside the old bridge."),
                    harness.game.publishedLines());
            assertTrue(invocation.firstReplySlot().hasFirstPublication());
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.PROGRESS_DISPATCHES.ordinal()]);

            Path evidence = temporaryDirectory.resolve("resp02-numeric.jsonl");
            assertTrue(harness.metrics.offerNumericSnapshot(evidence));
            assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));
            assertEquals(1, harness.metrics.persistedBatches());
            String snapshot = Files.readString(evidence);
            assertTrue(snapshot.contains("\"executionBudgetSeconds\":30"));
            assertTrue(snapshot.contains("\"absoluteExecutionDeadlineAtNanos\":\"48000000000\""));
            assertTrue(snapshot.contains("\"activeDeadlineAtNanos\":\"33000000000\""));
            assertTrue(snapshot.contains("\"terminalOutcome\":\"ANSWER\""));
            assertTrue(snapshot.contains("\"terminalDeliveryResult\":\"SUBMITTED\""));
            assertTrue(snapshot.contains("\"terminalAtNanos\":\"3000000100\""));
            assertFalse(snapshot.contains("The lantern is beside the old bridge"), "numeric evidence must remain body-free");
        }
    }

    @Test
    void bracketedStyleSurvivesActualIngressAckProgressFinalFailureAndTerminalDisposition() throws Exception {
        String displayName = "A".repeat(NaturalAddressParser.MAX_CONFIGURED_NAME_LENGTH);
        String prefix = "[Worldspeaker] [" + displayName + "]: ";
        try (Harness harness = new Harness(30, false, CharacterChatStyle.BRACKETED, displayName)) {
            InteractionIngress.Invocation invocation = harness.admit("A bracketed style lifecycle");
            harness.tickAt(START_NANOS);
            JsonObject request = harness.awaitFrame("dialogue.request");

            long acknowledgementAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(750);
            harness.tickAt(acknowledgementAt);
            FakeSocket.SendCompletion acknowledgement = harness.awaitSend("dialogue.ack");
            JsonObject acknowledgementRecord = acknowledgement.frame().getAsJsonObject("acknowledgement");
            String exactAcknowledgement = prefix + "Hello Tester";
            assertEquals(exactAcknowledgement, harness.game.publishedLines().get(0));
            assertEquals(exactAcknowledgement, acknowledgementRecord.get("text").getAsString());
            assertTrue(acknowledgementRecord.get("messageId").getAsString().matches(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
            assertEquals(PLAYER_ID, acknowledgementRecord.getAsJsonObject("replyAudience").get("playerId").getAsString());
            assertEquals(Long.toString(acknowledgementAt),
                    acknowledgementRecord.get("dispatchedAtMonotonicNanos").getAsString());
            harness.awaitAcknowledgementOffer();
            harness.tickAt(acknowledgementAt);

            long progressAt = START_NANOS + TimeUnit.SECONDS.toNanos(10);
            harness.tickAt(progressAt);
            FakeSocket.SendCompletion progressSend = harness.awaitSend("dialogue.progress");
            JsonObject progressRecord = progressSend.frame().getAsJsonObject("progress");
            String exactProgress = prefix + "Your request has been forwarded and I am still waiting for a complete answer.";
            assertTrue(harness.game.publishedLines().contains(exactProgress));
            assertEquals(exactProgress, progressRecord.get("text").getAsString());
            assertEquals(PLAYER_ID, progressRecord.getAsJsonObject("replyAudience").get("playerId").getAsString());
            assertEquals("awaiting_answer", progressRecord.get("phase").getAsString());
            assertEquals(Long.toString(progressAt), progressRecord.get("dispatchedAtMonotonicNanos").getAsString());
            assertTrue(progressRecord.has("messageId"));
            harness.awaitProgressOffer();
            harness.tickAt(progressAt);

            String maxBody = "x".repeat(1978) + "🙂";
            assertEquals(VanillaDialogue.MAX_COMPLETED_ANSWER_LENGTH, maxBody.length());
            harness.sendAnswer(request, maxBody);
            harness.tickAt(progressAt + 1);
            assertTrue(harness.game.publishedLines().contains(prefix + maxBody),
                    "the complete 1,980-unit body is submitted with the same bracketed snapshot");
            assertEquals(VanillaDialogue.MAX_RENDERED_LENGTH - 1,
                    (prefix + maxBody).length(), "the maximum safe body is not truncated to fill the outer cap");

            long failureStart = progressAt + 2;
            InteractionIngress.Invocation failed = harness.admit("A bracketed integration failure");
            harness.tickAt(failureStart);
            JsonObject failedRequest = harness.awaitFrame("dialogue.request", 2);
            harness.sendFailure(failedRequest);
            harness.tickAt(failureStart + 1);
            assertTrue(harness.game.publishedLines().contains(prefix + "I couldn't complete that request right now."));
            assertTrue(failed.firstReplySlot().hasFirstPublication());

            long oversizedStart = failureStart + 2;
            harness.admit("A rejected oversized answer");
            harness.tickAt(oversizedStart);
            JsonObject oversizedRequest = harness.awaitFrame("dialogue.request", 3);
            int priorLineCount = harness.game.publishedLines().size();
            harness.sendAnswer(oversizedRequest, "x".repeat(1981));
            harness.tickAt(oversizedStart + 1);
            assertEquals(priorLineCount, harness.game.publishedLines().size(),
                    "a 1,981-unit final is rejected, never truncated or published");

            String help = harness.invokeHelp(oversizedStart + 2);
            assertTrue(help.startsWith("[Worldspeaker] Worldspeaker: "),
                    "generic service guidance keeps its fixed service origin");
            assertTrue(help.contains(displayName + " — use /worldspeaker ask"),
                    "generic help continues to name the character without applying its chat style");
            assertFalse(help.contains("[" + displayName + "]"),
                    "character chat style does not change the generic help envelope");
        }

        try (Harness timeout = new Harness(1, false, CharacterChatStyle.BRACKETED, displayName)) {
            timeout.admit("A bracketed expiry disposition");
            timeout.tickAt(START_NANOS);
            timeout.awaitFrame("dialogue.request");
            timeout.tickAt(START_NANOS + TimeUnit.SECONDS.toNanos(1));
            assertEquals(List.of(prefix + "I couldn't complete that request in time."), timeout.game.publishedLines());
        }
    }

    @Test
    void exactExecutionExpiryWinsOverAReadyAnswerAndCancellationIsOnlyLocalRetirement() {
        try (Harness harness = new Harness(1, false)) {
            harness.admit("A bounded request");
            harness.tickAt(START_NANOS);
            JsonObject request = harness.awaitFrame("dialogue.request");
            long exactExpiry = START_NANOS + TimeUnit.SECONDS.toNanos(1);

            harness.clock.set(exactExpiry);
            harness.sendAnswer(request, "A late final must not win.");
            harness.tickAt(exactExpiry);

            assertEquals(List.of("[Worldspeaker] Archivist: I couldn't complete that request in time."),
                    harness.game.publishedLines());
            assertTrue(harness.awaitFrame("dialogue.cancel") != null,
                    "expiry submits a local cancel frame without claiming the provider stopped");
            assertFalse(harness.game.publishedLines().stream().anyMatch(line -> line.contains("late final")));
        }
    }

    @Test
    void admittedAndCoalescedRowsKeepOriginalRequestLinksAndExpiredWorkCannotSupportADuplicateClaim()
            throws Exception {
        try (Harness harness = new Harness(30, false)) {
            InteractionIngress.Invocation first = harness.admit("An exact request link");
            harness.tickAt(START_NANOS);
            harness.awaitFrame("dialogue.request");

            assertEquals(0, harness.invokeAsk(harness.player, "An exact request link", START_NANOS));
            InteractionIngress.Invocation coalesced = harness.invocations.get(harness.invocations.size() - 1);

            long queuedAt = START_NANOS + 100_000_000L;
            harness.clock.set(queuedAt);
            InteractionIngress.Invocation expiredOriginal = harness.admit("A queued request whose handler is delayed");
            harness.tickAt(queuedAt);

            long pendingDeadline = queuedAt + TimeUnit.SECONDS.toNanos(15);
            harness.clock.set(pendingDeadline);
            long repeatedCaptureAt = queuedAt + TimeUnit.SECONDS.toNanos(2);
            assertEquals(1, harness.invokeAsk(harness.player,
                    "A queued request whose handler is delayed", repeatedCaptureAt),
                    "the incoming receipt is still fresh, but an expired live request is not a coalescing target");
            InteractionIngress.Invocation replacement = harness.invocations.get(harness.invocations.size() - 1);
            assertEquals(3, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_COALESCED.ordinal()]);

            harness.tickAt(pendingDeadline);
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.PENDING_EXPIRED.ordinal()]);

            Path evidence = temporaryDirectory.resolve("request-link-lifecycle.jsonl");
            assertTrue(harness.metrics.offerNumericSnapshot(evidence));
            assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));

            JsonObject firstRow = evidenceRow(evidence, first.invocationId().toString());
            JsonObject coalescedRow = evidenceRow(evidence, coalesced.invocationId().toString());
            JsonObject expiredRow = evidenceRow(evidence, expiredOriginal.invocationId().toString());
            JsonObject replacementRow = evidenceRow(evidence, replacement.invocationId().toString());
            assertEquals("ADMITTED", firstRow.get("outcome").getAsString());
            assertEquals(firstRow.get("requestId").getAsString(), coalescedRow.get("requestId").getAsString());
            assertEquals("COALESCED", coalescedRow.get("outcome").getAsString());
            assertTrue(coalescedRow.get("queuedAtNanos").isJsonNull(),
                    "a coalesced invocation is linked without claiming a second queue admission");
            assertEquals("ADMITTED", expiredRow.get("outcome").getAsString());
            assertEquals("EXPIRED", expiredRow.get("terminalOutcome").getAsString());
            assertEquals("ADMITTED", replacementRow.get("outcome").getAsString());
            assertFalse(expiredRow.get("requestId").getAsString().equals(replacementRow.get("requestId").getAsString()),
                    "the fresh replacement has its own request rather than claiming the expired request");
            assertEquals(replacementRow.get("requestLinkAtNanos").getAsString(),
                    replacementRow.get("queuedAtNanos").getAsString());
        }
    }

    @Test
    void boundedDistinctWorkFloodPublishesOneTargetedRateLimitNoticeAndDoesNotStartExtraRequests() {
        try (Harness harness = new Harness(30, false, new InvocationRateLimiter())) {
            harness.admit("The first bounded request");
            harness.tickAt(START_NANOS);
            harness.awaitFrame("dialogue.request");

            long secondAt = START_NANOS + 100_000_000L;
            harness.clock.set(secondAt);
            assertEquals(1, harness.invokeAsk(harness.player, "The second bounded request", secondAt));
            harness.tickAt(secondAt);

            long floodAt = secondAt + 100_000_000L;
            for (int index = 0; index < 24; index++) {
                assertEquals(0, harness.invokeAsk(harness.player, "Distinct flood input " + index, floodAt));
            }
            assertEquals(2, harness.metrics.countersSnapshot()[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()]);
            assertTrue(harness.metrics.countersSnapshot()[TickMetrics.Counter.NEW_WORK_RATE_LIMITED.ordinal()] > 0);
            assertTrue(harness.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATION_RATE_LIMITED.ordinal()] > 0);
            assertTrue(harness.scheduler.pendingDispositionCount() <= FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS);

            harness.tickAt(floodAt + TimeUnit.SECONDS.toNanos(1));
            List<FakeGamePort.RenderedAttempt> limitNotices = harness.game.attempts().stream()
                    .filter(attempt -> attempt.renderedLine().contains("Your requests are being throttled.") &&
                            attempt.renderedLine().contains("No new work was accepted."))
                    .toList();
            assertEquals(1, limitNotices.size(), "one sender-scoped notice is sent while repeated notices are suppressed; attempts=" +
                    harness.game.attempts());
            assertTrue(limitNotices.get(0).target() == harness.player,
                    "the rate-limit notice stays on the invoking player's original connection");
            assertFalse(limitNotices.get(0).renderedLine().contains("Archivist:"),
                    "a refusal cannot be formatted as a character acknowledgement");
            assertEquals(1, harness.socket.sendCount("dialogue.request"),
                    "bounded and rejected distinct work never becomes extra companion requests");
        }
    }

    @Test
    void sevenCompatibleRepeatsShareOneFutureNoticeWithoutClaimingTheDistinctSendersFirstReply() throws Exception {
        try (Harness harness = new Harness(30, false, new InvocationRateLimiter())) {
            InteractionIngress.Invocation first = harness.admit("The same live request");
            harness.tickAt(START_NANOS);
            JsonObject firstRequest = harness.awaitFrame("dialogue.request");

            List<InteractionIngress.Invocation> repeats = new java.util.ArrayList<>(7);
            for (int offsetMillis = 100; offsetMillis <= 106; offsetMillis++) {
                long capturedAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(offsetMillis);
                harness.clock.set(capturedAt);
                assertEquals(0, harness.invokeAsk(harness.player, "The same live request", capturedAt));
                repeats.add(harness.invocations.get(harness.invocations.size() - 1));
            }
            assertEquals(7, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_COALESCED.ordinal()]);
            assertEquals(1, harness.scheduler.pendingDispositionCount(),
                    "all compatible repeats join the one pending sender batch");
            assertEquals(7, harness.rateLimiter.pendingNoticeMembers());

            long batchSendAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(200);
            harness.tickAt(batchSendAt);
            assertEquals(0, harness.scheduler.pendingDispositionCount());
            assertEquals(1, harness.game.attempts().stream()
                    .filter(attempt -> attempt.renderedLine().contains("I already have that request."))
                    .count(), "the shared batch makes one physical send, not seven ACK-shaped sends");
            assertTrue(harness.game.attempts().stream()
                    .filter(attempt -> attempt.renderedLine().contains("I already have that request."))
                    .noneMatch(attempt -> attempt.renderedLine().contains("Archivist:")),
                    "a coalesced refusal does not use the character acknowledgement persona");

            long firstReplyAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(750);
            harness.tickAt(firstReplyAt);
            harness.awaitSend("dialogue.ack");
            harness.awaitAcknowledgementOffer();
            harness.tickAt(firstReplyAt);
            harness.sendAnswer(firstRequest, "The original request still gets its answer.");
            harness.tickAt(firstReplyAt + 1);

            long distinctAt = firstReplyAt + 2;
            InteractionIngress.Invocation distinct = harness.admit(harness.secondPlayer, "A distinct B request");
            harness.tickAt(distinctAt);
            JsonObject distinctRequest = harness.awaitFrame("dialogue.request", 2);
            long distinctReplyAt = distinctAt + TimeUnit.MILLISECONDS.toNanos(750);
            harness.tickAt(distinctReplyAt);
            harness.awaitSend("dialogue.ack", 2);
            harness.awaitAcknowledgementOffer();
            harness.tickAt(distinctReplyAt);
            harness.sendAnswer(distinctRequest, "B still receives its useful answer.");
            harness.tickAt(distinctReplyAt + 1);

            Path evidence = temporaryDirectory.resolve("compatible-notice-batch.jsonl");
            assertTrue(harness.metrics.offerNumericSnapshot(evidence));
            assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));
            String publicationId = null;
            for (InteractionIngress.Invocation repeat : repeats) {
                JsonObject row = evidenceRow(evidence, repeat.invocationId().toString());
                assertEquals("COALESCED", row.get("outcome").getAsString());
                assertEquals("SUBMITTED", row.get("noticeStatus").getAsString());
                assertEquals("SUBMITTED", row.get("noticeBatchResult").getAsString());
                assertEquals(row.get("noticeBatchPublicationId").getAsString(),
                        row.get("publicationId").getAsString());
                if (publicationId == null) publicationId = row.get("noticeBatchPublicationId").getAsString();
                assertEquals(publicationId, row.get("noticeBatchPublicationId").getAsString());
                long capturedAt = Long.parseLong(row.get("t0Nanos").getAsString());
                long dispatchedAt = Long.parseLong(row.get("noticeBatchAttemptedAtNanos").getAsString());
                long deadline = Long.parseLong(row.get("deadlineAtNanos").getAsString());
                assertTrue(dispatchedAt > 0);
                assertEquals(dispatchedAt, Long.parseLong(row.get("firstDispatchAtNanos").getAsString()));
                assertEquals(dispatchedAt - capturedAt,
                        Long.parseLong(row.get("firstDispatchNanos").getAsString()),
                        "each row keeps its own receipt t0 rather than a standing batch timestamp");
                assertTrue(dispatchedAt <= deadline, "one shared send covers every retained row's deadline");
            }
            assertTrue(harness.game.publishedLines().stream()
                    .anyMatch(line -> line.contains("The original request still gets its answer.")));
            assertTrue(harness.game.publishedLines().stream()
                    .anyMatch(line -> line.contains("B still receives its useful answer.")));
            assertEquals(1, harness.game.publishedLines().stream().filter(line -> line.endsWith("Hello Tester")).count());
            assertEquals(1, harness.game.publishedLines().stream().filter(line -> line.endsWith("Hello TesterTwo")).count());
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.FIRST_REPLY_DEADLINE_MISSES.ordinal()]);
            assertEquals("ADMITTED", evidenceRow(evidence, distinct.invocationId().toString()).get("outcome").getAsString());
            assertEquals("ADMITTED", evidenceRow(evidence, first.invocationId().toString()).get("outcome").getAsString());
        }
    }

    @Test
    void exactRepeatSuppressionUsesTheOriginalCaptureDeadlineAfterAFastTerminal() throws Exception {
        for (long offset : new long[] { TimeUnit.SECONDS.toNanos(6) - 1,
                TimeUnit.SECONDS.toNanos(6), TimeUnit.SECONDS.toNanos(6) + 1 }) {
            try (Harness harness = new Harness(30, false, new InvocationRateLimiter())) {
                InteractionIngress.Invocation original = harness.admit("What is 7 * 8?");
                harness.tickAt(START_NANOS);
                JsonObject firstRequest = harness.awaitFrame("dialogue.request", 1);
                harness.sendAnswer(firstRequest, "56");
                harness.tickAt(START_NANOS + TimeUnit.MILLISECONDS.toNanos(100));
                assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()],
                        "the original request is already terminal before its repeat is tested");

                long repeatAt = START_NANOS + offset;
                harness.clock.set(repeatAt);
                assertEquals(offset > TimeUnit.SECONDS.toNanos(6) ? 1 : 0,
                        harness.invokeAsk(harness.player, "What is 7 * 8?", repeatAt));
                InteractionIngress.Invocation repeat = harness.invocations.get(harness.invocations.size() - 1);
                harness.tickAt(repeatAt);
                if (offset > TimeUnit.SECONDS.toNanos(6)) {
                    harness.awaitFrame("dialogue.request", 2);
                    assertEquals(2, harness.socket.sendCount("dialogue.request"));
                } else {
                    assertEquals(1, harness.socket.sendCount("dialogue.request"),
                            "the fixed inclusive guard does not replay or re-run the completed answer");
                }

                Path evidence = temporaryDirectory.resolve("repeat-guard-" + offset + ".jsonl");
                assertTrue(harness.metrics.offerNumericSnapshot(evidence));
                assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));
                JsonObject originalRow = evidenceRow(evidence, original.invocationId().toString());
                JsonObject repeatRow = evidenceRow(evidence, repeat.invocationId().toString());
                assertEquals("ANSWER", originalRow.get("terminalOutcome").getAsString());
                assertEquals(offset > TimeUnit.SECONDS.toNanos(6) ? "ADMITTED" : "RATE_LIMITED",
                        repeatRow.get("outcome").getAsString());
                if (offset <= TimeUnit.SECONDS.toNanos(6)) {
                    assertEquals("REPEAT_GUARD", repeatRow.get("rateLimitStatus").getAsString());
                    assertEquals(originalRow.get("requestId").getAsString(), repeatRow.get("requestId").getAsString(),
                            "a guard refusal remains linked to its original logical request");
                    assertTrue(repeatRow.get("queuedAtNanos").isJsonNull(),
                            "a refused repeat is linked but is not marked as a second queue admission");
                } else {
                    assertEquals("ALLOWED", repeatRow.get("rateLimitStatus").getAsString());
                    assertNotEquals(originalRow.get("requestId").getAsString(), repeatRow.get("requestId").getAsString());
                }
            }
        }
    }

    @Test
    void repeatedAStaysOneLiveTurnWhileQueuedBGetsAnOnTimeAckAndUsefulCompletion() throws Exception {
        try (Harness harness = new Harness(30, false, new InvocationRateLimiter())) {
            InteractionIngress.Invocation originalA = harness.admit("What is 7 * 8?");
            harness.tickAt(START_NANOS);
            JsonObject requestA = harness.awaitFrame("dialogue.request", 1);

            long firstRepeatAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(100);
            harness.clock.set(firstRepeatAt);
            assertEquals(0, harness.invokeAsk(harness.player, "What is 7 * 8?", firstRepeatAt));

            long bCapturedAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(250);
            harness.clock.set(bCapturedAt);
            InteractionIngress.Invocation b = harness.admit(harness.secondPlayer, "What is 11 * 12?");
            harness.tickAt(bCapturedAt);
            long bAckAt = bCapturedAt;
            assertTrue(bAckAt - bCapturedAt <= TimeUnit.SECONDS.toNanos(1),
                    "B's local acknowledgement is submitted within its one-second first-reply bound");
            assertTrue(b.firstReplySlot().hasFirstPublication(),
                    "B's local acknowledgement is submitted while A remains active");
            assertEquals(0, harness.socket.sendCount("dialogue.ack"),
                    "B's queued acknowledgement is retained for its request, not sent as a separate ACK frame");
            assertTrue(harness.game.attempts().stream().anyMatch(attempt -> attempt.target() == harness.secondPlayer &&
                    attempt.result() == FirstReplyScheduler.PublicationResult.SUBMITTED &&
                    attempt.renderedLine().contains("Hello TesterTwo")),
                    "B's timely local acknowledgement is submitted to B's original connection");

            long aAckAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(750);
            harness.tickAt(aAckAt);
            FakeSocket.SendCompletion firstAck = harness.awaitSend("dialogue.ack", 1);
            assertEquals(requestA.get("requestId").getAsString(),
                    firstAck.frame().get("requestId").getAsString(), "A's ACK context is first");
            harness.awaitAcknowledgementOffers(1);
            harness.tickAt(aAckAt);

            for (long offsetMillis : new long[] { 1_000, 2_000, 3_000, 4_000, 5_000, 6_000 }) {
                long at = START_NANOS + TimeUnit.MILLISECONDS.toNanos(offsetMillis);
                harness.clock.set(at);
                assertEquals(0, harness.invokeAsk(harness.player, "What is 7 * 8?", at));
                if (offsetMillis == 1_000) harness.tickAt(at);
            }

            assertEquals(1, harness.socket.sendCount("dialogue.ack"),
                    "B's queued acknowledgement is retained for its request, not sent as a separate ACK frame");

            long aAnswerAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(6_100);
            harness.clock.set(aAnswerAt);
            harness.sendAnswer(requestA, "A's held answer is complete.");
            harness.tickAt(aAnswerAt);
            JsonObject requestB = harness.awaitFrame("dialogue.request", 2);
            assertEquals("What is 11 * 12?", requestB.getAsJsonObject("event").get("text").getAsString());
            assertEquals(2, harness.socket.sendCount("dialogue.request"),
                    "the seven live repeats never produce additional model requests");
            JsonObject acknowledgementB = requestB.getAsJsonObject("acknowledgement");
            assertTrue(acknowledgementB.get("text").getAsString().contains("Hello TesterTwo"),
                    "B's already-submitted acknowledgement is preserved in shared context before B");
            assertEquals(Long.toString(bAckAt), acknowledgementB.get("dispatchedAtMonotonicNanos").getAsString(),
                    "the forwarded context retains B's actual local first-send time");
            assertEquals(harness.secondPlayerId.toString(),
                    acknowledgementB.getAsJsonObject("replyAudience").get("playerId").getAsString(),
                    "the retained acknowledgement remains attributed to B");

            long bAnswerAt = START_NANOS + TimeUnit.SECONDS.toNanos(7);
            harness.clock.set(bAnswerAt);
            harness.sendAnswer(requestB, "132");
            harness.tickAt(bAnswerAt);
            assertTrue(harness.game.attempts().stream().anyMatch(attempt -> attempt.target() == harness.secondPlayer &&
                    attempt.renderedLine().endsWith("132")), "B receives its distinct correct answer");
            assertEquals(2, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);
            assertEquals(7, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_COALESCED.ordinal()]);

            Path evidence = temporaryDirectory.resolve("held-a-queued-b-fairness.jsonl");
            assertTrue(harness.metrics.offerNumericSnapshot(evidence));
            assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));
            JsonObject originalRow = evidenceRow(evidence, originalA.invocationId().toString());
            JsonObject bRow = evidenceRow(evidence, b.invocationId().toString());
            assertEquals("ADMITTED", bRow.get("outcome").getAsString());
            assertTrue(Long.parseLong(bRow.get("firstDispatchNanos").getAsString()) <= TimeUnit.SECONDS.toNanos(1),
                    "B's actual first connection submission is at most 1,000 ms from capture");
            assertTrue(Long.parseLong(bRow.get("pendingResidenceNanos").getAsString()) > 0,
                    "forwarding wait is measured separately from B's prompt acknowledgement");
            assertTrue(Long.parseLong(bRow.get("forwardedAtNanos").getAsString()) >= aAnswerAt);
            assertEquals("ANSWER", bRow.get("terminalOutcome").getAsString());
            assertEquals("SUBMITTED", bRow.get("terminalDeliveryResult").getAsString());
            assertEquals(0, bRow.get("globalPendingAtForward").getAsInt());
            assertEquals(1, bRow.get("queueDepthAtAdmission").getAsInt());
            assertEquals(1, bRow.get("playerQueueDepthAtAdmission").getAsInt());
            String manifest = Files.readAllLines(evidence).getFirst();
            JsonObject queueEvidence = JsonParser.parseString(manifest).getAsJsonObject().getAsJsonObject("queueEvidence");
            assertTrue(queueEvidence.get("pendingQueueScans").getAsLong() > 0);
            assertTrue(queueEvidence.get("maxPendingGlobal").getAsInt() >= 1);
        }
    }

    @Test
    void productionIngressClosesWhenThe4097thDispositionCannotReserveEvidence() throws Exception {
        try (Harness harness = new Harness(30, false)) {
            for (int index = 0; index <= TickMetrics.MAX_INTERACTION_RECORDS; index++) {
                assertEquals(index < TickMetrics.MAX_INTERACTION_RECORDS ? (index < 2 ? 1 : 0) : 0,
                        harness.invokeAsk(harness.player, "Evidence-cap input " + index, START_NANOS),
                        "unexpected ingress result at row " + index);
            }
            long[] counters = harness.metrics.countersSnapshot();
            assertEquals(4097, counters[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
            assertEquals(4096, harness.metrics.reservedInteractionCount());
            assertEquals(1, counters[TickMetrics.Counter.INTERACTION_EVIDENCE_DROPPED.ordinal()]);
            assertEquals(2, counters[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()]);
            assertTrue(harness.metrics.evidenceIncomplete());
            assertEquals(FirstReplyScheduler.MAX_DEFERRED_DISPOSITIONS,
                    harness.scheduler.pendingDispositionCount());
            assertEquals(0, harness.socket.sendCount("dialogue.request"), "no tick starts flood work in this test");

            Path evidence = temporaryDirectory.resolve("ingress-evidence-cap.jsonl");
            assertTrue(harness.metrics.offerNumericSnapshot(evidence));
            assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));
            String snapshot = Files.readString(evidence);
            assertEquals(4096, evidenceRows(evidence).size());
            assertTrue(snapshot.contains("\"sourceInvocationCount\":4097"));
            assertTrue(snapshot.contains("\"retainedInvocationCount\":4096"));
            assertTrue(snapshot.contains("\"omittedInvocationCount\":1"));
            assertTrue(snapshot.contains("\"captureStatus\":\"incomplete\""));
        }
    }

    @Test
    void actualWriterPathMismatchFailsClosedBeforeIngressCanCreateOrForwardNewWork() throws Exception {
        try (Harness harness = new Harness(30, false, null, true)) {
            Path firstPath = temporaryDirectory.resolve("ingress-first-path.jsonl");
            Path differentPath = temporaryDirectory.resolve("ingress-different-path.jsonl");
            assertTrue(harness.metrics.offerNumericSnapshot(firstPath));
            assertTrue(harness.metrics.offerNumericSnapshot(differentPath));
            assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));

            assertTrue(harness.metrics.evidenceIncomplete());
            assertFalse(harness.metrics.canReserveInteraction());
            assertEquals(1, harness.metrics.persistedBatches(), "the first writer run succeeded before the path mismatch");
            assertEquals(0, harness.invokeAsk(harness.player, "Must not be accepted", START_NANOS));
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.INVOCATIONS_CAPTURED.ordinal()]);
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_EVIDENCE_DROPPED.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()]);
            assertTrue(harness.invocations.isEmpty(), "the stale common-ingress reservation never reaches submit");

            harness.tickAt(START_NANOS + TimeUnit.SECONDS.toNanos(1));
            assertEquals(0, harness.socket.sendCount("dialogue.request"),
                    "a missing evidence reservation cannot become companion work on tick");
            assertTrue(harness.game.attempts().stream().anyMatch(attempt -> attempt.target() == harness.player &&
                            attempt.renderedLine().contains("could not safely accept that request")),
                    "a bounded refusal is sent only to the authenticated original sender");
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_UNAVAILABLE.ordinal()]);
        }
    }

    @Test
    void writerFailureAfterReservationBeforeSubmitIsRejectedByTheCommonIngressRecheck() throws Exception {
        try (Harness harness = new Harness(30, false, null, true)) {
            Path firstPath = temporaryDirectory.resolve("common-race-first-path.jsonl");
            Path differentPath = temporaryDirectory.resolve("common-race-different-path.jsonl");
            assertTrue(harness.metrics.offerNumericSnapshot(firstPath));
            harness.beforeBeginExplicit = () -> {
                assertTrue(harness.metrics.offerNumericSnapshot(differentPath));
                awaitEvidenceAuthorityClosed(harness.metrics);
            };

            assertEquals(0, harness.invokeAsk(harness.player, "Reserved then evidence-lost", START_NANOS));
            assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));
            assertTrue(harness.invocations.isEmpty(), "common ingress refuses before calling production submit");
            assertEquals(1, harness.metrics.reservedInteractionCount(), "the known row remains retained");
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()]);
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_UNAVAILABLE.ordinal()]);

            harness.tickAt(START_NANOS + TimeUnit.SECONDS.toNanos(1));
            assertEquals(0, harness.socket.sendCount("dialogue.request"));
            assertTrue(harness.game.attempts().stream().anyMatch(attempt -> attempt.target() == harness.player &&
                            attempt.renderedLine().contains("could not safely accept that request")),
                    "the original connection receives only the fixed evidence-loss refusal");
        }
    }

    @Test
    void writerFailureAfterReservationButBeforeSubmitCommitRejectsTheRowWithoutLosingItsEvidenceSlot() throws Exception {
        try (Harness harness = new Harness(30, false)) {
            Path firstPath = temporaryDirectory.resolve("race-first-path.jsonl");
            Path differentPath = temporaryDirectory.resolve("race-different-path.jsonl");
            assertTrue(harness.metrics.offerNumericSnapshot(firstPath));
            harness.beforeConversationSubmit = ignored -> {
                assertTrue(harness.metrics.offerNumericSnapshot(differentPath));
                awaitEvidenceAuthorityClosed(harness.metrics);
            };

            assertEquals(0, harness.invokeAsk(harness.player, "Reserved before writer failure", START_NANOS));
            assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));
            assertEquals(1, harness.metrics.persistedBatches());
            assertTrue(harness.metrics.evidenceIncomplete());
            assertEquals(1, harness.metrics.reservedInteractionCount(),
                    "the reserved invocation remains represented even though its admission commit lost the race");
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_ADMITTED.ordinal()]);
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.INTERACTION_UNAVAILABLE.ordinal()]);
            assertEquals(1, harness.invocations.size());
            assertFalse(harness.invocations.get(0).hasRequestLink(),
                    "the failed submit cannot leave an admitted request link behind");

            harness.tickAt(START_NANOS);
            assertEquals(0, harness.socket.sendCount("dialogue.request"),
                    "the direct SmokeConversation.submit boundary rejects a stale reservation version");
        }
    }

    @Test
    void writerFailureRejectsLaterIngressButPreviouslyCommittedRequestCanFinish() throws Exception {
        try (Harness harness = new Harness(30, false)) {
            String originalText = "Committed before writer failure";
            InteractionIngress.Invocation original = harness.admit(originalText);
            Path firstPath = temporaryDirectory.resolve("accepted-first-path.jsonl");
            Path differentPath = temporaryDirectory.resolve("accepted-different-path.jsonl");
            assertTrue(harness.metrics.offerNumericSnapshot(firstPath));
            assertTrue(harness.metrics.offerNumericSnapshot(differentPath));
            assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));
            assertFalse(harness.metrics.canReserveInteraction());

            assertEquals(SmokeConversation.SubmitOutcome.UNAVAILABLE,
                    harness.conversation.submit(harness.game.sourceFacts(harness.player), originalText, original),
                    "direct submit cannot reuse a reservation from an older authority version");
            assertEquals(0, harness.invokeAsk(harness.player, "Must not create another request", START_NANOS));

            harness.tickAt(START_NANOS);
            JsonObject request = harness.awaitFrame("dialogue.request");
            harness.sendAnswer(request, "The original accepted request still completes.");
            harness.tickAt(START_NANOS + 100);

            assertEquals(1, harness.socket.sendCount("dialogue.request"));
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.REQUESTS_ADMITTED.ordinal()]);
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);
            assertEquals(List.of("[Worldspeaker] Archivist: The original accepted request still completes."),
                    harness.game.publishedLines());
        }
    }

    @Test
    void activeExecutionDeadlineHasDistinctBeforeExactAndAfterNanosecondOutcomes() {
        try (Harness before = new Harness(1, false)) {
            before.admit("Answer just before the deadline");
            before.tickAt(START_NANOS);
            JsonObject request = before.awaitFrame("dialogue.request");
            long deadline = START_NANOS + TimeUnit.SECONDS.toNanos(1);
            before.clock.set(deadline - 1);
            before.sendAnswer(request, "One nanosecond remained.");
            before.tickAt(deadline - 1);

            assertEquals(List.of("[Worldspeaker] Archivist: One nanosecond remained."),
                    before.game.publishedLines());
            assertEquals(1, before.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);
        }

        assertExpiredAt(START_NANOS + TimeUnit.SECONDS.toNanos(1));
        assertExpiredAt(START_NANOS + TimeUnit.SECONDS.toNanos(1) + 1);
    }

    @Test
    void finalAnswerGateRechecksDeadlineAfterItsLastInspectionBeforeClaiming() throws Exception {
        long deadline = START_NANOS + TimeUnit.SECONDS.toNanos(1);
        assertAnswerAtFinalInspection(deadline - 1, true);
        assertAnswerAtFinalInspection(deadline, false);
        assertAnswerAtFinalInspection(deadline + 1, false);
    }

    @Test
    void lateActivationKeepsItsCaptureBasedAbsoluteCapAndDoesNotReplayExpiredAnswer() throws Exception {
        try (Harness harness = new Harness(30, false)) {
            harness.admit("Active request before a nearly expired queued turn");
            harness.tickAt(START_NANOS);
            JsonObject requestA = harness.awaitFrame("dialogue.request", 1);

            long capturedB = START_NANOS + 1;
            harness.clock.set(capturedB);
            InteractionIngress.Invocation invocationB = harness.admit(harness.secondPlayer,
                    "Queued request with the original absolute cap");
            harness.tickAt(capturedB);
            assertTrue(invocationB.firstReplySlot().hasFirstPublication(),
                    "the pending turn keeps its own locally submitted acknowledgement");

            long pendingDeadline = capturedB + TimeUnit.SECONDS.toNanos(15);
            long activationAt = pendingDeadline - 1;
            harness.sendAnswer(requestA, "A completed just before B's original pending cutoff.");
            harness.tickAt(activationAt);
            JsonObject requestB = harness.awaitFrame("dialogue.request", 2);
            String firstPublicationId = invocationB.firstReplySlot().publicationId();

            long absoluteDeadline = pendingDeadline + TimeUnit.SECONDS.toNanos(30);
            long activeDeadline = Math.min(activationAt + TimeUnit.SECONDS.toNanos(30), absoluteDeadline);
            assertEquals(absoluteDeadline - 1, activeDeadline,
                    "activation immediately before the capture-based pending cutoff remains capped by the original t0");

            long preInspectionAt = activeDeadline - 2;
            harness.sendAnswer(requestB, "B's answer inspected at expiry must not publish.");
            harness.game.onNthNextInspect(2, () -> harness.clock.set(activeDeadline));
            harness.tickAt(preInspectionAt);

            assertEquals(firstPublicationId, invocationB.firstReplySlot().publicationId(),
                    "a late terminal does not rewrite B's already-published acknowledgement claim");
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()],
                    "only A's in-budget answer is counted");
            assertEquals(0, countContaining(harness.game.publishedLines(), "B's answer inspected at expiry"));
            assertEquals(1, countContaining(harness.game.publishedLines(), "I couldn't complete that request in time."));

            int lineCountAfterExpiry = harness.game.publishedLines().size();
            harness.sendAnswer(requestB, "A late final after retirement must not replay.");
            harness.tickAt(activeDeadline + 1);
            assertEquals(lineCountAfterExpiry, harness.game.publishedLines().size());
            assertEquals(1, countContaining(harness.game.publishedLines(), "I couldn't complete that request in time."));
            assertEquals(1, harness.socket.sendCount("dialogue.cancel"));

            Path evidence = temporaryDirectory.resolve("capture-based-active-deadline.jsonl");
            assertTrue(harness.metrics.offerNumericSnapshot(evidence));
            assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));
            JsonObject row = evidenceRow(evidence, invocationB.invocationId().toString());
            assertEquals(Long.toString(pendingDeadline), row.get("pendingDeadlineAtNanos").getAsString());
            assertEquals(Long.toString(absoluteDeadline), row.get("absoluteExecutionDeadlineAtNanos").getAsString());
            assertEquals(Long.toString(activeDeadline), row.get("activeDeadlineAtNanos").getAsString());
            assertEquals("EXPIRED", row.get("terminalOutcome").getAsString());
            assertEquals("SUBMITTED", row.get("terminalDeliveryResult").getAsString());
            assertEquals(Long.toString(activeDeadline), row.get("terminalAtNanos").getAsString());
        }
    }

    @Test
    void progressAndCachedFirstReplyCannotCrossTheActiveDeadlineDuringFinalInspection() throws Exception {
        try (Harness progress = new Harness(30, false)) {
            progress.admit("Progress inspection crosses active expiry");
            progress.tickAt(START_NANOS);
            JsonObject request = progress.awaitFrame("dialogue.request");
            long firstReplyAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(750);
            progress.tickAt(firstReplyAt);
            FakeSocket.SendCompletion acknowledgement = progress.awaitSend("dialogue.ack");
            progress.awaitAcknowledgementOffer();
            progress.tickAt(firstReplyAt);
            assertTrue(acknowledgement.completion().isDone());

            long deadline = START_NANOS + TimeUnit.SECONDS.toNanos(30);
            progress.game.onNthNextInspect(3, () -> progress.clock.set(deadline));
            progress.tickAt(deadline - 2);
            assertFalse(progress.game.publishedLines().stream().anyMatch(line -> line.contains("still waiting")),
                    "the final progress inspection is followed by the protected active-deadline check");
            assertEquals(1, progress.metrics.countersSnapshot()[TickMetrics.Counter.PROGRESS_MISSED.ordinal()]);

            progress.tickAt(deadline);
            assertEquals(1, countContaining(progress.game.publishedLines(), "I couldn't complete that request in time."),
                    "expiry is carried to the next budgeted tick when the progress action crossed the cutoff");
            int linesAfterExpiry = progress.game.publishedLines().size();
            progress.sendAnswer(request, "A final after progress-triggered expiry must not publish.");
            progress.tickAt(deadline + 1);
            assertEquals(linesAfterExpiry, progress.game.publishedLines().size());
            assertEquals(0, progress.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);
        }

        long deadline = START_NANOS + TimeUnit.SECONDS.toNanos(1);
        try (Harness acknowledgement = new Harness(1, false)) {
            acknowledgement.admit("Acknowledgement inspection crosses active expiry");
            acknowledgement.tickAt(START_NANOS);
            acknowledgement.awaitFrame("dialogue.request");

            acknowledgement.game.onNthNextInspect(2, () -> acknowledgement.clock.set(deadline));
            acknowledgement.tickAt(deadline - 2);
            assertEquals(0, acknowledgement.socket.sendCount("dialogue.ack"),
                    "the fallback ACK is not claimed after its final in-gate inspection reaches expiry");
            assertEquals(1, countContaining(acknowledgement.game.publishedLines(),
                    "I couldn't complete that request in time."));
            assertEquals(1, acknowledgement.game.attempts().size(), "only the safe expiry terminal is attempted");
            assertEquals(0, acknowledgement.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);
            acknowledgement.tickAt(deadline + 1);
            assertEquals(1, countContaining(acknowledgement.game.publishedLines(),
                    "I couldn't complete that request in time."));
        }
    }

    @Test
    void terminalOfferedAfterDeadlineScanPreventsTheDueProgressPortSend() throws InterruptedException {
        try (Harness harness = new Harness(30, false)) {
            harness.admit("Answer at the progress boundary");
            harness.tickAt(START_NANOS);
            JsonObject request = harness.awaitFrame("dialogue.request");

            long firstReplyAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(750);
            harness.tickAt(firstReplyAt);
            FakeSocket.SendCompletion acknowledgement = harness.awaitSend("dialogue.ack");
            harness.awaitAcknowledgementOffer();
            harness.tickAt(firstReplyAt);

            harness.game.onNextInspect(() -> harness.sendAnswer(request, "The complete answer won the tick."));
            harness.tickAt(START_NANOS + TimeUnit.SECONDS.toNanos(10));

            assertEquals(List.of(
                    "[Worldspeaker] Archivist: Hello Tester",
                    "[Worldspeaker] Archivist: The complete answer won the tick."), harness.game.publishedLines());
            assertEquals(0, harness.socket.sendCount("dialogue.progress"),
                    "the answer entered after the deadline scan but before the real progress port claim");
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);
            assertTrue(acknowledgement.completion().isDone());
        }
    }

    @Test
    void gateOutageDuringTheActualGamePortSendRetainsOneBudgetedTerminalBeforeRetirement() throws Exception {
        try (Harness harness = new Harness(30, false)) {
            harness.admit("Outage at the game port");
            harness.tickAt(START_NANOS);
            JsonObject request = harness.awaitFrame("dialogue.request");
            harness.socket.holdAcknowledgementSends(true);

            CountDownLatch outageAttempted = new CountDownLatch(1);
            CountDownLatch outageCompleted = new CountDownLatch(1);
            AtomicReference<Thread> outageWorker = new AtomicReference<>();
            harness.game.onNextSubmit(() -> {
                Thread worker = new Thread(() -> {
                    outageAttempted.countDown();
                    harness.gate.disconnected();
                    outageCompleted.countDown();
                }, "test-gate-outage");
                outageWorker.set(worker);
                worker.start();
                try {
                    assertTrue(outageAttempted.await(3, TimeUnit.SECONDS), "outage worker did not reach the gate");
                    awaitBlockedOnGate(worker);
                    assertEquals(GateState.READY, harness.gate.snapshot().state(),
                            "the in-flight game-port send still owns the gate's synchronized dispatch");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });

            try {
                harness.tickAt(START_NANOS + TimeUnit.MILLISECONDS.toNanos(750));
            } finally {
                Thread worker = outageWorker.get();
                if (worker != null) {
                    assertTrue(outageCompleted.await(3, TimeUnit.SECONDS), "outage worker was not released after the send");
                    worker.join(TimeUnit.SECONDS.toMillis(3));
                    assertFalse(worker.isAlive(), "outage worker must terminate before the fixture is closed");
                }
            }

            String firstTickTelemetry = harness.lifecycleTelemetry(request);
            try {
                assertTrue(harness.acknowledgementPathResolved.await(3, TimeUnit.SECONDS),
                        "the ACK path neither reached the socket nor offered a rejection completion; " + firstTickTelemetry);
                FakeSocket.SendCompletion acknowledgementSend = harness.socket.firstSend("dialogue.ack");
                assertEquals(FirstReplyScheduler.PublicationResult.SUBMITTED,
                        harness.game.attempts().get(0).result(), "the local ACK was actually sent; " + firstTickTelemetry);
                if (acknowledgementSend != null) harness.socket.fail(acknowledgementSend);
                harness.awaitAcknowledgementOffer();
                GameLoopMailbox.AcknowledgementCompletion completion = harness.acknowledgementCompletionAtOffer.get();
                assertNotNull(completion, "the ACK completion was absent at mailbox offer; " +
                        harness.lifecycleTelemetry(request));
                assertEquals(GameLoopMailbox.Offer.ACCEPTED, harness.acknowledgementOfferOutcome.get(),
                        "the bounded mailbox accepted the ACK completion; " + harness.lifecycleTelemetry(request));
                assertFalse(completion.delivered(), "the controlled outage path must settle failed ACK context; " +
                        harness.lifecycleTelemetry(request));
                assertTrue(acknowledgementSend == null || acknowledgementSend.completion().isCompletedExceptionally(),
                        "an ACK that reached the socket is explicitly completed as failed; " +
                                harness.lifecycleTelemetry(request));
                harness.tickCurrentTime();
            } finally {
                harness.socket.completeHeldSends();
            }

            assertEquals(GateState.UNAVAILABLE, harness.gate.snapshot().state());
            assertEquals(2, harness.game.publishedLines().size(),
                    "the visible acknowledgement is followed by one safe terminal disposition; " +
                            "firstTick=" + firstTickTelemetry + ", afterOwnerDrain=" +
                            harness.lifecycleTelemetry(request));
            assertTrue(harness.game.publishedLines().get(0).contains("Hello Tester"));
            assertTrue(harness.game.publishedLines().get(1).contains(SmokeOutageRetirement.TERMINAL_TEXT));
            harness.tickAt(START_NANOS + TimeUnit.MILLISECONDS.toNanos(750));
            harness.tickAt(START_NANOS + TimeUnit.MILLISECONDS.toNanos(750));
            harness.tickAt(START_NANOS + TimeUnit.MILLISECONDS.toNanos(750));
            assertEquals(2, harness.game.publishedLines().size(), "retirement publishes exactly one terminal");
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.ACK_CONTEXT_FAILURES.ordinal()]);
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.UNAVAILABLE_DISPOSITIONS.ordinal()]);
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.TICK_BUDGET_EXHAUSTED.ordinal()]);
            assertEquals(2, harness.game.attempts().size(), "no fallback ACK or second public terminal is replayed");
            assertNull(harness.mailbox.peekAcknowledgement(), "the ACK completion slot is drained exactly once");
            assertNull(harness.mailbox.activeRequest(), "the failed-context request is retired after its terminal");
            assertTrue(harness.originalConnectionIsCurrent(), "retirement used the current original synthetic connection");

            int lineCountAfterRetirement = harness.game.publishedLines().size();
            harness.sendAnswer(request, "A stale answer after outage must not publish.");
            harness.gate.companionAuthenticated(peer(harness.binding));
            harness.tickAt(START_NANOS + TimeUnit.MILLISECONDS.toNanos(750));

            assertEquals(lineCountAfterRetirement, harness.game.publishedLines().size());
            assertEquals(0, harness.socket.sendCount("dialogue.cancel"),
                    "a closed compatibility gate does not imply that an upstream cancel was sent");
            assertEquals(1, harness.socket.sendCount("dialogue.request"), "reconnect does not replay the retired request");
        }
    }

    @Test
    void failedAcknowledgementContextIsTicketedBeforeGateGenerationInvalidationRetiresTheRequest() throws Exception {
        try (Harness harness = new Harness(30, false)) {
            harness.socket.holdAcknowledgementSend = true;
            InteractionIngress.Invocation firstInvocation = harness.admit("Context failure across a gate generation change");
            harness.tickAt(START_NANOS);
            JsonObject request = harness.awaitFrame("dialogue.request");

            long queuedAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(100);
            harness.clock.set(queuedAt);
            InteractionIngress.Invocation queuedInvocation = harness.admit("Queued work after the ACK barrier");
            harness.tickAt(queuedAt);
            assertEquals(1, harness.game.publishedLines().size(), "the queued turn keeps its own already-visible ACK");

            long firstReplyAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(750);
            harness.tickAt(firstReplyAt);
            FakeSocket.SendCompletion acknowledgement = harness.awaitSend("dialogue.ack");
            assertEquals(List.of("[Worldspeaker] Archivist: Hello Tester", "[Worldspeaker] Archivist: Hello Tester"),
                    harness.game.publishedLines(), "both local acknowledgements are visible before context failure");
            String firstPublicationId = firstInvocation.firstReplySlot().publicationId();

            harness.socket.fail(acknowledgement);
            harness.awaitAcknowledgementOffer();
            assertEquals(GateState.UNAVAILABLE, harness.gate.snapshot().state());
            harness.gate.companionAuthenticated(peer(harness.binding));
            assertEquals(GateState.READY, harness.gate.snapshot().state());

            long failureAt = firstReplyAt + 1;
            harness.tickAt(failureAt);
            assertEquals(3, harness.game.publishedLines().size(), "the active request's terminal has priority over queued work: " +
                    harness.game.publishedLines());
            harness.tickAt(failureAt + 1);

            assertEquals(List.of(
                    "[Worldspeaker] Archivist: Hello Tester",
                    "[Worldspeaker] Archivist: Hello Tester",
                    "[Worldspeaker] Archivist: I delivered the acknowledgement locally, but could not add it to the conversation. Further requests are stopped to avoid missing context.",
                    "[Worldspeaker] Archivist: I stopped this request because earlier delivery context could not be added to the conversation."),
                    harness.game.publishedLines(), "the pending ACK completion is drained before obsolete retirement and queued slots remain ordered");
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.ACK_CONTEXT_FAILURES.ordinal()]);
            assertEquals(1, harness.socket.sendCount("dialogue.request"), "neither old nor queued work is replayed after reconnect");
            assertTrue(queuedInvocation.firstReplySlot().hasFirstPublication(),
                    "queued request publication evidence remains attached to its own first-reply slot");
            assertEquals(firstPublicationId, firstInvocation.firstReplySlot().publicationId(),
                    "late terminal dialogue does not rewrite the active acknowledgement's first-publication slot");

            int linesAfterRetirement = harness.game.publishedLines().size();
            harness.sendAnswer(request, "A stale answer after context failure must not publish.");
            harness.tickAt(failureAt + 2);
            assertEquals(linesAfterRetirement, harness.game.publishedLines().size());
            assertEquals(4, harness.game.attempts().size(), "two ACKs and two ordered terminals are sent without replay");

            assertEquals(0, harness.tryAdmit("A later turn must remain fail-closed"));
            assertEquals(1, harness.socket.sendCount("dialogue.request"),
                    "the context failure blocks later substantive work despite a ready gate generation");
            assertEquals(1, countContaining(harness.game.publishedLines(), "could not add it to the conversation"),
                    "the original request retains exactly one terminal context-failure notice");
            assertEquals(1, countContaining(harness.game.publishedLines(), "I stopped this request because earlier delivery context"),
                    "the queued request receives one stop disposition in its own publication slot");
        }
    }

    @Test
    void progressContextFailureSurvivesGateGenerationRetirementAfterVisibleProgress() throws Exception {
        try (Harness harness = new Harness(30, false)) {
            harness.socket.holdProgressSend = true;
            harness.admit("Progress context fails");
            harness.tickAt(START_NANOS);
            JsonObject request = harness.awaitFrame("dialogue.request");

            long firstReplyAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(750);
            harness.tickAt(firstReplyAt);
            FakeSocket.SendCompletion acknowledgement = harness.awaitSend("dialogue.ack");
            harness.awaitAcknowledgementOffer();
            harness.tickAt(firstReplyAt);
            assertTrue(acknowledgement.completion().isDone());

            long progressAt = START_NANOS + TimeUnit.SECONDS.toNanos(10);
            harness.tickAt(progressAt);
            FakeSocket.SendCompletion progress = harness.awaitSend("dialogue.progress");
            assertTrue(harness.game.publishedLines().stream().anyMatch(line -> line.contains("still waiting")));
            harness.socket.fail(progress);
            harness.awaitProgressOffer();
            assertEquals(GateState.UNAVAILABLE, harness.gate.snapshot().state());
            harness.gate.companionAuthenticated(peer(harness.binding));
            harness.tickAt(progressAt);

            assertEquals(1, countContaining(harness.game.publishedLines(),
                            "I delivered a progress notice, but could not preserve it in the conversation."),
                    "the pending progress completion is drained before obsolete retirement: " + harness.game.publishedLines());
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.PROGRESS_CONTEXT_FAILURES.ordinal()]);
            assertEquals(1L, harness.game.attempts().stream()
                    .filter(attempt -> attempt.renderedLine().contains("I delivered a progress notice, but could not preserve it in the conversation.")).count());

            int linesAfterRetirement = harness.game.publishedLines().size();
            harness.sendAnswer(request, "A stale answer after progress context failure must not publish.");
            harness.gate.companionAuthenticated(peer(harness.binding));
            harness.tickAt(progressAt + 1);
            assertEquals(linesAfterRetirement, harness.game.publishedLines().size());
            assertEquals(1, harness.socket.sendCount("dialogue.request"));
        }
    }

    @Test
    void outageBeforeForwardingPublishesOneFirstDispositionAndNeverStartsTheRequest() {
        try (Harness harness = new Harness(30, false)) {
            harness.admit("Outage before forwarding");
            harness.gate.disconnected();

            harness.tickAt(START_NANOS);
            assertEquals(List.of("[Worldspeaker] Archivist: " + SmokeOutageRetirement.TERMINAL_TEXT),
                    harness.game.publishedLines());
            assertEquals(1, harness.game.attempts().size());
            assertEquals(0, harness.socket.sendCount("dialogue.request"));
            assertEquals(0, harness.socket.sendCount("dialogue.cancel"));

            harness.gate.companionAuthenticated(peer(harness.binding));
            harness.tickAt(START_NANOS + 1);
            assertEquals(1, harness.game.publishedLines().size(), "reconnect does not replay retired work");
            assertEquals(0, harness.socket.sendCount("dialogue.request"));
        }
    }

    @Test
    void unknownAndDefinitelyUnsentLateOutageTerminalsAreNotRetriedAfterReconnect() throws Exception {
        for (FirstReplyScheduler.PublicationResult terminalResult : List.of(
                FirstReplyScheduler.PublicationResult.UNKNOWN,
                FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT)) {
            try (Harness harness = new Harness(30, false)) {
                harness.admit("Outage terminal result " + terminalResult);
                harness.tickAt(START_NANOS);
                JsonObject request = harness.awaitFrame("dialogue.request");

                long firstReplyAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(750);
                harness.tickAt(firstReplyAt);
                FakeSocket.SendCompletion acknowledgement = harness.awaitSend("dialogue.ack");
                harness.awaitAcknowledgementOffer();
                harness.tickAt(firstReplyAt);
                assertTrue(acknowledgement.completion().isDone());

                harness.game.enqueueResult(terminalResult);
                harness.gate.disconnected();
                long outageAt = firstReplyAt + 1;
                harness.tickAt(outageAt);
                assertEquals(2, harness.game.attempts().size());
                assertEquals(terminalResult, harness.game.attempts().get(1).result());
                assertEquals(1, harness.game.publishedLines().size(), "an unconfirmed terminal is not represented as visible");

                harness.gate.companionAuthenticated(peer(harness.binding));
                harness.sendAnswer(request, "A stale answer after an uncertain terminal must not publish.");
                harness.tickAt(outageAt + 1);
                harness.tickAt(outageAt + 2);
                assertEquals(2, harness.game.attempts().size(), "UNKNOWN and UNSENT each consume exactly one ticket");
                assertEquals(1, harness.game.publishedLines().size());
                assertEquals(1, harness.socket.sendCount("dialogue.request"));
            }
        }
    }

    @Test
    void outageTicketNeverMovesToAReconnectedPlayerAndRecordsCancellationWithoutDelivery() throws Exception {
        try (Harness harness = new Harness(30, false)) {
            InteractionIngress.Invocation invocation = harness.admit("Original connection is gone");
            harness.tickAt(START_NANOS);
            JsonObject request = harness.awaitFrame("dialogue.request");
            harness.gate.disconnected();
            harness.game.replaceConnection(harness.player);

            long outageAt = START_NANOS + 1;
            harness.tickAt(outageAt);
            assertTrue(harness.game.publishedLines().isEmpty(), "the terminal is not sent to a new connection");
            assertTrue(harness.game.attempts().isEmpty(), "no public send was attempted for the new login");

            harness.gate.companionAuthenticated(peer(harness.binding));
            harness.sendAnswer(request, "A stale answer for the old connection must not publish.");
            harness.tickAt(outageAt + 1);
            assertTrue(harness.game.publishedLines().isEmpty());
            assertEquals(1, harness.socket.sendCount("dialogue.request"));

            Path evidence = temporaryDirectory.resolve("retired-original-connection.jsonl");
            assertTrue(harness.metrics.offerNumericSnapshot(evidence));
            assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));
            JsonObject row = evidenceRow(evidence, invocation.invocationId().toString());
            assertEquals("UNAVAILABLE", row.get("terminalOutcome").getAsString());
            assertEquals("UPSTREAM_UNAVAILABLE", row.get("terminalCause").getAsString());
            assertEquals("NO_ATTEMPT", row.get("terminalDeliveryResult").getAsString());
            assertEquals("NO_AUDIENCE", row.get("retirementNoticeResult").getAsString());
            assertEquals("DEFINITELY_UNSENT", row.get("localCancelStatus").getAsString());
            assertFalse(row.get("providerCancel").getAsBoolean());
            assertEquals(1, row.get("lateOutputRejections").getAsInt());
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.LATE_OUTPUT_REJECTIONS.ordinal()]);
            assertFalse(invocation.firstReplySlot().hasFirstPublication(),
                    "no-delivery cancellation does not claim the invocation's publication slot");
        }
    }

    @Test
    void definiteUnsentAndUnknownFirstRepliesKeepDifferentTruthWithoutAutomaticAcknowledgementReplay() {
        try (Harness definite = new Harness(30, false)) {
            definite.game.enqueueResult(FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT);
            definite.admit("A definitely unsent acknowledgement");
            definite.tickAt(START_NANOS);
            JsonObject request = definite.awaitFrame("dialogue.request");
            assertTrue(request.get("acknowledgement").isJsonNull(), "no unsubmitted ACK is represented as request context");

            long firstReplyAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(750);
            definite.tickAt(firstReplyAt);
            assertTrue(definite.game.publishedLines().isEmpty());
            assertTrue(definite.invocations.get(0).firstReplySlot().openForFirstPublication(),
                    "a definite non-send leaves the first-publication slot available for a distinct safe response");
            definite.tickAt(firstReplyAt);

            assertEquals(2, definite.game.attempts().size());
            assertEquals(FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT,
                    definite.game.attempts().get(0).result());
            assertEquals(List.of("[Worldspeaker] Archivist: I couldn't deliver a reply right now."),
                    definite.game.publishedLines());
            assertEquals(0, definite.socket.sendCount("dialogue.ack"), "the original ACK is not retransmitted");
        }

        try (Harness unknown = new Harness(30, false)) {
            unknown.game.enqueueResult(FirstReplyScheduler.PublicationResult.UNKNOWN);
            unknown.admit("An acknowledgement with an unknown result");
            unknown.tickAt(START_NANOS);
            JsonObject request = unknown.awaitFrame("dialogue.request");
            assertTrue(request.get("acknowledgement").isJsonNull(), "UNKNOWN is not promoted to confirmed context");

            long firstReplyAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(750);
            unknown.tickAt(firstReplyAt);
            unknown.tickAt(firstReplyAt);
            assertTrue(unknown.invocations.get(0).firstReplySlot().unknown());
            assertEquals(1, unknown.game.attempts().size(), "an ambiguous ACK is never automatically replayed");
            assertTrue(unknown.game.publishedLines().isEmpty(), "UNKNOWN is not asserted as confirmed local delivery");
            assertEquals(0, unknown.socket.sendCount("dialogue.ack"));

            unknown.clock.set(firstReplyAt + 1);
            unknown.sendAnswer(request, "A later technical answer remains independently deliverable.");
            unknown.tickAt(firstReplyAt + 1);
            assertEquals(List.of("[Worldspeaker] Archivist: A later technical answer remains independently deliverable."),
                    unknown.game.publishedLines());
        }
    }

    @Test
    void acknowledgedContextBarrierReleaseForwardsQueuedBAndPreservesItsDeadlineAndTurnOrder() throws Exception {
        try (Harness harness = new Harness(30, false)) {
            harness.socket.holdAcknowledgementSend = true;
            harness.admit("Active request A");
            harness.tickAt(START_NANOS);
            JsonObject requestA = harness.awaitFrame("dialogue.request", 1);

            long capturedB = START_NANOS + TimeUnit.MILLISECONDS.toNanos(100);
            harness.admit(harness.player, "Useful technical question B");
            harness.tickAt(capturedB);
            long capturedC = START_NANOS + TimeUnit.MILLISECONDS.toNanos(200);
            harness.admit(harness.secondPlayer, "Fair request C");
            harness.tickAt(capturedC);

            long firstReplyAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(750);
            harness.tickAt(firstReplyAt);
            FakeSocket.SendCompletion acknowledgement = harness.awaitSend("dialogue.ack");
            assertFalse(acknowledgement.completion().isDone(), "the real ACK send completion remains held");
            assertEquals(1, harness.socket.sendCount("dialogue.request"));

            long answerAt = firstReplyAt + 1;
            harness.clock.set(answerAt);
            harness.sendAnswer(requestA, "A's technical answer is ready while ACK context is pending.");
            harness.tickAt(answerAt);
            assertEquals(1, harness.socket.sendCount("dialogue.request"),
                    "completing A cannot bypass the unresolved ACK context barrier");
            assertFalse(harness.game.publishedLines().stream().anyMatch(line -> line.contains("expired before I could start")));

            harness.socket.complete(acknowledgement);
            harness.awaitAcknowledgementOffer();
            assertEquals(GameLoopMailbox.Offer.ACCEPTED, harness.acknowledgementOfferOutcome.get());
            assertTrue(harness.mailbox.peekAcknowledgement() != null,
                    "the socket completion, rather than sendText entry, owns the mailbox barrier release");
            harness.tickAt(answerAt);

            JsonObject requestB = harness.awaitFrame("dialogue.request", 2);
            assertEquals("Useful technical question B", requestB.getAsJsonObject("event").get("text").getAsString());
            assertFalse(requestB.get("acknowledgement").isJsonNull(), "B's locally submitted ACK travels with B's request");
            harness.sendAnswer(requestB, "B's useful answer: inspect the failing call site and compare its expected type.");
            harness.tickAt(answerAt);

            JsonObject requestC = harness.awaitFrame("dialogue.request", 3);
            assertEquals("Fair request C", requestC.getAsJsonObject("event").get("text").getAsString(),
                    "the other player's queued turn remains next after B completes");
            harness.sendAnswer(requestC, "C's answer follows B without being starved.");
            harness.tickAt(answerAt);

            assertTrue(harness.game.publishedLines().stream().anyMatch(line -> line.contains(
                    "B's useful answer: inspect the failing call site and compare its expected type.")));
            assertTrue(harness.game.publishedLines().stream().anyMatch(line -> line.contains("C's answer follows B")));
            assertFalse(harness.game.publishedLines().stream().anyMatch(line -> line.contains("expired before I could start")));
            Path evidence = temporaryDirectory.resolve("queued-b-deadline.jsonl");
            assertTrue(harness.metrics.offerNumericSnapshot(evidence));
            assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));
            assertTrue(Files.readString(evidence).contains("\"pendingDeadlineAtNanos\":\"" +
                            (capturedB + TimeUnit.SECONDS.toNanos(15)) + "\""),
                    "context release forwards B against the original capture-based pending deadline");
            assertEquals(3, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()],
                    "B and C are each delivered once in FIFO/round-robin order after A");
        }
    }

    @Test
    void outageAfterAcknowledgementAndAfterProgressProducesOneTerminalAndNeverReplaysStaleWork() throws Exception {
        try (Harness acknowledgement = new Harness(30, false)) {
            acknowledgement.admit("Request retired after its acknowledgement");
            acknowledgement.tickAt(START_NANOS);
            JsonObject request = acknowledgement.awaitFrame("dialogue.request");
            long firstReplyAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(750);
            acknowledgement.tickAt(firstReplyAt);
            FakeSocket.SendCompletion send = acknowledgement.awaitSend("dialogue.ack");
            acknowledgement.awaitAcknowledgementOffer();
            acknowledgement.tickAt(firstReplyAt);
            assertTrue(send.completion().isDone());

            assertSingleOutageRetirement(acknowledgement, request, firstReplyAt);
        }

        try (Harness progress = new Harness(30, true)) {
            progress.admit("Request retired after progress");
            progress.tickAt(START_NANOS);
            JsonObject request = progress.awaitFrame("dialogue.request");
            long firstReplyAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(750);
            progress.tickAt(firstReplyAt);
            FakeSocket.SendCompletion acknowledgement = progress.awaitSend("dialogue.ack");
            progress.awaitAcknowledgementOffer();
            progress.tickAt(firstReplyAt);
            assertTrue(acknowledgement.completion().isDone());

            long progressAt = START_NANOS + TimeUnit.SECONDS.toNanos(10);
            progress.tickAt(progressAt);
            FakeSocket.SendCompletion progressSend = progress.awaitSend("dialogue.progress");
            assertFalse(progressSend.completion().isDone());
            progress.socket.complete(progressSend);
            progress.awaitProgressOffer();
            assertEquals(GameLoopMailbox.Offer.ACCEPTED, progress.progressOfferOutcome.get());
            progress.tickAt(progressAt);

            assertSingleOutageRetirement(progress, request, progressAt);
        }
    }

    @Test
    void queuedProgressReportsContextBarrierAndPendingExpiryStillUsesOriginalFifteenSeconds() throws InterruptedException {
        try (Harness harness = new Harness(30, true)) {
            harness.socket.holdAcknowledgementSend = true;
            harness.admit("Active request A");
            harness.tickAt(START_NANOS);
            harness.awaitFrame("dialogue.request");

            long queuedAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(100);
            harness.clock.set(queuedAt);
            harness.admit("Distinct queued request B");
            harness.tickAt(queuedAt);
            assertTrue(harness.game.publishedLines().stream().anyMatch(line -> line.contains("Hello")),
                    "the blocked request retains its own local first reply");

            long firstReplyAt = START_NANOS + TimeUnit.MILLISECONDS.toNanos(750);
            harness.tickAt(firstReplyAt);
            FakeSocket.SendCompletion acknowledgementSend = harness.awaitSend("dialogue.ack");
            assertEquals("worldspeaker-transport-owner", acknowledgementSend.senderThread().getName(),
                    "sendText must be reached through the production owner pump");
            assertFalse(acknowledgementSend.completion().isDone(),
                    "recording sendText entry must not stand in for the WebSocket send completion");
            assertNull(harness.mailbox.peekAcknowledgement(),
                    "the mailbox callback must not run before the held socket completion");
            harness.socket.complete(acknowledgementSend);
            harness.awaitAcknowledgementOffer();
            assertEquals(GameLoopMailbox.Offer.ACCEPTED, harness.acknowledgementOfferOutcome.get(),
                    "record the actual asynchronous mailbox callback outcome");
            assertTrue(harness.mailbox.peekAcknowledgement() != null);
            long progressAt = queuedAt + TimeUnit.SECONDS.toNanos(10);
            harness.tickAt(progressAt);
            // The independently reserved ACK completion drains after this tick's progress pass.
            harness.tickAt(progressAt);
            assertTrue(harness.game.publishedLines().stream()
                    .anyMatch(line -> line.contains("forwarded and I am still waiting")));

            harness.tickAt(progressAt);
            assertTrue(harness.game.publishedLines().stream()
                    .anyMatch(line -> line.contains("holding this request until earlier conversation context is preserved")),
                    "B reports the actual unresolved context barrier, not model activity");
            FakeSocket.SendCompletion progressSend = harness.awaitSend("dialogue.progress");
            assertFalse(progressSend.completion().isDone(), "the held progress send remains in flight");

            long pendingExpiry = queuedAt + TimeUnit.SECONDS.toNanos(15);
            harness.tickAt(pendingExpiry - 1);
            assertFalse(harness.game.publishedLines().stream()
                            .anyMatch(line -> line.contains("This request expired before I could start it.")),
                    "the queued request must remain live immediately before its original deadline");
            harness.tickAt(pendingExpiry);
            assertTrue(harness.game.publishedLines().stream()
                    .anyMatch(line -> line.contains("This request expired before I could start it.")),
                    "the 15-second pending deadline remains anchored to B's original capture time");
            assertEquals(2, harness.metrics.countersSnapshot()[TickMetrics.Counter.PROGRESS_DISPATCHES.ordinal()]);
        }
    }

    private void assertExpiredAt(long deadlineNanos) {
        try (Harness harness = new Harness(1, false)) {
            harness.admit("A terminal at the active deadline");
            harness.tickAt(START_NANOS);
            JsonObject request = harness.awaitFrame("dialogue.request");
            harness.clock.set(deadlineNanos);
            harness.sendAnswer(request, "This must lose at or after the deadline.");
            harness.tickAt(deadlineNanos);
            harness.tickAt(deadlineNanos + 1);

            assertEquals(List.of("[Worldspeaker] Archivist: I couldn't complete that request in time."),
                    harness.game.publishedLines());
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);
            harness.awaitSend("dialogue.cancel");
            assertEquals(1, harness.socket.sendCount("dialogue.cancel"), "expiry retires and cancels the forwarded turn once");
        }
    }

    private void assertAnswerAtFinalInspection(long finalInspectionNanos, boolean shouldSubmit) throws Exception {
        long deadline = START_NANOS + TimeUnit.SECONDS.toNanos(1);
        long beforeInspectionNanos = deadline - 2;
        try (Harness harness = new Harness(1, false)) {
            InteractionIngress.Invocation invocation = harness.admit("Answer at the last publication inspection");
            harness.tickAt(START_NANOS);
            JsonObject request = harness.awaitFrame("dialogue.request");

            harness.sendAnswer(request, "The final-inspection answer.");
            harness.game.onNthNextInspect(2, () -> harness.clock.set(finalInspectionNanos));
            harness.tickAt(beforeInspectionNanos);

            if (shouldSubmit) {
                assertEquals(List.of("[Worldspeaker] Archivist: The final-inspection answer."),
                        harness.game.publishedLines(), "the −1 ns control remains eligible at the protected claim gate");
                assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);
                assertEquals(FirstReplyScheduler.PublicationResult.SUBMITTED,
                        harness.game.attempts().get(0).result());
                return;
            }

            assertEquals(List.of("[Worldspeaker] Archivist: I couldn't complete that request in time."),
                    harness.game.publishedLines(), "at/after the final cutoff only the safe expiry terminal is sent");
            assertEquals(1, harness.game.attempts().size(), "the expired answer never reaches the game-port send");
            assertFalse(harness.game.attempts().get(0).renderedLine().contains("The final-inspection answer."));
            assertEquals(0, harness.metrics.countersSnapshot()[TickMetrics.Counter.ANSWERS_DELIVERED.ordinal()]);
            assertTrue(invocation.firstReplySlot().hasFirstPublication(),
                    "the safe terminal, not the expired answer, consumes the first publication claim");

            harness.sendAnswer(request, "A duplicate late final must not add another terminal.");
            harness.tickAt(finalInspectionNanos + 1);
            assertEquals(1, countContaining(harness.game.publishedLines(), "I couldn't complete that request in time."));
            assertEquals(1, harness.game.attempts().size(), "late mailbox output is not replayed after retirement");
            assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.LATE_OUTPUT_REJECTIONS.ordinal()],
                    "one late final is attributed once, not double-counted");

            Path evidence = temporaryDirectory.resolve("final-inspection-" + finalInspectionNanos + ".jsonl");
            assertTrue(harness.metrics.offerNumericSnapshot(evidence));
            assertTrue(TickMetricsTestAccess.awaitPersistence(harness.metrics, 3, TimeUnit.SECONDS));
            JsonObject row = evidenceRow(evidence, invocation.invocationId().toString());
            assertEquals("EXPIRED", row.get("terminalOutcome").getAsString());
            assertEquals("ACTIVE_EXPIRY", row.get("terminalCause").getAsString());
            assertEquals("SUBMITTED", row.get("terminalDeliveryResult").getAsString());
            assertEquals(Long.toString(finalInspectionNanos), row.get("terminalAtNanos").getAsString());
            assertEquals(1, row.get("lateOutputRejections").getAsInt());
            assertEquals(0, row.get("failedDispatches").getAsInt(),
                    "a rejected pre-claim answer is not counted as a local send attempt");
        }
    }

    private static JsonObject evidenceRow(Path evidence, String invocationId) throws Exception {
        for (String line : Files.readAllLines(evidence)) {
            JsonObject frame = JsonParser.parseString(line).getAsJsonObject();
            if (!frame.has("records")) continue;
            for (var item : frame.getAsJsonArray("records")) {
                JsonObject row = item.getAsJsonObject();
                if (row.has("id") && invocationId.equals(row.get("id").getAsString())) return row;
            }
        }
        throw new AssertionError("no numeric evidence row for invocation " + invocationId);
    }

    private static List<JsonObject> evidenceRows(Path evidence) throws Exception {
        List<JsonObject> rows = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(evidence)) {
            JsonObject frame = JsonParser.parseString(line).getAsJsonObject();
            if (!frame.has("records")) continue;
            for (var item : frame.getAsJsonArray("records")) rows.add(item.getAsJsonObject());
        }
        return List.copyOf(rows);
    }

    private static void assertSingleOutageRetirement(Harness harness, JsonObject request, long nowNanos)
            throws InterruptedException {
        harness.gate.disconnected();
        harness.tickAt(nowNanos);
        assertEquals(1, harness.metrics.countersSnapshot()[TickMetrics.Counter.UNAVAILABLE_DISPOSITIONS.ordinal()],
                "the outage disposition must run in the same production tick; output=" +
                        harness.game.publishedLines() + ", attempts=" + harness.game.attempts());
        assertEquals(1, countContaining(harness.game.publishedLines(), SmokeOutageRetirement.TERMINAL_TEXT));
        assertEquals(0, harness.socket.sendCount("dialogue.cancel"),
                "the unavailable gate prevents claiming that an upstream cancellation was sent");

        int linesAfterRetirement = harness.game.publishedLines().size();
        harness.sendAnswer(request, "A stale answer after the outage must not publish.");
        harness.gate.companionAuthenticated(peer(harness.binding));
        harness.tickAt(nowNanos);

        assertEquals(linesAfterRetirement, harness.game.publishedLines().size());
        assertEquals(1, countContaining(harness.game.publishedLines(), SmokeOutageRetirement.TERMINAL_TEXT));
        assertEquals(1, harness.socket.sendCount("dialogue.request"), "reconnect does not replay the retired request");
        assertEquals(0, harness.socket.sendCount("dialogue.cancel"), "no cancel is claimed while the gate is unavailable");
    }

    private static void awaitBlockedOnGate(Thread worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (worker.getState() != Thread.State.BLOCKED && System.nanoTime() - deadline < 0) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.BLOCKED, worker.getState(),
                "the outage reaches the synchronized gate while the actual game-port send is in flight");
    }

    private static int countContaining(List<String> lines, String text) {
        return (int) lines.stream().filter(line -> line.contains(text)).count();
    }

    private static void awaitEvidenceAuthorityClosed(TickMetrics metrics) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (metrics.canReserveInteraction() && System.nanoTime() - deadline < 0) {
            try {
                TimeUnit.MILLISECONDS.sleep(1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("writer path failure wait was interrupted", interrupted);
            }
        }
        assertFalse(metrics.canReserveInteraction(), "the real writer did not publish its path-mismatch failure");
    }

    private static PeerFacts peer(ConfiguredBinding binding) {
        return new PeerFacts(DeploymentFacts.DEPLOYMENT_ID, DeploymentFacts.MANIFEST_SHA256,
                DeploymentFacts.PROTOCOL, DeploymentFacts.OPENCLAW_VERSION, DeploymentFacts.NODE_VERSION,
                DeploymentFacts.COMPANION_VERSION, binding.serverId(), binding.worldId(), binding.characterId(),
                binding.agentId());
    }

    /** Shared production-path fixture for qualification tests in this package. */
    private enum AbilityOwnerTestMode { ISOLATED_SIMULATION, NORMAL_ISOLATED }

    static final class Harness implements AutoCloseable {
        final AtomicLong clock = new AtomicLong(START_NANOS);
        final UUID playerId = UUID.fromString(PLAYER_ID);
        final UUID secondPlayerId = UUID.fromString("00000000-0000-4000-8000-000000000002");
        final UUID thirdPlayerId = UUID.fromString("00000000-0000-4000-8000-000000000003");
        final FakePlayer player = new FakePlayer("Tester");
        final FakePlayer secondPlayer = new FakePlayer("TesterTwo");
        final FakePlayer thirdPlayer = new FakePlayer("TesterThree");
        final FakeGamePort game = new FakeGamePort(player, playerId, secondPlayer, secondPlayerId,
                thirdPlayer, thirdPlayerId);
        final AtomicReference<SmokeConversation> conversationRef = new AtomicReference<>();
        final CountDownLatch acknowledgementPathResolved = new CountDownLatch(1);
        final FakeSocket socket = new FakeSocket(acknowledgementPathResolved);
        final CountDownLatch acknowledgementOfferObserved = new CountDownLatch(1);
        final AtomicInteger acknowledgementOfferCount = new AtomicInteger();
        final AtomicReference<GameLoopMailbox.Offer> acknowledgementOfferOutcome = new AtomicReference<>();
        final AtomicReference<GameLoopMailbox> observedMailbox = new AtomicReference<>();
        final AtomicReference<GameLoopMailbox.AcknowledgementCompletion> acknowledgementCompletionAtOffer =
                new AtomicReference<>();
        final CountDownLatch progressOfferObserved = new CountDownLatch(1);
        final AtomicReference<GameLoopMailbox.Offer> progressOfferOutcome = new AtomicReference<>();
        final GameLoopMailbox mailbox = GameLoopMailboxTestAccess.observingCompletionOffers(outcome -> {
                    GameLoopMailbox observed = observedMailbox.get();
                    if (observed != null) acknowledgementCompletionAtOffer.set(observed.peekAcknowledgement());
                    acknowledgementOfferOutcome.set(outcome);
                    acknowledgementOfferCount.incrementAndGet();
                    acknowledgementOfferObserved.countDown();
                    acknowledgementPathResolved.countDown();
                }, outcome -> {
                    progressOfferOutcome.set(outcome);
                    progressOfferObserved.countDown();
                });
        {
            observedMailbox.set(mailbox);
        }
        final TickMetrics metrics = new TickMetrics();
        final InvocationRateLimiter rateLimiter;
        final boolean publishIngressDispositions;
        final FirstReplyScheduler scheduler = new FirstReplyScheduler(clock::get, () -> WALL_MILLIS);
        final Object serviceEpoch = new Object();
        final ConfiguredBinding binding = new ConfiguredBinding("server-test", "world-test", "character-test", "agent-test");
        final CompanionConfig config;
        final CompatibilityGate gate;
        final BoundCompanionClient companion;
        final FakeAuthorityGamePort abilityGame;
        final AbilityLimitLedger abilityLedger;
        final SmokeConversation conversation;
        final List<InteractionIngress.Invocation> invocations = new CopyOnWriteArrayList<>();
        volatile java.util.function.Consumer<InteractionIngress.Invocation> beforeConversationSubmit = ignored -> {};
        volatile Runnable beforeBeginExplicit = () -> {};
        final InteractionIngress.Port<Object> ingress;

        Harness(int executionBudgetSeconds, boolean holdProgressSend) {
            this(executionBudgetSeconds, holdProgressSend, null, false);
        }

        Harness(int executionBudgetSeconds, boolean holdProgressSend, InvocationRateLimiter rateLimiter) {
            this(executionBudgetSeconds, holdProgressSend, rateLimiter, true);
        }

        Harness(int executionBudgetSeconds, boolean holdProgressSend, InvocationRateLimiter rateLimiter,
                boolean publishIngressDispositions) {
            this(executionBudgetSeconds, holdProgressSend, rateLimiter, publishIngressDispositions,
                    CharacterChatStyle.PLAIN, "Archivist");
        }

        Harness(int executionBudgetSeconds, boolean holdProgressSend, CharacterChatStyle style, String displayName) {
            this(executionBudgetSeconds, holdProgressSend, null, true, style, displayName, null);
        }

        Harness(int executionBudgetSeconds, boolean holdProgressSend, AbilityPolicy abilityPolicy) {
            this(executionBudgetSeconds, holdProgressSend, null, true, CharacterChatStyle.PLAIN,
                    "Archivist", abilityPolicy, AbilityOwnerTestMode.ISOLATED_SIMULATION);
        }

        Harness(int executionBudgetSeconds, boolean holdProgressSend, AbilityPolicy abilityPolicy,
                AbilityOwnerTestMode abilityOwnerTestMode) {
            this(executionBudgetSeconds, holdProgressSend, null, true, CharacterChatStyle.PLAIN,
                    "Archivist", abilityPolicy, abilityOwnerTestMode);
        }

        private Harness(int executionBudgetSeconds, boolean holdProgressSend, InvocationRateLimiter rateLimiter,
                        boolean publishIngressDispositions, CharacterChatStyle style, String displayName) {
            this(executionBudgetSeconds, holdProgressSend, rateLimiter, publishIngressDispositions,
                    style, displayName, null);
        }

        private Harness(int executionBudgetSeconds, boolean holdProgressSend, InvocationRateLimiter rateLimiter,
                        boolean publishIngressDispositions, CharacterChatStyle style, String displayName,
                        AbilityPolicy abilityPolicy) {
            this(executionBudgetSeconds, holdProgressSend, rateLimiter, publishIngressDispositions,
                    style, displayName, abilityPolicy, AbilityOwnerTestMode.ISOLATED_SIMULATION);
        }

        private Harness(int executionBudgetSeconds, boolean holdProgressSend, InvocationRateLimiter rateLimiter,
                        boolean publishIngressDispositions, CharacterChatStyle style, String displayName,
                        AbilityPolicy abilityPolicy, AbilityOwnerTestMode abilityOwnerTestMode) {
            ThreadBoundary.markServerThread();
            this.rateLimiter = rateLimiter;
            this.publishIngressDispositions = publishIngressDispositions;
            config = abilityPolicy == null
                    ? new CompanionConfig(URI.create("ws://127.0.0.1:18473"), new byte[32], binding,
                            "Hello {player}", displayName,
                            new InteractionPolicy(Set.of(playerId, secondPlayerId, thirdPlayerId)), List.of(), 30,
                            AttentionPolicy.disabled(), executionBudgetSeconds, style)
                    : new CompanionConfig(URI.create("ws://127.0.0.1:18473"), new byte[32], binding,
                            "Hello {player}", displayName,
                            new InteractionPolicy(Set.of(playerId, secondPlayerId, thirdPlayerId)), List.of(), 30,
                            AttentionPolicy.disabled(), executionBudgetSeconds, style,
                            abilityPolicy, "0".repeat(64));
            RuntimeFacts local = new RuntimeFacts(DeploymentFacts.MINECRAFT_VERSION,
                    DeploymentFacts.FABRIC_LOADER_VERSION, DeploymentFacts.FABRIC_API_VERSION,
                    Integer.parseInt(DeploymentFacts.JAVA_MAJOR), DeploymentFacts.JAVA_VENDOR,
                    DeploymentFacts.JAVA_RUNTIME_VERSION);
            gate = new CompatibilityGate(binding);
            gate.checkLocal(local);
            PeerFacts peer = peer(binding);
            companion = new BoundCompanionClient(config, local, gate, frame -> {
                SmokeConversation current = conversationRef.get();
                if (current != null) current.onDialogueMessage(frame);
            }, ignored -> {});
            abilityGame = abilityPolicy == null ? null : new FakeAuthorityGamePort(game, clock, companion, gate, config);
            abilityLedger = abilityGame == null ? null : new AbilityLimitLedger(clock::get);
            AbilityRequestAuthority.IsolatedOwner isolatedAbilityOwner = abilityGame == null
                    ? null : abilityOwnerTestMode == AbilityOwnerTestMode.ISOLATED_SIMULATION
                            ? AbilityRequestAuthority.isolatedSimulationOwner(abilityGame, abilityLedger, true)
                            : AbilityRequestAuthority.isolatedOwner(abilityGame, abilityLedger);
            conversation = isolatedAbilityOwner == null
                    ? new SmokeConversation(null, config, gate, companion, mailbox, metrics, 7,
                            serviceEpoch, SERVER_EPOCH, ignored -> {}, scheduler, game)
                    : new SmokeConversation(null, config, gate, companion, mailbox, metrics, 7,
                            serviceEpoch, SERVER_EPOCH, ignored -> {}, scheduler, game, isolatedAbilityOwner);
            conversationRef.set(conversation);
            socket.holdProgressSend = holdProgressSend;
            if (abilityGame != null) {
                companion.onOpen(socket.webSocket());
                companion.onError(socket.webSocket(), new IllegalStateException("advance isolated peer generation"));
            }
            companion.onOpen(socket.webSocket());
            companion.onText(socket.webSocket(), challengeFrame(config, peer).toString(), true)
                    .toCompletableFuture().join();
            JsonObject hello = awaitJsonFrame("hello");
            String nonce = hello.get("nonce").getAsString();
            companion.onText(socket.webSocket(), welcomeFrame(config, peer, nonce).toString(), true)
                    .toCompletableFuture().join();
            assertTrue(companion.isReady(), "the offline peer completed the real authenticated client handshake");

            ingress = new InteractionIngress.Port<>() {
                @Override public InteractionIngress.SourceFacts resolve(Object source) {
                    return InteractionIngress.SourceFacts.nonPlayer();
                }
                @Override public InteractionIngress.SourceFacts resolve(Object source, String input) {
                    return game.sourceFacts(source);
                }
                @Override public CompanionConfig config() { return config; }
                @Override public TickMetrics metrics() { return metrics; }
                @Override public InvocationRateLimiter rateLimiter() { return rateLimiter; }
                @Override public boolean serviceActive() { return true; }
                @Override public FirstReplyScheduler firstReplyScheduler() { return scheduler; }
                @Override public FollowupWindow.Candidate beginExplicit(InteractionIngress.SourceFacts source,
                                                                         InteractionIngress.Captured captured) {
                    beforeBeginExplicit.run();
                    return conversation.beginExplicit(source, captured, serviceEpoch);
                }
                @Override public void invalidateFollowup(InteractionIngress.SourceFacts source) {
                    if (source != null) conversation.invalidateFollowup(source.playerId());
                }
                @Override public InteractionIngress.Outcome submit(InteractionIngress.SourceFacts source, String text,
                                                                     InteractionIngress.Invocation invocation) {
                    invocations.add(invocation);
                    beforeConversationSubmit.accept(invocation);
                    return InteractionIngress.Outcome.valueOf(conversation.submit(source, text, invocation).name());
                }
                @Override public boolean publishPlayer(InteractionIngress.SourceFacts source, String message,
                                                       boolean characterMessage, String characterDisplayName) { return false; }
                @Override public FirstReplyScheduler.PublicationResult submitPlayerDisposition(
                        InteractionIngress.SourceFacts source, String message, boolean characterMessage,
                        String characterDisplayName, boolean requireCurrentGrant) {
                    return submitPlayerDisposition(source, message, characterMessage, characterDisplayName,
                            CharacterChatStyle.PLAIN, requireCurrentGrant);
                }
                @Override public FirstReplyScheduler.PublicationResult submitPlayerDisposition(
                        InteractionIngress.SourceFacts source, String message, boolean characterMessage,
                        String characterDisplayName, CharacterChatStyle characterChatStyle,
                        boolean requireCurrentGrant) {
                    if (!publishIngressDispositions || source == null || source.player() == null ||
                            requireCurrentGrant && !config.interactionPolicy().allows(source.playerId(),
                                    InteractionPolicy.CONVERSE_PERMISSION)) {
                        return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
                    }
                    String rendered = characterMessage
                            ? VanillaDialogue.renderCharacterLine(characterDisplayName, message, characterChatStyle)
                            : VanillaDialogue.renderLine(message);
                    return game.submitRendered(source.player(), rendered);
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

        InteractionIngress.Invocation admit(String text) {
            return admit(player, text);
        }

        InteractionIngress.Invocation admit(FakePlayer target, String text) {
            InteractionIngress.Captured captured = InteractionIngress.Captured.capture(clock::get,
                    () -> WALL_MILLIS, InteractionIngress.Origin.COMMAND);
            int accepted = InteractionIngress.execute(captured, target, "worldspeaker ask", InteractionIngress.Route.ASK,
                    text, ingress);
            assertEquals(1, accepted, "the production ingress admits the request through SmokeConversation.submit");
            return invocations.get(invocations.size() - 1);
        }

        int tryAdmit(String text) {
            InteractionIngress.Captured captured = InteractionIngress.Captured.capture(clock::get,
                    () -> WALL_MILLIS, InteractionIngress.Origin.COMMAND);
            return InteractionIngress.execute(captured, player, "worldspeaker ask", InteractionIngress.Route.ASK,
                    text, ingress);
        }

        int invokeAsk(FakePlayer target, String text, long capturedAtNanos) {
            InteractionIngress.Captured captured = InteractionIngress.Captured.capture(() -> capturedAtNanos,
                    () -> WALL_MILLIS, InteractionIngress.Origin.COMMAND);
            return InteractionIngress.execute(captured, target, "worldspeaker ask", InteractionIngress.Route.ASK,
                    text, ingress);
        }

        String invokeHelp(long capturedAtNanos) {
            clock.set(capturedAtNanos);
            InteractionIngress.Captured captured = InteractionIngress.Captured.capture(() -> capturedAtNanos,
                    () -> WALL_MILLIS, InteractionIngress.Origin.COMMAND);
            assertEquals(1, InteractionIngress.execute(captured, player, "worldspeaker help",
                    InteractionIngress.Route.HELP, null, ingress));
            tickAt(capturedAtNanos);
            return game.publishedLines().get(game.publishedLines().size() - 1);
        }

        void tickAt(long nowNanos) {
            clock.set(nowNanos);
            conversation.tick();
        }

        void tickCurrentTime() {
            conversation.tick();
        }

        JsonObject awaitFrame(String type) {
            return awaitSend(type).frame();
        }

        JsonObject awaitFrame(String type, int occurrence) {
            return awaitSend(type, occurrence).frame();
        }

        FakeSocket.SendCompletion awaitSend(String type) {
            return awaitSend(type, 1);
        }

        FakeSocket.SendCompletion awaitSend(String type, int occurrence) {
            try {
                return socket.awaitSend(type, occurrence);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }

        void awaitAcknowledgementOffer() throws InterruptedException {
            assertTrue(acknowledgementOfferObserved.await(3, TimeUnit.SECONDS),
                    "ACK send completion did not reach the mailbox callback within three seconds; send=" +
                            socket.describe("dialogue.ack") + ", offerOutcome=" + acknowledgementOfferOutcome.get() +
                            ", mailbox=" + mailbox.peekAcknowledgement());
        }

        void awaitAcknowledgementOffers(int expected) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (acknowledgementOfferCount.get() < expected && System.nanoTime() - deadline < 0) {
                Thread.onSpinWait();
            }
            assertEquals(expected, acknowledgementOfferCount.get(),
                    "ACK send completion did not reach the mailbox within three seconds");
        }

        void awaitProgressOffer() throws InterruptedException {
            assertTrue(progressOfferObserved.await(3, TimeUnit.SECONDS),
                    "progress send completion did not reach the mailbox callback within three seconds; send=" +
                            socket.describe("dialogue.progress") + ", offerOutcome=" + progressOfferOutcome.get() +
                            ", mailbox=" + mailbox.peekProgress());
        }

        String lifecycleTelemetry(JsonObject request) {
            List<FakeGamePort.RenderedAttempt> attempts = game.attempts();
            FirstReplyScheduler.PublicationResult gameSend = attempts.isEmpty() ? null : attempts.get(0).result();
            FakeSocket.SendCompletion acknowledgementSend = socket.firstSend("dialogue.ack");
            GameLoopMailbox.AcknowledgementCompletion completion = acknowledgementCompletionAtOffer.get();
            GameLoopMailbox.AcknowledgementCompletion pendingCompletion = mailbox.peekAcknowledgement();
            GameLoopMailbox.ActiveRequest active = mailbox.activeRequest();
            SmokeConversation.PlayerView current = game.inspect(player);
            InteractionIngress.Invocation invocation = invocations.isEmpty() ? null : invocations.get(0);
            long[] counters = metrics.countersSnapshot();
            return "gamePortResult=" + gameSend +
                    ", ackSendCount=" + socket.sendCount("dialogue.ack") +
                    ", ackFuture=" + (acknowledgementSend == null ? "none" :
                    "done:" + acknowledgementSend.completion().isDone() +
                            "/failed:" + acknowledgementSend.completion().isCompletedExceptionally()) +
                    ", ackOfferCount=" + acknowledgementOfferCount.get() +
                    ", ackOffer=" + acknowledgementOfferOutcome.get() +
                    ", ackCompletionAtOffer=" + (completion == null ? "none" : completion.delivered()) +
                    ", ackMailboxNow=" + (pendingCompletion == null ? "empty" : pendingCompletion.delivered()) +
                    ", gate=" + gate.snapshot().state() + "/generation:" + gate.snapshot().generation() +
                    ", activeRequestMatches=" + (active != null && active.requestId().equals(
                    request.get("requestId").getAsString())) +
                    ", ackContextFailures=" + counters[TickMetrics.Counter.ACK_CONTEXT_FAILURES.ordinal()] +
                    ", unavailableDispositions=" + counters[TickMetrics.Counter.UNAVAILABLE_DISPOSITIONS.ordinal()] +
                    ", originalConnectionCurrent=" + (invocation != null && current != null &&
                    invocation.source().connection() == current.connection());
        }

        boolean originalConnectionIsCurrent() {
            if (invocations.isEmpty()) return false;
            SmokeConversation.PlayerView current = game.inspect(player);
            return current != null && invocations.get(0).source().connection() == current.connection();
        }

        JsonObject awaitJsonFrame(String type) {
            return awaitFrame(type);
        }

        JsonObject awaitJsonFrame(String type, int occurrence) {
            return awaitFrame(type, occurrence);
        }

        void restartPeer() {
            long priorGeneration = companion.authenticatedPeerGeneration();
            companion.onClose(socket.webSocket(), 1000, "controlled peer restart").toCompletableFuture().join();
            companion.onOpen(socket.webSocket());
            PeerFacts peer = peer(binding);
            companion.onText(socket.webSocket(), challengeFrame(config, peer).toString(), true)
                    .toCompletableFuture().join();
            JsonObject hello = awaitJsonFrame("hello", 2);
            companion.onText(socket.webSocket(), welcomeFrame(config, peer,
                    hello.get("nonce").getAsString()).toString(), true).toCompletableFuture().join();
            assertTrue(companion.isReady(), "the controlled peer re-authenticated on its new connection");
            assertNotEquals(priorGeneration, companion.authenticatedPeerGeneration(),
                    "a peer restart produces a different authenticated generation");
        }

        void sendAnswer(JsonObject request, String text) {
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "dialogue.message");
            frame.addProperty("protocol", DeploymentFacts.PROTOCOL);
            frame.addProperty("messageId", UUID.randomUUID().toString());
            frame.addProperty("requestId", request.get("requestId").getAsString());
            frame.addProperty("invocationId", request.get("invocationId").getAsString());
            frame.addProperty("characterId", binding.characterId());
            JsonObject audience = new JsonObject();
            audience.addProperty("kind", "player");
            audience.addProperty("playerId", request.getAsJsonObject("replyAudience").get("playerId").getAsString());
            frame.add("replyAudience", audience);
            frame.addProperty("kind", "answer");
            frame.addProperty("text", text);
            companion.onText(socket.webSocket(), frame.toString(), true).toCompletableFuture().join();
        }

        void sendFailure(JsonObject request) {
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "dialogue.failure");
            frame.addProperty("protocol", DeploymentFacts.PROTOCOL);
            frame.addProperty("requestId", request.get("requestId").getAsString());
            frame.addProperty("invocationId", request.get("invocationId").getAsString());
            frame.addProperty("characterId", binding.characterId());
            JsonObject audience = new JsonObject();
            audience.addProperty("kind", "player");
            audience.addProperty("playerId", request.getAsJsonObject("replyAudience").get("playerId").getAsString());
            frame.add("replyAudience", audience);
            frame.addProperty("reason", "unavailable");
            companion.onText(socket.webSocket(), frame.toString(), true).toCompletableFuture().join();
        }

        @Override
        public void close() {
            try {
                socket.completeHeldSends();
            } finally {
                try {
                    conversation.close();
                } finally {
                    try {
                        companion.close();
                        BoundCompanionClientTestAccess.awaitOwnerTermination(companion, 5, TimeUnit.SECONDS);
                    } finally {
                        metrics.close();
                    }
                }
            }
        }

        static JsonObject challengeFrame(CompanionConfig config, PeerFacts peer) {
            JsonObject frame = peerFields(peer);
            frame.addProperty("type", "challenge");
            frame.addProperty("challenge", CHALLENGE);
            frame.addProperty("proof", PeerHandshake.serverChallengeProof(config, CHALLENGE, peer));
            return frame;
        }

        static JsonObject welcomeFrame(CompanionConfig config, PeerFacts peer, String nonce) {
            JsonObject frame = peerFields(peer);
            frame.addProperty("type", "welcome");
            frame.addProperty("challenge", CHALLENGE);
            frame.addProperty("nonce", nonce);
            frame.addProperty("proof", PeerHandshake.serverProof(config, CHALLENGE, nonce, peer, peer.nodeVersion()));
            return frame;
        }

        static JsonObject peerFields(PeerFacts peer) {
            JsonObject frame = new JsonObject();
            frame.addProperty("deploymentId", peer.deploymentId());
            frame.addProperty("manifestSha256", peer.manifestSha256());
            frame.addProperty("protocol", peer.protocol());
            frame.addProperty("hostVersion", peer.hostVersion());
            frame.addProperty("nodeVersion", peer.nodeVersion());
            frame.addProperty("companionVersion", peer.companionVersion());
            frame.addProperty("serverId", peer.serverId());
            frame.addProperty("worldId", peer.worldId());
            frame.addProperty("characterId", peer.characterId());
            frame.addProperty("agentId", peer.agentId());
            frame.addProperty("configurationHandshakeVersion", peer.configurationHandshakeVersion());
            frame.addProperty("configSetSha256", peer.configSetSha256());
            return frame;
        }
    }

    static final class FakePlayer {
        final String name;

        FakePlayer(String name) {
            this.name = name;
        }
    }

    /** Isolated simulation port: it models identity/context changes but never asserts live packet authentication. */
    static final class FakeAuthorityGamePort implements AbilityRequestAuthority.ReadOnlyAuthorityGamePort {
        private static final long MAX_REQUEST_AGE = TimeUnit.SECONDS.toNanos(15);
        private final FakeGamePort game;
        private final AtomicLong clock;
        private final BoundCompanionClient companion;
        private final CompatibilityGate gate;
        private final CompanionConfig config;
        private final Map<Object, Observation> admitted = new IdentityHashMap<>();
        final AtomicInteger simulatedIdentityReads = new AtomicInteger();
        final AtomicInteger currentChecks = new AtomicInteger();
        final AtomicInteger freshFactsReads = new AtomicInteger();
        volatile boolean livePacketEvidenceObserved;
        volatile boolean epochCurrent = true;
        volatile boolean omitFreshFacts;
        volatile String omitLightningFact;

        FakeAuthorityGamePort(FakeGamePort game, AtomicLong clock, BoundCompanionClient companion,
                              CompatibilityGate gate, CompanionConfig config) {
            this.game = game;
            this.clock = clock;
            this.companion = companion;
            this.gate = gate;
            this.config = config;
        }

        void revokeEpoch() { epochCurrent = false; }

        @Override public boolean isCurrent(AbilityRequestAuthority.Admission admission) {
            currentChecks.incrementAndGet();
            if (!currentMetadata(admission)) return false;
            Observation current = observe(admission);
            if (current == null || current.player() != admission.originalPlayer() ||
                    current.connection() != admission.connection() || current.world() != admission.originalWorld()) return false;
            synchronized (admitted) {
                Observation first = admitted.get(admission.requestIdentity());
                return first == null || first.sameContext(current);
            }
        }

        @Override public boolean requesterMatches(AbilityRequestAuthority.Admission admission) {
            simulatedIdentityReads.incrementAndGet();
            if (!isCurrent(admission)) return false;
            if (admission == null || admission.liveOrigin() ||
                    admission.captureProvenance() != InteractionIngress.CaptureProvenance.SYNTHETIC_HARNESS) {
                livePacketEvidenceObserved = admission != null && admission.liveOrigin();
                return false;
            }
            Observation current = observe(admission);
            if (current == null || current.player() != admission.originalPlayer() ||
                    current.connection() != admission.connection() || current.world() != admission.originalWorld() ||
                    !admission.requesterId().equals(admission.audiencePlayerId())) return false;
            synchronized (admitted) { admitted.put(admission.requestIdentity(), current); }
            return true;
        }

        @Override public AbilityPolicyResolver.WorldFacts freshFacts(
                AbilityRequestAuthority.Admission admission, AbilityValidation.Input intent) {
            if (!isCurrent(admission) || intent == null) return null;
            freshFactsReads.incrementAndGet();
            if (omitFreshFacts) return null;
            // Explicitly simulated facts exercise the private ledger only; they are never live-world proof.
            UUID requesterId = UUID.fromString(admission.requesterId());
            UUID targetId = UUID.fromString(intent.targetPlayerId());
            Object requester = game.currentPlayer(requesterId);
            Object target = targetId.equals(requesterId) ? requester : game.currentPlayer(targetId);
            SmokeConversation.PlayerView requesterView = requester == null ? null : game.inspect(requester);
            SmokeConversation.PlayerView targetView = target == null ? null : game.inspect(target);
            FakeGamePort.Position requesterPosition = requester instanceof FakePlayer player
                    ? game.positions.get(player) : null;
            FakeGamePort.Position targetPosition = target instanceof FakePlayer player
                    ? game.positions.get(player) : null;
            boolean requesterReady = requesterView != null && requesterView.validForConversation() &&
                    requester == admission.originalPlayer() && game.worldIdentity(requester) == admission.originalWorld();
            boolean targetReady = targetView != null && targetView.validForConversation() && targetPosition != null;
            boolean sameWorld = requesterReady && targetReady && game.worldIdentity(requester) == game.worldIdentity(target) &&
                    requesterView.worldId().equals(targetView.worldId());
            return new AbilityPolicyResolver.WorldFacts(sameWorld, true, requesterReady, targetReady,
                    targetReady ? targetId.toString() : null, targetReady, targetReady, targetReady, targetReady,
                    requesterReady ? requesterView.worldId() : null, targetReady ? targetView.worldId() : null,
                    requesterPosition == null ? null : new AbilityPolicyResolver.Position(requesterPosition.x(),
                            requesterPosition.y(), requesterPosition.z()),
                    targetPosition == null ? null : new AbilityPolicyResolver.Position(targetPosition.x(),
                            targetPosition.y(), targetPosition.z()),
                    new AbilityPolicyResolver.Border(-30_000_000, 30_000_000, -30_000_000, 30_000_000),
                    lightningFact("sky"), lightningFact("spawn"), lightningFact("claims"));
        }

        private Boolean lightningFact(String name) {
            if (name.equals(omitLightningFact)) return null;
            if ((name + "-denied").equals(omitLightningFact)) return false;
            return true;
        }

        private boolean currentMetadata(AbilityRequestAuthority.Admission admission) {
            if (admission == null || !epochCurrent || admission.liveOrigin() ||
                    admission.captureProvenance() != InteractionIngress.CaptureProvenance.SYNTHETIC_HARNESS ||
                    admission.configurationSnapshot() != config || admission.configurationHash() == null ||
                    !admission.configurationHash().equals(config.configSetSha256()) ||
                    admission.policy() != config.abilityPolicy() || admission.policy().revision() != admission.policyRevision() ||
                    admission.gateGeneration() != gate.snapshot().generation() ||
                    gate.snapshot().state() != GateState.READY || !companion.isAuthenticatedPeerGenerationCurrent(
                            admission.peerGeneration()) || admission.serverGeneration() != 7 ||
                    !SERVER_EPOCH.equals(admission.serverEpochId())) return false;
            long age = clock.get() - admission.capturedAtNanos();
            return age >= 0 && age < MAX_REQUEST_AGE;
        }

        private Observation observe(AbilityRequestAuthority.Admission admission) {
            final UUID id;
            try { id = UUID.fromString(admission.requesterId()); }
            catch (RuntimeException malformed) { return null; }
            Object player = game.currentPlayer(id);
            SmokeConversation.PlayerView view = player == null ? null : game.inspect(player);
            if (view == null || !view.validForConversation() || !id.equals(view.playerId())) return null;
            return new Observation(player, view.connection(), game.worldIdentity(player), view.playerId(),
                    view.worldId(), view.blockX(), view.blockY(), view.blockZ());
        }

        private record Observation(Object player, Object connection, Object world, UUID playerId,
                                   String dimension, int x, int y, int z) {
            private boolean sameContext(Observation other) {
                return other != null && player == other.player && connection == other.connection && world == other.world &&
                        playerId.equals(other.playerId) && dimension.equals(other.dimension) &&
                        x == other.x && y == other.y && z == other.z;
            }
        }
    }

    static final class FakeGamePort implements SmokeConversation.GamePort {
        private final Map<UUID, FakePlayer> playersById = new ConcurrentHashMap<>();
        private final Map<FakePlayer, UUID> idsByPlayer = new ConcurrentHashMap<>();
        private final Map<FakePlayer, Object> connections = new ConcurrentHashMap<>();
        private final Map<FakePlayer, Object> worlds = new ConcurrentHashMap<>();
        private final Map<FakePlayer, String> dimensions = new ConcurrentHashMap<>();
        private final Map<FakePlayer, Position> positions = new ConcurrentHashMap<>();
        private final Object overworldIdentity = new Object();
        private final CopyOnWriteArrayList<String> lines = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<RenderedAttempt> renderedAttempts = new CopyOnWriteArrayList<>();
        private final ConcurrentLinkedQueue<FirstReplyScheduler.PublicationResult> plannedResults =
                new ConcurrentLinkedQueue<>();
        private final AtomicReference<Runnable> nextInspectHook = new AtomicReference<>();
        private final AtomicReference<InspectionHook> nthInspectHook = new AtomicReference<>();
        private final AtomicReference<Runnable> nextSubmitHook = new AtomicReference<>();

        FakeGamePort(FakePlayer player, UUID playerId, FakePlayer secondPlayer, UUID secondPlayerId,
                     FakePlayer thirdPlayer, UUID thirdPlayerId) {
            addPlayer(player, playerId);
            addPlayer(secondPlayer, secondPlayerId);
            addPlayer(thirdPlayer, thirdPlayerId);
        }

        void addPlayer(FakePlayer player, UUID playerId) {
            playersById.put(playerId, player);
            idsByPlayer.put(player, playerId);
            connections.put(player, new Object());
            worlds.put(player, overworldIdentity);
            dimensions.put(player, "minecraft:overworld");
            positions.put(player, new Position(0, 64, 0));
        }

        void disconnect(UUID playerId) { playersById.remove(playerId); }

        void replacePlayer(UUID playerId, FakePlayer replacement) { addPlayer(replacement, playerId); }

        void changeContext(FakePlayer player, String dimension, int x, int y, int z) {
            String oldDimension = dimensions.put(player, dimension);
            if (!java.util.Objects.equals(oldDimension, dimension)) worlds.put(player, new Object());
            positions.put(player, new Position(x, y, z));
        }

        void replaceConnection(FakePlayer player) {
            if (!connections.containsKey(player)) throw new IllegalArgumentException("unknown fake player");
            connections.put(player, new Object());
        }

        @Override public SmokeConversation.PlayerView inspect(Object candidate) {
            Runnable hook = nextInspectHook.getAndSet(null);
            InspectionHook nthHook = nthInspectHook.get();
            if (nthHook != null && nthHook.remaining().decrementAndGet() == 0 &&
                    nthInspectHook.compareAndSet(nthHook, null)) {
                if (hook != null) throw new IllegalStateException("multiple inspection hooks reached the same call");
                hook = nthHook.action();
            }
            if (hook != null) hook.run();
            if (!(candidate instanceof FakePlayer player)) return null;
            UUID playerId = idsByPlayer.get(player);
            if (playerId == null) return null;
            Position position = positions.get(player);
            return new SmokeConversation.PlayerView(player, connections.get(player), playerId, player.name,
                    dimensions.get(player), true, true, true, true, true,
                    position.x(), position.y(), position.z());
        }

        @Override public Object currentPlayer(UUID id) { return playersById.get(id); }
        @Override public boolean onlineMode() { return true; }
        @Override public Object worldIdentity(Object player) {
            return player instanceof FakePlayer fake ? worlds.get(fake) : null;
        }

        @Override public FirstReplyScheduler.PublicationResult submitRendered(Object target, String renderedLine) {
            if (!(target instanceof FakePlayer) || !connections.containsKey(target) ||
                    !VanillaDialogue.isSafeRenderedLine(renderedLine)) {
                return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
            }
            FirstReplyScheduler.PublicationResult result = plannedResults.poll();
            if (result == null) result = FirstReplyScheduler.PublicationResult.SUBMITTED;
            renderedAttempts.add(new RenderedAttempt((FakePlayer) target, renderedLine, result));
            if (result == FirstReplyScheduler.PublicationResult.SUBMITTED) lines.add(renderedLine);
            Runnable hook = nextSubmitHook.getAndSet(null);
            if (hook != null) hook.run();
            return result;
        }

        @Override public InteractionIngress.SourceFacts sourceFacts(Object target) {
            return SmokeConversation.GamePort.super.sourceFacts(target);
        }

        void enqueueResult(FirstReplyScheduler.PublicationResult result) {
            plannedResults.add(result);
        }

        void onNextInspect(Runnable hook) {
            if (!nextInspectHook.compareAndSet(null, hook)) throw new IllegalStateException("inspect hook already installed");
        }

        void onNthNextInspect(int inspection, Runnable hook) {
            if (inspection < 1) throw new IllegalArgumentException("inspection must be positive");
            if (!nthInspectHook.compareAndSet(null, new InspectionHook(new AtomicInteger(inspection), hook))) {
                throw new IllegalStateException("inspection hook already installed");
            }
        }

        void onNextSubmit(Runnable hook) {
            if (!nextSubmitHook.compareAndSet(null, hook)) throw new IllegalStateException("submit hook already installed");
        }

        List<RenderedAttempt> attempts() { return List.copyOf(renderedAttempts); }
        List<String> publishedLines() { return List.copyOf(lines); }

        record InspectionHook(AtomicInteger remaining, Runnable action) {}
        record RenderedAttempt(FakePlayer target, String renderedLine, FirstReplyScheduler.PublicationResult result) {}
        record Position(int x, int y, int z) {}
    }

    static final class FakeSocket {
        private final ConcurrentHashMap<String, CopyOnWriteArrayList<SendCompletion>> sendsByType =
                new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, CompletableFuture<SendCompletion>>> waiters =
                new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<SendCompletion> heldSends = new CopyOnWriteArrayList<>();
        private final AtomicReference<WebSocket> socketRef = new AtomicReference<>();
        private final CountDownLatch acknowledgementPathResolved;
        private volatile boolean holdProgressSend;
        private volatile boolean holdAcknowledgementSend;

        FakeSocket(CountDownLatch acknowledgementPathResolved) {
            this.acknowledgementPathResolved = acknowledgementPathResolved;
        }

        SendCompletion awaitSend(String type) throws InterruptedException {
            return awaitSend(type, 1);
        }

        SendCompletion awaitSend(String type, int occurrence) throws InterruptedException {
            CopyOnWriteArrayList<SendCompletion> sends = sendsByType.computeIfAbsent(
                    type, ignored -> new CopyOnWriteArrayList<>());
            if (sends.size() >= occurrence) return sends.get(occurrence - 1);
            CompletableFuture<SendCompletion> observed = waiters.computeIfAbsent(type,
                    ignored -> new ConcurrentHashMap<>()).computeIfAbsent(occurrence,
                    ignored -> new CompletableFuture<>());
            if (sends.size() >= occurrence) observed.complete(sends.get(occurrence - 1));
            try {
                return observed.get(3, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                String requestIds = sends.stream().map(send -> send.frame().has("requestId")
                        ? send.frame().get("requestId").getAsString() : "no-request-id").toList().toString();
                throw new AssertionError("WebSocket sendText(" + type + ") occurrence " + occurrence +
                        " was not observed within three seconds; observed " + sends.size() + " request IDs " + requestIds,
                        timeout);
            } catch (ExecutionException failure) {
                throw new AssertionError("WebSocket send observation failed for " + type, failure.getCause());
            }
        }

        String describe(String type) {
            List<SendCompletion> sends = sendsByType.get(type);
            if (sends == null || sends.isEmpty()) return "not observed";
            SendCompletion send = sends.get(0);
            return send == null ? "observation unavailable" : "futureDone=" + send.completion().isDone();
        }

        int sendCount(String type) {
            List<SendCompletion> sends = sendsByType.get(type);
            return sends == null ? 0 : sends.size();
        }

        SendCompletion firstSend(String type) {
            List<SendCompletion> sends = sendsByType.get(type);
            return sends == null || sends.isEmpty() ? null : sends.get(0);
        }

        void complete(SendCompletion send) {
            send.completion().complete(webSocket());
        }

        void fail(SendCompletion send) {
            send.completion().completeExceptionally(new IllegalStateException("synthetic WebSocket send failure"));
        }

        void holdAcknowledgementSends(boolean hold) {
            holdAcknowledgementSend = hold;
        }

        void completeHeldSends() {
            for (SendCompletion send : heldSends) complete(send);
        }

        WebSocket webSocket() {
            WebSocket existing = socketRef.get();
            if (existing != null) return existing;
            WebSocket created = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                     new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                         if ("sendText".equals(method.getName())) {
                              String text = args[0].toString();
                              String type = JsonParser.parseString(text).getAsJsonObject().get("type").getAsString();
                              CompletableFuture<WebSocket> completion = new CompletableFuture<>();
                              SendCompletion send = new SendCompletion(type, text, completion, Thread.currentThread());
                              CopyOnWriteArrayList<SendCompletion> sends = sendsByType.computeIfAbsent(
                                      type, ignored -> new CopyOnWriteArrayList<>());
                              int occurrence = sends.size() + 1;
                              sends.add(send);
                              if ("dialogue.ack".equals(type)) acknowledgementPathResolved.countDown();
                             ConcurrentHashMap<Integer, CompletableFuture<SendCompletion>> typeWaiters =
                                     waiters.get(type);
                             CompletableFuture<SendCompletion> observed = typeWaiters == null
                                     ? null : typeWaiters.remove(occurrence);
                             if (observed != null) observed.complete(send);
                            if ((holdProgressSend && "dialogue.progress".equals(type)) ||
                                    (holdAcknowledgementSend && "dialogue.ack".equals(type))) {
                                heldSends.add(send);
                            } else {
                                completion.complete((WebSocket) proxy);
                            }
                            return completion;
                        }
                        if ("sendClose".equals(method.getName())) return CompletableFuture.completedFuture(proxy);
                        if ("request".equals(method.getName())) return null;
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                    });
            socketRef.compareAndSet(null, created);
            return socketRef.get();
        }

        record SendCompletion(String type, String text, CompletableFuture<WebSocket> completion,
                              Thread senderThread) {
            JsonObject frame() {
                return JsonParser.parseString(text).getAsJsonObject();
            }
        }
    }
}
