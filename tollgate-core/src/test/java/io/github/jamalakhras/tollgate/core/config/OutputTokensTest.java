package io.github.jamalakhras.tollgate.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OutputTokensTest {

    @Test
    void capsTheRequestedLimit() {
        var tokens = new OutputTokens(1_024, 4_096);

        assertThat(tokens.limit(100)).isEqualTo(100);
        assertThat(tokens.limit(10_000)).isEqualTo(4_096);
    }

    @Test
    void acceptsTheSmallestLimits() {
        assertThat(new OutputTokens(1, 1).cap()).isOne();
    }

    @Test
    void rejectsLimitsThatCannotBeHonoured() {
        assertThatThrownBy(() -> new OutputTokens(0, 10)).hasMessage("default, cap and floor must be positive");
        assertThatThrownBy(() -> new OutputTokens(1, 0)).hasMessage("default, cap and floor must be positive");
        assertThatThrownBy(() -> new OutputTokens(1, 10, 0)).hasMessage("default, cap and floor must be positive");
        assertThatThrownBy(() -> new OutputTokens(11, 10)).hasMessage("default must not exceed cap");
        assertThatThrownBy(() -> new OutputTokens(1, 10, 11)).hasMessage("floor must not exceed cap");
    }

    @Test
    void defaultsTheFloorTo256OrTheCap() {
        assertThat(new OutputTokens(1_024, 4_096).floor()).isEqualTo(256);
        assertThat(new OutputTokens(100, 100).floor()).isEqualTo(100);
        assertThat(new OutputTokens(1, 256).floor()).isEqualTo(256);
    }

    @Test
    void lowersEveryLimitToFitASmallerCap() {
        assertThat(new OutputTokens(1_024, 4_096, 300).loweredTo(500)).isEqualTo(new OutputTokens(500, 500, 300));
        assertThat(new OutputTokens(200, 4_096, 300).loweredTo(250)).isEqualTo(new OutputTokens(200, 250, 250));
    }
}
