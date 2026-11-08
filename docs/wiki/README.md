# Wiki de lakehouse-mcp

lakehouse-mcp es un proyecto de portfolio: un pequeño almacén de datos moderno (un [lakehouse](01-conceptos.md#lakehouse)) que corre entero en tu ordenador y que, cuando esté terminado, un [agente de IA](01-conceptos.md#agente) podrá consultar, vigilar y reparar a través de [MCP](01-conceptos.md#mcp).

Piensa en una tienda online. Cada clic es una nota. Las notas llegan por una cinta transportadora (Kafka), un operario las archiva en cajas ordenadas (Iceberg sobre SeaweedFS), un fichero central dice qué hay en cada caja (Polaris) y alguien puede hacer preguntas sobre todo ello sin mover las cajas (DuckDB). Al final de la cadena habrá un ayudante de IA que hace esas preguntas y avisa cuando algo se rompe.

Cada página empieza en lenguaje llano y avanza hasta el detalle técnico. Puedes parar donde quieras.

> **Fecha de esta foto:** 7 de noviembre de 2026, sobre la rama `develop` en el commit `090b32f`. El proyecto se está construyendo mientras se escribe esto: si algo no coincide, mandan el código y [el panel](componentes/agent-board.md).

## Dos rutas de lectura

**Empiezo de cero**

1. [Conceptos desde cero](01-conceptos.md): qué es un evento, un topic, un lakehouse, un snapshot.
2. [Arquitectura](02-arquitectura.md), hasta «El viaje de un evento».
3. [Pruébalo tú](03-probarlo.md): levantas el stack y creas una tabla.
   Después, [la demo](06-demo.md): una pregunta al agente, de principio a fin, con dibujos.
4. La página de la pieza que te llame la atención. Lee hasta «Para qué está aquí».

**Ya sé de esto**

1. [Tabla de conexiones y orden de arranque](02-arquitectura.md#tabla-de-conexiones).
2. [Decisiones, alternativas y coste](05-decisiones.md).
3. En cada [componente](#componentes), las secciones «Cómo está configurado aquí» y «Qué puede fallar».
4. [Operación y diagnóstico](04-operacion.md).

## Qué funciona hoy

| | Pieza | Estado en una línea (el detalle y lo «sin probar» están en su página) |
|---|---|---|
| ✅ | Stack en Docker Compose, tabla Iceberg escrita y leída desde DuckDB | `./mk smoke` y [el tutorial](03-probarlo.md) |
| ✅ | Catálogo persistente, imagen `tools`, muestra sintética | [Polaris](componentes/polaris.md), [Postgres](componentes/postgres.md), [Imagen tools](componentes/imagen-tools.md) |
| ✅ | Conector hacia Iceberg | [Kafka Connect](componentes/kafka-connect.md) |
| 🚧 | Productor y ingesta de principio a fin | [Productor](componentes/productor.md) |
| ✅ | Servidor MCP: nueve herramientas, validador de SQL y roles por clave | [Servidor MCP](componentes/servidor-mcp.md), [DuckDB](componentes/duckdb.md#dentro-del-servidor-mcp) |
| ✅ | Capa semántica: métricas en YAML y `get_metric` | [Capa semántica](componentes/capa-semantica.md) |
| 🟡 | Agente: contesta por MCP; ensayado con Gemini, falta el GIF real | [Agente](componentes/agente.md), [la demo](06-demo.md) |
| 🟡 | Chaos y self-healing (hecho el ciclo de duplicados), seguridad (falta auditoría) | Planificado el resto: `./mk chaos` completo y `./mk demo` |

Solo hay una comprobación puntual: las cinco marcas de la demo coinciden con su referencia (8-nov). No hay cifras de precisión ni de rendimiento medidas.

Leyenda: ✅ funciona · 🟡 funciona en parte (mira «Sin probar») · 🚧 en curso · ⬜ planificado.

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
| 🚧 [Productor](componentes/productor.md) | ✅ [SeaweedFS](componentes/seaweedfs.md) | 🟡 [Agente](componentes/agente.md) | ✅ [Testkit](componentes/testkit.md) |
| | | 🚧 [Evals](componentes/evals.md) | ✅ [Mantenimiento](componentes/maintenance.md) |

Los scripts de apoyo del tutorial están en [`ejemplos/`](ejemplos/lh.sh).
