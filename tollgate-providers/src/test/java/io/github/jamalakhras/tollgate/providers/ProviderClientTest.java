package io.github.jamalakhras.tollgate.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.config.Provider;
import io.github.jamalakhras.tollgate.core.config.SecretRef;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProviderClientTest {

    private static final byte[] BODY = "{\"model\":\"qwen3:32b\"}".getBytes(StandardCharsets.UTF_8);

    private final FakeProvider fake = new FakeProvider();
    private final HttpClient http = ProviderClient.newHttpClient();
    private final ProviderClient client = new ProviderClient(http);

    @AfterEach
    void close() {
        fake.close();
        http.close();
    }

    private static @Nullable String noClientHeader(String name) {
        return null;
    }

    private static Provider provider(WireFormat format, String baseUrl, @Nullable SecretRef apiKey) {
        return new Provider(format, URI.create(baseUrl), apiKey, null, Duration.ofSeconds(5));
    }

    @Test
    void postsOpenAiRequestsWithABearerToken() {
        fake.respond(200, "{\"id\":\"chatcmpl-1\"}");

        ProviderResponse response = client.send(
                provider(WireFormat.OPENAI, fake.baseUrl() + "/v1", new SecretRef("KEY")),
                "sk-test",
                BODY,
                ProviderClientTest::noClientHeader);

        assertThat(response.status()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"chatcmpl-1\"}");
        assertThat(response.contentType()).isEqualTo("application/json");
        assertThat(response.retryAfter()).isEqualTo("7");
        FakeProvider.Received request = fake.received().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/chat/completions");
        assertThat(request.headers().get("Authorization")).containsExactly("Bearer sk-test");
        assertThat(request.headers().get("Content-type")).containsExactly("application/json");
        assertThat(request.body()).isEqualTo("{\"model\":\"qwen3:32b\"}");
    }

    @Test
    void postsAnthropicRequestsWithAnApiKeyHeader() {
        client.send(
                provider(WireFormat.ANTHROPIC, fake.baseUrl(), new SecretRef("KEY")),
                "sk-ant-test",
                BODY,
                ProviderClientTest::noClientHeader);

        FakeProvider.Received request = fake.received().getFirst();
        assertThat(request.path()).isEqualTo("/v1/messages");
        assertThat(request.headers().get("X-api-key")).containsExactly("sk-ant-test");
        assertThat(request.headers()).doesNotContainKey("Authorization");
    }

    @Test
    void passesTheClientsAnthropicVersionAndBetaFlags() {
        Map<String, String> clientHeaders = Map.of(
                "anthropic-version", "2024-01-01", "anthropic-beta", "prompt-caching-2024-07-31", "cookie", "secret");

        client.send(provider(WireFormat.ANTHROPIC, fake.baseUrl(), null), null, BODY, clientHeaders::get);

        FakeProvider.Received request = fake.received().getFirst();
        assertThat(request.headers().get("Anthropic-version")).containsExactly("2024-01-01");
        assertThat(request.headers().get("Anthropic-beta")).containsExactly("prompt-caching-2024-07-31");
        assertThat(request.headers()).doesNotContainKey("Cookie");
    }

    @Test
    void defaultsTheAnthropicVersion() {
        client.send(
                provider(WireFormat.ANTHROPIC, fake.baseUrl(), null), null, BODY, ProviderClientTest::noClientHeader);

        FakeProvider.Received request = fake.received().getFirst();
        assertThat(request.headers().get("Anthropic-version")).containsExactly("2023-06-01");
        assertThat(request.headers()).doesNotContainKey("Anthropic-beta");
    }

    @Test
    void neverForwardsClientHeadersToOpenAiProviders() {
        Map<String, String> clientHeaders = Map.of("anthropic-version", "2024-01-01");

        client.send(provider(WireFormat.OPENAI, fake.baseUrl(), null), null, BODY, clientHeaders::get);

        assertThat(fake.received().getFirst().headers()).doesNotContainKey("Anthropic-version");
    }

    @Test
    void sendsNoCredentialsWhenTheProviderHasNoKey() {
        client.send(provider(WireFormat.OPENAI, fake.baseUrl(), null), null, BODY, ProviderClientTest::noClientHeader);

        assertThat(fake.received().getFirst().headers()).doesNotContainKeys("Authorization", "X-api-key");
    }

    @Test
    void returnsProviderErrorsAsResponses() {
        fake.respond(429, "{\"error\":{\"message\":\"slow down\"}}");

        ProviderResponse response = client.send(
                provider(WireFormat.OPENAI, fake.baseUrl(), null), null, BODY, ProviderClientTest::noClientHeader);

        assertThat(response.status()).isEqualTo(429);
    }

    @Test
    void reportsATimeout() {
        fake.delay(Duration.ofSeconds(2));
        Provider slow = new Provider(WireFormat.OPENAI, URI.create(fake.baseUrl()), null, null, Duration.ofMillis(200));

        assertThatThrownBy(() -> client.send(slow, null, BODY, ProviderClientTest::noClientHeader))
                .isInstanceOfSatisfying(
                        ProviderUnavailableException.class,
                        e -> assertThat(e.timedOut()).isTrue())
                .hasMessage("The provider did not answer within PT0.2S");
    }

    @Test
    void reportsAnUnreachableProvider() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Provider unreachable = provider(WireFormat.OPENAI, "http://127.0.0.1:" + closedPort, null);

        assertThatThrownBy(() -> client.send(unreachable, null, BODY, ProviderClientTest::noClientHeader))
                .isInstanceOfSatisfying(
                        ProviderUnavailableException.class,
                        e -> assertThat(e.timedOut()).isFalse())
                .hasMessage("The provider could not be reached");
    }
}
