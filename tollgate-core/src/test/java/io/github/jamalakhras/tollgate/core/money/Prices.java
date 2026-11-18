package io.github.jamalakhras.tollgate.core.money;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;

public final class Prices {

    private static final Currency USD = Currency.getInstance("USD");

    private Prices() {}

    public static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), USD);
    }

    public static PriceCatalog catalog(String rows) {
        try {
            return PriceCatalog.read(new BufferedReader(new StringReader("# test catalog\n" + rows)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static TokenRates rates(String input, String output, String cacheRead, String cacheWrite) {
        TokenPrice outputPrice = new TokenPrice(usd(output));
        return new TokenRates(
                new TokenPrice(usd(input)),
                outputPrice,
                outputPrice,
                new TokenPrice(usd(cacheRead)),
                new TokenPrice(usd(cacheWrite)));
    }

    public static ModelPrice perMillion(String input, String output, String cacheRead, String cacheWrite) {
        return new ModelPrice(rates(input, output, cacheRead, cacheWrite), List.of(), null);
    }
}
