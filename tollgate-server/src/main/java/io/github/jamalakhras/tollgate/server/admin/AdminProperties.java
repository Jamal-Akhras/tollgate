package io.github.jamalakhras.tollgate.server.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("tollgate.admin")
public record AdminProperties(
        @DefaultValue("8081") int port,
        @DefaultValue("127.0.0.1") String address) {

    public AdminProperties {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("tollgate.admin.port must be between 1 and 65535, was " + port);
        }
        if (address.isBlank()) {
            throw new IllegalArgumentException("tollgate.admin.address must not be blank");
        }
    }
}
