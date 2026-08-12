package io.agentforge.policy;

import java.util.regex.Pattern;

final class PathPolicyMatcher {
    private PathPolicyMatcher() {}

    static boolean matches(String pattern, String path) {
        String normalizedPattern = pattern.replace('\\', '/');
        String normalizedPath = path.replace('\\', '/');
        if (!normalizedPattern.contains("/")) {
            String base = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1);
            return wildcard(normalizedPattern).matcher(base).matches();
        }
        return glob(normalizedPattern).matcher(normalizedPath).matches();
    }

    private static Pattern wildcard(String value) {
        return glob(value);
    }

    private static Pattern glob(String value) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '*') {
                boolean doubleStar = index + 1 < value.length() && value.charAt(index + 1) == '*';
                if (doubleStar) {
                    index++;
                    if (index + 1 < value.length() && value.charAt(index + 1) == '/') {
                        index++;
                        regex.append("(?:.*/)?");
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (current == '?') {
                regex.append("[^/]");
            } else if (".[]{}()+-^$|".indexOf(current) >= 0) {
                regex.append('\\').append(current);
            } else {
                regex.append(current);
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
