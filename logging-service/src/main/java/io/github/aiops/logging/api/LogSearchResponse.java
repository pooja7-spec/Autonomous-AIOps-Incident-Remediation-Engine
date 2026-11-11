package io.github.aiops.logging.api;

import io.github.aiops.contracts.event.LogEvent;

import java.util.List;

public record LogSearchResponse(
        List<LogEvent> content,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages
) {
}
