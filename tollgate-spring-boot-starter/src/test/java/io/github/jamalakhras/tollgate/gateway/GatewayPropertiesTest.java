package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

class GatewayPropertiesTest {

    @Test
    void convertsTheLimitToBytes() {
        assertThat(new GatewayProperties(
                                Path.of("tollgate.yaml"),
                                DataSize.parse("16MB"),
                                DataSize.parse("32MB"),
                                Duration.ofMinutes(10),
                                Duration.ofMillis(200))
                        .maxRequestBytes())
                .isEqualTo(16 * 1024 * 1024);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0B", "513MB"})
    void keepsTheLimitWithinBounds(String size) {
        assertThatThrownBy(() -> new GatewayProperties(
                        Path.of("tollgate.yaml"),
                        DataSize.parse(size),
                        DataSize.parse("32MB"),
                        Duration.ofMinutes(10),
                        Duration.ofMillis(200)))
                .hasMessageStartingWith("tollgate.max-request-size must be between 1B and 512MB");
        assertThatThrownBy(() -> new GatewayProperties(
                        Path.of("tollgate.yaml"),
                        DataSize.parse("16MB"),
                        DataSize.parse(size),
                        Duration.ofMinutes(10),
                        Duration.ofMillis(200)))
                .hasMessageStartingWith("tollgate.max-response-size must be between 1B and 512MB");
    }

    @Test
    void requiresAPositiveStreamDuration() {
        assertThatThrownBy(() -> new GatewayProperties(
                        Path.of("tollgate.yaml"),
                        DataSize.parse("16MB"),
                        DataSize.parse("32MB"),
                        Duration.ZERO,
                        Duration.ofMillis(200)))
                .hasMessage("tollgate.max-stream-duration must be positive, was PT0S");
    }

    @Test
    void requiresAPositiveScanTime() {
        assertThatThrownBy(() -> new GatewayProperties(
                        Path.of("tollgate.yaml"),
                        DataSize.parse("16MB"),
                        DataSize.parse("32MB"),
                        Duration.ofMinutes(10),
                        Duration.ZERO))
                .hasMessage("tollgate.max-scan-time must be positive, was PT0S");
    }
}
