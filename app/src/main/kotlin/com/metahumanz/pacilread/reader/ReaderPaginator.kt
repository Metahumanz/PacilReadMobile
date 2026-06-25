package com.metahumanz.pacilread.reader

import android.annotation.SuppressLint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import java.util.concurrent.CancellationException
import java.util.function.BooleanSupplier

object ReaderPaginator {
    private const val PROGRESSIVE_WINDOW_CHARS = 8_000
    private const val PROGRESSIVE_WINDOW_LOOKAHEAD_CHARS = 2_000

    class ProgressiveResult internal constructor(
        pages: List<PageSlice>?,
        targetPageIndex: Int,
        @JvmField val complete: Boolean,
    ) {
        @JvmField val pages: List<PageSlice> = pages ?: ArrayList()
        @JvmField val targetPageIndex: Int = Math.max(targetPageIndex, 0)
    }

    @JvmStatic
    fun paginate(source: CharSequence?, paint: TextPaint, width: Int, height: Int, lineSpacingExtra: Float): List<PageSlice> =
        paginate(source, paint, width, height, height, lineSpacingExtra, 0)

    @JvmStatic
    fun paginate(source: CharSequence?, paint: TextPaint, width: Int, firstPageHeight: Int, regularPageHeight: Int, lineSpacingExtra: Float): List<PageSlice> =
        paginate(source, paint, width, firstPageHeight, regularPageHeight, lineSpacingExtra, 0)

    @JvmStatic
    fun paginate(
        source: CharSequence?, paint: TextPaint, width: Int, firstPageHeight: Int, regularPageHeight: Int,
        lineSpacingExtra: Float, bodyStartIndex: Int,
    ): List<PageSlice> {
        val pages = ArrayList<PageSlice>()
        val safeSource = source ?: ""
        val safeBodyStartIndex = Math.max(0, Math.min(bodyStartIndex, safeSource.length))
        if (source == null) {
            pages.add(PageSlice(0, 0, -1, -1, ""))
            return pages
        }
        if (safeSource.isEmpty() || width <= 0 || firstPageHeight <= 0 || regularPageHeight <= 0) {
            pages.add(buildPageSlice(safeSource, safeBodyStartIndex, 0, safeSource.length))
            return pages
        }
        val layout = buildLayout(safeSource, paint, width, lineSpacingExtra)
        val lineCount = layout.lineCount
        if (lineCount == 0) {
            pages.add(buildPageSlice(safeSource, safeBodyStartIndex, 0, safeSource.length))
            return pages
        }
        var startLine = 0
        while (startLine < lineCount) {
            val pageHeight = if (pages.isEmpty()) firstPageHeight else regularPageHeight
            var endLine = startLine
            while (endLine + 1 < lineCount &&
                lineBottomForPageEnd(safeSource, layout, endLine + 1) - layout.getLineTop(startLine) <= pageHeight
            ) endLine++
            val start = layout.getLineStart(startLine)
            val end = layout.getLineEnd(endLine)
            if (end <= start) break
            pages.add(buildPageSlice(safeSource, safeBodyStartIndex, start, end))
            startLine = endLine + 1
        }
        if (pages.isEmpty()) pages.add(buildPageSlice(safeSource, safeBodyStartIndex, 0, safeSource.length))
        return pages
    }

    @JvmStatic
    fun paginateUntilOffset(
        source: CharSequence?, paint: TextPaint, width: Int, firstPageHeight: Int, regularPageHeight: Int,
        lineSpacingExtra: Float, bodyStartIndex: Int, targetBodyOffset: Int, extraPagesAfterTarget: Int,
    ): ProgressiveResult = paginateUntilOffset(
        source, paint, width, firstPageHeight, regularPageHeight, lineSpacingExtra, bodyStartIndex,
        targetBodyOffset, extraPagesAfterTarget, BooleanSupplier { false },
    )

    @JvmStatic
    fun paginateUntilOffset(
        source: CharSequence?, paint: TextPaint, width: Int, firstPageHeight: Int, regularPageHeight: Int,
        lineSpacingExtra: Float, bodyStartIndex: Int, targetBodyOffset: Int, extraPagesAfterTarget: Int,
        cancellationRequested: BooleanSupplier?,
    ): ProgressiveResult {
        val pages = ArrayList<PageSlice>()
        val safeSource = source ?: ""
        val safeBodyStartIndex = Math.max(0, Math.min(bodyStartIndex, safeSource.length))
        val safeTargetOffset = Math.max(0, Math.min(targetBodyOffset, Math.max(0, safeSource.length - safeBodyStartIndex)))
        val safeExtraPages = Math.max(0, extraPagesAfterTarget)
        throwIfCancelled(cancellationRequested)
        if (source == null) {
            pages.add(PageSlice(0, 0, -1, -1, ""))
            return ProgressiveResult(pages, 0, true)
        }
        if (safeSource.isEmpty() || width <= 0 || firstPageHeight <= 0 || regularPageHeight <= 0) {
            pages.add(buildPageSlice(safeSource, safeBodyStartIndex, 0, safeSource.length))
            return ProgressiveResult(pages, findPageForOffset(pages, safeTargetOffset), true)
        }
        var targetPageIndex = -1
        var pageStart = 0
        while (pageStart < safeSource.length) {
            throwIfCancelled(cancellationRequested)
            val windowEnd = chooseProgressiveWindowEnd(safeSource, pageStart)
            val layout = buildLayout(safeSource, paint, width, lineSpacingExtra, pageStart, windowEnd)
            throwIfCancelled(cancellationRequested)
            val lineCount = layout.lineCount
            if (lineCount == 0) break
            val offsetsRelative = pageStart > 0 && layout.getLineStart(0) < pageStart
            var startLine = 0
            var advanced = false
            while (startLine < lineCount) {
                throwIfCancelled(cancellationRequested)
                val pageHeight = if (pages.isEmpty()) firstPageHeight else regularPageHeight
                var endLine = startLine
                while (endLine + 1 < lineCount &&
                    lineBottomForPageEnd(safeSource, layout, endLine + 1, pageStart, windowEnd, offsetsRelative) -
                    layout.getLineTop(startLine) <= pageHeight
                ) endLine++
                val start = layoutOffset(layout.getLineStart(startLine), pageStart, windowEnd, offsetsRelative)
                val end = layoutOffset(layout.getLineEnd(endLine), pageStart, windowEnd, offsetsRelative)
                if (end <= start) break
                val page = buildPageSlice(safeSource, safeBodyStartIndex, start, end)
                pages.add(page)
                val pageIndex = pages.size - 1
                if (targetPageIndex < 0 && page.hasBodyText() && safeTargetOffset >= page.start && safeTargetOffset < page.end) {
                    targetPageIndex = pageIndex
                }
                if (targetPageIndex >= 0 && pages.size > targetPageIndex + safeExtraPages) {
                    return ProgressiveResult(pages, targetPageIndex, false)
                }
                pageStart = end
                advanced = true
                if (pageStart >= safeSource.length) {
                    if (targetPageIndex < 0) targetPageIndex = findPageForOffset(pages, safeTargetOffset)
                    return ProgressiveResult(pages, targetPageIndex, true)
                }
                startLine = endLine + 1
            }
            if (!advanced) {
                pages.add(buildPageSlice(safeSource, safeBodyStartIndex, pageStart, safeSource.length))
                if (targetPageIndex < 0) targetPageIndex = findPageForOffset(pages, safeTargetOffset)
                return ProgressiveResult(pages, targetPageIndex, true)
            }
        }
        if (pages.isEmpty()) pages.add(buildPageSlice(safeSource, safeBodyStartIndex, 0, safeSource.length))
        if (targetPageIndex < 0) targetPageIndex = findPageForOffset(pages, safeTargetOffset)
        return ProgressiveResult(pages, targetPageIndex, true)
    }

    private fun throwIfCancelled(cancellationRequested: BooleanSupplier?) {
        if (cancellationRequested?.asBoolean == true) throw CancellationException("progressive pagination cancelled")
    }

    @JvmStatic
    fun findPageForOffset(pages: List<PageSlice>?, offset: Int): Int {
        if (pages.isNullOrEmpty()) return 0
        val safeOffset = Math.max(offset, 0)
        var firstBodyPageIndex = -1
        var lastBodyPageIndex = -1
        for (i in pages.indices) {
            val page = pages[i]
            if (!page.hasBodyText()) continue
            if (firstBodyPageIndex < 0) firstBodyPageIndex = i
            lastBodyPageIndex = i
            if (safeOffset < page.end) return i
        }
        if (safeOffset == 0 && firstBodyPageIndex >= 0) return firstBodyPageIndex
        return if (lastBodyPageIndex >= 0) lastBodyPageIndex else 0
    }

    private fun buildLayout(source: CharSequence, paint: TextPaint, width: Int, lineSpacingExtra: Float): StaticLayout =
        buildLayout(source, paint, width, lineSpacingExtra, 0, source.length)

    @SuppressLint("WrongConstant")
    private fun buildLayout(source: CharSequence, paint: TextPaint, width: Int, lineSpacingExtra: Float, start: Int, end: Int): StaticLayout {
        val safeStart = Math.max(0, Math.min(start, source.length))
        val safeEnd = Math.max(safeStart, Math.min(end, source.length))
        return StaticLayout.Builder.obtain(source, safeStart, safeEnd, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(lineSpacingExtra, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
    }

    private fun chooseProgressiveWindowEnd(source: CharSequence, start: Int): Int {
        val minimumEnd = Math.min(source.length, start + PROGRESSIVE_WINDOW_CHARS)
        if (minimumEnd >= source.length) return source.length
        val maximumEnd = Math.min(source.length, minimumEnd + PROGRESSIVE_WINDOW_LOOKAHEAD_CHARS)
        for (index in minimumEnd until maximumEnd) if (source[index] == '\n') return index + 1
        return maximumEnd
    }

    private fun layoutOffset(rawOffset: Int, windowStart: Int, windowEnd: Int, offsetsRelative: Boolean): Int {
        val absoluteOffset = if (offsetsRelative) windowStart + rawOffset else rawOffset
        return Math.max(windowStart, Math.min(absoluteOffset, windowEnd))
    }

    @JvmStatic
    fun lineBottomForPageEnd(source: CharSequence, layout: Layout, lineIndex: Int): Int =
        lineBottomForPageEnd(source, layout, lineIndex, 0, source.length, false)

    @JvmStatic
    fun lineBottomForPageEnd(
        source: CharSequence, layout: Layout, lineIndex: Int, windowStart: Int, windowEnd: Int, offsetsRelative: Boolean,
    ): Int {
        val lineBottom = layout.getLineBottom(lineIndex)
        if (source !is Spanned) return lineBottom
        val lineStart = layoutOffset(layout.getLineStart(lineIndex), windowStart, windowEnd, offsetsRelative)
        val visibleEnd = layoutOffset(layout.getLineVisibleEnd(lineIndex), windowStart, windowEnd, offsetsRelative)
        val lineEnd = layoutOffset(layout.getLineEnd(lineIndex), windowStart, windowEnd, offsetsRelative)
        if (lineStart >= visibleEnd || visibleEnd >= lineEnd) return lineBottom
        var spacingPx = 0
        for (span in source.getSpans(visibleEnd, lineEnd, ReaderParagraphBottomSpacingSpan::class.java)) {
            if (source.getSpanStart(span) < lineEnd && source.getSpanEnd(span) > visibleEnd) spacingPx += span.spacingPx
        }
        return if (spacingPx <= 0) lineBottom else Math.max(layout.getLineTop(lineIndex), lineBottom - spacingPx)
    }

    private fun buildPageSlice(source: CharSequence, bodyStartIndex: Int, contentStart: Int, contentEnd: Int): PageSlice {
        val safeContentStart = Math.max(0, Math.min(contentStart, source.length))
        val safeContentEnd = Math.max(safeContentStart, Math.min(contentEnd, source.length))
        val bodyTextStart = Math.max(safeContentStart, bodyStartIndex)
        val bodyTextEnd = Math.max(bodyTextStart, safeContentEnd)
        val hasBodyText = bodyTextEnd > bodyTextStart
        return PageSlice(
            Math.max(0, bodyTextStart - bodyStartIndex), Math.max(0, bodyTextEnd - bodyStartIndex),
            if (hasBodyText) bodyTextStart - safeContentStart else -1,
            if (hasBodyText) bodyTextEnd - safeContentStart else -1,
            buildSliceText(source, safeContentStart, safeContentEnd),
        )
    }

    private fun buildSliceText(source: CharSequence, start: Int, end: Int): CharSequence {
        if (source !is Spanned) return source.subSequence(start, end)
        val slice = SpannableStringBuilder(source.subSequence(start, end).toString())
        for (span in source.getSpans(start, end, Any::class.java)) {
            val spanStart = source.getSpanStart(span)
            val spanEnd = source.getSpanEnd(span)
            val clippedStart = Math.max(spanStart, start) - start
            val clippedEnd = Math.min(spanEnd, end) - start
            if (spanStart < 0 || clippedStart >= clippedEnd) continue
            val displaySpan: Any = if (span is LeadingMarginSpan && spanStart < start) {
                val margin = span.getLeadingMargin(false)
                LeadingMarginSpan.Standard(margin, margin)
            } else span
            slice.setSpan(displaySpan, clippedStart, clippedEnd, source.getSpanFlags(span))
        }
        return slice
    }
}
