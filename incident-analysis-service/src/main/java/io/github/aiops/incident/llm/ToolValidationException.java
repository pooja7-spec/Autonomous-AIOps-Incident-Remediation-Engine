package io.github.aiops.incident.llm;

public class ToolValidationException extends IllegalArgumentException {

    private final ToolFailureReason reason;

    public ToolValidationException(ToolFailureReason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public ToolValidationException(ToolFailureReason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public ToolFailureReason getReason() {
        return reason;
    }
}
