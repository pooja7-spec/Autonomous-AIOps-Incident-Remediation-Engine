package io.github.aiops.serviceb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiops.contracts.event.LogEvent;
import io.github.aiops.contracts.event.Severity;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
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
@TestPropertySource(properties = "app.kafka.log-topic=" + LogEventProducerIntegrationTest.TOPIC)
class LogEventProducerIntegrationTest {

    static final String TOPIC = "service-b-log-events-test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Test
    void publishesHttpLogEventToKafka() throws Exception {
        LogEvent event = new LogEvent(
                "service-b-integration-event",
                Instant.parse("2026-01-02T03:04:05Z"),
                Severity.WARN,
                "database connection recovered",
                "service-b");

        Map<String, Object> consumerProperties =
                KafkaTestUtils.consumerProps("service-b-integration-test", "false", embeddedKafka);
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
}
