package org.worldspeaker.bootstrap;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;

/** Narrow Fabric callback adapter; guards the active server epoch before reading callback-owned game objects. */
public final class AttentionListener {
    private AttentionListener() {}

    public static void register(Access access) {
        ServerPlayConnectionEvents.JOIN.register(createJoinCallback(access));
        ServerPlayConnectionEvents.DISCONNECT.register(createDisconnectCallback(access));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(createDimensionCallback(access));
        ServerLivingEntityEvents.AFTER_DEATH.register(createDeathCallback(access));
        ServerPlayerEvents.AFTER_RESPAWN.register(createRespawnCallback(access));
    }

    public static ServerPlayConnectionEvents.Join createJoinCallback(Access access) {
        return (handler, sender, server) -> {
            Times times = captureTimes(access);
            Object epoch = access.activeEpoch();
            if (!validServer(access, epoch, server)) return;
            ServerPlayerEntity player = handler == null ? null : handler.player;
            if (player == null || player.getServer() != server || !validServer(access, epoch, server)) return;
            SmokeConversation conversation = access.conversation(epoch);
            if (conversation != null && validServer(access, epoch, server)) conversation.onPlayerJoined(player, times.monotonicNanos, times.wallTimeMillis);
        };
    }

    public static ServerPlayConnectionEvents.Disconnect createDisconnectCallback(Access access) {
        return (handler, server) -> {
            Times times = captureTimes(access);
            Object epoch = access.activeEpoch();
            if (!validServer(access, epoch, server)) return;
            ServerPlayerEntity player = handler == null ? null : handler.player;
            if (player == null) return;
            SmokeConversation conversation = access.conversation(epoch);
            if (conversation != null && validServer(access, epoch, server)) {
                conversation.onPlayerDisconnected(handler, times.monotonicNanos, times.wallTimeMillis);
            }
        };
    }

    public static ServerEntityWorldChangeEvents.AfterPlayerChange createDimensionCallback(Access access) {
        return (player, origin, destination) -> {
            Times times = captureTimes(access);
            Object epoch = access.activeEpoch();
            MinecraftServer server = validPlayerServer(access, epoch, player);
            if (server == null) return;
            SmokeConversation conversation = access.conversation(epoch);
            if (conversation != null && validPlayerServer(access, epoch, player) == server) {
                conversation.onPlayerDimensionChanged(player, origin, destination, times.monotonicNanos, times.wallTimeMillis);
            }
        };
    }

    public static ServerLivingEntityEvents.AfterDeath createDeathCallback(Access access) {
        return (entity, damageSource) -> {
            if (!(entity instanceof ServerPlayerEntity player)) return;
            Times times = captureTimes(access);
            Object epoch = access.activeEpoch();
            MinecraftServer server = validPlayerServer(access, epoch, player);
            if (server == null) return;
            SmokeConversation conversation = access.conversation(epoch);
            if (conversation != null && validPlayerServer(access, epoch, player) == server) {
                conversation.onPlayerDied(player, times.monotonicNanos, times.wallTimeMillis);
            }
        };
    }

    public static ServerPlayerEvents.AfterRespawn createRespawnCallback(Access access) {
        return (oldPlayer, newPlayer, copiedAlive) -> {
            Times times = captureTimes(access);
            Object epoch = access.activeEpoch();
            MinecraftServer server = validPlayerServer(access, epoch, newPlayer);
            if (server == null) return;
            SmokeConversation conversation = access.conversation(epoch);
            if (conversation != null && validPlayerServer(access, epoch, newPlayer) == server) {
                conversation.onPlayerRespawned(oldPlayer, newPlayer, copiedAlive,
                        times.monotonicNanos, times.wallTimeMillis);
            }
        };
    }

    /** Combines lifecycle/bootstrap handling with the existing bounded conversation tick. */
    public static ServerTickEvents.EndTick createEndTickCallback(Access access, TickHandler handler) {
        return server -> {
            Object epoch = access.activeEpoch();
            if (!validServer(access, epoch, server)) return;
            handler.onEndTick(server, epoch);
        };
    }

    private static Times captureTimes(Access access) {
        long monotonicNanos = access.monotonicNanos();
        long wallTimeMillis = access.wallTimeMillis();
        return new Times(monotonicNanos, wallTimeMillis);
    }

    private static boolean validServer(Access access, Object epoch, MinecraftServer server) {
        return server != null && epoch != null && access.isServerThread() && access.isCurrentEpoch(epoch) &&
                access.ownsServer(epoch, server) && access.isCurrentEpoch(epoch);
    }

    private static MinecraftServer validPlayerServer(Access access, Object epoch, ServerPlayerEntity player) {
        if (player == null || epoch == null || !access.isServerThread() || !access.isCurrentEpoch(epoch)) return null;
        MinecraftServer server = access.currentServer(epoch, player);
        return server != null && access.ownsServer(epoch, server) && access.isCurrentEpoch(epoch) ? server : null;
    }

    public interface Access {
        long monotonicNanos();
        long wallTimeMillis();
        boolean isServerThread();
        Object activeEpoch();
        boolean isCurrentEpoch(Object epoch);
        boolean ownsServer(Object epoch, MinecraftServer server);
        /** Must check thread/epoch before reading player.getServer(). */
        MinecraftServer currentServer(Object epoch, ServerPlayerEntity player);
        SmokeConversation conversation(Object epoch);
    }

    @FunctionalInterface
    public interface TickHandler {
        void onEndTick(MinecraftServer server, Object epoch);
    }

    private record Times(long monotonicNanos, long wallTimeMillis) {}
}
