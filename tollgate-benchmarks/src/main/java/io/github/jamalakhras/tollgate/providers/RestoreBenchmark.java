package io.github.jamalakhras.tollgate.providers;

import io.github.jamalakhras.tollgate.benchmarks.Workload;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.redact.Detector;
import io.github.jamalakhras.tollgate.core.redact.Placeholders;
import io.github.jamalakhras.tollgate.core.redact.Redactor;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class RestoreBenchmark {

    private ProviderResponse response;
    private List<List<String>> events;
    private Placeholders placeholders;

    @Setup
    public void setUp() {
        Redactor redactor = new Redactor(Set.of(), Set.copyOf(Detector.BUILT_IN));
        redactor.redact(Workload.chatRequest(Workload.SMALL, false));
        placeholders = redactor.placeholders();
        response = new ProviderResponse(200, Workload.completion().getBytes(StandardCharsets.UTF_8), null, null);
        events = Workload.streamEvents().stream().map(List::of).toList();
    }

    @Benchmark
    public ProviderResponse wholeAnswer() {
        return response.withModel("stub").restored(WireFormat.OPENAI, placeholders);
    }

    @Benchmark
    public ProviderResponse wholeAnswerWithoutRedaction() {
        return response.withModel("stub");
    }

    @Benchmark
    public void streamedAnswer(Blackhole sink) {
        stream(new StreamRewriter(WireFormat.OPENAI, "stub", false, placeholders), sink);
    }

    @Benchmark
    public void streamedAnswerWithoutRedaction(Blackhole sink) {
        stream(new StreamRewriter(WireFormat.OPENAI, "stub", false), sink);
    }

    private void stream(StreamRewriter rewriter, Blackhole sink) {
        for (List<String> event : events) {
            Optional<byte[]> relayed = rewriter.rewrite(event);
            sink.consume(relayed);
        }
        sink.consume(rewriter.finish());
    }
}
