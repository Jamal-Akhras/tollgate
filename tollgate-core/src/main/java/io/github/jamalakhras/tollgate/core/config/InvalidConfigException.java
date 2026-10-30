package io.github.jamalakhras.tollgate.core.config;

public final class InvalidConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InvalidConfigException(String message) {
        super(message);
    }

    InvalidConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
