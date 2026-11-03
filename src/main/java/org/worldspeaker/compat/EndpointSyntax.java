package org.worldspeaker.compat;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Strict, DNS-free syntax shared by startup loading and every direct config constructor. */
final class EndpointSyntax {
    private static final int MAX_LENGTH = 2_048;

    private EndpointSyntax() {}

    static URI parse(String value) throws IOException {
        if (!isValid(value)) throw invalid();
        try {
            return URI.create(value);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
    }

    static void validate(URI value) throws IOException {
        if (value == null || !isValid(value.toString())) throw invalid();
    }

    private static boolean isValid(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e || character == '\\' || character == '?' || character == '#') {
                return false;
            }
        }

        String scheme;
        int authorityStart;
        if (value.startsWith("ws://")) {
            scheme = "ws";
            authorityStart = 5;
        } else if (value.startsWith("wss://")) {
            scheme = "wss";
            authorityStart = 6;
        } else {
            return false;
        }

        int pathStart = value.indexOf('/', authorityStart);
        String authority = pathStart < 0 ? value.substring(authorityStart) : value.substring(authorityStart, pathStart);
        String path = pathStart < 0 ? null : value.substring(pathStart);
        if (authority.isEmpty() || authority.indexOf('@') >= 0 || !validPath(path)) return false;

        String host;
        String port = null;
        if (authority.charAt(0) == '[') {
            int closingBracket = authority.indexOf(']');
            if (closingBracket <= 1 || !validIpv6(authority.substring(1, closingBracket))) return false;
            host = authority.substring(0, closingBracket + 1);
            String remainder = authority.substring(closingBracket + 1);
            if (!remainder.isEmpty()) {
                if (remainder.charAt(0) != ':' || remainder.length() == 1) return false;
                port = remainder.substring(1);
            }
        } else {
            if (authority.indexOf('[') >= 0 || authority.indexOf(']') >= 0) return false;
            int colon = authority.indexOf(':');
            if (colon >= 0) {
                if (colon == 0 || colon != authority.lastIndexOf(':') || colon == authority.length() - 1) return false;
                host = authority.substring(0, colon);
                port = authority.substring(colon + 1);
            } else {
                host = authority;
            }
            if (!validHost(host)) return false;
        }

        if (port != null && !validPort(port)) return false;
        if (scheme.equals("ws") && !host.equals("127.0.0.1") && !host.equals("[::1]")) return false;
        return true;
    }

    private static boolean validPath(String path) {
        if (path == null || path.equals("/")) return true;
        if (!path.startsWith("/") || path.contains("//")) return false;
        String[] segments = path.substring(1).split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty()) {
                if (index == segments.length - 1) continue;
                return false;
            }
            if (segment.equals(".") || segment.equals("..")) return false;
            for (int characterIndex = 0; characterIndex < segment.length(); characterIndex++) {
                char character = segment.charAt(characterIndex);
                if (!asciiPathAlphaNumeric(character) && character != '.' && character != '_' && character != '~' && character != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean validPort(String port) {
        if (port.isEmpty() || port.length() > 5 || port.charAt(0) == '0') return false;
        int number = 0;
        for (int index = 0; index < port.length(); index++) {
            char digit = port.charAt(index);
            if (digit < '0' || digit > '9') return false;
            number = number * 10 + digit - '0';
        }
        return number >= 1 && number <= 65_535;
    }

    private static boolean validHost(String host) {
        if (host.isEmpty()) return false;
        boolean numericAndDots = true;
        for (int index = 0; index < host.length(); index++) {
            char character = host.charAt(index);
            if ((character < '0' || character > '9') && character != '.') numericAndDots = false;
        }
        if (numericAndDots) return validIpv4(host);
        if (host.length() > 253) return false;

        String[] labels = host.split("\\.", -1);
        boolean containsLetter = false;
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63 || !asciiAlphaNumeric(label.charAt(0)) ||
                    !asciiAlphaNumeric(label.charAt(label.length() - 1))) return false;
            for (int index = 0; index < label.length(); index++) {
                char character = label.charAt(index);
                if (!asciiAlphaNumeric(character) && character != '-') return false;
                if (character >= 'a' && character <= 'z') containsLetter = true;
            }
        }
        String finalLabel = labels[labels.length - 1];
        return containsLetter && !isWhatwgIpv4Number(finalLabel);
    }

    private static boolean validIpv4(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || octet.length() > 1 && octet.charAt(0) == '0') return false;
            int number = 0;
            for (int index = 0; index < octet.length(); index++) {
                char digit = octet.charAt(index);
                if (digit < '0' || digit > '9') return false;
                number = number * 10 + digit - '0';
            }
            if (number > 255) return false;
        }
        return true;
    }

    private static boolean isWhatwgIpv4Number(String label) {
        if (allDigits(label)) return true;
        if (!label.startsWith("0x")) return false;
        if (label.length() == 2) return true;
        for (int index = 2; index < label.length(); index++) {
            char character = label.charAt(index);
            if (!(character >= '0' && character <= '9') && !(character >= 'a' && character <= 'f')) return false;
        }
        return true;
    }

    private static boolean allDigits(String value) {
        if (value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') return false;
        }
        return true;
    }

    private static boolean validIpv6(String value) {
        if (value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9') && !(character >= 'a' && character <= 'f') && character != ':') return false;
        }
        int compression = value.indexOf("::");
        if (compression >= 0 && compression != value.lastIndexOf("::")) return false;

        List<Integer> groups = new ArrayList<>(8);
        if (compression < 0) {
            List<Integer> parsed = parseIpv6Side(value);
            if (parsed == null || parsed.size() != 8) return false;
            groups.addAll(parsed);
        } else {
            List<Integer> left = parseIpv6Side(value.substring(0, compression));
            List<Integer> right = parseIpv6Side(value.substring(compression + 2));
            if (left == null || right == null || left.size() + right.size() >= 8) return false;
            groups.addAll(left);
            for (int index = left.size() + right.size(); index < 8; index++) groups.add(0);
            groups.addAll(right);
        }
        return value.equals(canonicalIpv6(groups));
    }

    private static List<Integer> parseIpv6Side(String side) {
        List<Integer> groups = new ArrayList<>();
        if (side.isEmpty()) return groups;
        for (String group : side.split(":", -1)) {
            if (group.isEmpty() || group.length() > 4) return null;
            int number = 0;
            for (int index = 0; index < group.length(); index++) {
                char digit = group.charAt(index);
                int value = digit >= '0' && digit <= '9' ? digit - '0' : digit - 'a' + 10;
                if (value < 0 || value > 15) return null;
                number = number * 16 + value;
            }
            groups.add(number);
        }
        return groups;
    }

    private static String canonicalIpv6(List<Integer> groups) {
        int bestStart = -1;
        int bestLength = 1;
        for (int index = 0; index < groups.size();) {
            if (groups.get(index) != 0) {
                index++;
                continue;
            }
            int end = index;
            while (end < groups.size() && groups.get(end) == 0) end++;
            if (end - index > bestLength) {
                bestStart = index;
                bestLength = end - index;
            }
            index = end;
        }
        if (bestStart < 0) return joinIpv6Groups(groups, 0, groups.size());
        return joinIpv6Groups(groups, 0, bestStart) + "::" +
                joinIpv6Groups(groups, bestStart + bestLength, groups.size());
    }

    private static String joinIpv6Groups(List<Integer> groups, int start, int end) {
        List<String> values = new ArrayList<>(end - start);
        for (int index = start; index < end; index++) values.add(Integer.toHexString(groups.get(index)));
        return String.join(":", values);
    }

    private static boolean asciiAlphaNumeric(char character) {
        return character >= 'a' && character <= 'z' || character >= '0' && character <= '9';
    }

    private static boolean asciiPathAlphaNumeric(char character) {
        return asciiAlphaNumeric(character) || character >= 'A' && character <= 'Z';
    }

    private static IOException invalid() {
        return new IOException("server endpoint does not match the strict endpoint syntax");
    }
}
