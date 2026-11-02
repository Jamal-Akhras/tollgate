package io.github.jamalakhras.tollgate.providers;

public final class InvalidProviderResponseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InvalidProviderResponseException(String message) {
        super(message);
    }
}
