package com.fran.tello;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.List;

/**
 * Esqueleto (33 puntos) y manos (21 puntos) con MediaPipe Tasks, corriendo en
 * el propio móvil. Es la versión Android de pc/skeleton.py: los mismos gestos,
 * los mismos umbrales y la misma confirmación por repetición.
 *
 * Ventaja sobre la cara: el esqueleto sigue funcionando aunque la persona se dé
 * la vuelta, y las manos cuentan dedos de verdad (no por color de piel).
 *
 * Todo se llama desde el hilo de visión (uno solo), así que no hay bloqueos.
 */
public class SkeletonProcessor {

    private static final String TAG = "Skeleton";

    private static final String POSE_MODEL = "pose_landmarker_lite.task";
    private static final String HAND_MODEL = "hand_landmarker.task";

    /** Fotogramas seguidos con el mismo gesto antes de que cuente como orden. */
    private static final int CONFIRM_FRAMES = 4;

    /** Huesos del cuerpo que se dibujan (índices de PoseLandmark). */
    public static final int[][] BONES = {
            {11, 12}, {11, 23}, {12, 24}, {23, 24},          // tronco
            {11, 13}, {13, 15}, {12, 14}, {14, 16},          // brazos
            {23, 25}, {25, 27}, {24, 26}, {26, 28},          // piernas
            {27, 31}, {28, 32},                              // pies
            {0, 11}, {0, 12},                                // cuello
    };

    /** Huesos de la mano. */
    public static final int[][] HAND_BONES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 4},                          // pulgar
            {0, 5}, {5, 6}, {6, 7}, {7, 8},                          // índice
            {5, 9}, {9, 10}, {10, 11}, {11, 12},                     // corazón
            {9, 13}, {13, 14}, {14, 15}, {15, 16},                   // anular
            {13, 17}, {17, 18}, {18, 19}, {19, 20}, {0, 17},         // meñique
    };

    private final Context ctx;

    private PoseLandmarker pose;
    private HandLandmarker hands;
    private volatile boolean ready = false;
    private volatile String error = "";

    // Último resultado (en píxeles del fotograma analizado)
    private float[] body;        // 33 * 3  -> x, y, visibilidad
    private float[] hand;        // 21 * 2  -> x, y
    private String handLabel = "";
    private int fingers = -1;
    private String gestureText = "";

    private String lastGesture = "";
    private String stableAction = "";
    private int gestureCount = 0;
    private long ts = 0;

    public SkeletonProcessor(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean isReady() { return ready; }
    public String getError() { return error; }
    public float[] getBody() { return body; }
    public float[] getHand() { return hand; }
    public int getFingers() { return fingers; }
    public String getHandLabel() { return handLabel; }
    public String getGestureText() { return gestureText; }

    /** Carga los dos modelos. Tarda ~1 s, conviene llamarlo en segundo plano. */
    public synchronized boolean load() {
        if (ready) return true;
        try {
            pose = PoseLandmarker.createFromOptions(ctx,
                    PoseLandmarker.PoseLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder()
                                    .setModelAssetPath(POSE_MODEL).build())
                            .setRunningMode(RunningMode.VIDEO)
                            .setNumPoses(1)
                            .setMinPoseDetectionConfidence(0.5f)
                            .setMinPosePresenceConfidence(0.5f)
                            .setMinTrackingConfidence(0.5f)
                            .setOutputSegmentationMasks(false)
                            .build());

            hands = HandLandmarker.createFromOptions(ctx,
                    HandLandmarker.HandLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder()
                                    .setModelAssetPath(HAND_MODEL).build())
                            .setRunningMode(RunningMode.VIDEO)
                            .setNumHands(1)
                            .setMinHandDetectionConfidence(0.5f)
                            .setMinHandPresenceConfidence(0.5f)
                            .setMinTrackingConfidence(0.5f)
                            .build());

            ready = true;
            error = "";
            Log.i(TAG, "esqueleto y manos listos");
            return true;
        } catch (Throwable t) {
            close();
            error = String.valueOf(t.getMessage());
            Log.w(TAG, "no se pudo cargar MediaPipe: " + error);
            return false;
        }
    }

    /**
     * Analiza un fotograma. Devuelve la orden confirmada ("takeoff", "land",
     * "up", "down", "left", "right", "stop", "photo") o "" si no hay ninguna.
     *
     * @param wantPose     buscar el cuerpo (dibujarlo y poder seguirlo)
     * @param wantGestures buscar la mano y leer los gestos (de mano y de brazos)
     */
    public String process(Bitmap bmp, boolean wantPose, boolean wantGestures) {
        if (!ready || bmp == null) return "";

        int w = bmp.getWidth(), h = bmp.getHeight();
        MPImage img = new BitmapImageBuilder(bmp).build();
        ts += 40;                       // debe crecer siempre en modo VIDEO

        float[] newBody = null, newHand = null;
        String label = "";
        String gesture = "";

        if (wantPose) {
            try {
                PoseLandmarkerResult r = pose.detectForVideo(img, ts);
                if (!r.landmarks().isEmpty()) {
                    newBody = toBody(r.landmarks().get(0), w, h);
                    if (wantGestures) gesture = bodyGesture(newBody);
                }
            } catch (Throwable t) {
                Log.w(TAG, "pose: " + t.getMessage());
            }
        }

        if (wantGestures) {
            try {
                HandLandmarkerResult r = hands.detectForVideo(img, ts + 1);
                if (!r.landmarks().isEmpty()) {
                    newHand = toHand(r.landmarks().get(0), w, h);
                    List<List<Category>> hd = r.handedness();
                    if (!hd.isEmpty() && !hd.get(0).isEmpty()) {
                        label = hd.get(0).get(0).categoryName();
                    }
                    String g = handGesture(newHand);
                    if (!g.isEmpty()) gesture = g;   // la mano manda sobre el cuerpo
                }
            } catch (Throwable t) {
                Log.w(TAG, "manos: " + t.getMessage());
            }
        }

        body = newBody;
        hand = newHand;
        handLabel = label;
        if (newHand == null) fingers = -1;

        return confirm(gesture);
    }

    /** Borra el último resultado (al apagar el modo). */
    public void reset() {
        body = null;
        hand = null;
        fingers = -1;
        handLabel = "";
        gestureText = "";
        lastGesture = "";
        stableAction = "";
        gestureCount = 0;
    }

    public synchronized void close() {
        try { if (pose != null) pose.close(); } catch (Throwable ignore) { }
        try { if (hands != null) hands.close(); } catch (Throwable ignore) { }
        pose = null;
        hands = null;
        ready = false;
    }

    // ------------------------------------------------------------------

    private static float[] toBody(List<NormalizedLandmark> lm, int w, int h) {
        float[] out = new float[33 * 3];
        for (int i = 0; i < 33 && i < lm.size(); i++) {
            NormalizedLandmark p = lm.get(i);
            out[i * 3] = p.x() * w;
            out[i * 3 + 1] = p.y() * h;
            out[i * 3 + 2] = p.visibility().isPresent() ? p.visibility().get() : 1f;
        }
        return out;
    }

    private static float[] toHand(List<NormalizedLandmark> lm, int w, int h) {
        float[] out = new float[21 * 2];
        for (int i = 0; i < 21 && i < lm.size(); i++) {
            out[i * 2] = lm.get(i).x() * w;
            out[i * 2 + 1] = lm.get(i).y() * h;
        }
        return out;
    }

    /**
     * Exige repetición para no disparar una orden por un fotograma suelto.
     * Devuelve la orden sólo en el fotograma en que se confirma (para cosas de
     * una vez: despegar, aterrizar, foto). Mientras el gesto se mantenga,
     * {@link #getStableAction()} sigue devolviéndola (para subir, bajar...).
     */
    private String confirm(String gesture) {
        if (!gesture.isEmpty() && gesture.equals(lastGesture)) {
            gestureCount++;
        } else {
            lastGesture = gesture;
            gestureCount = 1;
        }
        if (gesture.isEmpty()) {
            gestureText = "";
            stableAction = "";
            return "";
        }
        if (gestureCount < CONFIRM_FRAMES) return "";

        gestureText = describe(gesture);
        stableAction = action(gesture);
        return gestureCount == CONFIRM_FRAMES ? stableAction : "";
    }

    /** Orden del gesto que se está manteniendo ahora mismo ("" si ninguno). */
    public String getStableAction() { return stableAction; }

    private static String action(String g) {
        switch (g) {
            case "palma": case "brazos_arriba": return "takeoff";
            case "puño":  case "brazos_abajo":  return "land";
            case "uno":                         return "up";
            case "dos":                         return "down";
            case "tres":                        return "photo";
            case "pulgar_izq": case "brazo_izq": return "left";
            case "pulgar_der": case "brazo_der": return "right";
            case "cruz":                        return "stop";
            default:                            return "";
        }
    }

    private static String describe(String g) {
        switch (g) {
            case "palma":         return "🖐 palma = DESPEGAR";
            case "puño":          return "✊ puño = ATERRIZAR";
            case "uno":           return "☝ 1 dedo = SUBIR";
            case "dos":           return "✌ 2 dedos = BAJAR";
            case "tres":          return "🤟 3 dedos = FOTO";
            case "pulgar_izq":    return "👈 pulgar = IZQUIERDA";
            case "pulgar_der":    return "👉 pulgar = DERECHA";
            case "brazos_arriba": return "🙌 brazos arriba = DESPEGAR";
            case "brazos_abajo":  return "🧍 brazos abajo = ATERRIZAR";
            case "brazo_der":     return "🙋 brazo derecho = DERECHA";
            case "brazo_izq":     return "🙋 brazo izquierdo = IZQUIERDA";
            case "cruz":          return "🅃 brazos en cruz = QUIETO";
            default:              return g;
        }
    }

    // ------------------------------------------------------------------
    //  Caja del torso: es lo que persigue el PID en modo SEGUIR
    // ------------------------------------------------------------------

    /** Devuelve {x, y, w, h} del cuerpo, o null si no se ve con claridad. */
    public int[] targetBox() {
        float[] b = body;
        if (b == null) return null;
        float lsX = b[11 * 3], lsY = b[11 * 3 + 1], lsV = b[11 * 3 + 2];
        float rsX = b[12 * 3], rsY = b[12 * 3 + 1], rsV = b[12 * 3 + 2];
        if (Math.min(lsV, rsV) < 0.4f) return null;
        float lhX = b[23 * 3], lhY = b[23 * 3 + 1];
        float rhX = b[24 * 3], rhY = b[24 * 3 + 1];

        float minX = Math.min(Math.min(lsX, rsX), Math.min(lhX, rhX));
        float maxX = Math.max(Math.max(lsX, rsX), Math.max(lhX, rhX));
        float minY = Math.min(Math.min(lsY, rsY), Math.min(lhY, rhY));
        float maxY = Math.max(Math.max(lsY, rsY), Math.max(lhY, rhY));

        // La cabeza queda por encima de los hombros: se sube la caja para
        // encuadrar a la persona entera y no sólo el tronco.
        float head = b[1];
        if (head < minY) minY = head;

        int w = (int) Math.max(4f, maxX - minX);
        int h = (int) Math.max(4f, maxY - minY);
        return new int[]{(int) minX, (int) minY, w, h};
    }

    /**
     * Ancho de hombros en píxeles. Para medir la distancia es mucho más estable
     * que el alto de la caja (que cambia al mover los brazos o agacharse).
     */
    public float shoulderWidth() {
        float[] b = body;
        if (b == null) return 0f;
        return Math.abs(b[11 * 3] - b[12 * 3]);
    }

    // ------------------------------------------------------------------
    //  Gestos
    // ------------------------------------------------------------------

    /** Cuenta dedos extendidos y traduce la postura de la mano. */
    private String handGesture(float[] p) {
        float scale = Math.max(20f, dist(p, 0, 9));
        boolean[] up = new boolean[4];
        int[][] pairs = {{8, 6}, {12, 10}, {16, 14}, {20, 18}};
        int n = 0;
        for (int i = 0; i < 4; i++) {
            up[i] = p[pairs[i][0] * 2 + 1] < p[pairs[i][1] * 2 + 1] - scale * 0.15f;
            if (up[i]) n++;
        }
        // Pulgar: extendido si la punta se aleja de la base del meñique más que
        // su propia articulación. Vale para mano izquierda o derecha y girada.
        boolean thumbOut = dist(p, 4, 17) > dist(p, 3, 17) + scale * 0.12f;
        if (thumbOut) n++;
        fingers = n;

        if (n == 5) return "palma";
        if (n == 0) return "puño";
        if (thumbOut && !up[0] && !up[1] && !up[2] && !up[3]) {
            return p[4 * 2] < p[0] ? "pulgar_izq" : "pulgar_der";
        }
        if (!thumbOut) {
            if (up[0] && !up[1] && !up[2] && !up[3]) return "uno";
            if (up[0] && up[1] && !up[2] && !up[3]) return "dos";
            if (up[0] && up[1] && up[2] && !up[3]) return "tres";
        }
        return "";
    }

    /** Gestos con los brazos, medidos en unidades de "ancho de hombros". */
    private String bodyGesture(float[] b) {
        float lsX = b[11 * 3], lsY = b[11 * 3 + 1], lsV = b[11 * 3 + 2];
        float rsX = b[12 * 3], rsY = b[12 * 3 + 1], rsV = b[12 * 3 + 2];
        if (Math.min(lsV, rsV) < 0.4f) return "";

        float lwX = b[15 * 3], lwY = b[15 * 3 + 1], lwV = b[15 * 3 + 2];
        float rwX = b[16 * 3], rwY = b[16 * 3 + 1], rwV = b[16 * 3 + 2];

        float sw = Math.max(30f, Math.abs(lsX - rsX));
        float sy = (lsY + rsY) / 2f;

        boolean lUp = lwY < sy - sw * 0.6f && lwV > 0.3f;
        boolean rUp = rwY < sy - sw * 0.6f && rwV > 0.3f;
        if (lUp && rUp) return "brazos_arriba";

        boolean lOut = Math.abs(lwY - sy) < sw * 0.45f
                && Math.abs(lwX - lsX) > sw * 0.8f && lwV > 0.3f;
        boolean rOut = Math.abs(rwY - sy) < sw * 0.45f
                && Math.abs(rwX - rsX) > sw * 0.8f && rwV > 0.3f;
        if (lOut && rOut) return "cruz";
        if (rOut) return "brazo_der";
        if (lOut) return "brazo_izq";

        boolean lDown = lwY > sy + sw * 1.1f && Math.abs(lwX - lsX) < sw * 0.5f;
        boolean rDown = rwY > sy + sw * 1.1f && Math.abs(rwX - rsX) < sw * 0.5f;
        if (lDown && rDown && Math.min(lwV, rwV) > 0.3f) return "brazos_abajo";

        return "";
    }

    private static float dist(float[] p, int a, int b) {
        float dx = p[a * 2] - p[b * 2];
        float dy = p[a * 2 + 1] - p[b * 2 + 1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
