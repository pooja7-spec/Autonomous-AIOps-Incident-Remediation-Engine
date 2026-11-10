package io.github.aiops.incident.domain;

public enum Severity {
    INFO(0),
    WARN(1),
    ERROR(2),
    CRITICAL(3);

    private final int level;

    Severity(int level) {
        this.level = level;
    }

    public boolean isAtLeast(Severity minimum) {
        return level >= minimum.level;
    }
}
