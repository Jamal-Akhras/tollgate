package io.github.jamalakhras.tollgate.core.redact;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum Detector {
    SECRET("secret", "SECRET", "a secret such as an API key or private key"),
    EMAIL("email", "EMAIL", "an email address"),
    CARD("card", "CARD", "a card number"),
    IBAN("iban", "IBAN", "an IBAN"),
    NATIONAL_ID("national-id", "NATIONAL_ID", "a national ID number"),
    PHONE("phone", "PHONE", "a phone number"),
    IP("ip", "IP", "an IP address");

    private final String configName;
    private final String label;
    private final String description;

    Detector(String configName, String label, String description) {
        this.configName = configName;
        this.label = label;
        this.description = description;
    }

    public String configName() {
        return configName;
    }

    public String description() {
        return description;
    }

    String label() {
        return label;
    }

    void find(String text, List<Span> spans) {
        switch (this) {
            case SECRET -> Patterns.secrets(text, spans);
            case EMAIL -> Patterns.emails(text, spans);
            case CARD -> Patterns.cards(text, spans);
            case IBAN -> Patterns.ibans(text, spans);
            case NATIONAL_ID -> Patterns.nationalIds(text, spans);
            case PHONE -> Patterns.phones(text, spans);
            case IP -> Patterns.ips(text, spans);
        }
    }

    public static Detector fromConfigName(String name) {
        return Arrays.stream(values())
                .filter(detector -> detector.configName.equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("detector must be one of "
                        + Arrays.stream(values()).map(Detector::configName).collect(Collectors.joining(", "))
                        + ", was " + name));
    }
}
