package org.worldspeaker.compat;

import java.util.Objects;

/** Immutable, value-free diagnostic data for private startup configuration failures. */
public record PrivateConfigurationProblem(Code code, Field field, Expected expected, Action action) {
    public PrivateConfigurationProblem {
        Objects.requireNonNull(code);
        Objects.requireNonNull(field);
        Objects.requireNonNull(expected);
        Objects.requireNonNull(action);
    }

    public static PrivateConfigurationProblem invalid(Field field, Expected expected) {
        return invalid(field, expected, Action.CORRECT_V1_FIELD);
    }

    public static PrivateConfigurationProblem invalid(Field field, Expected expected, Action action) {
        return new PrivateConfigurationProblem(Code.FIELD_INVALID, field, expected, action);
    }

    public static PrivateConfigurationProblem unknownKey() {
        return new PrivateConfigurationProblem(Code.UNKNOWN_KEY, Field.UNKNOWN_KEY, Expected.NO_UNKNOWN_KEYS,
                Action.REMOVE_UNKNOWN_KEYS);
    }

    public static PrivateConfigurationProblem legacyBootstrap() {
        return new PrivateConfigurationProblem(Code.LEGACY_BOOTSTRAP, Field.BOOTSTRAP,
                Expected.VERSIONED_BOOTSTRAP, Action.MIGRATE_LEGACY_BOOTSTRAP);
    }

    public static PrivateConfigurationProblem privateFile(Field field, Expected expected, Action action) {
        return new PrivateConfigurationProblem(Code.PRIVATE_FILE_INVALID, field, expected, action);
    }

    public static PrivateConfigurationProblem personaInactive() {
        return new PrivateConfigurationProblem(Code.PERSONA_PUBLICATION_UNQUALIFIED, Field.PERSONA_FILE,
                Expected.PERSONA_INACTIVE, Action.KEEP_PERSONA_INACTIVE);
    }

    public String safeMessage() {
        return code.wireCode + " field=" + field.label + " expected=" + expected.description + " action=" + action.instruction;
    }

    public enum Code {
        FIELD_INVALID("WS-COMPAT-CONFIG-FIELD-INVALID"),
        UNKNOWN_KEY("WS-COMPAT-CONFIG-UNKNOWN-KEY"),
        LEGACY_BOOTSTRAP("WS-COMPAT-CONFIG-LEGACY-BOOTSTRAP"),
        PRIVATE_FILE_INVALID("WS-COMPAT-CONFIG-PRIVATE-FILE-INVALID"),
        PERSONA_PUBLICATION_UNQUALIFIED("WS-COMPAT-PERSONA-PUBLICATION-CONTROL-UNQUALIFIED");

        private final String wireCode;

        Code(String wireCode) { this.wireCode = wireCode; }

        public String wireCode() { return wireCode; }
    }

    /** Only schema paths and fixed labels belong here; never add input-derived strings. */
    public enum Field {
        BOOTSTRAP("bootstrap"),
        STARTUP_BUNDLE("startup.bundle"),
        SCHEMA_VERSION("schemaVersion"),
        CONFIG_FILE("configFile"),
        CREDENTIAL_FILE("credentialFile"),
        CREDENTIAL_CONTENT("credentialFile content"),
        UNKNOWN_KEY("unknown-key"),
        BINDING_SERVER_ID("binding.serverId"),
        BINDING_WORLD_ID("binding.worldId"),
        BINDING_CHARACTER_ID("binding.characterId"),
        BINDING_AGENT_ID("binding.agentId"),
        COMPANION_BIND_HOST("companion.bindHost"),
        COMPANION_PORT("companion.port"),
        COMPANION_DEPLOYMENT_ID("companion.deploymentId"),
        COMPANION_MANIFEST_HASH("companion.manifestSha256"),
        SERVER_ENDPOINT("server.endpoint"),
        SERVER_ACKNOWLEDGEMENT("server.acknowledgementTemplate"),
        SERVER_DISPLAY_NAME("server.characterDisplayName"),
        SERVER_ALLOWED_PLAYERS("server.conversationAllowedPlayers"),
        SERVER_ALIASES("server.characterAliases"),
        SERVER_FOLLOWUP_WINDOW("server.followupWindowSeconds"),
        SERVER_EXECUTION_BUDGET("server.executionBudgetSeconds"),
        SERVER_CHAT_STYLE("server.characterChatStyle"),
        SERVER_ATTENTION("server.attention"),
        ATTENTION_SUBSCRIPTIONS("server.attention.subscriptions"),
        ATTENTION_MAX_EVENTS("server.attention.maxEvents"),
        ATTENTION_MAX_AGE("server.attention.maxAgeSeconds"),
        ATTENTION_MAX_BYTES("server.attention.maxBytes"),
        ATTENTION_MAX_EVENTS_PER_SECOND("server.attention.maxEventsPerSecond"),
        ATTENTION_MAX_EVENTS_PER_PLAYER_SECOND("server.attention.maxEventsPerPlayerSecond"),
        ABILITY_POLICY("abilityPolicy"),
        ABILITY_POLICY_SCHEMA_VERSION("abilityPolicy.schemaVersion"),
        ABILITY_POLICY_REVISION("abilityPolicy.revision"),
        ABILITY_POLICY_GRANTS("abilityPolicy.grants"),
        ABILITY_POLICY_DENIALS("abilityPolicy.denials"),
        ABILITY_DENIAL_ABILITY_ID("abilityPolicy.denials[].abilityId"),
        ABILITY_GRANT_ABILITY_ID("abilityPolicy.grants[].abilityId"),
        ABILITY_POLICY_PROTECTION("abilityPolicy.protection"),
        ABILITY_EXTERNAL_CLAIMS_MODE("abilityPolicy.protection.externalClaimsMode"),
        PERSONA_FILE("personaFile"),
        NODE_RUNTIME("Node.js runtime");

        private final String label;

        Field(String label) { this.label = label; }

        public String label() { return label; }
    }

    public enum Expected {
        VERSIONED_BOOTSTRAP("schemaVersion=1 and one configFile reference"),
        V1_BUNDLE("exact schemaVersion 1 JSON bundle"),
        SCHEMA_VERSION_1("integer 1"),
        CANONICAL_LOCAL_REFERENCE("canonical absolute local path"),
        PRIVATE_BOOTSTRAP_FILE("0600 regular single-link file, 0700 parent, 1..4096 bytes"),
        PRIVATE_BUNDLE_FILE("0600 regular single-link file, 0700 parent, 1..32768 bytes"),
        PRIVATE_CREDENTIAL_FILE("0600 regular single-link file, 0700 parent, 1..86 bytes"),
        PRIVATE_READER_RUNTIME("pinned private-file helper with Node.js 24.22.1"),
        CREDENTIAL_CONTENT("canonical unpadded base64url encoding of 32..64 bytes"),
        NO_UNKNOWN_KEYS("only fields defined by startup schema v1"),
        ASCII_IDENTIFIER("ASCII string [A-Za-z0-9._:-], 1..128 characters"),
        LOOPBACK_HOST("literal 127.0.0.1"),
        PORT("integer 1024..65535"),
        INSTALLED_DEPLOYMENT("installed deployment identifier and manifest digest"),
        STRICT_ENDPOINT("strict ws/wss endpoint syntax, 1..2048 printable ASCII characters"),
        ACKNOWLEDGEMENT("visible safe text, at most 256 units, optional {player}"),
        DISPLAY_NAME("visible naturally addressable single-line name, 1..48 units"),
        UUID_LIST("array of at most 32 unique canonical UUID strings"),
        ALIASES("at most eight unique visible naturally addressable names, 1..48 units each"),
        FOLLOWUP_WINDOW("integer 0..30"),
        EXECUTION_BUDGET("integer 1..30"),
        CHAT_STYLE("plain or bracketed"),
        ATTENTION_OBJECT("bounded attention object with known fields only"),
        SUBSCRIPTIONS("up to five unique supported subscription strings"),
        INTEGER_1_64("integer 1..64"),
        INTEGER_1_60("integer 1..60"),
        INTEGER_1024_8192("integer 1024..8192"),
        INTEGER_1_32("integer 1..32"),
        INTEGER_1_8_AT_MOST_GLOBAL("integer 1..8 and no greater than maxEventsPerSecond"),
        ABILITY_ID_V1("known observe_player or strike_lightning ability v1"),
        BOUNDED_ABILITY_POLICY("bounded schemaVersion 1 ability policy with known fields"),
        PERSONA_INACTIVE("persona publication control remains unqualified");

        private final String description;

        Expected(String description) { this.description = description; }

        public String description() { return description; }
    }

    public enum Action {
        CORRECT_V1_FIELD("correct this field in the v1 bundle and coordinate a restart"),
        CORRECT_BOOTSTRAP("set bootstrap schemaVersion=1 and configFile to the private v1 bundle, then restart"),
        REMOVE_UNKNOWN_KEYS("remove unknown fields from the v1 bundle and restart"),
        MIGRATE_LEGACY_BOOTSTRAP("migrate inline legacy properties privately to a v1 bundle and restart"),
        REPAIR_BOOTSTRAP_FILE("repair private bootstrap file; helper requires Node.js 24.22.1; restart"),
        REPAIR_BUNDLE_FILE("repair private startup bundle file; helper requires Node.js 24.22.1; restart"),
        REPAIR_CREDENTIAL_FILE("repair private credential file; helper requires Node.js 24.22.1; restart"),
        REPLACE_CREDENTIAL_CONTENT("write canonical unpadded base64url for 32..64 bytes to the private credential file and restart"),
        INSTALL_NODE_RUNTIME("provide Node.js 24.22.1 on the trusted service PATH and restart"),
        KEEP_PERSONA_INACTIVE("keep persona inactive until qualified private-prose publication control exists");

        private final String instruction;

        Action(String instruction) { this.instruction = instruction; }

        public String instruction() { return instruction; }
    }
}
