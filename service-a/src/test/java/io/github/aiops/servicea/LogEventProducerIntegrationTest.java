package io.github.aiops.servicea;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiops.contracts.event.LogEvent;
import io.github.aiops.contracts.event.Severity;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(
        partitions = 1,
        topics = LogEventProducerIntegrationTest.TOPIC,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = {
        "app.kafka.log-topic=" + LogEventProducerIntegrationTest.TOPIC,
        "spring.datasource.url=jdbc:h2:mem:service-a-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
class LogEventProducerIntegrationTest {

    static final String TOPIC = "service-a-log-events-test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearDatabaseRecords() {
        jdbcTemplate.update("DELETE FROM service_a_demo_records");
    }

    @Test
    void publishesHttpLogEventToKafka() throws Exception {
        LogEvent event = new LogEvent(
                "service-a-integration-event",
                Instant.parse("2026-01-02T03:04:05Z"),
                Severity.ERROR,
                "payment processing failed",
                "service-a");

        Map<String, Object> consumerProperties =
                KafkaTestUtils.consumerProps("service-a-integration-test", "false", embeddedKafka);
        JsonDeserializer<LogEvent> valueDeserializer = new JsonDeserializer<>(LogEvent.class, false);
        valueDeserializer.addTrustedPackages("io.github.aiops.contracts.event");
        try (Consumer<String, LogEvent> consumer = new KafkaConsumer<>(
                consumerProperties, new StringDeserializer(), valueDeserializer)) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, TOPIC);

            var result = mockMvc.perform(post("/api/logs")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsBytes(event)))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isAccepted());

            var records = consumer.poll(Duration.ofSeconds(10));
            assertTrue(records.count() > 0, "Kafka should contain the published log event");
            ConsumerRecord<String, LogEvent> record = records.iterator().next();
            assertEquals(event.eventId(), record.key());
            assertEquals(event, record.value());
        }
    }

    @Test
    void insertsDemoRecordIntoDatabase() throws Exception {
        mockMvc.perform(post("/api/demo/database-operation")
                        .contentType("application/json")
                        .content("""
                                {"recordId":"record-success","payload":"sample database operation"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.recordId").value("record-success"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.createdAt").isNotEmpty());

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_a_demo_records WHERE record_id = ?",
                Integer.class,
                "record-success"));
    }

    @Test
    void rejectsInvalidDatabaseOperationRequest() throws Exception {
        mockMvc.perform(post("/api/demo/database-operation")
                        .contentType("application/json")
                        .content("""
                                {"recordId":" ","payload":"sample"}
                                """))
                .andExpect(status().isBadRequest());

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_a_demo_records",
                Integer.class));
    }

    @Test
    void publishesErrorEventToKafkaWhenDatabaseOperationFails() throws Exception {
        String recordId = "record-duplicate";
        String requestBody = """
                {"recordId":"record-duplicate","payload":"sample database operation"}
                """;
        mockMvc.perform(post("/api/demo/database-operation")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isCreated());

        Map<String, Object> consumerProperties =
                KafkaTestUtils.consumerProps("service-a-db-failure-" + recordId, "false", embeddedKafka);
        JsonDeserializer<LogEvent> valueDeserializer = new JsonDeserializer<>(LogEvent.class, false);
        valueDeserializer.addTrustedPackages("io.github.aiops.contracts.event");
        try (Consumer<String, LogEvent> consumer = new KafkaConsumer<>(
                consumerProperties, new StringDeserializer(), valueDeserializer)) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, TOPIC);

            mockMvc.perform(post("/api/demo/database-operation")
                            .contentType("application/json")
                            .content(requestBody))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.message").value("Database operation failed"));

            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            LogEvent failureEvent = null;
            while (System.nanoTime() < deadline && failureEvent == null) {
                var records = consumer.poll(Duration.ofMillis(250));
                for (ConsumerRecord<String, LogEvent> record : records.records(TOPIC)) {
                    LogEvent event = record.value();
                    if ("ServiceA".equals(event.service())
                            && Severity.ERROR.equals(event.severity())
                            && "Database operation failed".equals(event.message())) {
                        failureEvent = event;
                        break;
                    }
                }
            }

            assertTrue(failureEvent != null, "Kafka should contain the database failure event");
            assertTrue(failureEvent.eventId() != null && !failureEvent.eventId().isBlank());
            assertTrue(failureEvent.timestamp().isBefore(Instant.now().plusSeconds(1)));
        }
    }
}
