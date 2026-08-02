@echo off
title Tello Station
cd /d "%~dp0"
echo.
echo   ==========================================================
echo    TELLO STATION - estacion de control completa
echo    1) Conecta el PC al WiFi del dron (TELLO-XXXXXX)
echo    2) Se abrira solo http://127.0.0.1:8770
echo    3) Pulsa CONECTAR (activa video, telemetria y joysticks)
echo.
echo    Sin dron: pulsa "Webcam" para probar toda la vision
echo    con la camara del PC.
echo   ==========================================================
echo.
where py >nul 2>&1 && (py station.py %*) || (python station.py %*)
echo.
pause
