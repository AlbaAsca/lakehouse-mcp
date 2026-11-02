@echo off
REM Ejecución programada (la crea scripts\install-schedule.ps1): una tarjeta del panel o, si no hay, una ronda de
REM aportación. No arranca si ya hay un runner en marcha. Pausa desde el panel o creando board\STOP.
REM Log: board\runs\scheduled.log
cd /d "%~dp0"
if exist .venv\Scripts set "PATH=%~dp0.venv\Scripts;%PATH%"
set PYTHONUTF8=1
if not exist board\runs mkdir board\runs
where claude >nul 2>nul || for /f "delims=" %%i in ('dir /b /s /o:n "%APPDATA%\Claude\claude-code\claude.exe" 2^>nul') do set "CLAUDE_BIN=%%i"
echo ==== %date% %time% CLAUDE_BIN=%CLAUDE_BIN% >> board\runs\scheduled.log
python board\runner.py --once --contribute >> board\runs\scheduled.log 2>&1
