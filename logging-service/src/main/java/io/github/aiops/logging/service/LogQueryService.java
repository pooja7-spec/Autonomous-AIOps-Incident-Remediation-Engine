package io.github.aiops.logging.service;

import io.github.aiops.contracts.event.LogEvent;
import io.github.aiops.contracts.event.Severity;
import io.github.aiops.logging.api.LogSearchResponse;
import io.github.aiops.logging.persistence.LogEventEntity;
import io.github.aiops.logging.persistence.LogEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class LogQueryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_RECENT_MINUTES = 60;

    private final LogEventRepository repository;

    public LogQueryService(LogEventRepository repository) {
        this.repository = repository;
    }

    public LogSearchResponse search(
            String service,
            Severity severity,
            String startTime,
            String endTime,
            int page,
            int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }

        Instant start = parseTimestamp("startTime", startTime);
        Instant end = parseTimestamp("endTime", endTime);
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("startTime must be before or equal to endTime");
        }

        Page<LogEventEntity> result = repository.search(
                normalize(service),
                severity,
                start,
                end,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "timestamp")));
        return new LogSearchResponse(
                result.getContent().stream().map(LogEventEntity::toContract).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    public List<LogEvent> recent(String service, int minutes) {
        if (minutes < 1 || minutes > MAX_RECENT_MINUTES) {
            throw new IllegalArgumentException("minutes must be between 1 and " + MAX_RECENT_MINUTES);
        }
        Instant now = Instant.now();
        Instant start = now.minusSeconds(minutes * 60L);
        return repository.findRecent(normalize(service), start, now)
                .stream()
                .map(LogEventEntity::toContract)
                .toList();
    }

    private Instant parseTimestamp(String parameter, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    parameter + " must be a valid ISO-8601 UTC timestamp, for example 2026-11-11T06:00:00Z");
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
