package com.fran.tello;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Control por voz en español mediante SpeechRecognizer. Escucha de forma continua
 * (se reinicia solo tras cada frase) y traduce palabras clave a acciones.
 */
public class VoiceController {

    public interface Listener {
        void onCommand(String action);   // takeoff/land/photo/record/emergency/flipL/flipR/up/down/left/right/forward/back/yawL/yawR/stop
        void onVoiceStatus(String heard);
    }

    private final Context ctx;
    private final Listener listener;
    private SpeechRecognizer recognizer;
    private boolean active = false;

    public VoiceController(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public boolean isActive() { return active; }

    public void start() {
        if (active) return;
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            listener.onVoiceStatus("Reconocimiento de voz no disponible");
            return;
        }
        active = true;
        recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
        recognizer.setRecognitionListener(new Handler());
        listen();
    }

    private void listen() {
        if (!active || recognizer == null) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        try { recognizer.startListening(i); } catch (Exception ignore) { }
    }

    public void stop() {
        active = false;
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Exception ignore) { }
            try { recognizer.destroy(); } catch (Exception ignore) { }
            recognizer = null;
        }
    }

    private void handle(ArrayList<String> texts) {
        if (texts == null) return;
        for (String t : texts) {
            String s = t.toLowerCase(Locale.ROOT);
            String action = match(s);
            if (action != null) {
                listener.onVoiceStatus("🎤 " + s);
                listener.onCommand(action);
                return;
            }
        }
        if (!texts.isEmpty()) listener.onVoiceStatus("🎤 " + texts.get(0));
    }

    private String match(String s) {
        if (has(s, "despega", "despegar", "arranca", "vuela")) return "takeoff";
        if (has(s, "aterriza", "aterrizar", "baja del todo", "al suelo")) return "land";
        if (has(s, "para", "emergencia", "stop", "parada")) return "emergency";
        if (has(s, "foto", "captura", "fotografía", "fotografia")) return "photo";
        if (has(s, "graba", "grabar", "vídeo", "video")) return "record";
        if (has(s, "voltereta", "flip", "giro completo")) return "flipF";
        if (has(s, "sube", "subir", "arriba")) return "up";
        if (has(s, "baja", "bajar", "abajo")) return "down";
        if (has(s, "adelante", "avanza", "delante")) return "forward";
        if (has(s, "atrás", "atras", "retrocede")) return "back";
        if (has(s, "gira izquierda", "rota izquierda")) return "yawL";
        if (has(s, "gira derecha", "rota derecha")) return "yawR";
        if (has(s, "izquierda")) return "left";
        if (has(s, "derecha")) return "right";
        if (has(s, "quieto", "hover", "flota")) return "stop";
        return null;
    }

    private static boolean has(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    /** Listener interno del reconocedor: reinicia la escucha en bucle. */
    private class Handler implements RecognitionListener {
        @Override public void onResults(Bundle b) {
            handle(b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
            listen();   // seguir escuchando
        }
        @Override public void onPartialResults(Bundle b) {
            ArrayList<String> p = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (p != null && !p.isEmpty()) {
                String action = match(p.get(0).toLowerCase(Locale.ROOT));
                if (action != null) { listener.onCommand(action); }
            }
        }
        @Override public void onError(int error) { if (active) listen(); }
        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rms) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }
    }
}
