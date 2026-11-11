package io.github.aiops.servicea;

import io.github.aiops.contracts.event.LogEvent;
import io.github.aiops.contracts.event.Severity;
import io.github.aiops.servicea.api.DatabaseOperationRequest;
import io.github.aiops.servicea.messaging.LogEventProducer;
import io.github.aiops.servicea.service.DatabaseOperationException;
import io.github.aiops.servicea.service.DatabaseOperationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.SendResult;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseOperationServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private LogEventProducer logEventProducer;

    @InjectMocks
    private DatabaseOperationService databaseOperationService;

    @Test
    void databaseFailurePublishesSanitizedErrorEvenWhenKafkaPublicationFails() {
        when(jdbcTemplate.update(anyString(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        when(logEventProducer.publish(any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        assertThrows(
                DatabaseOperationException.class,
                () -> databaseOperationService.insert(
                        new DatabaseOperationRequest("record-failure", "private payload")));

        ArgumentCaptor<LogEvent> eventCaptor = ArgumentCaptor.forClass(LogEvent.class);
        verify(logEventProducer).publish(eventCaptor.capture());
        LogEvent event = eventCaptor.getValue();
        assertEquals(Severity.ERROR, event.severity());
        assertEquals("ServiceA", event.service());
        assertEquals("Database operation failed", event.message());
        assertFalse(event.eventId().isBlank());
        verify(jdbcTemplate).update(
                eq("""
                        INSERT INTO service_a_demo_records (record_id, payload, created_at)
                        VALUES (?, ?, ?)
                        """),
                eq("record-failure"),
                eq("private payload"),
                any());
    }
}
