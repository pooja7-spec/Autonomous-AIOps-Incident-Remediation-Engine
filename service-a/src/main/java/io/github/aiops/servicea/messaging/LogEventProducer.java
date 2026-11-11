package io.github.aiops.servicea.messaging;

import io.github.aiops.contracts.event.LogEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.CompletableFuture;

@Service
public class LogEventProducer {

    private final KafkaTemplate<String, LogEvent> kafkaTemplate;
    private final String topic;

    public LogEventProducer(
            KafkaTemplate<String, LogEvent> kafkaTemplate,
            @Value("${app.kafka.log-topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public CompletableFuture<SendResult<String, LogEvent>> publish(LogEvent event) {
        if (event == null
                || isBlank(event.eventId())
                || event.timestamp() == null
                || event.severity() == null
                || isBlank(event.message())
                || isBlank(event.service())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A complete log event is required");
        }
        return kafkaTemplate.send(topic, event.eventId(), event);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
