package io.github.aiops.incident.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiops.incident.domain.CodeChange;
import io.github.aiops.incident.domain.DetectionResult;
import io.github.aiops.incident.domain.MetricAlert;
import io.github.aiops.incident.domain.PatchProposal;
import io.github.aiops.incident.domain.PatchValidationStatus;
import io.github.aiops.incident.domain.RootCauseAnalysis;
import io.github.aiops.incident.llm.StructuredLlmClient;
import io.github.aiops.incident.tools.GitDiffTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PatchGenerationSkill {

    private static final Logger LOGGER = LoggerFactory.getLogger(PatchGenerationSkill.class);
    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?: .*)?$"
    );
    private static final Pattern INDEX_METADATA = Pattern.compile(
            "^index [0-9a-fA-F]+\\.\\.[0-9a-fA-F]+(?: [0-7]{6})?$"
    );
    private static final Pattern MODE_METADATA = Pattern.compile(
            "^(?:old mode|new mode|new file mode|deleted file mode) [0-7]{6}$"
    );
    private static final Pattern SIMILARITY_METADATA = Pattern.compile(
            "^(?:similarity|dissimilarity) index \\d+%$"
    );
    private final GitDiffTool gitDiffTool;
    private final StructuredLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final Duration gitLookback;
    private final String analysisMode;

    public PatchGenerationSkill(
            GitDiffTool gitDiffTool,
            StructuredLlmClient llmClient,
            ObjectMapper objectMapper,
            @Value("${aiops.git.lookback-hours:24}") int gitLookbackHours,
            @Value("${aiops.analysis.mode:demo}") String analysisMode
    ) {
        if (gitLookbackHours < 1) {
            throw new IllegalStateException("aiops.git.lookback-hours must be positive.");
        }
        this.gitDiffTool = gitDiffTool;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.gitLookback = Duration.ofHours(gitLookbackHours);
        this.analysisMode = analysisMode;
    }

    public PatchProposal generate(
            String incidentId,
            MetricAlert alert,
            DetectionResult detection,
            RootCauseAnalysis rootCause
    ) {
        if (!isLlmMode()) {
            return rejected(incidentId, rootCause.commitId(), "LLM_MODE_REQUIRED");
        }
        if (!gitDiffTool.isRepositoryBacked()) {
            return rejected(incidentId, rootCause.commitId(), "REPOSITORY_CONTEXT_UNAVAILABLE");
        }

        List<CodeChange> changes = gitDiffTool.getRecentCommitDiffs(
                alert.service(),
                alert.timestamp().minus(gitLookback)
        );
        CodeChange sourceChange = changes.stream()
                .filter(change -> Objects.equals(change.commitId(), rootCause.commitId()))
                .findFirst()
                .orElse(null);
        if (sourceChange == null || rootCause.changedFiles().isEmpty()
                || !sourceChange.changedFiles().containsAll(rootCause.changedFiles())) {
            return rejected(incidentId, rootCause.commitId(), "SOURCE_CONTEXT_NOT_VERIFIED");
        }
        String summary = rootCause.fixNeeded();
        if (summary == null || summary.isBlank()) {
            return rejected(incidentId, rootCause.commitId(), "EMPTY_PATCH");
        }

        String unifiedDiff = llmClient.completeText(
                """
                        Return only a conventional unified diff as plain text. Do not return JSON, Markdown code
                        fences, explanatory prose, or reasoning outside the diff. Include standard Git diff file
                        headers and valid hunk information. Generate the smallest possible code correction, based
                        only on the supplied incident evidence and Git source context. Only modify files supported
                        by the validated root-cause changedFiles list. Do not invent APIs, classes, methods, or
                        database columns. Do not execute commands, access files, or attempt to apply the patch.
                        """,
                "Incident alert: " + objectMapper.valueToTree(alert)
                        + "\nDetection evidence: " + objectMapper.valueToTree(detection)
                        + "\nValidated root cause: " + objectMapper.valueToTree(rootCause)
                        + "\nVerified source commit: " + sourceChange.commitId()
                        + "\nVerified changed files: " + objectMapper.valueToTree(sourceChange.changedFiles())
                        + "\nRead-only Git diff:\n" + sourceChange.diff()
                        + "\nIncident ID: " + incidentId,
                "patch_proposal"
        );

        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            return rejected(incidentId, rootCause.commitId(), "EMPTY_PATCH");
        }

        DiffParseResult diffResult = parseUnifiedDiffFiles(unifiedDiff);
        if (diffResult.failureCategory() != null) {
            DiffDiagnostics diagnostics = collectDiffDiagnostics(unifiedDiff);
            LOGGER.warn(
                    "Patch unified diff rejected: parserFailureCategory={}, generatedTextLength={}, "
                            + "lineCount={}, fileHeaderCount={}, hunkHeaderCount={}, addedLineCount={}, "
                            + "removedLineCount={}",
                    diffResult.failureCategory(),
                    diagnostics.generatedTextLength(),
                    diagnostics.lineCount(),
                    diagnostics.fileHeaderCount(),
                    diagnostics.hunkHeaderCount(),
                    diagnostics.addedLineCount(),
                    diagnostics.removedLineCount()
            );
            String rejectionReason = "INVALID_FILE_PATH".equals(diffResult.failureCategory())
                    ? "INVALID_TARGET_PATH"
                    : "MALFORMED_UNIFIED_DIFF";
            return rejected(incidentId, rootCause.commitId(), rejectionReason);
        }
        List<String> targetFiles = List.copyOf(diffResult.files());
        if (targetFiles.isEmpty()) {
            return rejected(incidentId, rootCause.commitId(), "INVALID_TARGET_FILES");
        }
        for (String targetFile : targetFiles) {
            if (!isSafeRelativePath(targetFile)) {
                return rejected(incidentId, rootCause.commitId(), "INVALID_TARGET_PATH");
            }
            if (!sourceChange.changedFiles().contains(targetFile)) {
                return rejected(incidentId, rootCause.commitId(), "TARGET_OUTSIDE_SERVICE_SCOPE");
            }
            if (!rootCause.changedFiles().contains(targetFile)) {
                return rejected(incidentId, rootCause.commitId(), "TARGET_NOT_IN_ROOT_CAUSE_EVIDENCE");
            }
        }
        return new PatchProposal(
                incidentId,
                rootCause.commitId(),
                summary,
                targetFiles,
                unifiedDiff,
                PatchValidationStatus.PROPOSED_FOR_REVIEW,
                null
        );
    }

    private boolean isLlmMode() {
        return "llm".equalsIgnoreCase(analysisMode) || "openai".equalsIgnoreCase(analysisMode);
    }

    private boolean isSafeRelativePath(String path) {
        if (path.isBlank() || path.startsWith("/") || path.startsWith("\\")
                || path.contains("\\") || path.matches("^[A-Za-z]:.*")
                || path.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private DiffDiagnostics collectDiffDiagnostics(String diff) {
        String[] lines = diff.replace("\r\n", "\n").split("\n", -1);
        int lineCount = lines.length;
        if (lineCount > 0 && lines[lineCount - 1].isEmpty()) {
            lineCount--;
        }

        int fileHeaderCount = 0;
        int hunkHeaderCount = 0;
        int addedLineCount = 0;
        int removedLineCount = 0;
        for (int index = 0; index < lineCount; index++) {
            String line = lines[index];
            if (line.startsWith("--- a/") && index + 1 < lineCount
                    && lines[index + 1].startsWith("+++ b/")) {
                fileHeaderCount++;
                index++;
                continue;
            }
            if (!line.startsWith("@@ ")) {
                continue;
            }

            hunkHeaderCount++;
            Matcher header = HUNK_HEADER.matcher(line);
            if (!header.matches()) {
                continue;
            }
            int oldRemaining = parseCount(header.group(2));
            int newRemaining = parseCount(header.group(4));
            if (oldRemaining < 0 || newRemaining < 0) {
                continue;
            }
            while ((oldRemaining > 0 || newRemaining > 0) && index + 1 < lineCount) {
                String hunkLine = lines[++index];
                if (hunkLine.equals("\\ No newline at end of file")) {
                    continue;
                }
                if (hunkLine.startsWith(" ")) {
                    oldRemaining--;
                    newRemaining--;
                } else if (hunkLine.startsWith("-")) {
                    removedLineCount++;
                    oldRemaining--;
                } else if (hunkLine.startsWith("+")) {
                    addedLineCount++;
                    newRemaining--;
                } else {
                    break;
                }
            }
        }
        return new DiffDiagnostics(
                diff.length(),
                lineCount,
                fileHeaderCount,
                hunkHeaderCount,
                addedLineCount,
                removedLineCount
        );
    }

    private DiffParseResult parseUnifiedDiffFiles(String diff) {
        if (diff == null || diff.isBlank()) {
            return DiffParseResult.failure("EMPTY_DIFF");
        }
        diff = diff.replace("\r\n", "\n");
        if (diff.contains("\r")) {
            return DiffParseResult.failure("INVALID_LINE_ENDING");
        }
        String[] lines = diff.split("\n", -1);
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        int index = 0;
        while (index < lines.length) {
            if (index == lines.length - 1 && lines[index].isEmpty()) {
                break;
            }
            String gitOldPath = null;
            String gitNewPath = null;
            String renameFrom = null;
            String renameTo = null;
            while (index < lines.length && !lines[index].startsWith("--- a/")) {
                String metadata = lines[index];
                if (metadata.startsWith("diff --git ")) {
                    if (gitOldPath != null) {
                        return DiffParseResult.failure("DUPLICATE_GIT_HEADER", paths);
                    }
                    String[] gitPaths = parseGitHeaderPaths(metadata);
                    if (gitPaths == null) {
                        return DiffParseResult.failure("INVALID_GIT_HEADER", paths);
                    }
                    gitOldPath = gitPaths[0];
                    gitNewPath = gitPaths[1];
                } else if (INDEX_METADATA.matcher(metadata).matches()
                        || MODE_METADATA.matcher(metadata).matches()
                        || SIMILARITY_METADATA.matcher(metadata).matches()) {
                    // Recognized Git metadata does not change the file path or hunk contents.
                } else if (metadata.startsWith("rename from ")) {
                    if (renameFrom != null) {
                        return DiffParseResult.failure("DUPLICATE_RENAME_METADATA", paths);
                    }
                    renameFrom = metadata.substring("rename from ".length());
                    if (!isSafeRelativePath(renameFrom)) {
                        return DiffParseResult.failure("INVALID_RENAME_PATH", paths);
                    }
                } else if (metadata.startsWith("rename to ")) {
                    if (renameTo != null) {
                        return DiffParseResult.failure("DUPLICATE_RENAME_METADATA", paths);
                    }
                    renameTo = metadata.substring("rename to ".length());
                    if (!isSafeRelativePath(renameTo)) {
                        return DiffParseResult.failure("INVALID_RENAME_PATH", paths);
                    }
                } else {
                    return DiffParseResult.failure("UNSUPPORTED_OR_INVALID_METADATA", paths);
                }
                index++;
            }
            if (index + 1 >= lines.length || !lines[index + 1].startsWith("+++ b/")) {
                return DiffParseResult.failure("INVALID_FILE_HEADERS", paths);
            }
            String oldPath = lines[index].substring("--- a/".length());
            String newPath = lines[index + 1].substring("+++ b/".length());
            if (!isSafeRelativePath(oldPath) || !isSafeRelativePath(newPath)
                    || !paths.add(newPath)) {
                return DiffParseResult.failure("INVALID_FILE_PATH", paths);
            }
            if (gitOldPath != null && (!gitOldPath.equals(oldPath) || !gitNewPath.equals(newPath))) {
                return DiffParseResult.failure("INCONSISTENT_GIT_FILE_HEADERS", paths);
            }
            if ((renameFrom == null) != (renameTo == null)
                    || (renameFrom != null && (!renameFrom.equals(oldPath) || !renameTo.equals(newPath)))
                    || (renameFrom == null && !oldPath.equals(newPath))) {
                return DiffParseResult.failure("INCONSISTENT_RENAME_HEADERS", paths);
            }
            index += 2;
            boolean hasHunk = false;
            boolean hasChange = false;
            while (index < lines.length && lines[index].startsWith("@@ ")) {
                Matcher header = HUNK_HEADER.matcher(lines[index]);
                if (!header.matches()
                        || parseCount(header.group(1)) < 0
                        || parseCount(header.group(3)) < 0) {
                    return DiffParseResult.failure("INVALID_HUNK_HEADER", paths);
                }
                int oldRemaining = parseCount(header.group(2));
                int newRemaining = parseCount(header.group(4));
                if (oldRemaining < 0 || newRemaining < 0) {
                    return DiffParseResult.failure("INVALID_HUNK_COUNTS", paths);
                }
                index++;
                while (oldRemaining > 0 || newRemaining > 0) {
                    if (index >= lines.length) {
                        return DiffParseResult.failure("INCOMPLETE_HUNK", paths);
                    }
                    String line = lines[index++];
                    if (line.equals("\\ No newline at end of file")) {
                        continue;
                    }
                    if (line.isEmpty()) {
                        return DiffParseResult.failure("INVALID_HUNK_LINE", paths);
                    }
                    char prefix = line.charAt(0);
                    if (prefix == ' ') {
                        oldRemaining--;
                        newRemaining--;
                    } else if (prefix == '-') {
                        oldRemaining--;
                        hasChange = true;
                    } else if (prefix == '+') {
                        newRemaining--;
                        hasChange = true;
                    } else {
                        return DiffParseResult.failure("INVALID_HUNK_LINE", paths);
                    }
                    if (oldRemaining < 0 || newRemaining < 0) {
                        return DiffParseResult.failure("HUNK_COUNT_MISMATCH", paths);
                    }
                }
                while (index < lines.length && lines[index].equals("\\ No newline at end of file")) {
                    index++;
                }
                hasHunk = true;
            }
            if (!hasHunk) {
                return DiffParseResult.failure("MISSING_HUNK_HEADER", paths);
            }
            if (!hasChange) {
                return DiffParseResult.failure("HUNK_WITHOUT_CHANGES", paths);
            }
        }
        return paths.isEmpty()
                ? DiffParseResult.failure("NO_FILE_SECTIONS", paths)
                : new DiffParseResult(paths, null);
    }

    private String[] parseGitHeaderPaths(String line) {
        String headerPrefix = "diff --git a/";
        if (!line.startsWith(headerPrefix)) {
            return null;
        }
        String paths = line.substring(headerPrefix.length());
        int separator = paths.lastIndexOf(" b/");
        if (separator <= 0) {
            return null;
        }
        String oldPath = paths.substring(0, separator);
        String newPath = paths.substring(separator + " b/".length());
        return isSafeRelativePath(oldPath) && isSafeRelativePath(newPath)
                ? new String[]{oldPath, newPath}
                : null;
    }

    private int parseCount(String count) {
        if (count == null) {
            return 1;
        }
        try {
            return Integer.parseInt(count);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private PatchProposal rejected(String incidentId, String sourceCommitId, String reason) {
        return new PatchProposal(
                incidentId,
                sourceCommitId,
                "",
                List.of(),
                "",
                PatchValidationStatus.REJECTED,
                reason
        );
    }

    private record DiffParseResult(Set<String> files, String failureCategory) {

        private DiffParseResult {
            files = new LinkedHashSet<>(files);
        }

        private static DiffParseResult failure(String category) {
            return failure(category, Set.of());
        }

        private static DiffParseResult failure(String category, Set<String> parsedFiles) {
            return new DiffParseResult(parsedFiles, category);
        }
    }

    private record DiffDiagnostics(
            int generatedTextLength,
            int lineCount,
            int fileHeaderCount,
            int hunkHeaderCount,
            int addedLineCount,
            int removedLineCount
    ) {
    }
}
