package io.github.jamalakhras.tollgate.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.audit.TrailVerifier;
import io.github.jamalakhras.tollgate.core.money.Usage;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import io.github.jamalakhras.tollgate.server.audit.AuditDirectory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;

class AuditTest extends GatewayTestSupport {

    @LocalManagementPort
    private int managementPort;

    private static final String QWEN = "{\"model\":\"qwen3\",\"messages\":[]}";
    private static final String ANSWER = "{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}";

    private static final AuditKeys KEYS =
            AuditKeys.of(AUDIT_KEYS.publicKey(), AUDIT_KEYS.signingKey(), AUDIT_KEYS.hmacKey());

    @Test
    void recordsTheDecisionBeforeTheCallAndTheOutcomeAfterIt() throws Exception {
        PROVIDER.respond(200, ANSWER);

        HttpResponse<String> response = post(Endpoints.OPENAI_CHAT, QWEN, "Authorization", "Bearer " + KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
        List<AuditRecord> records = auditRecordsOf(response);
        assertThat(records).hasSize(2);
        Intent intent = (Intent) records.get(0);
        assertThat(intent.tenant()).isEqualTo("support-bot");
        assertThat(intent.key()).isEqualTo(KEY.id());
        assertThat(intent.model()).isEqualTo("qwen3");
        assertThat(intent.upstream()).isEqualTo("qwen3:32b");
        assertThat(intent.dataClass()).isEqualTo("internal");
        assertThat(intent.decision()).isEqualTo(Decision.ALLOW);
        assertThat(intent.steps())
                .extracting(step -> step.guard())
                .containsExactly("request-features", "request-size", "data-class");
        assertThat(intent.requestHmac()).isEqualTo(KEYS.hmac(QWEN.getBytes(StandardCharsets.UTF_8)));
        assertThat(intent.configVersion()).matches("[0-9a-f]{64}");
        Outcome outcome = (Outcome) records.get(1);
        assertThat(outcome.result()).isEqualTo(Outcome.Result.RESPONDED);
        assertThat(outcome.status()).isEqualTo(200);
        assertThat(outcome.usage()).isEqualTo(new Usage(12, 3, 0, 0));
        assertThat(outcome.responseHmac()).isEqualTo(KEYS.hmac(ANSWER.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void recordsARefusalWithoutAnOutcome() throws Exception {
        HttpResponse<String> response = post(
                Endpoints.OPENAI_CHAT,
                QWEN,
                "Authorization",
                "Bearer " + KEY.value(),
                Gateway.DATA_CLASS_HEADER,
                "public");

        assertThat(response.statusCode()).isEqualTo(403);
        List<AuditRecord> records = auditRecordsOf(response);
        assertThat(records).hasSize(1);
        assertThat(((Intent) records.getFirst()).decision())
                .isInstanceOfSatisfying(
                        Decision.Deny.class, deny -> assertThat(deny.refusal()).isEqualTo(Refusal.POLICY_DENIED));
        assertThat(PROVIDER.received()).isEmpty();
    }

    @Test
    void recordsAnUnreachableProvider() throws Exception {
        HttpResponse<String> response = post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"offline-model\",\"messages\":[]}",
                "Authorization",
                "Bearer " + KEY.value());

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(auditRecordsOf(response).getLast()).isInstanceOfSatisfying(Outcome.class, outcome -> {
            assertThat(outcome.result()).isEqualTo(Outcome.Result.UNREACHABLE);
            assertThat(outcome.status()).isNull();
            assertThat(outcome.responseHmac()).isNull();
        });
    }

    @Test
    void leavesRequestsThatNeverReachADecisionOutOfTheTrail() throws Exception {
        HttpResponse<String> response = post(Endpoints.OPENAI_CHAT, QWEN, "Authorization", "Bearer tg_nope");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(auditRecordsOf(response)).isEmpty();
    }

    @Test
    void keepsATrailThatVerifies() throws Exception {
        PROVIDER.respond(200, ANSWER);
        post(Endpoints.OPENAI_CHAT, QWEN, "Authorization", "Bearer " + KEY.value());

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        TrailVerifier.Report report = verify();
        while (report.batches() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
            report = verify();
        }

        assertThat(report.failures()).isEmpty();
        assertThat(report.batches()).isPositive();
    }

    @Test
    void reportsTheAuditInTheReadinessProbe() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> readiness = client.send(
                    HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + managementPort + "/actuator/health/readiness"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(readiness.statusCode()).isEqualTo(200);
            assertThat(readiness.body()).contains("\"status\":\"UP\"");
        }
    }

    private static TrailVerifier.Report verify() throws IOException {
        return AuditDirectory.verify(AUDIT_DIRECTORY, AuditKeys.publicKey(AUDIT_KEYS.publicKey()));
    }
}
