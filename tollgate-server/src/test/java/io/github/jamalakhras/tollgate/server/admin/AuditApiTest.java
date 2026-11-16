package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AuditApiTest extends ServerTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ADMIN_PORT + "/admin/v1/audit" + path));
            if (headers.length > 0) {
                request.headers(headers);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpResponse<String> admin(String path) throws IOException, InterruptedException {
        return get(path, "Authorization", "Bearer " + ADMIN_TOKEN);
    }

    private JsonNode sealedBatches() throws Exception {
        PROVIDER.respond(200, "{\"id\":\"c\",\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}");
        post(CHAT, "{\"model\":\"qwen3\",\"messages\":[]}", "Authorization", "Bearer " + KEY.value());
        JsonNode batches = JSON.readTree(admin("/batches").body());
        for (int i = 0; i < 50 && batches.isEmpty(); i++) {
            Thread.sleep(100);
            batches = JSON.readTree(admin("/batches").body());
        }
        assertThat(batches).isNotEmpty();
        return batches;
    }

    @Test
    void statusNamesTheModeTheKeyAndTheLastSeal() throws Exception {
        sealedBatches();

        HttpResponse<String> response = admin("");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode status = JSON.readTree(response.body());
        assertThat(status.get("mode").asString()).isEqualTo("sync");
        assertThat(status.get("publicKey").asString()).isEqualTo(AUDIT_KEYS.publicKey());
        assertThat(status.get("keyId").asString()).hasSize(16);
        assertThat(status.get("files").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(status.get("lastSeal").get("batch").asLong()).isPositive();
    }

    @Test
    void batchesComeNewestFirst() throws Exception {
        sealedBatches();
        sealedBatches();

        JsonNode batches = JSON.readTree(admin("/batches").body());

        for (int i = 1; i < batches.size(); i++) {
            assertThat(batches.get(i - 1).get("seal").get("batch").asLong())
                    .isGreaterThan(batches.get(i).get("seal").get("batch").asLong());
        }
        assertThat(batches.get(0).get("file").asString()).startsWith("tollgate-audit-");
    }

    @Test
    void aBatchListsItsLeavesAndChecksOut() throws Exception {
        long number = sealedBatches().get(0).get("seal").get("batch").asLong();

        HttpResponse<String> response = admin("/batches/" + number);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode batch = JSON.readTree(response.body());
        assertThat(batch.get("leaves")).hasSize(batch.get("seal").get("records").asInt());
        assertThat(batch.get("signatureValid").asBoolean()).isTrue();
        assertThat(batch.get("rootMatches").asBoolean()).isTrue();
        assertThat(batch.get("leaves").get(0).get("index").asInt()).isZero();
        assertThat(batch.get("leaves").get(0).get("type").asString()).isIn("intent", "outcome");
        assertThat(batch.get("leaves").get(0).get("leafHash").asString()).hasSize(64);
    }

    @Test
    void aMissingBatchAnswers404() throws Exception {
        HttpResponse<String> response = admin("/batches/999999");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"not_found\"").contains("No batch 999999 in the audit trail");
    }

    @Test
    void refusesALimitItCannotServe() throws Exception {
        HttpResponse<String> response = admin("/batches?limit=0");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .contains("\"code\":\"invalid_request\"")
                .contains("limit must be between 1 and 200, was 0");
        assertThat(admin("/batches?limit=201").statusCode()).isEqualTo(400);
    }

    @Test
    void needsTheAdminToken() throws Exception {
        assertThat(get("").statusCode()).isEqualTo(401);
        assertThat(get("/batches").statusCode()).isEqualTo(401);
        assertThat(get("/batches/1").statusCode()).isEqualTo(401);
    }
}
