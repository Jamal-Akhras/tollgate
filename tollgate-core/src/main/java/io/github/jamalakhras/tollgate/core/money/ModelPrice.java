package io.github.jamalakhras.tollgate.core.money;

import java.util.Currency;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record ModelPrice(
        TokenRates rates,
        List<ContextTier> longContext,
        @Nullable SearchPrice webSearch) {

    public ModelPrice {
        longContext = List.copyOf(longContext);
        Currency currency = rates.currency();
        long previous = 0;
        for (ContextTier tier : longContext) {
            if (!tier.rates().currency().equals(currency)) {
                throw new IllegalArgumentException("All prices of a model must be in one currency");
            }
            if (tier.fromTokens() <= previous) {
                throw new IllegalArgumentException("longContext tiers must be in increasing order of fromTokens");
            }
            previous = tier.fromTokens();
        }
        if (webSearch != null && !webSearch.perThousandSearches().currency().equals(currency)) {
            throw new IllegalArgumentException("All prices of a model must be in one currency");
        }
    }

    public ModelPrice(TokenPrice input, TokenPrice output, TokenPrice cacheRead, TokenPrice cacheWrite) {
        this(new TokenRates(input, output, cacheRead, cacheWrite), List.of(), null);
    }

    public Currency currency() {
        return rates.currency();
    }

    public TokenRates ratesFor(long promptTokens) {
        TokenRates applies = rates;
        for (ContextTier tier : longContext) {
            if (promptTokens >= tier.fromTokens()) {
                applies = tier.rates();
            }
        }
        return applies;
    }

    public Money costOf(Usage usage) {
        return costOf(usage, 0, 0);
    }

    public Money costOf(Usage usage, long reasoningTokens, long webSearches) {
        long prompt =
                Math.addExact(Math.addExact(usage.inputTokens(), usage.cacheReadTokens()), usage.cacheWriteTokens());
        return ratesFor(prompt).costOf(usage, reasoningTokens).plus(searches(webSearches));
    }

    public Money mostFor(long inputTokens, long outputTokens) {
        return mostFor(inputTokens, outputTokens, 0);
    }

    public Money mostFor(long inputTokens, long outputTokens, long webSearches) {
        return ratesFor(inputTokens).mostFor(inputTokens, outputTokens).plus(searches(webSearches));
    }

    public long outputTokensWithin(Money available, long inputTokens, long webSearches) {
        Money left = available.minus(mostFor(inputTokens, 0, webSearches));
        return left.isNegative() ? 0 : ratesFor(inputTokens).dearestOutput().tokensWithin(left);
    }

    private Money searches(long count) {
        if (count == 0) {
            return new Money(0, currency());
        }
        if (webSearch == null) {
            throw new IllegalStateException("This model has no web search price");
        }
        return webSearch.costOf(count);
    }
}
