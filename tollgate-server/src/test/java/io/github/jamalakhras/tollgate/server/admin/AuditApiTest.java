package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.InclusionProof;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AuditApiTest extends ServerTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static HttpResponse<String> audit(String path) throws IOException, InterruptedException {
        return admin("/audit" + path);
    }

    private JsonNode sealedBatches() throws Throwable {
        answeredCall();
        JsonNode batches = poll(() -> JSON.readTree(audit("/batches").body()), found -> !found.isEmpty());
        assertThat(batches).isNotEmpty();
        return batches;
    }

    @Test
    void statusNamesTheModeTheKeyAndTheLastSeal() throws Throwable {
        sealedBatches();

        HttpResponse<String> response = audit("");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode status = JSON.readTree(response.body());
        assertThat(status.get("mode").asString()).isEqualTo("sync");
        assertThat(status.get("publicKey").asString()).isEqualTo(AUDIT_KEYS.publicKey());
        assertThat(status.get("keyId").asString()).hasSize(16);
        assertThat(status.get("files").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(status.get("lastSeal").get("batch").asLong()).isPositive();
    }

    @Test
    void batchesComeNewestFirst() throws Throwable {
        sealedBatches();
        sealedBatches();

        JsonNode batches = JSON.readTree(audit("/batches").body());

        for (int i = 1; i < batches.size(); i++) {
            assertThat(batches.get(i - 1).get("seal").get("batch").asLong())
                    .isGreaterThan(batches.get(i).get("seal").get("batch").asLong());
        }
        assertThat(batches.get(0).get("file").asString()).startsWith("tollgate-audit-");
    }

    @Test
    void aBatchListsItsLeavesAndChecksOut() throws Throwable {
        long number = sealedBatches().get(0).get("seal").get("batch").asLong();

        HttpResponse<String> response = audit("/batches/" + number);

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
    void aMissingBatchAnswers404() throws Throwable {
        HttpResponse<String> response = audit("/batches/999999");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"not_found\"").contains("No batch 999999 in the audit trail");
    }

    @Test
    void refusesALimitItCannotServe() throws Throwable {
        HttpResponse<String> response = audit("/batches?limit=0");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .contains("\"code\":\"invalid_request\"")
                .contains("limit must be between 1 and 200, was 0");
        assertThat(audit("/batches?limit=201").statusCode()).isEqualTo(400);
    }

    private JsonNode sealedRecord(String id) throws Throwable {
        JsonNode proofs = poll(() -> JSON.readTree(audit("/records/" + id).body()), AuditApiTest::sealed);
        if (!sealed(proofs)) {
            throw new AssertionError("record " + id + " was not sealed");
        }
        return proofs;
    }

    private static boolean sealed(JsonNode proofs) {
        return proofs.has("intent")
                && proofs.get("intent").get("sealed").asBoolean()
                && (proofs.get("outcome").isNull()
                        || proofs.get("outcome").get("sealed").asBoolean());
    }

    private String firstRequestId() throws Throwable {
        long number = sealedBatches().get(0).get("seal").get("batch").asLong();
        return JSON.readTree(audit("/batches/" + number).body())
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
    void anAnsweredCallHasBothRecordsSealedAndProved() throws Throwable {
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
    void aRefusedCallHasAnIntentAndNoOutcome() throws Throwable {
        HttpResponse<String> refused = post(
                CHAT,
                "{\"model\":\"qwen3\",\"messages\":[]}",
                "Authorization",
                "Bearer " + KEY.value(),
                "X-Tollgate-Dry-Run",
                "true");
        String id = refused.headers().firstValue("X-Tollgate-Trace-Id").orElseThrow();

        JsonNode proofs = sealedRecord(id);

        assertThat(proofs.get("outcome").isNull()).isTrue();
        assertThat(proofs.get("intent")
                        .get("record")
                        .get("decision")
                        .get("verdict")
                        .asString())
                .isEqualTo("deny");
    }

    @Test
    void anUnknownRequestAnswers404() throws Throwable {
        HttpResponse<String> response = audit("/records/nothing");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("No audit record for request nothing");
    }

    @Test
    void anIdWithADotReachesTheHandlerWhole() throws Throwable {
        HttpResponse<String> response = audit("/records/01KA3WXD2F5G8H1J4K7L0MNPQR.2");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("No audit record for request 01KA3WXD2F5G8H1J4K7L0MNPQR.2");
    }

    @Test
    void anIdOverSixtyFourCharactersIsRefused() throws Throwable {
        HttpResponse<String> response = audit("/records/" + "a".repeat(65));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"invalid_request\"");
    }

    @Test
    void verificationFindsTheTrailIntact() throws Throwable {
        sealedBatches();

        HttpResponse<String> response = audit("/verification");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode verification = JSON.readTree(response.body());
        assertThat(verification.get("intact").asBoolean()).isTrue();
        assertThat(verification.get("batches").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(verification.get("records").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(verification.get("failures")).isEmpty();
        assertThat(verification.get("lastSeal").get("batch").asLong()).isPositive();
    }

    @Test
    void needsTheAdminToken() throws Throwable {
        assertThat(TestHttp.get(ADMIN_PORT, "/admin/v1/audit/records/x").statusCode())
                .isEqualTo(401);
        assertThat(TestHttp.get(ADMIN_PORT, "/admin/v1/audit/verification").statusCode())
                .isEqualTo(401);
        assertThat(TestHttp.get(ADMIN_PORT, "/admin/v1/audit").statusCode()).isEqualTo(401);
        assertThat(TestHttp.get(ADMIN_PORT, "/admin/v1/audit/batches").statusCode())
                .isEqualTo(401);
        assertThat(TestHttp.get(ADMIN_PORT, "/admin/v1/audit/batches/1").statusCode())
                .isEqualTo(401);
    }
}
