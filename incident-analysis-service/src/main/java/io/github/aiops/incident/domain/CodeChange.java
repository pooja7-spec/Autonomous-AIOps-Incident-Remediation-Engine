package io.github.aiops.incident.domain;

import java.time.Instant;
import java.util.List;

public record CodeChange(
        String serviceRepository,
        String commitId,
        Instant committedAt,
        String commitMessage,
        List<String> changedFiles,
        String diff
) {
    public CodeChange {
        changedFiles = List.copyOf(changedFiles);
    }
}
