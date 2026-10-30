package io.github.jamalakhras.tollgate.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class ModelPriceTest {

    private final ModelPrice sonnet = Prices.perMillion("3", "15", "0.30", "3.75");

    @Test
    void pricesEachKindOfTokenAtItsOwnRate() {
        Usage usage = new Usage(1_000, 500, 10_000, 2_000);

        assertThat(sonnet.costOf(usage))
                .isEqualTo(Prices.usd("0.003")
                        .plus(Prices.usd("0.0075"))
                        .plus(Prices.usd("0.003"))
                        .plus(Prices.usd("0.0075")));
    }

    @Test
    void boundsACallAtTheDearestInputRate() {
        assertThat(sonnet.mostFor(1_000_000, 1_000_000)).isEqualTo(Prices.usd("18.75"));
        assertThat(Prices.perMillion("3", "15", "5", "1").mostFor(1_000_000, 0)).isEqualTo(Prices.usd("5"));
        assertThat(Prices.perMillion("3", "15", "1", "1").mostFor(1_000_000, 0)).isEqualTo(Prices.usd("3"));
    }

    @Test
    void neverCostsLessThanTheActualUsageAllows() {
        Usage usage = new Usage(800, 300, 150, 50);
        long input = usage.inputTokens() + usage.cacheReadTokens() + usage.cacheWriteTokens();

        assertThat(sonnet.mostFor(input, usage.outputTokens())).isGreaterThanOrEqualTo(sonnet.costOf(usage));
    }

    @Test
    void requiresOneCurrency() {
        var pounds = new TokenPrice(Money.of(BigDecimal.ONE, Currency.getInstance("GBP")));
        var dollars = new TokenPrice(Prices.usd("1"));

        assertThatThrownBy(() -> new ModelPrice(dollars, dollars, dollars, pounds))
                .hasMessage("All prices of a model must be in one currency");
    }

    @Test
    void acceptsZeroTokens() {
        assertThat(sonnet.costOf(new Usage(0, 0, 0, 0))).isEqualTo(Prices.usd("0"));
    }

    @Test
    void rejectsNegativeTokenCounts() {
        assertThatThrownBy(() -> new Usage(0, -1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(0, 0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(-1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(0, 0, 0, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
