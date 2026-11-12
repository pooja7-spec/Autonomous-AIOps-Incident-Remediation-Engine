package io.github.aiops.incident;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiops.contracts.event.Severity;
import io.github.aiops.incident.api.LlmErrorHandler;
import io.github.aiops.incident.domain.CodeChange;
import io.github.aiops.incident.domain.DetectionResult;
import io.github.aiops.incident.domain.LogEntry;
import io.github.aiops.incident.domain.MetricAlert;
import io.github.aiops.incident.llm.LlmIntegrationException;
import io.github.aiops.incident.llm.ToolFailureReason;
import io.github.aiops.incident.llm.ToolValidationException;
import io.github.aiops.incident.skills.DetectionSkill;
import io.github.aiops.incident.skills.IdentifierSkill;
import io.github.aiops.incident.tools.GitDiffTool;
import io.github.aiops.incident.tools.InMemoryGitDiffTool;
import io.github.aiops.incident.tools.InMemoryLogQueryTool;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmSkillTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void detectionModelQueriesBoundedLogsAndReturnsToolEvidence() {
        InMemoryLogQueryTool logTool = new InMemoryLogQueryTool();
        LogEntry log = new LogEntry(
                Instant.now(),
                Severity.ERROR,
                "Database connection pool exhausted",
                "payment-service"
        );
        logTool.add(log);
        MetricAlert alert = alert();
        DetectionSkill skill = new DetectionSkill(
                logTool,
                (system, user, schemaName, schema, tools, requireToolCall) -> {
                    assertTrue(requireToolCall);
                    assertEquals("detection_result", schemaName);
                    JsonNode result = tools.get(0).executor().apply(json("""
                            {
                              "service": "payment-service",
                              "lookback_minutes": 5,
                              "min_severity": "ERROR"
                            }
                            """));
                    assertEquals(1, result.size());
                    return json("""
                            {"isHealthy":false,"summary":"Connection errors detected."}
                            """);
                },
                objectMapper,
                5,
                "openai",
                "groq"
        );

        DetectionResult result = skill.analyze(alert);

        assertFalse(result.isHealthy());
        assertEquals("Connection errors detected.", result.summary());
        assertEquals(List.of(log), result.relevantLogs());
    }

    @Test
    void identifierModelCanOnlyReferenceToolReturnedCommitAndFiles() {
        InMemoryGitDiffTool gitTool = new InMemoryGitDiffTool();
        Instant alertTime = Instant.now();
        gitTool.add(new CodeChange(
                "payment-service",
                "abc123",
                alertTime.minusSeconds(30),
                "Reduce database pool",
                List.of("application.yml"),
                "pool-size: 2"
        ));
        MetricAlert alert = alert(alertTime);
        IdentifierSkill skill = new IdentifierSkill(
                gitTool,
                (system, user, schemaName, schema, tools, requireToolCall) -> {
                    assertTrue(requireToolCall);
                    assertEquals("identifier_result", schemaName);
                    assertTrue(system.contains("exact paths"));
                    assertTrue(system.contains("actual relevant error log"));
                    assertTrue(system.contains("Java controls the Git service and inclusive timestamp window"));
                    assertEquals(1, tools.get(0).parameters().path("properties").size());
                    assertEquals("current_incident_context",
                            tools.get(0).parameters().path("properties").path("context_scope").path("enum").get(0).asText());
                    assertEquals("context_scope", tools.get(0).parameters().path("required").get(0).asText());
                    JsonNode result = tools.get(0).executor().apply(json("""
                            {"context_scope":"current_incident_context"}
                            """));
                    assertEquals("abc123", result.path(0).path("commitId").asText());
                    return json("""
                            {
                              "dependentServiceFailure": true,
                              "commitId": "abc123",
                              "changedFiles": ["application.yml"],
                              "hypothesis": "The pool size change may have caused failures.",
                              "fixNeeded": "Review and test a corrected pool size.",
                              "confidence": 0.72
                            }
                            """);
                },
                objectMapper,
                24,
                "llm",
                "groq"
        );

        var result = skill.analyze(alert, new DetectionResult(false, "Connection errors.", List.of()));

        assertTrue(result.isPresent());
        assertEquals("abc123", result.get().commitId());
        assertEquals(0.72, result.get().confidence());
    }

    @Test
    void javaSelectsIncidentBoundedWindowAndIncludesSixteenHourRegressionCommit() {
        Instant incidentTime = Instant.parse("2026-11-12T05:35:30.946229Z");
        Instant lowerBound = incidentTime.minus(Duration.ofHours(24));
        RecordingGitDiffTool gitTool = new RecordingGitDiffTool();
        gitTool.add(new CodeChange(
                "ServiceA",
                "c95e3d688de8f14559a148ca7b038ba079ea0f6c",
                incidentTime.minus(Duration.ofHours(16)),
                "Controlled database regression",
                List.of("service-a/src/main/java/io/github/aiops/servicea/service/DatabaseOperationService.java"),
                "-payload\n+paylod"
        ));
        gitTool.add(new CodeChange(
                "ServiceA",
                "outside-window",
                incidentTime.minus(Duration.ofHours(25)),
                "Older change",
                List.of("service-a/src/main/java/io/github/aiops/servicea/service/DatabaseOperationService.java"),
                "old diff"
        ));
        AtomicReference<JsonNode> suppliedEvidence = new AtomicReference<>();
        IdentifierSkill skill = new IdentifierSkill(
                gitTool,
                (system, user, schemaName, schema, tools, requireToolCall) -> {
                    JsonNode toolResult = tools.get(0).executor().apply(json("""
                            {"context_scope":"current_incident_context"}
                            """));
                    suppliedEvidence.set(toolResult);
                    assertTrue(schema.path("properties").path("commitId").path("description")
                            .asText().contains("exact commit ID returned by Git"));
                    assertTrue(schema.path("properties").path("changedFiles").path("description")
                            .asText().contains("exact paths"));
                    assertFalse(schema.has("if"));
                    assertFalse(schema.has("oneOf"));
                    return json("""
                            {
                              "dependentServiceFailure": true,
                              "commitId": "c95e3d688de8f14559a148ca7b038ba079ea0f6c",
                              "changedFiles": ["service-a/src/main/java/io/github/aiops/servicea/service/DatabaseOperationService.java"],
                              "hypothesis": "The diff changes the SQL column name and the incident error log reports a database operation failure.",
                              "fixNeeded": "Review the SQL column name and test a correction.",
                              "confidence": 0.95
                            }
                            """);
                },
                objectMapper,
                24,
                "llm",
                "groq"
        );

        var result = skill.analyze(
                new MetricAlert("ServiceA", "http_5xx_rate", BigDecimal.TEN, BigDecimal.ONE, incidentTime),
                new DetectionResult(false, "Database operation failed.", List.of(
                        new LogEntry(incidentTime.minusSeconds(30), Severity.ERROR, "Database operation failed", "ServiceA")
                ))
        );

        assertTrue(result.isPresent());
        assertEquals("c95e3d688de8f14559a148ca7b038ba079ea0f6c", result.get().commitId());
        assertEquals("ServiceA", gitTool.service);
        assertEquals(lowerBound, gitTool.since);
        assertEquals(incidentTime, gitTool.until);
        assertEquals(1, suppliedEvidence.get().size());
        assertEquals("c95e3d688de8f14559a148ca7b038ba079ea0f6c",
                suppliedEvidence.get().path(0).path("commitId").asText());
    }

    @Test
    void modelCannotOverrideTheJavaSelectedGitWindow() {
        AtomicReference<Integer> queryCount = new AtomicReference<>(0);
        GitDiffTool gitTool = new InMemoryGitDiffTool() {
            @Override
            public List<CodeChange> getRecentCommitDiffs(String service, Instant since, Instant until) {
                queryCount.set(queryCount.get() + 1);
                return super.getRecentCommitDiffs(service, since).stream()
                        .filter(change -> !change.committedAt().isAfter(until))
                        .toList();
            }
        };
        IdentifierSkill skill = new IdentifierSkill(
                gitTool,
                (system, user, schemaName, schema, tools, requireToolCall) -> {
                    assertThrows(ToolValidationException.class, () -> tools.get(0).executor().apply(json("""
                            {"service_repository":"other-service","since_timestamp":"1970-01-01T00:00:00Z"}
                            """)));
                    return json("""
                            {
                              "dependentServiceFailure": false,
                              "commitId": null,
                              "changedFiles": [],
                              "hypothesis": "The available evidence is insufficient to identify a code cause.",
                              "fixNeeded": "Continue investigation using additional evidence.",
                              "confidence": 0.2
                            }
                            """);
                },
                objectMapper,
                24,
                "llm",
                "groq"
        );

        var result = skill.analyze(
                alert(),
                new DetectionResult(false, "Database operation failed.", List.of())
        );

        assertTrue(result.isEmpty());
        assertEquals(0, queryCount.get());
    }

    @Test
    void detectionToolRejectsQueryingAnotherService() {
        DetectionSkill skill = new DetectionSkill(
                new InMemoryLogQueryTool(),
                (system, user, schemaName, schema, tools, requireToolCall) -> tools.get(0)
                        .executor()
                        .apply(objectMapper.createObjectNode()
                                .put("service", "other-service")
                                .put("lookback_minutes", 5)
                                .put("min_severity", "ERROR")),
                objectMapper,
                5,
                "openai",
                "groq"
        );

        assertThrows(IllegalArgumentException.class, () -> skill.analyze(alert()));
    }

    @Test
    void reportsDistinctReasonForMissingCommitId() {
        LlmIntegrationException exception = identifierFailure("""
                {
                  "dependentServiceFailure": true,
                  "commitId": null,
                  "changedFiles": ["application.yml"],
                  "hypothesis": "A cause.",
                  "fixNeeded": "Review it.",
                  "confidence": 0.5
                }
                """);

        assertEquals("COMMIT_ID_MISSING", exception.getValidationReasonCode());
    }

    @Test
    void reportsDistinctReasonForCommitNotReturnedByGit() {
        LlmIntegrationException exception = identifierFailure("""
                {
                  "dependentServiceFailure": true,
                  "commitId": "invented",
                  "changedFiles": ["application.yml"],
                  "hypothesis": "A cause.",
                  "fixNeeded": "Review it.",
                  "confidence": 0.5
                }
                """);

        assertEquals("COMMIT_ID_NOT_FOUND_IN_GIT_RESULTS", exception.getValidationReasonCode());
    }

    @Test
    void reportsDistinctReasonForEmptyChangedFiles() {
        LlmIntegrationException exception = identifierFailure("""
                {
                  "dependentServiceFailure": true,
                  "commitId": "real123",
                  "changedFiles": [],
                  "hypothesis": "A cause.",
                  "fixNeeded": "Review it.",
                  "confidence": 0.5
                }
                """);

        assertEquals("CHANGED_FILES_EMPTY", exception.getValidationReasonCode());
    }

    @Test
    void reportsDistinctReasonForChangedFileNotReturnedByCommit() {
        LlmIntegrationException exception = identifierFailure("""
                {
                  "dependentServiceFailure": true,
                  "commitId": "real123",
                  "changedFiles": ["invented.java"],
                  "hypothesis": "A cause.",
                  "fixNeeded": "Review it.",
                  "confidence": 0.5
                }
                """);

        assertEquals("CHANGED_FILE_NOT_FOUND_IN_COMMIT", exception.getValidationReasonCode());
    }

    @Test
    void reportsDistinctReasonForEvidenceOnNegativeResult() {
        LlmIntegrationException exception = identifierFailure("""
                {
                  "dependentServiceFailure": false,
                  "commitId": "real123",
                  "changedFiles": ["application.yml"],
                  "hypothesis": "Insufficient evidence.",
                  "fixNeeded": "Continue investigation.",
                  "confidence": 0.2
                }
                """);

        assertEquals("UNEXPECTED_EVIDENCE_FOR_NEGATIVE_RESULT", exception.getValidationReasonCode());
    }

    @Test
    void acceptsValidNegativeIdentifierResult() {
        IdentifierSkill skill = identifierSkill("""
                {
                  "dependentServiceFailure": false,
                  "commitId": null,
                  "changedFiles": [],
                  "hypothesis": "The available evidence does not establish a code-related cause.",
                  "fixNeeded": "Continue investigating the incident.",
                  "confidence": 0.2
                }
                """, Instant.now());

        Optional<?> result = skill.analyze(
                alert(),
                new DetectionResult(false, "Database operation failed.", List.of())
        );

        assertTrue(result.isEmpty());
    }

    @Test
    void evidenceFailurePreservesMetadataCauseAndSafeDiagnostics() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(IdentifierSkill.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        MDC.put("incidentCorrelationId", "incident-safe-123");
        LlmIntegrationException exception;
        try {
            exception = identifierFailure("""
                    {
                      "dependentServiceFailure": true,
                      "commitId": "invented-secret-commit",
                      "changedFiles": ["private/secret-file.java"],
                      "hypothesis": "private customer evidence",
                      "fixNeeded": "Do not expose this text.",
                      "confidence": 0.5
                    }
                    """);
        } finally {
            MDC.remove("incidentCorrelationId");
            logger.detachAppender(logs);
            logs.stop();
        }

        assertEquals("groq", exception.getProvider());
        assertEquals("IdentifierSkill validation", exception.getStage());
        assertEquals("evidence_validation", exception.getFailureCategory());
        assertEquals("get_recent_commit_diffs", exception.getToolName());
        assertEquals("COMMIT_ID_NOT_FOUND_IN_GIT_RESULTS", exception.getValidationReasonCode());
        assertEquals("incident-safe-123", exception.getCorrelationId());
        assertInstanceOf(ToolValidationException.class, exception.getCause());
        assertEquals(
                ToolFailureReason.COMMIT_ID_NOT_FOUND_IN_GIT_RESULTS,
                ((ToolValidationException) exception.getCause()).getReason()
        );
        LlmIntegrationException bounded = exception.withBoundary("incident orchestration");
        assertEquals("incident orchestration", bounded.getBoundary());
        assertEquals(exception.getProvider(), bounded.getProvider());
        assertEquals(exception.getStage(), bounded.getStage());
        assertEquals(exception.getFailureCategory(), bounded.getFailureCategory());
        assertEquals(exception.getToolName(), bounded.getToolName());
        assertEquals(exception.getValidationReasonCode(), bounded.getValidationReasonCode());
        assertEquals(exception.getCorrelationId(), bounded.getCorrelationId());
        assertSame(exception, bounded.getCause());
        String logMessage = logs.list.get(0).getFormattedMessage();
        assertTrue(logMessage.contains("suppliedGitCommitCount=1"));
        assertTrue(logMessage.contains("selectedCommitExists=false"));
        assertTrue(logMessage.contains("reportedFilePathsMatch=false"));
        assertTrue(logMessage.contains("validationReasonCode=COMMIT_ID_NOT_FOUND_IN_GIT_RESULTS"));
        assertFalse(logMessage.contains("invented-secret-commit"));
        assertFalse(logMessage.contains("private/secret-file.java"));
        assertFalse(logMessage.contains("private customer evidence"));
        assertFalse(logMessage.contains("Do not expose this text"));

        var problem = new LlmErrorHandler().handleLlmIntegrationException(bounded);
        assertEquals("incident-safe-123", problem.getProperties().get("correlationId"));
        assertEquals(
                "COMMIT_ID_NOT_FOUND_IN_GIT_RESULTS",
                problem.getProperties().get("validationReasonCode")
        );
        assertFalse(problem.toString().contains("invented-secret-commit"));
        assertFalse(problem.toString().contains("private/secret-file.java"));
    }

    @Test
    void toolDiagnosticWrappingPreservesOriginalCause() {
        IllegalArgumentException originalCause = new IllegalArgumentException("sensitive detail");
        LlmIntegrationException original = new LlmIntegrationException(
                "safe message",
                originalCause,
                "groq",
                "tool execution",
                "tool_execution"
        );

        LlmIntegrationException diagnostic = original.withToolDiagnostics(
                "get_recent_commit_diffs",
                "INVALID_TIMESTAMP",
                "incident-safe-123"
        );

        assertNotSame(original, diagnostic);
        assertSame(originalCause, diagnostic.getCause());
        assertEquals("groq", diagnostic.getProvider());
        assertEquals("tool execution", diagnostic.getStage());
        assertEquals("tool_execution", diagnostic.getFailureCategory());
        assertEquals("get_recent_commit_diffs", diagnostic.getToolName());
        assertEquals("INVALID_TIMESTAMP", diagnostic.getValidationReasonCode());
        assertEquals("incident-safe-123", diagnostic.getCorrelationId());
        assertNull(diagnostic.getBoundary());
    }

    private LlmIntegrationException identifierFailure(String response) {
        Instant alertTime = Instant.now();
        IdentifierSkill skill = identifierSkill(response, alertTime);
        MDC.put("incidentCorrelationId", "incident-safe-123");
        try {
            return assertThrows(
                    LlmIntegrationException.class,
                    () -> skill.analyze(
                            alert(alertTime),
                            new DetectionResult(false, "Failure.", List.of())
                    )
            );
        } finally {
            MDC.remove("incidentCorrelationId");
        }
    }

    private IdentifierSkill identifierSkill(String response, Instant alertTime) {
        InMemoryGitDiffTool gitTool = new InMemoryGitDiffTool();
        gitTool.add(new CodeChange(
                "payment-service",
                "real123",
                alertTime.minusSeconds(30),
                "A change",
                List.of("application.yml"),
                "diff"
        ));
        return new IdentifierSkill(
                gitTool,
                (system, user, schemaName, schema, tools, requireToolCall) -> {
                    JsonNode result = tools.get(0).executor().apply(json("""
                            {"context_scope":"current_incident_context"}
                            """));
                    assertEquals(1, result.size());
                    return json(response);
                },
                objectMapper,
                24,
                "llm",
                "groq"
        );
    }

    @Test
    void metricThresholdBreachCannotBeReportedHealthyByModel() {
        DetectionSkill skill = new DetectionSkill(
                new InMemoryLogQueryTool(),
                (system, user, schemaName, schema, tools, requireToolCall) -> {
                    tools.get(0).executor().apply(json("""
                            {
                              "service": "payment-service",
                              "lookback_minutes": 5,
                              "min_severity": "ERROR"
                            }
                            """));
                    return json("""
                            {"isHealthy":true,"summary":"No relevant logs found."}
                            """);
                },
                objectMapper,
                5,
                "openai",
                "groq"
        );

        DetectionResult result = skill.analyze(alert());

        assertFalse(result.isHealthy());
        assertTrue(result.summary().startsWith("The metric threshold was breached."));
    }

    private MetricAlert alert() {
        return alert(Instant.now());
    }

    private MetricAlert alert(Instant timestamp) {
        return new MetricAlert(
                "payment-service",
                "http_5xx_rate",
                new BigDecimal("7.5"),
                new BigDecimal("5.0"),
                timestamp
        );
    }

    private JsonNode json(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (JsonProcessingException exception) {
            throw new AssertionError("Invalid test JSON.", exception);
        }
    }

    private static class RecordingGitDiffTool extends InMemoryGitDiffTool {

        private String service;
        private Instant since;
        private Instant until;

        @Override
        public List<CodeChange> getRecentCommitDiffs(String service, Instant since, Instant until) {
            this.service = service;
            this.since = since;
            this.until = until;
            return super.getRecentCommitDiffs(service, since).stream()
                    .filter(change -> !change.committedAt().isAfter(until))
                    .toList();
        }
    }
}
