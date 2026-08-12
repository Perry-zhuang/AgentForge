package io.agentforge.policy;

import java.util.Collection;

public final class PolicyPathMatcher {
    private PolicyPathMatcher() {}

    public static boolean matchesAny(Collection<String> patterns, String path) {
        return patterns.stream().anyMatch(pattern -> PathPolicyMatcher.matches(pattern, path));
    }
}
