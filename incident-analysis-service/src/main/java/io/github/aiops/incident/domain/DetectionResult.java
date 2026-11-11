package io.github.aiops.incident.domain;

import java.util.List;

public record DetectionResult(
        boolean isHealthy,
        String summary,
        List<LogEntry> relevantLogs
) {
    public DetectionResult {
        relevantLogs = List.copyOf(relevantLogs);
    }
}
