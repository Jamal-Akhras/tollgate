package io.github.jamalakhras.tollgate.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import java.io.IOException;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class BudgetTest extends GatewayTestSupport {

    private static final String METERED = "{\"model\":\"metered-model\",\"messages\":[]}";
    private static final String USAGE = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";

    private HttpResponse<String> chat() throws IOException, InterruptedException {
        return post(Endpoints.OPENAI_CHAT, METERED, "Authorization", "Bearer " + METERED_KEY.value());
    }

    @Test
    void chargesWhatWasUsedAndStopsWhenTheBudgetCannotCoverTheNextCall() throws Exception {
        PROVIDER.respond(200, USAGE);
        assertThat(chat().statusCode()).isEqualTo(200);
        assertThat(PROVIDER.received().getFirst().body())
                .isEqualTo("{\"model\":\"qwen3:32b\",\"messages\":[],\"max_tokens\":1000}");

        PROVIDER.respond(500, "{\"error\":{\"message\":\"overloaded\"}}");
        assertThat(chat().statusCode()).isEqualTo(500);

        PROVIDER.respond(200, "{\"choices\":[]}");
        for (int call = 0; call < 3; call++) {
            assertThat(chat().statusCode()).isEqualTo(200);
        }
        int callsSoFar = PROVIDER.received().size();

        HttpResponse<String> refused = chat();

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(refused.body())
                .contains(
                        "\"type\":\"insufficient_quota\"",
                        "\"code\":\"budget_exceeded\"",
                        "which this tenant's total budget of 0.01 USD cannot cover");
        assertThat(PROVIDER.received()).hasSize(callsSoFar);
        assertThat(auditRecordsOf(refused))
                .singleElement()
                .isInstanceOfSatisfying(
                        Intent.class,
                        intent -> assertThat(intent.decision())
                                .isInstanceOfSatisfying(
                                        Decision.Deny.class,
                                        deny -> assertThat(deny.refusal()).isEqualTo(Refusal.BUDGET_EXCEEDED)));
    }
}
