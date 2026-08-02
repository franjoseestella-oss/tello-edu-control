#!/usr/bin/env python3
"""
Tello Debug Station — consola de depuracion del DJI Tello desde el PC.

Habla el mismo protocolo que la APK (UDP SDK 2.0):
    comandos -> 192.168.10.1:8889     estado <- puerto local 8890

Registra ABSOLUTAMENTE TODO con marca de tiempo en milisegundos:
cada paquete enviado, cada respuesta (con latencia), cada timeout, la
telemetria y los errores de socket. El log se guarda en pc/logs/*.log
y tambien se ve en vivo en la interfaz web.

Uso:
    1. Conecta el PC al WiFi del dron (TELLO-XXXXXX).
    2. python tello_debug.py
    3. Se abre solo http://127.0.0.1:8770  (si no, abrelo a mano).

Sin dependencias: solo la libreria estandar de Python 3.8+.
"""

import json
import os
import socket
import sys
import threading
import time
import webbrowser
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TELLO_IP = "192.168.10.1"
CMD_PORT = 8889
STATE_PORT = 8890
WEB_PORT = int(os.environ.get("TELLO_WEB_PORT", "8770"))

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LOG_DIR = os.path.join(BASE_DIR, "logs")

# Comandos que devuelven un valor en vez de "ok"
QUERY_CMDS = ("battery?", "speed?", "time?", "wifi?", "sdk?", "sn?", "height?",
              "temp?", "attitude?", "baro?", "acceleration?", "tof?")


# --------------------------------------------------------------------------
# Log
# --------------------------------------------------------------------------

class Log:
    """Log en fichero + buffer en memoria para la web."""

    def __init__(self):
        # La consola de Windows es cp1252 y revienta con los emojis del log.
        for stream in (sys.stdout, sys.stderr):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except Exception:
                pass
        os.makedirs(LOG_DIR, exist_ok=True)
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.path = os.path.join(LOG_DIR, "tello_debug_%s.log" % stamp)
        self.file = open(self.path, "a", encoding="utf-8", buffering=1)
        self.lines = []            # [{"i","t","kind","msg"}]
        self.lock = threading.Lock()
        self.t0 = time.time()

    def add(self, kind, msg):
        now = time.time()
        ts = datetime.fromtimestamp(now).strftime("%H:%M:%S.") + "%03d" % int((now % 1) * 1000)
        rel = now - self.t0
        text = "[%s] +%7.3fs %-6s %s" % (ts, rel, kind, msg)
        with self.lock:
            i = len(self.lines)
            self.lines.append({"i": i, "t": ts, "kind": kind, "msg": msg})
            if len(self.lines) > 20000:
                del self.lines[:5000]
        try:
            self.file.write(text + "\n")
        except Exception:
            pass
        try:
            print(text, flush=True)
        except Exception:
            # consola sin unicode: mejor perder un acento que tirar el programa
            print(text.encode("ascii", "replace").decode("ascii"), flush=True)

    def since(self, idx):
        with self.lock:
            if not self.lines:
                return []
            first = self.lines[0]["i"]
            start = max(0, idx - first)
            return list(self.lines[start:])

    def dump(self):
        try:
            self.file.flush()
            with open(self.path, "r", encoding="utf-8") as f:
                return f.read()
        except Exception as e:
            return "no se pudo leer el log: %s" % e


LOG = Log()


# --------------------------------------------------------------------------
# Cliente Tello
# --------------------------------------------------------------------------

class Tello:
    def __init__(self):
        self.cmd_sock = None
        self.state_sock = None
        self.local_cmd_port = None
        self.running = False
        self.sdk_ok = False              # ha respondido al menos una vez
        self.telemetry = {}
        self.telemetry_ms = 0
        self.sent = 0
        self.recv = 0
        self.timeouts = 0

        self.pending = None              # comando esperando respuesta
        self.pending_at = 0.0
        self.resp_event = threading.Event()
        self.last_resp = None
        self.lock = threading.Lock()

        # Estado del joystick que reenvia el bucle rc (igual que la APK)
        self.rc = [0, 0, 0, 0]
        self.rc_enabled = False
        self.log_all_rc = False
        self._last_rc_logged = None
        self._rc_silenced = 0

    # ---- sockets ----

    def start(self):
        if self.running:
            return
        try:
            self.cmd_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.cmd_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                self.cmd_sock.bind(("", CMD_PORT))
                self.local_cmd_port = CMD_PORT
            except OSError as e:
                # Puerto ocupado (otra instancia?): usamos uno efimero.
                self.cmd_sock.bind(("", 0))
                self.local_cmd_port = self.cmd_sock.getsockname()[1]
                LOG.add("WARN", "puerto local 8889 ocupado (%s); uso el %d" % (e, self.local_cmd_port))
        except Exception as e:
            LOG.add("ERROR", "no puedo abrir el socket de comandos: %s" % e)
            return

        try:
            self.state_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.state_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.state_sock.bind(("", STATE_PORT))
            self.state_sock.settimeout(2.0)
        except Exception as e:
            self.state_sock = None
            LOG.add("WARN", "no puedo escuchar telemetria en 8890: %s "
                            "(la APK u otra instancia lo tienen abierto?)" % e)

        self.running = True
        threading.Thread(target=self._rx_loop, name="rx", daemon=True).start()
        if self.state_sock:
            threading.Thread(target=self._state_loop, name="state", daemon=True).start()
        threading.Thread(target=self._rc_loop, name="rc", daemon=True).start()

        LOG.add("INFO", "sockets listos: comandos %s:%d desde puerto local %d, estado en %d"
                % (TELLO_IP, CMD_PORT, self.local_cmd_port, STATE_PORT))
        self.log_network()

    def log_network(self):
        try:
            host = socket.gethostname()
            ips = set()
            for info in socket.getaddrinfo(host, None, socket.AF_INET):
                ips.add(info[4][0])
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                s.connect((TELLO_IP, CMD_PORT))
                route_ip = s.getsockname()[0]
            finally:
                s.close()
            LOG.add("NET", "IPs del PC: %s | ruta hacia el dron por: %s"
                    % (", ".join(sorted(ips)) or "?", route_ip))
            if not route_ip.startswith("192.168.10."):
                LOG.add("WARN", "tu IP hacia el dron es %s y deberia ser 192.168.10.x -> "
                                "NO estas en el WiFi del Tello (o hay otra red con prioridad)" % route_ip)
        except Exception as e:
            LOG.add("WARN", "no pude comprobar la red: %s" % e)

    # ---- envio ----

    def send(self, cmd, wait=None, quiet=False):
        """Envia un comando. Si wait (segundos), espera la respuesta y devuelve (resp, ms)."""
        if not self.cmd_sock:
            LOG.add("ERROR", "socket cerrado, no envio '%s'" % cmd)
            return None, 0
        data = cmd.encode("utf-8")
        with self.lock:
            self.pending = cmd
            self.pending_at = time.time()
            self.last_resp = None
            self.resp_event.clear()
        try:
            self.cmd_sock.sendto(data, (TELLO_IP, CMD_PORT))
            self.sent += 1
            if not quiet:
                LOG.add("TX", "'%s'  (%d bytes -> %s:%d)" % (cmd, len(data), TELLO_IP, CMD_PORT))
        except Exception as e:
            LOG.add("ERROR", "fallo al enviar '%s': %s" % (cmd, e))
            return None, 0

        if wait:
            got = self.resp_event.wait(wait)
            ms = (time.time() - self.pending_at) * 1000
            if not got:
                self.timeouts += 1
                LOG.add("TIMEOUT", "'%s' sin respuesta en %d ms  (enviados %d / recibidos %d / timeouts %d)"
                        % (cmd, int(wait * 1000), self.sent, self.recv, self.timeouts))
                if self.timeouts == 3 and self.recv == 0:
                    LOG.add("HINT", "0 respuestas del dron: revisa 1) estar en el WiFi TELLO-XXXXXX, "
                                    "2) el firewall de Windows bloqueando UDP entrante de python.exe, "
                                    "3) que la APK no este conectada a la vez")
                return None, ms
            return self.last_resp, ms
        return None, 0

    def _rx_loop(self):
        while self.running:
            try:
                data, addr = self.cmd_sock.recvfrom(2048)
            except OSError:
                if self.running:
                    time.sleep(0.05)
                continue
            except Exception as e:
                LOG.add("ERROR", "rx: %s" % e)
                continue
            resp = data.decode("utf-8", "replace").strip()
            self.recv += 1
            self.sdk_ok = True
            with self.lock:
                cmd = self.pending
                ms = (time.time() - self.pending_at) * 1000 if self.pending_at else 0
                self.last_resp = resp
                self.resp_event.set()
            src = "" if addr[0] == TELLO_IP else "  (!! viene de %s:%d, no del dron)" % addr
            kind = "ERR" if resp.lower().startswith("error") else "RX"
            LOG.add(kind, "'%s' -> '%s'   (%.0f ms)%s" % (cmd, resp, ms, src))
            if resp.lower().startswith("error"):
                LOG.add("HINT", self._explain_error(cmd, resp))

    @staticmethod
    def _explain_error(cmd, resp):
        r = resp.lower()
        if "motor stop" in r:
            return ("'error Motor stop': el dron cree que esta parado en el suelo. "
                    "Tras un aterrizaje o un fallo hay que volver a mandar 'command' y despegar.")
        if "not joystick" in r:
            return ("'error Not joystick': el Tello esta esperando paquetes 'rc' y llega otra cosa, "
                    "o al reves. Suspende el bucle rc antes de mandar comandos absolutos (up/cw/flip).")
        if "auto land" in r:
            return "El dron ha aterrizado solo (bateria o timeout de 15 s sin comandos)."
        if "no valid imu" in r:
            return "IMU sin calibrar o superficie inclinada: calibra el IMU en la app oficial y ponlo en plano."
        if "unactive" in r or "unactived" in r:
            return "Dron no activado: hay que activarlo una vez con la app oficial DJI Tello."
        if "out of range" in r:
            return "Parametro fuera de rango: movimientos 20-500 cm, giros 1-360, rc -100..100."
        if "low battery" in r or "battery" in r:
            return "Bateria insuficiente para ese comando (el Tello bloquea despegue por debajo de ~10-15%)."
        return "El dron RECHAZO '%s' con '%s'." % (cmd, resp)

    def _state_loop(self):
        misses = 0
        while self.running:
            try:
                data, _ = self.state_sock.recvfrom(2048)
            except socket.timeout:
                misses += 1
                if misses in (3, 10):
                    LOG.add("WARN", "sin telemetria en el puerto 8890 desde hace %ds "
                                    "(el dron solo la emite en modo SDK)" % (misses * 2))
                continue
            except Exception:
                continue
            misses = 0
            txt = data.decode("utf-8", "replace").strip()
            d = {}
            for kv in txt.split(";"):
                if ":" in kv:
                    k, v = kv.split(":", 1)
                    d[k.strip()] = v.strip()
            self.telemetry = d
            self.telemetry_ms = time.time()

    # ---- bucle rc (identico al de la APK: 20 Hz + keepalive) ----

    def _rc_loop(self):
        last_keep = 0.0
        while self.running:
            if self.rc_enabled:
                now = time.time()
                cur = tuple(self.rc)
                changed = cur != self._last_rc_logged
                if changed or now - last_keep > 1.0:
                    cmd = "rc %d %d %d %d" % cur
                    quiet = not (self.log_all_rc or changed)
                    if quiet:
                        self._rc_silenced += 1
                    self.send(cmd, quiet=quiet)
                    if changed:
                        if self._rc_silenced:
                            LOG.add("INFO", "(%d paquetes rc de keepalive omitidos del log)" % self._rc_silenced)
                            self._rc_silenced = 0
                        self._last_rc_logged = cur
                    last_keep = now
            time.sleep(0.05)

    # ---- secuencias ----

    def connect_sequence(self):
        LOG.add("STEP", "=== CONECTAR: entrando en modo SDK ===")
        ok = False
        for i in range(1, 6):
            resp, ms = self.send("command", wait=1.2)
            if resp and resp.lower().startswith("ok"):
                ok = True
                LOG.add("STEP", "modo SDK OK al intento %d (%.0f ms)" % (i, ms))
                break
            LOG.add("STEP", "intento %d/5 sin 'ok'" % i)
        if not ok:
            LOG.add("STEP", "=== NO responde al comando 'command'. Nada mas funcionara. ===")
            return False
        for q in ("sdk?", "sn?", "battery?", "speed?", "wifi?"):
            self.send(q, wait=1.0)
            time.sleep(0.1)
        self.send("streamon", wait=1.0)
        self.rc_enabled = True
        LOG.add("STEP", "=== bucle rc activado (20 Hz, igual que la APK) ===")
        return True

    def diagnose(self):
        """Bateria de pruebas SIN volar. Esto es lo que hay que mandar para analizar."""
        LOG.add("STEP", "############ DIAGNOSTICO ############")
        LOG.add("INFO", "python %s en %s" % (sys.version.split()[0], sys.platform))
        self.log_network()
        prev_rc = self.rc_enabled
        self.rc_enabled = False
        LOG.add("STEP", "bucle rc suspendido durante el diagnostico")

        pruebas = [
            ("command", "entrar en modo SDK"),
            ("sdk?", "version del SDK (falla en Tello normal antiguo: solo EDU)"),
            ("sn?", "numero de serie"),
            ("battery?", "bateria"),
            ("speed?", "velocidad configurada"),
            ("wifi?", "relacion senal/ruido del WiFi"),
            ("time?", "tiempo de motores"),
            ("height?", "altura"),
            ("tof?", "sensor de distancia al suelo"),
            ("rc 0 0 0 0", "paquete de joystick neutro (no responde nunca: es normal)"),
            ("speed 50", "fijar velocidad"),
        ]
        for cmd, desc in pruebas:
            LOG.add("STEP", "-> %s : %s" % (cmd, desc))
            wait = 0.4 if cmd.startswith("rc") else 2.0
            self.send(cmd, wait=wait)
            time.sleep(0.15)

        # Latencia: 20 pings seguidos para ver perdida de paquetes
        LOG.add("STEP", "-> 20 x 'command' para medir latencia y perdida de paquetes")
        lat, perdidos = [], 0
        for i in range(20):
            r, ms = self.send("command", wait=0.8, quiet=True)
            if r:
                lat.append(ms)
            else:
                perdidos += 1
            time.sleep(0.05)
        if lat:
            LOG.add("STEP", "latencia min/med/max = %.0f / %.0f / %.0f ms | perdidos %d/20"
                    % (min(lat), sum(lat) / len(lat), max(lat), perdidos))
        else:
            LOG.add("STEP", "NINGUNA respuesta en 20 intentos: no hay comunicacion con el dron")

        t = self.telemetry
        if t:
            LOG.add("STEP", "telemetria actual: bat=%s%% h=%scm tof=%s temp=%s-%s time=%ss" %
                    (t.get("bat"), t.get("h"), t.get("tof"), t.get("templ"), t.get("temph"), t.get("time")))
        else:
            LOG.add("STEP", "NO llega telemetria por el 8890 (firewall o no esta en modo SDK)")

        LOG.add("STEP", "resumen: enviados=%d recibidos=%d timeouts=%d" % (self.sent, self.recv, self.timeouts))
        LOG.add("STEP", "############ FIN DEL DIAGNOSTICO ############")
        self.rc_enabled = prev_rc

    def stop(self):
        self.running = False
        self.rc_enabled = False
        try:
            if self.cmd_sock:
                self.cmd_sock.close()
        except Exception:
            pass
        try:
            if self.state_sock:
                self.state_sock.close()
        except Exception:
            pass


TELLO = Tello()


# --------------------------------------------------------------------------
# Interfaz web
# --------------------------------------------------------------------------

PAGE = r"""<!doctype html>
<html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Tello Debug Station</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#0b1016;color:#e6edf3;font:14px/1.45 "Segoe UI",system-ui,sans-serif}
header{display:flex;align-items:center;gap:14px;padding:10px 16px;background:#121a24;border-bottom:1px solid #22303f;flex-wrap:wrap}
h1{font-size:16px;margin:0;letter-spacing:.5px}
.dot{width:10px;height:10px;border-radius:50%;background:#c2402a;display:inline-block;margin-right:6px}
.dot.on{background:#2ecc71;box-shadow:0 0 8px #2ecc71}
.hud{display:flex;gap:14px;margin-left:auto;flex-wrap:wrap;font-variant-numeric:tabular-nums}
.hud span{background:#0b1016;border:1px solid #22303f;border-radius:6px;padding:3px 9px}
main{display:grid;grid-template-columns:340px 1fr;gap:12px;padding:12px;height:calc(100vh - 52px)}
@media(max-width:900px){main{grid-template-columns:1fr;height:auto}}
.card{background:#121a24;border:1px solid #22303f;border-radius:10px;padding:12px;display:flex;flex-direction:column;min-height:0}
.card h2{font-size:12px;text-transform:uppercase;letter-spacing:1px;color:#7d8fa3;margin:0 0 8px}
button{font:inherit;color:#e6edf3;background:#1c2a38;border:1px solid #2c3e50;border-radius:8px;padding:9px 10px;cursor:pointer}
button:hover{background:#25384b}button:active{transform:translateY(1px)}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}
.grid3{display:grid;grid-template-columns:repeat(3,1fr);gap:6px}
.b-go{background:#14532d;border-color:#1a6b3a}.b-go:hover{background:#1a6b3a}
.b-land{background:#1e3a5f;border-color:#2a5286}.b-land:hover{background:#2a5286}
.b-stop{background:#5f1e1e;border-color:#8b2b2b}.b-stop:hover{background:#8b2b2b}
.b-diag{background:#4a3a10;border-color:#6d551a}.b-diag:hover{background:#6d551a}
input[type=text]{flex:1;background:#0b1016;border:1px solid #2c3e50;color:#e6edf3;border-radius:8px;padding:9px;font:inherit}
.row{display:flex;gap:8px;margin-top:8px}
#log{flex:1;overflow:auto;background:#080c11;border:1px solid #22303f;border-radius:8px;padding:8px;
     font:12px/1.5 Consolas,"Cascadia Mono",monospace;white-space:pre-wrap;word-break:break-word;min-height:240px}
.l-TX{color:#63b3ed}.l-RX{color:#68d391}.l-ERR{color:#fc8181;font-weight:600}
.l-TIMEOUT{color:#f6ad55}.l-WARN{color:#f6ad55}.l-ERROR{color:#fc8181}
.l-HINT{color:#d6bcfa}.l-STEP{color:#f0e68c;font-weight:600}.l-INFO,.l-NET{color:#8fa3b8}
label{color:#8fa3b8;font-size:12px;display:flex;align-items:center;gap:5px}
.pad{display:grid;grid-template-columns:repeat(3,1fr);gap:5px;margin-top:6px}
.pad button{padding:12px 0;font-size:16px}
small{color:#5f7183}
</style></head><body>
<header>
  <h1>🛸 TELLO DEBUG STATION</h1>
  <span><i class="dot" id="dot"></i><span id="conn">sin conectar</span></span>
  <div class="hud">
    <span id="h-bat">🔋 --</span><span id="h-alt">⛰ --</span><span id="h-tof">📏 --</span>
    <span id="h-time">⏱ --</span><span id="h-temp">🌡 --</span><span id="h-pkt">📡 --</span>
  </div>
</header>
<main>
  <div class="card">
    <h2>Control</h2>
    <div class="grid">
      <button class="b-go" onclick="api('/api/connect')">🔌 CONECTAR (SDK)</button>
      <button class="b-diag" onclick="api('/api/diag')">🩺 DIAGNÓSTICO</button>
      <button class="b-go" onclick="cmd('takeoff')">🛫 DESPEGAR</button>
      <button class="b-land" onclick="cmd('land')">🛬 ATERRIZAR</button>
      <button class="b-stop" onclick="if(confirm('¡Se paran los motores y el dron cae!'))cmd('emergency')">🛑 EMERGENCIA</button>
      <button onclick="cmd('battery?')">🔋 battery?</button>
    </div>

    <h2 style="margin-top:14px">Joystick (mantén pulsado)</h2>
    <small>Izquierda: subir/bajar y girar &nbsp;·&nbsp; Derecha: desplazamiento. También W A S D / flechas.</small>
    <div style="display:flex;gap:10px">
      <div class="pad" style="flex:1">
        <i></i><button data-rc="ud:+">▲</button><i></i>
        <button data-rc="yaw:-">⟲</button><button onclick="zero()">■</button><button data-rc="yaw:+">⟳</button>
        <i></i><button data-rc="ud:-">▼</button><i></i>
      </div>
      <div class="pad" style="flex:1">
        <i></i><button data-rc="fb:+">▲</button><i></i>
        <button data-rc="lr:-">◀</button><button onclick="zero()">■</button><button data-rc="lr:+">▶</button>
        <i></i><button data-rc="fb:-">▼</button><i></i>
      </div>
    </div>
    <div class="row"><label style="flex:1">Fuerza <input type="range" id="pow" min="10" max="100" value="50" style="flex:1"></label><span id="powv">50</span></div>

    <h2 style="margin-top:14px">Flips y giros</h2>
    <div class="grid3">
      <button onclick="cmd('flip l')">flip ←</button><button onclick="cmd('flip f')">flip ↑</button><button onclick="cmd('flip r')">flip →</button>
      <button onclick="cmd('ccw 90')">⟲ 90°</button><button onclick="cmd('up 50')">up 50</button><button onclick="cmd('cw 90')">⟳ 90°</button>
      <button onclick="cmd('forward 50')">fwd 50</button><button onclick="cmd('down 50')">down 50</button><button onclick="cmd('back 50')">back 50</button>
    </div>

    <h2 style="margin-top:14px">Comando manual</h2>
    <div class="row">
      <input type="text" id="raw" placeholder="p.ej.  command   ·   rc 0 50 0 0" onkeydown="if(event.key=='Enter')sendRaw()">
      <button onclick="sendRaw()">Enviar</button>
    </div>
    <div class="row">
      <label><input type="checkbox" id="rcloop" onchange="api('/api/rcloop?on='+(this.checked?1:0))"> bucle rc 20 Hz</label>
      <label><input type="checkbox" id="rcall" onchange="api('/api/rcall?on='+(this.checked?1:0))"> registrar todos los rc</label>
    </div>
  </div>

  <div class="card">
    <h2>Log en vivo <small id="logpath"></small></h2>
    <div id="log"></div>
    <div class="row">
      <button onclick="location='/api/log'">⬇ Descargar log</button>
      <button onclick="copyLog()">📋 Copiar todo</button>
      <button onclick="document.getElementById('log').innerHTML=''">🧹 Limpiar vista</button>
      <label style="margin-left:auto"><input type="checkbox" id="auto" checked> auto-scroll</label>
    </div>
  </div>
</main>
<script>
let since = 0, allText = "";
const $ = id => document.getElementById(id);

function api(u, body){ return fetch(u, body?{method:'POST',body:JSON.stringify(body)}:{method:'POST'}); }
function cmd(c){ return api('/api/cmd', {cmd:c}); }
function sendRaw(){ const v = $('raw').value.trim(); if(v){ cmd(v); $('raw').value=''; } }
function copyLog(){ navigator.clipboard.writeText(allText).then(()=>alert('Log copiado al portapapeles')); }

// --- joystick ---
const rc = {lr:0, fb:0, ud:0, yaw:0};
function push(){ api('/api/rc', rc); }
function zero(){ rc.lr=rc.fb=rc.ud=rc.yaw=0; push(); }
function set(axis, sign){ rc[axis] = sign * parseInt($('pow').value); push(); }
$('pow').oninput = e => $('powv').textContent = e.target.value;

document.querySelectorAll('[data-rc]').forEach(b => {
  const [axis, s] = b.dataset.rc.split(':');
  const on = e => { e.preventDefault(); set(axis, s === '+' ? 1 : -1); };
  const off = e => { e.preventDefault(); rc[axis] = 0; push(); };
  b.addEventListener('mousedown', on); b.addEventListener('touchstart', on, {passive:false});
  b.addEventListener('mouseup', off); b.addEventListener('mouseleave', off);
  b.addEventListener('touchend', off); b.addEventListener('touchcancel', off);
});

const keys = {w:['fb',1], s:['fb',-1], a:['lr',-1], d:['lr',1],
              arrowup:['ud',1], arrowdown:['ud',-1], arrowleft:['yaw',-1], arrowright:['yaw',1]};
addEventListener('keydown', e => {
  if (e.target.tagName === 'INPUT') return;
  const k = keys[e.key.toLowerCase()]; if(!k) return; e.preventDefault(); set(k[0], k[1]);
});
addEventListener('keyup', e => {
  const k = keys[e.key.toLowerCase()]; if(!k) return; rc[k[0]] = 0; push();
});

// --- log + telemetria ---
function esc(s){ return s.replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c])); }
async function poll(){
  try{
    const r = await fetch('/api/poll?since=' + since);
    const d = await r.json();
    since = d.since;
    if (d.lines.length){
      const box = $('log');
      let html = '';
      for (const l of d.lines){
        html += '<div class="l-' + l.kind + '">' + esc(l.t + '  ' + l.kind.padEnd(7) + ' ' + l.msg) + '</div>';
        allText += l.t + '  ' + l.kind + ' ' + l.msg + '\n';
      }
      box.insertAdjacentHTML('beforeend', html);
      while (box.children.length > 3000) box.removeChild(box.firstChild);
      if ($('auto').checked) box.scrollTop = box.scrollHeight;
    }
    const t = d.tel || {};
    $('h-bat').textContent  = '🔋 ' + (t.bat ?? '--') + '%';
    $('h-alt').textContent  = '⛰ ' + (t.h ?? '--') + 'cm';
    $('h-tof').textContent  = '📏 ' + (t.tof ?? '--') + 'cm';
    $('h-time').textContent = '⏱ ' + (t.time ?? '--') + 's';
    $('h-temp').textContent = '🌡 ' + (t.templ ?? '--') + '°';
    $('h-pkt').textContent  = '📡 tx' + d.sent + ' rx' + d.recv + ' to' + d.timeouts;
    $('dot').className = 'dot' + (d.alive ? ' on' : '');
    $('conn').textContent = d.alive ? 'dron respondiendo' : 'sin respuesta del dron';
    $('rcloop').checked = d.rc_enabled;
    $('logpath').textContent = d.path;
  }catch(e){ $('conn').textContent = 'servidor caído'; }
  setTimeout(poll, 250);
}
poll();
</script></body></html>
"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass  # no ensuciar el log con las peticiones HTTP

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

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/":
            self._send(200, PAGE, "text/html; charset=utf-8")
        elif path == "/api/poll":
            since = 0
            if "since=" in self.path:
                try:
                    since = int(self.path.split("since=")[1].split("&")[0])
                except ValueError:
                    pass
            lines = LOG.since(since)
            nxt = lines[-1]["i"] + 1 if lines else since
            alive = TELLO.recv > 0 and (time.time() - TELLO.telemetry_ms < 5 or TELLO.sdk_ok)
            self._send(200, json.dumps({
                "lines": lines, "since": nxt, "tel": TELLO.telemetry,
                "sent": TELLO.sent, "recv": TELLO.recv, "timeouts": TELLO.timeouts,
                "alive": bool(alive), "rc_enabled": TELLO.rc_enabled,
                "path": os.path.basename(LOG.path),
            }))
        elif path == "/api/log":
            self._send(200, LOG.dump(), "text/plain; charset=utf-8",
                       {"Content-Disposition": 'attachment; filename="%s"' % os.path.basename(LOG.path)})
        else:
            self._send(404, "{}")

    def do_POST(self):
        path = self.path.split("?")[0]
        if path == "/api/cmd":
            cmd = str(self._body().get("cmd", "")).strip()
            if cmd:
                LOG.add("USER", "boton/consola -> '%s'" % cmd)
                if not cmd.startswith("rc"):
                    # los comandos absolutos no conviven con el bucle rc
                    prev = TELLO.rc_enabled
                    if prev and cmd.split()[0] in ("up", "down", "left", "right", "forward",
                                                   "back", "cw", "ccw", "flip", "go", "curve"):
                        TELLO.rc_enabled = False
                        LOG.add("INFO", "bucle rc suspendido para ejecutar '%s'" % cmd)
                        threading.Timer(4.0, lambda: setattr(TELLO, "rc_enabled", prev)).start()
                threading.Thread(target=lambda: TELLO.send(cmd, wait=7.0), daemon=True).start()
            self._send(200, "{}")
        elif path == "/api/rc":
            b = self._body()
            TELLO.rc = [int(b.get("lr", 0)), int(b.get("fb", 0)),
                        int(b.get("ud", 0)), int(b.get("yaw", 0))]
            self._send(200, "{}")
        elif path == "/api/connect":
            threading.Thread(target=TELLO.connect_sequence, daemon=True).start()
            self._send(200, "{}")
        elif path == "/api/diag":
            threading.Thread(target=TELLO.diagnose, daemon=True).start()
            self._send(200, "{}")
        elif path == "/api/rcloop":
            TELLO.rc_enabled = self.path.endswith("=1")
            LOG.add("USER", "bucle rc %s" % ("ON" if TELLO.rc_enabled else "OFF"))
            self._send(200, "{}")
        elif path == "/api/rcall":
            TELLO.log_all_rc = self.path.endswith("=1")
            self._send(200, "{}")
        else:
            self._send(404, "{}")


class WebServer(ThreadingHTTPServer):
    # En Windows, allow_reuse_address deja que otro servidor ya montado en el
    # puerto lo "comparta" y conteste el equivocado. Preferimos fallar y cambiar.
    allow_reuse_address = False


def open_web_server():
    """Primer puerto libre a partir de WEB_PORT (evita chocar con otras apps)."""
    for port in range(WEB_PORT, WEB_PORT + 12):
        try:
            srv = WebServer(("127.0.0.1", port), Handler)
            if port != WEB_PORT:
                LOG.add("WARN", "el puerto %d estaba ocupado; uso el %d" % (WEB_PORT, port))
            return srv, port
        except OSError:
            continue
    raise SystemExit("no hay ningun puerto libre entre %d y %d" % (WEB_PORT, WEB_PORT + 11))


def main():
    LOG.add("INFO", "=== Tello Debug Station ===  log: %s" % LOG.path)
    TELLO.start()
    srv, port = open_web_server()
    url = "http://127.0.0.1:%d" % port
    LOG.add("INFO", "interfaz web en %s  (Ctrl+C para salir)" % url)
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
        TELLO.stop()
        srv.server_close()
        print("\nLog guardado en: %s" % LOG.path)


if __name__ == "__main__":
    main()
