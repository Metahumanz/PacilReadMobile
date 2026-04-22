package com.metahumanz.pacilread.reader;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;

public final class ReaderTitleSpan extends MetricAffectingSpan {
    private final Typeface typeface;
    private final float textSizePx;

    public ReaderTitleSpan(Typeface typeface, float textSizePx) {
        this.typeface = typeface;
        this.textSizePx = textSizePx;
    }

    @Override
    public void updateDrawState(TextPaint textPaint) {
        apply(textPaint);
    }

    @Override
    public void updateMeasureState(TextPaint textPaint) {
        apply(textPaint);
    }

    private void apply(Paint paint) {
        if (paint == null) {
            return;
        }
        if (typeface != null) {
            paint.setTypeface(typeface);
        }
        if (textSizePx > 0f) {
            paint.setTextSize(textSizePx);
        }
    }
}
