package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.gateway.StartupException;
import java.net.InetAddress;
import org.apache.catalina.connector.Connector;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
class AdminPortConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AdminPortConfiguration.class);

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> adminConnector(AdminProperties admin) {
        return factory -> {
            if (factory.getPort() == admin.port()) {
                throw new StartupException("tollgate.admin.port must differ from server.port (" + admin.port() + ")");
            }
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(admin.port());
            if (!connector.setProperty("address", admin.address())) {
                throw new StartupException("Could not bind the admin port to " + admin.address());
            }
            factory.addAdditionalConnectors(connector);
        };
    }

    @Bean
    ApplicationListener<WebServerInitializedEvent> listeningAddresses(AdminProperties admin) {
        return event -> {
            if (event.getApplicationContext().getServerNamespace() != null
                    || !(event.getWebServer() instanceof TomcatWebServer server)) {
                return;
            }
            for (Connector connector : server.getTomcat().getService().findConnectors()) {
                boolean adminPort = connector.getPort() == admin.port();
                log.info(
                        "{}",
                        listening(
                                adminPort ? "The admin API" : "The gateway",
                                connector.getProperty("address"),
                                connector.getLocalPort(),
                                adminPort ? null : "TOLLGATE_ADDRESS"));
            }
        };
    }

    static String listening(String server, @Nullable Object address, int port, @Nullable String setting) {
        if (!(address instanceof InetAddress bound) || bound.isAnyLocalAddress()) {
            return server + " listens on port " + port + " on every network interface";
        }
        String listening = server + " listens on " + bound.getHostAddress() + ":" + port;
        if (!bound.isLoopbackAddress()) {
            return listening;
        }
        listening += ", so only this machine can connect";
        return setting == null ? listening : listening + ". Set " + setting + " to accept other machines";
    }

    @Bean
    FilterRegistrationBean<AdminTokenFilter> adminTokenFilter(AdminProperties admin) {
        var registration = new FilterRegistrationBean<>(new AdminTokenFilter(admin.token()));
        registration.addUrlPatterns(PortSeparationFilter.ADMIN_PATH + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    @Bean
    FilterRegistrationBean<PortSeparationFilter> portSeparationFilter(AdminProperties admin) {
        var registration = new FilterRegistrationBean<>(new PortSeparationFilter(admin.port()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
