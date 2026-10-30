package io.github.jamalakhras.tollgate.core.money;

import java.util.Objects;

public record TokenPrice(Money perMillionTokens) {

    private static final long MILLION = 1_000_000;

    public TokenPrice {
        Objects.requireNonNull(perMillionTokens, "perMillionTokens");
        if (perMillionTokens.isNegative()) {
            throw new IllegalArgumentException("Price must not be negative: " + perMillionTokens);
        }
    }

    public Money costOf(long tokens) {
        if (tokens < 0) {
            throw new IllegalArgumentException("Token count must not be negative: " + tokens);
        }
        long nanos = Math.ceilDiv(Math.multiplyExact(tokens, perMillionTokens.nanos()), MILLION);
        return new Money(nanos, perMillionTokens.currency());
    }
}
