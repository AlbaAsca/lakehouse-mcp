# lakehouse-mcp

A local modern lakehouse that an AI agent queries, monitors and repairs through MCP.

**Status: phase 0 (infrastructure). Agent, evals and benchmark are not built yet.**

Stack: Kafka (KRaft) + Schema Registry, Apache Iceberg with Apache Polaris on SeaweedFS, DuckDB 2.0, Java 25, Spring Boot 4, Spring AI 2.0.

## Quickstart
Only Docker is required; JDK, Maven, make and the Kaggle CLI run in the `tools` image. On Windows use `mk.cmd`.

```bash
cp .env.example .env
./mk up
./mk smoke
```

## Why SeaweedFS instead of MinIO
The MinIO community repository is archived and no longer maintained. SeaweedFS is Apache-2.0 and exposes the S3 API.

## Data
[eCommerce behavior data from multi category store](https://www.kaggle.com/datasets/mkechinov/ecommerce-behavior-data-from-multi-category-store),
published on Kaggle by Michael Kechinov with data from the REES46 Marketing Platform. Licence on Kaggle: "Data files © Original Authors",
so the data is downloaded with your own Kaggle credentials and never committed; CI uses a synthetic sample with the same schema.

## Built with agent-board
Planned and executed with [agent-board](https://github.com/AlbaAsca/agent-board), a kanban panel for a team of Claude Code agents.
