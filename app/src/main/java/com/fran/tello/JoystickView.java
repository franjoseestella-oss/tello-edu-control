package com.fran.tello;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Joystick analógico en pantalla. Devuelve dos ejes normalizados en [-1, 1].
 * Al soltar, vuelve al centro automáticamente (auto-return).
 */
public class JoystickView extends View {

    public interface Listener {
        /** x: derecha(+)/izquierda(-), y: arriba(+)/abajo(-), en [-1,1]. */
        void onMove(float x, float y);
    }

    private Listener listener;
    private float centerX, centerY, baseRadius, hatRadius;
    private float hatX, hatY;
    private boolean active = false;
    private boolean selfCenter = true;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public JoystickView(Context c, AttributeSet a) {
        super(c, a);
        basePaint.setColor(Color.parseColor("#33000000"));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(4f);
        ringPaint.setColor(Color.parseColor("#66FFFFFF"));
        hatPaint.setColor(Color.parseColor("#EE1E88E5"));
        crossPaint.setColor(Color.parseColor("#33FFFFFF"));
        crossPaint.setStrokeWidth(2f);
    }

    public void setListener(Listener l) { this.listener = l; }

    /** Si false, el hat se queda donde lo dejes (útil para acelerador). */
    public void setSelfCenter(boolean v) { this.selfCenter = v; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        centerX = w / 2f;
        centerY = h / 2f;
        baseRadius = Math.min(w, h) / 2f * 0.92f;
        hatRadius = baseRadius * 0.42f;
        hatX = centerX;
        hatY = centerY;
        hatPaint.setShader(new RadialGradient(0, 0, hatRadius,
                new int[]{Color.parseColor("#FF64B5F6"), Color.parseColor("#FF0D47A1")},
                null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Base
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);
        canvas.drawCircle(centerX, centerY, baseRadius, ringPaint);
        // Cruz guía
        canvas.drawLine(centerX - baseRadius, centerY, centerX + baseRadius, centerY, crossPaint);
        canvas.drawLine(centerX, centerY - baseRadius, centerX, centerY + baseRadius, crossPaint);
        // Hat
        canvas.save();
        canvas.translate(hatX, hatY);
        canvas.drawCircle(0, 0, hatRadius, hatPaint);
        canvas.restore();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                active = true;
                float dx = e.getX() - centerX;
                float dy = e.getY() - centerY;
                float dist = (float) Math.hypot(dx, dy);
                float max = baseRadius - hatRadius;
                if (dist > max) {
                    dx = dx / dist * max;
                    dy = dy / dist * max;
                }
                hatX = centerX + dx;
                hatY = centerY + dy;
                emit(dx / max, -dy / max);   // y invertido: arriba = +
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                active = false;
                if (selfCenter) {
                    hatX = centerX;
                    hatY = centerY;
                    emit(0, 0);
                }
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void emit(float x, float y) {
        if (listener != null) listener.onMove(clamp(x), clamp(y));
    }

    private static float clamp(float v) { return Math.max(-1f, Math.min(1f, v)); }
}
