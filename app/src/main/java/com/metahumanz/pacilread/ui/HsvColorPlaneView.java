package com.metahumanz.pacilread.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class HsvColorPlaneView extends View {
    public interface OnColorChangeListener {
        void onColorChanged(int color);
    }

    private final Paint planePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float hue = 210f;
    private float saturation = 0.55f;
    private float value = 0.65f;
    private OnColorChangeListener listener;

    public HsvColorPlaneView(Context context) {
        super(context);
        init();
    }

    public HsvColorPlaneView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public HsvColorPlaneView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setStrokeWidth(dp(2));
        markerPaint.setColor(Color.WHITE);
        markerStrokePaint.setStyle(Paint.Style.STROKE);
        markerStrokePaint.setStrokeWidth(dp(4));
        markerStrokePaint.setColor(0x99000000);
        setFocusable(true);
    }

    public void setOnColorChangeListener(OnColorChangeListener listener) {
        this.listener = listener;
    }

    public void setColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hue = hsv[0];
        saturation = clamp01(hsv[1]);
        value = clamp01(hsv[2]);
        invalidate();
    }

    public void setHue(float hue) {
        this.hue = normalizeHue(hue);
        invalidate();
        notifyColorChanged();
    }

    public int getSelectedColor() {
        return Color.HSVToColor(new float[]{hue, saturation, value});
    }

    public float getHue() {
        return hue;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int hueColor = Color.HSVToColor(new float[]{hue, 1f, 1f});
        planePaint.setShader(new LinearGradient(
                0f,
                0f,
                width,
                0f,
                Color.WHITE,
                hueColor,
                Shader.TileMode.CLAMP
        ));
        canvas.drawRect(0f, 0f, width, height, planePaint);

        overlayPaint.setShader(new LinearGradient(
                0f,
                0f,
                0f,
                height,
                Color.TRANSPARENT,
                Color.BLACK,
                Shader.TileMode.CLAMP
        ));
        canvas.drawRect(0f, 0f, width, height, overlayPaint);

        float markerX = saturation * width;
        float markerY = (1f - value) * height;
        float radius = dp(7);
        canvas.drawCircle(markerX, markerY, radius, markerStrokePaint);
        canvas.drawCircle(markerX, markerY, radius, markerPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) {
            return true;
        }
        updateFromTouch(event.getX(), event.getY());
        return true;
    }

    private void updateFromTouch(float x, float y) {
        int width = Math.max(getWidth(), 1);
        int height = Math.max(getHeight(), 1);
        saturation = clamp01(x / width);
        value = 1f - clamp01(y / height);
        invalidate();
        notifyColorChanged();
    }

    private void notifyColorChanged() {
        if (listener != null) {
            listener.onColorChanged(getSelectedColor());
        }
    }

    private float normalizeHue(float hue) {
        float normalized = hue % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float dp(int value) {
        return getResources().getDisplayMetrics().density * value;
    }
}
