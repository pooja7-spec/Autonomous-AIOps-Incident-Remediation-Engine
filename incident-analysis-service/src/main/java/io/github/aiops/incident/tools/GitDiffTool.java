package io.github.aiops.incident.tools;

import io.github.aiops.incident.domain.CodeChange;
import io.github.aiops.incident.llm.ToolFailureReason;
import io.github.aiops.incident.llm.ToolValidationException;

import java.time.Instant;
import java.util.List;

public interface GitDiffTool {

    List<CodeChange> getRecentCommitDiffs(String serviceRepository, Instant sinceTimestamp);

    default List<CodeChange> getRecentCommitDiffs(
            String serviceRepository,
            Instant sinceTimestamp,
            Instant untilTimestamp
    ) {
        if (sinceTimestamp == null || untilTimestamp == null) {
            throw new ToolValidationException(ToolFailureReason.INVALID_TIMESTAMP);
        }
        if (sinceTimestamp.isAfter(untilTimestamp)) {
            throw new ToolValidationException(ToolFailureReason.TIMESTAMP_OUT_OF_RANGE);
        }
        return getRecentCommitDiffs(serviceRepository, sinceTimestamp).stream()
                .filter(change -> !change.committedAt().isAfter(untilTimestamp))
                .toList();
    }

    default boolean isRepositoryBacked() {
        return false;
    }
}
