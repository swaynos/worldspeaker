package org.worldspeaker.integration;

import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.PeerFacts;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

/** Canonical authenticated facts for the WS compatibility handshake. */
public final class PeerHandshake {
    private PeerHandshake() {}

    public static String clientProof(CompanionConfig config, String challenge, String nonce, RuntimeFacts facts) {
        return hmac(config.secret(), clientTranscript(challenge, nonce, config, facts));
    }

    public static boolean verifyServerChallenge(CompanionConfig config, String challenge, PeerFacts facts, String proof) {
        if (!configurationFactsMatch(config, facts)) return false;
        return verify(config, serverChallengeProof(config, challenge, facts), proof);
    }

    public static String serverChallengeProof(CompanionConfig config, String challenge, PeerFacts facts) {
        return hmac(config.secret(), String.join("\n", List.of(
                DeploymentFacts.PROTOCOL, "challenge", challenge,
                facts.deploymentId(), facts.manifestSha256(), facts.protocol(), facts.hostVersion(), facts.companionVersion(), facts.nodeVersion(),
                facts.serverId(), facts.worldId(), facts.characterId(), facts.agentId(),
                Integer.toString(facts.configurationHandshakeVersion()), facts.configSetSha256())));
    }

    public static boolean verifyServerProof(
            CompanionConfig config, String challenge, String nonce, PeerFacts facts, String nodeVersion, String proof) {
        if (!configurationFactsMatch(config, facts)) return false;
        return verify(config, serverProof(config, challenge, nonce, facts, nodeVersion), proof);
    }

    private static boolean verify(CompanionConfig config, String expectedProof, String proof) {
        byte[] expected = Base64.getUrlDecoder().decode(expectedProof);
        byte[] supplied;
        try {
            supplied = Base64.getUrlDecoder().decode(proof);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return MessageDigest.isEqual(expected, supplied);
    }

    public static String serverProof(CompanionConfig config, String challenge, String nonce, PeerFacts facts, String nodeVersion) {
        return hmac(config.secret(), String.join("\n", List.of(
                DeploymentFacts.PROTOCOL, "server", challenge, nonce,
                facts.deploymentId(), facts.manifestSha256(), facts.protocol(), facts.hostVersion(), facts.companionVersion(), nodeVersion,
                facts.serverId(), facts.worldId(), facts.characterId(), facts.agentId(),
                Integer.toString(facts.configurationHandshakeVersion()), facts.configSetSha256())));
    }

    private static String clientTranscript(String challenge, String nonce, CompanionConfig config, RuntimeFacts facts) {
        return String.join("\n", List.of(
                DeploymentFacts.PROTOCOL, "client", challenge, nonce,
                DeploymentFacts.DEPLOYMENT_ID, DeploymentFacts.MANIFEST_SHA256, DeploymentFacts.PROTOCOL,
                facts.minecraftVersion(), facts.fabricLoaderVersion(), facts.fabricApiVersion(),
                Integer.toString(facts.javaMajor()), facts.javaVendor(), facts.javaRuntimeVersion(),
                config.binding().serverId(), config.binding().worldId(), config.binding().characterId(), config.binding().agentId(),
                "1", config.configSetSha256()));
    }

    private static boolean configurationFactsMatch(CompanionConfig config, PeerFacts facts) {
        return facts.configurationHandshakeVersion() == 1 &&
                config.configSetSha256().equals(facts.configSetSha256());
    }

    private static String hmac(byte[] secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }
}
