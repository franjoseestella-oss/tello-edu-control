#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TELLO STATION — estacion de control completa del DJI Tello EDU desde el PC.

Todo lo que hace la APK, en el navegador:
  · video en directo del dron con overlay de vision
  · deteccion de caras + identificacion (LBPH), seguimiento con PID,
    seguimiento por color, gestos de mano, lectura de QR, selfie por sonrisa
  · dos joysticks tactiles, teclado, mando de juegos (Gamepad API)
  · despegue/aterrizaje/emergencia, flips, velocidad, misiones por waypoints
  · foto y grabacion de video
  · caja negra: log completo del protocolo UDP + diagnostico del enlace

Arranque:
    python station.py            (o doble clic en TELLO_STATION.bat)

Requiere opencv-contrib-python y numpy. La consola minima sin dependencias
sigue estando en tello_debug.py.
"""

import json
import os
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import cv2

# El enlace UDP y el log son exactamente los mismos que usa tello_debug.py
from tello_debug import LOG, TELLO
import vision as vz
from vision import VisionEngine
from video import VideoStream, MEDIA_DIR

WEB_PORT = int(os.environ.get("TELLO_WEB_PORT", "8770"))
# Escucha en todas las interfaces para poder abrirla desde el movil.
# Con TELLO_BIND=127.0.0.1 se limita a este PC.
BIND = os.environ.get("TELLO_BIND", "0.0.0.0")

VISION = VisionEngine(log=LOG.add)
VIDEO = VideoStream(vision=VISION, log=LOG.add)
VISION.on_photo = lambda: VIDEO.take_photo()

ABSOLUTE = ("up", "down", "left", "right", "forward", "back", "cw", "ccw",
            "flip", "go", "curve")

_warned_at = 0.0     # antirrepeticion del aviso "la vision manda"


# --------------------------------------------------------------------------
# Estado de la estacion
# --------------------------------------------------------------------------

class Station:
    def __init__(self):
        self.speed = 50
        self.mission = []            # [(kind, value)] kind: up/down/.../cw/ccw
        self.mission_running = False
        self.last_gesture_ms = 0.0
        self.gesture_cooldown = 4.0
        self.auto_land = True
        self.auto_land_pct = 10
        self._auto_landed = False
        self._low_warned = False
        self.last_photo = ""

    # ---- bucle que pasa el mando de la vision al dron ----

    def control_loop(self):
        while True:
            try:
                if VISION.controls_drone() and TELLO.rc_enabled:
                    lr, fb, ud, yaw = VISION.rc
                    TELLO.rc = [lr, fb, ud, yaw]
                if VISION.gesture_enabled:
                    self._apply_gesture(VISION.gesture)
                self._check_safety()
            except Exception as e:
                LOG.add("ERROR", "bucle de control: %s" % e)
            time.sleep(0.1)

    def _apply_gesture(self, g):
        if not g:
            return
        now = time.time()
        if g in ("takeoff", "land"):
            if now - self.last_gesture_ms < self.gesture_cooldown:
                return
            self.last_gesture_ms = now
            LOG.add("GESTO", "%s por gesto de mano" % g.upper())
            send_command(g)
        elif not VISION.controls_drone():
            if g == "up":
                TELLO.rc[2] = 40
            elif g == "down":
                TELLO.rc[2] = -40
            else:
                TELLO.rc[2] = 0

    def _check_safety(self):
        bat = TELLO.telemetry.get("bat")
        if bat is None:
            return
        try:
            bat = int(float(bat))
        except (TypeError, ValueError):
            return
        if self.auto_land and not self._auto_landed and 0 < bat <= self.auto_land_pct:
            self._auto_landed = True
            LOG.add("SEGURIDAD", "¡batería crítica (%d%%)! aterrizando automáticamente" % bat)
            send_command("land")
        elif not self._low_warned and 0 < bat <= 20:
            self._low_warned = True
            LOG.add("SEGURIDAD", "batería baja: %d%%" % bat)
        if bat > 25:
            self._low_warned = False

    # ---- misiones ----

    def run_mission(self):
        if self.mission_running or not self.mission:
            return
        steps = list(self.mission)
        self.mission_running = True

        def worker():
            LOG.add("MISION", "▶ ejecutando %d pasos" % len(steps))
            prev = TELLO.rc_enabled
            TELLO.rc_enabled = False       # los comandos absolutos no conviven con rc
            try:
                for i, (kind, val) in enumerate(steps, 1):
                    LOG.add("MISION", "paso %d/%d: %s %s" % (i, len(steps), kind, val))
                    r, ms = TELLO.send("%s %d" % (kind, val), wait=8.0)
                    if r is None:
                        LOG.add("MISION", "el paso %d no obtuvo respuesta; sigo" % i)
                    if kind in ("cw", "ccw"):
                        time.sleep(abs(val) * 0.022 + 1.0)
                    else:
                        time.sleep(val / max(10, self.speed) + 1.0)
                LOG.add("MISION", "✅ misión completada")
            finally:
                TELLO.rc_enabled = prev
                self.mission_running = False

        threading.Thread(target=worker, name="mission", daemon=True).start()


ST = Station()


def send_command(cmd, wait=7.0):
    """Envia un comando suspendiendo el bucle rc si es absoluto (up/cw/flip...)."""
    cmd = cmd.strip()
    if not cmd:
        return
    head = cmd.split()[0]
    LOG.add("ORDEN", "'%s'  |  conectado=%s rc=%s vision=%s"
            % (cmd, TELLO.sdk_ok, TELLO.rc_enabled, vz.MODE_NAMES.get(VISION.mode)))

    def worker():
        prev = TELLO.rc_enabled
        if head in ABSOLUTE and prev:
            TELLO.rc_enabled = False
            LOG.add("INFO", "bucle rc suspendido para '%s'" % cmd)
        try:
            TELLO.send(cmd, wait=wait)
            if head in ABSOLUTE:
                time.sleep(2.0)
        finally:
            TELLO.rc_enabled = prev

    threading.Thread(target=worker, name="cmd", daemon=True).start()


def qr_png(text, size=320):
    """QR con la URL de la estación, para abrirla en el móvil con la cámara."""
    try:
        enc = cv2.QRCodeEncoder_create()
        img = enc.encode(text)
        if img.max() <= 1:
            img = img * 255
        img = cv2.resize(img, (size, size), interpolation=cv2.INTER_NEAREST)
        img = cv2.copyMakeBorder(img, 16, 16, 16, 16, cv2.BORDER_CONSTANT, value=255)
        ok, buf = cv2.imencode(".png", img)
        return buf.tobytes() if ok else None
    except Exception as e:
        LOG.add("WARN", "no pude generar el QR: %s" % e)
        return None


def connect_all():
    """Conecta el enlace, arranca el vídeo y deja el rc en marcha."""
    ok = TELLO.connect_sequence()
    VIDEO.start()
    if ok:
        TELLO.send("speed %d" % ST.speed, wait=1.0)
    return ok


# --------------------------------------------------------------------------
# Interfaz web
# --------------------------------------------------------------------------

PAGE = r"""<!doctype html>
<html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<title>Tello Station</title>
<style>
*{box-sizing:border-box;margin:0;padding:0;-webkit-tap-highlight-color:transparent}
:root{
  --glass:rgba(14,20,30,.72); --line:rgba(120,160,220,.22); --fg:#e9f0fb;
  --muted:#93a6c4; --ok:#2ecc71; --bad:#ff5252; --acc:#22a7f0; --warn:#ffb300;
}
html,body{height:100%;overflow:hidden;background:#05080d;color:var(--fg);
  font:14px/1.4 "Segoe UI",system-ui,sans-serif;user-select:none}
#stage{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;background:#05080d}
#cam{max-width:100%;max-height:100%;object-fit:contain;display:block}
.panel{position:fixed;background:var(--glass);border:1px solid var(--line);
  border-radius:14px;backdrop-filter:blur(9px);padding:8px}
/* ---- HUD ---- */
#hud{top:10px;left:10px;display:flex;gap:8px;align-items:center;flex-wrap:wrap;max-width:62vw}
#hud .chip{background:rgba(0,0,0,.35);border:1px solid var(--line);border-radius:9px;
  padding:4px 9px;font-variant-numeric:tabular-nums;font-size:13px;white-space:nowrap}
.dot{width:9px;height:9px;border-radius:50%;background:var(--bad);display:inline-block;margin-right:6px}
.dot.on{background:var(--ok);box-shadow:0 0 9px var(--ok)}
/* ---- barras de botones ---- */
#icons{top:10px;right:10px;display:flex;gap:6px}
#modes{top:64px;left:50%;transform:translateX(-50%);display:flex;gap:6px;flex-wrap:wrap;
  justify-content:center;max-width:94vw}
button{font:inherit;color:var(--fg);background:rgba(30,44,62,.85);border:1px solid var(--line);
  border-radius:10px;padding:8px 11px;cursor:pointer;transition:.12s;white-space:nowrap}
button:hover{background:rgba(46,66,92,.95)}
button:active{transform:scale(.96)}
button.on{background:var(--acc);border-color:#7fd3ff;color:#04121e;font-weight:700}
.icon{font-size:17px;padding:7px 10px;line-height:1}
/* ---- mandos ---- */
#fly{bottom:14px;left:50%;transform:translateX(-50%);display:flex;gap:8px;align-items:center}
.big{font-size:15px;font-weight:700;padding:12px 18px}
.go{background:#146c3a;border-color:#1e9c55}.go:hover{background:#1a8347}
.land{background:#134b7a;border-color:#1e6ea8}.land:hover{background:#1a5f96}
.stop{background:#7a1d1d;border-color:#a82b2b}.stop:hover{background:#962525}
.stick{position:fixed;bottom:16px;width:150px;height:150px;border-radius:50%;
  background:radial-gradient(circle,rgba(20,30,44,.75),rgba(10,16,24,.55));
  border:1px solid var(--line);backdrop-filter:blur(4px);touch-action:none}
#stickL{left:16px}#stickR{right:16px}
.knob{position:absolute;width:58px;height:58px;border-radius:50%;left:46px;top:46px;
  background:radial-gradient(circle at 35% 30%,#7fd3ff,#1b6f9e);box-shadow:0 3px 14px rgba(0,0,0,.6);
  pointer-events:none;transition:.05s}
.stick small{position:absolute;bottom:-16px;width:100%;text-align:center;color:var(--muted);font-size:11px}
/* ---- cajones ---- */
.drawer{position:fixed;top:0;right:0;height:100%;width:min(460px,92vw);background:rgba(8,12,18,.96);
  border-left:1px solid var(--line);transform:translateX(100%);transition:.22s;z-index:20;
  display:flex;flex-direction:column;padding:12px;gap:8px}
.drawer.open{transform:none}
.drawer h3{font-size:13px;text-transform:uppercase;letter-spacing:1px;color:var(--muted)}
#log{flex:1;overflow:auto;background:#05080d;border:1px solid var(--line);border-radius:10px;padding:8px;
  font:11px/1.5 Consolas,monospace;white-space:pre-wrap;word-break:break-word}
.l-TX{color:#63b3ed}.l-RX{color:#68d391}.l-ERR,.l-ERROR{color:#fc8181;font-weight:600}
.l-TIMEOUT,.l-WARN{color:#f6ad55}.l-HINT{color:#d6bcfa}.l-STEP,.l-MISION{color:#f0e68c;font-weight:600}
.l-VISION,.l-GESTO{color:#7ee0c8}.l-ORDEN,.l-USER{color:#ffd479;font-weight:600}
.l-MEDIA{color:#c3a6ff}.l-SEGURIDAD{color:#ff9f9f;font-weight:600}
.l-INFO,.l-NET,.l-VIDEO,.l-RC,.l-STATE{color:#8fa3b8}
.row{display:flex;gap:6px;align-items:center;flex-wrap:wrap}
input[type=text]{flex:1;min-width:120px;background:#05080d;border:1px solid var(--line);color:var(--fg);
  border-radius:9px;padding:8px}
input[type=range]{accent-color:var(--acc)}
select{background:#0d1420;color:var(--fg);border:1px solid var(--line);border-radius:9px;padding:7px}
label{color:var(--muted);font-size:12px;display:flex;align-items:center;gap:5px}
#toast{position:fixed;bottom:100px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,.85);
  border:1px solid var(--line);border-radius:10px;padding:10px 16px;opacity:0;transition:.25s;pointer-events:none;z-index:40}
#toast.show{opacity:1}
.mlist{background:#05080d;border:1px solid var(--line);border-radius:10px;padding:8px;
  max-height:170px;overflow:auto;font-size:12px}
/* ---- móvil ---- */
@media(max-width:900px){
  body{font-size:13px}
  .stick{width:118px;height:118px;bottom:10px}
  .knob{width:46px;height:46px;left:36px;top:36px}
  #stickL{left:8px}#stickR{right:8px}
  #hud{max-width:98vw;gap:5px;padding:5px}
  #hud .chip{font-size:11px;padding:3px 6px}
  #modes{top:auto;bottom:64px;gap:4px;max-width:99vw}
  #modes button{font-size:11px;padding:6px 8px}
  #fly{bottom:10px;left:auto;right:150px;transform:none;flex-wrap:wrap;
       justify-content:center;max-width:calc(100vw - 300px)}
  .big{padding:9px 10px;font-size:12px}
  #icons{top:auto;bottom:10px;right:auto;left:150px;flex-wrap:wrap;max-width:120px}
  .icon{font-size:15px;padding:6px 8px}
}
@media(max-width:900px) and (orientation:portrait){
  #rotate{display:flex !important}
}
#rotate{display:none;position:fixed;inset:0;z-index:60;background:#05080d;color:var(--fg);
  align-items:center;justify-content:center;text-align:center;padding:30px;font-size:18px;flex-direction:column;gap:12px}
</style></head><body>

<div id="stage"><img id="cam" src="/video" alt="video"></div>

<div id="rotate">
  <div style="font-size:48px">📱↻</div>
  <div>Gira el móvil en horizontal para pilotar</div>
  <button onclick="goFull()">⛶ Pantalla completa</button>
</div>

<div class="panel" id="hud">
  <span class="chip"><i class="dot" id="dot"></i><span id="conn">sin conectar</span></span>
  <span class="chip" id="h-bat">🔋 --</span>
  <span class="chip" id="h-alt">⛰ --</span>
  <span class="chip" id="h-spd">🚀 --</span>
  <span class="chip" id="h-time">⏱ --</span>
  <span class="chip" id="h-temp">🌡 --</span>
  <span class="chip" id="h-pad">🎯 --</span>
  <span class="chip" id="h-vid">🎥 --</span>
</div>

<div class="panel" id="icons">
  <button class="icon" id="b-photo" title="Foto">📷</button>
  <button class="icon" id="b-rec" title="Grabar">⏺</button>
  <button class="icon" id="b-full" title="Pantalla completa" onclick="goFull()">⛶</button>
  <button class="icon" id="b-phone" title="Abrir en el móvil">📱</button>
  <button class="icon" id="b-voice" title="Voz">🎤</button>
  <button class="icon" id="b-mission" title="Misión">🗺</button>
  <button class="icon" id="b-log" title="Log y diagnóstico">🐞</button>
</div>

<div class="panel" id="modes">
  <button id="m-detect">👁 Caras</button>
  <button id="m-follow">🎯 Seguir</button>
  <button id="m-color">🟢 Color</button>
  <button id="m-gesture">✋ Gestos</button>
  <button id="m-qr">🔳 QR</button>
  <button id="m-selfie">😀 Selfie</button>
  <button id="m-enroll">➕ Memorizar cara</button>
  <button id="m-src">💻 Webcam</button>
</div>

<div class="panel" id="fly">
  <button class="big go" id="b-connect">🔌 CONECTAR</button>
  <button class="big go" id="b-takeoff">🛫 DESPEGAR</button>
  <button class="big land" id="b-land">🛬 ATERRIZAR</button>
  <button class="big stop" id="b-emg">🛑</button>
  <label style="margin-left:6px">Vel <input type="range" id="speed" min="10" max="100" value="50" style="width:96px"><span id="speedv">50</span></label>
</div>

<div class="stick" id="stickL"><div class="knob" id="knobL"></div><small>subir / girar</small></div>
<div class="stick" id="stickR"><div class="knob" id="knobR"></div><small>desplazar</small></div>

<div class="drawer" id="dLog">
  <div class="row"><h3 style="flex:1">🐞 Enlace y registro</h3><button onclick="closeAll()">✕</button></div>
  <div class="row">
    <button onclick="post('/api/diag')">🩺 Diagnóstico</button>
    <button onclick="location='/api/log'">⬇ Descargar</button>
    <button onclick="copyLog()">📋 Copiar</button>
    <label><input type="checkbox" id="rcall"> ver todos los rc</label>
  </div>
  <div class="row"><input type="text" id="raw" placeholder="comando manual: battery?, rc 0 30 0 0..."><button onclick="sendRaw()">Enviar</button></div>
  <div id="log"></div>
</div>

<div class="drawer" id="dPhone">
  <div class="row"><h3 style="flex:1">📱 Abrir en el móvil</h3><button onclick="closeAll()">✕</button></div>
  <p style="color:var(--muted);font-size:13px">
    Conecta el móvil <b>a la misma WiFi que este PC</b> (la del dron, TELLO-XXXXXX)
    y escanea el código con la cámara. El PC sigue haciendo el trabajo: vídeo,
    visión y el enlace con el dron. Si el móvil no la abre, revisa el firewall de
    Windows para <code>python.exe</code> en redes privadas.
  </p>
  <img id="qr" src="/qr.png" alt="QR" style="width:min(320px,70vw);align-self:center;border-radius:12px;background:#fff;padding:6px">
  <div class="mlist" id="urls">buscando direcciones...</div>
</div>

<div class="drawer" id="dMission">
  <div class="row"><h3 style="flex:1">🗺 Misión por waypoints</h3><button onclick="closeAll()">✕</button></div>
  <div class="mlist" id="mlist">Sin pasos.</div>
  <div class="row">
    <button onclick="addStep('forward')">↑ Adelante</button><button onclick="addStep('back')">↓ Atrás</button>
    <button onclick="addStep('left')">← Izq</button><button onclick="addStep('right')">→ Der</button>
    <button onclick="addStep('up')">⬆ Subir</button><button onclick="addStep('down')">⬇ Bajar</button>
    <button onclick="addStep('ccw',90)">⟲ 90°</button><button onclick="addStep('cw',90)">⟳ 90°</button>
  </div>
  <div class="row">
    <label>cm <input type="range" id="mdist" min="20" max="300" value="50" style="width:120px"><span id="mdistv">50</span></label>
    <button onclick="post('/api/mission',{action:'clear'})">🗑 Vaciar</button>
    <button class="go" onclick="post('/api/mission',{action:'run'})">▶ Ejecutar</button>
  </div>
  <h3>Flips</h3>
  <div class="row">
    <button onclick="cmd('flip l')">↩ izq</button><button onclick="cmd('flip r')">↪ der</button>
    <button onclick="cmd('flip f')">⤴ frente</button><button onclick="cmd('flip b')">⤵ atrás</button>
  </div>
  <h3>Color a seguir</h3>
  <div class="row">
    <select id="color"><option>verde</option><option>azul</option><option>rojo</option><option>amarillo</option><option>naranja</option></select>
    <button onclick="post('/api/faces',{action:'reset'})">🧹 Borrar caras memorizadas</button>
  </div>
  <div class="row"><small id="names" style="color:var(--muted)"></small></div>
</div>

<div id="toast"></div>

<script>
const $ = s => document.querySelector(s);
let state = {};

function goFull(){
  const el = document.documentElement;
  if(document.fullscreenElement){ document.exitFullscreen(); return; }
  (el.requestFullscreen || el.webkitRequestFullscreen || (()=>{})).call(el);
  if(screen.orientation && screen.orientation.lock)
    screen.orientation.lock('landscape').catch(()=>{});
}
function toast(t){ const e=$('#toast'); e.textContent=t; e.classList.add('show');
  clearTimeout(e._t); e._t=setTimeout(()=>e.classList.remove('show'),2200); }
function post(u, body){ return fetch(u,{method:'POST',body:JSON.stringify(body||{})}); }
function cmd(c){ return post('/api/cmd',{cmd:c}); }
function closeAll(){ document.querySelectorAll('.drawer').forEach(d=>d.classList.remove('open')); }
function toggle(sel){ const d=$(sel), was=d.classList.contains('open'); closeAll(); if(!was) d.classList.add('open'); }

// ---------------- botones ----------------
$('#b-connect').onclick = () => { post('/api/connect'); toast('Conectando con el dron...'); };
$('#b-takeoff').onclick = () => cmd('takeoff');
$('#b-land').onclick    = () => cmd('land');
$('#b-emg').onclick     = () => { if(confirm('¡PARADA DE EMERGENCIA! Se paran los motores y el dron cae.')) cmd('emergency'); };
$('#b-photo').onclick   = () => post('/api/photo').then(()=>toast('📷 Foto guardada'));
$('#b-rec').onclick     = () => post('/api/record');
$('#b-phone').onclick   = () => { toggle('#dPhone'); $('#qr').src = '/qr.png?' + Date.now(); };
$('#b-mission').onclick = () => toggle('#dMission');
$('#b-log').onclick     = () => toggle('#dLog');
$('#m-detect').onclick  = () => post('/api/mode',{mode: state.mode==1?0:1});
$('#m-follow').onclick  = () => post('/api/mode',{mode: state.mode==2?0:2});
$('#m-color').onclick   = () => post('/api/mode',{mode: state.mode==3?0:3});
$('#m-gesture').onclick = () => post('/api/mode',{gesture: !state.gesture});
$('#m-qr').onclick      = () => post('/api/mode',{qr: !state.qr});
$('#m-selfie').onclick  = () => post('/api/mode',{selfie: !state.selfie});
$('#m-src').onclick     = () => post('/api/source',{source: state.source==='webcam'?'drone':'webcam'});
$('#m-enroll').onclick  = () => { const n = prompt('Nombre de la persona (mira a la cámara):'); if(n) post('/api/faces',{action:'enroll',name:n}); };
$('#color').onchange    = e => post('/api/mode',{color:e.target.value});
$('#rcall').onchange    = e => post('/api/rcall',{on:e.target.checked});
$('#speed').oninput     = e => { $('#speedv').textContent=e.target.value; };
$('#speed').onchange    = e => post('/api/speed',{speed:+e.target.value});
$('#mdist').oninput     = e => $('#mdistv').textContent = e.target.value;
function sendRaw(){ const v=$('#raw').value.trim(); if(v){ cmd(v); $('#raw').value=''; } }
$('#raw').onkeydown = e => { if(e.key==='Enter') sendRaw(); };
function addStep(kind, val){ post('/api/mission',{action:'add',kind:kind,value: val||+$('#mdist').value}); }
let allText='';
function copyLog(){ navigator.clipboard.writeText(allText).then(()=>toast('Log copiado')); }

// ---------------- joysticks ----------------
const rc = {lr:0, fb:0, ud:0, yaw:0};
let rcDirty = false;
function pushRc(){ rcDirty = true; }
setInterval(() => { if(rcDirty){ rcDirty=false; post('/api/rc', rc); } }, 60);

function stick(el, knob, axX, axY){
  let id = null, cx = 0, cy = 0, R = 0;
  const set = (x, y) => {
    const k = +$('#speed').value / 100;
    rc[axX] = Math.round(Math.max(-100, Math.min(100, x*100)) * k);
    rc[axY] = Math.round(Math.max(-100, Math.min(100, -y*100)) * k);
    knob.style.transform = `translate(${x*R}px, ${y*R}px)`;
    pushRc();
  };
  const down = e => { const r = el.getBoundingClientRect();
    cx = r.left + r.width/2; cy = r.top + r.height/2; R = r.width/2 - 28;
    id = e.pointerId; el.setPointerCapture(id); move(e); };
  const move = e => { if(e.pointerId !== id) return;
    let dx = (e.clientX - cx)/R, dy = (e.clientY - cy)/R;
    const m = Math.hypot(dx,dy); if(m > 1){ dx/=m; dy/=m; }
    set(dx, dy); };
  const up = e => { if(e.pointerId !== id) return; id = null; set(0,0); };
  el.addEventListener('pointerdown', down);
  el.addEventListener('pointermove', move);
  el.addEventListener('pointerup', up);
  el.addEventListener('pointercancel', up);
}
stick($('#stickL'), $('#knobL'), 'yaw', 'ud');
stick($('#stickR'), $('#knobR'), 'lr', 'fb');

// ---------------- teclado ----------------
const keys = {w:['fb',1], s:['fb',-1], a:['lr',-1], d:['lr',1],
  arrowup:['ud',1], arrowdown:['ud',-1], arrowleft:['yaw',-1], arrowright:['yaw',1]};
addEventListener('keydown', e => {
  if(e.target.tagName === 'INPUT') return;
  const k = e.key.toLowerCase();
  if(k === 't'){ cmd('takeoff'); return; }
  if(k === 'l'){ cmd('land'); return; }
  if(k === ' '){ e.preventDefault(); rc.lr=rc.fb=rc.ud=rc.yaw=0; pushRc(); return; }
  const m = keys[k]; if(!m) return; e.preventDefault();
  rc[m[0]] = m[1] * +$('#speed').value; pushRc();
});
addEventListener('keyup', e => { const m = keys[e.key.toLowerCase()]; if(m){ rc[m[0]]=0; pushRc(); } });

// ---------------- mando de juegos ----------------
let padPrev = [];
function padLoop(){
  const pads = navigator.getGamepads ? navigator.getGamepads() : [];
  const p = [...pads].find(Boolean);
  if(p){
    const dz = v => Math.abs(v) < 0.12 ? 0 : v;
    const k = +$('#speed').value;
    rc.yaw = Math.round(dz(p.axes[0]) * k); rc.ud = Math.round(-dz(p.axes[1]) * k);
    rc.lr  = Math.round(dz(p.axes[2]||0) * k); rc.fb = Math.round(-dz(p.axes[3]||0) * k);
    pushRc();
    const press = i => p.buttons[i] && p.buttons[i].pressed && !padPrev[i];
    if(press(0)) cmd('takeoff');
    if(press(1)) cmd('land');
    if(press(2)) post('/api/photo');
    if(press(3)) post('/api/record');
    if(press(4)) cmd('flip l');
    if(press(5)) cmd('flip r');
    if(press(9)) cmd('emergency');
    padPrev = p.buttons.map(b => b.pressed);
  }
  requestAnimationFrame(padLoop);
}
addEventListener('gamepadconnected', e => { toast('🎮 Mando conectado: ' + e.gamepad.id.slice(0,28)); });
padLoop();

// ---------------- voz (mismo vocabulario que la APK) ----------------
const VOICE = [
  [['despega','despegar','arranca','vuela'], () => cmd('takeoff')],
  [['aterriza','aterrizar','al suelo'],      () => cmd('land')],
  [['emergencia','parada'],                  () => cmd('emergency')],
  [['foto','captura','fotografia','fotografía'], () => post('/api/photo')],
  [['graba','grabar','vídeo','video'],       () => post('/api/record')],
  [['voltereta','flip'],                     () => cmd('flip f')],
  [['gira izquierda','rota izquierda'],      () => pulse('yaw',-1)],
  [['gira derecha','rota derecha'],          () => pulse('yaw',1)],
  [['sube','subir','arriba'],                () => pulse('ud',1)],
  [['baja','bajar','abajo'],                 () => pulse('ud',-1)],
  [['adelante','avanza','delante'],          () => pulse('fb',1)],
  [['atrás','atras','retrocede'],            () => pulse('fb',-1)],
  [['izquierda'],                            () => pulse('lr',-1)],
  [['derecha'],                              () => pulse('lr',1)],
  [['quieto','hover','flota','para','stop'], () => { rc.lr=rc.fb=rc.ud=rc.yaw=0; pushRc(); }],
];
function pulse(axis, sign){
  rc[axis] = sign * +$('#speed').value; pushRc();
  setTimeout(() => { rc[axis]=0; pushRc(); }, 1200);
}
let recog = null;
$('#b-voice').onclick = () => {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if(!SR){ toast('Este navegador no reconoce voz (usa Chrome/Edge)'); return; }
  if(recog){ recog.stop(); recog=null; $('#b-voice').classList.remove('on'); toast('🎤 voz off'); return; }
  recog = new SR();
  recog.lang='es-ES'; recog.continuous=true; recog.interimResults=true;
  recog.onresult = e => {
    const txt = e.results[e.results.length-1][0].transcript.toLowerCase().trim();
    for(const [keys, fn] of VOICE){
      if(keys.some(k => txt.includes(k))){ toast('🎤 '+txt); fn(); return; }
    }
  };
  recog.onerror = ev => {
    if(ev.error === 'network') toast('⚠️ El reconocimiento de voz de Chrome necesita internet; en el WiFi del dron no funciona');
    else if(ev.error === 'not-allowed') toast('⚠️ Permiso de micrófono denegado');
  };
  recog.onend = () => { if(recog) try{ recog.start(); }catch(e){} };
  try{ recog.start(); $('#b-voice').classList.add('on');
       toast('🎤 Di: despega, aterriza, sube, gira derecha, foto, quieto...'); }
  catch(e){ toast('No se pudo iniciar el micrófono'); }
};

// ---------------- sondeo ----------------
let since = 0;
async function poll(){
  try{
    const d = await (await fetch('/api/poll?since=' + since)).json();
    state = d; since = d.since;
    const t = d.tel || {};
    $('#h-bat').textContent  = (t.bat<=15?'🪫 ':'🔋 ') + (t.bat ?? '--') + '%';
    $('#h-alt').textContent  = '⛰ ' + (t.h ?? '--') + 'cm';
    $('#h-spd').textContent  = '🚀 ' + (d.kmh ?? 0).toFixed(0) + ' km/h';
    $('#h-time').textContent = '⏱ ' + (t.time ?? '--') + 's';
    $('#h-temp').textContent = '🌡 ' + (t.templ ?? '--') + '°';
    $('#h-pad').textContent  = '🎯 ' + (t.mid && t.mid !== '-1' ? t.mid : '--');
    $('#h-vid').textContent  = '🎥 ' + (d.video ? d.vfps.toFixed(0)+' fps' : 'sin vídeo')
                             + (d.vision_fps ? ' · 👁 '+d.vision_fps.toFixed(0) : '');
    $('#dot').className = 'dot' + (d.alive ? ' on' : '');
    $('#conn').textContent = d.alive ? 'dron conectado' : 'sin respuesta';
    $('#m-detect').classList.toggle('on', d.mode===1);
    $('#m-follow').classList.toggle('on', d.mode===2);
    $('#m-color').classList.toggle('on', d.mode===3);
    $('#m-gesture').classList.toggle('on', d.gesture);
    $('#m-qr').classList.toggle('on', d.qr);
    $('#m-selfie').classList.toggle('on', d.selfie);
    $('#m-src').classList.toggle('on', d.source==='webcam');
    $('#m-src').textContent = d.source==='webcam' ? '💻 Webcam ON' : '💻 Webcam';
    $('#b-rec').classList.toggle('on', d.recording);
    $('#b-rec').textContent = d.recording ? '⏹' : '⏺';
    $('#names').textContent = d.names.length ? 'Caras memorizadas: ' + d.names.join(', ') : 'Ninguna cara memorizada.';
    $('#urls').textContent = (d.urls && d.urls.length)
        ? 'Direcciones de esta estación:\n' + d.urls.join('\n')
        : 'No encuentro ninguna dirección de red. ¿Está el PC en alguna WiFi?';
    $('#mlist').textContent = d.mission.length
        ? d.mission.map((m,i)=>(i+1)+'. '+m[0]+' '+m[1]).join('\n') : 'Sin pasos.';
    if(d.lines.length){
      const box = $('#log');
      let html='';
      for(const l of d.lines){
        html += '<div class="l-'+l.kind+'">'+ (l.t+'  '+l.kind+'  '+l.msg)
                .replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c])) +'</div>';
        allText += l.t+'  '+l.kind+'  '+l.msg+'\n';
      }
      box.insertAdjacentHTML('beforeend', html);
      while(box.children.length > 2500) box.removeChild(box.firstChild);
      box.scrollTop = box.scrollHeight;
    }
  }catch(e){ $('#conn').textContent = 'servidor caído'; }
  setTimeout(poll, 300);
}
poll();

// el <img> del MJPEG se recupera solo si se corta
$('#cam').onerror = () => setTimeout(()=>{ $('#cam').src = '/video?' + Date.now(); }, 1200);
</script></body></html>
"""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    # ---- utilidades ----

    def _send(self, code, body, ctype="application/json; charset=utf-8", extra=None):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        try:
            self.wfile.write(data)
        except Exception:
            pass

    def _body(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
            return json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return {}

    # ---- GET ----

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/":
            self._send(200, PAGE, "text/html; charset=utf-8")
        elif path == "/video":
            self._stream_video()
        elif path == "/snapshot.jpg":
            jpg = VIDEO.snapshot_jpeg()
            if jpg:
                self._send(200, jpg, "image/jpeg")
            else:
                self._send(404, b"", "image/jpeg")
        elif path == "/qr.png":
            urls = lan_urls(self.server.server_address[1])
            png = qr_png(urls[0] if urls else "http://127.0.0.1:%d" % WEB_PORT)
            if png:
                self._send(200, png, "image/png")
            else:
                self._send(404, b"", "image/png")
        elif path == "/api/poll":
            self._poll()
        elif path == "/api/log":
            self._send(200, LOG.dump(), "text/plain; charset=utf-8",
                       {"Content-Disposition": 'attachment; filename="%s"'
                        % os.path.basename(LOG.path)})
        else:
            self._send(404, "{}")

    def _stream_video(self):
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        seq = 0
        try:
            while True:
                jpg, seq = VIDEO.latest_jpeg(seq, timeout=5.0)
                if jpg is None:
                    continue
                self.wfile.write(b"--frame\r\nContent-Type: image/jpeg\r\n"
                                 b"Content-Length: " + str(len(jpg)).encode() + b"\r\n\r\n")
                self.wfile.write(jpg)
                self.wfile.write(b"\r\n")
        except Exception:
            pass   # el navegador ha cerrado la pestaña

    def _poll(self):
        since = 0
        if "since=" in self.path:
            try:
                since = int(self.path.split("since=")[1].split("&")[0])
            except ValueError:
                pass
        lines = LOG.since(since)
        nxt = lines[-1]["i"] + 1 if lines else since
        t = TELLO.telemetry
        try:
            vgx, vgy, vgz = (float(t.get("vgx", 0)), float(t.get("vgy", 0)), float(t.get("vgz", 0)))
            kmh = (vgx * vgx + vgy * vgy + vgz * vgz) ** 0.5 * 0.036
        except (TypeError, ValueError):
            kmh = 0.0
        self._send(200, json.dumps({
            "lines": lines, "since": nxt, "tel": t, "kmh": kmh,
            "alive": bool(TELLO.recv > 0 and TELLO.sdk_ok),
            "sent": TELLO.sent, "recv": TELLO.recv, "timeouts": TELLO.timeouts,
            "rc_enabled": TELLO.rc_enabled,
            "mode": VISION.mode, "gesture": VISION.gesture_enabled,
            "qr": VISION.qr_enabled, "selfie": VISION.selfie,
            "color": VISION.color, "names": VISION.known_names(),
            "vision_fps": round(VISION.fps, 1), "detector": VISION.detector,
            "video": VIDEO.connected, "vfps": round(VIDEO.fps, 1),
            "source": VIDEO.source, "recording": VIDEO.is_recording(),
            "mission": ST.mission, "mission_running": ST.mission_running,
            "speed": ST.speed, "path": os.path.basename(LOG.path),
            "urls": lan_urls(self.server.server_address[1]),
        }))

    # ---- POST ----

    def do_POST(self):
        path = self.path.split("?")[0]
        b = self._body()
        out = {}

        if path == "/api/connect":
            threading.Thread(target=connect_all, daemon=True).start()
        elif path == "/api/cmd":
            send_command(str(b.get("cmd", "")))
        elif path == "/api/rc":
            TELLO.rc = [int(b.get("lr", 0)), int(b.get("fb", 0)),
                        int(b.get("ud", 0)), int(b.get("yaw", 0))]
            if VISION.controls_drone() and any(TELLO.rc):
                global _warned_at
                if time.time() - _warned_at > 3:
                    _warned_at = time.time()
                    LOG.add("WARN", "joystick manual con la visión al mando (%s): manda la visión"
                            % vz.MODE_NAMES.get(VISION.mode))
        elif path == "/api/speed":
            ST.speed = max(10, min(100, int(b.get("speed", 50))))
            send_command("speed %d" % ST.speed, wait=1.0)
        elif path == "/api/diag":
            threading.Thread(target=TELLO.diagnose, daemon=True).start()
        elif path == "/api/rcall":
            TELLO.log_all_rc = bool(b.get("on"))
        elif path == "/api/mode":
            if "mode" in b:
                VISION.set_mode(int(b["mode"]))
                if not VISION.controls_drone():
                    TELLO.rc = [0, 0, 0, 0]
            if "gesture" in b:
                VISION.set_gesture(b["gesture"])
            if "qr" in b:
                VISION.set_qr(b["qr"])
            if "selfie" in b:
                VISION.set_selfie(b["selfie"])
            if "color" in b:
                VISION.set_color(str(b["color"]))
            VIDEO.start()
        elif path == "/api/source":
            VIDEO.set_source(str(b.get("source", "drone")))
            VIDEO.start()
        elif path == "/api/faces":
            act = b.get("action")
            if act == "enroll":
                VISION.enroll_next(str(b.get("name", "?")))
                if VISION.mode == vz.MODE_OFF:
                    VISION.set_mode(vz.MODE_DETECT)
                VIDEO.start()
            elif act == "reset":
                VISION.reset_ids()
        elif path == "/api/photo":
            out["path"] = VIDEO.take_photo() or ""
        elif path == "/api/record":
            if VIDEO.is_recording():
                out["path"] = VIDEO.stop_recording() or ""
            else:
                out["path"] = VIDEO.start_recording() or ""
        elif path == "/api/mission":
            act = b.get("action")
            if act == "add":
                ST.mission.append([str(b.get("kind", "forward")), int(b.get("value", 50))])
            elif act == "clear":
                ST.mission.clear()
                LOG.add("MISION", "misión vaciada")
            elif act == "run":
                ST.run_mission()
        else:
            self._send(404, "{}")
            return
        self._send(200, json.dumps(out))


class WebServer(ThreadingHTTPServer):
    allow_reuse_address = False
    daemon_threads = True


def lan_urls(port):
    """URLs por las que se puede abrir la estación desde el móvil."""
    import socket
    urls, ips = [], set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except Exception:
        pass
    try:                       # la IP por la que se sale hacia el dron
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("192.168.10.1", 8889))
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:
        pass
    # primero la del WiFi del dron: es la que ve el móvil conectado al Tello
    for ip in sorted(ips, key=lambda i: (not i.startswith("192.168.10."), i)):
        if not ip.startswith("127."):
            urls.append("http://%s:%d" % (ip, port))
    return urls


def open_web_server():
    for port in range(WEB_PORT, WEB_PORT + 12):
        try:
            srv = WebServer((BIND, port), Handler)
            if port != WEB_PORT:
                LOG.add("WARN", "el puerto %d estaba ocupado; uso el %d" % (WEB_PORT, port))
            return srv, port
        except OSError:
            continue
    raise SystemExit("no hay puertos libres entre %d y %d" % (WEB_PORT, WEB_PORT + 11))


def main():
    LOG.add("INFO", "=== TELLO STATION ===  log: %s" % LOG.path)
    LOG.add("INFO", "visión: detector=%s  identificación=%s  caras=%s"
            % (VISION.detector,
               "sí" if VISION.has_face_module else "NO (falta opencv-contrib)",
               ", ".join(VISION.known_names()) or "ninguna"))
    TELLO.start()
    VIDEO.start()
    threading.Thread(target=ST.control_loop, name="control", daemon=True).start()

    srv, port = open_web_server()
    url = "http://127.0.0.1:%d" % port
    LOG.add("INFO", "estación en %s   (Ctrl+C para salir)" % url)
    for u in lan_urls(port):
        LOG.add("INFO", "📱 desde el móvil (misma WiFi): %s" % u)
    if BIND != "127.0.0.1":
        LOG.add("INFO", "cualquiera en esta WiFi puede abrirla; "
                        "para limitarla a este PC: TELLO_BIND=127.0.0.1")
    if "--no-browser" not in sys.argv:
        try:
            webbrowser.open(url)
        except Exception:
            pass
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        LOG.add("INFO", "cerrando...")
    finally:
        VIDEO.stop()
        TELLO.stop()
        srv.server_close()
        print("\nLog: %s\nFotos y vídeos: %s" % (LOG.path, MEDIA_DIR))


if __name__ == "__main__":
    main()
