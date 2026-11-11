package io.github.jamalakhras.tollgate.core.money;

import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;

public record TokenRates(
        TokenPrice input,
        TokenPrice output,
        TokenPrice reasoning,
        TokenPrice cacheRead,
        TokenPrice cacheWrite,
        TokenPrice cacheWrite1h) {

    public TokenRates {
        Currency currency = input.perMillionTokens().currency();
        for (TokenPrice price : List.of(output, reasoning, cacheRead, cacheWrite, cacheWrite1h)) {
            if (!price.perMillionTokens().currency().equals(currency)) {
                throw new IllegalArgumentException("All prices of a model must be in one currency");
            }
        }
    }

    public TokenRates(
            TokenPrice input, TokenPrice output, TokenPrice reasoning, TokenPrice cacheRead, TokenPrice cacheWrite) {
        this(input, output, reasoning, cacheRead, cacheWrite, cacheWrite);
    }

    public TokenRates(TokenPrice input, TokenPrice output, TokenPrice cacheRead, TokenPrice cacheWrite) {
        this(input, output, output, cacheRead, cacheWrite);
    }

    public TokenRates withCacheWrite1h(TokenPrice price) {
        return new TokenRates(input, output, reasoning, cacheRead, cacheWrite, price);
    }

    public TokenRates withCacheWrite1hAtTwiceInput() {
        return withCacheWrite1h(new TokenPrice(input.perMillionTokens().plus(input.perMillionTokens())));
    }

    public Currency currency() {
        return input.perMillionTokens().currency();
    }

    Money costOf(Usage usage, long reasoningTokens, long cacheWrite1hTokens) {
        long reasoned = Math.min(reasoningTokens, usage.outputTokens());
        long keptForAnHour = Math.min(cacheWrite1hTokens, usage.cacheWriteTokens());
        return input.costOf(usage.inputTokens())
                .plus(output.costOf(usage.outputTokens() - reasoned))
                .plus(reasoning.costOf(reasoned))
                .plus(cacheRead.costOf(usage.cacheReadTokens()))
                .plus(cacheWrite.costOf(usage.cacheWriteTokens() - keptForAnHour))
                .plus(cacheWrite1h.costOf(keptForAnHour));
    }

    Money mostFor(long inputTokens, long outputTokens) {
        return dearest(input, cacheRead, cacheWrite, cacheWrite1h)
                .costOf(inputTokens)
                .plus(dearestOutput().costOf(outputTokens));
    }

    TokenPrice dearestOutput() {
        return dearest(output, reasoning);
    }

    private static TokenPrice dearest(TokenPrice... prices) {
        return Collections.max(List.of(prices), Comparator.comparing(TokenPrice::perMillionTokens));
    }
}
