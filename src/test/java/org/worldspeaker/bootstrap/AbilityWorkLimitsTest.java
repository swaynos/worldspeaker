package org.worldspeaker.bootstrap;

import org.junit.jupiter.api.Test;
import org.worldspeaker.ability.AbilityPolicyResolver;
import org.worldspeaker.ability.AbilityValidation;
import org.worldspeaker.compat.AbilityPolicy;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AbilityWorkLimitsTest {
    private static final String REQUESTER = "00000000-0000-4000-8000-000000000011";
    private static final String TARGET = "00000000-0000-4000-8000-000000000012";

    @Test
    void plansEnforceUtf8BytesDepthAndFixedWorkRatherThanDisplayedCeilings() {
        assertEquals(2, AbilityWorkLimits.utf8Bytes("é"));
        assertEquals(AbilityWorkLimits.Rejection.NONE,
                AbilityWorkLimits.validatePlan(AbilityWorkLimits.observePlan(4_096, 4_096, 8)));
        assertEquals(AbilityWorkLimits.Rejection.INVALID_BOUNDS,
                AbilityWorkLimits.validatePlan(AbilityWorkLimits.observePlan(4_097, 1, 1)));
        assertEquals(AbilityWorkLimits.Rejection.INVALID_BOUNDS,
                AbilityWorkLimits.validatePlan(AbilityWorkLimits.observePlan(1, 1, 9)));

        AbilityWorkLimits.WorkPlan scan = new AbilityWorkLimits.WorkPlan(AbilityWorkLimits.Kind.OBSERVE,
                1, 1, 1, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, false);
        assertEquals(AbilityWorkLimits.Rejection.INVALID_BOUNDS, AbilityWorkLimits.validatePlan(scan));
        AbilityWorkLimits.WorkPlan inventory = new AbilityWorkLimits.WorkPlan(AbilityWorkLimits.Kind.OBSERVE,
                1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
        assertEquals(AbilityWorkLimits.Rejection.INVALID_BOUNDS, AbilityWorkLimits.validatePlan(inventory));
        AbilityWorkLimits.WorkPlan chunkLoad = new AbilityWorkLimits.WorkPlan(AbilityWorkLimits.Kind.LIGHTNING,
                1, 1, 1, 1, 1, 0, 0, 0, 1, 1, 1_000, 0, 0, 0, 32, 1, false);
        assertEquals(AbilityWorkLimits.Rejection.INVALID_BOUNDS, AbilityWorkLimits.validatePlan(chunkLoad));
        AbilityWorkLimits.WorkPlan unauthorizedAudio = AbilityWorkLimits.lightningPlan(1, 1, 1,
                false, 33, true);
        assertEquals(AbilityWorkLimits.Rejection.INVALID_BOUNDS,
                AbilityWorkLimits.validatePlan(unauthorizedAudio));
        AbilityWorkLimits.WorkPlan unboundedRecipients = AbilityWorkLimits.lightningPlan(1, 1, 1,
                false, 1, true);
        assertEquals(AbilityWorkLimits.Rejection.QUALIFICATION_UNAVAILABLE,
                AbilityWorkLimits.validatePlan(unboundedRecipients));
        assertEquals(AbilityWorkLimits.Rejection.NONE, AbilityWorkLimits.validatePlan(
                AbilityWorkLimits.lightningPlan(4_096, 4_096, 8, false, 0, false)));
        assertEquals(AbilityWorkLimits.Rejection.INVALID_BOUNDS, AbilityWorkLimits.validatePlan(
                new AbilityWorkLimits.WorkPlan(AbilityWorkLimits.Kind.LIGHTNING, 1, 1, 1,
                        1, 1, 0, 0, 0, 0, 1, 1_001, 0, 0, 0,
                        AbilityWorkLimits.MAX_AUDIO_RADIUS, AbilityWorkLimits.MAX_AUDIO_VOLUME, false)));
        assertEquals(AbilityWorkLimits.Rejection.QUALIFICATION_UNAVAILABLE, AbilityWorkLimits.validatePlan(
                AbilityWorkLimits.lightningPlan(1, 1, 1, false, 0, true)));
        assertEquals(AbilityWorkLimits.Rejection.INVALID_BOUNDS, AbilityWorkLimits.validatePlan(
                new AbilityWorkLimits.WorkPlan(AbilityWorkLimits.Kind.LIGHTNING, 1, 1, 1,
                        1, 1, 0, 0, 0, 0, 1, 1_000, 1, 0, 0, 0, 0, false)));
        assertEquals(AbilityWorkLimits.Rejection.INVALID_BOUNDS, AbilityWorkLimits.validatePlan(
                new AbilityWorkLimits.WorkPlan(AbilityWorkLimits.Kind.OBSERVE, 1, 1, 1,
                        1, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, false)));
        assertEquals(AbilityWorkLimits.Rejection.INVALID_BOUNDS, AbilityWorkLimits.validatePlan(
                new AbilityWorkLimits.WorkPlan(AbilityWorkLimits.Kind.LIGHTNING, 1, 1, 1,
                        1, 1, 0, 0, 0, 0, 1, 1_000, 0, 0, 0,
                        AbilityWorkLimits.MAX_AUDIO_RADIUS + 0.001, AbilityWorkLimits.MAX_AUDIO_VOLUME, false)));
        assertEquals(AbilityWorkLimits.Rejection.INVALID_BOUNDS, AbilityWorkLimits.validatePlan(
                new AbilityWorkLimits.WorkPlan(AbilityWorkLimits.Kind.LIGHTNING, 1, 1, 1,
                        1, 1, 0, 0, 0, 0, 1, 1_000, 0, 0, 0,
                        AbilityWorkLimits.MAX_AUDIO_RADIUS, AbilityWorkLimits.MAX_AUDIO_VOLUME + 0.001, false)));
    }

    @Test
    void exactRangeAndPlacementCoverNegativeChunksFractionalFeetAndOverflow() {
        AbilityPolicyResolver.Position origin = new AbilityPolicyResolver.Position(0, 64, 0);
        assertTrue(AbilityWorkLimits.withinEuclideanRange(origin,
                new AbilityPolicyResolver.Position(64, 64, 0), 64));
        assertFalse(AbilityWorkLimits.withinEuclideanRange(origin,
                new AbilityPolicyResolver.Position(64.000_000_1, 64, 0), 64));
        assertFalse(AbilityWorkLimits.withinEuclideanRange(origin,
                new AbilityPolicyResolver.Position(Double.NaN, 64, 0), 64));

        AbilityWorkLimits.Placement negative = AbilityWorkLimits.placement("minecraft:overworld",
                new AbilityPolicyResolver.Position(-0.25, 64.75, -0.25)).orElseThrow();
        assertEquals(-1, negative.feetBlockX());
        assertEquals(64, negative.feetBlockY());
        assertEquals(-5, negative.minColumnX());
        assertEquals(4, negative.maxColumnXExclusive());
        assertEquals(-5, negative.minColumnZ());
        assertEquals(4, negative.maxColumnZExclusive());
        assertEquals(9, negative.maxColumnXExclusive() - negative.minColumnX());
        assertEquals(9, negative.maxColumnZExclusive() - negative.minColumnZ());
        int footprintColumns = 0;
        for (int x = negative.minColumnX(); x < negative.maxColumnXExclusive(); x++) {
            for (int z = negative.minColumnZ(); z < negative.maxColumnZExclusive(); z++) footprintColumns++;
        }
        assertEquals(81, footprintColumns, "all columns, including the four corners, are in the half-open footprint");
        assertEquals(4, negative.distinctChunks(), "the 9x9 footprint is bounded to four chunks at negative edges");
        assertEquals(81, AbilityWorkLimits.MAX_LIGHTNING_COLUMNS);
        assertTrue(AbilityWorkLimits.placement("minecraft:overworld",
                new AbilityPolicyResolver.Position(12.5, 64, 12.5)).isPresent());
        assertTrue(AbilityWorkLimits.placement("minecraft:overworld",
                new AbilityPolicyResolver.Position(Integer.MIN_VALUE, 64, 0)).isEmpty());
        assertTrue(AbilityWorkLimits.placement("minecraft:" + "x".repeat(129), origin).isEmpty());
    }

    @Test
    void deadlinesUseInclusiveAbilityAgesAndStricterRequestExpiry() {
        assertTrue(AbilityWorkLimits.ageWithinInclusive(999, 0, AbilityWorkLimits.MAX_OBSERVE_READ_AGE_MILLIS));
        assertTrue(AbilityWorkLimits.ageWithinInclusive(1_000, 0, AbilityWorkLimits.MAX_OBSERVE_READ_AGE_MILLIS));
        assertFalse(AbilityWorkLimits.ageWithinInclusive(1_001, 0, AbilityWorkLimits.MAX_OBSERVE_READ_AGE_MILLIS));
        assertTrue(AbilityWorkLimits.ageWithinInclusive(2_000, 0, AbilityWorkLimits.MAX_OBSERVE_DELIVERY_AGE_MILLIS));
        assertFalse(AbilityWorkLimits.ageWithinInclusive(2_001, 0, AbilityWorkLimits.MAX_OBSERVE_DELIVERY_AGE_MILLIS));
        assertTrue(AbilityWorkLimits.ageWithinInclusive(4_999, 0,
                AbilityWorkLimits.MAX_LIGHTNING_FIRST_ADMISSION_AGE_MILLIS));
        assertTrue(AbilityWorkLimits.ageWithinInclusive(5_000, 0,
                AbilityWorkLimits.MAX_LIGHTNING_FIRST_ADMISSION_AGE_MILLIS));
        assertFalse(AbilityWorkLimits.ageWithinInclusive(5_001, 0,
                AbilityWorkLimits.MAX_LIGHTNING_FIRST_ADMISSION_AGE_MILLIS));
        assertTrue(AbilityWorkLimits.ageWithinInclusive(14_999, 0,
                AbilityWorkLimits.MAX_LIGHTNING_INVOCATION_AGE_MILLIS));
        assertTrue(AbilityWorkLimits.ageWithinInclusive(15_000, 0, 15_000));
        assertFalse(AbilityWorkLimits.ageWithinInclusive(15_001, 0, 15_000));
        assertFalse(AbilityWorkLimits.ageWithinInclusive(0, 1, 15_000));
        assertFalse(AbilityWorkLimits.ageWithinInclusive(Long.MAX_VALUE, Long.MIN_VALUE, 1));
        for (long maximumAgeNanos : List.of(
                TimeUnit.MILLISECONDS.toNanos(AbilityWorkLimits.MAX_OBSERVE_READ_AGE_MILLIS),
                TimeUnit.MILLISECONDS.toNanos(AbilityWorkLimits.MAX_OBSERVE_DELIVERY_AGE_MILLIS),
                TimeUnit.MILLISECONDS.toNanos(AbilityWorkLimits.MAX_LIGHTNING_FIRST_ADMISSION_AGE_MILLIS),
                TimeUnit.MILLISECONDS.toNanos(AbilityWorkLimits.MAX_LIGHTNING_INVOCATION_AGE_MILLIS))) {
            assertTrue(AbilityWorkLimits.ageWithinInclusiveNanos(maximumAgeNanos - 1, 0, maximumAgeNanos));
            assertTrue(AbilityWorkLimits.ageWithinInclusiveNanos(maximumAgeNanos, 0, maximumAgeNanos));
            assertFalse(AbilityWorkLimits.ageWithinInclusiveNanos(maximumAgeNanos + 1, 0, maximumAgeNanos));
        }
        assertTrue(AbilityWorkLimits.requestStillLive(14_999, 0, 15_000));
        assertFalse(AbilityWorkLimits.requestStillLive(15_000, 0, 15_000));
    }

    @Test
    void damageCapAndCurrentFactsValidationFailClosed() {
        assertEquals(BigDecimal.valueOf(4), AbilityWorkLimits.damageAttemptCap(5, 5));
        assertEquals(BigDecimal.valueOf(2.5), AbilityWorkLimits.damageAttemptCap(2.5, 20));
        assertEquals(BigDecimal.ZERO, AbilityWorkLimits.damageAttemptCap(5, 1.0f));
        float nextHealth = Math.nextUp(1.0f);
        assertEquals(new BigDecimal((double) nextHealth).subtract(BigDecimal.ONE),
                AbilityWorkLimits.damageAttemptCap(5, nextHealth),
                "the nonlethal remainder preserves float32 precision immediately above one health");
        assertThrows(IllegalArgumentException.class,
                () -> AbilityWorkLimits.damageAttemptCap(Double.POSITIVE_INFINITY, 20));

        AbilityPolicy.Grant observeGrant = new AbilityPolicy.Grant("observe-self", "character-test",
                AbilityPolicy.OBSERVE, 1, List.of(REQUESTER), List.of(), List.of("minecraft:overworld"),
                64, false, false, null);
        AbilityPolicyResolver.WorldFacts facts = validObserveFacts();
        AbilityWorkLimits.WorkDecision valid = AbilityWorkLimits.validateCurrentFacts(
                new AbilityValidation.ObserveInput(REQUESTER), observeGrant, facts, "REQUIRED",
                AbilityWorkLimits.observePlan(100, 100, 3));
        assertTrue(valid.allowedForBoundedHold());
        assertNull(valid.placement());

        AbilityPolicyResolver.WorldFacts unknownBorder = new AbilityPolicyResolver.WorldFacts(true, true, true,
                true, REQUESTER, true, true, true, true, "minecraft:overworld", "minecraft:overworld",
                facts.requesterFeet(), facts.targetFeet(), null, null, null, null);
        assertEquals(AbilityWorkLimits.Rejection.MISSING_FACTS,
                AbilityWorkLimits.validateCurrentFacts(new AbilityValidation.ObserveInput(REQUESTER),
                        observeGrant, unknownBorder, "REQUIRED", AbilityWorkLimits.observePlan(100, 100, 3))
                        .rejection());
    }

    @Test
    void lightningRequiresTheWholeFractionalFootprintInsideBorderAndGrantRegion() {
        AbilityPolicy.Region exactRegion = new AbilityPolicy.Region(-5, 4, -5, 4);
        AbilityPolicy.Grant lightningGrant = new AbilityPolicy.Grant("lightning-self", "character-test",
                AbilityPolicy.LIGHTNING, 1, List.of(REQUESTER), List.of(), List.of("minecraft:overworld"),
                64, true, false, null, exactRegion, null, AbilityPolicy.LightningLimits.defaults());
        AbilityValidation.StrikeInput intent = new AbilityValidation.StrikeInput(REQUESTER, true);
        AbilityWorkLimits.WorkPlan plan = AbilityWorkLimits.lightningPlan(100, 100, 3, false, 0, false);
        AbilityPolicyResolver.WorldFacts exact = validLightningFacts(new AbilityPolicyResolver.Border(-5, 4, -5, 4));
        assertTrue(AbilityWorkLimits.validateCurrentFacts(intent, lightningGrant, exact, "NONE", plan)
                .allowedForBoundedHold());

        for (AbilityPolicyResolver.Border border : List.of(
                new AbilityPolicyResolver.Border(-4.999, 4, -5, 4),
                new AbilityPolicyResolver.Border(-5, 3.999, -5, 4),
                new AbilityPolicyResolver.Border(-5, 4, -4.999, 4),
                new AbilityPolicyResolver.Border(-5, 4, -5, 3.999))) {
            assertEquals(AbilityWorkLimits.Rejection.OUT_OF_SCOPE,
                    AbilityWorkLimits.validateCurrentFacts(intent, lightningGrant,
                            validLightningFacts(border), "NONE", plan).rejection());
        }

        AbilityPolicy.Grant narrowRegion = new AbilityPolicy.Grant("lightning-narrow", "character-test",
                AbilityPolicy.LIGHTNING, 1, List.of(REQUESTER), List.of(), List.of("minecraft:overworld"),
                64, true, false, null, new AbilityPolicy.Region(-5, 4, -5, 3), null,
                AbilityPolicy.LightningLimits.defaults());
        assertEquals(AbilityWorkLimits.Rejection.OUT_OF_SCOPE,
                AbilityWorkLimits.validateCurrentFacts(intent, narrowRegion, exact, "NONE", plan).rejection());
    }

    private static AbilityPolicyResolver.WorldFacts validObserveFacts() {
        return new AbilityPolicyResolver.WorldFacts(true, true, true, true, REQUESTER,
                true, true, true, true, "minecraft:overworld", "minecraft:overworld",
                new AbilityPolicyResolver.Position(0, 64, 0), new AbilityPolicyResolver.Position(0, 64, 0),
                new AbilityPolicyResolver.Border(-100, 100, -100, 100), null, null, null);
    }

    private static AbilityPolicyResolver.WorldFacts validLightningFacts(AbilityPolicyResolver.Border border) {
        AbilityPolicyResolver.Position feet = new AbilityPolicyResolver.Position(-0.25, 64.75, -0.25);
        return new AbilityPolicyResolver.WorldFacts(true, true, true, true, REQUESTER,
                true, true, true, true, "minecraft:overworld", "minecraft:overworld", feet, feet, border,
                true, true, true);
    }
}
