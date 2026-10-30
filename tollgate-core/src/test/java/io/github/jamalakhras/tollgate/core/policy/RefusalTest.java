package io.github.jamalakhras.tollgate.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RefusalTest {

    @ParameterizedTest
    @EnumSource(Refusal.class)
    void readsEachRefusalBackFromItsCode(Refusal refusal) {
        assertThat(Refusal.fromCode(refusal.code())).isEqualTo(refusal);
    }

    @Test
    void writesCodesInLowerCase() {
        assertThat(Refusal.BUDGET_EXCEEDED.code()).isEqualTo("budget_exceeded");
        assertThatThrownBy(() -> Refusal.fromCode("BUDGET_EXCEEDED")).hasMessage("Unknown refusal: BUDGET_EXCEEDED");
    }
}
