package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReloadTest extends ServerTestSupport {

    private static final String QWEN = "{\"model\":\"qwen3\",\"messages\":[]}";

    private String original = "";

    @BeforeEach
    void rememberTheConfig() throws IOException {
        original = Files.readString(CONFIG);
    }

    @AfterEach
    void restoreTheConfig() throws Exception {
        Files.writeString(CONFIG, original);
        assertThat(reload().statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> reload() throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ADMIN_PORT + "/admin/v1/config/reload"))
                            .header("Authorization", "Bearer " + ADMIN_TOKEN)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> chat(ApiKey key) throws IOException, InterruptedException {
        return post(CHAT, QWEN, "Authorization", "Bearer " + key.value());
    }

    @Test
    void acceptsANewKeyWithoutARestart() throws Exception {
        ApiKey added = ApiKey.generate(new SecureRandom());
        assertThat(chat(added).statusCode()).isEqualTo(401);
        String before = configVersionOf(chat(KEY));

        Files.writeString(
                CONFIG,
                original.replace(
                        "keys:\n",
                        "keys:\n  - { id: %s, tenant: support-bot, sha256: %s }\n"
                                .formatted(added.id(), added.sha256())));
        HttpResponse<String> reloaded = reload();

        assertThat(reloaded.statusCode()).isEqualTo(200);
        assertThat(reloaded.body()).contains("\"changed\":true");
        HttpResponse<String> response = chat(added);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(configVersionOf(response)).isNotEqualTo(before).isEqualTo(versionIn(reloaded));
    }

    @Test
    void reportsWhenNothingChanged() throws Exception {
        HttpResponse<String> reloaded = reload();

        assertThat(reloaded.statusCode()).isEqualTo(200);
        assertThat(reloaded.body()).contains("\"changed\":false");
    }

    @Test
    void keepsTheRunningConfigWhenTheFileIsInvalid() throws Exception {
        Files.writeString(CONFIG, original.replace("tenants:", "tenantz:"));

        HttpResponse<String> reloaded = reload();

        assertThat(reloaded.statusCode()).isEqualTo(422);
        assertThat(reloaded.body())
                .contains("\"code\":\"invalid_config\"")
                .contains("tenantz is not a recognised setting");
        assertThat(chat(KEY).statusCode()).isEqualTo(200);
    }

    @Test
    void asksForARestartWhenTheAuditSettingsChange() throws Exception {
        Files.writeString(CONFIG, original.replace("mode: sync", "mode: async"));

        HttpResponse<String> reloaded = reload();

        assertThat(reloaded.statusCode()).isEqualTo(409);
        assertThat(reloaded.body()).contains("\"code\":\"restart_required\"");
    }

    @Test
    void answersOnlyOnTheAdminPort() throws Exception {
        assertThat(post("/admin/v1/config/reload", "").statusCode()).isEqualTo(404);
    }

    private static String configVersionOf(HttpResponse<String> response) throws IOException {
        List<AuditRecord> records = auditRecordsOf(response);
        return ((Intent) records.getFirst()).configVersion();
    }

    private static String versionIn(HttpResponse<String> reloaded) {
        return reloaded.body().replaceAll(".*\"version\":\"([0-9a-f]{64})\".*", "$1");
    }
}
