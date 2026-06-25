package com.metahumanz.pacilread.reader

import android.text.TextPaint
import java.util.Collections

object ReaderLineJustifier {
    private const val MIN_RESIDUAL_PX = 0.5f

    @JvmStatic
    fun layout(lineText: String?, startX: Float, availableWidth: Float, paint: TextPaint, allowJustify: Boolean): LineLayout {
        val safeText = lineText ?: ""
        val units = splitToUnits(safeText)
        val naturalWidth = measureRunAdvance(safeText, safeText.length, paint)
        val residualWidth = availableWidth - naturalWidth
        val positions = FloatArray(units.size)
        val contentStartUnit = firstContentUnit(units)
        val contentUnitCount = units.size - contentStartUnit
        val spaceGapCount = countSpaceGaps(units, contentStartUnit)
        val useSpaceGaps = allowJustify && residualWidth > MIN_RESIDUAL_PX && contentUnitCount > 1 && spaceGapCount > 1
        val gapCount = if (useSpaceGaps) spaceGapCount else Math.max(contentUnitCount - 1, 0)
        val justified = allowJustify && residualWidth > MIN_RESIDUAL_PX && gapCount > 0
        val extraGap = if (justified) residualWidth / gapCount else 0f
        var distributedExtra = 0f
        for (i in units.indices) {
            val unit = units[i]
            positions[i] = startX + measureRunAdvance(safeText, unit.start, paint) + distributedExtra
            if (!justified || i < contentStartUnit || i >= units.size - 1) continue
            if (useSpaceGaps) {
                if (unit.isOrdinarySpace()) distributedExtra += extraGap
            } else {
                distributedExtra += extraGap
            }
        }
        val endX = if (units.isEmpty()) {
            startX
        } else {
            val last = units[units.size - 1]
            positions[units.size - 1] + measureRunAdvance(safeText.substring(last.start, last.end), last.length(), paint)
        }
        return LineLayout(safeText, units, positions, startX, naturalWidth, residualWidth, endX, justified, useSpaceGaps, extraGap)
    }

    private fun firstContentUnit(units: List<TextUnit>): Int {
        var index = 0
        while (index < units.size && units[index].isIndent()) index++
        return index
    }

    private fun countSpaceGaps(units: List<TextUnit>, contentStartUnit: Int): Int {
        var count = 0
        for (i in Math.max(contentStartUnit, 0) until units.size - 1) if (units[i].isOrdinarySpace()) count++
        return count
    }

    private fun splitToUnits(text: String): List<TextUnit> {
        if (text.isEmpty()) return Collections.emptyList()
        val units = ArrayList<TextUnit>()
        var index = 0
        while (index < text.length) {
            val start = index
            index = consumeCluster(text, index)
            units.add(TextUnit(text.substring(start, index), start, index))
        }
        return units
    }

    private fun consumeCluster(text: String, start: Int): Int {
        var index = start + Character.charCount(text.codePointAt(start))
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (!isCombiningMark(codePoint) && !isVariationSelector(codePoint) && !isZeroWidthFormat(codePoint)) break
            index += Character.charCount(codePoint)
            if (isZeroWidthJoiner(codePoint) && index < text.length) index += Character.charCount(text.codePointAt(index))
        }
        return index
    }

    private fun isCombiningMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    private fun isVariationSelector(codePoint: Int): Boolean =
        codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF
    private fun isZeroWidthFormat(codePoint: Int): Boolean =
        codePoint == 0x200C || codePoint == 0x200D || codePoint == 0x2060 || codePoint == 0xFEFF
    private fun isZeroWidthJoiner(codePoint: Int): Boolean = codePoint == 0x200D

    @JvmStatic
    fun measureRunAdvance(text: CharSequence?, offset: Int, paint: TextPaint): Float {
        if (text == null || text.isEmpty() || offset <= 0) return 0f
        val safeOffset = Math.max(0, Math.min(offset, text.length))
        return paint.getRunAdvance(text, 0, text.length, 0, text.length, false, safeOffset)
    }

    class LineLayout internal constructor(
        private val text: String,
        private val units: List<TextUnit>,
        private val positions: FloatArray,
        private val startX: Float,
        private val naturalWidth: Float,
        private val residualWidth: Float,
        private val endX: Float,
        private val justified: Boolean,
        private val spaceGaps: Boolean,
        private val extraGap: Float,
    ) {
        fun text(): String = text
        fun unitCount(): Int = units.size
        fun unitAt(index: Int): TextUnit = units[index]
        fun unitX(index: Int): Float = positions[index]
        fun isJustified(): Boolean = justified
        fun usesSpaceGaps(): Boolean = spaceGaps
        fun naturalWidth(): Float = naturalWidth
        fun residualWidth(): Float = residualWidth
        fun extraGap(): Float = extraGap

        fun xForOffset(offset: Int, paint: TextPaint): Float {
            val safeOffset = Math.max(0, Math.min(offset, text.length))
            if (safeOffset <= 0 || units.isEmpty()) return startX
            for (i in units.indices) {
                val unit = units[i]
                if (safeOffset <= unit.start) return positions[i]
                if (safeOffset < unit.end) return positions[i] + measureRunAdvance(unit.text, safeOffset - unit.start, paint)
            }
            return endX
        }

        fun offsetForX(x: Float, paint: TextPaint): Int {
            if (text.isEmpty() || units.isEmpty() || x <= startX) return 0
            for (i in units.indices) {
                val unit = units[i]
                val unitStartX = positions[i]
                val unitEndX = unitStartX + measureRunAdvance(unit.text, unit.length(), paint)
                if (x < unitStartX) return unit.start
                if (x <= unitEndX) return unit.start + nearestOffsetInUnit(unit.text, x - unitStartX, paint)
                if (i + 1 < units.size && x < positions[i + 1]) return unit.end
            }
            return text.length
        }

        private fun nearestOffsetInUnit(unitText: String?, advance: Float, paint: TextPaint): Int {
            if (unitText.isNullOrEmpty() || advance <= 0f) return 0
            var bestOffset = 0
            var bestDistance = Math.abs(advance)
            for (offset in 1..unitText.length) {
                val distance = Math.abs(measureRunAdvance(unitText, offset, paint) - advance)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestOffset = offset
                }
            }
            return bestOffset
        }
    }

    class TextUnit(
        @JvmField val text: String,
        @JvmField val start: Int,
        @JvmField val end: Int,
    ) {
        fun length(): Int = end - start
        fun isOrdinarySpace(): Boolean = text == " "
        fun isIndent(): Boolean = text == " " || text == "\t" || text == "\u3000"
    }
}
