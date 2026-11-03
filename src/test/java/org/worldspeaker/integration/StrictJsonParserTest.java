package org.worldspeaker.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrictJsonParserTest {
    @Test
    void rejectsDuplicateAndEscapedEquivalentObjectNames() {
        assertThrows(IllegalArgumentException.class, () -> StrictJsonParser.parse("{\"text\":\"first\",\"text\":\"last\"}"));
        assertThrows(IllegalArgumentException.class, () -> StrictJsonParser.parse("{\"serverId\":\"attacker\",\"\\u0073erverId\":\"bound\"}"));
    }

    @Test
    void rejectsNestedDuplicatesAndAllowsSeparateObjectScopes() {
        assertThrows(IllegalArgumentException.class, () -> StrictJsonParser.parse("{\"metadata\":{\"mode\":1,\"mode\":2}}"));
        assertEquals(1, StrictJsonParser.parse("{\"left\":{\"mode\":1},\"right\":{\"mode\":2}}")
                .getAsJsonObject().getAsJsonObject("left").get("mode").getAsInt());
    }

    @Test
    void rejectsExcessiveNestingDeterministically() {
        assertThrows(IllegalArgumentException.class,
                () -> StrictJsonParser.parse("[".repeat(129) + "0" + "]".repeat(129)));
    }
}
