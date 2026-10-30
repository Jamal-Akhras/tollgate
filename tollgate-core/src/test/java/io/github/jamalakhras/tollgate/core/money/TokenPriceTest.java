package io.github.jamalakhras.tollgate.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class TokenPriceTest {

    private static final Currency USD = Currency.getInstance("USD");

    private static TokenPrice dollarsPerMillion(String amount) {
        return new TokenPrice(Money.of(new BigDecimal(amount), USD));
    }

    @Test
    void pricesTokensExactly() {
        assertThat(dollarsPerMillion("0.15").costOf(7)).isEqualTo(new Money(1_050, USD));
        assertThat(dollarsPerMillion("3").costOf(1)).isEqualTo(new Money(3_000, USD));
        assertThat(dollarsPerMillion("15").costOf(2_500_000)).isEqualTo(Money.of(new BigDecimal("37.5"), USD));
        assertThat(dollarsPerMillion("3").costOf(0)).isEqualTo(new Money(0, USD));
    }

    @Test
    void roundsUpToTheNextNano() {
        TokenPrice oneNanoPerMillionTokens = new TokenPrice(new Money(1, USD));
        assertThat(oneNanoPerMillionTokens.costOf(1).nanos()).isEqualTo(1);
        assertThat(oneNanoPerMillionTokens.costOf(1_000_000).nanos()).isEqualTo(1);
        assertThat(oneNanoPerMillionTokens.costOf(1_000_001).nanos()).isEqualTo(2);
    }

    @Test
    void rejectsNegativeInputs() {
        assertThatThrownBy(() -> dollarsPerMillion("3").costOf(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dollarsPerMillion("-3")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overflowThrowsInsteadOfWrapping() {
        TokenPrice absurdPrice = new TokenPrice(new Money(Long.MAX_VALUE, USD));
        assertThatThrownBy(() -> absurdPrice.costOf(2)).isInstanceOf(ArithmeticException.class);
    }
}
