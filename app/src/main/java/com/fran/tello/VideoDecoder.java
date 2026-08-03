package com.fran.tello;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Recibe el stream H.264 del Tello (UDP 11111) y lo pinta con la mínima latencia
 * posible. Está partido en tres hilos para que nada bloquee a la red:
 *
 *   1) hilo de red      : sólo recibe paquetes UDP y arma fotogramas completos.
 *   2) hilo de pantalla : decodifica DIRECTAMENTE sobre la Surface (el chip hace
 *                         la conversión de color y el escalado: coste de CPU ~0).
 *   3) hilo de píxeles  : segundo decodificador, en modo ByteBuffer, que sólo se
 *                         enciende cuando alguien necesita los píxeles (visión
 *                         por OpenCV o grabación). Si nadie los pide, no existe.
 *
 * Las fotos se sacan con PixelCopy sobre la Surface, así que no hace falta
 * mantener un Bitmap de cada fotograma.
 */
public class VideoDecoder {

    private static final String TAG = "VideoDecoder";
    private static final int VIDEO_PORT = 11111;
    private static final int MAX_UDP = 1460;
    private static final String MIME = "video/avc";

    /** Tamaño máximo de un fotograma comprimido (los del Tello son ~30-60 KB). */
    private static final int MAX_FRAME = 512 * 1024;
    /** Fotogramas comprimidos en cola antes de empezar a tirar los viejos. */
    private static final int QUEUE_SIZE = 3;

    // Tamaño de la imagen que se manda a OpenCV (reducida = más rápida)
    private static final int VISION_W = 480;
    private static final int VISION_H = 360;
    private static final long VISION_INTERVAL_MS = 100;   // ~10 fps de análisis

    public interface FrameListener {
        /** Fotograma reducido en BGR (w*h*3) para procesar con OpenCV. */
        void onFrame(byte[] bgr, int w, int h);
        /** true si ahora mismo se van a usar los fotogramas (si no, no se gasta CPU). */
        boolean wantsFrames();
    }

    public interface SnapshotCallback {
        /** Se llama en el hilo principal; bmp es null si no se pudo capturar. */
        void onSnapshot(Bitmap bmp);
    }

    private final SurfaceView surfaceView;
    private final Network network;
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameListener frameListener;
    private VideoRecorder recorder;

    private DatagramSocket socket;
    private Thread netThread, displayThread, pixelThread;
    private volatile boolean running = false;

    private final BlockingQueue<byte[]> displayQueue = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private final BlockingQueue<byte[]> pixelQueue = new ArrayBlockingQueue<>(QUEUE_SIZE);
    /** El hilo de red sólo alimenta la cola de píxeles si esta bandera está activa. */
    private volatile boolean pixelWanted = false;

    private volatile int lastW = 0, lastH = 0;
    private long frameIndex = 0;
    private long lastVisionMs = 0;

    private int[] argb;   // sólo se reserva si se graba

    public VideoDecoder(SurfaceView surfaceView, Network network) {
        this.surfaceView = surfaceView;
        this.network = network;
    }

    public void setFrameListener(FrameListener l) { this.frameListener = l; }

    public void setRecorder(VideoRecorder r) { this.recorder = r; }

    public int getFrameWidth()  { return lastW > 0 ? lastW : 960; }
    public int getFrameHeight() { return lastH > 0 ? lastH : 720; }

    /**
     * Captura el fotograma que se está viendo. Copia el contenido de la Surface
     * (lo hace la GPU) y lo entrega a resolución nativa del vídeo.
     */
    public void requestSnapshot(SnapshotCallback cb) {
        Surface s = surfaceView.getHolder().getSurface();
        if (!running || s == null || !s.isValid() || surfaceView.getWidth() <= 0) {
            cb.onSnapshot(null);
            return;
        }
        final Bitmap bmp;
        try {
            bmp = Bitmap.createBitmap(getFrameWidth(), getFrameHeight(), Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            cb.onSnapshot(null);
            return;
        }
        try {
            PixelCopy.request(surfaceView, bmp,
                    result -> cb.onSnapshot(result == PixelCopy.SUCCESS ? bmp : null), main);
        } catch (Throwable t) {
            Log.w(TAG, "PixelCopy: " + t.getMessage());
            cb.onSnapshot(null);
        }
    }

    public void start() {
        if (running) return;
        running = true;
        frameIndex = 0;
        displayQueue.clear();
        pixelQueue.clear();

        netThread = startThread(this::runNetwork, "video-net");
        displayThread = startThread(this::runDisplay, "video-display");
        pixelThread = startThread(this::runPixels, "video-pixels");
    }

    public void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignore) { }
        join(netThread); join(displayThread); join(pixelThread);
        netThread = displayThread = pixelThread = null;
        socket = null;
        displayQueue.clear();
        pixelQueue.clear();
    }

    private Thread startThread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void join(Thread t) {
        if (t == null) return;
        try { t.join(400); } catch (InterruptedException ignore) { }
    }

    // ---------------------------------------------------------------- red

    /** Recibe UDP y arma fotogramas. No hace nada más: nunca debe atascarse. */
    private void runNetwork() {
        try {
            socket = new DatagramSocket(VIDEO_PORT);
            if (network != null) network.bindSocket(socket);
            // Buffer grande: evita perder paquetes en ráfagas
            try { socket.setReceiveBufferSize(1024 * 1024); } catch (Exception ignore) { }
            socket.setSoTimeout(1000);

            byte[] recvBuf = new byte[2048];
            byte[] frame = new byte[MAX_FRAME];
            int frameLen = 0;

            while (running) {
                DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                try {
                    socket.receive(packet);
                } catch (Exception timeout) {
                    if (!running) break;
                    continue;
                }

                int len = packet.getLength();
                if (frameLen + len <= frame.length) {
                    System.arraycopy(recvBuf, 0, frame, frameLen, len);
                    frameLen += len;
                } else {
                    frameLen = frame.length + 1;   // fotograma corrupto: se descarta
                }

                if (len < MAX_UDP) {
                    if (frameLen > 0 && frameLen <= frame.length) {
                        byte[] nal = Arrays.copyOf(frame, frameLen);
                        publish(displayQueue, nal);
                        if (pixelWanted) publish(pixelQueue, nal);
                    }
                    frameLen = 0;
                }
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "error en la recepción de vídeo", e);
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignore) { }
        }
    }

    /** Encola tirando el fotograma más viejo si el consumidor va retrasado. */
    private static void publish(BlockingQueue<byte[]> q, byte[] data) {
        while (!q.offer(data)) {
            if (q.poll() == null) return;
        }
    }

    // ------------------------------------------------------------ pantalla

    /** Decodifica sobre la Surface: la conversión de color y el escalado los hace el chip. */
    private void runDisplay() {
        MediaCodec codec = null;
        try {
            Surface surface = waitForSurface();
            if (surface == null) return;

            MediaFormat format = MediaFormat.createVideoFormat(MIME, 960, 720);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_FRAME);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }

            codec = MediaCodec.createDecoderByType(MIME);
            codec.configure(format, surface, null, 0);
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running) {
                byte[] frame = displayQueue.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null) feed(codec, frame);
                renderNewest(codec, info);
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "error en el decodificador de pantalla", e);
        } finally {
            release(codec);
        }
    }

    /**
     * Saca todo lo que el decodificador tenga listo y pinta SÓLO el último: si el
     * móvil se ha quedado atrás, se salta los fotogramas viejos en vez de
     * acumular retardo.
     */
    private void renderNewest(MediaCodec codec, MediaCodec.BufferInfo info) {
        int pending = -1;
        while (true) {
            int idx;
            try {
                idx = codec.dequeueOutputBuffer(info, 0);
            } catch (IllegalStateException e) {
                return;
            }
            if (idx >= 0) {
                if (pending >= 0) codec.releaseOutputBuffer(pending, false);   // descartado
                pending = idx;
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                readSize(codec.getOutputFormat());
            } else {
                break;
            }
        }
        if (pending >= 0) codec.releaseOutputBuffer(pending, true);
    }

    private Surface waitForSurface() {
        for (int i = 0; i < 200 && running; i++) {
            Surface s = surfaceView.getHolder().getSurface();
            if (s != null && s.isValid()) return s;
            try { Thread.sleep(50); } catch (InterruptedException e) { return null; }
        }
        return null;
    }

    private void readSize(MediaFormat f) {
        try {
            int w = f.getInteger(MediaFormat.KEY_WIDTH);
            int h = f.getInteger(MediaFormat.KEY_HEIGHT);
            if (f.containsKey("crop-left") && f.containsKey("crop-right")) {
                w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
            }
            if (f.containsKey("crop-top") && f.containsKey("crop-bottom")) {
                h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
            }
            if (w > 0 && h > 0) { lastW = w; lastH = h; }
        } catch (Exception ignore) { }
    }

    // -------------------------------------------------------------- píxeles

    private boolean needPixels() {
        FrameListener l = frameListener;
        VideoRecorder r = recorder;
        return (l != null && l.wantsFrames()) || (r != null && r.isRecording());
    }

    /**
     * Segundo decodificador, sólo mientras haga falta (visión o grabación).
     * Convierte a píxeles únicamente el fotograma que se va a usar de verdad.
     */
    private void runPixels() {
        MediaCodec codec = null;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean waitingKeyFrame = true;
        int failures = 0;

        try {
            while (running) {
                if (!needPixels() || failures >= 3) {
                    pixelWanted = false;
                    if (codec != null) { release(codec); codec = null; }
                    pixelQueue.clear();
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                    continue;
                }

                if (codec == null) {
                    codec = createPixelCodec();
                    if (codec == null) {
                        failures++;
                        if (failures >= 3) Log.w(TAG, "sin decodificador extra: visión/grabación sin imagen");
                        try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                        continue;
                    }
                    waitingKeyFrame = true;
                    pixelQueue.clear();
                    pixelWanted = true;
                }

                byte[] frame = pixelQueue.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    // Arrancar en mitad de un GOP sólo da basura: se espera al keyframe
                    if (waitingKeyFrame && !isKeyFrame(frame)) frame = null;
                    else waitingKeyFrame = false;
                }
                if (frame != null) feed(codec, frame);
                drainPixels(codec, info);
            }
        } catch (InterruptedException ignore) {
        } catch (Exception e) {
            if (running) Log.w(TAG, "hilo de píxeles: " + e.getMessage());
        } finally {
            pixelWanted = false;
            release(codec);
        }
    }

    private MediaCodec createPixelCodec() {
        try {
            int w = getFrameWidth(), h = getFrameHeight();
            MediaFormat format = MediaFormat.createVideoFormat(MIME, w, h);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_FRAME);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            MediaCodec c = MediaCodec.createDecoderByType(MIME);
            c.configure(format, null, null, 0);   // sin Surface: modo ByteBuffer
            c.start();
            return c;
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo crear el decodificador de píxeles: " + t.getMessage());
            return null;
        }
    }

    private void drainPixels(MediaCodec codec, MediaCodec.BufferInfo info) {
        while (true) {
            int idx;
            try {
                idx = codec.dequeueOutputBuffer(info, 0);
            } catch (IllegalStateException e) {
                return;
            }
            if (idx < 0) return;

            Image image = null;
            try {
                VideoRecorder r = recorder;
                FrameListener l = frameListener;
                boolean rec = r != null && r.isRecording();
                long now = SystemClock.uptimeMillis();
                boolean visionDue = l != null && l.wantsFrames()
                        && now - lastVisionMs >= VISION_INTERVAL_MS;

                if (rec || visionDue) {
                    image = codec.getOutputImage(idx);
                }
                if (image != null) {
                    int w = image.getWidth(), h = image.getHeight();
                    if (rec) {
                        if (argb == null || argb.length != w * h) argb = new int[w * h];
                        yuvToArgb(image, argb, w, h);
                        r.encodeFrame(argb, w, h);
                    }
                    if (visionDue) {
                        lastVisionMs = now;
                        byte[] bgr = yuvToBgrScaled(image, VISION_W, VISION_H);
                        try { l.onFrame(bgr, VISION_W, VISION_H); } catch (Exception ignore) { }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "píxeles: " + e.getMessage());
            } finally {
                if (image != null) image.close();
                try { codec.releaseOutputBuffer(idx, false); } catch (Exception ignore) { }
            }
        }
    }

    /** ¿El fotograma trae SPS (0x67) o IDR (0x65)? Sólo así se puede empezar a decodificar. */
    private static boolean isKeyFrame(byte[] d) {
        for (int i = 0; i + 4 < d.length && i < 64; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                int type = d[i + 3] & 0x1f;
                return type == 7 || type == 5;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ comunes

    private void feed(MediaCodec codec, byte[] data) {
        try {
            int idx = codec.dequeueInputBuffer(15_000);
            if (idx < 0) return;   // decodificador saturado: se tira el fotograma
            ByteBuffer ib = codec.getInputBuffer(idx);
            long pts = (frameIndex++) * 33_333L;
            if (ib == null || ib.capacity() < data.length) {
                codec.queueInputBuffer(idx, 0, 0, pts, 0);
                return;
            }
            ib.clear();
            ib.put(data);
            codec.queueInputBuffer(idx, 0, data.length, pts, 0);
        } catch (IllegalStateException e) {
            Log.w(TAG, "feed: " + e.getMessage());
        }
    }

    private void release(MediaCodec codec) {
        if (codec == null) return;
        try { codec.stop(); } catch (Exception ignore) { }
        try { codec.release(); } catch (Exception ignore) { }
    }

    // --------------------------------------------------- conversión de color

    /** Conversión YUV_420_888 -> ARGB a resolución completa (sólo para grabar). */
    private static void yuvToArgb(Image image, int[] out, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yOff = yBuf.position(), uOff = uBuf.position(), vOff = vBuf.position();
        int yRow = planes[0].getRowStride();
        int uvRow = planes[1].getRowStride();
        int uvPix = planes[1].getPixelStride();
        int uLim = uBuf.limit(), vLim = vBuf.limit();

        for (int y = 0; y < h; y++) {
            int yBase = yOff + y * yRow;
            int uvBase = (y >> 1) * uvRow;
            for (int x = 0; x < w; x++) {
                int Y = yBuf.get(yBase + x) & 0xff;
                int uvIndex = uvBase + (x >> 1) * uvPix;
                int U = 128, V = 128;
                if (uOff + uvIndex < uLim) U = uBuf.get(uOff + uvIndex) & 0xff;
                if (vOff + uvIndex < vLim) V = vBuf.get(vOff + uvIndex) & 0xff;
                out[y * w + x] = yuvToArgbPixel(Y, U, V);
            }
        }
    }

    /**
     * YUV_420_888 -> BGR ya reducido al tamaño de visión. Convierte sólo los
     * píxeles que se van a usar (480x360 en vez de 960x720 + escalado aparte).
     */
    private static byte[] yuvToBgrScaled(Image image, int dw, int dh) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yOff = yBuf.position(), uOff = uBuf.position(), vOff = vBuf.position();
        int yRow = planes[0].getRowStride();
        int uvRow = planes[1].getRowStride();
        int uvPix = planes[1].getPixelStride();
        int uLim = uBuf.limit(), vLim = vBuf.limit();
        int sw = image.getWidth(), sh = image.getHeight();

        byte[] out = new byte[dw * dh * 3];
        int i = 0;
        for (int y = 0; y < dh; y++) {
            int sy = y * sh / dh;
            int yBase = yOff + sy * yRow;
            int uvBase = (sy >> 1) * uvRow;
            for (int x = 0; x < dw; x++) {
                int sx = x * sw / dw;
                int Y = yBuf.get(yBase + sx) & 0xff;
                int uvIndex = uvBase + (sx >> 1) * uvPix;
                int U = 128, V = 128;
                if (uOff + uvIndex < uLim) U = uBuf.get(uOff + uvIndex) & 0xff;
                if (vOff + uvIndex < vLim) V = vBuf.get(vOff + uvIndex) & 0xff;

                int p = yuvToArgbPixel(Y, U, V);
                out[i++] = (byte) (p & 0xff);          // B
                out[i++] = (byte) ((p >> 8) & 0xff);   // G
                out[i++] = (byte) ((p >> 16) & 0xff);  // R
            }
        }
        return out;
    }

    /** BT.601 en enteros. */
    private static int yuvToArgbPixel(int Y, int U, int V) {
        int d = U - 128;
        int e = V - 128;
        int r = Y + ((91881 * e) >> 16);
        int g = Y - ((22554 * d + 46802 * e) >> 16);
        int b = Y + ((116130 * d) >> 16);
        if (r < 0) r = 0; else if (r > 255) r = 255;
        if (g < 0) g = 0; else if (g > 255) g = 255;
        if (b < 0) b = 0; else if (b > 255) b = 255;
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
}
