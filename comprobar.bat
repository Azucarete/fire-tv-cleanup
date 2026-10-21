@echo off
rem Comprueba que la tele sigue como la dejamos y repara lo que haya deshecho una actualizacion de Fire OS.
cd /d "%~dp0"
if not "%~1"=="" set TV_HOST=%~1
"%ProgramFiles%\Git\bin\bash.exe" tools/comprobar.sh
pause
