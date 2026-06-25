package com.metahumanz.pacilread.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.LineHeightSpan
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.max
import kotlin.math.min

open class JustifiedPageTextView : AppCompatTextView {
    private var fullJustifyEnabled = true
    private var treatFinalLineAsParagraphEnd = true
    private var bottomJustifyEnabled = false
    private var highlightStart = -1
    private var highlightEnd = -1
    private var selectionHighlightStart = -1
    private var selectionHighlightEnd = -1
    private val highlightPaint = Paint()
    private val selectionHighlightPaint = Paint()
    private val handleFillPaint = Paint()
    private val handleStrokePaint = Paint()
    private var handleRadius = 0f
    private var handleTouchRadius = 0f
    private val startHandleBounds = RectF()
    private val endHandleBounds = RectF()

    constructor(context: Context) : super(context) {
        initView()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initView()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initView()
    }

    @SuppressLint("WrongConstant")
    private fun initView() {
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        includeFontPadding = false
        gravity = Gravity.START or Gravity.TOP
        highlightPaint.color = 0x40FFC107
        highlightPaint.style = Paint.Style.FILL
        selectionHighlightPaint.color = 0x663B82F6
        selectionHighlightPaint.style = Paint.Style.FILL
        val density = resources.displayMetrics.density
        handleRadius = density * 5f
        handleTouchRadius = density * 14f
        handleFillPaint.color = 0xFF3B82F6.toInt()
        handleFillPaint.style = Paint.Style.FILL
        handleFillPaint.isAntiAlias = true
        handleStrokePaint.color = 0xFFFFFFFF.toInt()
        handleStrokePaint.style = Paint.Style.STROKE
        handleStrokePaint.strokeWidth = density * 1.5f
        handleStrokePaint.isAntiAlias = true
    }

    fun setFullJustifyEnabled(enabled: Boolean) {
        if (fullJustifyEnabled == enabled) return
        fullJustifyEnabled = enabled
        invalidate()
    }

    fun setTreatFinalLineAsParagraphEnd(enabled: Boolean) {
        if (treatFinalLineAsParagraphEnd == enabled) return
        treatFinalLineAsParagraphEnd = enabled
        invalidate()
    }

    fun setBottomJustifyEnabled(enabled: Boolean) {
        if (bottomJustifyEnabled == enabled) return
        bottomJustifyEnabled = enabled
        invalidate()
    }

    fun setHighlightRange(start: Int, end: Int) {
        highlightStart = start
        highlightEnd = end
        invalidate()
    }

    fun clearHighlight() {
        highlightStart = -1
        highlightEnd = -1
        invalidate()
    }

    fun setSelectionHighlightRange(start: Int, end: Int) {
        selectionHighlightStart = start
        selectionHighlightEnd = end
        invalidate()
    }

    fun clearSelectionHighlight() {
        selectionHighlightStart = -1
        selectionHighlightEnd = -1
        invalidate()
    }

    fun offsetForTouch(x: Float, y: Float): Int {
        val layout = layout
        val text = text
        if (layout == null || text == null || text.isEmpty()) return 0
        val contentX = x - totalPaddingLeft
        val contentY = y - contentTopPadding()
        val verticalAdjustments = verticalAdjustments(layout, text)
        val lineIndex = verticalAdjustments.lineForVertical(layout, contentY)
        val start = layout.getLineStart(lineIndex)
        val end = layout.getLineEnd(lineIndex)
        val visibleEnd = layout.getLineVisibleEnd(lineIndex)
        if (start >= visibleEnd) return max(0, min(start, text.length))
        if (!fullJustifyEnabled || shouldUsePlatformLine(text, start, end)) {
            return max(start, min(layout.getOffsetForHorizontal(lineIndex, contentX), visibleEnd))
        }
        val paragraphEnd = hasTrailingLineBreak(text, visibleEnd, end) ||
            isFinalLineParagraphEnd(text, visibleEnd, lineIndex, layout.lineCount)
        val drawLine = trimLineBreaks(text.subSequence(start, visibleEnd).toString())
        if (drawLine.isEmpty()) return start
        val paint = paint
        val availableWidth = width - totalPaddingLeft - totalPaddingRight
        val lineLeft = lineContentLeft(layout, lineIndex)
        val lineAvailableWidth = lineContentWidth(layout, lineIndex, availableWidth)
        val lineLayout = ReaderLineJustifier.layout(
            drawLine,
            lineLeft,
            lineAvailableWidth,
            paint,
            shouldJustify(drawLine, paragraphEnd, lineAvailableWidth),
        )
        return max(start, min(start + lineLayout.offsetForX(contentX, paint), visibleEnd))
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        if (layout == null || !fullJustifyEnabled && !bottomJustifyEnabled) {
            drawHighlight(canvas, layout)
            super.onDraw(canvas)
            if (hasSelectionHighlight() && layout != null) {
                val save = canvas.save()
                canvas.translate(totalPaddingLeft.toFloat(), contentTopPadding().toFloat())
                drawSelectionHandles(canvas, layout, verticalAdjustments(layout, text))
                canvas.restoreToCount(save)
            }
            return
        }
        val paint = paint
        paint.color = currentTextColor
        val text = text
        val lineCount = layout.lineCount
        val availableWidth = width - totalPaddingLeft - totalPaddingRight
        val verticalAdjustments = verticalAdjustments(layout, text)
        val saveCount = canvas.save()
        canvas.translate(totalPaddingLeft.toFloat(), contentTopPadding().toFloat())
        for (lineIndex in 0 until lineCount) {
            val start = layout.getLineStart(lineIndex)
            val end = layout.getLineEnd(lineIndex)
            val visibleEnd = layout.getLineVisibleEnd(lineIndex)
            if (start >= visibleEnd) continue
            val lineSaveCount = canvas.save()
            canvas.translate(0f, verticalAdjustments.offsetForLine(lineIndex))
            if (shouldUsePlatformLine(text, start, end)) {
                drawLineHighlight(canvas, layout, lineIndex, paint, text, start, visibleEnd)
                drawPlatformLine(canvas, layout, lineIndex, availableWidth)
                canvas.restoreToCount(lineSaveCount)
                continue
            }
            val paragraphEnd = hasTrailingLineBreak(text, visibleEnd, end) ||
                isFinalLineParagraphEnd(text, visibleEnd, lineIndex, lineCount)
            val drawLine = trimLineBreaks(text.subSequence(start, visibleEnd).toString())
            if (drawLine.isEmpty()) {
                canvas.restoreToCount(lineSaveCount)
                continue
            }
            val lineLeft = lineContentLeft(layout, lineIndex)
            val baseline = layout.getLineBaseline(lineIndex).toFloat()
            val lineAvailableWidth = lineContentWidth(layout, lineIndex, availableWidth)
            val lineLayout = ReaderLineJustifier.layout(
                drawLine,
                lineLeft,
                lineAvailableWidth,
                paint,
                shouldJustify(drawLine, paragraphEnd, lineAvailableWidth),
            )
            if (lineLayout.isJustified()) {
                drawLineLayoutWithHighlight(canvas, lineLayout, layout, lineIndex, baseline, paint, start)
            } else {
                drawLineHighlightManual(canvas, lineLeft, layout, lineIndex, paint, text, start, visibleEnd)
                canvas.drawText(drawLine, lineLeft, baseline, paint)
            }
            canvas.restoreToCount(lineSaveCount)
        }
        if (hasSelectionHighlight()) drawSelectionHandles(canvas, layout, verticalAdjustments)
        canvas.restoreToCount(saveCount)
    }

    private fun shouldUsePlatformLine(text: CharSequence, lineStart: Int, lineEnd: Int): Boolean {
        if (text !is Spanned) return false
        return text.getSpans(lineStart, lineEnd, ReaderTitleSpan::class.java).isNotEmpty() ||
            text.getSpans(lineStart, lineEnd, AlignmentSpan::class.java).isNotEmpty() ||
            text.getSpans(lineStart, lineEnd, LineHeightSpan::class.java).isNotEmpty()
    }

    private fun lineContentLeft(layout: Layout, lineIndex: Int): Float = max(0f, layout.getParagraphLeft(lineIndex).toFloat())

    private fun lineContentWidth(layout: Layout, lineIndex: Int, availableWidth: Int): Float {
        val paragraphLeft = lineContentLeft(layout, lineIndex)
        val paragraphRight = min(availableWidth.toFloat(), layout.getParagraphRight(lineIndex).toFloat())
        return max(0f, paragraphRight - paragraphLeft)
    }

    private fun drawPlatformLine(canvas: Canvas, layout: Layout, lineIndex: Int, availableWidth: Int) {
        val saveCount = canvas.save()
        canvas.clipRect(0, layout.getLineTop(lineIndex), availableWidth, layout.getLineBottom(lineIndex))
        layout.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    private fun drawHighlight(canvas: Canvas, layout: Layout?) {
        if (layout == null || !hasAnyHighlight()) return
        val paint = paint
        val text = text
        val verticalAdjustments = verticalAdjustments(layout, text)
        val saveCount = canvas.save()
        canvas.translate(totalPaddingLeft.toFloat(), contentTopPadding().toFloat())
        for (lineIndex in 0 until layout.lineCount) {
            val start = layout.getLineStart(lineIndex)
            val visibleEnd = layout.getLineVisibleEnd(lineIndex)
            if (start >= visibleEnd) continue
            val lineSaveCount = canvas.save()
            canvas.translate(0f, verticalAdjustments.offsetForLine(lineIndex))
            drawLineHighlight(canvas, layout, lineIndex, paint, text, start, visibleEnd)
            canvas.restoreToCount(lineSaveCount)
        }
        canvas.restoreToCount(saveCount)
    }

    private fun drawLineHighlight(
        canvas: Canvas,
        layout: Layout,
        lineIndex: Int,
        paint: Paint,
        text: CharSequence,
        lineStart: Int,
        lineEnd: Int,
    ) {
        drawLineHighlightRangePlatform(
            canvas, layout, lineIndex, paint, text, lineStart, lineEnd,
            highlightStart, highlightEnd, highlightPaint,
        )
        drawLineHighlightRangePlatform(
            canvas, layout, lineIndex, paint, text, lineStart, lineEnd,
            selectionHighlightStart, selectionHighlightEnd, selectionHighlightPaint,
        )
    }

    private fun drawLineHighlightRangePlatform(
        canvas: Canvas,
        layout: Layout,
        lineIndex: Int,
        @Suppress("UNUSED_PARAMETER") paint: Paint,
        @Suppress("UNUSED_PARAMETER") text: CharSequence,
        lineStart: Int,
        lineEnd: Int,
        rangeStart: Int,
        rangeEnd: Int,
        rangePaint: Paint,
    ) {
        if (rangeStart < 0 || rangeEnd <= rangeStart) return
        val lineHighlightStart = max(rangeStart, lineStart)
        val lineHighlightEnd = min(rangeEnd, lineEnd)
        if (lineHighlightStart < lineHighlightEnd) {
            canvas.drawRect(
                layout.getPrimaryHorizontal(lineHighlightStart),
                layout.getLineTop(lineIndex).toFloat(),
                layout.getPrimaryHorizontal(lineHighlightEnd),
                layout.getLineBottom(lineIndex).toFloat(),
                rangePaint,
            )
        }
    }

    private fun drawLineHighlightManual(
        canvas: Canvas,
        lineLeft: Float,
        layout: Layout,
        lineIndex: Int,
        paint: Paint,
        text: CharSequence,
        lineStart: Int,
        lineEnd: Int,
    ) {
        drawLineHighlightRangeManual(
            canvas, lineLeft, layout, lineIndex, paint, text, lineStart, lineEnd,
            highlightStart, highlightEnd, highlightPaint,
        )
        drawLineHighlightRangeManual(
            canvas, lineLeft, layout, lineIndex, paint, text, lineStart, lineEnd,
            selectionHighlightStart, selectionHighlightEnd, selectionHighlightPaint,
        )
    }

    private fun drawLineHighlightRangeManual(
        canvas: Canvas,
        lineLeft: Float,
        layout: Layout,
        lineIndex: Int,
        paint: Paint,
        text: CharSequence,
        lineStart: Int,
        lineEnd: Int,
        rangeStart: Int,
        rangeEnd: Int,
        rangePaint: Paint,
    ) {
        if (rangeStart < 0 || rangeEnd <= rangeStart) return
        val lineHighlightStart = max(rangeStart, lineStart)
        val lineHighlightEnd = min(rangeEnd, lineEnd)
        if (lineHighlightStart < lineHighlightEnd) {
            canvas.drawRect(
                lineLeft + paint.measureText(text, lineStart, lineHighlightStart),
                layout.getLineTop(lineIndex).toFloat(),
                lineLeft + paint.measureText(text, lineStart, lineHighlightEnd),
                layout.getLineBottom(lineIndex).toFloat(),
                rangePaint,
            )
        }
    }

    private fun shouldJustify(lineText: String, paragraphEnd: Boolean, availableWidth: Float): Boolean {
        if (!fullJustifyEnabled || paragraphEnd) return false
        return lineText.trim().length > 1 && availableWidth > 0f
    }

    private fun drawLineLayoutWithHighlight(
        canvas: Canvas,
        lineLayout: ReaderLineJustifier.LineLayout,
        layout: Layout,
        lineIndex: Int,
        baseline: Float,
        paint: TextPaint,
        lineStart: Int,
    ) {
        val highlightTop = layout.getLineTop(lineIndex).toFloat()
        val highlightBottom = layout.getLineBottom(lineIndex).toFloat()
        drawLineLayoutHighlightRange(
            canvas, lineLayout, highlightTop, highlightBottom, paint, lineStart,
            highlightStart, highlightEnd, highlightPaint,
        )
        drawLineLayoutHighlightRange(
            canvas, lineLayout, highlightTop, highlightBottom, paint, lineStart,
            selectionHighlightStart, selectionHighlightEnd, selectionHighlightPaint,
        )
        val text = lineLayout.text()
        for (i in 0 until lineLayout.unitCount()) {
            val unit = lineLayout.unitAt(i)
            canvas.drawText(text, unit.start, unit.end, lineLayout.unitX(i), baseline, paint)
        }
    }

    private fun drawLineLayoutHighlightRange(
        canvas: Canvas,
        lineLayout: ReaderLineJustifier.LineLayout,
        top: Float,
        bottom: Float,
        paint: TextPaint,
        lineStart: Int,
        rangeStart: Int,
        rangeEnd: Int,
        rangePaint: Paint,
    ) {
        if (rangeStart < 0 || rangeEnd <= rangeStart) return
        val lineHighlightStart = max(rangeStart - lineStart, 0)
        val lineHighlightEnd = min(rangeEnd - lineStart, lineLayout.text().length)
        if (lineHighlightStart < lineHighlightEnd) {
            canvas.drawRect(
                lineLayout.xForOffset(lineHighlightStart, paint),
                top,
                lineLayout.xForOffset(lineHighlightEnd, paint),
                bottom,
                rangePaint,
            )
        }
    }

    private fun hasAnyHighlight(): Boolean =
        highlightStart >= 0 && highlightEnd > highlightStart ||
            selectionHighlightStart >= 0 && selectionHighlightEnd > selectionHighlightStart

    private fun hasSelectionHighlight(): Boolean =
        selectionHighlightStart >= 0 && selectionHighlightEnd > selectionHighlightStart

    private fun drawSelectionHandles(canvas: Canvas, layout: Layout, verticalAdjustments: LineVerticalAdjustments) {
        val text = text ?: return
        val screenPos = IntArray(2)
        getLocationOnScreen(screenPos)
        val padLeft = totalPaddingLeft.toFloat()
        val padTop = contentTopPadding().toFloat()
        drawHandleAtOffset(
            canvas, layout, text, verticalAdjustments, screenPos, padLeft, padTop,
            selectionHighlightStart, startHandleBounds,
        )
        drawHandleAtOffset(
            canvas, layout, text, verticalAdjustments, screenPos, padLeft, padTop,
            selectionHighlightEnd, endHandleBounds,
        )
    }

    private fun drawHandleAtOffset(
        canvas: Canvas,
        layout: Layout,
        text: CharSequence,
        verticalAdjustments: LineVerticalAdjustments,
        screenPos: IntArray,
        padLeft: Float,
        padTop: Float,
        offset: Int,
        outBounds: RectF,
    ) {
        val canvasX = canvasXForOffset(layout, text, offset)
        val lineIndex = when {
            offset <= 0 -> 0
            offset >= text.length -> layout.lineCount - 1
            else -> layout.getLineForOffset(offset)
        }
        val canvasY = visibleTextBottom(layout, lineIndex, paint) + verticalAdjustments.offsetForLine(lineIndex)
        canvas.drawCircle(canvasX, canvasY, handleRadius, handleStrokePaint)
        canvas.drawCircle(canvasX, canvasY, handleRadius - handleStrokePaint.strokeWidth, handleFillPaint)
        val screenX = screenPos[0] + padLeft + canvasX
        val screenY = screenPos[1] + padTop + canvasY
        outBounds.set(
            screenX - handleTouchRadius,
            screenY - handleTouchRadius,
            screenX + handleTouchRadius,
            screenY + handleTouchRadius,
        )
    }

    private fun canvasXForOffset(layout: Layout, text: CharSequence, offset: Int): Float {
        if (offset < 0 || offset > text.length) return 0f
        val safeOffset = max(0, min(offset, text.length))
        val lineIndex = if (safeOffset >= text.length) max(0, layout.lineCount - 1) else layout.getLineForOffset(safeOffset)
        val lineStart = layout.getLineStart(lineIndex)
        val lineEnd = layout.getLineEnd(lineIndex)
        if (!fullJustifyEnabled || shouldUsePlatformLine(text, lineStart, lineEnd)) {
            return layout.getPrimaryHorizontal(offset)
        }
        val visibleEnd = layout.getLineVisibleEnd(lineIndex)
        if (lineStart >= visibleEnd) return 0f
        val paragraphEnd = hasTrailingLineBreak(text, visibleEnd, lineEnd) ||
            isFinalLineParagraphEnd(text, visibleEnd, lineIndex, layout.lineCount)
        val drawLine = trimLineBreaks(text.subSequence(lineStart, visibleEnd).toString())
        if (drawLine.isEmpty()) return 0f
        val lineLeft = lineContentLeft(layout, lineIndex)
        val paint = paint
        val availableWidth = width - totalPaddingLeft - totalPaddingRight
        val lineAvailableWidth = lineContentWidth(layout, lineIndex, availableWidth)
        val lineLayout = ReaderLineJustifier.layout(
            drawLine,
            lineLeft,
            lineAvailableWidth,
            paint,
            shouldJustify(drawLine, paragraphEnd, lineAvailableWidth),
        )
        if (lineLayout.isJustified()) {
            val offsetInLine = max(0, min(offset - lineStart, drawLine.length))
            return lineLayout.xForOffset(offsetInLine, paint)
        }
        return lineLeft + paint.measureText(text, lineStart, offset)
    }

    fun getSelectionHandleScreenBounds(offset: Int): RectF? = when (offset) {
        selectionHighlightStart -> if (startHandleBounds.isEmpty) null else RectF(startHandleBounds)
        selectionHighlightEnd -> if (endHandleBounds.isEmpty) null else RectF(endHandleBounds)
        else -> null
    }

    internal fun adjustedLineTopForTest(lineIndex: Int): Float {
        val layout = layout
        val text = text
        if (layout == null || text == null || lineIndex < 0 || lineIndex >= layout.lineCount) return 0f
        return layout.getLineTop(lineIndex) + verticalAdjustments(layout, text).offsetForLine(lineIndex)
    }

    internal fun adjustedVisualLineBottomForTest(lineIndex: Int): Float {
        val layout = layout
        val text = text
        if (layout == null || text == null || lineIndex < 0 || lineIndex >= layout.lineCount) return 0f
        return visibleTextBottom(layout, lineIndex, paint) + verticalAdjustments(layout, text).offsetForLine(lineIndex)
    }

    private fun contentTopPadding(): Int = compoundPaddingTop

    private fun contentHeight(): Int = max(0, height - contentTopPadding() - compoundPaddingBottom)

    private fun verticalAdjustments(layout: Layout, text: CharSequence): LineVerticalAdjustments =
        LineVerticalAdjustments.create(layout, text, paint, bottomJustifyEnabled, contentHeight())

    private fun hasTrailingLineBreak(text: CharSequence, visibleEnd: Int, lineEnd: Int): Boolean {
        val safeVisibleEnd = max(0, min(visibleEnd, text.length))
        val safeLineEnd = max(safeVisibleEnd, min(lineEnd, text.length))
        for (i in safeVisibleEnd until safeLineEnd) {
            val char = text[i]
            if (char == '\n' || char == '\r') return true
        }
        return false
    }

    private fun isFinalLineParagraphEnd(text: CharSequence, visibleEnd: Int, lineIndex: Int, lineCount: Int): Boolean {
        if (!treatFinalLineAsParagraphEnd || lineIndex != lineCount - 1) return false
        val safeVisibleEnd = max(0, min(visibleEnd, text.length))
        for (i in safeVisibleEnd until text.length) if (!text[i].isWhitespace()) return false
        return true
    }

    private class LineVerticalAdjustments private constructor(
        private val firstLine: Int,
        private val lastLine: Int,
        private val surplus: Float,
    ) {
        fun offsetForLine(lineIndex: Int): Float {
            if (surplus <= 0f || firstLine < 0 || lastLine <= firstLine || lineIndex <= firstLine) return 0f
            if (lineIndex >= lastLine) return surplus
            return surplus * (lineIndex - firstLine) / (lastLine - firstLine).toFloat()
        }

        fun firstLine(): Int = firstLine
        fun lastLine(): Int = lastLine
        fun surplus(): Float = surplus

        fun lineForVertical(layout: Layout, y: Float): Int {
            val lineCount = layout.lineCount
            if (surplus <= 0f || firstLine < 0 || lastLine <= firstLine) {
                return max(0, min(layout.getLineForVertical(Math.round(y)), lineCount - 1))
            }
            for (lineIndex in 0 until lineCount) {
                val top = layout.getLineTop(lineIndex) + offsetForLine(lineIndex)
                val bottom = layout.getLineBottom(lineIndex) + offsetForLine(lineIndex)
                if (y < bottom) return lineIndex
                if (y >= top && y <= bottom) return lineIndex
            }
            return max(0, lineCount - 1)
        }

        companion object {
            private val NONE = LineVerticalAdjustments(-1, -1, 0f)

            fun create(
                layout: Layout?,
                text: CharSequence?,
                paint: Paint?,
                enabled: Boolean,
                contentHeight: Int,
            ): LineVerticalAdjustments {
                if (!enabled || layout == null || text == null || paint == null || layout.lineCount <= 1 || contentHeight <= 0) {
                    return NONE
                }
                var first = -1
                var last = -1
                for (lineIndex in 0 until layout.lineCount) {
                    if (!isDrawableLine(layout, text, lineIndex)) continue
                    if (first < 0) first = lineIndex
                    last = lineIndex
                }
                if (first < 0 || last <= first) return NONE
                val surplus = contentHeight - visibleTextBottom(layout, last, paint)
                if (surplus <= 0f) return NONE
                return LineVerticalAdjustments(first, last, surplus)
            }

            private fun isDrawableLine(layout: Layout, text: CharSequence, lineIndex: Int): Boolean {
                val start = layout.getLineStart(lineIndex)
                val visibleEnd = layout.getLineVisibleEnd(lineIndex)
                if (start >= visibleEnd) return false
                return trimLineBreaks(text.subSequence(start, visibleEnd).toString()).isNotEmpty()
            }
        }
    }

    companion object {
        private fun visibleTextBottom(layout: Layout?, lineIndex: Int, paint: Paint?): Float {
            if (layout == null || paint == null || lineIndex < 0 || lineIndex >= layout.lineCount) return 0f
            return layout.getLineBaseline(lineIndex) + paint.fontMetrics.descent
        }

        private fun trimLineBreaks(line: String): String {
            var end = line.length
            while (end > 0) {
                val char = line[end - 1]
                if (char == '\n' || char == '\r') end-- else break
            }
            return line.substring(0, end)
        }
    }
}
