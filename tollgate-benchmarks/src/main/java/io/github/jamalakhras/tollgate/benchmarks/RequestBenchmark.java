package io.github.jamalakhras.tollgate.benchmarks;

import io.github.jamalakhras.tollgate.core.config.Admission;
import io.github.jamalakhras.tollgate.core.config.GatewayConfig;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.policy.RequestFeatures;
import io.github.jamalakhras.tollgate.providers.ChatRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class RequestBenchmark {

    @Param({"2048", "32768", "1048576"})
    int bytes;

    private byte[] body;
    private GatewayConfig config;
    private GatewayConfig.Route route;
    private RequestFeatures features;

    @Setup
    public void setUp() {
        body = Workload.chatRequest(bytes, false).getBytes(StandardCharsets.UTF_8);
        config = Configs.gateway();
        route = config.route(Configs.TENANT, Configs.MODEL).orElseThrow();
        features = ChatRequest.parse(body).review(WireFormat.OPENAI, null, List.of());
    }

    @Benchmark
    public ChatRequest parse() {
        return ChatRequest.parse(body);
    }

    @Benchmark
    public RequestFeatures parseAndReview() {
        return ChatRequest.parse(body).review(WireFormat.OPENAI, null, List.of());
    }

    @Benchmark
    public ChatRequest.Forwarded parseAndForward() {
        return ChatRequest.parse(body)
                .forward(Configs.MODEL, WireFormat.OPENAI, route.definition().maxOutputTokens(), 0, true);
    }

    @Benchmark
    public Admission admit() {
        return config.admit(Configs.TENANT, route, null, body.length, features);
    }
}
