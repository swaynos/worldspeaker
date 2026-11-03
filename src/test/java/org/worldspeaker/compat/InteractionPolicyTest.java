package org.worldspeaker.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionPolicyTest {
    @Test
    void missingAndEmptyGrantsDenyAllAndOperatorStatusIsNotAnInput() throws Exception {
        assertTrue(InteractionPolicy.parse(null).conversationAllowedPlayers().isEmpty());
        assertTrue(InteractionPolicy.parse("  ").conversationAllowedPlayers().isEmpty());
        assertFalse(InteractionPolicy.denyAll().allows(UUID.randomUUID(), InteractionPolicy.CONVERSE_PERMISSION));

        UUID granted = UUID.fromString("00000000-0000-4000-8000-000000000001");
        InteractionPolicy policy = InteractionPolicy.parse(granted.toString());
        assertTrue(policy.allows(granted, InteractionPolicy.CONVERSE_PERMISSION));
        assertFalse(policy.allows(granted, "worldspeaker.admin"));
        assertFalse(policy.allows(null, InteractionPolicy.CONVERSE_PERMISSION));
    }

    @Test
    void grantsMustBeUniqueCanonicalUuidsAndNoMoreThanThirtyTwo() throws Exception {
        UUID first = UUID.fromString("abcdefab-cdef-4abc-8abc-abcdefabcdef");
        assertThrows(InteractionPolicy.InvalidInteractionPolicyException.class,
                () -> InteractionPolicy.parse(first + "," + first));
        assertThrows(InteractionPolicy.InvalidInteractionPolicyException.class,
                () -> InteractionPolicy.parse(first.toString().toUpperCase(java.util.Locale.ROOT)));
        assertThrows(InteractionPolicy.InvalidInteractionPolicyException.class,
                () -> InteractionPolicy.parse("*"));
        assertThrows(InteractionPolicy.InvalidInteractionPolicyException.class,
                () -> InteractionPolicy.parse("ordinaryPlayer"));
        assertThrows(InteractionPolicy.InvalidInteractionPolicyException.class,
                () -> InteractionPolicy.parse(first + ","));

        LinkedHashSet<UUID> thirtyTwo = new LinkedHashSet<>();
        for (int index = 1; index <= InteractionPolicy.MAX_ALLOWED_PLAYERS; index++) {
            thirtyTwo.add(UUID.fromString(String.format(java.util.Locale.ROOT,
                    "00000000-0000-4000-8000-%012d", index)));
        }
        assertEquals(32, new InteractionPolicy(thirtyTwo).conversationAllowedPlayers().size());
        LinkedHashSet<UUID> thirtyThree = new LinkedHashSet<>(thirtyTwo);
        thirtyThree.add(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> new InteractionPolicy(thirtyThree));
        assertThrows(InteractionPolicy.InvalidInteractionPolicyException.class,
                () -> InteractionPolicy.parse(String.join(",", thirtyTwo.stream().map(UUID::toString).toList()) +
                ",00000000-0000-4000-8000-000000000033"));
    }

    @Test
    void directUuidSetPolicyUsesTheSameConfigSyntaxWithoutAssertingProfileAuthentication() throws Exception {
        JsonObject corpus = JsonParser.parseString(Files.readString(
                Path.of("compatibility/startup-config-v1-uuid-corpus.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> values = new ArrayList<>();
        for (JsonElement item : corpus.getAsJsonArray("valid")) {
            values.add(item.getAsJsonObject().get("value").getAsString());
        }
        Set<UUID> expected = values.stream().map(UUID::fromString).collect(java.util.stream.Collectors.toSet());
        InteractionPolicy policy = InteractionPolicy.parse(String.join(",", values));
        assertEquals(expected, policy.conversationAllowedPlayers());
        assertEquals(expected, new InteractionPolicy(expected).conversationAllowedPlayers());

        for (JsonElement item : corpus.getAsJsonArray("invalidItems")) {
            JsonElement value = item.getAsJsonObject().get("value");
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                assertThrows(InteractionPolicy.InvalidInteractionPolicyException.class,
                        () -> InteractionPolicy.parse(value.getAsString()), item.getAsJsonObject().get("name").getAsString());
            }
        }
        String duplicate = values.getFirst();
        assertThrows(InteractionPolicy.InvalidInteractionPolicyException.class,
                () -> InteractionPolicy.parse(duplicate + "," + duplicate));
    }
}
