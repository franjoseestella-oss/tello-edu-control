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

**Visión (en el propio móvil, sin internet)**
- 👁 **CARAS**: detecta y dibuja las caras (OpenCV / Haar)
- 🎯 **SEGUIR**: el dron mantiene el objetivo centrado (gira, sube/baja, se acerca/aleja)
- 🟢 **COLOR**: sigue un objeto por su color (mantén pulsado para cambiarlo)
- 🦴 **CUERPO**: esqueleto de 33 puntos con **MediaPipe**. Se dibuja encima del
  vídeo y, combinado con 🎯 SEGUIR, el dron persigue a la persona entera — sigue
  funcionando aunque te des la vuelta, al revés que la cara. La distancia se mide
  por el ancho de hombros, más estable que el alto de la caja.
- ✋ **GESTOS**: con MediaPipe se detecta la mano (21 puntos) y se cuentan los
  dedos de verdad. Un gesto tiene que mantenerse 4 fotogramas para que cuente:
  - 🖐 palma → **despegar** · ✊ puño → **aterrizar**
  - ☝ 1 dedo → subir · ✌ 2 dedos → bajar · 🤟 3 dedos → **foto**
  - 👈 / 👉 pulgar a un lado → izquierda / derecha
  - Con los brazos: 🙌 arriba → despegar · 🧍 abajo → aterrizar · brazo en cruz → lateral
- ➕ **CARA**: memoriza una cara con un nombre (identificación LBPH). Cuando esa
  persona aparece, se muestra su nombre sobre el recuadro.

Los modelos `.task` de MediaPipe (13 MB) no están en git: los descarga Gradle
antes de compilar y quedan dentro de la APK, así que el móvil no necesita
internet al volar.

## Cómo se usa

1. Enciende el Tello. En el móvil, conéctate a su WiFi **`TELLO-XXXXXX`**
   (Ajustes → WiFi). No tiene internet; es normal.
2. Abre la app **Tello Control**.
3. Pulsa **CONECTAR**. Debería aparecer el vídeo y la batería.
4. Pilota con las flechas, **DESPEGAR** / **ATERRIZAR**.
5. Para visión: pulsa **CARAS**, **CUERPO** o **GESTOS**; con 🎯 **SEGUIR** el
   dron persigue lo que haya activo (el cuerpo si 🦴 está encendido, si no la cara).

> ⚠️ En **SEGUIR** y **GESTOS** el dron se mueve solo. Hazlo en un espacio amplio
> y ten siempre a mano **ATERRIZAR**.

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
