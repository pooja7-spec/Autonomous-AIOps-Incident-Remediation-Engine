package io.github.aiops.incident.api;

import io.github.aiops.contracts.event.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record IngestLogRequest(
        Instant timestamp,
        @NotNull Severity severity,
        @NotBlank String message,
        @NotBlank String service
) {
}
