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

    default JsonNode completeJsonObject(
            String systemPrompt,
            String userPrompt,
            String requestName
    ) {
        return completeJson(systemPrompt, userPrompt, requestName, null, List.of(), false);
    }

    default String completeText(
            String systemPrompt,
            String userPrompt,
            String requestName
    ) {
        JsonNode response = completeJson(systemPrompt, userPrompt, requestName, null, List.of(), false);
        return response == null ? null : response.isTextual() ? response.asText() : response.toString();
    }
}
