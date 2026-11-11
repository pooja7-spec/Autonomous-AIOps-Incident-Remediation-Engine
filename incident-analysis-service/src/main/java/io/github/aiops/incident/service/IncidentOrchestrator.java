package io.github.aiops.incident.service;

import io.github.aiops.incident.domain.DetectionResult;
import io.github.aiops.incident.domain.IncidentReport;
import io.github.aiops.incident.domain.IncidentStatus;
import io.github.aiops.incident.domain.MetricAlert;
import io.github.aiops.incident.domain.RootCauseAnalysis;
import io.github.aiops.incident.skills.DetectionSkill;
import io.github.aiops.incident.skills.IdentifierSkill;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class IncidentOrchestrator {

    private final DetectionSkill detectionSkill;
    private final IdentifierSkill identifierSkill;
    private final ConcurrentMap<String, IncidentReport> incidents = new ConcurrentHashMap<>();

    public IncidentOrchestrator(DetectionSkill detectionSkill, IdentifierSkill identifierSkill) {
        this.detectionSkill = detectionSkill;
        this.identifierSkill = identifierSkill;
    }

    public IncidentReport trigger(MetricAlert alert) {
        DetectionResult detection = detectionSkill.analyze(alert);
        if (detection.isHealthy()) {
            return save(alert, IncidentStatus.HEALTHY, detection, null);
        }

        Optional<RootCauseAnalysis> rootCause = identifierSkill.analyze(alert, detection);
        IncidentStatus status = rootCause.isPresent()
                ? IncidentStatus.ROOT_CAUSE_IDENTIFIED
                : IncidentStatus.NEEDS_INVESTIGATION;
        return save(alert, status, detection, rootCause.orElse(null));
    }

    public Optional<IncidentReport> findById(String incidentId) {
        return Optional.ofNullable(incidents.get(incidentId));
    }

    private IncidentReport save(
            MetricAlert alert,
            IncidentStatus status,
            DetectionResult detection,
            RootCauseAnalysis rootCause
    ) {
        IncidentReport report = new IncidentReport(
                UUID.randomUUID().toString(),
                alert,
                status,
                detection,
                rootCause,
                Instant.now()
        );
        incidents.put(report.incidentId(), report);
        return report;
    }
}
