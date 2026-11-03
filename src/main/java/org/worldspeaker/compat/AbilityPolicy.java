package org.worldspeaker.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict policy syntax for the selected ability catalogue; it is not an execution or discovery API. */
public record AbilityPolicy(long revision, List<Grant> grants, List<Denial> denials, String externalClaimsMode) {
    public static final String OBSERVE = "worldspeaker.core.observe_player";
    public static final String LIGHTNING = "worldspeaker.core.strike_lightning";
    public static final int CONTRACT_VERSION = 1;
    public static final int MAX_ENTRIES = 32;
    public static final int MAX_IDS = 32;
    private static final Pattern STABLE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern MATHEMATICAL_ZERO = Pattern.compile("-?0(?:\\.0+)?(?:[eE][+-]?[0-9]+)?");
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final long MIN_SAFE_INTEGER = -MAX_SAFE_INTEGER;
    private static final BigInteger MAX_SAFE_INTEGER_VALUE = BigInteger.valueOf(MAX_SAFE_INTEGER);
    private static final Set<String> POLICY_FIELDS = Set.of("schemaVersion", "revision", "grants", "denials", "protection");
    private static final Set<String> PROTECTION_FIELDS = Set.of("externalClaimsMode");
    private static final Set<String> REQUIRED_GRANT_FIELDS = Set.of("id", "characterId", "abilityId", "contractVersion",
            "requesterIds", "dimensions");
    private static final Set<String> OPTIONAL_GRANT_FIELDS = Set.of("otherTargetIds", "maxRangeBlocks", "region", "limits");

    public AbilityPolicy {
        if (revision < 0 || revision > MAX_SAFE_INTEGER || grants == null || denials == null ||
                grants.size() > MAX_ENTRIES || denials.size() > MAX_ENTRIES ||
                !("REQUIRED".equals(externalClaimsMode) || "NONE".equals(externalClaimsMode))) {
            throw invalid();
        }
        grants = List.copyOf(grants);
        denials = List.copyOf(denials);
        Set<String> ids = new HashSet<>();
        for (Grant grant : grants) if (!ids.add(grant.id())) throw invalid();
        for (Denial denial : denials) if (!ids.add(denial.id())) throw invalid();
    }

    public static AbilityPolicy denyAll() {
        return new AbilityPolicy(0, List.of(), List.of(), "REQUIRED");
    }

    public static AbilityPolicy parse(JsonElement value, String boundCharacterId) {
        JsonObject policy = object(value);
        exact(policy, POLICY_FIELDS);
        if (!integer(policy.get("schemaVersion"), 1) || !safeInteger(policy.get("revision"))) throw invalid();
        long revision = policy.get("revision").getAsLong();
        JsonObject protection = object(policy.get("protection"));
        if (!only(protection, PROTECTION_FIELDS)) throw invalid();
        String externalClaimsMode = protection.has("externalClaimsMode")
                ? string(protection.get("externalClaimsMode")) : "REQUIRED";
        if (!("REQUIRED".equals(externalClaimsMode) || "NONE".equals(externalClaimsMode))) throw invalid();

        List<Grant> grants = new ArrayList<>();
        JsonArray rawGrants = array(policy.get("grants"));
        if (rawGrants.size() > MAX_ENTRIES) throw invalid();
        Set<String> stableIds = new HashSet<>();
        for (JsonElement raw : rawGrants) {
            JsonObject grant = object(raw);
            JsonElement abilityValue = grant.get("abilityId");
            if (!knownAbility(abilityValue)) throw invalidField("abilityPolicy.grants[].abilityId must name a known v1 ability");
            String ability = string(abilityValue);
            boolean lightning = LIGHTNING.equals(ability);
            Set<String> fields = new HashSet<>(REQUIRED_GRANT_FIELDS);
            fields.addAll(OPTIONAL_GRANT_FIELDS);
            if (lightning) {
                fields.add("allowVisual");
                fields.add("allowDamage");
                fields.add("maxDamage");
            }
            if (!fields.containsAll(grant.keySet())) throw invalidField("unknown-key: remove unknown abilityPolicy grant fields");
            if (!grant.keySet().containsAll(REQUIRED_GRANT_FIELDS)) throw invalid();
            String id = stableId(grant.get("id"));
            if (!stableIds.add(id)) throw invalid();
            String characterId = string(grant.get("characterId"));
            if (!characterId.equals(boundCharacterId)) throw invalid();
            checkAbility(ability, grant.get("contractVersion"));
            List<String> requesters = uuids(grant.get("requesterIds"), 1, MAX_IDS);
            List<String> otherTargets = grant.has("otherTargetIds")
                    ? uuids(grant.get("otherTargetIds"), 0, MAX_IDS) : List.of();
            List<String> dimensions = dimensions(grant.get("dimensions"));
            double range = grant.has("maxRangeBlocks") ? boundedNumber(grant.get("maxRangeBlocks"), 0.0, 64.0)
                    : 64.0;
            Region region = grant.has("region") ? region(grant.get("region")) : null;
            if (!lightning) {
                ObserveLimits limits = grant.has("limits") ? observeLimits(grant.get("limits")) : ObserveLimits.defaults();
                grants.add(new Grant(id, characterId, ability, 1, requesters, otherTargets, dimensions, range,
                        false, false, null, region, limits, null));
                continue;
            }
            if (!grant.has("allowVisual") || !booleanValue(grant.get("allowVisual"))) {
                throw invalid();
            }
            boolean allowDamage = grant.has("allowDamage") && booleanValue(grant.get("allowDamage"));
            Double maxDamage = null;
            if (allowDamage) {
                maxDamage = boundedNumber(grant.get("maxDamage"), 0.0, 5.0);
            } else if (grant.has("maxDamage")) {
                throw invalid();
            }
            LightningLimits limits = grant.has("limits") ? lightningLimits(grant.get("limits")) : LightningLimits.defaults();
            grants.add(new Grant(id, characterId, ability, 1, requesters, otherTargets, dimensions, range,
                    true, allowDamage, maxDamage, region, null, limits));
        }

        List<Denial> denials = new ArrayList<>();
        JsonArray rawDenials = array(policy.get("denials"));
        if (rawDenials.size() > MAX_ENTRIES) throw invalid();
        for (JsonElement raw : rawDenials) {
            JsonObject denial = object(raw);
            exact(denial, Set.of("id", "abilityId", "contractVersion"));
            String id = stableId(denial.get("id"));
            if (!stableIds.add(id)) throw invalid();
            JsonElement abilityValue = denial.get("abilityId");
            if (!knownAbility(abilityValue)) throw invalidField("abilityPolicy.denials[].abilityId must name a known v1 ability");
            String ability = string(abilityValue);
            checkAbility(ability, denial.get("contractVersion"));
            denials.add(new Denial(id, ability, 1));
        }
        return new AbilityPolicy(revision, grants, denials, externalClaimsMode);
    }

    /** Applicable explicit denials always win; callers must not treat this as permission to execute. */
    public boolean explicitlyDenies(String abilityId) {
        return denials.stream().anyMatch(denial -> denial.abilityId().equals(abilityId));
    }

    /** Complete grant ordering is stable and deliberately does not union restrictions. */
    public List<Grant> grantsFor(String abilityId) {
        if (explicitlyDenies(abilityId)) return List.of();
        return grants.stream().filter(grant -> grant.abilityId().equals(abilityId))
                .sorted(Comparator.comparing(Grant::id)).toList();
    }

    public record Grant(String id, String characterId, String abilityId, int contractVersion,
                        List<String> requesterIds, List<String> otherTargetIds, List<String> dimensions,
                        double maxRangeBlocks, boolean allowVisual, boolean allowDamage, Double maxDamage,
                        Region region, ObserveLimits observeLimits, LightningLimits lightningLimits) {
        /** Compatibility constructor for existing typed callers; omitted optional fields use v1 defaults. */
        public Grant(String id, String characterId, String abilityId, int contractVersion,
                     List<String> requesterIds, List<String> otherTargetIds, List<String> dimensions,
                     double maxRangeBlocks, boolean allowVisual, boolean allowDamage, Double maxDamage) {
            this(id, characterId, abilityId, contractVersion, requesterIds, otherTargetIds, dimensions,
                    maxRangeBlocks, allowVisual, allowDamage, maxDamage, null,
                    OBSERVE.equals(abilityId) ? ObserveLimits.defaults() : null,
                    LIGHTNING.equals(abilityId) ? LightningLimits.defaults() : null);
        }

        public Grant {
            if (id == null || !STABLE_ID.matcher(id).matches() || characterId == null || !characterId.matches("[A-Za-z0-9._:-]{1,128}") ||
                    !(OBSERVE.equals(abilityId) || LIGHTNING.equals(abilityId)) || contractVersion != CONTRACT_VERSION ||
                    !Double.isFinite(maxRangeBlocks) || maxRangeBlocks <= 0 || maxRangeBlocks > 64) throw invalid();
            requesterIds = List.copyOf(requesterIds);
            otherTargetIds = List.copyOf(otherTargetIds);
            dimensions = List.copyOf(dimensions);
            validateUuidList(requesterIds, 1, MAX_IDS);
            validateUuidList(otherTargetIds, 0, MAX_IDS);
            if (dimensions.isEmpty() || dimensions.size() > 16 || dimensions.stream().anyMatch(dimension -> dimension == null ||
                    dimension.length() > 256 || !DIMENSION.matcher(dimension).matches()) ||
                    new HashSet<>(dimensions).size() != dimensions.size()) throw invalid();
            if (OBSERVE.equals(abilityId)) {
                if (allowVisual || allowDamage || maxDamage != null || observeLimits == null || lightningLimits != null) throw invalid();
            } else if (!allowVisual || allowDamage && (maxDamage == null || !Double.isFinite(maxDamage) || maxDamage <= 0 || maxDamage > 5) ||
                    !allowDamage && maxDamage != null || observeLimits != null || lightningLimits == null) throw invalid();
        }
    }

    /** Integer half-open X/Z rectangle; it narrows, and never replaces, the current border. */
    public record Region(long minX, long maxXExclusive, long minZ, long maxZExclusive) {
        public Region {
            if (minX < MIN_SAFE_INTEGER || minX > MAX_SAFE_INTEGER || maxXExclusive < MIN_SAFE_INTEGER ||
                    maxXExclusive > MAX_SAFE_INTEGER || minZ < MIN_SAFE_INTEGER || minZ > MAX_SAFE_INTEGER ||
                    maxZExclusive < MIN_SAFE_INTEGER || maxZExclusive > MAX_SAFE_INTEGER ||
                    minX >= maxXExclusive || minZ >= maxZExclusive) throw invalid();
        }
    }

    public record ObserveLimits(long requesterIntervalMs, long characterIntervalMs) {
        public ObserveLimits {
            if (requesterIntervalMs < 2_000 || requesterIntervalMs > 300_000 ||
                    characterIntervalMs < 250 || characterIntervalMs > 300_000) throw invalid();
        }
        public static ObserveLimits defaults() { return new ObserveLimits(2_000, 250); }
    }

    public record LightningLimits(long requesterCooldownMs, long targetCooldownMs, long characterIntervalMs) {
        public LightningLimits {
            if (requesterCooldownMs < 30_000 || requesterCooldownMs > 300_000 ||
                    targetCooldownMs < 30_000 || targetCooldownMs > 300_000 ||
                    characterIntervalMs < 1_000 || characterIntervalMs > 300_000) throw invalid();
        }
        public static LightningLimits defaults() { return new LightningLimits(30_000, 30_000, 1_000); }
    }

    public record Denial(String id, String abilityId, int contractVersion) {
        public Denial {
            if (id == null || !STABLE_ID.matcher(id).matches() || !(OBSERVE.equals(abilityId) || LIGHTNING.equals(abilityId)) ||
                    contractVersion != CONTRACT_VERSION) throw invalid();
        }
    }

    private static void checkAbility(String ability, JsonElement version) {
        if (!(OBSERVE.equals(ability) || LIGHTNING.equals(ability))) {
            throw invalidField("abilityPolicy abilityId must name observe_player or strike_lightning v1");
        }
        if (!integer(version, CONTRACT_VERSION)) throw invalidField("abilityPolicy.contractVersion must be integer 1");
    }

    private static boolean knownAbility(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() &&
                (OBSERVE.equals(value.getAsString()) || LIGHTNING.equals(value.getAsString()));
    }

    private static List<String> uuids(JsonElement element, int minimum, int maximum) {
        JsonArray values = array(element);
        if (values.size() < minimum || values.size() > maximum) throw invalid();
        List<String> result = new ArrayList<>(values.size());
        Set<String> unique = new HashSet<>();
        for (JsonElement value : values) {
            String uuid = string(value);
            try {
                if (!UUID.fromString(uuid).toString().equals(uuid) || !unique.add(uuid)) throw invalid();
            } catch (IllegalArgumentException invalidUuid) {
                throw invalid();
            }
            result.add(uuid);
        }
        return List.copyOf(result);
    }

    private static List<String> dimensions(JsonElement element) {
        JsonArray values = array(element);
        if (values.size() < 1 || values.size() > 16) throw invalid();
        List<String> result = new ArrayList<>(values.size());
        Set<String> unique = new HashSet<>();
        for (JsonElement value : values) {
            String dimension = string(value);
            if (dimension.length() > 256 || !DIMENSION.matcher(dimension).matches() || !unique.add(dimension)) throw invalid();
            result.add(dimension);
        }
        return List.copyOf(result);
    }

    private static void validateUuidList(List<String> values, int minimum, int maximum) {
        if (values.size() < minimum || values.size() > maximum) throw invalid();
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            try {
                if (value == null || !UUID.fromString(value).toString().equals(value) || !unique.add(value)) throw invalid();
            } catch (IllegalArgumentException malformed) {
                throw invalid();
            }
        }
    }

    private static String stableId(JsonElement value) {
        String id = string(value);
        if (!STABLE_ID.matcher(id).matches()) throw invalid();
        return id;
    }

    private static double boundedNumber(JsonElement value, double exclusiveMinimum, double maximum) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid();
        BigDecimal decimal;
        try { decimal = new BigDecimal(value.getAsString()); }
        catch (NumberFormatException malformed) { throw invalid(); }
        if (decimal.compareTo(BigDecimal.valueOf(exclusiveMinimum)) <= 0 ||
                decimal.compareTo(BigDecimal.valueOf(maximum)) > 0) throw invalid();
        double number = decimal.doubleValue();
        if (!Double.isFinite(number) || number <= exclusiveMinimum || number > maximum) throw invalid();
        return number;
    }

    private static Region region(JsonElement value) {
        JsonObject rectangle = object(value);
        exact(rectangle, Set.of("minX", "maxXExclusive", "minZ", "maxZExclusive"));
        return new Region(signedSafeInteger(rectangle.get("minX")), signedSafeInteger(rectangle.get("maxXExclusive")),
                signedSafeInteger(rectangle.get("minZ")), signedSafeInteger(rectangle.get("maxZExclusive")));
    }

    private static ObserveLimits observeLimits(JsonElement value) {
        JsonObject limits = object(value);
        if (!only(limits, Set.of("requesterIntervalMs", "characterIntervalMs"))) throw invalid();
        long requester = limits.has("requesterIntervalMs") ? boundedInteger(limits.get("requesterIntervalMs"), 2_000, 300_000) : 2_000;
        long character = limits.has("characterIntervalMs") ? boundedInteger(limits.get("characterIntervalMs"), 250, 300_000) : 250;
        return new ObserveLimits(requester, character);
    }

    private static LightningLimits lightningLimits(JsonElement value) {
        JsonObject limits = object(value);
        if (!only(limits, Set.of("requesterCooldownMs", "targetCooldownMs", "characterIntervalMs"))) throw invalid();
        long requester = limits.has("requesterCooldownMs") ? boundedInteger(limits.get("requesterCooldownMs"), 30_000, 300_000) : 30_000;
        long target = limits.has("targetCooldownMs") ? boundedInteger(limits.get("targetCooldownMs"), 30_000, 300_000) : 30_000;
        long character = limits.has("characterIntervalMs") ? boundedInteger(limits.get("characterIntervalMs"), 1_000, 300_000) : 1_000;
        return new LightningLimits(requester, target, character);
    }

    private static long boundedInteger(JsonElement value, long minimum, long maximum) {
        BigInteger integer = mathematicalInteger(value);
        if (integer == null || integer.compareTo(BigInteger.valueOf(minimum)) < 0 ||
                integer.compareTo(BigInteger.valueOf(maximum)) > 0) throw invalid();
        return integer.longValueExact();
    }

    private static long signedSafeInteger(JsonElement value) {
        BigInteger integer = mathematicalInteger(value);
        if (integer == null || integer.compareTo(BigInteger.valueOf(MIN_SAFE_INTEGER)) < 0 ||
                integer.compareTo(MAX_SAFE_INTEGER_VALUE) > 0) throw invalid();
        return integer.longValueExact();
    }

    private static boolean safeInteger(JsonElement value) {
        BigInteger integer = mathematicalInteger(value);
        return integer != null && integer.signum() >= 0 && integer.compareTo(MAX_SAFE_INTEGER_VALUE) <= 0;
    }

    private static boolean integer(JsonElement value, long expected) {
        BigInteger integer = mathematicalInteger(value);
        return integer != null && integer.equals(BigInteger.valueOf(expected));
    }

    /** JSON Schema's integer type is mathematical: decimal/exponent spellings may still be integral. */
    private static BigInteger mathematicalInteger(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        String token = value.getAsString();
        if (MATHEMATICAL_ZERO.matcher(token).matches()) return BigInteger.ZERO;
        try { return new BigDecimal(token).stripTrailingZeros().toBigIntegerExact(); }
        catch (NumberFormatException | ArithmeticException malformed) { return null; }
    }

    private static boolean booleanValue(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) throw invalid();
        return element.getAsBoolean();
    }

    private static String string(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw invalid();
        return element.getAsString();
    }

    private static JsonObject object(JsonElement element) {
        if (element == null || !element.isJsonObject()) throw invalid();
        return element.getAsJsonObject();
    }

    private static JsonArray array(JsonElement element) {
        if (element == null || !element.isJsonArray()) throw invalid();
        return element.getAsJsonArray();
    }

    private static void exact(JsonObject object, Set<String> fields) {
        if (!fields.containsAll(object.keySet())) throw invalidField("unknown-key: remove unknown abilityPolicy fields");
        if (!object.keySet().containsAll(fields)) throw invalid();
    }

    private static boolean only(JsonObject object, Set<String> fields) {
        if (!fields.containsAll(object.keySet())) throw invalidField("unknown-key: remove unknown abilityPolicy protection fields");
        return fields.containsAll(object.keySet());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("abilityPolicy must be a bounded schemaVersion 1 policy with known fields and abilities");
    }

    private static IllegalArgumentException invalidField(String description) {
        return new IllegalArgumentException(description);
    }
}
