package io.github.aiops.incident.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public record TriggerIncidentRequest(
        @NotBlank String service,
        @NotBlank String metricName,
        @NotNull @DecimalMin("0.0") BigDecimal observedValue,
        @NotNull @DecimalMin("0.0") BigDecimal threshold,
        Instant timestamp
) {
}
