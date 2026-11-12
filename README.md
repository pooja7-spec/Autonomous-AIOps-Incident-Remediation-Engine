# Autonomous AI-Powered Incident Remediation Engine

A Java 17 and Spring Boot Maven multi-module monorepo for an incident analysis
workflow. The existing incident detection, identification, and optional Groq-compatible LLM
tool-calling workflow is preserved in `incident-analysis-service`.

## Modules

| Module | Responsibility | Default port |
| --- | --- | ---: |
| `service-a` | Service A API and Kafka log-event producer | 8081 |
| `service-b` | Service B API and Kafka log-event producer | 8082 |
| `logging-service` | Kafka log-event consumer, MySQL persistence, and log lookup API | 8083 |
| `incident-analysis-service` | Incident API, orchestrator, detection/identifier skills, demo adapters, optional Groq LLM analysis | 8084 |
| `common-contracts` | Shared event models such as `LogEvent`, `IncidentAnalysisEvent`, and `Severity` | n/a |

All three services expose `GET /api/info`. Service A and Service B accept
`POST /api/logs` and publish shared `LogEvent` records to the `log-events`
Kafka topic. Logging Service consumes those events and persists them to MySQL;
`GET /api/logs/{eventId}` retrieves a persisted event. Logging Service also
supports filtered, paginated search at `GET /api/logs/search` and a bounded
recent-log query at `GET /api/logs/recent`.

### Logging Service query APIs

`GET /api/logs/search` accepts optional `service`, `severity`, `startTime`,
`endTime`, `page` (default `0`), and `size` (default `20`) query parameters.
Timestamps must be ISO-8601 UTC instants (for example
`2026-11-11T06:00:00Z`). Filters can be combined. Results are ordered newest
first; `size` must be between 1 and 100.

```powershell
curl.exe "http://localhost:8083/api/logs/search?service=ServiceA&severity=ERROR&page=0&size=20"
curl.exe "http://localhost:8083/api/logs/search?service=ServiceA&startTime=2026-11-11T06:00:00Z&endTime=2026-11-11T07:00:00Z"
```

The response contains the event contracts and pagination metadata:

```json
{
  "content": [
    {
      "eventId": "evt-001",
      "timestamp": "2026-11-11T06:30:00Z",
      "severity": "ERROR",
      "message": "payment processing failed",
      "service": "ServiceA"
    }
  ],
  "pageNumber": 0,
  "pageSize": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

`GET /api/logs/recent` accepts an optional `service` and `minutes` (default
`5`; valid range 1–60). It returns matching events within the current UTC time
window in newest-first order:

```powershell
curl.exe "http://localhost:8083/api/logs/recent?service=ServiceA&minutes=5"
```

Both endpoints return HTTP 400 with a JSON `status`, `error`, and `message`
when a timestamp, severity, or pagination/time-window value is invalid.

## Current implementation boundary

Service A/B log ingestion uses Kafka and Logging Service stores consumed events
in MySQL. The incident-analysis workflow can query recent logs from Logging
Service through its existing `LogQueryTool` boundary when the HTTP adapter is
selected; the in-memory adapter remains the default for tests and demo usage.
Git evidence can be retrieved from the local monorepo using the read-only Git
CLI adapter; the in-memory adapter remains the default for tests and demo
usage. This does not connect to GitHub or an MCP server. LLM analysis is
optional and enabled only when configured.

### Read-only Git evidence

The Git adapter is selected with `AIOPS_GIT_ADAPTER`. Its default is `memory`;
set it to `cli` to read actual commit metadata and diffs. The configured
registry maps `ServiceA` to the monorepo root and limits queries to
`service-a/`. The repository root is supplied using
`AIOPS_REPOSITORY_ROOT`; the history reference is configured with
`AIOPS_GIT_HISTORY_REF` (default `main`). For this development branch, set it
to `feature/autonomous-remediation-foundation`. The configured reference must
exist in the local repository and the allowed service directory must exist.
Git must be installed and available on `PATH` (or selected with
`GIT_EXECUTABLE`).

Run these commands from the repository root in PowerShell:

```powershell
$env:AIOPS_GIT_ADAPTER = "cli"
$env:AIOPS_REPOSITORY_ROOT = (Get-Location).Path
$env:AIOPS_GIT_HISTORY_REF = "feature/autonomous-remediation-foundation"
$env:AIOPS_LOGGING_ADAPTER = "http"
$env:LOGGING_SERVICE_BASE_URL = "http://localhost:8083"
mvn -pl incident-analysis-service -am spring-boot:run
```

In another terminal, trigger an incident. The affected service must be
`ServiceA` to use its configured Git mapping:

```powershell
curl.exe -X POST http://localhost:8084/api/incidents/trigger `
  -H "Content-Type: application/json" `
  -d "{\"service\":\"ServiceA\",\"metricName\":\"http_5xx_rate\",\"observedValue\":7.5,\"threshold\":5.0}"
```

Git mode only reads the configured local history reference and the allowlisted
`service-a/` path. `aiops.git.history-ref` defaults to `main` and can be
overridden with `AIOPS_GIT_HISTORY_REF`. An invalid or missing reference
prevents CLI mode from starting; there is no fallback to `main` or in-memory
Git data. Git mode does not fetch, switch branches, modify files, create
branches or commits, push, or create pull requests.

The proposed recovery action is advisory; the application does not modify code
or deploy changes automatically.

### AI patch proposals

When `AIOPS_ANALYSIS_MODE=llm` and root-cause identification succeeds, the
incident workflow asks the configured LLM provider for a minimal unified-diff
proposal. Patch context is retrieved through the existing read-only
`GitDiffTool` boundary, using the incident service mapping and allowed paths.
For repository-backed context, configure `AIOPS_GIT_ADAPTER=cli`,
`AIOPS_REPOSITORY_ROOT`, and the appropriate `AIOPS_GIT_HISTORY_REF`.
The memory adapter is not considered repository-backed and causes patch
generation to be rejected without an LLM request.

The incident response includes `patchProposal` with `incidentId`,
`sourceCommitId`, `summary`, `targetFiles`, `unifiedDiff`, `validationStatus`,
and `rejectionReason`. `PROPOSED_FOR_REVIEW` means Java accepted the diff's
structure and checked its commit and file paths against the returned Git
evidence. It does not mean the patch was applied, built, tested, or proven
correct. Invalid proposals are returned with `validationStatus: "REJECTED"`
and a safe reason code. Demo mode returns a rejection without making an LLM
request.

The patch generator has no shell, Git-command, or filesystem tools. It never
applies patches, writes source files, creates branches, commits, pull requests,
or deploys changes. Developers must review and independently test any proposal.

## Requirements and build

- JDK 17 or newer
- Maven 3.8 or newer
- Docker Compose (only if you want to start local Kafka and MySQL)

Build and run all module tests from the repository root:

```powershell
mvn clean test
```

Package all applications:

```powershell
mvn package
```

## Run applications

Package the reactor, then start the desired executable JAR:

```powershell
mvn clean package
java -jar service-a\target\service-a-0.1.0-SNAPSHOT.jar
```

To start a different application, run its executable JAR instead:

```powershell
java -jar service-b\target\service-b-0.1.0-SNAPSHOT.jar
java -jar logging-service\target\logging-service-0.1.0-SNAPSHOT.jar
java -jar incident-analysis-service\target\incident-analysis-service-0.1.0-SNAPSHOT.jar
```

Default ports are 8081 through 8084 as listed above. The three scaffolding
services respond at `/api/info`.

The Logging Service requires credentials for the existing MySQL database in
environment variables. The default JDBC URL targets database `aiops`; override
it with `MYSQL_URL` only if needed. Set `MYSQL_USER` and `MYSQL_PASSWORD` to
the credentials configured for your existing MySQL container before starting
Logging Service:

```powershell
$env:MYSQL_USER = "<configured MySQL user>"
$env:MYSQL_PASSWORD = "<configured MySQL password>"
mvn -pl logging-service -am spring-boot:run
```

Service A, Service B, and Logging Service use `localhost:9092` by default.
Override the broker and topic with `KAFKA_BOOTSTRAP_SERVERS` and
`KAFKA_LOG_TOPIC`. Service ports can be overridden with `SERVICE_A_PORT`,
`SERVICE_B_PORT`, `LOGGING_SERVICE_PORT`, and `INCIDENT_ANALYSIS_PORT`.

Publish a log event from either Service A or Service B:

```powershell
curl.exe -X POST http://localhost:8081/api/logs `
  -H "Content-Type: application/json" `
  -d "{\"eventId\":\"evt-001\",\"timestamp\":\"2026-01-02T03:04:05Z\",\"severity\":\"ERROR\",\"message\":\"payment processing failed\",\"service\":\"service-a\"}"
```

The endpoint responds with `202 Accepted` after Kafka acknowledges the event.
After Logging Service consumes it, retrieve it with
`curl.exe http://localhost:8083/api/logs/evt-001`.

### Service A database operation demo

Service A writes demo requests to its own `service_a_demo_records` table in the
existing MySQL database. The idempotent `schema.sql` initialization creates
the table if missing; it does not modify Logging Service's `log_events` table.
Configure the connection with `MYSQL_HOST` (default `localhost`),
`MYSQL_PORT` (default `3306`), `MYSQL_DATABASE` (default `aiops`), and
`MYSQL_USER` (default `aiops`). Set `MYSQL_PASSWORD` in the environment. An
optional `MYSQL_URL` overrides the assembled JDBC URL.

Start Service A from the repository root in PowerShell, using credentials
already configured for your MySQL container:

```powershell
$env:MYSQL_HOST = "localhost"
$env:MYSQL_PORT = "3306"
$env:MYSQL_DATABASE = "aiops"
$env:MYSQL_USER = "<configured MySQL user>"
$env:MYSQL_PASSWORD = "<configured MySQL password>"
mvn -pl service-a -am spring-boot:run
```

Submit an insert to `POST /api/demo/database-operation`:

```powershell
$body = @{ recordId = "record-001"; payload = "sample database operation" } |
  ConvertTo-Json -Compress
curl.exe -i -X POST http://localhost:8081/api/demo/database-operation `
  -H "Content-Type: application/json" -d $body
```

A successful insert returns `201 Created` with the record ID and UTC creation
time. Request fields are validated (`recordId` is required and at most 100
characters; `payload` is required and at most 10,000 characters). To generate
a real database error without changing schema or stopping MySQL, repeat the
same request: the duplicate primary key returns `503 Service Unavailable` and
Service A publishes a sanitized `ERROR` event to the existing `log-events`
Kafka topic. If Kafka publishing also fails, that failure is logged and the
endpoint still returns the database `503`.

With Logging Service running against the same Kafka broker and MySQL instance,
verify the generated event after the failed request:

```powershell
curl.exe "http://localhost:8083/api/logs/recent?service=ServiceA&minutes=5"
```

The event uses severity `ERROR`, service `ServiceA`, a generated event ID, a
UTC timestamp, and the fixed message `Database operation failed`; it does not
include credentials or exception details. Integration tests use an isolated
H2 database and embedded Kafka.

To run Incident Analysis using logs from the existing Logging Service, set the
adapter selection before starting the service:

```powershell
$env:AIOPS_LOGGING_ADAPTER = "http"
$env:LOGGING_SERVICE_BASE_URL = "http://localhost:8083"
mvn -pl incident-analysis-service -am spring-boot:run
```

`logging.service.base-url` defaults to `http://localhost:8083` and can be
overridden with `LOGGING_SERVICE_BASE_URL`. Connect/read timeouts default to
2/3 seconds and can be changed with `LOGGING_SERVICE_CONNECT_TIMEOUT` and
`LOGGING_SERVICE_READ_TIMEOUT`. HTTP failures return `502 Bad Gateway`; there
is no automatic fallback to the in-memory adapter.

Trigger the seeded demo incident:

```powershell
curl.exe -X POST http://localhost:8084/api/incidents/trigger `
  -H "Content-Type: application/json" `
  -d "{\"service\":\"payment-service\",\"metricName\":\"http_5xx_rate\",\"observedValue\":7.5,\"threshold\":5.0}"
```

To verify the HTTP adapter, first publish an error event whose `service` value
matches the incident's `service` value, such as `payment-service`, and confirm
it is persisted by Logging Service. Then trigger an incident with that same
service value. Detection Skill requests `GET
/api/logs/recent?service=<service>&minutes=5`, filters to `ERROR`/`CRITICAL`
logs, and includes the resulting evidence in the detection result. For example:

```powershell
$timestamp = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
$event = @{
  eventId = "payment-error-001"
  timestamp = $timestamp
  severity = "ERROR"
  message = "database connection failed"
  service = "payment-service"
} | ConvertTo-Json -Compress

curl.exe -X POST http://localhost:8081/api/logs `
  -H "Content-Type: application/json" `
  -d $event

curl.exe -X POST http://localhost:8084/api/incidents/trigger `
  -H "Content-Type: application/json" `
  -d "{\"service\":\"payment-service\",\"metricName\":\"http_5xx_rate\",\"observedValue\":7.5,\"threshold\":5.0}"
```

The producer and consumer integration tests use embedded Kafka and an
in-memory H2 database, so `mvn test` does not require the running Docker
services. Runtime ingestion uses the existing Compose services.

## Optional Groq LLM analysis

The default `demo` mode uses deterministic rules and does not call an LLM. Set
`AIOPS_ANALYSIS_MODE=llm` to use the configured provider (default: Groq).
`openai` remains an accepted legacy mode value. Enter the Groq API key without
echoing it into the terminal:

```powershell
$env:GROQ_API_KEY = [System.Net.NetworkCredential]::new(
    "",
    (Read-Host "Groq API key" -AsSecureString)
).Password
$env:AIOPS_ANALYSIS_MODE = "llm"
$env:AIOPS_LLM_PROVIDER = "groq"
$env:AIOPS_GIT_ADAPTER = "cli"
$env:AIOPS_REPOSITORY_ROOT = (Get-Location).Path
$env:AIOPS_GIT_HISTORY_REF = "feature/autonomous-remediation-foundation"
$env:AIOPS_LOGGING_ADAPTER = "http"
$env:LOGGING_SERVICE_BASE_URL = "http://localhost:8083"
mvn -pl incident-analysis-service -am spring-boot:run
```

The default Groq endpoint is `https://api.groq.com/openai/v1` and model is
`openai/gpt-oss-120b`. Override these with `GROQ_BASE_URL` and `GROQ_MODEL`.
Provider-neutral overrides are `AIOPS_LLM_API_KEY`, `AIOPS_LLM_BASE_URL`, and
`AIOPS_LLM_MODEL`. The provider uses the OpenAI-compatible chat-completions
protocol; incident alerts and bounded log/diff evidence are sent to that
provider. Keep API keys in environment variables and never commit them.

## Communication flow

1. Service A and Service B serialize shared `LogEvent` contracts and publish
   them to Kafka.
2. Logging Service consumes those events and persists logs to MySQL.
3. When `AIOPS_LOGGING_ADAPTER=http`, Incident Analysis calls Logging Service's
   recent-log endpoint through `LogQueryTool`; in-memory mode remains the
   default for tests and demos. Detection runs before identification, and
   `GitDiffTool` remains the boundary for code-change evidence. Set
   `AIOPS_GIT_ADAPTER=cli` and `AIOPS_REPOSITORY_ROOT` to use real, read-only
   local Git history, restricted to configured service paths.

Logging Service failures in HTTP mode are reported as gateway errors; the
application does not silently substitute demo logs.

See [the Project Information Document](docs/PROJECT_INFORMATION_DOCUMENT.md)
for the detailed architecture and workflow.
