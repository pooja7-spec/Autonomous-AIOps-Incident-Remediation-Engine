package io.github.aiops.incident;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aiops.incident.api.LlmErrorHandler;
import io.github.aiops.incident.llm.LlmIntegrationException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmErrorHandlerTest {

    private final LlmErrorHandler errorHandler = new LlmErrorHandler();

    @Test
    void logsResourceAccessFailureUsingSafeFieldsOnly() {
        ListAppender<ILoggingEvent> logs = captureLogs();
        MDC.put("incidentCorrelationId", "incident-safe-123");
        LlmIntegrationException exception = new LlmIntegrationException(
                "request prompt-secret",
                new ResourceAccessException("credential-secret transport detail"),
                "groq",
                "tool-call request",
                "network_error"
        );

        ProblemDetail problem = errorHandler.handleLlmIntegrationException(exception);

        assertEquals(HttpStatus.BAD_GATEWAY.value(), problem.getStatus());
        assertEquals(1, logs.list.size());
        String message = logs.list.get(0).getFormattedMessage();
        assertTrue(message.contains("provider=groq"));
        assertTrue(message.contains("stage=tool-call request"));
        assertTrue(message.contains("category=network_error"));
        assertTrue(message.contains("ResourceAccessException"));
        assertTrue(message.contains("correlationId=incident-safe-123"));
        assertFalse(message.contains("credential-secret"));
        assertFalse(message.contains("request prompt-secret"));
        assertFalse(problem.toString().contains("credential-secret"));
        assertFalse(problem.toString().contains("request prompt-secret"));
        assertTrue(problem.toString().contains("incident-safe-123"));
        MDC.clear();
        detachLogs(logs);
    }

    @Test
    void logsGenericFailureWithoutExceptionMessageOrStackTrace() {
        ListAppender<ILoggingEvent> logs = captureLogs();
        LlmIntegrationException exception = new LlmIntegrationException(
                "sensitive prompt and api-key value"
        );

        ProblemDetail problem = errorHandler.handleLlmIntegrationException(exception);

        assertEquals(HttpStatus.BAD_GATEWAY.value(), problem.getStatus());
        assertEquals(1, logs.list.size());
        ILoggingEvent event = logs.list.get(0);
        assertEquals(0, event.getThrowableProxy() == null ? 0 : 1);
        String message = event.getFormattedMessage();
        assertTrue(message.contains("category=llm_integration"));
        assertTrue(message.contains("LlmIntegrationException"));
        assertFalse(message.contains("sensitive prompt"));
        assertFalse(message.contains("api-key value"));
        assertFalse(problem.toString().contains("sensitive prompt"));
        assertFalse(problem.toString().contains("api-key value"));
        detachLogs(logs);
    }

    private ListAppender<ILoggingEvent> captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(LlmErrorHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachLogs(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(LlmErrorHandler.class);
        logger.detachAppender(appender);
        appender.stop();
    }
}
