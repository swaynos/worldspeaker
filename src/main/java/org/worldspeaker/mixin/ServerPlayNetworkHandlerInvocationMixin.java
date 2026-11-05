package org.worldspeaker.mixin;

import net.minecraft.network.message.LastSeenMessageList;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.worldspeaker.bootstrap.InvocationCapture;
import org.worldspeaker.bootstrap.InvocationCaptureContext;
import org.worldspeaker.bootstrap.CaptureHandoffBudget;
import org.worldspeaker.bootstrap.PacketCaptureAccess;
import org.worldspeaker.bootstrap.ServerConnectionGeneration;
import org.worldspeaker.WorldspeakerServer;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Exact pinned 1.21.1 packet ingress seams; no source text is used to correlate metadata. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerInvocationMixin implements ServerConnectionGeneration {
    @Unique private static final AtomicLong WORLDSPEAKER$NEXT_CONNECTION_GENERATION = new AtomicLong();
    @Unique private final long worldspeaker$connectionGeneration = worldspeaker$nextConnectionGeneration();

    @Override public long worldspeaker$connectionGeneration() { return worldspeaker$connectionGeneration; }

    @Inject(method = "onChatMessage", at = @At("HEAD"))
    private void worldspeaker$captureSignedChat(ChatMessageC2SPacket packet, CallbackInfo ci) {
        PacketCaptureAccess carrier = (PacketCaptureAccess) (Object) packet;
        worldspeaker$attach(carrier, worldspeaker$capture(InvocationCapture.Source.SIGNED_CHAT));
    }

    @Inject(method = "onCommandExecution", at = @At("HEAD"))
    private void worldspeaker$captureUnsignedCommand(CommandExecutionC2SPacket packet, CallbackInfo ci) {
        PacketCaptureAccess carrier = (PacketCaptureAccess) (Object) packet;
        worldspeaker$attach(carrier, worldspeaker$capture(InvocationCapture.Source.UNSIGNED_COMMAND));
    }

    @Inject(method = "onChatCommandSigned", at = @At("HEAD"))
    private void worldspeaker$captureSignedCommand(ChatCommandSignedC2SPacket packet, CallbackInfo ci) {
        PacketCaptureAccess carrier = (PacketCaptureAccess) (Object) packet;
        worldspeaker$attach(carrier, worldspeaker$capture(InvocationCapture.Source.SIGNED_COMMAND));
    }

    @Inject(method = "onDisconnected", at = @At("HEAD"))
    private void worldspeaker$retireCaptureHandoffs(DisconnectionInfo info, CallbackInfo ci) {
        CaptureHandoffBudget.retireConnection(this);
    }

    @Inject(method = "getSignedMessage", at = @At("RETURN"), cancellable = true)
    private void worldspeaker$attachChatReceipt(ChatMessageC2SPacket packet, LastSeenMessageList lastSeen,
                                                 CallbackInfoReturnable<SignedMessage> result) {
        InvocationCapture capture = ((PacketCaptureAccess) (Object) packet).worldspeaker$getInvocationCapture();
        SignedMessage message = result.getReturnValue();
        if (capture == null) return;
        if (message == null || !capture.belongsTo(this, worldspeaker$connectionGeneration)) {
            capture.releaseHandoff();
            return;
        }
        if (!((PacketCaptureAccess) (Object) message).worldspeaker$setInvocationCapture(capture)) {
            capture.releaseHandoff();
        }
    }

    @ModifyArgs(method = "onCommandExecution",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayNetworkHandler;validateMessage(Ljava/lang/String;Ljava/lang/Runnable;)V"),
            require = 1, allow = 1)
    private void worldspeaker$bindUnsignedCommandReceipt(Args arguments, CommandExecutionC2SPacket packet) {
        InvocationCapture capture = ((PacketCaptureAccess) (Object) packet).worldspeaker$getInvocationCapture();
        arguments.set(1, InvocationCaptureContext.bind(capture, arguments.<Runnable>get(1)));
    }

    @ModifyArgs(method = "onChatCommandSigned",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayNetworkHandler;validateMessage(Ljava/lang/String;Ljava/lang/Runnable;)V"),
            require = 1, allow = 1)
    private void worldspeaker$bindSignedCommandReceipt(Args arguments, ChatCommandSignedC2SPacket packet) {
        InvocationCapture capture = ((PacketCaptureAccess) (Object) packet).worldspeaker$getInvocationCapture();
        arguments.set(1, InvocationCaptureContext.bind(capture, arguments.<Runnable>get(1)));
    }

    @Unique private static long worldspeaker$nextConnectionGeneration() {
        long next = WORLDSPEAKER$NEXT_CONNECTION_GENERATION.incrementAndGet();
        if (next <= 0) throw new IllegalStateException("network connection generation exhausted");
        return next;
    }

    @Unique private InvocationCapture worldspeaker$capture(InvocationCapture.Source source) {
        InvocationCapture capture = InvocationCapture.captureHandoff(System::nanoTime, System::currentTimeMillis,
                this, worldspeaker$connectionGeneration, source);
        if (capture.handoffCapacityRejected()) WorldspeakerServer.recordCaptureHandoffOverflow();
        return capture;
    }

    @Unique private static void worldspeaker$attach(PacketCaptureAccess carrier, InvocationCapture capture) {
        if (capture != null && !carrier.worldspeaker$setInvocationCapture(capture)) capture.releaseHandoff();
    }
}
