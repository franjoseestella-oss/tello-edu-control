package com.fran.tello;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Caja negra: registra la telemetría del vuelo en un CSV (carpeta de la app). */
public class FlightLogger {

    private FileWriter writer;
    private boolean logging = false;
    private long startMs;
    private String path;

    public boolean isLogging() { return logging; }
    public String getPath() { return path; }

    public synchronized void start(Context ctx) {
        if (logging) return;
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dir = new File(ctx.getExternalFilesDir(null), "logs");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "vuelo_" + ts + ".csv");
            path = f.getAbsolutePath();
            writer = new FileWriter(f);
            writer.write("t_ms,bateria,altura_cm,tof_cm,baro,tiempo_s,templ,temph,"
                    + "pitch,roll,yaw,vgx,vgy,vgz,mission_pad\n");
            startMs = System.currentTimeMillis();
            logging = true;
        } catch (Exception e) {
            logging = false;
        }
    }

    public synchronized void log(TelloController.Telemetry t) {
        if (!logging || writer == null) return;
        try {
            long dt = System.currentTimeMillis() - startMs;
            writer.write(dt + "," + t.battery + "," + t.height + "," + t.tof + "," + t.baro + ","
                    + t.flightTime + "," + t.templ + "," + t.temph + "," + t.pitch + "," + t.roll + ","
                    + t.yaw + "," + t.vgx + "," + t.vgy + "," + t.vgz + "," + t.missionPad + "\n");
        } catch (Exception ignore) { }
    }

    public synchronized String stop() {
        logging = false;
        try { if (writer != null) { writer.flush(); writer.close(); } } catch (Exception ignore) { }
        writer = null;
        return path;
    }
}
