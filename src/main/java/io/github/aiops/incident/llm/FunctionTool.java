package io.github.aiops.incident.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Function;

public record FunctionTool(
        String name,
        String description,
        JsonNode parameters,
        Function<JsonNode, JsonNode> executor
) {
}
