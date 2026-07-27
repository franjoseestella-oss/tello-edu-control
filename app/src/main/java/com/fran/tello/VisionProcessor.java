package com.fran.tello;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Puente entre los fotogramas del vídeo y el script de visión en Python (OpenCV).
 * Procesa en un hilo aparte (descartando fotogramas si va saturado) y traduce el
 * resultado a: detecciones en pantalla + control del dron (seguir cara / gestos).
 */
public class VisionProcessor implements VideoDecoder.FrameListener {

    // Modos de visión
    public static final int MODE_OFF = 0;
    public static final int MODE_DETECT = 1;
    public static final int MODE_FOLLOW = 2;
    public static final int MODE_COLOR = 3;

    public static final String[] COLORS = {"verde", "azul", "rojo", "amarillo", "naranja"};

    public interface Listener {
        void onDetections(List<OverlayView.Face> faces, String gesture,
                          OverlayView.Qr qr, int srcW, int srcH);
        void onVisionStatus(String msg);
    }

    private TelloController controller;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String filesDir;

    private PyObject module;
    private boolean pythonReady = false;

    private final AtomicBoolean busy = new AtomicBoolean(false);

    private volatile int mode = MODE_OFF;
    private volatile boolean gestureEnabled = false;
    private volatile String pendingEnroll = null;

    private long lastActionMs = 0;
    private static final long ACTION_COOLDOWN = 4000;

    public VisionProcessor(Context ctx, Listener listener) {
        this.listener = listener;
        this.filesDir = ctx.getFilesDir().getAbsolutePath();
        initPython(ctx.getApplicationContext());
    }

    public void setController(TelloController controller) { this.controller = controller; }

    private void initPython(Context appCtx) {
        try {
            if (!Python.isStarted()) Python.start(new AndroidPlatform(appCtx));
            module = Python.getInstance().getModule("tello_vision");
            pythonReady = true;
            try {
                String r = module.callAttr("load_model", filesDir).toString();
                if (r.startsWith("cargado")) status("Caras cargadas (" + r + ")");
            } catch (Throwable ignore) { }
        } catch (Throwable t) {
            pythonReady = false;
            status("Error iniciando OpenCV: " + t.getMessage());
        }
    }

    public void setMode(int m) {
        mode = m;
        if (m != MODE_FOLLOW) stopMotion();
    }
    public int getMode() { return mode; }

    public void setGesture(boolean v) { gestureEnabled = v; if (!v) stopMotion(); }
    public boolean isGesture() { return gestureEnabled; }
    public boolean isReady() { return pythonReady; }

    /** Cambia el color a seguir en modo COLOR. */
    public void setColor(String name) {
        if (!pythonReady) return;
        try { module.callAttr("set_color", name); } catch (Throwable ignore) { }
    }

    public void enrollNext(String name) { pendingEnroll = name; }

    public void resetIds() {
        if (!pythonReady) return;
        try {
            module.callAttr("reset_ids");
            module.callAttr("save_model", filesDir);
            status("Caras memorizadas borradas");
        } catch (Throwable ignore) { }
    }

    private void stopMotion() {
        if (controller != null && controller.isConnected()) controller.resetJoystick();
    }

    private boolean visionActive() { return mode != MODE_OFF || gestureEnabled; }

    @Override
    public void onFrame(byte[] bgr, int w, int h) {
        if (!pythonReady || !visionActive()) return;
        if (!busy.compareAndSet(false, true)) return;

        final byte[] data = bgr;
        new Thread(() -> {
            try {
                String enrollName = pendingEnroll;
                if (enrollName != null) {
                    pendingEnroll = null;
                    try {
                        String r = module.callAttr("enroll", enrollName, data, w, h).toString();
                        module.callAttr("save_model", filesDir);
                        status("Cara '" + enrollName + "': " + r);
                    } catch (Throwable t) {
                        status("Error memorizar: " + t.getMessage());
                    }
                }
                String result = module.callAttr(
                        "process", data, w, h, mode, gestureEnabled).toString();
                handleResult(result, w, h);
            } catch (Throwable t) {
                status("Error visión: " + t.getMessage());
            } finally {
                busy.set(false);
            }
        }, "vision-worker").start();
    }

    private void handleResult(String result, int w, int h) {
        List<OverlayView.Face> faces = new ArrayList<>();
        String gesture = "";
        OverlayView.Qr qr = null;
        int lr = 0, fb = 0, ud = 0, yaw = 0;

        for (String block : result.split("\\|")) {
            if (block.startsWith("G:")) {
                gesture = block.substring(2);
            } else if (block.startsWith("Q:")) {
                qr = parseQr(block.substring(2));
            } else if (block.startsWith("C:")) {
                String[] c = block.substring(2).split(",");
                if (c.length == 4) {
                    lr = parse(c[0]); fb = parse(c[1]); ud = parse(c[2]); yaw = parse(c[3]);
                }
            } else if (!block.isEmpty()) {
                for (String fstr : block.split(";")) {
                    if (!fstr.startsWith("F:")) continue;
                    String[] p = fstr.substring(2).split(",", 5);
                    if (p.length >= 4) {
                        String label = p.length >= 5 ? p[4] : "";
                        faces.add(new OverlayView.Face(
                                parse(p[0]), parse(p[1]), parse(p[2]), parse(p[3]), label));
                    }
                }
            }
        }

        final List<OverlayView.Face> fFaces = faces;
        final String fGesture = gesture;
        final OverlayView.Qr fQr = qr;
        main.post(() -> listener.onDetections(fFaces, fGesture, fQr, w, h));

        if ((mode == MODE_FOLLOW || mode == MODE_COLOR)
                && controller != null && controller.isConnected()) {
            controller.setRc(lr, fb, ud, yaw);
        }
        if (gestureEnabled && controller != null && controller.isConnected()) {
            applyGesture(gesture);
        }
    }

    private OverlayView.Qr parseQr(String s) {
        if (s.isEmpty()) return null;
        String[] parts = s.split(",", 9);
        if (parts.length < 8) return null;
        int[] pts = new int[8];
        for (int i = 0; i < 8; i++) pts[i] = parse(parts[i]);
        String text = parts.length >= 9 ? parts[8] : "";
        return new OverlayView.Qr(pts, text);
    }

    private void applyGesture(String g) {
        long now = System.currentTimeMillis();
        switch (g) {
            case "takeoff":
                if (now - lastActionMs > ACTION_COOLDOWN) {
                    controller.takeoff(); lastActionMs = now; status("Gesto: DESPEGAR");
                }
                break;
            case "land":
                if (now - lastActionMs > ACTION_COOLDOWN) {
                    controller.land(); lastActionMs = now; status("Gesto: ATERRIZAR");
                }
                break;
            case "up":   if (mode != MODE_FOLLOW) controller.setUpDown(40); break;
            case "down": if (mode != MODE_FOLLOW) controller.setUpDown(-40); break;
            default:     if (mode != MODE_FOLLOW) controller.setUpDown(0); break;
        }
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private void status(String msg) { main.post(() -> listener.onVisionStatus(msg)); }
}
