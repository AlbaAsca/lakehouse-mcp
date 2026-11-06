<p align="center">
  <img src="docs/img/hero.svg" alt="lakehouse-mcp: un lakehouse local que un agente de IA consulta, vigila y repara vía MCP" width="100%">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-24283B?logo=openjdk&logoColor=FF9E64&labelColor=1A1B26" alt="Java 25">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1-24283B?logo=springboot&logoColor=9ECE6A&labelColor=1A1B26" alt="Spring Boot 4.1">
  <img src="https://img.shields.io/badge/Spring%20AI-2.0-24283B?logo=spring&logoColor=9ECE6A&labelColor=1A1B26" alt="Spring AI 2.0">
  <img src="https://img.shields.io/badge/Apache%20Kafka-KRaft-24283B?logo=apachekafka&logoColor=7DCFFF&labelColor=1A1B26" alt="Apache Kafka en modo KRaft">
  <img src="https://img.shields.io/badge/Apache%20Iceberg-Polaris-24283B?logo=apache&logoColor=BB9AF7&labelColor=1A1B26" alt="Apache Iceberg con Apache Polaris">
  <img src="https://img.shields.io/badge/DuckDB-2.0-24283B?logo=duckdb&logoColor=E0AF68&labelColor=1A1B26" alt="DuckDB 2.0">
  <img src="https://img.shields.io/badge/estado-en%20construcci%C3%B3n-24283B?labelColor=1A1B26&color=E0AF68" alt="Estado: en construcción">
</p>

<p align="center">
  <b>Un lakehouse local que un agente de IA consulta, vigila y repara vía MCP.</b><br>
  Streaming a Iceberg · capa semántica · self-healing con aprobación humana · evals con verdad de referencia. Solo necesita Docker.
</p>

> 🇬🇧 **In English:** a local modern lakehouse that an AI agent queries, monitors and repairs through MCP. A Java producer
> replays real e-commerce events into Apache Kafka; the Iceberg Kafka Connect sink writes them to Apache Iceberg tables,
> catalogued by Apache Polaris on SeaweedFS and queried with DuckDB 2.0. A Spring AI agent reaches them through an MCP server.
> **Work in progress:** the infrastructure runs and is verified end to end by a smoke test; the producer, MCP server, agent,
> and evals are being built. Everything runs in Docker.

> [!NOTE]
> **Estado: infraestructura levantada, aplicación en construcción.** Hoy funciona lo marcado como ✅ en [Qué hay y qué falta](#qué-hay-y-qué-falta).
> Aquí no se publica ninguna cifra de precisión ni de rendimiento hasta que exista la medición que la respalde.

## El problema

Un agente que escribe SQL contra un lakehouse impresiona en una demo y asusta en producción. Inventa definiciones de negocio,
puede lanzar cualquier consulta, y nadie sabe si acierta. Cuando el pipeline falla, además, suele ser una persona quien lo
descubre leyendo un dashboard. Este proyecto pone al agente delante de esos problemas:

| Lo que pasa en producción | Qué lo resolverá aquí |
|---|---|
| Cada cual **calcula las métricas a su manera** | Capa semántica en YAML: el agente pide la métrica, no improvisa SQL |
| Un agente **lanza lo que se le ocurre** | `run_query` solo admite `SELECT` validados y con límite de filas |
| Un productor **cambia el esquema y rompe a los demás** | Data contracts en Schema Registry: un test rompe el build |
| Se cae un consumidor o **llegan duplicados** | `make chaos` inyecta el fallo; el agente detecta, diagnostica y propone el arreglo |
| Un arreglo automático **empeora las cosas** | `apply_fix` solo se ejecuta con aprobación humana, y después se verifica |
| Nadie sabe **si el agente acierta** | 20-30 preguntas con la respuesta calculada de forma independiente |
| Quien mira los datos **no debería verlo todo** | RBAC en las tools, log de auditoría y `user_id` enmascarado |

## Arquitectura

<p align="center">
  <img src="docs/img/architecture.svg" alt="Arquitectura: el productor envía eventos a Kafka, Kafka Connect los escribe en Iceberg (Polaris y SeaweedFS), DuckDB los consulta, el servidor MCP los expone y el agente propone arreglos que aprueba el responsable" width="100%">
</p>

<p align="center"><sub>Es el diseño completo: lo que ya funciona está en <a href="#qué-hay-y-qué-falta">Qué hay y qué falta</a>.</sub></p>

| Pieza | Versión | Para qué |
|---|---|---|
| Apache Kafka (KRaft) | 4.4.0 | Transporte de eventos, sin ZooKeeper |
| Confluent Schema Registry | 8.4.0 | Esquemas versionados y reglas de compatibilidad |
| Iceberg Kafka Connect sink | 1.12.0 | Escribe de Kafka a Iceberg |
| Apache Polaris | 1.8.0 | Catálogo REST de Iceberg |
| SeaweedFS | 4.51 | Almacenamiento compatible con S3 |
| DuckDB | 2.0.0 | Motor de consulta (extensión `iceberg`) |
| Java · Spring Boot · Spring AI | 25 · 4.1.2 · 2.0.2 | Productor, servidor MCP y agente |

## La wiki

¿No sabes qué es un lakehouse? ¿O quieres ver exactamente lo que hay montado? [**La wiki**](docs/wiki/README.md) sirve para las dos cosas: conceptos desde cero, el viaje de un evento,
una página por cada pieza (qué es, cómo se conecta, cómo está configurada y cómo probarla), [un tutorial paso a paso](docs/wiki/03-probarlo.md) y [guía de diagnóstico](docs/wiki/04-operacion.md).

## Qué hay y qué falta

| | Pieza | Estado |
|---|---|---|
| ✅ | Stack en Docker Compose, con healthchecks | Arranca con un comando |
| ✅ | Ida y vuelta DuckDB → Polaris → SeaweedFS | Lo comprueba `./mk smoke` |
| ✅ | Conector de Iceberg | Un evento llega de Kafka a la tabla, comprobado en contenedores; falta el stack completo con el mes real |
| ✅ | Herramientas en una imagen (`tools`) | JDK, Maven, make, DuckDB y Kaggle, sin instalar nada |
| ✅ | Persistencia del catálogo de Polaris | En Postgres: sobrevive a un reinicio |
| 🚧 | Productor e ingesta del mes real | Lectura, muestreo y orden hechos; falta el envío y la ingesta |
| ⬜ | Servidor MCP: `list_tables`, `describe_table`, `run_query`, `get_metric`, `pipeline_health`, `data_quality_check`, `time_travel`, `propose_fix`, `apply_fix` | Planificado |
| 🚧 | Agente (Spring AI, Gemini por defecto o Claude por configuración) | Primera versión: contesta consultando por MCP y se corta en un tope, comprobado con un modelo simulado; falta probarlo con un modelo de verdad |
| ⬜ | Capa semántica | Planificado |
| ⬜ | Self-healing con `make chaos` | Planificado |
| ⬜ | Data contracts y mantenimiento de Iceberg | Planificado |
| ⬜ | Evals, RBAC y auditoría | Planificado |

## Cómo ejecutarlo

Solo necesitas Docker. En Windows, usa `mk.cmd` en lugar de `./mk`.

```bash
cp .env.example .env   # y cambia las contraseñas
./mk up                # la primera vez tarda: compila el conector de Iceberg
./mk smoke
```

`./mk smoke` termina con `duckdb -> polaris -> seaweedfs ok` cuando todo responde.

| Otras formas | |
|---|---|
| Cargar la muestra sintética, sin Kaggle (con el stack arriba; repetirlo duplica filas) | `./mk seed-sample` |
| Preguntar al agente (con el stack arriba y la clave en `.env`; sin probar con un modelo de verdad) | `./mk ask Q="How many rows does the events table have?"` |
| Descargar el dataset (necesita `~/.kaggle`) | `./mk download-data` |
| Otro mes | `./mk download-data MONTH=2019-Nov` |
| Reproducir el mes en Kafka (con el stack arriba) | `./mk seed` (por defecto 1 % de las sesiones a 3600×) |
| Otra muestra, velocidad o mes | `./mk seed MONTH=2019-Nov SAMPLE=0.1 SPEEDUP=60` |
| Ver el estado de los servicios | `docker compose ps` |
| Ver los logs de un servicio | `docker compose logs --tail 50 connect` |
| Maven dentro del contenedor | `./mvnw verify` |
| Apagar | `./mk down` |
| Apagar y borrar los datos | `docker compose down -v` |

Los objetivos `evals`, `chaos` y `demo` ya existen en el `Makefile`, pero todavía avisan de que no están implementados.

## Los datos

[eCommerce behavior data from multi category store](https://www.kaggle.com/datasets/mkechinov/ecommerce-behavior-data-from-multi-category-store),
publicado en Kaggle por mkechinov con datos de REES46 Marketing Platform. Son eventos reales de una tienda (`view`, `cart`, `purchase`), y
se usa un solo mes, de 5,6 GB sin comprimir.

Su licencia en Kaggle es «Data files © Original Authors». Por eso **los datos no están en este repositorio**: se descargan con tus propias
credenciales, y la muestra de los tests es sintética, con el mismo esquema. Los datos sucios del origen se conservan a propósito, como casos de calidad.

## Decisiones y su porqué

| Decisión | Por qué | Lo que cuesta |
|---|---|---|
| **SeaweedFS** frente a MinIO | El repositorio comunitario de MinIO está archivado y sin mantenimiento. SeaweedFS es Apache-2.0 y habla S3 | Es menos conocido, y sin STS: Polaris no entrega credenciales temporales, así que cada cliente usa claves S3 estáticas |
| **Todo en Docker**, incluidas las herramientas | El proyecto se clona y se ejecuta sin instalar JDK 25, Maven ni make | Cada comando arranca un contenedor, y hay que montar el socket de Docker en `tools` |
| **Sin bind mounts** fuera de `tools` | `./mk up` corre dentro de un contenedor y sus rutas no existen en el host. La configuración va dentro de las imágenes | Cambiar una configuración exige reconstruir la imagen |
| **Conector compilado desde el código** | El proyecto Iceberg no publica el zip: su documentación indica compilarlo | El primer arranque tarda unos minutos más |
| **Capa semántica** en vez de SQL libre | Una métrica de negocio tiene una sola definición, revisable en un diff | Hay que declarar cada métrica antes de poder usarla |

El diseño completo, con las alternativas descartadas, está en [docs/architecture.md](docs/architecture.md).

## Tests

```bash
./mk test         # Java, con Testcontainers
./mk test MODULE=producer   # solo los tests de ese módulo
./mk test-board   # el panel de agentes
./mk smoke        # el stack levantado, de punta a punta
```

Los módulos Java aún no tienen código ni tests propios: de momento `./mk test` solo comprueba que el proyecto compila y sus dependencias se resuelven.

## Hecho con agent-board

Este proyecto se planifica y se construye con [agent-board](https://github.com/AlbaAsca/agent-board): un kanban en el que un equipo de agentes de Claude Code
(arquitecto, desarrollador, tester, investigador y experto) trabaja por tarjetas estimadas. El panel está en [`board/`](board) y el historial de commits muestra
qué tarea produjo cada cambio. El tester construye las respuestas de referencia de las evals sin mirar la implementación.

```bash
python board/server.py   # http://localhost:8020
```

## Limitaciones y siguientes pasos

Es un proyecto de portfolio, no una plataforma de producción, y está a medio construir:

- **El catálogo de Polaris se guarda en Postgres.** Un test demuestra que sobrevive a un reinicio de Polaris en contenedores de prueba; falta comprobarlo en el stack completo.
- **Un solo nodo de todo.** Un broker y una instancia de cada servicio, con unos 8 GB de RAM para Docker.
- **Secretos locales.** Las credenciales son claves estáticas en `.env`, adecuadas para una demo local.
- **Coste de las evals.** Llaman a un modelo de pago, así que no pueden lanzarse enteras en cada push.

## Licencia

[MIT](LICENSE). El dataset tiene su propia licencia: mira [Los datos](#los-datos).
