package io.github.aiops.logging;

import io.github.aiops.contracts.event.LogEvent;
import io.github.aiops.contracts.event.Severity;
import io.github.aiops.logging.persistence.LogEventEntity;
import io.github.aiops.logging.persistence.LogEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(
        partitions = 1,
        topics = LogEventIngestionIntegrationTest.TOPIC,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = {
        "app.kafka.log-topic=" + LogEventIngestionIntegrationTest.TOPIC,
        "spring.datasource.url=jdbc:h2:mem:logging-it;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class LogEventIngestionIntegrationTest {

    static final String TOPIC = "logging-service-ingestion-test";

    @Autowired
    private KafkaTemplate<String, LogEvent> kafkaTemplate;

    @Autowired
    private LogEventRepository repository;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void clearStoredEvents() {
        repository.deleteAll();
    }

    @Test
    void consumesKafkaEventPersistsItAndServesItFromTheApi() throws Exception {
        LogEvent event = new LogEvent(
                "logging-service-integration-event",
                Instant.parse("2026-01-02T03:04:05Z"),
                Severity.CRITICAL,
                "database unavailable",
                "service-a");

        kafkaTemplate.send(TOPIC, event.eventId(), event)
                .get(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var storedEvent = repository.findById(event.eventId());
            assertTrue(storedEvent.isPresent());
            assertEquals(event, storedEvent.get().toContract());
        });

        mockMvc.perform(get("/api/logs/{eventId}", event.eventId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(event.eventId()))
                .andExpect(jsonPath("$.severity").value("CRITICAL"));
    }

    @Test
    void searchesByServiceSeverityTimeRangeAndCombinedFiltersWithPagination() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        repository.saveAll(List.of(
                entity("search-a-error-new", "ServiceA", Severity.ERROR, now.minusSeconds(30)),
                entity("search-a-error-old", "ServiceA", Severity.ERROR, now.minusSeconds(90)),
                entity("search-a-warn", "ServiceA", Severity.WARN, now.minusSeconds(20)),
                entity("search-b-error", "ServiceB", Severity.ERROR, now.minusSeconds(10))));

        mockMvc.perform(get("/api/logs/search").param("service", "ServiceA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        mockMvc.perform(get("/api/logs/search").param("severity", "ERROR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        mockMvc.perform(get("/api/logs/search")
                        .param("startTime", now.minusSeconds(60).toString())
                        .param("endTime", now.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        mockMvc.perform(get("/api/logs/search")
                        .param("service", "ServiceA")
                        .param("severity", "ERROR")
                        .param("startTime", now.minusSeconds(60).toString())
                        .param("endTime", now.toString())
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].eventId").value("search-a-error-new"))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        mockMvc.perform(get("/api/logs/search").param("service", "not-present"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void returnsRecentLogsWithinFiveMinutesAndCanFilterByService() throws Exception {
        Instant now = Instant.now();
        repository.saveAll(List.of(
                entity("recent-service-a", "ServiceA", Severity.ERROR, now.minusSeconds(60)),
                entity("recent-service-b", "ServiceB", Severity.WARN, now.minusSeconds(30)),
                entity("old-service-a", "ServiceA", Severity.ERROR, now.minusSeconds(6 * 60L))));

        mockMvc.perform(get("/api/logs/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventId").value("recent-service-b"));

        mockMvc.perform(get("/api/logs/recent")
                        .param("service", "ServiceA")
                        .param("minutes", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value("recent-service-a"));
    }

    @Test
    void returnsClearBadRequestErrorsForInvalidQueryParameters() throws Exception {
        mockMvc.perform(get("/api/logs/recent").param("minutes", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("minutes must be between 1 and 60"));

        mockMvc.perform(get("/api/logs/recent").param("minutes", "61"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/logs/search").param("startTime", "not-a-timestamp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("startTime")));

        mockMvc.perform(get("/api/logs/search")
                        .param("startTime", "2026-11-11T07:00:00Z")
                        .param("endTime", "2026-11-11T06:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("startTime must be before or equal to endTime"));

        mockMvc.perform(get("/api/logs/search").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("page must be zero or greater"));

        mockMvc.perform(get("/api/logs/search").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size must be between 1 and 100"));

        mockMvc.perform(get("/api/logs/search").param("severity", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("severity")));
    }

    private LogEventEntity entity(String id, String service, Severity severity, Instant timestamp) {
        return new LogEventEntity(new LogEvent(id, timestamp, severity, "test message", service));
    }
}
