# La demo: una pregunta de principio a fin

> **Estado (8-nov-2026):** la demo está grabada con un cliente MCP (Claude Code), sin clave de modelo. La demo con el agente propio de Spring AI está ensayada con Gemini, pero no grabada: el cupo gratuito del modelo estaba agotado.

<p align="center"><img src="../media/demo-minima.gif" alt="Cliente MCP llamando a las herramientas del servidor" width="80%"></p>

## En una frase

Escribes una pregunta en lenguaje normal, un agente de IA la convierte en una consulta segura sobre los datos y te contesta con cifras.

<p align="center"><img src="../img/demo-flow.svg" alt="Recorrido de una pregunta de principio a fin" width="100%"></p>

## Los pasos, para principiantes

1. **Tú preguntas.** `./mk ask Q="¿Qué cinco marcas ingresan más?"`.
2. **El agente** (un programa Java con un modelo de IA) decide qué herramienta necesita.
3. **El servidor MCP** es la puerta: comprueba tu clave (analista o administrador) y solo deja pasar lecturas.
4. **Dos caminos.** Si la pregunta es de negocio, usa `get_metric`: la cifra está definida una sola vez, en un fichero YAML. Si es una consulta suelta («¿cuántas filas hay?»), usa `run_query`, con SQL validado.
5. **DuckDB** lee las tablas de Iceberg y devuelve el resultado; el agente lo explica.

## Para quien ya sabe de esto

- La regla de elección está en el prompt del agente (`agent/src/main/resources/prompts/system.md`): métrica primero, `run_query` solo si ninguna encaja o si la pregunta fija sus propias reglas («sin quitar duplicados», «incluyendo negativos»).
- `top_brands` limpia los datos: `DISTINCT *`, `price > 0` y `user_session` no vacía. Sus cifras no son sumas en crudo.
- `run_query` pasa por un validador de solo lectura y un tope de filas.

## Lo simulado y lo real

| Elemento | Real | Simulado |
|---|---|---|
| Stack, 50 000 filas y claves locales | ✅ | |
| Llamadas a `list_tables`, `run_query` y `get_metric`, y sus respuestas | ✅ las da el servidor en cada grabación | |
| Rechazo de la consulta que lee un fichero del disco | ✅ lo hace el validador | |
| El agente que decide qué herramienta llamar | | 🟡 aquí es Claude Code como cliente MCP, no el agente de Spring AI |
| Texto de la respuesta final | | 🟡 lo redacta el script a partir de las respuestas |
| La animación del GIF | | 🟡 dibujada con Pillow, no es una captura de pantalla |
| Demo con el agente Java (`./mk ask`) | ✅ ensayo del 8-nov con Gemini cuadró con la referencia | 🟥 sin grabar |

## Cómo se grabó

```bash
python scripts/demo/record_mcp_demo.py
```

Necesita el stack arriba y `MCP_ANALYST_KEY` en `.env`. No usa ningún modelo, no gasta cupo y no carga datos. Para grabar con el agente Java cuando haya cupo: `python scripts/demo/record_demo.py` (el GIF con el agente se guardaría en `docs/media/`).

## Qué puede fallar

| Síntoma | Causa | Qué hacer |
|---|---|---|
| El agente recibe 401 | Faltan `MCP_ANALYST_KEY` y `MCP_ADMIN_KEY` en `.env` | Añadir dos cadenas aleatorias y `./mk up` |
| Error de nombre de tabla (`Table not allowed`) | `run_query` pide el nombre completo `lh.ecommerce.events` | Escribirlo así (T-213 lo hará explícito) |
| Error de cuota (429) | Se acabaron las 20 peticiones diarias del modelo gratuito | Esperar a la renovación, hacia las 9:00 en España |
| Importes distintos de la referencia | Se cargó la muestra dos veces | No repetir `./mk seed-sample` |
