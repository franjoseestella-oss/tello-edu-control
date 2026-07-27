package com.fran.tello;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Dibuja encima del vídeo los recuadros de las caras y el gesto detectado. */
public class OverlayView extends View {

    public static class Face {
        final int x, y, w, h;
        final String label;
        Face(int x, int y, int w, int h, String label) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.label = label;
        }
    }

    private final List<Face> faces = new ArrayList<>();
    private String gesture = "";
    private int srcW = 480, srcH = 360;

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gesturePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
    }

    /** Actualiza detecciones (coordenadas en el sistema de la imagen reducida). */
    public void update(List<Face> newFaces, String gesture, int srcW, int srcH) {
        faces.clear();
        if (newFaces != null) faces.addAll(newFaces);
        this.gesture = gesture == null ? "" : gesture;
        this.srcW = srcW;
        this.srcH = srcH;
        postInvalidate();
    }

    public void clear() {
        faces.clear();
        gesture = "";
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

        if (!gesture.isEmpty()) {
            canvas.drawText("✋ " + gesture, 40, getHeight() - 60, gesturePaint);
        }
    }
}
