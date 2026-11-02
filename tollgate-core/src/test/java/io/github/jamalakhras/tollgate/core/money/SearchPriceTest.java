package io.github.jamalakhras.tollgate.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class SearchPriceTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), USD);
    }

    private static TokenPrice perMillion(String amount) {
        return new TokenPrice(usd(amount));
    }

    @Test
    void chargesPerSearchRoundingUp() {
        var price = new SearchPrice(usd("10"));

        assertThat(price.costOf(0)).isEqualTo(usd("0"));
        assertThat(price.costOf(3)).isEqualTo(usd("0.03"));
        assertThat(new SearchPrice(new Money(1, USD)).costOf(1)).isEqualTo(new Money(1, USD));
    }

    @Test
    void rejectsNegativeValues() {
        assertThatThrownBy(() -> new SearchPrice(usd("-1"))).hasMessage("Price must not be negative: -1 USD");
        assertThatThrownBy(() -> new SearchPrice(usd("10")).costOf(-1))
                .hasMessage("Search count must not be negative: -1");
    }

    @Test
    void addsSearchesToAModelsCostAndWorstCase() {
        var price = new ModelPrice(
                perMillion("3"), perMillion("15"), perMillion("0.3"), perMillion("3.75"), new SearchPrice(usd("10")));
        var usage = new Usage(1_000_000, 0, 0, 0);

        assertThat(price.costOf(usage, 2)).isEqualTo(usd("3.02"));
        assertThat(price.costOf(usage)).isEqualTo(usd("3"));
        assertThat(price.mostFor(1_000_000, 0, 5)).isEqualTo(usd("3.80"));
        assertThat(price.mostFor(1_000_000, 0)).isEqualTo(usd("3.75"));
    }

    @Test
    void findsTheMostTokensAnAmountCovers() {
        TokenPrice price = perMillion("3");

        assertThat(price.tokensWithin(usd("0.000003"))).isOne();
        assertThat(price.tokensWithin(usd("0.0000059"))).isOne();
        assertThat(price.tokensWithin(usd("3"))).isEqualTo(1_000_000);
        assertThat(price.costOf(price.tokensWithin(usd("1.23456789")))).isLessThanOrEqualTo(usd("1.23456789"));
        assertThat(price.costOf(price.tokensWithin(usd("1.23456789")) + 1)).isGreaterThan(usd("1.23456789"));
        assertThat(perMillion("0").tokensWithin(usd("1"))).isEqualTo(Long.MAX_VALUE);
        assertThat(new TokenPrice(new Money(1, USD)).tokensWithin(new Money(Long.MAX_VALUE, USD)))
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void leavesOutputTheRestOfAnAmountAfterInputAndSearches() {
        var price = new ModelPrice(
                perMillion("1"), perMillion("10"), perMillion("1"), perMillion("1"), new SearchPrice(usd("10")));

        assertThat(price.outputTokensWithin(usd("1.02"), 1_000_000, 0)).isEqualTo(2_000);
        assertThat(price.outputTokensWithin(usd("1.02"), 1_000_000, 2)).isZero();
        assertThat(price.outputTokensWithin(usd("0.5"), 1_000_000, 0)).isZero();
    }

    @Test
    void refusesToPriceSearchesWithoutASearchPrice() {
        var price = new ModelPrice(perMillion("3"), perMillion("15"), perMillion("0.3"), perMillion("3.75"));

        assertThat(price.webSearch()).isNull();
        assertThatThrownBy(() -> price.costOf(new Usage(1, 1, 0, 0), 1))
                .hasMessage("This model has no web search price");
    }

    @Test
    void requiresTheSearchPriceInTheModelsCurrency() {
        assertThatThrownBy(() -> new ModelPrice(
                        perMillion("3"),
                        perMillion("15"),
                        perMillion("0.3"),
                        perMillion("3.75"),
                        new SearchPrice(Money.of(BigDecimal.TEN, EUR))))
                .hasMessage("All prices of a model must be in one currency");
    }
}
