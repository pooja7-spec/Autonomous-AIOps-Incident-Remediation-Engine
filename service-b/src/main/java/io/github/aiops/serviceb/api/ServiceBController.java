package io.github.aiops.serviceb.api;

import io.github.aiops.contracts.event.LogEvent;
import io.github.aiops.serviceb.messaging.LogEventProducer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;
import java.util.Map;

@RestController
public class ServiceBController {

    private final LogEventProducer logEventProducer;

    public ServiceBController(LogEventProducer logEventProducer) {
        this.logEventProducer = logEventProducer;
    }

    @GetMapping("/api/info")
    public Map<String, String> info() {
        return Map.of(
                "service", "service-b",
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
