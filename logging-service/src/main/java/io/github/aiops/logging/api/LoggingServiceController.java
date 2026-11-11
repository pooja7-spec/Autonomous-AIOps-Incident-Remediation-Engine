package io.github.aiops.logging.api;

import io.github.aiops.contracts.event.LogEvent;
import io.github.aiops.contracts.event.Severity;
import io.github.aiops.logging.persistence.LogEventRepository;
import io.github.aiops.logging.service.LogQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class LoggingServiceController {

    private final LogEventRepository repository;
    private final LogQueryService logQueryService;

    public LoggingServiceController(LogEventRepository repository, LogQueryService logQueryService) {
        this.repository = repository;
        this.logQueryService = logQueryService;
    }

    @GetMapping("/api/info")
    public Map<String, String> info() {
        return Map.of(
                "service", "logging-service",
                "status", "running",
                "ingestion", "consuming Kafka log events and persisting to MySQL"
        );
    }

    @GetMapping("/api/logs/{eventId}")
    public ResponseEntity<LogEvent> getLog(@PathVariable String eventId) {
        return ResponseEntity.of(repository.findById(eventId).map(entity -> entity.toContract()));
    }

    @GetMapping("/api/logs/search")
    public LogSearchResponse searchLogs(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return logQueryService.search(service, severity, startTime, endTime, page, size);
    }

    @GetMapping("/api/logs/recent")
    public List<LogEvent> recentLogs(
            @RequestParam(required = false) String service,
            @RequestParam(defaultValue = "5") int minutes) {
        return logQueryService.recent(service, minutes);
    }
}
