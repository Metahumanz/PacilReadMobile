package com.metahumanz.pacilread.reader;

import android.text.style.LineHeightSpan;

public final class ReaderParagraphBottomSpacingSpan implements LineHeightSpan {
    private final int spacingPx;

    public ReaderParagraphBottomSpacingSpan(int spacingPx) {
        this.spacingPx = Math.max(spacingPx, 0);
    }

    public int getSpacingPx() {
        return spacingPx;
    }

    @Override
    public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int v, android.graphics.Paint.FontMetricsInt fontMetricsInt) {
        if (fontMetricsInt == null || spacingPx <= 0) {
            return;
        }
        fontMetricsInt.descent += spacingPx;
        fontMetricsInt.bottom += spacingPx;
    }
}
