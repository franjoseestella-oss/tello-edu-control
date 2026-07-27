# -*- coding: utf-8 -*-
"""
Visión por computador para el Tello, ejecutada en el móvil con Chaquopy.

Funciones expuestas a Java:
  process(data, w, h, follow, gesture)  -> str   analiza un fotograma
  enroll(name, data, w, h)              -> str   memoriza una cara (identificación)
  known_names()                         -> str   nombres memorizados
  reset_ids()                           -> str   borra las caras memorizadas

Formato del string devuelto por process():
  "F:x,y,w,h,label;F:...|G:gesto|C:lr,fb,ud,yaw"
  (coordenadas en píxeles sobre la imagen reducida w x h)
"""

import numpy as np
import cv2

# ---- Detector de caras (Haar cascade incluido en opencv) ----
_face_cascade = cv2.CascadeClassifier(
    cv2.data.haarcascades + "haarcascade_frontalface_default.xml")

# ---- Reconocedor LBPH (requiere opencv-contrib) ----
try:
    _recognizer = cv2.face.LBPHFaceRecognizer_create()
    _has_face_module = True
except Exception:
    _recognizer = None
    _has_face_module = False

_samples = []          # lista de caras (100x100 gris)
_sample_ids = []       # id numérico por muestra
_id_to_name = {}       # id -> nombre
_name_to_id = {}       # nombre -> id
_trained = False
_ID_THRESHOLD = 75.0   # confianza LBPH (menor = mejor)


def _to_bgr(data, w, h):
    arr = np.frombuffer(bytes(data), dtype=np.uint8)
    return arr.reshape((h, w, 3))


def _largest(faces):
    return max(faces, key=lambda f: f[2] * f[3])


# =====================================================================
#  ANÁLISIS DE FOTOGRAMA
# =====================================================================
def process(data, w, h, follow, gesture):
    try:
        img = _to_bgr(data, w, h)
    except Exception as e:
        return "|G:|C:0,0,0,0"

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    faces = _face_cascade.detectMultiScale(gray, 1.2, 5, minSize=(30, 30))

    parts = []
    ctrl = (0, 0, 0, 0)

    if len(faces) > 0:
        fx, fy, fw, fh = _largest(faces)
        main_name = _identify(gray[fy:fy + fh, fx:fx + fw])
        for (x, y, ww, hh) in faces:
            is_main = (x == fx and y == fy and ww == fw and hh == fh)
            label = main_name if is_main else ""
            parts.append("F:%d,%d,%d,%d,%s" % (x, y, ww, hh, label))
        if follow:
            ctrl = _follow_control(fx, fy, fw, fh, w, h)

    gesture_name = ""
    if gesture:
        gesture_name = _detect_gesture(img)

    out = ";".join(parts)
    out += "|G:" + gesture_name
    out += "|C:%d,%d,%d,%d" % ctrl
    return out


# =====================================================================
#  SEGUIMIENTO DE CARA  ->  vector rc (lr, fb, ud, yaw)
# =====================================================================
def _follow_control(fx, fy, fw, fh, w, h):
    cx = fx + fw / 2.0
    cy = fy + fh / 2.0

    # Error normalizado respecto al centro (-1..1)
    ex = (cx - w / 2.0) / (w / 2.0)
    ey = (cy - h / 2.0) / (h / 2.0)

    # Giro (yaw) para centrar horizontalmente + altura para centrar vertical
    yaw = int(_dead(ex) * 60)
    ud = int(-_dead(ey) * 50)

    # Distancia: mantener la cara a un tamaño objetivo (~35% de la altura)
    target = h * 0.35
    err = (target - fh) / target
    fb = int(_dead(err, 0.15) * 40)   # cara pequeña -> avanzar

    return (0, _clamp(fb), _clamp(ud), _clamp(yaw))


def _dead(v, dz=0.08):
    """Zona muerta para evitar temblores cuando ya está centrado."""
    if abs(v) < dz:
        return 0.0
    return v


def _clamp(v):
    return max(-100, min(100, v))


# =====================================================================
#  IDENTIFICACIÓN DE PERSONAS (LBPH)
# =====================================================================
def _identify(face_gray):
    if not _has_face_module or not _trained or face_gray.size == 0:
        return ""
    try:
        f = cv2.resize(face_gray, (100, 100))
        label_id, confidence = _recognizer.predict(f)
        if confidence <= _ID_THRESHOLD:
            return _id_to_name.get(label_id, "")
    except Exception:
        pass
    return ""


def enroll(name, data, w, h):
    """Memoriza la cara más grande del fotograma con un nombre."""
    global _trained
    if not _has_face_module:
        return "sin_modulo_face"
    try:
        img = _to_bgr(data, w, h)
    except Exception:
        return "frame_invalido"

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    faces = _face_cascade.detectMultiScale(gray, 1.2, 5, minSize=(40, 40))
    if len(faces) == 0:
        return "sin_cara"

    fx, fy, fw, fh = _largest(faces)
    face = cv2.resize(gray[fy:fy + fh, fx:fx + fw], (100, 100))

    if name not in _name_to_id:
        new_id = len(_name_to_id) + 1
        _name_to_id[name] = new_id
        _id_to_name[new_id] = name

    _samples.append(face)
    _sample_ids.append(_name_to_id[name])

    _recognizer.train(_samples, np.array(_sample_ids))
    _trained = True
    n = _sample_ids.count(_name_to_id[name])
    return "ok:%s:%d" % (name, n)


def known_names():
    return ",".join(_name_to_id.keys())


def reset_ids():
    global _trained
    _samples.clear()
    _sample_ids.clear()
    _id_to_name.clear()
    _name_to_id.clear()
    _trained = False
    return "ok"


# =====================================================================
#  GESTOS DE LA MANO (segmentación de piel + defectos de convexidad)
#  Devuelve: "takeoff" (palma, ~5 dedos), "land" (puño, 0 dedos),
#            "up" (2 dedos), "down" (3 dedos), o "".
# =====================================================================
def _detect_gesture(img):
    try:
        ycrcb = cv2.cvtColor(img, cv2.COLOR_BGR2YCrCb)
        lower = np.array([0, 133, 77], dtype=np.uint8)
        upper = np.array([255, 173, 127], dtype=np.uint8)
        mask = cv2.inRange(ycrcb, lower, upper)
        mask = cv2.GaussianBlur(mask, (5, 5), 0)
        mask = cv2.erode(mask, None, iterations=2)
        mask = cv2.dilate(mask, None, iterations=2)

        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        if not contours:
            return ""
        cnt = max(contours, key=cv2.contourArea)
        area = cv2.contourArea(cnt)
        if area < (img.shape[0] * img.shape[1]) * 0.03:
            return ""   # mano demasiado pequeña / ruido

        hull = cv2.convexHull(cnt, returnPoints=False)
        if hull is None or len(hull) < 3:
            return ""
        defects = cv2.convexityDefects(cnt, hull)
        if defects is None:
            return "land"   # contorno casi convexo -> puño

        fingers = 0
        for i in range(defects.shape[0]):
            s, e, f, d = defects[i, 0]
            start = cnt[s][0]
            end = cnt[e][0]
            far = cnt[f][0]
            a = np.linalg.norm(end - start)
            b = np.linalg.norm(far - start)
            c = np.linalg.norm(end - far)
            if b * c == 0:
                continue
            angle = np.arccos((b ** 2 + c ** 2 - a ** 2) / (2 * b * c))
            # Un valle profundo y anguloso = separación entre dos dedos
            if angle <= np.pi / 2 and d > 8000:
                fingers += 1
        fingers += 1   # nº de dedos ≈ nº de valles + 1

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
