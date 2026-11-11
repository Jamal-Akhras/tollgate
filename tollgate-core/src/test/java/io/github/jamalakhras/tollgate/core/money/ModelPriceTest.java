package io.github.jamalakhras.tollgate.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
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
    void pricesTheWholeCallAtTheHighestTierItsPromptReaches() {
        ModelPrice qwen = tiered();

        assertThat(qwen.costOf(new Usage(31_999, 1_000_000, 0, 0)))
                .isEqualTo(Prices.usd("0.031999").plus(usd("1")));
        assertThat(qwen.costOf(new Usage(22_000, 1_000_000, 10_000, 0)))
                .isEqualTo(Prices.usd("0.044").plus(usd("0.02")).plus(usd("2")));
        assertThat(qwen.costOf(new Usage(1_000_000, 0, 0, 0))).isEqualTo(usd("4"));
        assertThat(qwen.ratesFor(127_999)).isEqualTo(qwen.longContext().get(0).rates());
        assertThat(qwen.ratesFor(128_000)).isEqualTo(qwen.longContext().get(1).rates());
    }

    @Test
    void reservesAtTheTierOfTheEstimatedPrompt() {
        ModelPrice qwen = tiered();

        assertThat(qwen.mostFor(1_000, 1_000_000)).isEqualTo(usd("0.001").plus(usd("1")));
        assertThat(qwen.mostFor(1_000_000, 1_000_000)).isEqualTo(usd("4").plus(usd("4")));
        assertThat(qwen.outputTokensWithin(usd("4.000004"), 1_000_000, 0)).isOne();
    }

    @Test
    void chargesReasoningTokensAtTheirOwnRate() {
        ModelPrice thinking = reasoning("1.2", "4");
        Usage usage = new Usage(0, 1_000_000, 0, 0);

        assertThat(thinking.costOf(usage, 250_000, 0)).isEqualTo(usd("0.9").plus(usd("1")));
        assertThat(thinking.costOf(usage, 0, 0)).isEqualTo(usd("1.2"));
        assertThat(thinking.costOf(usage, 2_000_000, 0)).isEqualTo(usd("4"));
    }

    @Test
    void chargesCacheWritesKeptForAnHourAtTheirOwnRate() {
        ModelPrice claude = sonnet.withCacheWrite1hAtTwiceInput();
        Usage usage = new Usage(0, 0, 0, 1_000_000);

        assertThat(claude.costOf(usage, 0, 0, 400_000)).isEqualTo(usd("2.25").plus(usd("2.4")));
        assertThat(claude.costOf(usage, 0, 0, 2_000_000)).isEqualTo(usd("6"));
        assertThat(sonnet.costOf(usage, 0, 0, 1_000_000)).isEqualTo(usd("3.75"));
        assertThat(claude.mostFor(1_000_000, 0)).isEqualTo(usd("6"));
    }

    @Test
    void reservesOutputAtTheDearerOfOutputAndReasoning() {
        assertThat(reasoning("1.2", "4").mostFor(0, 1_000_000)).isEqualTo(usd("4"));
        assertThat(reasoning("4", "1.2").mostFor(0, 1_000_000)).isEqualTo(usd("4"));
        assertThat(reasoning("1.2", "4").outputTokensWithin(usd("2"), 0, 0)).isEqualTo(500_000);
    }

    @Test
    void requiresTiersInIncreasingOrder() {
        TokenRates rates = sonnet.rates();

        assertThatThrownBy(() -> new ModelPrice(
                        rates, List.of(new ContextTier(128_000, rates), new ContextTier(32_000, rates)), null))
                .hasMessage("longContext tiers must be in increasing order of fromTokens");
        assertThatThrownBy(() -> new ModelPrice(
                        rates, List.of(new ContextTier(32_000, rates), new ContextTier(32_000, rates)), null))
                .hasMessage("longContext tiers must be in increasing order of fromTokens");
        assertThatThrownBy(() -> new ContextTier(0, rates)).hasMessage("fromTokens must be at least 1, was 0");
        var pounds = new TokenPrice(Money.of(BigDecimal.ONE, Currency.getInstance("GBP")));
        assertThatThrownBy(() -> new ModelPrice(
                        rates, List.of(new ContextTier(1, new TokenRates(pounds, pounds, pounds, pounds))), null))
                .hasMessage("All prices of a model must be in one currency");
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

    private static ModelPrice tiered() {
        return new ModelPrice(
                rates("1", "1"),
                List.of(new ContextTier(32_000, rates("2", "2")), new ContextTier(128_000, rates("4", "4"))),
                null);
    }

    private static ModelPrice reasoning(String output, String reasoning) {
        TokenPrice none = new TokenPrice(usd("0"));
        return new ModelPrice(
                new TokenRates(none, new TokenPrice(usd(output)), new TokenPrice(usd(reasoning)), none, none),
                List.of(),
                null);
    }

    private static TokenRates rates(String input, String output) {
        return new TokenRates(
                new TokenPrice(usd(input)),
                new TokenPrice(usd(output)),
                new TokenPrice(usd(input)),
                new TokenPrice(usd(input)));
    }

    private static Money usd(String amount) {
        return Prices.usd(amount);
    }
}
