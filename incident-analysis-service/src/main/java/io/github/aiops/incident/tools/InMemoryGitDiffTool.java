package io.github.aiops.incident.tools;

import io.github.aiops.incident.domain.CodeChange;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class InMemoryGitDiffTool implements GitDiffTool {

    private final CopyOnWriteArrayList<CodeChange> changes = new CopyOnWriteArrayList<>();

    public CodeChange add(CodeChange change) {
        changes.add(change);
        return change;
    }

    @Override
    public List<CodeChange> getRecentCommitDiffs(String serviceRepository, Instant sinceTimestamp) {
        return changes.stream()
                .filter(change -> change.serviceRepository().equals(serviceRepository))
                .filter(change -> !change.committedAt().isBefore(sinceTimestamp))
                .sorted(Comparator.comparing(CodeChange::committedAt).reversed())
                .toList();
    }
}
