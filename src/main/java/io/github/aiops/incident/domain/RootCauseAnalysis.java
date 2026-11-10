package io.github.aiops.incident.domain;

import java.util.List;

public record RootCauseAnalysis(
        String commitId,
        List<String> changedFiles,
        String hypothesis,
        String fixNeeded,
        double confidence
) {
    public RootCauseAnalysis {
        changedFiles = List.copyOf(changedFiles);
    }
}
