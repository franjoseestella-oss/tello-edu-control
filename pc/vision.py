# -*- coding: utf-8 -*-
"""
Motor de vision por computador de la estacion de PC.

Es el mismo cerebro que lleva la APK (app/src/main/python/tello_vision.py),
adaptado para trabajar directamente sobre fotogramas BGR de OpenCV y para
dibujar el overlay que se ve en el navegador.

Modos:  0=off  1=detectar caras  2=seguir cara (PID)  3=seguir color (PID)
Extras: identificacion de caras (LBPH), gestos de mano, lectura de QR.
"""

import os
import threading
import time

import cv2
import numpy as np

from skeleton import SkeletonEngine

MODE_OFF, MODE_DETECT, MODE_FOLLOW, MODE_COLOR, MODE_SKELETON = 0, 1, 2, 3, 4
MODE_NAMES = {0: "OFF", 1: "CARAS", 2: "SEGUIR", 3: "COLOR", 4: "ESQUELETO"}

DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")

# Rangos HSV (H: 0-179 en OpenCV), iguales que en la APK
COLORS = {
    "verde":    [((40, 70, 70), (80, 255, 255))],
    "azul":     [((100, 130, 60), (130, 255, 255))],
    "rojo":     [((0, 120, 70), (10, 255, 255)), ((170, 120, 70), (179, 255, 255))],
    "amarillo": [((20, 100, 100), (35, 255, 255))],
    "naranja":  [((10, 120, 120), (20, 255, 255))],
}

_ID_THRESHOLD = 78.0

# Encuadre: el objetivo se centra un poco por encima del centro, para que el
# dron coja también el cuerpo del sujeto (idea de juanmapf97/Tello-Face-Recognition).
FRAME_OFFSET_Y = 0.08

# Ancho de hombros que el dron intenta mantener, en fracción del ancho de la
# imagen. ~0.16 deja a la persona entera en cuadro a unos 2-2,5 m.
BODY_TARGET_W = 0.16

# Área del rostro (px²) que se considera "distancia buena". Fuera de la banda,
# el dron se acerca o se aleja. También sacado del proyecto de referencia.
AREA_MIN, AREA_MAX = 15000, 30000

# Detector rápido LBP (del repo de referencia); si falta, se usa el Haar de OpenCV.
LBP_CASCADE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           "cascades", "lbpcascade_frontalface_improved.xml")

# Colores de dibujo (BGR)
C_MAIN = (0, 230, 255)     # cara principal / objetivo
C_FACE = (0, 200, 120)     # otras caras
C_QR = (255, 120, 0)
C_HUD = (230, 240, 255)


class VisionEngine:
    """Analiza fotogramas y produce detecciones + mando (rc) para seguimiento."""

    def __init__(self, log=None):
        self.log = log or (lambda k, m: None)

        self.mode = MODE_OFF
        self.gesture_enabled = False
        self.qr_enabled = True
        self.color = "verde"

        # Ultimo resultado (lo dibuja el hilo de vídeo en cada fotograma)
        self.faces = []          # [(x, y, w, h, label, is_main)]
        self.gesture = ""
        self.qr = None           # (pts, texto)
        self.rc = (0, 0, 0, 0)
        self.fps = 0.0
        self.status = ""
        self.last_ms = 0.0
        self.offsets = (0, 0, 0)   # (dx, dy, area) del objetivo respecto al centro

        # Selfie: dispara foto sola cuando detecta una sonrisa
        self.selfie = False
        self.smiling = False
        self.on_photo = None       # callback que hace la foto
        self._last_selfie = 0.0

        # Esqueleto (MediaPipe): seguimiento del cuerpo y gestos con los dedos
        self.skeleton = SkeletonEngine(log=self.log)
        self.pending_action = ""   # orden que la estación debe ejecutar

        self._pid = {"yaw": [0.0, 0.0], "ud": [0.0, 0.0], "fb": [0.0, 0.0]}
        self._lock = threading.Lock()
        self._pending_enroll = None
        self._t_last = 0.0

        # LBP es ~3x más rápido que Haar en CPU y aquí procesamos 720p en directo.
        self.detector = "lbp" if os.path.exists(LBP_CASCADE) else "haar"
        self.face_cascade = cv2.CascadeClassifier(
            LBP_CASCADE if self.detector == "lbp"
            else cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
        if self.face_cascade.empty():
            self.detector = "haar"
            self.face_cascade = cv2.CascadeClassifier(
                cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
        self.smile_cascade = cv2.CascadeClassifier(
            cv2.data.haarcascades + "haarcascade_smile.xml")
        try:
            self.recognizer = cv2.face.LBPHFaceRecognizer_create()
            self.has_face_module = True
        except Exception:
            self.recognizer = None
            self.has_face_module = False
        self.qr_detector = cv2.QRCodeDetector()

        self._samples, self._sample_ids = [], []
        self._id_to_name, self._name_to_id = {}, {}
        self._trained = False
        self.load_model()

    # ------------------------------------------------------------------
    # Configuración
    # ------------------------------------------------------------------

    def set_mode(self, m):
        self.mode = int(m)
        self._pid_reset()
        if not self.controls_drone():
            self.rc = (0, 0, 0, 0)
        # el esqueleto solo se carga si hace falta (tarda ~1 s en arrancar)
        self.skeleton.pose_on = (self.mode == MODE_SKELETON)
        if self.skeleton.pose_on and not self.skeleton.ready:
            self.skeleton.start()
        self.log("VISION", "modo -> %s" % MODE_NAMES.get(self.mode, self.mode))

    def set_hands(self, on):
        """Gestos con los dedos por esqueleto de la mano (MediaPipe)."""
        on = bool(on)
        if on and not self.skeleton.ready and not self.skeleton.start():
            self.log("WARN", "no se pudo activar el gesto por dedos: %s" % self.skeleton.error)
            return False
        self.skeleton.hands_on = on
        self.log("VISION", "gestos con los dedos %s" % ("ON" if on else "OFF"))
        return True

    def set_color(self, name):
        if name in COLORS:
            self.color = name
            self.log("VISION", "color a seguir -> %s" % name)
        return self.color

    def set_gesture(self, on):
        self.gesture_enabled = bool(on)
        self.log("VISION", "gestos %s" % ("ON" if on else "OFF"))

    def set_qr(self, on):
        self.qr_enabled = bool(on)

    def enroll_next(self, name):
        self._pending_enroll = name
        self.log("VISION", "memorizar la siguiente cara como '%s'" % name)

    def active(self):
        return (self.mode != MODE_OFF or self.gesture_enabled
                or self.qr_enabled or self.selfie or self.skeleton.active())

    def controls_drone(self):
        return self.mode in (MODE_FOLLOW, MODE_COLOR, MODE_SKELETON)

    def take_action(self):
        """Devuelve (y consume) la orden pendiente de un gesto."""
        a, self.pending_action = self.pending_action, ""
        return a

    def known_names(self):
        return sorted(self._name_to_id.keys())

    # ------------------------------------------------------------------
    # Proceso de un fotograma
    # ------------------------------------------------------------------

    def process(self, frame):
        """Analiza un fotograma BGR y actualiza el estado. Devuelve (lr, fb, ud, yaw)."""
        t0 = time.time()
        h, w = frame.shape[:2]
        faces, ctrl, gesture, qr = [], (0, 0, 0, 0), "", None

        try:
            if self._pending_enroll:
                name, self._pending_enroll = self._pending_enroll, None
                self.status = self._enroll(name, frame)

            # Esqueleto del cuerpo y de la mano (MediaPipe)
            if self.skeleton.active():
                action = self.skeleton.process(frame)
                if action:
                    self.pending_action = action
                if self.mode == MODE_SKELETON:
                    box = self.skeleton.target_box()
                    if box is not None:
                        bx, by, bw, bh = box
                        faces.append((bx, by, bw, bh, "cuerpo", True))
                        self._update_offsets(bx, by, bw, bh, w, h)
                        ctrl = self._follow_body(bx, by, bw, bh, w, h)
                    else:
                        self._pid_reset()
                        self.offsets = (0, 0, 0)

            if self.mode in (MODE_DETECT, MODE_FOLLOW) or self.selfie:
                gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
                gray = cv2.equalizeHist(gray)
                det = self.face_cascade.detectMultiScale(gray, 1.2, 5, minSize=(30, 30))
                if len(det) > 0:
                    fx, fy, fw, fh = max(det, key=lambda f: f[2] * f[3])
                    name = self._identify(gray[fy:fy + fh, fx:fx + fw])
                    for (x, y, ww, hh) in det:
                        main = (x == fx and y == fy and ww == fw and hh == fh)
                        faces.append((int(x), int(y), int(ww), int(hh),
                                      name if main else "", bool(main)))
                    self._update_offsets(fx, fy, fw, fh, w, h)
                    if self.selfie:
                        self._check_smile(gray[fy:fy + fh, fx:fx + fw])
                    if self.mode == MODE_FOLLOW:
                        ctrl = self._follow_pid(fx, fy, fw, fh, w, h)
                else:
                    self._pid_reset()
                    self.offsets = (0, 0, 0)
                    self.smiling = False

            elif self.mode == MODE_COLOR:
                box = self._detect_color(frame)
                if box is not None:
                    bx, by, bw, bh = box
                    faces.append((int(bx), int(by), int(bw), int(bh), self.color, True))
                    self._update_offsets(bx, by, bw, bh, w, h)
                    ctrl = self._follow_pid(bx, by, bw, bh, w, h)
                else:
                    self._pid_reset()
                    self.offsets = (0, 0, 0)

            if self.gesture_enabled:
                gesture = self._detect_gesture(frame)
            if self.qr_enabled:
                qr = self._detect_qr(frame)

        except Exception as e:
            self.status = "error de vision: %s" % e

        with self._lock:
            self.faces, self.gesture, self.qr, self.rc = faces, gesture, qr, ctrl
        self.last_ms = (time.time() - t0) * 1000
        now = time.time()
        if self._t_last:
            dt = now - self._t_last
            if dt > 0:
                self.fps = 0.8 * self.fps + 0.2 * (1.0 / dt)
        self._t_last = now
        return ctrl

    # ------------------------------------------------------------------
    # Overlay
    # ------------------------------------------------------------------

    def draw(self, frame):
        """Dibuja las detecciones sobre el fotograma (in-place)."""
        with self._lock:
            faces, gesture, qr, rc = list(self.faces), self.gesture, self.qr, self.rc
        h, w = frame.shape[:2]
        dx, dy, area = self.offsets

        for (x, y, ww, hh, label, main) in faces:
            col = C_MAIN if main else C_FACE
            cv2.rectangle(frame, (x, y), (x + ww, y + hh), col, 2 if main else 1)
            if main:
                # esquinas tipo visor
                L = max(12, ww // 5)
                for (px, py, dx, dy) in ((x, y, 1, 1), (x + ww, y, -1, 1),
                                         (x, y + hh, 1, -1), (x + ww, y + hh, -1, -1)):
                    cv2.line(frame, (px, py), (px + dx * L, py), col, 3)
                    cv2.line(frame, (px, py), (px, py + dy * L), col, 3)
            if main:
                cv2.circle(frame, (x + ww // 2, y + hh // 2), 5, (0, 0, 255), -1)
            if label:
                cv2.rectangle(frame, (x, y - 20), (x + max(60, 9 * len(label)), y), col, -1)
                cv2.putText(frame, label, (x + 4, y - 6),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.5, (20, 20, 20), 1, cv2.LINE_AA)

        if qr:
            pts, text = qr
            cv2.polylines(frame, [np.array(pts, np.int32)], True, C_QR, 2)
            if text:
                cv2.putText(frame, text[:40], (pts[0][0], max(18, pts[0][1] - 8)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.6, C_QR, 2, cv2.LINE_AA)

        if self.mode != MODE_OFF:
            # centro del encuadre (el punto al que el dron lleva el objetivo)
            cx, cy = w // 2, int(h // 2 + h * FRAME_OFFSET_Y)
            cv2.circle(frame, (cx, cy), 10, (0, 255, 0), 1)
            cv2.line(frame, (cx - 18, cy), (cx + 18, cy), C_HUD, 1)
            cv2.line(frame, (cx, cy - 18), (cx, cy + 18), C_HUD, 1)
            if area:
                dist = ("ACERCARSE" if area < AREA_MIN
                        else "ALEJARSE" if area > AREA_MAX else "DISTANCIA OK")
                cv2.putText(frame, "[%d, %d, %d] %s" % (dx, dy, area, dist),
                            (12, 58), cv2.FONT_HERSHEY_SIMPLEX, 0.55,
                            (120, 255, 120) if dist == "DISTANCIA OK" else (120, 200, 255),
                            2, cv2.LINE_AA)

        if self.controls_drone():
            cx, cy = w // 2, h // 2
            lr, fb, ud, yaw = rc
            cv2.arrowedLine(frame, (cx, cy), (cx + yaw, cy - ud), (0, 200, 255), 2, tipLength=0.3)
            cv2.putText(frame, "AUTO %s  rc %d %d %d %d" % (MODE_NAMES[self.mode], lr, fb, ud, yaw),
                        (12, h - 14), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 200, 255), 2, cv2.LINE_AA)

        if self.skeleton.active():
            self.skeleton.draw(frame)

        if gesture:
            cv2.putText(frame, "GESTO: " + gesture.upper(), (12, 30),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 230, 255), 2, cv2.LINE_AA)
        if self.selfie:
            cv2.putText(frame, "SELFIE: SONRIE" if not self.smiling else "SELFIE: :)",
                        (w - 210, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.6,
                        (0, 230, 255) if self.smiling else C_HUD, 2, cv2.LINE_AA)
        return frame

    # ------------------------------------------------------------------
    # Seguimiento PID (idéntico a la APK)
    # ------------------------------------------------------------------

    def _pid_step(self, key, error, kp, ki, kd, dt=0.1):
        integ, prev = self._pid[key]
        integ = max(-50.0, min(50.0, integ + error * dt))
        deriv = (error - prev) / dt
        self._pid[key] = [integ, error]
        return kp * error + ki * integ + kd * deriv

    def _pid_reset(self):
        for k in self._pid:
            self._pid[k] = [0.0, 0.0]

    def _update_offsets(self, fx, fy, fw, fh, w, h):
        """Desviación del objetivo en píxeles y su área (lectura directa en el HUD)."""
        cx, cy = fx + fw // 2, fy + fh // 2
        self.offsets = (int(cx - w // 2),
                        int(cy - h // 2 - h * FRAME_OFFSET_Y),
                        int(fw * fh))

    def _follow_pid(self, fx, fy, fw, fh, w, h):
        cx, cy = fx + fw / 2.0, fy + fh / 2.0
        ex = (cx - w / 2.0) / (w / 2.0)
        # el objetivo se encuadra algo por encima del centro (queda mejor plano)
        ey = (cy - h / 2.0 - h * FRAME_OFFSET_Y) / (h / 2.0)
        target = h * 0.38
        ez = (target - fh) / target

        ex = 0.0 if abs(ex) < 0.06 else ex
        ey = 0.0 if abs(ey) < 0.06 else ey
        ez = 0.0 if abs(ez) < 0.15 else ez

        yaw = self._pid_step("yaw", ex, 70, 2, 8)
        ud = self._pid_step("ud", -ey, 60, 2, 6)
        fb = self._pid_step("fb", ez, 45, 1, 4)
        return (0, _clamp(fb), _clamp(ud), _clamp(yaw))

    def _follow_body(self, bx, by, bw, bh, w, h):
        """Seguimiento del cuerpo entero: la distancia se mide por los hombros,
        que es mucho más estable que la altura de la caja (brazos, agacharse...)."""
        cx = bx + bw / 2.0
        cy = by + bh / 2.0
        ex = (cx - w / 2.0) / (w / 2.0)
        ey = (cy - h / 2.0 - h * FRAME_OFFSET_Y) / (h / 2.0)

        sw = self.skeleton.shoulder_width()
        target = w * BODY_TARGET_W
        ez = (target - sw) / target if sw > 5 else 0.0

        ex = 0.0 if abs(ex) < 0.07 else ex
        ey = 0.0 if abs(ey) < 0.10 else ey
        ez = 0.0 if abs(ez) < 0.20 else ez

        # ganancias algo más suaves: el cuerpo ocupa más y el error crece rápido
        yaw = self._pid_step("yaw", ex, 60, 2, 7)
        ud = self._pid_step("ud", -ey, 45, 1, 5)
        fb = self._pid_step("fb", ez, 35, 1, 3)
        return (0, _clamp(fb), _clamp(ud), _clamp(yaw))

    # ------------------------------------------------------------------
    # Identificación de caras (LBPH)
    # ------------------------------------------------------------------

    def set_selfie(self, on):
        self.selfie = bool(on)
        self.log("VISION", "modo selfie (foto al sonreír) %s" % ("ON" if on else "OFF"))

    def _check_smile(self, face_gray):
        """Sonrisa dentro de la cara -> dispara foto (con 3 s de guarda)."""
        if self.smile_cascade.empty() or face_gray.size == 0:
            return
        h = face_gray.shape[0]
        lower = face_gray[h // 2:, :]          # la boca está en la mitad inferior
        if lower.size == 0:
            return
        smiles = self.smile_cascade.detectMultiScale(lower, 1.7, 22, minSize=(25, 15))
        self.smiling = len(smiles) > 0
        if self.smiling and time.time() - self._last_selfie > 3.0 and self.on_photo:
            self._last_selfie = time.time()
            self.log("VISION", "¡sonrisa detectada! disparando foto")
            try:
                self.on_photo()
            except Exception as e:
                self.log("VISION", "foto automática: %s" % e)

    def _identify(self, face_gray):
        if not self.has_face_module or not self._trained or face_gray.size == 0:
            return ""
        try:
            f = cv2.resize(face_gray, (100, 100))
            label_id, conf = self.recognizer.predict(f)
            if conf <= _ID_THRESHOLD:
                return self._id_to_name.get(label_id, "")
        except Exception:
            pass
        return ""

    def _enroll(self, name, frame):
        if not self.has_face_module:
            return "sin modulo cv2.face"
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        det = self.face_cascade.detectMultiScale(gray, 1.2, 5, minSize=(40, 40))
        if len(det) == 0:
            self.log("VISION", "memorizar '%s': no veo ninguna cara" % name)
            return "no veo ninguna cara"
        fx, fy, fw, fh = max(det, key=lambda f: f[2] * f[3])
        face = cv2.resize(gray[fy:fy + fh, fx:fx + fw], (100, 100))
        if name not in self._name_to_id:
            new_id = len(self._name_to_id) + 1
            self._name_to_id[name] = new_id
            self._id_to_name[new_id] = name
        self._samples.append(face)
        self._sample_ids.append(self._name_to_id[name])
        self.recognizer.train(self._samples, np.array(self._sample_ids))
        self._trained = True
        self.save_model()
        n = self._sample_ids.count(self._name_to_id[name])
        self.log("VISION", "cara memorizada: %s (%d muestras)" % (name, n))
        return "cara de %s memorizada (%d)" % (name, n)

    def save_model(self):
        if not self.has_face_module or not self._trained:
            return "nada que guardar"
        try:
            os.makedirs(DATA_DIR, exist_ok=True)
            self.recognizer.write(os.path.join(DATA_DIR, "faces.yml"))
            with open(os.path.join(DATA_DIR, "names.txt"), "w", encoding="utf-8") as f:
                for i, n in self._id_to_name.items():
                    f.write("%d:%s\n" % (i, n))
            return "guardado"
        except Exception as e:
            return "error: %s" % e

    def load_model(self):
        if not self.has_face_module:
            return "sin modulo cv2.face"
        ym = os.path.join(DATA_DIR, "faces.yml")
        yn = os.path.join(DATA_DIR, "names.txt")
        if not (os.path.exists(ym) and os.path.exists(yn)):
            return "sin datos"
        try:
            self.recognizer.read(ym)
            self._id_to_name.clear()
            self._name_to_id.clear()
            with open(yn, "r", encoding="utf-8") as f:
                for line in f:
                    if ":" in line:
                        i, n = line.strip().split(":", 1)
                        self._id_to_name[int(i)] = n
                        self._name_to_id[n] = int(i)
            self._trained = True
            self.log("VISION", "caras cargadas: %s" % ", ".join(self._name_to_id))
            return "cargado"
        except Exception as e:
            return "error: %s" % e

    def reset_ids(self):
        self._samples.clear()
        self._sample_ids.clear()
        self._id_to_name.clear()
        self._name_to_id.clear()
        self._trained = False
        if self.has_face_module:
            self.recognizer = cv2.face.LBPHFaceRecognizer_create()
        for f in ("faces.yml", "names.txt"):
            try:
                os.remove(os.path.join(DATA_DIR, f))
            except OSError:
                pass
        self.log("VISION", "caras memorizadas borradas")
        return "ok"

    # ------------------------------------------------------------------
    # Color / QR / gestos
    # ------------------------------------------------------------------

    def _detect_color(self, img):
        try:
            hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
            mask = None
            for lo, hi in COLORS.get(self.color, []):
                m = cv2.inRange(hsv, np.array(lo, np.uint8), np.array(hi, np.uint8))
                mask = m if mask is None else cv2.bitwise_or(mask, m)
            if mask is None:
                return None
            mask = cv2.erode(mask, None, iterations=2)
            mask = cv2.dilate(mask, None, iterations=2)
            contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            if not contours:
                return None
            cnt = max(contours, key=cv2.contourArea)
            if cv2.contourArea(cnt) < (img.shape[0] * img.shape[1]) * 0.01:
                return None
            return cv2.boundingRect(cnt)
        except Exception:
            return None

    def _detect_qr(self, img):
        try:
            data, points, _ = self.qr_detector.detectAndDecode(img)
            if points is None:
                return None
            pts = points.reshape(-1, 2).astype(int)
            if pts.shape[0] < 4:
                return None
            return ([tuple(p) for p in pts[:4]], data or "")
        except Exception:
            return None

    def _detect_gesture(self, img):
        try:
            ycrcb = cv2.cvtColor(img, cv2.COLOR_BGR2YCrCb)
            mask = cv2.inRange(ycrcb, np.array([0, 133, 77], np.uint8),
                               np.array([255, 173, 127], np.uint8))
            mask = cv2.GaussianBlur(mask, (5, 5), 0)
            mask = cv2.erode(mask, None, iterations=2)
            mask = cv2.dilate(mask, None, iterations=2)
            contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            if not contours:
                return ""
            cnt = max(contours, key=cv2.contourArea)
            if cv2.contourArea(cnt) < (img.shape[0] * img.shape[1]) * 0.03:
                return ""
            hull = cv2.convexHull(cnt, returnPoints=False)
            if hull is None or len(hull) < 3:
                return ""
            defects = cv2.convexityDefects(cnt, hull)
            if defects is None:
                return "land"
            fingers = 0
            for i in range(defects.shape[0]):
                s, e, f, d = defects[i, 0]
                start, end, far = cnt[s][0], cnt[e][0], cnt[f][0]
                a = np.linalg.norm(end - start)
                b = np.linalg.norm(far - start)
                c = np.linalg.norm(end - far)
                if b * c == 0:
                    continue
                angle = np.arccos((b ** 2 + c ** 2 - a ** 2) / (2 * b * c))
                if angle <= np.pi / 2 and d > 8000:
                    fingers += 1
            fingers += 1
            if fingers >= 5:
                return "takeoff"
            if fingers == 1:
                return "land"
            if fingers == 2:
                return "up"
            if fingers == 3:
                return "down"
            return ""
        except Exception:
            return ""


def _clamp(v):
    return int(max(-100, min(100, v)))
