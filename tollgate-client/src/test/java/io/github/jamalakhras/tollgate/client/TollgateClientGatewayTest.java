package io.github.jamalakhras.tollgate.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.openai.client.OpenAIClient;
import com.openai.core.http.HttpResponseFor;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.FunctionDefinition;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.providers.FakeProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class TollgateClientGatewayTest {

    private static final String CHAT_ANSWER = "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,"
            + "\"model\":\"qwen3:32b\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"Hello from the fake\"}}],"
            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1,\"total_tokens\":6}}";
    private static final String MESSAGE_ANSWER = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"claude-sonnet-4-5\",\"content\":[{\"type\":\"text\",\"text\":\"Hello from the fake\"}],"
            + "\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}";

    @TempDir
    Path directory;

    private final FakeProvider openai = new FakeProvider();
    private final FakeProvider anthropic = new FakeProvider();
    private final ApiKey key = ApiKey.generate(new SecureRandom());
    private ConfigurableApplicationContext gateway;
    private String address;

    @BeforeEach
    void startTheGateway() throws Exception {
        openai.respond(200, CHAT_ANSWER);
        anthropic.respond(200, MESSAGE_ANSWER);
        Path config = Files.writeString(directory.resolve("tollgate.yaml"), """
                providers:
                  local: { format: openai, baseUrl: "%s/v1", region: local }
                  cloud: { format: anthropic, baseUrl: "%s", region: us }
                models:
                  qwen3: { provider: local, upstream: "qwen3:32b", price: free }
                  claude-sonnet: { provider: cloud, upstream: claude-sonnet-4-5, price: free }
                tenants:
                  app: { models: [qwen3, claude-sonnet], policy: careful }
                keys:
                  - { id: %s, tenant: app, sha256: %s }
                policies:
                  careful:
                    guards:
                      - { type: tool-names, refuse: [run_command] }
                audit:
                  mode: off
                """.formatted(
                        openai.baseUrl(), anthropic.baseUrl(), key.id(), key.sha256()));
        gateway = new SpringApplicationBuilder(TestApplication.class)
                .run(
                        "--spring.config.location=optional:classpath:/no-application-config/",
                        "--server.port=0",
                        "--management.metrics.use-global-registry=false",
                        "--tollgate.config=" + config);
        address = "http://127.0.0.1" + ":" + gateway.getEnvironment().getRequiredProperty("local.server.port");
    }

    @AfterEach
    void stopTheGateway() {
        gateway.close();
        openai.close();
        anthropic.close();
    }

    private OpenAIClient openAiClient() {
        return TollgateOpenAI.builder(address, key.value()).maxRetries(0).build();
    }

    private AnthropicClient anthropicClient() {
        return TollgateAnthropic.builder(address, key.value()).maxRetries(0).build();
    }

    private static ChatCompletionCreateParams.Builder chat() {
        return ChatCompletionCreateParams.builder().model("qwen3").addUserMessage("hi");
    }

    @Test
    void answersAChatAndGivesAReceiptWithATraceId() {
        try (HttpResponseFor<ChatCompletion> raw =
                openAiClient().chat().completions().withRawResponse().create(chat().build())) {
            assertThat(raw.parse().choices().get(0).message().content()).contains("Hello from the fake");
            assertThat(TollgateOpenAI.receipt(raw).traceId()).isNotBlank();
        }
    }

    @Test
    void answersAMessage() {
        Message message = anthropicClient()
                .messages()
                .create(MessageCreateParams.builder()
                        .model("claude-sonnet")
                        .maxTokens(16)
                        .addUserMessage("hi")
                        .build());

        assertThat(message.content().get(0).text().orElseThrow().text()).isEqualTo("Hello from the fake");
    }

    @Test
    void refusesAToolTheTenantMayNotUseAndSaysWhy() {
        ChatCompletionCreateParams params = chat().addFunctionTool(
                        FunctionDefinition.builder().name("run_command").build())
                .build();

        OpenAIServiceException e = catchThrowableOfType(
                OpenAIServiceException.class,
                () -> openAiClient().chat().completions().create(params));

        Refusal refusal = TollgateOpenAI.refusal(e).orElseThrow();
        assertThat(refusal.code()).isEqualTo("policy_denied");
        assertThat(refusal.status()).isEqualTo(403);
        assertThat(refusal.traceId())
                .isNotBlank()
                .isEqualTo(e.headers().values("X-Tollgate-Trace-Id").get(0));
    }

    @Test
    void listsTheModelsOfEachFormat() {
        assertThat(openAiClient().models().list().data())
                .extracting(model -> model.id())
                .containsExactly("qwen3");
        assertThat(anthropicClient().models().list().data())
                .extracting(model -> model.id())
                .containsExactly("claude-sonnet");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}
