package io.github.jamalakhras.tollgate.server.admin;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AdminApiDocsConfiguration {

    static final String GROUP = "admin";

    @Bean
    GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group(GROUP)
                .pathsToMatch(PortSeparationFilter.ADMIN_PATH + "/**")
                .build();
    }

    @Bean
    OpenAPI tollgateOpenApi() {
        var license = new License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0");
        var info = new Info()
                .title("Tollgate Admin API")
                .version("v1")
                .description("Manage tenants, policies and keys. Served only on the admin port.")
                .license(license);
        var adminPort = new Server().url("/").description("The admin port of this instance");
        return new OpenAPI().info(info).servers(List.of(adminPort));
    }
}
