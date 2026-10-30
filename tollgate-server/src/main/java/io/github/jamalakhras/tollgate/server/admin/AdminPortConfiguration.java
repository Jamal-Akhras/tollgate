package io.github.jamalakhras.tollgate.server.admin;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
class AdminPortConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> adminConnector(AdminProperties admin) {
        return factory -> {
            if (factory.getPort() == admin.port()) {
                throw new IllegalStateException(
                        "tollgate.admin.port must differ from server.port (" + admin.port() + ")");
            }
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(admin.port());
            if (!connector.setProperty("address", admin.address())) {
                throw new IllegalStateException("Could not bind the admin port to " + admin.address());
            }
            factory.addAdditionalConnectors(connector);
        };
    }

    @Bean
    FilterRegistrationBean<PortSeparationFilter> portSeparationFilter(AdminProperties admin) {
        var registration = new FilterRegistrationBean<>(new PortSeparationFilter(admin.port()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
