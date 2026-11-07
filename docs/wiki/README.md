# Wiki de lakehouse-mcp

lakehouse-mcp es un proyecto de portfolio: un pequeño almacén de datos moderno (un [lakehouse](01-conceptos.md#lakehouse)) que corre entero en tu ordenador y que, cuando esté terminado, un [agente de IA](01-conceptos.md#agente) podrá consultar, vigilar y reparar a través de [MCP](01-conceptos.md#mcp).

Piensa en una tienda online. Cada clic es una nota. Las notas llegan por una cinta transportadora (Kafka), un operario las archiva en cajas ordenadas (Iceberg sobre SeaweedFS), un fichero central dice qué hay en cada caja (Polaris) y alguien puede hacer preguntas sobre todo ello sin mover las cajas (DuckDB). Al final de la cadena habrá un ayudante de IA que hace esas preguntas y avisa cuando algo se rompe.

Cada página empieza en lenguaje llano y avanza hasta el detalle técnico. Puedes parar donde quieras.

> **Fecha de esta foto:** 2 de noviembre de 2026, sobre la rama `develop` en el commit `129dee6`. El proyecto se está construyendo mientras se escribe esto: si algo no coincide, mandan el código y [el panel](componentes/agent-board.md).

## Dos rutas de lectura

**Empiezo de cero**

1. [Conceptos desde cero](01-conceptos.md): qué es un evento, un topic, un lakehouse, un snapshot.
2. [Arquitectura](02-arquitectura.md), hasta «El viaje de un evento».
3. [Pruébalo tú](03-probarlo.md): levantas el stack y creas una tabla.
4. La página de la pieza que te llame la atención. Lee hasta «Para qué está aquí».

**Ya sé de esto**

1. [Tabla de conexiones y orden de arranque](02-arquitectura.md#tabla-de-conexiones).
2. [Decisiones, alternativas y coste](05-decisiones.md).
3. En cada [componente](#componentes), las secciones «Cómo está configurado aquí» y «Qué puede fallar».
4. [Operación y diagnóstico](04-operacion.md).

## Qué funciona hoy

| | Pieza | Estado real |
|---|---|---|
| ✅ | Stack en Docker Compose con healthchecks | Levantado y comprobado |
| ✅ | Tabla Iceberg creada, escrita y leída desde DuckDB, pasando por Polaris y SeaweedFS | `./mk smoke`, y [el tutorial](03-probarlo.md) |
| ✅ | El catálogo sobrevive a un reinicio de Polaris (T-002, T-005) | Guardado en Postgres. El reinicio no se repitió para esta wiki: consta en la tarjeta T-005 |
| ✅ | Imagen `tools` con JDK, Maven, make, DuckDB y Kaggle | Versiones comprobadas |
| ✅ | Muestra sintética de 50.000 filas para tests | `testdata/synthetic-events.csv.gz` |
| 🚧 | Productor Java (T-008, T-011, T-013) | Lectura, muestreo, orden y envío a Kafka con tests en verde sobre la muestra sintética. `./mk seed-sample` carga la muestra en el stack (T-091). Falta probar `./mk seed` con el CSV real. [Detalle](componentes/productor.md) |
| ✅ | Conector hacia Iceberg (T-012, T-072) | La tarea sale `RUNNING` y un evento llega a la tabla, comprobado en contenedores; `./mk up` sobre un stack ya existente termina sin error. **Sin probar:** `./mk up` desde cero, un evento en el stack de `compose.yaml` y el mes real. [Detalle](componentes/kafka-connect.md) |
| 🚧 | Ingesta de principio a fin (T-015, T-018) | La muestra sintética llega del CSV a la tabla fila a fila, comprobado en contenedores (`IngestE2EIT`). **Sin probar:** el stack de `compose.yaml` y el mes real |
| 🟡 | [Servidor MCP](componentes/servidor-mcp.md) | Arranca y sirve `list_tables`, `describe_table`, `run_query`, `time_travel` y `pipeline_health` a un cliente MCP real en los tests, con su [validador de SQL](componentes/servidor-mcp.md#el-validador-de-sql). Su imagen arranca sana y contesta a un cliente MCP en contenedores (T-093) y ya es un servicio del compose. **Sin probar:** `./mk up` con ese servicio. Aún no tiene claves de API |
| 🟡 | [Agente](componentes/agente.md) (T-094) | Contesta una pregunta consultando por MCP, con tope de llamadas; comprobado con un modelo simulado contra la imagen real del servidor. Gemini por defecto, Claude por configuración. Se pregunta con `./mk ask Q="..."` (imagen probada con modelo simulado). **Sin probar:** con un modelo de verdad (T-097). Aún sin métricas (T-034) |
| ⬜ | [Capa semántica](componentes/capa-semantica.md) | Solo existen su `pom.xml` y sus [definiciones en palabras](../metrics.md) |
| ⬜ | Chaos y self-healing, evals, RBAC y auditoría | Planificado. `./mk evals`, `chaos` y `demo` responden «not implemented yet» |

No hay ninguna cifra de precisión ni de rendimiento porque todavía no se ha medido nada de eso.

Leyenda: ✅ funciona · 🚧 en curso · ⬜ planificado.

## Índice

- [01. Conceptos y glosario](01-conceptos.md)
- [02. Arquitectura](02-arquitectura.md)
- [03. Pruébalo tú](03-probarlo.md)
- [04. Operación y diagnóstico](04-operacion.md)
- [05. Decisiones](05-decisiones.md)

### Componentes

| Transporte | Almacén | Consulta y agente | Entorno |
|---|---|---|---|
| ✅ [Kafka](componentes/kafka.md) | ✅ [Iceberg](componentes/iceberg.md) | ✅ [DuckDB](componentes/duckdb.md) | ✅ [Imagen tools](componentes/imagen-tools.md) |
| ✅ [Schema Registry](componentes/schema-registry.md) | ✅ [Polaris](componentes/polaris.md) | 🟡 [Servidor MCP](componentes/servidor-mcp.md) | ✅ [agent-board](componentes/agent-board.md) |
| ✅ [Kafka Connect e Iceberg sink](componentes/kafka-connect.md) | ✅ [Postgres](componentes/postgres.md) | ⬜ [Capa semántica](componentes/capa-semantica.md) | |
| 🚧 [Productor](componentes/productor.md) | ✅ [SeaweedFS](componentes/seaweedfs.md) | 🟡 [Agente](componentes/agente.md) | |

Los scripts de apoyo del tutorial están en [`ejemplos/`](ejemplos/lh.sh).
