package io.github.jamalakhras.tollgate.gateway;

import io.github.jamalakhras.tollgate.providers.ProviderClient;
import io.micrometer.tracing.Tracer;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class TestGateway {

    static final StreamTarget NOT_STREAMED = new StreamTarget() {
        @Override
        public void open(Map<String, String> headers) {
            throw new AssertionError("Not a streamed request");
        }

        @Override
        public void write(byte[] event) {
            throw new AssertionError("Not a streamed request");
        }
    };

    private TestGateway() {}

    static Gateway start(Path config, Function<String, @Nullable String> environment, HttpClient http, Clock clock) {
        return GatewayConfiguration.start(
                ConfigFile.read(config),
                environment,
                new ProviderClient(http, 1_024 * 1_024),
                clock,
                1_000_000,
                Duration.ofMinutes(1),
                Duration.ofSeconds(1),
                Tracer.NOOP);
    }
}
