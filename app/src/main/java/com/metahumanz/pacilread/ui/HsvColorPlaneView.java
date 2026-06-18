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
    private final float[] hsvScratch = new float[3];

    private float hue = 210f;
    private float saturation = 0.55f;
    private float value = 0.65f;
    private float markerRadius;
    private int shaderWidth = -1;
    private int shaderHeight = -1;
    private float shaderHue = Float.NaN;
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
        markerRadius = dp(7);
        setFocusable(true);
    }

    public void setOnColorChangeListener(OnColorChangeListener listener) {
        this.listener = listener;
    }

    public void setColor(int color) {
        Color.colorToHSV(color, hsvScratch);
        hue = hsvScratch[0];
        saturation = clamp01(hsvScratch[1]);
        value = clamp01(hsvScratch[2]);
        invalidate();
    }

    public void setHue(float hue) {
        this.hue = normalizeHue(hue);
        invalidate();
        notifyColorChanged();
    }

    public int getSelectedColor() {
        hsvScratch[0] = hue;
        hsvScratch[1] = saturation;
        hsvScratch[2] = value;
        return Color.HSVToColor(hsvScratch);
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
        updateShadersIfNeeded(width, height);
        canvas.drawRect(0f, 0f, width, height, planePaint);

        canvas.drawRect(0f, 0f, width, height, overlayPaint);

        float markerX = saturation * width;
        float markerY = (1f - value) * height;
        canvas.drawCircle(markerX, markerY, markerRadius, markerStrokePaint);
        canvas.drawCircle(markerX, markerY, markerRadius, markerPaint);
    }

    private void updateShadersIfNeeded(int width, int height) {
        if (width == shaderWidth && height == shaderHeight && hue == shaderHue) {
            return;
        }
        hsvScratch[0] = hue;
        hsvScratch[1] = 1f;
        hsvScratch[2] = 1f;
        int hueColor = Color.HSVToColor(hsvScratch);
        planePaint.setShader(new LinearGradient(
                0f,
                0f,
                width,
                0f,
                Color.WHITE,
                hueColor,
                Shader.TileMode.CLAMP
        ));
        overlayPaint.setShader(new LinearGradient(
                0f,
                0f,
                0f,
                height,
                Color.TRANSPARENT,
                Color.BLACK,
                Shader.TileMode.CLAMP
        ));
        shaderWidth = width;
        shaderHeight = height;
        shaderHue = hue;
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
