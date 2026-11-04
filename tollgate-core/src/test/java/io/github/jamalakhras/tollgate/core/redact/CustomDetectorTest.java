package io.github.jamalakhras.tollgate.core.redact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CustomDetectorTest {

    private static final Detector CUSTOMER_ID = Detector.pattern("customer-id", "CUST-[0-9]{6}");
    private static final Detector ACCOUNT =
            Detector.pattern("account-number", "(?i)account(?: number| no\\.?)?[ :#]*(?<value>[0-9]{8})");
    private static final Detector CODENAMES = Detector.terms("codenames", List.of("Bluebird", "Project Falcon"), false);

    private static List<String> found(Detector detector, String text) {
        List<Span> spans = new ArrayList<>();
        detector.find(text, spans, ScanBudget.UNLIMITED);
        return spans.stream()
                .map(span -> text.substring(span.start(), span.end()))
                .toList();
    }

    @Test
    void findsWhatAPatternDescribes() {
        assertThat(found(CUSTOMER_ID, "Orders for CUST-004211 and CUST-990001, not CUST-12"))
                .containsExactly("CUST-004211", "CUST-990001");
    }

    @Test
    void replacesOnlyTheValueGroupWhenThereIsOne() {
        assertThat(found(ACCOUNT, "Account number 12345678, account no. 87654321 and acct 11112222"))
                .containsExactly("12345678", "87654321");

        Redactor redactor = new Redactor(Set.of(), Set.of(ACCOUNT));
        assertThat(redactor.redact("Pay from account 12345678 today"))
                .isEqualTo("Pay from account [ACCOUNT_NUMBER_1] today");
    }

    @Test
    void skipsMatchesWhereTheValueGroupIsEmptyOrMissing() {
        Detector optional = Detector.pattern("ref", "REF(?<value>[0-9]*)");

        assertThat(found(optional, "REF and REF42")).containsExactly("42");
    }

    @Test
    void explainsAPatternItCannotUse() {
        assertThatThrownBy(() -> Detector.pattern("broken", "CUST-[0-9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("pattern is not a valid regular expression: Unclosed character class");
        assertThatThrownBy(() -> Detector.pattern("everything", "[0-9]*"))
                .hasMessage("pattern matches empty text, so it would match everywhere");
    }

    @Test
    void findsTermsAsWholeWordsInAnyCase() {
        assertThat(found(CODENAMES, "Bluebird ships, BLUEBIRD's date and bluebirds stay; project   falcon too"))
                .containsExactly("Bluebird", "BLUEBIRD", "project   falcon");
        assertThat(found(CODENAMES, "Bluebirds, xBluebird, Bluebird_2, Project, Project\nFalcon"))
                .containsExactly("Project\nFalcon");
        assertThat(found(CODENAMES, "ProjectFalcon Project Falconry")).isEmpty();
    }

    @Test
    void matchesATermWrittenWithTabsOrLineBreaksAgainstAnySpacing() {
        Detector written = Detector.terms("t", List.of("Project\tFalcon", "Blue\n  Bird"), false);

        assertThat(found(written, "Project Falcon and blue bird")).containsExactly("Project Falcon", "blue bird");
    }

    @Test
    void prefersTheLongestTermThatStartsTheSame() {
        Detector clients = Detector.terms("clients", List.of("Acme", "Acme Holdings", "Acme-Corp"), false);

        assertThat(found(clients, "Acme Holdings bought Acme-Corp from Acme; Acme Hold"))
                .containsExactly("Acme Holdings", "Acme-Corp", "Acme", "Acme");
    }

    @Test
    void matchesCaseWhenAsked() {
        Detector exact = Detector.terms("clients", List.of("Globex"), true);

        assertThat(found(exact, "Globex, globex, GLOBEX")).containsExactly("Globex");
    }

    @Test
    void findsTermsInOtherScripts() {
        Detector names = Detector.terms("names", List.of("Zürich Fonds", "東京"), false);

        assertThat(found(names, "ZÜRICH FONDS und 東京 aber 東京都")).containsExactly("ZÜRICH FONDS", "東京");
    }

    @Test
    void restoresATermAsTheUserSpelledIt() {
        Redactor redactor = new Redactor(Set.of(), Set.of(CODENAMES));

        String redacted = redactor.redact("bluebird and Bluebird");

        assertThat(redacted).isEqualTo("[CODENAMES_1] and [CODENAMES_2]");
        assertThat(redactor.placeholders().restore(redacted)).isEqualTo("bluebird and Bluebird");
        assertThat(redactor.values()).isEqualTo(Map.of("codenames", 2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " Bluebird", "Bluebird ", "-x", "x.", "#tag"})
    void refusesTermsThatDoNotStartAndEndWithALetterOrDigit(String term) {
        assertThatThrownBy(() -> Detector.terms("t", List.of("ok", term), false))
                .hasMessage("each term must start and end with a letter or digit, was '" + term + "'");
    }

    @Test
    void limitsHowManyTermsADetectorHas() {
        List<String> many =
                IntStream.rangeClosed(0, Terms.MAX_TERMS).mapToObj(i -> "t" + i).toList();

        assertThatThrownBy(() -> Detector.terms("t", List.of(), false)).hasMessage("terms must list at least one term");
        assertThatThrownBy(() -> Detector.terms("t", many, false))
                .hasMessage("terms may list at most 10000, was 10001");
        assertThat(Detector.terms("t", many.subList(0, Terms.MAX_TERMS), false)).isNotNull();
        assertThat(found(Detector.terms("t", List.of("a", "a"), false), "a")).containsExactly("a");
    }

    @Test
    void scansAMegabyteAgainstTenThousandTermsQuickly() {
        List<String> terms =
                IntStream.range(0, Terms.MAX_TERMS).mapToObj(i -> "client " + i).toList();
        Detector clients = Detector.terms("clients", terms, false);
        String text = "client 4 met client x and Client 9999. ".repeat(25_000);

        List<String> found = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> found(clients, text));

        assertThat(found).hasSize(50_000);
    }

    @Test
    void namesPlaceholdersAfterTheDetector() {
        assertThat(CUSTOMER_ID.label()).isEqualTo("CUSTOMER_ID");
        assertThat(Detector.pattern("eu.vat:id", "VAT[0-9]+").label()).isEqualTo("EU_VAT_ID");
        assertThat(Detector.pattern("2fa", "[0-9]{6}").label()).isEqualTo("2FA");
        assertThat(CUSTOMER_ID.description()).isEqualTo("a value matching the 'customer-id' detector");

        Redactor redactor = new Redactor(Set.of(), Set.of(Detector.pattern("2fa", "\\b[0-9]{6}\\b")));
        String redacted = redactor.redact("Code 123456, and the template said [2FA_1]");
        assertThat(redacted).isEqualTo("Code [2FA_1], and the template said [2FA_2]");
        assertThat(redactor.placeholders().restore(redacted)).isEqualTo("Code 123456, and the template said [2FA_1]");
    }

    @Test
    void ordersCustomDetectorsAfterTheBuiltInOnesByName() {
        Detector apples = Detector.terms("apples", List.of("x"), false);
        Detector zebras = Detector.terms("zebras", List.of("x"), false);
        List<Detector> detectors = new ArrayList<>(List.of(zebras, Detector.IP, apples, Detector.SECRET));

        Collections.sort(detectors);

        assertThat(detectors).containsExactly(Detector.SECRET, Detector.IP, apples, zebras);
        assertThat(new TreeSet<>(List.of(apples, Detector.terms("apples", List.of("y"), true))))
                .hasSize(1);
        assertThat(apples)
                .isEqualTo(Detector.pattern("apples", "z"))
                .isNotEqualTo(zebras)
                .isNotEqualTo("apples");
        assertThat(apples.hashCode()).isEqualTo("apples".hashCode());
    }

    @Test
    void stopsAPatternThatRunsOutOfTimeInTheMiddleOfAText() {
        Detector slow = Detector.pattern("slow", "(.*a){12}b");
        String text = "a".repeat(40) + "!";
        List<Span> spans = new ArrayList<>();

        ScanTimeoutException timeout = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            ScanBudget budget = ScanBudget.of(Duration.ofMillis(200));
            try {
                slow.find(text, spans, budget);
                throw new AssertionError("the scan should have run out of time");
            } catch (ScanTimeoutException e) {
                assertThat(budget.spent()).isTrue();
                return e;
            }
        });

        assertThat(timeout.detector()).isEqualTo("slow");
        assertThat(timeout).hasMessage("The 'slow' detector ran out of time");
    }

    @Test
    void scansLongTextsWithinItsBudget() {
        Redactor redactor = new Redactor(Set.of(), Set.of(CUSTOMER_ID), Duration.ofSeconds(30));
        String text = "x".repeat(100_000) + " CUST-004211 " + "y.".repeat(100_000);

        assertThat(redactor.redact(text)).contains(" [CUSTOMER_ID_1] ").hasSize(text.length() + 4);
        assertThat(Detector.pattern("ref", "REF-[0-9]+").description())
                .isEqualTo("a value matching the 'ref' detector");
    }

    @Test
    void sharesOneBudgetAcrossEveryTextOfARequest() {
        Redactor redactor = new Redactor(Set.of(), Set.of(CUSTOMER_ID), Duration.ofMillis(1));
        String text = "x".repeat(4_096);

        assertThatThrownBy(() -> {
                    for (int i = 0; i < 1_000_000; i++) {
                        redactor.redact(text);
                    }
                })
                .isInstanceOf(ScanTimeoutException.class);
        assertThat(new Redactor(Set.of(), Set.of(CUSTOMER_ID), Duration.ofSeconds(5)).redact("CUST-000001"))
                .isEqualTo("[CUSTOMER_ID_1]");
    }

    @Test
    void refusesACustomDetector() {
        Redactor redactor = new Redactor(Set.of(CODENAMES), Set.of(Detector.EMAIL));

        redactor.redact("The Project Falcon launch, jane@example.com");

        assertThat(redactor.refused()).contains(CODENAMES);
    }
}
