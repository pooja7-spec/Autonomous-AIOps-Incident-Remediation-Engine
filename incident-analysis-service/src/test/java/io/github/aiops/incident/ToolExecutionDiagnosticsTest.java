package io.github.aiops.incident;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aiops.contracts.event.Severity;
import io.github.aiops.incident.api.IncidentCorrelationFilter;
import io.github.aiops.incident.api.LlmErrorHandler;
import io.github.aiops.incident.domain.CodeChange;
import io.github.aiops.incident.domain.DetectionResult;
import io.github.aiops.incident.domain.MetricAlert;
import io.github.aiops.incident.llm.LlmIntegrationException;
import io.github.aiops.incident.llm.OpenAiCompatibleStructuredLlmClient;
import io.github.aiops.incident.llm.ToolFailureReason;
import io.github.aiops.incident.llm.ToolValidationException;
import io.github.aiops.incident.skills.DetectionSkill;
import io.github.aiops.incident.skills.IdentifierSkill;
import io.github.aiops.incident.tools.GitDiffTool;
import io.github.aiops.incident.tools.LogQueryTool;
import io.github.aiops.incident.tools.LoggingServiceLogQueryTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ToolExecutionDiagnosticsTest {

    private static final String CORRELATION_ID = "incident-safe-123";
    private static final String GROQ_COMPLETIONS_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final Instant FIXED_ALERT_TIME = Instant.parse("2026-11-12T05:30:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setCorrelationId() {
        MDC.put(IncidentCorrelationFilter.MDC_KEY, CORRELATION_ID);
    }

    @AfterEach
    void clearCorrelationId() {
        MDC.remove(IncidentCorrelationFilter.MDC_KEY);
    }

    @Test
    void querySystemLogsExecutesSuccessfully() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(GROQ_COMPLETIONS_URL))
                .andExpect(request -> {
                    JsonNode payload = requestPayload((MockClientHttpRequest) request);
                    JsonNode function = payload.path("tools").get(0).path("function");
                    assertEquals("query_system_logs", function.path("name").asText());
                    assertEquals(true, function.path("strict").asBoolean());
                    assertEquals("object", function.path("parameters").path("type").asText());
                    assertFalse(function.path("parameters").path("additionalProperties").asBoolean());
                    assertEquals(
                            List.of("service", "lookback_minutes", "min_severity"),
                            objectMapper.convertValue(
                                    function.path("parameters").path("required"),
                                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                            )
                    );
                    assertEquals("required", payload.path("tool_choice").asText());
                    assertFalse(payload.path("parallel_tool_calls").asBoolean(true));
                    JsonNode lookback = function.path("parameters").path("properties").path("lookback_minutes");
                    assertEquals("integer", lookback.path("type").asText());
                    assertEquals(1, lookback.path("minimum").asInt());
                    assertEquals(5, lookback.path("maximum").asInt());
                    assertTrue(lookback.path("description").asText().contains("Number of minutes"));
                    assertTrue(function.path("description").asText().contains("from 1 to 5"));
                    assertTrue(function.path("description").asText()
                            .contains("service associated with the current incident"));
                })
                .andRespond(withSuccess(toolCallResponse("query_system_logs", """
                        {"service":"ServiceA","lookback_minutes":5,"min_severity":"ERROR"}
                        """), MediaType.APPLICATION_JSON));
        server.expect(requestTo(GROQ_COMPLETIONS_URL))
                .andExpect(request -> {
                    JsonNode payload = requestPayload((MockClientHttpRequest) request);
                    assertEquals("json_schema", payload.path("response_format").path("type").asText());
                    assertTrue(payload.path("response_format").path("json_schema").path("strict").asBoolean());
                    assertEquals("detection_result",
                            payload.path("response_format").path("json_schema").path("name").asText());
                })
                .andRespond(withSuccess(analysisResponse("""
                        {"isHealthy":false,"summary":"Recent errors found."}
                        """), MediaType.APPLICATION_JSON));
        AtomicReference<String> requestedService = new AtomicReference<>();
        LogQueryTool logs = (service, minutes, severity) -> {
            requestedService.set(service);
            assertEquals(5, minutes);
            assertEquals(Severity.ERROR, severity);
            return List.of();
        };

        DetectionSkill skill = detectionSkill(builder, logs);
        assertFalse(skill.analyze(alert()).isHealthy());
        assertEquals("ServiceA", requestedService.get());
        server.verify();
    }

    @Test
    void querySystemLogsAcceptsMinimumLookback() {
        assertDetectionLookbackSucceeds(1);
    }

    @Test
    void querySystemLogsAcceptsConfiguredMaximumLookback() {
        assertDetectionLookbackSucceeds(5);
    }

    @Test
    void querySystemLogsServiceMismatchCarriesSafeDiagnostics() {
        LlmIntegrationException exception = detectionFailure("""
                {"service":"sensitive-customer-value","lookback_minutes":5,"min_severity":"ERROR"}
                """, (service, minutes, severity) -> List.of());

        assertToolFailure(exception, "query_system_logs", ToolFailureReason.SERVICE_MISMATCH);
        assertNoSensitiveDiagnostics(exception, "sensitive-customer-value");
    }

    @Test
    void querySystemLogsInvalidLookbackCarriesReasonCode() {
        LlmIntegrationException exception = detectionFailure("""
                {"service":"ServiceA","lookback_minutes":99,"min_severity":"ERROR"}
                """, (service, minutes, severity) -> List.of());

        assertToolFailure(exception, "query_system_logs", ToolFailureReason.LOOKBACK_OUT_OF_RANGE);
    }

    @Test
    void querySystemLogsRejectsLookbackBelowMinimum() {
        LlmIntegrationException exception = detectionFailure("""
                {"service":"ServiceA","lookback_minutes":0,"min_severity":"ERROR"}
                """, (service, minutes, severity) -> List.of());

        assertToolFailure(exception, "query_system_logs", ToolFailureReason.LOOKBACK_OUT_OF_RANGE);
    }

    @Test
    void querySystemLogsRejectsLookbackAboveConfiguredMaximum() {
        LlmIntegrationException exception = detectionFailure("""
                {"service":"ServiceA","lookback_minutes":6,"min_severity":"ERROR"}
                """, (service, minutes, severity) -> List.of());

        assertToolFailure(exception, "query_system_logs", ToolFailureReason.LOOKBACK_OUT_OF_RANGE);
    }

    @Test
    void getRecentCommitDiffsIncludesSixteenHourCommitWithinConfiguredLookback() {
        Instant alertTime = FIXED_ALERT_TIME;
        Instant commitTime = alertTime.minusSeconds(16 * 60 * 60);
        String commitId = "c95e3d688de8f14559a148ca7b038ba079ea0f6c";
        String changedFile = "service-a/src/main/java/io/github/aiops/servicea/service/DatabaseOperationService.java";
        String diff = "- INSERT INTO service_a_demo_records (record_id, payload, created_at)\n"
                + "+ INSERT INTO service_a_demo_records (record_id, paylod, created_at)";
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(GROQ_COMPLETIONS_URL))
                .andExpect(request -> {
                    JsonNode payload = requestPayload((MockClientHttpRequest) request);
                    JsonNode function = payload.path("tools").get(0).path("function");
                    assertEquals("get_recent_commit_diffs", function.path("name").asText());
                    assertEquals(true, function.path("strict").asBoolean());
                    assertEquals("object", function.path("parameters").path("type").asText());
                    JsonNode properties = function.path("parameters").path("properties");
                    assertEquals(1, properties.size());
                    assertEquals("string", properties.path("context_scope").path("type").asText());
                    assertEquals(
                            List.of("current_incident_context"),
                            objectMapper.convertValue(
                                    properties.path("context_scope").path("enum"),
                                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                            )
                    );
                    assertEquals(
                            List.of("context_scope"),
                            objectMapper.convertValue(
                                    function.path("parameters").path("required"),
                                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                            )
                    );
                    assertFalse(function.path("parameters").path("additionalProperties").asBoolean());
                    assertEquals("required", payload.path("tool_choice").asText());
                    assertFalse(payload.path("parallel_tool_calls").asBoolean(true));
                    assertTrue(function.path("description").asText().contains("Java has already selected"));
                })
                .andRespond(withSuccess(
                        toolCallResponse(
                                "get_recent_commit_diffs",
                                "{\"context_scope\":\"current_incident_context\"}"
                        ),
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(GROQ_COMPLETIONS_URL))
                .andExpect(request -> {
                    JsonNode payload = requestPayload((MockClientHttpRequest) request);
                    assertEquals("json_schema", payload.path("response_format").path("type").asText());
                    assertTrue(payload.path("response_format").path("json_schema").path("strict").asBoolean());
                    assertEquals("identifier_result",
                            payload.path("response_format").path("json_schema").path("name").asText());
                    assertTrue(payload.path("messages").get(0).path("content").asText()
                            .contains("must be the exact ID of a commit returned by the tool"));
                    assertTrue(payload.path("messages").toString().contains(commitId));
                    assertTrue(payload.path("messages").toString().contains(changedFile));
                    assertTrue(payload.path("messages").toString().contains("paylod"));
                    JsonNode schema = payload.path("response_format").path("json_schema").path("schema");
                    assertTrue(schema.path("properties").path("commitId").path("description")
                            .asText().contains("exact commit ID returned by Git"));
                    assertFalse(schema.has("if"));
                    assertFalse(schema.has("oneOf"));
                })
                .andRespond(withSuccess(analysisResponse("""
                        {"dependentServiceFailure":true,"commitId":"%s","changedFiles":["%s"],
                         "hypothesis":"The SQL column typo is in the recent commit.",
                         "fixNeeded":"Review the column name and test the insert.",
                         "confidence":0.9}
                        """.formatted(commitId, changedFile)), MediaType.APPLICATION_JSON));
        AtomicBoolean gitCalled = new AtomicBoolean();
        AtomicReference<Instant> queriedSince = new AtomicReference<>();
        AtomicReference<Instant> queriedUntil = new AtomicReference<>();
        GitDiffTool git = new GitDiffTool() {
            @Override
            public List<CodeChange> getRecentCommitDiffs(String service, Instant since) {
                throw new AssertionError("IdentifierSkill must use the incident-bounded Git query.");
            }

            @Override
            public List<CodeChange> getRecentCommitDiffs(String service, Instant since, Instant until) {
                gitCalled.set(true);
                assertEquals("ServiceA", service);
                queriedSince.set(since);
                queriedUntil.set(until);
                return List.of(new CodeChange(
                        service,
                        commitId,
                        commitTime,
                        "Introduce controlled database column regression",
                        List.of(changedFile),
                        diff
                ));
            }
        };

        IdentifierSkill skill = identifierSkill(builder, git);
        var result = skill.analyze(alert(alertTime), new DetectionResult(false, "Errors.", List.of()));
        assertTrue(result.isPresent());
        assertEquals(commitId, result.get().commitId());
        assertTrue(result.get().hypothesis().contains("SQL column typo"));
        assertTrue(gitCalled.get());
        assertEquals(alertTime.minusSeconds(24 * 60 * 60), queriedSince.get());
        assertEquals(alertTime, queriedUntil.get());
        server.verify();
    }

    @Test
    void modelCannotSupplyRepositoryOrTimestampForGitQuery() {
        AtomicBoolean gitCalled = new AtomicBoolean();
        LlmIntegrationException exception = identifierFailure(
                "{\"service_repository\":\"private-repository\",\"since_timestamp\":\"1970-01-01T00:00:00Z\"}",
                (service, since) -> {
                    gitCalled.set(true);
                    return List.of();
                },
                FIXED_ALERT_TIME
        );

        assertEquals("identifier_result tool-call response parsing", exception.getStage());
        assertFalse(gitCalled.get());
        assertNoSensitiveDiagnostics(exception, "private-repository");
        assertNoSensitiveDiagnostics(exception, "1970-01-01T00:00:00Z");
    }

    @Test
    void gitToolRejectsMissingContextMarker() {
        AtomicBoolean gitCalled = new AtomicBoolean();
        LlmIntegrationException exception = identifierFailure(
                "{}",
                (service, since) -> {
                    gitCalled.set(true);
                    return List.of();
                },
                FIXED_ALERT_TIME
        );

        assertEquals("identifier_result tool-call response parsing", exception.getStage());
        assertFalse(gitCalled.get());
    }

    @Test
    void gitToolRejectsIncorrectContextMarker() {
        AtomicBoolean gitCalled = new AtomicBoolean();
        LlmIntegrationException exception = identifierFailure(
                "{\"context_scope\":\"another_incident\"}",
                (service, since) -> {
                    gitCalled.set(true);
                    return List.of();
                },
                FIXED_ALERT_TIME
        );

        assertEquals("identifier_result tool-call response parsing", exception.getStage());
        assertFalse(gitCalled.get());
    }

    @Test
    void gitToolRejectsUnexpectedArgumentsAlongsideContextMarker() {
        AtomicBoolean gitCalled = new AtomicBoolean();
        LlmIntegrationException exception = identifierFailure(
                "{\"context_scope\":\"current_incident_context\",\"repository\":\"private-repository\"}",
                (service, since) -> {
                    gitCalled.set(true);
                    return List.of();
                },
                FIXED_ALERT_TIME
        );

        assertEquals("identifier_result tool-call response parsing", exception.getStage());
        assertFalse(gitCalled.get());
        assertNoSensitiveDiagnostics(exception, "private-repository");
    }

    @Test
    void missingLoggingServiceConfigurationCarriesReasonCode() {
        LlmIntegrationException exception = detectionFailure("""
                {"service":"ServiceA","lookback_minutes":5,"min_severity":"ERROR"}
                """, new LoggingServiceLogQueryTool(null)::queryLogs);

        assertToolFailure(exception, "query_system_logs", ToolFailureReason.LOGGING_CONFIGURATION_MISSING);
    }

    @Test
    void missingGitRepositoryMappingCarriesReasonCode() {
        LlmIntegrationException exception = identifierFailure(
                "{\"context_scope\":\"current_incident_context\"}",
                (service, since) -> {
                    throw new ToolValidationException(ToolFailureReason.REPOSITORY_MAPPING_MISSING);
                }
        );

        assertToolFailure(exception, "get_recent_commit_diffs", ToolFailureReason.REPOSITORY_MAPPING_MISSING);
    }

    @Test
    void errorHandlerReturnsOnlySafeToolDiagnosticsAndPreservesCorrelation() {
        String sensitiveValue = "private-user-value";
        LlmIntegrationException failure = detectionFailure(
                """
                {"service":"%s","lookback_minutes":5,"min_severity":"ERROR"}
                """.formatted(sensitiveValue),
                (service, minutes, severity) -> List.of()
        );
        LlmIntegrationException exception = failure.withBoundary("incident orchestration");
        LlmErrorHandler handler = new LlmErrorHandler();
        ListAppender<ILoggingEvent> appender = captureHandlerLogs();
        try {
            ProblemDetail problem = handler.handleLlmIntegrationException(exception);
            String response = problem.toString();
            String log = appender.list.get(0).getFormattedMessage();

            assertEquals(CORRELATION_ID, problem.getProperties().get("correlationId"));
            assertEquals("query_system_logs", problem.getProperties().get("toolName"));
            assertEquals("SERVICE_MISMATCH", problem.getProperties().get("validationReasonCode"));
            assertEquals("incident orchestration", problem.getProperties().get("boundary"));
            assertEquals(ToolValidationException.class.getName(),
                    problem.getProperties().get("rootCauseClass"));
            assertTrue(log.contains("correlationId=" + CORRELATION_ID));
            assertTrue(log.contains("toolName=query_system_logs"));
            assertTrue(log.contains("validationReasonCode=SERVICE_MISMATCH"));
            assertFalse(response.contains(sensitiveValue));
            assertFalse(log.contains(sensitiveValue));
            assertEquals(ToolFailureReason.SERVICE_MISMATCH.name(), exception.getValidationReasonCode());
            assertNotNull(exception.getCause());
            assertInstanceOf(LlmIntegrationException.class, exception.getCause());
            assertInstanceOf(ToolValidationException.class, rootCause(exception));
        } finally {
            detachHandlerLogs(appender);
        }
    }

    private DetectionSkill detectionSkill(RestClient.Builder builder, LogQueryTool logs) {
        return new DetectionSkill(logs, llmClient(builder), objectMapper, 5, "llm", "groq");
    }

    private IdentifierSkill identifierSkill(RestClient.Builder builder, GitDiffTool git) {
        return new IdentifierSkill(git, llmClient(builder), objectMapper, 24, "llm", "groq");
    }

    private LlmIntegrationException detectionFailure(String arguments, LogQueryTool logQueryTool) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(GROQ_COMPLETIONS_URL))
                .andRespond(withSuccess(toolCallResponse("query_system_logs", arguments), MediaType.APPLICATION_JSON));
        LlmIntegrationException exception = assertThrows(
                LlmIntegrationException.class,
                () -> detectionSkill(builder, logQueryTool).analyze(alert())
        );
        server.verify();
        return exception;
    }

    private void assertDetectionLookbackSucceeds(int lookbackMinutes) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(GROQ_COMPLETIONS_URL))
                .andRespond(withSuccess(
                        toolCallResponse(
                                "query_system_logs",
                                """
                                {"service":"ServiceA","lookback_minutes":%d,"min_severity":"ERROR"}
                                """.formatted(lookbackMinutes)
                        ),
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo(GROQ_COMPLETIONS_URL))
                .andRespond(withSuccess(
                        analysisResponse("""
                                {"isHealthy":false,"summary":"Recent errors found."}
                                """),
                        MediaType.APPLICATION_JSON
                ));
        AtomicReference<Integer> requestedLookback = new AtomicReference<>();
        DetectionSkill skill = detectionSkill(
                builder,
                (service, minutes, severity) -> {
                    requestedLookback.set(minutes);
                    return List.of();
                }
        );

        skill.analyze(alert());

        assertEquals(lookbackMinutes, requestedLookback.get());
        server.verify();
    }

    private LlmIntegrationException identifierFailure(String arguments, GitDiffTool gitDiffTool) {
        return identifierFailure(arguments, gitDiffTool, Instant.now());
    }

    private LlmIntegrationException identifierFailure(
            String arguments,
            GitDiffTool gitDiffTool,
            Instant alertTime
    ) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(GROQ_COMPLETIONS_URL))
                .andRespond(withSuccess(
                        toolCallResponse("get_recent_commit_diffs", arguments),
                        MediaType.APPLICATION_JSON
                ));
        LlmIntegrationException exception = assertThrows(
                LlmIntegrationException.class,
                () -> identifierSkill(builder, gitDiffTool).analyze(
                        alert(alertTime),
                        new DetectionResult(false, "Errors.", List.of())
                )
        );
        server.verify();
        return exception;
    }

    private OpenAiCompatibleStructuredLlmClient llmClient(RestClient.Builder builder) {
        return new OpenAiCompatibleStructuredLlmClient(
                builder,
                objectMapper,
                "https://api.groq.com/openai/v1",
                "mock-key",
                "openai/gpt-oss-120b",
                "groq",
                "llm"
        );
    }

    private MetricAlert alert() {
        return alert(Instant.now());
    }

    private MetricAlert alert(Instant timestamp) {
        return new MetricAlert(
                "ServiceA",
                "error_rate",
                new BigDecimal("10"),
                new BigDecimal("5"),
                timestamp
        );
    }

    private String toolCallResponse(String toolName, String arguments) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode message = response.putArray("choices").addObject().putObject("message");
        message.put("role", "assistant");
        message.putNull("content");
        ObjectNode call = message.putArray("tool_calls").addObject();
        call.put("id", "call-test");
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", toolName);
        function.put("arguments", arguments);
        return response.toString();
    }

    private String analysisResponse(String content) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("choices").addObject()
                .putObject("message")
                .put("role", "assistant")
                .put("content", content);
        return response.toString();
    }

    private void assertToolFailure(
            LlmIntegrationException exception,
            String toolName,
            ToolFailureReason reason
    ) {
        assertEquals("groq", exception.getProvider());
        assertEquals(
                "query_system_logs".equals(toolName)
                        ? "detection_result tool execution"
                        : "identifier_result tool execution",
                exception.getStage()
        );
        assertEquals("tool_execution", exception.getFailureCategory());
        assertEquals(toolName, exception.getToolName());
        assertEquals(reason.name(), exception.getValidationReasonCode());
        assertEquals(CORRELATION_ID, exception.getCorrelationId());
        assertInstanceOf(ToolValidationException.class, exception.getCause());
    }

    private Throwable rootCause(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private void assertNoSensitiveDiagnostics(LlmIntegrationException exception, String sensitiveValue) {
        ProblemDetail problem = new LlmErrorHandler().handleLlmIntegrationException(exception);
        assertFalse(problem.toString().contains(sensitiveValue));
        assertFalse(exception.getMessage().contains(sensitiveValue));
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

    private JsonNode requestPayload(MockClientHttpRequest request) throws IOException {
        return objectMapper.readTree(request.getBodyAsBytes());
    }
}
