@echo off
title Tello Debug Station
cd /d "%~dp0"
echo.
echo   ================================================
echo    TELLO DEBUG STATION
echo    1) Conecta el PC al WiFi del dron (TELLO-XXXXXX)
echo    2) Se abrira solo http://127.0.0.1:8770
echo    3) Pulsa CONECTAR y luego DIAGNOSTICO
echo    El log queda en la carpeta pc\logs\
echo   ================================================
echo.
where py >nul 2>&1 && (py tello_debug.py %*) || (python tello_debug.py %*)
echo.
pause
