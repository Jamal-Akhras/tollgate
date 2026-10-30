package io.github.jamalakhras.tollgate.providers;

import org.jspecify.annotations.Nullable;

public final class ProviderResponse {

    private final int status;
    private final byte[] body;
    private final @Nullable String contentType;
    private final @Nullable String retryAfter;

    ProviderResponse(int status, byte[] body, @Nullable String contentType, @Nullable String retryAfter) {
        this.status = status;
        this.body = body;
        this.contentType = contentType;
        this.retryAfter = retryAfter;
    }

    public int status() {
        return status;
    }

    public byte[] body() {
        return body.clone();
    }

    public @Nullable String contentType() {
        return contentType;
    }

    public @Nullable String retryAfter() {
        return retryAfter;
    }
}
