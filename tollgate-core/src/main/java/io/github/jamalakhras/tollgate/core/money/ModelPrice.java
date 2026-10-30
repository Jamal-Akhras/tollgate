package io.github.jamalakhras.tollgate.core.money;

import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;

public record ModelPrice(TokenPrice input, TokenPrice output, TokenPrice cacheRead, TokenPrice cacheWrite) {

    public ModelPrice {
        Currency currency = input.perMillionTokens().currency();
        for (TokenPrice price : List.of(output, cacheRead, cacheWrite)) {
            if (!price.perMillionTokens().currency().equals(currency)) {
                throw new IllegalArgumentException("All prices of a model must be in one currency");
            }
        }
    }

    public Money costOf(Usage usage) {
        return input.costOf(usage.inputTokens())
                .plus(output.costOf(usage.outputTokens()))
                .plus(cacheRead.costOf(usage.cacheReadTokens()))
                .plus(cacheWrite.costOf(usage.cacheWriteTokens()));
    }

    public Money mostFor(long inputTokens, long outputTokens) {
        TokenPrice dearestInput = Collections.max(
                List.of(input, cacheRead, cacheWrite), Comparator.comparing(TokenPrice::perMillionTokens));
        return dearestInput.costOf(inputTokens).plus(output.costOf(outputTokens));
    }
}
