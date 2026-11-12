package io.github.aiops.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiops.contracts.event.Severity;
import io.github.aiops.incident.config.GitIntegrationProperties;
import io.github.aiops.incident.domain.CodeChange;
import io.github.aiops.incident.domain.DetectionResult;
import io.github.aiops.incident.domain.LogEntry;
import io.github.aiops.incident.domain.MetricAlert;
import io.github.aiops.incident.domain.PatchProposal;
import io.github.aiops.incident.domain.PatchValidationStatus;
import io.github.aiops.incident.domain.RootCauseAnalysis;
import io.github.aiops.incident.llm.FunctionTool;
import io.github.aiops.incident.llm.StructuredLlmClient;
import io.github.aiops.incident.skills.PatchGenerationSkill;
import io.github.aiops.incident.tools.GitCliDiffTool;
import io.github.aiops.incident.tools.GitCommandRunner;
import io.github.aiops.incident.tools.InMemoryGitDiffTool;
import io.github.aiops.incident.tools.ServiceRepositoryRegistry;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchGenerationSkillTest {

    private static final String INCIDENT_ID = "incident-123";
    private static final String COMMIT_ID = "commit-abc";
    private static final String SOURCE_FILE = "service-a/src/main/java/example/Database.java";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesMinimalProposalUsingVerifiedGitDiffAndPreservesIdentifiers() {
        PatchProposal proposal = generate(validResponse(SOURCE_FILE, validDiff(SOURCE_FILE)));

        assertEquals(INCIDENT_ID, proposal.incidentId());
        assertEquals(COMMIT_ID, proposal.sourceCommitId());
        assertEquals(List.of(SOURCE_FILE), proposal.targetFiles());
        assertEquals(PatchValidationStatus.PROPOSED_FOR_REVIEW, proposal.validationStatus());
        assertEquals(null, proposal.rejectionReason());
        assertTrue(proposal.unifiedDiff().contains("-INSERT INTO records (paylod)"));
        assertTrue(proposal.unifiedDiff().contains("+INSERT INTO records (payload)"));
    }

    @Test
    void patchGenerationExplicitlySelectsPlainTextMode() {
        Instant incidentTime = Instant.now();
        RepositoryBackedTestGitDiffTool gitDiffTool = new RepositoryBackedTestGitDiffTool();
        gitDiffTool.add(new CodeChange(
                "ServiceA",
                COMMIT_ID,
                incidentTime.minusSeconds(60),
                "Fix database insert",
                List.of(SOURCE_FILE),
                "verified source diff"
        ));
        StructuredLlmClient client = new StructuredLlmClient() {
            @Override
            public JsonNode completeJson(
                    String systemPrompt,
                    String userPrompt,
                    String schemaName,
                    JsonNode outputSchema,
                    List<FunctionTool> tools,
                    boolean requireToolCall
            ) {
                throw new AssertionError("Patch generation must explicitly select plain-text mode.");
            }

            @Override
            public String completeText(String systemPrompt, String userPrompt, String requestName) {
                assertEquals("patch_proposal", requestName);
                assertTrue(systemPrompt.contains("Do not return JSON"));
                assertTrue(systemPrompt.contains("Markdown code"));
                assertTrue(systemPrompt.contains("fences"));
                assertTrue(systemPrompt.contains("explanatory prose, or reasoning outside the diff"));
                return validDiff(SOURCE_FILE);
            }
        };
        PatchGenerationSkill skill = new PatchGenerationSkill(
                gitDiffTool,
                client,
                objectMapper,
                24,
                "llm"
        );

        PatchProposal proposal = skill.generate(
                INCIDENT_ID,
                new MetricAlert("ServiceA", "http_5xx_rate", BigDecimal.TEN, BigDecimal.ONE, incidentTime),
                new DetectionResult(false, "Failure.", List.of()),
                new RootCauseAnalysis(COMMIT_ID, List.of(SOURCE_FILE), "Hypothesis.", "Review.", 0.9)
        );

        assertEquals(PatchValidationStatus.PROPOSED_FOR_REVIEW, proposal.validationStatus());
    }

    @Test
    void acceptsConventionalGitDiffPreambleAndMetadata() {
        String gitDiff = "diff --git a/" + SOURCE_FILE + " b/" + SOURCE_FILE + "\n"
                + "index 1234567..89abcde 100644\n"
                + "old mode 100644\n"
                + "new mode 100755\n"
                + validDiff(SOURCE_FILE);

        PatchProposal proposal = generate(validResponse(SOURCE_FILE, gitDiff));

        assertEquals(PatchValidationStatus.PROPOSED_FOR_REVIEW, proposal.validationStatus());
    }

    @Test
    void acceptsDiffWithOnlyFileHeadersAndHunks() {
        PatchProposal proposal = generate(validResponse(SOURCE_FILE, validDiff(SOURCE_FILE)));

        assertEquals(PatchValidationStatus.PROPOSED_FOR_REVIEW, proposal.validationStatus());
    }

    @Test
    void acceptsMultipleFilesWhenEveryTargetIsAuthorized() {
        String secondFile = "service-a/src/main/java/example/Repository.java";
        String diff = validDiff(SOURCE_FILE) + validDiff(secondFile);
        PatchProposal proposal = generate(
                validResponseJson(INCIDENT_ID, COMMIT_ID, List.of(SOURCE_FILE, secondFile), diff),
                List.of(SOURCE_FILE, secondFile),
                List.of(SOURCE_FILE, secondFile)
        );

        assertEquals(PatchValidationStatus.PROPOSED_FOR_REVIEW, proposal.validationStatus());
        assertEquals(List.of(SOURCE_FILE, secondFile), proposal.targetFiles());
    }

    @Test
    void rejectsEmptyPatch() {
        PatchProposal proposal = generate(validResponse(SOURCE_FILE, ""));

        assertRejected(proposal, "EMPTY_PATCH");
    }

    @Test
    void rejectsMalformedUnifiedDiff() {
        PatchProposal proposal = generate(validResponse(SOURCE_FILE, "not a unified diff"));

        assertRejected(proposal, "MALFORMED_UNIFIED_DIFF");
    }

    @Test
    void rejectsUnifiedDiffWithInvalidHunkLineCounts() {
        String malformed = "--- a/" + SOURCE_FILE + "\n"
                + "+++ b/" + SOURCE_FILE + "\n"
                + "@@ -1,2 +1,1 @@\n"
                + "-old\n"
                + "+new\n";

        assertRejected(generate(validResponse(SOURCE_FILE, malformed)), "MALFORMED_UNIFIED_DIFF");
    }

    @Test
    void rejectsInconsistentGitAndFileHeaders() {
        String otherFile = "service-a/src/main/java/example/Other.java";
        String diff = "diff --git a/" + SOURCE_FILE + " b/" + SOURCE_FILE + "\n"
                + "index 1234567..89abcde 100644\n"
                + validDiff(otherFile);

        assertRejected(generate(validResponse(SOURCE_FILE, diff)), "MALFORMED_UNIFIED_DIFF");
    }

    @Test
    void rejectsMalformedGitMetadata() {
        String diff = "diff --git a/" + SOURCE_FILE + " b/" + SOURCE_FILE + "\n"
                + "index not-a-valid-index\n"
                + validDiff(SOURCE_FILE);

        assertRejected(generate(validResponse(SOURCE_FILE, diff)), "MALFORMED_UNIFIED_DIFF");
    }

    @Test
    void acceptsGitRenameMetadataOnlyWhenItMatchesFileHeaders() {
        String oldFile = "service-a/src/main/java/example/OldDatabase.java";
        String newFile = "service-a/src/main/java/example/Database.java";
        String diff = "diff --git a/" + oldFile + " b/" + newFile + "\n"
                + "similarity index 80%\n"
                + "rename from " + oldFile + "\n"
                + "rename to " + newFile + "\n"
                + "--- a/" + oldFile + "\n"
                + "+++ b/" + newFile + "\n"
                + "@@ -1,1 +1,1 @@\n"
                + "-old\n"
                + "+new\n";
        PatchProposal proposal = generate(
                validResponseJson(INCIDENT_ID, COMMIT_ID, List.of(newFile), diff),
                List.of(oldFile, newFile),
                List.of(newFile)
        );

        assertEquals(PatchValidationStatus.PROPOSED_FOR_REVIEW, proposal.validationStatus());
        assertEquals(List.of(newFile), proposal.targetFiles());
    }

    @Test
    void rejectsRenameMetadataThatDoesNotMatchTheGitHeader() {
        String diff = "diff --git a/" + SOURCE_FILE + " b/" + SOURCE_FILE + "\n"
                + "similarity index 80%\n"
                + "rename from " + SOURCE_FILE + "\n"
                + "rename to service-a/src/main/java/example/Other.java\n"
                + validDiff(SOURCE_FILE);

        assertRejected(generate(validResponse(SOURCE_FILE, diff)), "MALFORMED_UNIFIED_DIFF");
    }

    @Test
    void rejectsTargetOutsideServiceScope() {
        String otherServiceFile = "service-b/src/main/java/example/Other.java";
        PatchProposal proposal = generate(validResponse(otherServiceFile, validDiff(otherServiceFile)));

        assertRejected(proposal, "TARGET_OUTSIDE_SERVICE_SCOPE");
    }

    @Test
    void rejectsPathTraversal() {
        String traversalPath = "service-a/../../outside.java";
        PatchProposal proposal = generate(validResponse(traversalPath, validDiff(traversalPath)));

        assertRejected(proposal, "INVALID_TARGET_PATH");
    }

    @Test
    void rejectsAbsolutePath() {
        String absolutePath = "/service-a/src/main/java/example/Database.java";
        PatchProposal proposal = generate(validResponse(absolutePath, validDiff(absolutePath)));

        assertRejected(proposal, "INVALID_TARGET_PATH");
    }

    @Test
    void sourceCommitComesFromValidatedEvidenceRatherThanModelOutput() {
        PatchProposal proposal = generate(
                validDiff(SOURCE_FILE),
                List.of(SOURCE_FILE),
                "other-commit",
                List.of(SOURCE_FILE)
        );

        assertRejected(proposal, "SOURCE_CONTEXT_NOT_VERIFIED");
    }

    @Test
    void rejectsChangedFileNotPresentInValidatedRootCauseEvidence() {
        String secondFile = "service-a/src/main/java/example/Other.java";
        PatchProposal proposal = generate(
                validResponseJson(INCIDENT_ID, COMMIT_ID, List.of(secondFile), validDiff(secondFile)),
                List.of(SOURCE_FILE, secondFile),
                List.of(SOURCE_FILE)
        );

        assertRejected(proposal, "TARGET_NOT_IN_ROOT_CAUSE_EVIDENCE");
    }

    @Test
    void rejectsMultipleFilePatchWhenOnlyOneFileIsAuthorized() {
        String secondFile = "service-a/src/main/java/example/Other.java";
        PatchProposal proposal = generate(
                validResponseJson(
                        INCIDENT_ID,
                        COMMIT_ID,
                        List.of(SOURCE_FILE, secondFile),
                        validDiff(SOURCE_FILE) + validDiff(secondFile)
                ),
                List.of(SOURCE_FILE, secondFile),
                List.of(SOURCE_FILE)
        );

        assertRejected(proposal, "TARGET_NOT_IN_ROOT_CAUSE_EVIDENCE");
    }

    @Test
    void rejectsDiffThatModifiesAFileNotListedAsATarget() {
        String secondFile = "service-a/src/main/java/example/Other.java";
        PatchProposal proposal = generate(validResponse(
                SOURCE_FILE,
                validDiff(secondFile)
        ));

        assertRejected(proposal, "TARGET_OUTSIDE_SERVICE_SCOPE");
    }

    @Test
    void javaPopulatesProposalMetadataFromValidatedIncidentAndRootCause() {
        PatchProposal proposal = generate(validDiff(SOURCE_FILE));

        assertEquals(INCIDENT_ID, proposal.incidentId());
        assertEquals(COMMIT_ID, proposal.sourceCommitId());
        assertEquals("Review the proposed correction.", proposal.summary());
        assertEquals(List.of(SOURCE_FILE), proposal.targetFiles());
        assertEquals(validDiff(SOURCE_FILE), proposal.unifiedDiff());
    }

    @Test
    void javaRejectsEmptyValidatedFixDescription() {
        assertRejected(generateWithFixDescription(validDiff(SOURCE_FILE), ""), "EMPTY_PATCH");
    }

    @Test
    void rejectsDiffWithoutHunkHeaderAndLogsStructuralDiagnostics() {
        String diff = "--- a/" + SOURCE_FILE + "\n"
                + "+++ b/" + SOURCE_FILE + "\n";

        assertParserFailureDiagnostics(diff, "MISSING_HUNK_HEADER", 2, 1, 0, 0, 0);
    }

    @Test
    void rejectsHunkWithoutChangesAndLogsStructuralDiagnostics() {
        String diff = "--- a/" + SOURCE_FILE + "\n"
                + "+++ b/" + SOURCE_FILE + "\n"
                + "@@ -1,1 +1,1 @@\n"
                + " private source text must not be logged\n";

        assertParserFailureDiagnostics(diff, "HUNK_WITHOUT_CHANGES", 4, 1, 1, 0, 0);
    }

    private void assertParserFailureDiagnostics(
            String diff,
            String expectedCategory,
            int expectedLineCount,
            int expectedFileHeaderCount,
            int expectedHunkHeaderCount,
            int expectedAddedLineCount,
            int expectedRemovedLineCount
    ) {
        Logger logger = (Logger) LoggerFactory.getLogger(PatchGenerationSkill.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertRejected(generate(validResponse(SOURCE_FILE, diff)), "MALFORMED_UNIFIED_DIFF");
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }

        assertEquals(1, logs.list.size());
        String diagnostic = logs.list.get(0).getFormattedMessage();
        assertTrue(diagnostic.contains("parserFailureCategory=" + expectedCategory));
        assertTrue(diagnostic.contains("generatedTextLength=" + diff.length()));
        assertTrue(diagnostic.contains("lineCount=" + expectedLineCount));
        assertTrue(diagnostic.contains("fileHeaderCount=" + expectedFileHeaderCount));
        assertTrue(diagnostic.contains("hunkHeaderCount=" + expectedHunkHeaderCount));
        assertTrue(diagnostic.contains("addedLineCount=" + expectedAddedLineCount));
        assertTrue(diagnostic.contains("removedLineCount=" + expectedRemovedLineCount));
        assertFalse(diagnostic.contains(diff));
    }

    @Test
    void acceptsUnifiedDiffFormatProducedByGitCliDiffTool() throws Exception {
        Path repository = temporaryDirectory.resolve("git-repository");
        Path sourceFile = repository.resolve(SOURCE_FILE);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "INSERT INTO records (payload)\n", StandardCharsets.UTF_8);
        runGit(repository, "init", "-b", "main");
        runGit(repository, "config", "user.email", "patch-test@example.invalid");
        runGit(repository, "config", "user.name", "Patch Test");
        runGit(repository, "add", SOURCE_FILE);
        runGit(repository, "commit", "-m", "Add database insert");
        Files.writeString(sourceFile, "INSERT INTO records (paylod)\n", StandardCharsets.UTF_8);
        runGit(repository, "add", SOURCE_FILE);
        runGit(repository, "commit", "-m", "Introduce database typo");

        GitIntegrationProperties properties = new GitIntegrationProperties();
        properties.setRepositoryRoot(repository);
        properties.setHistoryRef("main");
        GitIntegrationProperties.ServiceRepository service = new GitIntegrationProperties.ServiceRepository();
        service.setAllowedPaths(List.of("service-a"));
        properties.setServices(Map.of("ServiceA", service));
        GitCliDiffTool gitDiffTool = new GitCliDiffTool(
                new ServiceRepositoryRegistry(properties),
                new GitCommandRunner("git", Duration.ofSeconds(5))
        );
        CodeChange sourceChange = gitDiffTool.getRecentCommitDiffs("ServiceA", Instant.EPOCH).get(0);
        assertTrue(sourceChange.diff().startsWith("diff --git "));
        assertTrue(sourceChange.diff().contains("index "));

        PatchProposal proposal = generateWithGitDiff(
                gitDiffTool,
                sourceChange,
                sourceChange.diff()
        );

        assertEquals(PatchValidationStatus.PROPOSED_FOR_REVIEW, proposal.validationStatus());
    }

    @Test
    void refusesToGenerateWithoutRepositoryBackedContext() {
        PatchGenerationSkill skill = new PatchGenerationSkill(
                new InMemoryGitDiffTool(),
                (systemPrompt, userPrompt, schemaName, schema, tools, requireToolCall) -> {
                    throw new AssertionError("The LLM must not be called without repository-backed context.");
                },
                objectMapper,
                24,
                "llm"
        );

        PatchProposal proposal = skill.generate(
                INCIDENT_ID,
                new MetricAlert("ServiceA", "http_5xx_rate", BigDecimal.TEN, BigDecimal.ONE, Instant.now()),
                new DetectionResult(false, "Failure.", List.of()),
                new RootCauseAnalysis(COMMIT_ID, List.of(SOURCE_FILE), "Hypothesis.", "Review.", 0.9)
        );

        assertRejected(proposal, "REPOSITORY_CONTEXT_UNAVAILABLE");
    }

    private PatchProposal generate(String response) {
        return generate(response, List.of(SOURCE_FILE), List.of(SOURCE_FILE));
    }

    private PatchProposal generate(
            String response,
            List<String> sourceFiles,
            List<String> rootCauseFiles
    ) {
        return generate(response, sourceFiles, COMMIT_ID, rootCauseFiles);
    }

    private PatchProposal generate(
            String response,
            List<String> sourceFiles,
            String commitId,
            List<String> rootCauseFiles
    ) {
        return generate(response, sourceFiles, commitId, rootCauseFiles, "Review the proposed correction.");
    }

    private PatchProposal generateWithFixDescription(String response, String fixDescription) {
        return generate(
                response,
                List.of(SOURCE_FILE),
                COMMIT_ID,
                List.of(SOURCE_FILE),
                fixDescription
        );
    }

    private PatchProposal generate(
            String response,
            List<String> sourceFiles,
            String commitId,
            List<String> rootCauseFiles,
            String fixDescription
    ) {
        Instant incidentTime = Instant.now();
        RepositoryBackedTestGitDiffTool gitDiffTool = new RepositoryBackedTestGitDiffTool();
        gitDiffTool.add(new CodeChange(
                "ServiceA",
                COMMIT_ID,
                incidentTime.minusSeconds(60),
                "Fix database insert",
                sourceFiles,
                "verified source diff"
        ));
        PatchGenerationSkill skill = new PatchGenerationSkill(
                gitDiffTool,
                (systemPrompt, userPrompt, schemaName, schema, tools, requireToolCall) -> {
                    assertEquals("patch_proposal", schemaName);
                    assertTrue(tools.isEmpty());
                    assertFalse(requireToolCall);
                    assertTrue(systemPrompt.contains("Return only a conventional unified diff as plain text"));
                    assertTrue(systemPrompt.contains("Do not return JSON"));
                    assertTrue(systemPrompt.contains("Markdown code"));
                    assertTrue(systemPrompt.contains("fences"));
                    assertTrue(systemPrompt.contains("explanatory prose, or reasoning outside the diff"));
                    assertTrue(systemPrompt.contains("Do not execute commands"));
                    assertTrue(userPrompt.contains("verified source diff"));
                    assertTrue(userPrompt.contains("Database operation failed"));
                    return objectMapper.valueToTree(response);
                },
                objectMapper,
                24,
                "llm"
        );
        return skill.generate(
                INCIDENT_ID,
                new MetricAlert(
                        "ServiceA",
                        "http_5xx_rate",
                        new BigDecimal("7.5"),
                        new BigDecimal("5.0"),
                        incidentTime
                ),
                new DetectionResult(false, "Database operation failed.", List.of(
                        new LogEntry(incidentTime.minusSeconds(30), Severity.ERROR, "Database operation failed", "ServiceA")
                )),
                new RootCauseAnalysis(
                        commitId,
                        rootCauseFiles,
                        "The change may cause the observed database failure.",
                        fixDescription,
                        0.95
                )
        );
    }

    private String validResponse(String targetFile, String diff) {
        return diff;
    }

    private String validResponse(String targetFile, String diff, String commitId) {
        return diff;
    }

    private String validResponseJson(String incidentId, String commitId, List<String> targetFiles, String diff) {
        return diff;
    }

    private PatchProposal generateWithGitDiff(
            GitCliDiffTool gitDiffTool,
            CodeChange sourceChange,
            String proposedDiff
    ) {
        Instant incidentTime = Instant.now();
        PatchGenerationSkill skill = new PatchGenerationSkill(
                gitDiffTool,
                (systemPrompt, userPrompt, schemaName, schema, tools, requireToolCall) ->
                        objectMapper.valueToTree(proposedDiff),
                objectMapper,
                24,
                "llm"
        );
        String path = sourceChange.changedFiles().get(0);
        return skill.generate(
                INCIDENT_ID,
                new MetricAlert("ServiceA", "http_5xx_rate", BigDecimal.TEN, BigDecimal.ONE, incidentTime),
                new DetectionResult(false, "Database operation failed.", List.of()),
                new RootCauseAnalysis(
                        sourceChange.commitId(),
                        List.of(path),
                        "The change may have introduced a SQL typo.",
                        "Review a correction to the column name.",
                        0.95
                )
        );
    }

    private void runGit(Path repository, String... arguments) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Git fixture command failed with exit code " + exitCode);
        }
    }

    private String validDiff(String path) {
        return "--- a/" + path + "\n"
                + "+++ b/" + path + "\n"
                + "@@ -1,1 +1,1 @@\n"
                + "-INSERT INTO records (paylod)\n"
                + "+INSERT INTO records (payload)\n";
    }

    private void assertRejected(PatchProposal proposal, String reason) {
        assertEquals(PatchValidationStatus.REJECTED, proposal.validationStatus());
        assertEquals(reason, proposal.rejectionReason());
        assertTrue(proposal.unifiedDiff().isEmpty());
    }

    private static class RepositoryBackedTestGitDiffTool extends InMemoryGitDiffTool {

        @Override
        public boolean isRepositoryBacked() {
            return true;
        }
    }
}
