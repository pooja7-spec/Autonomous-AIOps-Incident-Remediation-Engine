package io.github.aiops.servicea.service;

public class DatabaseOperationException extends RuntimeException {

    public DatabaseOperationException(Throwable cause) {
        super("Database operation failed", cause);
    }
}
