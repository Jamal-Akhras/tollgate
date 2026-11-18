package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class MetricsTest extends GatewayTestSupport {

    private String scrape() throws Exception {
        HttpResponse<String> response = management("/actuator/prometheus");
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    @Test
    void countsCallsTokensAndCostForPrometheus() throws Exception {
        PROVIDER.respond(
                200,
                "{\"id\":\"c\",\"model\":\"qwen3:32b\",\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":40,\"completion_tokens\":2}}");
        HttpResponse<String> answered = chat(KEY, "{\"model\":\"qwen3\",\"messages\":[]}");
        assertThat(answered.statusCode()).isEqualTo(200);
        HttpResponse<String> refused =
                chat(KEY, "{\"model\":\"qwen3\",\"messages\":[]}", Gateway.DATA_CLASS_HEADER, "public");
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
                .contains("tollgate_providers_in_flight{provider=\"ollama\"} 0.0")
                .containsPattern("gen_ai_client_operation_duration_seconds_bucket\\{model=\"qwen3\","
                        + "provider=\"ollama\",result=\"responded\",le=\"[0-9.]+\"}");
    }
}
