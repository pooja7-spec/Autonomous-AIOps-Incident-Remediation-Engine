package io.github.aiops.servicea.api;

import io.github.aiops.contracts.event.LogEvent;
import io.github.aiops.servicea.messaging.LogEventProducer;
import io.github.aiops.servicea.service.DatabaseOperationException;
import io.github.aiops.servicea.service.DatabaseOperationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.time.Instant;

@RestController
public class ServiceAController {

    private final LogEventProducer logEventProducer;
    private final DatabaseOperationService databaseOperationService;

    public ServiceAController(
            LogEventProducer logEventProducer,
            DatabaseOperationService databaseOperationService) {
        this.logEventProducer = logEventProducer;
        this.databaseOperationService = databaseOperationService;
    }

    @GetMapping("/api/info")
    public Map<String, String> info() {
        return Map.of(
                "service", "service-a",
                "status", "running",
                "logEvents", "publishing to Kafka"
        );
    }

    @PostMapping("/api/logs")
    public CompletableFuture<ResponseEntity<LogEvent>> publishLog(@RequestBody LogEvent event) {
        return logEventProducer.publish(event)
                .thenApply(ignored -> ResponseEntity.accepted().body(event));
    }

    @PostMapping("/api/demo/database-operation")
    @ResponseStatus(HttpStatus.CREATED)
    public DatabaseOperationResponse performDatabaseOperation(
            @Valid @RequestBody DatabaseOperationRequest request) {
        Instant createdAt = databaseOperationService.insert(request);
        return new DatabaseOperationResponse(request.recordId(), createdAt);
    }

    @ExceptionHandler(DatabaseOperationException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ResponseBody
    public Map<String, String> handleDatabaseOperationFailure() {
        return Map.of(
                "error", "Service Unavailable",
                "message", "Database operation failed");
    }
}
