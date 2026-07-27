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
 * Controla el DJI Tello / Tello EDU por el protocolo UDP oficial (SDK).
 *
 *  - Envío de comandos:  192.168.10.1 : 8889
 *  - Estado del dron:     escucha en el puerto local 8890 (batería, etc.)
 *
 * El movimiento se hace con el comando "rc a b c d" (tipo joystick), que se
 * reenvía continuamente. Eso mantiene el dron volando (si no recibe comandos
 * durante ~15 s aterriza solo) y da un control suave.
 */
public class TelloController {

    private static final String TAG = "TelloController";
    public static final String TELLO_IP = "192.168.10.1";
    public static final int CMD_PORT = 8889;
    public static final int STATE_PORT = 8890;

    public interface Listener {
        void onStatus(String msg);
        void onBattery(int pct);
        void onConnected(boolean ok);
    }

    private final Listener listener;
    private final Network network;                 // red WiFi del dron (para enrutar el UDP por ahí)
    private final Handler main = new Handler(Looper.getMainLooper());

    private DatagramSocket cmdSocket;
    private DatagramSocket stateSocket;
    private InetAddress telloAddr;

    private volatile boolean running = false;
    private volatile boolean connected = false;

    // Valores del joystick (-100..100)
    private volatile int lr = 0;   // izquierda(-) / derecha(+)
    private volatile int fb = 0;   // atrás(-) / adelante(+)
    private volatile int ud = 0;   // bajar(-) / subir(+)
    private volatile int yaw = 0;  // girar izq(-) / der(+)

    public TelloController(Listener listener, Network network) {
        this.listener = listener;
        this.network = network;
    }

    /** Arranca la conexión. Debe llamarse desde un hilo de fondo. */
    public void connect() {
        try {
            telloAddr = InetAddress.getByName(TELLO_IP);

            cmdSocket = new DatagramSocket();
            if (network != null) network.bindSocket(cmdSocket);   // fuerza salida por WiFi
            cmdSocket.setSoTimeout(0);

            running = true;

            startResponseListener();
            startStateListener();

            // Secuencia de inicio del SDK
            status("Entrando en modo SDK...");
            sendRaw("command");
            sleep(600);
            sendRaw("command");   // repetir por si el primero se perdió
            sleep(600);

            status("Activando vídeo...");
            sendRaw("streamon");
            sleep(400);

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

    /** Bucle que reenvía el joystick cada 100 ms (control + keepalive). */
    private void startRcLoop() {
        Thread t = new Thread(() -> {
            while (running && connected) {
                sendRaw("rc " + lr + " " + fb + " " + ud + " " + yaw);
                sleep(100);
            }
        }, "rc-loop");
        t.setDaemon(true);
        t.start();
    }

    /** Escucha respuestas del dron a los comandos (ok / error). */
    private void startResponseListener() {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[1518];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    cmdSocket.receive(p);
                    String resp = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                    Log.d(TAG, "resp: " + resp);
                } catch (Exception e) {
                    if (running) Log.w(TAG, "resp listener: " + e.getMessage());
                }
            }
        }, "resp-listener");
        t.setDaemon(true);
        t.start();
    }

    /** Escucha el estado del dron en el 8890 y extrae la batería. */
    private void startStateListener() {
        Thread t = new Thread(() -> {
            try {
                stateSocket = new DatagramSocket(STATE_PORT);
                if (network != null) network.bindSocket(stateSocket);
                stateSocket.setSoTimeout(2000);
            } catch (Exception e) {
                Log.w(TAG, "no se pudo abrir el puerto de estado: " + e.getMessage());
                return;
            }
            byte[] buf = new byte[1518];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    stateSocket.receive(p);
                    String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    int idx = s.indexOf("bat:");
                    if (idx >= 0) {
                        int end = s.indexOf(';', idx);
                        if (end > idx) {
                            try {
                                int bat = Integer.parseInt(s.substring(idx + 4, end).trim());
                                main.post(() -> listener.onBattery(bat));
                            } catch (NumberFormatException ignore) { }
                        }
                    }
                } catch (Exception e) {
                    // timeout normal si el dron no manda estado; seguimos
                }
            }
        }, "state-listener");
        t.setDaemon(true);
        t.start();
    }

    // ---------- Acciones ----------

    public void takeoff() {
        resetJoystick();
        sendRaw("takeoff");
        status("Despegando...");
    }

    public void land() {
        resetJoystick();
        sendRaw("land");
        status("Aterrizando...");
    }

    public void emergency() {
        resetJoystick();
        sendRaw("emergency");
        status("¡PARADA DE EMERGENCIA!");
    }

    // Ejes del joystick (cada uno -100..100)
    public void setForwardBack(int v) { fb = clamp(v); }
    public void setLeftRight(int v)   { lr = clamp(v); }
    public void setUpDown(int v)      { ud = clamp(v); }
    public void setYaw(int v)         { yaw = clamp(v); }

    public void resetJoystick() { lr = fb = ud = yaw = 0; }

    public boolean isConnected() { return connected; }

    public void disconnect() {
        running = false;
        connected = false;
        try { if (cmdSocket != null) { sendRaw("streamoff"); } } catch (Exception ignore) { }
        try { if (cmdSocket != null) cmdSocket.close(); } catch (Exception ignore) { }
        try { if (stateSocket != null) stateSocket.close(); } catch (Exception ignore) { }
    }

    // ---------- Utilidades ----------

    private synchronized void sendRaw(String cmd) {
        try {
            byte[] data = cmd.getBytes(StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(data, data.length, telloAddr, CMD_PORT);
            if (cmdSocket != null && !cmdSocket.isClosed()) cmdSocket.send(p);
        } catch (Exception e) {
            Log.w(TAG, "send '" + cmd + "': " + e.getMessage());
        }
    }

    private void status(String msg) { main.post(() -> listener.onStatus(msg)); }

    private static int clamp(int v) { return Math.max(-100, Math.min(100, v)); }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) { }
    }
}
