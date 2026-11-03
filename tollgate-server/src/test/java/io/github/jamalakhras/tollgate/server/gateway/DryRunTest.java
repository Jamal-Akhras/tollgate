package io.github.jamalakhras.tollgate.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class DryRunTest extends GatewayTestSupport {

    private HttpResponse<String> dryRun(String body, String key) throws Exception {
        return post(Endpoints.OPENAI_CHAT, body, "Authorization", "Bearer " + key, Gateway.DRY_RUN_HEADER, "true");
    }

    @Test
    void estimatesACallWithoutSendingIt() throws Exception {
        HttpResponse<String> response = dryRun(
                "{\"model\":\"thinking-model\",\"max_tokens\":100,\"stream\":true,\"messages\":[]}", CODER_KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(Gateway.COST_MAX_HEADER)).contains("0.0004 USD");
        assertThat(response.body())
                .contains(
                        "\"object\":\"tollgate.estimate\"",
                        "\"model\":\"thinking-model\"",
                        "\"outputTokens\":100",
                        "\"maxCost\":\"0.0004 USD\"",
                        "\"price\":\"config\"");
        assertThat(PROVIDER.received()).isEmpty();
        List<AuditRecord> records = auditRecordsOf(response);
        assertThat(records.getFirst()).isInstanceOfSatisfying(Intent.class, intent -> {
            assertThat(intent.allowed()).isTrue();
            assertThat(intent.reservation()).isNull();
        });
        assertThat(records.get(1)).isInstanceOfSatisfying(Outcome.class, outcome -> {
            assertThat(outcome.result()).isEqualTo(Outcome.Result.DRY_RUN);
            assertThat(outcome.status()).isNull();
            assertThat(outcome.cost()).hasToString("0 USD");
        });
    }

    @Test
    void checksTheBudgetWithoutSpendingIt() throws Exception {
        String request = "{\"model\":\"frugal-model\",\"messages\":[]}";

        HttpResponse<String> first = dryRun(request, PLANNER_KEY.value());
        HttpResponse<String> second = dryRun(request, PLANNER_KEY.value());
        HttpResponse<String> tooMany =
                dryRun("{\"model\":\"frugal-model\",\"n\":2,\"messages\":[]}", PLANNER_KEY.value());

        for (HttpResponse<String> response : List.of(first, second)) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue(Gateway.OUTPUT_LIMITED_HEADER))
                    .contains("500");
            assertThat(response.body()).contains("\"outputTokens\":500");
        }
        assertThat(tooMany.statusCode()).isEqualTo(429);
        assertThat(PROVIDER.received()).isEmpty();
    }

    @Test
    void refusesADryRunFromATenantThatCannotSeeCosts() throws Exception {
        HttpResponse<String> response = dryRun("{\"model\":\"qwen3\",\"messages\":[]}", KEY.value());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("Dry runs are only for tenants with showCosts: true");
        assertThat(PROVIDER.received()).isEmpty();
    }
}
