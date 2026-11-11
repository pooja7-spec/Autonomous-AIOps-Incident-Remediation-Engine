package io.github.aiops.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "aiops.git")
public class GitIntegrationProperties {

    private String adapter = "memory";
    private String executable = "git";
    private Path repositoryRoot = Path.of(".");
    private String historyRef = "main";
    private int commandTimeoutSeconds = 10;
    private Map<String, ServiceRepository> services = new LinkedHashMap<>();

    public String getAdapter() {
        return adapter;
    }

    public void setAdapter(String adapter) {
        this.adapter = adapter;
    }

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = executable;
    }

    public Path getRepositoryRoot() {
        return repositoryRoot;
    }

    public void setRepositoryRoot(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
    }

    public String getHistoryRef() {
        return historyRef;
    }

    public void setHistoryRef(String historyRef) {
        this.historyRef = historyRef;
    }

    public int getCommandTimeoutSeconds() {
        return commandTimeoutSeconds;
    }

    public void setCommandTimeoutSeconds(int commandTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }

    public Map<String, ServiceRepository> getServices() {
        return services;
    }

    public void setServices(Map<String, ServiceRepository> services) {
        this.services = services;
    }

    public static class ServiceRepository {

        private List<String> allowedPaths = new ArrayList<>();

        public List<String> getAllowedPaths() {
            return allowedPaths;
        }

        public void setAllowedPaths(List<String> allowedPaths) {
            this.allowedPaths = allowedPaths;
        }
    }
}
