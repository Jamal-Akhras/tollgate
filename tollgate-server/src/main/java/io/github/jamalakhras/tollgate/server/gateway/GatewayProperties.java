package io.github.jamalakhras.tollgate.server.gateway;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("tollgate")
public record GatewayProperties(
        @DefaultValue("tollgate.yaml") Path config,
        @DefaultValue("16MB") DataSize maxRequestSize,
        @DefaultValue("32MB") DataSize maxResponseSize) {

    private static final DataSize LARGEST_BODY = DataSize.ofMegabytes(512);

    public GatewayProperties {
        if (maxRequestSize.toBytes() < 1 || maxRequestSize.compareTo(LARGEST_BODY) > 0) {
            throw new IllegalArgumentException(
                    "tollgate.max-request-size must be between 1B and 512MB, was " + maxRequestSize);
        }
        if (maxResponseSize.toBytes() < 1 || maxResponseSize.compareTo(LARGEST_BODY) > 0) {
            throw new IllegalArgumentException(
                    "tollgate.max-response-size must be between 1B and 512MB, was " + maxResponseSize);
        }
    }

    int maxRequestBytes() {
        return Math.toIntExact(maxRequestSize.toBytes());
    }
}
