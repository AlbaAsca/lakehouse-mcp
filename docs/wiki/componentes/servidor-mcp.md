# Servidor MCP

**Estado:** ⬜ planificado. Solo existe la [conexión con DuckDB](duckdb.md#dentro-del-servidor-mcp), probada por sus tests: todavía no hay aplicación, herramientas ni comandos que enseñar. Tarjetas T-020 a T-028.

[Portada](../README.md) · [Glosario](../01-conceptos.md)

## Qué es, en cristiano

El mostrador de atención. El agente de IA no entra al almacén: pide cosas en el mostrador, de una lista cerrada («dime qué tablas hay», «calcula esta métrica»). El mostrador comprueba si la petición es válida y quién la hace. Esa lista la define [MCP](../01-conceptos.md#mcp).

## Para qué estará aquí y qué pasaría sin él

Limita lo que puede hacer un modelo. Sin él, el agente necesitaría acceso directo al SQL y a las credenciales, y un error podría leer o romper cualquier cosa.

## Cómo se conectará

| Dirección | Quién | Puerto |
|---|---|---|
| Entrada | [Agente](agente.md), Claude Desktop | HTTP, por decidir (T-028) |
| Salida | [DuckDB](duckdb.md), embebido: una instancia de lectura y otra de reparación | mismo proceso |
| Salida | [Polaris](polaris.md), a través de DuckDB | 8181 |
| Salida | [Postgres](postgres.md), base `ops` | 5432 |
| Salida | [Kafka](kafka.md), para medir lag | 9092 |

## Cómo funcionará por dentro

Las herramientas previstas, del [brief](../../brief.md#servidor-mcp-tools):

| Herramienta | Qué hará | Tarjeta |
|---|---|---|
| `list_tables`, `describe_table` | Tablas, esquema, particiones y snapshots | T-020 |
| `run_query` | Solo `SELECT`, validado y con límite de filas | T-019, T-021 |
| `time_travel` | Consulta sobre un snapshot anterior | T-021 |
| `pipeline_health` | Lag, frescura y último snapshot | T-022 |
| `data_quality_check` | Nulos, duplicados, rangos y volumen | T-025 |
| `propose_fix`, `apply_fix` | Propone y aplica un arreglo solo con aprobación humana | T-026 |
| `get_metric` | Métricas de la [capa semántica](capa-semantica.md) | T-033 |

Reglas de diseño, en [`docs/architecture.md`](../../architecture.md#decisiones): validar SQL con el parser de DuckDB (7), arreglos de catálogo cerrado cuya aprobación llega por `POST /fix-proposals/{id}/approvals` con una credencial que el modelo nunca recibe (9), roles por clave de API (11), enmascarado de `user_id` por vistas (12), dos instancias de DuckDB (20) y `time_travel` como única vía al pasado, también para analyst (21).

## Cómo estará configurado

Dependencias ya declaradas en el `pom.xml`: `spring-ai-starter-mcp-server-webmvc`, `duckdb_jdbc`, Actuator y Spring Security. Spring AI 2.0.2.

Dos datos de la [documentación de Spring AI](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) que afectan al diseño: ese starter ofrece transportes HTTP, y el endpoint queda **sin autenticar** por defecto, de modo que RBAC no es opcional en cuanto salga de localhost (T-050).

Límite previsto en el [presupuesto de memoria](../../architecture.md#memoria-docker-76-gb): 1 GB.

## Cómo probarlo tú mismo

Todavía no se puede. No hay servicio en `compose.yaml` ni código que ejecutar.

## Qué puede fallar

Riesgos de diseño conocidos, no observados:

- **Un fallo del validador.** La instancia `READ_ONLY` protege el catálogo, pero el validador seguiría siendo la única barrera entre una consulta y los ficheros del contenedor o de S3. [`NOTAS-investigador.md`](../../NOTAS-investigador.md) propone una segunda barrera con los ajustes de seguridad de DuckDB, aún sin tarjeta.
- **DuckDB sobre ficheros del sink.** [T-014](../../spike-duckdb-iceberg.md) lo comprobó con ficheros escritos por DuckDB; sobre los del sink falta probarlo.

## Para saber más

- [Model Context Protocol](https://modelcontextprotocol.io/docs/getting-started/intro)
- [Servidor MCP en Spring AI](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
