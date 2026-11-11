package io.github.aiops.contracts.event;

import java.time.Instant;

public record LogEvent(
        String eventId,
        Instant timestamp,
        Severity severity,
        String message,
        String service
) {
}
