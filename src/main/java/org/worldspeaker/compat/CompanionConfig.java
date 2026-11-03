package org.worldspeaker.compat;

import org.worldspeaker.bootstrap.VanillaDialogue;
import org.worldspeaker.bootstrap.NaturalAddressParser;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Local-only connection configuration. It is never copied into diagnostics. */
public record CompanionConfig(
        URI endpoint,
        byte[] secret,
        ConfiguredBinding binding,
        String acknowledgementTemplate,
        String characterDisplayName,
        InteractionPolicy interactionPolicy,
        List<String> characterAliases,
        int followupWindowSeconds,
        AttentionPolicy attentionPolicy,
        int executionBudgetSeconds,
        CharacterChatStyle characterChatStyle,
        AbilityPolicy abilityPolicy,
        String configSetSha256) {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    public static final int MAX_CHARACTER_ALIASES = 8;
    public static final int MAX_CHARACTER_ALIAS_LENGTH = NaturalAddressParser.MAX_CONFIGURED_NAME_LENGTH;
    public static final int MAX_CHARACTER_ALIASES_TOTAL_LENGTH = 384;
    private static final int MAX_ACKNOWLEDGEMENT_TEMPLATE_LENGTH = 256;
    private static final String INVALID_ACKNOWLEDGEMENT_TEMPLATE =
            "server.acknowledgementTemplate must be visible safe text of at most 256 units with optional {player}";
    private static final String INVALID_CHARACTER_DISPLAY_NAME =
            "server.characterDisplayName must be a visible, naturally addressable single-line name of 1 to 48 units";
    private static final String INVALID_INTERACTION_POLICY =
            "server.conversationAllowedPlayers must contain at most 32 unique canonical UUIDs";
    private static final String INVALID_CHARACTER_ALIASES =
            "server.characterAliases must contain at most eight unique visible, naturally addressable names of 1 to 48 units each";
    private static final String INVALID_FOLLOWUP_WINDOW = "server.followupWindowSeconds must be an integer from 0 to 30";
    private static final String INVALID_EXECUTION_BUDGET = "server.executionBudgetSeconds must be an integer from 1 to 30";
    private static final String INVALID_ABILITY_POLICY = "abilityPolicy must be a bounded v1 policy with known fields and abilities";
    private static final Set<String> KEYS = Set.of("endpoint", "bearerSecret", "serverId", "worldId", "characterId", "agentId",
            "acknowledgementTemplate", "characterDisplayName", "conversationAllowedPlayers", "characterAliases",
            "followupWindowSeconds", "executionBudgetSeconds", "attentionSubscriptions", "attentionMaxEvents", "attentionMaxAgeSeconds",
            "attentionMaxBytes", "attentionMaxEventsPerSecond", "attentionMaxEventsPerPlayerSecond", "characterChatStyle");

    public CompanionConfig {
        if (secret == null || secret.length < 32 || secret.length > 64) throw new IllegalArgumentException("credential content must encode 32..64 bytes");
        secret = secret.clone();
        if (binding == null || !binding.isComplete()) {
            throw new IllegalArgumentException("binding identifiers must be ASCII [A-Za-z0-9._:-], 1..128 characters");
        }
        if (!isValidAcknowledgementTemplate(acknowledgementTemplate)) {
            throw new IllegalArgumentException(INVALID_ACKNOWLEDGEMENT_TEMPLATE);
        }
        if (!VanillaDialogue.isValidCharacterDisplayName(characterDisplayName)) {
            throw new IllegalArgumentException(INVALID_CHARACTER_DISPLAY_NAME);
        }
        characterAliases = validateCharacterAliases(characterAliases, characterDisplayName);
        if (interactionPolicy == null || interactionPolicy.conversationAllowedPlayers().size() > InteractionPolicy.MAX_ALLOWED_PLAYERS) {
            throw new IllegalArgumentException(INVALID_INTERACTION_POLICY);
        }
        if (followupWindowSeconds < 0 || followupWindowSeconds > 30) {
            throw new IllegalArgumentException(INVALID_FOLLOWUP_WINDOW);
        }
        if (executionBudgetSeconds < 1 || executionBudgetSeconds > 30) {
            throw new IllegalArgumentException(INVALID_EXECUTION_BUDGET);
        }
        if (attentionPolicy == null) throw new IllegalArgumentException("server.attention must be a bounded known-field policy");
        if (characterChatStyle == null) throw new IllegalArgumentException("server.characterChatStyle must be plain or bracketed");
        if (abilityPolicy == null) throw new IllegalArgumentException(INVALID_ABILITY_POLICY);
        validateAbilityPolicyScope(abilityPolicy, binding);
        if (configSetSha256 == null || !SHA256.matcher(configSetSha256).matches()) {
            throw new IllegalArgumentException("startup bundle digest must be 64 lowercase hexadecimal characters");
        }
        try {
            EndpointSyntax.validate(endpoint);
        } catch (IOException invalidEndpoint) {
            throw new IllegalArgumentException("server.endpoint must follow strict ws/wss syntax", invalidEndpoint);
        }
    }

    public CompanionConfig(URI endpoint, byte[] secret, ConfiguredBinding binding, String acknowledgementTemplate,
                           String characterDisplayName, InteractionPolicy interactionPolicy,
                           List<String> characterAliases, int followupWindowSeconds,
                           AttentionPolicy attentionPolicy, int executionBudgetSeconds,
                           CharacterChatStyle characterChatStyle) {
        this(endpoint, secret, binding, acknowledgementTemplate, characterDisplayName, interactionPolicy,
                characterAliases, followupWindowSeconds, attentionPolicy, executionBudgetSeconds,
                characterChatStyle, AbilityPolicy.denyAll(), "0".repeat(64));
    }

    /** Source-compatible canonical construction with the default plain style. */
    public CompanionConfig(URI endpoint, byte[] secret, ConfiguredBinding binding, String acknowledgementTemplate,
                           String characterDisplayName, InteractionPolicy interactionPolicy,
                           List<String> characterAliases, int followupWindowSeconds,
                           AttentionPolicy attentionPolicy, int executionBudgetSeconds) {
        this(endpoint, secret, binding, acknowledgementTemplate, characterDisplayName, interactionPolicy,
                characterAliases, followupWindowSeconds, attentionPolicy, executionBudgetSeconds, CharacterChatStyle.PLAIN,
                AbilityPolicy.denyAll(), "0".repeat(64));
    }

    /** Source-compatible construction before the finite execution budget was configurable. */
    public CompanionConfig(URI endpoint, byte[] secret, ConfiguredBinding binding, String acknowledgementTemplate,
                           String characterDisplayName, InteractionPolicy interactionPolicy,
                           List<String> characterAliases, int followupWindowSeconds,
                           AttentionPolicy attentionPolicy) {
        this(endpoint, secret, binding, acknowledgementTemplate, characterDisplayName, interactionPolicy,
                characterAliases, followupWindowSeconds, attentionPolicy, 30, CharacterChatStyle.PLAIN,
                AbilityPolicy.denyAll(), "0".repeat(64));
    }

    /** Source-compatible direct construction for configurations without optional aliases. */
    public CompanionConfig(URI endpoint, byte[] secret, ConfiguredBinding binding, String acknowledgementTemplate,
                           String characterDisplayName, InteractionPolicy interactionPolicy) {
        this(endpoint, secret, binding, acknowledgementTemplate, characterDisplayName, interactionPolicy,
                List.of(), 30, AttentionPolicy.disabled(), 30, CharacterChatStyle.PLAIN,
                AbilityPolicy.denyAll(), "0".repeat(64));
    }

    /** Source-compatible direct construction for configurations with aliases but the default window. */
    public CompanionConfig(URI endpoint, byte[] secret, ConfiguredBinding binding, String acknowledgementTemplate,
                           String characterDisplayName, InteractionPolicy interactionPolicy, List<String> characterAliases) {
        this(endpoint, secret, binding, acknowledgementTemplate, characterDisplayName, interactionPolicy,
                characterAliases, 30, AttentionPolicy.disabled(), 30, CharacterChatStyle.PLAIN,
                AbilityPolicy.denyAll(), "0".repeat(64));
    }

    /** Source-compatible full construction before attention configuration was introduced. */
    public CompanionConfig(URI endpoint, byte[] secret, ConfiguredBinding binding, String acknowledgementTemplate,
                           String characterDisplayName, InteractionPolicy interactionPolicy, List<String> characterAliases,
                           int followupWindowSeconds) {
        this(endpoint, secret, binding, acknowledgementTemplate, characterDisplayName, interactionPolicy,
                characterAliases, followupWindowSeconds, AttentionPolicy.disabled(), 30, CharacterChatStyle.PLAIN,
                AbilityPolicy.denyAll(), "0".repeat(64));
    }

    @Override
    public byte[] secret() {
        return secret.clone();
    }

    public static CompanionConfig load(Path path) throws IOException {
        return StartupConfig.load(path).serverConfig();
    }

    static boolean isValidAcknowledgementTemplateForStartup(String template) {
        return isValidAcknowledgementTemplate(template);
    }

    static List<String> validateAliasesForStartup(List<String> aliases, String displayName) {
        return validateCharacterAliases(aliases, displayName);
    }

    @Override
    public String toString() {
        return "CompanionConfig[local startup snapshot]";
    }

    private static int parseFollowupWindow(String raw) throws InvalidFollowupWindowConfigException {
        if (raw == null) return 30;
        if (!raw.matches("[0-9]+")) throw new InvalidFollowupWindowConfigException();
        try {
            int value = Integer.parseInt(raw);
            if (value > 30) throw new InvalidFollowupWindowConfigException();
            return value;
        } catch (NumberFormatException overflow) {
            throw new InvalidFollowupWindowConfigException();
        }
    }

    private static int parseExecutionBudget(String raw) throws InvalidExecutionBudgetConfigException {
        if (raw == null) return 30;
        if (!raw.matches("[0-9]+")) throw new InvalidExecutionBudgetConfigException();
        try {
            int value = Integer.parseInt(raw);
            if (value < 1 || value > 30) throw new InvalidExecutionBudgetConfigException();
            return value;
        } catch (NumberFormatException overflow) {
            throw new InvalidExecutionBudgetConfigException();
        }
    }

    private static List<String> parseCharacterAliases(String value) {
        if (value == null || value.isEmpty()) return List.of();
        String[] entries = value.split(",", -1);
        if (entries.length > MAX_CHARACTER_ALIASES) throw new IllegalArgumentException(INVALID_CHARACTER_ALIASES);
        List<String> aliases = new ArrayList<>(entries.length);
        for (String entry : entries) aliases.add(stripEntryEdgeSpaces(entry));
        return aliases;
    }

    private static List<String> validateCharacterAliases(List<String> values, String displayName) {
        if (values == null || values.size() > MAX_CHARACTER_ALIASES) {
            throw new IllegalArgumentException(INVALID_CHARACTER_ALIASES);
        }
        List<String> aliases = new ArrayList<>(values.size());
        Set<String> normalized = new HashSet<>();
        normalized.add(NaturalAddressParser.normalize(displayName));
        int totalLength = 0;
        for (String value : values) {
            if (value == null) throw new IllegalArgumentException(INVALID_CHARACTER_ALIASES);
            String alias = stripEntryEdgeSpaces(value);
            if (alias.isEmpty() || alias.length() > MAX_CHARACTER_ALIAS_LENGTH ||
                    !VanillaDialogue.isValidCharacterDisplayName(alias)) {
                throw new IllegalArgumentException(INVALID_CHARACTER_ALIASES);
            }
            totalLength += alias.length();
            if (totalLength > MAX_CHARACTER_ALIASES_TOTAL_LENGTH ||
                    !normalized.add(NaturalAddressParser.normalize(alias))) {
                throw new IllegalArgumentException(INVALID_CHARACTER_ALIASES);
            }
            aliases.add(alias);
        }
        return List.copyOf(aliases);
    }

    private static String stripEntryEdgeSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') start++;
        while (end > start && value.charAt(end - 1) == ' ') end--;
        return value.substring(start, end);
    }

    private static String requiredAcknowledgement(Properties properties) throws InvalidAcknowledgementTemplateException {
        String value = properties.getProperty("acknowledgementTemplate");
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new InvalidAcknowledgementTemplateException();
        }
        return value.trim();
    }

    private static void validateAcknowledgementTemplate(String template) throws InvalidAcknowledgementTemplateException {
        if (!isValidAcknowledgementTemplate(template)) throw new InvalidAcknowledgementTemplateException();
    }

    private static boolean isValidAcknowledgementTemplate(String template) {
        if (template == null || template.isBlank() || template.length() > MAX_ACKNOWLEDGEMENT_TEMPLATE_LENGTH ||
                hasUnsafeControl(template)) return false;
        String withoutPlayerPlaceholder = template.replace("{player}", "");
        if (withoutPlayerPlaceholder.contains("{") || withoutPlayerPlaceholder.contains("}")) return false;
        return VanillaDialogue.isUsableAcknowledgementTemplate(template);
    }

    /** Safe, typed config failure so startup can give an actionable private diagnostic without echoing the value. */
    public static final class InvalidAcknowledgementTemplateException extends PrivateConfigurationException {
        private static final long serialVersionUID = 1L;

        InvalidAcknowledgementTemplateException() {
            super(PrivateConfigurationProblem.invalid(PrivateConfigurationProblem.Field.SERVER_ACKNOWLEDGEMENT,
                    PrivateConfigurationProblem.Expected.ACKNOWLEDGEMENT));
        }
    }

    public static final class InvalidCharacterDisplayNameException extends PrivateConfigurationException {
        private static final long serialVersionUID = 1L;

        InvalidCharacterDisplayNameException() {
            super(PrivateConfigurationProblem.invalid(PrivateConfigurationProblem.Field.SERVER_DISPLAY_NAME,
                    PrivateConfigurationProblem.Expected.DISPLAY_NAME));
        }
    }

    public static final class InvalidInteractionPolicyConfigException extends PrivateConfigurationException {
        private static final long serialVersionUID = 1L;

        InvalidInteractionPolicyConfigException() {
            super(PrivateConfigurationProblem.invalid(PrivateConfigurationProblem.Field.SERVER_ALLOWED_PLAYERS,
                    PrivateConfigurationProblem.Expected.UUID_LIST));
        }
    }

    public static final class InvalidCharacterAliasesConfigException extends PrivateConfigurationException {
        private static final long serialVersionUID = 1L;

        InvalidCharacterAliasesConfigException() {
            super(PrivateConfigurationProblem.invalid(PrivateConfigurationProblem.Field.SERVER_ALIASES,
                    PrivateConfigurationProblem.Expected.ALIASES));
        }
    }

    public static final class InvalidFollowupWindowConfigException extends PrivateConfigurationException {
        private static final long serialVersionUID = 1L;

        InvalidFollowupWindowConfigException() {
            super(PrivateConfigurationProblem.invalid(PrivateConfigurationProblem.Field.SERVER_FOLLOWUP_WINDOW,
                    PrivateConfigurationProblem.Expected.FOLLOWUP_WINDOW));
        }
    }

    public static final class InvalidExecutionBudgetConfigException extends PrivateConfigurationException {
        private static final long serialVersionUID = 1L;

        InvalidExecutionBudgetConfigException() {
            super(PrivateConfigurationProblem.invalid(PrivateConfigurationProblem.Field.SERVER_EXECUTION_BUDGET,
                    PrivateConfigurationProblem.Expected.EXECUTION_BUDGET));
        }
    }

    public static final class InvalidAttentionPolicyConfigException extends PrivateConfigurationException {
        private static final long serialVersionUID = 1L;

        InvalidAttentionPolicyConfigException() {
            this(PrivateConfigurationProblem.Field.SERVER_ATTENTION, PrivateConfigurationProblem.Expected.ATTENTION_OBJECT);
        }

        InvalidAttentionPolicyConfigException(PrivateConfigurationProblem.Field field,
                                              PrivateConfigurationProblem.Expected expected) {
            super(PrivateConfigurationProblem.invalid(field, expected));
        }
    }

    public static final class InvalidCharacterChatStyleConfigException extends PrivateConfigurationException {
        private static final long serialVersionUID = 1L;

        InvalidCharacterChatStyleConfigException() {
            super(PrivateConfigurationProblem.invalid(PrivateConfigurationProblem.Field.SERVER_CHAT_STYLE,
                    PrivateConfigurationProblem.Expected.CHAT_STYLE));
        }
    }

    private static void validateAbilityPolicyScope(AbilityPolicy policy, ConfiguredBinding binding) {
        List<AbilityPolicy.Grant> grants = policy.grants();
        List<AbilityPolicy.Denial> denials = policy.denials();
        if (grants == null || denials == null || grants.size() > AbilityPolicy.MAX_ENTRIES ||
                denials.size() > AbilityPolicy.MAX_ENTRIES) {
            throw new IllegalArgumentException(INVALID_ABILITY_POLICY);
        }
        for (AbilityPolicy.Grant grant : grants) {
            if (grant == null || !binding.characterId().equals(grant.characterId())) {
                throw new IllegalArgumentException(INVALID_ABILITY_POLICY);
            }
        }
        // Denials have no character identifier in the v1 schema and can only narrow this policy.
        for (AbilityPolicy.Denial denial : denials) {
            if (denial == null) throw new IllegalArgumentException(INVALID_ABILITY_POLICY);
        }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank() || value.length() > 512) throw new IOException("missing or invalid configuration field");
        return value.trim();
    }

    private static String identifier(String value) throws IOException {
        if (!ID.matcher(value).matches()) throw new IOException("invalid binding identifier");
        return value;
    }

    private static boolean hasUnsafeControl(String value) {
        return value.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\t');
    }

    private static void requireOwnerOnly(Path path) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            EnumSet<PosixFilePermission> forbidden = EnumSet.of(
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
            forbidden.retainAll(permissions);
            if (!forbidden.isEmpty()) throw new IOException("credential file permissions must be owner-only (0600)");
        } catch (UnsupportedOperationException ignored) {
            // The supported Linux deployment is POSIX; preflight reports this explicitly elsewhere.
        }
    }
}
