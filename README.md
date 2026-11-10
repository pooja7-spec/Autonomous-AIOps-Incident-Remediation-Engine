# Incident Remediation Engine

A Java/Spring Boot MVP based on the Project Information Document. It demonstrates
an incident lifecycle from a metric alert through log-based detection and
recent-change analysis.

## What is included

- `POST /api/incidents/trigger`: runs the Detection stage, then runs the
  Identifier stage only when the incident is unhealthy.
- `GET /api/incidents/{incidentId}`: retrieves an incident report held in
  application memory.
- `POST /api/logs`: adds a log entry to the in-memory log adapter.
- `LogQueryTool` and `GitDiffTool`: narrow interfaces that can be replaced with
  real log-store and Git-provider integrations.
- Seeded `payment-service` demo data to exercise the full workflow immediately.
- Validation and tests for healthy and degraded incident paths.

The demo adapters are in-memory examples, not production Kafka, Elasticsearch,
GitHub, MCP, or LLM integrations. The detection and identification stages use
deterministic rules so the project runs locally without external credentials.
The proposed recovery action is advisory; the application does not modify code
or deploy changes.

See [the Project Information Document](docs/PROJECT_INFORMATION_DOCUMENT.md)
for the detailed workflow, proposed MCP contracts, prompt outlines, API
contracts, and production integration roadmap.

## Requirements

- JDK 17 or newer
- Maven 3.8 or newer

## Run and test

From the project directory:

```powershell
mvn spring-boot:run
```

In another terminal, trigger the seeded incident:

```powershell
curl.exe -X POST http://localhost:8080/api/incidents/trigger `
  -H "Content-Type: application/json" `
  -d "{\"service\":\"payment-service\",\"metricName\":\"http_5xx_rate\",\"observedValue\":7.5,\"threshold\":5.0}"
```

The response includes the queried log evidence, a root-cause hypothesis for demo
commit `7f3a2c1`, and a manual recovery recommendation. The incident ID can be
used with `GET /api/incidents/{incidentId}`.

Run the test suite:

```powershell
mvn test
```

## Workflow

1. A monitoring system posts a metric alert to `/api/incidents/trigger`.
2. The orchestrator invokes `DetectionSkill`, which queries the `LogQueryTool`
   for up to 20 `ERROR`/`CRITICAL` logs in the configured lookback window
   (`aiops.detection.lookback-minutes`, default five minutes).
3. If the threshold is breached or relevant error logs exist, the orchestrator
   invokes `IdentifierSkill`.
4. The identifier queries recent changes through `GitDiffTool` for the
   preceding hour and returns the newest change as a hypothesis. No matching
   change results in `NEEDS_INVESTIGATION`.
5. The orchestrator returns and temporarily stores the structured report.

## Example request

```json
{
  "service": "payment-service",
  "metricName": "http_5xx_rate",
  "observedValue": 7.5,
  "threshold": 5.0
}
```

The `timestamp` property is optional; when omitted, the current time is used.
The alert values must be non-negative, and `observedValue` must be greater than
`threshold` to count as a threshold breach.

## Production integration roadmap

1. Implement `LogQueryTool` against the selected log backend and apply access
   control, query limits, and redaction before returning tool results.
2. Implement `GitDiffTool` against an authorized Git provider; scope repositories
   to an allowlist and return bounded diffs.
3. Add a model/reasoning adapter with strict JSON-schema validation if LLM-based
   analysis is required. Keep tool execution in the host and pass summarized
   results back to the model.
4. Add Kafka ingestion, durable incident storage, authentication, observability,
   and operational policies before production deployment.
