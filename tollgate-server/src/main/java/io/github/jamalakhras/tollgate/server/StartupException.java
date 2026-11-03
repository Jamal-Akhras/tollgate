package io.github.jamalakhras.tollgate.server;

public final class StartupException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public StartupException(String message) {
        super(message);
    }

    public StartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
