package io.github.jamalakhras.tollgate.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;

class MetricsTest extends GatewayTestSupport {

    @LocalManagementPort
    private int managementPort;

    private String scrape() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    @Test
    void countsCallsTokensAndCostForPrometheus() throws Exception {
        PROVIDER.respond(
                200,
                "{\"id\":\"c\",\"model\":\"qwen3:32b\",\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":40,\"completion_tokens\":2}}");
        HttpResponse<String> answered = post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"qwen3\",\"messages\":[]}",
                "Authorization",
                "Bearer " + KEY.value());
        assertThat(answered.statusCode()).isEqualTo(200);
        HttpResponse<String> refused = post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"qwen3\",\"messages\":[]}",
                "Authorization",
                "Bearer " + KEY.value(),
                Gateway.DATA_CLASS_HEADER,
                "public");
        assertThat(refused.statusCode()).isEqualTo(403);

        String metrics = scrape();

        assertThat(metrics)
                .containsPattern("tollgate_requests_total\\{model=\"qwen3\",provider=\"ollama\","
                        + "result=\"responded\",tenant=\"support-bot\"} [1-9]")
                .containsPattern("tollgate_requests_total\\{model=\"qwen3\",provider=\"ollama\","
                        + "result=\"policy_denied\",tenant=\"support-bot\"} [1-9]")
                .containsPattern("gen_ai_client_token_usage_sum\\{gen_ai_token_type=\"input\",model=\"qwen3\","
                        + "provider=\"ollama\",tenant=\"support-bot\"} [1-9]")
                .containsPattern("tollgate_cost_total\\{currency=\"USD\",model=\"qwen3\",tenant=\"support-bot\"} ")
                .containsPattern("gen_ai_client_operation_duration_seconds_count\\{model=\"qwen3\","
                        + "provider=\"ollama\",result=\"responded\"} [1-9]")
                .containsPattern("tollgate_budget_spent\\{currency=\"USD\",tenant=\"metered\"} ")
                .containsPattern("tollgate_budget_available\\{currency=\"USD\",tenant=\"metered\"} ")
                .contains("tollgate_providers_in_flight{provider=\"ollama\"} 0.0");
    }
}
