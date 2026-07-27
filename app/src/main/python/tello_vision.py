# -*- coding: utf-8 -*-
"""
Visión por computador para el Tello, ejecutada en el móvil con Chaquopy (OpenCV).

Funciones expuestas a Java:
  process(data, w, h, mode, gesture)   -> str    analiza un fotograma
  enroll(name, data, w, h)             -> str    memoriza una cara
  save_model(dir)                      -> str    guarda las caras memorizadas
  load_model(dir)                      -> str    carga las caras memorizadas
  known_names()                        -> str
  reset_ids()                          -> str

mode:  0=off  1=detectar caras  2=seguir cara (PID)

Formato de salida:
  "F:x,y,w,h,label;...|G:gesto|Q:x1,y1,x2,y2,x3,y3,x4,y4,texto|C:lr,fb,ud,yaw"
"""

import os
import numpy as np
import cv2

_face_cascade = cv2.CascadeClassifier(
    cv2.data.haarcascades + "haarcascade_frontalface_default.xml")

try:
    _recognizer = cv2.face.LBPHFaceRecognizer_create()
    _has_face_module = True
except Exception:
    _recognizer = None
    _has_face_module = False

_qr = cv2.QRCodeDetector()

_samples = []
_sample_ids = []
_id_to_name = {}
_name_to_id = {}
_trained = False
_ID_THRESHOLD = 78.0

# Estado del PID de seguimiento
_pid = {"yaw": [0.0, 0.0], "ud": [0.0, 0.0], "fb": [0.0, 0.0]}  # [integral, prev_error]


def _to_bgr(data, w, h):
    return np.frombuffer(bytes(data), dtype=np.uint8).reshape((h, w, 3))


def _largest(faces):
    return max(faces, key=lambda f: f[2] * f[3])


# =====================================================================
def process(data, w, h, mode, gesture):
    try:
        img = _to_bgr(data, w, h)
    except Exception:
        return "|G:|Q:|C:0,0,0,0"

    parts = []
    ctrl = (0, 0, 0, 0)

    if mode >= 1:
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        faces = _face_cascade.detectMultiScale(gray, 1.2, 5, minSize=(30, 30))
        if len(faces) > 0:
            fx, fy, fw, fh = _largest(faces)
            main_name = _identify(gray[fy:fy + fh, fx:fx + fw])
            for (x, y, ww, hh) in faces:
                is_main = (x == fx and y == fy and ww == fw and hh == fh)
                parts.append("F:%d,%d,%d,%d,%s" % (x, y, ww, hh, main_name if is_main else ""))
            if mode == 2:
                ctrl = _follow_pid(fx, fy, fw, fh, w, h)
        else:
            _pid_reset()
    else:
        _pid_reset()

    gesture_name = ""
    if gesture:
        gesture_name = _detect_gesture(img)

    qr_str = _detect_qr(img)

    out = ";".join(parts)
    out += "|G:" + gesture_name
    out += "|Q:" + qr_str
    out += "|C:%d,%d,%d,%d" % ctrl
    return out


# =====================================================================
#  SEGUIMIENTO DE CARA con PID (suave)
# =====================================================================
def _pid_step(key, error, kp, ki, kd, dt=0.1):
    integ, prev = _pid[key]
    integ = max(-50.0, min(50.0, integ + error * dt))
    deriv = (error - prev) / dt
    _pid[key] = [integ, error]
    return kp * error + ki * integ + kd * deriv


def _pid_reset():
    for k in _pid:
        _pid[k] = [0.0, 0.0]


def _follow_pid(fx, fy, fw, fh, w, h):
    cx = fx + fw / 2.0
    cy = fy + fh / 2.0
    ex = (cx - w / 2.0) / (w / 2.0)          # -1..1
    ey = (cy - h / 2.0) / (h / 2.0)
    target = h * 0.38
    ez = (target - fh) / target

    ex = 0.0 if abs(ex) < 0.06 else ex
    ey = 0.0 if abs(ey) < 0.06 else ey
    ez = 0.0 if abs(ez) < 0.15 else ez

    yaw = _pid_step("yaw", ex, 70, 2, 8)
    ud = _pid_step("ud", -ey, 60, 2, 6)
    fb = _pid_step("fb", ez, 45, 1, 4)
    return (0, _clamp(fb), _clamp(ud), _clamp(yaw))


def _clamp(v):
    return int(max(-100, min(100, v)))


# =====================================================================
#  IDENTIFICACIÓN (LBPH) + persistencia
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
    return "ok:%s:%d" % (name, _sample_ids.count(_name_to_id[name]))


def save_model(dir_path):
    if not _has_face_module or not _trained:
        return "nada_que_guardar"
    try:
        _recognizer.write(os.path.join(dir_path, "faces.yml"))
        with open(os.path.join(dir_path, "names.txt"), "w", encoding="utf-8") as f:
            for i, n in _id_to_name.items():
                f.write("%d:%s\n" % (i, n))
        return "guardado:%d" % len(_id_to_name)
    except Exception as e:
        return "error:" + str(e)


def load_model(dir_path):
    global _trained
    if not _has_face_module:
        return "sin_modulo_face"
    ymodel = os.path.join(dir_path, "faces.yml")
    ynames = os.path.join(dir_path, "names.txt")
    if not os.path.exists(ymodel) or not os.path.exists(ynames):
        return "sin_datos"
    try:
        _recognizer.read(ymodel)
        _id_to_name.clear()
        _name_to_id.clear()
        with open(ynames, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if ":" in line:
                    i, n = line.split(":", 1)
                    _id_to_name[int(i)] = n
                    _name_to_id[n] = int(i)
        _trained = True
        return "cargado:%d" % len(_id_to_name)
    except Exception as e:
        return "error:" + str(e)


def known_names():
    return ",".join(_name_to_id.keys())


def reset_ids():
    global _trained, _recognizer
    _samples.clear()
    _sample_ids.clear()
    _id_to_name.clear()
    _name_to_id.clear()
    _trained = False
    if _has_face_module:
        _recognizer = cv2.face.LBPHFaceRecognizer_create()
    return "ok"


# =====================================================================
#  QR (uso educativo del Tello EDU)
# =====================================================================
def _detect_qr(img):
    try:
        data, points, _ = _qr.detectAndDecode(img)
        if points is None:
            return ""
        pts = points.reshape(-1, 2).astype(int)
        if pts.shape[0] < 4:
            return ""
        coords = ",".join(str(int(v)) for v in pts[:4].flatten())
        text = (data or "").replace("|", " ").replace(";", " ")
        return coords + "," + text
    except Exception:
        return ""


# =====================================================================
#  GESTOS (segmentación de piel + defectos de convexidad)
# =====================================================================
def _detect_gesture(img):
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
