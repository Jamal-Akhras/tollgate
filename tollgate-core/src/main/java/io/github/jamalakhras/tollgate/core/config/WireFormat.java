package io.github.jamalakhras.tollgate.core.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public enum WireFormat {
    OPENAI,
    ANTHROPIC;

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    static WireFormat fromConfigName(String name) {
        return Arrays.stream(values())
                .filter(format -> format.configName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("format must be one of "
                        + Arrays.stream(values()).map(WireFormat::configName).collect(Collectors.joining(", "))
                        + ", was " + name));
    }
}
