# Brief del proyecto

Demo de portfolio de GitHub (AlbaAsca): un lakehouse moderno local que un agente de IA consulta, vigila y repara vía MCP.
Decisiones de la fase 0 que cambian el brief original: SeaweedFS en lugar de MinIO (repo archivado) y todo en Docker, sin instalar nada en el host.

## Datos
Kaggle `mkechinov/ecommerce-behavior-data-from-multi-category-store` (REES46).
- Un solo mes, con muestreo configurable (1-5M filas).
- Productor Java que reproduce los eventos en Kafka en orden temporal, con aceleración configurable y opción de reescribir el timestamp a "ahora".
- Esquema: event_time, event_type, product_id, category_id, category_code, brand, price, user_id, user_session. Particionado por día y event_type.
- Conserva los datos sucios del origen como casos de calidad.
- Atribución y licencia en el README. La licencia es «© Original Authors»: la muestra de CI es sintética.

## Servidor MCP (tools)
- list_tables / describe_table (esquema, particiones, snapshots)
- run_query: solo SELECT, validado y con límite de filas
- get_metric: métricas de la capa semántica
- pipeline_health: lag del consumidor, frescura, último snapshot
- data_quality_check: nulos, duplicados, rangos, volumen vs. histórico
- time_travel: consulta sobre un snapshot anterior
- propose_fix / apply_fix: el agente propone el arreglo y solo se aplica con aprobación humana

## Funcionalidades clave
1. Agente demo: cliente Spring AI (y configuración para Claude Desktop) que responde preguntas de negocio, p. ej. "¿por qué cayeron las compras ayer?", detecta la anomalía y la explica.
2. Self-healing: `make chaos` inyecta fallos (schema drift, duplicados, consumidor caído, partición vacía) y el agente sigue el ciclo detectar → diagnosticar → proponer arreglo → aplicarlo con aprobación → verificar.
3. Capa semántica: métricas en YAML (conversión view→cart→purchase, ticket medio, abandono de carrito, top marcas) que el agente usa en vez de improvisar SQL.
4. Data contracts: esquemas versionados en Schema Registry con reglas de compatibilidad, y un test que rompe el build si se rompe el contrato.
5. Mantenimiento de Iceberg: compactación, expiración de snapshots y evolución de esquema demostrada (añadir una columna sin reprocesar).
6. Evals del agente: 20-30 preguntas con la respuesta esperada calculada de forma independiente, ejecutadas en CI y con la precisión publicada en el README.
7. Benchmark: DuckDB 2.0 frente a Trino sobre las mismas consultas, con tabla reproducible (`make bench`).
8. Seguridad y gobierno: RBAC en las tools MCP (analyst frente a admin), log de auditoría de cada llamada del agente y enmascarado de user_id.

## Requisitos técnicos
- Módulos: producer/, connect/, mcp-server/, agent/, semantic/, evals/, bench/, infra/, docs/
- Tests unitarios y de integración con Testcontainers; CI sin Kaggle
- Observabilidad: Micrometer/Actuator, con métricas de las tools MCP
- GitHub Actions: build, tests y evals
- Makefile: up, down, download-data, seed, test, evals, chaos, bench, demo
- Sin secretos en el repo (.env.example)

## Presentación
- README en inglés: diagrama Mermaid, quickstart, GIF/vídeo de la demo del agente y del self-healing, ejemplos de prompts, resultados de evals y benchmark, "Design decisions" y "Built with agent-board".
- GitHub Pages con arquitectura, demo y decisiones de diseño.

## Forma de trabajar
- Fases: infra → productor → ingesta → consulta → MCP → semántica → agente → chaos/self-healing → contracts → mantenimiento Iceberg → evals → benchmark → seguridad → README/Pages.
- El tester construye las respuestas de referencia de las evals sin mirar la implementación.
- Commits en inglés con Conventional Commits y el id de tarea.
- Si una versión o característica no existe o no funciona como se espera, a «Te necesita» con una alternativa propuesta.
