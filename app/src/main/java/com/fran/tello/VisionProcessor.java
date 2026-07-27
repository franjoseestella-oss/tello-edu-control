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
 * resultado a: recuadros en pantalla + control del dron (seguir cara / gestos).
 */
public class VisionProcessor implements VideoDecoder.FrameListener {

    public interface Listener {
        void onDetections(List<OverlayView.Face> faces, String gesture, int srcW, int srcH);
        void onVisionStatus(String msg);
    }

    private TelloController controller;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private PyObject module;
    private boolean pythonReady = false;

    private final AtomicBoolean busy = new AtomicBoolean(false);

    private volatile boolean visionEnabled = false;
    private volatile boolean followEnabled = false;
    private volatile boolean gestureEnabled = false;
    private volatile String pendingEnroll = null;

    private long lastActionMs = 0;
    private static final long ACTION_COOLDOWN = 4000;   // ms entre acciones por gesto

    public VisionProcessor(Context ctx, Listener listener) {
        this.listener = listener;
        initPython(ctx.getApplicationContext());
    }

    public void setController(TelloController controller) { this.controller = controller; }

    private void initPython(Context appCtx) {
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(appCtx));
            }
            module = Python.getInstance().getModule("tello_vision");
            pythonReady = true;
        } catch (Throwable t) {
            pythonReady = false;
            status("Error iniciando OpenCV: " + t.getMessage());
        }
    }

    // ---- Interruptores de modo ----
    public void setVisionEnabled(boolean v) { visionEnabled = v; }
    public void setFollow(boolean v)  { followEnabled = v; if (!v) stopMotion(); }
    public void setGesture(boolean v) { gestureEnabled = v; if (!v) stopMotion(); }
    public boolean isReady() { return pythonReady; }

    /** Memoriza la cara del próximo fotograma con este nombre. */
    public void enrollNext(String name) { pendingEnroll = name; }

    public void resetIds() {
        if (!pythonReady) return;
        try { module.callAttr("reset_ids"); status("Caras memorizadas borradas"); }
        catch (Throwable ignore) { }
    }

    private void stopMotion() {
        if (controller != null && controller.isConnected()) controller.resetJoystick();
    }

    // ---- Fotograma entrante desde el decodificador ----
    @Override
    public void onFrame(byte[] bgr, int w, int h) {
        if (!pythonReady || !visionEnabled) return;
        if (!busy.compareAndSet(false, true)) return;   // ocupado: descartar fotograma

        final byte[] data = bgr;
        new Thread(() -> {
            try {
                String enrollName = pendingEnroll;
                if (enrollName != null) {
                    pendingEnroll = null;
                    try {
                        String r = module.callAttr("enroll", enrollName, data, w, h).toString();
                        status("Enrolar '" + enrollName + "': " + r);
                    } catch (Throwable t) {
                        status("Error enrolar: " + t.getMessage());
                    }
                }

                String result = module.callAttr(
                        "process", data, w, h, followEnabled, gestureEnabled).toString();
                handleResult(result, w, h);
            } catch (Throwable t) {
                status("Error visión: " + t.getMessage());
            } finally {
                busy.set(false);
            }
        }, "vision-worker").start();
    }

    // ---- Parseo del resultado y acciones ----
    private void handleResult(String result, int w, int h) {
        List<OverlayView.Face> faces = new ArrayList<>();
        String gesture = "";
        int lr = 0, fb = 0, ud = 0, yaw = 0;

        String[] blocks = result.split("\\|");
        for (String block : blocks) {
            if (block.startsWith("G:")) {
                gesture = block.substring(2);
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
        main.post(() -> listener.onDetections(fFaces, fGesture, w, h));

        // Seguir la cara: aplicar el vector de control calculado en Python
        if (followEnabled && controller != null && controller.isConnected()) {
            controller.setLeftRight(lr);
            controller.setForwardBack(fb);
            controller.setUpDown(ud);
            controller.setYaw(yaw);
        }

        // Gestos -> comandos
        if (gestureEnabled && controller != null && controller.isConnected()) {
            applyGesture(gesture);
        }
    }

    private void applyGesture(String g) {
        long now = System.currentTimeMillis();
        switch (g) {
            case "takeoff":
                if (now - lastActionMs > ACTION_COOLDOWN) {
                    controller.takeoff();
                    lastActionMs = now;
                    status("Gesto: DESPEGAR");
                }
                break;
            case "land":
                if (now - lastActionMs > ACTION_COOLDOWN) {
                    controller.land();
                    lastActionMs = now;
                    status("Gesto: ATERRIZAR");
                }
                break;
            case "up":
                if (!followEnabled) controller.setUpDown(40);
                break;
            case "down":
                if (!followEnabled) controller.setUpDown(-40);
                break;
            default:
                if (!followEnabled) controller.setUpDown(0);
                break;
        }
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private void status(String msg) {
        main.post(() -> listener.onVisionStatus(msg));
    }
}
