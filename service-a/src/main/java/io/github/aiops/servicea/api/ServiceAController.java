package io.github.aiops.servicea.api;

import io.github.aiops.contracts.event.LogEvent;
import io.github.aiops.servicea.messaging.LogEventProducer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;
import java.util.Map;

@RestController
public class ServiceAController {

    private final LogEventProducer logEventProducer;

    public ServiceAController(LogEventProducer logEventProducer) {
        this.logEventProducer = logEventProducer;
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
}
