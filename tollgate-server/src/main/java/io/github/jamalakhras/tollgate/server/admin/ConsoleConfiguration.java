package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.gateway.Gateway;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "tollgate.console.enabled", havingValue = "true", matchIfMissing = true)
class ConsoleConfiguration implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/console/**").addResourceLocations("classpath:/META-INF/tollgate-console/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/console/");
        registry.addRedirectViewController("/console", "/console/");
        registry.addViewController("/console/").setViewName("forward:/console/index.html");
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    LiveTail liveTail(Gateway gateway) {
        return new LiveTail(gateway::auditDirectory, Clock.systemUTC());
    }

    @Bean
    FilterRegistrationBean<ConsoleHeadersFilter> consoleHeadersFilter() {
        var registration = new FilterRegistrationBean<>(new ConsoleHeadersFilter());
        registration.addUrlPatterns("/console/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        return registration;
    }
}
