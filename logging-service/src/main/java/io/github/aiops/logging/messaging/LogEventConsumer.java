package io.github.aiops.logging.messaging;

import io.github.aiops.contracts.event.LogEvent;
import io.github.aiops.logging.persistence.LogEventEntity;
import io.github.aiops.logging.persistence.LogEventRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LogEventConsumer {

    private final LogEventRepository repository;

    public LogEventConsumer(LogEventRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = "${app.kafka.log-topic}")
    public void consume(LogEvent event) {
        repository.save(new LogEventEntity(event));
    }
}
