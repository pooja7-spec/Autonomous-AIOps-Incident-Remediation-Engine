package io.github.aiops.logging.persistence;

import io.github.aiops.contracts.event.LogEvent;
import io.github.aiops.contracts.event.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "log_events", indexes = {
        @Index(name = "idx_log_events_timestamp", columnList = "event_timestamp"),
        @Index(name = "idx_log_events_service_timestamp", columnList = "service,event_timestamp"),
        @Index(name = "idx_log_events_severity", columnList = "severity")
})
public class LogEventEntity {

    @Id
    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Column(name = "event_timestamp", nullable = false)
    private Instant timestamp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Severity severity;

    @Column(nullable = false, length = 4000)
    private String message;

    @Column(nullable = false, length = 255)
    private String service;

    protected LogEventEntity() {
    }

    public LogEventEntity(LogEvent event) {
        this.eventId = event.eventId();
        this.timestamp = event.timestamp();
        this.severity = event.severity();
        this.message = event.message();
        this.service = event.service();
    }

    public LogEvent toContract() {
        return new LogEvent(eventId, timestamp, severity, message, service);
    }
}
