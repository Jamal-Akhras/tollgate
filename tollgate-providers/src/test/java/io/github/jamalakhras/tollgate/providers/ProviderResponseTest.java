package io.github.jamalakhras.tollgate.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderResponseTest {

    private static ProviderResponse response(String body) {
        return new ProviderResponse(200, body.getBytes(StandardCharsets.UTF_8), "text/html", "3");
    }

    private static String text(ProviderResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    @Test
    void replacesTheModelWithTheNameTheClientUsed() {
        ProviderResponse rebuilt = response("{\"id\":\"1\",\"model\":\"qwen3:32b\",\"usage\":{\"x\":1.50}}")
                .withModel("qwen3");

        assertThat(text(rebuilt)).isEqualTo("{\"id\":\"1\",\"model\":\"qwen3\",\"usage\":{\"x\":1.50}}");
        assertThat(rebuilt.status()).isEqualTo(200);
        assertThat(rebuilt.retryAfter()).isEqualTo("3");
        assertThat(text(response("{\"id\":\"1\"}").withModel("qwen3"))).isEqualTo("{\"id\":\"1\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"<html></html>", "[1,2]", "\"text\"", ""})
    void refusesABodyThatIsNotAJsonObject(String body) {
        assertThatThrownBy(() -> response(body).withModel("qwen3"))
                .isInstanceOf(InvalidProviderResponseException.class)
                .hasMessageStartingWith("The provider's response was not");
    }

    @Test
    void readsTheErrorMessage() {
        assertThat(response("{\"error\":{\"message\":\"Bad temperature\"}}").errorMessage())
                .contains("Bad temperature");
        assertThat(response("{\"error\":{\"message\":\" \"}}").errorMessage()).isEmpty();
        assertThat(response("{\"error\":\"plain\"}").errorMessage()).isEmpty();
        assertThat(response("not json").errorMessage()).isEmpty();
    }
}
