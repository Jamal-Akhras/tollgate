package io.github.jamalakhras.tollgate.core.policy;

import java.util.List;

public record ToolNamesGuard(List<String> allow, List<String> refuse) implements Guard {

    public static final String TYPE = "tool-names";

    public ToolNamesGuard {
        allow = List.copyOf(allow);
        refuse = List.copyOf(refuse);
        if (allow.isEmpty() && refuse.isEmpty()) {
            throw new IllegalArgumentException("a tool-names guard needs allow, refuse or both");
        }
        if (allow.stream().anyMatch(String::isBlank) || refuse.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("allow and refuse must not hold an empty name");
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Decision check(GuardRequest request) {
        for (String name : request.features().tools()) {
            boolean missing = !allow.isEmpty() && allow.stream().noneMatch(pattern -> matches(pattern, name));
            if (missing || refuse.stream().anyMatch(pattern -> matches(pattern, name))) {
                return Decision.deny(Refusal.POLICY_DENIED, "The tool '" + name + "' is not allowed by this policy");
            }
        }
        return Decision.ALLOW;
    }

    private static boolean matches(String pattern, String name) {
        int p = 0;
        int n = 0;
        int star = -1;
        int resume = 0;
        while (n < name.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                resume = n;
            } else if (p < pattern.length() && pattern.charAt(p) == name.charAt(n)) {
                p++;
                n++;
            } else if (star >= 0) {
                p = star + 1;
                n = ++resume;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}
