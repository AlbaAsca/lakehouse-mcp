# Guion de la primera demo y del GIF

**Sin probar con un modelo de verdad.** `./mk ask` existe (T-096, probado con un modelo simulado) y `./mk seed-sample` funciona (T-091), pero el guion entero no se ha ejecutado. Este guion se ejecuta en T-097; corrige aquí lo que cambie. Diseño: [`architecture.md`](architecture.md#recorrido-mínimo-la-primera-demo).

## Antes de grabar

1. Clave con límite de gasto en `.env` (T-032) y `AGENT_MODEL` definido.
2. Tabla sin datos anteriores: **cargar la muestra dos veces duplica las filas** y el resultado ya no cuadra. No uses `docker compose down -v` para vaciarla sin pensarlo: también borra el volumen `data`, con el CSV real descargado.
3. Terminal limpia, con letra grande, unas 100 columnas y sin mostrar `.env` ni variables de entorno.
4. Herramienta de grabación: [ScreenToGif](https://www.screentogif.com/) permite recortar las esperas. Si prefieres no instalar nada, Win+G graba vídeo; en ese caso, enlázalo en vez de usar un GIF.

## Ensayo sin grabar

```
./mk up
./mk seed-sample
```

Espera al menos un minuto, porque el conector guarda cada ~30 s, y luego pregunta:

```
./mk ask Q="How many rows does the events table have?"
./mk ask Q="Which five brands have the highest purchase revenue? Sum the price of every purchase row as stored (no deduplication, negative prices included, ignore empty brands). Show the amounts."
```

Compara con `evals/demo/expected.json` (T-092):

- **Filas:** exactas. Si faltan, espera y repite; si hay de más, se cargó dos veces.
- **Marcas:** las mismas cinco y en el mismo orden.
- **Importes:** iguales salvo redondeo a dos decimales.

Si no cuadra, **no grabes**: copia la salida en una tarjeta para el desarrollador. Anota en T-097 tokens, coste y duración.

## Grabación (30-60 s)

| Toma | Qué se ve |
|------|-----------|
| 1 | `./mk up` terminado, con los servicios sanos (recorta la espera) |
| 2 | `./mk seed-sample` con las filas enviadas |
| 3 | `How many rows does the events table have?`: se ve `run_query` y la cifra exacta (50 000) |
| 3b | La pregunta de las marcas: el agente usa `list_metrics` y `get_metric` (capa semántica, por diseño) y da las cifras de `expected.json` |
| 4 | Opcional: una consulta prohibida, como leer un fichero del disco, rechazada por el validador |

La toma 3 es la que prueba que el agente consulta de verdad: si su salida no muestra `run_query`, avísalo en T-097. La 3b no lo muestra, porque las preguntas de negocio van por métricas (`agent/src/main/resources/prompts/system.md`).

Guarda el GIF en `docs/media/demo-minima.gif` (menos de 10 MB). Revisa fotograma a fotograma que no salga ninguna clave. El texto que lo acompañe debe decir que los datos son sintéticos (T-098).
