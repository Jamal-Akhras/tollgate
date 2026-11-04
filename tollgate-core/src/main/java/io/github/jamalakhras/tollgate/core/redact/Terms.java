package io.github.jamalakhras.tollgate.core.redact;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class Terms implements Detector.Finder {

    static final int MAX_TERMS = 10_000;

    private static final char SPACE = ' ';

    private final boolean caseSensitive;
    private final Node root = new Node();

    Terms(List<String> terms, boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("terms must list at least one term");
        }
        if (terms.size() > MAX_TERMS) {
            throw new IllegalArgumentException("terms may list at most " + MAX_TERMS + ", was " + terms.size());
        }
        for (String term : terms) {
            if (term.isEmpty()
                    || !Character.isLetterOrDigit(term.charAt(0))
                    || !Character.isLetterOrDigit(term.charAt(term.length() - 1))) {
                throw new IllegalArgumentException(
                        "each term must start and end with a letter or digit, was '" + term + "'");
            }
            add(term);
        }
    }

    private void add(String term) {
        Node node = root;
        String[] words = term.split("\\s+", -1);
        for (int w = 0; w < words.length; w++) {
            if (w > 0) {
                node = node.next.computeIfAbsent(SPACE, key -> new Node());
            }
            for (int i = 0; i < words[w].length(); i++) {
                node = node.next.computeIfAbsent(fold(words[w].charAt(i)), key -> new Node());
            }
        }
        node.term = true;
    }

    @Override
    public void find(String text, List<Span> spans, ScanBudget budget) {
        int at = 0;
        while (at < text.length()) {
            if (!isWord(text.charAt(at)) || (at > 0 && isWord(text.charAt(at - 1)))) {
                at++;
                continue;
            }
            int end = longestAt(text, at);
            if (end > at) {
                spans.add(new Span(at, end));
                at = end;
            } else {
                at++;
            }
        }
    }

    private int longestAt(String text, int start) {
        @Nullable Node node = root;
        int at = start;
        int found = start;
        while (node != null && at < text.length()) {
            char c = text.charAt(at);
            if (Character.isWhitespace(c)) {
                node = node.next.get(SPACE);
                while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                    at++;
                }
            } else {
                node = node.next.get(fold(c));
                at++;
                if (node != null && node.term && (at == text.length() || !isWord(text.charAt(at)))) {
                    found = at;
                }
            }
        }
        return found;
    }

    private char fold(char c) {
        return caseSensitive ? c : Character.toLowerCase(Character.toUpperCase(c));
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static final class Node {

        private final Map<Character, Node> next = new HashMap<>();
        private boolean term;
    }
}
