package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.core.budget.InMemoryBudgetStore;
import io.github.jamalakhras.tollgate.providers.ProviderClient;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class GatewayConfiguration {

    @Bean(destroyMethod = "close")
    HttpClient providerHttpClient() {
        return ProviderClient.newHttpClient();
    }

    @Bean
    Gateway gateway(GatewayProperties properties, HttpClient providerHttpClient, Environment environment) {
        return new Gateway(
                ConfigFile.read(properties.config()),
                environment::getProperty,
                new ProviderClient(providerHttpClient),
                new InMemoryBudgetStore(Clock.systemUTC()),
                properties.maxRequestBytes());
    }

    @Bean
    FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        var registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/v1/*", "/anthropic/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
