package io.github.aiops.incident.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class OpenAiCompatibleStructuredLlmClient implements StructuredLlmClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiCompatibleStructuredLlmClient.class);
    private static final Pattern SAFE_DIAGNOSTIC_TOKEN = Pattern.compile("[^A-Za-z0-9_.-]");
    private static final int MAX_DIAGNOSTIC_TOKEN_LENGTH = 64;
    private static final int MAX_ERROR_BODY_PARSE_CHARACTERS = 8_192;
    private static final Set<String> SAFE_UPSTREAM_ERROR_CODES = Set.of(
            "authentication_error",
            "context_length_exceeded",
            "failed_generation",
            "insufficient_quota",
            "invalid_api_key",
            "invalid_function_parameters",
            "invalid_json_schema",
            "invalid_request_error",
            "json_validate_failed",
            "model_not_found",
            "permission_error",
            "rate_limit_exceeded",
            "tool_use_failed"
    );
    private static final Set<String> SAFE_UPSTREAM_ERROR_CATEGORIES = Set.of(
            "EMPTY_PARAMETERS_SCHEMA",
            "INVALID_TOOL_SCHEMA",
            "UNSUPPORTED_STRICT_SCHEMA",
            "INVALID_TOOL_CHOICE",
            "OTHER_INVALID_REQUEST"
    );
    private static final Pattern SAFE_UPSTREAM_PARAM = Pattern.compile(
            "(?:tools(?:\\[\\d+\\])?(?:\\.function(?:\\.(?:name|description|strict|parameters"
                    + "(?:\\.[A-Za-z0-9_]+(?:\\[\\d+\\])?)?))?)?|tool_choice|parallel_tool_calls"
                    + "|response_format(?:\\.json_schema(?:\\.[A-Za-z0-9_]+)?)?|model|max_completion_tokens"
                    + "|messages(?:\\[\\d+\\](?:\\.[A-Za-z0-9_]+)?)?)"
    );
    private static final List<String> SAFE_UPSTREAM_ERROR_TYPES = List.of(
            "authentication_error",
            "context_length_exceeded",
            "gateway_error",
            "insufficient_quota",
            "invalid_request_error",
            "model_not_found",
            "permission_error",
            "rate_limit_exceeded",
            "server_error",
            "service_unavailable",
            "upstream_failure"
    );
    private record UpstreamErrorDetails(String type, String code, String param, String category) {
    }

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String provider;

    public OpenAiCompatibleStructuredLlmClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${aiops.llm.base-url:https://api.groq.com/openai/v1}") String baseUrl,
            @Value("${aiops.llm.api-key:}") String apiKey,
            @Value("${aiops.llm.model:openai/gpt-oss-120b}") String model,
            @Value("${aiops.llm.provider:groq}") String provider,
            @Value("${aiops.analysis.mode:demo}") String analysisMode
    ) {
        this.restClient = restClientBuilder.baseUrl(Objects.requireNonNull(baseUrl, "baseUrl")).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.provider = provider;
        boolean apiKeyConfigured = !this.apiKey.isBlank();
        LOGGER.info("LLM provider '{}' API key configured: {}", provider, apiKeyConfigured);
        if (isLlmMode(analysisMode) && !apiKeyConfigured) {
            throw new IllegalStateException(
                    "LLM analysis is enabled but no API key is configured for provider '" + provider
                            + "'. Set AIOPS_LLM_API_KEY or the provider-specific environment variable."
            );
        }
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
        return complete(systemPrompt, userPrompt, schemaName, outputSchema, tools, requireToolCall, false);
    }

    @Override
    public JsonNode completeJsonObject(String systemPrompt, String userPrompt, String requestName) {
        return complete(systemPrompt, userPrompt, requestName, null, List.of(), false, true);
    }

    @Override
    public String completeText(String systemPrompt, String userPrompt, String requestName) {
        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        String safeRequestName = safeDiagnosticToken(requestName, "unknown");
        JsonNode response = sendRequest(baseRequest(messages), safeRequestName + " text-generation request");
        JsonNode assistantMessage = readAssistantMessage(
                response,
                safeRequestName + " text-generation response parsing"
        );
        JsonNode toolCalls = assistantMessage.get("tool_calls");
        if (toolCalls != null && !toolCalls.isNull() && (!toolCalls.isArray() || !toolCalls.isEmpty())) {
            throw diagnosticFailure(
                    "The LLM returned an unexpected tool call during text generation.",
                    safeRequestName + " text-generation response parsing",
                    "response_validation"
            );
        }
        String content = assistantMessage.path("content").asText();
        if (content.isBlank()) {
            throw diagnosticFailure(
                    "The LLM returned an empty text response.",
                    safeRequestName + " text-generation response parsing",
                    "response_validation"
            );
        }
        return content;
    }

    private JsonNode complete(
            String systemPrompt,
            String userPrompt,
            String schemaName,
            JsonNode outputSchema,
            List<FunctionTool> tools,
            boolean requireToolCall,
            boolean jsonObjectMode
    ) {
        if (requireToolCall && tools.isEmpty()) {
            throw diagnosticFailure(
                    "A required tool call was requested, but no tools are configured.",
                    "tool configuration validation",
                    "configuration_error"
            );
        }

        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        String requestName = safeDiagnosticToken(schemaName, "unknown");
        boolean toolCalled = false;
        JsonNode toolResult = null;

        if (!tools.isEmpty()) {
            JsonNode assistantMessage = readAssistantMessage(
                    requestToolCall(messages, tools, requireToolCall, requestName),
                    requestName + " tool-call response parsing"
            );
            JsonNode toolCalls = assistantMessage.get("tool_calls");
            if (toolCalls != null && !toolCalls.isNull()) {
                if (!toolCalls.isArray()) {
                    throw diagnosticFailure(
                            "The LLM returned malformed tool calls.",
                            requestName + " tool-call response parsing",
                            "response_validation"
                    );
                }
                if (toolCalls.size() > 1) {
                    throw diagnosticFailure(
                            "The LLM returned an unsupported number of tool calls.",
                            requestName + " tool-call response parsing",
                            "response_validation"
                    );
                }
                if (!toolCalls.isEmpty()) {
                    toolResult = executeTool(toolCalls.get(0), tools, requestName);
                    toolCalled = true;
                }
            }
            if (requireToolCall && !toolCalled) {
                throw diagnosticFailure(
                        "The LLM returned without using its required evidence tool.",
                        requestName + " tool-call response parsing",
                        "response_validation"
                );
            }
        }

        ArrayNode analysisMessages = objectMapper.createArrayNode();
        analysisMessages.add(message("system", systemPrompt));
        analysisMessages.add(message("user", userPrompt));
        if (toolResult != null) {
            analysisMessages.add(message("user", "Evidence tool result:\n" + toolResult));
        }
        JsonNode finalMessage = readAssistantMessage(
                requestStructuredAnalysis(
                        analysisMessages,
                        schemaName,
                        outputSchema,
                        requestName,
                        jsonObjectMode
                ),
                requestName + " structured JSON parsing"
        );
        JsonNode toolCalls = finalMessage.get("tool_calls");
        if (toolCalls != null && !toolCalls.isNull() && (!toolCalls.isArray() || !toolCalls.isEmpty())) {
            throw diagnosticFailure(
                    "The LLM returned a tool call during structured analysis.",
                    requestName + " structured JSON parsing",
                    "response_validation"
            );
        }
        String content = finalMessage.path("content").asText();
        if (content.isBlank()) {
            throw diagnosticFailure(
                    "The LLM returned an empty structured response.",
                    requestName + " structured JSON parsing",
                    "response_validation"
            );
        }
        try {
            return objectMapper.readTree(content);
        } catch (JsonProcessingException exception) {
            throw new LlmIntegrationException(
                    "The LLM returned invalid JSON.",
                    exception,
                    provider,
                    requestName + " structured JSON parsing",
                    "response_validation"
            );
        }
    }

    private JsonNode requestToolCall(
            ArrayNode messages,
            List<FunctionTool> tools,
            boolean requireToolCall,
            String requestName
    ) {
        ObjectNode request = baseRequest(messages);
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
        return sendRequest(request, requestName + " tool-call request");
    }

    private JsonNode requestStructuredAnalysis(
            ArrayNode messages,
            String schemaName,
            JsonNode outputSchema,
            String requestName,
            boolean jsonObjectMode
    ) {
        ObjectNode request = baseRequest(messages);
        ObjectNode responseFormat = request.putObject("response_format");
        if (jsonObjectMode) {
            responseFormat.put("type", "json_object");
        } else {
            responseFormat.put("type", "json_schema");
            ObjectNode jsonSchema = responseFormat.putObject("json_schema");
            jsonSchema.put("name", schemaName);
            jsonSchema.put("strict", true);
            jsonSchema.set("schema", outputSchema);
        }
        return sendRequest(request, requestName + " structured-analysis request");
    }

    private ObjectNode baseRequest(ArrayNode messages) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        request.put("max_completion_tokens", 1_000);
        request.set("messages", messages);
        return request;
    }

    private JsonNode sendRequest(ObjectNode request, String stage) {
        try {
            JsonNode response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", authorizationHeader())
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw diagnosticFailure(
                        "The LLM provider returned an empty response.",
                        stage + " response validation",
                        "response_validation"
                );
            }
            return response;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            UpstreamErrorDetails errorDetails = extractErrorDetails(exception);
            String safeProvider = safeDiagnosticToken(provider, "unknown");
            String safeStage = stage;
            String message = "LLM provider request failed during " + safeStage
                    + " (HTTP " + status + "; upstream error type: " + errorDetails.type() + ").";
            throw new LlmIntegrationException(
                    message,
                    exception,
                    safeProvider,
                    safeStage,
                    status,
                    errorDetails.type(),
                    errorDetails.code(),
                    errorDetails.param(),
                    errorDetails.category()
            );
        } catch (ResourceAccessException exception) {
            throw new LlmIntegrationException(
                    "The LLM provider could not be reached.",
                    exception,
                    safeDiagnosticToken(provider, "unknown"),
                    stage,
                    "network_error"
            );
        }
    }

    private UpstreamErrorDetails extractErrorDetails(RestClientResponseException exception) {
        try {
            String responseBody = exception.getResponseBodyAsString();
            String boundedBody = responseBody.substring(
                    0,
                    Math.min(responseBody.length(), MAX_ERROR_BODY_PARSE_CHARACTERS)
            );
            JsonNode body = objectMapper.readTree(boundedBody);
            JsonNode error = body.path("error");
            return new UpstreamErrorDetails(
                    safeUpstreamErrorType(error.path("type").asText("")),
                    safeUpstreamErrorCode(error.path("code").asText("")),
                    safeUpstreamErrorParam(error.path("param").asText("")),
                    classifyUpstreamError(error.path("type").asText(""), error.path("message").asText(""))
            );
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            return new UpstreamErrorDetails("unknown", null, null, null);
        }
    }

    private String classifyUpstreamError(String errorType, String message) {
        if (!"invalid_request_error".equals(errorType)) {
            return null;
        }
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        String category;
        if ((normalized.contains("strict") || normalized.contains("strict mode"))
                && (normalized.contains("unsupported")
                || normalized.contains("not supported")
                || normalized.contains("does not support"))) {
            category = "UNSUPPORTED_STRICT_SCHEMA";
        } else if (normalized.contains("tool_choice") || normalized.contains("tool choice")) {
            category = "INVALID_TOOL_CHOICE";
        } else if ((normalized.contains("empty") || normalized.contains("no properties"))
                && (normalized.contains("parameter")
                || normalized.contains("schema")
                || normalized.contains("properties"))) {
            category = "EMPTY_PARAMETERS_SCHEMA";
        } else if ((normalized.contains("schema") || normalized.contains("tool"))
                && (normalized.contains("invalid")
                || normalized.contains("unsupported")
                || normalized.contains("not supported"))) {
            category = "INVALID_TOOL_SCHEMA";
        } else {
            category = "OTHER_INVALID_REQUEST";
        }
        return SAFE_UPSTREAM_ERROR_CATEGORIES.contains(category) ? category : null;
    }

    private String safeUpstreamErrorType(String value) {
        return SAFE_UPSTREAM_ERROR_TYPES.contains(value) ? value : "unknown";
    }

    private String safeUpstreamErrorCode(String value) {
        return SAFE_UPSTREAM_ERROR_CODES.contains(value) ? value : null;
    }

    private String safeUpstreamErrorParam(String value) {
        return value.length() <= 128 && SAFE_UPSTREAM_PARAM.matcher(value).matches()
                ? value
                : null;
    }

    private String safeDiagnosticToken(String value, String fallback) {
        String sanitized = SAFE_DIAGNOSTIC_TOKEN.matcher(value == null ? "" : value).replaceAll("_");
        if (sanitized.isBlank()) {
            return fallback;
        }
        return sanitized.substring(0, Math.min(sanitized.length(), MAX_DIAGNOSTIC_TOKEN_LENGTH));
    }

    private String authorizationHeader() {
        if (apiKey.isBlank()) {
            throw new LlmIntegrationException(
                    "LLM provider '" + provider + "' requires an API key; configure AIOPS_LLM_API_KEY "
                            + "or the provider-specific environment variable."
            );
        }
        return "Bearer " + apiKey;
    }

    private boolean isLlmMode(String mode) {
        return "llm".equalsIgnoreCase(mode) || "openai".equalsIgnoreCase(mode);
    }

    private JsonNode readAssistantMessage(JsonNode response, String stage) {
        JsonNode message = response.path("choices").path(0).path("message");
        if (!message.isObject()) {
            throw diagnosticFailure("The LLM provider response did not contain a message.", stage, "response_validation");
        }
        JsonNode refusal = message.get("refusal");
        if (refusal != null && !refusal.isNull() && !refusal.asText().isBlank()) {
            throw diagnosticFailure("The LLM provider refused the analysis request.", stage, "response_validation");
        }
        return message;
    }

    private LlmIntegrationException diagnosticFailure(String message, String stage, String category) {
        return new LlmIntegrationException(message, safeDiagnosticToken(provider, "unknown"), stage, category);
    }

    private JsonNode executeTool(
            JsonNode toolCall,
            List<FunctionTool> tools,
            String requestName
    ) {
        String toolCallId = toolCall.path("id").asText();
        JsonNode function = toolCall.path("function");
        String name = function.path("name").asText();
        JsonNode argumentsValue = function.get("arguments");
        if (toolCallId.isBlank() || !"function".equals(toolCall.path("type").asText())
                || name.isBlank() || argumentsValue == null || !argumentsValue.isTextual()) {
            throw diagnosticFailure(
                    "The LLM returned a malformed tool call.",
                    requestName + " tool-call response parsing",
                    "response_validation"
            );
        }
        FunctionTool tool = tools.stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> diagnosticFailure(
                        "The LLM requested an unknown tool.",
                        requestName + " tool-call response parsing",
                        "response_validation"
                ));

        JsonNode arguments;
        try {
            arguments = objectMapper.readTree(argumentsValue.asText());
            validateArguments(arguments, tool.parameters());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new LlmIntegrationException(
                    "The LLM supplied invalid tool arguments.",
                    exception,
                    safeDiagnosticToken(provider, "unknown"),
                    requestName + " tool-call response parsing",
                    "response_validation"
            );
        }

        try {
            JsonNode result = tool.executor().apply(arguments);
            if (result == null) {
                throw diagnosticFailure(
                        "The host tool returned an empty result.",
                        requestName + " tool execution",
                        "tool_execution"
                );
            }
            return result;
        } catch (RuntimeException exception) {
            String reasonCode = exception instanceof ToolValidationException validationException
                    ? validationException.getReason().name()
                    : ToolFailureReason.TOOL_EXECUTOR_FAILURE.name();
            throw new LlmIntegrationException(
                    "The host tool could not be executed.",
                    exception,
                    safeDiagnosticToken(provider, "unknown"),
                    requestName + " tool execution",
                    "tool_execution"
            ).withToolDiagnostics(
                    safeDiagnosticToken(tool.name(), "unknown"),
                    reasonCode,
                    safeCorrelationId(MDC.get("incidentCorrelationId"))
            );
        }
    }

    private String safeCorrelationId(String correlationId) {
        if (correlationId == null || !correlationId.matches("[A-Za-z0-9._-]{1,64}")) {
            return null;
        }
        return correlationId;
    }

    private void validateArguments(JsonNode arguments, JsonNode schema) {
        if (arguments == null || !arguments.isObject() || !"object".equals(schema.path("type").asText())) {
            throw new IllegalArgumentException("Tool arguments must be a JSON object.");
        }
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode field : required) {
                if (!arguments.has(field.asText())) {
                    throw new IllegalArgumentException("A required tool argument is missing.");
                }
            }
        }

        JsonNode properties = schema.path("properties");
        boolean additionalPropertiesAllowed = schema.path("additionalProperties").asBoolean(true);
        var fields = arguments.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode propertySchema = properties.get(field.getKey());
            if (propertySchema == null) {
                if (!additionalPropertiesAllowed) {
                    throw new IllegalArgumentException("An unsupported tool argument was supplied.");
                }
                continue;
            }
            if (!matchesType(field.getValue(), propertySchema.path("type").asText())) {
                throw new IllegalArgumentException("A tool argument has an invalid type.");
            }
            JsonNode enumValues = propertySchema.path("enum");
            if (enumValues.isArray() && !containsValue(enumValues, field.getValue())) {
                throw new IllegalArgumentException("A tool argument has an unsupported value.");
            }
        }
    }

    private boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> false;
        };
    }

    private boolean containsValue(JsonNode values, JsonNode value) {
        for (JsonNode candidate : values) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode message(String role, String content) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}
