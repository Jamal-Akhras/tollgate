package io.github.jamalakhras.tollgate.core.money;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public record Money(long nanos, Currency currency) implements Comparable<Money> {

    private static final int NANO_DECIMAL_PLACES = 9;

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(BigDecimal amount, Currency currency) {
        long nanos = amount.movePointRight(NANO_DECIMAL_PLACES).longValueExact();
        return new Money(nanos, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(nanos, other.nanos), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(nanos, other.nanos), currency);
    }

    public boolean isNegative() {
        return nanos < 0;
    }

    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(nanos, NANO_DECIMAL_PLACES);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(nanos, other.nanos);
    }

    @Override
    public String toString() {
        return toBigDecimal().stripTrailingZeros().toPlainString() + " " + currency.getCurrencyCode();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency.getCurrencyCode() + " and " + other.currency.getCurrencyCode());
        }
    }
}
