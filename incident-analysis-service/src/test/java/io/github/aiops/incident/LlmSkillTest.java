package io.github.aiops.incident;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiops.contracts.event.Severity;
import io.github.aiops.incident.domain.CodeChange;
import io.github.aiops.incident.domain.DetectionResult;
import io.github.aiops.incident.domain.LogEntry;
import io.github.aiops.incident.domain.MetricAlert;
import io.github.aiops.incident.llm.LlmIntegrationException;
import io.github.aiops.incident.skills.DetectionSkill;
import io.github.aiops.incident.skills.IdentifierSkill;
import io.github.aiops.incident.tools.InMemoryGitDiffTool;
import io.github.aiops.incident.tools.InMemoryLogQueryTool;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                "openai"
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
                    JsonNode result = tools.get(0).executor().apply(json(
                            "{\"service_repository\":\"payment-service\","
                                    + "\"since_timestamp\":\"" + alertTime.minusSeconds(3600) + "\"}"
                    ));
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
                "openai"
        );

        var result = skill.analyze(alert, new DetectionResult(false, "Connection errors.", List.of()));

        assertTrue(result.isPresent());
        assertEquals("abc123", result.get().commitId());
        assertEquals(0.72, result.get().confidence());
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
                "openai"
        );

        assertThrows(IllegalArgumentException.class, () -> skill.analyze(alert()));
    }

    @Test
    void rejectsInventedCommitInLlmResult() {
        Instant alertTime = Instant.now();
        InMemoryGitDiffTool gitTool = new InMemoryGitDiffTool();
        gitTool.add(new CodeChange(
                "payment-service",
                "real123",
                alertTime.minusSeconds(30),
                "A change",
                List.of("application.yml"),
                "diff"
        ));
        IdentifierSkill skill = new IdentifierSkill(
                gitTool,
                (system, user, schemaName, schema, tools, requireToolCall) -> {
                    tools.get(0).executor().apply(json(
                            "{\"service_repository\":\"payment-service\","
                                    + "\"since_timestamp\":\"" + alertTime.minusSeconds(3600) + "\"}"
                    ));
                    return json("""
                            {
                              "dependentServiceFailure": true,
                              "commitId": "invented",
                              "changedFiles": ["application.yml"],
                              "hypothesis": "A cause.",
                              "fixNeeded": "Review it.",
                              "confidence": 0.5
                            }
                            """);
                },
                objectMapper,
                "openai"
        );

        assertThrows(
                LlmIntegrationException.class,
                () -> skill.analyze(
                        alert(alertTime),
                        new DetectionResult(false, "Failure.", List.of())
                )
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
                "openai"
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
}
