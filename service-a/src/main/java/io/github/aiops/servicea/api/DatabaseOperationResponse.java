package io.github.aiops.servicea.api;

import java.time.Instant;

public record DatabaseOperationResponse(String recordId, Instant createdAt) {
}
