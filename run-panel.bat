@echo off
REM Panel de agentes en http://localhost:8020
cd /d "%~dp0"
if exist .venv\Scripts set "PATH=%~dp0.venv\Scripts;%PATH%"
set PYTHONUTF8=1
python -m pip install -q fastapi uvicorn
start http://localhost:8020
python board\server.py
