package io.github.aiops.incident.skills;

import io.github.aiops.incident.domain.DetectionResult;
import io.github.aiops.incident.domain.LogEntry;
import io.github.aiops.incident.domain.MetricAlert;
import io.github.aiops.incident.domain.Severity;
import io.github.aiops.incident.tools.LogQueryTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DetectionSkill {

    private final LogQueryTool logQueryTool;
    private final int lookbackMinutes;

    public DetectionSkill(
            LogQueryTool logQueryTool,
            @Value("${aiops.detection.lookback-minutes:5}") int lookbackMinutes
    ) {
        this.logQueryTool = logQueryTool;
        this.lookbackMinutes = lookbackMinutes;
    }

    public DetectionResult analyze(MetricAlert alert) {
        List<LogEntry> logs = logQueryTool.queryLogs(alert.service(), lookbackMinutes, Severity.ERROR);
        boolean healthy = !alert.thresholdBreached() && logs.isEmpty();
        String summary = healthy
                ? "No metric threshold breach or error logs were found."
                : "Degradation detected: "
                    + (alert.thresholdBreached() ? "the metric threshold was breached" : "error logs were found")
                    + " and " + logs.size() + " relevant log(s) were retrieved.";
        return new DetectionResult(healthy, summary, logs);
    }
}
