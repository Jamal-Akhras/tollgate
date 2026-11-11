package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class FallbackTest extends GatewayTestSupport {

    private static final String ANSWER =
            "{\"id\":\"c\",\"model\":\"qwen3:235b\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Hi\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":5}}";

    private HttpResponse<String> chat(String model) throws Exception {
        return chat(model, "internal");
    }

    private HttpResponse<String> chat(String model, String dataClass) throws Exception {
        return post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"" + model + "\",\"messages\":[]}",
                "Authorization",
                "Bearer " + CODER_KEY.value(),
                Gateway.DATA_CLASS_HEADER,
                dataClass);
    }

    private static String traceOf(HttpResponse<String> response) {
        return response.headers().firstValue(TraceIdFilter.HEADER).orElseThrow();
    }

    private static double fallbacks(String model, String provider, String reason) {
        Counter counter = Metrics.globalRegistry
                .find(Gateway.FALLBACKS_METRIC)
                .tag("model", model)
                .tag("provider", provider)
                .tag("reason", reason)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void answersFromTheFallbackWhenTheProviderCannotBeReached() throws Exception {
        PROVIDER.respond(200, ANSWER);
        double before = fallbacks("sturdy-model", "down", "provider_unavailable");

        HttpResponse<String> response = chat("sturdy-model");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"model\":\"cloud-model\"");
        assertThat(PROVIDER.received())
                .singleElement()
                .satisfies(received -> assertThat(received.body()).contains("\"model\":\"qwen3:235b\""));
        String trace = traceOf(response);
        List<AuditRecord> first = auditRecordsOf(trace);
        assertThat(first.getFirst()).isInstanceOfSatisfying(Intent.class, intent -> {
            assertThat(intent.model()).isEqualTo("sturdy-model");
            assertThat(intent.provider()).isEqualTo("down");
        });
        assertThat(first.get(1))
                .isInstanceOfSatisfying(
                        Outcome.class, outcome -> assertThat(outcome.result()).isEqualTo(Outcome.Result.UNREACHABLE));
        List<AuditRecord> second = auditRecordsOf(trace + ".2");
        assertThat(second.getFirst()).isInstanceOfSatisfying(Intent.class, intent -> {
            assertThat(intent.model()).isEqualTo("cloud-model");
            assertThat(intent.provider()).isEqualTo("cloud");
            assertThat(intent.allowed()).isTrue();
        });
        assertThat(second.get(1))
                .isInstanceOfSatisfying(
                        Outcome.class, outcome -> assertThat(outcome.result()).isEqualTo(Outcome.Result.RESPONDED));
        assertThat(fallbacks("sturdy-model", "down", "provider_unavailable")).isEqualTo(before + 1);
    }

    @Test
    void neverFallsBackWhereThePolicyKeepsTheData() throws Exception {
        PROVIDER.respond(200, ANSWER);

        HttpResponse<String> response = chat("sturdy-model", "confidential");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("\"code\":\"provider_unavailable\"");
        assertThat(PROVIDER.received()).isEmpty();
        assertThat(auditRecordsOf(traceOf(response) + ".2"))
                .singleElement()
                .isInstanceOfSatisfying(Intent.class, intent -> {
                    assertThat(intent.model()).isEqualTo("cloud-model");
                    assertThat(intent.decision()).isInstanceOf(Decision.Deny.class);
                });
    }

    @Test
    void fallsBackWhenTheProviderFailsButNotWhenItRefusesTheRequest() throws Exception {
        PROVIDER.respond(503, "{\"error\":{\"message\":\"overloaded\"}}");
        HttpResponse<String> overloaded = chat("backed-model");
        assertThat(overloaded.statusCode()).isEqualTo(502);
        assertThat(PROVIDER.received()).hasSize(2);

        PROVIDER.reset();
        PROVIDER.respond(429, "{\"error\":{\"message\":\"slow down\"}}");
        HttpResponse<String> limited = chat("backed-model");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(PROVIDER.received()).hasSize(2);

        PROVIDER.reset();
        PROVIDER.respond(400, "{\"error\":{\"message\":\"messages must not be empty\"}}");
        HttpResponse<String> refused = chat("backed-model");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("messages must not be empty");
        assertThat(PROVIDER.received()).hasSize(1);
        assertThat(auditRecordsOf(traceOf(refused) + ".2")).isEmpty();
    }

    @Test
    void spillsOverWhenTheProviderIsBusy() throws Exception {
        PROVIDER.respond(200, ANSWER);
        PROVIDER.delay(Duration.ofSeconds(1));
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HttpResponse<String>> first = threads.submit(() -> chat("gpu-model"));
            while (PROVIDER.received().isEmpty()) {
                Thread.onSpinWait();
            }

            HttpResponse<String> spilled = chat("spill-model");

            assertThat(spilled.statusCode()).isEqualTo(200);
            assertThat(spilled.body()).contains("\"model\":\"cloud-model\"");
            assertThat(auditRecordsOf(traceOf(spilled))).isEmpty();
            assertThat(auditRecordsOf(traceOf(spilled) + ".2")).hasSize(2);
            assertThat(first.get().statusCode()).isEqualTo(200);
        }
    }

    @Test
    void fallsBackBeforeAStreamStarts() throws Exception {
        PROVIDER.respond(200, """
                data: {"id":"c","model":"qwen3:235b","choices":[{"delta":{"content":"Hi"}}]}

                data: [DONE]

                """);

        HttpResponse<String> response = post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"sturdy-model\",\"stream\":true,\"messages\":[]}",
                "Authorization",
                "Bearer " + CODER_KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"model\":\"cloud-model\"").contains("data: [DONE]");
    }
}
