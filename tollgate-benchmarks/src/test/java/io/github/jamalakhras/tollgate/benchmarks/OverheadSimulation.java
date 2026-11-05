package io.github.jamalakhras.tollgate.benchmarks;

import static io.gatling.javaapi.core.CoreDsl.RawFileBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.core.CoreDsl.substring;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.sse;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.CoreDsl;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;

@SuppressWarnings("this-escape")
public class OverheadSimulation extends Simulation {

    private static final String PATH = "/v1/chat/completions";
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final String target = System.getProperty("target", "http://127.0.0.1:18080");
    private final String key = System.getProperty("key", "none");
    private final String body = System.getProperty("body", "request-2kb.json");
    private final double rate = Double.parseDouble(System.getProperty("rate", "50"));
    private final int duration = Integer.parseInt(System.getProperty("duration", "120"));
    private final String stream = System.getProperty("stream", "none");

    {
        HttpProtocolBuilder protocol = http.baseUrl(target)
                .contentTypeHeader("application/json")
                .authorizationHeader("Bearer " + key)
                .shareConnections();
        setUp(scenario("chat").exec(call()).injectOpen(constantUsersPerSec(rate).during(duration)))
                .protocols(protocol);
    }

    private ChainBuilder call() {
        return switch (stream) {
            case "first" ->
                CoreDsl.exec(sse("stream")
                                .post(PATH)
                                .body(RawFileBody(body))
                                .await(WAIT)
                                .on(sse.checkMessage("first event").check(substring("chat.completion.chunk"))))
                        .exec(sse("stream").close());
            case "last" ->
                CoreDsl.exec(sse("stream")
                                .post(PATH)
                                .body(RawFileBody(body))
                                .await(WAIT)
                                .on(sse.checkMessage("last event")
                                        .matching(substring("[DONE]"))
                                        .check(substring("[DONE]"))))
                        .exec(sse("stream").close());
            default ->
                CoreDsl.exec(http("chat").post(PATH).body(RawFileBody(body)).check(status().is(200)));
        };
    }
}
