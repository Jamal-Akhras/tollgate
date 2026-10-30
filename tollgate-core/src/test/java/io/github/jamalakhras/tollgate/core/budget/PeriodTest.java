package io.github.jamalakhras.tollgate.core.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.money.Prices;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PeriodTest {

    @ParameterizedTest
    @CsvSource({
        "DAILY,   2026-10-29T23:59:59Z, 2026-10-29T00:00:00Z",
        "MONTHLY, 2026-10-29T12:00:00Z, 2026-10-01T00:00:00Z",
        "MONTHLY, 2026-11-01T00:00:00Z, 2026-11-01T00:00:00Z",
        "TOTAL,   2026-10-29T12:00:00Z, 1970-01-01T00:00:00Z"
    })
    void startsInUtc(Period period, Instant now, Instant start) {
        assertThat(period.start(now)).isEqualTo(start);
    }

    @Test
    void readsConfigNames() {
        assertThat(Period.fromConfigName("monthly")).isEqualTo(Period.MONTHLY);
        assertThatThrownBy(() -> Period.fromConfigName("MONTHLY"))
                .hasMessage("period must be one of daily, monthly, total, was MONTHLY");
    }

    @Test
    void budgetsCannotBeNegative() {
        assertThatThrownBy(() -> new Budget(Prices.usd("-1"), Period.DAILY)).hasMessage("limit must not be negative");
    }
}
