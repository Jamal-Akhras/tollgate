package io.github.jamalakhras.tollgate.benchmarks;

import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.core.config.ConfigReader;
import io.github.jamalakhras.tollgate.core.config.GatewayConfig;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

final class Configs {

    static final String TENANT = "bench";
    static final String MODEL = "stub";

    private static final List<String> CLASSES = List.of("public", "internal", "confidential");

    private Configs() {}

    static GatewayConfig gateway() {
        ApiKey key = ApiKey.generate(new SecureRandom());
        Map<String, Object> document = Map.of(
                "dataClasses",
                CLASSES,
                "providers",
                Map.of("stub", Map.of("format", "openai", "baseUrl", "http://127.0.0.1:18434/v1", "region", "local")),
                "models",
                Map.of(
                        MODEL,
                        Map.of(
                                "provider",
                                "stub",
                                "upstream",
                                MODEL,
                                "price",
                                Map.of("input", 1, "output", 2, "cacheRead", 1, "cacheWrite", 1),
                                "maxOutputTokens",
                                Map.of("default", 256, "cap", 1024))),
                "tenants",
                Map.of(
                        TENANT,
                        Map.of(
                                "models",
                                List.of(MODEL),
                                "dataClass",
                                "internal",
                                "budget",
                                Map.of("limit", 1_000_000, "period", "total"))),
                "keys",
                List.of(Map.of("id", key.id(), "tenant", TENANT, "sha256", key.sha256())),
                "policies",
                Map.of(
                        "default",
                        Map.of(
                                "guards",
                                List.of(
                                        Map.of("type", "request-size", "maxBytes", 2 * 1024 * 1024),
                                        Map.of(
                                                "type",
                                                "data-class",
                                                "regions",
                                                Map.of(
                                                        "public",
                                                        List.of("local"),
                                                        "internal",
                                                        List.of("local"),
                                                        "confidential",
                                                        List.of("local")))))),
                "audit",
                Map.of("mode", "off"));
        return ConfigReader.read(document);
    }
}
