package io.github.jamalakhras.tollgate.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class TollgateTest {

    @Test
    void usesTheLocalGatewayWhenNoAddressIsGiven() {
        assertThat(Tollgate.baseUrl(null)).isEqualTo("http://localhost:8080");
        assertThat(Tollgate.baseUrl("")).isEqualTo("http://localhost:8080");
    }

    @Test
    void dropsTrailingSlashesFromTheAddress() {
        assertThat(Tollgate.baseUrl("https://gate.example.com/")).isEqualTo("https://gate.example.com");
        assertThat(Tollgate.baseUrl("https://gate.example.com//")).isEqualTo("https://gate.example.com");
        assertThat(Tollgate.baseUrl("https://gate.example.com")).isEqualTo("https://gate.example.com");
    }

    @Test
    void returnsTheKeyItWasGiven() {
        assertThat(Tollgate.apiKey("tg_abc")).isEqualTo("tg_abc");
    }

    @Test
    void refusesAMissingKeyAndSaysHowToSetOne() {
        assertThatThrownBy(() -> Tollgate.apiKey(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Set TOLLGATE_API_KEY or pass an API key");
        assertThatThrownBy(() -> Tollgate.apiKey("")).hasMessage("Set TOLLGATE_API_KEY or pass an API key");
    }

    @Test
    void readsWholeNumbersAndIgnoresTheRest() {
        assertThat(Tollgate.number("7")).isEqualTo(7L);
        assertThat(Tollgate.number("+7")).isNull();
        assertThat(Tollgate.number("-7")).isNull();
        assertThat(Tollgate.number("Wed, 21 Oct 2026 07:28:00 GMT")).isNull();
        assertThat(Tollgate.number(null)).isNull();
    }

    @Test
    void buildsAReceiptFromWhatTheHeadersHold() {
        Receipt receipt = Receipt.of(name -> name.equals(Tollgate.COST) ? List.of("0.04 USD") : List.of());

        assertThat(receipt).isEqualTo(new Receipt(null, "0.04 USD", null, null));
    }

    @Test
    void buildsARefusalWithoutAMessage() {
        Refusal refusal = Refusal.of(name -> name.equals(Tollgate.ERROR) ? List.of("x") : List.of(), 400, null)
                .orElseThrow();

        assertThat(refusal).isEqualTo(new Refusal("x", "", 400, null, null));
    }
}
