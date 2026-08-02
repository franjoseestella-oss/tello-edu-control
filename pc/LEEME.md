# Tello en el PC

Dos programas, ambos en el navegador y sin instalar servidores:

| Programa | Para qué | Dependencias |
|----------|----------|--------------|
| **`TELLO_STATION.bat`** → `station.py` | Estación completa: vídeo, visión artificial, joysticks, misiones, foto/vídeo, log | opencv-contrib-python, numpy, mediapipe |
| `TELLO_DEBUG.bat` → `tello_debug.py` | Solo depuración del enlace UDP (por si falla lo demás) | ninguna |

Los dos usan el puerto **8770**, así que solo uno a la vez.

## Estación completa

1. Conecta el **PC** al WiFi del dron `TELLO-XXXXXX` (dirá "sin Internet": normal).
2. Doble clic en **`TELLO_STATION.bat`**.
3. Se abre `http://127.0.0.1:8770` → pulsa **🔌 CONECTAR**.

Lo que hay, igual que en la APK:

- **Vídeo en directo** del dron con el overlay de visión dibujado encima.
- **👁 Caras** — detección múltiple + nombre de las caras memorizadas (LBPH).
- **🎯 Seguir** — el dron te sigue con control PID suave (`rc`, no saltos).
- **🟢 Color** — sigue un objeto por color (verde, azul, rojo, amarillo, naranja).
- **🦴 Esqueleto** — te sigue el **cuerpo entero** (33 puntos, MediaPipe). Aguanta
  aunque te des la vuelta o bajes la cara, y mide la distancia por el ancho de
  hombros, que es mucho más estable que el tamaño de la cara.
- **🖐 Dedos** — esqueleto de la mano (21 puntos) y órdenes por postura:

  | Gesto | Orden |
  |-------|-------|
  | 🖐 palma abierta | despegar |
  | ✊ puño | aterrizar |
  | ☝ un dedo | subir |
  | ✌ dos dedos | bajar |
  | 🤟 tres dedos | foto |
  | 👈 / 👉 pulgar a un lado | izquierda / derecha |

  Y con los brazos (modo esqueleto): 🙌 los dos arriba = despegar · 🧍 pegados al
  cuerpo = aterrizar · 🙋 uno en cruz = a ese lado · 🅃 los dos en cruz = quieto.

  Ningún gesto dispara con un fotograma suelto: hay que mantenerlo 4 seguidos.
- **✋ Gestos (clásico)** — el método por color de piel, sin MediaPipe: mano abierta =
  despegar, puño = aterrizar, 2 dedos = subir, 3 = bajar.
- **🔳 QR** — lee códigos QR y los muestra en pantalla.
- **😀 Selfie** — dispara la foto solo cuando detecta una sonrisa.
- **➕ Memorizar cara** — le pones nombre a una cara y la reconoce a partir de entonces.
- **🎤 Voz** — «despega», «aterriza», «sube», «gira derecha», «foto», «quieto»…
- **🗺 Misión** — waypoints encadenados, con el bucle `rc` suspendido mientras corren.
- **📷 / ⏺** — foto y grabación a `pc/media/`.
- **🐞** — el log completo del protocolo y el botón de diagnóstico.

Mandos: **joysticks táctiles** (izq: subir/girar, der: desplazar), **teclado**
(`W A S D`, flechas, `T` despegar, `L` aterrizar, `espacio` parar) y **mando de
juegos** por USB/Bluetooth (A despegar, B aterrizar, X foto, Y grabar, L1/R1 flip,
START emergencia).

### 💻 Webcam: probar sin volar

El botón **💻 Webcam** cambia la fuente de vídeo a la cámara del PC. Toda la visión
(caras, nombres, gestos, QR, sonrisa, el PID dibujado) funciona igual, pero sin
dron. Ideal para ajustar y memorizar caras antes de volar. Idea tomada del modo
`is_dummy` de [juanmapf97/Tello-Face-Recognition](https://github.com/juanmapf97/Tello-Face-Recognition),
igual que el detector LBP rápido (`cascades/`), el área del rostro como medida de
distancia y los offsets `[dx, dy, área]` que se ven en pantalla.

## Consola de depuración

Ver el apartado "Qué registra" más abajo: TX/RX con latencia, errores del dron
traducidos, timeouts, IPs y pérdida de paquetes. El log se guarda siempre en
`pc/logs/tello_debug_AAAAMMDD_HHMMSS.log` (también desde la estación, botón 🐞).

| Marca | Significado |
|-------|-------------|
| `TX` / `RX` | paquete enviado / respuesta con latencia en ms |
| `ERR` + `HINT` | el dron rechazó el comando, y la causa probable |
| `TIMEOUT` | enviado sin respuesta |
| `NET` | IPs del PC y ruta hacia el dron |
| `RC` | cambios del joystick (los keepalive se resumen) |
| `ORDEN` / `MISION` / `VISION` / `GESTO` | de dónde salió cada orden |

## Si algo no va

- **`WARN ... tu IP hacia el dron es ...`** → no estás en el WiFi del Tello.
- **Firewall de Windows**: permite `python.exe` en redes privadas o no llegan
  las respuestas UDP ni el vídeo.
- **La APK no puede estar conectada a la vez**: el Tello solo admite un cliente.
- **Sin vídeo**: el dron solo emite tras `streamon`, que se manda al CONECTAR.
- **La voz necesita internet** (el reconocimiento de Chrome es en la nube), así que
  en el WiFi del dron no funciona; el resto sí.

## Ficheros

```
station.py      estacion completa (servidor + interfaz web)
vision.py       motor de vision (mismo cerebro que la APK)
skeleton.py     esqueleto del cuerpo y de las manos (MediaPipe Tasks)
video.py        captura de video del dron o de la webcam
tello_debug.py  enlace UDP + log + consola minima
cascades/       detector de caras LBP rapido
models/         modelos .task de esqueleto y manos (se bajan solos)
data/           caras memorizadas (faces.yml, names.txt)
media/          fotos y grabaciones
logs/           registros
```

Los modelos de esqueleto (13 MB) se descargan solos la primera vez que pulsas
🦴 o 🖐, así que **hazlo con internet antes de irte a volar**. Una vez bajados
quedan en `pc/models/` y ya no hacen falta más.
