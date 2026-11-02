# Capa semántica

**Estado:** ⬜ planificado. Solo existe [`semantic/pom.xml`](../../../semantic/pom.xml). Tarjetas T-027, T-029, T-031 y T-033.

[Portada](../README.md) · [Glosario](../01-conceptos.md)

## Qué es, en cristiano

Un diccionario de la empresa. Si preguntas a tres personas qué es «tasa de conversión», puedes recibir tres cálculos. Aquí se escribe una sola definición, y todos, incluido el agente, la usan por su nombre.

## Para qué estará aquí y qué pasaría sin ella

Hará que el agente pida la métrica en vez de improvisar SQL. Sin ella, cada pregunta sería un SQL nuevo, irrepetible y quizá distinto cada vez.

## Cómo se conectará

Es una biblioteca Java, sin servicio ni puertos.

| Dirección | Quién |
|---|---|
| Usada por | [Servidor MCP](servidor-mcp.md), herramienta `get_metric` |
| Ejecuta en | [DuckDB](duckdb.md) |

## Cómo funcionará por dentro

Métricas en ficheros YAML dentro de `semantic/` ([decisión 8](../../architecture.md#decisiones)). Las cuatro del [brief](../../brief.md#funcionalidades-clave): conversión view→cart→purchase, ticket medio, abandono de carrito y top marcas.

El orden de las tarjetas es la parte interesante:

1. T-027: el arquitecto las define en palabras.
2. T-029: el tester calcula la respuesta correcta sin ver la implementación.
3. T-031: se escribe el YAML y debe coincidir.
4. T-033: `get_metric(name, from, to, limit)` las expone por MCP.

## Cómo estará configurado

Aún no hay formato de YAML ni fichero alguno. La razón de descartar dbt o Cube está en [Decisiones](../05-decisiones.md).

## Cómo probarlo tú mismo

Todavía no se puede.

## Qué puede fallar

Riesgos de diseño: que una definición en palabras sea ambigua, sobre todo con datos sucios (duplicados, sesiones nulas), y que una pregunta no encaje con ninguna métrica. En ese caso el agente recurriría a `run_query`.

## Para saber más

- [Brief del proyecto](../../brief.md)
- [Bases de la decisión](../../architecture.md#decisiones)
