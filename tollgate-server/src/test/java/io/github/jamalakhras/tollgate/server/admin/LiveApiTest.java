package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class LiveApiTest extends ServerTestSupport {

    private static HttpResponse<String> awaiting(String path, String text) throws Throwable {
        return poll(() -> admin(path), response -> response.body().contains(text));
    }

    @Test
    void showsACallInTheLiveSnapshotAndTheActivityList() throws Throwable {
        HttpResponse<String> chat = answeredCall();
        assertThat(chat.statusCode()).isEqualTo(200);
        String traceId = chat.headers().firstValue("X-Tollgate-Trace-Id").orElseThrow();

        HttpResponse<String> live = awaiting("/live", "\"provider\":\"ollama\"");
        HttpResponse<String> activity = awaiting("/activity", traceId);

        assertThat(live.statusCode()).isEqualTo(200);
        assertThat(live.body())
                .containsPattern(
                        "\"tenant\":\"support-bot\",\"model\":\"qwen3\",\"provider\":\"ollama\",\"sent\":[1-9]")
                .contains("\"guard\":\"data-class\"")
                .contains("\"bucketSeconds\":5");
        assertThat(activity.statusCode()).isEqualTo(200);
        assertThat(activity.body())
                .contains("\"id\":\"" + traceId + "\"")
                .contains("\"guard\":\"data-class\"")
                .contains("\"result\":\"responded\"");
    }

    @Test
    void refusesRangesItCannotServe() throws Throwable {
        HttpResponse<String> minutes = admin("/live?minutes=0");
        assertThat(minutes.statusCode()).isEqualTo(400);
        assertThat(minutes.body())
                .contains("\"code\":\"invalid_request\"")
                .contains("minutes must be between 1 and 60, was 0");
        assertThat(admin("/live?minutes=61").statusCode()).isEqualTo(400);
        HttpResponse<String> step = admin("/live?step=10");
        assertThat(step.statusCode()).isEqualTo(400);
        assertThat(step.body()).contains("\"code\":\"invalid_request\"").contains("step must be 5, 15 or 60, was 10");
        assertThat(admin("/live?step=abc").statusCode()).isEqualTo(400);
        HttpResponse<String> limit = admin("/activity?limit=201");
        assertThat(limit.statusCode()).isEqualTo(400);
        assertThat(limit.body()).contains("limit must be between 1 and 200, was 201");
        assertThat(admin("/live?minutes=1").statusCode()).isEqualTo(200);
        assertThat(admin("/activity?limit=1").statusCode()).isEqualTo(200);
    }

    @Test
    void answersWithTheStepTheTotalsAndTheLatency() throws Throwable {
        answeredCall();
        awaiting("/live", "\"provider\":\"ollama\"");

        HttpResponse<String> fifteen = admin("/live?minutes=5&step=15");
        HttpResponse<String> minute = admin("/live?minutes=15&step=60");
        HttpResponse<String> flow = admin("/live?minutes=15&step=5");

        assertThat(fifteen.statusCode()).isEqualTo(200);
        assertThat(fifteen.body())
                .contains("\"bucketSeconds\":15")
                .contains("\"clientErrors\":0,\"serverErrors\":0")
                .containsPattern("\"totals\":\\{\"seconds\":[0-9]+,\"sent\":[1-9]")
                .contains("\"previous\":null")
                .containsPattern(
                        "\"latency\":\\{\"providers\":\\{\"ollama\":\\{\"p50Millis\":[0-9]+,\"p95Millis\":[0-9]+");
        assertThat(minute.body()).contains("\"bucketSeconds\":60");
        assertThat(flow.body()).contains("\"bucketSeconds\":5");
        assertThat(flow.body()).containsPattern("\"start\":[0-9]*[05],");
    }

    @Test
    void needsTheAdminToken() throws Throwable {
        assertThat(TestHttp.get(ADMIN_PORT, "/admin/v1/live").statusCode()).isEqualTo(401);
        assertThat(TestHttp.get(ADMIN_PORT, "/admin/v1/activity").statusCode()).isEqualTo(401);
    }

    @Test
    void answersOnlyOnTheAdminPort() throws Throwable {
        assertThat(TestHttp.get(gatewayPort, "/admin/v1/live").statusCode()).isEqualTo(404);
        assertThat(TestHttp.get(gatewayPort, "/admin/v1/activity").statusCode()).isEqualTo(404);
    }
}
