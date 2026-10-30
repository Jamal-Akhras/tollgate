package io.github.jamalakhras.tollgate.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecretRefTest {

    @Test
    void resolvesFromTheEnvironment() {
        SecretRef ref = SecretRef.parse("${env:OPENAI_API_KEY}");
        assertThat(ref.resolve(Map.of("OPENAI_API_KEY", "sk-test")::get)).isEqualTo("sk-test");
    }

    @Test
    void failsWhenTheVariableIsMissingOrBlank() {
        SecretRef ref = new SecretRef("OPENAI_API_KEY");
        assertThatThrownBy(() -> ref.resolve(Map.<String, String>of()::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Environment variable OPENAI_API_KEY is not set");
        assertThatThrownBy(() -> ref.resolve(Map.of("OPENAI_API_KEY", " ")::get))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sk-live-123", "${OPENAI_API_KEY}", "${env:}", "${env:1KEY}", "${vault:x}"})
    void acceptsOnlyEnvironmentReferences(String reference) {
        assertThatThrownBy(() -> SecretRef.parse(reference)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void printsAsTheReferenceNotTheValue() {
        assertThat(new SecretRef("OPENAI_API_KEY")).hasToString("${env:OPENAI_API_KEY}");
        assertThatThrownBy(() -> new SecretRef("not valid")).isInstanceOf(IllegalArgumentException.class);
    }
}
