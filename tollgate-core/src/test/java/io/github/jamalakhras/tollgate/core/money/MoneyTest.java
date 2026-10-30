package io.github.jamalakhras.tollgate.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency GBP = Currency.getInstance("GBP");

    @Test
    void convertsExactDecimalsToNanos() {
        assertThat(Money.of(new BigDecimal("0.15"), USD).nanos()).isEqualTo(150_000_000L);
        assertThat(Money.of(new BigDecimal("0.000000001"), USD).nanos()).isEqualTo(1L);
        assertThat(Money.of(new BigDecimal("-2"), USD).nanos()).isEqualTo(-2_000_000_000L);
    }

    @Test
    void refusesToRoundSilently() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("0.0000000015"), USD)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void rejectsAmountsOutOfRange() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("10000000000"), USD)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void equalityIgnoresDecimalScale() {
        assertThat(Money.of(new BigDecimal("1.0"), USD)).isEqualTo(Money.of(new BigDecimal("1.000"), USD));
        assertThat(Money.of(BigDecimal.ONE, USD)).isNotEqualTo(Money.of(BigDecimal.ONE, GBP));
    }

    @Test
    void addsAndSubtractsExactly() {
        Money seven = new Money(7, USD);
        Money five = new Money(5, USD);
        assertThat(seven.plus(five)).isEqualTo(new Money(12, USD));
        assertThat(seven.minus(five)).isEqualTo(new Money(2, USD));
        assertThat(five.minus(seven).isNegative()).isTrue();
        assertThat(seven.minus(seven).isNegative()).isFalse();
    }

    @Test
    void overflowThrowsInsteadOfWrapping() {
        Money one = new Money(1, USD);
        assertThatThrownBy(() -> new Money(Long.MAX_VALUE, USD).plus(one)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new Money(Long.MIN_VALUE, USD).minus(one)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void refusesToMixCurrencies() {
        Money dollar = new Money(1, USD);
        Money pound = new Money(1, GBP);
        assertThatThrownBy(() -> dollar.plus(pound))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Currency mismatch: USD and GBP");
        assertThatThrownBy(() -> dollar.minus(pound)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dollar.compareTo(pound)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparesWithinACurrency() {
        assertThat(new Money(1, USD)).isLessThan(new Money(2, USD));
        assertThat(new Money(2, USD)).isGreaterThan(new Money(1, USD));
        assertThat(new Money(2, USD)).isEqualByComparingTo(new Money(2, USD));
    }

    @Test
    void printsWithoutTrailingZeros() {
        assertThat(new Money(1_050, USD)).hasToString("0.00000105 USD");
        assertThat(new Money(2_000_000_000L, GBP)).hasToString("2 GBP");
        assertThat(new Money(0, USD)).hasToString("0 USD");
        assertThat(new Money(1_050, USD).toBigDecimal()).isEqualByComparingTo("0.00000105");
    }
}
