package org.worldspeaker.ability;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.worldspeaker.compat.AbilityPolicy;
import org.worldspeaker.integration.StrictJsonParser;
import org.worldspeaker.bootstrap.AbilityRequestAuthority;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/** Strict parser for untrusted model intent; all validated operations still remain deny-only. */
public final class AbilityValidation {
    private static final Set<String> ENVELOPE_FIELDS = Set.of("abilityId", "contractVersion", "arguments");
    private static final Set<String> OBSERVE_ARGUMENT_FIELDS = Set.of("targetPlayerId");
    private static final Set<String> STRIKE_ARGUMENT_FIELDS = Set.of("targetPlayerId", "visualOnly");
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private AbilityValidation() {}

    /** Parses untrusted model intent only; parsing never establishes requester or policy authority. */
    public static Decision parse(byte[] utf8Json) {
        if (utf8Json == null || utf8Json.length < 1 || utf8Json.length > AbilityCatalogue.MAX_REQUEST_BYTES) {
            return rejected(Reason.INVALID_INPUT);
        }
        final String text;
        final JsonObject envelope;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8Json)).toString();
            if (text.startsWith("\ufeff")) return rejected(Reason.INVALID_INPUT);
            JsonElement parsed = StrictJsonParser.parse(text, AbilityCatalogue.MAX_JSON_DEPTH);
            if (!parsed.isJsonObject()) return rejected(Reason.INVALID_INPUT);
            envelope = parsed.getAsJsonObject();
        } catch (RuntimeException | java.nio.charset.CharacterCodingException malformed) {
            return rejected(Reason.INVALID_INPUT);
        }
        if (!ENVELOPE_FIELDS.equals(envelope.keySet())) return rejected(Reason.INVALID_INPUT);
        JsonElement rawId = envelope.get("abilityId");
        if (rawId == null || !rawId.isJsonPrimitive() || !rawId.getAsJsonPrimitive().isString()) {
            return rejected(Reason.INVALID_INPUT);
        }
        String abilityId = rawId.getAsString();
        if (AbilityCatalogue.selected().find(abilityId) == null) return rejected(Reason.UNSUPPORTED_CONTRACT);
        JsonElement rawVersion = envelope.get("contractVersion");
        if (rawVersion == null || !rawVersion.isJsonPrimitive() || !rawVersion.getAsJsonPrimitive().isNumber()) {
            return rejected(Reason.INVALID_INPUT);
        }
        BigInteger version = safeMathematicalInteger(rawVersion);
        if (version == null) return rejected(Reason.INVALID_INPUT);
        if (!version.equals(BigInteger.ONE)) return rejected(Reason.UNSUPPORTED_CONTRACT);

        JsonElement rawArguments = envelope.get("arguments");
        if (rawArguments == null || !rawArguments.isJsonObject()) return rejected(Reason.INVALID_INPUT);
        JsonObject arguments = rawArguments.getAsJsonObject();
        boolean observe = AbilityPolicy.OBSERVE.equals(abilityId);
        if (!(observe ? OBSERVE_ARGUMENT_FIELDS : STRIKE_ARGUMENT_FIELDS).containsAll(arguments.keySet()) ||
                !arguments.has("targetPlayerId")) return rejected(Reason.INVALID_INPUT);
        JsonElement rawTarget = arguments.get("targetPlayerId");
        if (rawTarget == null || !rawTarget.isJsonPrimitive() || !rawTarget.getAsJsonPrimitive().isString()) {
            return rejected(Reason.INVALID_INPUT);
        }
        String target = rawTarget.getAsString();
        if (!canonicalUuid(target)) return rejected(Reason.INVALID_INPUT);
        boolean visualOnly = true;
        if (!observe && arguments.has("visualOnly")) {
            JsonElement rawVisual = arguments.get("visualOnly");
            if (rawVisual == null || !rawVisual.isJsonPrimitive() || !rawVisual.getAsJsonPrimitive().isBoolean()) {
                return rejected(Reason.INVALID_INPUT);
            }
            visualOnly = rawVisual.getAsBoolean();
        }
        if (observe && !OBSERVE_ARGUMENT_FIELDS.equals(arguments.keySet()) ||
                !observe && !STRIKE_ARGUMENT_FIELDS.containsAll(arguments.keySet())) return rejected(Reason.INVALID_INPUT);

        Input intent = observe ? new ObserveInput(target) : new StrikeInput(target, visualOnly);
        return new Decision(DecisionStatus.VALID, Reason.NONE, intent);
    }

    /** Checked request consumption; the expected admitted-request identity is matched by the private owner. */
    public static Decision validate(byte[] utf8Json, AbilityTrustedContextHandle context,
                                    AbilityRequestAuthority.ExpectedContext expected) {
        Decision parsed = parse(utf8Json);
        if (parsed.status() != DecisionStatus.VALID) return parsed;
        if (!AbilityRequestAuthority.isCurrent(context, expected)) return rejected(Reason.STALE_CONTEXT);
        return parsed;
    }

    private static boolean canonicalUuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException malformed) { return false; }
    }

    private static BigInteger safeMathematicalInteger(JsonElement value) {
        try {
            BigDecimal number = new BigDecimal(value.getAsString());
            if (number.signum() < 0 || number.compareTo(BigDecimal.valueOf(MAX_SAFE_INTEGER)) > 0 ||
                    number.stripTrailingZeros().scale() > 0) return null;
            return number.toBigIntegerExact();
        } catch (NumberFormatException | ArithmeticException malformed) { return null; }
    }

    private static Decision rejected(Reason reason) {
        return new Decision(DecisionStatus.REJECTED, reason, null);
    }

    public enum DecisionStatus { VALID, REJECTED }
    public enum Reason { NONE, INVALID_INPUT, UNSUPPORTED_CONTRACT, STALE_CONTEXT }
    public sealed interface Input permits ObserveInput, StrikeInput {
        String abilityId();
        String targetPlayerId();
    }
    public record ObserveInput(String targetPlayerId) implements Input {
        public ObserveInput { if (!canonicalUuid(targetPlayerId)) throw new IllegalArgumentException("invalid target UUID"); }
        @Override public String abilityId() { return AbilityPolicy.OBSERVE; }
    }
    public record StrikeInput(String targetPlayerId, boolean visualOnly) implements Input {
        public StrikeInput { if (!canonicalUuid(targetPlayerId)) throw new IllegalArgumentException("invalid target UUID"); }
        @Override public String abilityId() { return AbilityPolicy.LIGHTNING; }
    }
    public record Decision(DecisionStatus status, Reason reason, Input intent) {
        public Decision {
            if (status == null || reason == null || status == DecisionStatus.VALID != (intent != null) ||
                    status == DecisionStatus.VALID != (reason == Reason.NONE)) throw new IllegalArgumentException("invalid ability decision");
        }
    }
    public record Binding(String serverId, String worldId, String characterId, String agentId) {}
    /** A marker type only; authority exists solely for owner-issued registry identities. */
    public interface AbilityTrustedContextHandle {}
}
