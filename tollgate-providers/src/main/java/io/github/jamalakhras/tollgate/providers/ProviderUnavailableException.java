package io.github.jamalakhras.tollgate.providers;

public final class ProviderUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean timedOut;

    ProviderUnavailableException(String message, boolean timedOut, Throwable cause) {
        super(message, cause);
        this.timedOut = timedOut;
    }

    public boolean timedOut() {
        return timedOut;
    }
}
