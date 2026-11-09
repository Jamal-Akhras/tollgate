package io.github.jamalakhras.tollgate.core.limit;

import org.jspecify.annotations.Nullable;

public record Limits(
        @Nullable Long requestsPerMinute,
        @Nullable Long tokensPerMinute,
        @Nullable Long concurrent) {

    public static final Limits NONE = new Limits(null, null, null);

    public Limits {
        requirePositive("requestsPerMinute", requestsPerMinute);
        requirePositive("tokensPerMinute", tokensPerMinute);
        requirePositive("concurrent", concurrent);
    }

    private static void requirePositive(String name, @Nullable Long value) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }
}
