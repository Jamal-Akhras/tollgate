package io.github.jamalakhras.tollgate.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class BudgetTest extends GatewayTestSupport {

    private static final String METERED = "{\"model\":\"metered-model\",\"messages\":[]}";
    private static final String USAGE = "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";

    private HttpResponse<String> chat() throws IOException, InterruptedException {
        return post(Endpoints.OPENAI_CHAT, METERED, "Authorization", "Bearer " + METERED_KEY.value());
    }

    @Test
    void shortensTheAnswerToFitWhatIsLeftThenRefusesBelowTheFloor() throws Exception {
        PROVIDER.respond(200, "{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":300}}");
        String plain = "{\"model\":\"frugal-model\",\"messages\":[]}";

        HttpResponse<String> twoAnswers = post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"frugal-model\",\"n\":2,\"messages\":[]}",
                "Authorization",
                "Bearer " + FRUGAL_KEY.value());
        HttpResponse<String> shortened =
                post(Endpoints.OPENAI_CHAT, plain, "Authorization", "Bearer " + FRUGAL_KEY.value());
        HttpResponse<String> belowFloor =
                post(Endpoints.OPENAI_CHAT, plain, "Authorization", "Bearer " + FRUGAL_KEY.value());

        assertThat(twoAnswers.statusCode()).isEqualTo(429);
        assertThat(shortened.statusCode()).isEqualTo(200);
        assertThat(shortened.headers().firstValue(Gateway.OUTPUT_LIMITED_HEADER))
                .contains("500");
        assertThat(PROVIDER.received()).hasSize(1);
        assertThat(PROVIDER.received().getFirst().body()).contains("\"max_tokens\":500");
        assertThat(belowFloor.statusCode()).isEqualTo(429);
        assertThat(belowFloor.body()).contains("even with a shorter answer");
    }

    @Test
    void chargesReasoningTokensAtTheirOwnRate() throws Exception {
        PROVIDER.respond(
                200,
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":1000,"
                        + "\"completion_tokens_details\":{\"reasoning_tokens\":250}}}");

        HttpResponse<String> response = post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"thinking-model\",\"messages\":[]}",
                "Authorization",
                "Bearer " + CODER_KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
        Outcome outcome = (Outcome) auditRecordsOf(response).get(1);
        assertThat(outcome.cost()).isEqualTo(Money.of(new BigDecimal("0.00175"), Currency.getInstance("USD")));
    }

    @Test
    void chargesWhatWasUsedAndStopsWhenTheBudgetCannotCoverTheNextCall() throws Exception {
        PROVIDER.respond(200, USAGE);
        assertThat(chat().statusCode()).isEqualTo(200);
        assertThat(PROVIDER.received().getFirst().body())
                .isEqualTo("{\"model\":\"qwen3:32b\",\"messages\":[],\"max_tokens\":1000}");

        PROVIDER.respond(500, "{\"error\":{\"message\":\"overloaded\"}}");
        assertThat(chat().statusCode()).isEqualTo(502);

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
