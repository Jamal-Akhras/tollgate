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

    private static GatewayProperties properties(String request, String response, Duration stream, Duration scan) {
        return new GatewayProperties(
                Path.of("tollgate.yaml"), DataSize.parse(request), DataSize.parse(response), stream, scan);
    }

    @Test
    void convertsTheLimitToBytes() {
        assertThat(properties("16MB", "32MB", Duration.ofMinutes(10), Duration.ofMillis(200))
                        .maxRequestBytes())
                .isEqualTo(16 * 1024 * 1024);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0B", "513MB"})
    void keepsTheLimitWithinBounds(String size) {
        assertThatThrownBy(() -> properties(size, "32MB", Duration.ofMinutes(10), Duration.ofMillis(200)))
                .hasMessageStartingWith("tollgate.max-request-size must be between 1B and 512MB");
        assertThatThrownBy(() -> properties("16MB", size, Duration.ofMinutes(10), Duration.ofMillis(200)))
                .hasMessageStartingWith("tollgate.max-response-size must be between 1B and 512MB");
    }

    @Test
    void requiresAPositiveStreamDuration() {
        assertThatThrownBy(() -> properties("16MB", "32MB", Duration.ZERO, Duration.ofMillis(200)))
                .hasMessage("tollgate.max-stream-duration must be positive, was PT0S");
    }

    @Test
    void requiresAPositiveScanTime() {
        assertThatThrownBy(() -> properties("16MB", "32MB", Duration.ofMinutes(10), Duration.ZERO))
                .hasMessage("tollgate.max-scan-time must be positive, was PT0S");
    }
}
