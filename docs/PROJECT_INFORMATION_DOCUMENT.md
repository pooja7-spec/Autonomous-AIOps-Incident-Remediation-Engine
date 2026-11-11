# Project Information Document: Incident Remediation Engine

## 1. Purpose

The Incident Remediation Engine demonstrates an incident-response workflow for
distributed services. It receives a metric alert, gathers a small amount of
relevant log and source-control evidence through tool boundaries, detects
degradation, and returns a root-cause hypothesis with a proposed recovery action.
The output is advisory; the system does not change code or deploy a fix.

The current Java project is a local MVP. Kafka-backed log ingestion and MySQL
persistence are implemented. Incident Analysis can query recent logs from
Logging Service over HTTP through `LogQueryTool`; the in-memory log adapter
remains selectable for tests and demo usage. Git evidence remains in-memory.
The application supports deterministic `demo` mode and an optional OpenAI-backed
`openai` mode. OpenAI mode uses host-executed function tools; it is not itself
an MCP server and does not add a GitHub integration.

## 1.1 Maven monorepo structure and implementation status

The repository is a Java 17 Maven reactor with five modules:

| Module | Responsibility | Default port |
| --- | --- | ---: |
| `service-a` | Service A API and Kafka `LogEvent` producer | 8081 |
| `service-b` | Service B API and Kafka `LogEvent` producer | 8082 |
| `logging-service` | Kafka `LogEvent` consumer, MySQL persistence, and event lookup API | 8083 |
| `incident-analysis-service` | Existing incident API, orchestrator, Detection/Identifier skills, adapters, and optional OpenAI client | 8084 |
| `common-contracts` | Shared `LogEvent`, `IncidentAnalysisEvent`, and `Severity` types | n/a |

`common-contracts` is a plain Java library. The other four modules are
independently runnable Spring Boot applications with their own configuration.
All three ingestion services expose `GET /api/info`; Service A and Service B
also accept `POST /api/logs`, while Logging Service exposes
`GET /api/logs/{eventId}`, `GET /api/logs/search`, and
`GET /api/logs/recent`.

Service A/B publish shared `LogEvent` JSON messages to the configurable Kafka
topic (default `log-events`). Logging Service consumes that topic and persists
events through Spring Data JPA to the existing MySQL `aiops` database. Kafka
bootstrap servers and the MySQL URL are configurable; MySQL username and
password are read from `MYSQL_USER` and `MYSQL_PASSWORD`. The local Docker
Compose configuration supplies the Kafka KRaft broker and MySQL 8.4 services.

`GET /api/logs/search` supports optional `service`, `severity`, ISO-8601 UTC
`startTime`/`endTime`, and `page`/`size` parameters (defaults: `0`/`20`, maximum
size: 100). It returns `content`, `pageNumber`, `pageSize`, `totalElements`,
and `totalPages`, ordered by descending event timestamp. `GET /api/logs/recent`
accepts optional `service` and `minutes` (default 5, maximum 60), and returns
events between the current UTC time and `now - minutes`, newest first. Invalid
timestamps and out-of-range query values return HTTP 400 with a structured
error message. Both queries run in the database; the search endpoint uses
Spring Data pagination. The existing event-by-ID endpoint remains available.

Detection Skill queries through `LogQueryTool`. The default `memory` adapter
preserves in-memory demo/test behavior; setting `AIOPS_LOGGING_ADAPTER=http`
selects an HTTP adapter that calls
`/api/logs/recent?service=<affected-service>&minutes=<lookback>` on Logging
Service. Configure its base URL with `logging.service.base-url` (default
`http://localhost:8083`), or `LOGGING_SERVICE_BASE_URL`. The HTTP adapter maps
shared `LogEvent` records into the existing internal `LogEntry` model, then
applies the requested minimum severity and existing 20-log bound. Connect and
read timeouts default to two and three seconds. Failures are logged and surfaced
as `502 Bad Gateway`; there is no silent fallback to in-memory logs.

The orchestrator continues to keep detection and identification as separate
stages, and proposed fixes remain advisory.

## 2. Core system design and workflow

### Intended production lifecycle

1. **Ingestion and metric spike:** Services publish standardized log events
   containing a timestamp, severity, message, and service name. A monitoring
   component evaluates error counts or rates over a time window.
2. **Orchestrator trigger:** A breached threshold, such as a 5xx rate over 5%
   for Service A, causes the orchestrator to start the Detection stage.
3. **Detection stage:** The stage queries a bounded time window of logs through
   a log-query tool. It identifies repeating errors, connection failures, or
   cascading failures and returns a structured health result.
4. **Identifier stage:** If the incident is unhealthy, the orchestrator passes
   the structured detection report to the Identifier stage. It asks a
   code-history tool for recent changes in the affected service repository.
5. **Incident report:** The service returns the supporting log evidence, a
   recent commit identifier when one is available, a root-cause hypothesis,
   confidence, and a manual recovery recommendation. If evidence is insufficient,
   it returns `NEEDS_INVESTIGATION`.

### Current MVP flow

The Java application accepts a metric alert at `POST /api/incidents/trigger`.
`IncidentOrchestrator` invokes `DetectionSkill`, which queries up to 20 recent
`ERROR` or `CRITICAL` logs through `LogQueryTool`. The default memory adapter
uses demo entries; HTTP mode retrieves the affected service's configured
lookback from Logging Service. In OpenAI mode, the model calls the host-provided
`query_system_logs` tool, the host queries `LogQueryTool`, and
the model returns schema-constrained health JSON. If the result is unhealthy,
`IdentifierSkill` similarly provides `get_recent_commit_diffs`; the host queries
`GitDiffTool` and the model returns structured analysis. The host verifies that
the commit and files came from the tool results. A recent change remains a
hypothesis, not a proven cause. Reports are stored in process memory and are
lost when the service restarts.

## 3. Tool contracts

These are MCP-style contracts for possible future MCP integration. OpenAI mode
uses equivalent OpenAI function-calling definitions over the existing Java
interfaces. The project does not implement the MCP protocol. The HTTP
`LogQueryTool` adapter connects to the Logging Service; Git queries still use
the in-memory adapter.

### Log query tool

```json
{
  "name": "query_system_logs",
  "description": "Queries a log store for a service, lookback period, and minimum severity; returns a bounded summary.",
  "inputSchema": {
    "type": "object",
    "additionalProperties": false,
    "properties": {
      "service": { "type": "string" },
      "lookback_minutes": { "type": "integer", "minimum": 1, "maximum": 60 },
      "min_severity": { "type": "string", "enum": ["ERROR", "CRITICAL"] }
    },
    "required": ["service", "lookback_minutes", "min_severity"]
  }
}
```

`LogQueryTool.queryLogs(service, lookbackMinutes, minimumSeverity)` is
implemented by `InMemoryLogQueryTool` in memory mode and
`LoggingServiceLogQueryTool` in HTTP mode. The HTTP adapter calls the Logging
Service recent endpoint, maps results to `LogEntry`, filters minimum severity,
sorts newest first, and returns at most 20 entries. In OpenAI mode, the host
further truncates each message to 2,000 characters before returning the query
result to the model.

### Recent Git diff tool

```json
{
  "name": "get_recent_commit_diffs",
  "description": "Returns bounded recent commit metadata and diffs for an authorized service repository.",
  "inputSchema": {
    "type": "object",
    "additionalProperties": false,
    "properties": {
      "service_repository": { "type": "string" },
      "since_timestamp": { "type": "string", "format": "date-time" }
    },
    "required": ["service_repository", "since_timestamp"]
  }
}
```

In the MVP, `GitDiffTool.getRecentCommitDiffs(serviceRepository,
sinceTimestamp)` is implemented by `InMemoryGitDiffTool`. A production adapter
must authorize repositories, bound the number and size of diffs, and avoid
exposing unrelated source code or secrets.
OpenAI mode limits each response to five changes, 50 paths and 4,000 diff
characters per change; commit messages are capped at 500 characters.

## 4. Skill prompting and structured outputs

Demo mode uses deterministic Java logic. OpenAI mode uses the following prompt
behavior with host-executed tools and schema-constrained JSON. Tool execution
remains in the host and only relevant, bounded results are returned to the model.

### Detection stage prompt outline

> You are a site reliability engineering agent specialized in incident triage.
> Use `query_system_logs` to inspect the target service and timeframe. Identify
> repeating error clusters, resource connection drops, and cascading failures.
> Treat the metric alert as evidence. Return only JSON matching the configured
> detection schema; do not include conversational text.

The model response shape is:

```json
{
  "isHealthy": false,
  "summary": "Database connection errors coincide with the elevated 5xx rate."
}
```

### Identifier stage prompt outline

> You are a root-cause analysis agent. You receive a verified incident report
> and may use `get_recent_commit_diffs` for the affected, authorized service
> repository. Compare the incident time with recent changes. Inspect relevant
> network, connection-string, pool-sizing, and client-driver changes. Return a
> hypothesis and concrete recovery steps as JSON. Distinguish evidence from
> speculation; do not execute a fix.

Expected output shape:

```json
{
  "dependentServiceFailure": true,
  "commitId": "abc123",
  "changedFiles": ["src/main/resources/application.yml"],
  "hypothesis": "The pool-size change may have contributed to connection exhaustion.",
  "fixNeeded": "Review the change and validate a tested rollback or corrected pool size.",
  "confidence": 0.65
}
```

The model responses map to `DetectionResult` and `RootCauseAnalysis`. The host
rejects unknown commit IDs and files; demo commit IDs and confidence are sample
values, not findings about a real deployment.

## 5. Java project components

| Component | Responsibility |
|---|---|
| `IncidentController` | Accepts alerts and retrieves reports over HTTP |
| `LogController` | Adds sample log events to the in-memory adapter |
| `IncidentOrchestrator` | Runs detection, conditionally runs identification, stores reports |
| `DetectionSkill` | Runs deterministic demo checks or asks the LLM to query logs and return structured health JSON |
| `IdentifierSkill` | Returns a demo hypothesis or asks the LLM to inspect diffs and return structured analysis |
| `StructuredLlmClient` | OpenAI function-calling loop with JSON-schema response handling |
| `LogQueryTool` | Host-side boundary for log queries |
| `GitDiffTool` | Host-side boundary for Git diffs |
| `InMemoryLogQueryTool` | Local demo implementation with a 20-entry result limit |
| `LoggingServiceLogQueryTool` | HTTP implementation for querying Logging Service recent logs |
| `InMemoryGitDiffTool` | Local demo implementation for commit metadata and diffs |

## 6. API summary

### Trigger an incident

`POST /api/incidents/trigger`

```json
{
  "service": "payment-service",
  "metricName": "http_5xx_rate",
  "observedValue": 7.5,
  "threshold": 5.0
}
```

`timestamp` is optional and defaults to the current time. Values must be
non-negative. A breach is `observedValue > threshold`.

### Ingest a log event

`POST /api/logs`

```json
{
  "severity": "ERROR",
  "message": "Database connection pool exhausted",
  "service": "payment-service"
}
```

`timestamp` is optional and defaults to the current time.

### Retrieve a report

`GET /api/incidents/{incidentId}` returns the report created by the trigger
endpoint, while the application process remains running.

## 7. Status and safety behavior

- `HEALTHY`: no threshold breach and no matching error logs.
- `ROOT_CAUSE_IDENTIFIED`: the Identifier stage judged a returned recent change
  to be a plausible cause, and its commit/files were verified against tool
  results. This remains a hypothesis.
- `NEEDS_INVESTIGATION`: degradation was detected, but the Identifier stage
  found no plausible cause or no recent evidence.
- No remediation is automatically executed.
- Production integrations must enforce service/repository allowlists, access
  controls, query limits, secret redaction, and strict output validation.

## 8. Running and testing

Requirements: JDK 17+ and Maven 3.8+.

```powershell
mvn spring-boot:run
mvn test
```

The `payment-service` demo data is seeded at startup to make the full workflow
easy to try without external services.

For OpenAI mode, set `OPENAI_API_KEY` and `AIOPS_ANALYSIS_MODE=openai` in the
terminal before starting the app. The model defaults to `gpt-4o-mini`; override
it with `OPENAI_MODEL`. OpenAI mode sends the alert and bounded tool results to
the configured endpoint and may incur API charges. Demo mode makes no model
calls.

## 9. Next implementation steps

1. Add authentication/access control and operational monitoring for the
   Logging Service HTTP adapter.
2. Replace the in-memory Git adapter with a provider integration that returns
   only authorized, bounded diffs.
3. Add Kafka ingestion for metric alerts and harden the incident workflow for
   production operation.
4. Add durable incident persistence, authentication, observability, retries,
   and deployment configuration.
5. Add approval controls and separately designed safeguards before considering
   any automated remediation.
