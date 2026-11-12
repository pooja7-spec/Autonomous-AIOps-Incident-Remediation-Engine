package io.github.aiops.incident;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.aiops.incident.config.RuntimeConfigurationDiagnostics;
import io.github.aiops.incident.tools.InMemoryGitDiffTool;
import io.github.aiops.incident.tools.InMemoryLogQueryTool;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigurationDiagnosticsTest {

    @Test
    void llmModeFailsClearlyWhenMemoryIntegrationsAreConfigured() {
        RuntimeConfigurationDiagnostics diagnostics = diagnostics(
                "llm",
                "memory",
                "main",
                24,
                "memory"
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                diagnostics::afterPropertiesSet
        );

        assertTrue(exception.getMessage().contains("LLM analysis requires"));
        assertTrue(exception.getMessage().contains("aiops.git.adapter=cli"));
        assertTrue(exception.getMessage().contains("aiops.logging.adapter=http"));
    }

    @Test
    void startupDiagnosticReportsEffectiveValuesWithoutCredentials() {
        Logger logger = (Logger) LoggerFactory.getLogger(RuntimeConfigurationDiagnostics.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            diagnostics(
                    "demo",
                    "memory",
                    "feature/autonomous-remediation-foundation",
                    24,
                    "memory"
            ).afterPropertiesSet();
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }

        assertEquals(1, logs.list.size());
        String message = logs.list.get(0).getFormattedMessage();
        assertTrue(message.contains("analysisMode=demo"));
        assertTrue(message.contains("gitAdapter=memory"));
        assertTrue(message.contains("gitHistoryRef=feature/autonomous-remediation-foundation"));
        assertTrue(message.contains("gitLookbackHours=24"));
        assertTrue(message.contains("loggingAdapter=memory"));
        assertFalse(message.contains("api-key"));
        assertFalse(message.contains("password"));
    }

    private RuntimeConfigurationDiagnostics diagnostics(
            String analysisMode,
            String gitAdapter,
            String historyRef,
            int lookbackHours,
            String loggingAdapter
    ) {
        return new RuntimeConfigurationDiagnostics(
                analysisMode,
                gitAdapter,
                historyRef,
                lookbackHours,
                loggingAdapter,
                new InMemoryGitDiffTool(),
                new InMemoryLogQueryTool()
        );
    }
}
