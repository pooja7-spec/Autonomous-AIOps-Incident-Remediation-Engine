package io.github.aiops.logging.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.format.DateTimeParseException;

@RestControllerAdvice
public class LoggingServiceExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, DateTimeParseException.class})
    public ResponseEntity<ApiError> handleInvalidRequest(RuntimeException exception) {
        return badRequest(exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleInvalidParameter(MethodArgumentTypeMismatchException exception) {
        String message = "Invalid value for query parameter '" + exception.getName() + "'";
        if (exception.getRequiredType() != null && exception.getRequiredType().isEnum()) {
            message += "; allowed values: " + String.join(", ",
                    java.util.Arrays.stream(exception.getRequiredType().getEnumConstants())
                            .map(Object::toString)
                            .toList());
        }
        return badRequest(message);
    }

    private ResponseEntity<ApiError> badRequest(String message) {
        return ResponseEntity.badRequest()
                .body(new ApiError(HttpStatus.BAD_REQUEST.value(), "Bad Request", message));
    }
}
