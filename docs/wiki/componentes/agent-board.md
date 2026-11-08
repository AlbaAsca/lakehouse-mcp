# agent-board

**Estado:** ✅ funciona. Es la herramienta con la que se construye el proyecto, no parte del lakehouse.

[Portada](../README.md) · [Glosario](../01-conceptos.md)

## Qué es, en cristiano

Un tablero de tareas, como los de notas adhesivas, donde parte del trabajo lo hace un equipo de agentes de Claude Code: arquitecto, desarrollador, tester, investigador y experto. Cada tarjeta tiene su responsable, su estimación y su historial.

## Para qué está aquí y qué pasaría sin él

Hace que el trabajo sea visible y trazable. Sin él, los agentes trabajarían sin una lista común, y no habría forma de saber qué está hecho y qué no. En esta wiki, las referencias tipo `T-012` apuntan a sus tarjetas.

## Cómo se conecta

| Dirección | Quién | Puerto |
|---|---|---|
| Entrada | Tu navegador | 8020, en tu ordenador, fuera de Docker |
| Salida | `claude -p --agent <rol>`, lanzado por el runner | proceso local |
| Almacén | [`board/board.json`](../../../board/board.json) | fichero |

No se conecta con el stack. Los tests de `./mk test-board` lo usan sobre una copia ([`tests/conftest.py`](../../../tests/conftest.py)).

## Cómo funciona por dentro

```mermaid
stateDiagram-v2
    backlog --> next
    next --> doing
    doing --> review
    review --> done
    doing --> human
    doing --> blocked
```

- [`board/board.py`](../../../board/board.py): la interfaz de línea de comandos. El JSON nunca se edita a mano.
- [`board/server.py`](../../../board/server.py): el panel web, con FastAPI.
- [`board/runner.py`](../../../board/runner.py): elige tarea por estado y prioridad (primero `review`, luego `next` y `backlog`) y lanza al agente. Cada carril (`think`, `measure`) trabaja en paralelo y solo hace commit de lo que cambió.
- [`.claude/agents/`](../../../.claude/agents/tester.md): las instrucciones de cada rol.
- `human` significa «solo lo puede hacer la responsable», y `blocked`, que espera otra cosa.

## Cómo está configurado aquí

Las reglas están en [`CLAUDE.md`](../../../CLAUDE.md). Los permisos de los agentes están en listas `ALLOWED` y `DENIED` en `runner.py`, más [`board/permissions.json`](../../../board/permissions.json). Por eso los agentes no pueden hacer `git commit`: los commits los hace el runner. Los comandos destructivos (`docker compose down`, `docker … prune`, `docker … rm`, `./mk down`, `gh … delete`) se deniegan siempre desde `never_rules()` en `board/permissions.py`, aunque exista un permiso amplio como `docker *`, porque la denegación manda sobre lo permitido.

El botón **▶ Iniciar** arranca los dos carriles y trabaja solo las tarjetas del panel. Si marcas «con rondas de aportación», el carril de pensar lanza además una ronda cuando se queda sin tarjetas, y esa ronda gasta cupo en ideas fuera del plan. Por API es `POST /api/runner/start?contribute=false`; sin el parámetro, las rondas se activan (T-082).

## Cómo probarlo tú mismo

Solo lectura:

```bash
python board/board.py list
```
```text
T-001  done     consultor     est 45.0 min · real    –  Fase 0: dejar el entorno levantado
T-002  done     desarrollador est 45.0 min · real    5  Infra: que el catálogo sobreviva a un reinicio de Polaris
...
```

Panel: `python board/server.py` y abre `http://localhost:8020`. Se comprobó que responde con HTTP 200 uno ya arrancado. **Sin probar:** arrancarlo desde cero, ya que requiere FastAPI y uvicorn en el Python del host. El runner no se ha ejecutado para esta wiki.

## Qué puede fallar

- **Tarjeta `done` sin su código.** Ocurre hoy: [T-011 está cerrada y sus clases no están en la rama](productor.md). Los commits del runner a veces solo contienen el cambio de estado. Cerrar una tarjeta no prueba que el código esté subido.
- **Corte por límite de uso, o agente que termina sin mensaje final.** La tarjeta vuelve a su estado sin contar como fallo y el runner para hasta la siguiente ejecución (T-013, T-084). Puede dejar ficheros a medias en la rama.
- **Pausa automática.** Tres fallos seguidos del agente (error o tiempo agotado; un `RESULT: blocked` no cuenta) pausan el carril, y el panel muestra el motivo en «En pausa: …» (T-084).
- **Un carril se apaga y no vuelve solo.** El botón arranca con `--until-idle`: un carril sin tarjetas listas sale, y las que lleguen después no lo despiertan. Las del arquitecto y el investigador esperan hasta volver a pulsar **▶ Iniciar** o hasta la ejecución programada (T-082).
- **Verificar con ficheros sin subir (T-079).** Al verificar, el runner lista en el prompt del tester los ficheros sin confirmar y le ordena fallar la tarjeta, nombrando el fichero, si el código depende de alguno. No es una copia limpia (worktree): es un aviso, sin probar con un agente real.
- **Una rama y un PR por entrega (T-191, `PR_MODE`).** Apagado por defecto: pon `PR_MODE = True` en `board/runner.py` para activarlo. Cada tarjeta se ejecuta en un worktree propio (`../lakehouse-mcp.wt/T-xxx`, rama `feature/T-xxx-slug`); al acabar, el runner empuja la rama y abre el PR a `develop` (`gh pr create`), y anota el número en la tarjeta. En cada vuelta revisa los PR: CI verde → `gh pr merge --squash --delete-branch`, borra el worktree y la tarjeta pasa a `done`; CI roja → vuelve a `next` con el log del fallo (tras `MAX_REJECTS` rechazos se bloquea); pendiente → no hace nada. El estado del panel no viaja en la rama: `ci.yml` falla si un PR toca `board/board.json`, y el runner lo guarda con commits `chore(board)` directos a `develop`. Requisitos: `gh` autenticado y *squash merge* permitido en GitHub (paso manual). **Sin probar:** el ciclo completo con un PR real, y `./mk` desde un worktree en Windows.
- **Comandos denegados.** Quedan anotados en la tarjeta.

## Operar el runner
- **Arrancar:** botón **▶ Iniciar** del panel, `run-agents.bat` / `./run-agents.sh`, o la tarea programada de `scripts/install-schedule.ps1`
  (cada 3 h, sin rondas de aportación). **■ Parar** mata al agente en curso y devuelve su tarjeta a Próximo; pausa con el botón o con `board/STOP`.
- **Ver qué hace** sin gastar tokens: «Ver en directo» en el panel, que lee `board/runs/live-*.jsonl`.
- **Carriles:** nunca hay dos del mismo carril a la vez, y cada uno confirma solo los ficheros que cambió.
- **Límites:** tiempo máximo por tarjeta de 4 × la estimación, entre 15 y 120 min; tope de turnos por rol en `MAX_TURNS` de `board/runner.py`.
- **Modelo:** el tester usa Sonnet (`model` en `.claude/agents/tester.md`); el resto, el de la sesión.

## Para saber más

- [agent-board en GitHub](https://github.com/AlbaAsca/agent-board)
- [Claude Code](https://docs.claude.com/en/docs/claude-code/overview)
