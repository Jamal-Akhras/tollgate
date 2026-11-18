package io.github.jamalakhras.tollgate.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class TollgateAnthropicTest {

    private static final String MESSAGE =
            "{\"id\":\"m1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-sonnet\",\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";

    private static AnthropicClient client(StubServer stub) {
        return TollgateAnthropic.builder(stub.url() + "/", "tg_key")
                .maxRetries(0)
                .build();
    }

    private static MessageCreateParams.Builder message() {
        return MessageCreateParams.builder()
                .model("claude-sonnet")
                .maxTokens(16)
                .addUserMessage("hi");
    }

    @Test
    void sendsAMessageToTheAnthropicPathWithTheKeyAsABearer() throws IOException {
        try (StubServer stub = new StubServer().answer(200, MESSAGE)) {
            Message message = client(stub).messages().create(message().build());

            assertThat(message.model().asString()).isEqualTo("claude-sonnet");
            assertThat(stub.path()).isEqualTo("/anthropic/v1/messages");
            assertThat(stub.requestHeader("Authorization")).isEqualTo("Bearer tg_key");
            assertThat(stub.requestHeader("x-api-key")).isNull();
        }
    }

    @Test
    void sendsTheHeadersAskedForOnOneCall() throws IOException {
        try (StubServer stub = new StubServer().answer(200, MESSAGE)) {
            client(stub)
                    .messages()
                    .create(message()
                            .putAdditionalHeader(Tollgate.DATA_CLASS, "confidential")
                            .putAdditionalHeader(Tollgate.USER, "ada")
                            .build());

            assertThat(stub.requestHeader(Tollgate.DATA_CLASS)).isEqualTo("confidential");
            assertThat(stub.requestHeader(Tollgate.USER)).isEqualTo("ada");
        }
    }

    @Test
    void readsAReceiptFromTheRawResponse() throws IOException {
        try (StubServer stub = new StubServer()
                        .answer(200, MESSAGE)
                        .header(Tollgate.TRACE_ID, "trace-1")
                        .header(Tollgate.COST, "0.0412 USD")
                        .header(Tollgate.COST_MAX, "0.1 USD")
                        .header(Tollgate.OUTPUT_LIMITED, "256");
                HttpResponseFor<Message> raw = client(stub)
                        .messages()
                        .withRawResponse()
                        .create(message().build())) {
            assertThat(TollgateAnthropic.receipt(raw)).isEqualTo(new Receipt("trace-1", "0.0412 USD", "0.1 USD", 256L));
        }
    }

    @Test
    void givesAReceiptOfNullsWhenTheGatewaySaidNothing() throws IOException {
        try (StubServer stub = new StubServer().answer(200, MESSAGE);
                HttpResponseFor<Message> raw = client(stub)
                        .messages()
                        .withRawResponse()
                        .create(message().build())) {
            assertThat(TollgateAnthropic.receipt(raw)).isEqualTo(new Receipt(null, null, null, null));
        }
    }

    @Test
    void readsARefusalFromAForbiddenAnswer() throws IOException {
        String body =
                "{\"error\":{\"message\":\"Tool run_command is refused\",\"type\":\"x\",\"code\":\"policy_denied\"}}";
        try (StubServer stub = new StubServer()
                .answer(403, body)
                .header(Tollgate.ERROR, "policy_denied")
                .header(Tollgate.TRACE_ID, "trace-2")) {
            AnthropicServiceException e = catchThrowableOfType(
                    AnthropicServiceException.class,
                    () -> client(stub).messages().create(message().build()));

            Refusal refusal = TollgateAnthropic.refusal(e).orElseThrow();
            assertThat(refusal.code()).isEqualTo("policy_denied");
            assertThat(refusal.status()).isEqualTo(403);
            assertThat(refusal.traceId()).isEqualTo("trace-2");
            assertThat(refusal.message()).contains("run_command");
            assertThat(refusal.retryAfter()).isNull();
        }
    }

    @Test
    void readsRetryAfterInSecondsFromATooManyRequestsAnswer() throws IOException {
        try (StubServer stub = new StubServer()
                .answer(429, "{\"error\":{\"message\":\"slow down\"}}")
                .header(Tollgate.ERROR, "rate_limited")
                .header("Retry-After", "7")) {
            AnthropicServiceException e = catchThrowableOfType(
                    AnthropicServiceException.class,
                    () -> client(stub).messages().create(message().build()));

            Refusal refusal = TollgateAnthropic.refusal(e).orElseThrow();
            assertThat(refusal.status()).isEqualTo(429);
            assertThat(refusal.retryAfter()).isEqualTo(7L);
        }
    }

    @Test
    void aSpentBudgetIsNotRetried() throws IOException {
        try (StubServer stub = new StubServer()
                .answer(
                        429,
                        "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"budget spent\"}}")
                .header(Tollgate.ERROR, "budget_exceeded")
                .header("x-should-retry", "false")) {
            AnthropicClient client =
                    TollgateAnthropic.builder(stub.url(), "tg_key").build();

            AnthropicServiceException e = catchThrowableOfType(
                    AnthropicServiceException.class,
                    () -> client.messages().create(message().build()));

            assertThat(TollgateAnthropic.refusal(e).orElseThrow().code()).isEqualTo("budget_exceeded");
            assertThat(stub.requests()).isEqualTo(1);
        }
    }

    @Test
    void givesNoRefusalWhenTheErrorHasNoTollgateHeader() throws IOException {
        try (StubServer stub = new StubServer().answer(500, "{\"error\":{\"message\":\"boom\"}}")) {
            AnthropicServiceException e = catchThrowableOfType(
                    AnthropicServiceException.class,
                    () -> client(stub).messages().create(message().build()));

            assertThat(TollgateAnthropic.refusal(e)).isEmpty();
        }
    }
}
