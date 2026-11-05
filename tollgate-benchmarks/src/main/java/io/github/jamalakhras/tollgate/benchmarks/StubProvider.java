package io.github.jamalakhras.tollgate.benchmarks;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

public final class StubProvider {

    private static final long STREAM_GAP_MILLIS = 10;
    private static final byte[] COMPLETION = Workload.completion().getBytes(StandardCharsets.UTF_8);
    private static final List<byte[]> EVENTS = Workload.streamEvents().stream()
            .map(event -> (event + "\n\n").getBytes(StandardCharsets.UTF_8))
            .toList();

    private StubProvider() {}

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 18434;
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1024);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/chat/completions", StubProvider::answer);
        server.start();
        System.out.println("Stub provider listening on 127.0.0.1:" + port);
    }

    private static void answer(HttpExchange exchange) throws IOException {
        try (exchange) {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (request.contains("\"stream\":true")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                stream(exchange.getResponseBody());
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, COMPLETION.length);
                exchange.getResponseBody().write(COMPLETION);
            }
        }
    }

    private static void stream(OutputStream out) throws IOException {
        for (int i = 0; i < EVENTS.size(); i++) {
            out.write(EVENTS.get(i));
            out.flush();
            if (i < EVENTS.size() - 2) {
                pause();
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(STREAM_GAP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
