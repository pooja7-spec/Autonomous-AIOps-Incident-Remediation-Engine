package io.github.aiops.incident.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Objects;

@Component
public class OpenAiStructuredLlmClient implements StructuredLlmClient {

    private static final int MAX_TOOL_ROUNDS = 3;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public OpenAiStructuredLlmClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${aiops.llm.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${aiops.llm.api-key:}") String apiKey,
            @Value("${aiops.llm.model:gpt-4o-mini}") String model
    ) {
        this.restClient = restClientBuilder.baseUrl(Objects.requireNonNull(baseUrl, "baseUrl")).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public JsonNode completeJson(
            String systemPrompt,
            String userPrompt,
            String schemaName,
            JsonNode outputSchema,
            List<FunctionTool> tools,
            boolean requireToolCall
    ) {
        if (apiKey.isBlank()) {
            throw new LlmIntegrationException(
                    "OpenAI mode requires the OPENAI_API_KEY environment variable."
            );
        }

        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        boolean toolCalled = false;

        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            JsonNode response = request(messages, schemaName, outputSchema, tools, requireToolCall && !toolCalled);
            JsonNode assistantMessage = response.path("choices").path(0).path("message");
            if (!assistantMessage.isObject()) {
                throw new LlmIntegrationException("The LLM provider response did not contain a message.");
            }
            if (!assistantMessage.path("refusal").isMissingNode()
                    && !assistantMessage.path("refusal").isNull()) {
                throw new LlmIntegrationException("The LLM provider refused the analysis request.");
            }

            JsonNode toolCalls = assistantMessage.path("tool_calls");
            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                if (round == MAX_TOOL_ROUNDS) {
                    throw new LlmIntegrationException("The LLM exceeded the allowed tool-call limit.");
                }
                if (toolCalls.size() != 1) {
                    throw new LlmIntegrationException("The LLM returned an unsupported number of tool calls.");
                }
                messages.add(assistantMessage.deepCopy());
                for (JsonNode toolCall : toolCalls) {
                    executeTool(toolCall, tools, messages);
                }
                toolCalled = true;
                continue;
            }

            if (requireToolCall && !toolCalled) {
                throw new LlmIntegrationException("The LLM returned without using its required evidence tool.");
            }
            String content = assistantMessage.path("content").asText();
            if (content.isBlank()) {
                throw new LlmIntegrationException("The LLM returned an empty structured response.");
            }
            try {
                return objectMapper.readTree(content);
            } catch (JsonProcessingException exception) {
                throw new LlmIntegrationException("The LLM returned invalid JSON.", exception);
            }
        }
        throw new LlmIntegrationException("The LLM did not complete the request.");
    }

    private JsonNode request(
            ArrayNode messages,
            String schemaName,
            JsonNode outputSchema,
            List<FunctionTool> tools,
            boolean requireToolCall
    ) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        request.put("max_completion_tokens", 1_000);
        request.set("messages", messages);
        ObjectNode responseFormat = request.putObject("response_format");
        responseFormat.put("type", "json_schema");
        ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", schemaName);
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", outputSchema);

        if (!tools.isEmpty()) {
            ArrayNode toolDefinitions = request.putArray("tools");
            for (FunctionTool tool : tools) {
                ObjectNode definition = toolDefinitions.addObject();
                definition.put("type", "function");
                ObjectNode function = definition.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.put("strict", true);
                function.set("parameters", tool.parameters());
            }
            request.put("tool_choice", requireToolCall ? "required" : "auto");
            request.put("parallel_tool_calls", false);
        }

        try {
            JsonNode response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new LlmIntegrationException("The LLM provider returned an empty response.");
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw new LlmIntegrationException(
                    "The LLM provider returned HTTP " + exception.getStatusCode().value() + ".",
                    exception
            );
        } catch (ResourceAccessException exception) {
            throw new LlmIntegrationException("The LLM provider could not be reached.", exception);
        }
    }

    private void executeTool(JsonNode toolCall, List<FunctionTool> tools, ArrayNode messages) {
        String toolCallId = toolCall.path("id").asText();
        String name = toolCall.path("function").path("name").asText();
        String arguments = toolCall.path("function").path("arguments").asText();
        if (toolCallId.isBlank()) {
            throw new LlmIntegrationException("The LLM returned a tool call without an identifier.");
        }
        FunctionTool tool = tools.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new LlmIntegrationException("The LLM requested an unknown tool."));

        try {
            JsonNode argumentsNode = objectMapper.readTree(arguments);
            JsonNode result = tool.executor().apply(argumentsNode);
            if (result == null) {
                throw new LlmIntegrationException("The host tool returned an empty result.");
            }
            ObjectNode toolMessage = message("tool", result.toString());
            toolMessage.put("tool_call_id", toolCallId);
            messages.add(toolMessage);
        } catch (JsonProcessingException exception) {
            throw new LlmIntegrationException("The LLM supplied invalid tool arguments.", exception);
        } catch (IllegalArgumentException exception) {
            throw new LlmIntegrationException("The LLM supplied invalid arguments for " + name + ".", exception);
        }
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}
