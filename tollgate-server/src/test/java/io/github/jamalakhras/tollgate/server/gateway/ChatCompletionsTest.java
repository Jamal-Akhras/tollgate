package io.github.jamalakhras.tollgate.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.providers.FakeProvider;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ChatCompletionsTest extends GatewayTestSupport {

    private static final String QWEN = "{\"model\":\"qwen3\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";

    private HttpResponse<String> chat(String body, String... headers) throws IOException, InterruptedException {
        return post(Endpoints.OPENAI_CHAT, body, headers);
    }

    private HttpResponse<String> chatWithKey(String body) throws IOException, InterruptedException {
        return chat(body, "Authorization", "Bearer " + KEY.value());
    }

    @Test
    void forwardsTheRequestWithTheUpstreamModelAndTheProviderKey() throws Exception {
        PROVIDER.respond(200, "{\"id\":\"chatcmpl-1\",\"choices\":[]}");

        HttpResponse<String> response = chatWithKey(QWEN);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"id\":\"chatcmpl-1\",\"choices\":[]}");
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(response.headers().firstValue(TraceIdFilter.HEADER))
                .hasValueSatisfying(id -> assertThat(id).hasSize(36));

        FakeProvider.Received forwarded = PROVIDER.received().getFirst();
        assertThat(forwarded.path()).isEqualTo("/v1/chat/completions");
        assertThat(forwarded.headers().get("Authorization")).containsExactly("Bearer sk-provider");
        assertThat(forwarded.body())
                .isEqualTo("{\"model\":\"qwen3:32b\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}],"
                        + "\"max_tokens\":256}");
    }

    @Test
    void acceptsTheKeyInTheAnthropicStyleHeaderToo() throws Exception {
        assertThat(chat(QWEN, "x-api-key", KEY.value()).statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsMissingAndUnknownKeysWithoutCallingTheProvider() throws Exception {
        HttpResponse<String> missing = chat(QWEN);
        HttpResponse<String> unknown = chat(
                QWEN,
                "Authorization",
                "Bearer " + ApiKey.generate(new SecureRandom()).value());

        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(missing.body())
                .isEqualTo("{\"error\":{\"message\":\"The Tollgate API key is missing or not valid\","
                        + "\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"invalid_api_key\"}}");
        assertThat(missing.headers().firstValue(TraceIdFilter.HEADER)).isPresent();
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(PROVIDER.received()).isEmpty();
    }

    @Test
    void hidesModelsTheTenantMayNotUse() throws Exception {
        HttpResponse<String> response = chatWithKey("{\"model\":\"llama\",\"messages\":[]}");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"model_not_found\"", "\"param\":\"model\"");
        assertThat(PROVIDER.received()).isEmpty();
    }

    @Test
    void pointsToTheRightEndpointForAnotherFormat() throws Exception {
        HttpResponse<String> response = chatWithKey("{\"model\":\"claude-sonnet\",\"messages\":[]}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .contains("The model 'claude-sonnet' is served on /anthropic/v1/messages", "wrong_endpoint");
    }

    @Test
    void rejectsStreamingForNow() throws Exception {
        HttpResponse<String> response = chatWithKey("{\"model\":\"qwen3\",\"stream\":true,\"messages\":[]}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"unsupported_parameter\"", "\"param\":\"stream\"");
    }

    @Test
    void rejectsBodiesThatAreNotJsonObjects() throws Exception {
        HttpResponse<String> response = chatWithKey("[1, 2]");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("The request body must be a JSON object", "invalid_request");
    }

    @Test
    void relaysProviderErrorsUnchanged() throws Exception {
        PROVIDER.respond(429, "{\"error\":{\"message\":\"Rate limit reached\"}}");

        HttpResponse<String> response = chatWithKey(QWEN);

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).isEqualTo("{\"error\":{\"message\":\"Rate limit reached\"}}");
        assertThat(response.headers().firstValue("Retry-After")).contains("7");
    }

    @Test
    void doesNotBlameTheCallerWhenTheProviderRejectsTollgatesKey() throws Exception {
        PROVIDER.respond(401, "{\"error\":{\"message\":\"Incorrect API key provided: sk-pr***der\"}}");

        HttpResponse<String> response = chatWithKey(QWEN);

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body())
                .contains("\"code\":\"provider_auth_failed\"")
                .doesNotContain("sk-pr");
    }

    @Test
    void reportsAnUnreachableProvider() throws Exception {
        HttpResponse<String> response = chatWithKey("{\"model\":\"offline-model\",\"messages\":[]}");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("\"type\":\"server_error\"", "\"code\":\"provider_unavailable\"");
    }

    @Test
    void reportsAProviderTimeout() throws Exception {
        PROVIDER.delay(Duration.ofSeconds(2));

        HttpResponse<String> response = chatWithKey("{\"model\":\"slow-model\",\"messages\":[]}");

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.body()).contains("\"code\":\"provider_timeout\"");
    }

    @Test
    void timesOutAResponseBodyThatArrivesTooSlowly() throws Exception {
        PROVIDER.respond(200, "{\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");
        PROVIDER.drip(Duration.ofMillis(50));

        HttpResponse<String> response = chatWithKey("{\"model\":\"slow-model\",\"messages\":[]}");

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.body()).contains("\"code\":\"provider_timeout\"");
    }

    @Test
    void refusesAProviderResponseOverTheLimitAndRecordsIt() throws Exception {
        PROVIDER.respond(200, "{\"pad\":\"" + "x".repeat(17_000) + "\"}");

        HttpResponse<String> response = chatWithKey("{\"model\":\"qwen3\",\"messages\":[]}");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body())
                .contains("\"code\":\"provider_invalid_response\"")
                .contains("larger than 16384 bytes");
        Outcome outcome = (Outcome) auditRecordsOf(response).get(1);
        assertThat(outcome.result()).isEqualTo(Outcome.Result.INVALID_RESPONSE);
    }

    @Test
    void refusesBodiesOverTheSizeLimitWithoutCallingTheProvider() throws Exception {
        String content = "x".repeat(1_024);

        HttpResponse<String> response =
                chatWithKey("{\"model\":\"qwen3\",\"messages\":[{\"role\":\"user\",\"content\":\"" + content + "\"}]}");

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("The request body is larger than the limit of 1024 bytes");
        assertThat(PROVIDER.received()).isEmpty();
    }
}
