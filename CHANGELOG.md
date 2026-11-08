# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions: [Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-11-08

The first end-to-end demo: an AI agent answers a business question by querying Iceberg tables through MCP.
It runs on a synthetic sample of 50,000 events; the real Kaggle month is not part of this version.

### Added
- **Stack in Docker Compose:** Kafka (KRaft), Schema Registry, Kafka Connect with the Iceberg sink, Apache Polaris
  with its catalog stored in Postgres, SeaweedFS as S3 storage, and the MCP server. One command: `./mk up`.
- **Tools image:** JDK 25, Maven, make, DuckDB and the Kaggle CLI run in Docker; nothing is installed on the host.
- **Producer:** reads the dataset CSV tolerantly, samples whole sessions, replays events in time order with a
  configurable speed-up and optional "now" timestamps, and sends Avro to Kafka (`./mk seed`, `./mk seed-sample`).
- **Ingestion:** the Iceberg sink writes the events to `ecommerce.events`, tested from CSV to table.
- **Query layer:** DuckDB 2.0 over Iceberg via Polaris, with separate read-only and repair connections and an
  SQL validator that only lets known reads through.
- **MCP server (Spring AI 2.0):** ten tools: `list_tables`, `describe_table`, `run_query`, `time_travel`, `get_metric`,
  `list_metrics`, `pipeline_health`, `data_quality_check`, `propose_fix` and `apply_fix` (admin approval required),
  behind a read-only SQL validator, two roles (analyst, admin) with API keys, and per-tool metrics.
- **Semantic layer:** business metrics defined once in YAML (`top_brands`, average ticket, conversion funnel, cart
  abandonment), served by `get_metric`.
- **Chaos:** `./mk chaos` injects duplicate events and the agent diagnoses and proposes a repair. The other three failures are planned.
- **Agent (Spring AI 2.0):** answers questions with `./mk ask`, with caps on iterations, tokens, cost and time.
  Gemini by default; Claude and any OpenAI-compatible service (`AGENT_PROVIDER=openai` with base URL, key and model)
  by configuration. The OpenAI-compatible provider is tested against a fake server only, not against a real service.
- **Demo:** a GIF recorded with an MCP client (Claude Code) calling the real server, without a model key
  (`scripts/demo/record_mcp_demo.py`). The same demo with the Spring AI agent is rehearsed but not yet recorded.
- **Maintenance:** snapshot expiry as a one-off process (`./mk expire`).
- **Quality:** containers-based integration tests with Testcontainers, CI on every push, a synthetic sample with
  the dataset's schema, and an independently computed reference answer for the demo question.
- **Documentation:** a wiki for beginners and experts, published to the GitHub Wiki on every push to `main`.

### Changed
- SeaweedFS instead of MinIO, whose community repository is archived.
- Gemini instead of Anthropic as the default model provider, because of its free tier.

### Removed
- The DuckDB versus Trino benchmark, to save memory and scope.

### Known limitations
- The Gemini free tier allows about 20 requests a day per model, and each agent question uses several.
- Data quality checks, pipeline health, the semantic layer, self-healing, data contracts, evals and RBAC are planned
  for later versions.
