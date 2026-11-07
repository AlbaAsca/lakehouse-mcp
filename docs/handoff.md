# Traspaso entre sesiones del consultor

Léelo al empezar una sesión nueva, junto con el panel (`python board/board.py list`). Actualízalo al terminar: corto, solo lo que no está en el panel.

**Actualizado:** 2026-11-07

## En curso
- **Versión 0.1.0 (T-175):** primera demo de principio a fin. Alcance: T-097, T-098, T-171, T-172, T-173, T-174, T-141, T-133, T-075, T-113.
  La responsable autoriza al consultor a publicarla entera (rama `release/0.1.0`, etiqueta `v0.1.0`, release en GitHub) cuando se cumpla el alcance.
- **Grabación del GIF (T-097):** pendiente de que se renueve el cupo diario gratuito de Gemini (20 peticiones al día por modelo).
  Guion en `docs/demo-grabacion.md`. El ensayo del 7-nov cuadró exacto con `evals/demo/expected.json` usando `AGENT_MODEL=gemini-3.5-flash`.
  La tabla `ecommerce.events` tiene 50 000 filas: no volver a cargar la muestra.

## Decisiones vigentes
- Gemini por defecto (capa gratuita), proveedor configurable; con datos reales, cuenta de pago.
- Sin Trino ni benchmark. SeaweedFS en lugar de MinIO.
- Tests por niveles: módulo en cada tarjeta, suite completa en la CI. A `main` solo sube lo verde.
- Los agentes no pueden ejecutar `./mk seed*`. Comandos destructivos prohibidos siempre.
- Tester con Sonnet desde el 7-nov: vigilar si se le escapan fallos (T-081).

## Cosas del entorno
- Si Docker no responde: `powershell -NoProfile -ExecutionPolicy Bypass -File ..\docker-start.ps1`.
- La tarea programada «lakehouse-mcp runner» reanuda el runner cada 3 h, pero no arranca Docker.
- Tras un corte de luz o reinicio: arrancar Docker, el panel (`python board/server.py`) y el carril de medir.
