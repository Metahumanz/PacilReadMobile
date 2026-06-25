package com.metahumanz.pacilread.reader

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan

class ReaderTitleSpan(private val typeface: Typeface?, private val textSizePx: Float) : MetricAffectingSpan() {
    override fun updateDrawState(textPaint: TextPaint) = apply(textPaint)

    override fun updateMeasureState(textPaint: TextPaint) = apply(textPaint)

    private fun apply(paint: Paint?) {
        if (paint == null) return
        if (typeface != null) paint.typeface = typeface
        if (textSizePx > 0f) paint.textSize = textSizePx
    }
}
