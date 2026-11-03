package org.worldspeaker.compat;

import org.worldspeaker.generated.DeploymentFacts;

import java.util.Objects;

/** Compatibility is a prerequisite only; it never grants an ability. */
public final class CompatibilityGate {
    private final ConfiguredBinding binding;
    private final String configSetSha256;
    private RuntimeFacts localFacts;
    private volatile GateSnapshot snapshot = new GateSnapshot(GateState.CHECKING, 0, "WS-COMPAT-CHECKING");

    public CompatibilityGate(ConfiguredBinding binding) {
        this(binding, "0".repeat(64));
    }

    public CompatibilityGate(ConfiguredBinding binding, String configSetSha256) {
        this.binding = Objects.requireNonNull(binding, "binding");
        if (configSetSha256 == null || !configSetSha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("configuration digest is invalid");
        }
        this.configSetSha256 = configSetSha256;
    }

    public GateSnapshot snapshot() {
        return snapshot;
    }

    public synchronized GateSnapshot checkLocal(RuntimeFacts facts) {
        localFacts = Objects.requireNonNull(facts, "facts");
        String mismatch = localMismatch(facts);
        if (mismatch != null) {
            return transition(GateState.UNAVAILABLE, mismatch);
        }
        return transition(GateState.UNAVAILABLE, "WS-COMPAT-COMPANION-MISSING");
    }

    /** Called only after the peer response has passed nonce, HMAC, size, and schema checks. */
    public synchronized GateSnapshot companionAuthenticated(PeerFacts peer) {
        Objects.requireNonNull(peer, "peer");
        if (localFacts == null) {
            return transition(GateState.UNAVAILABLE, "WS-COMPAT-LOCAL-UNCHECKED");
        }
        String mismatch = localMismatch(localFacts);
        if (mismatch != null) {
            return transition(GateState.UNAVAILABLE, mismatch);
        }
        if (!binding.isComplete()) {
            return transition(GateState.UNAVAILABLE, "WS-COMPAT-BINDING-INCOMPLETE");
        }
        if (!DeploymentFacts.DEPLOYMENT_ID.equals(peer.deploymentId()) ||
                !DeploymentFacts.MANIFEST_SHA256.equals(peer.manifestSha256()) ||
                !DeploymentFacts.PROTOCOL.equals(peer.protocol()) ||
                !DeploymentFacts.OPENCLAW_VERSION.equals(peer.hostVersion()) ||
                !DeploymentFacts.NODE_VERSION.equals(peer.nodeVersion()) ||
                !DeploymentFacts.COMPANION_VERSION.equals(peer.companionVersion()) ||
                peer.configurationHandshakeVersion() != 1 || !configSetSha256.equals(peer.configSetSha256()) ||
                !binding.serverId().equals(peer.serverId()) ||
                !binding.worldId().equals(peer.worldId()) ||
                !binding.characterId().equals(peer.characterId()) ||
                !binding.agentId().equals(peer.agentId())) {
            return transition(GateState.UNAVAILABLE, "WS-COMPAT-PEER-MISMATCH");
        }
        return transition(GateState.READY, "WS-COMPAT-READY");
    }

    public synchronized GateSnapshot unavailable(String safeReasonCode) {
        return transition(GateState.UNAVAILABLE, safeReasonCode);
    }

    public synchronized GateSnapshot disconnected() {
        return transition(GateState.UNAVAILABLE, "WS-COMPAT-COMPANION-DISCONNECTED");
    }

    /** Run a bounded tick-thread action only if the generation is still current and ready. */
    public synchronized boolean executeIfReady(long expectedGeneration, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (snapshot.state() != GateState.READY || snapshot.generation() != expectedGeneration) {
            return false;
        }
        action.run();
        return true;
    }

    /** Safe local dispositions may run during an upstream outage, but only in the unchanged unavailable epoch. */
    public synchronized boolean executeIfUnavailable(long expectedGeneration, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (snapshot.state() == GateState.READY || snapshot.generation() != expectedGeneration) return false;
        action.run();
        return true;
    }

    private String localMismatch(RuntimeFacts facts) {
        if (!DeploymentFacts.MINECRAFT_VERSION.equals(facts.minecraftVersion())) return "WS-COMPAT-MINECRAFT";
        if (!DeploymentFacts.FABRIC_LOADER_VERSION.equals(facts.fabricLoaderVersion())) return "WS-COMPAT-LOADER";
        if (!DeploymentFacts.FABRIC_API_VERSION.equals(facts.fabricApiVersion())) return "WS-COMPAT-FABRIC-API";
        if (Integer.parseInt(DeploymentFacts.JAVA_MAJOR) != facts.javaMajor()) return "WS-COMPAT-JAVA-MAJOR";
        if (!DeploymentFacts.JAVA_VENDOR.equals(facts.javaVendor()) ||
                !DeploymentFacts.JAVA_RUNTIME_VERSION.equals(facts.javaRuntimeVersion())) return "WS-COMPAT-JAVA-BUILD";
        return null;
    }

    private GateSnapshot transition(GateState state, String reasonCode) {
        if (snapshot.state() != state || !snapshot.reasonCode().equals(reasonCode)) {
            snapshot = new GateSnapshot(state, snapshot.generation() + 1, reasonCode);
        }
        return snapshot;
    }
}
