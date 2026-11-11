package io.github.aiops.incident.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record MetricAlert(
        String service,
        String metricName,
        BigDecimal observedValue,
        BigDecimal threshold,
        Instant timestamp
) {
    public boolean thresholdBreached() {
        return observedValue.compareTo(threshold) > 0;
    }
}
