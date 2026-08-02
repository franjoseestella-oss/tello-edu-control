package com.fran.tello;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Estación de control del DJI Tello EDU. Vídeo + HUD + joysticks + gamepad + voz,
 * despegue/aterrizaje/emergencia, flips, foto/vídeo, visión OpenCV (caras, color,
 * gestos, QR), misiones, seguridad y caja negra.
 */
public class MainActivity extends AppCompatActivity
        implements TelloController.Listener, SurfaceHolder.Callback,
                   VisionProcessor.Listener, VideoRecorder.Listener,
                   GamepadController.Listener, VoiceController.Listener {

    private static final int REQ_AUDIO = 101;

    private TelloController controller;
    private VideoDecoder videoDecoder;
    private VisionProcessor vision;
    private VideoRecorder recorder;
    private GamepadController gamepad;
    private VoiceController voice;
    private final FlightLogger logger = new FlightLogger();

    private SurfaceView surfaceVideo;
    private OverlayView overlay;
    private boolean surfaceReady = false;

    private TextView txtStatus, txtSpeed, txtConnStatus;
    private TextView hudBattery, hudAlt, hudSpeed, hudTime, hudTemp, hudPad;
    private Button btnDetect, btnFollow, btnColor, btnGesture, btnRecord, btnVoice, btnLog;
    private View connectOverlay;
    private JoystickView joyLeft, joyRight;
    private SeekBar seekSpeed;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private int speed = 50;
    private float jLr = 0, jFb = 0, jUd = 0, jYaw = 0;
    private int colorIndex = 0;

    // Misión (secuencia de pasos): int[]{kind, value}. kind 0..5 = up/down/left/right/forward/back, 6 = giro
    private final List<int[]> mission = new ArrayList<>();

    // Seguridad
    private boolean autoLand = true;
    private int autoLandPct = 10;
    private final int lowWarnPct = 20;
    private boolean lowWarned = false;
    private boolean autoLanded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DebugLog.init(this);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("tello", MODE_PRIVATE);

        surfaceVideo = findViewById(R.id.surfaceVideo);
        surfaceVideo.getHolder().addCallback(this);
        overlay = findViewById(R.id.overlay);
        connectOverlay = findViewById(R.id.connectOverlay);

        txtStatus = findViewById(R.id.txtStatus);
        txtSpeed = findViewById(R.id.txtSpeed);
        txtConnStatus = findViewById(R.id.txtConnStatus);
        hudBattery = findViewById(R.id.hudBattery);
        hudAlt = findViewById(R.id.hudAlt);
        hudSpeed = findViewById(R.id.hudSpeed);
        hudTime = findViewById(R.id.hudTime);
        hudTemp = findViewById(R.id.hudTemp);
        hudPad = findViewById(R.id.hudPad);

        btnDetect = findViewById(R.id.btnDetect);
        btnFollow = findViewById(R.id.btnFollow);
        btnColor = findViewById(R.id.btnColor);
        btnGesture = findViewById(R.id.btnGesture);
        btnRecord = findViewById(R.id.btnRecord);
        btnVoice = findViewById(R.id.btnVoice);
        btnLog = findViewById(R.id.btnLog);
        joyLeft = findViewById(R.id.joyLeft);
        joyRight = findViewById(R.id.joyRight);
        seekSpeed = findViewById(R.id.seekSpeed);

        speed = prefs.getInt("speed", 50);
        autoLand = prefs.getBoolean("autoLand", true);
        autoLandPct = prefs.getInt("autoLandPct", 10);
        seekSpeed.setProgress(speed);
        txtSpeed.setText("Velocidad: " + speed + "%");

        joyLeft.setListener((x, y) -> { jYaw = x; jUd = y; pushRc(); });
        joyRight.setListener((x, y) -> { jLr = x; jFb = y; pushRc(); });

        seekSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                speed = Math.max(10, p);
                txtSpeed.setText("Velocidad: " + speed + "%");
                if (controller != null && controller.isConnected()) controller.setSpeed(speed);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { prefs.edit().putInt("speed", speed).apply(); }
        });

        findViewById(R.id.btnConnectBig).setOnClickListener(v -> { tap("CONECTAR"); haptic(v); connectDrone(); });
        findViewById(R.id.btnTakeoff).setOnClickListener(v -> { tap("DESPEGAR"); haptic(v); if (ensureConnected()) controller.takeoff(); });
        findViewById(R.id.btnLand).setOnClickListener(v -> { tap("ATERRIZAR"); haptic(v); if (ensureConnected()) controller.land(); });
        findViewById(R.id.btnEmergency).setOnClickListener(v -> { tap("EMERGENCIA"); haptic(v); if (ensureConnected()) confirmEmergency(); });
        findViewById(R.id.btnFlipL).setOnClickListener(v -> { tap("flip L"); haptic(v); if (ensureConnected()) controller.flip('l'); });
        findViewById(R.id.btnFlipR).setOnClickListener(v -> { tap("flip R"); haptic(v); if (ensureConnected()) controller.flip('r'); });
        findViewById(R.id.btnFlipF).setOnClickListener(v -> { tap("flip F"); haptic(v); if (ensureConnected()) controller.flip('f'); });
        findViewById(R.id.btnFlipB).setOnClickListener(v -> { tap("flip B"); haptic(v); if (ensureConnected()) controller.flip('b'); });
        findViewById(R.id.btnPhoto).setOnClickListener(v -> { haptic(v); takePhoto(); });
        btnRecord.setOnClickListener(v -> { haptic(v); toggleRecording(); });
        btnVoice.setOnClickListener(v -> { haptic(v); toggleVoice(); });
        btnLog.setOnClickListener(v -> { haptic(v); toggleLog(); });
        findViewById(R.id.btnDebug).setOnClickListener(v -> { haptic(v); showDebug(); });
        findViewById(R.id.btnSettings).setOnClickListener(v -> { haptic(v); showSettings(); });
        findViewById(R.id.btnMission).setOnClickListener(v -> { haptic(v); showMission(); });

        setupModeButtons();

        gamepad = new GamepadController(this);
        voice = new VoiceController(this, this);

        setConnStatus("Cargando OpenCV...");
        new Thread(() -> {
            vision = new VisionProcessor(this, this);
            runOnUiThread(() -> setConnStatus(vision.isReady()
                    ? "1. Conéctate al WiFi del dron (TELLO-XXXXXX)\n2. Pulsa Conectar"
                    : "1. Conéctate al WiFi del dron (TELLO-XXXXXX)\n2. Pulsa Conectar"));
        }, "python-init").start();

        startWatchdog();
    }

    private void pushRc() {
        if (controller == null || !controller.isConnected()) {
            if (jLr != 0 || jFb != 0 || jUd != 0 || jYaw != 0)
                DebugLog.d("WARN", "joystick movido sin conexión: no se envía nada");
            return;
        }
        int m = vision != null ? vision.getMode() : 0;
        if (m == VisionProcessor.MODE_FOLLOW || m == VisionProcessor.MODE_COLOR) {
            DebugLog.d("WARN", "joystick IGNORADO: el modo de visión "
                    + (m == VisionProcessor.MODE_FOLLOW ? "SEGUIR" : "COLOR") + " controla el dron");
            return; // la visión manda
        }
        float k = speed / 100f;
        controller.setRc(Math.round(jLr * 100 * k), Math.round(jFb * 100 * k),
                         Math.round(jUd * 100 * k), Math.round(jYaw * 100 * k));
    }

    // ---------- Gamepad ----------

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (gamepad != null && gamepad.onMotion(event)) return true;
        return super.onGenericMotionEvent(event);
    }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (gamepad != null && gamepad.onKey(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (gamepad != null && GamepadController.isGamepad(event.getSource())
                && gamepad.onKey(keyCode, event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override public void onRc(float lr, float fb, float ud, float yaw) {
        jLr = lr; jFb = fb; jUd = ud; jYaw = yaw; pushRc();
    }
    @Override public void onAction(String action) { runOnUiThread(() -> handleAction(action)); }

    // ---------- Voz ----------

    private void toggleVoice() {
        if (voice.isActive()) {
            voice.stop(); btnVoice.setSelected(false);
            Toast.makeText(this, "Voz desactivada", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        voice.start(); btnVoice.setSelected(true);
        Toast.makeText(this, "🎤 Di: despega, aterriza, sube, gira derecha, foto...", Toast.LENGTH_LONG).show();
    }

    @Override public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_AUDIO && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) toggleVoice();
    }

    @Override public void onCommand(String action) { runOnUiThread(() -> handleAction(action)); }
    @Override public void onVoiceStatus(String heard) { runOnUiThread(() -> txtStatus.setText(heard)); }

    // ---------- Acciones unificadas ----------

    private void handleAction(String action) {
        if (action == null) return;
        DebugLog.d("UI", "acción '" + action + "' (mando/voz/gesto)");
        switch (action) {
            case "takeoff": if (ensureConnected()) controller.takeoff(); return;
            case "land":    if (ensureConnected()) controller.land(); return;
            case "emergency": if (ensureConnected()) controller.emergency(); return;
            case "photo":   takePhoto(); return;
            case "record":  toggleRecording(); return;
            case "flipL":   if (ensureConnected()) controller.flip('l'); return;
            case "flipR":   if (ensureConnected()) controller.flip('r'); return;
            case "flipF":   if (ensureConnected()) controller.flip('f'); return;
            case "flipB":   if (ensureConnected()) controller.flip('b'); return;
            case "stop":    if (controller != null) controller.resetJoystick(); return;
        }
        if (!ensureConnected()) return;
        switch (action) {
            case "up":      pulseUd(speed); break;
            case "down":    pulseUd(-speed); break;
            case "left":    pulseLr(-speed); break;
            case "right":   pulseLr(speed); break;
            case "forward": pulseFb(speed); break;
            case "back":    pulseFb(-speed); break;
            case "yawL":    pulseYaw(-speed); break;
            case "yawR":    pulseYaw(speed); break;
        }
    }

    private void pulseUd(int v)  { controller.setUpDown(v);      ui.postDelayed(() -> controller.setUpDown(0), 1200); }
    private void pulseLr(int v)  { controller.setLeftRight(v);   ui.postDelayed(() -> controller.setLeftRight(0), 1200); }
    private void pulseFb(int v)  { controller.setForwardBack(v); ui.postDelayed(() -> controller.setForwardBack(0), 1200); }
    private void pulseYaw(int v) { controller.setYaw(v);         ui.postDelayed(() -> controller.setYaw(0), 1200); }

    // ---------- Caja negra ----------

    private void toggleLog() {
        if (logger.isLogging()) {
            String p = logger.stop();
            btnLog.setSelected(false);
            Toast.makeText(this, "📈 Registro guardado:\n" + p, Toast.LENGTH_LONG).show();
        } else {
            logger.start(this);
            btnLog.setSelected(logger.isLogging());
            Toast.makeText(this, logger.isLogging() ? "📈 Registrando vuelo..." : "No se pudo iniciar el registro",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Depuración ----------

    private void tap(String what) { DebugLog.d("UI", "botón " + what); }

    /** Ventana 🐞: log en vivo, diagnóstico del enlace y compartir el fichero. */
    private void showDebug() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);

        final TextView tv = new TextView(this);
        tv.setTextSize(9);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setText(DebugLog.tail(200));

        final ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        sv.setLayoutParams(svp);
        root.addView(sv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"🔄 Actualizar", "🩺 Diagnóstico", "📤 Compartir"};
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setTextSize(11);
            b.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final int idx = i;
            b.setOnClickListener(v -> {
                if (idx == 0) {
                    tv.setText(DebugLog.tail(200));
                    sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
                } else if (idx == 1) {
                    if (controller == null) {
                        Toast.makeText(this, "Pulsa CONECTAR primero", Toast.LENGTH_SHORT).show();
                    } else {
                        controller.diagnose();
                        Toast.makeText(this, "Diagnóstico en marcha (~20 s). Pulsa Actualizar.",
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    startActivity(Intent.createChooser(DebugLog.shareIntent(this), "Enviar el log"));
                }
            });
            row.addView(b);
        }
        root.addView(row);

        final CheckBox cbRc = new CheckBox(this);
        cbRc.setText("Registrar todos los paquetes rc (20/s, log enorme)");
        cbRc.setTextSize(11);
        cbRc.setChecked(TelloController.verboseRc);
        cbRc.setOnCheckedChangeListener((b, on) -> {
            TelloController.verboseRc = on;
            DebugLog.d("UI", "rc detallado = " + on);
        });
        root.addView(cbRc);

        final TextView info = new TextView(this);
        info.setTextSize(10);
        info.setText("Fichero: " + DebugLog.getPath());
        root.addView(info);

        new AlertDialog.Builder(this)
                .setTitle("🐞 Depuración del enlace")
                .setView(root)
                .setPositiveButton("Cerrar", null)
                .show();
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
    }

    // ---------- Ajustes ----------

    private void showSettings() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        ll.setPadding(pad, pad, pad, pad);

        final CheckBox cb = new CheckBox(this);
        cb.setText("Auto-aterrizar con batería crítica");
        cb.setChecked(autoLand);
        ll.addView(cb);

        final TextView lbl = new TextView(this);
        lbl.setText("Umbral de batería: " + autoLandPct + "%");
        ll.addView(lbl);

        final SeekBar sb = new SeekBar(this);
        sb.setMax(50); sb.setProgress(autoLandPct);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                lbl.setText("Umbral de batería: " + Math.max(5, p) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        ll.addView(sb);

        final TextView info = new TextView(this);
        info.setText("\nMando: A=despegar B=aterrizar X=foto Y=grabar L1/R1=flip START=emergencia\n"
                + "Voz: «despega», «aterriza», «sube», «gira derecha», «foto», «para»…\n"
                + "Color: pulsación larga en 🟢 para cambiar de color.");
        info.setTextSize(12);
        ll.addView(info);

        new AlertDialog.Builder(this)
                .setTitle("Ajustes")
                .setView(ll)
                .setPositiveButton("Guardar", (d, w) -> {
                    autoLand = cb.isChecked();
                    autoLandPct = Math.max(5, sb.getProgress());
                    prefs.edit().putBoolean("autoLand", autoLand).putInt("autoLandPct", autoLandPct).apply();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    // ---------- Misiones ----------

    private void showMission() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        final TextView list = new TextView(this);
        list.setText(missionText());
        list.setTextSize(13);

        final String[][] adders = {
            {"⬆ Subir 50", "0"}, {"⬇ Bajar 50", "1"},
            {"⬅ Izq 50", "2"}, {"➡ Der 50", "3"},
            {"↑ Adelante 50", "4"}, {"↓ Atrás 50", "5"},
            {"⟲ Girar 90", "-90"}, {"⟳ Girar 90", "90"},
        };
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < adders.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 2 && j < adders.length; j++) {
                Button b = new Button(this);
                b.setText(adders[j][0]);
                b.setTextSize(12);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                b.setLayoutParams(lp);
                final int code = Integer.parseInt(adders[j][1]);
                final boolean rotate = adders[j][0].contains("Girar");
                b.setOnClickListener(v -> {
                    if (rotate) mission.add(new int[]{6, code});
                    else mission.add(new int[]{code, 50});
                    list.setText(missionText());
                });
                row.addView(b);
            }
            grid.addView(row);
        }

        Button clear = new Button(this);
        clear.setText("🗑 Vaciar");
        clear.setOnClickListener(v -> { mission.clear(); list.setText(missionText()); });
        grid.addView(clear);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.addView(list);
        inner.addView(grid);
        ScrollView sv = new ScrollView(this);
        sv.addView(inner);
        root.addView(sv);

        new AlertDialog.Builder(this)
                .setTitle("Misión (waypoints)")
                .setView(root)
                .setPositiveButton("▶ Ejecutar", (d, w) -> runMission())
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private String missionText() {
        if (mission.isEmpty()) return "Sin pasos. Añade movimientos abajo.\n";
        String[] names = {"Subir", "Bajar", "Izquierda", "Derecha", "Adelante", "Atrás"};
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (int[] s : mission) {
            if (s[0] == 6) sb.append(i++).append(". Girar ").append(s[1]).append("°\n");
            else sb.append(i++).append(". ").append(names[s[0]]).append(" ").append(s[1]).append(" cm\n");
        }
        return sb.toString();
    }

    private void runMission() {
        if (!ensureConnected() || mission.isEmpty()) return;
        final List<int[]> steps = new ArrayList<>(mission);
        final String[] dirs = {"up", "down", "left", "right", "forward", "back"};
        Toast.makeText(this, "▶ Ejecutando misión (" + steps.size() + " pasos). Despega primero.", Toast.LENGTH_LONG).show();
        DebugLog.d("UI", "▶ misión de " + steps.size() + " pasos");
        new Thread(() -> {
            controller.setRcSuspended(true);
            try {
                int i = 1;
                for (int[] s : steps) {
                    DebugLog.d("STEP", "misión paso " + (i++) + "/" + steps.size());
                    if (s[0] == 6) {
                        controller.rotateCmd(s[1]);
                        sleep(Math.abs(s[1]) * 22L + 1500);
                    } else {
                        controller.moveCmd(dirs[s[0]], s[1]);
                        sleep((long) s[1] * 1000 / Math.max(10, speed) + 1500);
                    }
                }
            } finally {
                controller.setRcSuspended(false);
            }
            runOnUiThread(() -> Toast.makeText(this, "✅ Misión completada", Toast.LENGTH_SHORT).show());
        }, "mission").start();
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignore) { } }

    // ---------- Visión ----------

    private void setupModeButtons() {
        btnDetect.setOnClickListener(v -> {
            if (!visionReady()) return;
            haptic(v);
            vision.setMode(vision.getMode() == VisionProcessor.MODE_DETECT
                    ? VisionProcessor.MODE_OFF : VisionProcessor.MODE_DETECT);
            refreshModeButtons();
        });
        btnFollow.setOnClickListener(v -> {
            if (!visionReady()) return;
            haptic(v);
            int m = vision.getMode() == VisionProcessor.MODE_FOLLOW
                    ? VisionProcessor.MODE_OFF : VisionProcessor.MODE_FOLLOW;
            vision.setMode(m);
            if (m == VisionProcessor.MODE_FOLLOW)
                Toast.makeText(this, "El dron seguirá tu cara. ¡Ojo!", Toast.LENGTH_SHORT).show();
            refreshModeButtons();
        });
        btnColor.setOnClickListener(v -> {
            if (!visionReady()) return;
            haptic(v);
            int m = vision.getMode() == VisionProcessor.MODE_COLOR
                    ? VisionProcessor.MODE_OFF : VisionProcessor.MODE_COLOR;
            vision.setMode(m);
            if (m == VisionProcessor.MODE_COLOR) {
                vision.setColor(VisionProcessor.COLORS[colorIndex]);
                Toast.makeText(this, "Siguiendo color: " + VisionProcessor.COLORS[colorIndex]
                        + " (mantén pulsado para cambiar)", Toast.LENGTH_LONG).show();
            }
            refreshModeButtons();
        });
        btnColor.setOnLongClickListener(v -> {
            if (!visionReady()) return false;
            colorIndex = (colorIndex + 1) % VisionProcessor.COLORS.length;
            vision.setColor(VisionProcessor.COLORS[colorIndex]);
            btnColor.setText("🟢 " + VisionProcessor.COLORS[colorIndex]);
            if (vision.getMode() != VisionProcessor.MODE_COLOR) vision.setMode(VisionProcessor.MODE_COLOR);
            refreshModeButtons();
            return true;
        });
        btnGesture.setOnClickListener(v -> {
            if (!visionReady()) return;
            haptic(v);
            vision.setGesture(!vision.isGesture());
            refreshModeButtons();
        });
        findViewById(R.id.btnEnroll).setOnClickListener(v -> {
            if (!visionReady()) return;
            haptic(v);
            if (vision.getMode() == VisionProcessor.MODE_OFF) {
                vision.setMode(VisionProcessor.MODE_DETECT);
                refreshModeButtons();
            }
            promptEnroll();
        });
    }

    private boolean visionReady() {
        if (vision == null || !vision.isReady()) {
            Toast.makeText(this, "OpenCV aún no está listo", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void refreshModeButtons() {
        int m = vision.getMode();
        btnDetect.setSelected(m == VisionProcessor.MODE_DETECT);
        btnFollow.setSelected(m == VisionProcessor.MODE_FOLLOW);
        btnColor.setSelected(m == VisionProcessor.MODE_COLOR);
        btnGesture.setSelected(vision.isGesture());
        if (m == VisionProcessor.MODE_OFF && !vision.isGesture()) overlay.clear();
    }

    private void promptEnroll() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Nombre de la persona");
        new AlertDialog.Builder(this)
                .setTitle("Memorizar cara")
                .setMessage("Mira a la cámara y escribe el nombre")
                .setView(input)
                .setPositiveButton("Guardar", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) vision.enrollNext(name);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- Foto / vídeo ----------

    private void takePhoto() {
        if (videoDecoder == null) { Toast.makeText(this, "Sin vídeo", Toast.LENGTH_SHORT).show(); return; }
        Bitmap bmp = videoDecoder.getSnapshot();
        if (bmp == null) { Toast.makeText(this, "Sin vídeo todavía", Toast.LENGTH_SHORT).show(); return; }
        new Thread(() -> {
            String dst = MediaSaver.saveImage(this, bmp, "tello_" + timestamp());
            runOnUiThread(() -> Toast.makeText(this,
                    dst != null ? "📷 Foto guardada en " + dst : "Error al guardar la foto",
                    Toast.LENGTH_SHORT).show());
        }, "photo-save").start();
    }

    private void toggleRecording() {
        if (videoDecoder == null || controller == null || !controller.isConnected()) {
            Toast.makeText(this, "Conéctate primero", Toast.LENGTH_SHORT).show();
            return;
        }
        if (recorder != null && recorder.isRecording()) {
            recorder.stop();
            btnRecord.setSelected(false);
            btnRecord.setText("⏺");
        } else {
            File out = new File(getExternalFilesDir(null), "rec_" + timestamp() + ".mp4");
            recorder = new VideoRecorder(this);
            videoDecoder.setRecorder(recorder);
            boolean ok = recorder.start(out.getAbsolutePath(),
                    videoDecoder.getFrameWidth(), videoDecoder.getFrameHeight());
            if (ok) {
                btnRecord.setSelected(true);
                btnRecord.setText("⏹");
                Toast.makeText(this, "🔴 Grabando...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Este dispositivo no soporta grabación", Toast.LENGTH_LONG).show();
                videoDecoder.setRecorder(null);
                recorder = null;
            }
        }
    }

    @Override public void onRecordingStopped(String path, boolean ok) {
        if (videoDecoder != null) videoDecoder.setRecorder(null);
        if (!ok || path == null) {
            runOnUiThread(() -> Toast.makeText(this, "Error en la grabación", Toast.LENGTH_SHORT).show());
            return;
        }
        new Thread(() -> {
            String dst = MediaSaver.saveVideo(this, new File(path), "tello_" + timestamp());
            runOnUiThread(() -> Toast.makeText(this,
                    dst != null ? "🎬 Vídeo guardado en " + dst : "Vídeo en " + path,
                    Toast.LENGTH_LONG).show());
        }, "video-save").start();
    }

    // ---------- Conexión ----------

    private void connectDrone() {
        Network wifi = getWifiNetwork();
        DebugLog.d("NET", "redes del móvil: " + describeNetworks());
        if (wifi == null) {
            DebugLog.d("ERR", "no hay ninguna red WiFi disponible: imposible hablar con el dron");
            setConnStatus("⚠️ No detecto WiFi del Tello.\nConéctate a la red TELLO-XXXXXX y reintenta.");
            return;
        }
        if (controller != null) controller.disconnect();
        if (videoDecoder != null) videoDecoder.stop();

        lowWarned = false; autoLanded = false;
        controller = new TelloController(this, wifi);
        videoDecoder = new VideoDecoder(surfaceVideo, wifi);
        if (vision != null) {
            vision.setController(controller);
            videoDecoder.setFrameListener(vision);
        }
        setConnStatus("Conectando...");
        new Thread(() -> {
            controller.connect();
            controller.setSpeed(speed);
            if (surfaceReady) videoDecoder.start();
        }, "connect-thread").start();
    }

    private Network getWifiNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    /** Para el log: qué redes ve el móvil y cuál es la del dron. */
    private String describeNetworks() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return "sin ConnectivityManager";
        StringBuilder sb = new StringBuilder();
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null) continue;
            String tipo = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "WIFI"
                    : c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "DATOS" : "otra";
            String ips = "";
            try {
                android.net.LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) {
                    StringBuilder a = new StringBuilder();
                    for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                        if (la.getAddress() instanceof java.net.Inet4Address)
                            a.append(la.getAddress().getHostAddress()).append(" ");
                    }
                    ips = a.toString().trim();
                }
            } catch (Exception ignore) { }
            sb.append(tipo).append("(").append(ips.isEmpty() ? "?" : ips)
              .append(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ? ",internet" : "")
              .append(") ");
        }
        String s = sb.toString().trim();
        if (!s.contains("192.168.10."))
            DebugLog.d("WARN", "ninguna interfaz tiene IP 192.168.10.x: el móvil NO está en el WiFi del Tello");
        return s.isEmpty() ? "ninguna" : s;
    }

    private boolean ensureConnected() {
        if (controller == null || !controller.isConnected()) {
            DebugLog.d("WARN", "orden descartada en la app: "
                    + (controller == null ? "no hay controlador (nunca se pulsó CONECTAR)"
                                          : "el controlador no está conectado"));
            Toast.makeText(this, "Pulsa CONECTAR primero", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void confirmEmergency() {
        new AlertDialog.Builder(this)
                .setTitle("¿Parada de emergencia?")
                .setMessage("Se PARAN LOS MOTORES al instante. El dron caerá.")
                .setPositiveButton("PARAR", (d, w) -> controller.emergency())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void setConnStatus(String msg) {
        if (txtConnStatus != null) txtConnStatus.setText(msg);
        txtStatus.setText(msg.split("\n")[0]);
    }

    // ---------- Callbacks del controlador ----------

    @Override public void onStatus(String msg) {
        txtStatus.setText(msg);
        if (connectOverlay.getVisibility() == View.VISIBLE) txtConnStatus.setText(msg);
    }

    @Override public void onTelemetry(TelloController.Telemetry t) {
        hudBattery.setText((t.battery <= 15 ? "🪫 " : "🔋 ") + t.battery + "%");
        hudAlt.setText("⛰ " + t.height + "cm");
        hudSpeed.setText(String.format(Locale.US, "🚀 %.0f", t.speedKmh()));
        hudTime.setText("⏱ " + t.flightTime + "s");
        hudTemp.setText("🌡 " + ((t.templ + t.temph) / 2) + "°");
        hudPad.setText(t.missionPad >= 0 ? "🎯" + t.missionPad : "🎯 --");
        if (logger.isLogging()) logger.log(t);
        checkSafety(t);
    }

    private void checkSafety(TelloController.Telemetry t) {
        if (t.battery <= 0) return;
        if (autoLand && !autoLanded && t.battery <= autoLandPct
                && controller != null && controller.isConnected()) {
            autoLanded = true;
            controller.land();
            Toast.makeText(this, "🔋 ¡Batería crítica! Aterrizando automáticamente.", Toast.LENGTH_LONG).show();
        } else if (!lowWarned && t.battery <= lowWarnPct) {
            lowWarned = true;
            Toast.makeText(this, "⚠️ Batería baja (" + t.battery + "%)", Toast.LENGTH_LONG).show();
            joyLeft.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
        if (t.battery > lowWarnPct + 5) lowWarned = false;
    }

    @Override public void onConnected(boolean ok) {
        DebugLog.d("STEP", "onConnected(" + ok + ")");
        if (ok) {
            connectOverlay.setVisibility(View.GONE);
        } else {
            connectOverlay.setVisibility(View.VISIBLE);
            setConnStatus("No se pudo conectar. Revisa el WiFi del Tello y reintenta.");
        }
    }

    @Override public void onResponse(String command, String response) {
        String r = response.toLowerCase(Locale.US);
        if (r.startsWith("error")) {
            txtStatus.setText(command + " → " + response);
            if ("takeoff".equals(command)) {
                Toast.makeText(this, "⚠️ El dron rechazó el despegue: " + response
                        + "\n(¿batería baja? ¿superficie inclinada?)", Toast.LENGTH_LONG).show();
            }
        } else if (r.startsWith("ok")) {
            if ("takeoff".equals(command)) txtStatus.setText("✅ ¡En el aire!");
            else if ("land".equals(command)) txtStatus.setText("✅ Aterrizado");
        }
    }

    // ---------- Callbacks de visión ----------

    @Override public void onDetections(List<OverlayView.Face> faces, String gesture,
                                       OverlayView.Qr qr, int sw, int sh) {
        overlay.update(faces, gesture, qr, sw, sh);
    }
    @Override public void onVisionStatus(String msg) { txtStatus.setText(msg); }

    // ---------- Vigilancia de señal ----------

    private void startWatchdog() {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (controller != null && controller.isConnected()) {
                    long last = controller.getTelemetry().lastUpdateMs;
                    if (last > 0 && System.currentTimeMillis() - last > 4000) {
                        txtStatus.setText("⚠️ Señal débil con el dron...");
                        DebugLog.d("WARN", "señal débil: " + (System.currentTimeMillis() - last)
                                + " ms sin telemetría");
                    }
                }
                ui.postDelayed(this, 2000);
            }
        }, 2000);
    }

    // ---------- Surface ----------

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (controller != null && controller.isConnected() && videoDecoder != null) videoDecoder.start();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        if (videoDecoder != null) videoDecoder.stop();
    }

    // ---------- Utilidades ----------

    private void haptic(View v) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    @SuppressWarnings("deprecation")
    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_FULLSCREEN
              | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (voice != null) voice.stop();
        if (logger.isLogging()) logger.stop();
        if (recorder != null && recorder.isRecording()) recorder.stop();
        if (videoDecoder != null) videoDecoder.stop();
        if (controller != null) controller.disconnect();
    }
}
