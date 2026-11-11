package io.github.aiops.incident.api;

import io.github.aiops.incident.domain.IncidentReport;
import io.github.aiops.incident.domain.MetricAlert;
import io.github.aiops.incident.service.IncidentOrchestrator;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentOrchestrator orchestrator;

    public IncidentController(IncidentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/trigger")
    @ResponseStatus(HttpStatus.CREATED)
    public IncidentReport trigger(@Valid @RequestBody TriggerIncidentRequest request) {
        MetricAlert alert = new MetricAlert(
                request.service(),
                request.metricName(),
                request.observedValue(),
                request.threshold(),
                request.timestamp() == null ? Instant.now() : request.timestamp()
        );
        return orchestrator.trigger(alert);
    }

    @GetMapping("/{incidentId}")
    public IncidentReport getIncident(@PathVariable String incidentId) {
        return orchestrator.findById(incidentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found"));
    }
}
