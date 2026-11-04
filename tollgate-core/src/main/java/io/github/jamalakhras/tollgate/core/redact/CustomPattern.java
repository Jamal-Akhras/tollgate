package io.github.jamalakhras.tollgate.core.redact;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CustomPattern implements Detector.Finder {

    private static final String VALUE = "value";
    private static final int CHECK_EVERY = 1024;

    private final String detector;
    private final Pattern pattern;
    private final boolean valued;

    CustomPattern(String detector, Pattern pattern) {
        this.detector = detector;
        this.pattern = pattern;
        this.valued = pattern.namedGroups().containsKey(VALUE);
    }

    @Override
    public void find(String text, List<Span> spans, ScanBudget budget) {
        if (budget.spent()) {
            throw new ScanTimeoutException(detector);
        }
        Matcher matcher = pattern.matcher(new TimedText(text, budget));
        while (matcher.find()) {
            int start = valued ? matcher.start(VALUE) : matcher.start();
            int end = valued ? matcher.end(VALUE) : matcher.end();
            if (end > start) {
                spans.add(new Span(start, end));
            }
        }
    }

    private final class TimedText implements CharSequence {

        private final String text;
        private final ScanBudget budget;
        private int reads;

        TimedText(String text, ScanBudget budget) {
            this.text = text;
            this.budget = budget;
        }

        @Override
        public char charAt(int index) {
            if (++reads % CHECK_EVERY == 0 && budget.spent()) {
                throw new ScanTimeoutException(detector);
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
