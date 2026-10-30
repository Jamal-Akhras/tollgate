package io.github.jamalakhras.tollgate.core.budget;

import io.github.jamalakhras.tollgate.core.money.Money;
import java.util.Objects;

public record Budget(Money limit, Period period) {

    public Budget {
        Objects.requireNonNull(period, "period");
        if (limit.isNegative()) {
            throw new IllegalArgumentException("limit must not be negative");
        }
    }
}
