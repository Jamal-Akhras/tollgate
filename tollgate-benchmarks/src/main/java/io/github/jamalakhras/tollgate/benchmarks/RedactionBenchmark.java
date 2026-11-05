package io.github.jamalakhras.tollgate.benchmarks;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.redact.Detector;
import io.github.jamalakhras.tollgate.core.redact.Redactor;
import io.github.jamalakhras.tollgate.providers.ChatRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
public class RedactionBenchmark {

    private static final Duration BUDGET = Duration.ofSeconds(10);

    @Param({"2048", "32768"})
    int bytes;

    private ChatRequest request;
    private ChatRequest clean;
    private Set<Detector> builtIn;
    private Set<Detector> withTerms;
    private Set<Detector> withManyTerms;
    private Set<Detector> withPattern;

    @Setup
    public void setUp() {
        request = ChatRequest.parse(Workload.chatRequest(bytes, false).getBytes(StandardCharsets.UTF_8));
        clean = ChatRequest.parse(Workload.cleanChatRequest(bytes).getBytes(StandardCharsets.UTF_8));
        builtIn = Set.copyOf(Detector.BUILT_IN);
        withTerms = with(builtIn, Detector.terms("clients", Workload.terms(), false));
        List<String> many =
                IntStream.range(0, 10_000).mapToObj(i -> "Client " + i).toList();
        withManyTerms = with(builtIn, Detector.terms("clients", many, false));
        withPattern = with(builtIn, Detector.pattern("order", "order (?<value>[0-9]{5})"));
    }

    private static Set<Detector> with(Set<Detector> detectors, Detector extra) {
        Set<Detector> all = new HashSet<>(detectors);
        all.add(extra);
        return Set.copyOf(all);
    }

    @Benchmark
    public ChatRequest builtInDetectors() {
        return request.redacted(WireFormat.OPENAI, new Redactor(Set.of(), builtIn, BUDGET));
    }

    @Benchmark
    public ChatRequest builtInDetectorsFindingNothing() {
        return clean.redacted(WireFormat.OPENAI, new Redactor(Set.of(), builtIn, BUDGET));
    }

    @Benchmark
    public ChatRequest withAThousandTerms() {
        return request.redacted(WireFormat.OPENAI, new Redactor(Set.of(), withTerms, BUDGET));
    }

    @Benchmark
    public ChatRequest withTenThousandTerms() {
        return request.redacted(WireFormat.OPENAI, new Redactor(Set.of(), withManyTerms, BUDGET));
    }

    @Benchmark
    public ChatRequest withACustomPattern() {
        return request.redacted(WireFormat.OPENAI, new Redactor(Set.of(), withPattern, BUDGET));
    }
}
