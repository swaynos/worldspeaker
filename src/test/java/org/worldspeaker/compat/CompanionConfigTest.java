package org.worldspeaker.compat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldspeaker.bootstrap.NaturalAddressParser;
import org.worldspeaker.bootstrap.VanillaDialogue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionConfigTest {
    @TempDir
    Path temporaryDirectory;

    private Path verifiedBootstrapPath;
    private Path verifiedBundlePath;
    private Path verifiedCredentialPath;
    private byte[] verifiedBootstrapBytes;
    private StartupConfig verifiedSnapshot;

    @AfterEach
    void deleteVerifiedPrivateConfig() throws IOException {
        if (verifiedBootstrapPath != null) deletePrivateConfig(verifiedBootstrapPath);
    }

    @Test
    void loadRejectsEveryTemplateThatThePublicationRendererSanitizesToEmpty() throws IOException {
        for (String template : rendererEmptyTemplates()) {
            assertEquals("", VanillaDialogue.renderAcknowledgement(template, "TestPlayer", "Archivist"),
                    "fixture must exercise an empty production render");
            CompanionConfig.InvalidAcknowledgementTemplateException exception = assertThrows(
                    CompanionConfig.InvalidAcknowledgementTemplateException.class,
                    () -> load(template), "template must fail configuration validation");
            assertEquals("server.acknowledgementTemplate", exception.problem().field().label());
            assertTrue(exception.getMessage().contains("visible safe text"), "diagnostic is typed and value-free");
        }
    }

    @Test
    void loadAcceptsOrdinaryUnicodeAndExistingNewlineTabAndLengthRules() throws IOException {
        String template = "こんにちは, {player}! 🙂";
        CompanionConfig config = load(template);

        assertEquals(template, config.acknowledgementTemplate());
        assertEquals("[Worldspeaker] Archivist: こんにちは, TestPlayer! 🙂",
                    VanillaDialogue.renderAcknowledgement(config.acknowledgementTemplate(), "TestPlayer", "Archivist"));
        String controlsTemplate = "first\n{player}\tready";
        assertEquals(controlsTemplate, load(controlsTemplate).acknowledgementTemplate());
        assertEquals("[Worldspeaker] Archivist: first TestPlayer ready",
                    VanillaDialogue.renderAcknowledgement(controlsTemplate, "TestPlayer", "Archivist"));
        assertEquals(256, load("é".repeat(256)).acknowledgementTemplate().length());
        assertThrows(CompanionConfig.InvalidAcknowledgementTemplateException.class,
                () -> load("é".repeat(257)));

        String joinedEmojiTemplate = "Hello 👩‍🚀, {player}";
        assertEquals("[Worldspeaker] Archivist: Hello 👩‍🚀, TestPlayer",
                VanillaDialogue.renderAcknowledgement(load(joinedEmojiTemplate).acknowledgementTemplate(),
                        "TestPlayer", "Archivist"));
    }

    @Test
    void sharedAcknowledgementTemplateCorpusMatchesTheRendererAndCanonicalConstructor() throws IOException {
        JsonObject corpus = JsonParser.parseString(Files.readString(
                Path.of("compatibility/acknowledgement-template-v1-corpus.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        for (JsonElement element : corpus.getAsJsonArray("cases")) {
            JsonObject entry = element.getAsJsonObject();
            String id = entry.get("id").getAsString();
            String template = expandAcknowledgementTemplate(entry);
            boolean valid = entry.get("valid").getAsBoolean();
            if (valid) {
                assertTrue(VanillaDialogue.isUsableAcknowledgementTemplate(template), id + ": usable by the production renderer");
                assertEquals(template, directAcknowledgementConfig(template).acknowledgementTemplate(),
                        id + ": canonical constructor retains the exact template");
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> directAcknowledgementConfig(template), id + ": rejected by the canonical config constructor");
            }
            if (entry.has("playerName")) {
                assertEquals(entry.get("renderedLine").getAsString(),
                        VanillaDialogue.renderAcknowledgement(template, entry.get("playerName").getAsString(), "Test Character"),
                        id + ": runtime player substitution");
            }
        }
    }

    @Test
    void loadRejectsUnsupportedPlaceholdersAndDirectConstructionCannotBypassAckValidation() throws IOException {
        assertThrows(CompanionConfig.InvalidAcknowledgementTemplateException.class,
                () -> load("Hello {player} {unknown}"));
        assertThrows(IllegalArgumentException.class,
                () -> new CompanionConfig(URI.create("ws://127.0.0.1:65530"), new byte[32],
                        new ConfiguredBinding("server", "world", "character", "agent"), "\u202e",
                "Archivist", InteractionPolicy.denyAll()));
    }

    @Test
    void everyDirectConstructorUsesSharedEndpointSyntaxAndRetainsValidUriText() throws IOException {
        JsonObject corpus = com.google.gson.JsonParser.parseString(Files.readString(
                Path.of("compatibility/endpoint-syntax-v1-corpus.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        ConfiguredBinding binding = new ConfiguredBinding("server", "world", "character", "agent");
        InteractionPolicy interaction = InteractionPolicy.denyAll();
        byte[] secret = new byte[32];
        List<Function<URI, CompanionConfig>> constructors = List.of(
                endpoint -> new CompanionConfig(endpoint, secret, binding, "Hello {player}", "Archivist", interaction,
                        List.of(), 30, AttentionPolicy.disabled(), 30, CharacterChatStyle.PLAIN,
                        AbilityPolicy.denyAll(), "0".repeat(64)),
                endpoint -> new CompanionConfig(endpoint, secret, binding, "Hello {player}", "Archivist", interaction,
                        List.of(), 30, AttentionPolicy.disabled(), 30, CharacterChatStyle.PLAIN),
                endpoint -> new CompanionConfig(endpoint, secret, binding, "Hello {player}", "Archivist", interaction,
                        List.of(), 30, AttentionPolicy.disabled(), 30),
                endpoint -> new CompanionConfig(endpoint, secret, binding, "Hello {player}", "Archivist", interaction,
                        List.of(), 30, AttentionPolicy.disabled()),
                endpoint -> new CompanionConfig(endpoint, secret, binding, "Hello {player}", "Archivist", interaction),
                endpoint -> new CompanionConfig(endpoint, secret, binding, "Hello {player}", "Archivist", interaction, List.of()),
                endpoint -> new CompanionConfig(endpoint, secret, binding, "Hello {player}", "Archivist", interaction,
                        List.of(), 30));

        for (JsonElement caseElement : corpus.getAsJsonArray("cases")) {
            JsonObject entry = caseElement.getAsJsonObject();
            String id = entry.get("id").getAsString();
            String value = entry.get("endpoint").getAsString();
            boolean valid = entry.get("valid").getAsBoolean();
            URI endpoint;
            try {
                endpoint = URI.create(value);
            } catch (IllegalArgumentException malformedJavaUri) {
                assertFalse(valid, id + " must be representable by Java URI when valid");
                continue;
            }

            for (Function<URI, CompanionConfig> constructor : constructors) {
                if (valid) {
                    assertEquals(value, constructor.apply(endpoint).endpoint().toString(), id);
                } else {
                    assertThrows(IllegalArgumentException.class, () -> constructor.apply(endpoint), id);
                }
            }
        }
    }

    @Test
    void invalidTemplateDiagnosticIsPrivateActionableAndDoesNotIncludeTheValue() {
        String rawTemplate = "\u202e";
        String diagnostic = CompatibilityDiagnostic.invalidAcknowledgementTemplate().privateLogLine();

        assertTrue(diagnostic.contains("WS-COMPAT-ACK-TEMPLATE-INVALID"));
        assertTrue(diagnostic.contains("component=local-config"));
        assertTrue(diagnostic.contains("set acknowledgementTemplate"));
        assertFalse(diagnostic.contains(rawTemplate));
    }

    @Test
    void configRequiresVisibleCharacterNameAndLoadsOnlyExplicitCanonicalUuidGrants() throws IOException {
        UUID allowed = UUID.fromString("00000000-0000-4000-8000-000000000123");
        CompanionConfig config = load("Hello {player}", "The Archivist", allowed.toString());

        assertEquals("The Archivist", config.characterDisplayName());
        assertTrue(config.interactionPolicy().allows(allowed, InteractionPolicy.CONVERSE_PERMISSION));
        assertFalse(config.interactionPolicy().allows(UUID.randomUUID(), InteractionPolicy.CONVERSE_PERMISSION));
        assertFalse(config.interactionPolicy().allows(allowed, "worldspeaker.operator"));
        assertFalse(load("Hello {player}", "Archivist", null).interactionPolicy()
                .allows(allowed, InteractionPolicy.CONVERSE_PERMISSION), "missing grants deny by default");
        assertEquals(48, load("Hello {player}", "n".repeat(48), null).characterDisplayName().length());

        for (String invalid : List.of("", " ", "x".repeat(49), "line\nbreak", "\u202eName", "\u00a7cName",
                String.valueOf(Character.MIN_SURROGATE), "\u200b\ufeff", "\u2800", " \u2800 ",
                "\u115f\u1160\u3164\uffa0", "\u0301\u0903\u20dd", "🏻🏼🏽🏾🏿")) {
            assertThrows(CompanionConfig.InvalidCharacterDisplayNameException.class,
                    () -> load("Hello {player}", invalid, null));
        }
        assertThrows(CompanionConfig.InvalidInteractionPolicyConfigException.class,
                () -> load("Hello {player}", "Archivist", "not-a-uuid"));
        assertThrows(CompanionConfig.InvalidInteractionPolicyConfigException.class,
                () -> load("Hello {player}", "Archivist", allowed + "," + allowed));
    }

    @Test
    void configRejectsBrailleBlankAsEitherTheConfiguredNameOrAcknowledgement() throws IOException {
        String permittedPlayer = "00000000-0000-4000-8000-000000000123";
        assertThrows(CompanionConfig.InvalidCharacterDisplayNameException.class,
                () -> load("Hello {player}", "\u2800", permittedPlayer));
        assertThrows(CompanionConfig.InvalidAcknowledgementTemplateException.class,
                () -> load("\u2800", "Archivist", permittedPlayer));
        assertThrows(CompanionConfig.InvalidAcknowledgementTemplateException.class,
                () -> load("\u2800", "\u2800", permittedPlayer));
    }

    @Test
    void directConstructionCannotBypassDisplayNameOrPolicyValidation() {
        URI endpoint = URI.create("ws://127.0.0.1:65530");
        ConfiguredBinding binding = new ConfiguredBinding("server", "world", "character", "agent");

        assertThrows(IllegalArgumentException.class, () -> new CompanionConfig(endpoint, new byte[32], binding,
                "Hello {player}", "\u202e", InteractionPolicy.denyAll()));
        assertThrows(IllegalArgumentException.class, () -> new CompanionConfig(endpoint, new byte[32], binding,
                "Hello {player}", "Archivist", null));
    }

    @Test
    void invalidIdentityAndGrantDiagnosticsArePrivateAndValueFree() {
        String nameDiagnostic = CompatibilityDiagnostic.invalidCharacterDisplayName().privateLogLine();
        String grantDiagnostic = CompatibilityDiagnostic.invalidInteractionPolicy().privateLogLine();

        assertTrue(nameDiagnostic.contains("WS-COMPAT-CHARACTER-NAME-INVALID"));
        assertTrue(nameDiagnostic.contains("set characterDisplayName"));
        assertFalse(nameDiagnostic.contains("\u202e"));
        assertTrue(grantDiagnostic.contains("WS-COMPAT-CONVERSATION-POLICY-INVALID"));
        assertTrue(grantDiagnostic.contains("repair conversationAllowedPlayers"));
        assertFalse(grantDiagnostic.contains("00000000-0000-4000-8000-000000000123"));
    }

    @Test
    void chatStyleDefaultsPlainAndRejectsEveryNonExactValueWithoutEchoingIt() throws IOException {
        assertEquals(CharacterChatStyle.PLAIN, load("Hello {player}").characterChatStyle());
        assertEquals(CharacterChatStyle.PLAIN, loadWithAdditionalProperties(Map.of("characterChatStyle", "plain"))
                .characterChatStyle());
        assertEquals(CharacterChatStyle.BRACKETED,
                loadWithAdditionalProperties(Map.of("characterChatStyle", "bracketed")).characterChatStyle());

        for (String invalid : List.of("", " ", "PLAIN", "Bracketed", "json", "[bracketed]", "plain ",
                "x".repeat(1024))) {
            CompanionConfig.InvalidCharacterChatStyleConfigException failure = assertThrows(
                    CompanionConfig.InvalidCharacterChatStyleConfigException.class,
                    () -> loadWithAdditionalProperties(Map.of("characterChatStyle", invalid)));
            assertEquals("server.characterChatStyle", failure.problem().field().label());
            assertTrue(failure.getMessage().contains("plain or bracketed"));
        }

        String diagnostic = CompatibilityDiagnostic.invalidCharacterChatStyle().privateLogLine();
        assertTrue(diagnostic.contains("WS-COMPAT-CHARACTER-CHAT-STYLE-INVALID"));
        assertTrue(diagnostic.contains("characterChatStyle"));
        assertFalse(diagnostic.contains("private-style-value"));

        URI endpoint = URI.create("ws://127.0.0.1:65530");
        ConfiguredBinding binding = new ConfiguredBinding("server", "world", "character", "agent");
        assertThrows(IllegalArgumentException.class, () -> new CompanionConfig(endpoint, new byte[32], binding,
                "Hello {player}", "Archivist", InteractionPolicy.denyAll(), List.of(), 30,
                AttentionPolicy.disabled(), 30, null));
    }

    @Test
    void loadedPresentationChangesPreserveTheExactTypedCharacterKeyAndAllOtherSnapshotPolicy() throws IOException {
        UUID allowed = UUID.fromString("00000000-0000-4000-8000-000000000123");
        Path path = writeOwnerOnlyConfig(Map.of(
                "serverId", "StableServer", "worldId", "World_A", "characterId", "character-01", "agentId", "writer-A",
                "characterDisplayName", "Archivist", "characterAliases", "Old Name",
                "conversationAllowedPlayers", allowed.toString(), "characterChatStyle", "plain"));
        try {
            CompanionConfig before = CompanionConfig.load(path);
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
            ConfiguredBinding.CharacterKey expectedKey = new ConfiguredBinding.CharacterKey(
                    "StableServer", "World_A", "character-01");
            assertEquals(expectedKey, before.binding().characterKey());

            writeOwnerOnlyConfig(path, Map.of(
                    "serverId", "StableServer", "worldId", "World_A", "characterId", "character-01", "agentId", "writer-A",
                    "characterDisplayName", "The Archivist", "characterAliases", "Old Name,Keeper",
                    "conversationAllowedPlayers", allowed.toString(), "characterChatStyle", "bracketed"));
            CompanionConfig after = CompanionConfig.load(path);

            assertEquals(expectedKey, after.binding().characterKey());
            assertEquals(before.binding(), after.binding());
            assertEquals("writer-A", after.binding().agentId(), "agent association remains separate and unchanged");
            assertEquals(before.interactionPolicy(), after.interactionPolicy());
            assertEquals(before.executionBudgetSeconds(), after.executionBudgetSeconds());
            assertEquals(before.followupWindowSeconds(), after.followupWindowSeconds());
            assertEquals(before.attentionPolicy(), after.attentionPolicy());
            assertEquals(before.acknowledgementTemplate(), after.acknowledgementTemplate());
            assertEquals("Archivist", before.characterDisplayName(), "a loaded snapshot is immutable after file edit");
            assertEquals(List.of("Old Name"), before.characterAliases());
            assertEquals(CharacterChatStyle.PLAIN, before.characterChatStyle());
            assertEquals("The Archivist", after.characterDisplayName());
            assertEquals(List.of("Old Name", "Keeper"), after.characterAliases());
            assertEquals(CharacterChatStyle.BRACKETED, after.characterChatStyle());
            assertFalse(after.interactionPolicy().allows(allowed, "worldspeaker.operator"),
                    "presentation settings do not create authority grants");
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
        } finally {
            deletePrivateConfig(path);
        }
    }

    @Test
    void directConfigurationRequiresEveryLocalBindingIdAndCharacterKeyIsCaseSensitiveAndAgentIndependent() {
        URI endpoint = URI.create("ws://127.0.0.1:65530");
        for (ConfiguredBinding invalid : List.of(
                new ConfiguredBinding(null, "world", "character", "agent"),
                new ConfiguredBinding("server", " ", "character", "agent"),
                new ConfiguredBinding("server id", "world", "character", "agent"),
                new ConfiguredBinding("s".repeat(129), "world", "character", "agent"))) {
            assertThrows(IllegalArgumentException.class, () -> new CompanionConfig(endpoint, new byte[32], invalid,
                    "Hello {player}", "Archivist", InteractionPolicy.denyAll()));
        }

        ConfiguredBinding original = new ConfiguredBinding("Server", "world", "character", "agent-A");
        assertEquals(original.characterKey(), new ConfiguredBinding("Server", "world", "character", "agent-B").characterKey());
        assertNotEquals(original.characterKey(), new ConfiguredBinding("server", "world", "character", "agent-A").characterKey());
        assertNotEquals(original.characterKey(), new ConfiguredBinding("Server", "world", "Character", "agent-A").characterKey());
    }

    @Test
    void ownerOnlyConfigurationFileSizeBoundaryIs4096Bytes() throws IOException {
        Path path = writeOwnerOnlyConfig(Map.of());
        try {
            byte[] original = Files.readAllBytes(path);
            assertTrue(original.length < 4096);
            Files.write(path, "\n".repeat(4096 - original.length).getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.APPEND);
            assertEquals(4096, Files.size(path));
            CompanionConfig.load(path);
            Files.writeString(path, "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            assertEquals(4097, Files.size(path));
            IOException failure = assertThrows(IOException.class, () -> CompanionConfig.load(path));
            String diagnostic = failure.getMessage();
            assertNotNull(diagnostic, "private-reader rejection must provide a value-free diagnostic");
            assertFalse(diagnostic.isBlank(), "private-reader rejection must provide a value-free diagnostic");
            String configReference = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .filter(line -> line.startsWith("configFile="))
                    .map(line -> line.substring("configFile=".length()))
                    .findFirst().orElseThrow();
            assertFalse(diagnostic.contains(path.toString()), "diagnostic must not disclose the rejected file path");
            assertFalse(diagnostic.contains(configReference), "diagnostic must not disclose a referenced file path");
            assertFalse(diagnostic.contains("schemaVersion=1"), "diagnostic must not disclose rejected file values");
            assertFalse(diagnostic.contains("configFile="), "diagnostic must not disclose rejected file values");
        } finally {
            deletePrivateConfig(path);
        }
    }

    @Test
    void unsupportedPluralCharacterAndPersonaAuthorityKeysAreRejectedAsUnknownConfiguration() throws IOException {
        for (String unsupported : List.of("characters", "characterIds", "agents", "personaAuthorityGrants")) {
            assertThrows(IOException.class, () -> loadWithAdditionalProperties(Map.of(unsupported, "ignored")), unsupported);
        }
    }

    @Test
    void optionalAliasesAreBoundedNormalizedUniqueVisibleAndRestartScoped() throws IOException {
        ConfiguredBinding binding = new ConfiguredBinding("offline-test", "offline-test", "offline-test", "offline-test");
        CompanionConfig noAliases = load("Hello {player}", "Archivist", null, "");
        assertEquals(List.of(), noAliases.characterAliases(), "absent and empty alias settings mean none");
        assertEquals(binding, noAliases.binding());

        CompanionConfig config = load("Hello {player}", "Archivist", null,
                "  The Scribe  ,Old Name,Archive Keeper");
        assertEquals(List.of("The Scribe", "Old Name", "Archive Keeper"), config.characterAliases());
        assertEquals(binding, config.binding(), "renaming and alias updates do not replace stable identity");
        assertEquals(8, load("Hello {player}", "Archivist", null,
                String.join(",", List.of("Alias0", "Alias1", "Alias2", "Alias3", "Alias4", "Alias5", "Alias6", "Alias7")))
                .characterAliases().size());
        assertEquals(48, load("Hello {player}", "Archivist", null, "a".repeat(48)).characterAliases().get(0).length());
        assertEquals(384, load("Hello {player}", "Archivist", null,
                String.join(",", List.of("a".repeat(48), "b".repeat(48), "c".repeat(48), "d".repeat(48),
                        "e".repeat(48), "f".repeat(48), "g".repeat(48), "h".repeat(48))))
                .characterAliases().stream().mapToInt(String::length).sum());

        for (String invalid : List.of(",Alias", "Alias,", "Alias,,Other", " ", "x".repeat(49),
                String.join(",", List.of("A", "B", "C", "D", "E", "F", "G", "H", "I")),
                "café,Cafe\u0301", "ALIAS,alias", "Archivist", "Bad\nName", "Bad\u200bName", "\u2800")) {
            assertThrows(CompanionConfig.InvalidCharacterAliasesConfigException.class,
                    () -> load("Hello {player}", "Archivist", null, invalid), invalid);
        }
        assertThrows(CompanionConfig.InvalidCharacterAliasesConfigException.class,
                () -> load("Hello {player}", "Archivist", null, "a".repeat(49) + ",Other"));
        assertThrows(CompanionConfig.InvalidCharacterAliasesConfigException.class,
                () -> load("Hello {player}", "Archivist", null,
                String.join(",", List.of("a".repeat(48), "b".repeat(48), "c".repeat(48), "d".repeat(48),
                                "e".repeat(48), "f".repeat(48), "g".repeat(48), "h".repeat(49)))));
    }

    @Test
    void directConstructionCopiesAndValidatesAliasesWithoutChangingTheBinding() {
        URI endpoint = URI.create("ws://127.0.0.1:65530");
        ConfiguredBinding binding = new ConfiguredBinding("server", "world", "stable-character", "agent");
        List<String> aliases = new ArrayList<>(List.of("Old Name"));
        CompanionConfig config = new CompanionConfig(endpoint, new byte[32], binding, "Hello {player}",
                "Archivist", InteractionPolicy.denyAll(), aliases);
        aliases.add("Added later");
        assertEquals(List.of("Old Name"), config.characterAliases());
        assertEquals(binding, config.binding());

        for (List<String> invalid : List.of(List.of("Archivist"), List.of("Alias", "alias"),
                List.of("Alias", ""), List.of("x".repeat(49)), List.of("Bad\u202eName"))) {
            assertThrows(IllegalArgumentException.class, () -> new CompanionConfig(endpoint, new byte[32], binding,
                    "Hello {player}", "Archivist", InteractionPolicy.denyAll(), invalid));
        }
    }

    @Test
    void followupWindowDefaultsToThirtySecondsAndAcceptsOnlyUnsignedIntegersFromZeroToThirty() throws IOException {
        assertEquals(30, load("Hello {player}").followupWindowSeconds());
        assertEquals(0, loadWithFollowupWindow("0").followupWindowSeconds());
        assertEquals(30, loadWithFollowupWindow("30").followupWindowSeconds());
        for (String invalid : List.of("", " ", "+1", "-1", "1.5", "31", "999999999999999999999999")) {
            CompanionConfig.InvalidFollowupWindowConfigException failure = assertThrows(
                    CompanionConfig.InvalidFollowupWindowConfigException.class,
                    () -> loadWithFollowupWindow(invalid), invalid);
            assertEquals("server.followupWindowSeconds", failure.problem().field().label());
            assertTrue(failure.getMessage().contains("integer 0..30"));
        }

        URI endpoint = URI.create("ws://127.0.0.1:65530");
        ConfiguredBinding binding = new ConfiguredBinding("server", "world", "character", "agent");
        assertEquals(0, new CompanionConfig(endpoint, new byte[32], binding, "Hello {player}", "Archivist",
                InteractionPolicy.denyAll(), List.of(), 0).followupWindowSeconds());
        assertThrows(IllegalArgumentException.class, () -> new CompanionConfig(endpoint, new byte[32], binding,
                "Hello {player}", "Archivist", InteractionPolicy.denyAll(), List.of(), 31));

        String diagnostic = CompatibilityDiagnostic.invalidFollowupWindow().privateLogLine();
        assertTrue(diagnostic.contains("WS-COMPAT-FOLLOWUP-WINDOW-INVALID"));
        assertTrue(diagnostic.contains("followupWindowSeconds"));
    }

    @Test
    void executionBudgetDefaultsToThirtyAndAcceptsOnlyOneThroughThirtySeconds() throws IOException {
        assertEquals(30, load("Hello {player}").executionBudgetSeconds());
        assertEquals(1, loadWithExecutionBudget("1").executionBudgetSeconds());
        assertEquals(30, loadWithExecutionBudget("30").executionBudgetSeconds());
        for (String invalid : List.of("", " ", "+1", "-1", "1.5", "0", "31", "999999999999999999999999")) {
            CompanionConfig.InvalidExecutionBudgetConfigException failure = assertThrows(
                    CompanionConfig.InvalidExecutionBudgetConfigException.class,
                    () -> loadWithExecutionBudget(invalid), invalid);
            assertEquals("server.executionBudgetSeconds", failure.problem().field().label());
            assertTrue(failure.getMessage().contains("integer 1..30"));
        }

        URI endpoint = URI.create("ws://127.0.0.1:65530");
        ConfiguredBinding binding = new ConfiguredBinding("server", "world", "character", "agent");
        for (int invalid : List.of(0, 31, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
            assertThrows(IllegalArgumentException.class, () -> new CompanionConfig(endpoint, new byte[32], binding,
                    "Hello {player}", "Archivist", InteractionPolicy.denyAll(), List.of(), 30,
                    AttentionPolicy.disabled(), invalid));
        }
    }

    @Test
    void attentionConfigurationDefaultsOffLoadsAtomicallyAndRejectsInvalidValuesPrivately() throws IOException {
        assertFalse(load("Hello {player}").attentionPolicy().enabled());
        CompanionConfig enabled = loadWithAttention(Map.of(
                "attentionSubscriptions", "player_connection,player_death",
                "attentionMaxEvents", "64",
                "attentionMaxAgeSeconds", "60",
                "attentionMaxBytes", "8192",
                "attentionMaxEventsPerSecond", "8",
                "attentionMaxEventsPerPlayerSecond", "4"));
        assertEquals(Set.of(AttentionPolicy.Subscription.PLAYER_CONNECTION, AttentionPolicy.Subscription.PLAYER_DEATH),
                enabled.attentionPolicy().subscriptions());
        assertEquals(64, enabled.attentionPolicy().maxEvents());
        assertEquals(60, enabled.attentionPolicy().maxAgeSeconds());

        for (Map<String, String> invalid : List.of(
                Map.of("attentionSubscriptions", "player_connection,unknown"),
                Map.of("attentionSubscriptions", "player_death,player_death"),
                Map.of("attentionSubscriptions", "Player_death"),
                Map.of("attentionMaxEvents", "65"),
                Map.of("attentionMaxAgeSeconds", "-1"),
                Map.of("attentionMaxBytes", "1.5"),
                Map.of("attentionMaxEventsPerSecond", "2", "attentionMaxEventsPerPlayerSecond", "3"))) {
            CompanionConfig.InvalidAttentionPolicyConfigException failure = assertThrows(
                    CompanionConfig.InvalidAttentionPolicyConfigException.class,
                    () -> loadWithAttention(invalid));
            assertNotNull(failure.problem());
            assertTrue(failure.problem().field().label().startsWith("server.attention"));
            assertFalse(failure.getMessage().contains("unknown"));
        }

        assertThrows(IllegalArgumentException.class, () -> new CompanionConfig(URI.create("ws://127.0.0.1:1"),
                new byte[32], new ConfiguredBinding("server", "world", "character", "agent"),
                "Hello {player}", "Archivist", InteractionPolicy.denyAll(), List.of(), 30, null));
    }

    @Test
    void everyLoadedAndDirectlyConstructedDisplayNameAndAliasIsNaturallyAddressable() throws IOException {
        URI endpoint = URI.create("ws://127.0.0.1:65530");
        ConfiguredBinding binding = new ConfiguredBinding("server", "world", "stable-character", "agent");
        ConfiguredBinding loadedBinding = new ConfiguredBinding("offline-test", "offline-test", "offline-test", "offline-test");
        List<Map.Entry<String, String>> cases = List.of(
                Map.entry("Archivist", "ARCHIVIST"),
                Map.entry("守り手", "守り手"),
                Map.entry("Café", "Cafe\u0301"),
                Map.entry("İris", "i\u0307RIS"),
                Map.entry("𐐀", "𐐀"),
                Map.entry("ب\u064e", "ب\u064e"),
                Map.entry("İ".repeat(48), "İ".repeat(48)),
                Map.entry("The Writer's Room", "The Writer's Room"));

        for (Map.Entry<String, String> testCase : cases) {
            String name = testCase.getKey();
            String literalInput = testCase.getValue();
            CompanionConfig loadedDisplay = load("Hello {player}", name, null, "");
            CompanionConfig directDisplay = new CompanionConfig(endpoint, new byte[32], binding, "Hello {player}",
                    name, InteractionPolicy.denyAll());
            assertEquals(loadedBinding, loadedDisplay.binding());
            assertEquals(binding, directDisplay.binding());
            assertAddressableAtAllPositions(literalInput, name, List.of(loadedDisplay.characterDisplayName()));
            assertAddressableAtAllPositions(literalInput, name, List.of(directDisplay.characterDisplayName()));

            CompanionConfig loadedAlias = load("Hello {player}", "Worldspeaker", null, name);
            CompanionConfig directAlias = new CompanionConfig(endpoint, new byte[32], binding, "Hello {player}",
                    "Worldspeaker", InteractionPolicy.denyAll(), List.of(name));
            assertEquals(loadedBinding, loadedAlias.binding());
            assertEquals(binding, directAlias.binding());
            List<String> loadedNames = new ArrayList<>(List.of(loadedAlias.characterDisplayName()));
            loadedNames.addAll(loadedAlias.characterAliases());
            List<String> directNames = new ArrayList<>(List.of(directAlias.characterDisplayName()));
            directNames.addAll(directAlias.characterAliases());
            assertAddressableAtAllPositions(literalInput, name, loadedNames);
            assertAddressableAtAllPositions(literalInput, name, directNames);
        }
    }

    @Test
    void loaderAndDirectConstructionRejectParserUnsafeNamesPrivately() throws IOException {
        URI endpoint = URI.create("ws://127.0.0.1:65530");
        ConfiguredBinding binding = new ConfiguredBinding("server", "world", "stable-character", "agent");
        for (String unsafeName : List.of("A\u200dB", "A\u200cB", "A\u2800B")) {
            assertFalse(NaturalAddressParser.isValidConfiguredName(unsafeName), unsafeName);
            assertFalse(VanillaDialogue.isValidCharacterDisplayName(unsafeName), unsafeName);
            assertEquals(NaturalAddressParser.Disposition.AMBIENT,
                    NaturalAddressParser.parse(unsafeName + ", help", List.of(unsafeName)).disposition(), unsafeName);

            CompanionConfig.InvalidCharacterDisplayNameException displayFailure = assertThrows(
                    CompanionConfig.InvalidCharacterDisplayNameException.class,
                    () -> load("Hello {player}", unsafeName, null));
            assertFalse(displayFailure.getMessage().contains(unsafeName));
            CompanionConfig.InvalidCharacterAliasesConfigException aliasFailure = assertThrows(
                    CompanionConfig.InvalidCharacterAliasesConfigException.class,
                    () -> load("Hello {player}", "Archivist", null, unsafeName));
            assertFalse(aliasFailure.getMessage().contains(unsafeName));

            IllegalArgumentException directDisplayFailure = assertThrows(IllegalArgumentException.class,
                    () -> new CompanionConfig(endpoint, new byte[32], binding, "Hello {player}", unsafeName,
                            InteractionPolicy.denyAll()));
            assertFalse(directDisplayFailure.getMessage().contains(unsafeName));
            IllegalArgumentException directAliasFailure = assertThrows(IllegalArgumentException.class,
                    () -> new CompanionConfig(endpoint, new byte[32], binding, "Hello {player}", "Archivist",
                            InteractionPolicy.denyAll(), List.of(unsafeName)));
            assertFalse(directAliasFailure.getMessage().contains(unsafeName));
        }
    }

    @Test
    void invalidAliasDiagnosticIsPrivateAndValueFree() {
        String sentinel = "Private Alias Sentinel";
        String diagnostic = CompatibilityDiagnostic.invalidCharacterAliases().privateLogLine();

        assertTrue(diagnostic.contains("WS-COMPAT-CHARACTER-ALIASES-INVALID"));
        assertTrue(diagnostic.contains("repair characterAliases"));
        assertFalse(diagnostic.contains(sentinel));
    }

    private CompanionConfig load(String template) throws IOException {
        return load(template, "Archivist", null);
    }

    private CompanionConfig load(String template, String characterDisplayName, String allowedPlayers) throws IOException {
        return load(template, characterDisplayName, allowedPlayers, null);
    }

    private CompanionConfig load(String template, String characterDisplayName, String allowedPlayers,
                                 String characterAliases) throws IOException {
        return load(template, characterDisplayName, allowedPlayers, characterAliases, null);
    }

    private CompanionConfig loadWithFollowupWindow(String value) throws IOException {
        return load("Hello {player}", "Archivist", null, null, value);
    }

    private CompanionConfig loadWithExecutionBudget(String value) throws IOException {
        return load("Hello {player}", "Archivist", null, null, null,
                Map.of("executionBudgetSeconds", value));
    }

    private CompanionConfig loadWithAdditionalProperties(Map<String, String> additionalProperties) throws IOException {
        return load("Hello {player}", "Archivist", null, null, null, additionalProperties);
    }

    private Path writeOwnerOnlyConfig(Map<String, String> overrides) throws IOException {
        Path directory = Files.createTempDirectory(temporaryDirectory.toRealPath(), "worldspeaker-private-config-");
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path path = directory.resolve("worldspeaker.properties");
        writeOwnerOnlyConfig(path, overrides);
        return path;
    }

    private void writeOwnerOnlyConfig(Path path, Map<String, String> overrides) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("endpoint", "ws://127.0.0.1:65530");
        properties.setProperty("bearerSecret", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        properties.setProperty("serverId", "offline-test");
        properties.setProperty("worldId", "offline-test");
        properties.setProperty("characterId", "offline-test");
        properties.setProperty("agentId", "offline-test");
        properties.setProperty("acknowledgementTemplate", "Hello {player}");
        properties.setProperty("characterDisplayName", "Archivist");
        overrides.forEach(properties::setProperty);

        Path directory = path.getParent();
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path bundlePath = directory.resolve("startup.json");
        Path credentialPath = directory.resolve("credential.txt");
        Files.writeString(credentialPath, properties.getProperty("bearerSecret"), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(credentialPath, PosixFilePermissions.fromString("rw-------"));

        JsonObject bundle = new JsonObject();
        bundle.addProperty("schemaVersion", 1);
        JsonObject binding = new JsonObject();
        binding.addProperty("serverId", properties.getProperty("serverId"));
        binding.addProperty("worldId", properties.getProperty("worldId"));
        binding.addProperty("characterId", properties.getProperty("characterId"));
        binding.addProperty("agentId", properties.getProperty("agentId"));
        bundle.add("binding", binding);

        JsonObject server = new JsonObject();
        server.addProperty("endpoint", properties.getProperty("endpoint"));
        server.addProperty("acknowledgementTemplate", properties.getProperty("acknowledgementTemplate"));
        server.addProperty("characterDisplayName", properties.getProperty("characterDisplayName"));
        server.add("conversationAllowedPlayers", commaList(properties.getProperty("conversationAllowedPlayers")));
        if (properties.containsKey("characterAliases")) {
            server.add("characterAliases", commaList(properties.getProperty("characterAliases")));
        }
        addNumericProperty(server, properties, "followupWindowSeconds");
        addNumericProperty(server, properties, "executionBudgetSeconds");
        if (properties.containsKey("characterChatStyle")) {
            server.addProperty("characterChatStyle", properties.getProperty("characterChatStyle"));
        }
        JsonObject attention = new JsonObject();
        if (properties.containsKey("attentionSubscriptions")) {
            attention.add("subscriptions", commaList(properties.getProperty("attentionSubscriptions")));
        }
        addNumericProperty(attention, properties, "attentionMaxEvents", "maxEvents");
        addNumericProperty(attention, properties, "attentionMaxAgeSeconds", "maxAgeSeconds");
        addNumericProperty(attention, properties, "attentionMaxBytes", "maxBytes");
        addNumericProperty(attention, properties, "attentionMaxEventsPerSecond", "maxEventsPerSecond");
        addNumericProperty(attention, properties, "attentionMaxEventsPerPlayerSecond", "maxEventsPerPlayerSecond");
        if (!attention.isEmpty()) server.add("attention", attention);
        bundle.add("server", server);

        JsonObject companion = new JsonObject();
        companion.addProperty("bindHost", "127.0.0.1");
        companion.addProperty("port", 65530);
        companion.addProperty("deploymentId", org.worldspeaker.generated.DeploymentFacts.DEPLOYMENT_ID);
        companion.addProperty("manifestSha256", org.worldspeaker.generated.DeploymentFacts.MANIFEST_SHA256);
        bundle.add("companion", companion);
        JsonObject abilityPolicy = new JsonObject();
        abilityPolicy.addProperty("schemaVersion", 1);
        abilityPolicy.addProperty("revision", 0);
        abilityPolicy.add("grants", new JsonArray());
        abilityPolicy.add("denials", new JsonArray());
        abilityPolicy.add("protection", new JsonObject());
        bundle.add("abilityPolicy", abilityPolicy);
        bundle.addProperty("credentialFile", credentialPath.toString());

        Set<String> known = Set.of("endpoint", "bearerSecret", "serverId", "worldId", "characterId", "agentId",
                "acknowledgementTemplate", "characterDisplayName", "conversationAllowedPlayers", "characterAliases",
                "followupWindowSeconds", "executionBudgetSeconds", "attentionSubscriptions", "attentionMaxEvents",
                "attentionMaxAgeSeconds", "attentionMaxBytes", "attentionMaxEventsPerSecond",
                "attentionMaxEventsPerPlayerSecond", "characterChatStyle");
        properties.stringPropertyNames().stream().filter(key -> !known.contains(key))
                .forEach(key -> bundle.addProperty(key, properties.getProperty(key)));

        Files.writeString(bundlePath, escapeUnpairedSurrogates(new Gson().toJson(bundle)), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(bundlePath, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(path, "schemaVersion=1\nconfigFile=" + bundlePath + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    private static JsonArray commaList(String value) {
        JsonArray result = new JsonArray();
        if (value != null && !value.isEmpty()) {
            for (String item : value.split(",", -1)) result.add(item);
        }
        return result;
    }

    private static void addNumericProperty(JsonObject destination, Properties properties, String key) {
        addNumericProperty(destination, properties, key, key);
    }

    private static void addNumericProperty(JsonObject destination, Properties properties, String sourceKey, String targetKey) {
        if (!properties.containsKey(sourceKey)) return;
        String value = properties.getProperty(sourceKey);
        try {
            if (value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) {
                destination.add(targetKey, new JsonPrimitive(new java.math.BigDecimal(value)));
                return;
            }
        } catch (NumberFormatException ignored) {
            // Keep malformed text as the wrong JSON type so the real loader rejects it privately.
        }
        destination.addProperty(targetKey, value);
    }

    private static void deletePrivateConfig(Path path) throws IOException {
        Path directory = path.getParent();
        Files.deleteIfExists(path);
        Files.deleteIfExists(directory.resolve("startup.json"));
        Files.deleteIfExists(directory.resolve("credential.txt"));
        Files.deleteIfExists(directory);
    }

    private static String escapeUnpairedSurrogates(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
                    result.append(current).append(value.charAt(++index));
                } else {
                    result.append(String.format("\\u%04x", (int) current));
                }
            } else if (Character.isLowSurrogate(current)) {
                result.append(String.format("\\u%04x", (int) current));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private CompanionConfig load(String template, String characterDisplayName, String allowedPlayers,
                                 String characterAliases, String followupWindowSeconds) throws IOException {
        return load(template, characterDisplayName, allowedPlayers, characterAliases, followupWindowSeconds, Map.of());
    }

    private CompanionConfig loadWithAttention(Map<String, String> attention) throws IOException {
        return load("Hello {player}", "Archivist", null, null, null, attention);
    }

    private CompanionConfig load(String template, String characterDisplayName, String allowedPlayers,
                                 String characterAliases, String followupWindowSeconds,
                                 Map<String, String> additionalProperties) throws IOException {
        ensureVerifiedPrivateConfig();
        Map<String, String> overrides = new java.util.HashMap<>();
        overrides.put("acknowledgementTemplate", template);
        overrides.put("characterDisplayName", characterDisplayName);
        if (allowedPlayers != null) overrides.put("conversationAllowedPlayers", allowedPlayers);
        if (characterAliases != null) overrides.put("characterAliases", characterAliases);
        if (followupWindowSeconds != null) overrides.put("followupWindowSeconds", followupWindowSeconds);
        overrides.putAll(additionalProperties);
        writeOwnerOnlyConfig(verifiedBootstrapPath, overrides);
        byte[] bundleBytes = PrivateFileReader.read(verifiedBundlePath, StartupConfig.MAX_BUNDLE_BYTES, false);
        return StartupConfig.loadForTest(verifiedBootstrapPath, (path, maximum, credential) -> {
            if (path.equals(verifiedBootstrapPath) && maximum == StartupConfig.MAX_BOOTSTRAP_BYTES && !credential) {
                return verifiedBootstrapBytes;
            }
            if (path.equals(verifiedBundlePath) && maximum == StartupConfig.MAX_BUNDLE_BYTES && !credential) {
                return bundleBytes;
            }
            if (path.equals(verifiedCredentialPath) && maximum == StartupConfig.MAX_CREDENTIAL_BYTES && credential) {
                return PrivateFileReader.read(path, maximum, true);
            }
            throw new AssertionError("unexpected private configuration read profile");
        }).serverConfig();
    }

    private void ensureVerifiedPrivateConfig() throws IOException {
        if (verifiedBootstrapPath != null) return;
        Path directory = Files.createTempDirectory(temporaryDirectory.toRealPath(), "worldspeaker-private-config-");
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        verifiedBootstrapPath = directory.resolve("worldspeaker.properties");
        verifiedBundlePath = directory.resolve("startup.json");
        verifiedCredentialPath = directory.resolve("credential.txt");
        writeOwnerOnlyConfig(verifiedBootstrapPath, Map.of());

        // One actual public loader pass verifies the complete private-file set. The
        // bootstrap bytes are then captured through the actual helper for parser
        // cases; each mutated bundle and any reached credential still use the helper.
        verifiedSnapshot = StartupConfig.load(verifiedBootstrapPath);
        verifiedBootstrapBytes = PrivateFileReader.read(verifiedBootstrapPath,
                StartupConfig.MAX_BOOTSTRAP_BYTES, false);
        byte[] verifiedBundleBytes = PrivateFileReader.read(verifiedBundlePath,
                StartupConfig.MAX_BUNDLE_BYTES, false);
        assertEquals(verifiedSnapshot.configSetSha256(), sha256(verifiedBundleBytes),
                "baseline bundle bytes are the actual-helper bytes validated in the snapshot");
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static List<String> rendererEmptyTemplates() {
        List<String> templates = new ArrayList<>();
        for (int codePoint = 0; codePoint <= 0x1f; codePoint++) {
            templates.add(new String(Character.toChars(codePoint)));
        }
        for (int codePoint = 0x7f; codePoint <= 0x9f; codePoint++) {
            templates.add(new String(Character.toChars(codePoint)));
        }
        templates.add("\u061c");
        templates.add("\u200e");
        templates.add("\u200f");
        for (char code = '\u202a'; code <= '\u202e'; code++) templates.add(String.valueOf(code));
        for (char code = '\u2066'; code <= '\u206f'; code++) templates.add(String.valueOf(code));
        templates.add("\u00a7");
        String legacyCodes = "0123456789abcdefklmnorxABCDEFKLMNORX";
        for (int index = 0; index < legacyCodes.length(); index++) {
            templates.add("\u00a7" + legacyCodes.charAt(index));
        }
        templates.add(String.valueOf(Character.MIN_SURROGATE));
        templates.add(String.valueOf(Character.MAX_SURROGATE));
        templates.add("\u2028");
        templates.add("\u2029");
        templates.add(" ");
        templates.add("\u00a0\u1680\u2000\u202f\u205f\u3000");
        templates.add("\n");
        templates.add("\r");
        templates.add("\t");
        templates.add("\u200b\ufeff\u00a0");
        templates.add("\u2800");
        templates.add("\u115f\u1160\u3164\uffa0");
        templates.add("\u00ad\u034f");
        templates.add("\u0301\u0903\u20dd");
        templates.add("🏻🏼🏽🏾🏿");

        StringBuilder formatOnly = new StringBuilder();
        for (int codePoint = Character.MIN_CODE_POINT; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (Character.getType(codePoint) != Character.FORMAT) continue;
            if (formatOnly.length() + Character.charCount(codePoint) > 200) {
                templates.add(formatOnly.toString());
                formatOnly.setLength(0);
            }
            formatOnly.appendCodePoint(codePoint);
        }
        if (!formatOnly.isEmpty()) templates.add(formatOnly.toString());
        return templates;
    }

    private static String expandAcknowledgementTemplate(JsonObject entry) {
        if (entry.has("template")) return entry.get("template").getAsString();
        JsonObject repeated = entry.getAsJsonObject("templateRepeat");
        return repeated.get("text").getAsString().repeat(repeated.get("count").getAsInt());
    }

    private static CompanionConfig directAcknowledgementConfig(String template) {
        return new CompanionConfig(URI.create("ws://127.0.0.1:65530"), new byte[32],
                new ConfiguredBinding("server", "world", "character", "agent"), template,
                "Archivist", InteractionPolicy.denyAll());
    }

    private static void assertAddressableAtAllPositions(String literalInput, String configuredName,
                                                         List<String> configuredNames) {
        for (String input : List.of(literalInput + ", help", "Please " + literalInput + ", help",
                "Please help, " + literalInput)) {
            NaturalAddressParser.Result result = NaturalAddressParser.parse(input, configuredNames);
            assertEquals(NaturalAddressParser.Disposition.ADDRESSED, result.disposition(), input);
            assertEquals(configuredName, result.matchedName(), input);
        }
    }
}
