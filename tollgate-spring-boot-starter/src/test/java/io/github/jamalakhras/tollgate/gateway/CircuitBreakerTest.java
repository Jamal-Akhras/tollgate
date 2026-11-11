package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest extends GatewayTestSupport {

    private static final String ANSWER =
            "{\"id\":\"c\",\"model\":\"qwen3:235b\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Hi\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":5}}";

    private HttpResponse<String> chat(String model) throws Exception {
        return post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"" + model + "\",\"messages\":[]}",
                "Authorization",
                "Bearer " + CODER_KEY.value());
    }

    private static String traceOf(HttpResponse<String> response) {
        return response.headers().firstValue(TraceIdFilter.HEADER).orElseThrow();
    }

    @Test
    void skipsAProviderThatKeepsFailing() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(chat("broken-model").statusCode()).isEqualTo(502);
        }

        HttpResponse<String> skipped = chat("broken-model");

        assertThat(skipped.statusCode()).isEqualTo(503);
        assertThat(skipped.headers().firstValue("Retry-After"))
                .hasValueSatisfying(
                        seconds -> assertThat(Integer.parseInt(seconds)).isBetween(25, 30));
        assertThat(skipped.body())
                .contains("\"code\":\"provider_unavailable\"")
                .contains("The model 'broken-model' is unavailable because its provider keeps failing");
        assertThat(auditRecordsOf(skipped)).isEmpty();

        PROVIDER.respond(200, ANSWER);
        HttpResponse<String> mended = chat("mended-model");

        assertThat(mended.statusCode()).isEqualTo(200);
        assertThat(mended.body()).contains("\"model\":\"cloud-model\"");
        assertThat(auditRecordsOf(traceOf(mended))).isEmpty();
        assertThat(auditRecordsOf(traceOf(mended) + ".2")).hasSize(2);
    }
}
