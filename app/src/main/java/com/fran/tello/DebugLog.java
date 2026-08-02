package com.fran.tello;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Registro de depuración del enlace con el dron.
 *
 * Guarda TODO lo que pasa en el protocolo UDP (comando enviado, respuesta,
 * latencia, errores de socket, telemetría, acciones del usuario) con marca de
 * tiempo en milisegundos y nombre de hilo, en memoria y en un fichero de texto
 * que se puede ver y compartir desde la propia app (botón 🐞).
 *
 * Fichero:  Android/data/com.fran.tello/files/logs/debug_AAAAMMDD_HHMMSS.txt
 */
public final class DebugLog {

    private static final String TAG = "TelloDebug";
    private static final int MAX_LINES = 4000;

    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final Object lock = new Object();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static FileWriter writer;
    private static String path;
    private static long t0 = System.currentTimeMillis();

    private DebugLog() { }

    /** Abre el fichero de log. Se llama una vez al arrancar la app. */
    public static void init(Context ctx) {
        synchronized (lock) {
            if (writer != null) return;
            try {
                File dir = new File(ctx.getExternalFilesDir(null), "logs");
                if (!dir.exists()) dir.mkdirs();
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File f = new File(dir, "debug_" + stamp + ".txt");
                path = f.getAbsolutePath();
                writer = new FileWriter(f, true);
                t0 = System.currentTimeMillis();
            } catch (Exception e) {
                Log.w(TAG, "no se pudo abrir el fichero de log: " + e);
            }
        }
        d("INIT", "=== Tello debug log ===");
        d("INIT", "modelo=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " android=" + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        d("INIT", "fichero=" + path);
    }

    /** kind: TX, RX, ERR, NET, UI, RC, STATE, STEP, WARN... */
    public static void d(String kind, String msg) {
        long now = System.currentTimeMillis();
        String line = String.format(Locale.US, "[%s] +%7.3fs %-6s [%s] %s",
                TS.format(new Date(now)), (now - t0) / 1000f, kind,
                Thread.currentThread().getName(), msg);
        Log.d(TAG, line);
        synchronized (lock) {
            lines.addLast(line);
            while (lines.size() > MAX_LINES) lines.removeFirst();
            if (writer != null) {
                try {
                    writer.write(line);
                    writer.write('\n');
                    writer.flush();
                } catch (Exception ignore) { }
            }
        }
    }

    public static String getPath() { return path; }

    /** Texto completo en memoria (para verlo o copiarlo dentro de la app). */
    public static String text() {
        StringBuilder sb = new StringBuilder();
        synchronized (lock) {
            for (String l : lines) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /** Últimas n líneas. */
    public static String tail(int n) {
        StringBuilder sb = new StringBuilder();
        synchronized (lock) {
            int skip = Math.max(0, lines.size() - n);
            int i = 0;
            for (String l : lines) {
                if (i++ < skip) continue;
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    /** Intent para enviar el fichero por WhatsApp / correo / Drive. */
    public static Intent shareIntent(Context ctx) {
        Intent it = new Intent(Intent.ACTION_SEND);
        it.setType("text/plain");
        it.putExtra(Intent.EXTRA_SUBJECT, "Log de depuración Tello");
        try {
            File f = new File(path);
            if (f.exists()) {
                android.net.Uri uri = FileProvider.getUriForFile(
                        ctx, ctx.getPackageName() + ".fileprovider", f);
                it.putExtra(Intent.EXTRA_STREAM, uri);
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (Exception e) {
            Log.w(TAG, "share: " + e);
        }
        // Además el texto, por si el destino no acepta adjuntos.
        it.putExtra(Intent.EXTRA_TEXT, tail(400));
        return it;
    }
}
