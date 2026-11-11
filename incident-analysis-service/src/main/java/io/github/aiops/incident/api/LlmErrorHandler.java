package io.github.aiops.incident.api;

import io.github.aiops.incident.llm.LlmIntegrationException;
import io.github.aiops.incident.tools.LogQueryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class LlmErrorHandler {

    @ExceptionHandler(LlmIntegrationException.class)
    public ProblemDetail handleLlmIntegrationException(LlmIntegrationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY,
                exception.getMessage()
        );
        problem.setTitle("LLM analysis failed");
        return problem;
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
