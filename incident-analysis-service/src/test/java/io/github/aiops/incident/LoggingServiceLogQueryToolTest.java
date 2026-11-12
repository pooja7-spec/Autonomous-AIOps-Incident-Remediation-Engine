package io.github.aiops.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiops.contracts.event.Severity;
import io.github.aiops.incident.domain.DetectionResult;
import io.github.aiops.incident.domain.LogEntry;
import io.github.aiops.incident.domain.MetricAlert;
import io.github.aiops.incident.skills.DetectionSkill;
import io.github.aiops.incident.tools.LogQueryException;
import io.github.aiops.incident.tools.LoggingServiceLogQueryTool;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class LoggingServiceLogQueryToolTest {

    private static final String BASE_URL = "http://localhost:8083";

    @Test
    void retrievesAndMapsRecentLogsAndAppliesMinimumSeverity() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/api/logs/recent?service=ServiceA&minutes=5"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        [
                          {
                            "eventId": "error-1",
                            "timestamp": "2026-11-11T12:45:00Z",
                            "severity": "ERROR",
                            "message": "database request failed",
                            "service": "ServiceA"
                          },
                          {
                            "eventId": "info-1",
                            "timestamp": "2026-11-11T12:44:00Z",
                            "severity": "INFO",
                            "message": "request received",
                            "service": "ServiceA"
                          }
                        ]
                        """, MediaType.APPLICATION_JSON));
        LoggingServiceLogQueryTool tool = new LoggingServiceLogQueryTool(builder.build());

        List<LogEntry> results = tool.queryLogs("ServiceA", 5, Severity.ERROR);

        assertEquals(1, results.size());
        assertEquals(new LogEntry(
                Instant.parse("2026-11-11T12:45:00Z"),
                Severity.ERROR,
                "database request failed",
                "ServiceA"), results.get(0));
        server.verify();
    }

    @Test
    void returnsEmptyListWhenLoggingServiceHasNoRecentLogs() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/api/logs/recent?service=ServiceA&minutes=5"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        LoggingServiceLogQueryTool tool = new LoggingServiceLogQueryTool(builder.build());

        assertTrue(tool.queryLogs("ServiceA", 5, Severity.ERROR).isEmpty());
        server.verify();
    }

    @Test
    void reportsConnectionFailuresWithoutFallingBack() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/api/logs/recent?service=ServiceA&minutes=5"))
                .andRespond(withException(new IOException("connection refused")));
        LoggingServiceLogQueryTool tool = new LoggingServiceLogQueryTool(builder.build());

        LogQueryException exception = assertThrows(
                LogQueryException.class,
                () -> tool.queryLogs("ServiceA", 5, Severity.ERROR));

        assertTrue(exception.getMessage().contains("Could not retrieve recent logs"));
        assertTrue(exception.getCause() instanceof org.springframework.web.client.RestClientException);
        server.verify();
    }

    @Test
    void rejectsInvalidLoggingServiceResponses() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/api/logs/recent?service=ServiceA&minutes=5"))
                .andRespond(withSuccess("""
                        [{"eventId":"invalid","timestamp":null,"severity":"ERROR",
                          "message":"missing timestamp","service":"ServiceA"}]
                        """, MediaType.APPLICATION_JSON));
        LoggingServiceLogQueryTool tool = new LoggingServiceLogQueryTool(builder.build());

        LogQueryException exception = assertThrows(
                LogQueryException.class,
                () -> tool.queryLogs("ServiceA", 5, Severity.ERROR));

        assertTrue(exception.getMessage().contains("invalid recent-log response"));
        server.verify();
    }

    @Test
    void detectionSkillAnalyzesLogsRetrievedByTheHttpAdapter() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/api/logs/recent?service=ServiceA&minutes=5"))
                .andRespond(withSuccess("""
                        [{"eventId":"error-1","timestamp":"2026-11-11T12:45:00Z",
                          "severity":"ERROR","message":"database request failed","service":"ServiceA"}]
                        """, MediaType.APPLICATION_JSON));
        LoggingServiceLogQueryTool tool = new LoggingServiceLogQueryTool(builder.build());
        DetectionSkill detectionSkill = new DetectionSkill(
                tool,
                (systemPrompt, userPrompt, schemaName, outputSchema, tools, requireToolCall) -> {
                    throw new AssertionError("Demo detection should not invoke the LLM");
                },
                new ObjectMapper().findAndRegisterModules(),
                5,
                "demo",
                "groq");
        MetricAlert alert = new MetricAlert(
                "ServiceA",
                "http_5xx_rate",
                new BigDecimal("1"),
                new BigDecimal("5"),
                Instant.parse("2026-11-11T12:46:00Z"));

        DetectionResult result = detectionSkill.analyze(alert);

        assertFalse(result.isHealthy());
        assertEquals(1, result.relevantLogs().size());
        assertEquals("database request failed", result.relevantLogs().get(0).message());
        assertTrue(result.summary().contains("1 relevant log(s)"));
        server.verify();
    }
}
