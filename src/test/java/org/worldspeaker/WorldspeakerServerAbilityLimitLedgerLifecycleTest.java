package org.worldspeaker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.bootstrap.AbilityLimitLedger;
import org.worldspeaker.bootstrap.AbilityLimitLedgerProcessTestAccess;
import org.worldspeaker.compat.CompatibilityGate;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.InteractionPolicy;
import org.worldspeaker.runtime.ThreadBoundary;
import org.worldspeaker.runtime.ThreadBoundaryTestAccess;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldspeakerServerAbilityLimitLedgerLifecycleTest {
    @TempDir Path directory;

    @Test
    void actualProcessLedgerRetainsUnknownEvidenceAcrossSameConfiguredServiceReplacement() throws Exception {
        ThreadBoundaryTestAccess.clearForTests();
        Object oldServerIdentity = new Object();
        Object replacementServerIdentity = new Object();
        ServerServices oldServices = WorldspeakerServer.newServerServices(
                directory.resolve("worldspeaker.properties"), oldServerIdentity, ignored -> {});
        ServerServices replacementServices = null;
        Consumer<String> retiredConnectionCallback = WorldspeakerServer.privateDiagnosticCallback(oldServices);
        boolean oldActivated = false;
        boolean replacementActivated = false;

        try {
            ThreadBoundary.beginServerEpoch(oldServices.threadBoundaryToken());
            WorldspeakerServer.activateServices(oldServices);
            oldActivated = true;
            ConfiguredBinding oldBinding = new ConfiguredBinding(
                    "server-process-ledger", "world-process-ledger", "character-process-ledger", "agent-old");
            assertTrue(oldServices.installConfiguration(config(oldBinding, "Archivist"),
                    new CompatibilityGate(oldBinding)));

            AbilityLimitLedger ledger = oldServices.abilityLimitLedgerFor(oldServerIdentity);
            assertNotNull(ledger, "the active service receives WorldspeakerServer's actual private static ledger");
            assertTrue(AbilityLimitLedgerProcessTestAccess.bind(ledger,
                    oldBinding.serverId(), oldBinding.worldId(), oldBinding.characterId()));
            var unknown = AbilityLimitLedgerProcessTestAccess.startUnknownLightning(ledger,
                    oldBinding.serverId(), oldBinding.worldId(), oldBinding.characterId(),
                    oldServices.generation(), 1);
            unknown.seedCompletedObserve(oldServices.generation(), 1);
            AbilityLimitLedger.Snapshot uncertain = AbilityLimitLedgerProcessTestAccess.snapshot(ledger);
            assertEquals(1, uncertain.inFlight());
            assertEquals(2, uncertain.unknownPins());
            assertEquals(5, uncertain.chargedNodes(),
                    "confirmed Observe requester/character charges share the process ledger too");

            assertTrue(WorldspeakerServer.stopServices(oldServices));
            oldActivated = false;
            retiredConnectionCallback.accept("WS-COMPAT-RECONNECT-EXHAUSTED");

            ConfiguredBinding replacementBinding = new ConfiguredBinding(
                    "server-process-ledger", "world-process-ledger", "character-process-ledger", "agent-new");
            replacementServices = WorldspeakerServer.newServerServices(
                    directory.resolve("worldspeaker.properties"), replacementServerIdentity, ignored -> {});
            ThreadBoundary.beginServerEpoch(replacementServices.threadBoundaryToken());
            WorldspeakerServer.activateServices(replacementServices);
            replacementActivated = true;
            assertNotEquals(oldServices.generation(), replacementServices.generation());
            assertTrue(replacementServices.installConfiguration(config(replacementBinding, "Archivist II"),
                    new CompatibilityGate(replacementBinding)));

            AbilityLimitLedger replacementLedger = replacementServices.abilityLimitLedgerFor(replacementServerIdentity);
            assertSame(ledger, replacementLedger,
                    "the production service-construction path injects the same static ledger into the new epoch");
            assertTrue(AbilityLimitLedgerProcessTestAccess.bind(ledger,
                    replacementBinding.serverId(), replacementBinding.worldId(), replacementBinding.characterId()),
                    "new server object, service generation, agent ID, and presentation do not change stable ledger binding");
            assertEquals(uncertain, AbilityLimitLedgerProcessTestAccess.snapshot(replacementLedger),
                    "service/configuration replacement does not flush an unknown in-flight record or its pins");
            assertEquals(0, replacementServices.metrics().countersSnapshot()[
                    org.worldspeaker.runtime.TickMetrics.Counter.RECONNECT_EXHAUSTED.ordinal()],
                    "the retired connection callback cannot write into the replacement service");

            Thread.sleep(30_250);
            AbilityLimitLedger.Snapshot afterNominalCooldown =
                    AbilityLimitLedgerProcessTestAccess.snapshot(replacementLedger);
            assertEquals(1, afterNominalCooldown.inFlight(),
                    "unproved asynchronous work remains in flight beyond the 30-second rate interval");
            assertEquals(2, afterNominalCooldown.unknownPins(),
                    "unknown requester and target pins survive nominal cooldown and replacement");
            assertEquals(5, afterNominalCooldown.chargedNodes(),
                    "confirmed requester/character charges survive the agent and config revision replacement");

            var sameRequesterRate = unknown.attemptObserve(true, replacementServices.generation(), 2, 63_150);
            assertFalse(sameRequesterRate.result().reserved());
            assertEquals(AbilityLimitLedger.Reason.REQUESTER_RATE, sameRequesterRate.result().reason(),
                    "a new service/config snapshot keeps the old selected requester interval");
            assertEquals(afterNominalCooldown, AbilityLimitLedgerProcessTestAccess.snapshot(replacementLedger));
            var sameCharacterRate = unknown.attemptObserve(false, replacementServices.generation(), 2, 63_160);
            assertFalse(sameCharacterRate.result().reserved());
            assertEquals(AbilityLimitLedger.Reason.CHARACTER_RATE, sameCharacterRate.result().reason(),
                    "a different requester remains subject to the same character interval");
            assertEquals(afterNominalCooldown, AbilityLimitLedgerProcessTestAccess.snapshot(replacementLedger));

            for (var conflict : new AbilityLimitLedgerProcessTestAccess.SharedResource[] {
                    AbilityLimitLedgerProcessTestAccess.SharedResource.REQUESTER,
                    AbilityLimitLedgerProcessTestAccess.SharedResource.TARGET,
                    AbilityLimitLedgerProcessTestAccess.SharedResource.CHARACTER}) {
                var denied = unknown.attempt(conflict, replacementServices.generation(), 2,
                        63_100 + conflict.ordinal() * 10);
                assertFalse(denied.result().reserved(), conflict + " stays blocked by retained process evidence");
                assertEquals(AbilityLimitLedger.Reason.CONCURRENCY_LIMITED, denied.result().reason());
                assertEquals(afterNominalCooldown, AbilityLimitLedgerProcessTestAccess.snapshot(replacementLedger),
                        conflict + " rejection leaves the process ledger snapshot unchanged");
            }

            assertEquals(AbilityLimitLedger.SettlementResult.SETTLED, unknown.proveCompletion(),
                    "only explicit trusted completion evidence releases old in-flight capacity");
            AbilityLimitLedger.Snapshot completedUnknown =
                    AbilityLimitLedgerProcessTestAccess.snapshot(replacementLedger);
            assertEquals(0, completedUnknown.inFlight());
            assertEquals(2, completedUnknown.unknownPins(),
                    "completion resolves capacity liveness, not the unknown requester/target outcomes");
            assertEquals(5, completedUnknown.chargedNodes());

            var unrelated = unknown.attempt(AbilityLimitLedgerProcessTestAccess.SharedResource.CHARACTER,
                    replacementServices.generation(), 2, 63_200);
            assertTrue(unrelated.result().reserved(), "an unrelated pair may proceed after trusted task completion");
            assertTrue(unrelated.release(), "the later pre-start reservation releases without a charge");
            AbilityLimitLedger.Snapshot beforeMismatch =
                    AbilityLimitLedgerProcessTestAccess.snapshot(replacementLedger);

            assertFalse(AbilityLimitLedgerProcessTestAccess.bind(replacementLedger,
                    "server-changed", replacementBinding.worldId(), replacementBinding.characterId()),
                    "a changed configured server binding closes admission");
            AbilityLimitLedger.Snapshot closed = AbilityLimitLedgerProcessTestAccess.snapshot(replacementLedger);
            assertEquals(new AbilityLimitLedger.Snapshot(beforeMismatch.bound(), true, beforeMismatch.clockFault(),
                    beforeMismatch.liveHolds(), beforeMismatch.inFlight(), beforeMismatch.observeRequesterNodes(),
                    beforeMismatch.lightningRequesterNodes(), beforeMismatch.lightningTargetNodes(),
                    beforeMismatch.identityRecords(), beforeMismatch.invocationRecords(), beforeMismatch.metadataBytes(),
                    beforeMismatch.chargedNodes(), beforeMismatch.unknownPins()), closed,
                    "binding closure changes no retained records, charges, in-flight state, or pins");
            var afterClose = unknown.attemptWithBinding("server-process-ledger", "world-process-ledger",
                    "character-process-ledger", replacementServices.generation(), 2, 63_300);
            assertFalse(afterClose.result().reserved());
            assertEquals(AbilityLimitLedger.Reason.BINDING_CLOSED, afterClose.result().reason());
            assertEquals(closed, AbilityLimitLedgerProcessTestAccess.snapshot(replacementLedger),
                    "closed-binding rejection preserves the evidence snapshot");
        } finally {
            if (replacementActivated && replacementServices != null) {
                WorldspeakerServer.stopServices(replacementServices);
            }
            if (oldActivated) WorldspeakerServer.stopServices(oldServices);
            oldServices.retire();
            if (replacementServices != null) replacementServices.retire();
            ThreadBoundaryTestAccess.clearForTests();
        }
    }

    private static CompanionConfig config(ConfiguredBinding binding, String displayName) {
        return new CompanionConfig(URI.create("ws://127.0.0.1:1"), new byte[32], binding,
                "Hello {player}", displayName, InteractionPolicy.denyAll());
    }
}
