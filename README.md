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
- `LogQueryTool` and `GitDiffTool`: host-side tool boundaries for log queries
  and Git diffs.
- Seeded `payment-service` demo data to exercise the full workflow immediately.
- Optional OpenAI mode: the model requests bounded log and Git evidence through
  host-executed tools and returns schema-constrained JSON.
- Validation and tests for healthy and degraded incident paths.

The demo adapters are in-memory examples, not production Kafka, Elasticsearch,
GitHub, or MCP integrations. By default, detection and identification use
deterministic rules so the project runs locally without external credentials.
In OpenAI mode, the model performs analysis using the in-memory demo data. The
proposed recovery action is advisory; the application does not modify code or
deploy changes.

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

### Enable OpenAI analysis

The default `demo` mode does not call an LLM. To use OpenAI, set your API key
and enable OpenAI mode in the same PowerShell terminal before starting the app:

```powershell
$env:OPENAI_API_KEY = "YOUR_OPENAI_API_KEY"
$env:AIOPS_ANALYSIS_MODE = "openai"
mvn spring-boot:run
```

The model defaults to `gpt-4o-mini`. To override it, set `OPENAI_MODEL` before
starting the app. Do not put the API key in source code or commit it to Git.
OpenAI API usage is billed separately from a ChatGPT subscription. In this mode,
the incident alert and bounded log/diff results are sent to the configured model
endpoint and may incur charges. Use only data you are authorized to share.
Missing credentials and provider failures are reported explicitly; the app does
not silently fall back to demo rules.

## Workflow

1. A monitoring system posts a metric alert to `/api/incidents/trigger`.
2. The orchestrator invokes `DetectionSkill`. In demo mode it queries the
   `LogQueryTool` for up to 20 `ERROR`/`CRITICAL` logs. In OpenAI mode, the
   detection LLM must call the host's `query_system_logs` tool. The host limits
   the request to the alerted service and configured lookback window
   (`aiops.detection.lookback-minutes`, default five minutes); the model returns
   structured health JSON and the host attaches the actual queried logs.
3. If the detector marks the service unhealthy, the orchestrator invokes
   `IdentifierSkill`.
4. In demo mode, the identifier returns the newest recent change as a
   hypothesis. In OpenAI mode, the identifier LLM must call the host's
   `get_recent_commit_diffs` tool. The host restricts the repository and time
   range, bounds the diff payload, and validates returned commit IDs and files.
   The LLM's root-cause result is a hypothesis; no automatic fix is run.
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
3. Add Kafka ingestion, durable incident persistence, authentication,
   observability, and operational policies before production deployment.
