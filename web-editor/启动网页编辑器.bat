@echo off
cd /d "%~dp0"
echo Starting ASTRA ECHO Web Beatmap Editor on http://127.0.0.1:5173 ...
start "" http://127.0.0.1:5173
node server.mjs
pause
