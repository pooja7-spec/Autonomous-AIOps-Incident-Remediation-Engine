package io.github.aiops.incident.api;

import io.github.aiops.incident.llm.LlmIntegrationException;
import io.github.aiops.incident.tools.LogQueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Set;
import java.util.regex.Pattern;

@RestControllerAdvice
public class LlmErrorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmErrorHandler.class);
    private static final Set<String> SAFE_UPSTREAM_ERROR_TYPES = Set.of(
            "authentication_error",
            "context_length_exceeded",
            "gateway_error",
            "insufficient_quota",
            "invalid_request_error",
            "model_not_found",
            "permission_error",
            "rate_limit_exceeded",
            "server_error",
            "service_unavailable",
            "upstream_failure"
    );
    private static final Set<String> SAFE_UPSTREAM_ERROR_CODES = Set.of(
            "authentication_error",
            "context_length_exceeded",
            "failed_generation",
            "insufficient_quota",
            "invalid_api_key",
            "invalid_function_parameters",
            "invalid_json_schema",
            "invalid_request_error",
            "json_validate_failed",
            "model_not_found",
            "permission_error",
            "rate_limit_exceeded",
            "tool_use_failed"
    );
    private static final Set<String> SAFE_UPSTREAM_ERROR_CATEGORIES = Set.of(
            "EMPTY_PARAMETERS_SCHEMA",
            "INVALID_TOOL_SCHEMA",
            "UNSUPPORTED_STRICT_SCHEMA",
            "INVALID_TOOL_CHOICE",
            "OTHER_INVALID_REQUEST"
    );
    private static final Pattern SAFE_UPSTREAM_PARAM = Pattern.compile(
            "(?:tools(?:\\[\\d+\\])?(?:\\.function(?:\\.(?:name|description|strict|parameters"
                    + "(?:\\.[A-Za-z0-9_]+(?:\\[\\d+\\])?)?))?)?|tool_choice|parallel_tool_calls"
                    + "|response_format(?:\\.json_schema(?:\\.[A-Za-z0-9_]+)?)?|model|max_completion_tokens"
                    + "|messages(?:\\[\\d+\\](?:\\.[A-Za-z0-9_]+)?)?)"
    );

    @ExceptionHandler(LlmIntegrationException.class)
    public ProblemDetail handleLlmIntegrationException(LlmIntegrationException exception) {
        String correlationId = correlationId();
        LOGGER.error(
                "LLM failure: provider={}, correlationId={}, stage={}, boundary={}, status={}, "
                        + "upstreamErrorType={}, upstreamErrorCode={}, upstreamErrorParam={}, "
                        + "upstreamErrorCategory={}, "
                        + "category={}, toolName={}, validationReasonCode={}, "
                        + "exceptionClass={}, rootCauseClass={}",
                safeProvider(exception.getProvider()),
                safeCorrelationId(exception.getCorrelationId(), correlationId),
                safeStage(exception.getStage()),
                safeStage(exception.getBoundary()),
                exception.getUpstreamStatus() == null ? "unknown" : exception.getUpstreamStatus(),
                safeUpstreamErrorType(exception.getUpstreamErrorType()),
                safeUpstreamErrorCode(exception.getUpstreamErrorCode()),
                safeUpstreamErrorParam(exception.getUpstreamErrorParam()),
                safeUpstreamErrorCategory(exception.getUpstreamErrorCategory()),
                safeCategory(exception.getFailureCategory()),
                safeDiagnosticToken(exception.getToolName()),
                safeDiagnosticToken(exception.getValidationReasonCode()),
                exception.getClass().getName(),
                rootCauseClass(exception)
        );
        String detail = "LLM analysis failed. Check service logs using the correlation ID.";
        if (exception.hasUpstreamMetadata()) {
            detail = "LLM provider '" + safeProvider(exception.getProvider()) + "' failed during "
                    + safeStage(exception.getStage()) + " (HTTP " + exception.getUpstreamStatus()
                    + "; error type: " + safeUpstreamErrorType(exception.getUpstreamErrorType()) + ").";
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY,
                detail
        );
        problem.setTitle("LLM analysis failed");
        problem.setProperty("correlationId", safeCorrelationId(exception.getCorrelationId(), correlationId));
        problem.setProperty("category", safeCategory(exception.getFailureCategory()));
        problem.setProperty("exceptionClass", exception.getClass().getName());
        problem.setProperty("rootCauseClass", rootCauseClass(exception));
        if (exception.getProvider() != null) {
            problem.setProperty("provider", safeProvider(exception.getProvider()));
        }
        if (exception.getStage() != null) {
            problem.setProperty("stage", safeStage(exception.getStage()));
        }
        if (exception.getUpstreamStatus() != null) {
            problem.setProperty("upstreamStatus", exception.getUpstreamStatus());
        }
        if (exception.getUpstreamErrorType() != null) {
            problem.setProperty("upstreamErrorType", safeUpstreamErrorType(exception.getUpstreamErrorType()));
        }
        if (exception.getUpstreamErrorCode() != null) {
            problem.setProperty("upstreamErrorCode", safeUpstreamErrorCode(exception.getUpstreamErrorCode()));
        }
        if (exception.getUpstreamErrorParam() != null) {
            problem.setProperty("upstreamErrorParam", safeUpstreamErrorParam(exception.getUpstreamErrorParam()));
        }
        if (exception.getUpstreamErrorCategory() != null) {
            problem.setProperty(
                    "upstreamErrorCategory",
                    safeUpstreamErrorCategory(exception.getUpstreamErrorCategory())
            );
        }
        if (exception.getBoundary() != null) {
            problem.setProperty("boundary", safeStage(exception.getBoundary()));
        }
        if (exception.getToolName() != null) {
            problem.setProperty("toolName", safeDiagnosticToken(exception.getToolName()));
        }
        if (exception.getValidationReasonCode() != null) {
            problem.setProperty("validationReasonCode", safeDiagnosticToken(exception.getValidationReasonCode()));
        }
        return problem;
    }

    private String correlationId() {
        String correlationId = MDC.get(IncidentCorrelationFilter.MDC_KEY);
        return correlationId == null || !correlationId.matches("[A-Za-z0-9._-]{1,64}")
                ? "unavailable"
                : correlationId;
    }

    private String safeProvider(String provider) {
        return provider == null || provider.isBlank() ? "unknown" : provider.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private String safeStage(String stage) {
        return stage == null || stage.isBlank() ? "unknown" : stage.replaceAll("[^A-Za-z0-9_. -]", "_");
    }

    private String safeCategory(String category) {
        return category == null || category.isBlank() ? "llm_integration" : category.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private String safeUpstreamErrorType(String errorType) {
        return errorType != null && SAFE_UPSTREAM_ERROR_TYPES.contains(errorType) ? errorType : "unknown";
    }

    private String safeUpstreamErrorCode(String errorCode) {
        return errorCode != null && SAFE_UPSTREAM_ERROR_CODES.contains(errorCode) ? errorCode : "unknown";
    }

    private String safeUpstreamErrorParam(String errorParam) {
        return errorParam != null && SAFE_UPSTREAM_PARAM.matcher(errorParam).matches()
                ? errorParam
                : "unknown";
    }

    private String safeUpstreamErrorCategory(String errorCategory) {
        return errorCategory != null && SAFE_UPSTREAM_ERROR_CATEGORIES.contains(errorCategory)
                ? errorCategory
                : "unknown";
    }

    private String safeCorrelationId(String exceptionCorrelationId, String requestCorrelationId) {
        return exceptionCorrelationId != null
                && exceptionCorrelationId.matches("[A-Za-z0-9._-]{1,64}")
                ? exceptionCorrelationId
                : requestCorrelationId;
    }

    private String safeDiagnosticToken(String value) {
        return value == null || !value.matches("[A-Za-z0-9_.-]{1,64}") ? "unknown" : value;
    }

    private String rootCauseClass(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause == exception ? "none" : cause.getClass().getName();
    }

    @ExceptionHandler(LogQueryException.class)
    public ProblemDetail handleLogQueryException(LogQueryException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY,
                exception.getMessage()
        );
        problem.setTitle("Logging Service request failed");
        return problem;
    }
}
