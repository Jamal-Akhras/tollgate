package io.github.jamalakhras.tollgate.core.money;

import java.util.Objects;

public record SearchPrice(Money perThousandSearches) {

    private static final long THOUSAND = 1_000;

    public SearchPrice {
        Objects.requireNonNull(perThousandSearches, "perThousandSearches");
        if (perThousandSearches.isNegative()) {
            throw new IllegalArgumentException("Price must not be negative: " + perThousandSearches);
        }
    }

    public Money costOf(long searches) {
        if (searches < 0) {
            throw new IllegalArgumentException("Search count must not be negative: " + searches);
        }
        long nanos = Math.ceilDiv(Math.multiplyExact(searches, perThousandSearches.nanos()), THOUSAND);
        return new Money(nanos, perThousandSearches.currency());
    }
}
