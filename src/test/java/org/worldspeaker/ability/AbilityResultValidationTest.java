package org.worldspeaker.ability;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.worldspeaker.ability.AbilityResultValidator.AbilityResult;
import org.worldspeaker.ability.AbilityResultValidator.AbilityExecutionWorldBoundsHandle;
import org.worldspeaker.ability.AbilityResultValidator.ExecutionWorldBoundsSnapshot;
import org.worldspeaker.compat.AbilityPolicy;
import org.worldspeaker.bootstrap.AbilityAuthorityFixture;
import org.worldspeaker.bootstrap.AbilityRequestAuthority;
import org.worldspeaker.integration.StrictJsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AbilityResultValidationTest {
    private static final String EPOCH = "00000000-0000-4000-8000-000000000106";
    private static final String TARGET = "00000000-0000-4000-8000-000000000107";
    private static final String ACTION = "00000000-0000-4000-8000-000000000109";

    @Test
    void sharedSyntheticSemanticCorpusMatchesJavaResultByteValidator() throws Exception {
        JsonObject corpus = StrictJsonParser.parse(Files.readString(Path.of(
                "compatibility/ability-results-v1-corpus.json"))).getAsJsonObject();
        assertEquals(1, corpus.get("schemaVersion").getAsInt());
        assertTrue(corpus.get("fixtureNature").getAsString().contains("synthetic"));
        String prefix = corpus.get("prefix").getAsString();
        JsonObject factTemplates = corpus.getAsJsonObject("facts");
        Set<String> states = new HashSet<>();
        Set<String> reasons = new HashSet<>();
        Set<String> wrongAbilityIdStates = new HashSet<>();
        for (var item : corpus.getAsJsonArray("positiveCases")) {
            JsonObject testCase = item.getAsJsonObject();
            String name = testCase.get("name").getAsString();
            String tail = expand(testCase.get("tail").getAsString(), factTemplates);
            String rawResult = prefix + tail;
            byte[] bytes = rawResult.getBytes(StandardCharsets.UTF_8);
            AbilityResult result = AbilityResultValidator.validateAbilityResultBytes(bytes);
            assertNotNull(result, name);
            assertEquals(testCase.get("state").getAsString(), result.identity().state().wire(), name);
            assertEquals(testCase.get("reason").getAsString(), result.identity().reasonCode().wire(), name);
            states.add(result.identity().state().wire());
            reasons.add(result.identity().reasonCode().wire());

            String correctAbilityId = result.identity().abilityId().wire();
            String wrongAbilityId = AbilityPolicy.OBSERVE.equals(correctAbilityId)
                    ? AbilityPolicy.LIGHTNING : AbilityPolicy.OBSERVE;
            String wrongIdentity = rawResult.replace("\"abilityId\":\"" + correctAbilityId + "\"",
                    "\"abilityId\":\"" + wrongAbilityId + "\"");
            assertNotEquals(rawResult, wrongIdentity, name + " identity mutation target");
            assertNull(AbilityResultValidator.validateAbilityResultBytes(wrongIdentity.getBytes(StandardCharsets.UTF_8)),
                    name + " rejects another known operation ID");
            wrongAbilityIdStates.add(testCase.get("state").getAsString());
        }
        assertEquals(Set.of("rejected", "admitted", "submitted", "executed", "failed", "partial", "expired", "cancelled", "unknown"), states);
        assertEquals(Set.of("rejected", "admitted", "submitted", "executed", "failed", "partial", "expired", "cancelled", "unknown"),
                wrongAbilityIdStates, "wrong operation IDs are checked in every lifecycle state");
        assertEquals(Set.of("none", "invalid_input", "unsupported_contract", "unsupported_state", "permission_denied",
                "target_unavailable", "out_of_scope", "protected_area", "stale_context", "rate_limited",
                "concurrency_limited", "deadline_expired", "cancelled", "storage_unavailable", "execution_unavailable",
                "execution_failed", "outcome_unknown"), reasons);

        String queryResult = null;
        String lightningResult = null;
        for (var item : corpus.getAsJsonArray("positiveCases")) {
            JsonObject testCase = item.getAsJsonObject();
            if ("rejected invalid_input".equals(testCase.get("name").getAsString())) {
                queryResult = prefix + expand(testCase.get("tail").getAsString(), factTemplates);
            } else if ("visual lightning executed".equals(testCase.get("name").getAsString())) {
                lightningResult = prefix + expand(testCase.get("tail").getAsString(), factTemplates);
            }
        }
        assertNotNull(queryResult);
        assertNotNull(lightningResult);
        assertNull(AbilityResultValidator.validateAbilityResultBytes(
                queryResult.replace("\"queryId\":", "\"actionId\":").getBytes(StandardCharsets.UTF_8)),
                "an actionId cannot substitute for queryId in byte input");
        int actionIndex = lightningResult.lastIndexOf("\"actionId\":");
        assertTrue(actionIndex >= 0);
        String lightningFieldSwap = lightningResult.substring(0, actionIndex) +
                lightningResult.substring(actionIndex).replaceFirst("\"actionId\":", "\"queryId\":");
        assertNull(AbilityResultValidator.validateAbilityResultBytes(lightningFieldSwap.getBytes(StandardCharsets.UTF_8)),
                "a queryId cannot substitute for actionId in byte input");

        for (var item : corpus.getAsJsonArray("negativeMutations")) {
            JsonObject mutation = item.getAsJsonObject();
            String name = mutation.get("name").getAsString();
            String sourceName = mutation.get("source").getAsString();
            JsonObject sourceCase = null;
            for (var positive : corpus.getAsJsonArray("positiveCases")) {
                JsonObject candidate = positive.getAsJsonObject();
                if (candidate.get("name").getAsString().equals(sourceName)) sourceCase = candidate;
            }
            assertNotNull(sourceCase, "missing shared positive fixture for " + name);
            String source = prefix + expand(sourceCase.get("tail").getAsString(), factTemplates);
            String find = expand(mutation.get("find").getAsString(), factTemplates);
            String replace = expand(mutation.get("replace").getAsString(), factTemplates);
            int index = "last".equals(mutation.has("occurrence") ? mutation.get("occurrence").getAsString() : "first")
                    ? source.lastIndexOf(find) : source.indexOf(find);
            assertTrue(index >= 0, "mutation needle not found: " + name);
            String changed = source.substring(0, index) + replace + source.substring(index + find.length());
            assertNull(AbilityResultValidator.validateAbilityResultBytes(changed.getBytes(StandardCharsets.UTF_8)), name);
        }

        for (var item : corpus.getAsJsonArray("negativeSuffixes")) {
            JsonObject testCase = item.getAsJsonObject();
            String name = testCase.get("name").getAsString();
            String suffix = expand(testCase.get("tail").getAsString(), factTemplates);
            assertNull(AbilityResultValidator.validateAbilityResultBytes((prefix + suffix).getBytes(StandardCharsets.UTF_8)), name);
        }
    }

    @Test
    void exactNumberCorpusMatchesJavaAndEcmaFloat32EncodingsAndIntegerTokens() throws Exception {
        JsonObject corpus = StrictJsonParser.parse(Files.readString(Path.of(
                "compatibility/ability-numbers-v1-corpus.json"))).getAsJsonObject();
        for (var item : corpus.getAsJsonArray("float32Serializations")) {
            JsonObject sample = item.getAsJsonObject();
            String name = sample.get("name").getAsString();
            int expectedBits = (int) Long.parseLong(sample.get("bits").getAsString(), 16);
            Float fromJava = AbilityResultValidator.normalizeFloat32NumberToken(sample.get("javaText").getAsString());
            Float fromNode = AbilityResultValidator.normalizeFloat32NumberToken(sample.get("nodeJsonText").getAsString());
            assertNotNull(fromJava, name + " Java form");
            assertNotNull(fromNode, name + " Node form");
            assertEquals(expectedBits, Float.floatToRawIntBits(fromJava), name + " Java bits");
            if (expectedBits == 0x80000000) {
                assertEquals(0, Float.floatToRawIntBits(fromNode), "Node JSON zero is positive zero");
            } else {
                assertEquals(expectedBits, Float.floatToRawIntBits(fromNode), name + " Node bits");
            }
        }
        for (var item : corpus.getAsJsonArray("rawFloatTokens")) {
            JsonObject sample = item.getAsJsonObject();
            boolean expected = sample.get("accepted").getAsBoolean();
            assertEquals(expected, AbilityResultValidator.normalizeFloat32NumberToken(sample.get("token").getAsString()) != null,
                    sample.get("name").getAsString());
        }
        for (var item : corpus.getAsJsonArray("safeIntegerTokens")) {
            JsonObject sample = item.getAsJsonObject();
            String token = sample.get("token").getAsString();
            assertEquals(sample.get("safe").getAsBoolean(), AbilityResultValidator.isSafeJsonIntegerToken(token, false), token);
            assertEquals(sample.get("nonNegative").getAsBoolean(), AbilityResultValidator.isSafeJsonIntegerToken(token, true), token);
        }
    }

    @Test
    void resultBytesNormalizeOnlyCanonicalFloat32AndPreserveExactIntegersAndUnknowns() {
        AbilityResultValidator.ObservePlayerResult javaPointOne = observeResult("0.1", "20.0", "100", "100");
        AbilityResultValidator.ObservePlayerResult nodePointOne = observeResult("0.10000000149011612", "20.0", "100", "100");
        assertNotNull(javaPointOne);
        assertNotNull(nodePointOne);
        assertEquals(Float.floatToRawIntBits(javaPointOne.facts().health()), Float.floatToRawIntBits(nodePointOne.facts().health()));
        assertEquals(Float.floatToRawIntBits(0.1f), Float.floatToRawIntBits(javaPointOne.facts().health()));

        AbilityResultValidator.ObservePlayerResult javaMinimumSubnormal = observeResult("1.4E-45", "1.4E-45", "100", "100");
        AbilityResultValidator.ObservePlayerResult nodeMinimumSubnormal = observeResult(
                "1.401298464324817e-45", "1.401298464324817e-45", "100", "100");
        assertNotNull(javaMinimumSubnormal);
        assertNotNull(nodeMinimumSubnormal);
        assertEquals(Float.floatToRawIntBits(javaMinimumSubnormal.facts().health()),
                Float.floatToRawIntBits(nodeMinimumSubnormal.facts().health()));
        assertEquals(1, Float.floatToRawIntBits(javaMinimumSubnormal.facts().health()));

        AbilityResultValidator.ObservePlayerResult javaMax = observeResult("3.4028235E38", "3.4028235E38", "100", "100");
        AbilityResultValidator.ObservePlayerResult nodeMax = observeResult(
                "3.4028234663852886e38", "3.4028234663852886e38", "100", "100");
        assertNotNull(javaMax);
        assertNotNull(nodeMax);
        assertEquals(Float.floatToRawIntBits(Float.MAX_VALUE), Float.floatToRawIntBits(javaMax.facts().health()));
        assertEquals(Float.floatToRawIntBits(javaMax.facts().health()), Float.floatToRawIntBits(nodeMax.facts().health()));

        assertNotNull(observeResult("21.0", "21.0", "100", "100"), "health above the vanilla default is not clamped");
        AbilityResultValidator.ObservePlayerResult negativeZero = observeResult("-0.0", "1.0", "-0.000e9999", "-0");
        assertNotNull(negativeZero);
        assertEquals(0x80000000, Float.floatToRawIntBits(negativeZero.facts().health()));
        assertNull(observeResult("1e-50", "1.0", "100", "100"), "nonzero float underflow is rejected");
        assertNull(observeResult("0.100000001490116120000000000001", "1.0", "100", "100"), "hidden precision is rejected");
        assertNull(observeResult("1.0000000000000001", "2.0", "100", "100"), "arbitrary rounding is rejected");
        assertNull(observeResult("3.4028236E38", "3.4028235E38", "100", "100"), "float overflow is rejected");
        assertNull(observeResult("0.1", "20.0", "1.0000000000000001", "1.0000000000000001"),
                "fractional timestamp cannot round to an integer");
        assertNotNull(observeResult("0.1", "20.0", "9.007199254740991e15", "9007199254740991"),
                "exact maximum safe integer is accepted");
        assertNull(observeResult("0.1", "20.0", "9007199254740992", "9007199254740992"),
                "integer above the safe boundary is rejected");
        assertNotNull(AbilityResultValidator.validateAbilityResultBytes(lightningDamageResult("3.0", "1.9999998807907104")),
                "damage sum below five remains valid");
        assertNull(AbilityResultValidator.validateAbilityResultBytes(lightningDamageResult("3.0", "2.000000238418579")),
                "sum above five must not round down through Java float addition");
    }

    @Test
    void sharedDamageSumCorpusKeepsJavaAndNodeSpellingsThresholdEquivalent() throws Exception {
        JsonObject corpus = StrictJsonParser.parse(Files.readString(Path.of(
                "compatibility/ability-results-v1-corpus.json"))).getAsJsonObject();
        for (var item : corpus.getAsJsonArray("damageSumCases")) {
            JsonObject sample = item.getAsJsonObject();
            String name = sample.get("name").getAsString();
            JsonObject healthForms = sample.getAsJsonObject("health");
            JsonObject absorptionForms = sample.getAsJsonObject("absorption");
            boolean resultAccepted = sample.get("resultAccepted").getAsBoolean();
            boolean sumAtMostLimit = sample.get("sumAtMostLimit").getAsBoolean();
            boolean sumIsZero = sample.get("sumIsZero").getAsBoolean();
            double comparisonLimit = Double.parseDouble(sample.get("comparisonLimit").getAsString());
            Set<String> decisions = new HashSet<>();

            for (String healthForm : Set.of("java", "node")) {
                for (String absorptionForm : Set.of("java", "node")) {
                    String healthToken = healthForms.get(healthForm).getAsString();
                    String absorptionToken = absorptionForms.get(absorptionForm).getAsString();
                    Float health = AbilityResultValidator.normalizeFloat32NumberToken(healthToken);
                    Float absorption = AbilityResultValidator.normalizeFloat32NumberToken(absorptionToken);
                    assertNotNull(health, name + " canonical health " + healthForm);
                    assertNotNull(absorption, name + " canonical absorption " + absorptionForm);

                    AbilityResult result = AbilityResultValidator.validateAbilityResultBytes(lightningDamageResult(
                            healthToken, absorptionToken, sample.get("disposition").getAsString()));
                    assertEquals(resultAccepted, result != null, name + " wire validation " + healthForm + "/" + absorptionForm);
                    assertEquals(sumAtMostLimit, ExactFloat32Sum.isAtMost(health, absorption, comparisonLimit),
                            name + " exact configured limit " + healthForm + "/" + absorptionForm);
                    assertEquals(sumIsZero, ExactFloat32Sum.isZero(health, absorption), name + " exact zero");
                    decisions.add((result != null) + "/" + ExactFloat32Sum.isAtMost(health, absorption, comparisonLimit) +
                            "/" + ExactFloat32Sum.isZero(health, absorption));
                }
            }
            assertEquals(Set.of(resultAccepted + "/" + sumAtMostLimit + "/" + sumIsZero), decisions, name);
        }
    }

    @Test
    void exactFloat32DamageSumUsesBoundedBitBasedArithmetic() {
        float minimumSubnormal = Float.intBitsToFloat(1);
        float largestFloatBelowFive = Float.intBitsToFloat(0x409fffff);
        assertFalse(ExactFloat32Sum.isAtMost(5.0f, minimumSubnormal, 5.0));
        assertTrue(ExactFloat32Sum.isAtMost(largestFloatBelowFive, minimumSubnormal, 5.0));
        assertFalse(ExactFloat32Sum.isAtMost(3.0f, minimumSubnormal, 3.0),
                "the exact sum must also exceed a smaller attempted-damage limit");
        assertTrue(ExactFloat32Sum.isAtMost(3.0f, 0.0f, 3.0));
        assertTrue(ExactFloat32Sum.isZero(-0.0f, 0.0f));
        assertFalse(ExactFloat32Sum.isPositive(-0.0f, 0.0f));
        assertTrue(ExactFloat32Sum.isPositive(-0.0f, minimumSubnormal));
        assertFalse(ExactFloat32Sum.isAtMost(Float.NaN, 0.0f, 5.0));
        assertFalse(ExactFloat32Sum.isAtMost(0.0f, 0.0f, Double.NaN));
        assertFalse(ExactFloat32Sum.isAtMost(0.0f, 0.0f, 5.000000000000001));
        assertFalse(ExactFloat32Sum.isAtMost(Float.MAX_VALUE, 0.0f, 5.0),
                "even the largest finite float32 uses fixed-width arithmetic");
    }

    @Test
    void packedBlockPositionBoundsStaySeparateFromTrustedBuildBorderAndNineByNineQualification() {
        assertNotNull(AbilityResultValidator.validateAbilityResultBytes(lightningResult("-33554432", "-2048", "33554431", "executed", "none")));
        assertNotNull(AbilityResultValidator.validateAbilityResultBytes(lightningResult("33554431", "2047", "-33554432", "executed", "none")));
        assertNull(AbilityResultValidator.validateAbilityResultBytes(lightningResult("-33554433", "0", "0", "executed", "none")));
        assertNull(AbilityResultValidator.validateAbilityResultBytes(lightningResult("0", "2048", "0", "executed", "none")));
        assertNull(AbilityResultValidator.validateAbilityResultBytes(lightningResult("1.5", "64", "0", "executed", "none")));
        assertNull(AbilityResultValidator.validateAbilityResultBytes(lightningResult("9007199254740992", "64", "0", "executed", "none")));

        byte[] knownPlacement = lightningResult("0", "64", "0", "executed", "none");
        assertNotNull(AbilityResultValidator.validateAbilityResultBytes(knownPlacement),
                "static result validation only checks packed representation");
        assertNull(AbilityResultValidator.validateAbilityResultForAuthoritativePublication(knownPlacement, null, null, null),
                "unknown context fails closed for non-null coordinates");

        AtomicBoolean current = new AtomicBoolean(true);
        AbilityValidation.AbilityTrustedContextHandle context = context(current);
        AbilityRequestAuthority.ExpectedContext expected = AbilityAuthorityFixture.expected(context);
        ExecutionWorldBoundsSnapshot inside = new ExecutionWorldBoundsSnapshot("minecraft:overworld", 0, 256,
                -5.0, 5.0, -5.0, 5.0);
        AbilityExecutionWorldBoundsHandle handle = AbilityResultValidator.captureAbilityExecutionWorldBounds(context, expected, inside);
        assertNotNull(handle);
        assertNotNull(AbilityResultValidator.validateAbilityResultForAuthoritativePublication(knownPlacement, context, expected, handle));
        assertNull(AbilityResultValidator.validateAbilityResultForAuthoritativePublication(knownPlacement, context, expected, null));

        AbilityValidation.AbilityTrustedContextHandle otherContext = context(new AtomicBoolean(true));
        assertNull(AbilityResultValidator.validateAbilityResultForAuthoritativePublication(knownPlacement, otherContext,
                AbilityAuthorityFixture.expected(otherContext), handle),
                "bounds evidence is bound to the exact request context");
        AbilityExecutionWorldBoundsHandle tooSmallBorder = AbilityResultValidator.captureAbilityExecutionWorldBounds(context, expected,
                new ExecutionWorldBoundsSnapshot("minecraft:overworld", 0, 256, -4.0, 4.0, -5.0, 5.0));
        assertNull(AbilityResultValidator.validateAbilityResultForAuthoritativePublication(knownPlacement, context, expected, tooSmallBorder),
                "whole 9x9 footprint, not only center, must fit the border");
        AbilityExecutionWorldBoundsHandle wrongBuild = AbilityResultValidator.captureAbilityExecutionWorldBounds(context, expected,
                new ExecutionWorldBoundsSnapshot("minecraft:overworld", 65, 256, -5.0, 5.0, -5.0, 5.0));
        assertNull(AbilityResultValidator.validateAbilityResultForAuthoritativePublication(knownPlacement, context, expected, wrongBuild));
        AbilityExecutionWorldBoundsHandle wrongDimension = AbilityResultValidator.captureAbilityExecutionWorldBounds(context, expected,
                new ExecutionWorldBoundsSnapshot("minecraft:the_nether", 0, 256, -5.0, 5.0, -5.0, 5.0));
        assertNull(AbilityResultValidator.validateAbilityResultForAuthoritativePublication(knownPlacement, context, expected, wrongDimension));
        current.set(false);
        assertNull(AbilityResultValidator.validateAbilityResultForAuthoritativePublication(knownPlacement, context, expected, handle),
                "retired contexts cannot qualify publication");
        assertNull(AbilityResultValidator.captureAbilityExecutionWorldBounds(context, expected, inside));

        byte[] unknownPlacement = lightningResultWithUnknownCoordinates();
        assertNotNull(AbilityResultValidator.validateAbilityResultForAuthoritativePublication(unknownPlacement, null, null, null),
                "unknown coordinates stay null instead of being clamped or fabricated");
    }

    @Test
    void frameValidationRejectsMalformedUtf8DuplicateKeysDepthAndOversize() throws Exception {
        JsonObject corpus = StrictJsonParser.parse(Files.readString(Path.of(
                "compatibility/ability-results-v1-corpus.json"))).getAsJsonObject();
        String prefix = corpus.get("prefix").getAsString();
        String duplicate = prefix + corpus.getAsJsonArray("negativeSuffixes").get(4).getAsJsonObject().get("tail").getAsString();
        assertNull(AbilityResultValidator.validateAbilityResultBytes(duplicate.getBytes(StandardCharsets.UTF_8)));
        assertNull(AbilityResultValidator.validateAbilityResultBytes(new byte[]{(byte) 0xc3, 0x28}));
        assertNull(AbilityResultValidator.validateAbilityResultBytes(new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'}));
        assertNull(AbilityResultValidator.validateAbilityResultBytes("{\"x\":}".getBytes(StandardCharsets.UTF_8)));
        assertNull(AbilityResultValidator.validateAbilityResultBytes(("[".repeat(9) + "0" + "]".repeat(9)).getBytes(StandardCharsets.UTF_8)));
        assertNull(AbilityResultValidator.validateAbilityResultBytes((" ".repeat(AbilityCatalogue.MAX_RESULT_BYTES + 1)).getBytes(StandardCharsets.UTF_8)));
        String normalFrame = observeResultJson("20.0", "20.0", "100", "100");
        byte[] normal = normalFrame.getBytes(StandardCharsets.UTF_8);
        String exactFrame = normalFrame + " ".repeat(AbilityCatalogue.MAX_RESULT_BYTES - normal.length);
        assertEquals(AbilityCatalogue.MAX_RESULT_BYTES, exactFrame.getBytes(StandardCharsets.UTF_8).length);
        assertNotNull(AbilityResultValidator.validateAbilityResultBytes(exactFrame.getBytes(StandardCharsets.UTF_8)),
                "the complete 4-KiB result frame is accepted without truncation");
        assertNull(AbilityResultValidator.validateAbilityResultBytes((exactFrame + " ").getBytes(StandardCharsets.UTF_8)));
        assertNull(AbilityResultValidator.validateAbilityResultBytes(normalFrame
                .replace("minecraft:air", "\\uD800").getBytes(StandardCharsets.UTF_8)),
                "escaped unpaired surrogates cannot pass identifier validation");

        String nestedPrefix = normalFrame.substring(0, normalFrame.length() - 1) + ",\"ignored\":";
        String eightContainerFrame = nestedPrefix + "[".repeat(7) + "0" + "]".repeat(7) + "}";
        String nineContainerFrame = nestedPrefix + "[".repeat(8) + "0" + "]".repeat(8) + "}";
        assertDoesNotThrow(() -> StrictJsonParser.parse(eightContainerFrame, AbilityCatalogue.MAX_JSON_DEPTH));
        assertThrows(IllegalArgumentException.class,
                () -> StrictJsonParser.parse(nineContainerFrame, AbilityCatalogue.MAX_JSON_DEPTH));

        String withExtra = new String(normal, StandardCharsets.UTF_8).replace("\"queryId\":", "\"unused\":\"" + "x".repeat(4100) + "\",\"queryId\":");
        assertNull(AbilityResultValidator.validateAbilityResultBytes(withExtra.getBytes(StandardCharsets.UTF_8)));
        String withMultibytePadding = new String(normal, StandardCharsets.UTF_8)
                .replace("\"queryId\":", "\"unused\":\"" + "é".repeat(2000) + "\",\"queryId\":");
        byte[] multibyteBytes = withMultibytePadding.getBytes(StandardCharsets.UTF_8);
        assertTrue(withMultibytePadding.length() < AbilityCatalogue.MAX_RESULT_BYTES);
        assertTrue(multibyteBytes.length > AbilityCatalogue.MAX_RESULT_BYTES);
        assertNull(AbilityResultValidator.validateAbilityResultBytes(multibyteBytes),
                "4 KiB is measured in UTF-8 bytes, not UTF-16 characters");
    }

    @Test
    void validatedResultValuesAndNestedCollectionsAreImmutableAndNotPubliclyForgeable() {
        AbilityResult result = AbilityResultValidator.validateAbilityResultBytes(lightningResult("0", "64", "0", "executed", "none"));
        assertNotNull(result);
        assertTrue(result instanceof AbilityResultValidator.StrikeLightningResult);
        AbilityResultValidator.StrikeLightningResult strike = (AbilityResultValidator.StrikeLightningResult) result;
        assertThrows(UnsupportedOperationException.class, () -> strike.facts().steps().clear());
        assertEquals(0, AbilityResultValidator.StrikeLightningResult.class.getConstructors().length);
        assertEquals(0, AbilityResultValidator.ObservePlayerResult.class.getConstructors().length);
    }

    private static String expand(String source, JsonObject facts) {
        String result = source;
        for (var entry : facts.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue().getAsString());
        }
        return result;
    }

    private static AbilityResultValidator.ObservePlayerResult observeResult(
            String health, String maxHealth, String observedAt, String factObservedAt) {
        AbilityResult result = AbilityResultValidator.validateAbilityResultBytes(
                observeResultJson(health, maxHealth, observedAt, factObservedAt).getBytes(StandardCharsets.UTF_8));
        return result instanceof AbilityResultValidator.ObservePlayerResult observe ? observe : null;
    }

    private static String observeResultJson(String health, String maxHealth, String observedAt, String factObservedAt) {
        return "{\"serverId\":\"00000000-0000-4000-8000-000000000101\",\"worldId\":\"00000000-0000-4000-8000-000000000102\"," +
                "\"characterId\":\"00000000-0000-4000-8000-000000000103\",\"requestId\":\"00000000-0000-4000-8000-000000000104\"," +
                "\"invocationId\":\"00000000-0000-4000-8000-000000000105\",\"abilityId\":\"worldspeaker.core.observe_player\"," +
                "\"contractVersion\":1,\"policyRevision\":7,\"serverEpochId\":\"" + EPOCH + "\",\"state\":\"executed\"," +
                "\"reasonCode\":\"none\",\"facts\":{\"targetPlayerId\":\"" + TARGET + "\",\"observedAt\":" + factObservedAt +
                ",\"health\":" + health + ",\"maxHealth\":" + maxHealth + ",\"dimension\":\"minecraft:overworld\"," +
                "\"biome\":\"minecraft:plains\",\"holdingItem\":\"minecraft:air\"},\"observedAt\":" + observedAt +
                ",\"executedAt\":null,\"retryAfterMs\":null,\"queryId\":\"00000000-0000-4000-8000-000000000108\"}";
    }

    private static byte[] lightningResult(String x, String y, String z, String state, String reason) {
        String raw = "{\"serverId\":\"00000000-0000-4000-8000-000000000101\",\"worldId\":\"00000000-0000-4000-8000-000000000102\"," +
                "\"characterId\":\"00000000-0000-4000-8000-000000000103\",\"requestId\":\"00000000-0000-4000-8000-000000000104\"," +
                "\"invocationId\":\"00000000-0000-4000-8000-000000000105\",\"abilityId\":\"worldspeaker.core.strike_lightning\"," +
                "\"contractVersion\":1,\"policyRevision\":7,\"serverEpochId\":\"" + EPOCH + "\",\"state\":\"" + state + "\"," +
                "\"reasonCode\":\"" + reason + "\",\"facts\":{\"actionId\":\"" + ACTION + "\",\"targetPlayerId\":\"" + TARGET + "\"," +
                "\"dimension\":\"minecraft:overworld\",\"executedAt\":200,\"coordinates\":[" + x + "," + y + "," + z + "]," +
                "\"visualOnly\":true,\"steps\":[{\"kind\":\"presentation\",\"state\":\"confirmed\"}]}," +
                "\"observedAt\":null,\"executedAt\":200,\"retryAfterMs\":null,\"actionId\":\"" + ACTION + "\"}";
        return raw.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] lightningResultWithUnknownCoordinates() {
        String raw = "{\"serverId\":\"00000000-0000-4000-8000-000000000101\",\"worldId\":\"00000000-0000-4000-8000-000000000102\"," +
                "\"characterId\":\"00000000-0000-4000-8000-000000000103\",\"requestId\":\"00000000-0000-4000-8000-000000000104\"," +
                "\"invocationId\":\"00000000-0000-4000-8000-000000000105\",\"abilityId\":\"worldspeaker.core.strike_lightning\"," +
                "\"contractVersion\":1,\"policyRevision\":7,\"serverEpochId\":\"" + EPOCH + "\",\"state\":\"unknown\"," +
                "\"reasonCode\":\"outcome_unknown\",\"facts\":{\"actionId\":\"" + ACTION + "\",\"targetPlayerId\":\"" + TARGET + "\"," +
                "\"dimension\":\"minecraft:overworld\",\"executedAt\":null,\"coordinates\":null,\"visualOnly\":true," +
                "\"steps\":[{\"kind\":\"presentation\",\"state\":\"unknown\"}]},\"observedAt\":null," +
                "\"executedAt\":null,\"retryAfterMs\":null,\"actionId\":\"" + ACTION + "\"}";
        return raw.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] lightningDamageResult(String healthRemoved, String absorptionRemoved) {
        return lightningDamageResult(healthRemoved, absorptionRemoved, "applied");
    }

    private static byte[] lightningDamageResult(String healthRemoved, String absorptionRemoved, String disposition) {
        String raw = "{\"serverId\":\"00000000-0000-4000-8000-000000000101\",\"worldId\":\"00000000-0000-4000-8000-000000000102\"," +
                "\"characterId\":\"00000000-0000-4000-8000-000000000103\",\"requestId\":\"00000000-0000-4000-8000-000000000104\"," +
                "\"invocationId\":\"00000000-0000-4000-8000-000000000105\",\"abilityId\":\"worldspeaker.core.strike_lightning\"," +
                "\"contractVersion\":1,\"policyRevision\":7,\"serverEpochId\":\"" + EPOCH + "\",\"state\":\"executed\"," +
                "\"reasonCode\":\"none\",\"facts\":{\"actionId\":\"" + ACTION + "\",\"targetPlayerId\":\"" + TARGET + "\"," +
                "\"dimension\":\"minecraft:overworld\",\"executedAt\":200,\"coordinates\":[0,64,0],\"visualOnly\":false," +
                "\"steps\":[{\"kind\":\"presentation\",\"state\":\"confirmed\"},{\"kind\":\"damage\",\"state\":\"confirmed\"," +
                "\"healthRemoved\":" + healthRemoved + ",\"absorptionRemoved\":" + absorptionRemoved +
                ",\"damageDisposition\":\"" + disposition + "\"}]},\"observedAt\":null,\"executedAt\":200,\"retryAfterMs\":null," +
                "\"actionId\":\"" + ACTION + "\"}";
        return raw.getBytes(StandardCharsets.UTF_8);
    }

    private static AbilityValidation.AbilityTrustedContextHandle context(AtomicBoolean current) {
        AbilityPolicy policy = AbilityPolicy.denyAll();
        return AbilityAuthorityFixture.issue(
                new AbilityValidation.Binding("test-server", "test-world", "test-character", "test-agent"),
                "00000000-0000-4000-8000-000000000104", "00000000-0000-4000-8000-000000000105",
                "00000000-0000-4000-8000-000000000107", EPOCH, policy, current::get);
    }
}
