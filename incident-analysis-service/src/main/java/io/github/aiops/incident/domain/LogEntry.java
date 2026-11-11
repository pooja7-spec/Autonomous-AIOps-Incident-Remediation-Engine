package io.github.aiops.incident.domain;

import io.github.aiops.contracts.event.Severity;

import java.time.Instant;

public record LogEntry(
        Instant timestamp,
        Severity severity,
        String message,
        String service
) {
}
