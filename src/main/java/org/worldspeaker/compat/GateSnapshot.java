package org.worldspeaker.compat;

public record GateSnapshot(GateState state, long generation, String reasonCode) {
    public GateSnapshot {
        if (state == null || generation < 0 || reasonCode == null) {
            throw new IllegalArgumentException("invalid gate snapshot");
        }
    }
}
