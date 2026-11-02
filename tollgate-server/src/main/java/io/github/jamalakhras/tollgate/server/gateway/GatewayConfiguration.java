package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditLog;
import io.github.jamalakhras.tollgate.core.budget.InMemoryBudgetStore;
import io.github.jamalakhras.tollgate.core.config.AuditSettings;
import io.github.jamalakhras.tollgate.providers.ProviderClient;
import io.github.jamalakhras.tollgate.server.audit.JsonLinesAuditSink;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class GatewayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfiguration.class);

    @Bean(destroyMethod = "close")
    HttpClient providerHttpClient() {
        return ProviderClient.newHttpClient();
    }

    @Bean(destroyMethod = "close")
    Gateway gateway(GatewayProperties properties, HttpClient providerHttpClient, Environment environment) {
        Clock clock = Clock.systemUTC();
        LoadedConfig loaded = ConfigFile.read(properties.config());
        return new Gateway(
                loaded,
                environment::getProperty,
                new ProviderClient(
                        providerHttpClient, properties.maxResponseSize().toBytes()),
                new InMemoryBudgetStore(clock),
                startAudit(loaded, environment::getProperty, clock),
                clock,
                properties.maxRequestBytes());
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
