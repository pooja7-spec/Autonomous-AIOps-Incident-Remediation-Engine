package io.github.aiops.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiops.incident.config.GitIntegrationProperties;
import io.github.aiops.incident.domain.DetectionResult;
import io.github.aiops.incident.domain.MetricAlert;
import io.github.aiops.incident.tools.GitCliDiffTool;
import io.github.aiops.incident.tools.GitCommandException;
import io.github.aiops.incident.tools.GitCommandRunner;
import io.github.aiops.incident.tools.ServiceRepositoryRegistry;
import io.github.aiops.incident.skills.IdentifierSkill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitCliDiffToolTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void repositoryRegistryResolvesServiceAToTheConfiguredRepositoryAndPath() throws Exception {
        Path repository = createRepository();
        GitIntegrationProperties properties = properties(repository, List.of("service-a"));
        ServiceRepositoryRegistry registry = new ServiceRepositoryRegistry(properties);

        ServiceRepositoryRegistry.RepositoryTarget target = registry.resolve("ServiceA");

        assertEquals(repository.toRealPath(), target.repositoryRoot());
        assertEquals("refs/heads/main", target.historyRef());
        assertEquals(List.of("service-a/"), target.allowedPaths());
    }

    @Test
    void historyReferenceDefaultsToMain() {
        assertEquals("main", new GitIntegrationProperties().getHistoryRef());
    }

    @Test
    void repositoryRegistryRejectsUnknownServices() throws Exception {
        ServiceRepositoryRegistry registry =
                new ServiceRepositoryRegistry(properties(createRepository(), List.of("service-a")));

        assertThrows(IllegalArgumentException.class, () -> registry.resolve("ServiceB"));
    }

    @Test
    void repositoryRegistryRejectsInvalidRepositoryAndAllowedPaths() throws Exception {
        GitIntegrationProperties invalidRepository = properties(
                temporaryDirectory.resolve("missing-repository"),
                List.of("service-a"));
        assertThrows(IllegalStateException.class, () -> new ServiceRepositoryRegistry(invalidRepository));

        Path repository = createRepository();
        GitIntegrationProperties invalidPath = properties(repository, List.of("../outside"));
        assertThrows(IllegalStateException.class, () -> new ServiceRepositoryRegistry(invalidPath));

        GitIntegrationProperties missingPath = properties(repository, List.of("missing-service"));
        assertThrows(IllegalStateException.class, () -> new ServiceRepositoryRegistry(missingPath));
    }

    @Test
    void gitCliReturnsActualCommitMetadataChangedPathsAndDiff() throws Exception {
        Path repository = createRepository();
        commit(repository, "service-a/src/App.java", "class App { int value = 1; }\n", "Add app");
        commit(repository, "service-a/src/App.java", "class App { int value = 2; }\n", "Update app");

        GitCliDiffTool tool = gitTool(repository, List.of("service-a"));
        var changes = tool.getRecentCommitDiffs("ServiceA", Instant.EPOCH);

        assertEquals(2, changes.size());
        var latest = changes.get(0);
        assertEquals("ServiceA", latest.serviceRepository());
        assertEquals(40, latest.commitId().length());
        assertTrue(latest.committedAt().isBefore(Instant.now().plusSeconds(2)));
        assertEquals("Update app", latest.commitMessage());
        assertEquals(List.of("service-a/src/App.java"), latest.changedFiles());
        assertTrue(latest.diff().contains("-class App { int value = 1; }"));
        assertTrue(latest.diff().contains("+class App { int value = 2; }"));
    }

    @Test
    void gitHistoryAndDiffAreRestrictedToConfiguredServicePaths() throws Exception {
        Path repository = createRepository();
        commit(repository, "service-a/src/App.java", "class App {}\n", "Service A change");
        commit(repository, "service-b/src/Other.java", "class Other {}\n", "Service B change");
        commit(repository, "README.md", "unrelated\n", "Root documentation change");

        var changes = gitTool(repository, List.of("service-a"))
                .getRecentCommitDiffs("ServiceA", Instant.EPOCH);

        assertEquals(1, changes.size());
        assertEquals("Service A change", changes.get(0).commitMessage());
        assertTrue(changes.get(0).changedFiles().stream()
                .allMatch(path -> path.startsWith("service-a/")));
        assertFalse(changes.get(0).diff().contains("class Other"));
        assertFalse(changes.get(0).diff().contains("Root documentation"));
    }

    @Test
    void configuredFeatureReferenceProvidesCommitsWithinTheServicePath() throws Exception {
        Path repository = createRepository();
        commit(repository, "README.md", "main history\n", "Main branch documentation");
        String featureBranch = "feature/autonomous-remediation-foundation";
        runGit(repository, "branch", featureBranch);
        runGit(repository, "switch", featureBranch);
        commit(repository, "service-a/src/App.java", "class App {}\n", "Feature Service A change");
        commit(repository, "service-b/src/Other.java", "class Other {}\n", "Feature Service B change");

        GitIntegrationProperties properties = properties(repository, List.of("service-a"));
        properties.setHistoryRef(featureBranch);
        ServiceRepositoryRegistry registry = new ServiceRepositoryRegistry(properties);
        var changes = new GitCliDiffTool(registry, new GitCommandRunner("git", Duration.ofSeconds(5)))
                .getRecentCommitDiffs("ServiceA", Instant.EPOCH);

        assertEquals("refs/heads/" + featureBranch, registry.resolve("ServiceA").historyRef());
        assertEquals(1, changes.size());
        assertEquals("Feature Service A change", changes.get(0).commitMessage());
        assertEquals(List.of("service-a/src/App.java"), changes.get(0).changedFiles());
    }

    @Test
    void registryRejectsMissingHistoryReferenceClearly() throws Exception {
        GitIntegrationProperties properties =
                properties(createRepository(), List.of("service-a"));
        properties.setHistoryRef("feature/missing");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new ServiceRepositoryRegistry(properties));

        assertTrue(exception.getMessage().contains("Configured Git history reference 'feature/missing'"));
        assertTrue(exception.getMessage().contains("does not exist"));
    }

    @Test
    void emptyGitHistoryReturnsNoChanges() throws Exception {
        Path repository = createRepository();
        commit(repository, "README.md", "no service changes\n", "Documentation only");

        assertTrue(gitTool(repository, List.of("service-a"))
                .getRecentCommitDiffs("ServiceA", Instant.EPOCH)
                .isEmpty());
    }

    @Test
    void missingGitExecutableFailsClearlyWithoutFallback() throws Exception {
        Path repository = createRepository();
        ServiceRepositoryRegistry registry =
                new ServiceRepositoryRegistry(properties(repository, List.of("service-a")));
        GitCliDiffTool tool = new GitCliDiffTool(
                registry,
                new GitCommandRunner(
                        temporaryDirectory.resolve("missing-git-executable").toString(),
                        Duration.ofSeconds(2)));

        GitCommandException exception = assertThrows(
                GitCommandException.class,
                () -> tool.getRecentCommitDiffs("ServiceA", Instant.EPOCH));

        assertTrue(exception.getMessage().contains("Could not start Git"));
    }

    @Test
    void identifierSkillCanUseActualGitEvidence() throws Exception {
        Path repository = createRepository();
        commit(repository, "service-a/src/App.java", "class App { int value = 1; }\n", "Recent Service A change");
        GitCliDiffTool tool = gitTool(repository, List.of("service-a"));
        IdentifierSkill skill = new IdentifierSkill(tool, null, new ObjectMapper(), "demo");
        Instant alertTime = Instant.now().plusSeconds(1);
        MetricAlert alert = new MetricAlert(
                "ServiceA",
                "http_5xx_rate",
                new BigDecimal("7.5"),
                new BigDecimal("5.0"),
                alertTime);

        var result = skill.analyze(alert, new DetectionResult(false, "Error logs were found.", List.of()));

        assertTrue(result.isPresent());
        assertEquals("Recent Service A change", result.get().hypothesis()
                .substring("The recent commit '".length(), result.get().hypothesis().indexOf("' may be related")));
        assertEquals(List.of("service-a/src/App.java"), result.get().changedFiles());
    }

    @Test
    void sensitivePathsAreNotReturnedAsDiffEvidence() throws Exception {
        Path repository = createRepository();
        commit(repository, "service-a/src/App.java", "class App {}\n", "Add app");
        commit(repository, "service-a/.env", "TOKEN=not-for-evidence\n", "Add local environment file");

        var changes = gitTool(repository, List.of("service-a"))
                .getRecentCommitDiffs("ServiceA", Instant.EPOCH);

        assertEquals(1, changes.size());
        assertFalse(changes.get(0).diff().contains("not-for-evidence"));
        assertFalse(changes.get(0).changedFiles().contains("service-a/.env"));
    }

    private Path createRepository() throws IOException {
        Path repository = Files.createDirectory(temporaryDirectory.resolve("repository-" + System.nanoTime()));
        runGit(repository, "init");
        runGit(repository, "symbolic-ref", "HEAD", "refs/heads/main");
        runGit(repository, "config", "user.name", "Integration Test");
        runGit(repository, "config", "user.email", "integration@example.invalid");
        Files.createDirectories(repository.resolve("service-a"));
        commit(repository, "README.md", "temporary test repository\n", "Temporary repository baseline");
        return repository;
    }

    private void commit(Path repository, String relativePath, String content, String message) throws IOException {
        Path file = repository.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        runGit(repository, "add", "--", relativePath);
        runGit(repository, "commit", "-m", message);
    }

    private void runGit(Path repository, String... arguments) throws IOException {
        runGitOutput(repository, arguments);
    }

    private String runGitOutput(Path repository, String... arguments) throws IOException {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Temporary test Git command timed out.");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException("Temporary test Git command failed: " + output);
            }
            return output;
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Temporary test Git command was interrupted.", exception);
        }
    }

    private GitCliDiffTool gitTool(Path repository, List<String> allowedPaths) {
        ServiceRepositoryRegistry registry =
                new ServiceRepositoryRegistry(properties(repository, allowedPaths));
        return new GitCliDiffTool(registry, new GitCommandRunner("git", Duration.ofSeconds(5)));
    }

    private GitIntegrationProperties properties(Path repository, List<String> allowedPaths) {
        GitIntegrationProperties properties = new GitIntegrationProperties();
        properties.setRepositoryRoot(repository);
        properties.setHistoryRef("main");
        properties.setExecutable("git");
        properties.setCommandTimeoutSeconds(5);
        GitIntegrationProperties.ServiceRepository serviceRepository =
                new GitIntegrationProperties.ServiceRepository();
        serviceRepository.setAllowedPaths(allowedPaths);
        properties.setServices(java.util.Map.of("ServiceA", serviceRepository));
        return properties;
    }
}
