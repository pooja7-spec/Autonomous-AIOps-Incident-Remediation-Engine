# Copilot instructions

This repository is a Java 17+ Spring Boot service for an AI-assisted incident
remediation workflow. Treat [the Project Information Document](../docs/PROJECT_INFORMATION_DOCUMENT.md)
as the product and architecture reference. Keep the detection and identification
stages separate, and use the `LogQueryTool` and `GitDiffTool` interfaces as
boundaries between the orchestration flow and external systems.

The current adapters use in-memory demo data. Do not describe them as live
Kafka, log-store, GitHub, MCP, or LLM integrations. Preserve the API's
validated request/response contracts, keep proposed fixes advisory (never
execute remediation automatically), and add or update tests for behavior
changes. Use Maven to build and test.
