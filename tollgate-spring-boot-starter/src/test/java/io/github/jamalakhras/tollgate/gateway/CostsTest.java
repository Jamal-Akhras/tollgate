package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.Outcome;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class CostsTest extends GatewayTestSupport {

    private static final String THINKING = """
            {"choices":[],"usage":{"prompt_tokens":0,"completion_tokens":1000,
             "completion_tokens_details":{"reasoning_tokens":250}}}""";

    private HttpResponse<String> chat(String model, String extra, String key) throws Exception {
        return post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"" + model + "\"," + extra + "\"messages\":[]}",
                "Authorization",
                "Bearer " + key);
    }

    @Test
    void showsTheMostACallCouldCostAndWhatItWasCharged() throws Exception {
        PROVIDER.respond(200, THINKING);

        HttpResponse<String> response = chat("thinking-model", "\"max_tokens\":100,", CODER_KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(Gateway.COST_MAX_HEADER)).contains("0.0004 USD");
        assertThat(response.headers().firstValue(Gateway.COST_HEADER)).contains("0.00175 USD");
        Outcome outcome = (Outcome) auditRecordsOf(response).get(1);
        assertThat(outcome.cost()).hasToString("0.00175 USD");
    }

    @Test
    void leavesOutTheMostWhenNothingCapsTheAnswer() throws Exception {
        PROVIDER.respond(200, THINKING);

        HttpResponse<String> response = chat("thinking-model", "", CODER_KEY.value());

        assertThat(response.headers().firstValue(Gateway.COST_MAX_HEADER)).isEmpty();
        assertThat(response.headers().firstValue(Gateway.COST_HEADER)).contains("0.00175 USD");
    }

    @Test
    void showsNoCostsToTenantsThatDidNotAskForThem() throws Exception {
        PROVIDER.respond(200, THINKING);

        HttpResponse<String> response = chat("qwen3", "\"max_tokens\":100,", KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(Gateway.COST_MAX_HEADER)).isEmpty();
        assertThat(response.headers().firstValue(Gateway.COST_HEADER)).isEmpty();
    }

    @Test
    void endsAStreamWithTheChargeAsACommentClientsIgnore() throws Exception {
        PROVIDER.respond(200, """
                data: {"id":"c","choices":[{"delta":{"content":"Hi"}}]}

                data: {"id":"c","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":2}}

                data: [DONE]

                """);

        HttpResponse<String> response =
                chat("thinking-model", "\"stream\":true,\"max_tokens\":100,", CODER_KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(Gateway.COST_MAX_HEADER)).contains("0.0004 USD");
        assertThat(response.headers().firstValue(Gateway.COST_HEADER)).isEmpty();
        assertThat(response.body()).endsWith("data: [DONE]\n\n: tollgate-cost 0.000002 USD\n\n");
    }
}
