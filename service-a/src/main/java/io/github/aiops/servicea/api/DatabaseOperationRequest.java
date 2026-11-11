package io.github.aiops.servicea.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DatabaseOperationRequest(
        @NotBlank @Size(max = 100) String recordId,
        @NotBlank @Size(max = 10_000) String payload
) {
}
