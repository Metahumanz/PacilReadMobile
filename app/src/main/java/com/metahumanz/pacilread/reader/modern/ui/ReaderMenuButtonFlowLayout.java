package com.metahumanz.pacilread.reader.modern.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

public final class ReaderMenuButtonFlowLayout extends ViewGroup {
    private final int rowGapPx;

    public ReaderMenuButtonFlowLayout(Context context) {
        this(context, null);
    }

    public ReaderMenuButtonFlowLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ReaderMenuButtonFlowLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        rowGapPx = Math.round(context.getResources().getDisplayMetrics().density * 8f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int availableWidth = widthMode == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE
                : Math.max(0, widthSize - getPaddingLeft() - getPaddingRight());
        int maxRows = maxRowsForOrientation();
        int rowCount = 1;
        int rowWidth = 0;
        int rowHeight = 0;
        int measuredWidth = 0;
        int measuredHeight = getPaddingTop() + getPaddingBottom();

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            measureChildWithMargins(
                    child,
                    widthMeasureSpec,
                    getPaddingLeft() + getPaddingRight(),
                    heightMeasureSpec,
                    getPaddingTop() + getPaddingBottom()
            );
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int childWidth = child.getMeasuredWidth() + lp.getMarginStart() + lp.getMarginEnd();
            int childHeight = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (rowWidth > 0 && rowWidth + childWidth > availableWidth && rowCount < maxRows) {
                measuredWidth = Math.max(measuredWidth, rowWidth);
                measuredHeight += rowHeight + rowGapPx;
                rowWidth = 0;
                rowHeight = 0;
                rowCount++;
            }
            rowWidth += childWidth;
            rowHeight = Math.max(rowHeight, childHeight);
        }

        measuredWidth = Math.max(measuredWidth, rowWidth) + getPaddingLeft() + getPaddingRight();
        measuredHeight += rowHeight;
        int resolvedWidth = widthMode == MeasureSpec.EXACTLY
                ? widthSize
                : (widthMode == MeasureSpec.UNSPECIFIED ? measuredWidth : Math.min(measuredWidth, widthSize));
        int resolvedHeight = heightMode == MeasureSpec.EXACTLY
                ? heightSize
                : (heightMode == MeasureSpec.UNSPECIFIED ? measuredHeight : Math.min(measuredHeight, heightSize));
        setMeasuredDimension(resolveSize(resolvedWidth, widthMeasureSpec), resolveSize(resolvedHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int availableWidth = Math.max(0, right - left - getPaddingLeft() - getPaddingRight());
        int maxRows = maxRowsForOrientation();
        int rowCount = 1;
        int rowLeft = getPaddingLeft();
        int rowTop = getPaddingTop();
        int rowHeight = 0;
        int x = rowLeft;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int childWidth = child.getMeasuredWidth() + lp.getMarginStart() + lp.getMarginEnd();
            int childHeight = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (x > rowLeft && x - rowLeft + childWidth > availableWidth && rowCount < maxRows) {
                x = rowLeft;
                rowTop += rowHeight + rowGapPx;
                rowHeight = 0;
                rowCount++;
            }
            int childLeft = x + lp.getMarginStart();
            int childTop = rowTop + lp.topMargin;
            child.layout(
                    childLeft,
                    childTop,
                    childLeft + child.getMeasuredWidth(),
                    childTop + child.getMeasuredHeight()
            );
            x += childWidth;
            rowHeight = Math.max(rowHeight, childHeight);
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams params) {
        return new MarginLayoutParams(params);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams params) {
        return params instanceof MarginLayoutParams;
    }

    private int maxRowsForOrientation() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 1 : 2;
    }
}
