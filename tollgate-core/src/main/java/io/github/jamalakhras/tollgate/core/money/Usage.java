package io.github.jamalakhras.tollgate.core.money;

public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {

    public Usage {
        if (inputTokens < 0 || outputTokens < 0 || cacheReadTokens < 0 || cacheWriteTokens < 0) {
            throw new IllegalArgumentException("Token counts must not be negative");
        }
    }
}
