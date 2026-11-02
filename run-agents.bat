@echo off
REM Agentes autónomos: trabajan las tarjetas del panel una tras otra. Pausa desde el panel o creando board\STOP.
REM Argumentos útiles: --until-idle (se para cuando no quedan), --lane think|measure (dos carriles en paralelo), --contribute
cd /d "%~dp0"
if exist .venv\Scripts set "PATH=%~dp0.venv\Scripts;%PATH%"
set PYTHONUTF8=1
REM Sin CLI en el PATH: usa la que trae la app de escritorio (la versión más reciente)
where claude >nul 2>nul || for /f "delims=" %%i in ('dir /b /s /o:n "%APPDATA%\Claude\claude-code\claude.exe" 2^>nul') do set "CLAUDE_BIN=%%i"
if not defined CLAUDE_BIN where claude >nul 2>nul || (
  echo [!] Claude Code no esta instalado. En PowerShell: irm https://claude.ai/install.ps1 ^| iex
  echo     y despues "claude" una vez para iniciar sesion.
  pause & exit /b 1
)
where git >nul 2>nul || echo [i] Sin git: no habra historial para deshacer cambios de los agentes.
python board\runner.py %*
pause
