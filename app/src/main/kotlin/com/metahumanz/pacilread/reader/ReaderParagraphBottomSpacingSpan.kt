package com.metahumanz.pacilread.reader

import android.graphics.Paint
import android.text.style.LineHeightSpan

class ReaderParagraphBottomSpacingSpan(spacingPx: Int) : LineHeightSpan {
    val spacingPx: Int = spacingPx.coerceAtLeast(0)

    override fun chooseHeight(
        text: CharSequence?,
        start: Int,
        end: Int,
        spanstartv: Int,
        v: Int,
        fontMetricsInt: Paint.FontMetricsInt?,
    ) {
        if (fontMetricsInt == null || spacingPx <= 0) return
        fontMetricsInt.descent += spacingPx
        fontMetricsInt.bottom += spacingPx
    }
}
