package io.github.aiops.incident.config;

import io.github.aiops.incident.tools.LogQueryTool;
import io.github.aiops.incident.tools.LoggingServiceLogQueryTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(prefix = "aiops.logging", name = "adapter", havingValue = "http")
public class LoggingServiceHttpConfiguration {

    @Bean
    RestClient loggingServiceRestClient(
            @Value("${logging.service.base-url:http://localhost:8083}") String baseUrl,
            @Value("${logging.service.connect-timeout:2s}") Duration connectTimeout,
            @Value("${logging.service.read-timeout:3s}") Duration readTimeout) {
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalStateException("Logging Service HTTP timeouts must be positive.");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    @Primary
    LogQueryTool loggingServiceLogQueryTool(RestClient loggingServiceRestClient) {
        return new LoggingServiceLogQueryTool(loggingServiceRestClient);
    }
}
