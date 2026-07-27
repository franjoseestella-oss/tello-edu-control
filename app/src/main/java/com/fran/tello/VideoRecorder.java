package com.fran.tello;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Graba el vídeo del dron a un fichero MP4. Recibe fotogramas ARGB (los mismos
 * que se pintan en pantalla), los convierte a YUV y los codifica con H.264.
 *
 * Es "best-effort": si el códec del dispositivo no lo soporta, se desactiva sin
 * romper la app.
 */
public class VideoRecorder {

    private static final String TAG = "VideoRecorder";
    private static final String MIME = "video/avc";

    public interface Listener {
        void onRecordingStopped(String path, boolean ok);
    }

    private final Listener listener;
    private MediaCodec encoder;
    private MediaMuxer muxer;
    private int trackIndex = -1;
    private boolean muxerStarted = false;
    private volatile boolean recording = false;

    private int colorFormat;
    private int width, height;
    private long startNs = 0;
    private byte[] yuv;
    private String outputPath;

    private final MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();

    public VideoRecorder(Listener listener) { this.listener = listener; }

    public boolean isRecording() { return recording; }

    public synchronized boolean start(String path, int w, int h) {
        if (recording) return false;
        this.outputPath = path;
        this.width = w;
        this.height = h;
        try {
            MediaCodecInfo info = selectEncoder();
            colorFormat = selectColorFormat(info);

            MediaFormat format = MediaFormat.createVideoFormat(MIME, w, h);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createByCodecName(info.getName());
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            trackIndex = -1;
            muxerStarted = false;
            yuv = new byte[w * h * 3 / 2];
            startNs = System.nanoTime();
            recording = true;
            Log.i(TAG, "grabando en " + path);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "no se pudo iniciar la grabación", t);
            releaseInternal();
            return false;
        }
    }

    public synchronized void encodeFrame(int[] argb, int w, int h) {
        if (!recording || encoder == null || w != width || h != height) return;
        try {
            argbToYuv(argb, w, h, yuv, colorFormat);
            int inIdx = encoder.dequeueInputBuffer(5_000);
            if (inIdx >= 0) {
                ByteBuffer ib = encoder.getInputBuffer(inIdx);
                if (ib != null) {
                    ib.clear();
                    ib.put(yuv);
                    long pts = (System.nanoTime() - startNs) / 1000;
                    encoder.queueInputBuffer(inIdx, 0, yuv.length, pts, 0);
                }
            }
            drain(false);
        } catch (Throwable t) {
            Log.w(TAG, "encodeFrame: " + t.getMessage());
        }
    }

    public synchronized void stop() {
        if (!recording) return;
        recording = false;
        boolean ok = false;
        try {
            int inIdx = encoder.dequeueInputBuffer(10_000);
            if (inIdx >= 0) {
                long pts = (System.nanoTime() - startNs) / 1000;
                encoder.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            drain(true);
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "stop: " + t.getMessage());
        } finally {
            releaseInternal();
        }
        final boolean fok = ok;
        if (listener != null) listener.onRecordingStopped(outputPath, fok);
    }

    private void drain(boolean endOfStream) {
        while (true) {
            int outIdx = encoder.dequeueOutputBuffer(bufInfo, endOfStream ? 10_000 : 0);
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return;
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) throw new IllegalStateException("formato cambiado dos veces");
                trackIndex = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                muxerStarted = true;
            } else if (outIdx >= 0) {
                ByteBuffer out = encoder.getOutputBuffer(outIdx);
                if ((bufInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufInfo.size = 0;
                }
                if (bufInfo.size > 0 && muxerStarted && out != null) {
                    out.position(bufInfo.offset);
                    out.limit(bufInfo.offset + bufInfo.size);
                    muxer.writeSampleData(trackIndex, out, bufInfo);
                }
                encoder.releaseOutputBuffer(outIdx, false);
                if ((bufInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    // ---------- Selección de códec / conversión ----------

    private static MediaCodecInfo selectEncoder() {
        int n = android.media.MediaCodecList.getCodecCount();
        for (int i = 0; i < n; i++) {
            MediaCodecInfo info = android.media.MediaCodecList.getCodecInfoAt(i);
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(MIME)) return info;
            }
        }
        throw new RuntimeException("sin codificador H.264");
    }

    private static int selectColorFormat(MediaCodecInfo info) {
        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MIME);
        for (int c : caps.colorFormats) {
            if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return c;
        }
        for (int c : caps.colorFormats) {
            if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return c;
        }
        for (int c : caps.colorFormats) {
            if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) return c;
        }
        return caps.colorFormats.length > 0 ? caps.colorFormats[0]
                : MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
    }

    /** ARGB -> YUV420 (planar I420 o semiplanar NV12 según el códec). */
    private static void argbToYuv(int[] argb, int w, int h, byte[] out, int colorFormat) {
        boolean semiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
        int frameSize = w * h;
        int uIndex = frameSize;
        int vIndex = semiPlanar ? frameSize + 1 : frameSize + frameSize / 4;
        int yIndex = 0;

        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int p = argb[j * w + i];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;

                int y = (66 * r + 129 * g + 25 * b + 128) >> 8;
                out[yIndex++] = (byte) clampByte(y + 16);

                if ((j & 1) == 0 && (i & 1) == 0) {
                    int u = (-38 * r - 74 * g + 112 * b + 128) >> 8;
                    int v = (112 * r - 94 * g - 18 * b + 128) >> 8;
                    if (semiPlanar) {
                        out[uIndex++] = (byte) clampByte(u + 128);
                        out[uIndex++] = (byte) clampByte(v + 128);
                    } else {
                        out[uIndex++] = (byte) clampByte(u + 128);
                        out[vIndex++] = (byte) clampByte(v + 128);
                    }
                }
            }
        }
    }

    private static int clampByte(int v) { return v < 0 ? 0 : Math.min(v, 255); }

    private void releaseInternal() {
        try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignore) { }
        try { if (muxer != null) { if (muxerStarted) muxer.stop(); muxer.release(); } } catch (Exception ignore) { }
        encoder = null;
        muxer = null;
        muxerStarted = false;
    }
}
