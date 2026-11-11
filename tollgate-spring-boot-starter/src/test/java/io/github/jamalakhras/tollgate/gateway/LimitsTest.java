package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class LimitsTest extends GatewayTestSupport {

    private static final String ANSWER =
            "{\"id\":\"c\",\"model\":\"qwen3:32b\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Hi\"}}],\"usage\":{\"prompt_tokens\":%d,\"completion_tokens\":%d}}";

    private HttpResponse<String> chat(ApiKey key) throws Exception {
        return post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"qwen3\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}",
                "Authorization",
                "Bearer " + key.value());
    }

    private HttpResponse<String> gpu(boolean dryRun) throws Exception {
        return post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"gpu-model\",\"messages\":[]}",
                "Authorization",
                "Bearer " + CODER_KEY.value(),
                Gateway.DRY_RUN_HEADER,
                Boolean.toString(dryRun));
    }

    private static double busy(String provider) {
        Counter counter = Metrics.globalRegistry
                .find(Gateway.PROVIDERS_BUSY_METRIC)
                .tag("provider", provider)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static double refused(String tenant, String limit) {
        Counter counter = Metrics.globalRegistry
                .find(Gateway.LIMITS_REFUSED_METRIC)
                .tag("tenant", tenant)
                .tag("limit", limit)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void refusesRequestsOverTheRateWithRetryAfterAndRecordsNothing() throws Exception {
        PROVIDER.respond(200, ANSWER.formatted(5, 5));
        assertThat(chat(RATIONED_KEY).statusCode()).isEqualTo(200);
        assertThat(chat(RATIONED_KEY).statusCode()).isEqualTo(200);

        HttpResponse<String> refused = chat(RATIONED_KEY);

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(refused.headers().firstValue("Retry-After"))
                .hasValueSatisfying(
                        seconds -> assertThat(Integer.parseInt(seconds)).isBetween(29, 30));
        assertThat(refused.body())
                .contains("\"code\":\"rate_limited\"")
                .contains("This tenant is limited to 2 requests a minute");
        assertThat(PROVIDER.received()).hasSize(2);
        assertThat(auditRecordsOf(refused)).isEmpty();
        assertThat(refused("rationed", "requestsPerMinute")).isEqualTo(1);

        HttpResponse<String> anthropic = post(
                Endpoints.ANTHROPIC_MESSAGES,
                "{\"model\":\"qwen3\",\"max_tokens\":10,\"messages\":[]}",
                "x-api-key",
                RATIONED_KEY.value());
        assertThat(anthropic.statusCode()).isEqualTo(429);
        assertThat(anthropic.body())
                .contains("\"type\":\"rate_limit_error\"")
                .contains("This tenant is limited to 2 requests a minute");
    }

    @Test
    void chargesTheTokensEachCallUsedStreamedOrNot() throws Exception {
        PROVIDER.respond(200, """
                data: {"id":"c","model":"qwen3:32b","choices":[{"delta":{"content":"Hi"}}]}

                data: {"id":"c","model":"qwen3:32b","choices":[],"usage":{"prompt_tokens":30,"completion_tokens":20}}

                data: [DONE]

                """);
        HttpResponse<String> streamed = post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"qwen3\",\"stream\":true,\"messages\":[]}",
                "Authorization",
                "Bearer " + THRIFTY_KEY.value());
        assertThat(streamed.statusCode()).isEqualTo(200);
        PROVIDER.respond(200, ANSWER.formatted(40, 20));
        assertThat(chat(THRIFTY_KEY).statusCode()).isEqualTo(200);

        HttpResponse<String> refused = chat(THRIFTY_KEY);

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(refused.headers().firstValue("Retry-After"))
                .hasValueSatisfying(
                        seconds -> assertThat(Integer.parseInt(seconds)).isBetween(1, 6));
        assertThat(refused.body()).contains("This tenant is limited to 100 tokens a minute");
        assertThat(PROVIDER.received()).hasSize(2);
    }

    @Test
    void refusesACallWhileTheTenantHasTooManyInFlight() throws Exception {
        PROVIDER.respond(200, ANSWER.formatted(5, 5));
        PROVIDER.delay(Duration.ofSeconds(1));
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HttpResponse<String>> first = threads.submit(() -> chat(SINGLE_KEY));
            while (PROVIDER.received().isEmpty()) {
                Thread.onSpinWait();
            }

            HttpResponse<String> refused = chat(SINGLE_KEY);

            assertThat(refused.statusCode()).isEqualTo(429);
            assertThat(refused.headers().firstValue("Retry-After")).isEmpty();
            assertThat(refused.body()).contains("This tenant is limited to 1 call at a time");
            assertThat(first.get().statusCode()).isEqualTo(200);
        }
        PROVIDER.delay(Duration.ZERO);
        assertThat(chat(SINGLE_KEY).statusCode()).isEqualTo(200);
    }

    @Test
    void refusesACallWhileItsProviderHasTooManyInFlight() throws Exception {
        PROVIDER.respond(200, ANSWER.formatted(5, 5));
        PROVIDER.delay(Duration.ofSeconds(1));
        double before = busy("gpu");
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HttpResponse<String>> first = threads.submit(() -> gpu(false));
            while (PROVIDER.received().isEmpty()) {
                Thread.onSpinWait();
            }

            HttpResponse<String> refused = gpu(false);

            assertThat(refused.statusCode()).isEqualTo(503);
            assertThat(refused.headers().firstValue("Retry-After")).isEmpty();
            assertThat(refused.body())
                    .contains("\"code\":\"provider_busy\"")
                    .contains("The model 'gpu-model' is answering as many calls as it can at once");
            assertThat(auditRecordsOf(refused)).isEmpty();
            assertThat(busy("gpu")).isEqualTo(before + 1);
            assertThat(gpu(true).statusCode()).isEqualTo(200);
            assertThat(first.get().statusCode()).isEqualTo(200);
        }
        assertThat(PROVIDER.received()).hasSize(1);
        PROVIDER.delay(Duration.ZERO);
        PROVIDER.respond(200, """
                data: {"id":"c","model":"qwen3:32b","choices":[{"delta":{"content":"Hi"}}]}

                data: [DONE]

                """);
        assertThat(post(
                                Endpoints.OPENAI_CHAT,
                                "{\"model\":\"gpu-model\",\"stream\":true,\"messages\":[]}",
                                "Authorization",
                                "Bearer " + CODER_KEY.value())
                        .statusCode())
                .isEqualTo(200);
        PROVIDER.respond(200, ANSWER.formatted(5, 5));
        assertThat(gpu(false).statusCode()).isEqualTo(200);
    }

    @Test
    void leavesTenantsWithoutLimitsAlone() throws Exception {
        PROVIDER.respond(200, ANSWER.formatted(5_000, 5_000));
        for (int i = 0; i < 5; i++) {
            assertThat(chat(KEY).statusCode()).isEqualTo(200);
        }
    }
}
