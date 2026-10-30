package io.github.jamalakhras.tollgate.core.config;

import io.github.jamalakhras.tollgate.core.money.ModelPrice;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record Model(
        String provider,
        String upstream,
        ModelPrice price,
        @Nullable OutputTokens maxOutputTokens) {

    public Model {
        Objects.requireNonNull(price, "price");
    }
}
