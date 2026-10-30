package io.github.jamalakhras.tollgate.core.money;

import java.math.BigDecimal;
import java.util.Currency;

public final class Prices {

    private static final Currency USD = Currency.getInstance("USD");

    private Prices() {}

    public static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), USD);
    }

    public static ModelPrice perMillion(String input, String output, String cacheRead, String cacheWrite) {
        return new ModelPrice(
                new TokenPrice(usd(input)),
                new TokenPrice(usd(output)),
                new TokenPrice(usd(cacheRead)),
                new TokenPrice(usd(cacheWrite)));
    }
}
