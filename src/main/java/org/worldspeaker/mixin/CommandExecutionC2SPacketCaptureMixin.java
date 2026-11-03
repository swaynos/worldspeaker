package org.worldspeaker.mixin;

import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.worldspeaker.bootstrap.InvocationCapture;
import org.worldspeaker.bootstrap.PacketCaptureAccess;

@Mixin(CommandExecutionC2SPacket.class)
public abstract class CommandExecutionC2SPacketCaptureMixin implements PacketCaptureAccess {
    @Unique private volatile InvocationCapture worldspeaker$invocationCapture;

    @Override public InvocationCapture worldspeaker$getInvocationCapture() { return worldspeaker$invocationCapture; }

    @Override public boolean worldspeaker$setInvocationCapture(InvocationCapture capture) {
        if (capture == null) return false;
        synchronized (this) {
            if (worldspeaker$invocationCapture != null) return false;
            worldspeaker$invocationCapture = capture;
            return true;
        }
    }
}
