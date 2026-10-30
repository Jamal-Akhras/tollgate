package io.github.jamalakhras.tollgate.core.budget;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public enum Period {
    DAILY,
    MONTHLY,
    TOTAL;

    public Instant start(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return switch (this) {
            case DAILY -> today.atStartOfDay(ZoneOffset.UTC).toInstant();
            case MONTHLY -> today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            case TOTAL -> Instant.EPOCH;
        };
    }

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Period fromConfigName(String name) {
        return Arrays.stream(values())
                .filter(period -> period.configName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("period must be one of "
                        + Arrays.stream(values()).map(Period::configName).collect(Collectors.joining(", "))
                        + ", was " + name));
    }
}
