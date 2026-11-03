package org.worldspeaker.bootstrap;

/** Packet/message-owned capture carrier; values are immutable and never keyed by chat text. */
public interface PacketCaptureAccess {
    InvocationCapture worldspeaker$getInvocationCapture();
    boolean worldspeaker$setInvocationCapture(InvocationCapture capture);
}
