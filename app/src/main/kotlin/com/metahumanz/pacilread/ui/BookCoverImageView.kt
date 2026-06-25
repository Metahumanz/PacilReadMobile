package com.metahumanz.pacilread.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatImageView
import com.metahumanz.pacilread.R

open class BookCoverImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {
    private val clipPath = Path()
    private val clipRect = RectF()
    private var cornerRadiusPx: Float = resolveCoverRadiusPx(context)

    init {
        scaleType = ScaleType.CENTER_CROP
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateClipPath(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (cornerRadiusPx <= 0f || width <= 0 || height <= 0) {
            super.onDraw(canvas)
            return
        }
        val saveCount = canvas.save()
        canvas.clipPath(clipPath)
        super.onDraw(canvas)
        canvas.restoreToCount(saveCount)
    }

    private fun updateClipPath(width: Int, height: Int) {
        clipPath.reset()
        if (width <= 0 || height <= 0) return
        clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.addRoundRect(clipRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        clipPath.close()
    }

    companion object {
        private fun resolveCoverRadiusPx(context: Context?): Float {
            if (context == null) return 0f
            val value = TypedValue()
            if (context.theme.resolveAttribute(R.attr.themeRadiusAppCard, value, true) && value.type == TypedValue.TYPE_DIMENSION) {
                return TypedValue.complexToDimension(value.data, context.resources.displayMetrics)
            }
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, context.resources.displayMetrics)
        }
    }
}
