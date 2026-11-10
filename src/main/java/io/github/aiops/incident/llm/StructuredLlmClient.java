package io.github.aiops.incident.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public interface StructuredLlmClient {

    JsonNode completeJson(
            String systemPrompt,
            String userPrompt,
            String schemaName,
            JsonNode outputSchema,
            List<FunctionTool> tools,
            boolean requireToolCall
    );
}
