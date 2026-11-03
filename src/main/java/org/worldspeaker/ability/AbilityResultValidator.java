package org.worldspeaker.ability;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.worldspeaker.ability.AbilityValidation.AbilityTrustedContextHandle;
import org.worldspeaker.bootstrap.AbilityRequestAuthority;
import org.worldspeaker.integration.StrictJsonParser;
import org.worldspeaker.integration.StrictJsonParser.ParsedDocument;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Bounded structural and semantic validation for ability-result bytes.
 *
 * <p>A returned result is an immutable, locally coherent value only. It is not
 * evidence that an outcome is true, permission to perform an operation, a live
 * world observation, or proof that any player received a presentation.</p>
 */
public final class AbilityResultValidator {
    private static final int MAX_RESULT_BYTES = AbilityCatalogue.MAX_RESULT_BYTES;
    private static final int MAX_JSON_DEPTH = AbilityCatalogue.MAX_JSON_DEPTH;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final double MAX_DAMAGE = 5.0;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern MINECRAFT_IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private static final Set<String> OBSERVE_RESULT_FIELDS = Set.of("serverId", "worldId", "characterId", "requestId",
            "invocationId", "abilityId", "contractVersion", "policyRevision", "serverEpochId", "state",
            "reasonCode", "facts", "observedAt", "executedAt", "retryAfterMs", "queryId");
    private static final Set<String> LIGHTNING_RESULT_FIELDS = Set.of("serverId", "worldId", "characterId", "requestId",
            "invocationId", "abilityId", "contractVersion", "policyRevision", "serverEpochId", "state",
            "reasonCode", "facts", "observedAt", "executedAt", "retryAfterMs", "actionId");
    private static final Set<String> OBSERVE_FACT_FIELDS = Set.of("targetPlayerId", "observedAt", "health", "maxHealth",
            "dimension", "biome", "holdingItem");
    private static final Set<String> LIGHTNING_FACT_FIELDS = Set.of("actionId", "targetPlayerId", "dimension", "executedAt",
            "coordinates", "visualOnly", "steps");
    private static final Set<String> PRESENTATION_STEP_FIELDS = Set.of("kind", "state");
    private static final Set<String> DAMAGE_STEP_FIELDS = Set.of("kind", "state", "healthRemoved", "absorptionRemoved",
            "damageDisposition");
    private static final Set<ReasonCode> PARTIAL_UNKNOWN_REASONS = Set.of(ReasonCode.OUTCOME_UNKNOWN,
            ReasonCode.STORAGE_UNAVAILABLE);
    private static final Map<AbilityExecutionWorldBoundsHandle, CapturedWorldBounds> WORLD_BOUNDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private AbilityResultValidator() {}

    /**
     * Validates a complete UTF-8 JSON result frame, returning null on any
     * malformed, out-of-contract, or semantically incoherent input.
     */
    public static AbilityResult validateAbilityResultBytes(byte[] utf8Json) {
        if (utf8Json == null || utf8Json.length < 1 || utf8Json.length > MAX_RESULT_BYTES) return null;
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8Json)).toString();
            if (text.startsWith("\ufeff")) return null;
            ParsedDocument parsed = StrictJsonParser.parseWithNumberTokens(text, MAX_JSON_DEPTH);
            if (!parsed.value().isJsonObject()) return null;
            return parseResult(parsed, parsed.value().getAsJsonObject());
        } catch (RuntimeException | CharacterCodingException malformed) {
            return null;
        }
    }

    /**
     * Future WORLD-adapter seam. Bounds must be captured from trusted current
     * server state, never from result/model fields. This helper does not itself
     * establish spawn/claim protection, loaded chunks, sky access, or any live
     * protection-provider guarantee.
     */
    public static AbilityExecutionWorldBoundsHandle captureAbilityExecutionWorldBounds(
            AbilityTrustedContextHandle context, AbilityRequestAuthority.ExpectedContext expected,
            ExecutionWorldBoundsSnapshot snapshot) {
        if (AbilityRequestAuthority.currentSnapshot(context, expected) == null || !isValidBoundsSnapshot(snapshot)) return null;
        AbilityExecutionWorldBoundsHandle handle = new AbilityExecutionWorldBoundsHandle();
        WORLD_BOUNDS.put(handle, new CapturedWorldBounds(context, expected, snapshot));
        return handle;
    }

    /**
     * Validates result bytes and, for known non-null lightning coordinates,
     * requires an opaque bounds capture tied to the exact current request
     * context. Static packed-position validity alone is not live-world
     * qualification.
     */
    public static AbilityResult validateAbilityResultForAuthoritativePublication(
            byte[] utf8Json, AbilityTrustedContextHandle context,
            AbilityRequestAuthority.ExpectedContext expected, AbilityExecutionWorldBoundsHandle worldBounds) {
        AbilityResult result = validateAbilityResultBytes(utf8Json);
        if (result == null || !(result instanceof StrikeLightningResult lightning) || lightning.facts() == null ||
                lightning.facts().coordinates() == null) return result;
        if (context == null || worldBounds == null) return null;
        CapturedWorldBounds captured = WORLD_BOUNDS.get(worldBounds);
        if (captured == null || captured.context() != context || !captured.expected().equals(expected) ||
                AbilityRequestAuthority.currentSnapshot(context, expected) == null) return null;
        return isInsideExecutionWorld(captured.snapshot(), lightning.facts().dimension(), lightning.facts().coordinates())
                ? result : null;
    }

    private static AbilityResult parseResult(ParsedDocument parsed, JsonObject root) {
        String abilityId = string(root.get("abilityId"));
        AbilityId ability = AbilityId.fromWire(abilityId);
        if (ability == null || !(ability == AbilityId.OBSERVE_PLAYER
                ? hasExactly(root, OBSERVE_RESULT_FIELDS) : hasExactly(root, LIGHTNING_RESULT_FIELDS))) return null;

        Long contractVersion = safeInteger(root.get("contractVersion"), parsed, false);
        Long policyRevision = safeInteger(root.get("policyRevision"), parsed, false);
        if (contractVersion == null || contractVersion != 1L || policyRevision == null) return null;

        UUID serverId = uuid(root.get("serverId"));
        UUID worldId = uuid(root.get("worldId"));
        UUID characterId = uuid(root.get("characterId"));
        UUID requestId = uuid(root.get("requestId"));
        UUID invocationId = uuid(root.get("invocationId"));
        UUID serverEpochId = uuid(root.get("serverEpochId"));
        State state = State.fromWire(string(root.get("state")));
        ReasonCode reason = ReasonCode.fromWire(string(root.get("reasonCode")));
        Long observedAt = nullableSafeInteger(root.get("observedAt"), parsed, false);
        Long executedAt = nullableSafeInteger(root.get("executedAt"), parsed, false);
        Long retryAfterMs = nullableSafeInteger(root.get("retryAfterMs"), parsed, false);
        if (serverId == null || worldId == null || characterId == null || requestId == null || invocationId == null ||
                serverEpochId == null || state == null || reason == null || observedAt == INVALID_INTEGER ||
                executedAt == INVALID_INTEGER || retryAfterMs == INVALID_INTEGER ||
                retryAfterMs != null && retryAfterMs > 30_000 || !reasonAllowsState(reason, state)) return null;
        if (retryAfterMs != null && (state != State.REJECTED || reason != ReasonCode.RATE_LIMITED)) return null;

        OutcomeIdentity identity = new OutcomeIdentity(serverId, worldId, characterId, requestId, invocationId,
                ability, 1, policyRevision, serverEpochId, state, reason, observedAt, executedAt, retryAfterMs);
        JsonElement rawFacts = root.get("facts");
        if (rawFacts == null) return null;
        if (rawFacts.isJsonNull()) {
            if (state == State.EXECUTED || state == State.PARTIAL || observedAt != null || executedAt != null) return null;
            if (ability == AbilityId.OBSERVE_PLAYER) {
                UUID queryId = uuid(root.get("queryId"));
                return queryId == null ? null : new ObservePlayerResult(identity, queryId, null);
            }
            UUID actionId = uuid(root.get("actionId"));
            return actionId == null ? null : new StrikeLightningResult(identity, actionId, null);
        }

        if (ability == AbilityId.OBSERVE_PLAYER) {
            ObserveFacts facts = parseObserveFacts(rawFacts, parsed);
            UUID queryId = uuid(root.get("queryId"));
            if (facts == null || queryId == null || state != State.EXECUTED || reason != ReasonCode.NONE ||
                    observedAt == null || observedAt.longValue() != facts.observedAt() || executedAt != null) return null;
            return new ObservePlayerResult(identity, queryId, facts);
        }

        UUID actionId = uuid(root.get("actionId"));
        LightningFacts facts = parseLightningFacts(rawFacts, parsed);
        if (actionId == null || facts == null || observedAt != null || !actionId.equals(facts.actionId()) ||
                !sameLong(executedAt, facts.executedAt()) || !lightningSemanticsAreCoherent(identity, facts)) return null;
        return new StrikeLightningResult(identity, actionId, facts);
    }

    private static ObserveFacts parseObserveFacts(JsonElement value, ParsedDocument parsed) {
        if (!value.isJsonObject()) return null;
        JsonObject facts = value.getAsJsonObject();
        if (!hasExactly(facts, OBSERVE_FACT_FIELDS)) return null;
        UUID target = uuid(facts.get("targetPlayerId"));
        Long observedAt = safeInteger(facts.get("observedAt"), parsed, false);
        Float health = float32(facts.get("health"), parsed);
        Float maxHealth = float32(facts.get("maxHealth"), parsed);
        String dimension = minecraftIdentifier(facts.get("dimension"));
        String biome = minecraftIdentifier(facts.get("biome"));
        String holdingItem = minecraftIdentifier(facts.get("holdingItem"));
        if (target == null || observedAt == null || health == null || maxHealth == null || health < 0.0f ||
                maxHealth <= 0.0f || health > maxHealth || dimension == null || biome == null || holdingItem == null) return null;
        return new ObserveFacts(target, observedAt, health, maxHealth, dimension, biome, holdingItem);
    }

    private static LightningFacts parseLightningFacts(JsonElement value, ParsedDocument parsed) {
        if (!value.isJsonObject()) return null;
        JsonObject facts = value.getAsJsonObject();
        if (!hasExactly(facts, LIGHTNING_FACT_FIELDS)) return null;
        UUID actionId = uuid(facts.get("actionId"));
        UUID target = uuid(facts.get("targetPlayerId"));
        String dimension = minecraftIdentifier(facts.get("dimension"));
        Long executedAt = nullableSafeInteger(facts.get("executedAt"), parsed, false);
        BlockPosition coordinates = blockPositionOrNull(facts.get("coordinates"), parsed);
        Boolean visualOnly = bool(facts.get("visualOnly"));
        JsonElement rawSteps = facts.get("steps");
        if (actionId == null || target == null || dimension == null || executedAt == INVALID_INTEGER ||
                coordinates == INVALID_POSITION || visualOnly == null || !rawSteps.isJsonArray()) return null;
        JsonArray array = rawSteps.getAsJsonArray();
        if (array.size() < 1 || array.size() > 2) return null;
        java.util.ArrayList<LightningStep> steps = new java.util.ArrayList<>(array.size());
        for (JsonElement rawStep : array) {
            LightningStep step = parseStep(rawStep, parsed);
            if (step == null) return null;
            steps.add(step);
        }
        try {
            return new LightningFacts(actionId, target, dimension, executedAt, coordinates, visualOnly, steps);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static LightningStep parseStep(JsonElement value, ParsedDocument parsed) {
        if (!value.isJsonObject()) return null;
        JsonObject step = value.getAsJsonObject();
        String kind = string(step.get("kind"));
        StepState state = StepState.fromWire(string(step.get("state")));
        if (kind == null || state == null) return null;
        if ("presentation".equals(kind)) {
            return hasExactly(step, PRESENTATION_STEP_FIELDS) ? new PresentationStep(state) : null;
        }
        if (!"damage".equals(kind) || !hasExactly(step, DAMAGE_STEP_FIELDS)) return null;
        JsonElement healthElement = step.get("healthRemoved");
        JsonElement absorptionElement = step.get("absorptionRemoved");
        JsonElement dispositionElement = step.get("damageDisposition");
        Float health;
        Float absorption;
        DamageDisposition disposition;
        if (state == StepState.CONFIRMED) {
            health = float32(healthElement, parsed);
            absorption = float32(absorptionElement, parsed);
            disposition = DamageDisposition.fromWire(string(dispositionElement));
            if (health == null || absorption == null || disposition == null || health < 0.0f || absorption < 0.0f ||
                    health > MAX_DAMAGE || absorption > MAX_DAMAGE || !ExactFloat32Sum.isAtMost(health, absorption, MAX_DAMAGE) ||
                    disposition == DamageDisposition.APPLIED && !ExactFloat32Sum.isPositive(health, absorption) ||
                    (disposition == DamageDisposition.INVULNERABLE || disposition == DamageDisposition.PROTECTION_VETO) &&
                            !ExactFloat32Sum.isZero(health, absorption)) return null;
        } else {
            if (!healthElement.isJsonNull() || !absorptionElement.isJsonNull() || !dispositionElement.isJsonNull()) return null;
            health = null;
            absorption = null;
            disposition = null;
        }
        return new DamageStep(state, health, absorption, disposition);
    }

    private static boolean lightningSemanticsAreCoherent(OutcomeIdentity identity, LightningFacts facts) {
        List<LightningStep> steps = facts.steps();
        if (!(steps.getFirst() instanceof PresentationStep presentation)) return false;
        DamageStep damage = null;
        if (steps.size() == 2) {
            if (!(steps.get(1) instanceof DamageStep parsedDamage)) return false;
            damage = parsedDamage;
        }
        if (facts.visualOnly() && damage != null) return false;
        if (!facts.visualOnly() && presentation.state() == StepState.CONFIRMED && damage == null) return false;

        boolean hasConfirmed = steps.stream().anyMatch(step -> step.state() == StepState.CONFIRMED);
        boolean hasUnknown = steps.stream().anyMatch(step -> step.state() == StepState.UNKNOWN);
        boolean allConfirmed = steps.stream().allMatch(step -> step.state() == StepState.CONFIRMED);
        if (hasConfirmed && facts.coordinates() == null) return false;

        return switch (identity.state()) {
            case EXECUTED -> identity.reasonCode() == ReasonCode.NONE && allConfirmed;
            case PARTIAL -> !facts.visualOnly() && hasConfirmed && !allConfirmed &&
                    (hasUnknown ? PARTIAL_UNKNOWN_REASONS.contains(identity.reasonCode()) :
                            identity.reasonCode() != ReasonCode.OUTCOME_UNKNOWN);
            case FAILED -> !hasConfirmed && !hasUnknown;
            case UNKNOWN -> !hasConfirmed && hasUnknown && PARTIAL_UNKNOWN_REASONS.contains(identity.reasonCode());
            default -> false;
        };
    }

    private static boolean reasonAllowsState(ReasonCode reason, State state) {
        Set<State> states = switch (reason) {
            case NONE -> EnumSet.of(State.ADMITTED, State.SUBMITTED, State.EXECUTED);
            case INVALID_INPUT, UNSUPPORTED_CONTRACT, RATE_LIMITED, CONCURRENCY_LIMITED -> Set.of(State.REJECTED);
            case UNSUPPORTED_STATE, PERMISSION_DENIED, TARGET_UNAVAILABLE, OUT_OF_SCOPE, PROTECTED_AREA,
                    STALE_CONTEXT, EXECUTION_UNAVAILABLE -> EnumSet.of(State.REJECTED, State.FAILED, State.PARTIAL);
            case DEADLINE_EXPIRED -> EnumSet.of(State.EXPIRED, State.PARTIAL);
            case CANCELLED -> EnumSet.of(State.CANCELLED, State.PARTIAL);
            case STORAGE_UNAVAILABLE -> EnumSet.of(State.REJECTED, State.FAILED, State.PARTIAL, State.UNKNOWN);
            case EXECUTION_FAILED -> EnumSet.of(State.FAILED, State.PARTIAL);
            case OUTCOME_UNKNOWN -> EnumSet.of(State.UNKNOWN, State.PARTIAL);
        };
        return states.contains(state);
    }

    private static Float float32(JsonElement value, ParsedDocument parsed) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        String token = parsed.numberToken(value.getAsJsonPrimitive());
        return normalizeFloat32NumberToken(token);
    }

    /** Package-visible for parity tests against the shared Java/Node number corpus. */
    static Float normalizeFloat32NumberToken(String token) {
        if (token == null) return null;
        try {
            BigDecimal exact = new BigDecimal(token);
            float normalized = Float.parseFloat(token);
            if (!Float.isFinite(normalized) || exact.signum() != 0 && normalized == 0.0f) return null;
            BigDecimal javaCanonical = new BigDecimal(Float.toString(normalized));
            BigDecimal ecmaCanonical = new BigDecimal(Double.toString((double) normalized));
            if (exact.compareTo(javaCanonical) != 0 && exact.compareTo(ecmaCanonical) != 0) return null;
            if (exact.signum() == 0 && token.startsWith("-")) return -0.0f;
            return normalized;
        } catch (NumberFormatException | ArithmeticException malformed) {
            return null;
        }
    }

    private static Long nullableSafeInteger(JsonElement value, ParsedDocument parsed, boolean signed) {
        if (value == null) return INVALID_INTEGER;
        if (value.isJsonNull()) return null;
        Long result = safeInteger(value, parsed, signed);
        return result == null ? INVALID_INTEGER : result;
    }

    private static final Long INVALID_INTEGER = Long.MIN_VALUE;
    private static final BlockPosition INVALID_POSITION = new BlockPosition(0, 0, 0);

    private static Long safeInteger(JsonElement value, ParsedDocument parsed, boolean signed) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        String token = parsed.numberToken(value.getAsJsonPrimitive());
        if (!isSafeJsonIntegerToken(token, !signed)) return null;
        try {
            return new BigDecimal(token).toBigIntegerExact().longValueExact();
        } catch (NumberFormatException | ArithmeticException malformed) {
            return null;
        }
    }

    /** Package-visible twin of the TypeScript raw-token mathematical-integer predicate. */
    static boolean isSafeJsonIntegerToken(String token, boolean nonNegative) {
        if (token == null) return false;
        try {
            BigInteger integer = new BigDecimal(token).toBigIntegerExact();
            return integer.abs().compareTo(BigInteger.valueOf(MAX_SAFE_INTEGER)) <= 0 &&
                    (!nonNegative || integer.signum() >= 0);
        } catch (NumberFormatException | ArithmeticException malformed) {
            return false;
        }
    }

    private static BlockPosition blockPositionOrNull(JsonElement value, ParsedDocument parsed) {
        if (value == null) return INVALID_POSITION;
        if (value.isJsonNull()) return null;
        if (!value.isJsonArray()) return INVALID_POSITION;
        JsonArray coordinates = value.getAsJsonArray();
        if (coordinates.size() != 3) return INVALID_POSITION;
        Long x = safeInteger(coordinates.get(0), parsed, true);
        Long y = safeInteger(coordinates.get(1), parsed, true);
        Long z = safeInteger(coordinates.get(2), parsed, true);
        if (x == null || y == null || z == null || x < BLOCK_XZ_MIN || x > BLOCK_XZ_MAX ||
                z < BLOCK_XZ_MIN || z > BLOCK_XZ_MAX || y < BLOCK_Y_MIN || y > BLOCK_Y_MAX) return INVALID_POSITION;
        return new BlockPosition(x.intValue(), y.intValue(), z.intValue());
    }

    private static final long BLOCK_XZ_MIN = -(1L << 25);
    private static final long BLOCK_XZ_MAX = (1L << 25) - 1;
    private static final long BLOCK_Y_MIN = -(1L << 11);
    private static final long BLOCK_Y_MAX = (1L << 11) - 1;

    private static UUID uuid(JsonElement value) {
        String text = string(value);
        if (text == null || !UUID_PATTERN.matcher(text).matches()) return null;
        try {
            UUID parsed = UUID.fromString(text);
            return parsed.toString().equals(text) ? parsed : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static String minecraftIdentifier(JsonElement value) {
        String text = string(value);
        return isMinecraftIdentifier(text) ? text : null;
    }

    private static boolean isMinecraftIdentifier(String text) {
        return text != null && text.length() <= 256 && MINECRAFT_IDENTIFIER.matcher(text).matches();
    }

    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static Boolean bool(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : null;
    }

    private static boolean hasExactly(JsonObject object, Set<String> fields) {
        return object != null && object.keySet().equals(fields);
    }

    private static boolean sameLong(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean isValidBoundsSnapshot(ExecutionWorldBoundsSnapshot snapshot) {
        return snapshot != null && isMinecraftIdentifier(snapshot.dimensionId()) &&
                snapshot.minBuildYInclusive() >= BLOCK_Y_MIN && snapshot.minBuildYInclusive() <= BLOCK_Y_MAX &&
                snapshot.maxBuildYExclusive() > snapshot.minBuildYInclusive() &&
                snapshot.maxBuildYExclusive() <= BLOCK_Y_MAX + 1 &&
                Double.isFinite(snapshot.borderMinX()) && Double.isFinite(snapshot.borderMaxX()) &&
                Double.isFinite(snapshot.borderMinZ()) && Double.isFinite(snapshot.borderMaxZ()) &&
                snapshot.borderMinX() < snapshot.borderMaxX() && snapshot.borderMinZ() < snapshot.borderMaxZ();
    }

    private static boolean isInsideExecutionWorld(ExecutionWorldBoundsSnapshot bounds, String dimension,
                                                 BlockPosition position) {
        if (!dimension.equals(bounds.dimensionId()) || position.y() < bounds.minBuildYInclusive() ||
                position.y() >= bounds.maxBuildYExclusive()) return false;
        long footprintMinX = (long) position.x() - 4;
        long footprintMaxXExclusive = (long) position.x() + 5;
        long footprintMinZ = (long) position.z() - 4;
        long footprintMaxZExclusive = (long) position.z() + 5;
        return footprintMinX >= BLOCK_XZ_MIN && footprintMaxXExclusive - 1 <= BLOCK_XZ_MAX &&
                footprintMinZ >= BLOCK_XZ_MIN && footprintMaxZExclusive - 1 <= BLOCK_XZ_MAX &&
                footprintMinX >= bounds.borderMinX() && footprintMaxXExclusive <= bounds.borderMaxX() &&
                footprintMinZ >= bounds.borderMinZ() && footprintMaxZExclusive <= bounds.borderMaxZ();
    }

    public enum AbilityId {
        OBSERVE_PLAYER("worldspeaker.core.observe_player"),
        STRIKE_LIGHTNING("worldspeaker.core.strike_lightning");
        private final String wire;
        AbilityId(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        private static AbilityId fromWire(String value) {
            for (AbilityId item : values()) if (item.wire.equals(value)) return item;
            return null;
        }
    }

    public enum State {
        REJECTED("rejected"), ADMITTED("admitted"), SUBMITTED("submitted"), EXECUTED("executed"),
        FAILED("failed"), PARTIAL("partial"), EXPIRED("expired"), CANCELLED("cancelled"), UNKNOWN("unknown");
        private final String wire;
        State(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        private static State fromWire(String value) {
            for (State item : values()) if (item.wire.equals(value)) return item;
            return null;
        }
    }

    public enum ReasonCode {
        NONE("none"), INVALID_INPUT("invalid_input"), UNSUPPORTED_CONTRACT("unsupported_contract"),
        UNSUPPORTED_STATE("unsupported_state"), PERMISSION_DENIED("permission_denied"),
        TARGET_UNAVAILABLE("target_unavailable"), OUT_OF_SCOPE("out_of_scope"), PROTECTED_AREA("protected_area"),
        STALE_CONTEXT("stale_context"), RATE_LIMITED("rate_limited"), CONCURRENCY_LIMITED("concurrency_limited"),
        DEADLINE_EXPIRED("deadline_expired"), CANCELLED("cancelled"), STORAGE_UNAVAILABLE("storage_unavailable"),
        EXECUTION_UNAVAILABLE("execution_unavailable"), EXECUTION_FAILED("execution_failed"),
        OUTCOME_UNKNOWN("outcome_unknown");
        private final String wire;
        ReasonCode(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        private static ReasonCode fromWire(String value) {
            for (ReasonCode item : values()) if (item.wire.equals(value)) return item;
            return null;
        }
    }

    public enum StepState {
        PREVENTED("prevented"), CONFIRMED("confirmed"), FAILED("failed"), UNKNOWN("unknown");
        private final String wire;
        StepState(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        private static StepState fromWire(String value) {
            for (StepState item : values()) if (item.wire.equals(value)) return item;
            return null;
        }
    }

    public enum DamageDisposition {
        APPLIED("applied"), MITIGATED("mitigated"), INVULNERABLE("invulnerable"),
        PROTECTION_VETO("protection_veto"), NON_LETHAL_LIMIT("non_lethal_limit");
        private final String wire;
        DamageDisposition(String wire) { this.wire = wire; }
        public String wire() { return wire; }
        private static DamageDisposition fromWire(String value) {
            for (DamageDisposition item : values()) if (item.wire.equals(value)) return item;
            return null;
        }
    }

    public sealed interface AbilityFacts permits ObserveFacts, LightningFacts {}

    public sealed interface AbilityResult permits ObservePlayerResult, StrikeLightningResult {
        OutcomeIdentity identity();
        AbilityFacts facts();
    }

    public record OutcomeIdentity(UUID serverId, UUID worldId, UUID characterId, UUID requestId, UUID invocationId,
                                  AbilityId abilityId, int contractVersion, long policyRevision, UUID serverEpochId,
                                  State state, ReasonCode reasonCode, Long observedAt, Long executedAt,
                                  Long retryAfterMs) {}

    public record ObserveFacts(UUID targetPlayerId, long observedAt, float health, float maxHealth, String dimension,
                               String biome, String holdingItem) implements AbilityFacts {}

    public record BlockPosition(int x, int y, int z) {
        public BlockPosition {
            if (x < BLOCK_XZ_MIN || x > BLOCK_XZ_MAX || z < BLOCK_XZ_MIN || z > BLOCK_XZ_MAX ||
                    y < BLOCK_Y_MIN || y > BLOCK_Y_MAX) throw new IllegalArgumentException("unrepresentable packed BlockPos");
        }
    }

    public sealed interface LightningStep permits PresentationStep, DamageStep {
        StepState state();
    }

    public record PresentationStep(StepState state) implements LightningStep {
        public PresentationStep {
            if (state == null) throw new IllegalArgumentException("missing presentation state");
        }
    }

    public record DamageStep(StepState state, Float healthRemoved, Float absorptionRemoved,
                             DamageDisposition damageDisposition) implements LightningStep {
        public DamageStep {
            if (state == null) throw new IllegalArgumentException("missing damage state");
            boolean confirmed = state == StepState.CONFIRMED;
            if (confirmed != (healthRemoved != null && absorptionRemoved != null && damageDisposition != null) ||
                    !confirmed && (healthRemoved != null || absorptionRemoved != null || damageDisposition != null)) {
                throw new IllegalArgumentException("incoherent damage-step facts");
            }
        }
    }

    public record LightningFacts(UUID actionId, UUID targetPlayerId, String dimension, Long executedAt,
                                 BlockPosition coordinates, boolean visualOnly,
                                 List<LightningStep> steps) implements AbilityFacts {
        public LightningFacts {
            steps = List.copyOf(steps);
        }
    }

    public static final class ObservePlayerResult implements AbilityResult {
        private final OutcomeIdentity identity;
        private final UUID queryId;
        private final ObserveFacts facts;
        private ObservePlayerResult(OutcomeIdentity identity, UUID queryId, ObserveFacts facts) {
            this.identity = identity;
            this.queryId = queryId;
            this.facts = facts;
        }
        @Override public OutcomeIdentity identity() { return identity; }
        public UUID queryId() { return queryId; }
        @Override public ObserveFacts facts() { return facts; }
    }

    public static final class StrikeLightningResult implements AbilityResult {
        private final OutcomeIdentity identity;
        private final UUID actionId;
        private final LightningFacts facts;
        private StrikeLightningResult(OutcomeIdentity identity, UUID actionId, LightningFacts facts) {
            this.identity = identity;
            this.actionId = actionId;
            this.facts = facts;
        }
        @Override public OutcomeIdentity identity() { return identity; }
        public UUID actionId() { return actionId; }
        @Override public LightningFacts facts() { return facts; }
    }

    /** Bounds captured by a future trusted WORLD adapter, not user/model input. */
    public record ExecutionWorldBoundsSnapshot(String dimensionId, int minBuildYInclusive, int maxBuildYExclusive,
                                               double borderMinX, double borderMaxX,
                                               double borderMinZ, double borderMaxZ) {}

    /** Opaque context-bound evidence handle; callers cannot construct one. */
    public static final class AbilityExecutionWorldBoundsHandle {
        private AbilityExecutionWorldBoundsHandle() {}
    }

    private record CapturedWorldBounds(AbilityTrustedContextHandle context,
                                       AbilityRequestAuthority.ExpectedContext expected,
                                       ExecutionWorldBoundsSnapshot snapshot) {}
}
