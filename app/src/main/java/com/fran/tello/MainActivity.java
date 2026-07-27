package com.fran.tello;

import android.annotation.SuppressLint;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.InputType;
import android.view.MotionEvent;
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

import java.util.List;

/**
 * Pantalla principal: vídeo a pantalla completa + control manual (flechas,
 * despegar, aterrizar) + visión por OpenCV (seguir cara, gestos, identificación).
 */
public class MainActivity extends AppCompatActivity
        implements TelloController.Listener, SurfaceHolder.Callback, VisionProcessor.Listener {

    private TelloController controller;
    private VideoDecoder videoDecoder;
    private VisionProcessor vision;

    private SurfaceView surfaceVideo;
    private OverlayView overlay;
    private boolean surfaceReady = false;

    private TextView txtStatus, txtBattery, txtSpeed;
    private Button btnConnect, btnVision, btnFollow, btnGesture, btnEnroll;
    private SeekBar seekSpeed;

    private int speed = 50;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceVideo = findViewById(R.id.surfaceVideo);
        surfaceVideo.getHolder().addCallback(this);
        overlay = findViewById(R.id.overlay);

        txtStatus = findViewById(R.id.txtStatus);
        txtBattery = findViewById(R.id.txtBattery);
        txtSpeed = findViewById(R.id.txtSpeed);
        btnConnect = findViewById(R.id.btnConnect);
        seekSpeed = findViewById(R.id.seekSpeed);

        btnVision = findViewById(R.id.btnVision);
        btnFollow = findViewById(R.id.btnFollow);
        btnGesture = findViewById(R.id.btnGesture);
        btnEnroll = findViewById(R.id.btnEnroll);

        seekSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                speed = Math.max(10, p);
                txtSpeed.setText("Velocidad: " + speed + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });

        btnConnect.setOnClickListener(v -> connectDrone());
        findViewById(R.id.btnTakeoff).setOnClickListener(v -> {
            if (ensureConnected()) controller.takeoff();
        });
        findViewById(R.id.btnLand).setOnClickListener(v -> {
            if (ensureConnected()) controller.land();
        });

        // Flechas tipo joystick
        setupJoystick(R.id.btnForward, Axis.FB, +1);
        setupJoystick(R.id.btnBack,    Axis.FB, -1);
        setupJoystick(R.id.btnLeft,    Axis.LR, -1);
        setupJoystick(R.id.btnRight,   Axis.LR, +1);
        setupJoystick(R.id.btnUp,      Axis.UD, +1);
        setupJoystick(R.id.btnDown,    Axis.UD, -1);
        setupJoystick(R.id.btnYawLeft, Axis.YAW, -1);
        setupJoystick(R.id.btnYawRight,Axis.YAW, +1);

        setupModeButtons();

        // Inicializar Python/OpenCV en segundo plano al arrancar
        txtStatus.setText("Cargando OpenCV...");
        new Thread(() -> {
            vision = new VisionProcessor(this, this);
            runOnUiThread(() -> txtStatus.setText(
                    vision.isReady() ? "Desconectado (OpenCV listo)" : "Desconectado"));
        }, "python-init").start();
    }

    // ---------- Control manual ----------

    private enum Axis { LR, FB, UD, YAW }

    @SuppressLint("ClickableViewAccessibility")
    private void setupJoystick(int viewId, Axis axis, int sign) {
        findViewById(viewId).setOnTouchListener((v, event) -> {
            if (controller == null || !controller.isConnected()) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                apply(axis, sign * speed);
                v.setPressed(true);
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                apply(axis, 0);
                v.setPressed(false);
                return true;
            }
            return false;
        });
    }

    private void apply(Axis axis, int value) {
        switch (axis) {
            case LR:  controller.setLeftRight(value); break;
            case FB:  controller.setForwardBack(value); break;
            case UD:  controller.setUpDown(value); break;
            case YAW: controller.setYaw(value); break;
        }
    }

    // ---------- Botones de modo (visión) ----------

    private void setupModeButtons() {
        btnVision.setOnClickListener(v -> {
            if (vision == null || !vision.isReady()) {
                Toast.makeText(this, "OpenCV aún no está listo", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean on = !btnVision.isSelected();
            btnVision.setSelected(on);
            vision.setVisionEnabled(on);
            if (!on) {
                btnFollow.setSelected(false); vision.setFollow(false);
                btnGesture.setSelected(false); vision.setGesture(false);
                overlay.clear();
            }
            Toast.makeText(this, on ? "Visión activada" : "Visión desactivada",
                    Toast.LENGTH_SHORT).show();
        });

        btnFollow.setOnClickListener(v -> {
            if (!requireVision()) return;
            boolean on = !btnFollow.isSelected();
            btnFollow.setSelected(on);
            vision.setFollow(on);
            if (on) { btnGesture.setSelected(false); vision.setGesture(false); }
        });

        btnGesture.setOnClickListener(v -> {
            if (!requireVision()) return;
            boolean on = !btnGesture.isSelected();
            btnGesture.setSelected(on);
            vision.setGesture(on);
            if (on) { btnFollow.setSelected(false); vision.setFollow(false); }
        });

        btnEnroll.setOnClickListener(v -> {
            if (!requireVision()) return;
            promptEnroll();
        });
    }

    private boolean requireVision() {
        if (vision == null || !vision.isReady()) {
            Toast.makeText(this, "OpenCV no está listo", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!btnVision.isSelected()) {
            Toast.makeText(this, "Activa primero VISIÓN", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
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

    // ---------- Conexión ----------

    private void connectDrone() {
        Network wifi = getWifiNetwork();
        if (wifi == null) {
            Toast.makeText(this,
                    "Conéctate primero al WiFi del Tello (TELLO-XXXXXX)",
                    Toast.LENGTH_LONG).show();
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
            if (surfaceReady) videoDecoder.start();
        }, "connect-thread").start();
    }

    private Network getWifiNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return n;
            }
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

    // ---------- Callbacks del controlador ----------

    @Override public void onStatus(String msg) { txtStatus.setText(msg); }

    @Override public void onBattery(int pct) {
        String icon = pct <= 15 ? "🪫" : "🔋";
        txtBattery.setText(icon + " " + pct + "%");
    }

    @Override public void onConnected(boolean ok) {
        if (ok) {
            btnConnect.setText("CONECTADO");
            if (surfaceReady && videoDecoder != null) videoDecoder.start();
        } else {
            btnConnect.setText("CONECTAR");
        }
    }

    // ---------- Callbacks de visión ----------

    @Override public void onDetections(List<OverlayView.Face> faces, String gesture, int sw, int sh) {
        overlay.update(faces, gesture, sw, sh);
    }

    @Override public void onVisionStatus(String msg) {
        txtStatus.setText(msg);
    }

    // ---------- Surface del vídeo ----------

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (controller != null && controller.isConnected() && videoDecoder != null) {
            videoDecoder.start();
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        if (videoDecoder != null) videoDecoder.stop();
    }

    // ---------- Ciclo de vida ----------

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (videoDecoder != null) videoDecoder.stop();
        if (controller != null) controller.disconnect();
    }
}
