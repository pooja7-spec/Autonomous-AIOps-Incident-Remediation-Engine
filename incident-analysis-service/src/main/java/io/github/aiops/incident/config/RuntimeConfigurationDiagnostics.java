package io.github.aiops.incident.config;

import io.github.aiops.incident.tools.GitCliDiffTool;
import io.github.aiops.incident.tools.GitDiffTool;
import io.github.aiops.incident.tools.InMemoryGitDiffTool;
import io.github.aiops.incident.tools.InMemoryLogQueryTool;
import io.github.aiops.incident.tools.LogQueryTool;
import io.github.aiops.incident.tools.LoggingServiceLogQueryTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class RuntimeConfigurationDiagnostics implements InitializingBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeConfigurationDiagnostics.class);

    private final String analysisMode;
    private final String gitAdapter;
    private final String gitHistoryRef;
    private final int gitLookbackHours;
    private final String loggingAdapter;
    private final GitDiffTool gitDiffTool;
    private final LogQueryTool logQueryTool;

    public RuntimeConfigurationDiagnostics(
            @Value("${aiops.analysis.mode:demo}") String analysisMode,
            @Value("${aiops.git.adapter:memory}") String gitAdapter,
            @Value("${aiops.git.history-ref:main}") String gitHistoryRef,
            @Value("${aiops.git.lookback-hours:24}") int gitLookbackHours,
            @Value("${aiops.logging.adapter:memory}") String loggingAdapter,
            GitDiffTool gitDiffTool,
            LogQueryTool logQueryTool
    ) {
        this.analysisMode = analysisMode;
        this.gitAdapter = gitAdapter;
        this.gitHistoryRef = gitHistoryRef;
        this.gitLookbackHours = gitLookbackHours;
        this.loggingAdapter = loggingAdapter;
        this.gitDiffTool = gitDiffTool;
        this.logQueryTool = logQueryTool;
    }

    @Override
    public void afterPropertiesSet() {
        String normalizedMode = normalize(analysisMode);
        String normalizedGitAdapter = normalize(gitAdapter);
        String normalizedLoggingAdapter = normalize(loggingAdapter);
        if (!normalizedMode.equals("demo") && !normalizedMode.equals("llm")
                && !normalizedMode.equals("openai")) {
            throw new IllegalStateException("Unsupported aiops.analysis.mode: " + safeToken(analysisMode));
        }
        if (gitLookbackHours < 1) {
            throw new IllegalStateException("aiops.git.lookback-hours must be positive.");
        }
        if (!normalizedGitAdapter.equals("memory") && !normalizedGitAdapter.equals("cli")) {
            throw new IllegalStateException("Unsupported aiops.git.adapter: " + safeToken(gitAdapter));
        }
        if (!normalizedLoggingAdapter.equals("memory") && !normalizedLoggingAdapter.equals("http")) {
            throw new IllegalStateException("Unsupported aiops.logging.adapter: " + safeToken(loggingAdapter));
        }
        if (normalizedGitAdapter.equals("cli") && !(gitDiffTool instanceof GitCliDiffTool)
                || normalizedGitAdapter.equals("memory") && !(gitDiffTool instanceof InMemoryGitDiffTool)) {
            throw new IllegalStateException(
                    "Configured Git adapter does not match the active Git tool; check aiops.git.adapter."
            );
        }
        if (normalizedLoggingAdapter.equals("http") && !(logQueryTool instanceof LoggingServiceLogQueryTool)
                || normalizedLoggingAdapter.equals("memory") && !(logQueryTool instanceof InMemoryLogQueryTool)) {
            throw new IllegalStateException(
                    "Configured Logging Service adapter does not match the active log tool; "
                            + "check aiops.logging.adapter."
            );
        }
        if ((normalizedMode.equals("llm") || normalizedMode.equals("openai"))
                && (!normalizedGitAdapter.equals("cli") || !normalizedLoggingAdapter.equals("http"))) {
            throw new IllegalStateException(
                    "LLM analysis requires aiops.git.adapter=cli and aiops.logging.adapter=http; "
                            + "memory adapters are not allowed in LLM mode."
            );
        }

        LOGGER.info(
                "Active integration configuration: analysisMode={}, gitAdapter={}, gitHistoryRef={}, "
                        + "gitLookbackHours={}, loggingAdapter={}",
                normalizedMode,
                normalizedGitAdapter,
                safeHistoryRef(gitHistoryRef),
                gitLookbackHours,
                normalizedLoggingAdapter
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String safeToken(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) {
            return "invalid";
        }
        return value;
    }

    private String safeHistoryRef(String value) {
        if (value == null || value.length() > 128 || !value.matches("[A-Za-z0-9._/-]+")) {
            return "invalid";
        }
        return value;
    }
}
