@echo off
pushd "%~dp0"
docker compose run --rm tools make %*
set "RC=%ERRORLEVEL%"
popd
exit /b %RC%
