package io.github.aiops.incident.config;

import io.github.aiops.incident.domain.CodeChange;
import io.github.aiops.incident.domain.LogEntry;
import io.github.aiops.incident.domain.Severity;
import io.github.aiops.incident.tools.InMemoryGitDiffTool;
import io.github.aiops.incident.tools.InMemoryLogQueryTool;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;

@Configuration
public class DemoDataConfiguration {

    @Bean
    CommandLineRunner loadDemoData(
            InMemoryLogQueryTool logQueryTool,
            InMemoryGitDiffTool gitDiffTool
    ) {
        return args -> {
            Instant now = Instant.now();
            logQueryTool.add(new LogEntry(
                    now.minusSeconds(90),
                    Severity.ERROR,
                    "Database connection pool exhausted while handling checkout request",
                    "payment-service"
            ));
            gitDiffTool.add(new CodeChange(
                    "payment-service",
                    "7f3a2c1",
                    now.minusSeconds(600),
                    "Adjust database connection pool",
                    List.of("src/main/resources/application.yml"),
                    "- hikari.maximum-pool-size: 20\n+ hikari.maximum-pool-size: 2"
            ));
        };
    }
}
