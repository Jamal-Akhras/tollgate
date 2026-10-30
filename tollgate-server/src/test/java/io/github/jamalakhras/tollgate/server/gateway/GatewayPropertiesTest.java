package io.github.jamalakhras.tollgate.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

class GatewayPropertiesTest {

    @Test
    void convertsTheLimitToBytes() {
        assertThat(new GatewayProperties(Path.of("tollgate.yaml"), DataSize.parse("16MB")).maxRequestBytes())
                .isEqualTo(16 * 1024 * 1024);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0B", "513MB"})
    void keepsTheLimitWithinBounds(String size) {
        assertThatThrownBy(() -> new GatewayProperties(Path.of("tollgate.yaml"), DataSize.parse(size)))
                .hasMessageStartingWith("tollgate.max-request-size must be between 1B and 512MB");
    }
}
