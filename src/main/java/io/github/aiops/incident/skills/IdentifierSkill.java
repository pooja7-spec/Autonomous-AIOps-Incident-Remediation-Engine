package io.github.aiops.incident.skills;

import io.github.aiops.incident.domain.CodeChange;
import io.github.aiops.incident.domain.MetricAlert;
import io.github.aiops.incident.domain.RootCauseAnalysis;
import io.github.aiops.incident.tools.GitDiffTool;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class IdentifierSkill {

    private static final Duration COMMIT_LOOKBACK = Duration.ofHours(1);
    private final GitDiffTool gitDiffTool;

    public IdentifierSkill(GitDiffTool gitDiffTool) {
        this.gitDiffTool = gitDiffTool;
    }

    public Optional<RootCauseAnalysis> analyze(MetricAlert alert) {
        return gitDiffTool.getRecentCommitDiffs(
                        alert.service(),
                        alert.timestamp().minus(COMMIT_LOOKBACK)
                ).stream()
                .findFirst()
                .map(this::toHypothesis);
    }

    private RootCauseAnalysis toHypothesis(CodeChange change) {
        String files = String.join(", ", change.changedFiles());
        return new RootCauseAnalysis(
                change.commitId(),
                change.changedFiles(),
                "The recent commit '" + change.commitMessage()
                        + "' may be related to the incident. Review its diff and validate against the logs.",
                "Review commit " + change.commitId() + " in " + files
                        + "; if the change is confirmed as the cause, prepare a tested fix or rollback. "
                        + "No change is applied automatically.",
                0.65
        );
    }
}
