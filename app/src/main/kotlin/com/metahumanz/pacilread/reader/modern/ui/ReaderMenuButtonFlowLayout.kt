package com.metahumanz.pacilread.reader.modern.ui

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

class ReaderMenuButtonFlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {
    private val rowGapPx = Math.round(context.resources.displayMetrics.density * 8f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val availableWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            Math.max(0, widthSize - paddingLeft - paddingRight)
        }
        val maxRows = maxRowsForOrientation()
        var rowWidth = 0
        var rowHeight = 0
        var measuredContentWidth = 0
        var measuredContentHeight = paddingTop + paddingBottom
        var rowCount = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChildWithMargins(
                child,
                widthMeasureSpec,
                paddingLeft + paddingRight,
                heightMeasureSpec,
                paddingTop + paddingBottom,
            )
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.marginStart + lp.marginEnd
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (rowWidth > 0 && rowWidth + childWidth > availableWidth && rowCount + 1 < maxRows) {
                measuredContentWidth = Math.max(measuredContentWidth, rowWidth)
                measuredContentHeight += rowHeight + rowGapPx
                rowWidth = 0
                rowHeight = 0
                rowCount++
            }
            rowWidth += childWidth
            rowHeight = Math.max(rowHeight, childHeight)
        }

        measuredContentWidth = Math.max(measuredContentWidth, rowWidth) + paddingLeft + paddingRight
        measuredContentHeight += rowHeight
        val resolvedWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.UNSPECIFIED -> measuredContentWidth
            else -> Math.min(measuredContentWidth, widthSize)
        }
        val resolvedHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.UNSPECIFIED -> measuredContentHeight
            else -> Math.min(measuredContentHeight, heightSize)
        }
        setMeasuredDimension(resolveSize(resolvedWidth, widthMeasureSpec), resolveSize(resolvedHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val availableWidth = Math.max(0, right - left - paddingLeft - paddingRight)
        val maxRows = maxRowsForOrientation()
        var rowTop = paddingTop
        var rowStartIndex = -1
        var rowContentWidth = 0
        var rowCount = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (rowStartIndex < 0) rowStartIndex = i
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.marginStart + lp.marginEnd
            if (rowContentWidth > 0 && rowContentWidth + childWidth > availableWidth && rowCount + 1 < maxRows) {
                rowTop += layoutRow(rowStartIndex, i, rowContentWidth, rowTop, availableWidth) + rowGapPx
                rowCount++
                rowStartIndex = i
                rowContentWidth = 0
            }
            rowContentWidth += childWidth
        }
        if (rowStartIndex >= 0) layoutRow(rowStartIndex, childCount, rowContentWidth, rowTop, availableWidth)
    }

    private fun layoutRow(startIndex: Int, endIndex: Int, rowContentWidth: Int, rowTop: Int, availableWidth: Int): Int {
        var x = paddingLeft + (availableWidth - rowContentWidth) / 2
        var rowHeight = 0
        for (i in startIndex until endIndex) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childLeft = x + lp.marginStart
            val childTop = rowTop + lp.topMargin
            child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)
            x += child.measuredWidth + lp.marginStart + lp.marginEnd
            rowHeight = Math.max(rowHeight, child.measuredHeight + lp.topMargin + lp.bottomMargin)
        }
        return rowHeight
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(params: LayoutParams?): LayoutParams = MarginLayoutParams(requireNotNull(params))

    override fun checkLayoutParams(params: LayoutParams?): Boolean = params is MarginLayoutParams

    private fun maxRowsForOrientation(): Int =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 1 else 2
}
