package io.github.aiops.incident.domain;

import java.time.Instant;

public record LogEntry(
        Instant timestamp,
        Severity severity,
        String message,
        String service
) {
}
