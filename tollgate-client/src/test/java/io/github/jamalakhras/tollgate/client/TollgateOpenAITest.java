package io.github.jamalakhras.tollgate.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.openai.client.OpenAIClient;
import com.openai.core.http.HttpResponseFor;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class TollgateOpenAITest {

    private static final String COMPLETION =
            "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"qwen3\",\"choices\":[]}";

    private static OpenAIClient client(StubServer stub) {
        return TollgateOpenAI.builder(stub.url() + "/", "tg_key").maxRetries(0).build();
    }

    private static ChatCompletionCreateParams.Builder chat() {
        return ChatCompletionCreateParams.builder().model("qwen3").addUserMessage("hi");
    }

    @Test
    void sendsAChatToTheV1PathWithTheKeyAsABearer() throws IOException {
        try (StubServer stub = new StubServer().answer(200, COMPLETION)) {
            ChatCompletion completion = client(stub).chat().completions().create(chat().build());

            assertThat(completion.model()).isEqualTo("qwen3");
            assertThat(stub.path()).isEqualTo("/v1/chat/completions");
            assertThat(stub.requestHeader("Authorization")).isEqualTo("Bearer tg_key");
        }
    }

    @Test
    void sendsTheHeadersAskedForOnOneCall() throws IOException {
        try (StubServer stub = new StubServer().answer(200, COMPLETION)) {
            client(stub)
                    .chat()
                    .completions()
                    .create(chat().putAdditionalHeader(Tollgate.DATA_CLASS, "confidential")
                            .putAdditionalHeader(Tollgate.USER, "ada")
                            .build());

            assertThat(stub.requestHeader(Tollgate.DATA_CLASS)).isEqualTo("confidential");
            assertThat(stub.requestHeader(Tollgate.USER)).isEqualTo("ada");
        }
    }

    @Test
    void readsAReceiptFromTheRawResponse() throws IOException {
        try (StubServer stub = new StubServer()
                        .answer(200, COMPLETION)
                        .header(Tollgate.TRACE_ID, "trace-1")
                        .header(Tollgate.COST, "0.0412 USD")
                        .header(Tollgate.COST_MAX, "0.1 USD")
                        .header(Tollgate.OUTPUT_LIMITED, "256");
                HttpResponseFor<ChatCompletion> raw =
                        client(stub).chat().completions().withRawResponse().create(chat().build())) {
            assertThat(TollgateOpenAI.receipt(raw)).isEqualTo(new Receipt("trace-1", "0.0412 USD", "0.1 USD", 256L));
        }
    }

    @Test
    void givesAReceiptOfNullsWhenTheGatewaySaidNothing() throws IOException {
        try (StubServer stub = new StubServer().answer(200, COMPLETION);
                HttpResponseFor<ChatCompletion> raw =
                        client(stub).chat().completions().withRawResponse().create(chat().build())) {
            assertThat(TollgateOpenAI.receipt(raw)).isEqualTo(new Receipt(null, null, null, null));
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
            OpenAIServiceException e = catchThrowableOfType(
                    OpenAIServiceException.class,
                    () -> client(stub).chat().completions().create(chat().build()));

            Refusal refusal = TollgateOpenAI.refusal(e).orElseThrow();
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
            OpenAIServiceException e = catchThrowableOfType(
                    OpenAIServiceException.class,
                    () -> client(stub).chat().completions().create(chat().build()));

            Refusal refusal = TollgateOpenAI.refusal(e).orElseThrow();
            assertThat(refusal.status()).isEqualTo(429);
            assertThat(refusal.retryAfter()).isEqualTo(7L);
        }
    }

    @Test
    void aSpentBudgetIsNotRetried() throws IOException {
        try (StubServer stub = new StubServer()
                .answer(429, "{\"error\":{\"message\":\"budget spent\"}}")
                .header(Tollgate.ERROR, "budget_exceeded")
                .header("x-should-retry", "false")) {
            OpenAIClient client = TollgateOpenAI.builder(stub.url(), "tg_key").build();

            OpenAIServiceException e = catchThrowableOfType(
                    OpenAIServiceException.class,
                    () -> client.chat().completions().create(chat().build()));

            assertThat(TollgateOpenAI.refusal(e).orElseThrow().code()).isEqualTo("budget_exceeded");
            assertThat(stub.requests()).isEqualTo(1);
        }
    }

    @Test
    void givesNoRefusalWhenTheErrorHasNoTollgateHeader() throws IOException {
        try (StubServer stub = new StubServer().answer(500, "{\"error\":{\"message\":\"boom\"}}")) {
            OpenAIServiceException e = catchThrowableOfType(
                    OpenAIServiceException.class,
                    () -> client(stub).chat().completions().create(chat().build()));

            assertThat(TollgateOpenAI.refusal(e)).isEmpty();
        }
    }
}
