package io.github.aiops.contracts.event;

import java.time.Instant;

public record IncidentAnalysisEvent(
        String incidentId,
        String service,
        String status,
        String summary,
        Instant timestamp
) {
}
