package org.worldspeaker.compat;

public record CompatibilityDiagnostic(String code, String component, String observed, String expected, String repair) {
    public CompatibilityDiagnostic {
        code = safe(code, 64);
        component = safe(component, 64);
        observed = safe(observed, 128);
        expected = safe(expected, 128);
        repair = safe(repair, 128);
    }

    public String privateLogLine() {
        return code + " component=" + component + " observed=" + observed + " expected=" + expected + " repair=" + repair;
    }

    public static CompatibilityDiagnostic fromPrivateConfigurationProblem(PrivateConfigurationProblem problem) {
        return new CompatibilityDiagnostic(problem.code().wireCode(), "local-config",
                "field=" + problem.field().label(), problem.expected().description(), problem.action().instruction());
    }

    public static CompatibilityDiagnostic invalidAcknowledgementTemplate() {
        return new CompatibilityDiagnostic(
                "WS-COMPAT-ACK-TEMPLATE-INVALID", "local-config", "unusable acknowledgement template",
                "bounded visible safe text", "set acknowledgementTemplate to readable text with optional {player} (max 256 units) and restart");
    }

    public static CompatibilityDiagnostic invalidCharacterDisplayName() {
        return new CompatibilityDiagnostic(
                "WS-COMPAT-CHARACTER-NAME-INVALID", "local-config", "unusable character display name",
                "1 to 48 units of visible, naturally addressable single-line text",
                "set characterDisplayName to readable text without hidden controls and restart");
    }

    public static CompatibilityDiagnostic invalidInteractionPolicy() {
        return new CompatibilityDiagnostic(
                "WS-COMPAT-CONVERSATION-POLICY-INVALID", "local-config", "invalid conversation grant list",
                "at most 32 unique canonical player UUIDs", "repair conversationAllowedPlayers and restart");
    }

    public static CompatibilityDiagnostic invalidCharacterAliases() {
        return new CompatibilityDiagnostic(
                "WS-COMPAT-CHARACTER-ALIASES-INVALID", "local-config", "invalid natural-address alias list",
                "at most eight unique visible, naturally addressable aliases, each 1 to 48 units",
                "repair characterAliases without hidden controls and restart");
    }

    public static CompatibilityDiagnostic invalidCharacterChatStyle() {
        return new CompatibilityDiagnostic(
                "WS-COMPAT-CHARACTER-CHAT-STYLE-INVALID", "local-config", "invalid character chat style",
                "plain or bracketed", "set characterChatStyle to plain or bracketed and restart");
    }

    public static CompatibilityDiagnostic invalidFollowupWindow() {
        return new CompatibilityDiagnostic(
                "WS-COMPAT-FOLLOWUP-WINDOW-INVALID", "local-config", "invalid follow-up window value",
                "an unsigned integer from 0 to 30 seconds", "set followupWindowSeconds to 0..30 and restart");
    }

    private static String safe(String value, int maxLength) {
        if (value == null) return "unknown";
        StringBuilder output = new StringBuilder(Math.min(value.length(), maxLength));
        for (int index = 0; index < value.length() && output.length() < maxLength; index++) {
            char current = value.charAt(index);
            if (current >= 0x20 && current != 0x7f) output.append(current);
        }
        return output.toString();
    }
}
