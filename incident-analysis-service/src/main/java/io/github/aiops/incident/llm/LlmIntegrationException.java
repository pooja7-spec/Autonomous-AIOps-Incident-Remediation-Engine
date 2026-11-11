package io.github.aiops.incident.llm;

public class LlmIntegrationException extends RuntimeException {

    public LlmIntegrationException(String message) {
        super(message);
    }

    public LlmIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
