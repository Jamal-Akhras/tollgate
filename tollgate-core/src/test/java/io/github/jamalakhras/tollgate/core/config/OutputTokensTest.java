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
        assertThatThrownBy(() -> new OutputTokens(0, 10)).hasMessage("default and cap must be positive");
        assertThatThrownBy(() -> new OutputTokens(1, 0)).hasMessage("default and cap must be positive");
        assertThatThrownBy(() -> new OutputTokens(11, 10)).hasMessage("default must not exceed cap");
    }
}
