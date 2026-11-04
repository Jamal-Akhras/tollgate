package io.github.jamalakhras.tollgate.core.redact;

public final class StreamedText {

    private final Placeholders placeholders;
    private final boolean encoded;
    private String held = "";

    StreamedText(Placeholders placeholders, boolean encoded) {
        this.placeholders = placeholders;
        this.encoded = encoded;
    }

    public String next(String fragment) {
        String text = held + fragment;
        int keep = placeholders.heldFrom(text);
        held = text.substring(keep);
        return placeholders.restore(text.substring(0, keep), encoded);
    }

    public String flush() {
        String rest = held;
        held = "";
        return rest;
    }
}
