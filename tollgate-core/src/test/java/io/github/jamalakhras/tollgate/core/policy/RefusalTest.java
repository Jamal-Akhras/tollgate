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
        assertThat(Refusal.TOO_LARGE.code()).isEqualTo("too_large");
        assertThatThrownBy(() -> Refusal.fromCode("TOO_LARGE")).hasMessage("Unknown refusal: TOO_LARGE");
    }
}
