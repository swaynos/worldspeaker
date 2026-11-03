package org.worldspeaker.ability;

import org.worldspeaker.compat.AbilityPolicy;
import org.worldspeaker.bootstrap.AbilityRequestAuthority;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Pure, deny-or-conditional policy evaluation; this class has no game or admission providers. */
public final class AbilityPolicyResolver {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private AbilityPolicyResolver() {}

    /** Discovery candidates are requester-filtered metadata. No execution grant is selected here. */
    public static Decision resolveDiscovery(AbilityValidation.AbilityTrustedContextHandle context,
                                            AbilityRequestAuthority.ExpectedContext expected, String abilityId) {
        return resolveDiscovery(AbilityRequestAuthority.currentSnapshot(context, expected), abilityId);
    }

    /** Pure discovery evaluation over a checked immutable snapshot. */
    public static Decision resolveDiscovery(AbilityRequestAuthority.ContextSnapshot trusted, String abilityId) {
        if (trusted == null) return denied(abilityId, 1, 0, Reason.STALE_CONTEXT);
        if (!supported(abilityId, 1)) return denied(abilityId, 1, trusted.policyRevision(), Reason.UNSUPPORTED_CONTRACT);
        AbilityPolicy policy = trusted.policy();
        if (policy.denials().stream().anyMatch(denial -> denial.abilityId().equals(abilityId) && denial.contractVersion() == 1)) {
            return denied(abilityId, 1, policy.revision(), Reason.EXPLICIT_DENIAL);
        }
        List<AbilityPolicy.Grant> abilityGrants = policy.grants().stream()
                .filter(grant -> grant.characterId().equals(trusted.binding().characterId()))
                .filter(grant -> grant.abilityId().equals(abilityId) && grant.contractVersion() == 1)
                .toList();
        if (abilityGrants.isEmpty()) return denied(abilityId, 1, policy.revision(), Reason.NO_COMPLETE_GRANT);
        List<AbilityPolicy.Grant> requesterGrants = abilityGrants.stream()
                .filter(grant -> grant.requesterIds().contains(trusted.requesterId()))
                .sorted(Comparator.comparing(AbilityPolicy.Grant::id)).toList();
        if (requesterGrants.isEmpty()) return denied(abilityId, 1, policy.revision(), Reason.REQUESTER_NOT_GRANTED);
        return conditional(abilityId, 1, policy.revision(), null, requesterGrants,
                null, List.of(), Reason.NONE, null);
    }

    /** Intent evaluation selects one complete grant; missing trusted world facts remain explicit conditions. */
    public static Decision resolveIntent(AbilityValidation.AbilityTrustedContextHandle context,
                                         AbilityRequestAuthority.ExpectedContext expected,
                                         AbilityValidation.Input intent, WorldFacts facts) {
        return resolveIntent(AbilityRequestAuthority.currentSnapshot(context, expected), intent, facts);
    }

    /** Pure intent evaluation over a checked immutable snapshot and explicit testable facts. */
    public static Decision resolveIntent(AbilityRequestAuthority.ContextSnapshot trusted,
                                         AbilityValidation.Input intent, WorldFacts facts) {
        if (intent == null) return denied("", 1, 0, Reason.STALE_CONTEXT);
        boolean visualOnly = !(intent instanceof AbilityValidation.StrikeInput strike) || strike.visualOnly();
        return resolveIntent(trusted, intent.abilityId(), 1, intent.targetPlayerId(), visualOnly, facts);
    }

    /** Versioned typed entry point; callers still receive only DENIED or CONDITIONAL. */
    public static Decision resolveIntent(AbilityValidation.AbilityTrustedContextHandle context, String abilityId,
                                         AbilityRequestAuthority.ExpectedContext expected, int contractVersion,
                                         String targetId, boolean visualOnly, WorldFacts facts) {
        return resolveIntent(AbilityRequestAuthority.currentSnapshot(context, expected), abilityId,
                contractVersion, targetId, visualOnly, facts);
    }

    /** Pure versioned resolver entry point over a checked immutable snapshot. */
    public static Decision resolveIntent(AbilityRequestAuthority.ContextSnapshot trusted, String abilityId,
                                         int contractVersion, String targetId, boolean visualOnly, WorldFacts facts) {
        if (trusted == null) return denied(abilityId == null ? "" : abilityId, contractVersion, 0, Reason.STALE_CONTEXT);
        long revision = trusted.policyRevision();
        if (!supported(abilityId, contractVersion)) return denied(abilityId, contractVersion, revision, Reason.UNSUPPORTED_CONTRACT);
        if (!canonicalUuid(targetId)) return denied(abilityId, contractVersion, revision, Reason.TARGET_UNAVAILABLE);
        AbilityPolicy policy = trusted.policy();
        if (policy.revision() != revision || !validBinding(trusted.binding())) {
            return denied(abilityId, 1, revision, Reason.STALE_CONTEXT);
        }
        if (policy.denials().stream().anyMatch(denial -> denial.abilityId().equals(abilityId) && denial.contractVersion() == 1)) {
            return denied(abilityId, 1, revision, Reason.EXPLICIT_DENIAL);
        }
        List<AbilityPolicy.Grant> abilityGrants = policy.grants().stream()
                .filter(grant -> grant.characterId().equals(trusted.binding().characterId()))
                .filter(grant -> grant.abilityId().equals(abilityId) && grant.contractVersion() == 1)
                .sorted(Comparator.comparing(AbilityPolicy.Grant::id)).toList();
        if (abilityGrants.isEmpty()) return denied(abilityId, 1, revision, Reason.NO_COMPLETE_GRANT);
        List<AbilityPolicy.Grant> requesterGrants = abilityGrants.stream()
                .filter(grant -> grant.requesterIds().contains(trusted.requesterId())).toList();
        if (requesterGrants.isEmpty()) return denied(abilityId, 1, revision, Reason.REQUESTER_NOT_GRANTED);
        List<AbilityPolicy.Grant> targetGrants = requesterGrants.stream()
                .filter(grant -> targetId.equals(trusted.requesterId()) || grant.otherTargetIds().contains(targetId)).toList();
        if (targetGrants.isEmpty()) return denied(abilityId, 1, revision, Reason.TARGET_NOT_GRANTED);
        boolean damageRequested = AbilityPolicy.LIGHTNING.equals(abilityId) && !visualOnly;
        if (damageRequested && targetGrants.stream().noneMatch(grant -> grant.allowVisual() && grant.allowDamage() && grant.maxDamage() != null)) {
            return denied(abilityId, 1, revision, Reason.SELECTED_GRANT_MODE_DENIED);
        }

        if (facts != null) {
            if (Boolean.FALSE.equals(facts.targetResolved()) || facts.targetPlayerId() != null &&
                    !facts.targetPlayerId().equals(targetId)) return denied(abilityId, 1, revision, Reason.TARGET_UNAVAILABLE);
            if (Boolean.TRUE.equals(facts.targetResolved()) && facts.targetPlayerId() == null) {
                return denied(abilityId, 1, revision, Reason.TARGET_UNAVAILABLE);
            }
            if (Boolean.FALSE.equals(facts.currentWorldMatches()) || Boolean.FALSE.equals(facts.requestLive())) {
                return denied(abilityId, 1, revision, Reason.STALE_CONTEXT);
            }
            if (Boolean.FALSE.equals(facts.requesterConnected()) || Boolean.FALSE.equals(facts.targetConnected()) ||
                    Boolean.FALSE.equals(facts.targetAlive()) || Boolean.FALSE.equals(facts.targetNotSpectating())) {
                return denied(abilityId, 1, revision, Reason.TARGET_UNAVAILABLE);
            }
        }
        if (AbilityPolicy.LIGHTNING.equals(abilityId) && facts != null &&
                (Boolean.FALSE.equals(facts.lightningSkyAccess()) || Boolean.FALSE.equals(facts.protectedSpawnClear()) ||
                        "REQUIRED".equals(policy.externalClaimsMode()) && Boolean.FALSE.equals(facts.protectedClaimsClear()))) {
            return denied(abilityId, 1, revision, Reason.OUT_OF_SCOPE);
        }

        String targetDimension = facts != null && Boolean.TRUE.equals(facts.targetResolved()) &&
                targetId.equals(facts.targetPlayerId()) ? facts.targetDimension() : null;
        if (targetDimension == null) {
            List<AbilityPolicy.Grant> candidates = targetGrants.stream()
                    .sorted(Comparator.comparing(AbilityPolicy.Grant::id)).toList();
            List<UnresolvedCheck> unresolved = unresolved(facts, abilityId, policy.externalClaimsMode(),
                    damageRequested);
            if (!unresolved.contains(UnresolvedCheck.SAME_DIMENSION)) unresolved.add(UnresolvedCheck.SAME_DIMENSION);
            return conditional(abilityId, 1, revision, null, candidates, null, unresolved, Reason.NONE, null);
        }
        if (facts != null && facts.requesterDimension() != null && !facts.requesterDimension().equals(targetDimension)) {
            return denied(abilityId, 1, revision, Reason.OUT_OF_SCOPE);
        }
        List<AbilityPolicy.Grant> dimensionGrants = targetGrants.stream()
                .filter(grant -> grant.dimensions().contains(targetDimension))
                .sorted(Comparator.comparing(AbilityPolicy.Grant::id)).toList();
        if (dimensionGrants.isEmpty()) return denied(abilityId, 1, revision, Reason.DIMENSION_NOT_GRANTED);

        AbilityPolicy.Grant selected = dimensionGrants.getFirst();
        String mode = abilityId.equals(AbilityPolicy.OBSERVE) ? "observe" : !visualOnly ? "damage" : "visual";
        if ("damage".equals(mode) && (!selected.allowVisual() || !selected.allowDamage() || selected.maxDamage() == null)) {
            EffectiveRestrictions restrictions = restrictions(selected, mode, facts);
            return denied(abilityId, 1, revision, selected, restrictions, Reason.SELECTED_GRANT_MODE_DENIED);
        }

        if (spatialIntersection(selected.region(), facts == null ? null : facts.worldBorder()).kind() == IntersectionKind.EMPTY) {
            return denied(abilityId, 1, revision, Reason.OUT_OF_SCOPE);
        }
        EffectiveRestrictions restrictions = restrictions(selected, mode, facts);
        Reason spatialFailure = spatialFailure(selected, abilityId, facts);
        if (spatialFailure != null) {
            return denied(abilityId, 1, revision, selected, restrictions, spatialFailure);
        }
        List<UnresolvedCheck> unresolved = unresolved(facts, abilityId, policy.externalClaimsMode(), "damage".equals(mode));
        LimitDemand demand = demand(trusted, targetId, selected, restrictions);
        return conditional(abilityId, 1, revision, selected, List.of(selected), restrictions, unresolved, Reason.NONE, demand);
    }

    private static Reason spatialFailure(AbilityPolicy.Grant grant, String abilityId, WorldFacts facts) {
        if (facts == null) return null;
        if (Boolean.FALSE.equals(facts.targetResolved()) || Boolean.FALSE.equals(facts.targetChunkLoadedAndTicking())) {
            return Reason.TARGET_UNAVAILABLE;
        }
        Position requester = facts.requesterFeet();
        Position target = facts.targetFeet();
        if (requester == null || target == null) return null;
        if (!requester.finite() || !target.finite()) return Reason.OUT_OF_SCOPE;
        double dx = requester.x() - target.x();
        double dy = requester.y() - target.y();
        double dz = requester.z() - target.z();
        double maxRange = Math.min(64.0, grant.maxRangeBlocks());
        if (!Double.isFinite(dx * dx + dy * dy + dz * dz) || dx * dx + dy * dy + dz * dz > maxRange * maxRange) {
            return Reason.OUT_OF_SCOPE;
        }

        Border border = facts.worldBorder();
        if (border != null && !border.valid()) return Reason.OUT_OF_SCOPE;
        if (AbilityPolicy.LIGHTNING.equals(abilityId)) {
            double columnX = Math.floor(target.x());
            double columnZ = Math.floor(target.z());
            if (!safeInteger(columnX) || !safeInteger(columnZ)) return Reason.OUT_OF_SCOPE;
            double minX = columnX - 4;
            double minZ = columnZ - 4;
            double maxXExclusive = minX + 9;
            double maxZExclusive = minZ + 9;
            if (!safeInteger(minX) || !safeInteger(minZ) || !safeInteger(maxXExclusive) || !safeInteger(maxZExclusive)) {
                return Reason.OUT_OF_SCOPE;
            }
            AbilityPolicy.Region region = grant.region();
            if (region != null && !containsColumns(region, minX, maxXExclusive, minZ, maxZExclusive)) return Reason.OUT_OF_SCOPE;
            if (border != null && (minX < border.minX() || maxXExclusive > border.maxXExclusive() ||
                    minZ < border.minZ() || maxZExclusive > border.maxZExclusive())) return Reason.OUT_OF_SCOPE;
        } else {
            if (grant.region() != null && !containsFeet(grant.region(), target)) return Reason.OUT_OF_SCOPE;
            if (border != null && !containsFeet(border, target)) return Reason.OUT_OF_SCOPE;
        }
        return null;
    }

    private static EffectiveRestrictions restrictions(AbilityPolicy.Grant grant, String mode, WorldFacts facts) {
        Double maxDamage = grant.allowDamage() && grant.maxDamage() != null ? Math.min(5.0, grant.maxDamage()) : null;
        Border border = facts == null || facts.worldBorder() == null || !facts.worldBorder().valid() ? null :
                new Border(facts.worldBorder().minX(), facts.worldBorder().maxXExclusive(),
                        facts.worldBorder().minZ(), facts.worldBorder().maxZExclusive());
        AbilityPolicy.Region region = grant.region() == null ? null : new AbilityPolicy.Region(grant.region().minX(),
                grant.region().maxXExclusive(), grant.region().minZ(), grant.region().maxZExclusive());
        EffectiveSpatialBounds effectiveSpatialBounds = intersect(region, border);
        AbilityPolicy.ObserveLimits observe = grant.observeLimits() == null ? null : new AbilityPolicy.ObserveLimits(
                Math.max(2_000, grant.observeLimits().requesterIntervalMs()),
                Math.max(250, grant.observeLimits().characterIntervalMs()));
        AbilityPolicy.LightningLimits lightning = grant.lightningLimits() == null ? null : new AbilityPolicy.LightningLimits(
                Math.max(30_000, grant.lightningLimits().requesterCooldownMs()),
                Math.max(30_000, grant.lightningLimits().targetCooldownMs()),
                Math.max(1_000, grant.lightningLimits().characterIntervalMs()));
        return new EffectiveRestrictions(Math.min(64.0, grant.maxRangeBlocks()), region, border, effectiveSpatialBounds,
                mode, maxDamage, observe, lightning);
    }

    /** Keeps an unknown current border distinct from a known empty half-open intersection. */
    private static SpatialIntersection spatialIntersection(AbilityPolicy.Region region, Border border) {
        if (border == null || !border.valid()) return new SpatialIntersection(IntersectionKind.UNKNOWN, null);
        double minX = region == null ? border.minX() : Math.max((double) region.minX(), border.minX());
        double maxXExclusive = region == null ? border.maxXExclusive() : Math.min((double) region.maxXExclusive(), border.maxXExclusive());
        double minZ = region == null ? border.minZ() : Math.max((double) region.minZ(), border.minZ());
        double maxZExclusive = region == null ? border.maxZExclusive() : Math.min((double) region.maxZExclusive(), border.maxZExclusive());
        if (minX >= maxXExclusive || minZ >= maxZExclusive) return new SpatialIntersection(IntersectionKind.EMPTY, null);
        return new SpatialIntersection(IntersectionKind.NON_EMPTY,
                new EffectiveSpatialBounds(minX, maxXExclusive, minZ, maxZExclusive));
    }

    private static EffectiveSpatialBounds intersect(AbilityPolicy.Region region, Border border) {
        SpatialIntersection intersection = spatialIntersection(region, border);
        return intersection.kind() == IntersectionKind.NON_EMPTY ? intersection.bounds() : null;
    }

    private static List<UnresolvedCheck> unresolved(WorldFacts facts, String abilityId, String claimsMode, boolean damage) {
        List<UnresolvedCheck> values = new ArrayList<>();
        if (facts == null || facts.requestLive() == null) values.add(UnresolvedCheck.AUTHENTICATED_REQUEST_ELIGIBILITY);
        if (facts == null || facts.requesterConnected() == null) values.add(UnresolvedCheck.REQUESTER_CONNECTED);
        if (facts == null || facts.currentWorldMatches() == null) values.add(UnresolvedCheck.CURRENT_WORLD_INSTANCE);
        values.add(UnresolvedCheck.NORMATIVE_WORLD_IDENTITY_MAPPING);
        if (facts == null || facts.targetResolved() == null) values.add(UnresolvedCheck.TARGET_RESOLUTION);
        if (facts == null || facts.targetConnected() == null || facts.targetAlive() == null || facts.targetNotSpectating() == null) {
            values.add(UnresolvedCheck.TARGET_CONNECTED_ALIVE_NON_SPECTATING);
        }
        if (facts == null || facts.requesterDimension() == null || facts.targetDimension() == null) values.add(UnresolvedCheck.SAME_DIMENSION);
        if (facts == null || facts.requesterFeet() == null || facts.targetFeet() == null) values.add(UnresolvedCheck.REQUESTER_TARGET_RANGE);
        if (facts == null || facts.worldBorder() == null) values.add(UnresolvedCheck.WORLD_BORDER);
        if (facts == null || facts.targetFeet() == null) values.add(UnresolvedCheck.GRANT_REGION);
        if (facts == null || facts.targetChunkLoadedAndTicking() == null) values.add(UnresolvedCheck.TARGET_CHUNK_LOADED_AND_TICKING);
        if (AbilityPolicy.OBSERVE.equals(abilityId)) values.add(UnresolvedCheck.OBSERVATION_FRESHNESS);
        if (AbilityPolicy.LIGHTNING.equals(abilityId)) {
            if (facts == null || facts.lightningSkyAccess() == null) values.add(UnresolvedCheck.LIGHTNING_SKY_ACCESS);
            if (facts == null || facts.protectedSpawnClear() == null) values.add(UnresolvedCheck.PROTECTED_SPAWN);
            if ("REQUIRED".equals(claimsMode) && (facts == null || facts.protectedClaimsClear() == null)) {
                values.add(UnresolvedCheck.EXTERNAL_CLAIMS_AUTHORITY);
            }
            values.add(UnresolvedCheck.DURABLE_PREPARATION);
            values.add(UnresolvedCheck.NATIVE_LIGHTNING_SIDE_EFFECT_SUPPRESSION);
            values.add(UnresolvedCheck.UNKNOWN_OUTCOME_PERSISTENCE);
            if (damage) values.add(UnresolvedCheck.DAMAGE_NONLETHAL_QUALIFICATION);
        }
        values.add(UnresolvedCheck.ATOMIC_LIMIT_ADMISSION);
        values.add(UnresolvedCheck.EXECUTION_BOUNDARY_POLICY_RECHECK);
        values.add(UnresolvedCheck.TICK_THREAD_REVALIDATION);
        values.add(UnresolvedCheck.AUTHORITATIVE_OUTCOME_IDENTITY_MAPPING);
        return values;
    }

    private static LimitDemand demand(AbilityRequestAuthority.ContextSnapshot trusted, String targetId,
                                      AbilityPolicy.Grant grant, EffectiveRestrictions effective) {
        boolean observe = AbilityPolicy.OBSERVE.equals(grant.abilityId());
        long requesterInterval = observe ? Math.max(2_000, grant.observeLimits().requesterIntervalMs())
                : Math.max(30_000, grant.lightningLimits().requesterCooldownMs());
        Long targetCooldown = observe ? null : Math.max(30_000, grant.lightningLimits().targetCooldownMs());
        long characterInterval = observe ? Math.max(250, grant.observeLimits().characterIntervalMs())
                : Math.max(1_000, grant.lightningLimits().characterIntervalMs());
        AbilityValidation.Binding binding = trusted.binding();
        return new LimitDemand(trusted.policyRevision(), grant.id(), grant.abilityId(),
                binding.serverId(), binding.worldId(), binding.characterId(), trusted.requesterId(),
                targetId, trusted.invocationId(), requesterInterval,
                targetCooldown, characterInterval, 1, observe ? 16 : 1, 1);
    }

    private static boolean containsFeet(AbilityPolicy.Region region, Position feet) {
        return feet.x() >= region.minX() && feet.x() < region.maxXExclusive() &&
                feet.z() >= region.minZ() && feet.z() < region.maxZExclusive();
    }

    private static boolean containsFeet(Border border, Position feet) {
        return feet.x() >= border.minX() && feet.x() < border.maxXExclusive() &&
                feet.z() >= border.minZ() && feet.z() < border.maxZExclusive();
    }

    private static boolean containsColumns(AbilityPolicy.Region region, double minX, double maxXExclusive,
                                           double minZ, double maxZExclusive) {
        return minX >= region.minX() && maxXExclusive <= region.maxXExclusive() &&
                minZ >= region.minZ() && maxZExclusive <= region.maxZExclusive();
    }

    private static boolean containsColumns(Border border, double minX, double maxXExclusive,
                                           double minZ, double maxZExclusive) {
        return minX >= border.minX() && maxXExclusive <= border.maxXExclusive() &&
                minZ >= border.minZ() && maxZExclusive <= border.maxZExclusive();
    }

    private static boolean safeInteger(double value) {
        return Double.isFinite(value) && value == Math.rint(value) && value >= -MAX_SAFE_INTEGER && value <= MAX_SAFE_INTEGER;
    }

    private static boolean validBinding(AbilityValidation.Binding binding) {
        return binding != null && stableId(binding.serverId()) && stableId(binding.worldId()) &&
                stableId(binding.characterId()) && stableId(binding.agentId());
    }

    private static boolean stableId(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}");
    }

    private static boolean canonicalUuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean supported(String abilityId, int version) {
        return version == 1 && (AbilityPolicy.OBSERVE.equals(abilityId) || AbilityPolicy.LIGHTNING.equals(abilityId));
    }

    private static Decision conditional(String abilityId, int version, long revision, AbilityPolicy.Grant selected,
                                       List<AbilityPolicy.Grant> candidates, EffectiveRestrictions restrictions,
                                       List<UnresolvedCheck> unresolved, Reason reason, LimitDemand demand) {
        return new Decision(Status.CONDITIONAL, abilityId, version, revision, selected == null ? null : selected.id(),
                candidates, restrictions, unresolved, reason, demand);
    }

    private static Decision denied(String abilityId, int version, long revision, Reason reason) {
        return new Decision(Status.DENIED, abilityId, version, revision, null, List.of(), null, List.of(), reason, null);
    }

    private static Decision denied(String abilityId, int version, long revision, AbilityPolicy.Grant selected,
                                   EffectiveRestrictions restrictions, Reason reason) {
        return new Decision(Status.DENIED, abilityId, version, revision, selected.id(), List.of(selected), restrictions,
                List.of(), reason, null);
    }

    public enum Status { DENIED, CONDITIONAL }
    public enum Reason {
        NONE, STALE_CONTEXT, UNSUPPORTED_CONTRACT, EXPLICIT_DENIAL, NO_COMPLETE_GRANT,
        REQUESTER_NOT_GRANTED, TARGET_NOT_GRANTED, DIMENSION_NOT_GRANTED, TARGET_UNAVAILABLE,
        OUT_OF_SCOPE, SELECTED_GRANT_MODE_DENIED
    }
    public enum UnresolvedCheck {
        AUTHENTICATED_REQUEST_ELIGIBILITY, REQUESTER_CONNECTED, CURRENT_WORLD_INSTANCE,
        NORMATIVE_WORLD_IDENTITY_MAPPING, TARGET_RESOLUTION, TARGET_CONNECTED_ALIVE_NON_SPECTATING,
        SAME_DIMENSION, REQUESTER_TARGET_RANGE, WORLD_BORDER, GRANT_REGION,
        TARGET_CHUNK_LOADED_AND_TICKING, OBSERVATION_FRESHNESS, LIGHTNING_SKY_ACCESS,
        PROTECTED_SPAWN, EXTERNAL_CLAIMS_AUTHORITY, ATOMIC_LIMIT_ADMISSION,
        EXECUTION_BOUNDARY_POLICY_RECHECK, DURABLE_PREPARATION, TICK_THREAD_REVALIDATION,
        NATIVE_LIGHTNING_SIDE_EFFECT_SUPPRESSION, DAMAGE_NONLETHAL_QUALIFICATION,
        AUTHORITATIVE_OUTCOME_IDENTITY_MAPPING, UNKNOWN_OUTCOME_PERSISTENCE
    }

    public record Position(double x, double y, double z) {
        boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }

    public record Border(double minX, double maxXExclusive, double minZ, double maxZExclusive) {
        boolean valid() { return Double.isFinite(minX) && Double.isFinite(maxXExclusive) &&
                Double.isFinite(minZ) && Double.isFinite(maxZExclusive) && minX < maxXExclusive && minZ < maxZExclusive; }
    }

    /** Immutable half-open rectangle, including fractional current-border boundaries. */
    public record EffectiveSpatialBounds(double minX, double maxXExclusive, double minZ, double maxZExclusive) {}

    private enum IntersectionKind { UNKNOWN, EMPTY, NON_EMPTY }

    private record SpatialIntersection(IntersectionKind kind, EffectiveSpatialBounds bounds) {}

    /** Nullable facts mean unresolved. The resolver never invokes a world/provider callback. */
    public record WorldFacts(Boolean currentWorldMatches, Boolean requestLive, Boolean requesterConnected,
                             Boolean targetResolved, String targetPlayerId, Boolean targetConnected,
                             Boolean targetAlive, Boolean targetNotSpectating, Boolean targetChunkLoadedAndTicking,
                             String requesterDimension, String targetDimension, Position requesterFeet,
                             Position targetFeet, Border worldBorder, Boolean lightningSkyAccess,
                             Boolean protectedSpawnClear, Boolean protectedClaimsClear) {}

    /**
     * Spatial values remain separate: region is grant configuration, worldBorder is a current fact,
     * and effectiveSpatialBounds is their known non-empty intersection (null when unknown or empty).
     */
    public record EffectiveRestrictions(double maxRangeBlocks, AbilityPolicy.Region region, Border worldBorder,
                                        EffectiveSpatialBounds effectiveSpatialBounds, String mode, Double maxDamage,
                                        AbilityPolicy.ObserveLimits observeLimits,
                                        AbilityPolicy.LightningLimits lightningLimits) {}

    /** A data-only ABIL-04 demand seam; no limiter or reservation implementation is supplied. */
    public record LimitDemand(long policyRevision, String grantId, String abilityId,
                              String serverId, String worldId, String characterId,
                              String requesterId, String targetPlayerId, String invocationId,
                              long requesterIntervalMs, Long targetCooldownMs, long characterIntervalMs,
                              int maxInFlightPerRequester, int maxInFlightPerCharacter, int invocationsPerOrigin) {}

    public record Decision(Status status, String abilityId, int contractVersion, long policyRevision,
                           String selectedGrantId, List<AbilityPolicy.Grant> candidateGrants,
                           EffectiveRestrictions effectiveRestrictions, List<UnresolvedCheck> unresolvedChecks,
                           Reason reason, LimitDemand limitDemand) {
        public Decision {
            if (status == null || abilityId == null || contractVersion < 1 || policyRevision < 0 || reason == null ||
                    candidateGrants == null || unresolvedChecks == null ||
                    status == Status.DENIED && reason == Reason.NONE || status == Status.CONDITIONAL && reason != Reason.NONE) {
                throw new IllegalArgumentException("invalid ability policy resolution");
            }
            candidateGrants = List.copyOf(candidateGrants);
            unresolvedChecks = List.copyOf(unresolvedChecks);
        }
    }
}
