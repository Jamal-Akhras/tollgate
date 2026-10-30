package io.github.jamalakhras.tollgate.providers;

import org.jspecify.annotations.Nullable;

public final class InvalidRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final @Nullable String param;

    InvalidRequestException(String message, @Nullable String param) {
        super(message);
        this.param = param;
    }

    public @Nullable String param() {
        return param;
    }
}
