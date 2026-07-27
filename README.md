# Tello Control — App Android para DJI Tello / Tello EDU

App nativa Android (Java) para pilotar el **DJI Tello EDU** desde el móvil, con
**vídeo en directo** de la cámara y **visión por computador con OpenCV** (Python
empotrado vía Chaquopy) para seguimiento facial, gestos e identificación.

## Funciones

**Control manual**
- 🛫 Despegar / 🛬 Aterrizar
- Flechas de movimiento (tipo joystick, *pulsar y mantener*):
  - Mando izquierdo: adelante / atrás / izquierda / derecha
  - Mando derecho: subir / bajar / girar (yaw)
- Barra de velocidad (10–100 %)
- Vídeo H.264 a pantalla completa (decodificado con `MediaCodec`)
- Indicador de batería

**Visión (OpenCV, en el propio móvil)**
- 👁 **VISIÓN**: activa el análisis del vídeo (detecta y dibuja las caras)
- 🎯 **SEGUIR**: el dron mantiene tu cara centrada (gira, sube/baja, se acerca/aleja)
- ✋ **GESTOS**: gestos de la mano → comandos
  - Palma abierta (≈5 dedos) → **despegar**
  - Puño (0 dedos) → **aterrizar**
  - 2 dedos → subir · 3 dedos → bajar
- ➕ **CARA**: memoriza una cara con un nombre (identificación LBPH). Cuando esa
  persona aparece, se muestra su nombre sobre el recuadro.

## Cómo se usa

1. Enciende el Tello. En el móvil, conéctate a su WiFi **`TELLO-XXXXXX`**
   (Ajustes → WiFi). No tiene internet; es normal.
2. Abre la app **Tello Control**.
3. Pulsa **CONECTAR**. Debería aparecer el vídeo y la batería.
4. Pilota con las flechas, **DESPEGAR** / **ATERRIZAR**.
5. Para visión: pulsa **VISIÓN**, y luego **SEGUIR** o **GESTOS**.

> ⚠️ En **SEGUIR** y **GESTOS** el dron se mueve solo. Hazlo en un espacio amplio
> y ten siempre a mano **ATERRIZAR**. Los gestos requieren buena luz y fondo
> despejado (usan segmentación de piel + contornos, sin IA de manos).

## Detalles técnicos

- Protocolo oficial del Tello (SDK) por **UDP**:
  - Comandos → `192.168.10.1:8889` (`command`, `takeoff`, `land`, `rc a b c d`, `streamon`)
  - Estado (batería) ← puerto local `8890`
  - Vídeo H.264 ← puerto local `11111`
- El movimiento usa `rc` (velocidad tipo joystick) reenviado cada 100 ms, que
  además hace de *keepalive* (si no, el Tello aterriza solo a los ~15 s).
- Los sockets se **fijan a la red WiFi** del dron (`Network.bindSocket`) para que
  el tráfico no se escape por los datos móviles (clave en Android moderno).
- El vídeo se decodifica con `MediaCodec` en modo ByteBuffer → YUV→RGB → se pinta
  y, reducido a 480×360 BGR, se pasa a OpenCV cada ~100 ms.
- Python 3.8 + `opencv-contrib-python 4.5.1.48` empotrados con **Chaquopy**.

## Compilar

Requisitos: Android SDK (API 34), JDK 17+, Python 3.8–3.12 local (para Chaquopy).

```bash
./gradlew assembleDebug        # genera app/build/outputs/apk/debug/app-debug.apk
```

El `buildPython` está fijado en `app/build.gradle` a:
`C:/Users/franj/AppData/Local/Programs/Python/Python311/python.exe`
(cámbialo si tu Python está en otra ruta).

## Instalar en el móvil

1. Copia `app-debug.apk` al teléfono.
2. Permite "instalar apps de orígenes desconocidos".
3. Instálalo y ábrelo.

O por cable con ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Limitaciones conocidas

- Solo **un** controlador a la vez habla con el Tello (no uses a la vez esta app
  y otra app/PC).
- Reconocimiento de **gestos**: básico (OpenCV clásico), sensible a luz/fondo.
- **Identificación** facial: LBPH, funciona mejor con varias tomas por persona y
  buena iluminación. No se guarda entre sesiones (se re-enrola al abrir la app).
