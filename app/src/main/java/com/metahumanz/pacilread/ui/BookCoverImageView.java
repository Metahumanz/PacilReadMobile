package com.metahumanz.pacilread.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;

import androidx.appcompat.widget.AppCompatImageView;

import com.metahumanz.pacilread.R;

public class BookCoverImageView extends AppCompatImageView {
    private final Path clipPath = new Path();
    private final RectF clipRect = new RectF();
    private float cornerRadiusPx;

    public BookCoverImageView(Context context) {
        super(context);
        init(context);
    }

    public BookCoverImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public BookCoverImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        cornerRadiusPx = resolveCoverRadiusPx(context);
        setScaleType(ScaleType.CENTER_CROP);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateClipPath(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (cornerRadiusPx <= 0f || getWidth() <= 0 || getHeight() <= 0) {
            super.onDraw(canvas);
            return;
        }
        int saveCount = canvas.save();
        canvas.clipPath(clipPath);
        super.onDraw(canvas);
        canvas.restoreToCount(saveCount);
    }

    private void updateClipPath(int width, int height) {
        clipPath.reset();
        if (width <= 0 || height <= 0) {
            return;
        }
        clipRect.set(0f, 0f, width, height);
        clipPath.addRoundRect(
                clipRect,
                cornerRadiusPx,
                cornerRadiusPx,
                Path.Direction.CW
        );
        clipPath.close();
    }

    private static float resolveCoverRadiusPx(Context context) {
        if (context == null) {
            return 0f;
        }
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(R.attr.themeRadiusAppCard, value, true)
                && value.type == TypedValue.TYPE_DIMENSION) {
            return TypedValue.complexToDimension(
                    value.data,
                    context.getResources().getDisplayMetrics()
            );
        }
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                12f,
                context.getResources().getDisplayMetrics()
        );
    }
}
