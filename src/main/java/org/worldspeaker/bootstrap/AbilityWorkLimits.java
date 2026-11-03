package org.worldspeaker.bootstrap;

import org.worldspeaker.ability.AbilityPolicyResolver;
import org.worldspeaker.ability.AbilityValidation;
import org.worldspeaker.compat.AbilityPolicy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Fixed, typed work ceilings for the two selected abilities. This class never performs game work. */
public final class AbilityWorkLimits {
    public static final int MAX_REQUEST_BYTES = 4_096;
    public static final int MAX_RESULT_BYTES = 4_096;
    public static final int MAX_JSON_DEPTH = 8;
    public static final int MAX_LIGHTNING_COLUMNS = 81;
    public static final int MAX_LIGHTNING_CHUNKS = 4;
    public static final int MAX_AUDIO_RECIPIENTS = 32;
    public static final double MAX_AUDIO_RADIUS = 32.0;
    public static final double MAX_AUDIO_VOLUME = 1.0;
    public static final long MAX_PRESENTATION_MILLIS = 1_000;
    public static final long MAX_OBSERVE_READ_AGE_MILLIS = 1_000;
    public static final long MAX_OBSERVE_DELIVERY_AGE_MILLIS = 2_000;
    public static final long MAX_LIGHTNING_FIRST_ADMISSION_AGE_MILLIS = 5_000;
    public static final long MAX_LIGHTNING_INVOCATION_AGE_MILLIS = 15_000;

    private AbilityWorkLimits() {}

    /** A declarative bound; it does not describe work that has already happened. */
    public record WorkPlan(Kind kind, int requestBytes, int resultBytes, int jsonDepth,
                           int inputTargets, int targetLookups, int inventoryIterations,
                           int entityScans, int worldScans, int chunkLoads,
                           int presentations, long presentationLifetimeMillis, int restrikes,
                           int damageAttempts, int audioRecipients, double audioRadius,
                           double audioVolume, boolean audioRecipientsPreauthorized) {}

    public enum Kind { OBSERVE, LIGHTNING }
    public enum Rejection {
        NONE, INVALID_BOUNDS, MISSING_FACTS, OUT_OF_SCOPE, INVALID_PLACEMENT,
        INVALID_FOOTPRINT, QUALIFICATION_UNAVAILABLE
    }

    public record WorkDecision(boolean allowedForBoundedHold, Rejection rejection,
                               Placement placement) {
        public WorkDecision {
            if (rejection == null || allowedForBoundedHold != (rejection == Rejection.NONE)) {
                throw new IllegalArgumentException("invalid work-limit decision");
            }
        }
    }

    public record Placement(String dimension, int feetBlockX, int feetBlockY, int feetBlockZ,
                            double originX, double originY, double originZ,
                            int minColumnX, int maxColumnXExclusive, int minColumnZ,
                            int maxColumnZExclusive, int distinctChunks) {}

    public static WorkPlan observePlan(int requestBytes, int resultBytes, int depth) {
        return new WorkPlan(Kind.OBSERVE, requestBytes, resultBytes, depth, 1, 1,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
    }

    public static WorkPlan lightningPlan(int requestBytes, int resultBytes, int depth,
                                         boolean damage, int audioRecipients,
                                         boolean recipientsPreauthorized) {
        return new WorkPlan(Kind.LIGHTNING, requestBytes, resultBytes, depth, 1, 1,
                0, 0, 0, 0, 1, MAX_PRESENTATION_MILLIS, 0, damage ? 1 : 0,
                audioRecipients, MAX_AUDIO_RADIUS, MAX_AUDIO_VOLUME, recipientsPreauthorized);
    }

    public static Rejection validatePlan(WorkPlan plan) {
        if (plan == null || plan.kind() == null || plan.requestBytes() < 1 ||
                plan.requestBytes() > MAX_REQUEST_BYTES || plan.resultBytes() < 1 ||
                plan.resultBytes() > MAX_RESULT_BYTES || plan.jsonDepth() < 1 ||
                plan.jsonDepth() > MAX_JSON_DEPTH || plan.inputTargets() != 1 ||
                plan.targetLookups() != 1 || plan.inventoryIterations() != 0 ||
                plan.entityScans() != 0 || plan.worldScans() != 0 || plan.chunkLoads() != 0) {
            return Rejection.INVALID_BOUNDS;
        }
        if (plan.kind() == Kind.OBSERVE) {
            return plan.presentations() == 0 && plan.presentationLifetimeMillis() == 0 &&
                    plan.restrikes() == 0 && plan.damageAttempts() == 0 && plan.audioRecipients() == 0 &&
                    plan.audioRadius() == 0 && plan.audioVolume() == 0 && !plan.audioRecipientsPreauthorized()
                    ? Rejection.NONE : Rejection.INVALID_BOUNDS;
        }
        if (plan.presentations() != 1 || plan.presentationLifetimeMillis() < 0 ||
                plan.presentationLifetimeMillis() > MAX_PRESENTATION_MILLIS || plan.restrikes() != 0 ||
                plan.damageAttempts() < 0 || plan.damageAttempts() > 1 || plan.audioRecipients() < 0 ||
                plan.audioRecipients() > MAX_AUDIO_RECIPIENTS || !Double.isFinite(plan.audioRadius()) ||
                plan.audioRadius() < 0 || plan.audioRadius() > MAX_AUDIO_RADIUS ||
                !Double.isFinite(plan.audioVolume()) || plan.audioVolume() < 0 ||
                plan.audioVolume() > MAX_AUDIO_VOLUME) return Rejection.INVALID_BOUNDS;
        // No current owner evidence can authenticate audio recipients; a boolean claim is not authority.
        if (plan.audioRecipients() != 0 || plan.audioRecipientsPreauthorized()) {
            return Rejection.QUALIFICATION_UNAVAILABLE;
        }
        return Rejection.NONE;
    }

    /** Exact arithmetic over the supplied IEEE-754 values; no rounded squared-distance shortcut. */
    public static boolean withinEuclideanRange(AbilityPolicyResolver.Position left,
                                               AbilityPolicyResolver.Position right,
                                               double maximum) {
        if (!finite(left) || !finite(right) ||
                !Double.isFinite(maximum) || maximum < 0) return false;
        BigDecimal dx = new BigDecimal(left.x()).subtract(new BigDecimal(right.x()));
        BigDecimal dy = new BigDecimal(left.y()).subtract(new BigDecimal(right.y()));
        BigDecimal dz = new BigDecimal(left.z()).subtract(new BigDecimal(right.z()));
        BigDecimal bound = new BigDecimal(maximum);
        return dx.multiply(dx).add(dy.multiply(dy)).add(dz.multiply(dz))
                .compareTo(bound.multiply(bound)) <= 0;
    }

    /** A finite full footprint: 81 half-open block columns in no more than four chunks. */
    public static Optional<Placement> placement(String dimension, AbilityPolicyResolver.Position feet) {
        if (dimension == null || dimension.length() > 128 ||
                !dimension.matches("[A-Za-z0-9._/-]+:[A-Za-z0-9._/-]+") || !finite(feet)) return Optional.empty();
        double floorX = Math.floor(feet.x());
        double floorY = Math.floor(feet.y());
        double floorZ = Math.floor(feet.z());
        if (!safeInt(floorX) || !safeInt(floorY) || !safeInt(floorZ)) return Optional.empty();
        int x = (int) floorX;
        int y = (int) floorY;
        int z = (int) floorZ;
        final int minX;
        final int maxX;
        final int minZ;
        final int maxZ;
        try {
            minX = Math.subtractExact(x, 4);
            maxX = Math.addExact(minX, 9);
            minZ = Math.subtractExact(z, 4);
            maxZ = Math.addExact(minZ, 9);
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
        Set<Long> chunks = new HashSet<>(4);
        for (int columnX = minX; columnX < maxX; columnX++) {
            for (int columnZ = minZ; columnZ < maxZ; columnZ++) {
                int chunkX = Math.floorDiv(columnX, 16);
                int chunkZ = Math.floorDiv(columnZ, 16);
                chunks.add((((long) chunkX) << 32) ^ (chunkZ & 0xffff_ffffL));
                if (chunks.size() > MAX_LIGHTNING_CHUNKS) return Optional.empty();
            }
        }
        if (maxX - minX != MAX_LIGHTNING_COLUMNS / 9 || maxZ - minZ != MAX_LIGHTNING_COLUMNS / 9) {
            return Optional.empty();
        }
        return Optional.of(new Placement(dimension, x, y, z, x + 0.5, y, z + 0.5,
                minX, maxX, minZ, maxZ, chunks.size()));
    }

    /** Exact cap; callers must not round a value above five down into the accepted range. */
    public static BigDecimal damageAttemptCap(double grantMaximum, float currentBaseHealth) {
        if (!Double.isFinite(grantMaximum) || grantMaximum <= 0 || !Float.isFinite(currentBaseHealth)) {
            throw new IllegalArgumentException("damage facts are unavailable");
        }
        BigDecimal grant = new BigDecimal(grantMaximum);
        BigDecimal five = BigDecimal.valueOf(5);
        BigDecimal healthRoom = new BigDecimal((double) currentBaseHealth).subtract(BigDecimal.ONE);
        if (healthRoom.signum() < 0) healthRoom = BigDecimal.ZERO;
        return grant.min(five).min(healthRoom);
    }

    /** Inclusive ability age boundary. Backward clocks and subtraction overflow fail closed. */
    public static boolean ageWithinInclusive(long nowMillis, long capturedMillis, long maximumMillis) {
        if (maximumMillis < 0) return false;
        try {
            long age = Math.subtractExact(nowMillis, capturedMillis);
            return age >= 0 && age <= maximumMillis;
        } catch (ArithmeticException ambiguous) {
            return false;
        }
    }

    /** Monotonic-nanosecond counterpart used for exact inclusive admission/start edges. */
    static boolean ageWithinInclusiveNanos(long nowNanos, long capturedAtNanos, long maximumAgeNanos) {
        if (maximumAgeNanos < 0) return false;
        try {
            long age = Math.subtractExact(nowNanos, capturedAtNanos);
            return age >= 0 && age <= maximumAgeNanos;
        } catch (ArithmeticException ambiguous) {
            return false;
        }
    }

    /** Existing request admission is deliberately stricter than the inclusive ability boundary. */
    public static boolean requestStillLive(long nowMillis, long capturedMillis, long requestLifetimeMillis) {
        if (requestLifetimeMillis <= 0) return false;
        try {
            long age = Math.subtractExact(nowMillis, capturedMillis);
            return age >= 0 && age < requestLifetimeMillis;
        } catch (ArithmeticException ambiguous) {
            return false;
        }
    }

    public static int utf8Bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    static WorkDecision validateCurrentFacts(AbilityValidation.Input intent,
                                              AbilityPolicy.Grant selectedGrant,
                                              AbilityPolicyResolver.WorldFacts facts,
                                              String externalClaimsMode, WorkPlan plan) {
        Rejection planFailure = validatePlan(plan);
        if (planFailure != Rejection.NONE || intent == null || selectedGrant == null || facts == null) {
            return new WorkDecision(false, planFailure == Rejection.NONE ? Rejection.MISSING_FACTS : planFailure, null);
        }
        if (!Boolean.TRUE.equals(facts.requestLive()) || !Boolean.TRUE.equals(facts.currentWorldMatches()) ||
                !Boolean.TRUE.equals(facts.requesterConnected()) || !Boolean.TRUE.equals(facts.targetResolved()) ||
                !Boolean.TRUE.equals(facts.targetConnected()) || !Boolean.TRUE.equals(facts.targetAlive()) ||
                !Boolean.TRUE.equals(facts.targetNotSpectating()) ||
                !Boolean.TRUE.equals(facts.targetChunkLoadedAndTicking()) || facts.requesterDimension() == null ||
                !facts.requesterDimension().equals(facts.targetDimension()) || facts.requesterFeet() == null ||
                facts.targetFeet() == null || !validBorder(facts.worldBorder())) {
            return new WorkDecision(false, Rejection.MISSING_FACTS, null);
        }
        if (!withinEuclideanRange(facts.requesterFeet(), facts.targetFeet(),
                Math.min(64.0, selectedGrant.maxRangeBlocks()))) {
            return new WorkDecision(false, Rejection.OUT_OF_SCOPE, null);
        }
        if (AbilityPolicy.OBSERVE.equals(intent.abilityId())) {
            if (!containsFeet(facts.worldBorder(), facts.targetFeet()) ||
                    selectedGrant.region() != null && !containsFeet(selectedGrant.region(), facts.targetFeet())) {
                return new WorkDecision(false, Rejection.OUT_OF_SCOPE, null);
            }
            return new WorkDecision(true, Rejection.NONE, null);
        }

        boolean claimsRequired = "REQUIRED".equals(externalClaimsMode);
        if (!Boolean.TRUE.equals(facts.lightningSkyAccess()) ||
                !Boolean.TRUE.equals(facts.protectedSpawnClear()) ||
                claimsRequired && !Boolean.TRUE.equals(facts.protectedClaimsClear())) {
            if (facts.lightningSkyAccess() == null || facts.protectedSpawnClear() == null ||
                    claimsRequired && facts.protectedClaimsClear() == null) {
                return new WorkDecision(false, Rejection.MISSING_FACTS, null);
            }
            return new WorkDecision(false, Rejection.QUALIFICATION_UNAVAILABLE, null);
        }
        Optional<Placement> placement = placement(facts.targetDimension(), facts.targetFeet());
        if (placement.isEmpty()) return new WorkDecision(false, Rejection.INVALID_PLACEMENT, null);
        Placement area = placement.get();
        if (!containsColumns(facts.worldBorder(), area.minColumnX(), area.maxColumnXExclusive(),
                area.minColumnZ(), area.maxColumnZExclusive()) || selectedGrant.region() != null &&
                !containsColumns(selectedGrant.region(), area.minColumnX(), area.maxColumnXExclusive(),
                        area.minColumnZ(), area.maxColumnZExclusive())) {
            return new WorkDecision(false, Rejection.OUT_OF_SCOPE, null);
        }
        return new WorkDecision(true, Rejection.NONE, area);
    }

    private static boolean containsFeet(AbilityPolicyResolver.Border border,
                                       AbilityPolicyResolver.Position feet) {
        return feet.x() >= border.minX() && feet.x() < border.maxXExclusive() &&
                feet.z() >= border.minZ() && feet.z() < border.maxZExclusive();
    }

    private static boolean containsFeet(AbilityPolicy.Region region,
                                       AbilityPolicyResolver.Position feet) {
        return feet.x() >= region.minX() && feet.x() < region.maxXExclusive() &&
                feet.z() >= region.minZ() && feet.z() < region.maxZExclusive();
    }

    private static boolean containsColumns(AbilityPolicyResolver.Border border,
                                          int minX, int maxXExclusive, int minZ, int maxZExclusive) {
        return minX >= border.minX() && maxXExclusive <= border.maxXExclusive() &&
                minZ >= border.minZ() && maxZExclusive <= border.maxZExclusive();
    }

    private static boolean containsColumns(AbilityPolicy.Region region,
                                          int minX, int maxXExclusive, int minZ, int maxZExclusive) {
        return minX >= region.minX() && maxXExclusive <= region.maxXExclusive() &&
                minZ >= region.minZ() && maxZExclusive <= region.maxZExclusive();
    }

    private static boolean safeInt(double value) {
        return Double.isFinite(value) && value == Math.rint(value) && value >= Integer.MIN_VALUE &&
                value <= Integer.MAX_VALUE;
    }

    private static boolean finite(AbilityPolicyResolver.Position position) {
        return position != null && Double.isFinite(position.x()) && Double.isFinite(position.y()) &&
                Double.isFinite(position.z());
    }

    private static boolean validBorder(AbilityPolicyResolver.Border border) {
        return border != null && Double.isFinite(border.minX()) && Double.isFinite(border.maxXExclusive()) &&
                Double.isFinite(border.minZ()) && Double.isFinite(border.maxZExclusive()) &&
                border.minX() < border.maxXExclusive() && border.minZ() < border.maxZExclusive();
    }

    enum ReserveBoundary { ADMISSION, START }
}
