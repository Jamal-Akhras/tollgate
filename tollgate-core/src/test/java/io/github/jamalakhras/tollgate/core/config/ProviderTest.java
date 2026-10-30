package io.github.jamalakhras.tollgate.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderTest {

    private static final SecretRef KEY = new SecretRef("PROVIDER_KEY");

    private static Provider provider(String baseUrl, @Nullable SecretRef apiKey) {
        return new Provider(WireFormat.OPENAI, URI.create(baseUrl), apiKey, null, Duration.ofSeconds(5));
    }

    @Test
    void joinsEndpointPathsOntoTheBaseUrl() {
        assertThat(provider("http://gpu-box:11434/v1/", null).endpoint("/chat/completions"))
                .isEqualTo(URI.create("http://gpu-box:11434/v1/chat/completions"));
        assertThat(provider("https://api.anthropic.com", KEY).endpoint("/v1/messages"))
                .isEqualTo(URI.create("https://api.anthropic.com/v1/messages"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:11434/v1", "http://127.0.0.1:8000/v1", "http://[::1]:1234/v1"})
    void allowsKeysOverPlainHttpOnlyToThisMachine(String baseUrl) {
        assertThat(provider(baseUrl, KEY).apiKey()).isEqualTo(KEY);
        assertThatThrownBy(() -> provider("http://gpu-box:11434/v1", KEY))
                .hasMessage("an API key may only be sent over https, except to this machine");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://api.example.com/v1?key=1",
                "https://api.example.com/v1#top",
                "https://user:secret@api.example.com/v1"
            })
    void rejectsBaseUrlsThatCarryMoreThanALocation(String baseUrl) {
        assertThatThrownBy(() -> provider(baseUrl, null))
                .hasMessage("baseUrl must not contain credentials, a query or a fragment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1", "https:relative", "file:///etc/passwd"})
    void requiresAnAbsoluteHttpUrl(String baseUrl) {
        assertThatThrownBy(() -> provider(baseUrl, null)).hasMessageStartingWith("baseUrl must be an absolute");
    }

    @Test
    void requiresAPositiveTimeout() {
        URI url = URI.create("https://api.example.com");
        assertThatThrownBy(() -> new Provider(WireFormat.OPENAI, url, null, null, Duration.ofSeconds(-1)))
                .hasMessage("timeout must be positive");
    }
}
