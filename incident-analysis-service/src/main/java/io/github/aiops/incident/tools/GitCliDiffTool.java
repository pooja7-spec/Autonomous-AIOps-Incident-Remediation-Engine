package io.github.aiops.incident.tools;

import io.github.aiops.incident.domain.CodeChange;
import io.github.aiops.incident.llm.ToolFailureReason;
import io.github.aiops.incident.llm.ToolValidationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class GitCliDiffTool implements GitDiffTool {

    private static final int MAX_COMMITS = 5;
    private static final int MAX_DIFF_CHARACTERS = 4_000;
    private static final String COMMIT_RECORD_SEPARATOR = "\u001e";
    private static final String FIELD_SEPARATOR = "\u001f";
    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN [^-]*(?:PRIVATE KEY|CERTIFICATE)[^-]*-----[\\s\\S]*?-----END [^-]*(?:PRIVATE KEY|CERTIFICATE)[^-]*-----",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)((?:api[_-]?key|access[_-]?key|client[_-]?secret|password|passwd|secret|token|credential|private[_-]?key)"
                    + "\\s*[:=]\\s*)(['\"]?)[^\\s,'\";]+");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*");
    private static final Pattern GITHUB_TOKEN = Pattern.compile("\\bgh[pousr]_[A-Za-z0-9_]{20,}\\b");
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");

    private final ServiceRepositoryRegistry repositoryRegistry;
    private final GitCommandRunner git;

    public GitCliDiffTool(ServiceRepositoryRegistry repositoryRegistry, GitCommandRunner git) {
        this.repositoryRegistry = repositoryRegistry;
        this.git = git;
    }

    @Override
    public boolean isRepositoryBacked() {
        return true;
    }

    @Override
    public List<CodeChange> getRecentCommitDiffs(String serviceRepository, Instant sinceTimestamp) {
        return getRecentCommitDiffs(serviceRepository, sinceTimestamp, null);
    }

    @Override
    public List<CodeChange> getRecentCommitDiffs(
            String serviceRepository,
            Instant sinceTimestamp,
            Instant untilTimestamp
    ) {
        if (sinceTimestamp == null) {
            throw new ToolValidationException(ToolFailureReason.INVALID_TIMESTAMP);
        }
        if (untilTimestamp != null && sinceTimestamp.isAfter(untilTimestamp)) {
            throw new ToolValidationException(ToolFailureReason.TIMESTAMP_OUT_OF_RANGE);
        }
        ServiceRepositoryRegistry.RepositoryTarget target = repositoryRegistry.resolve(serviceRepository);

        List<String> logArguments = new ArrayList<>(List.of(
                "log",
                "--no-color",
                "--format=%H%x1f%ct%x1e",
                "--since=" + sinceTimestamp,
                "--max-count=" + MAX_COMMITS));
        if (untilTimestamp != null) {
            logArguments.add("--until=" + untilTimestamp);
        }
        logArguments.add(target.historyRef());
        logArguments.add("--");
        target.allowedPaths().stream()
                .map(this::literalPathspec)
                .forEach(logArguments::add);

        String logOutput = git.run(target.repositoryRoot(), logArguments);
        List<CodeChange> changes = new ArrayList<>();
        for (String record : logOutput.split(COMMIT_RECORD_SEPARATOR)) {
            CommitMetadata commit = parseCommit(record);
            if (commit == null) {
                continue;
            }
            List<String> changedPaths = changedPaths(target, commit.commitId());
            if (changedPaths.isEmpty()) {
                continue;
            }
            String diff = readDiff(target, commit.commitId(), changedPaths);
            changes.add(new CodeChange(
                    target.serviceName(),
                    commit.commitId(),
                    commit.committedAt(),
                    redact(readCommitMessage(target, commit.commitId())),
                    changedPaths,
                    truncate(redact(diff), MAX_DIFF_CHARACTERS)));
        }
        return List.copyOf(changes);
    }

    private CommitMetadata parseCommit(String record) {
        String value = record.strip();
        if (value.isEmpty()) {
            return null;
        }
        String[] fields = value.split(FIELD_SEPARATOR, 2);
        if (fields.length != 2 || fields[0].isBlank()) {
            throw new GitCommandException("Git returned commit metadata in an unexpected format.");
        }
        try {
            return new CommitMetadata(
                    fields[0],
                    Instant.ofEpochSecond(Long.parseLong(fields[1])));
        } catch (NumberFormatException exception) {
            throw new GitCommandException("Git returned a commit with an invalid timestamp.", exception);
        }
    }

    private String readCommitMessage(
            ServiceRepositoryRegistry.RepositoryTarget target,
            String commitId) {
        return git.run(target.repositoryRoot(), List.of(
                "show", "-s", "--format=%B", commitId)).strip();
    }

    private List<String> changedPaths(
            ServiceRepositoryRegistry.RepositoryTarget target,
            String commitId) {
        List<String> arguments = new ArrayList<>(List.of(
                "diff-tree",
                "--root",
                "--no-commit-id",
                "--name-only",
                "-r",
                "-z",
                "--no-renames",
                commitId,
                "--"));
        target.allowedPaths().stream()
                .map(this::literalPathspec)
                .forEach(arguments::add);

        String output = git.run(target.repositoryRoot(), arguments);
        return Arrays.stream(output.split("\u0000"))
                .map(String::strip)
                .filter(path -> isSafeAllowedPath(target, path))
                .filter(path -> !isSensitivePath(path))
                .distinct()
                .toList();
    }

    private String readDiff(
            ServiceRepositoryRegistry.RepositoryTarget target,
            String commitId,
            List<String> changedPaths) {
        List<String> arguments = new ArrayList<>(List.of(
                "show",
                "--no-ext-diff",
                "--no-textconv",
                "--no-renames",
                "--no-color",
                "--format=",
                commitId,
                "--"));
        changedPaths.stream()
                .map(this::literalPathspec)
                .forEach(arguments::add);
        return git.run(target.repositoryRoot(), arguments);
    }

    private boolean isSafeAllowedPath(
            ServiceRepositoryRegistry.RepositoryTarget target,
            String path) {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\")
                || path.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        List<String> segments = Arrays.asList(path.split("/"));
        if (segments.stream().anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
            return false;
        }
        return target.allowedPaths().stream().anyMatch(path::startsWith);
    }

    private boolean isSensitivePath(String path) {
        String normalized = path.toLowerCase(java.util.Locale.ROOT);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        return fileName.equals(".env")
                || fileName.startsWith(".env.")
                || fileName.equals(".npmrc")
                || fileName.equals("id_rsa")
                || fileName.equals("id_ed25519")
                || fileName.contains("secret")
                || fileName.contains("credential")
                || fileName.contains("password")
                || fileName.contains("token")
                || fileName.endsWith(".pem")
                || fileName.endsWith(".key")
                || fileName.endsWith(".p12")
                || fileName.endsWith(".pfx")
                || fileName.endsWith(".jks");
    }

    private String literalPathspec(String path) {
        return ":(top,literal)" + path;
    }

    private String redact(String value) {
        String redacted = PEM_BLOCK.matcher(value).replaceAll("[REDACTED PEM BLOCK]");
        redacted = SECRET_ASSIGNMENT.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = GITHUB_TOKEN.matcher(redacted).replaceAll("[REDACTED TOKEN]");
        return AWS_ACCESS_KEY.matcher(redacted).replaceAll("[REDACTED AWS KEY]");
    }

    private String truncate(String value, int maximumCharacters) {
        return value.length() <= maximumCharacters
                ? value
                : value.substring(0, maximumCharacters) + "\n[diff truncated]";
    }

    private record CommitMetadata(String commitId, Instant committedAt) {
    }
}
