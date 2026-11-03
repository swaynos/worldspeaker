package org.worldspeaker.integration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.compat.AttentionPolicy;
import org.worldspeaker.compat.CharacterChatStyle;
import org.worldspeaker.compat.CompanionConfig;
import org.worldspeaker.compat.ConfiguredBinding;
import org.worldspeaker.compat.InteractionPolicy;
import org.worldspeaker.compat.StartupConfigTestFiles;
import org.worldspeaker.generated.DeploymentFacts;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Loads actual owner-only Java config snapshots and passes only binding/presentation facts to Node. */
class CharacterIdentityContractTest {
    private static final UUID GRANTED_PLAYER = UUID.fromString("00000000-0000-4000-8000-000000000123");

    @TempDir Path directory;

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void presentationReloadPreservesJavaKeyAndTheActualSdkSharedGameRoute() throws Exception {
        String nodeVersion = readNodeVersion();
        assumeTrue(DeploymentFacts.NODE_VERSION.equals(nodeVersion),
                "the compiled SDK route fixture requires Node " + DeploymentFacts.NODE_VERSION + "; local Node is " + nodeVersion);
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path fixture = repository.resolve("companion/dist/test/character-identity-fixture.js");
        assumeTrue(Files.isRegularFile(fixture), "build the compiled companion fixture before this Java-to-TS contract test");

        Path configFile = StartupConfigTestFiles.createPrivate(directory, Map.of(
                "serverId", "server-char-01", "worldId", "world-char-01", "characterId", "character-char-01",
                "agentId", "worldspeaker-char-agent", "characterDisplayName", "Archivist", "characterAliases", "Old Name",
                "characterChatStyle", "plain", "conversationAllowedPlayers", GRANTED_PLAYER.toString(),
                "executionBudgetSeconds", "17"));
        CompanionConfig before = CompanionConfig.load(configFile);
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(configFile));

        writeConfiguration(configFile, "The Archivist", "Old Name,Keeper", "bracketed");
        CompanionConfig after = CompanionConfig.load(configFile);

        ConfiguredBinding.CharacterKey key = before.binding().characterKey();
        assertEquals(key, after.binding().characterKey());
        assertEquals(before.binding(), after.binding());
        assertEquals("worldspeaker-char-agent", after.binding().agentId());
        assertEquals(before.interactionPolicy(), after.interactionPolicy());
        assertEquals(before.attentionPolicy(), after.attentionPolicy());
        assertEquals(before.executionBudgetSeconds(), after.executionBudgetSeconds());
        assertEquals(before.acknowledgementTemplate(), after.acknowledgementTemplate());
        assertEquals("Archivist", before.characterDisplayName(), "the old snapshot was not mutated by rewriting the file");
        assertEquals(List.of("Old Name"), before.characterAliases());
        assertEquals(CharacterChatStyle.PLAIN, before.characterChatStyle());
        assertEquals("The Archivist", after.characterDisplayName());
        assertEquals(List.of("Old Name", "Keeper"), after.characterAliases());
        assertEquals(CharacterChatStyle.BRACKETED, after.characterChatStyle());

        JsonObject input = new JsonObject();
        input.add("before", snapshot(before));
        input.add("after", snapshot(after));
        String processOutput = runNodeFixture(repository, fixture, input.toString());
        JsonObject result = JsonParser.parseString(processOutput).getAsJsonObject();
        assertEquals("character-identity-result", result.get("kind").getAsString());
        assertEquals("worldspeaker-game:v2:", result.get("routePrefix").getAsString());
        assertEquals(result.get("beforeSessionKey").getAsString(), result.get("afterSessionKey").getAsString());
        assertEquals("worldspeaker-char-agent", result.get("beforeAgentId").getAsString());
        assertEquals("worldspeaker-char-agent", result.get("afterAgentId").getAsString());
        assertTrue(result.get("playersShareRoute").getAsBoolean(), "A and B use one real SDK-resolved shared game group");
        assertTrue(result.get("privateAdminSeparate").getAsBoolean(), "the actual Control UI SDK key stays separate");
        JsonObject changedRoutes = result.getAsJsonObject("changedRoutes");
        for (String field : List.of("serverId", "worldId", "characterId", "agentId", "accountId")) {
            assertTrue(changedRoutes.get(field).getAsBoolean(), field + " changes the production route/session key");
        }
        assertFalse(processOutput.contains("AAAAAAAAAAAAAAAA"), "credentials were never sent to Node");
        assertFalse(processOutput.contains("Old Name"), "presentation text is not returned as route output");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(configFile));

        assertNotEquals(key, new ConfiguredBinding("another-server", key.worldId(), key.characterId(),
                after.binding().agentId()).characterKey());
        assertNotEquals(key, new ConfiguredBinding(key.serverId(), "another-world", key.characterId(),
                after.binding().agentId()).characterKey());
        assertNotEquals(key, new ConfiguredBinding(key.serverId(), key.worldId(), "another-character",
                after.binding().agentId()).characterKey());
        assertEquals(key, new ConfiguredBinding(key.serverId(), key.worldId(), key.characterId(),
                "different-agent").characterKey(), "agent is authenticated routing association, not future state namespace");
    }

    private static JsonObject snapshot(CompanionConfig config) {
        JsonObject snapshot = new JsonObject();
        JsonObject binding = new JsonObject();
        binding.addProperty("serverId", config.binding().serverId());
        binding.addProperty("worldId", config.binding().worldId());
        binding.addProperty("characterId", config.binding().characterId());
        binding.addProperty("agentId", config.binding().agentId());
        JsonObject presentation = new JsonObject();
        presentation.addProperty("displayName", config.characterDisplayName());
        var aliases = new com.google.gson.JsonArray();
        config.characterAliases().forEach(aliases::add);
        presentation.add("aliases", aliases);
        presentation.addProperty("chatStyle", config.characterChatStyle().configValue());
        snapshot.add("binding", binding);
        snapshot.add("presentation", presentation);
        return snapshot;
    }

    private static void writeConfiguration(Path path, String displayName, String aliases, String style) throws IOException {
        StartupConfigTestFiles.write(path, Map.of(
                "serverId", "server-char-01", "worldId", "world-char-01", "characterId", "character-char-01",
                "agentId", "worldspeaker-char-agent", "characterDisplayName", displayName, "characterAliases", aliases,
                "characterChatStyle", style, "conversationAllowedPlayers", GRANTED_PLAYER.toString(),
                "executionBudgetSeconds", "17"));
    }

    private static String runNodeFixture(Path repository, Path fixture, String input) throws Exception {
        Process process = new ProcessBuilder("node", fixture.toString()).directory(repository.toFile())
                .redirectErrorStream(true).start();
        try {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                throw new AssertionError("compiled Node route fixture exceeded its bounded 10-second process budget");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals(0, process.exitValue(), "compiled Node route fixture failed: " + output);
            return output;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }
    }

    private static String readNodeVersion() throws Exception {
        Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "unavailable";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return output.startsWith("v") ? output.substring(1) : output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
