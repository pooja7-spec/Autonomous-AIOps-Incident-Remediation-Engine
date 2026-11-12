package io.github.aiops.incident;

import io.github.aiops.incident.domain.MetricAlert;
import io.github.aiops.incident.domain.DetectionResult;
import io.github.aiops.incident.domain.IncidentStatus;
import io.github.aiops.incident.domain.PatchProposal;
import io.github.aiops.incident.domain.PatchValidationStatus;
import io.github.aiops.incident.domain.RootCauseAnalysis;
import io.github.aiops.incident.llm.LlmIntegrationException;
import io.github.aiops.incident.service.IncidentOrchestrator;
import io.github.aiops.incident.skills.DetectionSkill;
import io.github.aiops.incident.skills.IdentifierSkill;
import io.github.aiops.incident.skills.PatchGenerationSkill;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IncidentOrchestratorTest {

    @Test
    void preservesLlmFailureMetadataAndCauseAtOrchestrationBoundary() {
        DetectionSkill detectionSkill = mock(DetectionSkill.class);
        IdentifierSkill identifierSkill = mock(IdentifierSkill.class);
        PatchGenerationSkill patchGenerationSkill = mock(PatchGenerationSkill.class);
        IncidentOrchestrator orchestrator = new IncidentOrchestrator(
                detectionSkill,
                identifierSkill,
                patchGenerationSkill
        );
        LlmIntegrationException original = new LlmIntegrationException(
                "safe message",
                new IllegalStateException("sensitive underlying detail"),
                "groq",
                "tool-call request",
                "network_error"
        );
        when(detectionSkill.analyze(org.mockito.ArgumentMatchers.any())).thenThrow(original);

        LlmIntegrationException propagated = assertThrows(
                LlmIntegrationException.class,
                () -> orchestrator.trigger(new MetricAlert(
                        "ServiceA",
                        "error_rate",
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        Instant.now()
                ))
        );

        assertEquals("incident orchestration", propagated.getBoundary());
        assertEquals("tool-call request", propagated.getStage());
        assertEquals("network_error", propagated.getFailureCategory());
        assertEquals("groq", propagated.getProvider());
        assertSame(original, propagated.getCause());
    }

    @Test
    void includesGeneratedPatchProposalWithIncidentAndSourceEvidence() {
        DetectionSkill detectionSkill = mock(DetectionSkill.class);
        IdentifierSkill identifierSkill = mock(IdentifierSkill.class);
        PatchGenerationSkill patchGenerationSkill = mock(PatchGenerationSkill.class);
        IncidentOrchestrator orchestrator = new IncidentOrchestrator(
                detectionSkill,
                identifierSkill,
                patchGenerationSkill
        );
        MetricAlert alert = new MetricAlert(
                "ServiceA",
                "error_rate",
                BigDecimal.ONE,
                BigDecimal.ZERO,
                Instant.now()
        );
        DetectionResult detection = new DetectionResult(false, "Database operation failed.", List.of());
        RootCauseAnalysis rootCause = new RootCauseAnalysis(
                "commit-123",
                List.of("service-a/src/Database.java"),
                "The change may be related.",
                "Review the proposed correction.",
                0.95
        );
        when(detectionSkill.analyze(alert)).thenReturn(detection);
        when(identifierSkill.analyze(alert, detection)).thenReturn(Optional.of(rootCause));
        when(patchGenerationSkill.generate(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(alert),
                org.mockito.ArgumentMatchers.eq(detection),
                org.mockito.ArgumentMatchers.eq(rootCause)
        )).thenAnswer(invocation -> new PatchProposal(
                invocation.getArgument(0),
                rootCause.commitId(),
                "Minimal correction.",
                rootCause.changedFiles(),
                "unified diff",
                PatchValidationStatus.PROPOSED_FOR_REVIEW,
                null
        ));

        var report = orchestrator.trigger(alert);

        assertEquals(IncidentStatus.ROOT_CAUSE_IDENTIFIED, report.status());
        assertNotNull(report.patchProposal());
        assertEquals(report.incidentId(), report.patchProposal().incidentId());
        assertEquals(rootCause.commitId(), report.patchProposal().sourceCommitId());
        assertEquals(PatchValidationStatus.PROPOSED_FOR_REVIEW, report.patchProposal().validationStatus());
    }
}
