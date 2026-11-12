package io.github.aiops.incident.tools;

import io.github.aiops.contracts.event.LogEvent;
import io.github.aiops.contracts.event.Severity;
import io.github.aiops.incident.domain.LogEntry;
import io.github.aiops.incident.llm.ToolFailureReason;
import io.github.aiops.incident.llm.ToolValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Comparator;
import java.util.List;

public class LoggingServiceLogQueryTool implements LogQueryTool {

    private static final Logger logger = LoggerFactory.getLogger(LoggingServiceLogQueryTool.class);
    private static final int MAX_RESULTS = 20;
    private static final ParameterizedTypeReference<List<LogEvent>> LOG_EVENT_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public LoggingServiceLogQueryTool(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<LogEntry> queryLogs(String service, int lookbackMinutes, Severity minimumSeverity) {
        if (restClient == null) {
            throw new ToolValidationException(ToolFailureReason.LOGGING_CONFIGURATION_MISSING);
        }
        if (service == null || service.isBlank()) {
            throw new ToolValidationException(ToolFailureReason.INVALID_SERVICE_ARGUMENT);
        }
        if (lookbackMinutes < 1 || lookbackMinutes > 60) {
            throw new ToolValidationException(ToolFailureReason.LOOKBACK_OUT_OF_RANGE);
        }
        if (minimumSeverity == null) {
            throw new ToolValidationException(ToolFailureReason.INVALID_SEVERITY);
        }

        List<LogEvent> events;
        try {
            events = restClient.get()
                    .uri("/api/logs/recent?service={service}&minutes={minutes}", service, lookbackMinutes)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(LOG_EVENT_LIST);
        } catch (RestClientException exception) {
            logger.warn("Logging Service request failed for service '{}'", service, exception);
            throw new LogQueryException(
                    "Could not retrieve recent logs for service '" + service + "' from Logging Service.",
                    exception);
        }

        if (events == null) {
            throw invalidResponse(service, "the response body was empty");
        }

        try {
            return events.stream()
                    .map(event -> toLogEntry(event, service))
                    .filter(entry -> entry.severity().isAtLeast(minimumSeverity))
                    .sorted(Comparator.comparing(LogEntry::timestamp).reversed())
                    .limit(MAX_RESULTS)
                    .toList();
        } catch (InvalidLogEventException exception) {
            throw invalidResponse(service, exception.getMessage());
        }
    }

    private LogEntry toLogEntry(LogEvent event, String requestedService) {
        if (event == null
                || event.eventId() == null
                || event.eventId().isBlank()
                || event.timestamp() == null
                || event.severity() == null
                || event.message() == null
                || event.service() == null
                || event.service().isBlank()) {
            throw new InvalidLogEventException("a log event is missing required fields");
        }
        if (!event.service().equals(requestedService)) {
            throw new InvalidLogEventException("a log event belongs to a different service");
        }
        return new LogEntry(event.timestamp(), event.severity(), event.message(), event.service());
    }

    private LogQueryException invalidResponse(String service, String reason) {
        logger.warn("Logging Service returned invalid recent logs for service '{}': {}", service, reason);
        return new LogQueryException(
                "Logging Service returned an invalid recent-log response for service '" + service + "': " + reason);
    }

    private static class InvalidLogEventException extends RuntimeException {
        private InvalidLogEventException(String message) {
            super(message);
        }
    }
}
