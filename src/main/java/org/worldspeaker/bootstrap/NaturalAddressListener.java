package org.worldspeaker.bootstrap;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.message.FilterMask;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.network.ServerPlayerEntity;
import org.worldspeaker.compat.CompanionConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Non-canceling observer for accepted signed player chat. */
public final class NaturalAddressListener {
    private static boolean registered;

    private NaturalAddressListener() {}

    /** Register one void observer; the original vanilla broadcast remains untouched. */
    public static synchronized void register(Access access) {
        if (registered) return;
        ServerMessageEvents.CHAT_MESSAGE.register(createCallback(access));
        registered = true;
    }

    /** The exact callback registered in production, also used by offline callback tests. */
    public static ServerMessageEvents.ChatMessage createCallback(Access access) {
        if (access == null) throw new IllegalArgumentException("natural-address access is required");
        return (message, sender, params) -> {
            InteractionIngress.Captured callbackCapture = access.capture(message, InteractionIngress.Origin.NATURAL_ADDRESS);
            long capturedAtNanos = callbackCapture.capturedAtNanos();
            try {
            if (!access.isServerThread()) return;
            Object epoch = access.activeEpoch();
            if (epoch == null || !access.isCurrentEpoch(epoch)) return;

            if (!access.isOrdinaryChat(params) || !access.hasSignature(message)) return;
            UUID signedSenderId = access.signedSenderId(message);
            if (signedSenderId == null) return;
            if (!access.isPassThrough(message)) return;

            Object server = access.currentServer(epoch, sender);
            if (server == null || !access.isCurrentEpoch(epoch)) return;

            if (callbackCapture.handoffCapacityRejected()) {
                InteractionIngress.SourceFacts overflowSource = access.resolveSender(epoch, server, sender);
                if (overflowSource == null || !overflowSource.authenticatedPlayer() ||
                        !signedSenderId.equals(overflowSource.playerId())) return;
                InteractionIngress.Port<ServerPlayerEntity> overflowIngress = access.ingress(epoch, server);
                if (overflowIngress != null && access.isCurrentEpoch(epoch)) {
                    InteractionIngress.execute(callbackCapture, sender, null, InteractionIngress.Route.ASK,
                            null, overflowIngress);
                }
                return;
            }

            CompanionConfig config = access.config(epoch);
            if (config == null || !access.isCurrentEpoch(epoch)) return;
            InteractionIngress.SourceFacts source = access.resolveSender(epoch, server, sender);
            if (source == null || !source.authenticatedPlayer() || !signedSenderId.equals(source.playerId())) return;

            List<NaturalAddressParser.OnlinePlayer> onlinePlayers;
            try {
                onlinePlayers = readBoundedRoster(access, epoch, server, source.playerId());
            } catch (RuntimeException staleRoster) {
                access.invalidateFollowup(epoch, source.playerId());
                return;
            }
            if (onlinePlayers == null || !access.isCurrentEpoch(epoch)) {
                access.invalidateFollowup(epoch, source.playerId());
                return;
            }

            String originalText = access.signedContent(message);
            if (originalText == null || originalText.length() > NaturalAddressParser.MAX_INPUT_LENGTH) {
                access.invalidateFollowup(epoch, source.playerId());
                return;
            }
            if (NaturalAddressParser.hasOtherOnlinePlayerPrefix(originalText, source.playerId(), onlinePlayers)) {
                access.invalidateFollowup(epoch, source.playerId());
                return;
            }

            List<String> names = new ArrayList<>(1 + config.characterAliases().size());
            names.add(config.characterDisplayName());
            names.addAll(config.characterAliases());
            NaturalAddressParser.Result result = NaturalAddressParser.parse(originalText, names);
            if (result.disposition() == NaturalAddressParser.Disposition.AMBIENT) {
                if (NaturalAddressParser.isContinuationPhrase(originalText) && access.isCurrentEpoch(epoch)) {
                    InteractionIngress.Port<ServerPlayerEntity> followupIngress = access.ingress(epoch, server);
                    if (followupIngress != null && access.isCurrentEpoch(epoch)) {
                        long beforeIngressNanos = access.monotonicNanos();
                        FollowupWindow.Anchor anchor = access.followupAnchor(epoch, server, source,
                                capturedAtNanos, beforeIngressNanos);
                        if (anchor != null) {
                            InteractionIngress.Captured followupCaptured = callbackCapture.asFollowup(
                                    anchor.conversationId(), anchor.replyToMessageId());
                            InteractionIngress.execute(followupCaptured, sender, originalText, InteractionIngress.Route.ASK,
                                    originalText, followupIngress);
                            return;
                        }
                    }
                }
                access.invalidateFollowup(epoch, source.playerId());
                return;
            }
            if (!access.isCurrentEpoch(epoch)) return;

            InteractionIngress.Port<ServerPlayerEntity> ingress = access.ingress(epoch, server);
            if (ingress == null || !access.isCurrentEpoch(epoch)) return;
            InteractionIngress.Route route = result.disposition() == NaturalAddressParser.Disposition.INVALID
                    ? InteractionIngress.Route.INVALID : InteractionIngress.Route.ASK;
            InteractionIngress.execute(callbackCapture, sender, originalText, route, originalText, ingress);
            } finally {
                if (callbackCapture.packetCapture() != null) callbackCapture.packetCapture().releaseHandoff();
            }
        };
    }

    /** Reads and validates the complete roster without allocating or scanning beyond the fixed cap. */
    private static List<NaturalAddressParser.OnlinePlayer> readBoundedRoster(Access access, Object epoch,
                                                                             Object server, UUID senderId) {
        PlayerRoster roster = access.onlineRoster(epoch, server);
        if (roster == null) return null;

        int rosterSize = roster.size();
        if (rosterSize < 0 || rosterSize > NaturalAddressParser.MAX_ONLINE_PLAYERS) return null;

        List<NaturalAddressParser.OnlinePlayer> onlinePlayers = new ArrayList<>(rosterSize);
        boolean senderPresent = false;
        for (int index = 0; index < rosterSize; index++) {
            PlayerIdentity identity = roster.playerAt(index);
            if (identity == null) return null;

            UUID playerId = identity.playerId();
            String profileName = identity.profileName();
            if (playerId == null || !NaturalAddressParser.isProfileName(profileName)) return null;
            if (playerId.equals(senderId)) {
                if (senderPresent) return null;
                senderPresent = true;
            }
            onlinePlayers.add(new NaturalAddressParser.OnlinePlayer(playerId, profileName));
        }
        return senderPresent ? onlinePlayers : null;
    }

    public interface Access {
        /** This clock read is the callback's first action and is retained through shared ingress. */
        long monotonicNanos();

        /** Test adapters are explicitly synthetic; production overrides this with signed-message receipt metadata. */
        default InteractionIngress.Captured capture(SignedMessage message, InteractionIngress.Origin origin) {
            return InteractionIngress.Captured.capture(this::monotonicNanos, this::wallTimeMillis, origin);
        }

        /** Read immediately after local monotonic t0, before source or roster lookup. */
        default long wallTimeMillis() { return System.currentTimeMillis(); }

        boolean isServerThread();

        Object activeEpoch();

        boolean isCurrentEpoch(Object epoch);

        /** Called only after thread and epoch guards; implementations verify sender/server ownership. */
        Object currentServer(Object epoch, ServerPlayerEntity sender);

        CompanionConfig config(Object epoch);

        InteractionIngress.SourceFacts resolveSender(Object epoch, Object server, ServerPlayerEntity sender);

        PlayerRoster onlineRoster(Object epoch, Object server);

        InteractionIngress.Port<ServerPlayerEntity> ingress(Object epoch, Object server);

        /** Re-resolves the complete current conversation context and grant for this bounded input. */
        default boolean followupOpen(Object epoch, Object server, InteractionIngress.SourceFacts source,
                                     long capturedAtNanos, long beforeIngressNanos) { return false; }

        /** Returns the checked answer anchor; implementations must repeat all live-context checks. */
        default FollowupWindow.Anchor followupAnchor(Object epoch, Object server, InteractionIngress.SourceFacts source,
                                                     long capturedAtNanos, long beforeIngressNanos) {
            return null;
        }

        /** Closes only this authenticated UUID's local recognition/candidates. */
        default void invalidateFollowup(Object epoch, UUID playerId) {}

        /** Read-only pinned Fabric/Minecraft envelope checks; tests may inject synthetic API facts. */
        default boolean isOrdinaryChat(MessageType.Parameters params) {
            return params != null && params.type() != null && params.type().matchesKey(MessageType.CHAT);
        }

        default boolean hasSignature(SignedMessage message) {
            return message != null && message.hasSignature();
        }

        default UUID signedSenderId(SignedMessage message) {
            return message == null ? null : message.getSender();
        }

        default boolean isPassThrough(SignedMessage message) {
            if (message == null) return false;
            FilterMask mask = message.filterMask();
            return mask != null && mask.isPassThrough();
        }

        default String signedContent(SignedMessage message) {
            return message == null ? null : message.getSignedContent();
        }
    }

    /** Bounded view of the live player-list; size must be checked before indexed reads/copying. */
    public interface PlayerRoster {
        int size();
        PlayerIdentity playerAt(int index);
    }

    /** Identity data extracted from one live roster entry. */
    public static class PlayerIdentity {
        private final UUID playerId;
        private final String profileName;

        public PlayerIdentity(UUID playerId, String profileName) {
            this.playerId = playerId;
            this.profileName = profileName;
        }

        public UUID playerId() { return playerId; }

        public String profileName() { return profileName; }
    }
}
