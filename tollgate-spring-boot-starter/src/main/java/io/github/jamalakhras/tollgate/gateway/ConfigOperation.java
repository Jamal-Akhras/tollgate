package io.github.jamalakhras.tollgate.gateway;

import java.util.Map;
import java.util.function.UnaryOperator;

public record ConfigOperation(String name, String path, UnaryOperator<Map<String, Object>> change) {}
