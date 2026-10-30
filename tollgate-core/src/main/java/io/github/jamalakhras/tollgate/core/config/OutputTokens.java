package io.github.jamalakhras.tollgate.core.config;

public record OutputTokens(long defaultTokens, long cap) {

    public OutputTokens {
        if (defaultTokens < 1 || cap < 1) {
            throw new IllegalArgumentException("default and cap must be positive");
        }
        if (defaultTokens > cap) {
            throw new IllegalArgumentException("default must not exceed cap");
        }
    }

    public long limit(long requested) {
        return Math.min(requested, cap);
    }
}
