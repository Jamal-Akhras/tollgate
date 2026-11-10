package io.github.jamalakhras.tollgate.server.audit;

import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.Usage;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public record UsageReport(Instant from, Instant to, String by, List<Row> rows) {

    public enum Grouping {
        TENANT,
        MODEL,
        KEY,
        DAY;

        String keyOf(Intent intent) {
            return switch (this) {
                case TENANT -> intent.tenant();
                case MODEL -> intent.model();
                case KEY -> intent.key();
                case DAY -> intent.time().atZone(ZoneOffset.UTC).toLocalDate().toString();
            };
        }

        public String configName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Grouping fromConfigName(String name) {
            return Arrays.stream(values())
                    .filter(grouping -> grouping.configName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("by must be one of "
                            + Arrays.stream(values()).map(Grouping::configName).collect(Collectors.joining(", "))
                            + ", was " + name));
        }
    }

    public record Row(
            String key,
            long calls,
            long unfinished,
            Map<String, Long> refused,
            Tokens tokens,
            Map<String, String> cost) {}

    public record Tokens(long input, long output, long cacheRead, long cacheWrite) {}

    public static UsageReport of(Path directory, @Nullable String from, @Nullable String to, String by, Clock clock)
            throws IOException {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate first = from == null ? today.withDayOfMonth(1) : date("from", from);
        LocalDate last = to == null ? today.plusDays(1) : date("to", to);
        if (!first.isBefore(last)) {
            throw new IllegalArgumentException("from must be before to, which is the first day not included");
        }
        return of(
                directory,
                first.atStartOfDay(ZoneOffset.UTC).toInstant(),
                last.atStartOfDay(ZoneOffset.UTC).toInstant(),
                Grouping.fromConfigName(by));
    }

    private static LocalDate date(String name, String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be a date such as 2026-11-01, was " + text, e);
        }
    }

    public static UsageReport of(Path directory, Instant from, Instant to, Grouping by) throws IOException {
        Map<String, Tally> tallies = new TreeMap<>();
        Map<String, Tally> open = new HashMap<>();
        AuditDirectory.records(directory, record -> {
            switch (record) {
                case Intent intent -> {
                    if (intent.time().isBefore(from) || !intent.time().isBefore(to)) {
                        return;
                    }
                    Tally tally = tallies.computeIfAbsent(by.keyOf(intent), key -> new Tally());
                    if (intent.decision() instanceof Decision.Deny denial) {
                        tally.refused.merge(denial.refusal().code(), 1L, Long::sum);
                    } else {
                        open.put(intent.id(), tally);
                    }
                }
                case Outcome outcome -> {
                    Tally tally = open.remove(outcome.id());
                    if (tally != null) {
                        tally.finish(outcome);
                    }
                }
            }
        });
        open.values().forEach(tally -> tally.unfinished++);
        List<Row> rows = tallies.entrySet().stream()
                .map(entry -> entry.getValue().row(entry.getKey()))
                .toList();
        return new UsageReport(from, to, by.configName(), rows);
    }

    private static final class Tally {

        private long calls;
        private long unfinished;
        private final Map<String, Long> refused = new TreeMap<>();
        private long input;
        private long output;
        private long cacheRead;
        private long cacheWrite;
        private final Map<Currency, Long> cost =
                new TreeMap<>((a, b) -> a.getCurrencyCode().compareTo(b.getCurrencyCode()));

        void finish(Outcome outcome) {
            if (outcome.result() == Outcome.Result.DRY_RUN) {
                return;
            }
            calls++;
            Usage usage = outcome.usage();
            if (usage != null) {
                input += usage.inputTokens();
                output += usage.outputTokens();
                cacheRead += usage.cacheReadTokens();
                cacheWrite += usage.cacheWriteTokens();
            }
            cost.merge(outcome.cost().currency(), outcome.cost().nanos(), Math::addExact);
        }

        Row row(String key) {
            Map<String, String> amounts = new TreeMap<>();
            cost.forEach((currency, nanos) -> amounts.put(
                    currency.getCurrencyCode(),
                    new Money(nanos, currency)
                            .toBigDecimal()
                            .stripTrailingZeros()
                            .toPlainString()));
            return new Row(
                    key,
                    calls,
                    unfinished,
                    new TreeMap<>(refused),
                    new Tokens(input, output, cacheRead, cacheWrite),
                    amounts);
        }
    }
}
