package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.ability.AbilityPolicyResolver;
import org.worldspeaker.ability.AbilityValidation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MinecraftAbilityFactsTest {
    private static final UUID REQUESTER = UUID.fromString("00000000-0000-4000-8000-000000000041");
    private static final UUID TARGET = UUID.fromString("00000000-0000-4000-8000-000000000042");
    private static final String INVOCATION = "00000000-0000-4000-8000-000000000043";

    @Test
    void resolvesOnlyTheRequestedUuidAndCapturesFractionalCurrentFacts() {
        Object server = new Object();
        Object world = new Object();
        Object requester = new Object();
        Object target = new Object();
        Object requesterConnection = new Object();
        Object targetConnection = new Object();
        FakeGamePort port = new FakeGamePort();
        port.players.put(REQUESTER, player(requester, requesterConnection, REQUESTER, server, world,
                "minecraft:overworld", 1, new AbilityPolicyResolver.Position(0.25, 64.5, -1.75), true));
        port.players.put(TARGET, player(target, targetConnection, TARGET, server, world,
                "minecraft:overworld", 2, new AbilityPolicyResolver.Position(3.5, 64.0, -1.75), true));
        InvocationCapture packet = packet(REQUESTER, requesterConnection, 1);

        AbilityPolicyResolver.WorldFacts facts = MinecraftAbilityFacts.capture(port, server,
                new MinecraftAbilityFacts.RequestContext(REQUESTER.toString(), requester, requesterConnection,
                        world, packet, true), new AbilityValidation.ObserveInput(TARGET.toString()), () -> true);

        assertEquals(List.of(REQUESTER, TARGET), port.lookups);
        assertEquals(Boolean.TRUE, facts.currentWorldMatches());
        assertEquals(Boolean.TRUE, facts.requestLive());
        assertEquals(Boolean.TRUE, facts.requesterConnected());
        assertEquals(Boolean.TRUE, facts.targetResolved());
        assertEquals(TARGET.toString(), facts.targetPlayerId());
        assertEquals(Boolean.TRUE, facts.targetConnected());
        assertEquals(Boolean.TRUE, facts.targetAlive());
        assertEquals(Boolean.TRUE, facts.targetNotSpectating());
        assertEquals(Boolean.TRUE, facts.targetChunkLoadedAndTicking());
        assertEquals(new AbilityPolicyResolver.Position(0.25, 64.5, -1.75), facts.requesterFeet());
        assertEquals(new AbilityPolicyResolver.Position(3.5, 64.0, -1.75), facts.targetFeet());
        assertEquals(new AbilityPolicyResolver.Border(-100.5, 100.5, -80.25, 80.25), facts.worldBorder());
        assertNull(facts.lightningSkyAccess());
        assertNull(facts.protectedSpawnClear());
        assertNull(facts.protectedClaimsClear());
    }

    @Test
    void selfTargetReusesItsSingleRosterLookupAndUnknownOrUnloadedTargetStaysDenied() {
        Object server = new Object();
        Object world = new Object();
        Object requester = new Object();
        Object connection = new Object();
        FakeGamePort selfPort = new FakeGamePort();
        selfPort.players.put(REQUESTER, player(requester, connection, REQUESTER, server, world,
                "minecraft:overworld", 1, new AbilityPolicyResolver.Position(0, 64, 0), true));
        AbilityPolicyResolver.WorldFacts selfFacts = MinecraftAbilityFacts.capture(selfPort, server,
                new MinecraftAbilityFacts.RequestContext(REQUESTER.toString(), requester, connection,
                        world, packet(REQUESTER, connection, 1), true),
                new AbilityValidation.ObserveInput(REQUESTER.toString()), () -> true);
        assertEquals(List.of(REQUESTER), selfPort.lookups);
        assertEquals(Boolean.TRUE, selfFacts.targetResolved());
        assertEquals(Boolean.TRUE, selfFacts.targetChunkLoadedAndTicking());

        FakeGamePort missingPort = new FakeGamePort();
        missingPort.players.put(REQUESTER, player(requester, connection, REQUESTER, server, world,
                "minecraft:overworld", 1, new AbilityPolicyResolver.Position(0, 64, 0), true));
        AbilityPolicyResolver.WorldFacts missing = MinecraftAbilityFacts.capture(missingPort, server,
                new MinecraftAbilityFacts.RequestContext(REQUESTER.toString(), requester, connection,
                        world, packet(REQUESTER, connection, 1), true),
                new AbilityValidation.ObserveInput(TARGET.toString()), () -> true);
        assertEquals(List.of(REQUESTER, TARGET), missingPort.lookups);
        assertEquals(Boolean.FALSE, missing.targetResolved());
        assertNull(missing.targetConnected());
        assertNull(missing.targetFeet());

        FakeGamePort unloadedPort = new FakeGamePort();
        unloadedPort.players.put(REQUESTER, player(requester, connection, REQUESTER, server, world,
                "minecraft:overworld", 1, new AbilityPolicyResolver.Position(0, 64, 0), true));
        unloadedPort.players.put(TARGET, player(new Object(), new Object(), TARGET, server, world,
                "minecraft:overworld", 2, new AbilityPolicyResolver.Position(2, 64, 0), false));
        AbilityPolicyResolver.WorldFacts unloaded = MinecraftAbilityFacts.capture(unloadedPort, server,
                new MinecraftAbilityFacts.RequestContext(REQUESTER.toString(), requester, connection,
                        world, packet(REQUESTER, connection, 1), true),
                new AbilityValidation.ObserveInput(TARGET.toString()), () -> true);
        assertEquals(Boolean.FALSE, unloaded.targetChunkLoadedAndTicking());
        assertEquals(List.of(REQUESTER, TARGET), unloadedPort.lookups,
                "the adapter performs one fixed target lookup and never searches for another entity");
    }

    @Test
    void entityReplacementAndSameDimensionDifferentWorldDoNotBecomeCurrentWorldFacts() {
        Object server = new Object();
        Object oldWorld = new Object();
        Object currentWorld = new Object();
        Object originalRequester = new Object();
        Object connection = new Object();
        FakeGamePort port = new FakeGamePort();
        port.players.put(REQUESTER, player(new Object(), connection, REQUESTER, server, currentWorld,
                "minecraft:overworld", 1, new AbilityPolicyResolver.Position(0, 64, 0), true));
        port.players.put(TARGET, player(new Object(), new Object(), TARGET, server, currentWorld,
                "minecraft:overworld", 2, new AbilityPolicyResolver.Position(1, 64, 0), true));

        AbilityPolicyResolver.WorldFacts replaced = MinecraftAbilityFacts.capture(port, server,
                new MinecraftAbilityFacts.RequestContext(REQUESTER.toString(), originalRequester, connection,
                        oldWorld, packet(REQUESTER, connection, 1), true),
                new AbilityValidation.ObserveInput(TARGET.toString()), () -> true);
        assertEquals(Boolean.FALSE, replaced.requesterConnected());
        assertEquals(Boolean.FALSE, replaced.currentWorldMatches());
        assertNull(replaced.lightningSkyAccess());
    }

    private static MinecraftAbilityFacts.PlayerState player(Object player, Object connection, UUID id,
                                                              Object server, Object world, String dimension,
                                                              long generation, AbilityPolicyResolver.Position feet,
                                                              boolean loadedAndTicking) {
        return new MinecraftAbilityFacts.PlayerState(player, connection, id, id, server, world, dimension,
                true, true, true, true, true, false, generation, feet,
                new AbilityPolicyResolver.Border(-100.5, 100.5, -80.25, 80.25), loadedAndTicking, true);
    }

    private static InvocationCapture packet(UUID id, Object connection, long generation) {
        UUID invocation = UUID.fromString(INVOCATION);
        UUID event = UUID.randomUUID();
        return new InvocationCapture(invocation, event, 123, 1_800_000_000_123L,
                connection, generation, InvocationCapture.Source.SIGNED_CHAT);
    }

    private static final class FakeGamePort implements SmokeConversation.GamePort {
        private final Map<UUID, MinecraftAbilityFacts.PlayerState> players = new HashMap<>();
        private final List<UUID> lookups = new ArrayList<>();

        @Override public SmokeConversation.PlayerView inspect(Object player) { return null; }
        @Override public Object currentPlayer(UUID playerId) {
            lookups.add(playerId);
            MinecraftAbilityFacts.PlayerState state = players.get(playerId);
            return state == null ? null : state.player();
        }
        @Override public boolean onlineMode() { return true; }
        @Override public FirstReplyScheduler.PublicationResult submitRendered(Object player, String renderedLine) {
            return FirstReplyScheduler.PublicationResult.DEFINITELY_UNSENT;
        }
        @Override public MinecraftAbilityFacts.PlayerState inspectAbilityPlayer(Object player) {
            return players.values().stream()
                    .filter(state -> state.player() == player).findFirst().orElse(null);
        }
    }
}
