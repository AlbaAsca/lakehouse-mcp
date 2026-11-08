# lakehouse-mcp — instrucciones para Claude Code

Qué es: demo de portfolio: lakehouse local (Kafka, Iceberg/Polaris sobre SeaweedFS, DuckDB) que un agente Spring AI consulta, vigila y repara vía MCP. Alcance completo: `docs/brief.md`.
Arranque: `./mk up` (`mk.cmd` en Windows). **Todo corre en Docker**: JDK, Maven, make y Kaggle viven en la imagen `tools`; no se instala nada en el host. Si Docker no responde: `powershell -NoProfile -ExecutionPolicy Bypass -File ..\docker-start.ps1`. Panel: `python board/server.py` → http://localhost:8020
Tests: `./mk test` (Java), `./mk test MODULE=<módulo>` (solo uno), `./mk test-board` (panel). Verifica versiones y APIs en la documentación oficial; si algo no existe o falla, a «Te necesita» con alternativa.

## Tu papel: consultor
La sesión principal es el **consultor**: habla con el responsable (respuestas breves y directas), decide prioridades
con él y delega en los agentes de `.claude/agents/`: `arquitecto`, `desarrollador`, `tester`, `investigador`, `experto`.
- El consultor **prioriza y mueve tarjetas** para que trabajen los agentes; no implementa él lo que se puede delegar.
- **Todo trabajo tiene tarjeta** y se actualiza al momento (también lo que hace el propio consultor).
- Tareas independientes → agentes **en paralelo**, cada uno en su propia copia (worktree git); el consultor integra
  las ramas, pasa la suite completa y sube.
- El **tester** trabaja de forma continua: verifica lo entregado y convierte cada fallo en una tarea nueva.

## Panel (obligatorio)
`board/board.json` es la fuente de verdad. **Nunca lo edites a mano**: usa `board/board.py` (tiene bloqueo).
- Crear: `python board/board.py add "Título" --owner <rol> --est <min> --prio alta|media|baja --desc "criterio de aceptación"`
  (`--manual "qué tiene que hacer el responsable"` si necesita instalar algo, un archivo o una decisión).
- Mover: `python board/board.py move T-012 doing|review|human|blocked|done --by <rol> --msg "..."`
- Nota: `python board/board.py note T-012 "texto"` · Listar: `python board/board.py list`
- Reescribir la descripción: `python board/board.py desc T-012 "texto"` · Dependencias al crear: `--deps T-003,T-007`

Estados: `backlog` → `next` → `doing` → `review` → `done`, más `human` y `blocked`:
- **Te necesita (`human`)** es solo para lo que únicamente puede hacer el responsable (instalar, credenciales, decidir, publicar).
- Comentar en el panel una tarjeta de Te necesita: si es de un agente vuelve a `next` con el comentario en el historial; si es del consultor queda marcada `[RESPONDIDA]`. **El consultor revisa esas respuestas al empezar cada turno** (`board.py list`).
- **Bloqueado (`blocked`)** es para lo que espera otra cosa: una medición, otra tarjeta, un recurso externo. No lo mandes a `human`.

**Cómo se escribe una tarjeta** (la lee una persona, no un robot): título en lenguaje llano; en la descripción, arriba 2-4 frases
llanas (qué problema hay, qué se propone y por qué) y una línea «Cómo sabremos si funciona: …». Los datos técnicos (ficheros,
funciones, números, criterio exacto) van al final, debajo de una línea `## Técnico`; el panel la muestra plegada.

## Tamaño y orden de las tarjetas
- Una tarjeta es una **entrega de unos 30–90 minutos de agente**. Los fallos o mejoras pequeños (<15 min) del mismo ámbito se agrupan en una sola tarjeta que los lista en `## Técnico`; las absorbidas se cierran con nota «Absorbida en T-xxx». Solo van sueltos los urgentes o los que bloquean la CI.
- Orden de ejecución: `rank` de 1 (primero) a 1000 (último), `python board/board.py rank T-012 40`. Sin `rank` se deriva de la prioridad (alta 100, media 500, baja 900). El runner coge Revisión, Próximo y Backlog, y dentro de cada uno por `rank`. 900 = después de la versión en curso.

- **Verificación:** solo pasa por el tester lo que toca seguridad, datos reales, `testkit`/`compose.yaml`/`connect/`/`infra/` o el recorrido de punta a punta (`needs_tester` en `board/runner.py`, o `verify: true` en la tarjeta). El resto lo da por hecho el desarrollador con los tests de su módulo en verde y la CI como revisor.

## Estimaciones
En **minutos de agente**. El tiempo real NO se escribe: se mide con `started_at`/`finished_at` al pasar por `doing`.
Calibración inicial (ajústala con los datos del panel): lo nuevo o de calidad difícil de medir tarda **2–3×** lo
estimado; interfaz, tests y documentación, la mitad. Si una tarea pasa de **1,5×**, se anota causa y nueva previsión
antes de seguir. Cada agente registra su propio inicio y fin (no se cierra en bloque).

## Calidad
- Antes de cambiar una regla o un algoritmo, mira el caso real (datos, captura, salida).
- Para resultados medibles: **verdad de referencia independiente** + tests elemento a elemento, no solo totales.
- Ninguna tarea se da por hecha sin los tests de su módulo en verde. Verificar es leer el diff, pasar los tests y probar el comportamiento;
  buscar palabras con grep no es verificar.
- Los tests **nunca** tocan `board/board.json` real ni procesos reales (`tests/conftest.py` redirige el panel a una copia).
- Windows (consola cp1252): todo script que imprime fuerza UTF-8 (`sys.stdout.reconfigure(encoding="utf-8")`; los .bat ponen
  `PYTHONUTF8=1`).

## Commits (obligatorio)
- Autoría: la del responsable (configurada en el repo). Sin líneas de coautoría automáticas.
- En **inglés**, **Conventional Commits**: `type(scope): summary` en imperativo, ≤72 caracteres
  (`feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `build`, `chore`), con la tarea al final: `(T-012)`.

## Ejecución autónoma
`board/runner.py` ejecuta las tarjetas con `claude -p --agent <rol>` (revisión → próximo → backlog, por prioridad), en dos carriles:
pensar (investigador, experto, arquitecto) y medir (el resto). Cómo arrancarlo, pausarlo y verlo: `docs/wiki/componentes/agent-board.md`.
- **Permisos:** python, git status/diff/log, lectura, `./mvnw`, `./mk test|evals`, `docker compose ps|logs`. Nada de instalar,
  borrar, `cp`/`mv`, `git add`/`commit`/`push` ni comandos encadenados (`&&`, `;`, `|`, heredoc): el runner hace los commits.
- Un corte por cupo, o un agente que acaba sin mensaje final, no cuenta como fallo: la tarjeta vuelve a su estado.
- **Rama y PR por entrega (`PR_MODE` en `board/runner.py`, apagado por defecto; diseño en `docs/pr-workflow.md`):** con
  `PR_MODE` encendido, cada tarjeta trabaja en su worktree (`../lakehouse-mcp.wt/T-xxx`, rama `feature/T-xxx-slug`) y el
  runner empuja la rama y abre el PR a `develop`; en cada vuelta fusiona (squash) los PR con CI verde y marca `done`,
  o devuelve la tarjeta a `next` con el log si la CI está roja (cuenta para `MAX_REJECTS`). `board/board.json` no viaja
  en las ramas (la CI rechaza el PR que lo toque): el runner lo guarda con commits `chore(board)` a `develop`. Los agentes
  usan `board.py` con normalidad (el runner les pone `LAKEHOUSE_BOARD_DIR`). **Sin probar de punta a punta**; apagado, rige
  el flujo anterior (commits directos a `develop`).
- **Tarjetas nuevas de los agentes:** prioridad baja salvo que algo esté roto o bloqueado; el consultor las ordena antes de cada versión.

## Tests por niveles
Casi todo el tiempo de una tarjeta es esperar tests, así que cada nivel se pasa donde aporta:
- **Desarrollador y tester:** solo los tests del módulo: `./mk test MODULE=<módulo>` (compila lo que necesita, pero solo ejecuta los tests de ese módulo).
- **Suite completa:** la pasa la CI en cada push. Si la CI está en rojo, tarjeta de prioridad alta. A `main` solo sube lo que está en verde.
- **Excepción, con tests de contenedores antes de cerrar:** las tarjetas que tocan `testkit`, `compose.yaml`, `connect/`, `infra/` o la integración de punta a punta. En caso de duda, pásalos.

## Documentación
`docs/wiki/` explica cada pieza a principiantes y expertos. Se actualiza **una vez por versión** (en su tarjeta de publicación),
no en cada tarjeta: quien cambie una pieza anota en su tarjeta qué cambia para la wiki. Todo comando publicado se ejecuta antes;
lo que no, se marca «sin probar». Nunca se presenta como hecho lo planificado.
- **Sesiones del consultor:** al empezar, lee `docs/handoff.md` y el panel; al terminar, actualiza `docs/handoff.md` (corto).

## Código
`producer/` reproduce eventos · `connect/` config del sink · `mcp-server/` tools MCP · `agent/` cliente Spring AI · `semantic/` métricas YAML · `evals/` preguntas y verdad de referencia · `testkit/` tests con contenedores · `maintenance/` expirar snapshots (proceso de un solo uso) · `infra/` imágenes y scripts · `docs/` diseño y Pages · `board/` panel.
- Dataset con licencia «© Original Authors»: **no subas datos reales al repo**; la muestra de CI es sintética con el mismo esquema.
- `compose.yaml` no usa bind mounts salvo en `tools`: la configuración va dentro de las imágenes.
