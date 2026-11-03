package org.worldspeaker.bootstrap;

import org.worldspeaker.ability.AbilityPolicyResolver;
import org.worldspeaker.ability.AbilityValidation;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Internal, bounded Minecraft facts adapter. It never loads chunks or searches for alternate entities. */
public final class MinecraftAbilityFacts {
    private MinecraftAbilityFacts() {}

    static boolean requesterMatches(SmokeConversation.GamePort port, Object server, RequestContext request) {
        if (port == null || server == null || request == null || request.packetCapture() == null) return false;
        try {
            UUID requesterId = UUID.fromString(request.requesterId());
            Object current = port.currentPlayer(requesterId);
            PlayerState requester = current == null ? null : port.inspectAbilityPlayer(current);
            return connected(requester, server, requesterId) && current == request.originalPlayer() &&
                    requester.connection() == request.connection() && requester.world() == request.originalWorld() &&
                    request.packetCapture().belongsTo(request.connection(), requester.connectionGeneration());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    static AbilityPolicyResolver.WorldFacts capture(SmokeConversation.GamePort port, Object server,
                                                    RequestContext request, AbilityValidation.Input intent,
                                                    BooleanSupplier currentRequest) {
        if (port == null || server == null || request == null || intent == null || currentRequest == null) return null;
        if (!currentRequest.getAsBoolean()) return unresolved();

        final UUID requesterId;
        final UUID targetId;
        try {
            requesterId = UUID.fromString(request.requesterId());
            targetId = UUID.fromString(intent.targetPlayerId());
        } catch (RuntimeException malformed) {
            return unresolved();
        }

        // Each lookup is by UUID only. A self-target reuses the requester's one roster lookup.
        Object requesterObject = port.currentPlayer(requesterId);
        Object targetObject = targetId.equals(requesterId) ? requesterObject : port.currentPlayer(targetId);
        PlayerState requester = requesterObject == null ? null : port.inspectAbilityPlayer(requesterObject);
        PlayerState target = targetObject == null ? null : port.inspectAbilityPlayer(targetObject);

        boolean requestLive = request.livePacket() && request.packetCapture() != null &&
                request.packetCapture().belongsTo(request.connection(), request.packetCapture().connectionGeneration()) &&
                currentRequest.getAsBoolean();
        boolean requesterConnected = requester != null && connected(requester, server, requesterId) &&
                requesterObject == request.originalPlayer() && requester.connection() == request.connection() &&
                request.packetCapture() != null && request.packetCapture().belongsTo(request.connection(),
                        requester.connectionGeneration());
        boolean targetResolved = target != null && targetId.equals(target.playerId()) &&
                targetId.equals(target.profileId());
        boolean targetConnected = targetResolved && connected(target, server, targetId);
        boolean sameWorld = requester != null && target != null && requesterConnected && targetConnected &&
                requester.world() == request.originalWorld() && requester.worldIsCurrent() &&
                target.world() == requester.world() && target.worldIsCurrent() &&
                requester.dimension() != null && requester.dimension().equals(target.dimension());

        AbilityPolicyResolver.Border border = requester == null ? null : requester.worldBorder();
        return new AbilityPolicyResolver.WorldFacts(
                requester == null ? null : sameWorld,
                requestLive,
                requester == null ? null : requesterConnected,
                targetObject == null ? false : targetResolved,
                targetResolved ? target.playerId().toString() : null,
                target == null ? null : targetConnected,
                target == null ? null : target.alive(),
                target == null ? null : !target.spectator(),
                target == null ? null : target.chunkLoadedAndTicking(),
                requester == null ? null : requester.dimension(),
                target == null ? null : target.dimension(),
                requester == null ? null : requester.feet(),
                target == null ? null : target.feet(),
                border,
                null,
                null,
                null);
    }

    private static boolean connected(PlayerState player, Object server, UUID expectedId) {
        return player != null && player.onlineMode() && player.playerId() != null &&
                expectedId.equals(player.playerId()) && expectedId.equals(player.profileId()) &&
                player.server() == server && player.currentRosterEntity() && player.handlerMatchesPlayer() &&
                player.connectionOpen() && player.world() != null && player.worldIsCurrent();
    }

    private static AbilityPolicyResolver.WorldFacts unresolved() {
        return new AbilityPolicyResolver.WorldFacts(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /** Private request facts copied from its owner; no client or model argument can construct this in production. */
    record RequestContext(String requesterId, Object originalPlayer, Object connection, Object originalWorld,
                          InvocationCapture packetCapture, boolean livePacket) {}

    /** GamePort value seam: tests may substitute game objects and observations, not request authority. */
    public record PlayerState(Object player, Object connection, UUID playerId, UUID profileId, Object server,
                              Object world, String dimension, boolean onlineMode, boolean currentRosterEntity,
                              boolean handlerMatchesPlayer, boolean connectionOpen, boolean alive,
                              boolean spectator, long connectionGeneration,
                              AbilityPolicyResolver.Position feet,
                              AbilityPolicyResolver.Border worldBorder,
                              Boolean chunkLoadedAndTicking, boolean worldIsCurrent) {}
}
