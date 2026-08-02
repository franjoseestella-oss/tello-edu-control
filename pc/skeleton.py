# -*- coding: utf-8 -*-
"""
Esqueleto y dedos con MediaPipe Tasks.

  PoseLandmarker  -> 33 puntos del cuerpo: seguir a la persona entera
                     (funciona aunque te des la vuelta, al reves que la cara)
                     y gestos con los brazos.
  HandLandmarker  -> 21 puntos por mano: contar dedos y reconocer la postura.

Los modelos (.task) se descargan una vez a pc/models/. Si no estan y no hay
internet, el modulo se queda inactivo y la vision sigue con el metodo clasico.

Para que un gesto no dispare por un fotograma suelto, hay que mantenerlo
CONFIRM_FRAMES veces seguidas antes de que cuente como orden.
"""

import os
import threading
import time
import urllib.request

import cv2
import numpy as np

MODEL_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "models")
MODELS = {
    "pose": ("pose_landmarker_lite.task",
             "https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
             "pose_landmarker_lite/float16/latest/pose_landmarker_lite.task"),
    "hand": ("hand_landmarker.task",
             "https://storage.googleapis.com/mediapipe-models/hand_landmarker/"
             "hand_landmarker/float16/latest/hand_landmarker.task"),
}

CONFIRM_FRAMES = 4          # fotogramas seguidos con el mismo gesto
COOLDOWN = 3.0              # segundos entre ordenes de despegue/aterrizaje

# Huesos que se dibujan (indices de PoseLandmark)
BONES = [
    (11, 12), (11, 23), (12, 24), (23, 24),          # tronco
    (11, 13), (13, 15), (12, 14), (14, 16),          # brazos
    (23, 25), (25, 27), (24, 26), (26, 28),          # piernas
    (27, 31), (28, 32),                              # pies
    (0, 11), (0, 12),                                # cuello
]
HAND_BONES = [
    (0, 1), (1, 2), (2, 3), (3, 4),                  # pulgar
    (0, 5), (5, 6), (6, 7), (7, 8),                  # indice
    (5, 9), (9, 10), (10, 11), (11, 12),             # corazon
    (9, 13), (13, 14), (14, 15), (15, 16),           # anular
    (13, 17), (17, 18), (18, 19), (19, 20), (0, 17), # menique
]

# Gestos de mano -> orden para el dron
HAND_GESTURES = {
    "palma":     ("takeoff", "🖐 palma abierta = DESPEGAR"),
    "puño":      ("land",    "✊ puño = ATERRIZAR"),
    "uno":       ("up",      "☝ un dedo = SUBIR"),
    "dos":       ("down",    "✌ dos dedos = BAJAR"),
    "tres":      ("photo",   "🤟 tres dedos = FOTO"),
    "pulgar_izq": ("left",   "👈 pulgar a la izquierda = IZQUIERDA"),
    "pulgar_der": ("right",  "👉 pulgar a la derecha = DERECHA"),
}

# Gestos de cuerpo (esqueleto) -> orden
BODY_GESTURES = {
    "brazos_arriba": ("takeoff", "🙌 dos brazos arriba = DESPEGAR"),
    "brazos_abajo":  ("land",    "🧍 brazos pegados al cuerpo = ATERRIZAR"),
    "brazo_der":     ("right",   "🙋 brazo derecho en cruz = DERECHA"),
    "brazo_izq":     ("left",    "🙋 brazo izquierdo en cruz = IZQUIERDA"),
    "cruz":          ("stop",    "🅃 los dos brazos en cruz = QUIETO"),
}


def model_path(kind, download=True, log=None):
    name, url = MODELS[kind]
    path = os.path.join(MODEL_DIR, name)
    if os.path.exists(path) and os.path.getsize(path) > 100000:
        return path
    if not download:
        return None
    try:
        os.makedirs(MODEL_DIR, exist_ok=True)
        if log:
            log("SKELETON", "descargando el modelo de %s (una sola vez)..." % kind)
        urllib.request.urlretrieve(url, path)
        if log:
            log("SKELETON", "modelo de %s listo" % kind)
        return path
    except Exception as e:
        if log:
            log("WARN", "no pude descargar el modelo de %s: %s "
                        "(¿estás en el WiFi del dron, sin internet?)" % (kind, e))
        return None


class SkeletonEngine:
    """Esqueleto del cuerpo + manos, con gestos confirmados por repeticion."""

    def __init__(self, log=None):
        self.log = log or (lambda k, m: None)
        self.ready = False
        self.error = ""

        self.pose_on = False
        self.hands_on = False

        # Ultimo resultado
        self.body = None          # np.array (33,3) en pixeles + visibilidad
        self.hand = None          # np.array (21,2) en pixeles
        self.hand_label = ""
        self.fingers = -1
        self.gesture = ""         # gesto crudo del fotograma
        self.gesture_stable = ""  # gesto ya confirmado
        self.gesture_text = ""
        self.action = ""          # orden asociada al gesto confirmado

        self._pose, self._hands = None, None
        self._t0 = time.time()
        self._last = ""
        self._count = 0
        self._lock = threading.Lock()

    # ------------------------------------------------------------------

    def start(self):
        """Carga los modelos (tarda ~1 s). Devuelve True si quedan listos."""
        if self.ready:
            return True
        try:
            from mediapipe.tasks import python as mp_python
            from mediapipe.tasks.python import vision as mp_vision

            pp = model_path("pose", log=self.log)
            hp = model_path("hand", log=self.log)
            if not pp or not hp:
                self.error = "faltan los modelos .task en pc/models/"
                return False

            self._pose = mp_vision.PoseLandmarker.create_from_options(
                mp_vision.PoseLandmarkerOptions(
                    base_options=mp_python.BaseOptions(model_asset_path=pp),
                    running_mode=mp_vision.RunningMode.VIDEO,
                    num_poses=1, min_pose_detection_confidence=0.5,
                    min_tracking_confidence=0.5))
            self._hands = mp_vision.HandLandmarker.create_from_options(
                mp_vision.HandLandmarkerOptions(
                    base_options=mp_python.BaseOptions(model_asset_path=hp),
                    running_mode=mp_vision.RunningMode.VIDEO,
                    num_hands=1, min_hand_detection_confidence=0.5,
                    min_tracking_confidence=0.5))
            self.ready = True
            self.log("SKELETON", "esqueleto y manos listos (MediaPipe Tasks)")
            return True
        except Exception as e:
            self.error = str(e)
            self.log("WARN", "no pude iniciar el esqueleto: %s" % e)
            return False

    def active(self):
        return self.ready and (self.pose_on or self.hands_on)

    # ------------------------------------------------------------------

    def process(self, frame):
        """Analiza un fotograma BGR. Devuelve la orden confirmada o ''."""
        if not self.active():
            return ""
        import mediapipe as mp
        h, w = frame.shape[:2]
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        img = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
        ts = int((time.time() - self._t0) * 1000)

        body = hand = None
        label = ""
        gesture = ""

        if self.pose_on:
            try:
                res = self._pose.detect_for_video(img, ts)
                if res.pose_landmarks:
                    lm = res.pose_landmarks[0]
                    body = np.array([[p.x * w, p.y * h, getattr(p, "visibility", 1.0)]
                                     for p in lm], np.float32)
                    gesture = self._body_gesture(body, w, h)
            except Exception as e:
                self.log("WARN", "pose: %s" % e)

        if self.hands_on:
            try:
                res = self._hands.detect_for_video(img, ts + 1)
                if res.hand_landmarks:
                    lm = res.hand_landmarks[0]
                    hand = np.array([[p.x * w, p.y * h] for p in lm], np.float32)
                    if res.handedness and res.handedness[0]:
                        label = res.handedness[0][0].category_name
                    g = self._hand_gesture(hand, label)
                    if g:                       # la mano manda sobre el cuerpo
                        gesture = g
            except Exception as e:
                self.log("WARN", "manos: %s" % e)

        with self._lock:
            self.body, self.hand, self.hand_label = body, hand, label
            self.gesture = gesture

        return self._confirm(gesture)

    def _confirm(self, gesture):
        """Exige repeticion para no disparar ordenes por un fotograma suelto."""
        if gesture and gesture == self._last:
            self._count += 1
        else:
            self._last, self._count = gesture, 1
        if not gesture:
            self.gesture_stable, self.gesture_text, self.action = "", "", ""
            return ""
        if self._count == CONFIRM_FRAMES:
            table = HAND_GESTURES if gesture in HAND_GESTURES else BODY_GESTURES
            action, text = table.get(gesture, ("", gesture))
            self.gesture_stable, self.gesture_text, self.action = gesture, text, action
            self.log("GESTO", "%s -> %s" % (text, action))
            return action
        return ""

    # ------------------------------------------------------------------
    # Objetivo para el seguimiento del esqueleto
    # ------------------------------------------------------------------

    def target_box(self):
        """Caja del torso (x, y, w, h) para que el PID siga al cuerpo."""
        with self._lock:
            body = None if self.body is None else self.body.copy()
        if body is None:
            return None
        ls, rs = body[11], body[12]         # hombros
        lh, rh = body[23], body[24]         # caderas
        if min(ls[2], rs[2]) < 0.4:
            return None
        xs = [ls[0], rs[0], lh[0], rh[0]]
        ys = [ls[1], rs[1], lh[1], rh[1]]
        x, y = min(xs), min(ys)
        w = max(4.0, max(xs) - x)
        hgt = max(4.0, max(ys) - y)
        # La cabeza queda por encima de los hombros: subimos la caja para
        # que el dron encuadre a la persona entera y no solo el tronco.
        head = body[0][1]
        if head < y:
            hgt += (y - head)
            y = head
        return (int(x), int(y), int(w), int(hgt))

    def shoulder_width(self):
        with self._lock:
            body = None if self.body is None else self.body
            if body is None:
                return 0.0
            return float(abs(body[11][0] - body[12][0]))

    # ------------------------------------------------------------------
    # Gestos
    # ------------------------------------------------------------------

    def _hand_gesture(self, p, label):
        """Cuenta dedos extendidos y traduce la postura."""
        # Escala de la mano para umbrales relativos al tamaño en pantalla
        scale = max(20.0, np.linalg.norm(p[0] - p[9]))
        up = []
        for tip, pip in ((8, 6), (12, 10), (16, 14), (20, 18)):
            up.append(p[tip][1] < p[pip][1] - scale * 0.15)
        # Pulgar: extendido si la punta se aleja de la base del meñique más que
        # su propia articulación. Así vale para mano izquierda o derecha y en
        # cualquier giro, sin depender de coordenadas horizontales.
        thumb_out = (np.linalg.norm(p[4] - p[17])
                     > np.linalg.norm(p[3] - p[17]) + scale * 0.12)
        n = sum(up) + (1 if thumb_out else 0)
        self.fingers = n

        if n == 5:
            return "palma"
        if n == 0:
            return "puño"
        if thumb_out and not any(up):
            # solo el pulgar: apunta a un lado
            return "pulgar_izq" if p[4][0] < p[0][0] else "pulgar_der"
        if not thumb_out:
            if up[0] and not up[1] and not up[2] and not up[3]:
                return "uno"
            if up[0] and up[1] and not up[2] and not up[3]:
                return "dos"
            if up[0] and up[1] and up[2] and not up[3]:
                return "tres"
        return ""

    def _body_gesture(self, b, w, h):
        """Gestos con los brazos, medidos en unidades de 'ancho de hombros'."""
        ls, rs = b[11], b[12]
        le, re = b[13], b[14]
        lw_, rw_ = b[15], b[16]
        if min(ls[2], rs[2]) < 0.4:
            return ""
        sw = max(30.0, abs(ls[0] - rs[0]))
        sy = (ls[1] + rs[1]) / 2.0

        lup = lw_[1] < sy - sw * 0.6 and lw_[2] > 0.3
        rup = rw_[1] < sy - sw * 0.6 and rw_[2] > 0.3
        if lup and rup:
            return "brazos_arriba"

        # brazo en cruz: muñeca a la altura del hombro y bien separada
        lout = abs(lw_[1] - sy) < sw * 0.45 and abs(lw_[0] - ls[0]) > sw * 0.8 and lw_[2] > 0.3
        rout = abs(rw_[1] - sy) < sw * 0.45 and abs(rw_[0] - rs[0]) > sw * 0.8 and rw_[2] > 0.3
        if lout and rout:
            return "cruz"
        if rout:
            return "brazo_der"
        if lout:
            return "brazo_izq"

        ldown = lw_[1] > sy + sw * 1.1 and abs(lw_[0] - ls[0]) < sw * 0.5
        rdown = rw_[1] > sy + sw * 1.1 and abs(rw_[0] - rs[0]) < sw * 0.5
        if ldown and rdown and min(lw_[2], rw_[2]) > 0.3:
            return "brazos_abajo"
        return ""

    # ------------------------------------------------------------------
    # Dibujo
    # ------------------------------------------------------------------

    def draw(self, frame):
        with self._lock:
            body = None if self.body is None else self.body.copy()
            hand = None if self.hand is None else self.hand.copy()
            label, gesture, text = self.hand_label, self.gesture_stable, self.gesture_text
        h, w = frame.shape[:2]

        if body is not None:
            for a, b in BONES:
                if body[a][2] > 0.3 and body[b][2] > 0.3:
                    cv2.line(frame, (int(body[a][0]), int(body[a][1])),
                             (int(body[b][0]), int(body[b][1])), (0, 255, 180), 2, cv2.LINE_AA)
            for i, p in enumerate(body):
                if p[2] > 0.3:
                    r = 5 if i in (0, 11, 12, 15, 16, 23, 24) else 3
                    cv2.circle(frame, (int(p[0]), int(p[1])), r, (255, 220, 60), -1, cv2.LINE_AA)

        if hand is not None:
            for a, b in HAND_BONES:
                cv2.line(frame, (int(hand[a][0]), int(hand[a][1])),
                         (int(hand[b][0]), int(hand[b][1])), (255, 140, 220), 2, cv2.LINE_AA)
            for p in hand:
                cv2.circle(frame, (int(p[0]), int(p[1])), 3, (255, 255, 255), -1, cv2.LINE_AA)
            if self.fingers >= 0:
                x, y = int(hand[0][0]), int(hand[0][1])
                cv2.putText(frame, "%d dedos%s" % (self.fingers, " " + label if label else ""),
                            (x - 40, y + 28), cv2.FONT_HERSHEY_SIMPLEX, 0.6,
                            (255, 140, 220), 2, cv2.LINE_AA)

        if gesture:
            txt = text or gesture
            cv2.rectangle(frame, (8, h - 74), (18 + 13 * len(txt), h - 40), (0, 0, 0), -1)
            cv2.putText(frame, txt, (14, h - 50), cv2.FONT_HERSHEY_SIMPLEX,
                        0.62, (0, 255, 180), 2, cv2.LINE_AA)
        return frame

    def close(self):
        for o in (self._pose, self._hands):
            try:
                if o:
                    o.close()
            except Exception:
                pass
        self._pose = self._hands = None
        self.ready = False
