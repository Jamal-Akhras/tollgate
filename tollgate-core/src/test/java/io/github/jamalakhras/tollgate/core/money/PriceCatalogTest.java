package io.github.jamalakhras.tollgate.core.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

class PriceCatalogTest {

    private static PriceCatalog read(String text) throws IOException {
        return PriceCatalog.read(new BufferedReader(new StringReader(text)));
    }

    @Test
    void readsBasePricesAndTheirLongContextTiers() throws IOException {
        PriceCatalog catalog = read("""
                # models.dev 2026-11-03 abc123
                alibaba\tqwen-plus\t0\t0.4\t1.2\t4\t0.08\t0.5
                alibaba\tqwen-plus\t256000\t1.2\t3.6\t12\t0.24\t1.5
                anthropic\tclaude-opus-5-5\t0\t4\t20\t20\t0.2\t5
                """);

        assertThat(catalog.version()).isEqualTo("models.dev 2026-11-03 abc123");
        ModelPrice qwen = catalog.price("alibaba", "qwen-plus").orElseThrow();
        assertThat(qwen.rates().reasoning().perMillionTokens()).isEqualTo(Prices.usd("4"));
        assertThat(qwen.longContext())
                .singleElement()
                .satisfies(tier -> assertThat(tier.fromTokens()).isEqualTo(256_000));
        assertThat(catalog.price("anthropic", "claude-opus-5-5").orElseThrow().longContext())
                .isEmpty();
        assertThat(catalog.price("anthropic", "qwen-plus")).isEmpty();
        assertThat(catalog.price("mistral", "qwen-plus")).isEmpty();
    }

    @Test
    void rejectsACatalogItCannotTrust() {
        assertThatThrownBy(() -> read("alibaba\tqwen-plus\t0\t1\t1\t1\t1\t1\n"))
                .hasMessage("The price catalog has no version line");
        assertThatThrownBy(() -> read("# v\nalibaba\tqwen-plus\t0\t1\t1\t1\n"))
                .hasMessage("Line 2 of the price catalog has 6 fields, not 8");
        assertThatThrownBy(() -> read("# v\nalibaba\tqwen-plus\t0\t1\tfree\t1\t1\t1\n"))
                .hasMessage("Line 2 of the price catalog is not valid");
        assertThatThrownBy(() -> read("# v\nalibaba\tqwen-plus\t0\t-1\t1\t1\t1\t1\n"))
                .hasMessage("Line 2 of the price catalog is not valid");
        assertThatThrownBy(() -> read("# v\nalibaba\tqwen-plus\t32000\t1\t1\t1\t1\t1\n"))
                .hasMessage("Line 2 of the price catalog has no base price");
        assertThatThrownBy(() ->
                        read("# v\nalibaba\tqwen-plus\t0\t1\t1\t1\t1\t1\nalibaba\tqwen-max\t32000\t1\t1\t1\t1\t1\n"))
                .hasMessage("Line 3 of the price catalog has no base price");
    }

    @Test
    void shipsTheCatalogInsideTheJar() {
        PriceCatalog catalog = PriceCatalog.bundled();

        assertThat(catalog.version()).startsWith("models.dev ");
        assertThat(catalog.price("anthropic", "claude-opus-5-5")).isPresent();
        assertThat(catalog.price("openai", "gpt-6.1-sol").orElseThrow().longContext())
                .isNotEmpty();
    }
}
