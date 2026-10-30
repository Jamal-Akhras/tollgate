package io.github.jamalakhras.tollgate.core.policy;

import org.jspecify.annotations.Nullable;

public record GuardRequest(
        String tenant,
        String model,
        String provider,
        @Nullable String region,
        @Nullable String dataClass,
        long sizeBytes) {}
