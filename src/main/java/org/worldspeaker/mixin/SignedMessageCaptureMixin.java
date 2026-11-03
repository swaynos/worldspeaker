package org.worldspeaker.mixin;

import net.minecraft.network.message.FilterMask;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.worldspeaker.bootstrap.InvocationCapture;
import org.worldspeaker.bootstrap.PacketCaptureAccess;

@Mixin(SignedMessage.class)
public abstract class SignedMessageCaptureMixin implements PacketCaptureAccess {
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

    @Inject(method = "withUnsignedContent", at = @At("RETURN"))
    private void worldspeaker$copyUnsignedContent(Text content, CallbackInfoReturnable<SignedMessage> result) {
        worldspeaker$copy(result.getReturnValue());
    }

    @Inject(method = "withoutUnsigned", at = @At("RETURN"))
    private void worldspeaker$copyWithoutUnsigned(CallbackInfoReturnable<SignedMessage> result) {
        worldspeaker$copy(result.getReturnValue());
    }

    @Inject(method = "withFilterMask", at = @At("RETURN"))
    private void worldspeaker$copyFilterMask(FilterMask mask, CallbackInfoReturnable<SignedMessage> result) {
        worldspeaker$copy(result.getReturnValue());
    }

    @Inject(method = "withFilterMaskEnabled", at = @At("RETURN"))
    private void worldspeaker$copyFilterMaskEnabled(boolean enabled, CallbackInfoReturnable<SignedMessage> result) {
        worldspeaker$copy(result.getReturnValue());
    }

    @Inject(method = "stripSignature", at = @At("RETURN"))
    private void worldspeaker$copyStrippedSignature(CallbackInfoReturnable<SignedMessage> result) {
        worldspeaker$copy(result.getReturnValue());
    }

    @Unique private void worldspeaker$copy(SignedMessage copied) {
        if (copied != null && (Object) copied != this) {
            ((PacketCaptureAccess) (Object) copied).worldspeaker$setInvocationCapture(worldspeaker$invocationCapture);
        }
    }
}
