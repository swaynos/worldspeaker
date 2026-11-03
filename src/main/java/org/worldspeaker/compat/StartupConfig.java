package org.worldspeaker.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.worldspeaker.bootstrap.VanillaDialogue;
import org.worldspeaker.generated.DeploymentFacts;
import org.worldspeaker.integration.StrictJsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** One all-or-nothing startup bundle and its independently referenced local credential. */
public record StartupConfig(
        CompanionConfig serverConfig,
        CompanionSettings companion,
        AbilityPolicy abilityPolicy,
        Path configFile,
        Path credentialFile,
        String configSetSha256,
        String personaFile) {
    public static final int MAX_BOOTSTRAP_BYTES = 4_096;
    public static final int MAX_BUNDLE_BYTES = 32_768;
    public static final int MAX_CREDENTIAL_BYTES = 86;
    public static final int MAX_PERSONA_BYTES = 8_192;
    public static final int MAX_REFERENCE_UNITS = 1_024;
    private static final int MAX_NESTING = 8;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern CREDENTIAL = Pattern.compile("[A-Za-z0-9_-]{43,86}");
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Set<String> BOOTSTRAP_KEYS = Set.of("schemaVersion", "configFile");
    private static final Set<String> LEGACY_BOOTSTRAP_KEYS = Set.of("endpoint", "bearerSecret", "serverId", "worldId",
            "characterId", "agentId", "acknowledgementTemplate", "characterDisplayName", "conversationAllowedPlayers",
            "characterAliases", "followupWindowSeconds", "executionBudgetSeconds", "attentionSubscriptions",
            "attentionMaxEvents", "attentionMaxAgeSeconds", "attentionMaxBytes", "attentionMaxEventsPerSecond",
            "attentionMaxEventsPerPlayerSecond", "characterChatStyle");

    public StartupConfig {
        if (serverConfig == null) throw new IllegalArgumentException("serverConfig is required");
        if (companion == null) throw new IllegalArgumentException("companion settings are required");
        if (abilityPolicy == null) throw new IllegalArgumentException("abilityPolicy is required");
        if (configFile == null) throw new IllegalArgumentException("configFile is required");
        if (credentialFile == null) throw new IllegalArgumentException("credentialFile is required");
        if (configSetSha256 == null || !SHA256.matcher(configSetSha256).matches()) {
            throw new IllegalArgumentException("configSetSha256 must be 64 lowercase hexadecimal characters");
        }
    }

    public static StartupConfig load(Path bootstrapPath) throws IOException {
        return load(bootstrapPath, PrivateFileReader::read);
    }

    static StartupConfig loadForTest(Path bootstrapPath, PrivateFileRead reader) throws IOException {
        return load(bootstrapPath, reader);
    }

    private static StartupConfig load(Path bootstrapPath, PrivateFileRead reader) throws IOException {
        java.util.Objects.requireNonNull(reader, "reader");
        try {
            Path checkedBootstrap = canonicalPath(bootstrapPath.toString(), PrivateConfigurationProblem.Field.BOOTSTRAP);
            byte[] bootstrapBytes = readPrivateFile(checkedBootstrap, MAX_BOOTSTRAP_BYTES, false,
                    PrivateConfigurationProblem.Field.BOOTSTRAP,
                    PrivateConfigurationProblem.Expected.PRIVATE_BOOTSTRAP_FILE,
                    PrivateConfigurationProblem.Action.REPAIR_BOOTSTRAP_FILE, reader);
            String bootstrapText = decodeStrictUtf8(bootstrapBytes, PrivateConfigurationProblem.Field.BOOTSTRAP,
                    PrivateConfigurationProblem.Expected.VERSIONED_BOOTSTRAP);
            Map<String, String> values = parseBootstrap(bootstrapText);
            if (isLegacyBootstrap(values)) throw new PrivateConfigurationException(PrivateConfigurationProblem.legacyBootstrap());
            if (!BOOTSTRAP_KEYS.containsAll(values.keySet())) throw new PrivateConfigurationException(PrivateConfigurationProblem.unknownKey());
            if (!values.keySet().containsAll(BOOTSTRAP_KEYS)) {
                throw bootstrapProblem();
            }
            if (!"1".equals(values.get("schemaVersion"))) {
                throw problem(PrivateConfigurationProblem.Field.SCHEMA_VERSION, PrivateConfigurationProblem.Expected.SCHEMA_VERSION_1,
                        PrivateConfigurationProblem.Action.CORRECT_BOOTSTRAP);
            }
            Path configFile = canonicalPath(values.get("configFile"), PrivateConfigurationProblem.Field.CONFIG_FILE);
            byte[] bundleBytes = readPrivateFile(configFile, MAX_BUNDLE_BYTES, false,
                    PrivateConfigurationProblem.Field.CONFIG_FILE,
                    PrivateConfigurationProblem.Expected.PRIVATE_BUNDLE_FILE,
                    PrivateConfigurationProblem.Action.REPAIR_BUNDLE_FILE, reader);
            String bundleText = decodeStrictUtf8(bundleBytes, PrivateConfigurationProblem.Field.STARTUP_BUNDLE,
                    PrivateConfigurationProblem.Expected.V1_BUNDLE);
            JsonElement parsed = StrictJsonParser.parse(bundleText, MAX_NESTING);
            JsonObject bundle = object(parsed);
            Set<String> allowed = Set.of("schemaVersion", "binding", "server", "companion", "abilityPolicy", "credentialFile", "personaFile");
            if (!allowed.containsAll(bundle.keySet())) throw new PrivateConfigurationException(PrivateConfigurationProblem.unknownKey());
            if (!bundle.keySet().containsAll(Set.of("schemaVersion", "binding", "server", "companion", "abilityPolicy", "credentialFile"))) {
                throw problem(PrivateConfigurationProblem.Field.STARTUP_BUNDLE, PrivateConfigurationProblem.Expected.V1_BUNDLE);
            }
            requireInteger(bundle.get("schemaVersion"), 1, 1, PrivateConfigurationProblem.Field.SCHEMA_VERSION,
                    PrivateConfigurationProblem.Expected.SCHEMA_VERSION_1);
            if (bundle.has("personaFile")) throw new PersonaPublicationUnqualifiedException();

            JsonObject rawBinding = object(bundle.get("binding"));
            rejectUnknown(rawBinding, Set.of("serverId", "worldId", "characterId", "agentId"));
            ConfiguredBinding binding = new ConfiguredBinding(
                    identifier(string(rawBinding.get("serverId"), PrivateConfigurationProblem.Field.BINDING_SERVER_ID,
                            PrivateConfigurationProblem.Expected.ASCII_IDENTIFIER), PrivateConfigurationProblem.Field.BINDING_SERVER_ID),
                    identifier(string(rawBinding.get("worldId"), PrivateConfigurationProblem.Field.BINDING_WORLD_ID,
                            PrivateConfigurationProblem.Expected.ASCII_IDENTIFIER), PrivateConfigurationProblem.Field.BINDING_WORLD_ID),
                    identifier(string(rawBinding.get("characterId"), PrivateConfigurationProblem.Field.BINDING_CHARACTER_ID,
                            PrivateConfigurationProblem.Expected.ASCII_IDENTIFIER), PrivateConfigurationProblem.Field.BINDING_CHARACTER_ID),
                    identifier(string(rawBinding.get("agentId"), PrivateConfigurationProblem.Field.BINDING_AGENT_ID,
                            PrivateConfigurationProblem.Expected.ASCII_IDENTIFIER), PrivateConfigurationProblem.Field.BINDING_AGENT_ID));

            JsonObject rawCompanion = object(bundle.get("companion"));
            rejectUnknown(rawCompanion, Set.of("bindHost", "port", "deploymentId", "manifestSha256"));
            String bindHost = string(rawCompanion.get("bindHost"), PrivateConfigurationProblem.Field.COMPANION_BIND_HOST,
                    PrivateConfigurationProblem.Expected.LOOPBACK_HOST);
            int port = intValue(rawCompanion.get("port"), 1024, 65535,
                    PrivateConfigurationProblem.Field.COMPANION_PORT, PrivateConfigurationProblem.Expected.PORT);
            String deploymentId = string(rawCompanion.get("deploymentId"), PrivateConfigurationProblem.Field.COMPANION_DEPLOYMENT_ID,
                    PrivateConfigurationProblem.Expected.INSTALLED_DEPLOYMENT);
            String manifestSha256 = string(rawCompanion.get("manifestSha256"), PrivateConfigurationProblem.Field.COMPANION_MANIFEST_HASH,
                    PrivateConfigurationProblem.Expected.INSTALLED_DEPLOYMENT);
            if (!"127.0.0.1".equals(bindHost) || !DeploymentFacts.DEPLOYMENT_ID.equals(deploymentId) ||
                    !DeploymentFacts.MANIFEST_SHA256.equals(manifestSha256)) {
                if (!"127.0.0.1".equals(bindHost)) throw problem(PrivateConfigurationProblem.Field.COMPANION_BIND_HOST,
                        PrivateConfigurationProblem.Expected.LOOPBACK_HOST);
                if (!DeploymentFacts.DEPLOYMENT_ID.equals(deploymentId)) throw problem(PrivateConfigurationProblem.Field.COMPANION_DEPLOYMENT_ID,
                        PrivateConfigurationProblem.Expected.INSTALLED_DEPLOYMENT);
                throw problem(PrivateConfigurationProblem.Field.COMPANION_MANIFEST_HASH,
                        PrivateConfigurationProblem.Expected.INSTALLED_DEPLOYMENT);
            }

            JsonObject rawServer = object(bundle.get("server"));
            Set<String> serverAllowed = Set.of("endpoint", "acknowledgementTemplate", "characterDisplayName",
                    "conversationAllowedPlayers", "characterAliases", "followupWindowSeconds", "executionBudgetSeconds",
                    "characterChatStyle", "attention");
            Set<String> serverRequired = Set.of("endpoint", "acknowledgementTemplate", "characterDisplayName",
                    "conversationAllowedPlayers");
            if (!serverAllowed.containsAll(rawServer.keySet())) throw new PrivateConfigurationException(PrivateConfigurationProblem.unknownKey());
            if (serverRequired.contains("endpoint") && !rawServer.has("endpoint")) throw problem(PrivateConfigurationProblem.Field.SERVER_ENDPOINT,
                    PrivateConfigurationProblem.Expected.STRICT_ENDPOINT);
            if (serverRequired.contains("acknowledgementTemplate") && !rawServer.has("acknowledgementTemplate")) throw problem(PrivateConfigurationProblem.Field.SERVER_ACKNOWLEDGEMENT,
                    PrivateConfigurationProblem.Expected.ACKNOWLEDGEMENT);
            if (serverRequired.contains("characterDisplayName") && !rawServer.has("characterDisplayName")) throw problem(PrivateConfigurationProblem.Field.SERVER_DISPLAY_NAME,
                    PrivateConfigurationProblem.Expected.DISPLAY_NAME);
            if (serverRequired.contains("conversationAllowedPlayers") && !rawServer.has("conversationAllowedPlayers")) throw problem(PrivateConfigurationProblem.Field.SERVER_ALLOWED_PLAYERS,
                    PrivateConfigurationProblem.Expected.UUID_LIST);
            URI endpoint;
            try {
                endpoint = EndpointSyntax.parse(string(rawServer.get("endpoint"), PrivateConfigurationProblem.Field.SERVER_ENDPOINT,
                        PrivateConfigurationProblem.Expected.STRICT_ENDPOINT));
            } catch (IOException invalidEndpoint) {
                throw problem(PrivateConfigurationProblem.Field.SERVER_ENDPOINT, PrivateConfigurationProblem.Expected.STRICT_ENDPOINT);
            }
            String acknowledgement = string(rawServer.get("acknowledgementTemplate"),
                    PrivateConfigurationProblem.Field.SERVER_ACKNOWLEDGEMENT,
                    PrivateConfigurationProblem.Expected.ACKNOWLEDGEMENT).trim();
            if (!CompanionConfig.isValidAcknowledgementTemplateForStartup(acknowledgement)) {
                throw new CompanionConfig.InvalidAcknowledgementTemplateException();
            }
            String displayName = string(rawServer.get("characterDisplayName"), PrivateConfigurationProblem.Field.SERVER_DISPLAY_NAME,
                    PrivateConfigurationProblem.Expected.DISPLAY_NAME);
            if (!VanillaDialogue.isValidCharacterDisplayName(displayName)) throw new CompanionConfig.InvalidCharacterDisplayNameException();
            InteractionPolicy interaction = interactionPolicy(rawServer.get("conversationAllowedPlayers"));
            List<String> aliases;
            try { aliases = rawServer.has("characterAliases") ? stringArray(rawServer.get("characterAliases"), 8) : List.of(); }
            catch (IOException | RuntimeException invalidAliases) { throw new CompanionConfig.InvalidCharacterAliasesConfigException(); }
            int followup = boundedOptionalInt(rawServer, "followupWindowSeconds", 30, 0, 30,
                    CompanionConfig.InvalidFollowupWindowConfigException::new);
            int executionBudget = boundedOptionalInt(rawServer, "executionBudgetSeconds", 30, 1, 30,
                    CompanionConfig.InvalidExecutionBudgetConfigException::new);
            CharacterChatStyle chatStyle;
            try { chatStyle = rawServer.has("characterChatStyle") ? CharacterChatStyle.parse(string(rawServer.get("characterChatStyle"))) : CharacterChatStyle.PLAIN; }
            catch (IllegalArgumentException invalid) { throw new CompanionConfig.InvalidCharacterChatStyleConfigException(); }
            AttentionPolicy attention = parseAttention(rawServer.get("attention"));
            List<String> checkedAliases;
            try { checkedAliases = CompanionConfig.validateAliasesForStartup(aliases, displayName); }
            catch (IllegalArgumentException invalid) { throw new CompanionConfig.InvalidCharacterAliasesConfigException(); }

            AbilityPolicy abilityPolicy;
            try { abilityPolicy = AbilityPolicy.parse(bundle.get("abilityPolicy"), binding.characterId()); }
            catch (RuntimeException invalid) { throw classifyAbilityPolicyFailure(bundle.get("abilityPolicy")); }

            Path credentialFile = canonicalPath(string(bundle.get("credentialFile"), PrivateConfigurationProblem.Field.CREDENTIAL_FILE,
                    PrivateConfigurationProblem.Expected.CANONICAL_LOCAL_REFERENCE), PrivateConfigurationProblem.Field.CREDENTIAL_FILE);
            byte[] credentialBytes = readPrivateFile(credentialFile, MAX_CREDENTIAL_BYTES, true,
                    PrivateConfigurationProblem.Field.CREDENTIAL_FILE,
                    PrivateConfigurationProblem.Expected.PRIVATE_CREDENTIAL_FILE,
                    PrivateConfigurationProblem.Action.REPAIR_CREDENTIAL_FILE, reader);
            String encodedSecret = decodeStrictUtf8(credentialBytes, PrivateConfigurationProblem.Field.CREDENTIAL_CONTENT,
                    PrivateConfigurationProblem.Expected.CREDENTIAL_CONTENT);
            if (!CREDENTIAL.matcher(encodedSecret).matches()) throw problem(PrivateConfigurationProblem.Field.CREDENTIAL_CONTENT,
                    PrivateConfigurationProblem.Expected.CREDENTIAL_CONTENT, PrivateConfigurationProblem.Action.REPLACE_CREDENTIAL_CONTENT);
            byte[] secret;
            try { secret = Base64.getUrlDecoder().decode(encodedSecret); }
            catch (IllegalArgumentException invalid) { throw problem(PrivateConfigurationProblem.Field.CREDENTIAL_CONTENT,
                    PrivateConfigurationProblem.Expected.CREDENTIAL_CONTENT, PrivateConfigurationProblem.Action.REPLACE_CREDENTIAL_CONTENT); }
            if (secret.length < 32 || secret.length > 64 ||
                    !Base64.getUrlEncoder().withoutPadding().encodeToString(secret).equals(encodedSecret)) {
                throw problem(PrivateConfigurationProblem.Field.CREDENTIAL_CONTENT,
                        PrivateConfigurationProblem.Expected.CREDENTIAL_CONTENT, PrivateConfigurationProblem.Action.REPLACE_CREDENTIAL_CONTENT);
            }

            String personaFile = null;
            String configHash = sha256(bundleBytes);
            CompanionConfig serverConfig = new CompanionConfig(endpoint, secret, binding, acknowledgement, displayName,
                    interaction, checkedAliases, followup, attention, executionBudget, chatStyle, abilityPolicy, configHash);
            return new StartupConfig(serverConfig, new CompanionSettings(bindHost, port, deploymentId, manifestSha256),
                    abilityPolicy, configFile, credentialFile, configHash, personaFile);
        } catch (PrivateConfigurationException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw problem(PrivateConfigurationProblem.Field.STARTUP_BUNDLE, PrivateConfigurationProblem.Expected.V1_BUNDLE);
        }
    }

    public record CompanionSettings(String bindHost, int port, String deploymentId, String manifestSha256) {}

    @Override
    public String toString() {
        return "StartupConfig[validated local startup snapshot]";
    }

    public static final class PersonaPublicationUnqualifiedException extends PrivateConfigurationException {
        private static final long serialVersionUID = 1L;

        public PersonaPublicationUnqualifiedException() {
            super(PrivateConfigurationProblem.personaInactive());
        }
    }

    private static Map<String, String> parseBootstrap(String text) throws IOException {
        if (text.startsWith("\ufeff")) throw bootstrapProblem();
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        String normalized = text.replace("\r\n", "\n");
        if (normalized.indexOf('\r') >= 0) throw bootstrapProblem();
        for (String line : normalized.split("\n", -1)) {
            if (line.isEmpty()) continue;
            int separator = line.indexOf('=');
            if (separator < 1 || separator == line.length() - 1) throw bootstrapProblem();
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!BOOTSTRAP_KEYS.contains(key) && !LEGACY_BOOTSTRAP_KEYS.contains(key)) {
                throw new PrivateConfigurationException(PrivateConfigurationProblem.unknownKey());
            }
            if (values.putIfAbsent(key, value) != null) throw bootstrapProblem();
        }
        return Map.copyOf(values);
    }

    private static boolean isLegacyBootstrap(Map<String, String> values) {
        boolean containsVersionedKey = values.keySet().stream().anyMatch(BOOTSTRAP_KEYS::contains);
        return !containsVersionedKey && values.keySet().stream().anyMatch(LEGACY_BOOTSTRAP_KEYS::contains);
    }

    private static Path canonicalPath(String value) throws IOException {
        return canonicalPath(value, PrivateConfigurationProblem.Field.CONFIG_FILE);
    }

    private static Path canonicalPath(String value, PrivateConfigurationProblem.Field field) throws IOException {
        if (value == null || value.length() < 1 || value.length() > MAX_REFERENCE_UNITS || value.contains("://") ||
                value.chars().anyMatch(Character::isISOControl)) throw problem(field,
                PrivateConfigurationProblem.Expected.CANONICAL_LOCAL_REFERENCE,
                field == PrivateConfigurationProblem.Field.BOOTSTRAP ? PrivateConfigurationProblem.Action.CORRECT_BOOTSTRAP :
                        PrivateConfigurationProblem.Action.CORRECT_V1_FIELD);
        Path path;
        try { path = Path.of(value); } catch (RuntimeException invalid) { throw problem(field,
                PrivateConfigurationProblem.Expected.CANONICAL_LOCAL_REFERENCE,
                field == PrivateConfigurationProblem.Field.BOOTSTRAP ? PrivateConfigurationProblem.Action.CORRECT_BOOTSTRAP :
                        PrivateConfigurationProblem.Action.CORRECT_V1_FIELD); }
        if (!path.isAbsolute() || !path.normalize().toString().equals(value) || path.getNameCount() < 2) {
            throw problem(field, PrivateConfigurationProblem.Expected.CANONICAL_LOCAL_REFERENCE,
                    field == PrivateConfigurationProblem.Field.BOOTSTRAP ? PrivateConfigurationProblem.Action.CORRECT_BOOTSTRAP :
                            PrivateConfigurationProblem.Action.CORRECT_V1_FIELD);
        }
        return path;
    }

    private static byte[] readPrivateFile(Path path, int maximum, boolean credential,
                                          PrivateConfigurationProblem.Field field,
                                          PrivateConfigurationProblem.Expected expected,
                                          PrivateConfigurationProblem.Action action,
                                          PrivateFileRead reader) throws IOException {
        try {
            return reader.read(path, maximum, credential);
        } catch (IOException | RuntimeException rejected) {
            throw new PrivateConfigurationException(PrivateConfigurationProblem.privateFile(field, expected, action));
        }
    }

    @FunctionalInterface
    interface PrivateFileRead {
        byte[] read(Path path, int maximum, boolean credential) throws IOException;
    }

    private static String decodeStrictUtf8(byte[] bytes, PrivateConfigurationProblem.Field field,
                                           PrivateConfigurationProblem.Expected expected) throws IOException {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            throw problem(field, expected, actionFor(field));
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.isEmpty()) throw problem(field, expected, actionFor(field));
            return text;
        } catch (CharacterCodingException malformed) {
            throw problem(field, expected, actionFor(field));
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 unavailable"); }
    }

    private static JsonObject object(JsonElement value) throws IOException {
        if (value == null || !value.isJsonObject()) throw problem(PrivateConfigurationProblem.Field.STARTUP_BUNDLE,
                PrivateConfigurationProblem.Expected.V1_BUNDLE);
        return value.getAsJsonObject();
    }

    private static void rejectUnknown(JsonObject object, Set<String> fields) throws IOException {
        if (!fields.containsAll(object.keySet())) throw new PrivateConfigurationException(PrivateConfigurationProblem.unknownKey());
    }

    private static String string(JsonElement value) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw problem(PrivateConfigurationProblem.Field.STARTUP_BUNDLE, PrivateConfigurationProblem.Expected.V1_BUNDLE);
        }
        return value.getAsString();
    }

    private static String string(JsonElement value, PrivateConfigurationProblem.Field field,
                                 PrivateConfigurationProblem.Expected expected) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw problem(field, expected);
        return value.getAsString();
    }

    private static int intValue(JsonElement value, int minimum, int maximum) throws IOException {
        long number = integer(value);
        if (number < minimum || number > maximum) throw new IOException("startup numeric field is outside its fixed bounds");
        return (int) number;
    }

    private static int intValue(JsonElement value, int minimum, int maximum,
                                PrivateConfigurationProblem.Field field,
                                PrivateConfigurationProblem.Expected expected) throws IOException {
        long number;
        try { number = integer(value); }
        catch (IOException | RuntimeException invalid) { throw problem(field, expected); }
        if (number < minimum || number > maximum) throw problem(field, expected);
        return (int) number;
    }

    private static int boundedOptionalInt(JsonObject object, String key, int fallback, int minimum, int maximum,
                                          java.util.function.Supplier<? extends IOException> invalid) throws IOException {
        if (!object.has(key)) return fallback;
        try { return intValue(object.get(key), minimum, maximum); }
        catch (IOException | RuntimeException malformed) { throw invalid.get(); }
    }

    private static int boundedOptionalInt(JsonObject object, String key, int fallback, int minimum, int maximum,
                                          PrivateConfigurationProblem.Field field,
                                          PrivateConfigurationProblem.Expected expected) throws IOException {
        if (!object.has(key)) return fallback;
        return intValue(object.get(key), minimum, maximum, field, expected);
    }

    private static void requireInteger(JsonElement value, long minimum, long maximum) throws IOException {
        long number = integer(value);
        if (number < minimum || number > maximum) throw new IOException("startup schema version is unsupported");
    }

    private static void requireInteger(JsonElement value, long minimum, long maximum,
                                       PrivateConfigurationProblem.Field field,
                                       PrivateConfigurationProblem.Expected expected) throws IOException {
        long number;
        try { number = integer(value); }
        catch (IOException | RuntimeException invalid) { throw problem(field, expected); }
        if (number < minimum || number > maximum) throw problem(field, expected);
    }

    private static long integer(JsonElement value) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IOException("startup numeric field has the wrong type");
        try {
            java.math.BigDecimal number = new java.math.BigDecimal(value.getAsString());
            long integer = number.longValueExact();
            if (number.signum() < 0 || integer > 9_007_199_254_740_991L) {
                throw new IOException("startup integer exceeds its JSON-safe range");
            }
            return integer;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IOException("startup integer is outside its fixed bounds");
        }
    }

    private static String identifier(String value) throws IOException {
        if (!ID.matcher(value).matches()) throw new IOException("binding identifier is invalid");
        return value;
    }

    private static String identifier(String value, PrivateConfigurationProblem.Field field) throws IOException {
        if (!ID.matcher(value).matches()) throw problem(field, PrivateConfigurationProblem.Expected.ASCII_IDENTIFIER);
        return value;
    }

    private static PrivateConfigurationException problem(PrivateConfigurationProblem.Field field,
                                                         PrivateConfigurationProblem.Expected expected) {
        return new PrivateConfigurationException(PrivateConfigurationProblem.invalid(field, expected));
    }

    private static PrivateConfigurationException problem(PrivateConfigurationProblem.Field field,
                                                         PrivateConfigurationProblem.Expected expected,
                                                         PrivateConfigurationProblem.Action action) {
        return new PrivateConfigurationException(PrivateConfigurationProblem.invalid(field, expected, action));
    }

    private static PrivateConfigurationException bootstrapProblem() {
        return problem(PrivateConfigurationProblem.Field.BOOTSTRAP, PrivateConfigurationProblem.Expected.VERSIONED_BOOTSTRAP,
                PrivateConfigurationProblem.Action.CORRECT_BOOTSTRAP);
    }

    private static PrivateConfigurationProblem.Action actionFor(PrivateConfigurationProblem.Field field) {
        if (field == PrivateConfigurationProblem.Field.BOOTSTRAP) return PrivateConfigurationProblem.Action.CORRECT_BOOTSTRAP;
        if (field == PrivateConfigurationProblem.Field.CREDENTIAL_CONTENT) return PrivateConfigurationProblem.Action.REPLACE_CREDENTIAL_CONTENT;
        return PrivateConfigurationProblem.Action.CORRECT_V1_FIELD;
    }

    private static PrivateConfigurationException classifyAbilityPolicyFailure(JsonElement value) {
        if (value != null && value.isJsonObject()) {
            JsonObject policy = value.getAsJsonObject();
            Set<String> policyFields = Set.of("schemaVersion", "revision", "grants", "denials", "protection");
            if (!policyFields.containsAll(policy.keySet())) return new PrivateConfigurationException(PrivateConfigurationProblem.unknownKey());
            JsonElement rawProtection = policy.get("protection");
            if (rawProtection != null && rawProtection.isJsonObject() &&
                    !Set.of("externalClaimsMode").containsAll(rawProtection.getAsJsonObject().keySet())) {
                return new PrivateConfigurationException(PrivateConfigurationProblem.unknownKey());
            }
            JsonElement rawGrants = policy.get("grants");
            if (rawGrants != null && rawGrants.isJsonArray() && rawGrants.getAsJsonArray().size() <= AbilityPolicy.MAX_ENTRIES) {
                for (JsonElement raw : rawGrants.getAsJsonArray()) {
                    if (raw == null || !raw.isJsonObject()) continue;
                    JsonObject grant = raw.getAsJsonObject();
                    JsonElement ability = grant.get("abilityId");
                    boolean lightning = ability != null && ability.isJsonPrimitive() && ability.getAsJsonPrimitive().isString() &&
                            AbilityPolicy.LIGHTNING.equals(ability.getAsString());
                    Set<String> grantFields = new HashSet<>(Set.of("id", "characterId", "abilityId", "contractVersion",
                            "requesterIds", "dimensions", "otherTargetIds", "maxRangeBlocks", "region", "limits"));
                    if (lightning) grantFields.addAll(Set.of("allowVisual", "allowDamage", "maxDamage"));
                    if (!grantFields.containsAll(grant.keySet())) return new PrivateConfigurationException(PrivateConfigurationProblem.unknownKey());
                }
            }
            JsonElement rawDenialsForKeys = policy.get("denials");
            if (rawDenialsForKeys != null && rawDenialsForKeys.isJsonArray() &&
                    rawDenialsForKeys.getAsJsonArray().size() <= AbilityPolicy.MAX_ENTRIES) {
                for (JsonElement raw : rawDenialsForKeys.getAsJsonArray()) {
                    if (raw != null && raw.isJsonObject() &&
                            !Set.of("id", "abilityId", "contractVersion").containsAll(raw.getAsJsonObject().keySet())) {
                        return new PrivateConfigurationException(PrivateConfigurationProblem.unknownKey());
                    }
                }
            }
            JsonElement rawDenials = policy.get("denials");
            if (rawDenials != null && rawDenials.isJsonArray() && rawDenials.getAsJsonArray().size() <= AbilityPolicy.MAX_ENTRIES) {
                for (JsonElement raw : rawDenials.getAsJsonArray()) {
                    if (raw == null || !raw.isJsonObject()) continue;
                    JsonElement ability = raw.getAsJsonObject().get("abilityId");
                    if (!knownAbility(ability)) return problem(PrivateConfigurationProblem.Field.ABILITY_DENIAL_ABILITY_ID,
                            PrivateConfigurationProblem.Expected.ABILITY_ID_V1);
                }
            }
            if (rawGrants != null && rawGrants.isJsonArray() && rawGrants.getAsJsonArray().size() <= AbilityPolicy.MAX_ENTRIES) {
                for (JsonElement raw : rawGrants.getAsJsonArray()) {
                    if (raw == null || !raw.isJsonObject()) continue;
                    JsonElement ability = raw.getAsJsonObject().get("abilityId");
                    if (!knownAbility(ability)) return problem(PrivateConfigurationProblem.Field.ABILITY_GRANT_ABILITY_ID,
                            PrivateConfigurationProblem.Expected.ABILITY_ID_V1);
                }
            }
        }
        return problem(PrivateConfigurationProblem.Field.ABILITY_POLICY,
                PrivateConfigurationProblem.Expected.BOUNDED_ABILITY_POLICY);
    }

    private static boolean knownAbility(JsonElement ability) {
        return ability != null && ability.isJsonPrimitive() && ability.getAsJsonPrimitive().isString() &&
                (AbilityPolicy.OBSERVE.equals(ability.getAsString()) || AbilityPolicy.LIGHTNING.equals(ability.getAsString()));
    }

    private static InteractionPolicy interactionPolicy(JsonElement value) throws IOException {
        JsonArray array = value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
        if (array == null || array.size() > InteractionPolicy.MAX_ALLOWED_PLAYERS) throw new CompanionConfig.InvalidInteractionPolicyConfigException();
        List<String> values = new ArrayList<>(array.size());
        Set<String> unique = new HashSet<>();
        for (JsonElement item : array) {
            String uuid;
            try {
                uuid = string(item, PrivateConfigurationProblem.Field.SERVER_ALLOWED_PLAYERS,
                        PrivateConfigurationProblem.Expected.UUID_LIST);
            } catch (PrivateConfigurationException invalid) {
                throw new CompanionConfig.InvalidInteractionPolicyConfigException();
            }
            try {
                if (!UUID.fromString(uuid).toString().equals(uuid) || !unique.add(uuid)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { throw new CompanionConfig.InvalidInteractionPolicyConfigException(); }
            values.add(uuid);
        }
        try { return InteractionPolicy.parse(String.join(",", values)); }
        catch (InteractionPolicy.InvalidInteractionPolicyException invalid) { throw new CompanionConfig.InvalidInteractionPolicyConfigException(); }
    }

    private static List<String> stringArray(JsonElement value, int maximum) throws IOException {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > maximum) throw new IOException("string list is outside its fixed bounds");
        List<String> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) result.add(string(element));
        return List.copyOf(result);
    }

    private static AttentionPolicy parseAttention(JsonElement value) throws IOException {
        if (value == null) return AttentionPolicy.disabled();
        try {
            JsonObject attention = object(value);
            Set<String> allowed = Set.of("subscriptions", "maxEvents", "maxAgeSeconds", "maxBytes", "maxEventsPerSecond", "maxEventsPerPlayerSecond");
            if (!allowed.containsAll(attention.keySet())) throw new PrivateConfigurationException(PrivateConfigurationProblem.unknownKey());
            List<String> subscriptions;
            try { subscriptions = attention.has("subscriptions") ? stringArray(attention.get("subscriptions"), 5) : List.of(); }
            catch (IOException | RuntimeException invalid) {
                throw new CompanionConfig.InvalidAttentionPolicyConfigException(PrivateConfigurationProblem.Field.ATTENTION_SUBSCRIPTIONS,
                        PrivateConfigurationProblem.Expected.SUBSCRIPTIONS);
            }
            Set<String> unique = new HashSet<>(subscriptions);
            Set<String> supported = Set.of("player_connection", "player_dimension", "player_death", "player_respawn", "player_movement");
            if (unique.size() != subscriptions.size() || !supported.containsAll(subscriptions)) {
                throw new CompanionConfig.InvalidAttentionPolicyConfigException(PrivateConfigurationProblem.Field.ATTENTION_SUBSCRIPTIONS,
                        PrivateConfigurationProblem.Expected.SUBSCRIPTIONS);
            }
            int maxEvents = boundedOptionalInt(attention, "maxEvents", AttentionPolicy.DEFAULT_MAX_EVENTS, 1, 64,
                    () -> new CompanionConfig.InvalidAttentionPolicyConfigException(PrivateConfigurationProblem.Field.ATTENTION_MAX_EVENTS,
                            PrivateConfigurationProblem.Expected.INTEGER_1_64));
            int maxAge = boundedOptionalInt(attention, "maxAgeSeconds", AttentionPolicy.DEFAULT_MAX_AGE_SECONDS, 1, 60,
                    () -> new CompanionConfig.InvalidAttentionPolicyConfigException(PrivateConfigurationProblem.Field.ATTENTION_MAX_AGE,
                            PrivateConfigurationProblem.Expected.INTEGER_1_60));
            int maxBytes = boundedOptionalInt(attention, "maxBytes", AttentionPolicy.DEFAULT_MAX_BYTES, 1024, 8192,
                    () -> new CompanionConfig.InvalidAttentionPolicyConfigException(PrivateConfigurationProblem.Field.ATTENTION_MAX_BYTES,
                            PrivateConfigurationProblem.Expected.INTEGER_1024_8192));
            int maxRate = boundedOptionalInt(attention, "maxEventsPerSecond", AttentionPolicy.DEFAULT_MAX_EVENTS_PER_SECOND, 1, 32,
                    () -> new CompanionConfig.InvalidAttentionPolicyConfigException(PrivateConfigurationProblem.Field.ATTENTION_MAX_EVENTS_PER_SECOND,
                            PrivateConfigurationProblem.Expected.INTEGER_1_32));
            int maxPlayerRate = boundedOptionalInt(attention, "maxEventsPerPlayerSecond", AttentionPolicy.DEFAULT_MAX_EVENTS_PER_PLAYER_SECOND, 1, 8,
                    () -> new CompanionConfig.InvalidAttentionPolicyConfigException(
                            PrivateConfigurationProblem.Field.ATTENTION_MAX_EVENTS_PER_PLAYER_SECOND,
                            PrivateConfigurationProblem.Expected.INTEGER_1_8_AT_MOST_GLOBAL));
            if (maxPlayerRate > maxRate) throw new CompanionConfig.InvalidAttentionPolicyConfigException(
                    PrivateConfigurationProblem.Field.ATTENTION_MAX_EVENTS_PER_PLAYER_SECOND,
                    PrivateConfigurationProblem.Expected.INTEGER_1_8_AT_MOST_GLOBAL);
            return AttentionPolicy.parse(String.join(",", subscriptions), Integer.toString(maxEvents), Integer.toString(maxAge),
                    Integer.toString(maxBytes), Integer.toString(maxRate), Integer.toString(maxPlayerRate));
        } catch (PrivateConfigurationException invalid) {
            throw invalid;
        } catch (IOException | IllegalArgumentException invalid) {
            throw new CompanionConfig.InvalidAttentionPolicyConfigException();
        }
    }

}
