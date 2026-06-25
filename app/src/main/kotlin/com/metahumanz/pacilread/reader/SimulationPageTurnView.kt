package com.metahumanz.pacilread.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Region
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class SimulationPageTurnView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val path0 = Path()
    private val path1 = Path()
    private val bezierStart1 = PointF()
    private val bezierControl1 = PointF()
    private val bezierVertex1 = PointF()
    private val bezierEnd1 = PointF()
    private val bezierStart2 = PointF()
    private val bezierControl2 = PointF()
    private val bezierVertex2 = PointF()
    private val bezierEnd2 = PointF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val foldShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val foldHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bookSpinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val desktopPagePath = Path()
    private val desktopTouchPoint = PointF()
    private val touchPoint = PointF()
    private val crossPoint1 = PointF()
    private val crossPoint2 = PointF()
    private val desktopFlipCalculation = DesktopFlipCalculation()
    private val matrix = Matrix()
    private val matrixArray = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
    private val shadowGradientColors = IntArray(4)
    private val highlightGradientColors = IntArray(3)
    private val shadowGradientPositions = floatArrayOf(0f, 0.42f, 0.62f, 1f)
    private val highlightGradientPositions = floatArrayOf(0f, 0.5f, 1f)
    private val backColorFilter = ColorMatrixColorFilter(
        ColorMatrix(floatArrayOf(
            0.9f, 0f, 0f, 0f, 12f,
            0f, 0.9f, 0f, 0f, 12f,
            0f, 0f, 0.9f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f,
        )),
    )

    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null
    private var direction = 0
    private var cornerX = 1
    private var cornerY = 1
    private var active = false
    private var turnMode = TURN_MODE_SINGLE
    private var outerPageTurn = false
    private var middleZoneTurn = false
    private var turnPageLeft = 0f
    private var turnPageWidth = 0f
    private var startX = MIN_TOUCH
    private var startY = MIN_TOUCH
    private var touchX = MIN_TOUCH
    private var touchY = MIN_TOUCH
    private var middleX = 0f
    private var middleY = 0f
    private var pageBackgroundColor = 0xFFF7F0E1.toInt()
    private var renderQuality = RENDER_QUALITY_FULL

    init {
        paint.style = Paint.Style.FILL
        foldShadowPaint.style = Paint.Style.STROKE
        foldShadowPaint.strokeCap = Paint.Cap.ROUND
        foldHighlightPaint.style = Paint.Style.STROKE
        foldHighlightPaint.strokeCap = Paint.Cap.ROUND
        bookSpinePaint.style = Paint.Style.FILL
        visibility = GONE
    }

    fun setPagingState(
        direction: Int,
        currentBitmap: Bitmap?,
        incomingBitmap: Bitmap?,
        startX: Float,
        startY: Float,
        touchX: Float,
        touchY: Float,
        turnMode: Int,
        pageBackgroundColor: Int,
    ) {
        if (direction == 0 || currentBitmap == null || incomingBitmap == null) {
            clear()
            return
        }
        val normalizedTurnMode = normalizeTurnMode(turnMode)
        val normalizedStartY = ensureTouch(startY)
        val staticStateChanged = !active || this.direction != direction ||
            frontBitmap !== currentBitmap || backBitmap !== incomingBitmap ||
            this.turnMode != normalizedTurnMode || !nearlyEqual(this.startY, normalizedStartY)
        this.direction = direction
        this.pageBackgroundColor = pageBackgroundColor
        frontBitmap = currentBitmap
        backBitmap = incomingBitmap
        this.turnMode = normalizedTurnMode
        if (staticStateChanged) {
            this.startY = normalizedStartY
            configureTurnPageBounds(this.turnMode, direction, this.startY)
            this.startX = ensureTouch(toTurnPageX(startX))
            configureCorner()
        }
        updateTouchInternal(toTurnPageX(touchX), touchY)
        active = true
        if (visibility != VISIBLE) visibility = VISIBLE
        invalidate()
    }

    fun clear() {
        val needsInvalidate = active || frontBitmap != null || backBitmap != null || visibility != GONE
        active = false
        direction = 0
        turnMode = TURN_MODE_SINGLE
        outerPageTurn = false
        middleZoneTurn = false
        turnPageLeft = 0f
        turnPageWidth = 0f
        frontBitmap = null
        backBitmap = null
        path0.reset()
        path1.reset()
        if (visibility != GONE) visibility = GONE
        if (needsInvalidate) invalidate()
    }

    fun isActive(): Boolean = active

    fun setRenderQuality(quality: Int) {
        val normalizedQuality = normalizeRenderQuality(quality)
        if (renderQuality == normalizedQuality) return
        renderQuality = normalizedQuality
        if (active) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val front = frontBitmap ?: return
        val back = backBitmap ?: return
        if (!active || width <= 0 || height <= 0) return
        if (isTurnPageCompletionFrame()) {
            drawCompletedTarget(canvas)
            return
        }
        if (outerPageTurn) {
            drawOuterPageTurn(canvas)
            return
        }
        if (direction < 0) {
            drawPreviousPageReturn(canvas)
            return
        }
        calcPoints()
        buildCurrentFoldPath()
        drawNextPageArea(canvas, back, 0f)
        drawCurrentPageArea(canvas, front, 0f)
        drawCurrentBackArea(canvas, if (direction > 0) front else back, 0f)
        drawFoldDepth(canvas)
    }

    private fun drawPreviousPageReturn(canvas: Canvas) {
        val pageWidth = activeWidth()
        if (pageWidth <= 0f) return
        val progress = previousReturnProgress(pageWidth)
        if (progress <= 0.001f) {
            canvas.drawColor(pageBackgroundColor)
            canvas.drawBitmap(frontBitmap!!, 0f, 0f, null)
            return
        }
        if (progress >= 0.999f) {
            canvas.drawColor(pageBackgroundColor)
            canvas.drawBitmap(backBitmap!!, 0f, 0f, null)
            return
        }
        val savedDirection = direction
        val savedCornerX = cornerX
        val savedCornerY = cornerY
        val savedStartX = startX
        val savedStartY = startY
        val savedTouchX = touchX
        val savedTouchY = touchY
        val savedMiddleX = middleX
        val savedMiddleY = middleY
        try {
            direction = 1
            startX = clamp(pageWidth - savedStartX, MIN_TOUCH, pageWidth - MIN_TOUCH)
            startY = savedStartY
            touchX = lerp(-pageWidth * 1.12f, startX, progress)
            touchY = savedStartY
            calcCornerXY(startX, startY)
            calcPoints()
            buildCurrentFoldPath()
            drawNextPageArea(canvas, frontBitmap!!, 0f)
            drawCurrentPageArea(canvas, backBitmap!!, 0f)
            drawCurrentBackArea(canvas, backBitmap!!, 0f)
            drawFoldDepth(canvas)
        } finally {
            direction = savedDirection
            cornerX = savedCornerX
            cornerY = savedCornerY
            startX = savedStartX
            startY = savedStartY
            touchX = savedTouchX
            touchY = savedTouchY
            middleX = savedMiddleX
            middleY = savedMiddleY
        }
    }

    private fun previousReturnProgress(pageWidth: Float): Float {
        val start = clamp(startX, MIN_TOUCH, pageWidth - MIN_TOUCH)
        val finish = pageWidth - MIN_TOUCH
        val denominator = max(1f, finish - start)
        return clamp((touchX - start) / denominator, 0f, 1f)
    }

    private fun lerp(start: Float, end: Float, progress: Float) = start + (end - start) * progress

    private fun drawOuterPageTurn(canvas: Canvas) {
        canvas.drawColor(pageBackgroundColor)
        if (isTurnPageCompletionFrame()) {
            drawCompletedTarget(canvas)
            return
        }
        drawOuterPageFixedHalf(canvas)
        drawOuterPageActiveBaseHalf(canvas)
        drawFixedBookSpine(canvas)
        val drewCurl = drawOuterPageCurl(canvas)
        if (drewCurl) drawOuterPageFoldDepth(canvas) else if (isTurnPageCompletionFrame()) drawCompletedTarget(canvas)
    }

    private fun configureCorner() {
        calcCornerXY(startX, startY)
        val pageWidth = activeWidth()
        val pageHeight = stateHeight()
        if (direction > 0 && pageWidth / 2f > startX) calcCornerXY(pageWidth - startX, startY)
    }

    private fun updateTouchInternal(touchX: Float, touchY: Float) {
        var adjustedTouchY = touchY
        val pageHeight = stateHeight()
        if (middleZoneTurn) {
            val edgeInset = if (outerPageTurn) max(resources.displayMetrics.density, pageHeight * 0.018f) else max(1f, resources.displayMetrics.density)
            adjustedTouchY = if (startY <= pageHeight / 2f) edgeInset else pageHeight - edgeInset
        }
        this.touchX = ensureTouch(touchX)
        this.touchY = ensureTouch(adjustedTouchY)
    }

    private fun ensureTouch(value: Float) = if (value == 0f) MIN_TOUCH else value
    private fun nearlyEqual(first: Float, second: Float) = abs(first - second) < 0.001f

    private fun drawCurrentBackArea(canvas: Canvas, bitmap: Bitmap, sourceLeft: Float) {
        path1.reset()
        path1.moveTo(bezierVertex2.x, bezierVertex2.y)
        path1.lineTo(bezierVertex1.x, bezierVertex1.y)
        path1.lineTo(bezierEnd1.x, bezierEnd1.y)
        path1.lineTo(touchX, touchY)
        path1.lineTo(bezierEnd2.x, bezierEnd2.y)
        path1.close()
        canvas.save()
        canvas.clipPath(path0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) canvas.clipPath(path1) else canvas.clipPath(path1, Region.Op.INTERSECT)
        paint.colorFilter = backColorFilter
        var distance = hypot((cornerX - bezierControl1.x).toDouble(), (bezierControl2.y - cornerY).toDouble()).toFloat()
        if (distance <= 0f) distance = 1f
        val f8 = (cornerX - bezierControl1.x) / distance
        val f9 = (bezierControl2.y - cornerY) / distance
        matrixArray[0] = 1 - 2 * f9 * f9
        matrixArray[1] = 2 * f8 * f9
        matrixArray[3] = matrixArray[1]
        matrixArray[4] = 1 - 2 * f8 * f8
        matrix.reset()
        matrix.setValues(matrixArray)
        matrix.preTranslate(-bezierControl1.x, -bezierControl1.y)
        matrix.postTranslate(bezierControl1.x, bezierControl1.y)
        canvas.drawColor(pageBackgroundColor)
        if (outerPageTurn) matrix.preTranslate(-sourceLeft, 0f)
        canvas.drawBitmap(bitmap, matrix, paint)
        paint.colorFilter = null
        canvas.restore()
    }

    private fun drawNextPageArea(canvas: Canvas, bitmap: Bitmap, sourceLeft: Float) {
        path1.reset()
        path1.moveTo(bezierStart1.x, bezierStart1.y)
        path1.lineTo(bezierVertex1.x, bezierVertex1.y)
        path1.lineTo(bezierVertex2.x, bezierVertex2.y)
        path1.lineTo(bezierStart2.x, bezierStart2.y)
        path1.lineTo(cornerX.toFloat(), cornerY.toFloat())
        path1.close()
        canvas.save()
        clipOuterPageRect(canvas)
        canvas.clipPath(path0)
        canvas.drawColor(pageBackgroundColor)
        canvas.drawBitmap(bitmap, bitmapDrawLeft(sourceLeft), 0f, null)
        canvas.restore()
    }

    private fun drawCurrentPageArea(canvas: Canvas, bitmap: Bitmap, sourceLeft: Float) {
        canvas.save()
        clipOuterPageRect(canvas)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) canvas.clipOutPath(path0) else canvas.clipPath(path0, Region.Op.XOR)
        canvas.drawColor(pageBackgroundColor)
        canvas.drawBitmap(bitmap, bitmapDrawLeft(sourceLeft), 0f, null)
        canvas.restore()
    }

    private fun drawFoldDepth(canvas: Canvas) {
        if (outerPageTurn || renderQuality == RENDER_QUALITY_LOW) return
        if (!isFinitePoint(bezierEnd1) || !isFinitePoint(bezierEnd2) || !isFinitePoint(touchX, touchY)) return
        val pageWidth = max(width, 1).toFloat()
        val pageHeight = max(height, 1).toFloat()
        val pull = clamp(abs(cornerX - touchX) / pageWidth, 0f, 1f)
        val pageMin = min(pageWidth, pageHeight)
        val lipShadowWidth = clamp(pageMin * 0.026f, 7f, 28f)
        val lipShadowAlpha = (42f + pull * 60f).roundToInt()
        val lipHighlightAlpha = (16f + pull * 30f).roundToInt()
        canvas.save()
        canvas.clipPath(path0)
        if (renderQuality == RENDER_QUALITY_FULL) drawCurlLipDepth(canvas, lipShadowWidth, lipShadowAlpha, lipHighlightAlpha)
        else drawSimpleCurlLipDepth(canvas, lipShadowWidth * 0.58f, (lipShadowAlpha * 0.52f).roundToInt())
        canvas.restore()
    }

    private fun drawCurlLipDepth(canvas: Canvas, shadowWidth: Float, shadowAlpha: Int, highlightAlpha: Int) {
        drawCreaseDepthGradient(canvas, bezierEnd1.x, bezierEnd1.y, touchX, touchY, shadowWidth, shadowAlpha, highlightAlpha)
        drawCreaseDepthGradient(canvas, touchX, touchY, bezierEnd2.x, bezierEnd2.y, shadowWidth, shadowAlpha, highlightAlpha)
    }

    private fun drawSimpleCurlLipDepth(canvas: Canvas, shadowWidth: Float, shadowAlpha: Int) {
        drawSimpleCreaseDepth(canvas, bezierEnd1.x, bezierEnd1.y, touchX, touchY, shadowWidth, shadowAlpha)
        drawSimpleCreaseDepth(canvas, touchX, touchY, bezierEnd2.x, bezierEnd2.y, shadowWidth, shadowAlpha)
    }

    private fun calcCornerXY(x: Float, y: Float) {
        val pageWidth = activeWidth()
        cornerX = (if (x <= pageWidth / 2f) 0f else pageWidth).roundToInt()
        val pageHeight = stateHeight()
        cornerY = if (y <= pageHeight / 2f) 0 else pageHeight.roundToInt()
    }

    private fun buildCurrentFoldPath() {
        path0.reset()
        path0.moveTo(bezierStart1.x, bezierStart1.y)
        path0.quadTo(bezierControl1.x, bezierControl1.y, bezierEnd1.x, bezierEnd1.y)
        path0.lineTo(touchX, touchY)
        path0.lineTo(bezierEnd2.x, bezierEnd2.y)
        path0.quadTo(bezierControl2.x, bezierControl2.y, bezierStart2.x, bezierStart2.y)
        path0.lineTo(cornerX.toFloat(), cornerY.toFloat())
        path0.close()
    }

    private fun calcPoints() {
        var localTouchX = touchX
        var localTouchY = touchY
        middleX = (localTouchX + cornerX) / 2f
        middleY = (localTouchY + cornerY) / 2f
        bezierControl1.x = middleX - (cornerY - middleY) * (cornerY - middleY) / safeDivisor(cornerX - middleX)
        bezierControl1.y = cornerY.toFloat()
        bezierControl2.x = cornerX.toFloat()
        bezierControl2.y = middleY - (cornerX - middleX) * (cornerX - middleX) / safeDivisor(cornerY - middleY)
        bezierStart1.x = bezierControl1.x - (cornerX - bezierControl1.x) / 2f
        bezierStart1.y = cornerY.toFloat()
        val pageWidth = activeWidth()
        if (localTouchX > 0 && localTouchX < pageWidth && (bezierStart1.x < 0 || bezierStart1.x > pageWidth)) {
            if (bezierStart1.x < 0) bezierStart1.x = pageWidth - bezierStart1.x
            var f1 = abs(cornerX - localTouchX)
            if (f1 <= 0f) f1 = 1f
            val f2 = pageWidth * f1 / safeDivisor(bezierStart1.x)
            localTouchX = abs(cornerX - f2)
            val f3 = abs(cornerX - localTouchX) * abs(cornerY - localTouchY) / f1
            localTouchY = abs(cornerY - f3)
            middleX = (localTouchX + cornerX) / 2f
            middleY = (localTouchY + cornerY) / 2f
            bezierControl1.x = middleX - (cornerY - middleY) * (cornerY - middleY) / safeDivisor(cornerX - middleX)
            bezierControl1.y = cornerY.toFloat()
            bezierControl2.x = cornerX.toFloat()
            bezierControl2.y = middleY - (cornerX - middleX) * (cornerX - middleX) / safeDivisor(cornerY - middleY)
            bezierStart1.x = bezierControl1.x - (cornerX - bezierControl1.x) / 2f
        }
        touchX = ensureTouch(localTouchX)
        touchY = ensureTouch(localTouchY)
        bezierStart2.x = cornerX.toFloat()
        bezierStart2.y = bezierControl2.y - (cornerY - bezierControl2.y) / 2f
        touchPoint.set(touchX, touchY)
        getCross(crossPoint1, touchPoint, bezierControl1, bezierStart1, bezierStart2)
        getCross(crossPoint2, touchPoint, bezierControl2, bezierStart1, bezierStart2)
        bezierEnd1.set(crossPoint1)
        bezierEnd2.set(crossPoint2)
        bezierVertex1.x = (bezierStart1.x + 2 * bezierControl1.x + bezierEnd1.x) / 4f
        bezierVertex1.y = (2 * bezierControl1.y + bezierStart1.y + bezierEnd1.y) / 4f
        bezierVertex2.x = (bezierStart2.x + 2 * bezierControl2.x + bezierEnd2.x) / 4f
        bezierVertex2.y = (2 * bezierControl2.y + bezierStart2.y + bezierEnd2.y) / 4f
    }

    private fun safeDivisor(value: Float) = if (value == 0f) 0.1f else value
    private fun clamp(value: Float, minimum: Float, maximum: Float) = max(minimum, min(maximum, value))
    private fun isFinitePoint(point: PointF?) = point != null && isFinitePoint(point.x, point.y)
    private fun isFinitePoint(x: Float, y: Float) = x.isFinite() && y.isFinite()

    private fun configureTurnPageBounds(turnMode: Int, direction: Int, gestureStartY: Float) {
        val pageWidth = stateWidth()
        outerPageTurn = turnMode == TURN_MODE_OUTER_PAGE && pageWidth > 0f
        val pageHeight = stateHeight()
        middleZoneTurn = gestureStartY > pageHeight / 3f && gestureStartY < pageHeight * 2f / 3f
        if (!outerPageTurn) {
            turnPageLeft = 0f
            turnPageWidth = pageWidth
            return
        }
        turnPageWidth = pageWidth * 0.5f
        turnPageLeft = if (direction > 0) turnPageWidth else 0f
    }

    private fun activeWidth() = if (outerPageTurn && turnPageWidth > 0f) turnPageWidth else stateWidth()

    private fun stateWidth(): Float {
        if (width > 0) return width.toFloat()
        val front = frontBitmap
        if (front != null && !front.isRecycled && front.width > 0) return front.width.toFloat()
        val back = backBitmap
        if (back != null && !back.isRecycled && back.width > 0) return back.width.toFloat()
        return 1f
    }

    private fun stateHeight(): Float {
        if (height > 0) return height.toFloat()
        val front = frontBitmap
        if (front != null && !front.isRecycled && front.height > 0) return front.height.toFloat()
        val back = backBitmap
        if (back != null && !back.isRecycled && back.height > 0) return back.height.toFloat()
        return 1f
    }

    private fun toTurnPageX(x: Float) = if (outerPageTurn) x - turnPageLeft else x
    private fun bitmapDrawLeft(sourceLeft: Float) = if (outerPageTurn) -sourceLeft else 0f

    private fun drawFixedBookSpine(canvas: Canvas) {
        if (turnMode != TURN_MODE_OUTER_PAGE || width <= 0 || height <= 0) return
        drawAdaptiveBookSpineLine(canvas)
    }

    private fun drawOuterPageFixedHalf(canvas: Canvas) {
        if (!outerPageTurn || turnPageWidth <= 0f) {
            canvas.drawBitmap(frontBitmap!!, 0f, 0f, null)
            return
        }
        canvas.save()
        if (direction > 0) canvas.clipRect(0f, 0f, turnPageWidth, height.toFloat())
        else canvas.clipRect(turnPageWidth, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(frontBitmap!!, 0f, 0f, null)
        canvas.restore()
    }

    private fun drawOuterPageBottomHalf(canvas: Canvas) {
        if (!outerPageTurn || turnPageWidth <= 0f) return
        canvas.save()
        if (direction > 0) {
            canvas.clipRect(turnPageWidth, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(backBitmap!!, -turnPageWidth, 0f, null)
        } else {
            canvas.clipRect(0f, 0f, turnPageWidth, height.toFloat())
            canvas.drawBitmap(backBitmap!!, 0f, 0f, null)
        }
        canvas.restore()
    }

    private fun drawOuterPageActiveBaseHalf(canvas: Canvas) {
        if (!outerPageTurn || turnPageWidth <= 0f) return
        canvas.save()
        if (direction > 0) canvas.clipRect(turnPageWidth, 0f, width.toFloat(), height.toFloat())
        else canvas.clipRect(0f, 0f, turnPageWidth, height.toFloat())
        canvas.drawBitmap(frontBitmap!!, 0f, 0f, null)
        canvas.restore()
    }

    private fun drawOuterPageCurl(canvas: Canvas): Boolean {
        if (!outerPageTurn || turnPageWidth <= 0f || frontBitmap == null || backBitmap == null) return false
        val desktopDirection = if (direction > 0) DesktopFlipCalculation.DIRECTION_NEXT else DesktopFlipCalculation.DIRECTION_PREV
        val topCorner = cornerY <= height / 2f
        desktopFlipCalculation.configure(desktopDirection, topCorner, turnPageWidth, height.toFloat())
        setOuterDesktopTouchPoint(desktopDirection)
        if (!desktopFlipCalculation.calc(desktopTouchPoint)) return false
        paint.isFilterBitmap = true
        paint.isDither = true
        val drewBottom = drawDesktopPage(
            canvas, backBitmap,
            if (desktopDirection == DesktopFlipCalculation.DIRECTION_NEXT) turnPageWidth else 0f,
            desktopFlipCalculation.getBottomClipArea(), desktopFlipCalculation.getBottomPagePosition(),
            0f, desktopDirection, null,
        )
        val drewFlip = drawDesktopPage(
            canvas, backBitmap,
            if (desktopDirection == DesktopFlipCalculation.DIRECTION_NEXT) 0f else turnPageWidth,
            desktopFlipCalculation.getFlippingClipArea(), desktopFlipCalculation.getActiveCorner(),
            desktopFlipCalculation.getAngle(), desktopDirection, backColorFilter,
        )
        paint.colorFilter = null
        return drewBottom || drewFlip
    }

    private fun drawOuterPageFoldDepth(canvas: Canvas) {
        if (renderQuality == RENDER_QUALITY_LOW) return
        var creaseStart = desktopFlipCalculation.getTopIntersectPoint()
        var creaseEnd = desktopFlipCalculation.getBottomIntersectPoint()
        val sideIntersect = desktopFlipCalculation.getSideIntersectPoint()
        if (creaseStart == null) creaseStart = sideIntersect
        if (creaseStart == null || creaseEnd == null) creaseEnd = sideIntersect
        if (creaseStart == null || creaseEnd == null) return
        val desktopDirection = if (direction > 0) DesktopFlipCalculation.DIRECTION_NEXT else DesktopFlipCalculation.DIRECTION_PREV
        val startX = outerDesktopToGlobalX(creaseStart.x, desktopDirection)
        val startY = creaseStart.y
        val endX = outerDesktopToGlobalX(creaseEnd.x, desktopDirection)
        val endY = creaseEnd.y
        if (!startX.isFinite() || !startY.isFinite() || !endX.isFinite() || !endY.isFinite() ||
            hypot((endX - startX).toDouble(), (endY - startY).toDouble()) < 1.0) return
        var pull = if (direction > 0) (turnPageWidth - touchX) / (turnPageWidth * 2.1f) else touchX / (turnPageWidth * 2.1f)
        pull = clamp(pull, 0f, 1f)
        val depthWidth = clamp(min(turnPageWidth, height.toFloat()) * 0.035f, 8f, 30f)
        val shadowAlpha = (34f + pull * 62f).roundToInt()
        val highlightAlpha = (16f + pull * 34f).roundToInt()
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        if (renderQuality == RENDER_QUALITY_FULL) {
            drawCreaseDepthGradient(canvas, startX, startY, endX, endY, depthWidth, shadowAlpha, highlightAlpha)
        } else {
            drawSimpleCreaseDepth(canvas, startX, startY, endX, endY, depthWidth * 0.64f, (shadowAlpha * 0.52f).roundToInt())
        }
        canvas.restore()
    }

    private fun drawCreaseDepthGradient(
        canvas: Canvas, startX: Float, startY: Float, endX: Float, endY: Float,
        shadowWidth: Float, shadowAlpha: Int, highlightAlpha: Int,
    ) {
        val dx = endX - startX
        val dy = endY - startY
        val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (!length.isFinite() || length < 1f) return
        val normalX = -dy / length
        val normalY = dx / length
        val safeShadowWidth = max(1f, shadowWidth)
        drawGradientStroke(
            canvas, foldShadowPaint, startX, startY, endX, endY, normalX, normalY,
            safeShadowWidth, updateShadowGradientColors(shadowAlpha), shadowGradientPositions,
        )
        val highlightWidth = max(1.5f, safeShadowWidth * 0.24f)
        val highlightOffset = safeShadowWidth * 0.18f
        drawGradientStroke(
            canvas, foldHighlightPaint,
            startX + normalX * highlightOffset, startY + normalY * highlightOffset,
            endX + normalX * highlightOffset, endY + normalY * highlightOffset,
            normalX, normalY, highlightWidth, updateHighlightGradientColors(highlightAlpha), highlightGradientPositions,
        )
    }

    private fun updateShadowGradientColors(shadowAlpha: Int): IntArray {
        shadowGradientColors[0] = Color.argb(0, 0, 0, 0)
        shadowGradientColors[1] = Color.argb(shadowAlpha, 0, 0, 0)
        shadowGradientColors[2] = Color.argb((shadowAlpha * 0.42f).roundToInt(), 0, 0, 0)
        shadowGradientColors[3] = Color.argb(0, 0, 0, 0)
        return shadowGradientColors
    }

    private fun updateHighlightGradientColors(highlightAlpha: Int): IntArray {
        highlightGradientColors[0] = Color.argb(0, 255, 255, 255)
        highlightGradientColors[1] = Color.argb(highlightAlpha, 255, 255, 255)
        highlightGradientColors[2] = Color.argb(0, 255, 255, 255)
        return highlightGradientColors
    }

    private fun drawSimpleCreaseDepth(
        canvas: Canvas, startX: Float, startY: Float, endX: Float, endY: Float, strokeWidth: Float, alpha: Int,
    ) {
        if (!startX.isFinite() || !startY.isFinite() || !endX.isFinite() || !endY.isFinite() ||
            hypot((endX - startX).toDouble(), (endY - startY).toDouble()) < 1.0) return
        foldShadowPaint.shader = null
        foldShadowPaint.strokeWidth = max(1f, strokeWidth)
        foldShadowPaint.color = Color.argb(max(0, min(255, alpha)), 0, 0, 0)
        foldShadowPaint.alpha = 255
        canvas.drawLine(startX, startY, endX, endY, foldShadowPaint)
    }

    private fun drawGradientStroke(
        canvas: Canvas, targetPaint: Paint,
        startX: Float, startY: Float, endX: Float, endY: Float,
        normalX: Float, normalY: Float, strokeWidth: Float,
        colors: IntArray, positions: FloatArray,
    ) {
        val halfWidth = max(0.5f, strokeWidth * 0.5f)
        val midX = (startX + endX) * 0.5f
        val midY = (startY + endY) * 0.5f
        val shader: Shader = LinearGradient(
            midX - normalX * halfWidth, midY - normalY * halfWidth,
            midX + normalX * halfWidth, midY + normalY * halfWidth,
            colors, positions, Shader.TileMode.CLAMP,
        )
        targetPaint.shader = shader
        targetPaint.strokeWidth = strokeWidth
        targetPaint.color = Color.WHITE
        targetPaint.alpha = 255
        canvas.drawLine(startX, startY, endX, endY, targetPaint)
        targetPaint.shader = null
        targetPaint.alpha = 255
    }

    private fun setOuterDesktopTouchPoint(desktopDirection: Int) {
        if (desktopDirection == DesktopFlipCalculation.DIRECTION_NEXT) desktopTouchPoint.set(touchX, touchY)
        else desktopTouchPoint.set(turnPageWidth - touchX, touchY)
    }

    private fun drawDesktopPage(
        canvas: Canvas, bitmap: Bitmap?, sourceLeft: Float, area: Array<PointF?>?, position: PointF?,
        angle: Float, desktopDirection: Int, colorFilter: ColorMatrixColorFilter?,
    ): Boolean {
        if (bitmap == null || area == null || position == null) return false
        val globalPositionX = outerDesktopToGlobalX(position.x, desktopDirection)
        val globalPositionY = position.y
        desktopPagePath.reset()
        var hasPoint = false
        val cosine = cos(angle)
        val sine = sin(angle)
        for (point in area) {
            if (point == null) continue
            val localX = if (desktopDirection == DesktopFlipCalculation.DIRECTION_PREV) -point.x + position.x else point.x - position.x
            val localY = point.y - position.y
            val clipX = localX * cosine + localY * sine
            val clipY = localY * cosine - localX * sine
            if (hasPoint) desktopPagePath.lineTo(clipX, clipY) else { desktopPagePath.moveTo(clipX, clipY); hasPoint = true }
        }
        if (!hasPoint) return false
        desktopPagePath.close()
        canvas.save()
        canvas.translate(globalPositionX, globalPositionY)
        canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
        canvas.clipPath(desktopPagePath)
        canvas.drawColor(pageBackgroundColor)
        paint.colorFilter = colorFilter
        canvas.drawBitmap(bitmap, -sourceLeft, 0f, paint)
        paint.colorFilter = null
        canvas.restore()
        return true
    }

    private fun isTurnPageCompletionFrame(): Boolean {
        val pageWidth = activeWidth()
        if (direction == 0 || pageWidth <= 0f) return false
        return if (direction > 0) touchX <= -pageWidth * 1.10f else touchX >= pageWidth * 2.10f
    }

    private fun drawCompletedTarget(canvas: Canvas) {
        canvas.drawColor(pageBackgroundColor)
        backBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (outerPageTurn) drawFixedBookSpine(canvas)
    }

    private fun outerDesktopToGlobalX(x: Float, desktopDirection: Int) =
        if (desktopDirection == DesktopFlipCalculation.DIRECTION_NEXT) x + turnPageWidth else turnPageWidth - x

    private fun clipOuterPageRect(canvas: Canvas) {
        if (!outerPageTurn || turnPageWidth <= 0f) return
        canvas.clipRect(0f, 0f, turnPageWidth, height.toFloat())
    }

    private fun drawAdaptiveBookSpineLine(canvas: Canvas) {
        val centerX = width * 0.5f
        val pageHeight = height.toFloat()
        bookSpinePaint.shader = null
        bookSpinePaint.color = spineInkColor(if (lightSpineInk()) 184 else 132)
        val spineWidth = max(1f, resources.displayMetrics.density)
        canvas.drawRect(centerX - spineWidth / 2f, 0f, centerX + spineWidth / 2f, pageHeight, bookSpinePaint)
    }

    private fun lightSpineInk() = relativeLuminance(pageBackgroundColor) < 0.45f
    private fun spineInkColor(alpha: Int) = if (lightSpineInk()) Color.argb(alpha, 255, 255, 255) else Color.argb(alpha, 0, 0, 0)

    private fun relativeLuminance(color: Int) = (
        0.2126 * linearizedChannel(Color.red(color)) +
            0.7152 * linearizedChannel(Color.green(color)) +
            0.0722 * linearizedChannel(Color.blue(color))
        ).toFloat()

    private fun linearizedChannel(value: Int): Double {
        val channel = value / 255.0
        return if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    }

    private fun normalizeTurnMode(mode: Int) = if (mode == TURN_MODE_OUTER_PAGE || mode == TURN_MODE_SPREAD) mode else TURN_MODE_SINGLE
    private fun normalizeRenderQuality(quality: Int) =
        if (quality == RENDER_QUALITY_BALANCED || quality == RENDER_QUALITY_LOW) quality else RENDER_QUALITY_FULL

    private class DesktopFlipCalculation {
        private var direction = DIRECTION_NEXT
        private var topCorner = true
        private var pageWidth = 1f
        private var pageHeight = 1f
        private val rect = PageRect()
        private var angle = 0f
        private val position = PointF()
        private val safeTouch = PointF()
        private val firstCenter = PointF()
        private val secondCenter = PointF()
        private val pageTopLeft = PointF()
        private val pageTopRight = PointF()
        private val pageBottomLeft = PointF()
        private val pageBottomRight = PointF()
        private val bottomPagePosition = PointF()
        private val flippingArea = arrayOfNulls<PointF>(5)
        private val bottomArea = arrayOfNulls<PointF>(6)
        private var topIntersectPoint: PointF? = null
        private var sideIntersectPoint: PointF? = null
        private var bottomIntersectPoint: PointF? = null

        fun configure(direction: Int, topCorner: Boolean, pageWidth: Float, pageHeight: Float) {
            this.direction = direction
            this.topCorner = topCorner
            this.pageWidth = max(1f, pageWidth)
            this.pageHeight = max(1f, pageHeight)
            pageTopLeft.set(0f, 0f)
            pageTopRight.set(this.pageWidth, 0f)
            pageBottomLeft.set(0f, this.pageHeight)
            pageBottomRight.set(this.pageWidth, this.pageHeight)
        }

        fun calc(touch: PointF): Boolean = try {
            position.set(calcAngleAndPosition(touch))
            calculateIntersectPoint(position)
            true
        } catch (_: RuntimeException) {
            false
        }

        fun getFlippingClipArea(): Array<PointF?> {
            var includeBottomLeft = false
            clearArea(flippingArea)
            var index = 0
            flippingArea[index++] = rect.topLeft
            flippingArea[index++] = topIntersectPoint
            if (sideIntersectPoint == null) {
                includeBottomLeft = true
            } else {
                flippingArea[index++] = sideIntersectPoint
                if (bottomIntersectPoint == null) includeBottomLeft = false
            }
            flippingArea[index++] = bottomIntersectPoint
            if (includeBottomLeft || !topCorner) flippingArea[index] = rect.bottomLeft
            return flippingArea
        }

        fun getBottomClipArea(): Array<PointF?> {
            clearArea(bottomArea)
            var index = 0
            bottomArea[index++] = topIntersectPoint
            if (topCorner) {
                bottomArea[index++] = pageTopRight
            } else {
                if (topIntersectPoint != null) bottomArea[index++] = pageTopRight
                bottomArea[index++] = pageBottomRight
            }
            if (sideIntersectPoint != null) {
                if (distance(sideIntersectPoint, topIntersectPoint) >= 10f) bottomArea[index++] = sideIntersectPoint
            } else if (topCorner) {
                bottomArea[index++] = pageBottomRight
            }
            bottomArea[index++] = bottomIntersectPoint
            bottomArea[index] = topIntersectPoint
            return bottomArea
        }

        fun getAngle() = if (direction == DIRECTION_NEXT) -angle else angle
        fun getActiveCorner() = if (direction == DIRECTION_NEXT) rect.topLeft else rect.topRight
        fun getBottomPagePosition(): PointF {
            bottomPagePosition.set(if (direction == DIRECTION_PREV) pageWidth else 0f, 0f)
            return bottomPagePosition
        }
        fun getTopIntersectPoint() = topIntersectPoint
        fun getSideIntersectPoint() = sideIntersectPoint
        fun getBottomIntersectPoint() = bottomIntersectPoint

        private fun calcAngleAndPosition(touch: PointF): PointF {
            safeTouch.set(touch)
            updateAngleAndGeometry(safeTouch)
            if (topCorner) {
                firstCenter.set(0f, 0f)
                secondCenter.set(0f, pageHeight)
            } else {
                firstCenter.set(0f, pageHeight)
                secondCenter.set(0f, 0f)
            }
            val result = checkPositionAtCenterLine(safeTouch, firstCenter, secondCenter)
            safeTouch.set(result)
            if (abs(safeTouch.x - pageWidth) < 1f && abs(safeTouch.y) < 1f) throw IllegalStateException("Point is too small")
            return safeTouch
        }

        private fun updateAngleAndGeometry(point: PointF) {
            angle = calculateAngle(point)
            updatePageRect(point)
        }

        private fun calculateAngle(point: PointF): Float {
            val x = pageWidth - point.x + 1f
            val y = if (topCorner) point.y else pageHeight - point.y
            val radius = hypot(y.toDouble(), x.toDouble()).toFloat()
            if (radius <= 0f) throw IllegalStateException("Invalid point")
            val value = max(-1f, min(1f, x / radius))
            var result = 2f * acos(value)
            if (y < 0f) result = -result
            val foldedAngle = Math.PI.toFloat() - result
            if (!result.isFinite() || foldedAngle >= 0f && foldedAngle < 0.003f) throw IllegalStateException("The G point is too small")
            return if (topCorner) result else -result
        }

        private fun updatePageRect(point: PointF) {
            if (topCorner) {
                setRotatedPoint(rect.topLeft, 0f, 0f, point)
                setRotatedPoint(rect.topRight, pageWidth, 0f, point)
                setRotatedPoint(rect.bottomLeft, 0f, pageHeight, point)
                setRotatedPoint(rect.bottomRight, pageWidth, pageHeight, point)
            } else {
                setRotatedPoint(rect.topLeft, 0f, -pageHeight, point)
                setRotatedPoint(rect.topRight, pageWidth, -pageHeight, point)
                setRotatedPoint(rect.bottomLeft, 0f, 0f, point)
                setRotatedPoint(rect.bottomRight, pageWidth, 0f, point)
            }
        }

        private fun setRotatedPoint(target: PointF, x: Float, y: Float, origin: PointF) {
            val cosine = cos(angle)
            val sine = sin(angle)
            target.set(x * cosine + y * sine + origin.x, y * cosine - x * sine + origin.y)
        }

        private fun calculateIntersectPoint(point: PointF) {
            if (topCorner) {
                topIntersectPoint = getIntersectBetweenTwoSegment(point, rect.topRight, pageTopLeft, pageTopRight)
                sideIntersectPoint = getIntersectBetweenTwoSegment(point, rect.bottomLeft, pageTopRight, pageBottomRight)
                bottomIntersectPoint = getIntersectBetweenTwoSegment(rect.bottomLeft, rect.bottomRight, pageBottomLeft, pageBottomRight)
            } else {
                topIntersectPoint = getIntersectBetweenTwoSegment(rect.topLeft, rect.topRight, pageTopLeft, pageTopRight)
                sideIntersectPoint = getIntersectBetweenTwoSegment(point, rect.topLeft, pageTopRight, pageBottomRight)
                bottomIntersectPoint = getIntersectBetweenTwoSegment(rect.bottomLeft, rect.bottomRight, pageBottomLeft, pageBottomRight)
            }
        }

        private fun checkPositionAtCenterLine(point: PointF, firstCenter: PointF, secondCenter: PointF): PointF {
            var safePoint = point
            var limited = limitPointToCircle(firstCenter, pageWidth, safePoint)
            if (!samePoint(limited, safePoint)) {
                safePoint = limited
                updateAngleAndGeometry(safePoint)
            }
            val diagonal = hypot(pageWidth.toDouble(), pageHeight.toDouble()).toFloat()
            val outerCorner = if (topCorner) rect.bottomRight else rect.topRight
            val innerCorner = if (topCorner) rect.topLeft else rect.bottomLeft
            if (outerCorner.x <= 0f) {
                limited = limitPointToCircle(secondCenter, diagonal, innerCorner)
                if (!samePoint(limited, safePoint)) {
                    safePoint = limited
                    updateAngleAndGeometry(safePoint)
                }
            }
            return safePoint
        }

        private fun getIntersectBetweenTwoSegment(line1Start: PointF, line1End: PointF, line2Start: PointF, line2End: PointF): PointF? {
            val intersect = getIntersectBetweenTwoLine(line1Start, line1End, line2Start, line2End) ?: return null
            return if (pointInCalculationRect(intersect)) intersect else null
        }

        private fun getIntersectBetweenTwoLine(p1: PointF, p2: PointF, p3: PointF, p4: PointF): PointF? {
            val a1 = p1.y - p2.y
            val a2 = p3.y - p4.y
            val b1 = p2.x - p1.x
            val b2 = p4.x - p3.x
            val c1 = p1.x * p2.y - p2.x * p1.y
            val c2 = p3.x * p4.y - p4.x * p3.y
            val denominator = a1 * b2 - a2 * b1
            if (abs(denominator) < 0.0001f) return null
            return PointF(-(c1 * b2 - c2 * b1) / denominator, -(a1 * c2 - a2 * c1) / denominator)
        }

        private fun pointInCalculationRect(point: PointF) = point.x >= -1f && point.x <= pageWidth + 1f && point.y >= -1f && point.y <= pageHeight + 1f

        private fun limitPointToCircle(center: PointF, radius: Float, point: PointF): PointF {
            val dx = point.x - center.x
            val dy = point.y - center.y
            val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (distance <= radius || distance <= 0.0001f) return point
            val scale = radius / distance
            return PointF(center.x + dx * scale, center.y + dy * scale)
        }

        private fun samePoint(a: PointF, b: PointF) = abs(a.x - b.x) < 0.001f && abs(a.y - b.y) < 0.001f
        private fun distance(a: PointF?, b: PointF?): Float {
            if (a == null || b == null) return Float.POSITIVE_INFINITY
            return hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
        }
        private fun clearArea(area: Array<PointF?>) {
            var index = 0
            while (index < area.size) area[index++] = null
        }

        companion object {
            const val DIRECTION_NEXT = 0
            const val DIRECTION_PREV = 1
        }
    }

    private class PageRect {
        val topLeft = PointF()
        val topRight = PointF()
        val bottomLeft = PointF()
        val bottomRight = PointF()
    }

    private fun getCross(out: PointF, p1: PointF, p2: PointF, p3: PointF, p4: PointF) {
        val a1 = (p2.y - p1.y) / safeDivisor(p2.x - p1.x)
        val b1 = (p1.x * p2.y - p2.x * p1.y) / safeDivisor(p1.x - p2.x)
        val a2 = (p4.y - p3.y) / safeDivisor(p4.x - p3.x)
        val b2 = (p3.x * p4.y - p4.x * p3.y) / safeDivisor(p3.x - p4.x)
        val x = (b2 - b1) / safeDivisor(a1 - a2)
        val y = a1 * x + b1
        out.set(x, y)
    }

    companion object {
        const val TURN_MODE_SINGLE = 0
        const val TURN_MODE_OUTER_PAGE = 1
        const val TURN_MODE_SPREAD = 2
        const val RENDER_QUALITY_FULL = 0
        const val RENDER_QUALITY_BALANCED = 1
        const val RENDER_QUALITY_LOW = 2
        private const val MIN_TOUCH = 0.1f
    }
}
