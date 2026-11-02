package io.github.jamalakhras.tollgate.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.money.Usage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class StreamingTest extends GatewayTestSupport {

    private static final String STREAM = """
            data: {"id":"c","model":"qwen3:32b","choices":[{"delta":{"content":"He"}}]}

            data: {"id":"c","model":"qwen3:32b","choices":[{"delta":{"content":"llo"}}]}

            data: {"id":"c","model":"qwen3:32b","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":2}}

            data: [DONE]

            """;

    private HttpResponse<String> stream(String extra) throws Exception {
        return post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"qwen3\",\"stream\":true," + extra + "\"messages\":[]}",
                "Authorization",
                "Bearer " + KEY.value());
    }

    @Test
    void relaysEachEventWithYourModelNameAndSettlesFromTheUsage() throws Exception {
        PROVIDER.respond(200, STREAM);

        HttpResponse<String> response = stream("");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.body()).isEqualTo("""
                data: {"id":"c","model":"qwen3","choices":[{"delta":{"content":"He"}}]}

                data: {"id":"c","model":"qwen3","choices":[{"delta":{"content":"llo"}}]}

                data: [DONE]

                """);
        assertThat(PROVIDER.received().getFirst().body()).contains("\"stream_options\":{\"include_usage\":true}");
        Outcome outcome = (Outcome) auditRecordsOf(response).get(1);
        assertThat(outcome.result()).isEqualTo(Outcome.Result.RESPONDED);
        assertThat(outcome.status()).isEqualTo(200);
        assertThat(outcome.usage()).isEqualTo(new Usage(12, 2, 0, 0));
        assertThat(outcome.responseHmac()).isNotNull();
    }

    @Test
    void relaysTheUsageChunkWhenTheClientAskedForIt() throws Exception {
        PROVIDER.respond(200, STREAM);

        HttpResponse<String> response = stream("\"stream_options\":{\"include_usage\":true},");

        assertThat(response.body()).contains("\"usage\":{\"prompt_tokens\":12");
    }

    @Test
    void answersAProviderErrorBeforeTheStreamAsAnOrdinaryError() throws Exception {
        PROVIDER.respond(429, "{\"error\":{\"message\":\"slow down\"}}");

        HttpResponse<String> response = stream("");

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(response.body()).contains("\"code\":\"provider_rate_limited\"");
    }

    @Test
    void endsWithAnErrorEventWhenTheProviderSendsSomethingItCannotRelay() throws Exception {
        PROVIDER.respond(
                200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: {\"error\":{\"message\":\"boom\"}}\n\n");

        HttpResponse<String> response = stream("");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .startsWith("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n")
                .contains("\"code\":\"stream_interrupted\"")
                .doesNotContain("boom");
        assertThat(((Outcome) auditRecordsOf(response).get(1)).result()).isEqualTo(Outcome.Result.INTERRUPTED);
    }

    @Test
    void endsAStreamThatRunsPastTheLimit() throws Exception {
        PROVIDER.respond(200, "data: {\"choices\":[{\"delta\":{\"content\":\"" + "x".repeat(800) + "\"}}]}\n\n");
        PROVIDER.drip(Duration.ofMillis(5));

        HttpResponse<String> response = stream("");

        assertThat(response.body()).contains("The stream lasted longer than PT3S");
        assertThat(((Outcome) auditRecordsOf(response).get(1)).result()).isEqualTo(Outcome.Result.INTERRUPTED);
    }

    @Test
    void stopsWhenTheClientLeaves() throws Exception {
        PROVIDER.respond(
                200, ("data: {\"choices\":[{\"delta\":{\"content\":\"" + "x".repeat(40) + "\"}}]}\n\n").repeat(10));
        PROVIDER.drip(Duration.ofMillis(5));
        String body = "{\"model\":\"qwen3\",\"stream\":true,\"messages\":[]}";
        String traceId;
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port())) {
            socket.getOutputStream()
                    .write(("POST " + Endpoints.OPENAI_CHAT + " HTTP/1.1\r\nHost: localhost\r\n"
                                    + "Authorization: Bearer " + KEY.value() + "\r\n"
                                    + "Content-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n"
                                    + body)
                            .getBytes(StandardCharsets.UTF_8));
            traceId = traceIdFrom(
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)));
        }

        Outcome outcome = outcomeOf(traceId, Duration.ofSeconds(10));
        assertThat(outcome.result()).isEqualTo(Outcome.Result.CLIENT_CLOSED);
    }

    private static String traceIdFrom(BufferedReader response) throws Exception {
        String line = response.readLine();
        while (line != null && !line.isEmpty()) {
            if (line.toLowerCase(Locale.ROOT).startsWith(TraceIdFilter.HEADER.toLowerCase(Locale.ROOT))) {
                return line.substring(line.indexOf(':') + 1).strip();
            }
            line = response.readLine();
        }
        throw new AssertionError("No trace ID in the response headers");
    }

    private static Outcome outcomeOf(String traceId, Duration wait) throws Exception {
        Instant until = Instant.now().plus(wait);
        while (Instant.now().isBefore(until)) {
            List<AuditRecord> records = auditRecordsOf(traceId);
            if (records.size() > 1) {
                return (Outcome) records.get(1);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("No outcome was recorded for " + traceId);
    }
}
