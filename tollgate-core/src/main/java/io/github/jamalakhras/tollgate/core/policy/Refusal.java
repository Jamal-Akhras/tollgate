package io.github.jamalakhras.tollgate.core.policy;

import java.util.Arrays;
import java.util.Locale;

public enum Refusal {
    INVALID_DATA_CLASS,
    POLICY_DENIED,
    TOO_LARGE,
    BUDGET_EXCEEDED;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Refusal fromCode(String code) {
        return Arrays.stream(values())
                .filter(refusal -> refusal.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown refusal: " + code));
    }
}
