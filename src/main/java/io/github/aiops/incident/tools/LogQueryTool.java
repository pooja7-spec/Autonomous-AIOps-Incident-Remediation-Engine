package io.github.aiops.incident.tools;

import io.github.aiops.incident.domain.LogEntry;
import io.github.aiops.incident.domain.Severity;

import java.util.List;

public interface LogQueryTool {

    List<LogEntry> queryLogs(String service, int lookbackMinutes, Severity minimumSeverity);
}
