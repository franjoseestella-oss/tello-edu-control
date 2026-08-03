package com.fran.tello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Dibuja sobre el vídeo: recuadros de caras, esqueleto del cuerpo, mano con
 * dedos, gesto detectado y códigos QR.
 */
public class OverlayView extends View {

    public static class Face {
        final int x, y, w, h;
        final String label;
        Face(int x, int y, int w, int h, String label) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.label = label;
        }
    }

    public static class Qr {
        final int[] pts;      // 8 valores: x1,y1..x4,y4
        final String text;
        Qr(int[] pts, String text) { this.pts = pts; this.text = text; }
    }

    /** Todo lo que hay que pintar de un fotograma. */
    public static class Result {
        List<Face> faces;
        String gesture = "";
        Qr qr;
        float[] body;        // 33*3 (x, y, visibilidad) o null
        float[] hand;        // 21*2 (x, y) o null
        int fingers = -1;
        String handLabel = "";
        int srcW = 480, srcH = 360;
    }

    private final List<Face> faces = new ArrayList<>();
    private Qr qr;
    private String gesture = "";
    private float[] body, hand;
    private int fingers = -1;
    private String handLabel = "";
    private int srcW = 480, srcH = 360;

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gesturePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint qrPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint qrText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint jointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handBonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handJointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fingerText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public OverlayView(Context c, AttributeSet a) {
        super(c, a);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4f);
        boxPaint.setColor(Color.parseColor("#00E676"));

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(34f);
        textPaint.setFakeBoldText(true);
        textBg.setColor(Color.parseColor("#AA00A651"));

        gesturePaint.setColor(Color.parseColor("#FFEB3B"));
        gesturePaint.setTextSize(56f);
        gesturePaint.setFakeBoldText(true);
        gesturePaint.setShadowLayer(6f, 0, 0, Color.BLACK);

        qrPaint.setStyle(Paint.Style.STROKE);
        qrPaint.setStrokeWidth(5f);
        qrPaint.setColor(Color.parseColor("#FF00B0FF"));
        qrText.setColor(Color.parseColor("#FF00B0FF"));
        qrText.setTextSize(38f);
        qrText.setFakeBoldText(true);
        qrText.setShadowLayer(5f, 0, 0, Color.BLACK);

        bonePaint.setStyle(Paint.Style.STROKE);
        bonePaint.setStrokeWidth(7f);
        bonePaint.setStrokeCap(Paint.Cap.ROUND);
        bonePaint.setColor(Color.parseColor("#00FFC8"));
        bonePaint.setShadowLayer(6f, 0, 0, Color.BLACK);
        jointPaint.setColor(Color.parseColor("#FFDC3C"));

        handBonePaint.setStyle(Paint.Style.STROKE);
        handBonePaint.setStrokeWidth(5f);
        handBonePaint.setStrokeCap(Paint.Cap.ROUND);
        handBonePaint.setColor(Color.parseColor("#FF8CDC"));
        handBonePaint.setShadowLayer(5f, 0, 0, Color.BLACK);
        handJointPaint.setColor(Color.WHITE);

        fingerText.setColor(Color.parseColor("#FF8CDC"));
        fingerText.setTextSize(40f);
        fingerText.setFakeBoldText(true);
        fingerText.setShadowLayer(5f, 0, 0, Color.BLACK);

        // Las sombras necesitan capa por software
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void update(Result r) {
        faces.clear();
        if (r.faces != null) faces.addAll(r.faces);
        this.gesture = r.gesture == null ? "" : r.gesture;
        this.qr = r.qr;
        this.body = r.body;
        this.hand = r.hand;
        this.fingers = r.fingers;
        this.handLabel = r.handLabel == null ? "" : r.handLabel;
        this.srcW = r.srcW;
        this.srcH = r.srcH;
        postInvalidate();
    }

    public void clear() {
        faces.clear();
        gesture = "";
        qr = null;
        body = null;
        hand = null;
        fingers = -1;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float sx = (float) getWidth() / srcW;
        float sy = (float) getHeight() / srcH;

        for (Face f : faces) {
            RectF r = new RectF(f.x * sx, f.y * sy, (f.x + f.w) * sx, (f.y + f.h) * sy);
            canvas.drawRect(r, boxPaint);
            if (f.label != null && !f.label.isEmpty()) {
                float tw = textPaint.measureText(f.label);
                canvas.drawRect(r.left, r.top - 44, r.left + tw + 16, r.top, textBg);
                canvas.drawText(f.label, r.left + 8, r.top - 12, textPaint);
            }
        }

        drawBody(canvas, sx, sy);
        drawHand(canvas, sx, sy);

        if (qr != null && qr.pts != null && qr.pts.length >= 8) {
            Path p = new Path();
            p.moveTo(qr.pts[0] * sx, qr.pts[1] * sy);
            for (int i = 1; i < 4; i++) p.lineTo(qr.pts[i * 2] * sx, qr.pts[i * 2 + 1] * sy);
            p.close();
            canvas.drawPath(p, qrPaint);
            if (qr.text != null && !qr.text.isEmpty()) {
                canvas.drawText(qr.text, qr.pts[0] * sx, qr.pts[1] * sy - 12, qrText);
            }
        }

        if (!gesture.isEmpty()) {
            canvas.drawText(gesture, 40, getHeight() - 60, gesturePaint);
        }
    }

    private void drawBody(Canvas canvas, float sx, float sy) {
        float[] b = body;
        if (b == null || b.length < 33 * 3) return;

        for (int[] bone : SkeletonProcessor.BONES) {
            int a = bone[0], z = bone[1];
            if (b[a * 3 + 2] < 0.3f || b[z * 3 + 2] < 0.3f) continue;
            canvas.drawLine(b[a * 3] * sx, b[a * 3 + 1] * sy,
                            b[z * 3] * sx, b[z * 3 + 1] * sy, bonePaint);
        }
        for (int i = 0; i < 33; i++) {
            if (b[i * 3 + 2] < 0.3f) continue;
            boolean big = i == 0 || i == 11 || i == 12 || i == 15
                    || i == 16 || i == 23 || i == 24;
            canvas.drawCircle(b[i * 3] * sx, b[i * 3 + 1] * sy, big ? 10f : 6f, jointPaint);
        }
    }

    private void drawHand(Canvas canvas, float sx, float sy) {
        float[] p = hand;
        if (p == null || p.length < 21 * 2) return;

        for (int[] bone : SkeletonProcessor.HAND_BONES) {
            canvas.drawLine(p[bone[0] * 2] * sx, p[bone[0] * 2 + 1] * sy,
                            p[bone[1] * 2] * sx, p[bone[1] * 2 + 1] * sy, handBonePaint);
        }
        for (int i = 0; i < 21; i++) {
            canvas.drawCircle(p[i * 2] * sx, p[i * 2 + 1] * sy, 6f, handJointPaint);
        }
        if (fingers >= 0) {
            String txt = fingers + (fingers == 1 ? " dedo" : " dedos");
            if (!handLabel.isEmpty()) txt += " (" + handLabel + ")";
            canvas.drawText(txt, p[0] * sx - 60, p[1] * sy + 54, fingerText);
        }
    }
}
