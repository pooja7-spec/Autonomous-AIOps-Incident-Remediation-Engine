package io.github.aiops.incident.config;

import io.github.aiops.incident.tools.GitCliDiffTool;
import io.github.aiops.incident.tools.GitCommandRunner;
import io.github.aiops.incident.tools.GitDiffTool;
import io.github.aiops.incident.tools.ServiceRepositoryRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(GitIntegrationProperties.class)
@ConditionalOnProperty(prefix = "aiops.git", name = "adapter", havingValue = "cli")
public class GitCliConfiguration {

    @Bean
    ServiceRepositoryRegistry serviceRepositoryRegistry(GitIntegrationProperties properties) {
        return new ServiceRepositoryRegistry(properties);
    }

    @Bean
    @Primary
    GitDiffTool gitCliDiffTool(
            GitIntegrationProperties properties,
            ServiceRepositoryRegistry registry) {
        return new GitCliDiffTool(
                registry,
                new GitCommandRunner(
                        properties.getExecutable(),
                        Duration.ofSeconds(properties.getCommandTimeoutSeconds())));
    }
}
