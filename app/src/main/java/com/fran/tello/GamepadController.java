package com.fran.tello;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Traduce la entrada de un mando físico (Bluetooth/USB) a control del dron.
 *
 * Mapeo (modo 2, como una emisora de RC):
 *   Stick izquierdo:  X = giro (yaw),   Y = altura (throttle)
 *   Stick derecho:    X = lateral (roll), Y = adelante/atrás (pitch)
 *   A = despegar   B = aterrizar   X = foto   Y = grabar
 *   L1 = flip izq  R1 = flip der   START = emergencia
 */
public class GamepadController {

    public interface Listener {
        void onRc(float lr, float fb, float ud, float yaw);   // ejes en [-1,1]
        void onAction(String action);                          // takeoff/land/photo/record/flipL/flipR/emergency
    }

    private final Listener listener;
    private float lr, fb, ud, yaw;

    public GamepadController(Listener l) { this.listener = l; }

    public static boolean isGamepad(int source) {
        return (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            || (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
    }

    /** Procesa un movimiento de sticks. Devuelve true si lo ha consumido. */
    public boolean onMotion(MotionEvent e) {
        if (!isGamepad(e.getSource()) || e.getAction() != MotionEvent.ACTION_MOVE) return false;

        float lx = axis(e, MotionEvent.AXIS_X);
        float ly = axis(e, MotionEvent.AXIS_Y);
        // Stick derecho: distintos mandos usan Z/RZ o RX/RY
        float rx = axis(e, MotionEvent.AXIS_Z);
        float ry = axis(e, MotionEvent.AXIS_RZ);
        if (rx == 0 && ry == 0) {
            rx = axis(e, MotionEvent.AXIS_RX);
            ry = axis(e, MotionEvent.AXIS_RY);
        }
        // Cruceta como respaldo del stick derecho
        float hatx = axis(e, MotionEvent.AXIS_HAT_X);
        float haty = axis(e, MotionEvent.AXIS_HAT_Y);
        if (rx == 0 && hatx != 0) rx = hatx;
        if (ry == 0 && haty != 0) ry = haty;

        yaw = lx;
        ud = -ly;   // arriba = +
        lr = rx;
        fb = -ry;
        if (listener != null) listener.onRc(clamp(lr), clamp(fb), clamp(ud), clamp(yaw));
        return true;
    }

    /** Procesa un botón. Devuelve true si lo ha consumido. */
    public boolean onKey(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            // Solo actuamos en la pulsación inicial
            return isMappedButton(keyCode);
        }
        String action = null;
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: action = "takeoff"; break;
            case KeyEvent.KEYCODE_BUTTON_B: action = "land"; break;
            case KeyEvent.KEYCODE_BUTTON_X: action = "photo"; break;
            case KeyEvent.KEYCODE_BUTTON_Y: action = "record"; break;
            case KeyEvent.KEYCODE_BUTTON_L1: action = "flipL"; break;
            case KeyEvent.KEYCODE_BUTTON_R1: action = "flipR"; break;
            case KeyEvent.KEYCODE_BUTTON_START: action = "emergency"; break;
            default: return false;
        }
        if (listener != null) listener.onAction(action);
        return true;
    }

    private static boolean isMappedButton(int k) {
        return k == KeyEvent.KEYCODE_BUTTON_A || k == KeyEvent.KEYCODE_BUTTON_B
            || k == KeyEvent.KEYCODE_BUTTON_X || k == KeyEvent.KEYCODE_BUTTON_Y
            || k == KeyEvent.KEYCODE_BUTTON_L1 || k == KeyEvent.KEYCODE_BUTTON_R1
            || k == KeyEvent.KEYCODE_BUTTON_START;
    }

    private static float axis(MotionEvent e, int axis) {
        InputDevice dev = e.getDevice();
        float v = e.getAxisValue(axis);
        if (dev != null) {
            InputDevice.MotionRange range = dev.getMotionRange(axis, e.getSource());
            if (range != null) {
                float flat = range.getFlat();
                if (Math.abs(v) <= flat) return 0f;   // zona muerta del mando
            } else if (Math.abs(v) < 0.12f) {
                return 0f;
            }
        } else if (Math.abs(v) < 0.12f) {
            return 0f;
        }
        return v;
    }

    private static float clamp(float v) { return Math.max(-1f, Math.min(1f, v)); }
}
