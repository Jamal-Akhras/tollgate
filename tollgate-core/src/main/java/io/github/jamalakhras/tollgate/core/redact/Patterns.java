package io.github.jamalakhras.tollgate.core.redact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

final class Patterns {

    private static final Pattern SECRET = Pattern.compile("(?<![A-Za-z0-9_-])(?:"
            + "sk-[A-Za-z0-9_-]{20,}+"
            + "|gh[pousr]_[A-Za-z0-9]{36,}+"
            + "|github_pat_[A-Za-z0-9_]{22,}+"
            + "|glpat-[A-Za-z0-9_-]{20,}+"
            + "|xox[abposr]-[A-Za-z0-9-]{10,}+"
            + "|(?:AKIA|ASIA)[A-Z0-9]{16}(?![A-Za-z0-9])"
            + "|AIza[A-Za-z0-9_-]{35}(?![A-Za-z0-9_-])"
            + "|tg_[A-Za-z0-9]{12}_[A-Za-z0-9]{43}(?![A-Za-z0-9])"
            + ")");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z0-9 ]{0,40}PRIVATE KEY(?: BLOCK)?-----");
    private static final Pattern PRIVATE_KEY_END =
            Pattern.compile("-----END [A-Z0-9 ]{0,40}PRIVATE KEY(?: BLOCK)?-----");
    private static final Pattern EMAIL = Pattern.compile("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}+@"
            + "(?:[A-Za-z0-9-]{1,63}\\.){1,10}[A-Za-z]{2,63}(?![A-Za-z0-9-]|\\.[A-Za-z0-9])");
    private static final Pattern DIGIT_RUN = Pattern.compile("(?<![\\w.])\\d++(?:[ -]\\d++)*+");
    private static final Pattern IBAN_START = Pattern.compile("(?<![A-Za-z0-9])[A-Z]{2}\\d{2}");
    private static final Pattern NATIONAL_INSURANCE = Pattern.compile("(?<![A-Za-z0-9])(?!BG|GB|NK|KN|TN|NT|ZZ)"
            + "[A-CEGHJ-PR-TW-Z][A-CEGHJ-NPR-TW-Z] ?\\d{2} ?\\d{2} ?\\d{2} ?[A-D](?![A-Za-z0-9])");
    private static final Pattern SOCIAL_SECURITY =
            Pattern.compile("(?<![\\w-])(?!000|666|9)\\d{3}([- ])(?!00)\\d{2}\\1(?!0000)\\d{4}(?![\\w-]|\\.\\d)");
    private static final Pattern INTERNATIONAL_PHONE =
            Pattern.compile("(?<![\\w+])\\+[1-9]\\d*+(?:[ .-]?\\(?\\d++\\)?)*+");
    private static final Pattern NATIONAL_PHONE = Pattern.compile("(?<![\\w+.-])(?:"
            + "(?:\\([2-9]\\d{2}\\) ?|[2-9]\\d{2}[ .-])[2-9]\\d{2}[ .-]\\d{4}"
            + "|0\\d{2,4}[ -]\\d{3,4}[ -]?\\d{3,4}"
            + "|0\\d(?:[ .-]\\d{2}){4}"
            + ")(?!\\w|[.-]\\d)");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}"
            + "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?!\\w|\\.\\d)");
    private static final Pattern IPV6 = Pattern.compile("(?<![\\w:.])[0-9A-Fa-f:]{2,39}+(?![\\w:]|\\.\\d)");

    private static final Map<String, Integer> IBAN_LENGTHS =
            ibanLengths("AD24 AE23 AL28 AT20 AZ28 BA20 BE16 BG22 BH22 BI27 BR29 BY28 "
                    + "CH21 CR22 CY28 CZ24 DE22 DJ27 DK18 DO28 EE20 EG29 ES24 FI18 "
                    + "FK18 FO18 FR27 GB22 GE22 GI23 GL18 GR27 GT28 HN28 HR21 HU28 "
                    + "IE22 IL23 IQ23 IS26 IT27 JO30 KW30 KZ20 LB28 LC32 LI21 LT20 "
                    + "LU20 LV21 LY25 MC27 MD24 ME22 MK19 MN20 MR27 MT31 MU30 NI28 "
                    + "NL18 NO15 OM23 PK24 PL28 PS29 PT25 QA29 RO24 RS22 RU33 SA24 "
                    + "SC31 SD18 SE24 SI19 SK24 SM27 SO23 ST25 SV28 TL23 TN24 TR26 "
                    + "UA29 VA22 VG24 XK20 YE30");

    private static final int[] DOUBLED = {0, 2, 4, 6, 8, 1, 3, 5, 7, 9};
    private static final int MIN_CARD_DIGITS = 13;
    private static final int MAX_CARD_DIGITS = 19;
    private static final int MIN_PHONE_DIGITS = 8;
    private static final int MAX_PHONE_DIGITS = 15;
    private static final int MIN_NATIONAL_DIGITS = 10;
    private static final int MAX_NATIONAL_DIGITS = 11;

    private Patterns() {}

    private static Map<String, Integer> ibanLengths(String countries) {
        Map<String, Integer> lengths = new HashMap<>();
        for (String country : countries.split(" ", -1)) {
            lengths.put(country.substring(0, 2), Integer.parseInt(country.substring(2)));
        }
        return Map.copyOf(lengths);
    }

    static void secrets(String text, List<Span> spans) {
        all(SECRET, text, spans);
        Matcher begin = PRIVATE_KEY.matcher(text);
        int from = 0;
        while (begin.find(from)) {
            Matcher end = PRIVATE_KEY_END.matcher(text);
            int stop = end.find(begin.end()) ? end.end() : text.length();
            spans.add(new Span(begin.start(), stop));
            from = stop;
        }
    }

    static void emails(String text, List<Span> spans) {
        if (text.indexOf('@') >= 0) {
            all(EMAIL, text, spans);
        }
    }

    static void cards(String text, List<Span> spans) {
        Matcher run = DIGIT_RUN.matcher(text);
        while (run.find()) {
            List<Span> groups = digitGroups(text, run.start(), run.end());
            if (continues(text, run.end())) {
                groups.removeLast();
            }
            for (int first = 0; first < groups.size(); first++) {
                int last = longestCard(text, groups, first);
                if (last >= 0) {
                    spans.add(
                            new Span(groups.get(first).start(), groups.get(last).end()));
                }
            }
        }
    }

    static void ibans(String text, List<Span> spans) {
        Matcher start = IBAN_START.matcher(text);
        while (start.find()) {
            Integer length = IBAN_LENGTHS.get(text.substring(start.start(), start.start() + 2));
            if (length == null) {
                continue;
            }
            StringBuilder iban = new StringBuilder(text.substring(start.start(), start.end()));
            int at = start.end();
            while (iban.length() < length && at < text.length()) {
                char c = text.charAt(at);
                if (c == ' ' && at + 1 < text.length() && isUpperAlphanumeric(text.charAt(at + 1))) {
                    at++;
                } else if (isUpperAlphanumeric(c)) {
                    iban.append(c);
                    at++;
                } else {
                    break;
                }
            }
            boolean bounded = at == text.length() || !Character.isLetterOrDigit(text.charAt(at));
            if (iban.length() == length && bounded && mod97(iban) == 1) {
                spans.add(new Span(start.start(), at));
            }
        }
    }

    static void nationalIds(String text, List<Span> spans) {
        all(NATIONAL_INSURANCE, text, spans);
        all(SOCIAL_SECURITY, text, spans);
    }

    static void phones(String text, List<Span> spans) {
        Matcher international = INTERNATIONAL_PHONE.matcher(text);
        while (international.find()) {
            List<Span> groups = digitGroups(text, international.start(), international.end());
            if (continues(text, international.end())) {
                groups.removeLast();
            }
            int digits = 0;
            int end = international.start();
            for (Span group : groups) {
                digits += group.end() - group.start();
                if (digits > MAX_PHONE_DIGITS) {
                    break;
                }
                boolean endsHere = group.end() == international.end() || text.charAt(group.end()) == ' ';
                if (digits >= MIN_PHONE_DIGITS && endsHere) {
                    end = group.end();
                }
            }
            if (end > international.start()) {
                spans.add(new Span(international.start(), end));
            }
        }
        Matcher national = NATIONAL_PHONE.matcher(text);
        while (national.find()) {
            long digits = national.group().chars().filter(Patterns::isDigit).count();
            if (digits >= MIN_NATIONAL_DIGITS && digits <= MAX_NATIONAL_DIGITS) {
                spans.add(new Span(national.start(), national.end()));
            }
        }
    }

    static void ips(String text, List<Span> spans) {
        Matcher v4 = IPV4.matcher(text);
        while (v4.find()) {
            if (isPublicV4(v4.group())) {
                spans.add(new Span(v4.start(), v4.end()));
            }
        }
        if (text.indexOf(':') >= 0) {
            Matcher v6 = IPV6.matcher(text);
            while (v6.find()) {
                String candidate = v6.group();
                if (candidate.endsWith(":") && !candidate.endsWith("::")) {
                    candidate = candidate.substring(0, candidate.length() - 1);
                }
                int[] groups = ipv6(candidate);
                if (groups != null && isPublicV6(groups)) {
                    spans.add(new Span(v6.start(), v6.start() + candidate.length()));
                }
            }
        }
    }

    private static void all(Pattern pattern, String text, List<Span> spans) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            spans.add(new Span(matcher.start(), matcher.end()));
        }
    }

    private static List<Span> digitGroups(String text, int start, int end) {
        List<Span> groups = new ArrayList<>();
        int at = start;
        while (at < end) {
            int groupStart = at;
            while (at < end && isDigit(text.charAt(at))) {
                at++;
            }
            if (at > groupStart) {
                groups.add(new Span(groupStart, at));
            } else {
                at++;
            }
        }
        return groups;
    }

    private static boolean continues(String text, int at) {
        if (at >= text.length()) {
            return false;
        }
        char next = text.charAt(at);
        return Character.isLetterOrDigit(next)
                || next == '_'
                || (next == '.' && at + 1 < text.length() && isDigit(text.charAt(at + 1)));
    }

    private static int longestCard(String text, List<Span> groups, int first) {
        StringBuilder digits = new StringBuilder();
        int found = -1;
        for (int last = first; last < groups.size(); last++) {
            Span group = groups.get(last);
            digits.append(text, group.start(), group.end());
            if (digits.length() > MAX_CARD_DIGITS) {
                break;
            }
            if (digits.length() >= MIN_CARD_DIGITS && isCard(digits)) {
                found = last;
            }
        }
        return found;
    }

    static boolean isCard(CharSequence digits) {
        return issued(digits) && luhn(digits);
    }

    private static boolean issued(CharSequence digits) {
        int length = digits.length();
        int two = Integer.parseInt(digits.subSequence(0, 2).toString());
        int four = Integer.parseInt(digits.subSequence(0, 4).toString());
        return switch (digits.charAt(0)) {
            case '2' -> four >= 2221 && four <= 2720 && length == 16;
            case '3' -> {
                if (two == 34 || two == 37) {
                    yield length == 15;
                }
                if (two == 35) {
                    yield four >= 3528 && four <= 3589 && length >= 16;
                }
                yield (two == 30 || two == 36 || two == 38 || two == 39) && length >= 14;
            }
            case '4' -> length == 13 || length == 16 || length == 19;
            case '5' -> (two >= 51 && two <= 55) ? length == 16 : two != 59;
            case '6' -> length >= 16;
            default -> false;
        };
    }

    static boolean luhn(CharSequence digits) {
        int sum = 0;
        boolean doubled = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            sum += doubled ? DOUBLED[digit] : digit;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }

    static int mod97(CharSequence iban) {
        String rearranged = iban.subSequence(4, iban.length()).toString() + iban.subSequence(0, 4);
        int remainder = 0;
        for (int i = 0; i < rearranged.length(); i++) {
            int value = Character.digit(rearranged.charAt(i), 36);
            remainder = (value > 9 ? remainder * 100 : remainder * 10) + value;
            remainder %= 97;
        }
        return remainder;
    }

    private static boolean isPublicV4(String address) {
        String[] octets = address.split("\\.", -1);
        int a = Integer.parseInt(octets[0]);
        int b = Integer.parseInt(octets[1]);
        int c = Integer.parseInt(octets[2]);
        return a != 0
                && a != 127
                && !address.equals("255.255.255.255")
                && !(a == 192 && b == 0 && c == 2)
                && !(a == 198 && b == 51 && c == 100)
                && !(a == 203 && b == 0 && c == 113);
    }

    static int @Nullable [] ipv6(String text) {
        int compressed = text.indexOf("::");
        String head = compressed >= 0 ? text.substring(0, compressed) : text;
        String tail = compressed >= 0 ? text.substring(compressed + 2) : "";
        List<Integer> before = hextets(head);
        List<Integer> after = hextets(tail);
        if (before == null || after == null) {
            return null;
        }
        int written = before.size() + after.size();
        if (compressed < 0 ? written != 8 : written < 2 || written > 7 || !hasLongGroup(text)) {
            return null;
        }
        int[] groups = new int[8];
        for (int i = 0; i < before.size(); i++) {
            groups[i] = before.get(i);
        }
        for (int i = 0; i < after.size(); i++) {
            groups[8 - after.size() + i] = after.get(i);
        }
        return groups;
    }

    private static @Nullable List<Integer> hextets(String part) {
        List<Integer> hextets = new ArrayList<>();
        if (part.isEmpty()) {
            return hextets;
        }
        for (String group : part.split(":", -1)) {
            if (group.isEmpty() || group.length() > 4) {
                return null;
            }
            hextets.add(Integer.parseInt(group, 16));
        }
        return hextets;
    }

    private static boolean hasLongGroup(String text) {
        for (String group : text.split(":", -1)) {
            if (group.length() >= 3) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPublicV6(int[] groups) {
        boolean leadingZeros = true;
        for (int i = 0; i < 7; i++) {
            leadingZeros &= groups[i] == 0;
        }
        boolean loopbackOrUnspecified = leadingZeros && groups[7] <= 1;
        boolean documentation =
                (groups[0] == 0x2001 && groups[1] == 0x0db8) || (groups[0] == 0x3fff && groups[1] < 0x1000);
        return !loopbackOrUnspecified && !documentation;
    }

    private static boolean isUpperAlphanumeric(char c) {
        return (c >= 'A' && c <= 'Z') || isDigit(c);
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }
}
