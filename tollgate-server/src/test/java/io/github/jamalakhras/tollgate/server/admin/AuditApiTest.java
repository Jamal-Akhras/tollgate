package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.InclusionProof;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
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

    private JsonNode sealedRecord(String id) throws Exception {
        for (int i = 0; i < 50; i++) {
            HttpResponse<String> response = admin("/records/" + id);
            if (response.statusCode() == 200) {
                JsonNode proofs = JSON.readTree(response.body());
                JsonNode outcome = proofs.get("outcome");
                if (proofs.get("intent").get("sealed").asBoolean()
                        && (outcome.isNull() || outcome.get("sealed").asBoolean())) {
                    return proofs;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("record " + id + " was not sealed");
    }

    private String firstRequestId() throws Exception {
        long number = sealedBatches().get(0).get("seal").get("batch").asLong();
        return JSON.readTree(admin("/batches/" + number).body())
                .get("leaves")
                .get(0)
                .get("id")
                .asString();
    }

    private static InclusionProof proof(JsonNode view) {
        List<String> path = new ArrayList<>();
        view.get("path").forEach(step -> path.add(step.asString()));
        return new InclusionProof(
                view.get("index").asInt(),
                view.get("size").asInt(),
                view.get("encoding").asString(),
                view.get("leafHash").asString(),
                path,
                view.get("root").asString());
    }

    @Test
    void anAnsweredCallHasBothRecordsSealedAndProved() throws Exception {
        JsonNode proofs = sealedRecord(firstRequestId());

        for (String name : List.of("intent", "outcome")) {
            JsonNode proved = proofs.get(name);
            assertThat(proved.get("record").get("type").asString()).isEqualTo(name);
            assertThat(proved.get("proof").get("root").asString())
                    .isEqualTo(proved.get("seal").get("root").asString());
            assertThat(proved.get("signatureValid").asBoolean()).isTrue();
            assertThat(proof(proved.get("proof")).verifies()).isTrue();
        }
    }

    @Test
    void aRefusedCallHasAnIntentAndNoOutcome() throws Exception {
        post(
                CHAT,
                "{\"model\":\"qwen3\",\"messages\":[]}",
                "Authorization",
                "Bearer " + KEY.value(),
                "X-Tollgate-Dry-Run",
                "true");
        sealedBatches();
        String id = null;
        for (int i = 0; i < 50 && id == null; i++) {
            Thread.sleep(100);
            for (JsonNode batch : JSON.readTree(admin("/batches").body())) {
                long number = batch.get("seal").get("batch").asLong();
                for (JsonNode leaf :
                        JSON.readTree(admin("/batches/" + number).body()).get("leaves")) {
                    JsonNode found = JSON.readTree(
                            admin("/records/" + leaf.get("id").asString()).body());
                    if (found.get("outcome").isNull()) {
                        id = leaf.get("id").asString();
                    }
                }
            }
        }
        assertThat(id).isNotNull();
        JsonNode proofs = JSON.readTree(admin("/records/" + id).body());
        assertThat(proofs.get("intent")
                        .get("record")
                        .get("decision")
                        .get("verdict")
                        .asString())
                .isEqualTo("deny");
    }

    @Test
    void anUnknownRequestAnswers404() throws Exception {
        HttpResponse<String> response = admin("/records/nothing");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("No audit record for request nothing");
    }

    @Test
    void anIdWithADotReachesTheHandlerWhole() throws Exception {
        HttpResponse<String> response = admin("/records/01KA3WXD2F5G8H1J4K7L0MNPQR.2");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("No audit record for request 01KA3WXD2F5G8H1J4K7L0MNPQR.2");
    }

    @Test
    void anIdOverSixtyFourCharactersIsRefused() throws Exception {
        HttpResponse<String> response = admin("/records/" + "a".repeat(65));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"invalid_request\"");
    }

    @Test
    void verificationFindsTheTrailIntact() throws Exception {
        sealedBatches();

        HttpResponse<String> response = admin("/verification");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode verification = JSON.readTree(response.body());
        assertThat(verification.get("intact").asBoolean()).isTrue();
        assertThat(verification.get("batches").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(verification.get("records").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(verification.get("failures")).isEmpty();
        assertThat(verification.get("lastSeal").get("batch").asLong()).isPositive();
    }

    @Test
    void needsTheAdminToken() throws Exception {
        assertThat(get("/records/x").statusCode()).isEqualTo(401);
        assertThat(get("/verification").statusCode()).isEqualTo(401);
        assertThat(get("").statusCode()).isEqualTo(401);
        assertThat(get("/batches").statusCode()).isEqualTo(401);
        assertThat(get("/batches/1").statusCode()).isEqualTo(401);
    }
}
