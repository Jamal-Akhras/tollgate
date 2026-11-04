package io.github.jamalakhras.tollgate.core.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DetectorTest {

    static final String OPENAI_KEY = "sk-proj-" + "a1B2".repeat(12);
    static final String ANTHROPIC_KEY = "sk-ant-api03-" + "Zx9_".repeat(20) + "AA";
    static final String PRIVATE_KEY =
            "-----BEGIN " + "PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\nBKcwggSjAgEAAoIBAQC7\n" + "-----END "
                    + "PRIVATE KEY-----";

    static List<String> found(Detector detector, String text) {
        List<Span> spans = new ArrayList<>();
        detector.find(text, spans, ScanBudget.UNLIMITED);
        return spans.stream()
                .map(span -> text.substring(span.start(), span.end()))
                .toList();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jane@example.com",
                "jane.doe+invoices@mail.example.co.uk",
                "J_Smith-99%x@sub-domain.example.museum",
                "a@b.io"
            })
    void findsEmailAddresses(String email) {
        assertThat(found(Detector.EMAIL, "Write to " + email + ". Thanks")).containsExactly(email);
        assertThat(found(Detector.EMAIL, "<" + email + ">")).containsExactly(email);
        assertThat(found(Detector.EMAIL, "mailto:" + email)).containsExactly(email);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "@handle",
                "user@localhost",
                "a@b",
                "foo@bar.c",
                "foo@bar.c0m",
                "name@example.com-x",
                "name@example.com.5",
                "no at sign here"
            })
    void ignoresWhatIsNotAnEmailAddress(String text) {
        assertThat(found(Detector.EMAIL, text)).isEmpty();
    }

    @Test
    void findsAnEmailAddressAfterAnAtSignAtTheStart() {
        assertThat(found(Detector.EMAIL, "@team, write to jane@example.com")).containsExactly("jane@example.com");
    }

    @Test
    void findsEveryEmailAddressInOrder() {
        assertThat(found(Detector.EMAIL, "cc a@example.com, b@example.org;c@example.net"))
                .containsExactly("a@example.com", "b@example.org", "c@example.net");
    }

    @Test
    void ignoresAnOverlongLocalPart() {
        assertThat(found(Detector.EMAIL, "x".repeat(65) + "@example.com")).isEmpty();
        assertThat(found(Detector.EMAIL, "x".repeat(64) + "@example.com")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "4111111111111111",
                "4111 1111 1111 1111",
                "4111-1111-1111-1111",
                "4222222222222",
                "5555 5555 5555 4444",
                "2223 0031 2200 3222",
                "3782 822463 10005",
                "6011 1111 1111 1117",
                "3530 1113 3330 0000",
                "3056 9309 0259 04",
                "4000 0000 0000 0000 006",
                "6759 6498 2643 8453"
            })
    void findsCardNumbers(String card) {
        assertThat(found(Detector.CARD, "Card: " + card + ", thanks")).containsExactly(card);
        assertThat(found(Detector.CARD, card)).containsExactly(card);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "4111 1111 1111 1112",
                "1234 5678 9012 3456",
                "4111111111111111x",
                "x4111111111111111",
                "v1.4111111111111111",
                "4111111111111111.5",
                "41111111111111111111",
                "411111111111",
                "2720 9999 9999 9999 9",
                "5900 0000 0000 0000",
                "0000 0000 0000 0000",
                "7000 0000 0000 0005"
            })
    void ignoresWhatIsNotACardNumber(String text) {
        assertThat(found(Detector.CARD, text)).isEmpty();
    }

    @Test
    void findsACardNumberAmongOtherNumbers() {
        assertThat(found(Detector.CARD, "Pay 2 4111 1111 1111 1111 12 26")).containsExactly("4111 1111 1111 1111");
        assertThat(found(Detector.CARD, "4111 1111 1111 1111 8.8.8.8")).containsExactly("4111 1111 1111 1111");
        assertThat(found(Detector.CARD, "4111 1111 1111 1111 2x")).containsExactly("4111 1111 1111 1111");
        assertThat(found(Detector.CARD, "4111111111111111 5555555555554444"))
                .containsExactly("4111111111111111", "5555555555554444");
    }

    @Test
    void checksEachCardBrandsLengths() {
        assertThat(Patterns.isCard("378282246310005")).isTrue();
        assertThat(Patterns.isCard("3782822463100052")).isFalse();
        assertThat(Patterns.isCard("5555555555554444")).isTrue();
        assertThat(Patterns.isCard("55555555555544440")).isFalse();
        assertThat(Patterns.isCard("2221000000000009")).isTrue();
        assertThat(Patterns.isCard("2220999999999991")).isFalse();
        assertThat(Patterns.isCard("2720999999999996")).isTrue();
        assertThat(Patterns.isCard("2721000000000004")).isFalse();
        assertThat(Patterns.isCard("3528000000000007")).isTrue();
        assertThat(Patterns.isCard("3527000000000008")).isFalse();
        assertThat(Patterns.isCard("3589000000000003")).isTrue();
        assertThat(Patterns.isCard("3590000000000000")).isFalse();
        assertThat(Patterns.isCard("36000000000008")).isTrue();
        assertThat(Patterns.isCard("3600000000004")).isFalse();
        assertThat(Patterns.isCard("38000000000006")).isTrue();
        assertThat(Patterns.isCard("39000000000005")).isTrue();
        assertThat(Patterns.isCard("31000000000003")).isFalse();
        assertThat(Patterns.isCard("6011000000000004")).isTrue();
        assertThat(Patterns.isCard("601100000000001")).isFalse();
        assertThat(Patterns.isCard("4000000000006")).isTrue();
        assertThat(Patterns.isCard("40000000000002")).isFalse();
        assertThat(Patterns.isCard("4000000000000000006")).isTrue();
        assertThat(Patterns.isCard("5000000000005")).isTrue();
        assertThat(Patterns.isCard("5600000000000003")).isTrue();
        assertThat(Patterns.isCard("5100000000000008")).isTrue();
        assertThat(Patterns.isCard("5000000000000009")).isTrue();
        assertThat(Patterns.isCard("5100000000003")).isFalse();
        assertThat(Patterns.isCard("5500000000000004")).isTrue();
        assertThat(Patterns.isCard("5900000000000000")).isFalse();
    }

    @Test
    void checksTheLuhnDigit() {
        assertThat(Patterns.luhn("79927398713")).isTrue();
        assertThat(Patterns.luhn("79927398710")).isFalse();
        assertThat(Patterns.luhn("0")).isTrue();
        assertThat(Patterns.luhn("18")).isTrue();
        assertThat(Patterns.luhn("59")).isTrue();
        assertThat(Patterns.luhn("91")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GB82 WEST 1234 5698 7654 32",
                "GB82WEST12345698765432",
                "DE89 3704 0044 0532 0130 00",
                "FR14 2004 1010 0505 0001 3M02 606",
                "NL91 ABNA 0417 1643 00",
                "NO93 8601 1117 947",
                "GB85 ZYXW 1234 5698 7654 32",
                "LC55 HEMM 0001 0001 0012 0012 0002 3015"
            })
    void findsIbans(String iban) {
        assertThat(found(Detector.IBAN, "Pay into " + iban + ", please")).containsExactly(iban);
        assertThat(found(Detector.IBAN, iban)).containsExactly(iban);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GB82 WEST 1234 5698 7654 33",
                "GB82 WEST 1234 5698 7654 3",
                "GB82WEST12345698765432X",
                "XGB82WEST12345698765432",
                "GB82WEST12345698765432a",
                "ZZ82 WEST 1234 5698 7654 32",
                "GB82  WEST 1234 5698 7654 32",
                "gb82 west 1234 5698 7654 32",
                "GB82 WEST 1234 5698 7654 3X",
                "GB82 WEST 1234 "
            })
    void ignoresWhatIsNotAnIban(String text) {
        assertThat(found(Detector.IBAN, text)).isEmpty();
    }

    @Test
    void checksTheIbanRemainder() {
        assertThat(Patterns.mod97("GB82WEST12345698765432")).isEqualTo(1);
        assertThat(Patterns.mod97("NO9386011117947")).isEqualTo(1);
        for (String iban : List.of("GB82WEST12345698765433", "DE00370400440532013000", "ZZ99ABCDEFGHIJ0123456789")) {
            String digits = (iban.substring(4) + iban.substring(0, 4))
                    .chars()
                    .mapToObj(c -> Integer.toString(Character.digit(c, 36)))
                    .collect(Collectors.joining());
            assertThat(Patterns.mod97(iban))
                    .isEqualTo(
                            new BigInteger(digits).mod(BigInteger.valueOf(97)).intValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"AB 12 34 56 C", "AB123456C", "ZY 98 76 54 A", "JT123456D", "CE 00 00 00 B"})
    void findsUkNationalInsuranceNumbers(String number) {
        assertThat(found(Detector.NATIONAL_ID, "NI number " + number + ".")).containsExactly(number);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "BG123456C",
                "GB123456C",
                "NK123456C",
                "KN123456C",
                "TN123456C",
                "NT123456C",
                "ZZ123456C",
                "DA123456C",
                "AO123456C",
                "AB123456E",
                "ab123456c",
                "XAB123456C",
                "AB123456CX"
            })
    void ignoresWhatIsNotANationalInsuranceNumber(String text) {
        assertThat(found(Detector.NATIONAL_ID, text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"123-45-6789", "123 45 6789", "899-99-9999", "001-01-0001"})
    void findsUsSocialSecurityNumbers(String number) {
        assertThat(found(Detector.NATIONAL_ID, "SSN " + number + ".")).containsExactly(number);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "000-12-3456",
                "666-12-3456",
                "900-12-3456",
                "123-00-4567",
                "123-45-0000",
                "123456789",
                "123-45 6789",
                "123-45-6789-1",
                "1-123-45-6789",
                "123-45-6789.5"
            })
    void ignoresWhatIsNotASocialSecurityNumber(String text) {
        assertThat(found(Detector.NATIONAL_ID, text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "+44 20 7946 0958",
                "+442079460958",
                "+44 (0)20 7946 0958",
                "+1 (555) 123-4567",
                "+1-555-123-4567",
                "+33 1 23 45 67 89",
                "+49 30 12345678",
                "+44 1234 5678 90123",
                "(415) 555-2671",
                "415-555-2671",
                "415.555.2671",
                "020 7946 0958",
                "07700 900123",
                "030 12345678",
                "01 23 45 67 89"
            })
    void findsPhoneNumbers(String number) {
        assertThat(found(Detector.PHONE, "Call " + number + " today")).containsExactly(number);
    }

    @Test
    void trimsAnInternationalNumberThatRunsIntoOtherNumbers() {
        Redactor redactor = new Redactor(Set.of(), Set.of(Detector.PHONE));

        assertThat(redactor.redact("+1 415 555 2671 12345 times")).isEqualTo("[PHONE_1] 12345 times");
        assertThat(redactor.redact("+44 20 7946 0958 8.8.8.8")).isEqualTo("[PHONE_2] 8.8.8.8");
        assertThat(redactor.redact("+1 (415) 555 2671")).isEqualTo("[PHONE_3]");
        assertThat(redactor.redact("+44 20 7946 0958 and +33 1 23 45 67 89")).isEqualTo("[PHONE_2] and [PHONE_4]");
        assertThat(redactor.redact("+44 20 7946 0958 2a00:1450::200e")).isEqualTo("[PHONE_2] 2a00:1450::200e");
        assertThat(redactor.redact("+44 20 7946 0958x")).isEqualTo("[PHONE_5] 0958x");
        assertThat(found(Detector.PHONE, "+44.20.7946.0958.1234")).isEmpty();
        assertThat(found(Detector.PHONE, "+44.20.7946.0958.12")).containsExactly("+44.20.7946.0958.12");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2026-11-03",
                "12345678901",
                "+1234567",
                "+12 3",
                "+1234567890123456",
                "+44 20 7946x",
                "+44 20 7946_",
                "+12345678901234567",
                "a+44 20 7946 0958",
                "155-123-4567",
                "415-555-26718",
                "415-155-2671",
                "415-555-2671-8",
                "012 345 678",
                "0123 4567 89012",
                "1.2.3.4",
                "v415.555.2671"
            })
    void ignoresWhatIsNotAPhoneNumber(String text) {
        assertThat(found(Detector.PHONE, text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "8.8.8.8",
                "192.168.1.20",
                "10.0.0.1",
                "255.255.255.254",
                "2a00:1450:4009:81f::200e",
                "fe80::1",
                "2001:0db9:0:0:0:0:0:1",
                "1:2:3:4:5:6:7:8",
                "3fff:1000::1",
                "::abcd:1234",
                "abc::12",
                "1111:2:3:4:5:6::7"
            })
    void findsIpAddresses(String address) {
        assertThat(found(Detector.IP, "from " + address + ".")).containsExactly(address);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "127.0.0.1",
                "0.0.0.0",
                "255.255.255.255",
                "192.0.2.10",
                "198.51.100.7",
                "203.0.113.200",
                "1.2.3.256",
                "1.2.3.4.5",
                "x1.2.3.4",
                "1.2.3",
                "::1",
                "::",
                "0:0:0:0:0:0:0:1",
                "0:0:0:0:0:0:0:0",
                "2001:db8::1",
                "3fff::1",
                "10:30:45",
                "a[1::2]",
                "00:1a:2b:3c:4d:5e",
                "std::vector",
                "1::2::3",
                "12345::abcd:1",
                "::0000:0000:0001",
                "1:2:3:4:5:6:7:8:9",
                "1:2:3:4:5:6:7::8",
                "::ffff:",
                ":abc::1",
                "abcd:"
            })
    void ignoresWhatIsNotAPublicIpAddress(String text) {
        assertThat(found(Detector.IP, text)).isEmpty();
    }

    @Test
    void leavesTheIpv4PartOfAMappedAddressToTheIpv4Pattern() {
        assertThat(found(Detector.IP, "::ffff:192.168.1.1")).containsExactly("192.168.1.1");
    }

    @Test
    void leavesATrailingColonOutOfAnIpv6Address() {
        assertThat(found(Detector.IP, "Host 2a00:1450:4009:81f::200e: up")).containsExactly("2a00:1450:4009:81f::200e");
        assertThat(found(Detector.IP, "Host 2a00:1450::: up")).isEmpty();
        assertThat(found(Detector.IP, "Host fe80:: up")).isEmpty();
    }

    @Test
    void findsSecretsWithKnownPrefixes() {
        List<String> secrets = List.of(
                OPENAI_KEY,
                ANTHROPIC_KEY,
                "ghp_" + "A1b2".repeat(9),
                "github_pat_" + "11ABCDEFG0".repeat(3),
                "glpat-" + "xY7-".repeat(5),
                "xoxb-" + "1234567890-abc",
                "AKIA" + "IOSFODNN7EXAMPLE",
                "ASIA" + "IOSFODNN7EXAMPLE",
                "AIza" + "SyA-" + "1234567890".repeat(3) + "x",
                "tg_" + "k7Qm2xPa4bZr" + "_" + "a".repeat(43));
        for (String secret : secrets) {
            assertThat(found(Detector.SECRET, "key=" + secret + " end")).containsExactly(secret);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "sk-short",
                "task-12345678901234567890123",
                "ghp_short",
                "AKIA1234",
                "AKIAIOSFODNN7EXAMPLEX",
                "xAKIAIOSFODNN7EXAMPLE",
                "AIzaShort",
                "tg_k7Qm2xPa4bZr_short",
                "-----BEGIN PUBLIC KEY-----"
            })
    void ignoresWhatIsNotASecret(String text) {
        assertThat(found(Detector.SECRET, text)).isEmpty();
    }

    @Test
    void findsPrivateKeysWholeEvenWhenCutOff() {
        assertThat(found(Detector.SECRET, "Here:\n" + PRIVATE_KEY + "\nDone")).containsExactly(PRIVATE_KEY);
        String cutOff = "-----BEGIN " + "RSA PRIVATE KEY-----\nMIIEow";
        assertThat(found(Detector.SECRET, "Here: " + cutOff)).containsExactly(cutOff);
        String pgp = "-----BEGIN PGP " + "PRIVATE KEY BLOCK-----\nlQOYBF\n-----END PGP PRIVATE KEY BLOCK-----";
        assertThat(found(Detector.SECRET, pgp)).containsExactly(pgp);
        assertThat(found(Detector.SECRET, PRIVATE_KEY + " and " + PRIVATE_KEY))
                .containsExactly(PRIVATE_KEY, PRIVATE_KEY);
    }

    @Test
    void namesEachBuiltInDetector() {
        assertThat(Detector.builtIn("national-id")).contains(Detector.NATIONAL_ID);
        assertThat(Detector.builtIn("names")).isEmpty();
        for (Detector detector : Detector.BUILT_IN) {
            assertThat(Detector.builtIn(detector.configName())).contains(detector);
            assertThat(detector.description()).isNotBlank();
            assertThat(detector).hasToString(detector.configName());
        }
        assertThat(Detector.builtInNames()).isEqualTo("secret, email, card, iban, national-id, phone, ip");
        assertThat(Detector.NATIONAL_ID.label()).isEqualTo("NATIONAL_ID");
    }
}
