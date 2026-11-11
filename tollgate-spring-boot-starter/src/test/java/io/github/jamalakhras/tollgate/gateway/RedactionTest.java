package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.audit.Redactions;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class RedactionTest extends GatewayTestSupport {

    private static final String JANE = "jane@example.com";
    private static final String CARD = "4111 1111 1111 1111";
    private static final String SECRET = "sk-proj-" + "a1B2".repeat(12);
    private static final AuditKeys KEYS =
            AuditKeys.of(AUDIT_KEYS.publicKey(), AUDIT_KEYS.signingKey(), AUDIT_KEYS.hmacKey());

    private HttpResponse<String> chat(String content, String... extra) throws Exception {
        String body = "{\"model\":\"qwen3\"," + String.join("", extra) + "\"messages\":[{\"role\":\"user\",\"content\":"
                + "\"" + content + "\"}]}";
        return post(Endpoints.OPENAI_CHAT, body, "Authorization", "Bearer " + CLERK_KEY.value());
    }

    private static String sent() {
        return PROVIDER.received().getFirst().body();
    }

    @Test
    void sendsPlaceholdersAndRestoresTheAnswer() throws Exception {
        PROVIDER.respond(
                200,
                "{\"id\":\"c\",\"model\":\"qwen3:32b\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Writing to [EMAIL_1] about [CARD_1].\",\"tool_calls\":[{\"id\":\"t\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"send\",\"arguments\":"
                        + "\"{\\\"to\\\":\\\"[EMAIL_1]\\\"}\"}}]}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":5}}");

        HttpResponse<String> response = chat("Email " + JANE + " about card " + CARD + ", from " + JANE);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sent())
                .contains("Email [EMAIL_1] about card [CARD_1], from [EMAIL_1]")
                .doesNotContain(JANE)
                .doesNotContain(CARD);
        assertThat(response.body())
                .contains("Writing to jane@example.com about 4111 1111 1111 1111.")
                .contains("{\\\"to\\\":\\\"jane@example.com\\\"}")
                .contains("\"model\":\"qwen3\"");

        List<AuditRecord> records = auditRecordsOf(response);
        Intent intent = (Intent) records.getFirst();
        assertThat(intent.redactions())
                .isEqualTo(new Redactions(
                        Map.of("email", 1, "card", 1), KEYS.hmac(sent().getBytes(StandardCharsets.UTF_8))));
        assertThat(intent.requestHmac())
                .isNotEqualTo(Objects.requireNonNull(intent.redactions()).sentHmac());
        assertThat(intent.steps().getLast().guard()).isEqualTo(Gateway.PERSONAL_DATA_STEP);
        assertThat(intent.steps().getLast().decision()).isEqualTo(Decision.ALLOW);
        assertThat(records.get(1)).isInstanceOf(Outcome.class);
    }

    @Test
    void refusesASecretWithoutCallingTheProviderOrRepeatingIt() throws Exception {
        HttpResponse<String> response = chat("Use key " + SECRET + " for " + JANE);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body())
                .contains("\"code\":\"policy_denied\"")
                .contains("This request contains a secret such as an API key or private key, which this policy"
                        + " refuses for data class 'internal'")
                .doesNotContain(SECRET);
        assertThat(PROVIDER.received()).isEmpty();
        Intent intent = (Intent) auditRecordsOf(response).getFirst();
        assertThat(intent.allowed()).isFalse();
        assertThat(intent.redactions()).isNull();
        GuardStep step = intent.steps().getLast();
        assertThat(step.guard()).isEqualTo(Gateway.PERSONAL_DATA_STEP);
        assertThat(step.decision()).isEqualTo(intent.decision());
    }

    @Test
    void refusesASecretInADryRunToo() throws Exception {
        HttpResponse<String> response = post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"qwen3\",\"messages\":[{\"role\":\"user\",\"content\":\"" + SECRET + "\"}]}",
                "Authorization",
                "Bearer " + CLERK_KEY.value(),
                Gateway.DRY_RUN_HEADER,
                "true");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(PROVIDER.received()).isEmpty();
    }

    @Test
    void recordsADryRunWithItsRedactions() throws Exception {
        HttpResponse<String> response = post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"qwen3\",\"messages\":[{\"role\":\"user\",\"content\":\"" + JANE + "\"}]}",
                "Authorization",
                "Bearer " + CLERK_KEY.value(),
                Gateway.DRY_RUN_HEADER,
                "true");

        assertThat(response.statusCode()).isEqualTo(200);
        Intent intent = (Intent) auditRecordsOf(response).getFirst();
        assertThat(Objects.requireNonNull(intent.redactions()).values()).isEqualTo(Map.of("email", 1));
    }

    @Test
    void recordsTheScanEvenWhenItFindsNothing() throws Exception {
        PROVIDER.respond(200, "{\"choices\":[{\"message\":{\"content\":\"[EMAIL_1]\"}}]}");

        HttpResponse<String> response = chat("Nothing personal");

        assertThat(response.body()).contains("\"content\":\"[EMAIL_1]\"");
        Intent intent = (Intent) auditRecordsOf(response).getFirst();
        assertThat(intent.redactions()).isNull();
        assertThat(intent.steps().getLast().guard()).isEqualTo(Gateway.PERSONAL_DATA_STEP);
    }

    @Test
    void leavesRequestsAloneForTenantsWhosePolicyDoesNotRedact() throws Exception {
        PROVIDER.respond(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");

        HttpResponse<String> response = post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"qwen3\",\"messages\":[{\"role\":\"user\",\"content\":\"" + JANE + " " + SECRET + "\"}]}",
                "Authorization",
                "Bearer " + KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sent()).contains(JANE).contains(SECRET);
        Intent intent = (Intent) auditRecordsOf(response).getFirst();
        assertThat(intent.redactions()).isNull();
        assertThat(intent.steps()).extracting(GuardStep::guard).doesNotContain(Gateway.PERSONAL_DATA_STEP);
    }

    @Test
    void restoresPlaceholdersInAProvidersErrorMessage() throws Exception {
        PROVIDER.respond(400, "{\"error\":{\"message\":\"Cannot email [EMAIL_1]\"}}");

        HttpResponse<String> response = chat(JANE);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("Cannot email jane@example.com");
    }

    @Test
    void restoresAStreamedAnswerSplitAcrossEvents() throws Exception {
        PROVIDER.respond(200, """
                data: {"id":"c","model":"qwen3:32b","choices":[{"index":0,"delta":{"content":"Hi [EMA"}}]}

                data: {"id":"c","model":"qwen3:32b","choices":[{"index":0,"delta":{"content":"IL_1], card [CA"}}]}

                data: {"id":"c","model":"qwen3:32b","choices":[{"index":0,"delta":{"content":"RD_1] ["},"finish_reason":"stop"}]}

                data: {"id":"c","model":"qwen3:32b","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":3}}

                data: [DONE]

                """);

        HttpResponse<String> response = chat(JANE + " " + CARD, "\"stream\":true,");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sent()).contains("[EMAIL_1] [CARD_1]");
        assertThat(response.body()).isEqualTo("""
                data: {"id":"c","model":"qwen3","choices":[{"index":0,"delta":{"content":"Hi "}}]}

                data: {"id":"c","model":"qwen3","choices":[{"index":0,"delta":{"content":"jane@example.com, card "}}]}

                data: {"id":"c","model":"qwen3","choices":[{"index":0,"delta":{"content":"4111 1111 1111 1111 ["},"finish_reason":"stop"}]}

                data: [DONE]

                : tollgate-cost 0 USD

                """);
    }

    @Test
    void restoresAnAnthropicStreamAndLeavesThinkingAsTheModelWroteIt() throws Exception {
        PROVIDER.respond(200, """
                event: message_start
                data: {"type":"message_start","message":{"model":"claude-sonnet-4-5","usage":{"input_tokens":5,"output_tokens":1}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"[EMAIL_1]"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"To [EMAIL"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_delta
                data: {"type":"message_delta","usage":{"output_tokens":3}}

                """);

        HttpResponse<String> response = post(
                Endpoints.ANTHROPIC_MESSAGES,
                "{\"model\":\"claude-sonnet\",\"max_tokens\":64,\"stream\":true,\"messages\":[{\"role\":\"user\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"" + JANE + "\"}]}]}",
                "x-api-key",
                CLERK_KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sent()).contains("\"text\":\"[EMAIL_1]\"");
        assertThat(response.body())
                .contains("\"thinking\":\"[EMAIL_1]\"")
                .contains("\"text\":\"To \"")
                .contains("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"[EMAIL\"}}\n\nevent: content_block_stop");
    }

    @Test
    void restoresAWholeAnthropicAnswer() throws Exception {
        PROVIDER.respond(
                200,
                "{\"id\":\"m\",\"model\":\"claude-sonnet-4-5\",\"content\":[{\"type\":\"thinking\",\"thinking\":"
                        + "\"[EMAIL_1]\",\"signature\":\"c2ln\"},{\"type\":\"text\",\"text\":\"Hi [EMAIL_1]\"},"
                        + "{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"send\",\"input\":{\"to\":\"[EMAIL_1]\"}}],"
                        + "\"usage\":{\"input_tokens\":3,\"output_tokens\":3}}");

        HttpResponse<String> response = post(
                Endpoints.ANTHROPIC_MESSAGES,
                "{\"model\":\"claude-sonnet\",\"max_tokens\":64,\"system\":\"Clerk for " + JANE + "\","
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "x-api-key",
                CLERK_KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(sent()).contains("\"system\":\"Clerk for [EMAIL_1]\"");
        assertThat(response.body())
                .contains("\"thinking\":\"[EMAIL_1]\"")
                .contains("\"text\":\"Hi jane@example.com\"")
                .contains("\"input\":{\"to\":\"jane@example.com\"}");
    }

    @Test
    void redactsAndRestoresWhatTheConfigsOwnDetectorsFind() throws Exception {
        PROVIDER.respond(
                200,
                "{\"choices\":[{\"message\":{\"content\":\"Refund [CUSTOMER_ID_1] to account"
                        + " [ACCOUNT_NUMBER_1]\"}}]}");

        HttpResponse<String> response = chat("Customer CUST-004211 wants a refund to account 12345678");

        assertThat(sent()).contains("Customer [CUSTOMER_ID_1] wants a refund to account [ACCOUNT_NUMBER_1]");
        assertThat(response.body()).contains("Refund CUST-004211 to account 12345678");
        Intent intent = (Intent) auditRecordsOf(response).getFirst();
        assertThat(Objects.requireNonNull(intent.redactions()).values())
                .isEqualTo(Map.of("account-number", 1, "customer-id", 1));
    }

    @Test
    void refusesATermTheConfigNames() throws Exception {
        HttpResponse<String> response = chat("When does project falcon launch?");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body())
                .contains("This request contains a value matching the 'codenames' detector, which this policy refuses"
                        + " for data class 'internal'")
                .doesNotContain("falcon");
        assertThat(PROVIDER.received()).isEmpty();
    }

    @Test
    void refusesARequestWhoseScanRunsOutOfTime() throws Exception {
        HttpResponse<String> response = post(
                Endpoints.OPENAI_CHAT,
                "{\"model\":\"qwen3\",\"messages\":[{\"role\":\"user\",\"content\":\"" + "a".repeat(40) + "!\"}]}",
                "Authorization",
                "Bearer " + HASTY_KEY.value());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body())
                .contains("\"code\":\"redaction_timeout\"")
                .contains("because the 'backtracking' detector took too long, so it was not sent");
        assertThat(PROVIDER.received()).isEmpty();
        Intent intent = (Intent) auditRecordsOf(response).getFirst();
        assertThat(intent.allowed()).isFalse();
        assertThat(intent.steps().getLast().guard()).isEqualTo(Gateway.PERSONAL_DATA_STEP);
    }
}
