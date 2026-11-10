package io.github.aiops.incident.tools;

import io.github.aiops.incident.domain.LogEntry;
import io.github.aiops.incident.domain.Severity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class InMemoryLogQueryTool implements LogQueryTool {

    private static final int MAX_RESULTS = 20;
    private final CopyOnWriteArrayList<LogEntry> entries = new CopyOnWriteArrayList<>();

    public LogEntry add(LogEntry entry) {
        entries.add(entry);
        return entry;
    }

    @Override
    public List<LogEntry> queryLogs(String service, int lookbackMinutes, Severity minimumSeverity) {
        Instant cutoff = Instant.now().minus(lookbackMinutes, ChronoUnit.MINUTES);
        return entries.stream()
                .filter(entry -> entry.service().equals(service))
                .filter(entry -> !entry.timestamp().isBefore(cutoff))
                .filter(entry -> entry.severity().isAtLeast(minimumSeverity))
                .sorted(Comparator.comparing(LogEntry::timestamp).reversed())
                .limit(MAX_RESULTS)
                .toList();
    }
}
