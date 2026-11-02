package io.github.jamalakhras.tollgate.core.money;

import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record ModelPrice(
        TokenPrice input,
        TokenPrice output,
        TokenPrice cacheRead,
        TokenPrice cacheWrite,
        @Nullable SearchPrice webSearch) {

    public ModelPrice {
        Currency currency = input.perMillionTokens().currency();
        for (TokenPrice price : List.of(output, cacheRead, cacheWrite)) {
            if (!price.perMillionTokens().currency().equals(currency)) {
                throw new IllegalArgumentException("All prices of a model must be in one currency");
            }
        }
        if (webSearch != null && !webSearch.perThousandSearches().currency().equals(currency)) {
            throw new IllegalArgumentException("All prices of a model must be in one currency");
        }
    }

    public ModelPrice(TokenPrice input, TokenPrice output, TokenPrice cacheRead, TokenPrice cacheWrite) {
        this(input, output, cacheRead, cacheWrite, null);
    }

    public Money costOf(Usage usage) {
        return costOf(usage, 0);
    }

    public Money costOf(Usage usage, long webSearches) {
        return input.costOf(usage.inputTokens())
                .plus(output.costOf(usage.outputTokens()))
                .plus(cacheRead.costOf(usage.cacheReadTokens()))
                .plus(cacheWrite.costOf(usage.cacheWriteTokens()))
                .plus(searches(webSearches));
    }

    public Money mostFor(long inputTokens, long outputTokens) {
        return mostFor(inputTokens, outputTokens, 0);
    }

    public Money mostFor(long inputTokens, long outputTokens, long webSearches) {
        TokenPrice dearestInput = Collections.max(
                List.of(input, cacheRead, cacheWrite), Comparator.comparing(TokenPrice::perMillionTokens));
        return dearestInput
                .costOf(inputTokens)
                .plus(output.costOf(outputTokens))
                .plus(searches(webSearches));
    }

    public long outputTokensWithin(Money available, long inputTokens, long webSearches) {
        Money left = available.minus(mostFor(inputTokens, 0, webSearches));
        return left.isNegative() ? 0 : output.tokensWithin(left);
    }

    private Money searches(long count) {
        if (count == 0) {
            return new Money(0, input.perMillionTokens().currency());
        }
        if (webSearch == null) {
            throw new IllegalStateException("This model has no web search price");
        }
        return webSearch.costOf(count);
    }
}
