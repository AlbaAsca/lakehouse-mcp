# Evals: preguntas y verdad de referencia

**Estado:** 🚧 hay verdad de referencia para los chequeos de calidad; `./mk evals` aún no está implementado.

## Para qué está aquí

Para saber si el agente acierta hay que comparar con una respuesta correcta calculada **sin usar la aplicación**. Esa respuesta es la «verdad de referencia».

## Qué hay hoy

- [`evals/truth/compute_quality_truth.py`](../../../evals/truth/compute_quality_truth.py): calcula nulos, duplicados, precios fuera de rango y volumen por día y tipo, solo con la biblioteca estándar, desde la muestra. Salida: `quality.json`. Se usa para comparar `data_quality_check` elemento a elemento.
- [`evals/demo/`](../../../evals/demo/): resultado esperado (`expected.json`) y su cálculo (`reference.py`) para la demo.

No hay todavía cifras de precisión del agente: no se ha medido.

## Batería de preguntas sin modelo (8-nov-2026)

`python evals/battery/run.py` lanza 13 preguntas de dificultad creciente contra `run_query` del servidor real y compara cada resultado con una referencia calculada aparte desde el CSV sintético. No usa ningún modelo: mide el servidor y su validador, no al agente. Resultado: **13 de 13 coinciden** (informe en `evals/battery/report.md`).

- **Fácil:** filas, usuarios distintos, eventos por tipo.
- **Medio:** categorías con más ingresos, días y hora con más compras, conversión de vista a compra por marca.
- **Experta:** un cuadro de mando por marca en una sola consulta (cinco CTE, `LEFT JOIN`, embudo, dinero perdido en carritos abandonados, cuota de ingresos y Pareto acumulado con ventanas); retención por cohorte con un `JOIN` consigo misma; y mediana de minutos entre la primera vista y la primera compra de una sesión.
- **Difícil:** usuarios con 2 o más compras (`HAVING`), marca de la primera compra de cada usuario (función de ventana y CTE), sesiones con embudo completo (agregación condicional).

Dos preguntas se rechazaron al principio porque `run_query` no admite `timezone()`; se resuelven con `CAST(event_time AS DATE)` y `extract(hour FROM event_time)`, que trabajan en UTC. Lo que no cubre: preguntas con varias tablas (solo hay una), el comportamiento con el mes real de 5,6 GB y la elección de herramienta por un modelo (eso lo medirán las evals del agente, T-048 y T-056).
