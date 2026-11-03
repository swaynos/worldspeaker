package org.worldspeaker.compat;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.worldspeaker.integration.StrictJsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AbilityPolicyLimitsCorpusTest {
    private static final String CHARACTER = "fixture-character";

    @Test
    void productionJavaParserMatchesTheSharedRawLimitsCorpus() throws Exception {
        JsonObject corpus = StrictJsonParser.parse(Files.readString(
                Path.of("compatibility/ability-policy-v1-limits-corpus.json"), StandardCharsets.UTF_8), 8).getAsJsonObject();

        for (var item : corpus.getAsJsonArray("valid")) {
            JsonObject testCase = item.getAsJsonObject();
            String rawPolicy = testCase.get("rawPolicyJson").getAsString();
            AbilityPolicy parsed = assertDoesNotThrow(
                    () -> AbilityPolicy.parse(StrictJsonParser.parse(rawPolicy, 8), CHARACTER),
                    testCase.get("name").getAsString());
            if (testCase.has("expectedLimits")) {
                assertEquals(testCase.getAsJsonObject("expectedLimits"), effectiveLimits(parsed.grants().getFirst()),
                        testCase.get("name").getAsString() + ": effective defaults");
            }
        }

        for (var item : corpus.getAsJsonArray("invalid")) {
            JsonObject testCase = item.getAsJsonObject();
            String rawPolicy = testCase.get("rawPolicyJson").getAsString();
            assertThrows(IllegalArgumentException.class,
                    () -> AbilityPolicy.parse(StrictJsonParser.parse(rawPolicy, 8), CHARACTER),
                    testCase.get("name").getAsString());
        }
    }

    private static JsonObject effectiveLimits(AbilityPolicy.Grant grant) {
        JsonObject limits = new JsonObject();
        if (grant.observeLimits() != null) {
            limits.addProperty("requesterIntervalMs", grant.observeLimits().requesterIntervalMs());
            limits.addProperty("characterIntervalMs", grant.observeLimits().characterIntervalMs());
        } else {
            limits.addProperty("requesterCooldownMs", grant.lightningLimits().requesterCooldownMs());
            limits.addProperty("targetCooldownMs", grant.lightningLimits().targetCooldownMs());
            limits.addProperty("characterIntervalMs", grant.lightningLimits().characterIntervalMs());
        }
        return limits;
    }
}
