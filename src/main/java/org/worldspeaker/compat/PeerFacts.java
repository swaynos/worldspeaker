package org.worldspeaker.compat;

public record PeerFacts(
        String deploymentId,
        String manifestSha256,
        String protocol,
        String hostVersion,
        String nodeVersion,
        String companionVersion,
        String serverId,
        String worldId,
        String characterId,
        String agentId,
        int configurationHandshakeVersion,
        String configSetSha256) {
    public PeerFacts(String deploymentId, String manifestSha256, String protocol, String hostVersion, String nodeVersion,
                     String companionVersion, String serverId, String worldId, String characterId, String agentId) {
        this(deploymentId, manifestSha256, protocol, hostVersion, nodeVersion, companionVersion,
                serverId, worldId, characterId, agentId, 1, "0".repeat(64));
    }
}
