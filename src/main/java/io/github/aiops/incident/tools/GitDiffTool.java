package io.github.aiops.incident.tools;

import io.github.aiops.incident.domain.CodeChange;

import java.time.Instant;
import java.util.List;

public interface GitDiffTool {

    List<CodeChange> getRecentCommitDiffs(String serviceRepository, Instant sinceTimestamp);
}
