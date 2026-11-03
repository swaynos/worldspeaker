package org.worldspeaker.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityDiagnosticTest {
    @Test
    void diagnosticsBoundAndStripControlCharacters() {
        CompatibilityDiagnostic diagnostic = new CompatibilityDiagnostic(
                "WS-COMPAT-OPENCLAW\nsecret",
                "host\u0000value",
                "x".repeat(200),
                "2026.10.8",
                "Install the recorded host/plugin pair\nthen restart");
        String line = diagnostic.privateLogLine();

        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\u0000"));
        assertTrue(line.contains("WS-COMPAT-OPENCLAWsecret"));
        assertTrue(line.contains("expected=2026.10.8"));
        assertTrue(line.length() < 512);
    }
}
