package io.github.jamalakhras.tollgate.core.config;

import io.github.jamalakhras.tollgate.core.money.ModelPrice;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record Model(
        String provider,
        String upstream,
        ModelPrice price,
        @Nullable OutputTokens maxOutputTokens,
        long maxWebSearches,
        @Nullable String priceCatalog) {

    public Model {
        Objects.requireNonNull(price, "price");
        if (maxWebSearches < 0) {
            throw new IllegalArgumentException("maxWebSearches must not be negative");
        }
    }

    public Model(String provider, String upstream, ModelPrice price, @Nullable OutputTokens maxOutputTokens) {
        this(provider, upstream, price, maxOutputTokens, 0, null);
    }

    public Model(
            String provider,
            String upstream,
            ModelPrice price,
            @Nullable OutputTokens maxOutputTokens,
            long maxWebSearches) {
        this(provider, upstream, price, maxOutputTokens, maxWebSearches, null);
    }

    public boolean offersWebSearch() {
        return maxWebSearches > 0 && price.webSearch() != null;
    }
}
