package com.fran.tello;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Network;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;

/**
 * Recibe el stream H.264 del Tello (UDP 11111), lo decodifica con MediaCodec en
 * modo ByteBuffer (para poder acceder a los píxeles), lo pinta en la SurfaceView
 * y entrega una copia reducida en BGR a la capa de visión (OpenCV).
 */
public class VideoDecoder {

    private static final String TAG = "VideoDecoder";
    private static final int VIDEO_PORT = 11111;
    private static final int MAX_UDP = 1460;
    private static final String MIME = "video/avc";

    // Tamaño de la imagen que se manda a OpenCV (reducida = más rápida)
    private static final int VISION_W = 480;
    private static final int VISION_H = 360;
    private static final long VISION_INTERVAL_MS = 100;   // ~10 fps de análisis

    public interface FrameListener {
        /** Fotograma reducido en BGR (w*h*3) para procesar con OpenCV. */
        void onFrame(byte[] bgr, int w, int h);
    }

    private final SurfaceView surfaceView;
    private final Network network;
    private FrameListener frameListener;

    private MediaCodec codec;
    private DatagramSocket socket;
    private Thread thread;
    private volatile boolean running = false;
    private long frameIndex = 0;
    private long lastVisionMs = 0;

    private int[] argb;      // buffer de píxeles reutilizable
    private Bitmap bitmap;

    public VideoDecoder(SurfaceView surfaceView, Network network) {
        this.surfaceView = surfaceView;
        this.network = network;
    }

    public void setFrameListener(FrameListener l) { this.frameListener = l; }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "video-decoder");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME, 960, 720);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);
            // Salida en YUV flexible para poder leer los píxeles
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            codec = MediaCodec.createDecoderByType(MIME);
            codec.configure(format, null, null, 0);   // sin Surface: modo ByteBuffer
            codec.start();

            socket = new DatagramSocket(VIDEO_PORT);
            if (network != null) network.bindSocket(socket);
            socket.setSoTimeout(1000);

            byte[] recvBuf = new byte[2048];
            byte[] frame = new byte[1024 * 1024];
            int frameLen = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (running) {
                DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                try {
                    socket.receive(packet);
                } catch (Exception timeout) {
                    continue;
                }

                int len = packet.getLength();
                if (frameLen + len <= frame.length) {
                    System.arraycopy(packet.getData(), 0, frame, frameLen, len);
                    frameLen += len;
                }
                if (len < MAX_UDP) {
                    decodeFrame(frame, frameLen, info);
                    frameLen = 0;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "error en el decodificador", e);
        } finally {
            releaseInternal();
        }
    }

    private void decodeFrame(byte[] data, int len, MediaCodec.BufferInfo info) {
        if (len <= 0) return;
        try {
            int inIdx = codec.dequeueInputBuffer(10_000);
            if (inIdx >= 0) {
                ByteBuffer ib = codec.getInputBuffer(inIdx);
                if (ib != null) {
                    ib.clear();
                    ib.put(data, 0, len);
                    long pts = frameIndex++ * 33_000L;
                    codec.queueInputBuffer(inIdx, 0, len, pts, 0);
                }
            }

            int outIdx = codec.dequeueOutputBuffer(info, 0);
            while (outIdx >= 0) {
                Image image = null;
                try {
                    image = codec.getOutputImage(outIdx);
                    if (image != null) renderAndDispatch(image);
                } catch (Exception ex) {
                    Log.w(TAG, "getOutputImage: " + ex.getMessage());
                } finally {
                    if (image != null) image.close();
                    codec.releaseOutputBuffer(outIdx, false);
                }
                outIdx = codec.dequeueOutputBuffer(info, 0);
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "decode: " + e.getMessage());
        }
    }

    /** Convierte el Image YUV -> ARGB, lo pinta en pantalla y manda copia a visión. */
    private void renderAndDispatch(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (argb == null || argb.length != w * h) {
            argb = new int[w * h];
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        yuvToArgb(image, argb, w, h);
        bitmap.setPixels(argb, 0, w, 0, 0, w, h);

        // Pintar en la SurfaceView (escalado a pantalla)
        SurfaceHolder holder = surfaceView.getHolder();
        Canvas c = null;
        try {
            c = holder.lockCanvas();
            if (c != null) {
                Rect dst = new Rect(0, 0, c.getWidth(), c.getHeight());
                c.drawBitmap(bitmap, null, dst, null);
            }
        } catch (Exception e) {
            // superficie no lista
        } finally {
            if (c != null) try { holder.unlockCanvasAndPost(c); } catch (Exception ignore) { }
        }

        // Enviar fotograma reducido a OpenCV (throttled)
        long now = System.currentTimeMillis();
        if (frameListener != null && now - lastVisionMs >= VISION_INTERVAL_MS) {
            lastVisionMs = now;
            byte[] bgr = downscaleToBgr(argb, w, h, VISION_W, VISION_H);
            try { frameListener.onFrame(bgr, VISION_W, VISION_H); } catch (Exception ignore) { }
        }
    }

    /** Conversión YUV_420_888 -> ARGB (BT.601). */
    private static void yuvToArgb(Image image, int[] out, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int uvRow = planes[1].getRowStride();
        int uvPix = planes[1].getPixelStride();
        int uCap = uBuf.limit();
        int vCap = vBuf.limit();

        for (int y = 0; y < h; y++) {
            int yBase = y * yRow;
            int uvBase = (y >> 1) * uvRow;
            for (int x = 0; x < w; x++) {
                int Y = yBuf.get(yBase + x) & 0xff;
                int uvIndex = uvBase + (x >> 1) * uvPix;
                int U = 128, V = 128;
                if (uvIndex < uCap) U = uBuf.get(uvIndex) & 0xff;
                if (uvIndex < vCap) V = vBuf.get(uvIndex) & 0xff;

                int d = U - 128;
                int e = V - 128;
                int r = Y + ((91881 * e) >> 16);
                int g = Y - ((22554 * d + 46802 * e) >> 16);
                int b = Y + ((116130 * d) >> 16);
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;

                out[y * w + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    /** Reduce el ARGB a un buffer BGR pequeño (orden que espera OpenCV). */
    private static byte[] downscaleToBgr(int[] argb, int sw, int sh, int dw, int dh) {
        byte[] out = new byte[dw * dh * 3];
        int i = 0;
        for (int y = 0; y < dh; y++) {
            int sy = y * sh / dh;
            int rowBase = sy * sw;
            for (int x = 0; x < dw; x++) {
                int sx = x * sw / dw;
                int p = argb[rowBase + sx];
                out[i++] = (byte) (p & 0xff);          // B
                out[i++] = (byte) ((p >> 8) & 0xff);   // G
                out[i++] = (byte) ((p >> 16) & 0xff);  // R
            }
        }
        return out;
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignore) { }
        }
        releaseInternal();
    }

    private void releaseInternal() {
        try { if (socket != null) { socket.close(); socket = null; } } catch (Exception ignore) { }
        try { if (codec != null) { codec.stop(); codec.release(); codec = null; } } catch (Exception ignore) { }
    }
}
