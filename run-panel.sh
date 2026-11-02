#!/usr/bin/env bash
# Panel de agentes en http://localhost:8020
cd "$(dirname "$0")"
python3 -m pip install -q fastapi uvicorn
python3 board/server.py
