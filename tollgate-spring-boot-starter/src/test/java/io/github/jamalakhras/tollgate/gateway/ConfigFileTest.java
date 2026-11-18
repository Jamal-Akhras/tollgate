package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.config.AuditSettings;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigFileTest {

    private static String init(String publicKey) {
        return """
            dataClasses: [public, internal, confidential]

            providers:
              ollama: { format: openai, baseUrl: "http://localhost:11434/v1", region: local }

            models:
              qwen3:
                provider: ollama
                upstream: "qwen3:8b"
                price: free
                maxOutputTokens: { default: 1024, cap: 4096 }

            tenants:
              %1$s:
                models: [qwen3]
                dataClass: internal

            keys:
              - { id: %2$s, tenant: %1$s, sha256: %3$s }

            policies:
              default:
                guards:
                  - { type: request-size, maxBytes: 1048576 }
                  - type: data-class
                    regions: { public: [local], internal: [local], confidential: [local] }
                features:
                  remote-images: [public, internal]
                redact:
                  card: [public, internal, confidential]
                refuse:
                  secret: [public, internal, confidential]

            audit:
              mode: sync
              directory: audit
              publicKey: %4$s
              signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
              hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
            """.formatted("support-bot", "abcdef123456", "a".repeat(64), publicKey);
    }

    private static final String FLOW_STYLE = """
            currency: USD
            providers:
              ollama: { format: openai, baseUrl: "http://gpu-box:11434/v1" }
            models:
              qwen3:
                provider: ollama
                upstream: "qwen3:32b"
                price: { input: 0.000001, output: 0.40, cacheRead: 0.10, cacheWrite: 0.10 }
                fallback: [backup]
                maxOutputTokens: { default: 1024, cap: 4096 }
              backup:
                provider: ollama
                upstream: "qwen3:8b"
                price: free
                fallback: []
                maxOutputTokens: { default: 1024, cap: 4096 }
            tenants:
              support-bot:
                models: [qwen3, backup]
                showCosts: true
                budget: { limit: 5.00, period: monthly }
            policies:
              default:
                guards: [{ type: request-size, maxBytes: 1048576 }]
            detectors:
              codename: { terms: ["blue falcon", "qwen3:8b"] }
              project: { pattern: "PRJ-[0-9]+" }
            audit: { mode: off }
            """;

    private static final String PINNED_INPUT = """
            tenants:
              support-bot: { models: [qwen3], dataClass: internal }
              billing-bot: { models: [qwen3, gpt] }
            keys:
              - { id: k1, tenant: support-bot, sha256: abc123 }
            policies:
              default:
                guards:
                  - { type: request-size, maxBytes: 1048576 }
                  - { type: data-class, regions: { public: [local] } }
            budgets:
              support-bot: { amount: 5.00, period: monthly }
            tags: [one, "two words"]
            extras: []
            """;

    private static final String PINNED = """
            # Rewritten by Tollgate's admin API. Comments are not kept. The file as it was is beside this one, with .original added to its name.
            tenants:
              support-bot:
                models:
                  - "qwen3"
                dataClass: "internal"
              billing-bot:
                models:
                  - "qwen3"
                  - "gpt"
            keys:
              - id: "k1"
                tenant: "support-bot"
                sha256: "abc123"
            policies:
              default:
                guards:
                  - type: "request-size"
                    maxBytes: 1048576
                  - type: "data-class"
                    regions:
                      public:
                        - "local"
            budgets:
              support-bot:
                amount: 5.00
                period: "monthly"
            tags:
              - "one"
              - "two words"
            extras: []
            """;

    @TempDir
    private Path directory;

    private Path write(String yaml) throws IOException {
        return Files.writeString(directory.resolve("tollgate.yaml"), yaml);
    }

    private static List<byte[]> samples() throws IOException {
        var keys = AuditKeys.generate(new SecureRandom());
        return List.of(
                Files.readAllBytes(Path.of("src/test/resources/tollgate.yaml")),
                init(keys.publicKey()).getBytes(StandardCharsets.UTF_8),
                FLOW_STYLE.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsAConfigFile() throws IOException {
        Path file = write("""
                providers:
                  ollama: { format: openai, baseUrl: "http://gpu-box:11434/v1" }
                models:
                  qwen3:
                    provider: ollama
                    upstream: "qwen3:32b"
                    price: { input: 0.10, output: 0.40, cacheRead: 0.10, cacheWrite: 0.10 }
                tenants:
                  support-bot: { models: [qwen3] }
                audit: { mode: off }
                """);

        var route = ConfigFile.read(file).config().route("support-bot", "qwen3").orElseThrow();
        assertThat(route.definition().price().rates().output().perMillionTokens())
                .hasToString("0.4 USD");
    }

    @Test
    void asksAnEmptyFileForTheAuditSettings() throws IOException {
        Path file = write("");

        assertThatThrownBy(() -> ConfigFile.read(file)).hasMessage(file + ": audit is required");
    }

    @Test
    void readsOffAsAWordRatherThanFalse() throws IOException {
        assertThat(ConfigFile.read(write("audit: { mode: off }")).config().audit())
                .isEqualTo(AuditSettings.DISABLED);
    }

    @Test
    void explainsHowToPointAtAMissingFile() {
        assertThatThrownBy(() -> ConfigFile.read(directory.resolve("missing.yaml")))
                .hasMessageStartingWith("No Tollgate configuration at")
                .hasMessageEndingWith("set tollgate.config or TOLLGATE_CONFIG to its location");
    }

    @Test
    void rejectsDuplicateKeys() throws IOException {
        Path file = write("""
                tenants:
                  support-bot: { models: [] }
                  support-bot: { models: [] }
                """);

        assertThatThrownBy(() -> ConfigFile.read(file))
                .hasMessageContaining("is not valid YAML")
                .hasMessageContaining("support-bot");
    }

    @Test
    void namesTheFileAndTheSettingThatIsWrong() throws IOException {
        Path file = write("tenants:\n  support-bot: { modles: [] }\n");

        assertThatThrownBy(() -> ConfigFile.read(file))
                .hasMessage(file + ": tenants.support-bot.modles is not a recognised setting");
    }

    @Test
    void aRewrittenFileReadsBackAsTheSameDocument() throws IOException {
        for (byte[] sample : samples()) {
            var document = ConfigFile.document(sample);
            byte[] rewritten = ConfigFile.write(document);

            assertThat(ConfigFile.document(rewritten)).isEqualTo(document);
            assertThat(ConfigFile.parse(directory.resolve("tollgate.yaml"), rewritten))
                    .isNotNull();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "qwen3:8b",
                "${env:OPENAI_API_KEY}",
                "123456789012",
                "0123",
                "1e5",
                ".5",
                "0x1F",
                "1_000",
                "2026-11-16",
                "true",
                "no",
                "null",
                "~",
                "-",
                " leading",
                "trailing ",
                "a: b",
                "# not a comment",
                "it's",
                "say \"hi\"",
                "back\\slash",
                "two\nlines",
                "über",
                "[x]",
                "{x}",
                "*a",
                "&a",
                "!t",
                "%p",
                "@h"
            })
    void textThatLooksLikeSomethingElseStaysText(String term) {
        Map<String, Object> document = Map.of("detectors", Map.of("d", Map.of("terms", List.of(term))));

        assertThat(ConfigFile.document(ConfigFile.write(document))).isEqualTo(document);
    }

    @Test
    void numbersKeepTheirValueAndScale() {
        Map<String, Object> document = Map.of(
                "a", new BigDecimal("5.00"), "b", new BigDecimal("0.000001"), "c", 1048576, "d", 9007199254740993L);

        assertThat(ConfigFile.document(ConfigFile.write(document))).isEqualTo(document);
    }

    @Test
    void writingTheSameDocumentTwiceGivesTheSameBytes() throws IOException {
        for (byte[] sample : samples()) {
            var document = ConfigFile.document(sample);

            assertThat(ConfigFile.write(document)).isEqualTo(ConfigFile.write(document));
        }
    }

    @Test
    void theLayoutIsPinned() {
        var document = ConfigFile.document(PINNED_INPUT.getBytes(StandardCharsets.UTF_8));

        assertThat(new String(ConfigFile.write(document), StandardCharsets.UTF_8))
                .isEqualTo(PINNED);
    }

    @Test
    void theRewrittenFileStartsWithTheNote() {
        String text = new String(ConfigFile.write(Map.of()), StandardCharsets.UTF_8);

        assertThat(text)
                .startsWith("# Rewritten by Tollgate's admin API. Comments are not kept. "
                        + "The file as it was is beside this one, with .original added to its name.\n");
    }

    @Test
    void originalIsBesideTheConfig() {
        assertThat(ConfigFile.original(Path.of("conf", "tollgate.yaml")))
                .isEqualTo(Path.of("conf", "tollgate.yaml.original"));
    }
}
