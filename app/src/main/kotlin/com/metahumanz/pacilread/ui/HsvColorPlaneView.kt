package com.metahumanz.pacilread.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

open class HsvColorPlaneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    fun interface OnColorChangeListener {
        fun onColorChanged(color: Int)
    }

    private val planePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsvScratch = FloatArray(3)
    private var hue = 210f
    private var saturation = 0.55f
    private var value = 0.65f
    private var markerRadius = 0f
    private var shaderWidth = -1
    private var shaderHeight = -1
    private var shaderHue = Float.NaN
    private var listener: OnColorChangeListener? = null

    init {
        markerPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2)
            color = Color.WHITE
        }
        markerStrokePaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(4)
            color = 0x99000000.toInt()
        }
        markerRadius = dp(7)
        isFocusable = true
    }

    fun setOnColorChangeListener(listener: OnColorChangeListener?) {
        this.listener = listener
    }

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsvScratch)
        hue = hsvScratch[0]
        saturation = clamp01(hsvScratch[1])
        value = clamp01(hsvScratch[2])
        invalidate()
    }

    fun setHue(hue: Float) {
        this.hue = normalizeHue(hue)
        invalidate()
        notifyColorChanged()
    }

    fun getSelectedColor(): Int {
        hsvScratch[0] = hue
        hsvScratch[1] = saturation
        hsvScratch[2] = value
        return Color.HSVToColor(hsvScratch)
    }

    fun getHue(): Float = hue

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width
        val height = height
        if (width <= 0 || height <= 0) return
        updateShadersIfNeeded(width, height)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), planePaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        val markerX = saturation * width
        val markerY = (1f - value) * height
        canvas.drawCircle(markerX, markerY, markerRadius, markerStrokePaint)
        canvas.drawCircle(markerX, markerY, markerRadius, markerPaint)
    }

    private fun updateShadersIfNeeded(width: Int, height: Int) {
        if (width == shaderWidth && height == shaderHeight && hue == shaderHue) return
        hsvScratch[0] = hue
        hsvScratch[1] = 1f
        hsvScratch[2] = 1f
        val hueColor = Color.HSVToColor(hsvScratch)
        planePaint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, Color.WHITE, hueColor, Shader.TileMode.CLAMP)
        overlayPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        shaderWidth = width
        shaderHeight = height
        shaderHue = hue
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) return true
        updateFromTouch(event.x, event.y)
        return true
    }

    private fun updateFromTouch(x: Float, y: Float) {
        val width = Math.max(width, 1)
        val height = Math.max(height, 1)
        saturation = clamp01(x / width)
        value = 1f - clamp01(y / height)
        invalidate()
        notifyColorChanged()
    }

    private fun notifyColorChanged() = listener?.onColorChanged(getSelectedColor()) ?: Unit
    private fun normalizeHue(hue: Float): Float = (hue % 360f).let { if (it < 0f) it + 360f else it }
    private fun clamp01(value: Float): Float = Math.max(0f, Math.min(1f, value))
    private fun dp(value: Int): Float = resources.displayMetrics.density * value
}
