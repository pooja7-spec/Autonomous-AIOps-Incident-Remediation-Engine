package io.github.aiops.incident.domain;

import java.time.Instant;

public record IncidentReport(
        String incidentId,
        MetricAlert alert,
        IncidentStatus status,
        DetectionResult detection,
        RootCauseAnalysis rootCauseAnalysis,
        Instant createdAt
) {
}
