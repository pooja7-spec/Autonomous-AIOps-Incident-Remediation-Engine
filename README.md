# Autonomous AI-Powered Incident Remediation Engine

A Java 17 and Spring Boot Maven multi-module monorepo for an incident analysis
workflow. The existing incident detection, identification, and optional OpenAI
tool-calling workflow is preserved in `incident-analysis-service`.

## Modules

| Module | Responsibility | Default port |
| --- | --- | ---: |
| `service-a` | Service A API and Kafka log-event producer | 8081 |
| `service-b` | Service B API and Kafka log-event producer | 8082 |
| `logging-service` | Kafka log-event consumer, MySQL persistence, and log lookup API | 8083 |
| `incident-analysis-service` | Incident API, orchestrator, detection/identifier skills, demo adapters, optional OpenAI analysis | 8084 |
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
Git evidence remains in-memory, and the project is not connected to GitHub or
an MCP server. OpenAI analysis is optional and enabled only when configured.

The proposed recovery action is advisory; the application does not modify code
or deploy changes automatically.

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

## Optional OpenAI analysis

The default `demo` mode uses deterministic rules and does not call an LLM. To
enable OpenAI mode, set the API key in the same PowerShell terminal used to
start the service:

```powershell
$env:OPENAI_API_KEY = "YOUR_OPENAI_API_KEY"
$env:AIOPS_ANALYSIS_MODE = "openai"
mvn -pl incident-analysis-service -am spring-boot:run
```

The model defaults to `gpt-4o-mini`; override it with `OPENAI_MODEL`. API use is
billed separately from a ChatGPT subscription. In OpenAI mode, the incident
alert and bounded log/diff evidence are sent to the configured endpoint. Do not
put API keys in source code or commit them.

## Communication flow

1. Service A and Service B serialize shared `LogEvent` contracts and publish
   them to Kafka.
2. Logging Service consumes those events and persists logs to MySQL.
3. When `AIOPS_LOGGING_ADAPTER=http`, Incident Analysis calls Logging Service's
   recent-log endpoint through `LogQueryTool`; in-memory mode remains the
   default for tests and demos. Detection runs before identification, and
   `GitDiffTool` remains the boundary for code-change evidence.

Logging Service failures in HTTP mode are reported as gateway errors; the
application does not silently substitute demo logs.

See [the Project Information Document](docs/PROJECT_INFORMATION_DOCUMENT.md)
for the detailed architecture and workflow.
