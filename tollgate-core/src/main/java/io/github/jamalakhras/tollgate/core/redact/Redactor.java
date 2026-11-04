package io.github.jamalakhras.tollgate.core.redact;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import org.jspecify.annotations.Nullable;

public final class Redactor {

    private static final Comparator<Found> FIRST_THEN_LONGEST = Comparator.comparingInt(Found::start)
            .thenComparing(Comparator.comparingInt(Found::end).reversed());

    private final Set<Detector> refuse;
    private final Set<Detector> redact;
    private final Map<String, String> placeholders = new HashMap<>();
    private final Map<String, String> values = new HashMap<>();
    private final Map<String, Integer> issued = new TreeMap<>();
    private final Map<Detector, Integer> numbers = new HashMap<>();
    private final ScanBudget budget;
    private @Nullable Detector refused;

    public Redactor(Set<Detector> refuse, Set<Detector> redact) {
        this(refuse, redact, ScanBudget.UNLIMITED);
    }

    public Redactor(Set<Detector> refuse, Set<Detector> redact, Duration maxScanTime) {
        this(refuse, redact, ScanBudget.of(maxScanTime));
    }

    private Redactor(Set<Detector> refuse, Set<Detector> redact, ScanBudget budget) {
        this.refuse = Collections.unmodifiableSortedSet(new TreeSet<>(refuse));
        this.redact = Collections.unmodifiableSortedSet(new TreeSet<>(redact));
        this.budget = budget;
    }

    public String redact(String text) {
        if (refused == null) {
            refused = refuse.stream()
                    .filter(detector -> !spans(detector, text).isEmpty())
                    .findFirst()
                    .orElse(null);
        }
        List<Found> found = new ArrayList<>();
        for (Detector detector : redact) {
            for (Span span : spans(detector, text)) {
                found.add(new Found(detector, span.start(), span.end(), true));
            }
        }
        if (!redact.isEmpty() && text.indexOf('[') >= 0) {
            Matcher written = Placeholders.PLACEHOLDER.matcher(text);
            while (written.find()) {
                String label = written.group().substring(1, written.group().lastIndexOf('_'));
                redact.stream()
                        .filter(detector -> detector.label().equals(label))
                        .findFirst()
                        .ifPresent(detector -> found.add(new Found(detector, written.start(), written.end(), false)));
            }
        }
        if (found.isEmpty()) {
            return text;
        }
        found.sort(FIRST_THEN_LONGEST);
        StringBuilder redacted = new StringBuilder(text.length());
        int at = 0;
        for (Found next : found) {
            if (next.start() < at) {
                continue;
            }
            redacted.append(text, at, next.start())
                    .append(placeholder(next.detector(), text.substring(next.start(), next.end()), next.counted()));
            at = next.end();
        }
        return redacted.append(text, at, text.length()).toString();
    }

    public Optional<Detector> refused() {
        return Optional.ofNullable(refused);
    }

    public Map<String, Integer> values() {
        return Collections.unmodifiableMap(new TreeMap<>(issued));
    }

    public Placeholders placeholders() {
        return new Placeholders(values);
    }

    private String placeholder(Detector detector, String value, boolean counted) {
        String existing = placeholders.get(value);
        if (existing != null) {
            return existing;
        }
        if (counted) {
            issued.merge(detector.configName(), 1, Integer::sum);
        }
        int number = numbers.merge(detector, 1, Integer::sum);
        String placeholder = "[" + detector.label() + "_" + number + "]";
        placeholders.put(value, placeholder);
        values.put(placeholder, value);
        return placeholder;
    }

    private List<Span> spans(Detector detector, String text) {
        List<Span> spans = new ArrayList<>();
        detector.find(text, spans, budget);
        return spans;
    }

    private record Found(Detector detector, int start, int end, boolean counted) {}
}
