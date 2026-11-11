package io.github.aiops.incident.tools;

import io.github.aiops.incident.config.GitIntegrationProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ServiceRepositoryRegistry {

    private final Path repositoryRoot;
    private final Map<String, RepositoryTarget> targets;

    public ServiceRepositoryRegistry(GitIntegrationProperties properties) {
        GitCommandRunner git = new GitCommandRunner(
                properties.getExecutable(),
                Duration.ofSeconds(properties.getCommandTimeoutSeconds()));
        this.repositoryRoot = validateRepositoryRoot(properties.getRepositoryRoot(), git);
        String historyRef = validateHistoryRef(properties.getHistoryRef(), git);
        if (properties.getServices() == null || properties.getServices().isEmpty()) {
            throw new IllegalStateException("At least one aiops.git.services mapping must be configured.");
        }

        this.targets = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, GitIntegrationProperties.ServiceRepository> entry
                : properties.getServices().entrySet()) {
            String service = entry.getKey();
            GitIntegrationProperties.ServiceRepository serviceRepository = entry.getValue();
            if (service == null || service.isBlank() || serviceRepository == null) {
                throw new IllegalStateException("Git service mappings must have a service name and configuration.");
            }
            List<String> allowedPaths = validateAllowedPaths(service, serviceRepository.getAllowedPaths());
            targets.put(service, new RepositoryTarget(service, repositoryRoot, historyRef, allowedPaths));
        }
    }

    public RepositoryTarget resolve(String serviceName) {
        RepositoryTarget target = targets.get(serviceName);
        if (target == null) {
            throw new IllegalArgumentException("No Git repository is configured for service '" + serviceName + "'.");
        }
        return target;
    }

    private Path validateRepositoryRoot(Path configuredRoot, GitCommandRunner git) {
        if (configuredRoot == null) {
            throw new IllegalStateException("aiops.git.repository-root must be configured.");
        }
        try {
            Path root = configuredRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("Configured Git repository root does not exist or is not a directory.");
            }
            Path realRoot = root.toRealPath();
            Path gitRoot = Path.of(git.run(realRoot, List.of("rev-parse", "--show-toplevel")).strip())
                    .toAbsolutePath()
                    .normalize()
                    .toRealPath();
            if (!realRoot.equals(gitRoot)) {
                throw new IllegalStateException("Configured repository root must be the root of a Git repository.");
            }
            return realRoot;
        } catch (IOException | GitCommandException exception) {
            throw new IllegalStateException("Configured repository root is not a usable Git repository.", exception);
        }
    }

    private String validateHistoryRef(String configuredRef, GitCommandRunner git) {
        if (configuredRef == null || configuredRef.isBlank() || configuredRef.startsWith("-")) {
            throw new IllegalStateException("A valid aiops.git.history-ref must be configured.");
        }
        String historyRef = configuredRef.startsWith("refs/")
                ? configuredRef
                : "refs/heads/" + configuredRef;
        try {
            if (configuredRef.startsWith("refs/")) {
                git.run(repositoryRoot, List.of("check-ref-format", historyRef));
            } else {
                git.run(repositoryRoot, List.of("check-ref-format", "--branch", configuredRef));
            }
        } catch (GitCommandException exception) {
            throw new IllegalStateException(
                    "Configured Git history reference '" + configuredRef + "' is invalid.",
                    exception);
        }
        try {
            git.run(repositoryRoot, List.of("rev-parse", "--verify", historyRef + "^{commit}"));
        } catch (GitCommandException exception) {
            throw new IllegalStateException(
                    "Configured Git history reference '" + configuredRef
                            + "' does not exist or does not point to a commit.",
                    exception);
        }
        return historyRef;
    }

    private List<String> validateAllowedPaths(String service, List<String> configuredPaths) {
        if (configuredPaths == null || configuredPaths.isEmpty()) {
            throw new IllegalStateException("Service '" + service + "' must have at least one allowed Git path.");
        }

        List<String> paths = new ArrayList<>();
        for (String configuredPath : configuredPaths) {
            if (configuredPath == null || configuredPath.isBlank()
                    || configuredPath.startsWith("/") || configuredPath.startsWith("\\")
                    || configuredPath.matches("^[A-Za-z]:.*")) {
                throw new IllegalStateException("Service '" + service + "' has an invalid allowed Git path.");
            }
            try {
                Path relative = Path.of(configuredPath);
                if (relative.isAbsolute()) {
                    throw new IllegalStateException("Allowed Git paths must be relative to the repository root.");
                }
                for (Path part : relative) {
                    if (part.toString().equals("..") || part.toString().equals(".")) {
                        throw new IllegalStateException("Allowed Git paths must not contain traversal segments.");
                    }
                }
                Path resolved = repositoryRoot.resolve(relative).normalize();
                if (!resolved.startsWith(repositoryRoot)
                        || !Files.isDirectory(resolved)
                        || !Files.isReadable(resolved)) {
                    throw new IllegalStateException("Allowed Git path does not exist as a directory inside the repository.");
                }
                Path realPath = resolved.toRealPath();
                if (!realPath.startsWith(repositoryRoot)) {
                    throw new IllegalStateException("Allowed Git path resolves outside the repository.");
                }
                String gitPath = repositoryRoot.relativize(resolved).toString().replace('\\', '/');
                paths.add(gitPath.endsWith("/") ? gitPath : gitPath + "/");
            } catch (InvalidPathException | IOException exception) {
                throw new IllegalStateException("Service '" + service + "' has an invalid allowed Git path.", exception);
            }
        }
        return List.copyOf(paths);
    }

    public record RepositoryTarget(
            String serviceName,
            Path repositoryRoot,
            String historyRef,
            List<String> allowedPaths) {
        public RepositoryTarget {
            allowedPaths = List.copyOf(allowedPaths);
        }
    }
}
