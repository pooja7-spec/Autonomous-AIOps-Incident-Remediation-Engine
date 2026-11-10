package io.github.aiops.incident.api;

import io.github.aiops.incident.domain.LogEntry;
import io.github.aiops.incident.tools.InMemoryLogQueryTool;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final InMemoryLogQueryTool logQueryTool;

    public LogController(InMemoryLogQueryTool logQueryTool) {
        this.logQueryTool = logQueryTool;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LogEntry ingest(@Valid @RequestBody IngestLogRequest request) {
        LogEntry entry = new LogEntry(
                request.timestamp() == null ? Instant.now() : request.timestamp(),
                request.severity(),
                request.message(),
                request.service()
        );
        return logQueryTool.add(entry);
    }
}
