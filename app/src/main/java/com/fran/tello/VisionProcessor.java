package com.fran.tello;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cerebro de la visión. Recibe los fotogramas del vídeo y los reparte entre:
 *
 *   - OpenCV (Python/Chaquopy): caras, identificación, color y QR.
 *   - MediaPipe (Java): esqueleto del cuerpo (33 puntos) y mano (21 puntos).
 *
 * Con el resultado pinta el overlay y, si toca, mueve el dron (PID de
 * seguimiento) o ejecuta la orden de un gesto.
 *
 * Los dos motores arrancan en segundo plano: el objeto se puede usar desde el
 * primer momento aunque todavía no estén listos (wantsFrames() lo tiene en
 * cuenta), así que no hay carrera con la conexión del dron.
 */
public class VisionProcessor implements VideoDecoder.FrameListener {

    // Modos de visión (excluyentes)
    public static final int MODE_OFF = 0;
    public static final int MODE_DETECT = 1;
    public static final int MODE_FOLLOW = 2;
    public static final int MODE_COLOR = 3;

    public static final String[] COLORS = {"verde", "azul", "rojo", "amarillo", "naranja"};

    /** El objetivo se encuadra un poco por encima del centro: queda mejor plano. */
    private static final float FRAME_OFFSET_Y = 0.08f;
    /** Ancho de hombros deseado, en fracción del ancho de imagen (= distancia). */
    private static final float BODY_TARGET_W = 0.16f;

    public interface Listener {
        void onDetections(OverlayView.Result result);
        void onVisionStatus(String msg);
        /** Orden suelta disparada por un gesto que la actividad debe atender ("photo"). */
        void onVisionAction(String action);
    }

    private TelloController controller;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String filesDir;

    private PyObject module;
    private volatile boolean pythonReady = false;
    private volatile String pythonError = "";

    private final SkeletonProcessor skeleton;
    private volatile boolean skeletonReady = false;

    private final AtomicBoolean busy = new AtomicBoolean(false);
    /** Un único hilo reutilizado para todo el análisis. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vision-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile int mode = MODE_OFF;
    private volatile boolean gestureEnabled = false;
    private volatile boolean bodyEnabled = false;
    private volatile String pendingEnroll = null;

    // Buffers reutilizados (sólo los toca onFrame, y sólo cuando no hay trabajo)
    private byte[] bgrBuf;
    private Bitmap frameBmp;
    private int frameW, frameH;
    /** Qué motores usa el fotograma que se está analizando. */
    private boolean usePython, useSkeleton;

    private long lastActionMs = 0;
    private static final long ACTION_COOLDOWN = 4000;

    // Ejes que mantiene pulsados un gesto sostenido
    private int holdUd = 0, holdLr = 0;
    /** Último gesto que devolvió OpenCV (método de respaldo por color de piel). */
    private String pythonGesture = "";

    private String lastVisionRc = "";

    // Estado del PID de seguimiento: [integral, error anterior]
    private final float[] pidYaw = new float[2];
    private final float[] pidUd = new float[2];
    private final float[] pidFb = new float[2];

    public VisionProcessor(Context ctx, Listener listener) {
        this.listener = listener;
        this.filesDir = ctx.getFilesDir().getAbsolutePath();
        this.skeleton = new SkeletonProcessor(ctx);

        final Context app = ctx.getApplicationContext();
        // Arranque en segundo plano: Chaquopy tarda 1-3 s y MediaPipe ~1 s
        new Thread(() -> {
            initPython(app);
            initSkeleton();
            String msg = "Visión: " + (pythonReady ? "OpenCV ✓" : "OpenCV ✗")
                    + " · " + (skeletonReady ? "esqueleto ✓" : "esqueleto ✗");
            DebugLog.d("VISION", msg
                    + (pythonReady ? "" : " | OpenCV: " + pythonError)
                    + (skeletonReady ? "" : " | esqueleto: " + skeleton.getError()));
            status(msg);
        }, "vision-init").start();
    }

    public void setController(TelloController controller) { this.controller = controller; }

    private void initPython(Context appCtx) {
        try {
            if (!Python.isStarted()) Python.start(new AndroidPlatform(appCtx));
            module = Python.getInstance().getModule("tello_vision");
            String diag = "";
            try { diag = module.callAttr("diag").toString(); } catch (Throwable ignore) { }
            DebugLog.d("VISION", "OpenCV listo " + diag);
            pythonReady = true;
            try {
                String r = module.callAttr("load_model", filesDir).toString();
                if (r.startsWith("cargado")) status("Caras cargadas (" + r + ")");
            } catch (Throwable ignore) { }
        } catch (Throwable t) {
            pythonReady = false;
            pythonError = String.valueOf(t.getMessage());
            DebugLog.d("ERR", "no arrancó OpenCV: " + pythonError);
        }
    }

    private void initSkeleton() {
        skeletonReady = skeleton.load();
        if (!skeletonReady) {
            DebugLog.d("ERR", "no arrancó el esqueleto: " + skeleton.getError());
        }
    }

    // ---------------------------------------------------------- ajustes

    public void setMode(int m) {
        String[] n = {"OFF", "DETECTAR", "SEGUIR", "COLOR"};
        DebugLog.d("UI", "modo de visión -> " + (m >= 0 && m < n.length ? n[m] : m)
                + (m == MODE_FOLLOW || m == MODE_COLOR ? " (¡la visión toma el mando del joystick!)" : ""));
        mode = m;
        pidReset();
        if (m != MODE_FOLLOW && m != MODE_COLOR) stopMotion();
    }
    public int getMode() { return mode; }

    public void setGesture(boolean v) { gestureEnabled = v; if (!v) stopMotion(); }
    public boolean isGesture() { return gestureEnabled; }

    /** Esqueleto: dibuja el cuerpo y, en modo SEGUIR, persigue a la persona entera. */
    public void setBody(boolean v) {
        bodyEnabled = v;
        pidReset();
        if (!v) skeleton.reset();
    }
    public boolean isBody() { return bodyEnabled; }

    public boolean isReady() { return pythonReady || skeletonReady; }
    public boolean isPythonReady() { return pythonReady; }
    public boolean isSkeletonReady() { return skeletonReady; }
    public String getSkeletonError() { return skeleton.getError(); }
    public String getPythonError() { return pythonError; }

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

    private boolean visionActive() { return mode != MODE_OFF || gestureEnabled || bodyEnabled; }

    /** ¿Hace falta OpenCV para lo que está activo ahora mismo? */
    private boolean needPython() {
        if (!pythonReady) return false;
        if (mode == MODE_DETECT || mode == MODE_COLOR) return true;
        if (mode == MODE_FOLLOW) return !(bodyEnabled && skeletonReady);
        // gestos por color de piel: sólo si no hay MediaPipe
        return gestureEnabled && !skeletonReady;
    }

    /** ¿Hace falta MediaPipe? */
    private boolean needSkeleton() {
        return skeletonReady && (bodyEnabled || gestureEnabled);
    }

    // ------------------------------------------------------- fotogramas

    @Override
    public boolean wantsFrames() { return visionActive() && (needPython() || needSkeleton()); }

    @Override
    public void onFrame(int[] argb, int w, int h) {
        if (!wantsFrames()) return;
        if (!busy.compareAndSet(false, true)) return;   // aún trabajando: se salta

        // Se congela aquí lo que toca hacer, para que analyze() no use un buffer
        // que no se ha preparado si el modo cambia justo en medio.
        useSkeleton = needSkeleton();
        usePython = needPython();

        try {
            // El array se reutiliza fuera: hay que quedarse una copia propia.
            if (useSkeleton) {
                if (frameBmp == null || frameW != w || frameH != h) {
                    if (frameBmp != null) frameBmp.recycle();
                    frameBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                }
                frameBmp.setPixels(argb, 0, w, 0, 0, w, h);
            }
            if (usePython) {
                if (bgrBuf == null || bgrBuf.length != w * h * 3) bgrBuf = new byte[w * h * 3];
                argbToBgr(argb, bgrBuf);
            }
            frameW = w;
            frameH = h;
        } catch (Throwable t) {
            busy.set(false);
            return;
        }

        worker.execute(() -> {
            try {
                analyze(w, h);
            } catch (Throwable t) {
                status("Error visión: " + t.getMessage());
            } finally {
                busy.set(false);
            }
        });
    }

    private static void argbToBgr(int[] argb, byte[] out) {
        int i = 0;
        for (int p : argb) {
            out[i++] = (byte) (p & 0xff);          // B
            out[i++] = (byte) ((p >> 8) & 0xff);   // G
            out[i++] = (byte) ((p >> 16) & 0xff);  // R
        }
    }

    // ------------------------------------------------------- análisis

    private void analyze(int w, int h) {
        OverlayView.Result res = new OverlayView.Result();
        res.faces = new ArrayList<>();
        res.srcW = w;
        res.srcH = h;

        int[] ctrl = null;          // {lr, fb, ud, yaw} si la visión pilota
        String action = "";

        // ---- 1) Esqueleto y mano (MediaPipe) ----
        if (useSkeleton) {
            action = skeleton.process(frameBmp, bodyEnabled, gestureEnabled);
            res.body = skeleton.getBody();
            res.hand = skeleton.getHand();
            res.fingers = skeleton.getFingers();
            res.handLabel = skeleton.getHandLabel();
            res.gesture = skeleton.getGestureText();

            if (bodyEnabled) {
                int[] box = skeleton.targetBox();
                if (box != null) {
                    res.faces.add(new OverlayView.Face(box[0], box[1], box[2], box[3], "cuerpo"));
                    if (mode == MODE_FOLLOW) ctrl = followBody(box, w, h);
                } else if (mode == MODE_FOLLOW) {
                    pidReset();
                    ctrl = new int[]{0, 0, 0, 0};
                }
            }
        }

        // ---- 2) OpenCV (caras, color, QR, gestos de respaldo) ----
        if (usePython) {
            String enrollName = pendingEnroll;
            if (enrollName != null) {
                pendingEnroll = null;
                try {
                    String r = module.callAttr("enroll", enrollName, bgrBuf, w, h).toString();
                    module.callAttr("save_model", filesDir);
                    status("Cara '" + enrollName + "': " + r);
                } catch (Throwable t) {
                    status("Error memorizar: " + t.getMessage());
                }
            }
            boolean pyGesture = gestureEnabled && !skeletonReady;
            String result = module.callAttr(
                    "process", bgrBuf, w, h, mode, pyGesture).toString();
            int[] pyCtrl = parsePython(result, res);
            if (ctrl == null && pyCtrl != null) ctrl = pyCtrl;
            if (pyGesture) {
                // El método por color de piel no confirma nada: vale tal cual
                applyAction(pythonGesture);
                applyHold(pythonGesture);
            }
        }

        final OverlayView.Result fRes = res;
        main.post(() -> listener.onDetections(fRes));

        // ---- 3) Mando del dron ----
        if (ctrl != null && (mode == MODE_FOLLOW || mode == MODE_COLOR)
                && controller != null && controller.isConnected()) {
            String axes = ctrl[0] + "," + ctrl[1] + "," + ctrl[2] + "," + ctrl[3];
            if (!axes.equals(lastVisionRc)) {
                DebugLog.d("RC", "la visión manda rc " + axes.replace(',', ' '));
                lastVisionRc = axes;
            }
            controller.setRc(ctrl[0], ctrl[1], ctrl[2], ctrl[3]);
        }

        // ---- 4) Gestos de MediaPipe ----
        if (useSkeleton && gestureEnabled) {
            applyAction(action);                       // al confirmarse: despegar, foto...
            applyHold(skeleton.getStableAction());     // mientras se mantenga: subir...
        }
    }

    /** Lee la cadena que devuelve tello_vision.process y rellena el overlay. */
    private int[] parsePython(String result, OverlayView.Result res) {
        int[] ctrl = null;
        pythonGesture = "";
        for (String block : result.split("\\|")) {
            if (block.startsWith("G:")) {
                pythonGesture = block.substring(2);
                if (!pythonGesture.isEmpty() && res.gesture.isEmpty()) {
                    res.gesture = "✋ " + pythonGesture;
                }
            } else if (block.startsWith("Q:")) {
                OverlayView.Qr qr = parseQr(block.substring(2));
                if (qr != null) res.qr = qr;
            } else if (block.startsWith("C:")) {
                String[] c = block.substring(2).split(",");
                if (c.length == 4) {
                    int[] v = {parse(c[0]), parse(c[1]), parse(c[2]), parse(c[3])};
                    if (v[0] != 0 || v[1] != 0 || v[2] != 0 || v[3] != 0
                            || mode == MODE_FOLLOW || mode == MODE_COLOR) {
                        ctrl = v;
                    }
                }
            } else if (!block.isEmpty()) {
                for (String fstr : block.split(";")) {
                    if (!fstr.startsWith("F:")) continue;
                    String[] p = fstr.substring(2).split(",", 5);
                    if (p.length >= 4) {
                        String label = p.length >= 5 ? p[4] : "";
                        res.faces.add(new OverlayView.Face(
                                parse(p[0]), parse(p[1]), parse(p[2]), parse(p[3]), label));
                    }
                }
            }
        }
        return ctrl;
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

    // ------------------------------------------------- órdenes por gesto

    /** Órdenes de una sola vez: se disparan al confirmarse el gesto. */
    private void applyAction(String a) {
        if (a.isEmpty()) return;
        long now = System.currentTimeMillis();

        if (a.equals("photo")) {
            if (now - lastActionMs > ACTION_COOLDOWN) {
                lastActionMs = now;
                status("Gesto: FOTO");
                main.post(() -> listener.onVisionAction("photo"));
            }
            return;
        }
        if (controller == null || !controller.isConnected()) return;

        if (a.equals("takeoff") && now - lastActionMs > ACTION_COOLDOWN) {
            controller.takeoff(); lastActionMs = now; status("Gesto: DESPEGAR");
        } else if (a.equals("land") && now - lastActionMs > ACTION_COOLDOWN) {
            controller.land(); lastActionMs = now; status("Gesto: ATERRIZAR");
        }
    }

    /**
     * Movimientos que duran mientras se mantenga el gesto. Al soltarlo (orden
     * vacía) el dron se queda quieto: si no, seguiría subiendo para siempre.
     */
    private void applyHold(String a) {
        if (controller == null || !controller.isConnected()) return;
        // Si la visión ya está pilotando (SEGUIR/COLOR), los gestos no tocan los ejes
        if (mode == MODE_FOLLOW || mode == MODE_COLOR) return;

        int ud = 0, lr = 0;
        switch (a == null ? "" : a) {
            case "up":    ud = 40; break;
            case "down":  ud = -40; break;
            case "left":  lr = -35; break;
            case "right": lr = 35; break;
            default: break;   // "", stop, takeoff, land, photo -> quieto
        }
        if (ud == holdUd && lr == holdLr) return;
        holdUd = ud;
        holdLr = lr;
        controller.setUpDown(ud);
        controller.setLeftRight(lr);
    }

    // ------------------------------------------------------------- PID

    private void pidReset() {
        pidYaw[0] = pidYaw[1] = 0f;
        pidUd[0] = pidUd[1] = 0f;
        pidFb[0] = pidFb[1] = 0f;
        lastVisionRc = "";
    }

    private static float pidStep(float[] st, float error, float kp, float ki, float kd) {
        float dt = 0.1f;
        st[0] = Math.max(-50f, Math.min(50f, st[0] + error * dt));
        float deriv = (error - st[1]) / dt;
        st[1] = error;
        return kp * error + ki * st[0] + kd * deriv;
    }

    /**
     * Seguimiento del cuerpo entero. La distancia se mide por el ancho de
     * hombros, mucho más estable que el alto de la caja (brazos, agacharse...).
     */
    private int[] followBody(int[] box, int w, int h) {
        float cx = box[0] + box[2] / 2f;
        float cy = box[1] + box[3] / 2f;
        float ex = (cx - w / 2f) / (w / 2f);
        float ey = (cy - h / 2f - h * FRAME_OFFSET_Y) / (h / 2f);

        float sw = skeleton.shoulderWidth();
        float target = w * BODY_TARGET_W;
        float ez = sw > 5f ? (target - sw) / target : 0f;

        if (Math.abs(ex) < 0.07f) ex = 0f;
        if (Math.abs(ey) < 0.10f) ey = 0f;
        if (Math.abs(ez) < 0.20f) ez = 0f;

        float yaw = pidStep(pidYaw, ex, 60, 2, 7);
        float ud = pidStep(pidUd, -ey, 45, 1, 5);
        float fb = pidStep(pidFb, ez, 35, 1, 3);
        return new int[]{0, clamp(fb), clamp(ud), clamp(yaw)};
    }

    private static int clamp(float v) {
        return (int) Math.max(-100, Math.min(100, v));
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private void status(String msg) { main.post(() -> listener.onVisionStatus(msg)); }
}
