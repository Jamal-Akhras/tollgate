package io.github.jamalakhras.tollgate.core.money;

import java.util.Objects;

public record ContextTier(long fromTokens, TokenRates rates) {

    public ContextTier {
        if (fromTokens < 1) {
            throw new IllegalArgumentException("fromTokens must be at least 1, was " + fromTokens);
        }
        Objects.requireNonNull(rates, "rates");
    }
}
