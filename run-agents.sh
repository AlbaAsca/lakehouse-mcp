#!/usr/bin/env bash
# Agentes autónomos: ejecutan las tareas del panel. Pausa desde el panel o con: touch board/STOP
cd "$(dirname "$0")"
command -v claude >/dev/null || { echo "Instala Claude Code: curl -fsSL https://claude.ai/install.sh | bash"; exit 1; }
python3 board/runner.py "$@"
