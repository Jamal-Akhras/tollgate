package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class LiveApiTest extends ServerTestSupport {

    @LocalServerPort
    private int gatewayPort;

    private static HttpResponse<String> get(int port, String path, String... headers)
            throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            if (headers.length > 0) {
                request.headers(headers);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpResponse<String> admin(String path) throws IOException, InterruptedException {
        return get(ADMIN_PORT, "/admin/v1" + path, "Authorization", "Bearer " + ADMIN_TOKEN);
    }

    private static HttpResponse<String> awaiting(String path, String text) throws Exception {
        HttpResponse<String> response = admin(path);
        for (int i = 0; i < 50 && !response.body().contains(text); i++) {
            Thread.sleep(100);
            response = admin(path);
        }
        return response;
    }

    @Test
    void showsACallInTheLiveSnapshotAndTheActivityList() throws Exception {
        PROVIDER.respond(200, "{\"id\":\"c\",\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}");
        HttpResponse<String> chat =
                post(CHAT, "{\"model\":\"qwen3\",\"messages\":[]}", "Authorization", "Bearer " + KEY.value());
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
    void refusesRangesItCannotServe() throws Exception {
        HttpResponse<String> minutes = admin("/live?minutes=0");
        assertThat(minutes.statusCode()).isEqualTo(400);
        assertThat(minutes.body())
                .contains("\"code\":\"invalid_request\"")
                .contains("minutes must be between 1 and 60, was 0");
        assertThat(admin("/live?minutes=61").statusCode()).isEqualTo(400);
        HttpResponse<String> limit = admin("/activity?limit=201");
        assertThat(limit.statusCode()).isEqualTo(400);
        assertThat(limit.body()).contains("limit must be between 1 and 200, was 201");
        assertThat(admin("/live?minutes=1").statusCode()).isEqualTo(200);
        assertThat(admin("/activity?limit=1").statusCode()).isEqualTo(200);
    }

    @Test
    void needsTheAdminToken() throws Exception {
        assertThat(get(ADMIN_PORT, "/admin/v1/live").statusCode()).isEqualTo(401);
        assertThat(get(ADMIN_PORT, "/admin/v1/activity").statusCode()).isEqualTo(401);
    }

    @Test
    void answersOnlyOnTheAdminPort() throws Exception {
        assertThat(get(gatewayPort, "/admin/v1/live").statusCode()).isEqualTo(404);
        assertThat(get(gatewayPort, "/admin/v1/activity").statusCode()).isEqualTo(404);
    }
}
