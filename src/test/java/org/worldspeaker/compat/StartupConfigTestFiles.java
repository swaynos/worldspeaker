package org.worldspeaker.compat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.worldspeaker.generated.DeploymentFacts;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Generic test-only owner-mode startup files; no actual credential is used. */
public final class StartupConfigTestFiles {
    private StartupConfigTestFiles() {}

    public static Path createPrivate(Path parent, Map<String, String> overrides) throws IOException {
        Path physicalParent = parent.toRealPath();
        Path directory = Files.createTempDirectory(physicalParent, "worldspeaker-startup-test-");
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path bootstrap = directory.resolve("worldspeaker.properties");
        write(bootstrap, overrides);
        return bootstrap;
    }

    public static void write(Path bootstrap, Map<String, String> overrides) throws IOException {
        Map<String, String> values = new HashMap<>();
        values.put("endpoint", "ws://127.0.0.1:65530");
        values.put("bearerSecret", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        values.put("serverId", "fixture-server");
        values.put("worldId", "fixture-world");
        values.put("characterId", "fixture-character");
        values.put("agentId", "fixture-agent");
        values.put("acknowledgementTemplate", "Hello {player}");
        values.put("characterDisplayName", "Archivist");
        values.putAll(overrides);

        Path directory = bootstrap.getParent();
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path bundlePath = directory.resolve("startup.json");
        Path credentialPath = directory.resolve("credential.txt");
        Files.writeString(credentialPath, values.get("bearerSecret"), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(credentialPath, PosixFilePermissions.fromString("rw-------"));

        JsonObject bundle = new JsonObject();
        bundle.addProperty("schemaVersion", 1);
        JsonObject binding = new JsonObject();
        binding.addProperty("serverId", values.get("serverId"));
        binding.addProperty("worldId", values.get("worldId"));
        binding.addProperty("characterId", values.get("characterId"));
        binding.addProperty("agentId", values.get("agentId"));
        bundle.add("binding", binding);

        JsonObject server = new JsonObject();
        server.addProperty("endpoint", values.get("endpoint"));
        server.addProperty("acknowledgementTemplate", values.get("acknowledgementTemplate"));
        server.addProperty("characterDisplayName", values.get("characterDisplayName"));
        server.add("conversationAllowedPlayers", commaList(values.get("conversationAllowedPlayers")));
        if (values.containsKey("characterAliases")) server.add("characterAliases", commaList(values.get("characterAliases")));
        addNumber(server, values, "followupWindowSeconds");
        addNumber(server, values, "executionBudgetSeconds");
        if (values.containsKey("characterChatStyle")) server.addProperty("characterChatStyle", values.get("characterChatStyle"));
        JsonObject attention = new JsonObject();
        if (values.containsKey("attentionSubscriptions")) attention.add("subscriptions", commaList(values.get("attentionSubscriptions")));
        addNumber(attention, values, "attentionMaxEvents", "maxEvents");
        addNumber(attention, values, "attentionMaxAgeSeconds", "maxAgeSeconds");
        addNumber(attention, values, "attentionMaxBytes", "maxBytes");
        addNumber(attention, values, "attentionMaxEventsPerSecond", "maxEventsPerSecond");
        addNumber(attention, values, "attentionMaxEventsPerPlayerSecond", "maxEventsPerPlayerSecond");
        if (!attention.isEmpty()) server.add("attention", attention);
        bundle.add("server", server);

        JsonObject companion = new JsonObject();
        companion.addProperty("bindHost", "127.0.0.1");
        companion.addProperty("port", Integer.parseInt(values.getOrDefault("companionPort", "65530")));
        companion.addProperty("deploymentId", DeploymentFacts.DEPLOYMENT_ID);
        companion.addProperty("manifestSha256", DeploymentFacts.MANIFEST_SHA256);
        bundle.add("companion", companion);
        JsonObject abilityPolicy = new JsonObject();
        abilityPolicy.addProperty("schemaVersion", 1);
        abilityPolicy.addProperty("revision", 0);
        abilityPolicy.add("grants", new JsonArray());
        abilityPolicy.add("denials", new JsonArray());
        abilityPolicy.add("protection", new JsonObject());
        bundle.add("abilityPolicy", abilityPolicy);
        bundle.addProperty("credentialFile", credentialPath.toString());

        Files.writeString(bundlePath, new Gson().toJson(bundle), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(bundlePath, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(bootstrap, "schemaVersion=1\nconfigFile=" + bundlePath + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(bootstrap, PosixFilePermissions.fromString("rw-------"));
    }

    /** Reuses only bytes already verified by the actual helper, then performs one configured credential read. */
    public static PrivateConfigurationException loadWithVerifiedFilesAndCredentialFailure(
            Path bootstrapPath, StartupConfig verifiedSnapshot,
            Consumer<ProcessHandle> credentialChildObserver) throws IOException {
        Path bundlePath = bootstrapPath.getParent().resolve("startup.json");
        Path credentialPath = bootstrapPath.getParent().resolve("credential.txt");
        byte[] bootstrapBytes = PrivateFileReader.read(bootstrapPath, StartupConfig.MAX_BOOTSTRAP_BYTES, false);
        byte[] bundleBytes = PrivateFileReader.read(bundlePath, StartupConfig.MAX_BUNDLE_BYTES, false);
        String bundleHash;
        try {
            bundleHash = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bundleBytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        if (!verifiedSnapshot.configSetSha256().equals(bundleHash)) {
            throw new AssertionError("actual-helper bundle bytes differ from the verified snapshot");
        }
        AtomicInteger credentialReads = new AtomicInteger();

        try {
            StartupConfig.loadForTest(bootstrapPath, (path, maximum, credential) -> {
                if (path.equals(bootstrapPath) && maximum == StartupConfig.MAX_BOOTSTRAP_BYTES && !credential) {
                    return bootstrapBytes;
                }
                if (path.equals(bundlePath) && maximum == StartupConfig.MAX_BUNDLE_BYTES && !credential) {
                    return bundleBytes;
                }
                if (path.equals(credentialPath) && maximum == StartupConfig.MAX_CREDENTIAL_BYTES && credential) {
                    credentialReads.incrementAndGet();
                    return PrivateFileReader.readForTest(path, maximum, true, 2_000, null, credentialChildObserver);
                }
                throw new AssertionError("unexpected private startup read profile");
            });
            throw new AssertionError("non-owner-only credential unexpectedly loaded");
        } catch (PrivateConfigurationException failure) {
            if (credentialReads.get() != 1) throw new AssertionError("configured credential helper read count was not one");
            if (PrivateFileReader.activeChildrenForTest() != 0) throw new AssertionError("credential helper child was not reaped");
            return failure;
        }
    }

    private static JsonArray commaList(String value) {
        JsonArray result = new JsonArray();
        if (value != null && !value.isEmpty()) {
            for (String entry : value.split(",", -1)) result.add(entry);
        }
        return result;
    }

    private static void addNumber(JsonObject target, Map<String, String> values, String key) {
        addNumber(target, values, key, key);
    }

    private static void addNumber(JsonObject target, Map<String, String> values, String source, String destination) {
        if (!values.containsKey(source)) return;
        String value = values.get(source);
        try {
            if (value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) {
                target.add(destination, new JsonPrimitive(new BigDecimal(value)));
                return;
            }
        } catch (NumberFormatException ignored) {
            // Keep invalid values as strings for the production parser to reject.
        }
        target.addProperty(destination, value);
    }
}
