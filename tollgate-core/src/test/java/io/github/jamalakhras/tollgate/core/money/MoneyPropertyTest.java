package io.github.jamalakhras.tollgate.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Currency;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

class MoneyPropertyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final long ONE_HUNDRED_DOLLARS_IN_NANOS = 100_000_000_000L;

    @Property
    void costMatchesBigDecimalRoundedUp(
            @ForAll @LongRange(min = 0, max = 10_000_000) long tokens,
            @ForAll @LongRange(min = 0, max = ONE_HUNDRED_DOLLARS_IN_NANOS) long pricePerMillion) {
        long expected = BigDecimal.valueOf(tokens)
                .multiply(BigDecimal.valueOf(pricePerMillion))
                .divide(BigDecimal.valueOf(1_000_000), 0, RoundingMode.CEILING)
                .longValueExact();

        Money cost = new TokenPrice(new Money(pricePerMillion, USD)).costOf(tokens);

        assertThat(cost.nanos()).isEqualTo(expected);
    }

    @Property
    void bigDecimalRoundTripIsLossless(@ForAll long nanos) {
        Money money = new Money(nanos, USD);
        assertThat(Money.of(money.toBigDecimal(), USD)).isEqualTo(money);
    }

    @Property
    void additionIsExactOrThrows(@ForAll long a, @ForAll long b) {
        Money x = new Money(a, USD);
        Money y = new Money(b, USD);
        BigInteger sum = BigInteger.valueOf(a).add(BigInteger.valueOf(b));
        if (sum.bitLength() > 63) {
            assertThatThrownBy(() -> x.plus(y)).isInstanceOf(ArithmeticException.class);
        } else {
            assertThat(x.plus(y).nanos()).isEqualTo(sum.longValueExact());
        }
    }

    @Property
    void plusThenMinusGivesBackTheOriginal(
            @ForAll @LongRange(min = -1_000_000_000_000_000L, max = 1_000_000_000_000_000L) long a,
            @ForAll @LongRange(min = -1_000_000_000_000_000L, max = 1_000_000_000_000_000L) long b) {
        Money x = new Money(a, USD);
        Money y = new Money(b, USD);
        assertThat(x.plus(y).minus(y)).isEqualTo(x);
    }

    @Property
    void orderingMatchesBigDecimal(@ForAll long a, @ForAll long b) {
        Money x = new Money(a, USD);
        Money y = new Money(b, USD);
        assertThat(Integer.signum(x.compareTo(y)))
                .isEqualTo(Integer.signum(x.toBigDecimal().compareTo(y.toBigDecimal())));
    }
}
