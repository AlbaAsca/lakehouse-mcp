# La demo: una pregunta de principio a fin

> **Estado (8-nov-2026):** el ensayo con Gemini cuadra con la referencia. El GIF que se enseña es una **maqueta simulada**; la grabación real está pendiente de que se renueve el cupo gratuito del modelo.

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
| Cinco marcas e importes de la respuesta | ✅ ensayo del 8-nov | |
| Respuesta «50 000 filas» y línea `run_query` | | 🟥 esperada, aún no vista |
| El GIF `demo-minima-maqueta.gif` | | 🟥 animación dibujada, con aviso rojo en cada fotograma |

## Cómo se grabará la versión real

```bash
python scripts/demo/record_demo.py
```

Ejecuta las dos preguntas del guion contra el stack real, filtra el ruido de Docker, aborta si ve algo que parezca una clave y dibuja la terminal en `docs/media/demo-minima.gif`. No es una captura de pantalla: el texto es el real y la animación es sintética. `--replay` reutiliza la última salida real sin gastar cupo.

## Qué puede fallar

| Síntoma | Causa | Qué hacer |
|---|---|---|
| El agente recibe 401 | Faltan `MCP_ANALYST_KEY` y `MCP_ADMIN_KEY` en `.env` | Añadir dos cadenas aleatorias y `./mk up` |
| Error de cuota (429) | Se acabaron las 20 peticiones diarias del modelo gratuito | Esperar a la renovación, hacia las 9:00 en España |
| Importes distintos de la referencia | Se cargó la muestra dos veces | No repetir `./mk seed-sample` |
