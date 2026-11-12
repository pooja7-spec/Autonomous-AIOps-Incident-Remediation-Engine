package io.github.aiops.incident.domain;

import java.util.List;

public record PatchProposal(
        String incidentId,
        String sourceCommitId,
        String summary,
        List<String> targetFiles,
        String unifiedDiff,
        PatchValidationStatus validationStatus,
        String rejectionReason
) {
    public PatchProposal {
        targetFiles = List.copyOf(targetFiles);
    }
}
