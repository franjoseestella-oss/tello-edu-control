package com.fran.tello;

import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Controlador del DJI Tello / Tello EDU por el protocolo UDP oficial (SDK 2.0).
 *
 *  - Comandos:  192.168.10.1 : 8889
 *  - Estado:    escucha en el puerto local 8890 (telemetría completa)
 *
 * El movimiento usa "rc a b c d" (joystick) reenviado a alta frecuencia, que
 * además hace de keepalive (sin comandos ~15 s el Tello aterriza solo).
 */
public class TelloController {

    private static final String TAG = "TelloController";
    public static final String TELLO_IP = "192.168.10.1";
    public static final int CMD_PORT = 8889;
    public static final int STATE_PORT = 8890;

    /** Telemetría del dron (parseada del estado UDP). */
    public static class Telemetry {
        public int battery;        // %
        public int height;         // cm (h)
        public int tof;            // cm distancia al suelo
        public int baro;           // cm barómetro
        public int flightTime;     // s tiempo de motores
        public int templ, temph;   // °C rango de temperatura
        public int pitch, roll, yaw;
        public int vgx, vgy, vgz;  // velocidad cm/s
        public int agx, agy, agz;  // aceleración
        public int missionPad = -1;
        public int mpx, mpy, mpz;  // posición relativa al mission pad
        public long lastUpdateMs;

        public float speedKmh() {
            double s = Math.sqrt((double) vgx * vgx + (double) vgy * vgy + (double) vgz * vgz);
            return (float) (s * 0.036);   // cm/s -> km/h
        }
    }

    public interface Listener {
        void onStatus(String msg);
        void onTelemetry(Telemetry t);
        void onConnected(boolean ok);
        void onResponse(String command, String response);
    }

    private final Listener listener;
    private final Network network;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Telemetry telemetry = new Telemetry();

    // Los envíos UDP salen siempre por este hilo: Android prohíbe red en el
    // hilo principal (NetworkOnMainThreadException) y los botones llaman desde ahí.
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cmd-sender");
        t.setDaemon(true);
        return t;
    });

    private DatagramSocket cmdSocket;
    private DatagramSocket stateSocket;
    private InetAddress telloAddr;

    private volatile boolean running = false;
    private volatile boolean connected = false;
    private volatile boolean rcSuspended = false;
    private volatile String lastCommandSent = "";

    // Depuración: estadísticas del enlace
    private volatile String pendingCmd = "";
    private volatile long pendingAtMs = 0;
    private volatile int txCount = 0, rxCount = 0, errCount = 0, rcSilenced = 0;
    private volatile String lastRcLogged = "";
    /** Si es true se registra CADA paquete rc (20/s). Normalmente basta con los cambios. */
    public static volatile boolean verboseRc = false;

    public String linkStats() {
        return "tx=" + txCount + " rx=" + rxCount + " err=" + errCount
                + " conectado=" + connected + " rcSuspendido=" + rcSuspended
                + " rc=[" + lr + "," + fb + "," + ud + "," + yaw + "]"
                + " telemetriaHace=" + (telemetry.lastUpdateMs > 0
                        ? (System.currentTimeMillis() - telemetry.lastUpdateMs) + "ms" : "nunca");
    }

    // Para esperar la respuesta del dron durante el handshake
    private final Object respLock = new Object();
    private String lastResponse;

    // Joystick (-100..100)
    private volatile int lr = 0, fb = 0, ud = 0, yaw = 0;
    private volatile boolean rcDirty = true;

    public TelloController(Listener listener, Network network) {
        this.listener = listener;
        this.network = network;
    }

    public Telemetry getTelemetry() { return telemetry; }

    /** Conexión (llamar desde hilo de fondo). */
    public void connect() {
        try {
            DebugLog.d("STEP", "=== CONECTAR ===");
            telloAddr = InetAddress.getByName(TELLO_IP);
            cmdSocket = new DatagramSocket();
            if (network != null) {
                network.bindSocket(cmdSocket);
                DebugLog.d("NET", "socket de comandos atado a la red WiFi: " + network);
            } else {
                DebugLog.d("WARN", "network == null: el socket usa la ruta por defecto "
                        + "(¡puede salir por datos móviles y no llegar al dron!)");
            }
            DebugLog.d("NET", "socket local " + cmdSocket.getLocalAddress() + ":"
                    + cmdSocket.getLocalPort() + " -> " + TELLO_IP + ":" + CMD_PORT);
            running = true;

            startResponseListener();
            startStateListener();

            status("Entrando en modo SDK...");
            boolean sdkOk = false;
            for (int i = 1; i <= 5 && !sdkOk; i++) {
                long t = System.currentTimeMillis();
                String r = sendAndWait("command", 1200);
                DebugLog.d("STEP", "handshake 'command' intento " + i + "/5 -> "
                        + (r == null ? "SIN RESPUESTA" : "'" + r + "'")
                        + " (" + (System.currentTimeMillis() - t) + " ms)");
                // "ok" del dron, o telemetría llegando por el 8890 (solo emite en modo SDK)
                sdkOk = (r != null && r.toLowerCase().startsWith("ok")) || telemetry.lastUpdateMs > 0;
                if (!sdkOk) status("El dron no responde (intento " + i + "/5)...");
            }
            if (!sdkOk) {
                DebugLog.d("ERR", "el dron NO entra en modo SDK: sin él ignora todos los comandos. "
                        + linkStats());
                status("❌ El dron no responde. Comprueba que estás en la red TELLO-XXXXXX,\napaga y enciende el dron y vuelve a intentarlo.");
                running = false;
                try { cmdSocket.close(); } catch (Exception ignore) { }
                try { if (stateSocket != null) stateSocket.close(); } catch (Exception ignore) { }
                main.post(() -> listener.onConnected(false));
                return;
            }

            status("Activando vídeo...");
            sendRaw("streamon");
            sleep(300);

            // Mission pads (Tello EDU): activar detección en ambas cámaras
            sendRaw("mon");
            sleep(150);
            sendRaw("mdirection 2");
            sleep(150);

            connected = true;
            main.post(() -> listener.onConnected(true));
            status("Conectado");
            DebugLog.d("STEP", "=== CONECTADO === " + linkStats());

            startRcLoop();

        } catch (Exception e) {
            Log.e(TAG, "connect error", e);
            DebugLog.d("ERR", "excepción al conectar: " + e);
            status("Error al conectar: " + e.getMessage());
            main.post(() -> listener.onConnected(false));
        }
    }

    /** Reenvía el joystick cada 50 ms (control fluido + keepalive). */
    private void startRcLoop() {
        DebugLog.d("STEP", "bucle rc arrancado (20 Hz + keepalive cada 1 s)");
        Thread t = new Thread(() -> {
            long lastKeepalive = 0;
            while (running && connected) {
                long now = System.currentTimeMillis();
                if (!rcSuspended && (rcDirty || now - lastKeepalive > 1000)) {
                    String cmd = "rc " + lr + " " + fb + " " + ud + " " + yaw;
                    // Solo registramos los cambios: 20 paquetes/s ahogarían el log.
                    String axes = lr + "," + fb + "," + ud + "," + yaw;
                    if (!axes.equals(lastRcLogged)) {
                        if (rcSilenced > 0) {
                            DebugLog.d("RC", "(" + rcSilenced + " paquetes rc de keepalive omitidos)");
                            rcSilenced = 0;
                        }
                        DebugLog.d("RC", "joystick -> " + cmd);
                        lastRcLogged = axes;
                    } else {
                        rcSilenced++;
                    }
                    sendRaw(cmd);
                    rcDirty = false;
                    lastKeepalive = now;
                }
                sleep(50);
            }
            DebugLog.d("STEP", "bucle rc detenido (running=" + running + " connected=" + connected + ")");
        }, "rc-loop");
        t.setDaemon(true);
        t.start();
    }

    private void startResponseListener() {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[1518];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    cmdSocket.receive(p);
                    String resp = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                    final String cmd = lastCommandSent;
                    rxCount++;
                    long ms = pendingAtMs > 0 ? System.currentTimeMillis() - pendingAtMs : -1;
                    boolean isErr = resp.toLowerCase().startsWith("error");
                    if (isErr) errCount++;
                    DebugLog.d(isErr ? "ERR" : "RX",
                            "'" + pendingCmd + "' -> '" + resp + "'  (" + ms + " ms)"
                            + (p.getAddress() != null && !TELLO_IP.equals(p.getAddress().getHostAddress())
                               ? "  (!! viene de " + p.getAddress().getHostAddress() + ")" : ""));
                    if (isErr) DebugLog.d("PISTA", explainError(pendingCmd, resp));
                    Log.d(TAG, "resp[" + cmd + "]: " + resp);
                    synchronized (respLock) { lastResponse = resp; respLock.notifyAll(); }
                    main.post(() -> listener.onResponse(cmd, resp));
                } catch (Exception e) {
                    if (running) {
                        Log.w(TAG, "resp listener: " + e.getMessage());
                        DebugLog.d("ERR", "escucha de respuestas: " + e);
                    }
                }
            }
        }, "resp-listener");
        t.setDaemon(true);
        t.start();
    }

    private void startStateListener() {
        Thread t = new Thread(() -> {
            try {
                stateSocket = new DatagramSocket(STATE_PORT);
                if (network != null) network.bindSocket(stateSocket);
                stateSocket.setSoTimeout(2000);
                DebugLog.d("NET", "escuchando telemetría en el puerto " + STATE_PORT);
            } catch (Exception e) {
                Log.w(TAG, "puerto de estado: " + e.getMessage());
                DebugLog.d("ERR", "no puedo escuchar la telemetría en " + STATE_PORT + ": " + e
                        + " (¿quedó abierto de una conexión anterior?)");
                return;
            }
            byte[] buf = new byte[1518];
            boolean first = true;
            int misses = 0;
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    stateSocket.receive(p);
                    String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (first) {
                        DebugLog.d("STATE", "primer paquete de telemetría: " + s.trim());
                        first = false;
                    }
                    misses = 0;
                    parseState(s);
                    main.post(() -> listener.onTelemetry(telemetry));
                } catch (Exception e) {
                    // timeout normal (2 s)
                    if (connected && ++misses == 3)
                        DebugLog.d("WARN", "6 s sin telemetría del dron: enlace WiFi caído o fuera de modo SDK");
                }
            }
        }, "state-listener");
        t.setDaemon(true);
        t.start();
    }

    private void parseState(String s) {
        for (String kv : s.split(";")) {
            int c = kv.indexOf(':');
            if (c <= 0) continue;
            String k = kv.substring(0, c).trim();
            String v = kv.substring(c + 1).trim();
            try {
                switch (k) {
                    case "bat":   telemetry.battery = (int) Double.parseDouble(v); break;
                    case "h":     telemetry.height = intv(v); break;
                    case "tof":   telemetry.tof = intv(v); break;
                    case "baro":  telemetry.baro = (int) Double.parseDouble(v); break;
                    case "time":  telemetry.flightTime = intv(v); break;
                    case "templ": telemetry.templ = intv(v); break;
                    case "temph": telemetry.temph = intv(v); break;
                    case "pitch": telemetry.pitch = intv(v); break;
                    case "roll":  telemetry.roll = intv(v); break;
                    case "yaw":   telemetry.yaw = intv(v); break;
                    case "vgx":   telemetry.vgx = intv(v); break;
                    case "vgy":   telemetry.vgy = intv(v); break;
                    case "vgz":   telemetry.vgz = intv(v); break;
                    case "agx":   telemetry.agx = (int) Double.parseDouble(v); break;
                    case "agy":   telemetry.agy = (int) Double.parseDouble(v); break;
                    case "agz":   telemetry.agz = (int) Double.parseDouble(v); break;
                    case "mid":   telemetry.missionPad = intv(v); break;
                    case "x":     telemetry.mpx = intv(v); break;
                    case "y":     telemetry.mpy = intv(v); break;
                    case "z":     telemetry.mpz = intv(v); break;
                }
            } catch (NumberFormatException ignore) { }
        }
        telemetry.lastUpdateMs = System.currentTimeMillis();
    }

    private static int intv(String v) {
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) { return (int) Double.parseDouble(v); }
    }

    // ---------- Acciones ----------

    public void takeoff()   { action("takeoff"); resetJoystick(); sendRaw("takeoff"); status("Despegando..."); }
    public void land()      { action("land"); resetJoystick(); sendRaw("land"); status("Aterrizando..."); }
    public void emergency() { action("emergency"); resetJoystick(); sendRaw("emergency"); status("¡EMERGENCIA! motores parados"); }

    /** flip: 'l','r','f','b' */
    public void flip(char dir) {
        action("flip " + dir);
        sendRaw("flip " + dir);
        status("Flip " + dir);
    }

    /** Velocidad máxima para comandos absolutos (10..100 cm/s). */
    public void setSpeed(int cms) {
        sendRaw("speed " + clampPos(cms, 10, 100));
    }

    /** Movimiento absoluto para misiones: dir = up/down/left/right/forward/back (20..500 cm). */
    public void moveCmd(String dir, int cm) {
        action(dir + " " + cm);
        sendRaw(dir + " " + clampPos(cm, 20, 500));
    }

    /** Deja constancia en el log de que la ORDEN ha llegado hasta aquí (y en qué estado). */
    private void action(String what) {
        DebugLog.d("ORDEN", what + "  |  " + linkStats());
        if (!connected) DebugLog.d("WARN", "¡'" + what + "' con connected=false! El dron no lo obedecerá.");
        if (rcSuspended) DebugLog.d("WARN", "rc suspendido (misión en curso) mientras se pide '" + what + "'");
    }

    /** Rotación para misiones: grados +derecha / -izquierda. */
    public void rotateCmd(int deg) {
        action((deg >= 0 ? "cw " : "ccw ") + Math.abs(deg));
        if (deg >= 0) sendRaw("cw " + clampPos(deg, 1, 360));
        else sendRaw("ccw " + clampPos(-deg, 1, 360));
    }

    /** Suspende el envío de rc (para ejecutar comandos absolutos de misión). */
    public void setRcSuspended(boolean s) {
        if (s != rcSuspended) DebugLog.d("STEP", "bucle rc " + (s ? "SUSPENDIDO" : "reanudado"));
        rcSuspended = s;
    }

    // Ejes del joystick (-100..100)
    public void setForwardBack(int v) { int c = clamp(v); if (c != fb) { fb = c; rcDirty = true; } }
    public void setLeftRight(int v)   { int c = clamp(v); if (c != lr) { lr = c; rcDirty = true; } }
    public void setUpDown(int v)      { int c = clamp(v); if (c != ud) { ud = c; rcDirty = true; } }
    public void setYaw(int v)         { int c = clamp(v); if (c != yaw) { yaw = c; rcDirty = true; } }

    /** Fija los 4 ejes de golpe (para joysticks analógicos). */
    public void setRc(int lrv, int fbv, int udv, int yawv) {
        lr = clamp(lrv); fb = clamp(fbv); ud = clamp(udv); yaw = clamp(yawv);
        rcDirty = true;
    }

    public void resetJoystick() { lr = fb = ud = yaw = 0; rcDirty = true; }

    public boolean isConnected() { return connected; }

    public void disconnect() {
        DebugLog.d("STEP", "=== DESCONECTAR === " + linkStats());
        running = false;
        connected = false;
        try {
            sender.execute(() -> {
                if (cmdSocket != null) sendNow("streamoff");
                try { if (cmdSocket != null) cmdSocket.close(); } catch (Exception ignore) { }
                try { if (stateSocket != null) stateSocket.close(); } catch (Exception ignore) { }
            });
            sender.shutdown();
            // Espera breve para que el puerto 8890 quede libre antes de reconectar.
            sender.awaitTermination(300, TimeUnit.MILLISECONDS);
        } catch (Exception ignore) { }
        try { if (cmdSocket != null) cmdSocket.close(); } catch (Exception ignore) { }
        try { if (stateSocket != null) stateSocket.close(); } catch (Exception ignore) { }
    }

    // ---------- Utilidades ----------

    private void sendRaw(String cmd) {
        try {
            sender.execute(() -> sendNow(cmd));
        } catch (Exception e) {
            Log.w(TAG, "send '" + cmd + "': " + e.getMessage());
            DebugLog.d("ERR", "no se pudo encolar '" + cmd + "': " + e
                    + " (¿el controlador está desconectado?)");
        }
    }

    /** Envía un comando y espera su respuesta hasta timeoutMs. Devuelve la respuesta o null. */
    private String sendAndWait(String cmd, long timeoutMs) {
        synchronized (respLock) { lastResponse = null; }
        sendRaw(cmd);
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (respLock) {
            while (lastResponse == null) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try { respLock.wait(left); } catch (InterruptedException e) { break; }
            }
            return lastResponse;
        }
    }

    private void sendNow(String cmd) {
        boolean isRc = cmd.startsWith("rc ");
        try {
            lastCommandSent = cmd.split(" ")[0];
            pendingCmd = cmd;
            pendingAtMs = System.currentTimeMillis();
            byte[] data = cmd.getBytes(StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(data, data.length, telloAddr, CMD_PORT);
            if (cmdSocket == null || cmdSocket.isClosed()) {
                DebugLog.d("ERR", "socket cerrado: '" + cmd + "' NO se ha enviado");
                return;
            }
            cmdSocket.send(p);
            txCount++;
            if (!isRc || verboseRc)
                DebugLog.d("TX", "'" + cmd + "'  (" + data.length + " bytes -> "
                        + TELLO_IP + ":" + CMD_PORT + ")");
        } catch (Exception e) {
            Log.w(TAG, "send '" + cmd + "': " + e.getMessage());
            DebugLog.d("ERR", "fallo al enviar '" + cmd + "': " + e);
        }
    }

    /** Traduce los "error ..." del Tello a algo accionable. */
    private static String explainError(String cmd, String resp) {
        String r = resp.toLowerCase();
        if (r.contains("motor stop"))
            return "El dron se cree parado en el suelo. Tras aterrizar o tras un fallo hay que "
                 + "volver a mandar 'command' antes de despegar.";
        if (r.contains("not joystick"))
            return "El dron espera paquetes 'rc' y le llega otra cosa (o al revés): hay que suspender "
                 + "el bucle rc antes de mandar comandos absolutos (up/cw/flip).";
        if (r.contains("auto land"))
            return "Ha aterrizado solo: batería baja o 15 s sin recibir ningún comando.";
        if (r.contains("no valid imu"))
            return "IMU sin calibrar o superficie inclinada: calíbralo en la app oficial y ponlo en plano.";
        if (r.contains("unactive"))
            return "Dron sin activar: hay que activarlo una vez con la app oficial DJI Tello.";
        if (r.contains("out of range"))
            return "Parámetro fuera de rango (movimientos 20-500 cm, giros 1-360, rc -100..100).";
        if (r.contains("battery"))
            return "Batería insuficiente: el Tello bloquea el despegue por debajo de ~10-15 %.";
        return "El dron RECHAZÓ '" + cmd + "'.";
    }

    /** Prueba de enlace sin volar: deja en el log latencias, pérdidas y estado. */
    public void diagnose() {
        // En hilo propio: sendAndWait encola en 'sender', así que no puede correr ahí (se bloquearía).
        Thread th = new Thread(() -> {
            DebugLog.d("STEP", "######## DIAGNÓSTICO ########");
            DebugLog.d("STEP", linkStats());
            boolean prev = rcSuspended;
            rcSuspended = true;
            String[] pruebas = {"command", "sdk?", "sn?", "battery?", "speed?", "wifi?", "time?", "height?", "tof?"};
            for (String c : pruebas) {
                long t = System.currentTimeMillis();
                String r = sendAndWait(c, 2000);
                DebugLog.d("STEP", "-> " + c + " : " + (r == null ? "SIN RESPUESTA" : "'" + r + "'")
                        + " (" + (System.currentTimeMillis() - t) + " ms)");
            }
            int ok = 0; long suma = 0, max = 0;
            for (int i = 0; i < 20; i++) {
                long t = System.currentTimeMillis();
                String r = sendAndWait("command", 800);
                long ms = System.currentTimeMillis() - t;
                if (r != null) { ok++; suma += ms; max = Math.max(max, ms); }
                sleep(50);
            }
            DebugLog.d("STEP", "20 pings: respondidos " + ok + "/20"
                    + (ok > 0 ? ", latencia media " + (suma / ok) + " ms, máxima " + max + " ms" : ""));
            DebugLog.d("STEP", "telemetría: bat=" + telemetry.battery + "% h=" + telemetry.height
                    + "cm tof=" + telemetry.tof + " tiempo=" + telemetry.flightTime + "s "
                    + (telemetry.lastUpdateMs == 0 ? "(NUNCA ha llegado telemetría)" : ""));
            DebugLog.d("STEP", linkStats());
            DebugLog.d("STEP", "######## FIN DEL DIAGNÓSTICO ########");
            rcSuspended = prev;
        }, "diagnose");
        th.setDaemon(true);
        th.start();
    }

    private void status(String msg) { main.post(() -> listener.onStatus(msg)); }

    private static int clamp(int v) { return Math.max(-100, Math.min(100, v)); }
    private static int clampPos(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) { }
    }
}
