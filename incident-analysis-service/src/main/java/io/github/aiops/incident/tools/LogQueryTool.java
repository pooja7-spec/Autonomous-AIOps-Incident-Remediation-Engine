package io.github.aiops.incident.tools;

import io.github.aiops.contracts.event.Severity;
import io.github.aiops.incident.domain.LogEntry;

import java.util.List;

public interface LogQueryTool {

    List<LogEntry> queryLogs(String service, int lookbackMinutes, Severity minimumSeverity);
}
