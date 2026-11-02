package io.github.jamalakhras.tollgate.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.providers.FakeProvider;
import java.io.IOException;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class MessagesTest extends GatewayTestSupport {

    private static final String CLAUDE =
            "{\"model\":\"claude-sonnet\",\"max_tokens\":64,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";

    private HttpResponse<String> messages(String body, String... headers) throws IOException, InterruptedException {
        return post(Endpoints.ANTHROPIC_MESSAGES, body, headers);
    }

    @Test
    void forwardsAnthropicRequestsWithTheProviderKeyAndTheClientsVersion() throws Exception {
        PROVIDER.respond(200, "{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[]}");

        HttpResponse<String> response = messages(CLAUDE, "x-api-key", KEY.value(), "anthropic-version", "2023-06-01");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[]}");
        assertThat(response.headers().firstValue(TraceIdFilter.HEADER)).isPresent();

        FakeProvider.Received forwarded = PROVIDER.received().getFirst();
        assertThat(forwarded.path()).isEqualTo("/v1/messages");
        assertThat(forwarded.headers().get("X-api-key")).containsExactly("sk-provider");
        assertThat(forwarded.headers().get("Anthropic-version")).containsExactly("2023-06-01");
        assertThat(forwarded.headers()).doesNotContainKey("Anthropic-beta");
        assertThat(forwarded.body()).startsWith("{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":64,");
    }

    @Test
    void answersInTheAnthropicErrorFormat() throws Exception {
        HttpResponse<String> response = messages(CLAUDE, "x-api-key", "sk-ant-not-a-tollgate-key");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body())
                .isEqualTo("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                        + "\"message\":\"The Tollgate API key is missing or not valid\"}}");
    }

    @Test
    void pointsOpenAiModelsToTheChatCompletionsEndpoint() throws Exception {
        HttpResponse<String> response =
                messages("{\"model\":\"qwen3\",\"max_tokens\":64,\"messages\":[]}", "x-api-key", KEY.value());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"type\":\"invalid_request_error\"", "is served on /v1/chat/completions");
        assertThat(PROVIDER.received()).isEmpty();
    }

    @Test
    void reportsUnknownModelsAsNotFound() throws Exception {
        HttpResponse<String> response =
                messages("{\"model\":\"llama\",\"max_tokens\":64,\"messages\":[]}", "x-api-key", KEY.value());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"type\":\"not_found_error\"");
    }

    @Test
    void reportsOversizedRequestsInTheAnthropicFormat() throws Exception {
        String content = "x".repeat(1_024);

        HttpResponse<String> response = messages(
                "{\"model\":\"claude-sonnet\",\"messages\":[{\"role\":\"user\",\"content\":\"" + content + "\"}]}",
                "x-api-key",
                KEY.value());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"type\":\"request_too_large\"");
    }

    @Test
    void reportsProviderOutagesAsApiErrors() throws Exception {
        PROVIDER.respond(403, "{}");

        HttpResponse<String> response = messages(CLAUDE, "x-api-key", KEY.value());

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("\"type\":\"api_error\"");
    }
}
