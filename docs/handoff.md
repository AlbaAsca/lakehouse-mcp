# Traspaso entre sesiones del consultor

Léelo al empezar una sesión nueva, junto con el panel (`python board/board.py list`). Actualízalo al terminar: corto, solo lo que no está en el panel.

**Actualizado:** 2026-11-08

## En curso
- **Versión 0.1.0 (T-175):** primera demo de principio a fin. Alcance: T-097, T-098, T-171, T-172, T-173, T-174, T-141, T-133, T-075, T-113.
  La responsable autoriza al consultor a publicarla entera (rama `release/0.1.0`, etiqueta `v0.1.0`, release en GitHub) cuando se cumpla el alcance.
- **Grabación del GIF (T-097):** `python scripts/demo/record_demo.py` (real) ya está listo y probado sin llamar al modelo; `--replay` reusa la última salida.
  8-nov: `.env` usa `AGENT_MODEL=gemini-3.8-flash`, pero Google lo devolvía saturado (503, timeout de 300 s); 3.5 y 3.6 sin cupo hoy.
  Antes de lanzar: probar con una petición mínima que el modelo responde rápido. Al grabar, sustituir la maqueta (`docs/media/demo-minima-maqueta.gif`) en README y wiki/06-demo.md y borrarla.
  La tabla `ecommerce.events` tiene 50 000 filas: no volver a cargar la muestra.
- Pendiente tras la grabación: T-098, CI en verde y publicar 0.1.0 (autorizado).
- Idea sin tarjeta: proveedor gratuito de reserva (GitHub Models/Groq) exigiría añadir el starter de OpenAI de Spring AI; baja prioridad.

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
