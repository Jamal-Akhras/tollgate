package io.github.jamalakhras.tollgate.core.config;

public record OutputTokens(long defaultTokens, long cap, long floor) {

    public static final long DEFAULT_FLOOR = 256;

    public OutputTokens {
        if (defaultTokens < 1 || cap < 1 || floor < 1) {
            throw new IllegalArgumentException("default, cap and floor must be positive");
        }
        if (defaultTokens > cap) {
            throw new IllegalArgumentException("default must not exceed cap");
        }
        if (floor > cap) {
            throw new IllegalArgumentException("floor must not exceed cap");
        }
    }

    public OutputTokens(long defaultTokens, long cap) {
        this(defaultTokens, cap, Math.min(DEFAULT_FLOOR, cap));
    }

    public long limit(long requested) {
        return Math.min(requested, cap);
    }

    public OutputTokens loweredTo(long lowered) {
        return new OutputTokens(Math.min(defaultTokens, lowered), lowered, Math.min(floor, lowered));
    }
}
