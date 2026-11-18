package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class ConfigEditsOffTest extends ServerTestSupport {

    private static HttpResponse<String> put(String path, String body) throws Exception {
        return admin("PUT", path, body, "Content-Type", "application/json", "If-Match", "\"anything\"");
    }

    private static HttpResponse<String> send(String method, String path) throws Exception {
        return admin(method, path, "", "If-Match", "\"anything\"");
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
    void refusesToCreateOrRevokeAKey() throws Exception {
        byte[] before = Files.readAllBytes(CONFIG);

        HttpResponse<String> created = send("POST", "/tenants/metered/keys");
        HttpResponse<String> revoked = send("DELETE", "/keys/" + KEY.id());

        assertThat(created.statusCode()).isEqualTo(409);
        assertThat(created.body()).contains("\"code\":\"edits_off\"").doesNotContain("tg_");
        assertThat(revoked.statusCode()).isEqualTo(409);
        assertThat(Files.readAllBytes(CONFIG)).isEqualTo(before);
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
