@echo off
rem Reinstala el launcher en la tele: doble clic, o "instalar.bat 192.168.0.27" para otra IP.
cd /d "%~dp0"
if not "%~1"=="" set TV_HOST=%~1
"%ProgramFiles%\Git\bin\bash.exe" tools/instalar.sh
pause
