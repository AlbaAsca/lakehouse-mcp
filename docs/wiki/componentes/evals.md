# Evals: preguntas y verdad de referencia

**Estado:** 🚧 hay verdad de referencia para los chequeos de calidad; `./mk evals` aún no está implementado.

## Para qué está aquí

Para saber si el agente acierta hay que comparar con una respuesta correcta calculada **sin usar la aplicación**. Esa respuesta es la «verdad de referencia».

## Qué hay hoy

- [`evals/truth/compute_quality_truth.py`](../../../evals/truth/compute_quality_truth.py): calcula nulos, duplicados, precios fuera de rango y volumen por día y tipo, solo con la biblioteca estándar, desde la muestra. Salida: `quality.json`. Se usa para comparar `data_quality_check` elemento a elemento.
- [`evals/demo/`](../../../evals/demo/): resultado esperado (`expected.json`) y su cálculo (`reference.py`) para la demo.

No hay todavía cifras de precisión del agente: no se ha medido.
