package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.gateway.audit.AuditDirectory;
import io.github.jamalakhras.tollgate.gateway.audit.AuditJson;
import io.github.jamalakhras.tollgate.providers.FakeProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class EmbeddedGatewayTest {

    private static final String ANSWER = "{\"id\":\"c\",\"model\":\"qwen3:32b\",\"choices\":[{\"index\":0,\"message\":"
            + "{\"role\":\"assistant\",\"content\":\"Hi\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}";
    private static final HttpClient HOST_CLIENT = HttpClient.newHttpClient();

    @TempDir
    Path directory;

    private final FakeProvider provider = new FakeProvider();
    private final ApiKey key = ApiKey.generate(new SecureRandom());
    private final AuditKeys.NewKeys keys = AuditKeys.generate(new SecureRandom());

    @AfterEach
    void stopTheProvider() {
        provider.close();
    }

    @Test
    void servesTheGatewayFromAPlainSpringBootApplicationWithTheStartersDefaults() throws Exception {
        try (ConfigurableApplicationContext application = start(TestApplication.class)) {
            HttpResponse<String> answer = chat(application);

            assertThat(answer.statusCode()).isEqualTo(200);
            assertThat(answer.body()).contains("\"model\":\"qwen3\"");
            String trace = answer.headers().firstValue(TraceIdFilter.HEADER).orElseThrow();
            assertThat(recordsOf(directory.resolve("audit"), trace)).hasSize(2);
        }
    }

    @Test
    void leavesTheApplicationsOwnHttpClientTheOnlyOneItCanInject() throws Exception {
        try (ConfigurableApplicationContext application = start(TestApplication.class, HostWithAnHttpClient.class)) {
            assertThat(application.getBean(HttpClient.class)).isSameAs(HOST_CLIENT);
            assertThat(application.getBean(HostWithAnHttpClient.class).client).isSameAs(HOST_CLIENT);
            assertThat(chat(application).statusCode()).isEqualTo(200);
        }
    }

    private ConfigurableApplicationContext start(Class<?>... sources) throws Exception {
        Path config = Files.writeString(directory.resolve("tollgate.yaml"), """
                providers:
                  local: { format: openai, baseUrl: "%s/v1", region: local }
                models:
                  qwen3: { provider: local, upstream: "qwen3:32b", price: free }
                tenants:
                  app: { models: [qwen3] }
                keys:
                  - { id: %s, tenant: app, sha256: %s }
                audit:
                  mode: sync
                  directory: '%s'
                  publicKey: %s
                  signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
                  hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
                """.formatted(
                        provider.baseUrl(), key.id(), key.sha256(), directory.resolve("audit"), keys.publicKey()));
        provider.respond(200, ANSWER);
        return new SpringApplicationBuilder(sources)
                .run(
                        "--spring.config.location=optional:classpath:/no-application-config/",
                        "--server.port=0",
                        "--management.metrics.use-global-registry=false",
                        "--tollgate.config=" + config,
                        "--TOLLGATE_AUDIT_SIGNING_KEY=" + keys.signingKey(),
                        "--TOLLGATE_AUDIT_HMAC_KEY=" + keys.hmacKey());
    }

    private HttpResponse<String> chat(ConfigurableApplicationContext application) throws Exception {
        int port = application.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + Endpoints.OPENAI_CHAT))
                            .header("Authorization", "Bearer " + key.value())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"qwen3\",\"messages\":[]}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private static List<AuditRecord> recordsOf(Path audit, String trace) throws Exception {
        List<AuditRecord> records = new ArrayList<>();
        for (Path file : AuditDirectory.files(audit)) {
            for (String line : Files.readAllLines(file)) {
                if (AuditJson.read(line) instanceof AuditRecord record
                        && record.id().equals(trace)) {
                    records.add(record);
                }
            }
        }
        return records;
    }

    @Configuration(proxyBeanMethods = false)
    static class HostWithAnHttpClient {

        final HttpClient client;

        HostWithAnHttpClient(HttpClient client) {
            this.client = client;
        }

        @Bean
        static HttpClient hostHttpClient() {
            return HOST_CLIENT;
        }
    }
}
