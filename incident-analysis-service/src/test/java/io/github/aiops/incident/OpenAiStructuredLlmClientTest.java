package io.github.aiops.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aiops.incident.api.LlmErrorHandler;
import io.github.aiops.incident.llm.FunctionTool;
import io.github.aiops.incident.llm.LlmIntegrationException;
import io.github.aiops.incident.llm.OpenAiCompatibleStructuredLlmClient;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class OpenAiCompatibleStructuredLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void separatesHostToolExecutionFromStructuredAnalysis() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleStructuredLlmClient client = client(builder);
        AtomicBoolean toolExecuted = new AtomicBoolean();
        FunctionTool tool = new FunctionTool(
                "query_system_logs",
                "Query logs",
                objectMapper.readTree("""
                        {
                          "type": "object",
                          "properties": {"service": {"type": "string"}},
                          "required": ["service"],
                          "additionalProperties": false
                        }
                        """),
                arguments -> {
                    toolExecuted.set(arguments.path("service").asText().equals("payment-service"));
                    return objectMapper.createObjectNode()
                            .put("commitId", "abc123")
                            .put("changedFile", "service-a/src/main/java/App.java")
                            .put("diff", "- old code\n+ new code");
                }
        );

        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer mock-groq-key"))
                .andExpect(request -> {
                    JsonNode payload = requestPayload(request);
                    assertTrue(payload.path("response_format").isMissingNode());
                    assertEquals("required", payload.path("tool_choice").asText());
                    assertFalse(payload.path("parallel_tool_calls").asBoolean(true));
                    assertTrue(payload.path("tools").isArray());
                    JsonNode function = payload.path("tools").get(0).path("function");
                    assertEquals("query_system_logs", function.path("name").asText());
                    assertTrue(function.path("strict").asBoolean());
                    assertEquals("object", function.path("parameters").path("type").asText());
                    assertEquals(List.of("service"), objectMapper.convertValue(
                            function.path("parameters").path("required"),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                    ));
                    assertFalse(function.path("parameters").path("additionalProperties").asBoolean());
                })
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "content": null,
                              "tool_calls": [{
                                "id": "call_1",
                                "type": "function",
                                "function": {
                                  "name": "query_system_logs",
                                  "arguments": "{\\"service\\":\\"payment-service\\"}"
                                }
                              }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andExpect(request -> {
                    assertTrue(toolExecuted.get(), "Java must execute the tool before final analysis");
                    JsonNode payload = requestPayload(request);
                    assertTrue(payload.path("tools").isMissingNode());
                    assertTrue(payload.path("tool_choice").isMissingNode());
                    assertTrue(payload.path("parallel_tool_calls").isMissingNode());
                    assertEquals("json_schema", payload.path("response_format").path("type").asText());
                    assertTrue(payload.path("response_format").path("json_schema").path("strict").asBoolean());
                    JsonNode messages = payload.path("messages");
                    assertEquals(3, messages.size());
                    assertEquals("system", messages.get(0).path("role").asText());
                    assertEquals("system", messages.get(0).path("content").asText());
                    assertEquals("user", messages.get(1).path("role").asText());
                    assertEquals("user", messages.get(1).path("content").asText());
                    JsonNode evidenceMessage = messages.get(2);
                    assertEquals("user", evidenceMessage.path("role").asText());
                    assertTrue(evidenceMessage.path("content").asText().startsWith("Evidence tool result:\n"));
                    assertTrue(evidenceMessage.path("content").asText().contains("abc123"));
                    assertTrue(evidenceMessage.path("content").asText().contains("old code"));
                    for (JsonNode message : messages) {
                        assertFalse("tool".equals(message.path("role").asText()));
                        assertFalse(message.has("tool_calls"));
                    }
                })
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "content": "{\\"isHealthy\\":false,\\"summary\\":\\"Errors found.\\"}"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = client.completeJson(
                "system",
                "user",
                "detection_result",
                objectMapper.readTree("""
                        {
                          "type": "object",
                          "properties": {
                            "isHealthy": {"type": "boolean"},
                            "summary": {"type": "string"}
                          },
                          "required": ["isHealthy", "summary"],
                          "additionalProperties": false
                        }
                        """),
                List.of(tool),
                true
        );

        assertTrue(toolExecuted.get());
        assertEquals(false, result.path("isHealthy").asBoolean());
        assertEquals("Errors found.", result.path("summary").asText());
        server.verify();
    }

    @Test
    void jsonObjectModeSerializesWithoutStrictJsonSchema() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleStructuredLlmClient client = client(builder);
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andExpect(request -> {
                    JsonNode payload = requestPayload(request);
                    assertEquals("json_object", payload.path("response_format").path("type").asText());
                    assertTrue(payload.path("response_format").path("json_schema").isMissingNode());
                    assertEquals("openai/gpt-oss-120b", payload.path("model").asText());
                })
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"{\\"valid\\":true}"}}]}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = client.completeJsonObject("system", "user", "patch_proposal");

        assertTrue(result.path("valid").asBoolean());
        server.verify();
    }

    @Test
    void plainTextModeOmitsResponseFormatAndReturnsUnifiedDiffUnchanged() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleStructuredLlmClient client = client(builder);
        String unifiedDiff = "--- a/service-a/src/main/java/example/Database.java\n"
                + "+++ b/service-a/src/main/java/example/Database.java\n"
                + "@@ -1,1 +1,1 @@\n"
                + "-INSERT INTO records (paylod)\n"
                + "+INSERT INTO records (payload)\n";
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andExpect(request -> {
                    JsonNode payload = requestPayload(request);
                    assertTrue(payload.path("response_format").isMissingNode());
                    assertTrue(payload.path("tools").isMissingNode());
                    assertTrue(payload.path("tool_choice").isMissingNode());
                    assertTrue(payload.path("parallel_tool_calls").isMissingNode());
                    assertEquals("openai/gpt-oss-120b", payload.path("model").asText());
                })
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"--- a/service-a/src/main/java/example/Database.java\\n+++ b/service-a/src/main/java/example/Database.java\\n@@ -1,1 +1,1 @@\\n-INSERT INTO records (paylod)\\n+INSERT INTO records (payload)\\n"}}]}
                        """, MediaType.APPLICATION_JSON));

        String result = client.completeText("system", "user", "patch_proposal");

        assertEquals(unifiedDiff, result);
        server.verify();
    }

    @Test
    void rejectsInvalidToolArgumentsBeforeExecutingTheHostTool() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleStructuredLlmClient client = client(builder);
        AtomicBoolean toolExecuted = new AtomicBoolean();
        FunctionTool tool = new FunctionTool(
                "query_system_logs",
                "Query logs",
                objectMapper.readTree("""
                        {
                          "type": "object",
                          "properties": {"service": {"type": "string"}},
                          "required": ["service"],
                          "additionalProperties": false
                        }
                        """),
                arguments -> {
                    toolExecuted.set(true);
                    return objectMapper.createObjectNode();
                }
        );
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withSuccess(toolCallResponse("{\"unexpected\":true}"), MediaType.APPLICATION_JSON));

        LlmIntegrationException exception = assertThrows(
                LlmIntegrationException.class,
                () -> client.completeJson(
                        "system", "user", "result", objectMapper.createObjectNode(), List.of(tool), true
                )
        );

        assertFalse(toolExecuted.get());
        assertEquals("result tool-call response parsing", exception.getStage());
        assertEquals("response_validation", exception.getFailureCategory());
        ProblemDetail problem = new LlmErrorHandler().handleLlmIntegrationException(exception);
        assertTrue(problem.getDetail().contains("correlation ID"));
        server.verify();
    }

    @Test
    void categorizesMalformedToolCallResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleStructuredLlmClient client = client(builder);
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","tool_calls":{}}}]}
                        """, MediaType.APPLICATION_JSON));

        LlmIntegrationException exception = assertThrows(
                LlmIntegrationException.class,
                () -> client.completeJson(
                        "system", "user", "result", objectMapper.createObjectNode(),
                        List.of(testTool()), true
                )
        );

        assertEquals("result tool-call response parsing", exception.getStage());
        assertEquals("response_validation", exception.getFailureCategory());
        server.verify();
    }

    @Test
    void preservesToolExecutionFailureCauseAndStage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleStructuredLlmClient client = client(builder);
        IllegalStateException toolFailure = new IllegalStateException("sensitive tool detail");
        FunctionTool failingTool = new FunctionTool(
                "query_system_logs",
                "Test tool",
                objectMapper.createObjectNode()
                        .put("type", "object")
                        .set("properties", objectMapper.createObjectNode()),
                arguments -> {
                    throw toolFailure;
                }
        );
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withSuccess(toolCallResponse("{}"), MediaType.APPLICATION_JSON));

        LlmIntegrationException exception = assertThrows(
                LlmIntegrationException.class,
                () -> client.completeJson(
                        "system", "user", "result", objectMapper.createObjectNode(),
                        List.of(failingTool), true
                )
        );

        assertEquals("result tool execution", exception.getStage());
        assertEquals("tool_execution", exception.getFailureCategory());
        assertEquals("query_system_logs", exception.getToolName());
        assertEquals("TOOL_EXECUTOR_FAILURE", exception.getValidationReasonCode());
        assertEquals(toolFailure, exception.getCause());
        server.verify();
    }

    @Test
    void resourceAccessFailureCarriesStageAndNetworkCategory() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleStructuredLlmClient client = client(builder);
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withException(new IOException("sensitive transport text")));

        LlmIntegrationException exception = assertThrows(
                LlmIntegrationException.class,
                () -> client.completeJson(
                        "system", "user", "result", objectMapper.createObjectNode(),
                        List.of(testTool()), true
                )
        );

        assertEquals("result tool-call request", exception.getStage());
        assertEquals("network_error", exception.getFailureCategory());
        assertTrue(exception.getCause() instanceof org.springframework.web.client.ResourceAccessException);
        server.verify();
    }

    @Test
    void reportsMalformedFinalJson() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleStructuredLlmClient client = client(builder);
        FunctionTool tool = new FunctionTool(
                "query_system_logs",
                "Query logs",
                objectMapper.readTree("""
                        {
                          "type": "object",
                          "properties": {"service": {"type": "string"}},
                          "required": ["service"],
                          "additionalProperties": false
                        }
                        """),
                arguments -> objectMapper.createObjectNode().put("evidence", "real host result")
        );
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withSuccess(toolCallResponse("{\"service\":\"ServiceA\"}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andExpect(request -> {
                    JsonNode payload = requestPayload(request);
                    assertTrue(payload.path("tools").isMissingNode());
                    assertTrue(payload.path("messages").toString().contains("real host result"));
                })
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"not-json"}}]}
                        """, MediaType.APPLICATION_JSON));

        LlmIntegrationException exception = assertThrows(
                LlmIntegrationException.class,
                () -> client.completeJson(
                        "system", "user", "result", objectMapper.createObjectNode(), List.of(tool), true
                )
        );

        assertTrue(exception.getMessage().contains("invalid JSON"));
        assertEquals("result structured JSON parsing", exception.getStage());
        assertEquals("response_validation", exception.getFailureCategory());
        server.verify();
    }

    @Test
    void failsAtStartupWhenApiKeyIsMissingAndLlmModeIsEnabled() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new OpenAiCompatibleStructuredLlmClient(
                        RestClient.builder(),
                        objectMapper,
                        "https://api.groq.com/openai/v1",
                        "",
                        "openai/gpt-oss-120b",
                        "groq",
                        "llm"
                )
        );
        assertTrue(exception.getMessage().contains("AIOPS_LLM_API_KEY"));
        assertFalse(exception.getMessage().contains("mock-groq-key"));
    }

    @Test
    void demoModeAllowsMissingKeyButRejectsBeforeBuildingAuthorizationHeader() {
        OpenAiCompatibleStructuredLlmClient client = new OpenAiCompatibleStructuredLlmClient(
                RestClient.builder(),
                objectMapper,
                "https://api.groq.com/openai/v1",
                "",
                "openai/gpt-oss-120b",
                "groq",
                "demo"
        );

        LlmIntegrationException exception = assertThrows(
                LlmIntegrationException.class,
                () -> client.completeJson(
                        "system", "user", "result", objectMapper.createObjectNode(), List.of(), false
                )
        );
        assertTrue(exception.getMessage().contains("requires an API key"));
    }

    @Test
    void configuredApiKeyIsAcceptedForLlmModeAndUsedInAuthorizationHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleStructuredLlmClient client = new OpenAiCompatibleStructuredLlmClient(
                builder,
                objectMapper,
                "https://api.groq.com/openai/v1",
                "mock-groq-key",
                "openai/gpt-oss-120b",
                "groq",
                "llm"
        );
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer mock-groq-key"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"{\\"ok\\":true}"}}]}
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = client.completeJson(
                "system", "user", "result", objectMapper.createObjectNode(), List.of(), false
        );

        assertTrue(result.path("ok").asBoolean());
        server.verify();
    }

    @Test
    void toolCallHttp502LogsSafeDiagnosticsAndReturnsSanitizedProblemDetail() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleStructuredLlmClient client = client(builder);
        String sensitiveBody = """
                {"error":{"type":"upstream_failure",
                "message":"Bearer secret-token-should-not-leak prompt-marker-should-not-leak"}}
                """;
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(sensitiveBody));
        LlmIntegrationException exception = assertThrows(
                LlmIntegrationException.class,
                () -> client.completeJson(
                        "sensitive prompt-marker-should-not-leak",
                        "user",
                        "result",
                        objectMapper.createObjectNode(),
                        List.of(testTool()),
                        true
                )
        );

        ListAppender<ILoggingEvent> handlerLogs = captureHandlerLogs();
        org.slf4j.MDC.put("incidentCorrelationId", "incident-test-123");
        ProblemDetail problem = new LlmErrorHandler().handleLlmIntegrationException(exception);
        assertEquals(HttpStatus.BAD_GATEWAY.value(), problem.getStatus());
        assertEquals("result tool-call request", problem.getProperties().get("stage"));
        assertEquals(502, problem.getProperties().get("upstreamStatus"));
        assertEquals("upstream_failure", problem.getProperties().get("upstreamErrorType"));
        assertTrue(exception.getCause() instanceof org.springframework.web.client.RestClientResponseException);
        assertNotContainsSensitiveData(problem.toString());
        assertEquals(1, handlerLogs.list.size());
        String logMessage = handlerLogs.list.get(0).getFormattedMessage();
        assertTrue(logMessage.contains("provider=groq"));
        assertTrue(logMessage.contains("stage=result tool-call request"));
        assertTrue(logMessage.contains("status=502"));
        assertTrue(logMessage.contains("category=upstream_http"));
        assertTrue(logMessage.contains("upstreamErrorType=upstream_failure"));
        assertTrue(logMessage.contains("exceptionClass=io.github.aiops.incident.llm.LlmIntegrationException"));
        assertTrue(logMessage.contains("rootCauseClass=org.springframework.web.client."));
        assertTrue(logMessage.contains("correlationId=incident-test-123"));
        assertNotContainsSensitiveData(logMessage);
        assertEquals(1, handlerLogs.list.size(), "Failure should be logged once centrally");

        org.slf4j.MDC.clear();
        detachHandlerLogs(handlerLogs);
        server.verify();
    }

    @Test
    void toolCallHttp400ReportsRequestNameAndAllowlistedErrorFieldsOnly() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleStructuredLlmClient client = client(builder);
        String sensitiveMarker = "prompt-marker-should-not-leak";
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "error": {
                                    "type": "invalid_request_error",
                                    "code": "invalid_json_schema",
                                    "param": "tools[0].function.parameters",
                                    "message": "Tool parameter schema is empty: %s",
                                    "failed_generation": "%s"
                                  }
                                }
                                """.formatted(sensitiveMarker, sensitiveMarker)));

        LlmIntegrationException exception = assertThrows(
                LlmIntegrationException.class,
                () -> client.completeJson(
                        "system",
                        "user",
                        "identifier_result",
                        objectMapper.createObjectNode(),
                        List.of(testTool()),
                        true
                )
        );

        assertEquals("identifier_result tool-call request", exception.getStage());
        assertEquals(400, exception.getUpstreamStatus());
        assertEquals("invalid_request_error", exception.getUpstreamErrorType());
        assertEquals("invalid_json_schema", exception.getUpstreamErrorCode());
        assertEquals("tools[0].function.parameters", exception.getUpstreamErrorParam());
        assertEquals("EMPTY_PARAMETERS_SCHEMA", exception.getUpstreamErrorCategory());
        assertTrue(exception.getCause() instanceof org.springframework.web.client.RestClientResponseException);
        assertFalse(exception.getMessage().contains(sensitiveMarker));

        ListAppender<ILoggingEvent> handlerLogs = captureHandlerLogs();
        org.slf4j.MDC.put("incidentCorrelationId", "incident-test-400");
        try {
            ProblemDetail problem = new LlmErrorHandler().handleLlmIntegrationException(exception);
            assertEquals(400, problem.getProperties().get("upstreamStatus"));
            assertEquals("identifier_result tool-call request", problem.getProperties().get("stage"));
            assertEquals("invalid_json_schema", problem.getProperties().get("upstreamErrorCode"));
            assertEquals("tools[0].function.parameters", problem.getProperties().get("upstreamErrorParam"));
            assertEquals("EMPTY_PARAMETERS_SCHEMA", problem.getProperties().get("upstreamErrorCategory"));
            assertNotContainsSensitiveData(problem.toString());
            assertEquals(1, handlerLogs.list.size());
            String logMessage = handlerLogs.list.get(0).getFormattedMessage();
            assertTrue(logMessage.contains("stage=identifier_result tool-call request"));
            assertTrue(logMessage.contains("upstreamErrorCode=invalid_json_schema"));
            assertTrue(logMessage.contains("upstreamErrorParam=tools[0].function.parameters"));
            assertTrue(logMessage.contains("upstreamErrorCategory=EMPTY_PARAMETERS_SCHEMA"));
            assertFalse(logMessage.contains(sensitiveMarker));
        } finally {
            org.slf4j.MDC.clear();
            detachHandlerLogs(handlerLogs);
        }
        server.verify();
    }

    @Test
    void classifiesInvalidRequestMessagesUsingOnlySafeCategories() {
        Map<String, String> messages = Map.of(
                "The tool schema is invalid.", "INVALID_TOOL_SCHEMA",
                "Strict schema is not supported for this tool.", "UNSUPPORTED_STRICT_SCHEMA",
                "Invalid tool_choice value.", "INVALID_TOOL_CHOICE",
                "The request has an unsupported option.", "OTHER_INVALID_REQUEST"
        );

        messages.forEach((upstreamMessage, expectedCategory) -> {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            OpenAiCompatibleStructuredLlmClient client = client(builder);
            server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(upstreamErrorBody(upstreamMessage)));

            LlmIntegrationException exception = assertThrows(
                    LlmIntegrationException.class,
                    () -> client.completeJson(
                            "system", "user", "identifier_result",
                            objectMapper.createObjectNode(), List.of(testTool()), true
                    )
            );

            assertEquals("invalid_request_error", exception.getUpstreamErrorType());
            assertEquals(expectedCategory, exception.getUpstreamErrorCategory());
            assertFalse(exception.getMessage().contains(upstreamMessage));
            server.verify();
        });
    }

    @Test
    void structuredAnalysisHttp502ReportsItsStageAndPreservesCause() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleStructuredLlmClient client = client(builder);
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withSuccess(toolCallResponse("{}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"type":"gateway_error","message":"safe test detail"}}
                                """));

        LlmIntegrationException exception = assertThrows(
                LlmIntegrationException.class,
                () -> client.completeJson(
                        "system", "user", "result", objectMapper.createObjectNode(),
                        List.of(testTool()), true
                )
        );

        assertEquals("result structured-analysis request", exception.getStage());
        assertEquals(502, exception.getUpstreamStatus());
        assertEquals("gateway_error", exception.getUpstreamErrorType());
        assertTrue(exception.getCause() instanceof org.springframework.web.client.RestClientResponseException);
        ProblemDetail problem = new LlmErrorHandler().handleLlmIntegrationException(exception);
        assertTrue(problem.getDetail().contains("result structured-analysis request"));
        assertTrue(problem.getDetail().contains("HTTP 502"));
        server.verify();
    }

    @Test
    void rateLimitFailureIncludesStatusAndSafeType() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleStructuredLlmClient client = client(builder);
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"type":"rate_limit_exceeded","message":"private response detail"}}
                                """));

        LlmIntegrationException exception = assertThrows(
                LlmIntegrationException.class,
                () -> client.completeJson(
                        "system", "user", "result", objectMapper.createObjectNode(),
                        List.of(testTool()), true
                )
        );

        assertEquals(429, exception.getUpstreamStatus());
        assertEquals("rate_limit_exceeded", exception.getUpstreamErrorType());
        assertTrue(exception.getMessage().contains("HTTP 429"));
        assertTrue(exception.getCause() instanceof org.springframework.web.client.RestClientResponseException);
        assertFalse(new LlmErrorHandler().handleLlmIntegrationException(exception)
                .getDetail().contains("private response detail"));
        server.verify();
    }

    @Test
    void applicationPropertiesDefaultToGroqConfiguration() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
            assertTrue(input != null, "application.properties should be on the test classpath");
            properties.load(input);
        }

        assertEquals("${AIOPS_LLM_PROVIDER:groq}", properties.getProperty("aiops.llm.provider"));
        assertEquals("${AIOPS_LLM_API_KEY:${GROQ_API_KEY:}}",
                properties.getProperty("aiops.llm.api-key"));
        assertEquals("${AIOPS_LLM_MODEL:${GROQ_MODEL:openai/gpt-oss-120b}}",
                properties.getProperty("aiops.llm.model"));
        assertEquals(
                "${AIOPS_LLM_BASE_URL:${GROQ_BASE_URL:https://api.groq.com/openai/v1}}",
                properties.getProperty("aiops.llm.base-url"));
        assertEquals("${AIOPS_GIT_LOOKBACK_HOURS:24}",
                properties.getProperty("aiops.git.lookback-hours"));
        assertEquals("${AIOPS_GIT_HISTORY_REF:main}",
                properties.getProperty("aiops.git.history-ref"));
    }

    private OpenAiCompatibleStructuredLlmClient client(RestClient.Builder builder) {
        return new OpenAiCompatibleStructuredLlmClient(
                builder,
                objectMapper,
                "https://api.groq.com/openai/v1",
                "mock-groq-key",
                "openai/gpt-oss-120b",
                "groq",
                "llm"
        );
    }

    private String toolCallResponse(String arguments) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode message = response.putArray("choices").addObject().putObject("message");
        message.put("role", "assistant");
        message.putNull("content");
        ObjectNode call = message.putArray("tool_calls").addObject();
        call.put("id", "call_1");
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", "query_system_logs");
        function.put("arguments", arguments);
        return response.toString();
    }

    private FunctionTool testTool() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", objectMapper.createObjectNode());
        schema.putArray("required");
        schema.put("additionalProperties", false);
        return new FunctionTool(
                "query_system_logs",
                "Test tool",
                schema,
                arguments -> objectMapper.createObjectNode().put("evidence", "safe")
        );
    }

    private String upstreamErrorBody(String message) {
        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("error")
                .put("type", "invalid_request_error")
                .put("message", message);
        return body.toString();
    }

    private ListAppender<ILoggingEvent> captureHandlerLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(LlmErrorHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachHandlerLogs(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(LlmErrorHandler.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    private void assertNotContainsSensitiveData(String value) {
        assertFalse(value.contains("secret-token-should-not-leak"));
        assertFalse(value.contains("prompt-marker-should-not-leak"));
    }

    private JsonNode requestPayload(org.springframework.http.client.ClientHttpRequest request) throws IOException {
        return objectMapper.readTree(((MockClientHttpRequest) request).getBodyAsBytes());
    }
}
