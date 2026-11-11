package io.github.aiops.servicea.service;

import io.github.aiops.contracts.event.LogEvent;
import io.github.aiops.contracts.event.Severity;
import io.github.aiops.servicea.api.DatabaseOperationRequest;
import io.github.aiops.servicea.messaging.LogEventProducer;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
public class DatabaseOperationService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseOperationService.class);
    private static final String INSERT_SQL = """
            INSERT INTO service_a_demo_records (record_id, payload, created_at)
            VALUES (?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final LogEventProducer logEventProducer;

    public DatabaseOperationService(JdbcTemplate jdbcTemplate, LogEventProducer logEventProducer) {
        this.jdbcTemplate = jdbcTemplate;
        this.logEventProducer = logEventProducer;
    }

    public Instant insert(DatabaseOperationRequest request) {
        Instant createdAt = Instant.now();
        try {
            jdbcTemplate.update(
                    INSERT_SQL,
                    request.recordId(),
                    request.payload(),
                    Timestamp.from(createdAt));
            return createdAt;
        } catch (DataAccessException databaseFailure) {
            logger.error("Service A database operation failed.", databaseFailure);
            publishDatabaseFailure();
            throw new DatabaseOperationException(databaseFailure);
        }
    }

    private void publishDatabaseFailure() {
        LogEvent event = new LogEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                Severity.ERROR,
                "Database operation failed",
                "ServiceA");
        try {
            logEventProducer.publish(event).join();
        } catch (RuntimeException kafkaFailure) {
            logger.error("Could not publish the database failure event to Kafka.", kafkaFailure);
        }
    }
}
