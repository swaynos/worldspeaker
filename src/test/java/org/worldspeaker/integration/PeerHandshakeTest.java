package org.worldspeaker.integration;

import org.junit.jupiter.api.Test;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.PeerFacts;
import org.worldspeaker.compat.RuntimeFacts;
import org.worldspeaker.generated.DeploymentFacts;

import java.net.URI;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerHandshakeTest {
    private static final String CHALLENGE = repeatedByte(0x11);
    private static final String NONCE = repeatedByte(0x22);

    private static String repeatedByte(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Test
    void proofsBindBothDirectionsToTheFreshServerChallenge() {
        byte[] secret = new byte[32];
        for (int index = 0; index < secret.length; index++) secret[index] = (byte) index;
        CompanionConfig config = new CompanionConfig(
                URI.create("ws://127.0.0.1:18473"), secret,
                new ConfiguredBinding("server-test", "world-test", "character-test", "agent-test"), "Hello {player}",
                "Archivist", org.worldspeaker.compat.InteractionPolicy.denyAll());
        PeerFacts peer = new PeerFacts(
                DeploymentFacts.DEPLOYMENT_ID, DeploymentFacts.MANIFEST_SHA256, DeploymentFacts.PROTOCOL,
                DeploymentFacts.OPENCLAW_VERSION, DeploymentFacts.NODE_VERSION, DeploymentFacts.COMPANION_VERSION,
                "server-test", "world-test", "character-test", "agent-test");
        RuntimeFacts local = new RuntimeFacts(
                DeploymentFacts.MINECRAFT_VERSION, DeploymentFacts.FABRIC_LOADER_VERSION, DeploymentFacts.FABRIC_API_VERSION,
                Integer.parseInt(DeploymentFacts.JAVA_MAJOR), DeploymentFacts.JAVA_VENDOR, DeploymentFacts.JAVA_RUNTIME_VERSION);

        String challengeProof = PeerHandshake.serverChallengeProof(config, CHALLENGE, peer);
        assertEquals("kLNAixFZ_hKiR-oNGNQWZ5Z1SxmQcZWW5pie_onysv8", challengeProof);
        assertTrue(PeerHandshake.verifyServerChallenge(config, CHALLENGE, peer, challengeProof));
        String alteredChallenge = "B" + CHALLENGE.substring(1);
        String alteredNonce = "B" + NONCE.substring(1);
        assertFalse(PeerHandshake.verifyServerChallenge(config, alteredChallenge, peer, challengeProof));

        String clientProof = PeerHandshake.clientProof(config, CHALLENGE, NONCE, local);
        assertEquals("htUNjrre8sNnqoPf1GnKHNbKqR5ZVp-En7r_oB5sda8", clientProof);
        assertNotEquals(clientProof, PeerHandshake.clientProof(config, alteredChallenge, NONCE, local));

        String responseProof = PeerHandshake.serverProof(config, CHALLENGE, NONCE, peer, peer.nodeVersion());
        assertEquals("FcFcBF0ibVF1Zb7G9X0WJD9BijAe_bg32e_ud__109E", responseProof);
        assertTrue(PeerHandshake.verifyServerProof(config, CHALLENGE, NONCE, peer, peer.nodeVersion(), responseProof));
        assertFalse(PeerHandshake.verifyServerProof(config, alteredChallenge, NONCE, peer, peer.nodeVersion(), responseProof));
        assertFalse(PeerHandshake.verifyServerProof(config, CHALLENGE, alteredNonce, peer, peer.nodeVersion(), responseProof));
    }
}
