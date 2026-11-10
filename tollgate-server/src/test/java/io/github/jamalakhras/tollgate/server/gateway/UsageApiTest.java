package io.github.jamalakhras.tollgate.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class UsageApiTest extends GatewayTestSupport {

    private static HttpResponse<String> admin(String path) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ADMIN_PORT + "/admin/v1" + path))
                            .header("Authorization", "Bearer " + ADMIN_TOKEN)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void sumsTheAuditTrailForEachTenant() throws Exception {
        PROVIDER.respond(200, "{\"id\":\"c\",\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}");
        assertThat(post(
                                Endpoints.OPENAI_CHAT,
                                "{\"model\":\"qwen3\",\"messages\":[]}",
                                "Authorization",
                                "Bearer " + KEY.value())
                        .statusCode())
                .isEqualTo(200);

        HttpResponse<String> usage = admin("/usage?by=tenant");

        assertThat(usage.statusCode()).isEqualTo(200);
        assertThat(usage.body())
                .contains("\"by\":\"tenant\"")
                .contains("\"key\":\"support-bot\"")
                .containsPattern("\"calls\":[1-9]");
        assertThat(admin("/usage?by=day").body()).contains("\"by\":\"day\"");
    }

    @Test
    void explainsABadQuery() throws Exception {
        HttpResponse<String> week = admin("/usage?by=week");
        assertThat(week.statusCode()).isEqualTo(400);
        assertThat(week.body())
                .contains("\"code\":\"invalid_request\"")
                .contains("by must be one of tenant, model, key, day, was week");
        assertThat(admin("/usage?from=yesterday").statusCode()).isEqualTo(400);
    }

    @Test
    void showsEachBudget() throws Exception {
        HttpResponse<String> budgets = admin("/budgets");

        assertThat(budgets.statusCode()).isEqualTo(200);
        assertThat(budgets.body())
                .contains("\"tenant\":\"metered\"")
                .contains("\"period\":\"total\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"limit\":\"0.01\"")
                .contains("\"reserved\":\"0\"");
    }
}
