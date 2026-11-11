package io.github.jamalakhras.tollgate.gateway;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditLog;
import io.github.jamalakhras.tollgate.core.budget.InMemoryBudgetStore;
import io.github.jamalakhras.tollgate.core.config.AuditSettings;
import io.github.jamalakhras.tollgate.gateway.audit.JsonLinesAuditSink;
import io.github.jamalakhras.tollgate.providers.ProviderClient;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

@AutoConfiguration
@EnableConfigurationProperties(GatewayProperties.class)
@Import({
    ChatCompletionsController.class,
    MessagesController.class,
    ModelsController.class,
    OpenAiErrorHandler.class,
    AnthropicErrorHandler.class
})
class GatewayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfiguration.class);

    @Bean(destroyMethod = "close")
    HttpClient providerHttpClient() {
        return ProviderClient.newHttpClient();
    }

    @Bean(destroyMethod = "close")
    Gateway gateway(
            GatewayProperties properties,
            HttpClient providerHttpClient,
            Environment environment,
            ObjectProvider<Tracer> tracer) {
        try {
            return start(
                    ConfigFile.read(properties.config()),
                    environment::getProperty,
                    new ProviderClient(
                            providerHttpClient, properties.maxResponseSize().toBytes()),
                    Clock.systemUTC(),
                    properties.maxRequestBytes(),
                    properties.maxStreamDuration(),
                    properties.maxScanTime(),
                    tracer.getIfAvailable(() -> Tracer.NOOP));
        } catch (UncheckedIOException e) {
            throw new StartupException(e.getMessage() + ": " + reason(Objects.requireNonNull(e.getCause())), e);
        } catch (IllegalStateException e) {
            throw new StartupException(String.valueOf(e.getMessage()), e);
        }
    }

    private static String reason(IOException e) {
        return switch (e) {
            case AccessDeniedException denied -> "permission denied on " + denied.getFile();
            case NoSuchFileException missing -> missing.getFile() + " does not exist";
            default -> String.valueOf(e.getMessage());
        };
    }

    static Gateway start(
            LoadedConfig loaded,
            Function<String, @Nullable String> environment,
            ProviderClient client,
            Clock clock,
            int maxRequestBytes,
            Duration maxStreamDuration,
            Duration maxScanTime,
            Tracer tracer) {
        InMemoryBudgetStore budgets = new InMemoryBudgetStore(clock);
        AuditLog audit = startAudit(loaded, environment, clock);
        restoreBudgets(loaded, budgets);
        return new Gateway(
                loaded,
                environment,
                client,
                budgets,
                audit,
                clock,
                maxRequestBytes,
                maxStreamDuration,
                maxScanTime,
                tracer);
    }

    private static void restoreBudgets(LoadedConfig loaded, InMemoryBudgetStore budgets) {
        boolean budgeted = loaded.config().tenants().values().stream().anyMatch(tenant -> tenant.budget() != null);
        if (!budgeted) {
            return;
        }
        if (!(loaded.config().audit() instanceof AuditSettings.Enabled audit)) {
            log.warn("Budgets start from zero on every restart because the audit trail is off");
            return;
        }
        Path directory = loaded.resolve(audit.directory());
        try {
            BudgetReplay.Replayed replayed = BudgetReplay.replay(directory, loaded.config(), budgets);
            log.info(
                    "Restored budgets from {} settled calls and {} unfinished calls in {}",
                    replayed.settled(),
                    replayed.unfinished(),
                    directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the audit trail in " + directory, e);
        }
    }

    @Bean
    HealthIndicator auditHealthIndicator(Gateway gateway) {
        return () -> gateway.auditHealthy()
                ? Health.up().build()
                : Health.down()
                        .withDetail("reason", "The audit trail cannot be written")
                        .build();
    }

    @Bean
    FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        var registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/v1/*", "/anthropic/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    private static @Nullable AuditLog startAudit(
            LoadedConfig loaded, Function<String, @Nullable String> environment, Clock clock) {
        if (!(loaded.config().audit() instanceof AuditSettings.Enabled audit)) {
            log.warn("The audit trail is off: {} sets audit.mode to off", loaded.path());
            return null;
        }
        AuditKeys keys;
        try {
            keys = AuditKeys.of(
                    audit.publicKey(),
                    audit.signingKey().resolve(environment),
                    audit.hmacKey().resolve(environment));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(loaded.path() + ": audit." + e.getMessage(), e);
        }
        Path directory = loaded.resolve(audit.directory());
        try {
            return AuditLog.start(JsonLinesAuditSink.open(directory), keys, clock, audit.synchronous());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open the audit trail in " + directory, e);
        }
    }
}
