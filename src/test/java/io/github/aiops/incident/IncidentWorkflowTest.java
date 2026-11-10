package io.github.aiops.incident;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class IncidentWorkflowTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void breachedMetricRunsDetectionAndIdentifiesRecentChange() throws Exception {
        mockMvc.perform(post("/api/incidents/trigger")
                        .contentType("application/json")
                        .content("""
                                {
                                  "service": "payment-service",
                                  "metricName": "http_5xx_rate",
                                  "observedValue": 7.5,
                                  "threshold": 5.0
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("ROOT_CAUSE_IDENTIFIED")))
                .andExpect(jsonPath("$.detection.isHealthy", is(false)))
                .andExpect(jsonPath("$.rootCauseAnalysis.commitId", is("7f3a2c1")));
    }

    @Test
    void healthyMetricWithoutErrorLogsDoesNotRunIdentification() throws Exception {
        mockMvc.perform(post("/api/incidents/trigger")
                        .contentType("application/json")
                        .content("""
                                {
                                  "service": "catalog-service",
                                  "metricName": "http_5xx_rate",
                                  "observedValue": 1.0,
                                  "threshold": 5.0
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("HEALTHY")))
                .andExpect(jsonPath("$.detection.isHealthy", is(true)))
                .andExpect(jsonPath("$.rootCauseAnalysis").doesNotExist());
    }

    @Test
    void incidentCanBeRetrievedAfterCreation() throws Exception {
        String response = mockMvc.perform(post("/api/incidents/trigger")
                        .contentType("application/json")
                        .content("""
                                {
                                  "service": "payment-service",
                                  "metricName": "http_5xx_rate",
                                  "observedValue": 7.5,
                                  "threshold": 5.0
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String incidentId = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .build()
                .readTree(response)
                .get("incidentId")
                .asText();

        mockMvc.perform(get("/api/incidents/{incidentId}", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incidentId", is(incidentId)));
    }
}
