# Una rama y un PR corto por entrega (T-191)

Estado: **implementado en `board/runner.py` y `ci.yml`, apagado por defecto** (`PR_MODE = False`; apagado = el runner
sube commits a `develop` con `save()`). **Sin probar de punta a punta con un agente real y un PR real.** Cómo se usa:
[agent-board](wiki/componentes/agent-board.md).

## Problema
El runner hace `git push` de cada entrega directamente a `develop`. Un fallo llega a `develop` antes de que nadie lo
revise, la CI se entera después (`ci_is_red`) y el trabajo en paralelo de los dos carriles comparte un solo árbol de
trabajo, con todo el mecanismo de `claims` para no mezclar ficheros (origen de T-100, T-102, T-109, T-128, T-129).

## Decisiones

1. **Una entrega = una rama `feature/T-xxx-slug` = un PR a `develop`.** «Entrega» es lo que T-189 agrupa en una
   tarjeta. Nombre en inglés, Git Flow. Commits Conventional Commits como hasta ahora.
2. **Cada tarjeta trabaja en su propio `git worktree`**, fuera del repo (`../lakehouse-mcp.wt/T-xxx`, ruta corta por
   Windows). El agente se lanza con ese `cwd`. Dos carriles ya no comparten ficheros, así que `claims`,
   `snapshot()` y `commit_own()` se reducen a `git add -A` + `commit` dentro del worktree.
3. **El estado de las tarjetas no viaja en las ramas.** `board/board.json` solo lo escribe el runner, en el
   checkout principal (`develop`), con commits `chore(board): …` directos a `develop`. Para ello:
   - `board.py` lee la ruta de `LAKEHOUSE_BOARD_DIR` (que el runner pone al lanzar al agente) y, si no existe,
     usa la de siempre. Así `python board/board.py …` dentro de un worktree toca el panel real, no su copia vieja.
   - Una comprobación de CI en los PR falla si el diff toca `board/board.json`: el conflicto se evita por
     construcción, no resolviéndolo después.
   - Antes de empujar un commit de panel el runner hace `git pull --rebase`; solo toca `board/` y
     `docs/handoff.md`, que `paths-ignore` ya excluye de la CI.
4. **La CI es el revisor.** El PR a `develop` pasa la CI rápida (compilar + unitarios, `-DskipITs`). La suite
   completa corre solo en el PR `develop → main` (release), el push a `main` y la noche: ya está así en `ci.yml`
   desde T-188; no cambia.
5. **Quien fusiona es el runner, no GitHub.** Un repo privado sin plan de pago no admite reglas de protección ni
   auto-merge. Tras acabar el agente: el runner empuja la rama, abre el PR (`gh pr create`) y anota el número en la
   tarjeta (`pr`). En cada vuelta del bucle, antes de coger trabajo, revisa los PR abiertos:
   - CI verde → `gh pr merge --squash --delete-branch`, borra el worktree, tarjeta a `done`.
   - CI roja → la tarjeta vuelve a `next` con el motivo (log del job) en el historial; cuenta para `MAX_REJECTS`
     (T-192) y a la tercera se bloquea.
   - CI pendiente → nada; el carril sigue con otra tarjeta.
   Una tarjeta solo es `done` cuando está fusionada, así que `deps_done` garantiza que una tarjeta dependiente
   parte de un `develop` que ya contiene la anterior. Se hace *squash* para que `develop` tenga un commit por entrega.
6. **El tester verifica el worktree/rama del PR**, no el árbol principal (sustituye a la «copia limpia» de T-079).
7. **Interruptor de vuelta atrás:** `PR_MODE` en `board.runner` (por defecto apagado hasta que la pasada
   completa funcione); apagado = flujo actual.

## Alternativas descartadas
- *Guardar `board.json` en una rama aparte (`board-state`) u orfana:* limpia, pero el panel y los tests leen el
  fichero del árbol; obliga a un segundo checkout. Más piezas por el mismo resultado que (3).
- *Dejar `board.json` fuera de git:* pierde historial y copia de seguridad del panel.
- *Resolver conflictos de `board.json` al fusionar (driver de merge):* esconde el problema y sigue habiendo
  escrituras concurrentes sobre un JSON.
- *Auto-merge de GitHub / protección de rama:* no disponible sin Pro (ver CLAUDE.md global, CI/CD).
- *Un PR por tarjeta pequeña:* la CI y el overhead dominan; por eso depende de T-189.
- *Un solo árbol y PR al final de la tanda:* mantiene los problemas de `claims`.

## Riesgos
- `./mk` monta el directorio actual en `tools`; hay que comprobar que funciona desde un worktree (Windows, Docker
  inestable) y que la caché de Maven se comparte. Primera tarjeta lo verifica.
- Espacio y tiempo de crear un worktree por tarjeta: despreciable frente a la espera de tests.
- La CI tarda minutos por PR: es la espera que hoy ya existe tras cada push, pero ahora no bloquea al carril.
- Un PR cerrado a mano o una rama borrada: el runner trata «PR inexistente» como tarjeta a `next`.

## Tarjetas
1. Worktree por tarjeta y panel compartido (`board.py` + `runner.py`).
2. Empujar rama, abrir PR, fusionar con CI verde o devolver con el motivo (`runner.py`).
3. `ci.yml`: comprobación que impide tocar `board/board.json` en un PR; ajustes de `concurrency`.
4. Documentar en CLAUDE.md (Ejecución autónoma) y wiki, cuando 1–3 estén en marcha.
5. [Tú] Ajustes en GitHub: permitir *squash merge* y borrar ramas fusionadas; comprobar que `gh` puede abrir PR.
