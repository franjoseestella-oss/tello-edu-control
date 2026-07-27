package com.fran.tello;

import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Estación de control del DJI Tello EDU:
 * vídeo a pantalla completa + HUD de telemetría + joysticks analógicos +
 * despegue/aterrizaje/emergencia, flips, foto, grabación y visión OpenCV.
 */
public class MainActivity extends AppCompatActivity
        implements TelloController.Listener, SurfaceHolder.Callback,
                   VisionProcessor.Listener, VideoRecorder.Listener {

    private TelloController controller;
    private VideoDecoder videoDecoder;
    private VisionProcessor vision;
    private VideoRecorder recorder;

    private SurfaceView surfaceVideo;
    private OverlayView overlay;
    private boolean surfaceReady = false;

    private TextView txtStatus, txtSpeed;
    private TextView hudBattery, hudAlt, hudSpeed, hudTime, hudTemp, hudPad;
    private Button btnConnect, btnDetect, btnFollow, btnGesture, btnRecord;
    private JoystickView joyLeft, joyRight;
    private SeekBar seekSpeed;

    private int speed = 50;
    private float jLr = 0, jFb = 0, jUd = 0, jYaw = 0;   // ejes de los joysticks [-1,1]

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceVideo = findViewById(R.id.surfaceVideo);
        surfaceVideo.getHolder().addCallback(this);
        overlay = findViewById(R.id.overlay);

        txtStatus = findViewById(R.id.txtStatus);
        txtSpeed = findViewById(R.id.txtSpeed);
        hudBattery = findViewById(R.id.hudBattery);
        hudAlt = findViewById(R.id.hudAlt);
        hudSpeed = findViewById(R.id.hudSpeed);
        hudTime = findViewById(R.id.hudTime);
        hudTemp = findViewById(R.id.hudTemp);
        hudPad = findViewById(R.id.hudPad);

        btnConnect = findViewById(R.id.btnConnect);
        btnDetect = findViewById(R.id.btnDetect);
        btnFollow = findViewById(R.id.btnFollow);
        btnGesture = findViewById(R.id.btnGesture);
        btnRecord = findViewById(R.id.btnRecord);
        joyLeft = findViewById(R.id.joyLeft);
        joyRight = findViewById(R.id.joyRight);
        seekSpeed = findViewById(R.id.seekSpeed);

        // Joysticks
        joyLeft.setListener((x, y) -> { jYaw = x; jUd = y; pushRc(); });
        joyRight.setListener((x, y) -> { jLr = x; jFb = y; pushRc(); });

        seekSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                speed = Math.max(10, p);
                txtSpeed.setText("Velocidad: " + speed + "%");
                if (controller != null && controller.isConnected()) controller.setSpeed(speed);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        btnConnect.setOnClickListener(v -> { haptic(v); connectDrone(); });
        findViewById(R.id.btnTakeoff).setOnClickListener(v -> { haptic(v); if (ensureConnected()) controller.takeoff(); });
        findViewById(R.id.btnLand).setOnClickListener(v -> { haptic(v); if (ensureConnected()) controller.land(); });
        findViewById(R.id.btnEmergency).setOnClickListener(v -> { haptic(v); if (ensureConnected()) confirmEmergency(); });

        findViewById(R.id.btnFlipL).setOnClickListener(v -> { haptic(v); if (ensureConnected()) controller.flip('l'); });
        findViewById(R.id.btnFlipR).setOnClickListener(v -> { haptic(v); if (ensureConnected()) controller.flip('r'); });
        findViewById(R.id.btnFlipF).setOnClickListener(v -> { haptic(v); if (ensureConnected()) controller.flip('f'); });
        findViewById(R.id.btnFlipB).setOnClickListener(v -> { haptic(v); if (ensureConnected()) controller.flip('b'); });

        findViewById(R.id.btnPhoto).setOnClickListener(v -> { haptic(v); takePhoto(); });
        btnRecord.setOnClickListener(v -> { haptic(v); toggleRecording(); });

        setupModeButtons();

        txtStatus.setText("Cargando OpenCV...");
        new Thread(() -> {
            vision = new VisionProcessor(this, this);
            runOnUiThread(() -> txtStatus.setText(
                    vision.isReady() ? "Desconectado · OpenCV listo" : "Desconectado"));
        }, "python-init").start();
    }

    private void pushRc() {
        if (controller == null || !controller.isConnected()) return;
        if (vision != null && vision.getMode() == VisionProcessor.MODE_FOLLOW) return; // la visión manda
        float k = speed / 100f;
        controller.setRc(Math.round(jLr * 100 * k), Math.round(jFb * 100 * k),
                         Math.round(jUd * 100 * k), Math.round(jYaw * 100 * k));
    }

    // ---------- Visión ----------

    private void setupModeButtons() {
        btnDetect.setOnClickListener(v -> {
            if (!visionReady()) return;
            haptic(v);
            int m = vision.getMode() == VisionProcessor.MODE_DETECT
                    ? VisionProcessor.MODE_OFF : VisionProcessor.MODE_DETECT;
            vision.setMode(m);
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
        if (videoDecoder == null || (controller == null || !controller.isConnected())) {
            Toast.makeText(this, "Conéctate primero", Toast.LENGTH_SHORT).show();
            return;
        }
        if (recorder != null && recorder.isRecording()) {
            recorder.stop();
            btnRecord.setSelected(false);
            btnRecord.setText("⏺");
        } else {
            File dir = getExternalFilesDir(null);
            File out = new File(dir, "rec_" + timestamp() + ".mp4");
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

    @Override
    public void onRecordingStopped(String path, boolean ok) {
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
        if (wifi == null) {
            Toast.makeText(this, "Conéctate al WiFi del Tello (TELLO-XXXXXX)", Toast.LENGTH_LONG).show();
            txtStatus.setText("Sin WiFi del Tello");
            return;
        }
        if (controller != null) controller.disconnect();
        if (videoDecoder != null) videoDecoder.stop();

        controller = new TelloController(this, wifi);
        videoDecoder = new VideoDecoder(surfaceVideo, wifi);
        if (vision != null) {
            vision.setController(controller);
            videoDecoder.setFrameListener(vision);
        }
        txtStatus.setText("Conectando...");
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

    private boolean ensureConnected() {
        if (controller == null || !controller.isConnected()) {
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

    // ---------- Callbacks del controlador ----------

    @Override public void onStatus(String msg) { txtStatus.setText(msg); }

    @Override public void onTelemetry(TelloController.Telemetry t) {
        hudBattery.setText((t.battery <= 15 ? "🪫 " : "🔋 ") + t.battery + "%");
        hudAlt.setText("⛰ " + t.height + "cm");
        hudSpeed.setText(String.format(Locale.US, "🚀 %.0f", t.speedKmh()));
        hudTime.setText("⏱ " + t.flightTime + "s");
        hudTemp.setText("🌡 " + ((t.templ + t.temph) / 2) + "°");
        hudPad.setText(t.missionPad >= 0 ? "🎯 pad " + t.missionPad : "🎯 --");
    }

    @Override public void onConnected(boolean ok) {
        if (ok) { btnConnect.setText("CONECTADO"); if (surfaceReady && videoDecoder != null) videoDecoder.start(); }
        else btnConnect.setText("CONECTAR");
    }

    @Override public void onResponse(String command, String response) {
        if (response.toLowerCase(Locale.US).startsWith("error"))
            txtStatus.setText(command + " → " + response);
    }

    // ---------- Callbacks de visión ----------

    @Override public void onDetections(List<OverlayView.Face> faces, String gesture,
                                       OverlayView.Qr qr, int sw, int sh) {
        overlay.update(faces, gesture, qr, sw, sh);
    }

    @Override public void onVisionStatus(String msg) { txtStatus.setText(msg); }

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

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
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
        if (recorder != null && recorder.isRecording()) recorder.stop();
        if (videoDecoder != null) videoDecoder.stop();
        if (controller != null) controller.disconnect();
    }
}
