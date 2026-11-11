package io.github.aiops.incident.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class GitCommandRunner {

    private static final int MAX_COMMAND_OUTPUT_BYTES = 2 * 1024 * 1024;
    private static final ExecutorService STREAM_READERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "git-command-stream-reader");
        thread.setDaemon(true);
        return thread;
    });

    private final String gitExecutable;
    private final Duration timeout;

    public GitCommandRunner(String gitExecutable, Duration timeout) {
        if (gitExecutable == null || gitExecutable.isBlank()) {
            throw new IllegalArgumentException("The Git executable must not be blank.");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("The Git command timeout must be positive.");
        }
        this.gitExecutable = gitExecutable;
        this.timeout = timeout;
    }

    public String run(Path repositoryRoot, List<String> arguments) {
        List<String> command = new java.util.ArrayList<>();
        command.add(gitExecutable);
        command.add("-C");
        command.add(repositoryRoot.toString());
        command.addAll(arguments);

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new GitCommandException("Could not start Git. Check that the Git executable is installed and available.", exception);
        }

        CompletableFuture<byte[]> stdout = readStream(process.getInputStream(), process);
        CompletableFuture<byte[]> stderr = readStream(process.getErrorStream(), process);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GitCommandException("Git command exceeded the configured timeout of " + timeout + ".");
            }
            String output = new String(stdout.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            String error = new String(stderr.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                throw new GitCommandException("Git command failed with exit code "
                        + process.exitValue() + (error.isBlank() ? "." : ": " + limit(error, 1_000)));
            }
            return output;
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitCommandException("Git command was interrupted.", exception);
        } catch (ExecutionException | TimeoutException exception) {
            process.destroyForcibly();
            throw new GitCommandException("Could not read Git command output.", exception);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private CompletableFuture<byte[]> readStream(InputStream stream, Process process) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8_192];
                int total = 0;
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_COMMAND_OUTPUT_BYTES) {
                        process.destroyForcibly();
                        throw new IOException("Git command output exceeded the configured limit.");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            } catch (IOException exception) {
                throw new GitCommandException("Could not read Git command output.", exception);
            }
        }, STREAM_READERS);
    }

    private String limit(String value, int maximumCharacters) {
        return value.length() <= maximumCharacters
                ? value
                : value.substring(0, maximumCharacters) + " [truncated]";
    }
}
