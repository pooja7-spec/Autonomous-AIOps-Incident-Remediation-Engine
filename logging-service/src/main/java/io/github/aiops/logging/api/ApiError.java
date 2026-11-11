package io.github.aiops.logging.api;

public record ApiError(int status, String error, String message) {
}
