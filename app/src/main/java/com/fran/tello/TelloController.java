package com.fran.tello;

import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

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

    private DatagramSocket cmdSocket;
    private DatagramSocket stateSocket;
    private InetAddress telloAddr;

    private volatile boolean running = false;
    private volatile boolean connected = false;
    private volatile boolean rcSuspended = false;
    private volatile String lastCommandSent = "";

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
            telloAddr = InetAddress.getByName(TELLO_IP);
            cmdSocket = new DatagramSocket();
            if (network != null) network.bindSocket(cmdSocket);
            running = true;

            startResponseListener();
            startStateListener();

            status("Entrando en modo SDK...");
            sendRaw("command");
            sleep(500);
            sendRaw("command");
            sleep(500);

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

            startRcLoop();

        } catch (Exception e) {
            Log.e(TAG, "connect error", e);
            status("Error al conectar: " + e.getMessage());
            main.post(() -> listener.onConnected(false));
        }
    }

    /** Reenvía el joystick cada 50 ms (control fluido + keepalive). */
    private void startRcLoop() {
        Thread t = new Thread(() -> {
            long lastKeepalive = 0;
            while (running && connected) {
                long now = System.currentTimeMillis();
                if (!rcSuspended && (rcDirty || now - lastKeepalive > 1000)) {
                    sendRaw("rc " + lr + " " + fb + " " + ud + " " + yaw);
                    rcDirty = false;
                    lastKeepalive = now;
                }
                sleep(50);
            }
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
                    Log.d(TAG, "resp[" + cmd + "]: " + resp);
                    main.post(() -> listener.onResponse(cmd, resp));
                } catch (Exception e) {
                    if (running) Log.w(TAG, "resp listener: " + e.getMessage());
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
            } catch (Exception e) {
                Log.w(TAG, "puerto de estado: " + e.getMessage());
                return;
            }
            byte[] buf = new byte[1518];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    stateSocket.receive(p);
                    String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    parseState(s);
                    main.post(() -> listener.onTelemetry(telemetry));
                } catch (Exception e) {
                    // timeout normal
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

    public void takeoff()   { resetJoystick(); sendRaw("takeoff"); status("Despegando..."); }
    public void land()      { resetJoystick(); sendRaw("land"); status("Aterrizando..."); }
    public void emergency() { resetJoystick(); sendRaw("emergency"); status("¡EMERGENCIA! motores parados"); }

    /** flip: 'l','r','f','b' */
    public void flip(char dir) {
        sendRaw("flip " + dir);
        status("Flip " + dir);
    }

    /** Velocidad máxima para comandos absolutos (10..100 cm/s). */
    public void setSpeed(int cms) {
        sendRaw("speed " + clampPos(cms, 10, 100));
    }

    /** Movimiento absoluto para misiones: dir = up/down/left/right/forward/back (20..500 cm). */
    public void moveCmd(String dir, int cm) {
        sendRaw(dir + " " + clampPos(cm, 20, 500));
    }

    /** Rotación para misiones: grados +derecha / -izquierda. */
    public void rotateCmd(int deg) {
        if (deg >= 0) sendRaw("cw " + clampPos(deg, 1, 360));
        else sendRaw("ccw " + clampPos(-deg, 1, 360));
    }

    /** Suspende el envío de rc (para ejecutar comandos absolutos de misión). */
    public void setRcSuspended(boolean s) { rcSuspended = s; }

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
        running = false;
        connected = false;
        try { if (cmdSocket != null) sendRaw("streamoff"); } catch (Exception ignore) { }
        try { if (cmdSocket != null) cmdSocket.close(); } catch (Exception ignore) { }
        try { if (stateSocket != null) stateSocket.close(); } catch (Exception ignore) { }
    }

    // ---------- Utilidades ----------

    private synchronized void sendRaw(String cmd) {
        try {
            lastCommandSent = cmd.split(" ")[0];
            byte[] data = cmd.getBytes(StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(data, data.length, telloAddr, CMD_PORT);
            if (cmdSocket != null && !cmdSocket.isClosed()) cmdSocket.send(p);
        } catch (Exception e) {
            Log.w(TAG, "send '" + cmd + "': " + e.getMessage());
        }
    }

    private void status(String msg) { main.post(() -> listener.onStatus(msg)); }

    private static int clamp(int v) { return Math.max(-100, Math.min(100, v)); }
    private static int clampPos(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) { }
    }
}
