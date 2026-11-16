package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class ConfigEditsOffTest extends ServerTestSupport {

    private static HttpResponse<String> put(String path, String body) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ADMIN_PORT + "/admin/v1" + path))
                            .header("Authorization", "Bearer " + ADMIN_TOKEN)
                            .header("Content-Type", "application/json")
                            .header("If-Match", "\"anything\"")
                            .PUT(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void refusesEveryEditAndLeavesTheFileAlone() throws Exception {
        byte[] before = Files.readAllBytes(CONFIG);

        HttpResponse<String> response = put("/policies/default/guards/0/mode", "{\"mode\":\"monitor\"}");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("\"code\":\"edits_off\"").contains("TOLLGATE_ADMIN_EDITS=file");
        assertThat(Files.readAllBytes(CONFIG)).isEqualTo(before);
    }

    @Test
    void refusesABudgetEdit() throws Exception {
        HttpResponse<String> response = put("/tenants/metered/budget", "{\"limit\":\"5\",\"period\":\"daily\"}");

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("\"code\":\"edits_off\"");
    }

    @Test
    void answersEditsOffBeforeCheckingTheBody() throws Exception {
        assertThat(put("/policies/default/guards/0/mode", "{\"mode\":\"off\"}").statusCode())
                .isEqualTo(409);
        assertThat(put("/policies/default/guards/0/mode", "not json").statusCode())
                .isEqualTo(409);
        assertThat(put("/policies/default/guards/x/mode", "{\"mode\":\"monitor\"}")
                        .statusCode())
                .isEqualTo(409);
        assertThat(put("/tenants/metered/budget", "{\"limit\":\"abc\",\"period\":\"daily\"}")
                        .statusCode())
                .isEqualTo(409);
    }
}
