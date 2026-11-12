package io.github.aiops.incident.service;

import io.github.aiops.incident.domain.DetectionResult;
import io.github.aiops.incident.domain.IncidentReport;
import io.github.aiops.incident.domain.IncidentStatus;
import io.github.aiops.incident.domain.MetricAlert;
import io.github.aiops.incident.domain.PatchProposal;
import io.github.aiops.incident.domain.RootCauseAnalysis;
import io.github.aiops.incident.llm.LlmIntegrationException;
import io.github.aiops.incident.skills.DetectionSkill;
import io.github.aiops.incident.skills.IdentifierSkill;
import io.github.aiops.incident.skills.PatchGenerationSkill;
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
    private final PatchGenerationSkill patchGenerationSkill;
    private final ConcurrentMap<String, IncidentReport> incidents = new ConcurrentHashMap<>();

    public IncidentOrchestrator(
            DetectionSkill detectionSkill,
            IdentifierSkill identifierSkill,
            PatchGenerationSkill patchGenerationSkill
    ) {
        this.detectionSkill = detectionSkill;
        this.identifierSkill = identifierSkill;
        this.patchGenerationSkill = patchGenerationSkill;
    }

    public IncidentReport trigger(MetricAlert alert) {
        try {
            DetectionResult detection = detectionSkill.analyze(alert);
            String incidentId = UUID.randomUUID().toString();
            if (detection.isHealthy()) {
                return save(incidentId, alert, IncidentStatus.HEALTHY, detection, null, null);
            }

            Optional<RootCauseAnalysis> rootCause = identifierSkill.analyze(alert, detection);
            PatchProposal patchProposal = rootCause
                    .map(analysis -> patchGenerationSkill.generate(incidentId, alert, detection, analysis))
                    .orElse(null);
            IncidentStatus status = rootCause.isPresent()
                    ? IncidentStatus.ROOT_CAUSE_IDENTIFIED
                    : IncidentStatus.NEEDS_INVESTIGATION;
            return save(incidentId, alert, status, detection, rootCause.orElse(null), patchProposal);
        } catch (LlmIntegrationException exception) {
            throw exception.withBoundary("incident orchestration");
        }
    }

    public Optional<IncidentReport> findById(String incidentId) {
        return Optional.ofNullable(incidents.get(incidentId));
    }

    private IncidentReport save(
            String incidentId,
            MetricAlert alert,
            IncidentStatus status,
            DetectionResult detection,
            RootCauseAnalysis rootCause,
            PatchProposal patchProposal
    ) {
        IncidentReport report = new IncidentReport(
                incidentId,
                alert,
                status,
                detection,
                rootCause,
                patchProposal,
                Instant.now()
        );
        incidents.put(report.incidentId(), report);
        return report;
    }
}
