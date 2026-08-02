# -*- coding: utf-8 -*-
"""
Vídeo del Tello en el PC.

El dron emite H.264 por UDP al puerto 11111 en cuanto recibe 'streamon'.
OpenCV trae ffmpeg incrustado, así que no hace falta instalar nada aparte.

    captura (hilo)  ->  fotograma crudo
                        |-> hilo de visión (analiza el ultimo, descarta el resto)
                        |-> overlay + JPEG  ->  MJPEG para el navegador
                                            ->  grabacion a .mp4 / foto .jpg
"""

import os
import threading
import time

# Menos buffer = menos retardo. Debe fijarse antes de crear el VideoCapture.
# 'timeout' (microsegundos) evita que una lectura se quede colgada esperando
# paquetes que no llegan: sin esto, cambiar de fuente tardaba hasta 30 s.
os.environ.setdefault("OPENCV_FFMPEG_CAPTURE_OPTIONS",
                      "fflags;nobuffer|flags;low_delay|reorder_queue_size;0|timeout;3000000")

import cv2                                    # noqa: E402
import numpy as np                            # noqa: E402

VIDEO_URL = "udp://0.0.0.0:11111"
MEDIA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "media")


class VideoStream:
    def __init__(self, vision=None, log=None):
        self.vision = vision
        self.log = log or (lambda k, m: None)

        self.running = False
        self.connected = False
        self.frame_w = 0
        self.frame_h = 0
        self.fps = 0.0
        self.frames = 0

        self._raw = None                     # ultimo fotograma sin anotar
        self._jpeg = None                    # ultimo JPEG anotado
        self._seq = 0
        self._cond = threading.Condition()
        self._lock = threading.Lock()

        self._writer = None
        self._rec_path = None
        self.quality = 70

        # "drone" = vídeo UDP del Tello | "webcam" = cámara del PC, para probar
        # toda la visión sin volar (idea del proyecto Tello-Face-Recognition).
        self.source = "drone"
        self._reopen = False

    # ------------------------------------------------------------------

    def start(self):
        if self.running:
            return
        self.running = True
        threading.Thread(target=self._capture_loop, name="video", daemon=True).start()
        threading.Thread(target=self._vision_loop, name="vision", daemon=True).start()
        self.log("VIDEO", "esperando el vídeo del dron en %s" % VIDEO_URL)

    def stop(self):
        self.running = False
        self.stop_recording()

    def set_source(self, kind):
        """'drone' (UDP 11111) o 'webcam' (cámara del PC)."""
        kind = "webcam" if kind == "webcam" else "drone"
        if kind != self.source:
            self.source = kind
            self._reopen = True
            self.log("VIDEO", "fuente de vídeo -> %s (unos segundos hasta que "
                              "el flujo anterior se suelta)" % kind)
        return self.source

    # ------------------------------------------------------------------

    def _capture_loop(self):
        cap = None
        last_ok = 0.0
        t_prev = 0.0
        while self.running:
            if self._reopen and cap is not None:
                self._reopen = False
                try:
                    cap.release()
                except Exception:
                    pass
                cap = None
            if cap is None or not cap.isOpened():
                self.connected = False
                webcam = self.source == "webcam"
                self.log("VIDEO", "abriendo %s..." % ("la cámara del PC" if webcam else "el vídeo del dron"))
                try:
                    if webcam:
                        cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
                    else:
                        cap = cv2.VideoCapture(VIDEO_URL, cv2.CAP_FFMPEG)
                    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
                except Exception as e:
                    self.log("VIDEO", "no se pudo abrir: %s" % e)
                    cap = None
                if cap is None or not cap.isOpened():
                    self._publish_placeholder("SIN VIDEO - camara del PC" if webcam
                                              else "SIN VIDEO - pulsa CONECTAR (streamon)")
                    time.sleep(1.5)
                    continue
                self.log("VIDEO", "vídeo abierto (%s)" % self.source)

            ok, frame = cap.read()
            if not ok or frame is None:
                if time.time() - last_ok > 5 and last_ok:
                    self.log("VIDEO", "sin fotogramas 5 s: reabriendo el flujo")
                    try:
                        cap.release()
                    except Exception:
                        pass
                    cap = None
                    last_ok = 0
                self._publish_placeholder("SIN SENAL DE VIDEO")
                time.sleep(0.05)
                continue

            last_ok = time.time()
            self.connected = True
            self.frames += 1
            self.frame_h, self.frame_w = frame.shape[:2]
            with self._lock:
                self._raw = frame

            now = time.time()
            if t_prev:
                dt = now - t_prev
                if dt > 0:
                    self.fps = 0.9 * self.fps + 0.1 * (1.0 / dt)
            t_prev = now

            shown = frame.copy()
            if self.vision is not None:
                try:
                    self.vision.draw(shown)
                except Exception as e:
                    self.log("VIDEO", "overlay: %s" % e)
            self._record(shown)
            self._publish(shown)

        if cap is not None:
            try:
                cap.release()
            except Exception:
                pass

    def _vision_loop(self):
        """Analiza siempre el fotograma más reciente y descarta los atrasados."""
        while self.running:
            v = self.vision
            if v is None or not v.active():
                time.sleep(0.08)
                continue
            with self._lock:
                frame = None if self._raw is None else self._raw.copy()
            if frame is None:
                time.sleep(0.05)
                continue
            try:
                v.process(frame)
            except Exception as e:
                self.log("VISION", "error: %s" % e)
                time.sleep(0.2)

    # ------------------------------------------------------------------

    def _publish(self, frame):
        ok, buf = cv2.imencode(".jpg", frame,
                               [int(cv2.IMWRITE_JPEG_QUALITY), self.quality])
        if not ok:
            return
        with self._cond:
            self._jpeg = buf.tobytes()
            self._seq += 1
            self._cond.notify_all()

    def _publish_placeholder(self, text):
        # sin vídeo no hace falta gastar CPU: un fotograma por segundo basta
        now = time.time()
        if now - getattr(self, "_ph_t", 0) < 1.0:
            return
        self._ph_t = now
        img = np.zeros((360, 640, 3), np.uint8)
        img[:] = (18, 22, 30)
        cv2.putText(img, text, (28, 190), cv2.FONT_HERSHEY_SIMPLEX,
                    0.75, (120, 140, 170), 2, cv2.LINE_AA)
        self._publish(img)

    def latest_jpeg(self, last_seq=0, timeout=3.0):
        """Espera a que haya un fotograma nuevo. Devuelve (jpeg, seq)."""
        with self._cond:
            if self._seq == last_seq:
                self._cond.wait(timeout)
            return self._jpeg, self._seq

    def snapshot_jpeg(self):
        with self._cond:
            return self._jpeg

    # ------------------------------------------------------------------
    # Foto y grabación
    # ------------------------------------------------------------------

    def take_photo(self):
        with self._lock:
            frame = None if self._raw is None else self._raw.copy()
        if frame is None:
            return None
        if self.vision is not None:
            try:
                self.vision.draw(frame)
            except Exception:
                pass
        os.makedirs(MEDIA_DIR, exist_ok=True)
        path = os.path.join(MEDIA_DIR, "foto_%s.jpg" % time.strftime("%Y%m%d_%H%M%S"))
        cv2.imwrite(path, frame)
        self.log("MEDIA", "foto guardada: %s" % path)
        return path

    def is_recording(self):
        return self._writer is not None

    def start_recording(self):
        if self._writer is not None:
            return self._rec_path
        if not self.connected or not self.frame_w:
            self.log("MEDIA", "no puedo grabar: no hay vídeo")
            return None
        os.makedirs(MEDIA_DIR, exist_ok=True)
        path = os.path.join(MEDIA_DIR, "vuelo_%s.mp4" % time.strftime("%Y%m%d_%H%M%S"))
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        fps = self.fps if 5 < self.fps < 60 else 25.0
        w = cv2.VideoWriter(path, fourcc, fps, (self.frame_w, self.frame_h))
        if not w.isOpened():
            self.log("MEDIA", "este equipo no puede escribir mp4")
            return None
        self._writer, self._rec_path = w, path
        self.log("MEDIA", "grabando en %s (%.0f fps)" % (path, fps))
        return path

    def stop_recording(self):
        w, path = self._writer, self._rec_path
        self._writer, self._rec_path = None, None
        if w is not None:
            try:
                w.release()
            except Exception:
                pass
            self.log("MEDIA", "grabación guardada: %s" % path)
        return path

    def _record(self, frame):
        w = self._writer
        if w is None:
            return
        try:
            if frame.shape[1] == self.frame_w and frame.shape[0] == self.frame_h:
                w.write(frame)
        except Exception as e:
            self.log("MEDIA", "error grabando: %s" % e)
            self.stop_recording()
