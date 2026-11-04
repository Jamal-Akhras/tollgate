package io.github.jamalakhras.tollgate.core.redact;

public final class ScanTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String detector;

    ScanTimeoutException(String detector) {
        super("The '" + detector + "' detector ran out of time");
        this.detector = detector;
    }

    public String detector() {
        return detector;
    }
}
