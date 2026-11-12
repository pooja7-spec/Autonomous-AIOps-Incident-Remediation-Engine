package io.github.aiops.incident;

import io.github.aiops.incident.domain.DetectionResult;
import io.github.aiops.incident.domain.IncidentReport;
import io.github.aiops.incident.domain.IncidentStatus;
import io.github.aiops.incident.domain.MetricAlert;
import io.github.aiops.incident.domain.PatchProposal;
import io.github.aiops.incident.domain.PatchValidationStatus;
import io.github.aiops.incident.domain.RootCauseAnalysis;
import io.github.aiops.incident.api.IncidentController;
import io.github.aiops.incident.service.IncidentOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IncidentController.class)
class IncidentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IncidentOrchestrator orchestrator;

    @Test
    void triggerResponseIncludesPatchProposalForDeveloperReview() throws Exception {
        Instant now = Instant.now();
        MetricAlert alert = new MetricAlert(
                "ServiceA",
                "http_5xx_rate",
                new BigDecimal("7.5"),
                new BigDecimal("5.0"),
                now
        );
        RootCauseAnalysis rootCause = new RootCauseAnalysis(
                "commit-123",
                List.of("service-a/src/Database.java"),
                "The change may have caused the database error.",
                "Review the proposed correction.",
                0.95
        );
        PatchProposal proposal = new PatchProposal(
                "incident-123",
                "commit-123",
                "Correct the affected query.",
                rootCause.changedFiles(),
                "--- a/service-a/src/Database.java\n+++ b/service-a/src/Database.java\n"
                        + "@@ -1,1 +1,1 @@\n-old\n+new\n",
                PatchValidationStatus.PROPOSED_FOR_REVIEW,
                null
        );
        when(orchestrator.trigger(any(MetricAlert.class))).thenReturn(new IncidentReport(
                "incident-123",
                alert,
                IncidentStatus.ROOT_CAUSE_IDENTIFIED,
                new DetectionResult(false, "Database operation failed.", List.of()),
                rootCause,
                proposal,
                now
        ));

        mockMvc.perform(post("/api/incidents/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "service": "ServiceA",
                                  "metricName": "http_5xx_rate",
                                  "observedValue": 7.5,
                                  "threshold": 5.0
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.patchProposal.incidentId").value("incident-123"))
                .andExpect(jsonPath("$.patchProposal.sourceCommitId").value("commit-123"))
                .andExpect(jsonPath("$.patchProposal.validationStatus").value("PROPOSED_FOR_REVIEW"))
                .andExpect(jsonPath("$.patchProposal.rejectionReason").doesNotExist());
    }
}
