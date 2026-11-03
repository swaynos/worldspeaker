package org.worldspeaker.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.bootstrap.NaturalAddressParser;
import org.worldspeaker.integration.StrictJsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupConfigTest {
    @TempDir Path temporaryDirectory;

    @Test
    void loadsActualBootstrapBundleAndCredentialAsAnExactImmutableDenyAllSnapshot() throws Exception {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        Path bundlePath = bundlePath(bootstrap);
        byte[] bundleBytes = Files.readAllBytes(bundlePath);
        StartupConfig snapshot = StartupConfig.load(bootstrap);

        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bundleBytes)), snapshot.configSetSha256());
        assertEquals(snapshot.configSetSha256(), snapshot.serverConfig().configSetSha256());
        assertEquals(0, snapshot.abilityPolicy().grants().size());
        assertEquals(AbilityPolicy.denyAll(), snapshot.abilityPolicy());
        assertFalse(AbilityAvailability.isImplemented(AbilityPolicy.OBSERVE, 1));
        assertFalse(AbilityAvailability.isImplemented(AbilityPolicy.LIGHTNING, 1));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(bootstrap.getParent()));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(bootstrap));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(bundlePath));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(bootstrap.getParent().resolve("credential.txt")));
        assertEquals("StartupConfig[validated local startup snapshot]", snapshot.toString());
        assertEquals("CompanionConfig[local startup snapshot]", snapshot.serverConfig().toString());
        assertFalse(snapshot.toString().contains(bootstrap.toString()));
        assertFalse(snapshot.serverConfig().toString().contains("AAAAAAAA"));
    }

    @Test
    void actualJavaStartupFilesFollowSharedEndpointSyntaxCorpusWithoutRewriting() throws Exception {
        JsonObject corpus = JsonParser.parseString(Files.readString(
                Path.of("compatibility/endpoint-syntax-v1-corpus.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        for (JsonElement caseElement : corpus.getAsJsonArray("cases")) {
            JsonObject entry = caseElement.getAsJsonObject();
            String id = entry.get("id").getAsString();
            String endpoint = entry.get("endpoint").getAsString();
            boolean valid = entry.get("valid").getAsBoolean();
            Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
            Path bundlePath = bundlePath(bootstrap);
            JsonObject bundle = parsedBundle(bundlePath);
            bundle.getAsJsonObject("server").addProperty("endpoint", endpoint);
            writeBundle(bundlePath, new Gson().toJson(bundle).getBytes(StandardCharsets.UTF_8));

            if (valid) {
                assertEquals(endpoint, StartupConfig.load(bootstrap).serverConfig().endpoint().toString(), id);
            } else {
                assertThrows(IOException.class, () -> StartupConfig.load(bootstrap), id);
            }
        }
    }

    @Test
    void actualJavaStartupLoaderAndAddressParserFollowSharedNameCorpus() throws Exception {
        JsonObject corpus = JsonParser.parseString(Files.readString(
                Path.of("compatibility/startup-name-validation-v1-corpus.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        for (var entryElement : corpus.getAsJsonArray("cases")) {
            JsonObject entry = entryElement.getAsJsonObject();
            String id = entry.get("id").getAsString();
            boolean expectedValid = entry.get("valid").getAsBoolean();
            Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
            Path bundlePath = bundlePath(bootstrap);
            JsonObject bundle = parsedBundle(bundlePath);
            JsonObject server = bundle.getAsJsonObject("server");
            server.add("characterDisplayName", expandNameCorpusValue(entry.get("displayName")));
            if (entry.has("aliases")) server.add("characterAliases", expandNameCorpusValue(entry.get("aliases")));
            else server.remove("characterAliases");
            String serializedBundle = new Gson().toJson(bundle);
            if ("display-name-unpaired-high-surrogate".equals(id)) {
                String unpairedName = entry.get("displayName").getAsString();
                serializedBundle = serializedBundle.replace("\"characterDisplayName\":\"" + unpairedName + "\"",
                        "\"characterDisplayName\":\"" + "\\" + "uD800" + "\"");
            }
            writeBundle(bundlePath, serializedBundle.getBytes(StandardCharsets.UTF_8));

            if (!expectedValid) {
                assertThrows(IOException.class, () -> StartupConfig.load(bootstrap), id);
                continue;
            }

            StartupConfig snapshot = StartupConfig.load(bootstrap);
            if (entry.has("canonicalAliases")) {
                assertEquals(stringList(entry.getAsJsonArray("canonicalAliases")), snapshot.serverConfig().characterAliases(), id);
            }
            List<String> configuredNames = new ArrayList<>();
            configuredNames.add(snapshot.serverConfig().characterDisplayName());
            configuredNames.addAll(snapshot.serverConfig().characterAliases());
            for (String name : configuredNames) {
                assertTrue(NaturalAddressParser.isValidConfiguredName(name), id + " accepted loader name is parser-valid");
                NaturalAddressParser.Result result = NaturalAddressParser.parse(name + ", please", configuredNames);
                assertEquals(NaturalAddressParser.Disposition.ADDRESSED, result.disposition(), id + " is exactly addressable");
                assertEquals(name, result.matchedName(), id + " returns the literal configured name");
            }
        }
    }

    @Test
    void actualJavaStartupLoaderMatchesCanonicalConfigUuidCorpusAcrossBothPolicies() throws Exception {
        JsonObject corpus = JsonParser.parseString(Files.readString(
                Path.of("compatibility/startup-config-v1-uuid-corpus.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray validIds = uuidCorpusValues(corpus.getAsJsonArray("valid"));
        List<String> canonicalIds = new ArrayList<>();
        for (JsonElement value : validIds) canonicalIds.add(value.getAsString());

        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        JsonObject validBundle = bundleWithUuidLists(bootstrap, validIds, validIds, validIds);
        writeBundleObject(bundlePath(bootstrap), validBundle);
        StartupConfig snapshot = StartupConfig.load(bootstrap);
        assertEquals(canonicalIds.stream().map(UUID::fromString).collect(java.util.stream.Collectors.toSet()),
                snapshot.serverConfig().interactionPolicy().conversationAllowedPlayers());
        assertEquals(canonicalIds, snapshot.abilityPolicy().grants().getFirst().requesterIds());
        assertEquals(canonicalIds, snapshot.abilityPolicy().grants().getFirst().otherTargetIds());

        List<String> fields = List.of("conversationAllowedPlayers", "requesterIds", "otherTargetIds");
        for (JsonElement entry : corpus.getAsJsonArray("invalidItems")) {
            JsonObject testCase = entry.getAsJsonObject();
            JsonArray invalidItem = new JsonArray();
            invalidItem.add(testCase.get("value").deepCopy());
            for (String field : fields) {
                Path invalidBootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
                JsonElement allowed = validIds;
                JsonElement requesters = validIds;
                JsonElement otherTargets = validIds;
                switch (field) {
                    case "conversationAllowedPlayers" -> allowed = invalidItem;
                    case "requesterIds" -> requesters = invalidItem;
                    case "otherTargetIds" -> otherTargets = invalidItem;
                    default -> throw new AssertionError("unreachable UUID field");
                }
                JsonObject invalidBundle = bundleWithUuidLists(invalidBootstrap, allowed, requesters, otherTargets);
                writeBundleObject(bundlePath(invalidBootstrap), invalidBundle);
                assertThrows(IOException.class, () -> StartupConfig.load(invalidBootstrap), field + ": " + testCase.get("name"));
            }
        }
        for (JsonElement entry : corpus.getAsJsonArray("invalidLists")) {
            JsonObject testCase = entry.getAsJsonObject();
            for (String field : fields) {
                Path invalidBootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
                JsonElement invalidList = testCase.get("value");
                JsonElement allowed = validIds;
                JsonElement requesters = validIds;
                JsonElement otherTargets = validIds;
                switch (field) {
                    case "conversationAllowedPlayers" -> allowed = invalidList;
                    case "requesterIds" -> requesters = invalidList;
                    case "otherTargetIds" -> otherTargets = invalidList;
                    default -> throw new AssertionError("unreachable UUID field");
                }
                JsonObject invalidBundle = bundleWithUuidLists(invalidBootstrap, allowed, requesters, otherTargets);
                writeBundleObject(bundlePath(invalidBootstrap), invalidBundle);
                assertThrows(IOException.class, () -> StartupConfig.load(invalidBootstrap), field + ": " + testCase.get("name"));
            }
        }
        JsonArray duplicateIds = new JsonArray();
        duplicateIds.add(validIds.get(0).deepCopy());
        duplicateIds.add(validIds.get(0).deepCopy());
        for (String field : fields) {
            Path invalidBootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
            JsonElement allowed = validIds;
            JsonElement requesters = validIds;
            JsonElement otherTargets = validIds;
            switch (field) {
                case "conversationAllowedPlayers" -> allowed = duplicateIds;
                case "requesterIds" -> requesters = duplicateIds;
                case "otherTargetIds" -> otherTargets = duplicateIds;
                default -> throw new AssertionError("unreachable UUID field");
            }
            JsonObject invalidBundle = bundleWithUuidLists(invalidBootstrap, allowed, requesters, otherTargets);
            writeBundleObject(bundlePath(invalidBootstrap), invalidBundle);
            assertThrows(IOException.class, () -> StartupConfig.load(invalidBootstrap), field + ": duplicate canonical UUID");
        }
    }

    @Test
    void bootstrapAcceptsExactlyVersionAndOneBundleReferenceAndRejectsLegacyMixedAndDuplicateFields() throws Exception {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        String bundle = bundlePath(bootstrap).toString();
        assertThrows(IOException.class, () -> writeAndLoadBootstrap(bootstrap, "schemaVersion=1\nendpoint=ws://127.0.0.1\nconfigFile=" + bundle + "\n"));
        assertThrows(IOException.class, () -> writeAndLoadBootstrap(bootstrap, "schemaVersion=1\nconfigFile=" + bundle + "\nschemaVersion=1\n"));
        assertThrows(IOException.class, () -> writeAndLoadBootstrap(bootstrap, "schemaVersion=1\nconfigFile=relative.json\n"));
        assertThrows(IOException.class, () -> writeAndLoadBootstrap(bootstrap, "configFile=" + bundle + "\n"));
        assertThrows(IOException.class, () -> writeAndLoadBootstrap(bootstrap, "schemaVersion=2\nconfigFile=" + bundle + "\n"));

        String valid = "schemaVersion=1\nconfigFile=" + bundle + "\n";
        writeAndLoadBootstrap(bootstrap, valid);
        Files.writeString(bootstrap, valid + "\n".repeat((int) (StartupConfig.MAX_BOOTSTRAP_BYTES - Files.size(bootstrap))), StandardCharsets.UTF_8);
        assertEquals(StartupConfig.MAX_BOOTSTRAP_BYTES, Files.size(bootstrap));
        StartupConfig.load(bootstrap);
        Files.writeString(bootstrap, "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertEquals(StartupConfig.MAX_BOOTSTRAP_BYTES + 1, Files.size(bootstrap));
        assertThrows(IOException.class, () -> StartupConfig.load(bootstrap));
    }

    @Test
    void migrationInstructionsAreReservedForRecognizedLegacyInlineBootstrap() throws Exception {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        String bundle = bundlePath(bootstrap).toString();

        PrivateConfigurationException legacy = assertThrows(PrivateConfigurationException.class,
                () -> writeAndLoadBootstrap(bootstrap, "endpoint=ws://127.0.0.1:18473\n" +
                        "bearerSecret=legacy-inline-placeholder\nserverId=legacy-server\n"));
        assertEquals(PrivateConfigurationProblem.Code.LEGACY_BOOTSTRAP, legacy.problem().code());
        assertTrue(legacy.problem().action().instruction().contains("migrate inline legacy properties"));
        assertFalse(legacy.getMessage().contains("legacy-inline-placeholder"));

        PrivateConfigurationException mixed = assertThrows(PrivateConfigurationException.class,
                () -> writeAndLoadBootstrap(bootstrap,
                        "schemaVersion=1\nconfigFile=" + bundle + "\n" +
                                "unknownBootstrapCanary=do-not-echo\n"));
        assertEquals(PrivateConfigurationProblem.Code.UNKNOWN_KEY, mixed.problem().code());
        assertEquals("unknown-key", mixed.problem().field().label());
        assertFalse(mixed.problem().action().instruction().contains("migrate"));
        assertFalse(mixed.getMessage().contains("unknownBootstrapCanary"));

        PrivateConfigurationException incomplete = assertThrows(PrivateConfigurationException.class,
                () -> writeAndLoadBootstrap(bootstrap, "schemaVersion=1\n"));
        assertEquals(PrivateConfigurationProblem.Code.FIELD_INVALID, incomplete.problem().code());
        assertFalse(incomplete.problem().action().instruction().contains("migrate"));
    }

    @Test
    void rejectsStrictJsonSyntaxDuplicateNamesBomMalformedUtf8AndDepthOverEight() throws Exception {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        Path bundle = bundlePath(bootstrap);
        byte[][] invalid = {
                "{\"schemaVersion\":1,}".getBytes(StandardCharsets.UTF_8),
                "{\"schemaVersion\":1,\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8),
                "{schemaVersion:1}".getBytes(StandardCharsets.UTF_8),
                new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'} ,
                new byte[]{(byte) 0xc3, 0x28}
        };
        for (byte[] bytes : invalid) {
            writeBundle(bundle, bytes);
            assertThrows(IOException.class, () -> StartupConfig.load(bootstrap));
        }

        StartupConfigTestFiles.write(bootstrap, Map.of());
        JsonObject deep = parsedBundle(bundle);
        JsonObject nested = new JsonObject();
        JsonObject layer = nested;
        for (String key : new String[]{"a", "b", "c", "d", "e", "f", "g"}) {
            JsonObject child = new JsonObject();
            layer.add(key, child);
            layer = child;
        }
        deep.getAsJsonObject("server").add("nested", nested);
        writeBundle(bundle, new Gson().toJson(deep).getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> StartupConfig.load(bootstrap));
        assertThrows(IllegalArgumentException.class, () -> StrictJsonParser.parse("/* comment */ {}"));
        assertThrows(IllegalArgumentException.class, () -> StrictJsonParser.parse("{\"x\":NaN}"));
    }

    @Test
    void actualStartupLoaderRequiresExactRootSchemaAndRejectsNullsAndCoercion() throws Exception {
        String[] missing = {"schemaVersion", "binding", "server", "companion", "abilityPolicy", "credentialFile"};
        for (String field : missing) {
            Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
            JsonObject bundle = parsedBundle(bundlePath(bootstrap));
            bundle.remove(field);
            writeBundle(bundlePath(bootstrap), new Gson().toJson(bundle).getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> StartupConfig.load(bootstrap), "missing " + field);
        }

        for (int invalidCase = 0; invalidCase < 7; invalidCase++) {
            Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
            Path bundlePath = bundlePath(bootstrap);
            JsonObject bundle = parsedBundle(bundlePath);
            switch (invalidCase) {
                case 0 -> bundle.addProperty("schemaVersion", 2);
                case 1 -> bundle.addProperty("unrecognizedAuthority", true);
                case 2 -> bundle.add("binding", com.google.gson.JsonNull.INSTANCE);
                case 3 -> bundle.getAsJsonObject("server").addProperty("endpoint", 17);
                case 4 -> bundle.getAsJsonObject("companion").add("port", com.google.gson.JsonNull.INSTANCE);
                case 5 -> bundle.getAsJsonObject("abilityPolicy").add("protection", com.google.gson.JsonNull.INSTANCE);
                case 6 -> bundle.add("credentialFile", com.google.gson.JsonNull.INSTANCE);
                default -> throw new AssertionError("unreachable test case");
            }
            writeBundle(bundlePath, new Gson().toJson(bundle).getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> StartupConfig.load(bootstrap), "invalid case " + invalidCase);
        }
    }

    @Test
    void acceptsExactBundleCeilingAndRejectsOneByteOver() throws Exception {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        Path bundle = bundlePath(bootstrap);
        byte[] base = Files.readAllBytes(bundle);
        byte[] exact = java.util.Arrays.copyOf(base, StartupConfig.MAX_BUNDLE_BYTES);
        java.util.Arrays.fill(exact, base.length, exact.length, (byte) ' ');
        writeBundle(bundle, exact);
        assertEquals(StartupConfig.MAX_BUNDLE_BYTES, Files.size(bundle));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(exact)),
                StartupConfig.load(bootstrap).configSetSha256());
        byte[] over = java.util.Arrays.copyOf(exact, exact.length + 1);
        over[over.length - 1] = ' ';
        writeBundle(bundle, over);
        assertThrows(IOException.class, () -> StartupConfig.load(bootstrap));
    }

    @Test
    void acceptsExactCredentialCeilingAndRejectsOneByteOverWithoutExposingContents() throws Exception {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        Path credential = bootstrap.getParent().resolve("credential.txt");
        Files.writeString(credential, "A".repeat(StartupConfig.MAX_CREDENTIAL_BYTES), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(credential, PosixFilePermissions.fromString("rw-------"));
        StartupConfig.load(bootstrap);

        String overLimit = "A".repeat(StartupConfig.MAX_CREDENTIAL_BYTES + 1);
        Files.writeString(credential, overLimit, StandardCharsets.UTF_8);
        IOException rejected = assertThrows(IOException.class, () -> StartupConfig.load(bootstrap));
        assertFalse(rejected.getMessage().contains(overLimit));
        assertFalse(rejected.getMessage().contains("AAAAAAAA"));
    }

    @Test
    void rejectsReferenceLongerThanTheUtf16BoundBeforeLaunchingAReader() throws Exception {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        String tooLong = "/" + "a".repeat(StartupConfig.MAX_REFERENCE_UNITS);
        IOException rejected = assertThrows(IOException.class, () -> writeAndLoadBootstrap(bootstrap,
                "schemaVersion=1\nconfigFile=" + tooLong + "\n"));
        assertFalse(rejected.getMessage().contains(tooLong));
    }

    @Test
    void configuredPersonaBlocksBeforeCredentialAccessAndIsNeverSilentlyIgnored() throws Exception {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        Path bundlePath = bundlePath(bootstrap);
        JsonObject bundle = parsedBundle(bundlePath);
        bundle.addProperty("personaFile", "/private/inactive-persona.txt");
        writeBundle(bundlePath, new Gson().toJson(bundle).getBytes(StandardCharsets.UTF_8));
        Files.delete(bootstrap.getParent().resolve("credential.txt"));
        StartupConfig.PersonaPublicationUnqualifiedException failure = assertThrows(
                StartupConfig.PersonaPublicationUnqualifiedException.class, () -> StartupConfig.load(bootstrap));
        assertFalse(failure.getMessage().contains("inactive-persona"));
        assertFalse(failure.getMessage().contains("credential"));
    }

    @Test
    void actualGodTemplatesKeepJavaPersonaBlockAndRenderNoGuidanceControlAsDenyAll() throws Exception {
        Path mainBootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        Path mainDirectory = mainBootstrap.getParent();
        Path mainBundle = bundlePath(mainBootstrap);
        Path missingCredential = mainDirectory.resolve("not-created-credential.txt");
        Path privatePersona = mainDirectory.resolve("fictional-private-persona.txt");
        String mainTemplate = renderGodTemplate("startup-config.json.template", missingCredential, privatePersona, "God");
        writeBundle(mainBundle, mainTemplate.getBytes(StandardCharsets.UTF_8));
        Files.delete(mainDirectory.resolve("credential.txt"));
        StartupConfig.PersonaPublicationUnqualifiedException blocked = assertThrows(
                StartupConfig.PersonaPublicationUnqualifiedException.class, () -> StartupConfig.load(mainBootstrap));
        assertFalse(blocked.getMessage().contains("fictional-private-persona"));
        assertFalse(blocked.getMessage().contains("not-created-credential"));
        assertFalse(Files.exists(missingCredential), "the configured-persona gate does not create or access credentials");

        Path controlBootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        Path controlDirectory = controlBootstrap.getParent();
        Path controlCredential = controlDirectory.resolve("credential.txt");
        byte[] randomCredential = new byte[32];
        new SecureRandom().nextBytes(randomCredential);
        Files.writeString(controlCredential, Base64.getUrlEncoder().withoutPadding().encodeToString(randomCredential), StandardCharsets.US_ASCII);
        Files.setPosixFilePermissions(controlCredential, PosixFilePermissions.fromString("rw-------"));
        String controlTemplate = renderGodTemplate("startup-config.no-guidance-control.json.template", controlCredential, null, "Archivist");
        writeBundle(bundlePath(controlBootstrap), controlTemplate.getBytes(StandardCharsets.UTF_8));

        byte[] exactBytes = Files.readAllBytes(bundlePath(controlBootstrap));
        StartupConfig control = StartupConfig.load(controlBootstrap);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(exactBytes)), control.configSetSha256());
        assertEquals("fixture-server", control.serverConfig().binding().serverId());
        assertEquals("fixture-world", control.serverConfig().binding().worldId());
        assertEquals("fixture-character", control.serverConfig().binding().characterId());
        assertEquals("fixture-agent", control.serverConfig().binding().agentId());
        assertEquals("Archivist", control.serverConfig().characterDisplayName(), "presentation rename does not require a God runtime branch");
        assertEquals(AbilityPolicy.denyAll(), control.abilityPolicy());
        assertFalse(AbilityAvailability.isImplemented(AbilityPolicy.OBSERVE, 1));
        assertFalse(AbilityAvailability.isImplemented(AbilityPolicy.LIGHTNING, 1));
        assertFalse(control.toString().contains("not-created-credential"));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(controlDirectory));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(bundlePath(controlBootstrap)));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(controlCredential));

        JsonObject visual = JsonParser.parseString(Files.readString(
                Path.of("presets/god/visual-only-policy.example.json.template"), StandardCharsets.UTF_8)
                .replace("<GRANT_ID>", "preset-visual-example")
                .replace("<CHARACTER_ID>", control.serverConfig().binding().characterId())
                .replace("<REQUESTER_UUID>", "00000000-0000-7000-8000-000000000041")).getAsJsonObject();
        AbilityPolicy parsedVisual = AbilityPolicy.parse(visual, control.serverConfig().binding().characterId());
        assertEquals(1, parsedVisual.grants().size());
        assertEquals(AbilityPolicy.LIGHTNING, parsedVisual.grants().getFirst().abilityId());
        assertTrue(parsedVisual.grants().getFirst().allowVisual());
        assertFalse(parsedVisual.grants().getFirst().allowDamage());
        assertEquals(null, parsedVisual.grants().getFirst().maxDamage());
        assertEquals(List.of(), parsedVisual.grants().getFirst().otherTargetIds());
    }

    @Test
    void rejectsUnsafeModesAndHardLinkedBundleFiles() throws Exception {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        Path bundle = bundlePath(bootstrap);
        Files.setPosixFilePermissions(bundle, PosixFilePermissions.fromString("rw-r-----"));
        assertThrows(IOException.class, () -> StartupConfig.load(bootstrap));
        Files.setPosixFilePermissions(bundle, PosixFilePermissions.fromString("rw-------"));
        Path secondLink = bootstrap.getParent().resolve("bundle-hardlink.json");
        Files.createLink(secondLink, bundle);
        assertThrows(IOException.class, () -> StartupConfig.load(bootstrap));
        Files.delete(secondLink);
        assertTrue(Files.isRegularFile(bundle));
    }

    @Test
    void rejectsSymlinkAndSpecialBundleReferencesBeforeOpeningThem() throws Exception {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, Map.of());
        Path directory = bootstrap.getParent();
        Path bundle = bundlePath(bootstrap);
        Path alias = directory.resolve("startup-link.json");
        Files.createSymbolicLink(alias, bundle);
        assertThrows(IOException.class, () -> writeAndLoadBootstrap(bootstrap,
                "schemaVersion=1\nconfigFile=" + alias + "\n"));
        Files.delete(alias);

        Path special = directory.resolve("startup-fifo.json");
        Process mkfifo = new ProcessBuilder("mkfifo", special.toString()).start();
        assertTrue(mkfifo.waitFor(2, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, mkfifo.exitValue());
        assertThrows(IOException.class, () -> writeAndLoadBootstrap(bootstrap,
                "schemaVersion=1\nconfigFile=" + special + "\n"));
    }

    @Test
    void directJavaCompatibilityConstructionRemainsDenyAllAndRejectsInvalidEndpointOrSecret() {
        CompanionConfig config = new CompanionConfig(java.net.URI.create("ws://127.0.0.1:18473"), new byte[32],
                new ConfiguredBinding("server", "world", "character", "agent"), "Hello {player}", "Archivist",
                InteractionPolicy.denyAll());
        assertEquals(AbilityPolicy.denyAll(), config.abilityPolicy());
        assertEquals("0".repeat(64), config.configSetSha256());
        assertThrows(IllegalArgumentException.class, () -> new CompanionConfig(java.net.URI.create("ws://example.invalid:18473"),
                new byte[32], new ConfiguredBinding("server", "world", "character", "agent"), "Hello {player}", "Archivist",
                InteractionPolicy.denyAll()));
        assertThrows(IllegalArgumentException.class, () -> new CompanionConfig(java.net.URI.create("ws://127.0.0.1:18473"),
                new byte[31], new ConfiguredBinding("server", "world", "character", "agent"), "Hello {player}", "Archivist",
                InteractionPolicy.denyAll()));
        assertThrows(IllegalArgumentException.class, () -> new CompanionConfig(java.net.URI.create("ws://127.0.0.1/" + "a".repeat(2_048)),
                new byte[32], new ConfiguredBinding("server", "world", "character", "agent"), "Hello {player}", "Archivist",
                InteractionPolicy.denyAll()));
    }

    private static Path bundlePath(Path bootstrap) { return bootstrap.getParent().resolve("startup.json"); }

    private static String renderGodTemplate(String name, Path credential, Path persona, String displayName) throws IOException {
        String source = Files.readString(Path.of("presets/god", name), StandardCharsets.UTF_8)
                .replace("<SERVER_ID>", "fixture-server")
                .replace("<WORLD_ID>", "fixture-world")
                .replace("<CHARACTER_ID>", "fixture-character")
                .replace("<AGENT_ID>", "fixture-agent")
                .replace("<DEPLOYMENT_ID>", org.worldspeaker.generated.DeploymentFacts.DEPLOYMENT_ID)
                .replace("<DEPLOYMENT_MANIFEST_SHA256>", org.worldspeaker.generated.DeploymentFacts.MANIFEST_SHA256)
                .replace("/ABSOLUTE_PRIVATE_DIRECTORY/<CREDENTIAL_FILE>", credential.toString())
                .replace("\"characterDisplayName\": \"God\"", "\"characterDisplayName\": \"" + displayName + "\"");
        if (persona != null) source = source.replace("/ABSOLUTE_PRIVATE_DIRECTORY/<PERSONA_FILE>", persona.toString());
        return source;
    }

    private static JsonObject bundleWithUuidLists(Path bootstrap, JsonElement allowedPlayers,
                                                   JsonElement requesterIds, JsonElement otherTargetIds) throws IOException {
        JsonObject bundle = parsedBundle(bundlePath(bootstrap));
        bundle.getAsJsonObject("server").add("conversationAllowedPlayers", allowedPlayers.deepCopy());
        JsonObject abilityPolicy = bundle.getAsJsonObject("abilityPolicy");
        JsonObject grant = new JsonObject();
        grant.addProperty("id", "uuid-corpus-grant");
        grant.addProperty("characterId", "fixture-character");
        grant.addProperty("abilityId", AbilityPolicy.OBSERVE);
        grant.addProperty("contractVersion", 1);
        grant.add("requesterIds", requesterIds.deepCopy());
        grant.add("otherTargetIds", otherTargetIds.deepCopy());
        JsonArray dimensions = new JsonArray();
        dimensions.add("minecraft:overworld");
        grant.add("dimensions", dimensions);
        JsonArray grants = new JsonArray();
        grants.add(grant);
        abilityPolicy.add("grants", grants);
        return bundle;
    }

    private static JsonArray uuidCorpusValues(JsonArray cases) {
        JsonArray values = new JsonArray();
        for (JsonElement item : cases) values.add(item.getAsJsonObject().get("value").deepCopy());
        return values;
    }

    private static void writeBundle(Path bundle, byte[] bytes) throws IOException {
        Files.write(bundle, bytes);
        Files.setPosixFilePermissions(bundle, PosixFilePermissions.fromString("rw-------"));
    }

    private static void writeBundleObject(Path path, JsonObject bundle) throws IOException {
        byte[] bytes = new GsonBuilder().serializeNulls().create().toJson(bundle).getBytes(StandardCharsets.UTF_8);
        writeBundle(path, bytes);
    }

    private static JsonObject parsedBundle(Path bundle) throws IOException {
        return JsonParser.parseString(Files.readString(bundle, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonElement expandNameCorpusValue(JsonElement value) {
        if (value.isJsonArray()) {
            com.google.gson.JsonArray expanded = new com.google.gson.JsonArray();
            for (JsonElement item : value.getAsJsonArray()) expanded.add(expandNameCorpusValue(item));
            return expanded;
        }
        if (!value.isJsonObject()) return value.deepCopy();
        JsonObject object = value.getAsJsonObject();
        if (object.has("repeatCodePoint") && object.has("repeatCount")) {
            int codePoint = Integer.parseInt(object.get("repeatCodePoint").getAsString(), 16);
            return new com.google.gson.JsonPrimitive(new String(Character.toChars(codePoint))
                    .repeat(object.get("repeatCount").getAsInt()));
        }
        if (object.has("repeatCharacter") && object.has("repeatCount")) {
            return new com.google.gson.JsonPrimitive(object.get("repeatCharacter").getAsString()
                    .repeat(object.get("repeatCount").getAsInt()));
        }
        JsonObject expanded = new JsonObject();
        for (var entry : object.entrySet()) expanded.add(entry.getKey(), expandNameCorpusValue(entry.getValue()));
        return expanded;
    }

    private static List<String> stringList(com.google.gson.JsonArray values) {
        List<String> result = new ArrayList<>();
        for (JsonElement value : values) result.add(value.getAsString());
        return List.copyOf(result);
    }

    private static void writeAndLoadBootstrap(Path bootstrap, String value) throws IOException {
        Files.writeString(bootstrap, value, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(bootstrap, PosixFilePermissions.fromString("rw-------"));
        StartupConfig.load(bootstrap);
    }
}
