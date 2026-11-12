package io.github.aiops.incident.llm;

public class LlmIntegrationException extends RuntimeException {

    private final String provider;
    private final String stage;
    private final Integer upstreamStatus;
    private final String upstreamErrorType;
    private final String upstreamErrorCode;
    private final String upstreamErrorParam;
    private final String upstreamErrorCategory;
    private final String failureCategory;
    private final String boundary;
    private final String toolName;
    private final String validationReasonCode;
    private final String correlationId;

    public LlmIntegrationException(String message) {
        super(message);
        this.provider = null;
        this.stage = null;
        this.upstreamStatus = null;
        this.upstreamErrorType = null;
        this.upstreamErrorCode = null;
        this.upstreamErrorParam = null;
        this.upstreamErrorCategory = null;
        this.failureCategory = "llm_integration";
        this.boundary = null;
        this.toolName = null;
        this.validationReasonCode = null;
        this.correlationId = null;
    }

    public LlmIntegrationException(String message, Throwable cause) {
        super(message, cause);
        this.provider = null;
        this.stage = null;
        this.upstreamStatus = null;
        this.upstreamErrorType = null;
        this.upstreamErrorCode = null;
        this.upstreamErrorParam = null;
        this.upstreamErrorCategory = null;
        this.failureCategory = "llm_integration";
        this.boundary = null;
        this.toolName = null;
        this.validationReasonCode = null;
        this.correlationId = null;
    }

    public LlmIntegrationException(String message, String stage, String failureCategory) {
        this(message, null, stage, failureCategory);
    }

    public LlmIntegrationException(String message, String provider, String stage, String failureCategory) {
        super(message);
        this.provider = provider;
        this.stage = stage;
        this.upstreamStatus = null;
        this.upstreamErrorType = null;
        this.upstreamErrorCode = null;
        this.upstreamErrorParam = null;
        this.upstreamErrorCategory = null;
        this.failureCategory = failureCategory;
        this.boundary = null;
        this.toolName = null;
        this.validationReasonCode = null;
        this.correlationId = null;
    }

    public LlmIntegrationException(
            String message,
            Throwable cause,
            String provider,
            String stage,
            String failureCategory
    ) {
        super(message, cause);
        this.provider = provider;
        this.stage = stage;
        this.upstreamStatus = null;
        this.upstreamErrorType = null;
        this.upstreamErrorCode = null;
        this.upstreamErrorParam = null;
        this.upstreamErrorCategory = null;
        this.failureCategory = failureCategory;
        this.boundary = null;
        this.toolName = null;
        this.validationReasonCode = null;
        this.correlationId = null;
    }

    public LlmIntegrationException(
            String message,
            Throwable cause,
            String provider,
            String stage,
            int upstreamStatus,
            String upstreamErrorType
    ) {
        this(message, cause, provider, stage, upstreamStatus, upstreamErrorType, null, null);
    }

    public LlmIntegrationException(
            String message,
            Throwable cause,
            String provider,
            String stage,
            int upstreamStatus,
            String upstreamErrorType,
            String upstreamErrorCode,
            String upstreamErrorParam
    ) {
        this(message, cause, provider, stage, upstreamStatus, upstreamErrorType,
                upstreamErrorCode, upstreamErrorParam, null);
    }

    public LlmIntegrationException(
            String message,
            Throwable cause,
            String provider,
            String stage,
            int upstreamStatus,
            String upstreamErrorType,
            String upstreamErrorCode,
            String upstreamErrorParam,
            String upstreamErrorCategory
    ) {
        super(message, cause);
        this.provider = provider;
        this.stage = stage;
        this.upstreamStatus = upstreamStatus;
        this.upstreamErrorType = upstreamErrorType;
        this.upstreamErrorCode = upstreamErrorCode;
        this.upstreamErrorParam = upstreamErrorParam;
        this.upstreamErrorCategory = upstreamErrorCategory;
        this.failureCategory = "upstream_http";
        this.boundary = null;
        this.toolName = null;
        this.validationReasonCode = null;
        this.correlationId = null;
    }

    private LlmIntegrationException(LlmIntegrationException cause, String boundary) {
        super(cause.getMessage(), cause);
        this.provider = cause.provider;
        this.stage = cause.stage;
        this.upstreamStatus = cause.upstreamStatus;
        this.upstreamErrorType = cause.upstreamErrorType;
        this.upstreamErrorCode = cause.upstreamErrorCode;
        this.upstreamErrorParam = cause.upstreamErrorParam;
        this.upstreamErrorCategory = cause.upstreamErrorCategory;
        this.failureCategory = cause.failureCategory;
        this.boundary = boundary;
        this.toolName = cause.toolName;
        this.validationReasonCode = cause.validationReasonCode;
        this.correlationId = cause.correlationId;
    }

    private LlmIntegrationException(
            LlmIntegrationException cause,
            String toolName,
            String validationReasonCode,
            String correlationId
    ) {
        super(cause.getMessage(), cause.getCause() == null ? cause : cause.getCause());
        this.provider = cause.provider;
        this.stage = cause.stage;
        this.upstreamStatus = cause.upstreamStatus;
        this.upstreamErrorType = cause.upstreamErrorType;
        this.upstreamErrorCode = cause.upstreamErrorCode;
        this.upstreamErrorParam = cause.upstreamErrorParam;
        this.upstreamErrorCategory = cause.upstreamErrorCategory;
        this.failureCategory = cause.failureCategory;
        this.boundary = cause.boundary;
        this.toolName = toolName;
        this.validationReasonCode = validationReasonCode;
        this.correlationId = correlationId;
    }

    public String getProvider() {
        return provider;
    }

    public String getStage() {
        return stage;
    }

    public Integer getUpstreamStatus() {
        return upstreamStatus;
    }

    public String getUpstreamErrorType() {
        return upstreamErrorType;
    }

    public String getUpstreamErrorCode() {
        return upstreamErrorCode;
    }

    public String getUpstreamErrorParam() {
        return upstreamErrorParam;
    }

    public String getUpstreamErrorCategory() {
        return upstreamErrorCategory;
    }

    public boolean hasUpstreamMetadata() {
        return upstreamStatus != null;
    }

    public String getFailureCategory() {
        return failureCategory;
    }

    public String getBoundary() {
        return boundary;
    }

    public String getToolName() {
        return toolName;
    }

    public String getValidationReasonCode() {
        return validationReasonCode;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public LlmIntegrationException withBoundary(String boundary) {
        return new LlmIntegrationException(this, boundary);
    }

    public LlmIntegrationException withToolDiagnostics(
            String toolName,
            String validationReasonCode,
            String correlationId
    ) {
        return new LlmIntegrationException(this, toolName, validationReasonCode, correlationId);
    }
}
